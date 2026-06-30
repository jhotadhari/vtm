/*
 * Copyright 2024-2025 jhotadhari
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.terrain.tiling;

import org.mapsforge.map.elevation.ElevationAPI;
import org.oscim.backend.CanvasAdapter;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.backend.canvas.Canvas;
import org.oscim.core.GeometryBuffer;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Point;
import org.oscim.core.Tile;
import org.oscim.layers.tile.MapTile;
import org.oscim.terrain.TerrainUtils;
import org.oscim.terrain.projection.TerrainProjection;
import org.oscim.tiling.ITileDataSink;
import org.oscim.tiling.ITileDataSource;
import org.oscim.tiling.QueryResult;
import org.oscim.tiling.TileSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Generates terrain triangle meshes per tile from HGT elevation data
 * using the configured {@link TerrainProjection}.
 * <p>
 * For each tile request, this data source:
 * <ol>
 * <li>Computes the tile's geographic bounding box</li>
 * <li>Generates an N×N grid of sample points</li>
 * <li>Queries elevation from Mapsforge's {@link ElevationAPI}</li>
 * <li>Builds a {@link GeometryBuffer} with terrain mesh triangles</li>
 * <li>Passes the mesh to the sink for later rendering via
 *     {@link org.oscim.renderer.bucket.ExtrusionBucket#addMesh}</li>
 * </ol>
 */
public class TerrainTileDataSource implements ITileDataSource {

    private static final Logger log = Logger.getLogger(TerrainTileDataSource.class.getName());

    private final TerrainTileSource mTileSource;
    private final TerrainProjection mProjection;
    private final ElevationAPI mElevationAPI;

    /** Single-thread executor for async raster tile fetches. Created lazily. */
    private ExecutorService mRasterExecutor;

    /** Single-thread executor for async vector drape texture generation. */
    private ExecutorService mVectorDrapeExecutor;

    /** Set by cancel() to stop in-flight async tasks from storing results. */
    private volatile boolean mCancelled;

    /**
     * LRU cache of coarse parent bitmaps used when rasterMaxZoom is active.
     * Multiple terrain tiles share the same zoom-N parent; caching it avoids
     * redundant DB fetches. Accessed only from the single raster executor thread.
     * Evicted entries are recycled immediately to free heap.
     */
    private final LinkedHashMap<Long, Bitmap> mParentBitmapCache =
            new LinkedHashMap<Long, Bitmap>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Bitmap> eldest) {
                    if (size() > 16) {
                        Bitmap b = eldest.getValue();
                        if (b != null) b.recycle();
                        return true;
                    }
                    return false;
                }
            };

    public TerrainTileDataSource(TerrainTileSource tileSource) {
        mTileSource = tileSource;
        mProjection = tileSource.getProjection();
        mElevationAPI = tileSource.getElevationAPI(); // shared instance
    }

    @Override
    public void query(MapTile tile, ITileDataSink sink) {
        byte zoomLevel = tile.zoomLevel;

        // Out of zoom bounds
        if (zoomLevel > mTileSource.getZoomLevelMax()
                || zoomLevel < mTileSource.getZoomLevelMin()) {
            log.fine("TERRAIN: zoom out of bounds " + tile);
            sink.completed(QueryResult.SUCCESS);
            return;
        }

        try {
            mCancelled = false; // fresh query, clear cancellation
            if (tile.mapSize <= 0) {
                log.warning("TERRAIN: mapSize=0 for " + tile);
                sink.completed(QueryResult.FAILED);
                return;
            }

            // Compute tile geographic bounds
            Point origin = tile.getOrigin();
            long mapSize = tile.mapSize;
            double scale = 1L << tile.zoomLevel;

            double leftLon = MercatorProjection.pixelXToLongitude(origin.x, mapSize);
            double rightLon = MercatorProjection.pixelXToLongitude(origin.x + Tile.SIZE, mapSize);
            if (rightLon < leftLon)
                rightLon += 360.0;

            double topLat = MercatorProjection.pixelYToLatitude(origin.y, mapSize);
            double bottomLat = MercatorProjection.pixelYToLatitude(origin.y + Tile.SIZE, mapSize);

            // Check if tile is entirely ocean
            // Shared elevation sampler with base offset
            final float baseElev = mTileSource.getBaseElevation();
            TerrainUtils.ElevationSampler sampler = (lat, lon) -> {
                double e = mElevationAPI.getElevation(lat, lon);
                if (!ElevationAPI.isValid(e)) return Short.MIN_VALUE;
                return (float) e - baseElev;
            };

            // Check if tile has any elevation data
            boolean isOcean = TerrainUtils.isOceanTile(sampler, bottomLat, topLat, leftLon, rightLon);

            // When a raster source is configured, generate a flat sea-level
            // mesh for ocean tiles so the raster imagery has a surface to
            // drape on. Without this, only areas with HGT data show terrain.
            if (isOcean && mTileSource.getRasterSource() == null) {
                log.finer("TERRAIN: ocean tile " + tile);
                sink.completed(QueryResult.SUCCESS);
                return;
            }

            if (isOcean) {
                log.fine("TERRAIN: flat ocean tile for raster drape " + tile);
            } else {
                log.fine("TERRAIN: generating mesh for " + tile);
            }

            // For ocean tiles, use a no-data sampler to produce a flat
            // mesh at sea level (z=0). For globe projection, the sphere
            // curvature still applies — vertices land on the sphere surface.
            TerrainUtils.ElevationSampler activeSampler = isOcean
                    ? (lat, lon) -> Short.MIN_VALUE
                    : sampler;

            // Generate terrain mesh
            GeometryBuffer mesh = TerrainUtils.generateTerrainMesh(
                    mProjection,
                    mapSize,
                    origin.x,
                    origin.y,
                    scale,
                    bottomLat, topLat,
                    leftLon, rightLon,
                    activeSampler,
                    mTileSource.getElevationExaggeration());

            // Pass the mesh data to the loader via package-level field
            // (the loader is the sink, in the same package)
            if (sink instanceof TerrainTileLoader) {
                TerrainTileLoader loader = (TerrainTileLoader) sink;
                loader.mMesh = mesh;

                sink.completed(QueryResult.SUCCESS);
            } else {
                sink.completed(QueryResult.FAILED);
            }
        } catch (Throwable t) {
            log.severe("TERRAIN: mesh gen failed for " + tile + ": " + t);
            t.printStackTrace();
            sink.completed(QueryResult.FAILED);
        }
    }

    /**
     * Kicks off an asynchronous raster tile fetch for the given tile.
     * <p>
     * The raster query runs on a background thread so it does not block
     * the terrain loader thread. When the bitmap arrives, it is stored in
     * {@link org.oscim.terrain.layer.TerrainTileLayer}'s pending texture map
     * for consumption by the GL render thread on the next frame.
     *
     * @param tile         the terrain tile
     * @param rasterSource the raster tile source (e.g. OSM bitmap tiles)
     */
    public void fetchRasterAsync(MapTile tile, TileSource rasterSource) {
        // Check zoom bounds against the terrain tile's own zoom level
        if (tile.zoomLevel > rasterSource.getZoomLevelMax()
                || tile.zoomLevel < rasterSource.getZoomLevelMin()) {
            return;
        }

        // Determine whether to fetch a coarser parent tile and crop the sub-region.
        // This is used in globe mode so continental-scale imagery is shown instead
        // of city-level detail (e.g., rasterMaxZoom=3 means fetch zoom-3 tile and
        // crop the 1/8 sub-region that covers this terrain tile's area).
        final int rasterMaxZoom = mTileSource.getRasterMaxZoom();
        final boolean useCrop = rasterMaxZoom >= 0
                && tile.zoomLevel > rasterMaxZoom
                && rasterMaxZoom >= rasterSource.getZoomLevelMin();

        final int fetchTileX, fetchTileY, fetchZoom;
        final int localX, localY, subW;
        if (useCrop) {
            int zoomDiff = tile.zoomLevel - rasterMaxZoom;
            int subStep  = 1 << zoomDiff;
            fetchZoom  = rasterMaxZoom;
            fetchTileX = tile.tileX >> zoomDiff;
            fetchTileY = tile.tileY >> zoomDiff;
            localX     = tile.tileX & (subStep - 1);
            localY     = tile.tileY & (subStep - 1);
            subW       = 256 / subStep;
        } else {
            fetchZoom  = tile.zoomLevel;
            fetchTileX = tile.tileX;
            fetchTileY = tile.tileY;
            localX = 0; localY = 0; subW = 256;
        }

        // Lazy-init single-thread executor (daemon threads)
        if (mRasterExecutor == null) {
            synchronized (this) {
                if (mRasterExecutor == null) {
                    mRasterExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "terrain-raster");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }

        final TileSource rs = rasterSource;
        mRasterExecutor.execute(() -> {
            ITileDataSource rasterDs = null;
            try {
                rasterDs = rs.getDataSource();

                // Capture the bitmap from the raster source via a temporary sink
                final Bitmap[] captured = {null};
                ITileDataSink rasterSink = new ITileDataSink() {
                    @Override
                    public void process(org.oscim.core.MapElement element) {
                    }

                    @Override
                    public void setTileImage(Bitmap bitmap) {
                        captured[0] = bitmap;
                    }

                    @Override
                    public void completed(QueryResult result) {
                    }
                };

                Bitmap result;
                if (useCrop) {
                    // Many terrain tiles share the same coarse parent; reuse the cached
                    // bitmap rather than fetching the same DB row repeatedly.
                    long cacheKey = ((long) fetchZoom << 40)
                            | ((long) fetchTileX << 20) | fetchTileY;
                    Bitmap parentBitmap = mParentBitmapCache.get(cacheKey);
                    if (parentBitmap == null || !parentBitmap.isValid()) {
                        MapTile fetchTile = new MapTile(fetchTileX, fetchTileY, fetchZoom);
                        rasterDs.query(fetchTile, rasterSink);
                        parentBitmap = captured[0];
                        if (parentBitmap != null && parentBitmap.isValid()) {
                            mParentBitmapCache.put(cacheKey, parentBitmap);
                        }
                    }
                    if (mCancelled) return;
                    // Crop the sub-region for this terrain tile out of the parent bitmap.
                    // Draw parent at negative offset into a subW×subW canvas so the canvas
                    // shows exactly the right sub-region. Keep the crop at native size;
                    // OpenGL bilinear filtering handles magnification on-GPU.
                    if (parentBitmap != null && parentBitmap.isValid()) {
                        int srcX = localX * subW;
                        int srcY = localY * subW;
                        Bitmap crop = CanvasAdapter.newBitmap(subW, subW, 0);
                        Canvas cropCanvas = CanvasAdapter.newCanvas();
                        cropCanvas.setBitmap(crop);
                        cropCanvas.drawBitmap(parentBitmap, -srcX, -srcY);
                        result = crop;
                    } else {
                        result = null;
                    }
                } else {
                    rasterDs.query(tile, rasterSink);
                    if (mCancelled) return;
                    result = captured[0];
                }

                // Store the captured bitmap in the pending texture map.
                // The GL render thread picks it up on the next frame (update()).
                if (result != null && result.isValid()) {
                    org.oscim.terrain.layer.TerrainTileLayer.addPendingTexture(
                            tile, result);
                    log.fine("TERRAIN: async raster fetched for " + tile);
                }
            } catch (Throwable t) {
                log.fine("TERRAIN: async raster fetch failed for "
                        + tile + ": " + t);
            } finally {
                if (rasterDs != null) {
                    rasterDs.dispose();
                }
            }
        });
    }

    /**
     * Kicks off an asynchronous vector drape texture generation for the given tile.
     * Queries the vector tile source for area features and rasterizes them to a
     * bitmap, which is stored in TerrainTileLayer's pending texture map.
     */
    public void fetchVectorDrapeAsync(MapTile tile, TileSource vectorSource) {
        if (vectorSource == null) return;
        if (tile.zoomLevel > vectorSource.getZoomLevelMax()
                || tile.zoomLevel < vectorSource.getZoomLevelMin()) {
            return;
        }
        if (mVectorDrapeExecutor == null) {
            synchronized (this) {
                if (mVectorDrapeExecutor == null) {
                    mVectorDrapeExecutor = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "terrain-vector-drape");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
        }
        final TileSource vs = vectorSource;
        mVectorDrapeExecutor.execute(() -> {
            try {
                Bitmap bitmap =
                        org.oscim.terrain.layer.VectorDrapeRenderer.generateDrapeBitmap(tile, vs);
                if (bitmap != null && bitmap.isValid()) {
                    if (mCancelled) return;
                    org.oscim.terrain.layer.TerrainTileLayer.addPendingVectorTexture(
                            tile, bitmap);
                    log.fine("TERRAIN: vector drape generated for " + tile);
                }
            } catch (Throwable t) {
                log.fine("TERRAIN: vector drape failed for " + tile + ": " + t);
            }
        });
    }

    @Override
    public void dispose() {
        if (mRasterExecutor != null) {
            mRasterExecutor.shutdownNow();
            mRasterExecutor = null;
        }
        if (mVectorDrapeExecutor != null) {
            mVectorDrapeExecutor.shutdownNow();
            mVectorDrapeExecutor = null;
        }
        for (Bitmap b : mParentBitmapCache.values()) {
            if (b != null) b.recycle();
        }
        mParentBitmapCache.clear();
    }

    @Override
    public void cancel() {
        mCancelled = true;
    }
}

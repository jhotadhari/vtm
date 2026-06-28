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
import org.oscim.backend.canvas.Bitmap;
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

    public TerrainTileDataSource(TerrainTileSource tileSource) {
        mTileSource = tileSource;
        mProjection = tileSource.getProjection();
        mElevationAPI = new ElevationAPI(tileSource.getDemFolder());
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
                rightLon += mapSize;

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

            // Check if tile is entirely ocean
            if (TerrainUtils.isOceanTile(sampler, bottomLat, topLat, leftLon, rightLon)) {
                log.finer("TERRAIN: ocean tile " + tile);
                sink.completed(QueryResult.SUCCESS);
                return;
            }

            log.fine("TERRAIN: generating mesh for " + tile);

            // Generate terrain mesh
            GeometryBuffer mesh = TerrainUtils.generateTerrainMesh(
                    mProjection,
                    mapSize,
                    origin.x,
                    origin.y,
                    scale,
                    bottomLat, topLat,
                    leftLon, rightLon,
                    sampler,
                    mTileSource.getElevationExaggeration());

            // Pass the mesh data to the loader via package-level field
            // (the loader is the sink, in the same package)
            if (sink instanceof TerrainTileLoader) {
                TerrainTileLoader loader = (TerrainTileLoader) sink;
                loader.mMesh = mesh;

                // Fetch raster tile bitmap for texture draping (if configured)
                TileSource rasterSource = mTileSource.getRasterSource();
                if (rasterSource != null) {
                    fetchRasterTile(tile, loader, rasterSource);
                }

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
     * Fetches a raster tile bitmap from the given raster source for the
     * specified tile, and passes the bitmap to the terrain loader via
     * {@link ITileDataSink#setTileImage}.
     * <p>
     * The raster query runs synchronously on the current thread (the terrain
     * loader thread), using a temporary data source and sink.
     */
    private void fetchRasterTile(MapTile tile, TerrainTileLoader loader,
                                  TileSource rasterSource) {
        // Check zoom bounds
        if (tile.zoomLevel > rasterSource.getZoomLevelMax()
                || tile.zoomLevel < rasterSource.getZoomLevelMin()) {
            return;
        }

        ITileDataSource rasterDs = null;
        try {
            rasterDs = rasterSource.getDataSource();

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

            rasterDs.query(tile, rasterSink);

            // Pass the captured bitmap to the terrain loader
            if (captured[0] != null) {
                loader.setTileImage(captured[0]);
                log.fine("TERRAIN: raster tile fetched for " + tile);
            }
        } catch (Throwable t) {
            log.fine("TERRAIN: raster fetch failed for " + tile + ": " + t);
        } finally {
            if (rasterDs != null) {
                rasterDs.dispose();
            }
        }
    }

    @Override
    public void dispose() {
    }

    @Override
    public void cancel() {
    }
}

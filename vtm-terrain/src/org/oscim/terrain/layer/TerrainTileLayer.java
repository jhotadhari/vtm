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
package org.oscim.terrain.layer;

import org.mapsforge.map.elevation.ElevationAPI;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.core.MapPosition;
import org.oscim.core.MercatorProjection;
import org.oscim.event.Event;
import org.oscim.layers.tile.MapTile;
import org.oscim.layers.tile.MapTile.TileData;
import org.oscim.layers.tile.TileLayer;
import org.oscim.layers.tile.TileLoader;
import org.oscim.layers.tile.TileManager;
import org.oscim.map.Map;
import org.oscim.renderer.bucket.ExtrusionBuckets;
import org.oscim.renderer.bucket.TextureItem;
import org.oscim.terrain.ElevationProvider;
import org.oscim.terrain.projection.TerrainProjection;
import org.oscim.terrain.tiling.TerrainTileLoader;
import org.oscim.terrain.tiling.TerrainTileSource;
import org.oscim.tiling.ITileDataSource;
import org.oscim.utils.ExtrusionUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Tile layer for 3D terrain mesh rendering. Manages the tile loading pipeline
 * for terrain meshes generated from HGT elevation data.
 * <p>
 * The terrain mesh is rendered as a 3D surface via {@link TerrainTileRenderer},
 * which uses the {@code extrusion_layer_mesh.glsl} shader for lighting and
 * depth-buffered rendering.
 * <p>
 * Usage:
 * <pre>{@code
 * TerrainTileSource source = new TerrainTileSource(
 *     Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL,
 *     new DemFolderFS(new File("/path/to/hgt")));
 * TerrainTileLayer terrainLayer = new TerrainTileLayer(map, source);
 * map.layers().add(2, terrainLayer);
 * }</pre>
 */
public class TerrainTileLayer extends TileLayer {

    private static final Logger log = Logger.getLogger(TerrainTileLayer.class.getName());

    /** Flat-map cache limit: a typical view needs ~9 tiles, 30 gives ample headroom. */
    private static final int CACHE_LIMIT_FLAT = 30;

    /**
     * Globe cache limit.
     * Raster bitmaps are now 32×32 crops (~4 KB) rather than 256×256 scaled
     * bitmaps (~256 KB), so heap per tile is ~20 KB steady-state.
     * 1500 tiles × 20 KB = 30 MB, comfortably inside the 768 MB heap.
     * The larger cap is needed at dynamic tile zoom 7–8 where the Mercator
     * bounding box (rectangle) can hold ~1200 tiles even though only ~400
     * of them actually lie inside the visible spherical disc.
     */
    private static final int CACHE_LIMIT_GLOBE = 1500;

    /**
     * Maximum tiles ScanBox may request per frame in globe mode.
     * Matches CACHE_LIMIT_GLOBE so the ScanBox is never cut off before
     * the cache can absorb the result.
     */
    private static final int MAX_NEW_TILES_GLOBE = 1500;

    /** Key for storing ExtrusionBuckets in MapTile's TileData chain. */
    private static final Object TERRAIN_DATA = TerrainTileLayer.class.getName();

    /** Key for storing terrain TextureItem in MapTile's TileData chain. */
    private static final Object TERRAIN_TEX = "terrain_texture";

    /** Key for storing vector drape TextureItem in MapTile's TileData chain. */
    private static final Object TERRAIN_VECTOR_TEX = "terrain_vector_texture";

    /**
     * Thread-safe map of pending raster bitmaps awaiting texture upload.
     * Written by the async raster fetch thread, consumed by the GL render
     * thread in {@link TerrainTileRenderer#update}.
     */
    private static final ConcurrentHashMap<MapTile, Bitmap> sPendingTextures =
            new ConcurrentHashMap<>();

    /** Pending vector drape bitmaps awaiting texture upload (same pattern as sPendingTextures). */
    private static final ConcurrentHashMap<MapTile, Bitmap> sPendingVectorTextures =
            new ConcurrentHashMap<>();

    /**
     * Shared elevation query context. Set when the first TerrainTileLayer
     * is created, and used by building/label/road layers to sample terrain
     * height at arbitrary positions.
     */
    private static volatile ElevationAPI sElevationAPI;
    private static volatile TerrainProjection sProjection;
    private static volatile float sBaseElevation;

    /**
     * Minimal TileData wrapper for storing a TextureItem on a MapTile.
     */
    private static class TerrainTexData extends TileData {
        TextureItem texture;

        TerrainTexData(TextureItem texture) {
            this.texture = texture;
        }

        @Override
        protected void dispose() {
            if (texture != null) {
                texture.dispose();
                texture = null;
            }
        }
    }

    private final TerrainTileSource mTerrainSource;

    /** True when the terrain projection is GLOBE; enables dynamic tile zoom. */
    private final boolean mIsGlobe;

    public TerrainTileLayer(Map map, TerrainTileSource tileSource) {
        this(map, tileSource,
                tileSource.getProjection().getType() == TerrainProjection.Type.GLOBE
                        ? CACHE_LIMIT_GLOBE : CACHE_LIMIT_FLAT);
    }

    public TerrainTileLayer(Map map, TerrainTileSource tileSource, int cacheLimit) {
        super(map,
                new TileManager(map, cacheLimit),
                new TerrainTileRenderer());

        mTerrainSource = tileSource;
        mIsGlobe = tileSource.getProjection().getType() == TerrainProjection.Type.GLOBE;

        if (mIsGlobe) {
            // Globe mode needs far more tiles per frame than the flat-map default (~100).
            mTileManager.setMaxNewTiles(MAX_NEW_TILES_GLOBE);
            // Initial tile zoom; updated dynamically on every POSITION_EVENT
            // by onMapEvent() → computeGlobeTileZoom(). Start at zoom 5 which
            // is safe for the global overview the user typically enters at.
            mTileManager.setZoomLevel(5, 5);
        } else {
            mTileManager.setZoomLevel(tileSource.getZoomLevelMin(),
                    tileSource.getZoomLevelMax());
        }

        initLoader(getNumLoaders());

        // Register shared elevation query context for use by other layers
        setElevationContext(tileSource);

        // Sync the terrain renderer's projection for correct shader/matrix selection
        TerrainTileRenderer renderer = (TerrainTileRenderer) tileRenderer();
        renderer.setProjection(tileSource.getProjection());

        log.info("TERRAIN: layer created, zoom range "
                + tileSource.getZoomLevelMin() + "-" + tileSource.getZoomLevelMax());
    }

    /**
     * Computes the appropriate terrain tile zoom for globe mode from the current
     * map scale. As scale increases (camera approaches the sphere), the visible
     * surface cap shrinks and we load more-detailed tiles without exceeding
     * MAX_NEW_TILES_GLOBE.
     *
     * The logScale parameter t ∈ [0,1]:
     *   t=0 → camera at ~4R (global overview, cap ~75°)
     *   t=1 → camera at ~1.02R (just above surface, cap ~11.5°)
     *
     * The ScanBox fills a Mercator rectangle around the visible disc, so
     * roughly 2× more tiles are requested than actually land inside the
     * circular disc. The cap at 1500 absorbs that waste.
     *
     * The globe horizon (sphere edge) fills the 45° vertical FOV when D=2.61R
     * (t≈0.313). Below that distance the user is perceptually "inside" the
     * globe regardless of tile zoom. Zoom=5 tiles (coarser, more globe-like)
     * are kept active across that fill-FOV zone so the tile transition to
     * zoom=6 only fires when the camera is already well inside the globe and
     * the higher-resolution texture is visually appropriate.
     *
     * Tile counts at each transition point (verified ≤ 1500):
     *   t=0.70 (D≈1.56R, cap≈50°): zoom 6 → ~620 tiles
     *   t=0.92 (D≈1.14R, cap≈29°): zoom 7 → ~350 tiles
     *   t=0.996 (D≈1.026R, cap≈13°): zoom 8 → ~190 tiles
     */
    private static int computeGlobeTileZoom(double scale) {
        final double minScale = 1 << 2;   // Viewport.MIN_ZOOM_LEVEL = 2
        final double maxScale = 1 << 20;  // Viewport.MAX_ZOOM_LEVEL = 20
        if (scale <= minScale) return 5;
        if (scale >= maxScale) return 8;
        double t = Math.log(scale / minScale) / Math.log(maxScale / minScale);
        if (t < 0.70) return 5;   // D > ~1.56R: global overview; covers fill-FOV zone
        if (t < 0.92) return 6;   // D in [1.14R, 1.56R]: close-in view
        if (t < 0.996) return 7;  // D in [1.026R, 1.14R]: near surface
        return 8;                  // D ≤ ~1.026R: skimming the surface
    }

    @Override
    public void onMapEvent(Event event, MapPosition pos) {
        if (mIsGlobe && event == Map.POSITION_EVENT && isEnabled()) {
            // Recompute the tile zoom level from the current scale before
            // TileManager.update() uses it. Without this, mPos.zoomLevel
            // (= log2(scale)) is used directly, which at globe scale=256
            // (zoom 8) would request zoom-8 tiles for the full-globe view —
            // producing ~15,000 tiles instead of the safe ~200.
            int gz = computeGlobeTileZoom(pos.scale);
            gz = Math.max(mTerrainSource.getZoomLevelMin(),
                    Math.min(mTerrainSource.getZoomLevelMax(), gz));
            mTileManager.setZoomLevel(gz, gz);
        }
        super.onMapEvent(event, pos);
    }

    @Override
    protected TileLoader createLoader() {
        return new TerrainTileLoader(this, mTerrainSource);
    }

    /**
     * Terrain renders after the base map (0x100) and before buildings (0x300)
     * so that extrusion layers can depth-test against the terrain mesh.
     */
    @Override
    public int getRenderPriority() {
        return RENDER_PRIORITY_TERRAIN;
    }

    /**
     * Returns the terrain tile source.
     */
    public TerrainTileSource getTerrainSource() {
        return mTerrainSource;
    }

    /**
     * Sets whether the terrain renderer should clear the depth buffer before
     * rendering. Default is {@code true}. Set to {@code false} when a prior
     * layer already owns the depth buffer.
     *
     * @see TerrainTileRenderer#setClearDepth(boolean)
     */
    public void setClearDepth(boolean clearDepth) {
        TerrainTileRenderer renderer = (TerrainTileRenderer) tileRenderer();
        renderer.setClearDepth(clearDepth);
    }

    /**
     * Returns whether the terrain renderer clears the depth buffer before
     * rendering.
     */
    public boolean getClearDepth() {
        return ((TerrainTileRenderer) tileRenderer()).getClearDepth();
    }

    /**
     * Disposes the terrain layer, shutting down executor services
     * created by the underlying {@link TerrainTileDataSource}.
     * Call when removing the terrain layer from the map.
     */
    public void dispose() {
        if (mTerrainSource != null) {
            ITileDataSource ds = mTerrainSource.getDataSource();
            if (ds != null) {
                ds.dispose();
            }
        }
        // Clear pending texture maps to prevent leaks from in-flight
        // async lambdas that may complete after executor shutdown.
        sPendingTextures.clear();
        sPendingVectorTextures.clear();
    }

    /**
     * Removes pending texture entries for tiles that are no longer in the
     * active set. Called from the GL render thread each frame to prevent
     * orphaned {@link Bitmap} entries from accumulating when tiles are
     * evicted before their async fetch completes.
     *
     * @param activeTiles the currently visible tiles
     * @param count       number of valid entries in {@code activeTiles}
     */
    public static void prunePendingTextures(MapTile[] activeTiles, int count) {
        if (count <= 0) {
            sPendingTextures.clear();
            sPendingVectorTextures.clear();
            return;
        }
        java.util.Set<String> active = new java.util.HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            MapTile t = activeTiles[i];
            active.add(t.zoomLevel + "/" + t.tileX + "/" + t.tileY);
        }
        sPendingTextures.keySet().removeIf(tile ->
                !active.contains(tile.zoomLevel + "/" + tile.tileX + "/" + tile.tileY));
        sPendingVectorTextures.keySet().removeIf(tile ->
                !active.contains(tile.zoomLevel + "/" + tile.tileX + "/" + tile.tileY));
    }

    /**
     * Retrieves the {@link ExtrusionBuckets} stored on a map tile for terrain rendering.
     *
     * @param tile the map tile
     * @return the ExtrusionBuckets, or null if not present
     */
    public static ExtrusionBuckets getTerrainBuckets(MapTile tile) {
        return (ExtrusionBuckets) tile.getData(TERRAIN_DATA);
    }

    /**
     * Stores {@link ExtrusionBuckets} on a map tile for terrain rendering.
     *
     * @param tile    the map tile
     * @param buckets the extrusion buckets containing the terrain mesh
     */
    public static void setTerrainBuckets(MapTile tile, ExtrusionBuckets buckets) {
        tile.addData(TERRAIN_DATA, buckets);
    }

    /**
     * Retrieves the terrain {@link TextureItem} stored on a map tile.
     *
     * @param tile the map tile
     * @return the TextureItem, or null if not present
     */
    public static TextureItem getTerrainTexture(MapTile tile) {
        TerrainTexData data = (TerrainTexData) tile.getData(TERRAIN_TEX);
        return data != null ? data.texture : null;
    }

    /**
     * Stores a terrain {@link TextureItem} on a map tile for texture draping.
     *
     * @param tile    the map tile
     * @param texture the texture item (uploaded on GL thread by renderer)
     */
    public static void setTerrainTexture(MapTile tile, TextureItem texture) {
        tile.addData(TERRAIN_TEX, new TerrainTexData(texture));
    }

    /**
     * Registers a raster bitmap for later texture upload by the GL render thread.
     * Called from the async raster fetch background thread.
     * <p>
     * Thread-safe: uses {@link ConcurrentHashMap}.
     *
     * @param tile   the terrain tile
     * @param bitmap the raster tile bitmap to drape
     */
    public static void addPendingTexture(MapTile tile, Bitmap bitmap) {
        sPendingTextures.put(tile, bitmap);
    }

    /**
     * Consumes a pending raster bitmap for the given tile, creating a
     * {@link TextureItem} and storing it on the tile's data chain.
     * Called from the GL render thread ({@link TerrainTileRenderer#update}).
     * <p>
     * Thread-safe: {@link ConcurrentHashMap#remove} is atomic;
     * {@link MapTile#addData} is only called from the GL thread.
     *
     * @param tile the terrain tile
     */
    private static int sTexConsumeCount;
    public static void consumePendingTexture(MapTile tile) {
        Bitmap bitmap = sPendingTextures.remove(tile);
        if (bitmap != null && bitmap.isValid()) {
            TextureItem tex = new TextureItem(bitmap);
            tile.addData(TERRAIN_TEX, new TerrainTexData(tex));
            if (++sTexConsumeCount <= 3) {
                System.out.println("TERRAIN: consumed raster texture for " + tile
                        + " (" + bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
            }
        }
    }

    /**
     * Registers a vector drape bitmap for later texture upload.
     * Called from the async vector drape background thread.
     */
    public static void addPendingVectorTexture(MapTile tile, Bitmap bitmap) {
        sPendingVectorTextures.put(tile, bitmap);
    }

    /**
     * Consumes a pending vector drape bitmap, creating a TextureItem.
     * Called from the GL render thread.
     */
    public static void consumePendingVectorTexture(MapTile tile) {
        Bitmap bitmap = sPendingVectorTextures.remove(tile);
        if (bitmap != null && bitmap.isValid()) {
            TextureItem tex = new TextureItem(bitmap);
            tile.addData(TERRAIN_VECTOR_TEX, new TerrainTexData(tex));
        }
    }

    /**
     * Retrieves the vector drape {@link TextureItem} stored on a map tile.
     */
    public static TextureItem getTerrainVectorTexture(MapTile tile) {
        TerrainTexData data = (TerrainTexData) tile.getData(TERRAIN_VECTOR_TEX);
        return data != null ? data.texture : null;
    }

    // ─────────────────────────────────────────────
    // Elevation query API — for use by other layers
    // ─────────────────────────────────────────────

    /**
     * Registers the shared elevation query context. Called automatically
     * during {@link TerrainTileLayer} construction. Other layers (buildings,
     * labels, roads) use {@link #getElevation} to sample terrain height.
     */
    private static void setElevationContext(TerrainTileSource source) {
        sElevationAPI = source.getElevationAPI();
        sProjection = source.getProjection();
        sBaseElevation = source.getBaseElevation();

        final boolean isGlobe = (sProjection.getType() == TerrainProjection.Type.GLOBE);
        final float metersToRadius = (isGlobe && sProjection instanceof org.oscim.terrain.projection.GlobeTerrainProjection)
                ? ((org.oscim.terrain.projection.GlobeTerrainProjection) sProjection).getMetersToRadius()
                : 0f;

        // Also register as the global ElevationProvider for layers in the
        // vtm module that can't import vtm-terrain directly.
        ElevationProvider.set(new ElevationProvider.Sampler() {
            @Override
            public float getElevation(float lat, float lon) {
                return TerrainTileLayer.getElevation(lat, lon);
            }

            @Override
            public float metersToTileZ(float meters, double lat, double scale) {
                return TerrainTileLayer.metersToTileZ(meters, lat, scale);
            }

            @Override
            public float metersToTileZGlobe(float meters) {
                if (isGlobe) {
                    return meters * metersToRadius;
                }
                // Flat: delegate to standard Mercator conversion
                return metersToTileZ(meters, 0, 1);
            }
        });
    }

    /**
     * Returns true if the terrain elevation query API is available.
     */
    public static boolean hasElevationData() {
        return sElevationAPI != null;
    }

    /**
     * Returns the terrain elevation in meters (base-offset already applied)
     * at the given geographic position, or {@link Float#NaN} if no data
     * is available.
     * <p>
     * Thread-safe: delegates to mapsforge's {@link ElevationAPI}.
     *
     * @param lat latitude in degrees
     * @param lon longitude in degrees
     * @return elevation in meters, or NaN if no data
     */
    public static float getElevation(float lat, float lon) {
        if (sElevationAPI == null)
            return Float.NaN;
        double e = sElevationAPI.getElevation(lat, lon);
        if (!ElevationAPI.isValid(e))
            return Float.NaN;
        return (float) e - sBaseElevation;
    }

    /**
     * Converts an elevation in meters to tile-local Z units for use in
     * vertex buffers. Uses the same scaling as the terrain mesh generator
     * ({@link ExtrusionUtils#mapGroundScale}).
     *
     * @param meters elevation in meters
     * @param lat    latitude for ground resolution calculation
     * @param scale  tile scale (1 << zoomLevel)
     * @return tile-local Z value in 10cm units
     */
    public static float metersToTileZ(float meters, double lat, double scale) {
        if (meters == Short.MIN_VALUE)
            return 0f;
        float groundScale = (float) MercatorProjection.groundResolutionWithScale(lat, scale);
        return ExtrusionUtils.mapGroundScale(meters, groundScale);
    }
}

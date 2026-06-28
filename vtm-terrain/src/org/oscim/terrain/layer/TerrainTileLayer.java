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

import org.oscim.layers.tile.MapTile;
import org.oscim.layers.tile.MapTile.TileData;
import org.oscim.layers.tile.TileLayer;
import org.oscim.layers.tile.TileLoader;
import org.oscim.layers.tile.TileManager;
import org.oscim.map.Map;
import org.oscim.renderer.bucket.ExtrusionBuckets;
import org.oscim.renderer.bucket.TextureItem;
import org.oscim.terrain.tiling.TerrainTileLoader;
import org.oscim.terrain.tiling.TerrainTileSource;

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

    private static final int CACHE_LIMIT = 30;

    /** Key for storing ExtrusionBuckets in MapTile's TileData chain. */
    private static final Object TERRAIN_DATA = TerrainTileLayer.class.getName();

    /** Key for storing terrain TextureItem in MapTile's TileData chain. */
    private static final Object TERRAIN_TEX = "terrain_texture";

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

    public TerrainTileLayer(Map map, TerrainTileSource tileSource) {
        this(map, tileSource, CACHE_LIMIT);
    }

    public TerrainTileLayer(Map map, TerrainTileSource tileSource, int cacheLimit) {
        super(map,
                new TileManager(map, cacheLimit),
                new TerrainTileRenderer());

        mTerrainSource = tileSource;

        mTileManager.setZoomLevel(tileSource.getZoomLevelMin(),
                tileSource.getZoomLevelMax());

        initLoader(getNumLoaders());
        log.info("TERRAIN: layer created, zoom range "
                + tileSource.getZoomLevelMin() + "-" + tileSource.getZoomLevelMax());
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
}

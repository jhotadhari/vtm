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

import org.oscim.backend.canvas.Bitmap;
import org.oscim.backend.canvas.Color;
import org.oscim.core.GeometryBuffer;
import org.oscim.layers.tile.MapTile;
import org.oscim.layers.tile.TileLoader;
import org.oscim.renderer.bucket.ExtrusionBucket;
import org.oscim.renderer.bucket.ExtrusionBuckets;
import org.oscim.renderer.bucket.TextureItem;
import org.oscim.terrain.layer.TerrainTileLayer;
import org.oscim.tiling.ITileDataSource;
import org.oscim.tiling.QueryResult;

import java.util.logging.Logger;

/**
 * Tile loader that receives terrain mesh data from
 * {@link TerrainTileDataSource} and creates {@link ExtrusionBuckets}
 * for rendering via the terrain renderer.
 * <p>
 * Each loaded tile produces a single {@link ExtrusionBucket} containing
 * the terrain triangle mesh, stored in {@link ExtrusionBuckets} on the
 * tile's data chain via {@link TerrainTileLayer#setTerrainBuckets}.
 */
public class TerrainTileLoader extends TileLoader {

    private static final Logger log = Logger.getLogger(TerrainTileLoader.class.getName());

    private final TerrainTileSource mTileSource;
    private final ITileDataSource mTileDataSource;

    /**
     * Mesh data passed from the data source during query().
     * Set by TerrainTileDataSource within the same package.
     */
    GeometryBuffer mMesh;

    /**
     * Raster bitmap passed from the data source via {@link #setTileImage}.
     * When non-null, a texture will be created for draping onto the terrain mesh.
     */
    private Bitmap mRasterBitmap;

    public TerrainTileLoader(TerrainTileLayer tileLayer, TerrainTileSource tileSource) {
        super(tileLayer.getManager());
        mTileSource = tileSource;
        mTileDataSource = tileSource.getDataSource();
    }

    /**
     * Receives the raster tile bitmap from the terrain data source.
     * Called by {@link TerrainTileDataSource} after fetching a raster tile.
     */
    @Override
    public void setTileImage(Bitmap bitmap) {
        mRasterBitmap = bitmap;
    }

    @Override
    protected boolean loadTile(MapTile tile) {
        try {
            log.fine("TERRAIN: loadTile " + tile);
            mMesh = null;
            mTileDataSource.query(tile, this);
        } catch (Exception e) {
            log.warning("TERRAIN: loadTile error " + tile + ": " + e);
        }
        return true;
    }

    @Override
    public void completed(QueryResult result) {
        if (result == QueryResult.SUCCESS && mMesh != null && mTile != null) {
            float groundScale = mTile.getGroundScale();

            // Create ExtrusionBucket with the terrain mesh
            ExtrusionBucket bucket = new ExtrusionBucket(0, groundScale, mTileSource.getTerrainColor());
            bucket.addMesh(mMesh);

            // Wrap in ExtrusionBuckets for the renderer
            ExtrusionBuckets ebs = new ExtrusionBuckets(mTile);
            ebs.resetBuckets(bucket);

            // Store on the tile's data chain
            TerrainTileLayer.setTerrainBuckets(mTile, ebs);

            // Upload raster bitmap as texture (if available)
            if (mRasterBitmap != null && mRasterBitmap.isValid()) {
                TextureItem tex = new TextureItem(mRasterBitmap);
                TerrainTileLayer.setTerrainTexture(mTile, tex);
            }
        }
        super.completed(result);
    }

    @Override
    public void dispose() {
        mTileDataSource.cancel();
    }

    @Override
    public void cancel() {
        mTileDataSource.cancel();
    }
}

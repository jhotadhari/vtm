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
import org.mapsforge.map.layer.hills.DemFolder;
import org.oscim.backend.canvas.Color;
import org.oscim.map.Viewport;
import org.oscim.terrain.projection.MercatorTerrainProjection;
import org.oscim.terrain.projection.TerrainProjection;
import org.oscim.tiling.ITileDataSource;
import org.oscim.tiling.TileSource;

/**
 * Tile source for terrain mesh generation. Configured with a {@link DemFolder}
 * pointing to HGT files and a {@link TerrainProjection} for coordinate mapping.
 * <p>
 * An optional raster {@link TileSource} can be set to drape satellite or map
 * imagery onto the terrain mesh. When set, each terrain tile will attempt to
 * fetch the corresponding raster tile and blend it with the procedural height
 * coloring.
 * <p>
 * Usage:
 * <pre>{@code
 * TerrainTileSource terrainSource = new TerrainTileSource(
 *     Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL,
 *     new DemFolderFS(new File("/path/to/hgt")),
 *     new MercatorTerrainProjection());
 * // Optional: drape raster imagery
 * terrainSource.setRasterSource(new BitmapTileSource(...));
 * TerrainTileLayer terrainLayer = new TerrainTileLayer(map, terrainSource);
 * map.layers().add(terrainLayer);
 * }</pre>
 */
public class TerrainTileSource extends TileSource {

    private final DemFolder mDemFolder;
    private volatile TerrainProjection mProjection;
    private float mElevationExaggeration = 5.0f;
    private float mBaseElevation = 3800f; // Andes plateau baseline in meters
    private int mTerrainColor = Color.get(180, 160, 140, 255);

    /** Optional raster tile source for texture draping. */
    private TileSource mRasterSource;

    /** Blend factor between procedural color and texture (0=color only, 1=texture only). */
    private float mTexMix = 0.8f;

    /** Shared ElevationAPI for on-demand elevation queries (created lazily). */
    private ElevationAPI mElevationAPI;

    /**
     * Creates a terrain tile source with default Mercator projection.
     */
    public TerrainTileSource(int zoomLevelMin, int zoomLevelMax, DemFolder demFolder) {
        this(zoomLevelMin, zoomLevelMax, demFolder, new MercatorTerrainProjection());
    }

    /**
     * Creates a terrain tile source with a custom projection.
     */
    public TerrainTileSource(int zoomLevelMin, int zoomLevelMax,
                             DemFolder demFolder, TerrainProjection projection) {
        super(zoomLevelMin, zoomLevelMax);
        mDemFolder = demFolder;
        mProjection = projection;
    }

    public DemFolder getDemFolder() {
        return mDemFolder;
    }

    public TerrainProjection getProjection() {
        return mProjection;
    }

    /** Sets the terrain projection (e.g., Mercator or Globe). */
    public TerrainTileSource setProjection(TerrainProjection projection) {
        mProjection = projection;
        return this;
    }

    /** Sets the elevation exaggeration factor (default 3.0). */
    public TerrainTileSource setElevationExaggeration(float factor) {
        mElevationExaggeration = factor;
        return this;
    }

    /** Returns the elevation exaggeration factor. */
    public float getElevationExaggeration() {
        return mElevationExaggeration;
    }

    /** Sets the terrain mesh color. */
    public TerrainTileSource setTerrainColor(int color) {
        mTerrainColor = color;
        return this;
    }

    /** Returns the terrain mesh color. */
    public int getTerrainColor() {
        return mTerrainColor;
    }

    /** Sets the base elevation subtracted from all heights (meters). Default 3800m for Andes. */
    public TerrainTileSource setBaseElevation(float meters) {
        mBaseElevation = meters;
        return this;
    }

    /** Returns the base elevation offset in meters. */
    public float getBaseElevation() {
        return mBaseElevation;
    }

    /** Sets an optional raster tile source for texture draping onto terrain. */
    public TerrainTileSource setRasterSource(TileSource rasterSource) {
        mRasterSource = rasterSource;
        return this;
    }

    /** Returns the raster tile source, or null if none is configured. */
    public TileSource getRasterSource() {
        return mRasterSource;
    }

    /**
     * Sets the blend factor between procedural height coloring and raster
     * texture. 0.0 = procedural color only, 1.0 = texture only. Default 0.8.
     */
    public TerrainTileSource setTexMix(float texMix) {
        mTexMix = texMix;
        return this;
    }

    /** Returns the texture blend factor. */
    public float getTexMix() {
        return mTexMix;
    }

    /** Optional vector tile source for area-fill draping onto terrain. */
    private TileSource mVectorSource;

    /** Sets an optional vector tile source for area-fill texture draping. */
    public TerrainTileSource setVectorSource(TileSource vectorSource) {
        mVectorSource = vectorSource;
        return this;
    }

    /** Returns the vector tile source, or null if none is configured. */
    public TileSource getVectorSource() {
        return mVectorSource;
    }

    /**
     * Returns the shared {@link ElevationAPI} for on-demand elevation queries.
     * Created lazily on first access. The same instance is shared across all
     * {@link TerrainTileDataSource} instances created by this source.
     */
    public ElevationAPI getElevationAPI() {
        if (mElevationAPI == null) {
            synchronized (this) {
                if (mElevationAPI == null) {
                    mElevationAPI = new ElevationAPI(mDemFolder);
                }
            }
        }
        return mElevationAPI;
    }

    @Override
    public ITileDataSource getDataSource() {
        return new TerrainTileDataSource(this);
    }

    @Override
    public OpenResult open() {
        return OpenResult.SUCCESS;
    }

    @Override
    public void close() {
    }
}

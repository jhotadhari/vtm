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
 * Usage:
 * <pre>{@code
 * TerrainTileSource terrainSource = new TerrainTileSource(
 *     Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL,
 *     new DemFolderFS(new File("/path/to/hgt")),
 *     new MercatorTerrainProjection());
 * TerrainTileLayer terrainLayer = new TerrainTileLayer(map, terrainSource);
 * map.layers().add(terrainLayer);
 * }</pre>
 */
public class TerrainTileSource extends TileSource {

    private final DemFolder mDemFolder;
    private final TerrainProjection mProjection;
    private float mElevationExaggeration = 5.0f;
    private float mBaseElevation = 3800f; // Andes plateau baseline in meters
    private int mTerrainColor = Color.get(180, 160, 140, 255);

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

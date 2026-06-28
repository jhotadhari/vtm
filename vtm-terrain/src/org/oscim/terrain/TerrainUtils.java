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
package org.oscim.terrain;

import org.oscim.core.GeometryBuffer;
import org.oscim.core.Tile;
import org.oscim.renderer.MapRenderer;
import org.oscim.terrain.projection.TerrainProjection;

/**
 * Utility methods for terrain mesh generation: LOD resolution lookup,
 * geographic grid generation, elevation-to-mesh conversion.
 */
public final class TerrainUtils {

    /** Scale factor for short encoding of tile-local coordinates. */
    static final float COORD_SCALE = MapRenderer.COORD_SCALE;

    /** Default tile size in pixels. */
    static final float TILE_SIZE = Tile.SIZE;

    /** Maximum short value for tile-local coordinates (= TILE_SIZE * COORD_SCALE = 4096). */
    public static final float TILE_SCALE_MAX = TILE_SIZE * COORD_SCALE;

    private TerrainUtils() {
    }

    /**
     * Returns the terrain mesh grid resolution for a given zoom level.
     * <p>
     * Higher zoom → more vertices → finer terrain detail.
     *
     * @param zoomLevel the integer zoom level
     * @return N such that the grid is N×N vertices
     */
    public static int getGridResolution(int zoomLevel) {
        if (zoomLevel < 5)
            return 9;
        if (zoomLevel < 8)
            return 9;
        if (zoomLevel < 11)
            return 17;
        if (zoomLevel < 14)
            return 33;
        if (zoomLevel < 17)
            return 65;
        return 129;
    }

    /**
     * Generates a terrain mesh for a tile as a {@link GeometryBuffer} in triangle
     * mesh format suitable for {@link org.oscim.renderer.bucket.ExtrusionBucket#addMesh}.
     * <p>
     * The mesh is a regular N×N grid with each quad split into two triangles.
     * Vertex x and y are tile-local coordinates (0 to TILE_SCALE_MAX). Vertex z
     * is elevation encoded in tile-local z-units via the projection.
     * <p>
     * No-data vertices (elevation == Short.MIN_VALUE) are set to z=0 (sea level).
     *
     * @param projection  the terrain projection for coordinate conversion
     * @param tileMapSize the map size at this zoom level (e.g., Tile.SIZE * (1L << zoom))
     * @param tileOriginX world-pixel x of the tile's origin
     * @param tileOriginY world-pixel y of the tile's origin
     * @param tileScale   absolute map scale (e.g., 1 << zoomLevel)
     * @param latMin      minimum (southernmost) latitude of the tile in degrees
     * @param latMax      maximum (northernmost) latitude of the tile in degrees
     * @param lonMin      minimum (westernmost) longitude of the tile in degrees
     * @param lonMax      maximum (easternmost) longitude of the tile in degrees
     * @param elevationSampler callback to get elevation in meters at (lat, lon)
     * @param exaggeration    multiplier for elevation (1.0 = real, 2.0+ = mountains appear higher)
     * @return a GeometryBuffer with triangle mesh data, or null on error
     */
    public static GeometryBuffer generateTerrainMesh(
            TerrainProjection projection,
            long tileMapSize,
            double tileOriginX,
            double tileOriginY,
            double tileScale,
            double latMin, double latMax,
            double lonMin, double lonMax,
            ElevationSampler elevationSampler,
            float exaggeration) {

        int N = getGridResolution((int) (Math.log(tileScale) / Math.log(2) + 0.5));

        int vertexCount = N * N;
        int triCount = (N - 1) * (N - 1) * 2;
        int indexCount = triCount * 3;

        // Allocate geometry arrays
        float[] points = new float[vertexCount * 3];
        int[] indices = new int[indexCount];

        // Tile-local coordinate step per grid cell
        float step = TILE_SCALE_MAX / (N - 1);

        double latRange = latMax - latMin;
        double lonRange = lonMax - lonMin;

        // First pass: sample elevations, find minimum (ignoring no-data)
        float[] rawElev = new float[vertexCount];
        float elevMin = Float.MAX_VALUE;
        for (int j = 0; j < N; j++) {
            double lat = latMax - (j / (double) (N - 1)) * latRange;
            for (int i = 0; i < N; i++) {
                double lon = lonMin + (i / (double) (N - 1)) * lonRange;
                float elev = elevationSampler.getElevation((float) lat, (float) lon);
                if (elev == Short.MIN_VALUE) elev = 0f;
                rawElev[j * N + i] = elev;
                if (elev < elevMin) elevMin = elev;
            }
        }
        if (elevMin == Float.MAX_VALUE) elevMin = 0f;

        // Second pass: build vertices, subtracting base elevation so terrain sits on map
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                float tx = i * step;
                float ty = j * step;

                // Subtract base elevation so lowest point is at z=0
                float elevMeters = rawElev[j * N + i] - elevMin;

                // Convert to tile-local z with exaggeration
                float tz = projection.elevToTileZ(elevMeters * exaggeration,
                        latMax - (j / (double) (N - 1)) * latRange,
                        tileScale);

                int vIdx = (j * N + i) * 3;
                points[vIdx + 0] = tx;
                points[vIdx + 1] = ty;
                points[vIdx + 2] = tz;
            }
        }

        // Build triangle indices
        int idxPos = 0;
        for (int j = 0; j < N - 1; j++) {
            for (int i = 0; i < N - 1; i++) {
                int v00 = j * N + i;
                int v10 = j * N + (i + 1);
                int v01 = (j + 1) * N + i;
                int v11 = (j + 1) * N + (i + 1);

                // Triangle 1: upper-left triangle (v00, v10, v01)
                indices[idxPos++] = v00;
                indices[idxPos++] = v10;
                indices[idxPos++] = v01;

                // Triangle 2: lower-right triangle (v10, v11, v01)
                indices[idxPos++] = v10;
                indices[idxPos++] = v11;
                indices[idxPos++] = v01;
            }
        }

        // Build GeometryBuffer
        GeometryBuffer gb = new GeometryBuffer(vertexCount, indexCount);
        gb.points = points;
        gb.index = indices;
        gb.pointNextPos = vertexCount * 3;
        gb.indexCurrentPos = indexCount;

        // Mark as triangle mesh
        gb.type = GeometryBuffer.GeometryType.TRIS;

        return gb;
    }

    /**
     * Callback interface for sampling elevation at a geographic point.
     * Implementations typically wrap a mapsforge {@code ElevationAPI} or
     * {@code MemoryCachingHgtReaderTileSource}.
     */
    public interface ElevationSampler {
        /**
         * Returns elevation in meters at the given location.
         *
         * @param lat latitude in degrees (WGS84)
         * @param lon longitude in degrees (WGS84)
         * @return elevation in meters, or {@link Short#MIN_VALUE} for no-data
         */
        float getElevation(float lat, float lon);
    }

    /**
     * Checks whether a tile is entirely over ocean (no elevation data).
     * Samples the four corners; if all return no-data, the tile is ocean.
     *
     * @return true if all four corners return Short.MIN_VALUE
     */
    public static boolean isOceanTile(ElevationSampler sampler,
                                      double latMin, double latMax,
                                      double lonMin, double lonMax) {
        return sampler.getElevation((float) latMin, (float) lonMin) == Short.MIN_VALUE
                && sampler.getElevation((float) latMin, (float) lonMax) == Short.MIN_VALUE
                && sampler.getElevation((float) latMax, (float) lonMin) == Short.MIN_VALUE
                && sampler.getElevation((float) latMax, (float) lonMax) == Short.MIN_VALUE;
    }
}

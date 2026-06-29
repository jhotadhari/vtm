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

        boolean isGlobe = (projection.getType() == TerrainProjection.Type.GLOBE);

        // Build vertex array
        for (int j = 0; j < N; j++) {
            // Geographic lat: interpolate from top to bottom (j=0 → latMax, j=N-1 → latMin)
            double lat = latMax - (j / (double) (N - 1)) * latRange;

            for (int i = 0; i < N; i++) {
                // Geographic lon: interpolate from left to right (i=0 → lonMin, i=N-1 → lonMax)
                double lon = lonMin + (i / (double) (N - 1)) * lonRange;

                // Query elevation
                float elevMeters = elevationSampler.getElevation((float) lat, (float) lon);

                // Convert elevation to tile-local z with exaggeration.
                // Check no-data sentinel BEFORE multiplying by exaggeration,
                // otherwise Short.MIN_VALUE * exag ≠ Short.MIN_VALUE and the
                // guard inside elevToTileZ() is silently bypassed.
                float elev = elevMeters;
                if (elevMeters != Short.MIN_VALUE) {
                    elev = elevMeters * exaggeration;
                }

                int vIdx = (j * N + i) * 3;

                if (isGlobe) {
                    // Globe: projection computes all 3 coordinates (ECEF-relative)
                    float[] xyz = new float[3];
                    projection.project((float) lat, (float) lon, elev,
                            tileOriginX, tileOriginY, tileMapSize, xyz);
                    points[vIdx + 0] = xyz[0];
                    points[vIdx + 1] = xyz[1];
                    points[vIdx + 2] = xyz[2];
                } else {
                    // Mercator: flat grid positions, only Z through projection
                    float tx = i * step;
                    float ty = j * step;
                    float tz = projection.elevToTileZ(elev, lat, tileScale);
                    points[vIdx + 0] = tx;
                    points[vIdx + 1] = ty;
                    points[vIdx + 2] = tz;
                }
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

        // Compute per-vertex gradient normals for seamless tile boundaries
        short[] normals = computeTerrainNormals(projection, N, step, points,
                latMin, latMax, lonMin, lonMax,
                elevationSampler, exaggeration, tileScale,
                tileOriginX, tileOriginY, tileMapSize);

        // Build GeometryBuffer
        GeometryBuffer gb = new GeometryBuffer(vertexCount, indexCount);
        gb.points = points;
        gb.index = indices;
        gb.pointNextPos = vertexCount * 3;
        gb.indexCurrentPos = indexCount;
        gb.normals = normals;

        // Mark as triangle mesh
        gb.type = GeometryBuffer.GeometryType.TRIS;

        return gb;
    }

    /**
     * Computes per-vertex normals from the elevation gradient for seamless
     * tile boundary lighting. Uses central finite differences in tile-local
     * (tx, ty, tz) space. At tile boundaries, queries elevation one grid
     * step beyond the tile so that adjacent tiles compute the same gradient
     * at shared edges and corners.
     * <p>
     * For globe projections, boundary neighbor positions use the full 3D
     * {@link TerrainProjection#project} method to correctly account for
     * sphere curvature.
     * <p>
     * The normal packing matches {@code ExtrusionBucket.addMesh}:
     * two bytes encoding nx, ny, with the LSB of the first byte encoding
     * the sign of nz.
     */
    private static short[] computeTerrainNormals(
            TerrainProjection projection,
            int N,
            float step,
            float[] points,
            double latMin, double latMax,
            double lonMin, double lonMax,
            ElevationSampler elevationSampler,
            float exaggeration,
            double tileScale,
            double tileOriginX, double tileOriginY,
            long tileMapSize) {

        short[] normals = new short[N * N];
        double latRange = latMax - latMin;
        double lonRange = lonMax - lonMin;

        // Grid step sizes in geographic coordinates
        double latStep = latRange / (N - 1);
        double lonStep = lonRange / (N - 1);

        boolean isGlobe = (projection.getType() == TerrainProjection.Type.GLOBE);

        // Normal direction mask: clear LSB, then set from nz sign
        final int NORMAL_DIR_MASK = 0xFFFFFFFE;

        for (int j = 0; j < N; j++) {
            double lat = latMax - (j / (double) (N - 1)) * latRange;

            for (int i = 0; i < N; i++) {
                double lon = lonMin + (i / (double) (N - 1)) * lonRange;

                // Get z at center vertex from pre-computed points array
                float zCenter = points[(j * N + i) * 3 + 2];

                // ---- X gradient (east-west): ∂tz/∂tx ----
                float zRight;
                if (i < N - 1) {
                    zRight = points[(j * N + (i + 1)) * 3 + 2];
                } else {
                    zRight = getBoundaryZ(projection, lat, lonMax + lonStep,
                            elevationSampler, exaggeration, tileScale, isGlobe,
                            tileOriginX, tileOriginY, tileMapSize);
                }

                float zLeft;
                if (i > 0) {
                    zLeft = points[(j * N + (i - 1)) * 3 + 2];
                } else {
                    zLeft = getBoundaryZ(projection, lat, lonMin - lonStep,
                            elevationSampler, exaggeration, tileScale, isGlobe,
                            tileOriginX, tileOriginY, tileMapSize);
                }

                float gx = (zRight - zLeft) / (2.0f * step);

                // ---- Y gradient (north-south): ∂tz/∂ty ----
                // j=0 is top (latMax), j=N-1 is bottom (latMin)
                float zDown;
                if (j < N - 1) {
                    zDown = points[((j + 1) * N + i) * 3 + 2];
                } else {
                    zDown = getBoundaryZ(projection, latMin - latStep, lon,
                            elevationSampler, exaggeration, tileScale, isGlobe,
                            tileOriginX, tileOriginY, tileMapSize);
                }

                float zUp;
                if (j > 0) {
                    zUp = points[((j - 1) * N + i) * 3 + 2];
                } else {
                    zUp = getBoundaryZ(projection, latMax + latStep, lon,
                            elevationSampler, exaggeration, tileScale, isGlobe,
                            tileOriginX, tileOriginY, tileMapSize);
                }

                float gy = (zDown - zUp) / (2.0f * step);

                // Surface normal from gradient: (-gx, -gy, 1) normalized
                double len = Math.sqrt((double) gx * gx + (double) gy * gy + 1.0);
                float nx = (float) (-gx / len);
                float ny = (float) (-gy / len);
                float nz = (float) (1.0 / len);

                // Pack into short (same format as ExtrusionBucket face normals)
                int mx = org.oscim.utils.FastMath.clamp(127 + (int) (nx * 128), 0, 0xff);
                int my = org.oscim.utils.FastMath.clamp(127 + (int) (ny * 128), 0, 0xff);
                normals[j * N + i] = (short) ((my << 8) | (mx & NORMAL_DIR_MASK) | (nz > 0 ? 1 : 0));
            }
        }

        return normals;
    }

    /**
     * Returns the Z component of a boundary-neighbor sample point.
     * For globe projections, uses {@link TerrainProjection#project} to get
     * the full 3D ECEF-relative position. For Mercator, uses the simpler
     * {@link TerrainProjection#elevToTileZ} path.
     */
    private static float getBoundaryZ(TerrainProjection projection,
                                       double lat, double lon,
                                       ElevationSampler elevationSampler,
                                       float exaggeration,
                                       double tileScale,
                                       boolean isGlobe,
                                       double tileOriginX, double tileOriginY,
                                       long tileMapSize) {
        float elev = elevationSampler.getElevation((float) lat, (float) lon);
        if (elev != Short.MIN_VALUE) {
            elev *= exaggeration;
        }

        if (isGlobe) {
            float[] xyz = new float[3];
            projection.project((float) lat, (float) lon, elev,
                    tileOriginX, tileOriginY, tileMapSize, xyz);
            return xyz[2];
        } else {
            return projection.elevToTileZ(elev, lat, tileScale);
        }
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

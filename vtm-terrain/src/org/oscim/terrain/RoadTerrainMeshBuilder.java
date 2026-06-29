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

import org.oscim.backend.canvas.Color;
import org.oscim.core.GeometryBuffer;
import org.oscim.core.MapElement;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Tag;
import org.oscim.core.Tile;
import org.oscim.layers.tile.MapTile;
import org.oscim.renderer.MapRenderer;
import org.oscim.terrain.layer.TerrainTileLayer;

/**
 * Generates 3D triangle-strip meshes for major roads that follow the terrain
 * surface.
 * <p>
 * Only motorway, trunk, and primary highways receive 3D meshes. The mesh is a
 * series of quad segments (each split into two triangles) running along the
 * way's centerline, with left/right edges offset by half the road width.
 * <p>
 * Road vertices are elevated by a small Z bias ({@link #Z_BIAS_METERS}) above
 * the terrain surface to prevent z-fighting.
 * <p>
 * Output is a {@link GeometryBuffer} in {@code TRIS} format suitable for
 * {@link org.oscim.renderer.bucket.ExtrusionBucket#addMesh}.
 */
public final class RoadTerrainMeshBuilder {

    /** Scale factor for mesh coordinates (same as MapRenderer.COORD_SCALE). */
    private static final float COORD_SCALE = MapRenderer.COORD_SCALE;

    /**
     * Mesh-unit Z bias to prevent z-fighting with the terrain mesh.
     * Applied directly in short Z units (not meters) so it survives the
     * (short) cast in ExtrusionBucket.addMesh() at all zoom levels.
     * At zoom 14 equator, 0.35 mesh units ≈ 0.17 m — visible separation
     * for depth testing, invisible to the eye.
     */
    private static final float Z_BIAS_MESH_UNITS = 0.35f;

    // Road widths in meters
    private static final float WIDTH_MOTORWAY = 12f;
    private static final float WIDTH_TRUNK = 10f;
    private static final float WIDTH_PRIMARY = 8f;

    // Road surface colors (dark gray variants)
    /** Motorway color — darkest. */
    public static final int COLOR_MOTORWAY = Color.get(64, 64, 72, 255);
    /** Trunk road color. */
    public static final int COLOR_TRUNK = Color.get(80, 80, 88, 255);
    /** Primary road color — lightest of the three. */
    public static final int COLOR_PRIMARY = Color.get(96, 96, 104, 255);

    private RoadTerrainMeshBuilder() {
    }

    /**
     * Returns the road surface color for a highway element, or 0 if not a
     * qualifying highway.
     */
    public static int getRoadColor(MapElement element) {
        String highway = element.tags.getValue(Tag.KEY_HIGHWAY);
        if (highway == null)
            return 0;
        switch (highway) {
            case "motorway":
                return COLOR_MOTORWAY;
            case "trunk":
                return COLOR_TRUNK;
            case "primary":
                return COLOR_PRIMARY;
            default:
                return 0;
        }
    }

    /**
     * Returns the road width in meters for a highway element, or 0 if not
     * a qualifying highway.
     */
    public static float getRoadWidth(MapElement element) {
        String highway = element.tags.getValue(Tag.KEY_HIGHWAY);
        if (highway == null)
            return 0;
        switch (highway) {
            case "motorway":
                return WIDTH_MOTORWAY;
            case "trunk":
                return WIDTH_TRUNK;
            case "primary":
                return WIDTH_PRIMARY;
            default:
                return 0;
        }
    }

    /**
     * Returns true if the element is a highway type that should receive a
     * 3D road mesh.
     */
    public static boolean isMajorRoad(MapElement element) {
        String highway = element.tags.getValue(Tag.KEY_HIGHWAY);
        return "motorway".equals(highway)
                || "trunk".equals(highway)
                || "primary".equals(highway);
    }

    /**
     * Generates a triangle-strip road mesh from a highway way element.
     * <p>
     * The way's points are in tile-local pixel coordinates (0 to Tile.SIZE).
     * Each point is converted to geographic coordinates for elevation lookup,
     * then the left and right road edges are computed perpendicular to the
     * direction of travel. Quad segments are split into two triangles each.
     *
     * @param element the highway way MapElement
     * @param tile    the tile containing this way
     * @return a GeometryBuffer in TRIS format, or null on error
     */
    public static GeometryBuffer buildRoadMesh(MapElement element, MapTile tile) {
        float roadWidth = getRoadWidth(element);
        if (roadWidth <= 0)
            return null;

        float[] points = element.points;
        int pointCnt = element.pointNextPos;
        if (pointCnt < 4)
            return null; // need at least 2 points (4 floats: x,y,x,y)

        int numPoints = pointCnt / 2;

        // Compute tile parameters
        double scale = 1L << tile.zoomLevel;
        long mapSize = tile.mapSize;
        double originX = tile.getOrigin().x;
        double originY = tile.getOrigin().y;

        // Pre-compute geographic positions and elevations
        double[] lats = new double[numPoints];
        double[] lons = new double[numPoints];
        float[] tz = new float[numPoints];
        float[] mx = new float[numPoints];
        float[] my = new float[numPoints];

        for (int i = 0; i < numPoints; i++) {
            float px = points[i * 2];
            float py = points[i * 2 + 1];

            lons[i] = MercatorProjection.pixelXToLongitude(originX + px, mapSize);
            lats[i] = MercatorProjection.pixelYToLatitude(originY + py, mapSize);

            // Ensure elevation context is visible (volatile gate via ElevationProvider)
            float elevMeters = Float.NaN;
            if (ElevationProvider.isAvailable()) {
                elevMeters = TerrainTileLayer.getElevation((float) lats[i], (float) lons[i]);
            }
            if (Float.isNaN(elevMeters))
                elevMeters = 0f;

            tz[i] = TerrainTileLayer.metersToTileZ(elevMeters, lats[i], scale)
                    + Z_BIAS_MESH_UNITS;

            // Convert tile-local pixel coords to mesh coords (multiply by COORD_SCALE)
            mx[i] = px * COORD_SCALE;
            my[i] = py * COORD_SCALE;
        }

        // Each way point produces 2 vertices (left + right)
        int vertexCount = numPoints * 2;
        // Each consecutive pair of way points produces 2 triangles (one quad)
        int triCount = (numPoints - 1) * 2;
        int indexCount = triCount * 3;

        float[] meshPoints = new float[vertexCount * 3];
        int[] indices = new int[indexCount];
        short[] normals = new short[vertexCount];

        // Up-facing normal: (0, 0, 1) packed into 2 bytes
        // mx = 127, my = 127, direction bit = 1 (nz > 0)
        final short upNormal = (short) ((127 << 8) | (127 & 0xFFFFFFFE) | 1);

        // Generate left/right vertices for each way point
        for (int i = 0; i < numPoints; i++) {
            // Compute perpendicular direction at this point
            float perpX, perpY;
            if (numPoints == 1) {
                // Degenerate case — shouldn't happen with check above
                perpX = 1;
                perpY = 0;
            } else if (i == 0) {
                // First point: use direction to next point
                float dx = mx[i + 1] - mx[i];
                float dy = my[i + 1] - my[i];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.001f) {
                    perpX = 1;
                    perpY = 0;
                } else {
                    perpX = -dy / len;
                    perpY = dx / len;
                }
            } else if (i == numPoints - 1) {
                // Last point: use direction from previous point
                float dx = mx[i] - mx[i - 1];
                float dy = my[i] - my[i - 1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 0.001f) {
                    perpX = 1;
                    perpY = 0;
                } else {
                    perpX = -dy / len;
                    perpY = dx / len;
                }
            } else {
                // Interior point: average of incoming and outgoing directions
                float dxIn = mx[i] - mx[i - 1];
                float dyIn = my[i] - my[i - 1];
                float lenIn = (float) Math.sqrt(dxIn * dxIn + dyIn * dyIn);
                float dxOut = mx[i + 1] - mx[i];
                float dyOut = my[i + 1] - my[i];
                float lenOut = (float) Math.sqrt(dxOut * dxOut + dyOut * dyOut);

                if (lenIn < 0.001f && lenOut < 0.001f) {
                    // Both segments degenerate — use arbitrary perpendicular
                    perpX = 1;
                    perpY = 0;
                } else if (lenIn < 0.001f) {
                    // Only incoming degenerate — use outgoing direction
                    perpX = -dyOut / lenOut;
                    perpY = dxOut / lenOut;
                } else if (lenOut < 0.001f) {
                    // Only outgoing degenerate — use incoming direction
                    perpX = -dyIn / lenIn;
                    perpY = dxIn / lenIn;
                } else {
                    // Normalize and average
                    float avgX = dxIn / lenIn + dxOut / lenOut;
                    float avgY = dyIn / lenIn + dyOut / lenOut;
                    float avgLen = (float) Math.sqrt(avgX * avgX + avgY * avgY);
                    if (avgLen < 0.001f) {
                        // Opposite directions (u-turn) — use perpendicular of incoming
                        perpX = -dyIn / lenIn;
                        perpY = dxIn / lenIn;
                    } else {
                        avgX /= avgLen;
                        avgY /= avgLen;
                        perpX = -avgY;
                        perpY = avgX;
                    }
                }
            }

            // Convert road width from meters to mesh units
            double groundRes = MercatorProjection.groundResolutionWithScale(lats[i], scale);
            float halfWidthMesh = (float) ((roadWidth / 2.0) / groundRes * COORD_SCALE);

            // Left vertex: offset in negative perpendicular direction
            int li = i * 2;
            meshPoints[li * 3 + 0] = mx[i] - perpX * halfWidthMesh;
            meshPoints[li * 3 + 1] = my[i] - perpY * halfWidthMesh;
            meshPoints[li * 3 + 2] = tz[i];
            normals[li] = upNormal;

            // Right vertex: offset in positive perpendicular direction
            int ri = i * 2 + 1;
            meshPoints[ri * 3 + 0] = mx[i] + perpX * halfWidthMesh;
            meshPoints[ri * 3 + 1] = my[i] + perpY * halfWidthMesh;
            meshPoints[ri * 3 + 2] = tz[i];
            normals[ri] = upNormal;
        }

        // Build triangle indices for quad strips
        int idxPos = 0;
        for (int i = 0; i < numPoints - 1; i++) {
            int l0 = i * 2;       // left current
            int r0 = i * 2 + 1;   // right current
            int l1 = (i + 1) * 2; // left next
            int r1 = (i + 1) * 2 + 1; // right next

            // Triangle 1: l0, r0, l1
            indices[idxPos++] = l0;
            indices[idxPos++] = r0;
            indices[idxPos++] = l1;

            // Triangle 2: r0, r1, l1
            indices[idxPos++] = r0;
            indices[idxPos++] = r1;
            indices[idxPos++] = l1;
        }

        // Build GeometryBuffer
        GeometryBuffer gb = new GeometryBuffer(vertexCount, indexCount);
        gb.points = meshPoints;
        gb.index = indices;
        gb.pointNextPos = vertexCount * 3;
        gb.indexCurrentPos = indexCount;
        gb.normals = normals;
        gb.type = GeometryBuffer.GeometryType.TRIS;

        return gb;
    }

}

/*
 * Copyright 2025 jhotadhari
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

import org.junit.Assert;
import org.junit.Test;
import org.oscim.core.GeometryBuffer;
import org.oscim.core.Tile;
import org.oscim.terrain.projection.MercatorTerrainProjection;
import org.oscim.terrain.projection.TerrainProjection;

/**
 * Tests for {@link TerrainUtils}: LOD resolution, ocean tile detection,
 * mesh generation, and edge-vertex equality at shared tile boundaries.
 */
public class TerrainUtilsTest {

    // Reusable projection for all tests
    private static final TerrainProjection PROJ = new MercatorTerrainProjection();

    // ---- getGridResolution ----

    @Test
    public void getGridResolution_zoomBelow5_returns9() {
        Assert.assertEquals(9, TerrainUtils.getGridResolution(0));
        Assert.assertEquals(9, TerrainUtils.getGridResolution(4));
    }

    @Test
    public void getGridResolution_zoom5to7_returns9() {
        Assert.assertEquals(9, TerrainUtils.getGridResolution(5));
        Assert.assertEquals(9, TerrainUtils.getGridResolution(6));
        Assert.assertEquals(9, TerrainUtils.getGridResolution(7));
    }

    @Test
    public void getGridResolution_zoom8to10_returns17() {
        Assert.assertEquals(17, TerrainUtils.getGridResolution(8));
        Assert.assertEquals(17, TerrainUtils.getGridResolution(9));
        Assert.assertEquals(17, TerrainUtils.getGridResolution(10));
    }

    @Test
    public void getGridResolution_zoom11to13_returns33() {
        Assert.assertEquals(33, TerrainUtils.getGridResolution(11));
        Assert.assertEquals(33, TerrainUtils.getGridResolution(12));
        Assert.assertEquals(33, TerrainUtils.getGridResolution(13));
    }

    @Test
    public void getGridResolution_zoom14to16_returns65() {
        Assert.assertEquals(65, TerrainUtils.getGridResolution(14));
        Assert.assertEquals(65, TerrainUtils.getGridResolution(15));
        Assert.assertEquals(65, TerrainUtils.getGridResolution(16));
    }

    @Test
    public void getGridResolution_zoom17plus_returns129() {
        Assert.assertEquals(129, TerrainUtils.getGridResolution(17));
        Assert.assertEquals(129, TerrainUtils.getGridResolution(20));
    }

    @Test
    public void getGridResolution_returnsOddValues() {
        // All resolutions must be odd to ensure symmetrical edge sampling
        for (int z = 0; z <= 20; z++) {
            int n = TerrainUtils.getGridResolution(z);
            Assert.assertTrue("Grid resolution must be odd: zoom=" + z + " N=" + n, n % 2 == 1);
        }
    }

    // ---- isOceanTile ----

    @Test
    public void isOceanTile_allFourCornersNoData_returnsTrue() {
        TerrainUtils.ElevationSampler allNoData = (lat, lon) -> Short.MIN_VALUE;
        Assert.assertTrue(TerrainUtils.isOceanTile(allNoData, -10, 10, -20, 20));
    }

    @Test
    public void isOceanTile_allFourCornersValid_returnsFalse() {
        TerrainUtils.ElevationSampler allValid = (lat, lon) -> 100f;
        Assert.assertFalse(TerrainUtils.isOceanTile(allValid, -10, 10, -20, 20));
    }

    @Test
    public void isOceanTile_oneCornerValid_returnsFalse() {
        // Only latMin,lonMin returns valid elevation
        TerrainUtils.ElevationSampler oneValid = (lat, lon) ->
                (lat == -10f && lon == -20f) ? 100f : Short.MIN_VALUE;
        Assert.assertFalse(TerrainUtils.isOceanTile(oneValid, -10, 10, -20, 20));
    }

    @Test
    public void isOceanTile_partialData_returnsFalse() {
        // Two corners valid
        TerrainUtils.ElevationSampler partial = (lat, lon) ->
                (lat == 10f || lat == -10f) ? 500f : Short.MIN_VALUE;
        Assert.assertFalse(TerrainUtils.isOceanTile(partial, -10, 10, -20, 20));
    }

    // ---- generateTerrainMesh: structure ----

    @Test
    public void generateTerrainMesh_returnsNonNull() {
        GeometryBuffer mesh = generateFlatMesh(10, 0, 10, 10, 20);
        Assert.assertNotNull(mesh);
    }

    @Test
    public void generateTerrainMesh_typeIsTRIS() {
        GeometryBuffer mesh = generateFlatMesh(10, 0, 10, 10, 20);
        Assert.assertEquals(GeometryBuffer.GeometryType.TRIS, mesh.type);
    }

    @Test
    public void generateTerrainMesh_vertexCount() {
        // zoom 12 → N=33, vertices = 33*33 = 1089, each 3 floats
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        int N = 33;
        Assert.assertEquals(N * N * 3, mesh.pointNextPos);
    }

    @Test
    public void generateTerrainMesh_indexCount() {
        // zoom 12 → N=33, triangles = (N-1)*(N-1)*2 = 2048, 3 indices each = 6144
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        int N = 33;
        int expectedIndices = (N - 1) * (N - 1) * 2 * 3;
        Assert.assertEquals(expectedIndices, mesh.indexCurrentPos);
    }

    @Test
    public void generateTerrainMesh_vertexXInRange() {
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        float[] pts = mesh.points;
        for (int i = 0; i < mesh.pointNextPos; i += 3) {
            float x = pts[i];
            Assert.assertTrue("x must be >= 0: " + x, x >= 0);
            Assert.assertTrue("x must be <= TILE_SCALE_MAX: " + x,
                    x <= TerrainUtils.TILE_SCALE_MAX + 0.001f);
        }
    }

    @Test
    public void generateTerrainMesh_vertexYInRange() {
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        float[] pts = mesh.points;
        for (int i = 0; i < mesh.pointNextPos; i += 3) {
            float y = pts[i + 1];
            Assert.assertTrue("y must be >= 0: " + y, y >= 0);
            Assert.assertTrue("y must be <= TILE_SCALE_MAX: " + y,
                    y <= TerrainUtils.TILE_SCALE_MAX + 0.001f);
        }
    }

    @Test
    public void generateTerrainMesh_cornersReachExtremes() {
        // Verify the mesh spans the full tile — corners at (0,0) and (MAX,MAX)
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        float[] pts = mesh.points;
        int N = 33;

        // First vertex (i=0,j=0): should be at (0, 0)
        Assert.assertEquals(0f, pts[0], 0.001f);     // x
        Assert.assertEquals(0f, pts[1], 0.001f);     // y

        // Last column of first row (i=N-1, j=0): should be at (TILE_SCALE_MAX, 0)
        int topRight = ((N - 1) * 3);
        Assert.assertEquals(TerrainUtils.TILE_SCALE_MAX, pts[topRight], 0.001f);     // x
        Assert.assertEquals(0f, pts[topRight + 1], 0.001f);                          // y

        // First column of last row (i=0, j=N-1): should be at (0, TILE_SCALE_MAX)
        int bottomLeft = ((N - 1) * N * 3);
        Assert.assertEquals(0f, pts[bottomLeft], 0.001f);                            // x
        Assert.assertEquals(TerrainUtils.TILE_SCALE_MAX, pts[bottomLeft + 1], 0.001f); // y

        // Last vertex (i=N-1, j=N-1): should be at (TILE_SCALE_MAX, TILE_SCALE_MAX)
        int bottomRight = ((N * N - 1) * 3);
        Assert.assertEquals(TerrainUtils.TILE_SCALE_MAX, pts[bottomRight], 0.001f);     // x
        Assert.assertEquals(TerrainUtils.TILE_SCALE_MAX, pts[bottomRight + 1], 0.001f); // y
    }

    @Test
    public void generateTerrainMesh_flatElevation_zeroZ() {
        // Sea-level elevation everywhere → all z should be 0
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        float[] pts = mesh.points;
        for (int i = 0; i < mesh.pointNextPos; i += 3) {
            Assert.assertEquals("z must be 0 for sea-level: vertex " + (i / 3),
                    0f, pts[i + 2], 0.001f);
        }
    }

    @Test
    public void generateTerrainMesh_positiveElevation_positiveZ() {
        TerrainUtils.ElevationSampler sampler = (lat, lon) -> 500f;
        GeometryBuffer mesh = TerrainUtils.generateTerrainMesh(
                PROJ, 256L << 10, 0, 0, 1 << 10,
                0, 10, 0, 10, sampler, 1.0f);

        float[] pts = mesh.points;
        for (int i = 0; i < mesh.pointNextPos; i += 3) {
            Assert.assertTrue("z must be positive: " + pts[i + 2], pts[i + 2] > 0);
        }
    }

    @Test
    public void generateTerrainMesh_exaggerationDoublesZ() {
        TerrainUtils.ElevationSampler sampler = (lat, lon) -> 100f;
        GeometryBuffer mesh1x = TerrainUtils.generateTerrainMesh(
                PROJ, 256L << 10, 0, 0, 1 << 10,
                0, 10, 0, 10, sampler, 1.0f);

        GeometryBuffer mesh2x = TerrainUtils.generateTerrainMesh(
                PROJ, 256L << 10, 0, 0, 1 << 10,
                0, 10, 0, 10, sampler, 2.0f);

        // Z at 2x should be approximately double (allowing for float precision)
        float z1x = mesh1x.points[2];
        float z2x = mesh2x.points[2];
        Assert.assertEquals(z1x * 2.0f, z2x, z1x * 0.01f);
    }

    // ---- Tile boundary edge vertex equality ----

    @Test
    public void edgeVertices_matchAtSharedBoundary_sameZoom() {
        // Two adjacent tiles at zoom 12, sharing a vertical edge.
        // Tile A: covers lon 0-10, lat 40-50
        // Tile B: covers lon 10-20, lat 40-50
        // The right edge of Tile A = left edge of Tile B at lon=10

        int zoom = 12;
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;
        double latMin = 40, latMax = 50;

        TerrainUtils.ElevationSampler sampler = (lat, lon) -> (float) (lat * 10 + lon);

        // Tile A (lon 0-10)
        GeometryBuffer meshA = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                latMin, latMax, 0, 10, sampler, 1.0f);
        Assert.assertNotNull(meshA);

        // Tile B (lon 10-20)
        GeometryBuffer meshB = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                latMin, latMax, 10, 20, sampler, 1.0f);
        Assert.assertNotNull(meshB);

        int N = TerrainUtils.getGridResolution(zoom);
        Assert.assertTrue("Grid must match for both tiles", N >= 9);

        // Tile A's rightmost column (i = N-1) should match Tile B's leftmost column (i = 0)
        // in z values (elevation), since they share the same geographic coordinates.
        float[] ptsA = meshA.points;
        float[] ptsB = meshB.points;

        for (int j = 0; j < N; j++) {
            int idxA = (j * N + (N - 1)) * 3;  // rightmost column of A
            int idxB = (j * N + 0) * 3;         // leftmost column of B

            // x values differ (Tile A's right edge vs Tile B's left edge in tile-local coords)
            // But z values MUST match — same geographic position → same elevation → same z
            float zA = ptsA[idxA + 2];
            float zB = ptsB[idxB + 2];

            Assert.assertEquals(
                    "Z mismatch at shared boundary row " + j + ": " + zA + " vs " + zB,
                    zA, zB, 1e-5f);
        }
    }

    @Test
    public void edgeVertices_matchAtSharedBoundary_horizontalEdge() {
        // Two adjacent tiles sharing a horizontal edge.
        // Tile A: covers lat 30-40, lon -10-0
        // Tile B: covers lat 40-50, lon -10-0
        // Bottom edge of Tile A (lat=30) should NOT match top edge of Tile B (lat=40) —
        // but Tile A's TOP edge (lat=40) should match Tile B's BOTTOM edge (lat=40).

        int zoom = 12;
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;
        double lonMin = -10, lonMax = 0;

        TerrainUtils.ElevationSampler sampler = (lat, lon) -> (float) (lat * 10 + lon * 5);

        // Tile A (lat 30-40) — its TOP edge is lat=40
        GeometryBuffer meshA = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                30, 40, lonMin, lonMax, sampler, 1.0f);

        // Tile B (lat 40-50) — its BOTTOM edge is lat=40
        GeometryBuffer meshB = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, lonMin, lonMax, sampler, 1.0f);

        int N = TerrainUtils.getGridResolution(zoom);
        float[] ptsA = meshA.points;
        float[] ptsB = meshB.points;

        // Tile A's top row (j = 0 → lat=latMaxA=40) vs
        // Tile B's bottom row (j = N-1 → lat=latMinB=40)
        for (int i = 0; i < N; i++) {
            int idxA = (0 * N + i) * 3;              // top row of A (j=0 → latMax=40)
            int idxB = ((N - 1) * N + i) * 3;         // bottom row of B (j=N-1 → latMin=40)

            float zA = ptsA[idxA + 2];
            float zB = ptsB[idxB + 2];

            Assert.assertEquals(
                    "Z mismatch at shared boundary col " + i + ": " + zA + " vs " + zB,
                    zA, zB, 1e-5f);
        }
    }

    @Test
    public void edgeVertices_matchAtSharedBoundary_withBaseElevationOffset() {
        // Same as the vertical-edge test but with a base elevation offset applied
        // (simulating how TerrainTileDataSource applies baseElevation in the sampler).
        // The offset must not break edge vertex equality.

        int zoom = 12;
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;
        float baseElev = 3800f;

        TerrainUtils.ElevationSampler samplerA = (lat, lon) -> {
            double e = lat * 10 + lon;
            return (float) e - baseElev;
        };

        TerrainUtils.ElevationSampler samplerB = (lat, lon) -> {
            double e = lat * 10 + lon;
            return (float) e - baseElev;
        };

        GeometryBuffer meshA = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, 0, 10, samplerA, 1.0f);

        GeometryBuffer meshB = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, 10, 20, samplerB, 1.0f);

        int N = TerrainUtils.getGridResolution(zoom);
        float[] ptsA = meshA.points;
        float[] ptsB = meshB.points;

        for (int j = 0; j < N; j++) {
            int idxA = (j * N + (N - 1)) * 3;
            int idxB = (j * N + 0) * 3;
            Assert.assertEquals(ptsA[idxA + 2], ptsB[idxB + 2], 1e-5f);
        }
    }

    @Test
    public void edgeVertices_matchAtCorner_fourTiles() {
        // Four tiles meeting at a corner: the shared corner point should have
        // the same z in all four tiles.
        //
        // Tile TL: lat 40-50, lon 0-10  — bottom-right corner = (lat 40, lon 10)
        // Tile TR: lat 40-50, lon 10-20 — bottom-left corner  = (lat 40, lon 10)
        // Tile BL: lat 30-40, lon 0-10  — top-right corner    = (lat 40, lon 10)
        // Tile BR: lat 30-40, lon 10-20 — top-left corner     = (lat 40, lon 10)

        int zoom = 12;
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;

        TerrainUtils.ElevationSampler sampler = (lat, lon) -> (float) (lat * 10 + lon * 5);

        GeometryBuffer tl = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, 0, 10, sampler, 1.0f);
        GeometryBuffer tr = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, 10, 20, sampler, 1.0f);
        GeometryBuffer bl = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                30, 40, 0, 10, sampler, 1.0f);
        GeometryBuffer br = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                30, 40, 10, 20, sampler, 1.0f);

        int N = TerrainUtils.getGridResolution(zoom);

        // Corner index in each tile
        int tlCorner = (0 * N + (N - 1)) * 3;          // j=0 (top=lat 50), i=N-1 (right=lon 10) — wait no
        // Wait, I need to be careful about the coordinate mapping.
        // In generateTerrainMesh:
        //   lat = latMax - j/(N-1) * latRange → j=0 is latMax, j=N-1 is latMin
        //   lon = lonMin + i/(N-1) * lonRange → i=0 is lonMin, i=N-1 is lonMax
        //
        // For TL (lat 40-50, lon 0-10): j=0→lat=50, j=N-1→lat=40, i=0→lon=0, i=N-1→lon=10
        //   Bottom-right = (lat=40, lon=10) = j=N-1, i=N-1

        // For TR (lat 40-50, lon 10-20): j=N-1→lat=40, i=0→lon=10
        //   Bottom-left = (lat=40, lon=10) = j=N-1, i=0

        // For BL (lat 30-40, lon 0-10): j=0→lat=40, i=N-1→lon=10
        //   Top-right = (lat=40, lon=10) = j=0, i=N-1

        // For BR (lat 30-40, lon 10-20): j=0→lat=40, i=0→lon=10
        //   Top-left = (lat=40, lon=10) = j=0, i=0

        int tlIdx = ((N - 1) * N + (N - 1)) * 3;   // bottom-right
        int trIdx = ((N - 1) * N + 0) * 3;          // bottom-left
        int blIdx = (0 * N + (N - 1)) * 3;           // top-right
        int brIdx = (0 * N + 0) * 3;                 // top-left

        float zTL = tl.points[tlIdx + 2];
        float zTR = tr.points[trIdx + 2];
        float zBL = bl.points[blIdx + 2];
        float zBR = br.points[brIdx + 2];

        Assert.assertEquals("TL-TR corner Z mismatch", zTL, zTR, 1e-5f);
        Assert.assertEquals("TL-BL corner Z mismatch", zTL, zBL, 1e-5f);
        Assert.assertEquals("TL-BR corner Z mismatch", zTL, zBR, 1e-5f);
    }

    // ---- Pre-computed gradient normals ----

    @Test
    public void normals_areComputedForAllVertices() {
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        Assert.assertNotNull("normals array must not be null", mesh.normals);
        int N = TerrainUtils.getGridResolution(12);
        Assert.assertEquals(N * N, mesh.normals.length);
    }

    @Test
    public void normals_flatTerrain_pointStraightUp() {
        // On completely flat terrain, all normals should be (0, 0, 1)
        GeometryBuffer mesh = generateFlatMesh(12, -10, 10, -20, 20);
        int N = TerrainUtils.getGridResolution(12);

        for (int v = 0; v < N * N; v++) {
            short n = mesh.normals[v];
            // Unpack: low byte = nx, high byte = ny
            int mx = n & 0xFF;
            int my = (n >> 8) & 0xFF;
            // nx = (mx / 255) * 2 - 1, range [-1, 1]
            float nx = (mx / 255.0f) * 2.0f - 1.0f;
            float ny = (my / 255.0f) * 2.0f - 1.0f;

            // On flat terrain, gradient is zero → normal is (0, 0, 1)
            Assert.assertEquals("nx should be ~0 for flat terrain, vertex " + v,
                    0f, nx, 0.02f);
            Assert.assertEquals("ny should be ~0 for flat terrain, vertex " + v,
                    0f, ny, 0.02f);
        }
    }

    @Test
    public void normals_matchAtSharedVerticalBoundary() {
        // Two adjacent tiles with a sloping elevation function.
        // The pre-computed normals at shared edge vertices must be identical.
        int zoom = 12;
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;
        double latMin = 40, latMax = 50;

        // Elevation function with non-trivial slope in both directions
        TerrainUtils.ElevationSampler sampler = (lat, lon) ->
                (float) (Math.sin(lat * 0.1) * 500 + Math.cos(lon * 0.1) * 300 + lat * 5);

        // Tile A (lon 0-10), Tile B (lon 10-20)
        GeometryBuffer meshA = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                latMin, latMax, 0, 10, sampler, 1.0f);
        GeometryBuffer meshB = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                latMin, latMax, 10, 20, sampler, 1.0f);

        int N = TerrainUtils.getGridResolution(zoom);

        // Tile A's rightmost column (i=N-1) vs Tile B's leftmost column (i=0)
        for (int j = 0; j < N; j++) {
            int idxA = j * N + (N - 1);   // rightmost column of A
            int idxB = j * N + 0;          // leftmost column of B

            short nA = meshA.normals[idxA];
            short nB = meshB.normals[idxB];

            Assert.assertEquals(
                    "Normal mismatch at shared vertical boundary row " + j
                            + ": nA=0x" + Integer.toHexString(nA & 0xFFFF)
                            + " nB=0x" + Integer.toHexString(nB & 0xFFFF),
                    nA, nB);
        }
    }

    @Test
    public void normals_matchAtSharedHorizontalBoundary() {
        int zoom = 12;
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;
        double lonMin = -10, lonMax = 0;

        // Elevation function with non-trivial slope
        TerrainUtils.ElevationSampler sampler = (lat, lon) ->
                (float) (lat * 10 + lon * 5 + Math.cos(lat * 0.1) * 200);

        // Tile A (lat 30-40, bottom tile), Tile B (lat 40-50, top tile)
        // Shared edge: lat=40. Tile A's top (j=0) = Tile B's bottom (j=N-1)
        GeometryBuffer meshA = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                30, 40, lonMin, lonMax, sampler, 1.0f);
        GeometryBuffer meshB = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, lonMin, lonMax, sampler, 1.0f);

        int N = TerrainUtils.getGridResolution(zoom);

        // Tile A's top row (j=0) vs Tile B's bottom row (j=N-1)
        for (int i = 0; i < N; i++) {
            int idxA = 0 * N + i;              // top row of A (j=0 → lat=40)
            int idxB = (N - 1) * N + i;         // bottom row of B (j=N-1 → lat=40)

            short nA = meshA.normals[idxA];
            short nB = meshB.normals[idxB];

            Assert.assertEquals(
                    "Normal mismatch at shared horizontal boundary col " + i
                            + ": nA=0x" + Integer.toHexString(nA & 0xFFFF)
                            + " nB=0x" + Integer.toHexString(nB & 0xFFFF),
                    nA, nB);
        }
    }

    @Test
    public void normals_matchAtCorner_fourTiles() {
        // Four tiles meeting at a corner: all four must share the same normal
        // at the corner vertex.
        int zoom = 12;
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;

        TerrainUtils.ElevationSampler sampler = (lat, lon) ->
                (float) (lat * 10 + lon * 5 + Math.sin(lat * lon * 0.001) * 300);

        GeometryBuffer tl = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, 0, 10, sampler, 1.0f);
        GeometryBuffer tr = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                40, 50, 10, 20, sampler, 1.0f);
        GeometryBuffer bl = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                30, 40, 0, 10, sampler, 1.0f);
        GeometryBuffer br = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                30, 40, 10, 20, sampler, 1.0f);

        int N = TerrainUtils.getGridResolution(zoom);

        // All four tiles share the corner point (lat=40, lon=10)
        int tlCorner = (N - 1) * N + (N - 1);   // bottom-right of TL
        int trCorner = (N - 1) * N + 0;          // bottom-left of TR
        int blCorner = 0 * N + (N - 1);           // top-right of BL
        int brCorner = 0 * N + 0;                 // top-left of BR

        short nTL = tl.normals[tlCorner];
        short nTR = tr.normals[trCorner];
        short nBL = bl.normals[blCorner];
        short nBR = br.normals[brCorner];

        Assert.assertEquals("TL-TR corner normal mismatch", nTL, nTR);
        Assert.assertEquals("TL-BL corner normal mismatch", nTL, nBL);
        Assert.assertEquals("TL-BR corner normal mismatch", nTL, nBR);
    }

    @Test
    public void normals_varyWithTerrainSlope() {
        // On sloped terrain, normals should tilt away from straight up.
        // Use a steep east-west slope with strong exaggeration to ensure
        // the gradient produces a measurable normal tilt.
        TerrainUtils.ElevationSampler steepSlope = (lat, lon) -> lon * 5000f;

        int zoom = 14; // N=65, fine grid
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;

        // Stronger exaggeration amplifies the z gradient
        float exaggeration = 10.0f;

        GeometryBuffer mesh = TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                0, 10, 0, 10, steepSlope, exaggeration);

        int N = TerrainUtils.getGridResolution(zoom);

        // Interior vertex: should have non-zero nx due to east-west slope
        int interiorIdx = (N / 2) * N + (N / 2);
        short n = mesh.normals[interiorIdx];
        int mx = n & 0xFF;
        float nx = (mx / 255.0f) * 2.0f - 1.0f;

        // East-west slope: z increases with lon (which maps to x in tile coords)
        // gx = ∂z/∂x > 0 → normal nx = -gx < 0, pointing west
        Assert.assertTrue("nx should be non-zero on steep sloping terrain, got " + nx,
                Math.abs(nx) > 0.001f);
    }

    // ---- Helper ----

    /**
     * Generates a mesh with zero elevation (sea level) everywhere.
     */
    private static GeometryBuffer generateFlatMesh(int zoom,
                                                   double latMin, double latMax,
                                                   double lonMin, double lonMax) {
        double scale = 1 << zoom;
        long mapSize = Tile.SIZE * (long) scale;
        TerrainUtils.ElevationSampler seaLevel = (lat, lon) -> 0f;
        return TerrainUtils.generateTerrainMesh(
                PROJ, mapSize, 0, 0, scale,
                latMin, latMax, lonMin, lonMax, seaLevel, 1.0f);
    }
}

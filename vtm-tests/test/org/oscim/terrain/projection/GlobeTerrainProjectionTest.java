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
package org.oscim.terrain.projection;

import org.junit.Assert;
import org.junit.Test;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Tile;

/**
 * Tests for {@link GlobeTerrainProjection} — ECEF coordinate conversion,
 * spherical normals, elevation scaling, and tile-local projection.
 */
public class GlobeTerrainProjectionTest {

    private static final float SPHERE_RADIUS = 4096.0f;
    private static final float EPSILON = 0.001f;
    private static final float EPSILON_LOOSE = 1.0f; // for tile-local coords in short range

    private final GlobeTerrainProjection projection = new GlobeTerrainProjection(SPHERE_RADIUS);

    // ── Type ──

    @Test
    public void getType_returnsGlobe() {
        Assert.assertEquals(TerrainProjection.Type.GLOBE, projection.getType());
    }

    // ── ECEF math ──

    @Test
    public void latLonToECEF_equator_primeMeridian() {
        float[] ecef = new float[3];
        GlobeTerrainProjection.latLonToECEF(0f, 0f, SPHERE_RADIUS, ecef);
        // At (0°, 0°): x = R, y = 0, z = 0
        Assert.assertEquals(SPHERE_RADIUS, ecef[0], EPSILON);
        Assert.assertEquals(0f, ecef[1], EPSILON);
        Assert.assertEquals(0f, ecef[2], EPSILON);
    }

    @Test
    public void latLonToECEF_equator_90east() {
        float[] ecef = new float[3];
        GlobeTerrainProjection.latLonToECEF(0f, 90f, SPHERE_RADIUS, ecef);
        // At (0°, 90°E): x ≈ 0, y = R, z = 0
        Assert.assertEquals(0f, ecef[0], EPSILON);
        Assert.assertEquals(SPHERE_RADIUS, ecef[1], EPSILON);
        Assert.assertEquals(0f, ecef[2], EPSILON);
    }

    @Test
    public void latLonToECEF_northPole() {
        float[] ecef = new float[3];
        GlobeTerrainProjection.latLonToECEF(90f, 0f, SPHERE_RADIUS, ecef);
        // At north pole: z = R, x = 0, y = 0
        Assert.assertEquals(0f, ecef[0], EPSILON);
        Assert.assertEquals(0f, ecef[1], EPSILON);
        Assert.assertEquals(SPHERE_RADIUS, ecef[2], EPSILON);
    }

    @Test
    public void latLonToECEF_southPole() {
        float[] ecef = new float[3];
        GlobeTerrainProjection.latLonToECEF(-90f, 0f, SPHERE_RADIUS, ecef);
        Assert.assertEquals(0f, ecef[0], EPSILON);
        Assert.assertEquals(0f, ecef[1], EPSILON);
        Assert.assertEquals(-SPHERE_RADIUS, ecef[2], EPSILON);
    }

    @Test
    public void latLonToECEF_unitLength() {
        // At radius 1, the result should be unit-length
        float[] ecef = new float[3];
        GlobeTerrainProjection.latLonToECEF(30f, 45f, 1.0f, ecef);
        float len = (float) Math.sqrt(ecef[0] * ecef[0] + ecef[1] * ecef[1] + ecef[2] * ecef[2]);
        Assert.assertEquals(1.0f, len, EPSILON);
    }

    // ── getBaseNormal ──

    @Test
    public void getBaseNormal_equator_pointsOutward() {
        float[] normal = new float[3];
        projection.getBaseNormal(0f, 0f, normal);
        Assert.assertEquals(1f, normal[0], EPSILON);
        Assert.assertEquals(0f, normal[1], EPSILON);
        Assert.assertEquals(0f, normal[2], EPSILON);
    }

    @Test
    public void getBaseNormal_northPole_pointsUp() {
        float[] normal = new float[3];
        projection.getBaseNormal(90f, 0f, normal);
        Assert.assertEquals(0f, normal[0], EPSILON);
        Assert.assertEquals(0f, normal[1], EPSILON);
        Assert.assertEquals(1f, normal[2], EPSILON);
    }

    @Test
    public void getBaseNormal_isUnitLength() {
        float[] normal = new float[3];
        // Test at several positions
        float[][] positions = {{0, 0}, {45, 90}, {-30, -60}, {80, 170}};
        for (float[] pos : positions) {
            projection.getBaseNormal(pos[0], pos[1], normal);
            float len = (float) Math.sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2]);
            Assert.assertEquals("unit length at " + pos[0] + "," + pos[1], 1.0f, len, EPSILON);
        }
    }

    // ── elevToTileZ ──

    @Test
    public void elevToTileZ_zeroElevation_returnsZero() {
        float z = projection.elevToTileZ(0f, 0, 1);
        Assert.assertEquals(0f, z, EPSILON);
    }

    @Test
    public void elevToTileZ_noData_returnsZero() {
        float z = projection.elevToTileZ(Short.MIN_VALUE, 0, 1);
        Assert.assertEquals(0f, z, EPSILON);
    }

    @Test
    public void elevToTileZ_linearScaling() {
        // 6371m (0.1% of Earth radius at R=4096) should give about 4.1 units
        float earthRadius = TerrainProjection.EARTH_RADIUS_METERS;
        float elev = earthRadius * 0.01f; // 1% of Earth radius
        float expectedZ = SPHERE_RADIUS * 0.01f;
        float z = projection.elevToTileZ(elev, 0, 1);
        Assert.assertEquals(expectedZ, z, 0.1f);
    }

    @Test
    public void elevToTileZ_positiveElevation_positiveZ() {
        float z = projection.elevToTileZ(1000f, 0, 1);
        Assert.assertTrue("Positive elevation should produce positive Z", z > 0);
    }

    @Test
    public void elevToTileZ_negativeElevation_negativeZ() {
        float z = projection.elevToTileZ(-500f, 0, 1);
        Assert.assertTrue("Negative elevation should produce negative Z", z < 0);
    }

    // ── project (tile-local ECEF relative) ──

    @Test
    public void project_tileCenter_returnsNearZero() {
        // At zoom 10, a tile at the equator/prime-meridian
        long mapSize = Tile.SIZE * (1L << 10);
        // Tile origin in world-pixel coords for the tile centered on (0°,0°)
        double tileOriginX = mapSize / 2.0;
        double tileOriginY = mapSize / 2.0;

        // Compute the actual geographic center of this tile (Mercator tile center)
        double centerLon = MercatorProjection.pixelXToLongitude(
                tileOriginX + Tile.SIZE / 2.0, mapSize);
        double centerLat = MercatorProjection.pixelYToLatitude(
                tileOriginY + Tile.SIZE / 2.0, mapSize);

        float[] xyz = new float[3];
        projection.project((float) centerLat, (float) centerLon, 0f,
                tileOriginX, tileOriginY, mapSize, xyz);

        // At tile center, tile-local ECEF-relative coords should be near zero
        Assert.assertEquals("X at tile center", 0f, xyz[0], 5f);
        Assert.assertEquals("Y at tile center", 0f, xyz[1], 5f);
        Assert.assertEquals("Z at tile center", 0f, xyz[2], 5f);
    }

    @Test
    public void project_elevationAboveSeaLevel_increasesRadius() {
        long mapSize = Tile.SIZE * (1L << 10);
        double tileOriginX = mapSize / 2.0;
        double tileOriginY = mapSize / 2.0;

        // Point well above sea level
        float[] xyzHigh = new float[3];
        projection.project(0f, 0.1f, 5000f,
                tileOriginX, tileOriginY, mapSize, xyzHigh);

        // Point at sea level
        float[] xyzSea = new float[3];
        projection.project(0f, 0.1f, 0f,
                tileOriginX, tileOriginY, mapSize, xyzSea);

        // The elevated point should have a larger magnitude (further from center)
        float magHigh = (float) Math.sqrt(xyzHigh[0] * xyzHigh[0] + xyzHigh[1] * xyzHigh[1] + xyzHigh[2] * xyzHigh[2]);
        float magSea = (float) Math.sqrt(xyzSea[0] * xyzSea[0] + xyzSea[1] * xyzSea[1] + xyzSea[2] * xyzSea[2]);
        Assert.assertTrue("Elevated point should be further from tile center", magHigh > magSea);
    }

    @Test
    public void project_noDataElevation_usesSphereSurface() {
        long mapSize = Tile.SIZE * (1L << 10);
        double tileOriginX = mapSize / 2.0;
        double tileOriginY = mapSize / 2.0;

        float[] xyz = new float[3];
        projection.project(0f, 0.1f, Short.MIN_VALUE,
                tileOriginX, tileOriginY, mapSize, xyz);

        // Should not crash and should produce valid coords
        Assert.assertFalse(Float.isNaN(xyz[0]));
        Assert.assertFalse(Float.isNaN(xyz[1]));
        Assert.assertFalse(Float.isNaN(xyz[2]));
    }

    @Test
    public void project_coordinatesInShortRange() {
        // Verify tile-local coords fit within short range at zoom 14
        long mapSize = Tile.SIZE * (1L << 14);
        double tileOriginX = mapSize / 2.0;
        double tileOriginY = mapSize / 2.0;

        // Sample several points within a tile
        float maxAbs = 0;
        for (int j = 0; j < 10; j++) {
            double lat = -0.05 + (j / 9.0) * 0.1;
            for (int i = 0; i < 10; i++) {
                double lon = -0.05 + (i / 9.0) * 0.1;
                float[] xyz = new float[3];
                projection.project((float) lat, (float) lon, 0f,
                        tileOriginX, tileOriginY, mapSize, xyz);
                maxAbs = Math.max(maxAbs, Math.abs(xyz[0]));
                maxAbs = Math.max(maxAbs, Math.abs(xyz[1]));
                maxAbs = Math.max(maxAbs, Math.abs(xyz[2]));
            }
        }
        // Short.MAX_VALUE = 32767, COORD_SCALE = 16, so proper range is ~TILE_SCALE_MAX ~= 4096
        // Globe coords are ECEF-relative and should be in a similar range
        Assert.assertTrue("Coords should fit in short range, got max=" + maxAbs,
                maxAbs < 32767f / 16f); // roughly 2048
    }

    // ── getSphereRadius ──

    @Test
    public void getSphereRadius_returnsConfiguredValue() {
        Assert.assertEquals(SPHERE_RADIUS, projection.getSphereRadius(), EPSILON);
    }

    @Test
    public void getSphereRadius_customRadius() {
        GlobeTerrainProjection p = new GlobeTerrainProjection(8192.0f);
        Assert.assertEquals(8192.0f, p.getSphereRadius(), EPSILON);
    }

    // ── getTileCenterECEF ──

    @Test
    public void getTileCenterECEF_equator_returnsCorrectECEF() {
        float[] ecef = new float[3];
        projection.getTileCenterECEF(0.0, 0.0, ecef);
        Assert.assertEquals(SPHERE_RADIUS, ecef[0], EPSILON);
        Assert.assertEquals(0f, ecef[1], EPSILON);
        Assert.assertEquals(0f, ecef[2], EPSILON);
    }

    // ── metersToRadius ──

    @Test
    public void getMetersToRadius_matchesRatio() {
        float ratio = projection.getMetersToRadius();
        Assert.assertEquals(SPHERE_RADIUS / TerrainProjection.EARTH_RADIUS_METERS,
                ratio, 0.00001f);
    }

    // ── Mercator fallback methods ──

    @Test
    public void lonToWorldX_delegatesToMercator() {
        // For compat, globe projection delegates to standard Mercator for lon/lat pixel methods
        long mapSize = Tile.SIZE * (1L << 10);
        double x = projection.lonToWorldX(0, mapSize);
        Assert.assertEquals(mapSize / 2.0, x, 0.001);
    }

    @Test
    public void latToWorldY_delegatesToMercator() {
        long mapSize = Tile.SIZE * (1L << 10);
        double y = projection.latToWorldY(0, mapSize);
        Assert.assertEquals(mapSize / 2.0, y, 0.001);
    }
}

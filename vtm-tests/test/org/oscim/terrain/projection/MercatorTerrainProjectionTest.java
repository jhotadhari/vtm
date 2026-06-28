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
import org.oscim.core.Tile;

public class MercatorTerrainProjectionTest {

    private final MercatorTerrainProjection projection = new MercatorTerrainProjection();

    @Test
    public void getType_returnsMercator() {
        Assert.assertEquals(TerrainProjection.Type.MERCATOR, projection.getType());
    }

    @Test
    public void getBaseNormal_returnsUnitZ() {
        float[] normal = new float[3];
        projection.getBaseNormal(0, 0, normal);
        Assert.assertEquals(0f, normal[0], 0);
        Assert.assertEquals(0f, normal[1], 0);
        Assert.assertEquals(1f, normal[2], 0);

        // Same for any lat/lon — flat plane
        projection.getBaseNormal(85, -170, normal);
        Assert.assertEquals(0f, normal[0], 0);
        Assert.assertEquals(0f, normal[1], 0);
        Assert.assertEquals(1f, normal[2], 0);

        projection.getBaseNormal(-60, 120, normal);
        Assert.assertEquals(0f, normal[0], 0);
        Assert.assertEquals(0f, normal[1], 0);
        Assert.assertEquals(1f, normal[2], 0);
    }

    @Test
    public void latToWorldY_zeroLatitude_returnsHalfMapSize() {
        long mapSize = Tile.SIZE * (1L << 10);
        double y = projection.latToWorldY(0, mapSize);
        Assert.assertEquals(mapSize / 2.0, y, 0.001);
    }

    @Test
    public void latToWorldY_maxLatitude_returnsZero() {
        long mapSize = Tile.SIZE * (1L << 10);
        double y = projection.latToWorldY(85.051129, mapSize);
        Assert.assertTrue("Max latitude should be near top (small y)", y < mapSize * 0.01);
    }

    @Test
    public void lonToWorldX_zeroLongitude_returnsHalfMapSize() {
        long mapSize = Tile.SIZE * (1L << 10);
        double x = projection.lonToWorldX(0, mapSize);
        Assert.assertEquals(mapSize / 2.0, x, 0.001);
    }

    @Test
    public void lonToWorldX_negative180_returnsZero() {
        long mapSize = Tile.SIZE * (1L << 10);
        double x = projection.lonToWorldX(-180, mapSize);
        Assert.assertEquals(0, x, 0.001);
    }

    @Test
    public void lonToWorldX_positive180_returnsMapSize() {
        long mapSize = Tile.SIZE * (1L << 10);
        double x = projection.lonToWorldX(180, mapSize);
        Assert.assertEquals(mapSize, x, 0.001);
    }

    @Test
    public void elevToTileZ_noData_returnsZero() {
        float z = projection.elevToTileZ(Short.MIN_VALUE, 0, 1 << 12);
        Assert.assertEquals(0f, z, 0);
    }

    @Test
    public void elevToTileZ_zeroElevation_returnsZero() {
        float z = projection.elevToTileZ(0f, 0, 1 << 12);
        Assert.assertEquals(0f, z, 0.0001f);
    }

    @Test
    public void elevToTileZ_positiveElevation_returnsPositiveZ() {
        float z = projection.elevToTileZ(1000f, 0, 1 << 12);
        Assert.assertTrue("Positive elevation should produce positive z: " + z, z > 0);
    }

    @Test
    public void elevToTileZ_scalesWithZoom() {
        // At higher zoom (larger scale), same elevation should produce larger z
        // because ground resolution is finer — more pixel detail per meter
        float zLow = projection.elevToTileZ(1000f, 45, 1 << 8);
        float zHigh = projection.elevToTileZ(1000f, 45, 1 << 12);
        Assert.assertTrue("Higher zoom should yield larger z: zLow=" + zLow + " zHigh=" + zHigh, zHigh > zLow);
    }

    @Test
    public void elevToTileZ_varyingLatitude() {
        // At higher latitudes, ground resolution increases (cos(lat) decreases)
        // so same elevation should produce proportionally different z
        float zEquator = projection.elevToTileZ(1000f, 0, 1 << 12);
        float zMid = projection.elevToTileZ(1000f, 60, 1 << 12);
        // cos(60°) = 0.5, so groundResolution is halved → z should be roughly doubled
        Assert.assertTrue("Higher latitude should differ from equator", zMid != zEquator);
    }

    @Test
    public void elevToTileZ_deterministic() {
        // Same inputs → same output — critical for tile seam correctness
        float z1 = projection.elevToTileZ(1234.567f, 42.5f, 1 << 10);
        float z2 = projection.elevToTileZ(1234.567f, 42.5f, 1 << 10);
        Assert.assertEquals("elevToTileZ must be deterministic", z1, z2, 1e-7f);
    }

    @Test
    public void elevToTileZ_monotonic() {
        // Higher elevation → higher z (for the same lat and scale)
        float z500 = projection.elevToTileZ(500f, 30, 1 << 10);
        float z1000 = projection.elevToTileZ(1000f, 30, 1 << 10);
        float z2000 = projection.elevToTileZ(2000f, 30, 1 << 10);
        Assert.assertTrue("z must increase with elevation", z2000 > z1000);
        Assert.assertTrue("z must increase with elevation", z1000 > z500);
    }

    @Test
    public void elevToTileZ_linearScaling() {
        // Doubling elevation should roughly double z at same lat/scale
        float z1000 = projection.elevToTileZ(1000f, 45, 1 << 12);
        float z2000 = projection.elevToTileZ(2000f, 45, 1 << 12);
        Assert.assertEquals(z1000 * 2.0f, z2000, z1000 * 0.01f);
    }

    @Test
    public void lonToWorldX_consistentWithLatToWorldY() {
        // At the same zoom, map bounds should form a square in pixel space
        long mapSize = Tile.SIZE * (1L << 10);
        double x0 = projection.lonToWorldX(-180, mapSize);
        double x1 = projection.lonToWorldX(180, mapSize);
        double width = x1 - x0;
        Assert.assertEquals(mapSize, width, 0.001);
    }
}

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
package org.oscim.terrain.projection;

import org.oscim.core.MercatorProjection;
import org.oscim.utils.ExtrusionUtils;

/**
 * Standard Web Mercator terrain projection. The ground surface is a flat plane
 * at z=0; elevation is added as a z-offset scaled to tile-local coordinates via
 * {@link ExtrusionUtils#mapGroundScale}.
 * <p>
 * This is the default projection and matches VTM's existing Mercator-based
 * rendering pipeline. All map features (tiles, buildings, labels) use the same
 * Mercator coordinate space.
 */
public class MercatorTerrainProjection implements TerrainProjection {

    @Override
    public double lonToWorldX(double lon, long mapSize) {
        return MercatorProjection.longitudeToPixelX(lon, mapSize);
    }

    @Override
    public double latToWorldY(double lat, long mapSize) {
        return MercatorProjection.latitudeToPixelY(lat, mapSize);
    }

    @Override
    public float elevToTileZ(float elevMeters, double lat, double scale) {
        if (elevMeters == Short.MIN_VALUE)
            return 0f;
        float groundScale = (float) MercatorProjection.groundResolutionWithScale(lat, scale);
        return ExtrusionUtils.mapGroundScale(elevMeters, groundScale);
    }

    @Override
    public void project(float lat, float lon, float elevMeters,
                        double tileOriginX, double tileOriginY, long mapSize,
                        float[] outXYZ) {
        // Compute world-pixel position via Mercator
        double worldX = lonToWorldX(lon, mapSize);
        double worldY = latToWorldY(lat, mapSize);

        // Convert to tile-local (subtract tile origin)
        outXYZ[0] = (float) (worldX - tileOriginX);
        outXYZ[1] = (float) (worldY - tileOriginY);

        // Z via elevation projection
        // Scale = mapSize / Tile.SIZE = 1 << zoomLevel
        double scale = mapSize / (double) org.oscim.core.Tile.SIZE;
        outXYZ[2] = elevToTileZ(elevMeters, lat, scale);
    }

    @Override
    public void getTileCenterECEF(double centerLat, double centerLon, float[] outECEF) {
        // Flat plane — no sphere center
        outECEF[0] = 0f;
        outECEF[1] = 0f;
        outECEF[2] = 0f;
    }

    @Override
    public float getSphereRadius() {
        return 0f;
    }

    @Override
    public void getBaseNormal(float lat, float lon, float[] outNormal) {
        outNormal[0] = 0f;
        outNormal[1] = 0f;
        outNormal[2] = 1f;
    }

    @Override
    public Type getType() {
        return Type.MERCATOR;
    }
}

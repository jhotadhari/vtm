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

/**
 * Globe/spherical terrain projection. Maps geographic coordinates (lat, lon, elevation)
 * to ECEF (Earth-Centered, Earth-Fixed) positions on a sphere, relative to the tile
 * center. Elevation is applied as a radial offset from the sphere surface.
 * <p>
 * The sphere radius is configurable via the constructor. The default value of 4096
 * matches the existing tile coordinate scale ({@code Tile.SIZE * COORD_SCALE}),
 * keeping vertex data within the 16-bit short range used by the rendering pipeline.
 * <p>
 * Thread-safe — all methods are stateless aside from the immutable sphere radius.
 *
 * @see MercatorTerrainProjection for the flat-plane alternative
 */
public class GlobeTerrainProjection implements TerrainProjection {

    /** Rendering-space radius of the globe. */
    private final float mSphereRadius;

    /** Scale factor: meters of elevation → rendering-space radial offset. */
    private final float mMetersToRadius;

    /**
     * Creates a globe projection with the given sphere radius.
     *
     * @param sphereRadius rendering-space radius of the globe (e.g. 4096.0)
     */
    public GlobeTerrainProjection(float sphereRadius) {
        mSphereRadius = sphereRadius;
        mMetersToRadius = sphereRadius / EARTH_RADIUS_METERS;
    }

    /**
     * Creates a globe projection with default radius 4096.0.
     */
    public GlobeTerrainProjection() {
        this(4096.0f);
    }

    // ─────────────────────────────────────────────
    // Per-axis methods — not used directly by globe mesh gen,
    // but implement for interface completeness
    // ─────────────────────────────────────────────

    @Override
    public double lonToWorldX(double lon, long mapSize) {
        // Fallback to Mercator for compat — not used by globe mesh path
        return org.oscim.core.MercatorProjection.longitudeToPixelX(lon, mapSize);
    }

    @Override
    public double latToWorldY(double lat, long mapSize) {
        return org.oscim.core.MercatorProjection.latitudeToPixelY(lat, mapSize);
    }

    @Override
    public float elevToTileZ(float elevMeters, double lat, double scale) {
        if (elevMeters == Short.MIN_VALUE)
            return 0f;
        // Convert elevation meters to radial offset in rendering units
        return elevMeters * mMetersToRadius;
    }

    // ─────────────────────────────────────────────
    // Full 3D projection
    // ─────────────────────────────────────────────

    @Override
    public void project(float lat, float lon, float elevMeters,
                        double tileOriginX, double tileOriginY, long mapSize,
                        float[] outXYZ) {
        // Compute tile center geographic position from Mercator origin
        double scale = mapSize / (double) org.oscim.core.Tile.SIZE;
        double centerLat = org.oscim.core.MercatorProjection.pixelYToLatitude(
                tileOriginY + org.oscim.core.Tile.SIZE / 2.0, mapSize);
        double centerLon = org.oscim.core.MercatorProjection.pixelXToLongitude(
                tileOriginX + org.oscim.core.Tile.SIZE / 2.0, mapSize);

        // Compute tile center ECEF
        float[] centerECEF = new float[3];
        latLonToECEF((float) centerLat, (float) centerLon, mSphereRadius, centerECEF);

        // Compute vertex ECEF
        float vertexRadius = mSphereRadius;
        if (elevMeters != Short.MIN_VALUE) {
            vertexRadius += elevMeters * mMetersToRadius;
        }
        float[] vertexECEF = new float[3];
        latLonToECEF(lat, lon, vertexRadius, vertexECEF);

        // Tile-local = vertex ECEF - tile center ECEF
        outXYZ[0] = vertexECEF[0] - centerECEF[0];
        outXYZ[1] = vertexECEF[1] - centerECEF[1];
        outXYZ[2] = vertexECEF[2] - centerECEF[2];
    }

    @Override
    public void getTileCenterECEF(double centerLat, double centerLon, float[] outECEF) {
        latLonToECEF((float) centerLat, (float) centerLon, mSphereRadius, outECEF);
    }

    @Override
    public float getSphereRadius() {
        return mSphereRadius;
    }

    @Override
    public void getBaseNormal(float lat, float lon, float[] outNormal) {
        // Spherical surface normal = normalize(ECEF at radius 1)
        latLonToECEF(lat, lon, 1.0f, outNormal);
        // Already unit-length since cos²+sin²+sin² = 1 for unit sphere
    }

    @Override
    public Type getType() {
        return Type.GLOBE;
    }

    // ─────────────────────────────────────────────
    // Internal math
    // ─────────────────────────────────────────────

    /**
     * Converts geographic coordinates to ECEF (Earth-Centered, Earth-Fixed)
     * on a sphere of the given radius.
     * <p>
     * Coordinate system (right-handed):
     * <ul>
     * <li>+X = (lat=0°, lon=0°) — prime meridian at equator</li>
     * <li>+Y = (lat=0°, lon=90°E)</li>
     * <li>+Z = (lat=90°N) — north pole</li>
     * </ul>
     *
     * @param lat    latitude in degrees
     * @param lon    longitude in degrees
     * @param radius sphere radius (including elevation offset if any)
     * @param out    output array of length 3: [x, y, z]
     */
    public static void latLonToECEF(float lat, float lon, float radius, float[] out) {
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double cosLat = Math.cos(latRad);
        out[0] = (float) (radius * cosLat * Math.cos(lonRad));
        out[1] = (float) (radius * cosLat * Math.sin(lonRad));
        out[2] = (float) (radius * Math.sin(latRad));
    }

    /**
     * Returns the meters-to-radius scale factor for converting elevation
     * meters to rendering-space radial offsets.
     */
    public float getMetersToRadius() {
        return mMetersToRadius;
    }
}

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
 * Converts geographic coordinates (lat, lon, elevation) to rendering-space
 * coordinates for terrain mesh generation. This abstraction allows the terrain
 * system to support different map projections (Mercator, Globe, etc.) without
 * changing the mesh generation pipeline.
 * <p>
 * All implementations must be thread-safe — methods may be called from
 * multiple tile loader threads concurrently.
 */
public interface TerrainProjection {

    /**
     * The supported projection types.
     */
    enum Type {
        /** Standard Web Mercator — flat plane, elevation as z-offset. */
        MERCATOR,
        /** 3D globe — sphere/ellipsoid with elevation as radial offset. */
        GLOBE
    }

    /**
     * Converts a longitude to a world-pixel x-coordinate at the given map size.
     * The caller subtracts the tile origin to obtain tile-local coordinates.
     *
     * @param lon     longitude in degrees (WGS84)
     * @param mapSize the map size in pixels at the target zoom level
     *                (e.g., {@code Tile.SIZE * (1L << zoomLevel)})
     * @return world-pixel x coordinate
     */
    double lonToWorldX(double lon, long mapSize);

    /**
     * Converts a latitude to a world-pixel y-coordinate at the given map size.
     * The caller subtracts the tile origin to obtain tile-local coordinates.
     *
     * @param lat     latitude in degrees (WGS84)
     * @param mapSize the map size in pixels at the target zoom level
     * @return world-pixel y coordinate
     */
    double latToWorldY(double lat, long mapSize);

    /**
     * Converts an elevation in meters to tile-local z-units, accounting for
     * ground resolution. The caller multiplies by {@code COORD_SCALE} for
     * short encoding.
     *
     * @param elevMeters elevation above sea level in meters, or
     *                   {@link Short#MIN_VALUE} for no-data (returns 0)
     * @param lat        latitude in degrees (WGS84)
     * @param scale      the current map scale (e.g., {@code 1 << zoomLevel})
     * @return z value in tile-local units (ready for short encoding)
     */
    float elevToTileZ(float elevMeters, double lat, double scale);

    /**
     * Returns the base surface normal at a geographic position, before terrain
     * detail is applied. For Mercator this is always (0,0,1). For Globe this
     * varies with the sphere's surface normal.
     *
     * @param lat       latitude in degrees (WGS84)
     * @param lon       longitude in degrees (WGS84)
     * @param outNormal output array of length 3: [nx, ny, nz], unit-length
     */
    void getBaseNormal(float lat, float lon, float[] outNormal);

    /**
     * Returns the projection type.
     */
    Type getType();
}

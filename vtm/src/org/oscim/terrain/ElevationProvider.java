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

/**
 * Static holder for terrain elevation queries, bridging between the
 * {@code vtm-terrain} module (which owns the elevation data) and the
 * {@code vtm} module (which hosts building/label/road layers).
 * <p>
 * Set once by the terrain module during {@code TerrainTileLayer}
 * construction. Layers that need terrain-aware positioning call
 * {@link #isAvailable()} and {@link #getSampler()} to query elevation
 * without depending on {@code vtm-terrain} directly.
 */
public class ElevationProvider {

    /** Interface for sampling terrain elevation at arbitrary positions. */
    public interface Sampler {
        /** Returns elevation in meters at (lat,lon), or NaN if no data is available. */
        float getElevation(float lat, float lon);

        /** Converts elevation in meters to tile-local Z units for vertex buffers
         *  (flat Mercator ground scale). */
        float metersToTileZ(float meters, double lat, double scale);

        /**
         * Converts elevation in meters to a globe radial offset in rendering units.
         * For flat Mercator this returns the same as {@link #metersToTileZ}.
         * For globe this returns {@code meters / EARTH_RADIUS * sphereRadius}.
         */
        float metersToTileZGlobe(float meters);
    }

    private static volatile Sampler sInstance;

    /** Registers the elevation sampler. Called from the terrain module. */
    public static void set(Sampler sampler) {
        sInstance = sampler;
    }

    /** Returns the registered sampler, or null if terrain is not active. */
    public static Sampler getSampler() {
        return sInstance;
    }

    /** Returns true if a terrain elevation sampler is available. */
    public static boolean isAvailable() {
        return sInstance != null;
    }
}

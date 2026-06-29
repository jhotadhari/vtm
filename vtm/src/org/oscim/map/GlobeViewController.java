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
package org.oscim.map;

import org.oscim.core.GeoPoint;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Point;
import org.oscim.renderer.GLMatrix;
import org.oscim.utils.FastMath;

/**
 * Orbital camera controller for globe (spherical) map rendering.
 * Replaces the flat-plane {@link ViewController} when the map is in
 * globe projection mode.
 * <p>
 * The camera orbits a sphere centered at the world origin (0, 0, 0).
 * The orbit is defined by spherical angles (lat, lon) and a distance
 * from the sphere center. Zoom maps to camera distance, and panning
 * rotates the globe.
 * <p>
 * Touch/gesture mapping:
 * <ul>
 * <li>Drag → orbit around sphere (modifies orbitLat/orbitLon)</li>
 * <li>Scroll/pinch → change camera distance (zoom)</li>
 * <li>Tilt → change orbit elevation angle</li>
 * </ul>
 */
public class GlobeViewController extends ViewController {

    /** Default sphere radius in rendering units. */
    private static final float DEFAULT_SPHERE_RADIUS = 4096.0f;

    /** Near plane distance. */
    private static final float GLOBE_VIEW_NEAR = 10f;

    /** Far plane distance — must see the entire sphere from orbit. */
    private static final float GLOBE_VIEW_FAR = 30000f;

    /** Camera distance at minimum zoom (fully zoomed out). */
    private static final float DISTANCE_FAR = DEFAULT_SPHERE_RADIUS * 4.0f;

    /** Camera distance at maximum zoom (fully zoomed in). */
    private static final float DISTANCE_NEAR = DEFAULT_SPHERE_RADIUS * 1.02f;

    /** Sphere radius in rendering units. */
    private final float mSphereRadius;

    private final float[] mMatTemp = new float[16];
    private final float[] mCameraPos = new float[3];

    public GlobeViewController() {
        this(DEFAULT_SPHERE_RADIUS);
    }

    public GlobeViewController(float sphereRadius) {
        mSphereRadius = sphereRadius;
        // Override parent limits for globe mode
        mMaxTilt = 80;   // Camera can look down more
        mMinTilt = 10;   // Minimum elevation above horizon
    }

    // ─────────────────────────────────────────────
    // Viewport setup
    // ─────────────────────────────────────────────

    @Override
    public void setViewSize(int width, int height) {
        mHeight = height;
        mWidth = width;

        float ratio = (mHeight / mWidth) * VIEW_SCALE;

        // Perspective frustum — wider near/far for globe distances
        GLMatrix.frustumM(mMatTemp, 0,
                -VIEW_SCALE, VIEW_SCALE,
                ratio, -ratio,
                GLOBE_VIEW_NEAR, GLOBE_VIEW_FAR);

        mProjMatrix.set(mMatTemp);

        // Scale to window coordinates
        mTmpMatrix.setScale(1 / mWidth, 1 / mWidth, 1 / mWidth);
        mProjMatrix.multiplyRhs(mTmpMatrix);

        // Inverse projection
        mProjMatrix.get(mMatTemp);
        GLMatrix.invertM(mMatTemp, 0, mMatTemp, 0);
        mProjMatrixInverse.set(mMatTemp);

        mProjMatrixUnscaled.copy(mProjMatrix);

        updateMatrices();
    }

    // ─────────────────────────────────────────────
    // Camera position from orbit parameters
    // ─────────────────────────────────────────────

    /**
     * Returns the camera ECEF position for the current orbit state.
     * Camera orbits the sphere center (0,0,0) at a distance determined
     * by the current zoom scale.
     */
    private void getCameraPosition(float[] out) {
        double cameraDistance = getCameraDistance();

        // Orbit angles from MapPosition
        // x in [0,1] maps to orbit longitude [0, 360]
        // y in [0,1] maps to orbit latitude [-MAX_LAT, MAX_LAT]
        double orbitLon = mPos.x * 360.0;
        double orbitLat = (mPos.y - 0.5) * 2.0 * 70.0; // ±70° range
        double orbitLatRad = Math.toRadians(orbitLat);
        double orbitLonRad = Math.toRadians(orbitLon);

        double cosLat = Math.cos(orbitLatRad);
        out[0] = (float) (cameraDistance * cosLat * Math.cos(orbitLonRad));
        out[1] = (float) (cameraDistance * cosLat * Math.sin(orbitLonRad));
        out[2] = (float) (cameraDistance * Math.sin(orbitLatRad));
    }

    /**
     * Converts zoom scale to camera distance from sphere center.
     * Low zoom (zoomed out) → far distance. High zoom → near distance.
     */
    private double getCameraDistance() {
        // Map scale range [mMinScale, mMaxScale] to distance range [DISTANCE_FAR, DISTANCE_NEAR]
        double t = (mPos.scale - mMinScale) / (mMaxScale - mMinScale);
        t = FastMath.clamp(t, 0.0, 1.0);
        return DISTANCE_FAR - t * (DISTANCE_FAR - DISTANCE_NEAR);
    }

    // ─────────────────────────────────────────────
    // View/projection matrices
    // ─────────────────────────────────────────────

    @Override
    protected void updateMatrices() {
        // Camera position in world space (sphere center = origin)
        getCameraPosition(mCameraPos);

        // Look-at point: sphere center (0, 0, 0) with tilt/bearing offset
        float centerX = 0f;
        float centerY = 0f;
        float centerZ = 0f;

        // Up vector: world Z (north pole direction)
        float upX = 0f;
        float upY = 0f;
        float upZ = 1f;

        // Build view matrix using lookAt
        GLMatrix.lookAt(mMatTemp, 0,
                mCameraPos[0], mCameraPos[1], mCameraPos[2],
                centerX, centerY, centerZ,
                upX, upY, upZ);

        mViewMatrix.set(mMatTemp);

        // Apply bearing (rotation around look-at axis) and tilt
        if (mPos.bearing != 0) {
            mRotationMatrix.setRotation(mPos.bearing, 0, 0, 1);
            mViewMatrix.multiplyLhs(mRotationMatrix);
        }

        // Apply pivot offset (screen-space pan pivot)
        mTmpMatrix.setTranslation(mPivotX * mWidth, mPivotY * mHeight, 0);
        mViewMatrix.multiplyLhs(mTmpMatrix);

        // Build view-projection matrix
        mViewProjMatrix.multiplyMM(mProjMatrix, mViewMatrix);

        // Inverse for unproject
        mViewProjMatrix.get(mMatTemp);
        GLMatrix.invertM(mMatTemp, 0, mMatTemp, 0);
        mUnprojMatrix.set(mMatTemp);
    }

    // ─────────────────────────────────────────────
    // Navigation — map to orbit controls
    // ─────────────────────────────────────────────

    @Override
    public synchronized void moveMap(float mx, float my) {
        // Pan = orbit the globe
        double orbitSensitivity = 0.5 / mPos.scale;
        mPos.x -= mx * orbitSensitivity;
        mPos.y -= my * orbitSensitivity;
        clampPosition();
        updateMatrices();
    }

    @Override
    public boolean tiltMap(float move) {
        // Tilt adjusts the orbit latitude / elevation
        double newTilt = mPos.tilt + move;
        if (newTilt > mMaxTilt || newTilt < mMinTilt)
            return false;
        mPos.tilt = (float) newTilt;
        // Tilt modifies the orbit elevation: higher tilt = lower camera
        updateMatrices();
        return true;
    }

    @Override
    public boolean scaleMap(float scale, float pivotX, float pivotY) {
        if (scale < 0.000001)
            return false;
        double newScale = mPos.scale * scale;
        newScale = FastMath.clamp(newScale, mMinScale, mMaxScale);
        if (newScale == mPos.scale)
            return false;
        mPos.scale = newScale;
        mPos.zoomLevel = FastMath.log2((int) mPos.scale);
        updateMatrices();
        return true;
    }

    @Override
    public void rotateMap(double radians, float pivotX, float pivotY) {
        double degrees = Math.toDegrees(radians);
        mPos.bearing = (float) FastMath.clampDegree(mPos.bearing + degrees);
        updateMatrices();
    }

    @Override
    public void setRotation(double degree) {
        mPos.bearing = (float) FastMath.clampDegree(degree);
        updateMatrices();
    }

    private void clampPosition() {
        mPos.x = FastMath.clamp(mPos.x, 0, 1);
        mPos.y = FastMath.clamp(mPos.y, 0, 1);
        while (mPos.x > 1) mPos.x -= 1;
        while (mPos.x < 0) mPos.x += 1;
    }

    // ─────────────────────────────────────────────
    // Unproject — ray-sphere intersection
    // ─────────────────────────────────────────────

    @Override
    protected synchronized void unproject(float x, float y, float[] coords, int position) {
        // Get near-plane point
        mv[0] = x;
        mv[1] = y;
        mv[2] = -1;
        mUnprojMatrix.prj(mv);
        float nx = mv[0];
        float ny = mv[1];
        float nz = mv[2];

        // Get far-plane point
        mv[0] = x;
        mv[1] = y;
        mv[2] = 1;
        mUnprojMatrix.prj(mv);
        float fx = mv[0];
        float fy = mv[1];
        float fz = mv[2];

        // Ray direction from near to far
        float dx = fx - nx;
        float dy = fy - ny;
        float dz = fz - nz;

        // Ray origin = near point, ray direction = far - near
        // Ray-sphere intersection: |origin + t * dir|^2 = R^2
        // Solve: a*t^2 + b*t + c = 0
        // where a = |dir|^2, b = 2*dot(origin, dir), c = |origin|^2 - R^2
        float a = dx * dx + dy * dy + dz * dz;
        float b = 2f * (nx * dx + ny * dy + nz * dz);
        float c = nx * nx + ny * ny + nz * nz - mSphereRadius * mSphereRadius;

        float disc = b * b - 4f * a * c;

        if (disc < 0 || a < 0.0001f) {
            // No intersection — ray misses sphere. Fall back to near-plane.
            coords[position + 0] = nx;
            coords[position + 1] = ny;
            return;
        }

        // Nearest positive intersection
        float sqrtDisc = (float) Math.sqrt(disc);
        float t0 = (-b - sqrtDisc) / (2f * a);
        float t1 = (-b + sqrtDisc) / (2f * a);

        float t = (t0 > 0) ? t0 : t1;
        if (t < 0) {
            // Camera inside sphere? Use far intersection.
            t = t1;
        }

        // Intersection point in world (ECEF) space
        float ix = nx + t * dx;
        float iy = ny + t * dy;
        float iz = nz + t * dz;

        // Convert ECEF intersection to Mercator [0,1] space for MapPosition compat
        float lat = (float) Math.toDegrees(Math.asin(
                FastMath.clamp(iz / mSphereRadius, -1.0, 1.0)));
        float lon = (float) Math.toDegrees(Math.atan2(iy, ix));

        coords[position + 0] = (float) MercatorProjection.longitudeToX(lon);
        coords[position + 1] = (float) MercatorProjection.latitudeToY(lat);
    }

    // ─────────────────────────────────────────────
    // Screen coordinate helpers
    // ─────────────────────────────────────────────

    @Override
    public synchronized void fromScreenPoint(double x, double y, Point out) {
        unprojectScreen(x, y, mu);

        // mu contains Mercator [0,1] coordinates from unproject
        out.x = mu[0];
        out.y = mu[1];
    }

    @Override
    public synchronized GeoPoint fromScreenPoint(float x, float y) {
        fromScreenPoint(x, y, mMovePoint);
        return new GeoPoint(
                MercatorProjection.toLatitude(mMovePoint.y),
                MercatorProjection.toLongitude(mMovePoint.x));
    }

    // ─────────────────────────────────────────────
    // Getters
    // ─────────────────────────────────────────────

    /** Returns the sphere radius used by this controller. */
    public float getSphereRadius() {
        return mSphereRadius;
    }
}

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
import org.oscim.core.Tile;
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

    /** Vertical field of view in degrees. 45° comfortably shows the globe. */
    private static final float GLOBE_FOV_Y = 45f;

    /** Camera distance multiplier at minimum zoom (fully zoomed out). */
    private static final float DISTANCE_FAR_MULTIPLIER = 4.0f;

    /** Camera distance multiplier at maximum zoom (fully zoomed in). */
    private static final float DISTANCE_NEAR_MULTIPLIER = 1.02f;

    /** Sphere radius in rendering units. */
    private final float mSphereRadius;

    /** Camera distance at minimum zoom — computed from mSphereRadius. */
    private final float mDistanceFar;

    /** Camera distance at maximum zoom — computed from mSphereRadius. */
    private final float mDistanceNear;

    private final float[] mMatTemp = new float[16];
    private final float[] mCameraPos = new float[3];

    /** Last known view width for re-application after setGlobeMode. */
    private int mLastWidth;

    /** Last known view height for re-application after setGlobeMode. */
    private int mLastHeight;

    public GlobeViewController() {
        this(DEFAULT_SPHERE_RADIUS);
    }

    public GlobeViewController(float sphereRadius) {
        mSphereRadius = sphereRadius;
        mDistanceFar = sphereRadius * DISTANCE_FAR_MULTIPLIER;
        mDistanceNear = sphereRadius * DISTANCE_NEAR_MULTIPLIER;
        // Override parent limits for globe mode
        mMaxTilt = 80;   // Camera can look down more
        mMinTilt = 10;   // Minimum elevation above horizon
    }

    // ─────────────────────────────────────────────
    // Viewport setup
    // ─────────────────────────────────────────────

    @Override
    public void setViewSize(int width, int height) {
        mLastWidth = width;
        mLastHeight = height;
        mHeight = height;
        mWidth = width;

        // Build a proper perspective frustum with a configurable FOV.
        // The flat-map VIEW_SCALE was designed for a camera 3 units from
        // the Mercator plane — useless for a 3D globe.
        float aspect = mWidth / mHeight;
        float tanHalfFovY = (float) Math.tan(Math.toRadians(GLOBE_FOV_Y / 2.0f));
        float top = tanHalfFovY * GLOBE_VIEW_NEAR;
        float bottom = -top;
        float right = top * aspect;
        float left = -right;

        GLMatrix.frustumM(mMatTemp, 0,
                left, right,
                bottom, top,
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

    /**
     * Returns true if setViewSize has been called at least once
     * (i.e., the projection matrices have been built).
     */
    public boolean isViewSized() {
        return mLastWidth > 0 && mLastHeight > 0;
    }

    // ─────────────────────────────────────────────
    // Camera position from orbit parameters
    // ─────────────────────────────────────────────

    /**
     * Returns the camera ECEF position for the current orbit state.
     * The camera is positioned above the geographic point the user was
     * looking at in flat mode: mPos.x/y (Mercator [0,1]) map to the
     * longitude/latitude of the target point on the sphere surface.
     * The camera orbits above that point at a distance determined by zoom.
     */
    private void getCameraPosition(float[] out) {
        double cameraDistance = getCameraDistance();

        // MapPosition x,y in Mercator [0,1] → geographic position of
        // the point the user was centered on in flat mode.
        double targetLon = MercatorProjection.toLongitude(mPos.x);
        double targetLat = MercatorProjection.toLatitude(mPos.y);

        // Camera orbits above the target point, pushed out to cameraDistance
        // from the sphere center (along the surface normal at that point)
        double latRad = Math.toRadians(targetLat);
        double lonRad = Math.toRadians(targetLon);
        double cosLat = Math.cos(latRad);
        out[0] = (float) (cameraDistance * cosLat * Math.cos(lonRad));
        out[1] = (float) (cameraDistance * cosLat * Math.sin(lonRad));
        out[2] = (float) (cameraDistance * Math.sin(latRad));
    }

    /**
     * Converts zoom scale to camera distance from sphere center.
     * Low zoom (zoomed out) → far distance. High zoom → near distance.
     */
    private double getCameraDistance() {
        // Map scale range [mMinScale, mMaxScale] to distance range [mDistanceFar, mDistanceNear]
        double t = (mPos.scale - mMinScale) / (mMaxScale - mMinScale);
        t = FastMath.clamp(t, 0.0, 1.0);
        return mDistanceFar - t * (mDistanceFar - mDistanceNear);
    }

    // ─────────────────────────────────────────────
    // View/projection matrices
    // ─────────────────────────────────────────────

    @Override
    protected void updateMatrices() {
        // Camera position in world space (sphere center = origin)
        getCameraPosition(mCameraPos);

        // Look-at point: sphere center (0, 0, 0)
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

        // Apply bearing (rotation around look-at axis)
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

    /**
     * Overrides parent moveTo to call updateMatrices after position change.
     * The parent moveTo only mutates mPos and does not refresh the view
     * matrix, which is required for globe mode (camera position depends on mPos).
     */
    @Override
    void moveTo(double x, double y) {
        mPos.x = x;
        mPos.y = y;

        /* clamp latitude */
        mPos.y = FastMath.clamp(mPos.y, 0, 1);

        /* wrap longitude */
        while (mPos.x > 1)
            mPos.x -= 1;
        while (mPos.x < 0)
            mPos.x += 1;

        /* limit longitude */
        if (mPos.x > mMaxX)
            mPos.x = mMaxX;
        else if (mPos.x < mMinX)
            mPos.x = mMinX;
        /* limit latitude */
        if (mPos.y > mMaxY)
            mPos.y = mMaxY;
        else if (mPos.y < mMinY)
            mPos.y = mMinY;

        // Globe: camera position depends on mPos, refresh view matrix
        updateMatrices();
    }

    @Override
    public synchronized void moveMap(float mx, float my) {
        // Counter-rotate pan vector by map bearing
        ViewController.applyRotation(mx, my, mPos.bearing, mMovePoint);

        // 1 screen pixel → Mercator [0,1] offset.
        // scale = 2^zoom, Tile.SIZE pixels per tile at current zoom.
        double mercatorPerPixel = 1.0 / (mPos.scale * Tile.SIZE);

        // For globe, a full-width drag should orbit ~half the visible disc.
        // The visible Mercator span at this zoom is roughly the screen width
        // in pixels times mercatorPerPixel. So direct pixel→Mercator mapping
        // gives natural-feeling pan speed.
        mPos.x += mMovePoint.x * mercatorPerPixel;
        mPos.y += mMovePoint.y * mercatorPerPixel;

        mPos.y = FastMath.clamp(mPos.y, 0.0, 1.0);
        while (mPos.x > 1) mPos.x -= 1;
        while (mPos.x < 0) mPos.x += 1;

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

        // Save old scale for pivot compensation
        double oldScale = mPos.scale;
        mPos.scale = newScale;
        mPos.zoomLevel = FastMath.log2((int) mPos.scale);

        // Adjust orbit position so zoom centers on the pivot point.
        // Same formula as parent ViewController.scaleMap.
        if (pivotX != 0 || pivotY != 0) {
            pivotX -= mWidth * mPivotX;
            pivotY -= mHeight * mPivotY;
            // At old scale, the pixel displacement corresponds to a Mercator
            // displacement. At new scale, the same screen position needs
            // a different Mercator position. Adjust to keep pivot stationary.
            double mercatorPerPixel = 1.0 / (oldScale * Tile.SIZE);
            mPos.x += pivotX * (1.0f - scale) * mercatorPerPixel;
            mPos.y += pivotY * (1.0f - scale) * mercatorPerPixel;
            mPos.y = FastMath.clamp(mPos.y, 0.0, 1.0);
        }
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
            // No intersection — ray misses sphere. Return sentinel values
            // so callers can detect the miss (NaN signals no hit).
            coords[position + 0] = Float.NaN;
            coords[position + 1] = Float.NaN;
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
    // Map extents — override for sphere intersection
    // ─────────────────────────────────────────────

    @Override
    public void getMapExtents(float[] box, float add) {
        // Sample a grid across the screen to find the sphere's visible extent.
        // The 4 screen corners may miss the sphere entirely when the FOV is
        // wider than the sphere's apparent size. We sample 25 points (5×5)
        // and build a Mercator bounding box from the sphere intersections.
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        int hits = 0;

        float[] coords = new float[2];
        int GRID = 11;
        for (int j = 0; j < GRID; j++) {
            float sy = 1.0f - j * (2.0f / (GRID - 1));
            for (int i = 0; i < GRID; i++) {
                float sx = -1.0f + i * (2.0f / (GRID - 1));
                unproject(sx, sy, coords, 0);
                if (!Float.isNaN(coords[0])) {
                    minX = Math.min(minX, coords[0]);
                    maxX = Math.max(maxX, coords[0]);
                    minY = Math.min(minY, coords[1]);
                    maxY = Math.max(maxY, coords[1]);
                    hits++;
                }
            }
        }

        if (hits == 0) {
            // No sphere visible — return empty extent
            for (int i = 0; i < 8; i++) box[i] = 0;
            return;
        }

        // Fill box: bottom-right, bottom-left, top-left, top-right
        box[0] = maxX; box[1] = minY;
        box[2] = minX; box[3] = minY;
        box[4] = minX; box[5] = maxY;
        box[6] = maxX; box[7] = maxY;

        if (add != 0) {
            for (int i = 0; i < 8; i += 2) {
                float cx = (minX + maxX) * 0.5f;
                float cy = (minY + maxY) * 0.5f;
                float dx = box[i] - cx;
                float dy = box[i+1] - cy;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 0) {
                    box[i]   += dx / len * add;
                    box[i+1] += dy / len * add;
                }
            }
        }
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

    /**
     * Projects a map coordinate to screen pixel position.
     * Overrides the flat-plane Viewport.toScreenPoint with a globe-aware
     * version that accounts for the sphere projection.
     */
    @Override
    public synchronized void toScreenPoint(double x, double y, boolean relativeToCenter, Point out) {
        // Convert Mercator [0,1] to geographic
        double lat = MercatorProjection.toLatitude(y);
        double lon = MercatorProjection.toLongitude(x);

        // Convert geographic to ECEF on the sphere surface
        double latRad = Math.toRadians(lat);
        double lonRad = Math.toRadians(lon);
        double cosLat = Math.cos(latRad);
        float wx = (float) (mSphereRadius * cosLat * Math.cos(lonRad));
        float wy = (float) (mSphereRadius * cosLat * Math.sin(lonRad));
        float wz = (float) (mSphereRadius * Math.sin(latRad));

        // Project through view-projection matrix
        mv[0] = wx;
        mv[1] = wy;
        mv[2] = wz;
        mv[3] = 1;

        mViewProjMatrix.prj(mv);

        out.x = (mv[0] * (mWidth / 2));
        out.y = -(mv[1] * (mHeight / 2));

        if (!relativeToCenter) {
            out.x += mWidth / 2;
            out.y += mHeight / 2;
        }
    }

    // ─────────────────────────────────────────────
    // Getters
    // ─────────────────────────────────────────────

    /** Returns the sphere radius used by this controller. */
    public float getSphereRadius() {
        return mSphereRadius;
    }

    /** Returns the last known view width (0 if setViewSize not yet called). */
    public int getLastWidth() {
        return mLastWidth;
    }

    /** Returns the last known view height (0 if setViewSize not yet called). */
    public int getLastHeight() {
        return mLastHeight;
    }
}

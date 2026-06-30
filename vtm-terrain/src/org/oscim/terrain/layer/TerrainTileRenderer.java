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
package org.oscim.terrain.layer;

import org.oscim.backend.CanvasAdapter;
import org.oscim.backend.GL;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.backend.canvas.Color;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Tile;
import org.oscim.layers.tile.MapTile;
import org.oscim.layers.tile.TileRenderer;
import org.oscim.renderer.ExtrusionRenderer;
import org.oscim.renderer.GLShader;
import org.oscim.renderer.GLState;
import org.oscim.renderer.GLUtils;
import org.oscim.renderer.GLViewport;
import org.oscim.renderer.MapRenderer;
import org.oscim.renderer.bucket.ExtrusionBucket;
import org.oscim.renderer.bucket.ExtrusionBuckets;
import org.oscim.renderer.bucket.RenderBuckets;
import org.oscim.renderer.bucket.TextureItem;
import org.oscim.renderer.light.Sun;
import org.oscim.terrain.TerrainUtils;
import org.oscim.terrain.projection.TerrainProjection;
import org.oscim.utils.FastMath;

import java.util.logging.Logger;

import static org.oscim.backend.GLAdapter.gl;
import static org.oscim.renderer.MapRenderer.COORD_SCALE;

/**
 * Tile renderer for 3D terrain meshes. Extends {@link TileRenderer} to leverage
 * the existing tile visibility tracking, and implements {@link #render} to draw
 * terrain meshes using the {@code extrusion_layer_mesh} shader with directional
 * lighting and depth-buffered rendering.
 * <p>
 * Terrain meshes are stored as {@link ExtrusionBuckets} on each tile's data
 * chain (keyed by {@link TerrainTileLayer}). This renderer compiles them
 * (VBO/IBO upload) in {@link #update} and draws them in {@link #render}.
 */
public class TerrainTileRenderer extends TileRenderer {

    private static final Logger log = Logger.getLogger(TerrainTileRenderer.class.getName());

    /** Light source position for terrain shading. */
    private final Sun mSun;

    /** Mesh shader (procedural color only). */
    private ExtrusionRenderer.Shader mShader;

    /** Mesh shader with texture support (for raster draping). */
    private TerrainTexShader mTexShader;

    /** Globe mesh shader (procedural color + atmosphere). */
    private GlobeShader mGlobeShader;

    /** Globe mesh shader with texture support. */
    private GlobeTexShader mGlobeTexShader;

    /** The current projection type. Set by TerrainTileLayer during construction. */
    private TerrainProjection.Type mProjectionType = TerrainProjection.Type.MERCATOR;

    /**
     * The terrain projection instance, used to query sphere radius and tile
     * center ECEF for globe rendering. Null for Mercator (flat plane).
     */
    private TerrainProjection mProjection;

    /** Pre-allocated atmosphere color for globe shader uniforms. */
    private static final float[] ATMOSPHERE_COLOR = {0.65f, 0.78f, 0.92f, 1.0f};

    /** Whether the shader has been initialized. */
    private boolean mInitialized;

    /**
     * If true (default), clear the depth buffer at the start of
     * {@link #render}. Set to false when a prior layer already owns
     * the depth buffer, to avoid overwriting its content.
     */
    private boolean mClearDepth = true;

    /** 1x1 white fallback texture for tiles without raster data. */
    private TextureItem mFallbackTex;

    /** Blend factor between procedural color and texture (0=color, 1=texture). */
    private float mTexMix = 0.8f;

    /** Number of visible terrain tiles in the current frame. */
    private int mTerrainCnt;
    private int mLastLogCnt = -1; // for debug logging
    private boolean mLoggedTexStatus; // one-time tex shader status diagnostic
    private boolean mLoggedGlobeFrame; // first globe frame diagnostic

    /** Camera position in ECEF for globe atmosphere/fog. Default {0,0,R*3}. */
    private final float[] mCamPosUniform = new float[3];

    /** Guard for diagnostic logging in the render hot path. */
    private static final boolean RENDER_DEBUG = false;

    /**
     * Shader variant that adds texture sampling on top of the terrain mesh
     * shader. Shares the same vertex attributes and core uniforms as
     * {@link ExtrusionRenderer.Shader}, with additional texture uniforms.
     */
    private static class TerrainTexShader extends GLShader {
        public int aPos, aNormal;
        public int uMVP, uColor, uAlpha, uMode, uZLimit, uLight;
        public int uTex, uTexMix;
        public int program;

        TerrainTexShader() {
            if (!createDirective("terrain_tex", null))
                return;
            program = super.program;
            uMVP = getUniform("u_mvp");
            uColor = getUniform("u_color");
            uAlpha = getUniform("u_alpha");
            uMode = getUniform("u_mode");
            uZLimit = getUniform("u_zlimit");
            aPos = getAttrib("a_pos");
            aNormal = getAttrib("a_normal");
            uLight = getUniform("u_light");
            uTex = getUniform("u_tex");
            uTexMix = getUniform("u_texMix");
        }

        void activate() {
            GLState.useProgram(program);
        }
    }

    /**
     * Globe mesh shader with atmosphere/fog. Uses terrain_globe.glsl.
     */
    private static class GlobeShader extends GLShader {
        public int aPos, aNormal;
        public int uMVP, uColor, uAlpha, uMode, uZLimit, uLight;
        public int uGlobeRadius;
        public int uTileLonMin, uTileLonRange, uTileLatMax, uTileLatRange;
        public int uCameraPos, uAtmosphereColor, uFogDensity;
        public int program;

        GlobeShader() {
            if (!createDirective("terrain_globe", null))
                return;
            program = super.program;
            uMVP = getUniform("u_mvp");
            uColor = getUniform("u_color");
            uAlpha = getUniform("u_alpha");
            uMode = getUniform("u_mode");
            uZLimit = getUniform("u_zlimit");
            aPos = getAttrib("a_pos");
            aNormal = getAttrib("a_normal");
            uLight = getUniform("u_light");
            uGlobeRadius = getUniform("u_globeRadius");
            uTileLonMin = getUniform("u_tileLonMin");
            uTileLonRange = getUniform("u_tileLonRange");
            uTileLatMax = getUniform("u_tileLatMax");
            uTileLatRange = getUniform("u_tileLatRange");
            uCameraPos = getUniform("u_cameraPos");
            uAtmosphereColor = getUniform("u_atmosphereColor");
            uFogDensity = getUniform("u_fogDensity");
        }

        void activate() {
            GLState.useProgram(program);
        }
    }

    /**
     * Globe mesh shader with texture sampling and atmosphere.
     * Uses terrain_globe_tex.glsl.
     */
    private static class GlobeTexShader extends GLShader {
        public int aPos, aNormal;
        public int uMVP, uColor, uAlpha, uMode, uZLimit, uLight;
        public int uTex, uTexMix;
        public int uGlobeRadius;
        public int uTileLonMin, uTileLonRange, uTileLatMax, uTileLatRange;
        public int uCameraPos, uAtmosphereColor, uFogDensity;
        public int program;

        GlobeTexShader() {
            if (!createDirective("terrain_globe_tex", null))
                return;
            program = super.program;
            uMVP = getUniform("u_mvp");
            uColor = getUniform("u_color");
            uAlpha = getUniform("u_alpha");
            uMode = getUniform("u_mode");
            uZLimit = getUniform("u_zlimit");
            aPos = getAttrib("a_pos");
            aNormal = getAttrib("a_normal");
            uLight = getUniform("u_light");
            uTex = getUniform("u_tex");
            uTexMix = getUniform("u_texMix");
            uGlobeRadius = getUniform("u_globeRadius");
            uTileLonMin = getUniform("u_tileLonMin");
            uTileLonRange = getUniform("u_tileLonRange");
            uTileLatMax = getUniform("u_tileLatMax");
            uTileLatRange = getUniform("u_tileLatRange");
            uCameraPos = getUniform("u_cameraPos");
            uAtmosphereColor = getUniform("u_atmosphereColor");
            uFogDensity = getUniform("u_fogDensity");
        }

        void activate() {
            GLState.useProgram(program);
        }
    }

    public TerrainTileRenderer() {
        super();
        mSun = new Sun();
    }

    /**
     * Sets whether this renderer should clear the depth buffer before rendering.
     * Default is {@code true}. Set to {@code false} when a prior layer already
     * owns the depth buffer (e.g. when another 3D layer renders before terrain).
     */
    public void setClearDepth(boolean clearDepth) {
        mClearDepth = clearDepth;
    }

    /** Returns whether this renderer clears the depth buffer before rendering. */
    public boolean getClearDepth() {
        return mClearDepth;
    }

    /** Sets the texture blend factor (0=procedural color, 1=texture). */
    public void setTexMix(float texMix) {
        mTexMix = texMix;
    }

    /** Returns the texture blend factor. */
    public float getTexMix() {
        return mTexMix;
    }

    /**
     * Sets the camera ECEF position for globe atmosphere/fog shading.
     * Call each frame from the ViewController to provide the actual
     * orbital camera position. If never called, defaults to {0,0,R*3}.
     */
    public void setCameraPosition(float x, float y, float z) {
        mCamPosUniform[0] = x;
        mCamPosUniform[1] = y;
        mCamPosUniform[2] = z;
    }

    /**
     * Sets the projection and its type. Must be called before the first
     * render frame. Determines which shader and matrix strategy to use.
     * <p>
     * When the projection type changes, old GL shader programs are deleted
     * to prevent resource leaks.
     */
    public void setProjection(TerrainProjection projection) {
        if (projection == null) {
            throw new IllegalArgumentException("projection must not be null");
        }
        TerrainProjection.Type newType = projection.getType();
        if (mProjectionType != newType) {
            // Delete old GL programs before switching
            deleteShaderPrograms();
            mProjectionType = newType;
            mInitialized = false; // force shader re-init
        }
        mProjection = projection;
    }

    /** Returns the current projection type. */
    public TerrainProjection.Type getProjectionType() {
        return mProjectionType;
    }

    /** Deletes any currently loaded GL shader programs to prevent leaks. */
    private void deleteShaderPrograms() {
        if (mTexShader != null && mTexShader.program > 0) {
            gl.deleteProgram(mTexShader.program);
            mTexShader = null;
        }
        if (mGlobeTexShader != null && mGlobeTexShader.program > 0) {
            gl.deleteProgram(mGlobeTexShader.program);
            mGlobeTexShader = null;
        }
        if (mGlobeShader != null && mGlobeShader.program > 0) {
            gl.deleteProgram(mGlobeShader.program);
            mGlobeShader = null;
        }
        if (mShader != null && mShader.getProgram() > 0) {
            gl.deleteProgram(mShader.getProgram());
            mShader = null;
        }
        if (mFallbackTex != null) {
            mFallbackTex.dispose();
            mFallbackTex = null;
        }
    }

    /**
     * Pairs an ExtrusionBuckets with its optional raster and vector
     * textures for use during rendering.
     */
    private static class TerrainTileData {
        ExtrusionBuckets buckets;
        TextureItem texture;        // raster tile texture
        TextureItem vectorTexture;  // vector drape texture
    }

    /** Pre-allocated tile data array for the current frame. */
    private TerrainTileData[] mTerrainTileData = new TerrainTileData[32];

    @Override
    public synchronized void update(GLViewport v) {
        // Let parent handle tile visibility tracking (compileTileLayers is no-op for terrain)
        super.update(v);

        // Update sun position relative to current map center
        float lat = (float) v.pos.getLatitude();
        float lon = (float) v.pos.getLongitude();
        if (FastMath.abs(mSun.getLatitude() - lat) > 0.2f
                || Math.abs(mSun.getLongitude() - lon) > 0.2f) {
            mSun.setCoordinates(lat, lon);
        }
        mSun.update();

        // Compile terrain meshes
        MapTile[] tiles = mDrawTiles.tiles;
        int tileCnt = mDrawTiles.cnt;

        if (mTerrainTileData.length < tileCnt * 2) {
            mTerrainTileData = new TerrainTileData[tileCnt * 2];
        }

        int cnt = 0;
        for (int i = 0; i < tileCnt; i++) {
            MapTile tile = tiles[i];

            // Consume any pending async raster texture for this tile.
            TerrainTileLayer.consumePendingTexture(tile);

            // Consume any pending vector drape texture for this tile.
            TerrainTileLayer.consumePendingVectorTexture(tile);

            ExtrusionBuckets ebs = TerrainTileLayer.getTerrainBuckets(tile);
            if (ebs == null)
                continue;

            // Compile if not already done (one tile per frame max)
            if (!ebs.compiled) {
                if (!ebs.compile()) {
                    log.warning("TERRAIN: compile failed tile " + tile);
                    continue;
                }
            }

            // Pair bucket with textures (may be null)
            TerrainTileData data = mTerrainTileData[cnt];
            if (data == null) {
                data = new TerrainTileData();
                mTerrainTileData[cnt] = data;
            }
            data.buckets = ebs;
            data.texture = TerrainTileLayer.getTerrainTexture(tile);
            data.vectorTexture = TerrainTileLayer.getTerrainVectorTexture(tile);
            cnt++;
        }
        // Clean up orphaned pending textures for tiles no longer visible
        TerrainTileLayer.prunePendingTextures(tiles, tileCnt);
        mTerrainCnt = cnt;
        if (cnt > 0 && cnt != mLastLogCnt) {
            log.fine("TERRAIN: rendering " + cnt + " tiles");
            mLastLogCnt = cnt;
        }
    }

    @Override
    public void render(GLViewport v) {
        if (mTerrainCnt == 0)
            return;

        try {
            boolean isGlobe = (mProjectionType == TerrainProjection.Type.GLOBE);

            // Lazy-init shaders
            if (!mInitialized) {
                if (isGlobe) {
                    // Try globe texture shader first
                    mGlobeTexShader = new GlobeTexShader();
                    boolean useGlobeTex = mGlobeTexShader.program > 0;

                    if (!useGlobeTex) {
                        // Fall back to globe procedural shader
                        mGlobeShader = new GlobeShader();
                        if (mGlobeShader.program <= 0) {
                            log.severe("TERRAIN: globe shader init failed");
                            mInitialized = true;
                            return;
                        }
                    }

                    // Create 1x1 white fallback texture
                    if (useGlobeTex) {
                        Bitmap fb = CanvasAdapter.newBitmap(1, 1, 0);
                        fb.eraseColor(Color.WHITE);
                        mFallbackTex = new TextureItem(fb);
                    }

                    log.info("TERRAIN: globe shader initialized"
                            + (useGlobeTex ? " (tex)" : ""));
                } else {
                    // Try texture shader first
                    mTexShader = new TerrainTexShader();
                    boolean useTex = mTexShader.program > 0;

                    if (!useTex) {
                        // Fall back to procedural mesh shader
                        mShader = new ExtrusionRenderer.Shader("extrusion_layer_mesh");
                        if (mShader.getProgram() <= 0) {
                            log.severe("TERRAIN: shader init failed");
                            mInitialized = true;
                            return;
                        }
                    }

                    // Create 1x1 white fallback texture
                    if (useTex) {
                        Bitmap fb = CanvasAdapter.newBitmap(1, 1, 0);
                        fb.eraseColor(Color.WHITE);
                        mFallbackTex = new TextureItem(fb);
                    }

                    log.info("TERRAIN: shader initialized, program="
                            + (useTex ? mTexShader.program : mShader.getProgram()));
                }

                mInitialized = true;
            }

            boolean useTex;
            if (isGlobe) {
                useTex = mGlobeTexShader != null && mGlobeTexShader.program > 0;
            } else {
                useTex = mTexShader != null && mTexShader.program > 0;
            }

            // Diagnostic: print once when in globe mode (guarded so compiler
            // can eliminate the entire block when false).
            if (RENDER_DEBUG) {
                if (isGlobe && mTerrainCnt > 0) {
                    if (!mLoggedGlobeFrame) {
                        mLoggedGlobeFrame = true;
                        float sr = (mProjection != null) ? mProjection.getSphereRadius() : -1;
                        System.out.println("TERRAIN: GLOBE RENDERING — terrainCnt=" + mTerrainCnt
                                + " useTex=" + useTex + " sphereRadius=" + sr);
                        if (mTerrainTileData[0] != null && mTerrainTileData[0].buckets != null) {
                            ExtrusionBuckets ebs = mTerrainTileData[0].buckets;
                            System.out.println("TERRAIN: first tile x=" + ebs.x + " y=" + ebs.y
                                    + " z=" + ebs.zoomLevel);
                        }
                    }
                } else if (!isGlobe) {
                    mLoggedGlobeFrame = false;
                }

                // One-time diagnostic
                if (mTerrainCnt > 0 && !mLoggedTexStatus) {
                    mLoggedTexStatus = true;
                    System.out.println("TERRAIN: tex shader available=" + useTex
                            + " isGlobe=" + isGlobe
                            + " (terrain tiles rendered: " + mTerrainCnt + ")");
                    if (!useTex) {
                        System.out.println("TERRAIN: raster draping requires tex shader. "
                                + "Check terrain_globe_tex.glsl compilation.");
                    }
                }
            }

            // Depth buffer setup
            gl.depthMask(true);
            if (mClearDepth)
                gl.clear(GL.DEPTH_BUFFER_BIT);

            GLState.test(true, false);

            // Bind shader
            if (isGlobe) {
                if (useTex) {
                    mGlobeTexShader.activate();
                    GLState.enableVertexArrays(mGlobeTexShader.aPos, GLState.DISABLED);
                } else {
                    mGlobeShader.activate();
                    GLState.enableVertexArrays(mGlobeShader.aPos, GLState.DISABLED);
                }
            } else {
                if (useTex) {
                    mTexShader.activate();
                    GLState.enableVertexArrays(mTexShader.aPos, GLState.DISABLED);
                } else {
                    mShader.useProgram();
                    GLState.enableVertexArrays(mShader.aPos, GLState.DISABLED);
                }
            }

            // Face culling only for flat (Mercator) mode. Globe terrain mesh
            // triangles wind CW when projected from outside the sphere, so
            // enabling culling with the default CCW=front convention removes
            // the tiles we actually want to see. Back-of-sphere tiles are
            // never added to mDrawTiles (getMapExtents uses ray-sphere hits).
            if (!isGlobe && v.pos.zoomLevel < 18)
                gl.enable(GL.CULL_FACE);

            gl.depthFunc(GL.LESS);

            // Compute dynamic u_zlimit so height-based coloring adapts to zoom.
            // For Mercator: 8000m (high peak) maps to h=1.0 in tile-local z-units.
            // For Globe: use the same elevToTileZ conversion as the vertex pipeline
            // (elevMeters * sphereRadius / EARTH_RADIUS_METERS) so that
            // h = a_pos.z / zLimit maps an 8000m peak to h ≈ 1.0.
            double scale = 1L << v.pos.zoomLevel;

            // Get sphere radius from projection (or default for backward compat)
            float sphereRadius = (mProjection != null)
                    ? mProjection.getSphereRadius() : 4096.0f;

            float zLimit;
            if (isGlobe && mProjection != null) {
                zLimit = 8000f * sphereRadius / TerrainProjection.EARTH_RADIUS_METERS;
            } else {
                float groundScale = (float) MercatorProjection.groundResolutionWithScale(
                        (float) v.pos.getLatitude(), scale);
                zLimit = 8000.0f / (groundScale * 10);
            }

            // Camera position for atmosphere: use the setCameraPosition() value
            // if provided, otherwise default to (0, 0, sphereRadius*3).
            if (mCamPosUniform[0] == 0f && mCamPosUniform[1] == 0f
                    && mCamPosUniform[2] == 0f) {
                mCamPosUniform[2] = sphereRadius * 3f;
            }

            // Set common uniforms (per-shader-variant)
            if (isGlobe) {
                if (useTex) {
                    gl.uniform1f(mGlobeTexShader.uAlpha, 1.0f);
                    gl.uniform1f(mGlobeTexShader.uZLimit, zLimit);
                    GLUtils.glUniform3fv(mGlobeTexShader.uLight, 1, mSun.getPosition());
                    // Sphere warp + atmosphere uniforms
                    gl.uniform1f(mGlobeTexShader.uGlobeRadius, sphereRadius);
                    GLUtils.glUniform3fv(mGlobeTexShader.uCameraPos, 1, mCamPosUniform);
                    GLUtils.glUniform4fv(mGlobeTexShader.uAtmosphereColor, 1,
                            ATMOSPHERE_COLOR);
                    gl.uniform1f(mGlobeTexShader.uFogDensity, 0.6f);
                    gl.uniform1i(mGlobeTexShader.uMode, 0);
                    if (mFallbackTex != null) {
                        mFallbackTex.bind();
                    }
                } else {
                    gl.uniform1f(mGlobeShader.uAlpha, 1.0f);
                    gl.uniform1f(mGlobeShader.uZLimit, zLimit);
                    GLUtils.glUniform3fv(mGlobeShader.uLight, 1, mSun.getPosition());
                    // Sphere warp + atmosphere uniforms
                    gl.uniform1f(mGlobeShader.uGlobeRadius, sphereRadius);
                    GLUtils.glUniform3fv(mGlobeShader.uCameraPos, 1, mCamPosUniform);
                    GLUtils.glUniform4fv(mGlobeShader.uAtmosphereColor, 1,
                            ATMOSPHERE_COLOR);
                    gl.uniform1f(mGlobeShader.uFogDensity, 0.6f);
                    gl.uniform1i(mGlobeShader.uMode, 0);
                }
            } else {
                if (useTex) {
                    gl.uniform1f(mTexShader.uAlpha, 1.0f);
                    gl.uniform1f(mTexShader.uZLimit, zLimit);
                    GLUtils.glUniform3fv(mTexShader.uLight, 1, mSun.getPosition());
                    gl.uniform1i(mTexShader.uMode, 0);
                    if (mFallbackTex != null) {
                        mFallbackTex.bind();
                    }
                } else {
                    gl.uniform1f(mShader.uAlpha, 1.0f);
                    gl.uniform1f(mShader.uZLimit, zLimit);
                    GLUtils.glUniform3fv(mShader.uLight, 1, mSun.getPosition());
                    gl.uniform1i(mShader.uMode, 0);
                }
            }

            // Enable lighting
            GLState.blend(true);

            // Draw each terrain tile
            for (int i = 0; i < mTerrainCnt; i++) {
                TerrainTileData data = mTerrainTileData[i];
                if (data == null || data.buckets == null)
                    continue;

                ExtrusionBuckets ebs = data.buckets;
                if (ebs.vbo == null)
                    continue;

                ebs.vbo.bind();
                ebs.ibo.bind();

                // Set matrix using the appropriate shader + projection
                if (isGlobe) {
                    setMatrixGlobe(v, ebs);
                    // setMatrixGlobe sets mvp + tile bounds uniforms internally
                } else if (useTex) {
                    setMatrixTex(v, ebs);
                } else {
                    setMatrix(mShader, v, ebs);
                }

                // Bind texture: prefer vector drape (area fills), fall back
                // to raster tile texture, fall back to 1x1 white fallback.
                TextureItem tex = (data.vectorTexture != null)
                        ? data.vectorTexture : data.texture;
                if (useTex) {
                    if (tex != null) {
                        tex.bind();
                    } else if (mFallbackTex != null) {
                        mFallbackTex.bind();
                    }
                    if (isGlobe) {
                        gl.uniform1i(mGlobeTexShader.uTex, 0);
                        gl.uniform1f(mGlobeTexShader.uTexMix, (tex != null) ? mTexMix : 0.0f);
                    } else {
                        gl.uniform1i(mTexShader.uTex, 0);
                        gl.uniform1f(mTexShader.uTexMix, (tex != null) ? mTexMix : 0.0f);
                    }
                }

                // Iterate through extrusion buckets in this tile
                for (ExtrusionBucket eb = ebs.buckets(); eb != null; eb = eb.next()) {
                    if (isGlobe) {
                        if (useTex) {
                            GLUtils.glUniform4fv(mGlobeTexShader.uColor, 1, eb.getColors());
                            gl.vertexAttribPointer(mGlobeTexShader.aPos, 3, GL.SHORT,
                                    false, RenderBuckets.SHORT_BYTES * 4,
                                    eb.getVertexOffset());
                            gl.vertexAttribPointer(mGlobeTexShader.aNormal, 2, GL.UNSIGNED_BYTE,
                                    false, RenderBuckets.SHORT_BYTES * 4,
                                    eb.getVertexOffset() + RenderBuckets.SHORT_BYTES * 3);
                            if (eb.idx[4] > 0) {
                                gl.drawElements(GL.TRIANGLES, eb.idx[4],
                                        GL.UNSIGNED_SHORT, eb.off[4]);
                            }
                        } else {
                            GLUtils.glUniform4fv(mGlobeShader.uColor, 1, eb.getColors());
                            gl.vertexAttribPointer(mGlobeShader.aPos, 3, GL.SHORT,
                                    false, RenderBuckets.SHORT_BYTES * 4,
                                    eb.getVertexOffset());
                            gl.vertexAttribPointer(mGlobeShader.aNormal, 2, GL.UNSIGNED_BYTE,
                                    false, RenderBuckets.SHORT_BYTES * 4,
                                    eb.getVertexOffset() + RenderBuckets.SHORT_BYTES * 3);
                            if (eb.idx[4] > 0) {
                                gl.drawElements(GL.TRIANGLES, eb.idx[4],
                                        GL.UNSIGNED_SHORT, eb.off[4]);
                            }
                        }
                    } else if (useTex) {
                        GLUtils.glUniform4fv(mTexShader.uColor, 1, eb.getColors());

                        gl.vertexAttribPointer(mTexShader.aPos, 3, GL.SHORT,
                                false, RenderBuckets.SHORT_BYTES * 4,
                                eb.getVertexOffset());

                        gl.vertexAttribPointer(mTexShader.aNormal, 2, GL.UNSIGNED_BYTE,
                                false, RenderBuckets.SHORT_BYTES * 4,
                                eb.getVertexOffset() + RenderBuckets.SHORT_BYTES * 3);

                        if (eb.idx[4] > 0) {
                            gl.drawElements(GL.TRIANGLES, eb.idx[4],
                                    GL.UNSIGNED_SHORT, eb.off[4]);
                        }
                    } else {
                        GLUtils.glUniform4fv(mShader.uColor, 1, eb.getColors());

                        gl.vertexAttribPointer(mShader.aPos, 3, GL.SHORT,
                                false, RenderBuckets.SHORT_BYTES * 4,
                                eb.getVertexOffset());

                        gl.vertexAttribPointer(mShader.aNormal, 2, GL.UNSIGNED_BYTE,
                                false, RenderBuckets.SHORT_BYTES * 4,
                                eb.getVertexOffset() + RenderBuckets.SHORT_BYTES * 3);

                        if (eb.idx[4] > 0) {
                            gl.drawElements(GL.TRIANGLES, eb.idx[4],
                                    GL.UNSIGNED_SHORT, eb.off[4]);
                        }
                    }
                }
            }

            // Cleanup
            gl.depthMask(false);

            if (!isGlobe && v.pos.zoomLevel < 18)
                gl.disable(GL.CULL_FACE);
        } catch (Throwable t) {
            log.severe("TERRAIN: render error: " + t);
            t.printStackTrace();
            // Restore GL state to avoid corrupting subsequent layers
            gl.depthMask(false);
            if (mProjectionType != TerrainProjection.Type.GLOBE && v.pos.zoomLevel < 18)
                gl.disable(GL.CULL_FACE);
            GLState.test(false, false);
            GLState.blend(false);
        }
    }

    /**
     * Sets up the model-view-projection matrix for a terrain tile.
     * Mirrors {@link ExtrusionRenderer#setMatrix}.
     */
    private void setMatrix(ExtrusionRenderer.Shader s, GLViewport v, ExtrusionBuckets ebs) {
        int z = ebs.zoomLevel;
        double curScale = Tile.SIZE * v.pos.scale;
        float scale = (float) (v.pos.scale / (1 << z));

        float x = (float) ((ebs.x - v.pos.x) * curScale);
        float y = (float) ((ebs.y - v.pos.y) * curScale);

        v.mvp.setTransScale(x, y, scale / COORD_SCALE);
        v.mvp.setValue(10, scale / 10);
        v.mvp.multiplyLhs(v.viewproj);

        v.mvp.setAsUniform(s.uMVP);
    }

    /**
     * Sets up the model-view-projection matrix for a globe terrain tile.
     * For globe, vertices are ECEF-relative (relative to tile center on
     * sphere), so the model matrix is just a translation to the tile
     * center's absolute ECEF position. The viewproj matrix is the orbital
     * camera from {@code GlobeViewController}.
     */
    private void setMatrixGlobe(GLViewport v, ExtrusionBuckets ebs) {
        // Compute tile geographic bounds for the vertex shader's
        // Mercator→ECEF sphere warp. The shader needs:
        //   u_tileLonMin, u_tileLonRange, u_tileLatMax, u_tileLatRange
        //
        // ebs.x/y are in Mercator [0,1] space (same as MapPosition).
        // Convert to pixel coords first, then to geographic.
        int z = ebs.zoomLevel;
        long mapSize = Tile.SIZE << z;

        // Tile origin in world-pixel coordinates
        double pixelX = ebs.x * mapSize;
        double pixelY = ebs.y * mapSize;
        double tilePixelSize = Tile.SIZE;

        double leftLon = MercatorProjection.pixelXToLongitude(pixelX, mapSize);
        double rightLon = MercatorProjection.pixelXToLongitude(pixelX + tilePixelSize, mapSize);
        if (rightLon < leftLon) rightLon += 360.0;
        double topLat = MercatorProjection.pixelYToLatitude(pixelY, mapSize);
        double bottomLat = MercatorProjection.pixelYToLatitude(pixelY + tilePixelSize, mapSize);

        float tileLonMin = (float) leftLon;
        float tileLonRange = (float) (rightLon - leftLon);
        float tileLatMax = (float) topLat;
        float tileLatRange = (float) (topLat - bottomLat);

        // Set per-tile bounds uniforms on the active globe shader.
        boolean useTex = mGlobeTexShader != null && mGlobeTexShader.program > 0;
        if (useTex) {
            gl.uniform1f(mGlobeTexShader.uTileLonMin, tileLonMin);
            gl.uniform1f(mGlobeTexShader.uTileLonRange, tileLonRange);
            gl.uniform1f(mGlobeTexShader.uTileLatMax, tileLatMax);
            gl.uniform1f(mGlobeTexShader.uTileLatRange, tileLatRange);
        } else {
            gl.uniform1f(mGlobeShader.uTileLonMin, tileLonMin);
            gl.uniform1f(mGlobeShader.uTileLonRange, tileLonRange);
            gl.uniform1f(mGlobeShader.uTileLatMax, tileLatMax);
            gl.uniform1f(mGlobeShader.uTileLatRange, tileLatRange);
        }

        // Model matrix is identity — the vertex shader computes absolute
        // ECEF positions. View and projection come from GlobeViewController.
        v.mvp.copy(v.viewproj);

        // Set mvp on the active globe shader
        if (useTex) {
            v.mvp.setAsUniform(mGlobeTexShader.uMVP);
        } else {
            v.mvp.setAsUniform(mGlobeShader.uMVP);
        }
    }

    /**
     * Sets up the model-view-projection matrix using the tex shader uniforms.
     */
    private void setMatrixTex(GLViewport v, ExtrusionBuckets ebs) {
        int z = ebs.zoomLevel;
        double curScale = Tile.SIZE * v.pos.scale;
        float scale = (float) (v.pos.scale / (1 << z));

        float x = (float) ((ebs.x - v.pos.x) * curScale);
        float y = (float) ((ebs.y - v.pos.y) * curScale);

        v.mvp.setTransScale(x, y, scale / COORD_SCALE);
        v.mvp.setValue(10, scale / 10);
        v.mvp.multiplyLhs(v.viewproj);

        v.mvp.setAsUniform(mTexShader.uMVP);
    }
}

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
     * Pairs an ExtrusionBuckets with its optional raster TextureItem
     * for use during rendering.
     */
    private static class TerrainTileData {
        ExtrusionBuckets buckets;
        TextureItem texture;
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
            // The async fetch thread stores the bitmap; we create the
            // TextureItem here on the GL thread for safe upload.
            TerrainTileLayer.consumePendingTexture(tile);

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

            // Pair bucket with texture (may be null)
            TerrainTileData data = mTerrainTileData[cnt];
            if (data == null) {
                data = new TerrainTileData();
                mTerrainTileData[cnt] = data;
            }
            data.buckets = ebs;
            data.texture = TerrainTileLayer.getTerrainTexture(tile);
            cnt++;
        }
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
            // Lazy-init shaders
            if (!mInitialized) {
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

                mInitialized = true;
                if (useTex)
                    log.info("TERRAIN: tex shader initialized, program=" + mTexShader.program);
                else
                    log.info("TERRAIN: shader initialized, program=" + mShader.getProgram());
            }

            boolean useTex = mTexShader != null && mTexShader.program > 0;

            // Depth buffer setup
            gl.depthMask(true);
            if (mClearDepth)
                gl.clear(GL.DEPTH_BUFFER_BIT);

            GLState.test(true, false);

            // Bind shader
            if (useTex) {
                mTexShader.activate();
                GLState.enableVertexArrays(mTexShader.aPos, GLState.DISABLED);
            } else {
                mShader.useProgram();
                GLState.enableVertexArrays(mShader.aPos, GLState.DISABLED);
            }

            // Face culling at moderate zoom
            if (v.pos.zoomLevel < 18)
                gl.enable(GL.CULL_FACE);

            gl.depthFunc(GL.LESS);

            // Set common uniforms
            if (useTex) {
                gl.uniform1f(mTexShader.uAlpha, 1.0f);
                gl.uniform1f(mTexShader.uZLimit, Float.MAX_VALUE);
                GLUtils.glUniform3fv(mTexShader.uLight, 1, mSun.getPosition());
                // Ensure fallback texture is uploaded (bind triggers upload if needed)
                if (mFallbackTex != null) {
                    mFallbackTex.bind();
                }
            } else {
                gl.uniform1f(mShader.uAlpha, 1.0f);
                gl.uniform1f(mShader.uZLimit, Float.MAX_VALUE);
                GLUtils.glUniform3fv(mShader.uLight, 1, mSun.getPosition());
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

                // Set matrix using the appropriate shader
                if (useTex)
                    setMatrixTex(v, ebs);
                else
                    setMatrix(mShader, v, ebs);

                // Bind texture (or fallback) and set texture uniforms
                TextureItem tex = data.texture;
                if (useTex) {
                    if (tex != null) {
                        tex.bind();
                    } else if (mFallbackTex != null) {
                        mFallbackTex.bind();
                    }
                    gl.uniform1i(mTexShader.uTex, 0);
                    gl.uniform1f(mTexShader.uTexMix, (tex != null) ? mTexMix : 0.0f);
                }

                // Iterate through extrusion buckets in this tile
                for (ExtrusionBucket eb = ebs.buckets(); eb != null; eb = eb.next()) {
                    if (useTex) {
                        GLUtils.glUniform4fv(mTexShader.uColor, 1, eb.getColors());

                        gl.vertexAttribPointer(mTexShader.aPos, 3, GL.SHORT,
                                false, RenderBuckets.SHORT_BYTES * 4,
                                eb.getVertexOffset());

                        gl.vertexAttribPointer(mTexShader.aNormal, 2, GL.UNSIGNED_BYTE,
                                false, RenderBuckets.SHORT_BYTES * 4,
                                eb.getVertexOffset() + RenderBuckets.SHORT_BYTES * 3);

                        if (eb.idx[4] > 0) {
                            gl.uniform1i(mTexShader.uMode, 0);
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
                            gl.uniform1i(mShader.uMode, 0);
                            gl.drawElements(GL.TRIANGLES, eb.idx[4],
                                    GL.UNSIGNED_SHORT, eb.off[4]);
                        }
                    }
                }
            }

            // Cleanup
            gl.depthMask(false);

            if (v.pos.zoomLevel < 18)
                gl.disable(GL.CULL_FACE);
        } catch (Throwable t) {
            log.severe("TERRAIN: render error: " + t);
            t.printStackTrace();
            mTerrainCnt = 0; // skip future renders
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

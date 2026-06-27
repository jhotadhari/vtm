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

import org.oscim.backend.GL;
import org.oscim.core.Tile;
import org.oscim.layers.tile.MapTile;
import org.oscim.layers.tile.TileRenderer;
import org.oscim.renderer.ExtrusionRenderer;
import org.oscim.renderer.GLState;
import org.oscim.renderer.GLUtils;
import org.oscim.renderer.GLViewport;
import org.oscim.renderer.MapRenderer;
import org.oscim.renderer.bucket.ExtrusionBucket;
import org.oscim.renderer.bucket.ExtrusionBuckets;
import org.oscim.renderer.bucket.RenderBuckets;
import org.oscim.renderer.light.Sun;
import org.oscim.utils.FastMath;

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

    /** Light source position for terrain shading. */
    private final Sun mSun;

    /** Mesh shader. */
    private ExtrusionRenderer.Shader mShader;

    /** Whether the shader has been initialized. */
    private boolean mInitialized;

    /** Cached bucket set for current frame. */
    private ExtrusionBuckets[] mTerrainSet = new ExtrusionBuckets[32];
    private int mTerrainCnt;
    private int mLastLogCnt = -1; // for debug logging

    public TerrainTileRenderer() {
        super();
        mSun = new Sun();
    }

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

        if (mTerrainSet.length < tileCnt * 4) {
            mTerrainSet = new ExtrusionBuckets[tileCnt * 4];
        }

        int cnt = 0;
        for (int i = 0; i < tileCnt; i++) {
            MapTile tile = tiles[i];
            ExtrusionBuckets ebs = TerrainTileLayer.getTerrainBuckets(tile);
            if (ebs == null)
                continue;

            // Compile if not already done (one tile per frame max)
            if (!ebs.compiled) {
                if (!ebs.compile()) {
                    System.err.println("TERRAIN: compile failed tile " + tile);
                    continue;
                }
            }

            mTerrainSet[cnt++] = ebs;
        }
        mTerrainCnt = cnt;
        if (cnt > 0 && cnt != mLastLogCnt) {
            System.out.println("TERRAIN: rendering " + cnt + " tiles");
            mLastLogCnt = cnt;
        }
    }

    @Override
    public void render(GLViewport v) {
        if (mTerrainCnt == 0)
            return;

        // Lazy-init shader
        if (!mInitialized) {
            mShader = new ExtrusionRenderer.Shader("extrusion_layer_mesh");
            if (mShader.getProgram() <= 0) {
                mInitialized = true; // Don't retry
                return;
            }
            mInitialized = true;
        }

        // Depth buffer setup: terrain is the first 3D layer
        gl.depthMask(true);
        gl.clear(GL.DEPTH_BUFFER_BIT);

        GLState.test(true, false);

        ExtrusionRenderer.Shader s = mShader;
        s.useProgram();
        GLState.enableVertexArrays(s.aPos, GLState.DISABLED);

        // Face culling at moderate zoom
        if (v.pos.zoomLevel < 18)
            gl.enable(GL.CULL_FACE);

        gl.depthFunc(GL.LESS);
        gl.uniform1f(s.uAlpha, 1.0f);
        gl.uniform1f(s.uZLimit, Float.MAX_VALUE);
        GLUtils.glUniform3fv(s.uLight, 1, mSun.getPosition());

        // Enable lighting
        GLState.blend(true);

        // Draw each terrain tile
        for (int i = 0; i < mTerrainCnt; i++) {
            ExtrusionBuckets ebs = mTerrainSet[i];
            if (ebs.vbo == null)
                continue;

            ebs.vbo.bind();
            ebs.ibo.bind();

            setMatrix(s, v, ebs);

            // Iterate through extrusion buckets in this tile
            for (ExtrusionBucket eb = ebs.buckets(); eb != null; eb = eb.next()) {
                // Set color
                GLUtils.glUniform4fv(s.uColor, 1, eb.getColors());

                // Set vertex position attribute (x,y,z as shorts)
                gl.vertexAttribPointer(s.aPos, 3, GL.SHORT,
                        false, RenderBuckets.SHORT_BYTES * 4,
                        eb.getVertexOffset());

                // Set normal attribute (packed as 2 unsigned bytes)
                gl.vertexAttribPointer(s.aNormal, 2, GL.UNSIGNED_BYTE,
                        false, RenderBuckets.SHORT_BYTES * 4,
                        eb.getVertexOffset() + RenderBuckets.SHORT_BYTES * 3);

                // Draw mesh triangles (idx[4] = IND_MESH)
                if (eb.idx[4] > 0) {
                    gl.uniform1i(s.uMode, 0); // roof/mesh mode
                    gl.drawElements(GL.TRIANGLES, eb.idx[4],
                            GL.UNSIGNED_SHORT, eb.off[4]);
                }
            }
        }

        // Cleanup
        gl.depthMask(false);

        if (v.pos.zoomLevel < 18)
            gl.disable(GL.CULL_FACE);
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
}

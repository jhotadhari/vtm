/*
 * Copyright 2016-2022 devemux86
 * Copyright 2018-2019 Gustl22
 *
 * This file is part of the OpenScienceMap project (http://www.opensciencemap.org).
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
package org.oscim.test;

import com.badlogic.gdx.Input;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.layer.hills.AdaptiveClasyHillShading;
import org.mapsforge.map.layer.hills.DemFolderFS;
import org.oscim.backend.canvas.Color;
import org.oscim.core.BoundingBox;
import org.oscim.core.MapPosition;
import org.oscim.core.Tile;
import org.oscim.event.Event;
import org.oscim.gdx.GdxMapApp;
import org.oscim.gdx.poi3d.Poi3DLayer;
import org.oscim.layers.tile.bitmap.BitmapTileLayer;
import org.oscim.layers.tile.buildings.BuildingLayer;
import org.oscim.layers.tile.buildings.S3DBLayer;
import org.oscim.layers.tile.vector.VectorTileLayer;
import org.oscim.layers.tile.vector.labeling.LabelLayer;
import org.oscim.map.Map;
import org.oscim.map.Viewport;
import org.oscim.renderer.BitmapRenderer;
import org.oscim.renderer.ExtrusionRenderer;
import org.oscim.renderer.GLViewport;
import org.oscim.scalebar.*;
import org.oscim.theme.ExternalRenderTheme;
import org.oscim.theme.internal.VtmThemes;
import org.oscim.terrain.layer.TerrainTileLayer;
import org.oscim.terrain.projection.GlobeTerrainProjection;
import org.oscim.terrain.tiling.TerrainTileSource;
import org.oscim.tiling.source.bitmap.BitmapTileSource;
import org.oscim.tiling.source.hills.HillshadingTileSource;
import org.oscim.tiling.source.mapfile.MapFileTileSource;
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource;
import org.oscim.tiling.source.OkHttpEngine;
import org.oscim.tiling.TileSource;

import java.io.File;
import java.util.*;

public class MapsforgeTest extends GdxMapApp {

    private static final boolean SHADOWS = false;

    private final File demFolder;
    private final List<File> mapFiles;
    private final boolean poi3d;
    private final boolean s3db;
    private final File themeFile;

    // Layer references for toggling
    private TerrainTileLayer mTerrainLayer;
    private BitmapTileLayer mHillshadeLayer;
    private BitmapTileLayer mOsmLayer;
    private VectorTileLayer mBaseLayer;
    private BuildingLayer mBuildingLayer;
    private boolean mTerrainVisible = true;
    private boolean mHillshadeVisible = true;
    private boolean mOsmVisible;
    private boolean mBaseVisible = true;
    private float mExaggeration = 5.0f;
    private float mBaseElevation = 0f;
    private boolean mRasterDrape = false;
    private float mTexMix = 0.8f;
    private boolean mGlobeMode = false;
    private String mMbtilesPath; // optional MBTiles raster source path
    private DemFolderFS mDemFolderRef; // kept for layer recreation
    private MultiMapFileTileSource mMapFileSource; // kept for vector drape

    MapsforgeTest(File demFolder, List<File> mapFiles, File themeFile) {
        this(demFolder, mapFiles, false, false, themeFile, null);
    }

    MapsforgeTest(File demFolder, List<File> mapFiles, boolean s3db, boolean poi3d, File themeFile) {
        this(demFolder, mapFiles, s3db, poi3d, themeFile, null);
    }

    MapsforgeTest(File demFolder, List<File> mapFiles, boolean s3db, boolean poi3d,
                  File themeFile, String mbtilesPath) {
        this.demFolder = demFolder;
        this.mapFiles = mapFiles;
        this.s3db = s3db;
        this.poi3d = poi3d;
        this.themeFile = themeFile;
        this.mMbtilesPath = mbtilesPath;
    }

    @Override
    public void createLayers() {
        MultiMapFileTileSource multiMapFileTileSource = null;
        if (!mapFiles.isEmpty()) {
            multiMapFileTileSource = new MultiMapFileTileSource();
            for (File mapFile : mapFiles) {
                MapFileTileSource mapFileTileSource = new MapFileTileSource();
                mapFileTileSource.setMapFile(mapFile.getAbsolutePath());
                if ("world.map".equalsIgnoreCase(mapFile.getName()))
                    mapFileTileSource.setPriority(-1);
                multiMapFileTileSource.add(mapFileTileSource);
            }
            mMapFileSource = multiMapFileTileSource;
            mBaseLayer = mMap.setBaseMap(multiMapFileTileSource);
        } else {
            mBaseLayer = null;
        }
        if (mBaseLayer != null)
            loadTheme(null);

        // Optional OpenStreetMap raster baselayer (F4 to toggle)
        TileSource osmSource = createOsmSource();
        if (osmSource != null) {
            mOsmLayer = new BitmapTileLayer(mMap, osmSource, 100);
            mMap.layers().add(mOsmLayer);
            mOsmLayer.setEnabled(mOsmVisible);
        }

        System.out.println("DEM folder: " + (demFolder != null ? demFolder.getAbsolutePath() : "NOT DETECTED"));
        System.out.println("MBTiles path: " + (mMbtilesPath != null ? mMbtilesPath : "NOT SET"));

        if (demFolder != null) {
            // 2D hillshading overlay
            final AdaptiveClasyHillShading algorithm = new AdaptiveClasyHillShading()
                    .setAdaptiveZoomEnabled(true)
                    .setCustomQualityScale(1);
            final HillshadingTileSource hillshadingTileSource = new HillshadingTileSource(Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL, new DemFolderFS(demFolder), algorithm, 128, Color.BLACK, AwtGraphicFactory.INSTANCE);
            mHillshadeLayer = new BitmapTileLayer(mMap, hillshadingTileSource, 150);
            mMap.layers().add(mHillshadeLayer);

            // 3D terrain mesh
            mDemFolderRef = new DemFolderFS(demFolder);
            addTerrainLayer();
        } else {
            System.out.println("WARNING: No DEM folder found — terrain and hillshading disabled.");
            System.out.println("Expected: /home/jhotadhari/Development/android/test-data/hgt");
            System.out.println("Command: --args=\"<mapFile>,<hgtFolder>,--mbtiles,<mbtilesFile>\"");
            // Still allow MBTiles-only raster testing without terrain
            if (mMbtilesPath != null) {
                System.out.println("MBTiles available but no terrain to drape onto.");
            }
        }

        BuildingLayer buildingLayer = null;
        if (mBaseLayer != null) {
            buildingLayer = s3db ? new S3DBLayer(mMap, mBaseLayer, SHADOWS) : new BuildingLayer(mMap, mBaseLayer, false, SHADOWS);
            mBuildingLayer = buildingLayer;
            mMap.layers().add(buildingLayer);

            // When terrain is active, set building renderer to test against terrain depth
            // instead of clearing it (terrain layer clears depth first)
            if (mTerrainLayer != null) {
                buildingLayer.getExtrusionRenderer().setClearDepth(false);
            }

            if (poi3d)
                mMap.layers().add(new Poi3DLayer(mMap, mBaseLayer));

            mMap.layers().add(new LabelLayer(mMap, mBaseLayer));
        }

        DefaultMapScaleBar mapScaleBar = new DefaultMapScaleBar(mMap);
        mapScaleBar.setScaleBarMode(DefaultMapScaleBar.ScaleBarMode.BOTH);
        mapScaleBar.setDistanceUnitAdapter(MetricUnitAdapter.INSTANCE);
        mapScaleBar.setSecondaryDistanceUnitAdapter(ImperialUnitAdapter.INSTANCE);
        mapScaleBar.setScaleBarPosition(MapScaleBar.ScaleBarPosition.BOTTOM_LEFT);

        MapScaleBarLayer mapScaleBarLayer = new MapScaleBarLayer(mMap, mapScaleBar);
        BitmapRenderer renderer = mapScaleBarLayer.getRenderer();
        renderer.setPosition(GLViewport.Position.BOTTOM_LEFT);
        renderer.setOffset(5, 0);
        mMap.layers().add(mapScaleBarLayer);

        if (!mapFiles.isEmpty()) {
            MapPosition pos = MapPreferences.getMapPosition();
            BoundingBox bbox = multiMapFileTileSource.getBoundingBox();
            if (pos == null || !bbox.contains(pos.getGeoPoint())) {
                pos = new MapPosition();
                pos.setByBoundingBox(bbox, Tile.SIZE * 4, Tile.SIZE * 4);
            }
            mMap.setMapPosition(pos);
        } else {
            // No map file: position over DEM area (Southern Peru)
            MapPosition pos = new MapPosition();
            pos.setPosition(-18.5, -69.5); // Within S18-S19 W069 HGT tiles
            pos.setZoomLevel(10);
            mMap.setMapPosition(pos);
        }

        if (SHADOWS && buildingLayer != null) {
            final ExtrusionRenderer extrusionRenderer = buildingLayer.getExtrusionRenderer();
            mMap.events.bind(new Map.UpdateListener() {
                Calendar date = Calendar.getInstance();
                long prevTime = System.currentTimeMillis();

                @Override
                public void onMapEvent(Event e, MapPosition mapPosition) {
                    long curTime = System.currentTimeMillis();
                    int diff = (int) (curTime - prevTime);
                    prevTime = curTime;
                    date.add(Calendar.MILLISECOND, diff * 60 * 60); // Every second equates to one hour

                    //extrusionRenderer.getSun().setProgress((curTime % 2000) / 1000f);
                    extrusionRenderer.getSun().setProgress(date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), date.get(Calendar.SECOND));
                    extrusionRenderer.getSun().updatePosition();
                    extrusionRenderer.getSun().updateColor(); // only relevant for shadow implementation

                    mMap.updateMap(true);
                }
            });
        }

        System.out.println("=== TERRAIN KEYS ===");
        System.out.println("F4  = toggle OSM raster baselayer (currently " + (mOsmVisible ? "ON" : "OFF") + ")");
        if (mMbtilesPath != null) {
            System.out.println("F5  = toggle MBTiles raster drape ON/OFF (currently OFF)");
        } else {
            System.out.println("F5  = toggle OSM raster drape ON/OFF (currently OFF)");
        }
        System.out.println("F6  = toggle terrain ON/OFF (currently " + (mTerrainVisible ? "ON" : "OFF") + ")");
        if (themeFile != null) {
            System.out.println("T   = reload theme");
        }
        System.out.println("F7  = toggle hillshade ON/OFF");
        System.out.println("F8  = toggle base map ON/OFF");
        System.out.println("F9  = decrease exaggeration (-5)");
        System.out.println("F10 = increase exaggeration (+5)");
        System.out.println("F11 = lower base elevation (-500m)");
        System.out.println("F12 = raise base elevation (+500m)");
        System.out.println("G   = toggle globe projection ON/OFF");
        System.out.println("Current: exaggeration=" + mExaggeration + "x baseElev=" + mBaseElevation + "m"
                + " globe=" + (mGlobeMode ? "ON" : "OFF"));
    }

    private void addTerrainLayer() {
        // Remove and dispose old terrain layer if present
        if (mTerrainLayer != null) {
            int count = mMap.layers().size();
            for (int i = 0; i < count; i++) {
                if (mMap.layers().get(i) == mTerrainLayer) {
                    mMap.layers().remove(i);
                    break;
                }
            }
            mTerrainLayer.dispose();
        }
        TerrainTileSource terrainSource = new TerrainTileSource(
                Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL,
                mDemFolderRef)
                .setElevationExaggeration(mExaggeration)
                .setBaseElevation(mBaseElevation)
                .setTerrainColor(Color.get(220, 80, 60, 255))
                .setTexMix(mTexMix);

        // When globe mode is active, use GlobeTerrainProjection
        if (mGlobeMode) {
            terrainSource.setProjection(new GlobeTerrainProjection(4096.0f));
            mMap.setGlobeMode(true, 4096.0f);
        } else {
            mMap.setGlobeMode(false);
        }

        // Wire raster texture draping when enabled
        if (mRasterDrape) {
            TileSource rasterSource = createRasterSource();
            if (rasterSource != null) {
                terrainSource.setRasterSource(rasterSource);
                System.out.println("TERRAIN: raster source configured: "
                        + rasterSource.getClass().getSimpleName()
                        + " zoom " + rasterSource.getZoomLevelMin()
                        + "-" + rasterSource.getZoomLevelMax());
            } else {
                System.out.println("TERRAIN: createRasterSource() returned null!");
            }
        } else {
            System.out.println("TERRAIN: raster drape OFF (press F5 to enable)");
        }

        // Wire vector area-fill draping from the map file source
        if (mMapFileSource != null) {
            terrainSource.setVectorSource(mMapFileSource);
        }

        mTerrainLayer = new TerrainTileLayer(mMap, terrainSource);
        // TerrainTileLayer.getRenderPriority() = RENDER_PRIORITY_TERRAIN (0x200)
        // ensures terrain renders after base map and before buildings regardless
        // of insertion order.
        mMap.layers().add(mTerrainLayer);

        // Sync building renderer: when terrain is present, buildings test against terrain depth
        if (mBuildingLayer != null) {
            mBuildingLayer.getExtrusionRenderer().setClearDepth(false);
        }

        mMap.updateMap(true);
        System.out.println("TERRAIN: exaggeration = " + mExaggeration + "x"
                + ", baseElev = " + mBaseElevation + "m"
                + ", drape = " + (mRasterDrape ? "ON" : "OFF")
                + ", texMix = " + mTexMix);
    }

    /**
     * Creates an OpenStreetMap raster tile source for the flat baselayer (F4).
     */
    private TileSource createOsmSource() {
        try {
            okhttp3.OkHttpClient.Builder clientBuilder = new okhttp3.OkHttpClient.Builder();
            clientBuilder.addNetworkInterceptor(new okhttp3.Interceptor() {
                @Override
                public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain)
                        throws java.io.IOException {
                    return chain.proceed(chain.request().newBuilder()
                            .header("User-Agent", "VTM/1.0 (https://github.com/opensciencemap/vtm)")
                            .build());
                }
            });
            return BitmapTileSource.builder()
                    .url("https://tile.openstreetmap.org")
                    .zoomMin(Viewport.MIN_ZOOM_LEVEL)
                    .zoomMax(Viewport.MAX_ZOOM_LEVEL)
                    .httpFactory(new OkHttpEngine.OkHttpFactory(clientBuilder))
                    .build();
        } catch (Exception e) {
            System.err.println("OSM: failed to create tile source: " + e);
            return null;
        }
    }

    /**
     * Creates an optional raster tile source for texture draping.
     * Returns null if raster sources cannot be configured (e.g. no HTTP engine).
     */
    private TileSource createRasterSource() {
        // Prefer MBTiles raster source when configured
        if (mMbtilesPath != null) {
            try {
                File f = new File(mMbtilesPath);
                if (f.exists()) {
                    System.out.println("TERRAIN: using MBTiles raster source: " + mMbtilesPath);
                    return new MbtilesBitmapTileSource(
                            mMbtilesPath, Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL);
                }
            } catch (Exception e) {
                System.err.println("TERRAIN: failed to open MBTiles: " + e);
            }
        }

        try {
            // OSM tile usage policy requires a meaningful User-Agent
            // (https://operations.osmfoundation.org/policies/tiles/)
            // OkHttp's BridgeInterceptor overwrites the User-Agent header, so we
            // inject it via a network interceptor (which runs after BridgeInterceptor).
            okhttp3.OkHttpClient.Builder clientBuilder = new okhttp3.OkHttpClient.Builder();
            clientBuilder.addNetworkInterceptor(new okhttp3.Interceptor() {
                @Override
                public okhttp3.Response intercept(okhttp3.Interceptor.Chain chain)
                        throws java.io.IOException {
                    return chain.proceed(chain.request().newBuilder()
                            .header("User-Agent", "VTM/1.0 (https://github.com/opensciencemap/vtm)")
                            .build());
                }
            });
            return BitmapTileSource.builder()
                    .url("https://tile.openstreetmap.org")
                    .zoomMin(Viewport.MIN_ZOOM_LEVEL)
                    .zoomMax(Viewport.MAX_ZOOM_LEVEL)
                    .httpFactory(new OkHttpEngine.OkHttpFactory(clientBuilder))
                    .build();
        } catch (Exception e) {
            System.err.println("TERRAIN: failed to create raster source: " + e);
            return null;
        }
    }

    @Override
    public void dispose() {
        MapPreferences.saveMapPosition(mMap.getMapPosition());
        super.dispose();
    }

    @Override
    protected boolean onKeyDown(int keycode) {
        if (keycode == Input.Keys.F4) {
            // Toggle OSM raster baselayer
            mOsmVisible = !mOsmVisible;
            if (mOsmLayer != null)
                mOsmLayer.setEnabled(mOsmVisible);
            mMap.updateMap(true);
            System.out.println("OSM baselayer: " + (mOsmVisible ? "ON" : "OFF"));
            return true;
        }
        if (keycode == Input.Keys.F5) {
            // Toggle raster/MBTiles texture draping on terrain
            if (mTerrainLayer != null) {
                mRasterDrape = !mRasterDrape;
                addTerrainLayer();
                String src = (mMbtilesPath != null) ? "MBTiles" : "OSM";
                System.out.println(src + " raster drape: " + (mRasterDrape ? "ON" : "OFF"));
            }
            return true;
        }
        if (keycode == Input.Keys.T) {
            // Reload theme from file
            if (themeFile != null) {
                mMap.setTheme(new ExternalRenderTheme(themeFile.getAbsolutePath()));
                mMap.clearMap();
                System.out.println("Theme reloaded: " + themeFile.getName());
            }
            return true;
        }
        if (keycode == Input.Keys.F6) {
            // Toggle terrain layer
            mTerrainVisible = !mTerrainVisible;
            if (mTerrainLayer != null)
                mTerrainLayer.setEnabled(mTerrainVisible);
            // Sync building renderer: when terrain is hidden, restore depth clearing
            if (mBuildingLayer != null) {
                mBuildingLayer.getExtrusionRenderer().setClearDepth(!mTerrainVisible);
            }
            mMap.updateMap(true);
            System.out.println("Terrain: " + (mTerrainVisible ? "ON" : "OFF"));
            return true;
        }
        if (keycode == Input.Keys.F7) {
            // Toggle hillshade layer
            mHillshadeVisible = !mHillshadeVisible;
            if (mHillshadeLayer != null)
                mHillshadeLayer.setEnabled(mHillshadeVisible);
            mMap.updateMap(true);
            System.out.println("Hillshade: " + (mHillshadeVisible ? "ON" : "OFF"));
            return true;
        }
        if (keycode == Input.Keys.F8) {
            // Toggle base map layer
            mBaseVisible = !mBaseVisible;
            if (mBaseLayer != null)
                mBaseLayer.setEnabled(mBaseVisible);
            mMap.updateMap(true);
            System.out.println("Base map: " + (mBaseVisible ? "ON" : "OFF"));
            return true;
        }
        if (keycode == Input.Keys.F9) {
            // Decrease exaggeration
            mExaggeration = Math.max(1, mExaggeration - 5);
            addTerrainLayer();
            return true;
        }
        if (keycode == Input.Keys.F10) {
            // Increase exaggeration
            mExaggeration += 5;
            addTerrainLayer();
            return true;
        }
        if (keycode == Input.Keys.F11) {
            // Decrease base elevation (lowers terrain toward map)
            mBaseElevation = Math.max(0, mBaseElevation - 500);
            addTerrainLayer();
            return true;
        }
        if (keycode == Input.Keys.F12) {
            // Increase base elevation (lifts terrain above map)
            mBaseElevation += 500;
            addTerrainLayer();
            return true;
        }
        if (keycode == Input.Keys.G) {
            // Toggle globe projection mode
            mGlobeMode = !mGlobeMode;
            addTerrainLayer();
            // In globe mode, hide flat layers — they render in Mercator space
            // and appear tilted on the sphere surface.
            // Restore each layer to its individually-tracked visibility
            // when leaving globe mode, rather than blindly enabling all.
            if (mOsmLayer != null)
                mOsmLayer.setEnabled(mGlobeMode ? false : mOsmVisible);
            if (mHillshadeLayer != null)
                mHillshadeLayer.setEnabled(mGlobeMode ? false : mHillshadeVisible);
            if (mBaseLayer != null)
                mBaseLayer.setEnabled(mGlobeMode ? false : mBaseVisible);
            // Force a full redraw to ensure terrain tiles load immediately
            mMap.clearMap();
            mMap.updateMap(true);
            System.out.println("Globe projection: " + (mGlobeMode ? "ON" : "OFF")
                    + " (OSM/hillshade/base map " + (mGlobeMode ? "hidden" : "visible") + ")");
            return true;
        }

        return false;
    }

    static File getDemFolder(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("missing argument: <mapFile>");
        }

        // Scan all remaining args for the first directory (DEM/HGT folder)
        for (String arg : args) {
            File demFolder = new File(arg);
            if (demFolder.exists() && demFolder.isDirectory() && demFolder.canRead()) {
                return demFolder;
            }
        }
        return null;
    }

    static List<File> getMapFiles(String[] args) {
        List<File> result = new ArrayList<>();
        if (args.length == 0)
            return result;

        for (String arg : args) {
            File mapFile = new File(arg);
            if (!mapFile.exists()) {
                System.err.println("file does not exist: " + mapFile);
            } else if (!mapFile.isFile()) {
                System.err.println("not a file: " + mapFile);
            } else if (!mapFile.canRead()) {
                System.err.println("cannot read file: " + mapFile);
            } else
                result.add(mapFile);
        }
        return result;
    }

    static File getThemeFile(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("missing argument: <mapFile>");
        }

        File themeFile = new File(args[0]);
        if (themeFile.exists() && themeFile.isFile() && themeFile.canRead() && themeFile.getName().toLowerCase(Locale.ROOT).endsWith(".xml")) {
            return themeFile;
        }
        return null;
    }

    void loadTheme(final String styleId) {
        mMap.setTheme(themeFile != null ? new ExternalRenderTheme(themeFile.getAbsolutePath()) : VtmThemes.MOTORIDER);
    }

    /**
     * @param args command line args: expects the map files as multiple parameters
     *             with possible theme file as 1st argument
     *             and possible SRTM hgt folder as 2nd argument.
     *             Supports comma-separated single-argument syntax (e.g. from Gradle --args).
     */
    public static void main(String[] args) {
        // Split comma-separated single-argument (from Gradle's --args)
        if (args.length == 1 && args[0].contains(",")) {
            args = args[0].split(",");
        }

        GdxMapApp.init();
        File themeFile = getThemeFile(args);
        if (themeFile != null)
            args = Arrays.copyOfRange(args, 1, args.length);

        // Find and remove the DEM/HGT folder from args (may be at any position)
        File demFolder = null;
        for (int i = 0; i < args.length; i++) {
            File f = new File(args[i]);
            if (f.exists() && f.isDirectory() && f.canRead()) {
                demFolder = f;
                // Remove this entry from args
                String[] newArgs = new String[args.length - 1];
                System.arraycopy(args, 0, newArgs, 0, i);
                if (i < args.length - 1)
                    System.arraycopy(args, i + 1, newArgs, i, args.length - i - 1);
                args = newArgs;
                break;
            }
        }

        // Parse optional --mbtiles <path> argument
        String mbtilesPath = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mbtiles".equals(args[i])) {
                mbtilesPath = args[i + 1];
                // Remove the --mbtiles and its value from args
                String[] newArgs = new String[args.length - 2];
                System.arraycopy(args, 0, newArgs, 0, i);
                System.arraycopy(args, i + 2, newArgs, i, args.length - i - 2);
                args = newArgs;
                break;
            }
        }

        GdxMapApp.run(new MapsforgeTest(demFolder, getMapFiles(args),
                false, false, themeFile, mbtilesPath));
    }
}

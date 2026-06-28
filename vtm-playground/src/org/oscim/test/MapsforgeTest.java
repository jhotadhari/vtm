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
    private VectorTileLayer mBaseLayer;
    private BuildingLayer mBuildingLayer;
    private boolean mTerrainVisible = true;
    private boolean mHillshadeVisible = true;
    private boolean mBaseVisible = true;
    private float mExaggeration = 5.0f;
    private float mBaseElevation = 3800f;
    private boolean mRasterDrape = false;
    private float mTexMix = 0.8f;
    private DemFolderFS mDemFolderRef; // kept for layer recreation

    MapsforgeTest(File demFolder, List<File> mapFiles, File themeFile) {
        this(demFolder, mapFiles, false, false, themeFile);
    }

    MapsforgeTest(File demFolder, List<File> mapFiles, boolean s3db, boolean poi3d, File themeFile) {
        this.demFolder = demFolder;
        this.mapFiles = mapFiles;
        this.s3db = s3db;
        this.poi3d = poi3d;
        this.themeFile = themeFile;
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
            mBaseLayer = mMap.setBaseMap(multiMapFileTileSource);
        } else {
            mBaseLayer = null;
        }
        if (mBaseLayer != null)
            loadTheme(null);

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

        if (SHADOWS) {
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
    }

    private void addTerrainLayer() {
        // Remove old terrain layer if present
        if (mTerrainLayer != null) {
            int count = mMap.layers().size();
            for (int i = 0; i < count; i++) {
                if (mMap.layers().get(i) == mTerrainLayer) {
                    mMap.layers().remove(i);
                    break;
                }
            }
        }
        TerrainTileSource terrainSource = new TerrainTileSource(
                Viewport.MIN_ZOOM_LEVEL, Viewport.MAX_ZOOM_LEVEL,
                mDemFolderRef)
                .setElevationExaggeration(mExaggeration)
                .setBaseElevation(mBaseElevation)
                .setTerrainColor(Color.get(220, 80, 60, 255))
                .setTexMix(mTexMix);

        // Wire raster texture draping when enabled
        if (mRasterDrape) {
            TileSource rasterSource = createRasterSource();
            if (rasterSource != null) {
                terrainSource.setRasterSource(rasterSource);
            }
        }

        mTerrainLayer = new TerrainTileLayer(mMap, terrainSource);
        mMap.layers().add(2, mTerrainLayer);

        // Sync building renderer: when terrain is present, buildings test against terrain depth
        if (mBuildingLayer != null) {
            mBuildingLayer.getExtrusionRenderer().setClearDepth(false);
        }

        mMap.updateMap(true);
        System.out.println("TERRAIN: exaggeration = " + mExaggeration + "x"
                + ", drape = " + (mRasterDrape ? "ON" : "OFF"));
    }

    /**
     * Creates an optional raster tile source for texture draping.
     * Returns null if raster sources cannot be configured (e.g. no HTTP engine).
     */
    private TileSource createRasterSource() {
        try {
            return BitmapTileSource.builder()
                    .url("https://tile.openstreetmap.org")
                    .zoomMin(Viewport.MIN_ZOOM_LEVEL)
                    .zoomMax(Viewport.MAX_ZOOM_LEVEL)
                    .httpFactory(new OkHttpEngine.OkHttpFactory())
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
        if (keycode == Input.Keys.F5) {
            if (themeFile != null) {
                mMap.setTheme(new ExternalRenderTheme(themeFile.getAbsolutePath()));
                mMap.clearMap();
            } else if (mTerrainLayer != null) {
                // Toggle raster texture draping on terrain
                mRasterDrape = !mRasterDrape;
                addTerrainLayer();
                System.out.println("Raster drape: " + (mRasterDrape ? "ON" : "OFF"));
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

        return false;
    }

    static File getDemFolder(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("missing argument: <mapFile>");
        }

        File demFolder = new File(args[0]);
        if (demFolder.exists() && demFolder.isDirectory() && demFolder.canRead()) {
            return demFolder;
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
        File demFolder = getDemFolder(args);
        if (demFolder != null)
            args = Arrays.copyOfRange(args, 1, args.length);
        GdxMapApp.run(new MapsforgeTest(demFolder, getMapFiles(args), themeFile));
    }
}

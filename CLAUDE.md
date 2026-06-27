# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

VTM (Vector Tile Map) is a Java OpenGL vector map library targeting Android, Desktop (libGDX/LWJGL), iOS (libGDX/RoboVM), and browser (libGDX/GWT). This is a Mapsforge-compatible fork of the original OpenScienceMap VTM project. All source is under LGPL v3.

## Prerequisites

`ANDROID_HOME` must be set to your Android SDK path. Both `vtm-android` and `vtm-android-example` will fail to configure without it.

## Build Commands

```sh
# Build everything
./gradlew build

# Run all tests
./gradlew :vtm-tests:test

# Run a single test class
./gradlew :vtm-tests:test --tests "org.oscim.utils.ColorTest"

# Run the desktop playground (interactive map viewer)
./gradlew :vtm-playground:run

# Build Android example APK
./gradlew :vtm-android-example:assembleDebug

# Install and launch Android example (device/emulator must be connected)
./gradlew :vtm-android-example:run
```

## Module Structure

The project is a Gradle multi-module build. Active modules (per `settings.gradle`):

| Module | Purpose |
|---|---|
| `vtm` | Core library — map model, layers, renderer, tiling, themes |
| `vtm-android` | Android backend (GL, canvas, gestures); requires native `.so` JARs |
| `vtm-android-example` | Android demo application |
| `vtm-android-mvt` | Android-specific MBTiles/MVT support |
| `vtm-desktop` | Desktop backend (AWT canvas, SVG) |
| `vtm-desktop-lwjgl` | LWJGL 2 launcher |
| `vtm-desktop-lwjgl3` | LWJGL 3 launcher |
| `vtm-gdx` | Common libGDX backend |
| `vtm-gdx-poi3d` | 3D POI rendering |
| `vtm-extras` | Extra utilities |
| `vtm-hillshading` | Hillshading from HGT DEM files |
| `vtm-http` | Online tile loading via OkHttp |
| `vtm-json` | GeoJSON tile source |
| `vtm-jts` | JTS geometry overlay support |
| `vtm-models` | 3D model rendering |
| `vtm-mvt` | Mapbox Vector Tile / MBTiles decoding |
| `vtm-playground` | Desktop example/test application |
| `vtm-themes` | Bundled XML render themes |
| `vtm-tests` | JUnit tests (source in `test/`, resources in `resources/`) |
| `jni` | Native tessellation code (C, built via gdx-jnigen) |

iOS, web, and some other modules exist in the repo but are commented out in `settings.gradle`.

## Architecture

### Core Layer Stack (`vtm`)

All source lives under `org.oscim.*`:

- **`map/Map`** — abstract base class extended by each platform (e.g., `AndroidMap`, `GdxMap`). Owns the `Layers` list, `Viewport`, `Animator`, and the render/update event bus.
- **`layers/Layer`** — base class for everything rendered on the map. Layers hold a `LayerRenderer` and are stacked in `Map.layers()`.
- **`renderer/`** — OpenGL rendering pipeline. `MapRenderer` drives the GL thread; `LayerRenderer` instances are called per frame. Geometry is batched into `RenderBucket` subclasses (`LineBucket`, `PolygonBucket`, `TextBucket`, `SymbolItem`, etc.) before upload.
- **`tiling/`** — tile loading infrastructure. `TileSource` → `ITileDataSource` → `ITileDataSink` pipeline. `TileLayer`/`TileManager` coordinate async tile fetching and caching.
- **`backend/`** — platform abstraction interfaces (`GLAdapter`, `CanvasAdapter`, `AssetAdapter`, `DateTimeAdapter`). Each platform module provides concrete implementations.
- **`theme/`** — XML render theme parsing (`XmlThemeBuilder`) and matching (`RenderTheme`, `IRenderTheme`). Themes are XML files; bundled themes live in `vtm-themes/resources/assets/vtm/`.
- **`core/`** — fundamental data types: `GeoPoint`, `MapPosition`, `BoundingBox`, `GeometryBuffer`, `Tag`/`TagSet`, `Tile`.

### Platform Backend Pattern

Each platform module (e.g., `vtm-android`) implements the backend interfaces and provides a concrete `Map` subclass that wires up the GL surface, gesture input, and asset loading. The core `vtm` module never imports platform classes.

### Tile Sources

Built-in tile sources include Mapsforge map files (`MapFileTileSource`), MBTiles (`vtm-mvt`), Mapbox/GeoJSON vector tiles, bitmap (raster) tiles, and OSciMap4. The `vtm-http` module provides the `OkHttpEngine` for network requests.

## Code Conventions

- **Encoding**: UTF-8
- **Indentation**: 4 spaces, no tabs
- **Java version**: Source/target compatibility Java 8
- **License header**: LGPL v3, required on all source files
- PR commits should be squashed; one feature per PR

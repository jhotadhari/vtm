# Phase 4: Globe Projection — Implementation Plan

**Branch**: `feature/3d-terrain`
**Estimated effort**: 9–14 days
**Current state**: Phases 0–3 complete, projection abstraction ready, Mercator path stable

---

## Architecture Overview

### The core insight

VTM currently generates terrain meshes in tile-local flat coordinates: `(tx, ty, tz)` where tx/ty are pure grid positions (0..4096) and only tz uses the projection. For globe, ALL THREE coordinates depend on the projection — we map each (lat, lon, elev) to an ECEF (Earth-Centered, Earth-Fixed) position on a sphere, relative to the tile center.

### Key design decisions

| Decision | Choice | Rationale |
|---|---|---|
| Coordinate space | ECEF relative to tile sphere-center | Keeps vertices in short range for 16-bit encoding |
| Camera model | Orbital camera replacing flat frustum | Natural for globe navigation |
| Tile mapping | Same Mercator tiles, geometry mapped to sphere at mesh gen time | No change to tile fetching pipeline |
| Vertex format | No change (4 shorts: x, y, z, normal) | Backward compatible, same VBO layout |
| Shader | New `terrain_globe.glsl` + atmosphere variant | Separate from flat terrain shader for clarity |
| Backward compat | Globe mode is opt-in via `GlobeTerrainProjection` + `GlobeViewController` | Mercator path unchanged |

### Rendering pipeline comparison

```
MERCATOR (current):                    GLOBE (new):
┌─────────────────────┐               ┌─────────────────────────┐
│ Mesh gen: flat grid │               │ Mesh gen: ECEF patch    │
│ tx = i*step         │               │ (lat,lon,elev)→ECEF     │
│ ty = j*step         │               │ relative to tile center │
│ tz = elevToTileZ()  │               │                         │
└────────┬────────────┘               └───────────┬─────────────┘
         ▼                                        ▼
┌─────────────────────┐               ┌─────────────────────────┐
│ setMatrix:          │               │ setMatrix:              │
│ translate(x,y)      │               │ rotate + translate to   │
│ scale(scale)        │               │ tile center on sphere   │
└────────┬────────────┘               └───────────┬─────────────┘
         ▼                                        ▼
┌─────────────────────┐               ┌─────────────────────────┐
│ View matrix:        │               │ View matrix:            │
│ bearing/roll/tilt   │               │ orbital camera          │
│ + flat frustum      │               │ + perspective frustum   │
└────────┬────────────┘               └───────────┬─────────────┘
         ▼                                        ▼
┌─────────────────────┐               ┌─────────────────────────┐
│ Unproject:          │               │ Unproject:              │
│ ray ∩ z=0 plane     │               │ ray ∩ sphere            │
└─────────────────────┘               └─────────────────────────┘
```

---

## Work Items (in dependency order)

### Step 1: Extend `TerrainProjection` interface for 3D coordinate output

**Why**: The current interface (`lonToWorldX`, `latToWorldY`, `elevToTileZ`) only produces Z through the projection. Globe needs all 3 axes.

**What**:
- Add method: `void project(float lat, float lon, float elevMeters, float[] outXYZ, double tileCenterX, double tileCenterY, long mapSize)`
  - Returns tile-local (x, y, z) relative to the tile origin
  - For Mercator: delegates to existing lonToWorldX/latToWorldY/elevToTileZ (existing behavior)
  - For Globe: computes ECEF relative to tile center on sphere
- Add default implementation in the interface that calls existing methods (backward compat)
- Add method: `float getSphereRadius()` — returns 0 for Mercator (flat), configurable for Globe
- Add method: `void getTileCenterECEF(double tileCenterLat, double tileCenterLon, double scale, float[] outECEF)` — for Globe, returns the ECEF position of a tile's geographic center; for Mercator returns (0,0,0)

**Files**:
- `vtm-terrain/src/org/oscim/terrain/projection/TerrainProjection.java` — add methods
- `vtm-terrain/src/org/oscim/terrain/projection/MercatorTerrainProjection.java` — implement defaults

**Tests**: 3–4 tests verifying Mercator backward compat


### Step 2: Implement `GlobeTerrainProjection`

**Why**: The second concrete projection — maps geographic coords to ECEF positions on a sphere.

**What**:
- Constructor: `GlobeTerrainProjection(float sphereRadius)` — radius in rendering units
  - Default: 4096.0 (one tile's worth in Mercator coords — maps well to the existing coordinate scale)
- `project(lat, lon, elev, out, tileCenterX, tileCenterY, mapSize)`:
  1. Compute geographic position of the tile center (lat, lon)
  2. Compute tile center's ECEF position on sphere: `centerECEF = latLonToECEF(centerLat, centerLon, sphereRadius)`
  3. Compute vertex's ECEF position: `vertexECEF = latLonToECEF(lat, lon, sphereRadius + elevScaled)`
  4. Return `vertexECEF - centerECEF` as tile-local (x, y, z)
- `elevToTileZ(elevMeters, lat, scale)`: convert elevation meters to radial offset from sphere
  - `metersToRadialOffset = elevMeters / EARTH_RADIUS * sphereRadius`
  - `EARTH_RADIUS = 6371000.0` (mean Earth radius in meters)
- `getBaseNormal(lat, lon, out)`: spherical surface normal at (lat, lon)
  - `out = normalize(latLonToECEF(lat, lon, 1.0))` — unit vector from Earth center
- `latLonToECEF(lat, lon, radius)` utility:
  ```
  x = radius * cos(lat) * cos(lon)
  y = radius * cos(lat) * sin(lon)
  z = radius * sin(lat)
  ```
- `getTileCenterECEF(centerLat, centerLon, scale, out)`: returns the absolute ECEF position of a tile center (used by setMatrix)

**Key considerations**:
- Tile-local range: for a 65×65 grid at zoom 14, the curved patch spans ~2.4 km on Earth. At sphere radius 4096, that's a tile-local span of ~1.5 units — well within short range
- Coordinate encoding: ECEF differences produce small values that fit in 16-bit shorts with COORD_SCALE=16
- Ocean/no-data handling: no-data vertices use sphere radius (sea level on globe surface)

**Files**:
- `vtm-terrain/src/org/oscim/terrain/projection/GlobeTerrainProjection.java` — new file (~120 lines)

**Tests**: 8–10 tests:
- latLonToECEF: equator, pole, prime meridian, known points
- project: vertex at tile center → near-zero tile-local coords
- project: vertex at tile edge → correct offset direction
- getBaseNormal: equator (0,0,1), north pole (0,1,0), arbitrary point
- elevToTileZ: scaling linear, no-data returns 0
- Roundtrip: project → getBaseNormal consistency


### Step 3: Update `TerrainUtils.generateTerrainMesh` for globe coordinates

**Why**: Currently x=tx, y=ty are pure grid positions. For globe, the projection must compute all 3.

**What**:
- When `projection.getType() == GLOBE`:
  - Call `projection.project(lat, lon, elev, outXYZ, tileOriginX, tileOriginY, mapSize)` for each vertex
  - Use outXYZ[0], outXYZ[1], outXYZ[2] instead of tx, ty, tz
  - The vertex positions are now ECEF-relative, not flat grid positions
  - The coordinate range may exceed TILE_SCALE_MAX slightly due to curvature and elevation
- When `projection.getType() == MERCATOR`:
  - Use existing flat grid (tx, ty, projection.elevToTileZ)
  - No change in behavior

**Mesh generation changes**:
- Remove `float tx = i * step`, `float ty = j * step` for globe mode
- Call `projection.project()` for each vertex
- Adjust normal computation: for globe, base normal varies per vertex (spherical normals)

**Files**:
- `vtm-terrain/src/org/oscim/terrain/TerrainUtils.java` — modify `generateTerrainMesh` and `computeTerrainNormals`

**Tests**: Update existing mesh generation tests with Globe projection variant


### Step 4: Implement `GlobeViewController`

**Why**: Replaces the flat-plane view matrix with an orbital camera around the sphere.

**What**:
- New class: `GlobeViewController extends ViewController`
- Overrides `setViewSize()`: different frustum setup for globe rendering
- New camera state:
  - `mCameraDistance`: distance from sphere surface (maps to zoom)
  - `mOrbitLat`, `mOrbitLon`: camera position on an orbital sphere around the globe
  - Sphere center at world origin (0, 0, 0)
- `updateMatrices()` overrides:
  1. Compute camera ECEF position from orbit angles + distance:
     ```
     cameraX = (R + distance) * cos(orbitLat) * cos(orbitLon)
     cameraY = (R + distance) * cos(orbitLat) * sin(orbitLon)
     cameraZ = (R + distance) * sin(orbitLat)
     ```
  2. Look-at point = sphere center (0, 0, 0) or the visible point on the sphere
  3. Use `GLMatrix.lookAt()` to build view matrix
  4. Perspective frustum centered on the view direction
- Touch input mapping:
  - Drag → rotate globe (modify orbitLat/orbitLon)
  - Scroll → change camera distance (zoom)
  - Pinch → change camera distance
- `rotateMap(radians, pivotX, pivotY)` → orbit around sphere
- `tiltMap(move)` → change orbitLat (move camera up/down relative to globe)
- `moveMap(mx, my)` → orbit around sphere
- `scaleMap(scale, pivotX, pivotY)` → change camera distance
- Map zoom level maps to camera distance:
  - zoom 2 (far): distance = R * 3.0
  - zoom 20 (close): distance = R * 0.01

**Integration with MapPosition**:
- `MapPosition.x/y` in [0,1] are reinterpreted: x → orbitLon angle, y → orbitLat angle
- `MapPosition.scale` → camera distance
- `MapPosition.bearing` → orbit rotation around look-at axis
- `MapPosition.tilt` → orbit elevation angle

**Files**:
- `vtm/src/org/oscim/map/GlobeViewController.java` — new file (~250 lines)
- `vtm/src/org/oscim/map/Map.java` — add `setGlobeMode()` method that swaps ViewController


### Step 5: Globe-aware `unproject()` and `getMapExtents()`

**Why**: Current `unproject()` intersects a ray with the z=0 plane. For globe, it must intersect with a sphere.

**What**:
- In `GlobeViewController` or a `GlobeViewport` subclass:
  - Override `unproject(float x, float y, float[] coords, int position)`:
    1. Compute ray from camera through screen point (same as current)
    2. Ray-sphere intersection: `|ray_origin + t * ray_dir|^2 = R^2`
    3. Solve quadratic for t; take nearest positive intersection
    4. Return intersection point in Mercator coords (for compat) or ECEF
  - Override `getMapExtents()`:
    1. For globe, unproject screen corners to sphere surface
    2. Convert sphere intersection points to geographic coords
    3. Return bounding box in Mercator [0,1] space
  - Override `fromScreenPoint()`: ray-sphere → geographic → Mercator xy

**Ray-sphere intersection**:
```
// Camera pos = camECEF, ray dir = rayDir (normalized)
// Sphere: |P|² = R²
// (cam + t*dir)² = R²
// t² + 2(cam·dir)t + (cam² - R²) = 0
float b = 2 * dot(cam, dir);
float c = dot(cam, cam) - R*R;
float disc = b*b - 4*c;
if (disc < 0) return false; // no intersection (looking away from globe)
float t = (-b - sqrt(disc)) / 2; // nearest intersection
if (t < 0) t = (-b + sqrt(disc)) / 2; // camera inside sphere? use far intersection
vec3 intersection = cam + t * dir;
```

**Files**:
- `vtm/src/org/oscim/map/GlobeViewController.java` — add unproject override
- OR new `vtm/src/org/oscim/map/GlobeViewport.java` — extends Viewport with globe unproject


### Step 6: Globe model matrix in `TerrainTileRenderer.setMatrix`

**Why**: For Mercator, setMatrix does flat translate+scale. For globe, each tile is a curved ECEF patch that needs rotation + translation to its position on the sphere.

**What**:
- Detect projection type at render time (store on TerrainTileLayer or check TileData)
- For `GLOBE`:
  1. Get tile center's ECEF position from projection: `projection.getTileCenterECEF(...)`
  2. Build model matrix:
     - Start with identity
     - Translate to tile center ECEF position
     - No scaling (mesh is already in correct coordinate space)
  3. Simplified: `v.mvp.setTranslation(tileCenterX, tileCenterY, tileCenterZ)` then `v.mvp.multiplyLhs(v.viewproj)`
- For `MERCATOR`: existing behavior unchanged

**Files**:
- `vtm-terrain/src/org/oscim/terrain/layer/TerrainTileRenderer.java` — modify `setMatrix` and `setMatrixTex`
- Need globe render path variant or shader selection


### Step 7: Globe shader — `terrain_globe.glsl`

**Why**: Globe rendering benefits from atmospheric effects at the limb (horizon fog) and different lighting.

**What**:
- Copy `terrain_mesh.glsl` as base
- Add atmosphere/fog:
  - Compute fragment's distance from camera
  - Compute fragment's normal dot view direction (limb detection)
  - Blend to atmosphere color (light blue → white) near the limb
  - `float limbFactor = 1.0 - abs(dot(normal, viewDir))` — high at edges
  - `gl_FragColor = mix(terrainColor, atmosphereColor, limbFactor * fogDensity)`
- Add `u_cameraPos` uniform for view-dependent effects
- Add `u_globeRadius` uniform
- Keep existing lighting and height-based coloring

**Files**:
- `vtm/resources/assets/shaders/terrain_globe.glsl` — new (~80 lines)


### Step 8: Globe texture shader — `terrain_globe_tex.glsl`

**Why**: Same as terrain_globe but with texture sampling for raster/vector drape.

**What**:
- Copy `terrain_tex.glsl`, add atmosphere/fog from terrain_globe
- Same UV-from-position approach works (mesh coordinates are still tile-local)

**Files**:
- `vtm/resources/assets/shaders/terrain_globe_tex.glsl` — new (~100 lines)


### Step 9: Shader loading in `TerrainTileRenderer`

**Why**: Need to load and select the correct shader based on projection type.

**What**:
- Add `GlobeShader` inner class (similar to `TerrainTexShader`)
- Add `GlobeTexShader` inner class
- In `update()`/`render()`: check projection type, select shader
- Store projection type on renderer (set during layer construction)

**Files**:
- `vtm-terrain/src/org/oscim/terrain/layer/TerrainTileRenderer.java` — add globe shader support


### Step 10: `ElevationProvider` globe awareness

**Why**: Building and label layers use `ElevationProvider.metersToTileZ()` which assumes flat Mercator ground scale. For globe, elevation offset must be radial.

**What**:
- Add `metersToTileZGlobe(float meters)` to `ElevationProvider.Sampler`
  - Converts meters to radial offset in tile-local ECEF units
  - `return meters / EARTH_RADIUS * sphereRadius`
- Update `TerrainTileLayer` to register the globe variant when using `GlobeTerrainProjection`
- Update `BuildingLayer`: apply radial offset along surface normal (from ElevationProvider) instead of flat z-offset
- Update `TextRenderer`: same radial offset for labels

**Files**:
- `vtm/src/org/oscim/terrain/ElevationProvider.java` — add method to Sampler interface
- `vtm-terrain/src/org/oscim/terrain/layer/TerrainTileLayer.java` — register globe-aware sampler
- `vtm/src/org/oscim/layers/tile/buildings/BuildingLayer.java` — use radial offset for globe
- `vtm/src/org/oscim/layers/tile/vector/labeling/TextRenderer.java` — use radial offset for globe


### Step 11: Tile culling for globe

**Why**: Tiles behind the globe (opposite side of the sphere) should not render. Simple dot-product test can cull most invisible tiles.

**What**:
- In `TerrainTileDataSource.query()` or `TerrainTileRenderer.update()`:
  - Compute tile center's geographic position
  - Project to ECEF
  - Dot product with camera-to-globe-center vector
  - If dot < cos(maxVisibleAngle), skip tile
- For the tile manager: add a simple `isTileVisibleOnGlobe()` check
- At zoom 2–5 (zoomed far out), only ~40% of tiles are visible

**Files**:
- `vtm-terrain/src/org/oscim/terrain/layer/TerrainTileRenderer.java` — culling in update loop
- Or `vtm-terrain/src/org/oscim/terrain/tiling/TerrainTileDataSource.java` — culling in query


### Step 12: Playground integration

**Why**: Need to test and demonstrate globe mode in the playground.

**What**:
- Add `G` key to toggle between Mercator and Globe projection
- Create `GlobeTerrainProjection` with configurable radius
- Wire `GlobeViewController` into the Map
- Add F13/F14 for globe radius adjustment
- Print current projection mode

**Files**:
- `vtm-playground/src/org/oscim/test/MapsforgeTest.java` — add globe toggle


### Step 13: Tests

**Why**: Verify globe projection math, mesh generation, and edge cases.

**What**:
- `GlobeTerrainProjectionTest` (~10 tests):
  - ECEF position at equator, pole, arbitrary lat/lon
  - Tile-local coordinates at tile center → near-zero
  - Tile-local coordinates at tile edges → correct direction
  - getBaseNormal at various positions
  - elevToTileZ scaling
  - No-data handling
  - Roundtrip: geographic → ECEF → geographic (within tolerance)
- `GlobeMeshGenerationTest` (~5 tests):
  - Globe mesh vertex count matches Mercator
  - Globe mesh vertices in valid short range
  - Globe ocean tile detection
- Update existing `TerrainUtilsTest` with globe projection variants

**Files**:
- `vtm-tests/test/org/oscim/terrain/projection/GlobeTerrainProjectionTest.java` — new
- `vtm-tests/test/org/oscim/terrain/GlobeMeshTest.java` — new


### Step 14: Android compatibility

**Why**: Ensure GLES shader compatibility.

**What**:
- GLES precision qualifiers in globe shaders
- Test on Android emulator with globe mode active
- Verify shader compilation on GLES 2.0 / 3.0

**Files**:
- Shader files only (precision qualifiers)

---

## Dependency Graph

```
Step 1 (interface) ──→ Step 2 (GlobeTerrainProjection)
                    ──→ Step 3 (mesh gen update)
                           │
                    ┌──────┘
                    ▼
              Step 4 (GlobeViewController)
                    │
              ┌─────┴─────┐
              ▼             ▼
        Step 5 (unproject)  Step 6 (setMatrix)
              │             │
              └─────┬───────┘
                    ▼
              Step 7 (shader)
                    │
                    ▼
              Step 8 (tex shader)
                    │
                    ▼
              Step 9 (shader loading)
                    │
              ┌─────┴─────┐
              ▼             ▼
        Step 10 (Elevation) Step 11 (culling)
              │             │
              └─────┬───────┘
                    ▼
              Step 12 (playground)
                    │
                    ▼
              Step 13 (tests)
                    │
                    ▼
              Step 14 (Android verify)
```

Steps 1-3 are the foundation. Steps 4-6 are the camera+rendering core. Steps 7-9 are shader work. Steps 10-11 are polish. Step 12 wires it up. Step 13 validates. Step 14 is a final check.

Steps 1+2 can be done together. Steps 7+8 can be done together. Steps 10 and 11 are independent. Step 5 and 6 are independent (different files).

---

## Risk Items

| Risk | Likelihood | Mitigation |
|---|---|---|
| ECEF coordinates overflow short range | Low | Monitor range during development; clamp or scale if needed |
| Performance regression from sphere math per-vertex | Low | Mesh gen is on loader thread; sphere math is ~15 float ops/vertex — negligible vs I/O |
| Camera controls feel wrong for globe | Medium | Iterate on orbit sensitivity; keep Mercator path as fallback |
| Building/label radial offset looks wrong | Medium | Test with tall buildings near tile edges; may need interpolation |
| Shader compilation failures on GLES | Low | Include GLES precision qualifiers from day 1 |
| Ray-sphere unproject edge cases (camera inside sphere, looking away) | Medium | Handle all cases explicitly; fall back to near/far plane intersection |

---

## Files Summary

### New files (6)
| File | Lines (est.) | Step |
|---|---|---|
| `GlobeTerrainProjection.java` | ~120 | 2 |
| `GlobeViewController.java` | ~250 | 4 |
| `terrain_globe.glsl` | ~80 | 7 |
| `terrain_globe_tex.glsl` | ~100 | 8 |
| `GlobeTerrainProjectionTest.java` | ~100 | 13 |
| `GlobeMeshTest.java` | ~60 | 13 |

### Modified files (9)
| File | Change | Step |
|---|---|---|
| `TerrainProjection.java` | Add `project()`, `getSphereRadius()`, `getTileCenterECEF()` | 1 |
| `MercatorTerrainProjection.java` | Implement new interface methods (delegate to existing) | 1 |
| `TerrainUtils.java` | Globe-aware mesh generation | 3 |
| `TerrainTileRenderer.java` | Globe setMatrix + shader selection | 6, 9 |
| `Viewport.java` / `GlobeViewController.java` | Override unproject for sphere intersection | 5 |
| `ElevationProvider.java` | Globe radial offset method | 10 |
| `BuildingLayer.java` | Globe radial adjustment | 10 |
| `TextRenderer.java` | Globe label adjustment | 10 |
| `MapsforgeTest.java` | Globe toggle + controls | 12 |

---

## Verification Checklist (per original plan)

1. ✓ Globe rendering with `GlobeTerrainProjection` — visual inspection at multiple zoom levels
2. ✓ Horizon clipping — tiles behind the globe should not render
3. ✓ Camera orbit controls — drag to rotate globe, scroll to zoom
4. ✓ Terrain + buildings + labels on globe surface — all layers positioned correctly
5. ✓ Fallback to Mercator — toggle between modes, app doesn't crash
6. ✓ Ocean rendering — globe shows blue sphere where ocean tiles would be (or sea-level mesh)
7. ✓ Shader compatibility — GLES and desktop GL both compile globe shaders
8. ✓ Unit tests pass — `./gradlew :vtm-tests:test`
9. ✓ No performance regression in Mercator mode

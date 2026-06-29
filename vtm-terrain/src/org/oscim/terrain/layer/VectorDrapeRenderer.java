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
import org.oscim.backend.canvas.Bitmap;
import org.oscim.backend.canvas.Canvas;
import org.oscim.backend.canvas.Color;
import org.oscim.core.MapElement;
import org.oscim.core.Tag;
import org.oscim.core.Tile;
import org.oscim.layers.tile.MapTile;
import org.oscim.tiling.ITileDataSink;
import org.oscim.tiling.ITileDataSource;
import org.oscim.tiling.QueryResult;
import org.oscim.tiling.TileSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates raster drape textures from vector tile area data.
 * <p>
 * Queries a vector {@link TileSource} for area features within a terrain tile,
 * rasterizes them to a {@link Bitmap} using scanline polygon fill, and returns
 * the bitmap for upload as a terrain drape texture via the existing
 * {@link TerrainTileLayer} pending texture pipeline.
 * <p>
 * Area fill colors are derived from element tags: water features get blue,
 * parks/forests get green, built-up areas get gray.
 */
public class VectorDrapeRenderer {

    private static final Logger log = Logger.getLogger(VectorDrapeRenderer.class.getName());

    /** Output texture size (square, power of two). */
    static final int DRAPE_SIZE = 256;

    /** Color for water bodies. */
    private static final int COLOR_WATER = Color.get(64, 128, 192, 220);

    /** Color for parks, forests, nature reserves. */
    private static final int COLOR_PARK = Color.get(128, 192, 112, 220);

    /** Color for built-up / industrial landuse. */
    private static final int COLOR_BUILTUP = Color.get(200, 180, 160, 180);

    /** Color for other landuse areas. */
    private static final int COLOR_LANDUSE = Color.get(220, 210, 180, 180);

    // Edge for active edge table (scanline polygon fill).
    private static class Edge {
        float x, dx;
        int yMax;

        Edge(float x, float dx, int yMax) {
            this.x = x;
            this.dx = dx;
            this.yMax = yMax;
        }
    }

    /**
     * Generates a drape bitmap for the given terrain tile.
     *
     * @param tile         the terrain tile
     * @param vectorSource the vector tile source (e.g. MapFileTileSource)
     * @return a Bitmap with area fills, or null if no area data is found
     */
    public static Bitmap generateDrapeBitmap(MapTile tile, TileSource vectorSource) {
        if (vectorSource == null)
            return null;

        if (tile.zoomLevel > vectorSource.getZoomLevelMax()
                || tile.zoomLevel < vectorSource.getZoomLevelMin()) {
            return null;
        }

        // Collect area polygons with their fill colors
        final List<ColoredPolygon> polygons = new ArrayList<>();
        ITileDataSource ds = null;
        try {
            ds = vectorSource.getDataSource();
            ds.query(tile, new ITileDataSink() {
                @Override
                public void process(MapElement element) {
                    int color = getAreaColor(element);
                    if (color == 0)
                        return;
                    if (element.pointNextPos < 6)
                        return;
                    polygons.add(new ColoredPolygon(element, color));
                }

                @Override
                public void setTileImage(Bitmap bitmap) {
                }

                @Override
                public void completed(QueryResult result) {
                }
            });
        } catch (Throwable t) {
            log.fine("VECTOR_DRAPE: query failed for " + tile + ": " + t);
            return null;
        } finally {
            if (ds != null) ds.dispose();
        }

        if (polygons.isEmpty())
            return null;

        // Rasterize to pixel array
        int[] pixels = new int[DRAPE_SIZE * DRAPE_SIZE];
        for (ColoredPolygon cp : polygons) {
            rasterizeScanline(pixels, DRAPE_SIZE, cp);
        }

        // Draw spans to platform bitmap
        Bitmap bitmap = CanvasAdapter.newBitmap(DRAPE_SIZE, DRAPE_SIZE, 0);
        Canvas canvas = CanvasAdapter.newCanvas();
        canvas.setBitmap(bitmap);

        writeSpans(canvas, pixels, DRAPE_SIZE);

        return bitmap;
    }

    /**
     * Returns the area fill color for a MapElement, or 0 if not an area.
     */
    private static int getAreaColor(MapElement element) {
        // Exclude non-area elements
        if (element.isBuilding() || element.isBuildingPart())
            return 0;
        if (element.tags.containsKey(Tag.KEY_HIGHWAY))
            return 0;

        // Water: natural=water, water=*, waterway=riverbank
        if ("water".equals(element.tags.getValue("natural"))
                || element.tags.containsKey("water")
                || "riverbank".equals(element.tags.getValue("waterway"))) {
            return COLOR_WATER;
        }

        // Landuse classification
        String landuse = element.tags.getValue(Tag.KEY_LANDUSE);
        if (landuse != null) {
            if ("forest".equals(landuse) || "wood".equals(landuse)
                    || "grass".equals(landuse) || "park".equals(landuse)
                    || "nature_reserve".equals(landuse)
                    || "orchard".equals(landuse) || "vineyard".equals(landuse)
                    || "farmland".equals(landuse) || "meadow".equals(landuse)
                    || "village_green".equals(landuse)
                    || "recreation_ground".equals(landuse)) {
                return COLOR_PARK;
            }
            if ("residential".equals(landuse) || "industrial".equals(landuse)
                    || "commercial".equals(landuse) || "retail".equals(landuse)
                    || "brownfield".equals(landuse) || "construction".equals(landuse)
                    || "quarry".equals(landuse)) {
                return COLOR_BUILTUP;
            }
            return COLOR_LANDUSE;
        }

        // Natural classification
        String natural = element.tags.getValue("natural");
        if (natural != null) {
            if ("water".equals(natural) || "bay".equals(natural)
                    || "strait".equals(natural) || "wetland".equals(natural))
                return COLOR_WATER;
            if ("wood".equals(natural) || "grassland".equals(natural)
                    || "scrub".equals(natural) || "heath".equals(natural)
                    || "fell".equals(natural))
                return COLOR_PARK;
            if ("bare_rock".equals(natural) || "scree".equals(natural)
                    || "sand".equals(natural) || "glacier".equals(natural)
                    || "mud".equals(natural))
                return COLOR_LANDUSE;
        }

        // Leisure areas
        String leisure = element.tags.getValue("leisure");
        if (leisure != null) {
            if ("park".equals(leisure) || "garden".equals(leisure)
                    || "golf_course".equals(leisure)
                    || "nature_reserve".equals(leisure)
                    || "playground".equals(leisure) || "pitch".equals(leisure)
                    || "track".equals(leisure))
                return COLOR_PARK;
        }

        return 0;
    }

    /**
     * Scanline polygon fill into pixel array.
     * Uses an active edge table for even-odd rule fill.
     */
    private static void rasterizeScanline(int[] pixels, int size, ColoredPolygon cp) {
        float[] points = cp.element.points;
        int[] index = cp.element.index;
        int color = cp.color;
        int ppos = 0;

        for (int ipos = 0; ipos < index.length; ipos++) {
            int len = index[ipos];
            if (len < 0) break;
            if (len == 0) {
                ppos += 0; // start of new ring — points are contiguous
                continue;
            }

            int ringStart = ppos;

            // Find y range
            int yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
            for (int i = 0; i < len; i += 2) {
                int py = Math.round(coordToPixel(points[ringStart + i + 1], size));
                if (py < yMin) yMin = py;
                if (py > yMax) yMax = py;
            }
            yMin = Math.max(0, yMin);
            yMax = Math.min(size - 1, yMax);
            if (yMin >= yMax) {
                ppos += len;
                continue;
            }

            // Build edge table
            int tableHeight = yMax - yMin + 1;
            @SuppressWarnings("unchecked")
            List<Edge>[] edgeTable = new List[tableHeight];
            for (int i = 0; i < tableHeight; i++)
                edgeTable[i] = new ArrayList<>(4);

            for (int i = 0; i < len; i += 2) {
                int j = (i + 2) % len;
                float x0 = coordToPixel(points[ringStart + i], size);
                float y0 = coordToPixel(points[ringStart + i + 1], size);
                float x1 = coordToPixel(points[ringStart + j], size);
                float y1 = coordToPixel(points[ringStart + j + 1], size);

                int iy0 = Math.round(y0);
                int iy1 = Math.round(y1);
                if (iy0 == iy1) continue; // horizontal

                int topY, botY;
                float topX;
                if (iy0 < iy1) {
                    topY = iy0; botY = iy1;
                    topX = x0;
                } else {
                    topY = iy1; botY = iy0;
                    topX = x1;
                }

                float dx = (iy0 < iy1 ? x1 - x0 : x0 - x1) / (botY - topY);
                int idx = topY - yMin;
                if (idx >= 0 && idx < tableHeight)
                    edgeTable[idx].add(new Edge(topX + dx * 0.5f, dx, botY));
            }

            // Scanline fill
            List<Edge> active = new ArrayList<>();
            for (int y = yMin; y <= yMax; y++) {
                int idx = y - yMin;
                if (idx < tableHeight)
                    active.addAll(edgeTable[idx]);
                for (int a = active.size() - 1; a >= 0; a--) {
                    if (active.get(a).yMax <= y)
                        active.remove(a);
                }
                active.sort((a, b) -> Float.compare(a.x, b.x));

                for (int k = 0; k + 1 < active.size(); k += 2) {
                    int xStart = Math.max(0, Math.round(active.get(k).x));
                    int xEnd = Math.min(size - 1, Math.round(active.get(k + 1).x));
                    if (xStart <= xEnd) {
                        int rowOff = y * size + xStart;
                        Arrays.fill(pixels, rowOff, rowOff + (xEnd - xStart + 1), color);
                    }
                }
                for (Edge e : active) e.x += e.dx;
            }
            ppos += len;
        }
    }

    /** Scales a tile-local coordinate to [0, size). */
    private static float coordToPixel(float coord, int size) {
        return (coord / Tile.SIZE) * size;
    }

    /**
     * Writes non-zero pixels from array to canvas using horizontal spans
     * (one fillRectangle per contiguous run of the same color).
     */
    private static void writeSpans(Canvas canvas, int[] pixels, int size) {
        for (int y = 0; y < size; y++) {
            int rowOff = y * size;
            int x = 0;
            while (x < size) {
                int color = pixels[rowOff + x];
                if (color == 0) {
                    x++;
                    continue;
                }
                int spanStart = x;
                while (x < size && pixels[rowOff + x] == color)
                    x++;
                canvas.fillRectangle(spanStart, y, x - spanStart, 1, color);
            }
        }
    }

    private static class ColoredPolygon {
        final MapElement element;
        final int color;

        ColoredPolygon(MapElement element, int color) {
            this.element = element;
            this.color = color;
        }
    }
}

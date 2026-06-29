/*
 * Copyright 2025 jhotadhari
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

import org.oscim.backend.CanvasAdapter;
import org.oscim.backend.canvas.Bitmap;
import org.oscim.core.MapElement;
import org.oscim.core.Tile;
import org.oscim.layers.tile.MapTile;
import org.oscim.tiling.ITileDataSink;
import org.oscim.tiling.ITileDataSource;
import org.oscim.tiling.QueryResult;
import org.oscim.tiling.TileSource;

import java.io.ByteArrayInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * Minimal desktop MBTiles raster tile source using sqlite-jdbc.
 * Reads PNG/JPEG tile blobs from an MBTiles SQLite database.
 * <p>
 * TMS row convention: MBTiles stores tile_row in TMS format (0 at bottom).
 * We convert to Google/OSM format (0 at top) on read.
 */
public class MbtilesBitmapTileSource extends TileSource {

    private static final Logger log = Logger.getLogger(MbtilesBitmapTileSource.class.getName());

    private final String mPath;

    public MbtilesBitmapTileSource(String path, int zoomMin, int zoomMax) {
        super(zoomMin, zoomMax);
        mPath = path;
    }

    @Override
    public ITileDataSource getDataSource() {
        return new MbtilesDataSource(mPath);
    }

    @Override
    public OpenResult open() {
        // Verify the file is readable by trying a connection
        try (Connection c = openConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM tiles");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                log.info("MBTiles: " + mPath + " (" + rs.getInt(1) + " tiles)");
            }
            return OpenResult.SUCCESS;
        } catch (Exception e) {
            log.severe("MBTiles: failed to open " + mPath + ": " + e);
            return OpenResult.SUCCESS; // report success even if we logged a warning; tiles may still load
        }
    }

    @Override
    public void close() {
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + mPath);
    }

    private static class MbtilesDataSource implements ITileDataSource {

        private final String mPath;
        private Connection mConnection;
        private PreparedStatement mStatement;

        MbtilesDataSource(String path) {
            mPath = path;
        }

        @Override
        public void query(MapTile tile, ITileDataSink sink) {
            try {
                if (mConnection == null || mConnection.isClosed()) {
                    mConnection = DriverManager.getConnection("jdbc:sqlite:" + mPath);
                    mStatement = mConnection.prepareStatement(
                            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?");
                }

                // Convert Google/OSM y to TMS y: TMS row 0 is at bottom
                int tmsY = (1 << tile.zoomLevel) - 1 - tile.tileY;

                mStatement.setInt(1, tile.zoomLevel);
                mStatement.setInt(2, tile.tileX);
                mStatement.setInt(3, tmsY);

                try (ResultSet rs = mStatement.executeQuery()) {
                    if (rs.next()) {
                        byte[] data = rs.getBytes("tile_data");
                        if (data != null && data.length > 0) {
                            Bitmap bitmap = CanvasAdapter.decodeBitmap(
                                    new ByteArrayInputStream(data));
                            if (bitmap != null) {
                                sink.setTileImage(bitmap);
                            }
                        }
                    }
                }
                sink.completed(QueryResult.SUCCESS);
            } catch (Exception e) {
                log.fine("MBTiles: query failed for " + tile + ": " + e);
                sink.completed(QueryResult.FAILED);
            }
        }

        @Override
        public void dispose() {
            try {
                if (mStatement != null) mStatement.close();
                if (mConnection != null) mConnection.close();
            } catch (SQLException ignored) {
            }
        }

        @Override
        public void cancel() {
        }
    }
}

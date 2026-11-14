package ru.geekideas.mc.seasons;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Карта биомов в SQLite: plugins/Seasons/biomes.db.
 * Таблицы и смысл полей — в README.md рядом с плагином.
 * original_biome пишется один раз при сканировании и не меняется никогда.
 */
final class Store {

    /** Одна клетка 4×4×4 карты. */
    record Cell(int sectionY, int cellX, int cellY, int cellZ, String original) {}

    /** Строка таблицы chunks: что уже просканировано и в каком состоянии. */
    record ChunkState(int chunkX, int chunkZ, String state) {}

    private final File file;
    private Connection db;

    Store(File file) {
        this.file = file;
    }

    void open() throws SQLException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new SQLException("не удалось создать " + dir);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("нет драйвера SQLite в jar", e);
        }
        db = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        // PRAGMA journal_mode нельзя менять внутри транзакции: сначала режим, потом autocommit off
        try (Statement st = db.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA cache_size=-64000");
        }
        db.setAutoCommit(false);
        try (Statement st = db.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS meta ("
                    + " k TEXT PRIMARY KEY, v TEXT NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS chunks ("
                    + " world TEXT NOT NULL,"
                    + " chunk_x INTEGER NOT NULL,"
                    + " chunk_z INTEGER NOT NULL,"
                    + " state TEXT NOT NULL,"
                    + " cells_total INTEGER NOT NULL,"
                    + " cells_spring INTEGER NOT NULL DEFAULT 0,"
                    + " cells_summer INTEGER NOT NULL DEFAULT 0,"
                    + " cells_autumn INTEGER NOT NULL DEFAULT 0,"
                    + " cells_winter INTEGER NOT NULL DEFAULT 0,"
                    + " cells_original INTEGER NOT NULL,"
                    + " cells_changed INTEGER NOT NULL,"
                    + " scanned_at INTEGER NOT NULL,"
                    + " applied_at INTEGER,"
                    + " PRIMARY KEY (world, chunk_x, chunk_z))");
            st.execute("CREATE TABLE IF NOT EXISTS cells ("
                    + " world TEXT NOT NULL,"
                    + " chunk_x INTEGER NOT NULL,"
                    + " chunk_z INTEGER NOT NULL,"
                    + " section_y INTEGER NOT NULL,"
                    + " cell_x INTEGER NOT NULL,"
                    + " cell_y INTEGER NOT NULL,"
                    + " cell_z INTEGER NOT NULL,"
                    + " original_biome TEXT NOT NULL,"
                    + " applied_state TEXT NOT NULL,"
                    + " PRIMARY KEY (world, chunk_x, chunk_z, section_y, cell_x, cell_y, cell_z))"
                    + " WITHOUT ROWID");
        }
        migrate();
        db.commit();
    }

    /** Досоздать колонки счётчиков в базе, созданной предыдущей версией плагина. */
    private void migrate() throws SQLException {
        Set<String> columns = new HashSet<>();
        try (Statement st = db.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(chunks)")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        }
        for (String column : List.of("cells_spring", "cells_summer", "cells_autumn", "cells_winter")) {
            if (!columns.contains(column)) {
                try (Statement st = db.createStatement()) {
                    st.execute("ALTER TABLE chunks ADD COLUMN " + column + " INTEGER NOT NULL DEFAULT 0");
                }
            }
        }
    }

    void close() {
        if (db == null) {
            return;
        }
        try {
            db.commit();
            db.close();
        } catch (SQLException e) {
            // закрываемся на выключении сервера: последний коммит мог не пройти
        }
        db = null;
    }

    void commit() {
        try {
            db.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("commit: " + e.getMessage(), e);
        }
    }

    String meta(String key) {
        try (PreparedStatement sel = db.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
            sel.setString(1, key);
            try (ResultSet rs = sel.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("meta " + key + ": " + e.getMessage(), e);
        }
    }

    void putMeta(String key, String value) {
        try (PreparedStatement ins = db.prepareStatement(
                "INSERT INTO meta (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v")) {
            ins.setString(1, key);
            ins.setString(2, value);
            ins.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("meta " + key + ": " + e.getMessage(), e);
        }
    }

    /** Ключ чанка: старшие 32 бита — chunk_x, младшие — chunk_z. */
    static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) (key >> 32);
    }

    static int chunkZ(long key) {
        return (int) key;
    }

    /** Все просканированные чанки мира: ключ → состояние. */
    Map<Long, String> chunks(String world) {
        Map<Long, String> known = new HashMap<>();
        try (PreparedStatement sel = db.prepareStatement(
                "SELECT chunk_x, chunk_z, state FROM chunks WHERE world = ?")) {
            sel.setString(1, world);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    known.put(chunkKey(rs.getInt(1), rs.getInt(2)), rs.getString(3));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("чтение карты: " + e.getMessage(), e);
        }
        return known;
    }

    /** Записать результат сканирования чанка. Повторный скан оригинал не перезаписывает. */
    void insertChunk(String world, int chunkX, int chunkZ, List<Cell> cells) {
        try {
            try (PreparedStatement chunk = db.prepareStatement(
                    "INSERT OR IGNORE INTO chunks (world, chunk_x, chunk_z, state, cells_total,"
                            + " cells_original, cells_changed, scanned_at)"
                            + " VALUES (?, ?, ?, 'original', ?, ?, 0, ?)")) {
                chunk.setString(1, world);
                chunk.setInt(2, chunkX);
                chunk.setInt(3, chunkZ);
                chunk.setInt(4, cells.size());
                chunk.setInt(5, cells.size());
                chunk.setLong(6, System.currentTimeMillis());
                chunk.executeUpdate();
            }
            try (PreparedStatement ins = db.prepareStatement(
                    "INSERT OR IGNORE INTO cells (world, chunk_x, chunk_z, section_y, cell_x, cell_y,"
                            + " cell_z, original_biome, applied_state)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'original')")) {
                for (Cell cell : cells) {
                    ins.setString(1, world);
                    ins.setInt(2, chunkX);
                    ins.setInt(3, chunkZ);
                    ins.setInt(4, cell.sectionY());
                    ins.setInt(5, cell.cellX());
                    ins.setInt(6, cell.cellY());
                    ins.setInt(7, cell.cellZ());
                    ins.setString(8, cell.original());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("скан чанка " + chunkX + "," + chunkZ + ": " + e.getMessage(), e);
        }
    }

    /** Клетки чанка в порядке (section_y, cell_y, cell_z, cell_x). */
    List<Cell> cells(String world, int chunkX, int chunkZ) {
        List<Cell> cells = new ArrayList<>();
        try (PreparedStatement sel = db.prepareStatement(
                "SELECT section_y, cell_x, cell_y, cell_z, original_biome FROM cells"
                        + " WHERE world = ? AND chunk_x = ? AND chunk_z = ?"
                        + " ORDER BY section_y, cell_y, cell_z, cell_x")) {
            sel.setString(1, world);
            sel.setInt(2, chunkX);
            sel.setInt(3, chunkZ);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    cells.add(new Cell(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5)));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("клетки чанка " + chunkX + "," + chunkZ + ": " + e.getMessage(), e);
        }
        return cells;
    }

    /**
     * Отметить состояние сезона у клеток чанка: season ставится клеткам, чей original_biome
     * входит в сезонный набор (placeholders — список имён), остальные остаются 'original'.
     */
    void putCellState(String world, int chunkX, int chunkZ, String state, List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(names.size(), "?"));
        String sql = "UPDATE cells SET applied_state = ? WHERE world = ? AND chunk_x = ? AND chunk_z = ?"
                + " AND original_biome IN (" + placeholders + ")";
        try (PreparedStatement upd = db.prepareStatement(sql)) {
            int i = 1;
            upd.setString(i++, state);
            upd.setString(i++, world);
            upd.setInt(i++, chunkX);
            upd.setInt(i++, chunkZ);
            for (String name : names) {
                upd.setString(i++, name);
            }
            upd.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("состояние клеток " + chunkX + "," + chunkZ + ": " + e.getMessage(), e);
        }
    }

    /** Счётчики клеток по состояниям (индекс = Season.ordinal()) и число переписанных клеток. */
    void putChunkState(String world, int chunkX, int chunkZ, String state, int[] cellsByState, int changed) {
        try (PreparedStatement upd = db.prepareStatement(
                "UPDATE chunks SET state = ?, cells_spring = ?, cells_summer = ?, cells_autumn = ?,"
                        + " cells_winter = ?, cells_original = ?, cells_changed = ?, applied_at = ?"
                        + " WHERE world = ? AND chunk_x = ? AND chunk_z = ?")) {
            upd.setString(1, state);
            upd.setInt(2, cellsByState[Season.SPRING.ordinal()]);
            upd.setInt(3, cellsByState[Season.SUMMER.ordinal()]);
            upd.setInt(4, cellsByState[Season.AUTUMN.ordinal()]);
            upd.setInt(5, cellsByState[Season.WINTER.ordinal()]);
            upd.setInt(6, cellsByState[Season.RESTORE.ordinal()]);
            upd.setInt(7, changed);
            upd.setLong(8, System.currentTimeMillis());
            upd.setString(9, world);
            upd.setInt(10, chunkX);
            upd.setInt(11, chunkZ);
            upd.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("состояние чанка " + chunkX + "," + chunkZ + ": " + e.getMessage(), e);
        }
    }

    /** state → {чанков, spring, summer, autumn, winter, original, изменено последней операцией}. */
    Map<String, long[]> totals(String world) {
        Map<String, long[]> totals = new HashMap<>();
        try (PreparedStatement sel = db.prepareStatement(
                "SELECT state, COUNT(*), COALESCE(SUM(cells_spring), 0), COALESCE(SUM(cells_summer), 0),"
                        + " COALESCE(SUM(cells_autumn), 0), COALESCE(SUM(cells_winter), 0),"
                        + " COALESCE(SUM(cells_original), 0), COALESCE(SUM(cells_changed), 0)"
                        + " FROM chunks WHERE world = ? GROUP BY state")) {
            sel.setString(1, world);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    totals.put(rs.getString(1), new long[]{
                            rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                            rs.getLong(6), rs.getLong(7), rs.getLong(8)});
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("статистика: " + e.getMessage(), e);
        }
        return totals;
    }

    long fileSize() {
        return file.length() + new File(file.getPath() + "-wal").length();
    }
}

package com.huidu.farmersdelight.statistics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Accessed by one worker; all batch changes and their receipt commit together. */
public final class SqliteStatisticStore implements AutoCloseable {
    private final Path file;
    private Connection connection;

    public SqliteStatisticStore(Path file) { this.file = file; }

    private Connection connection() throws Exception {
        if (connection != null && !connection.isClosed()) return connection;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (var sql = connection.createStatement()) {
            sql.execute("PRAGMA journal_mode=WAL");
            sql.execute("PRAGMA busy_timeout=1000");
            sql.execute("CREATE TABLE IF NOT EXISTS totals (player TEXT NOT NULL, activity TEXT NOT NULL, item TEXT NOT NULL, amount INTEGER NOT NULL, PRIMARY KEY(player,activity,item))");
            sql.execute("CREATE TABLE IF NOT EXISTS batches (id TEXT PRIMARY KEY, committed_at INTEGER NOT NULL)");
        }
        return connection;
    }

    public Map<StatisticKey, Long> read(UUID player) throws Exception {
        Map<StatisticKey, Long> result = new HashMap<>();
        try (var query = connection().prepareStatement("SELECT activity,item,amount FROM totals WHERE player=?")) {
            query.setString(1, player.toString());
            try (var rows = query.executeQuery()) {
                while (rows.next()) result.put(new StatisticKey(player, rows.getString(1), rows.getString(2)), rows.getLong(3));
            }
        }
        return result;
    }

    public void write(UUID batch, Map<StatisticKey, Long> changes) throws Exception {
        Connection db = connection();
        db.setAutoCommit(false);
        try {
            boolean fresh;
            try (var receipt = db.prepareStatement("INSERT OR IGNORE INTO batches(id,committed_at) VALUES (?,?)")) {
                receipt.setString(1, batch.toString());
                receipt.setLong(2, System.currentTimeMillis());
                fresh = receipt.executeUpdate() == 1;
            }
            if (fresh) {
                try (var update = db.prepareStatement("INSERT INTO totals(player,activity,item,amount) VALUES (?,?,?,?) ON CONFLICT(player,activity,item) DO UPDATE SET amount=amount+excluded.amount")) {
                    for (var change : changes.entrySet()) {
                        if (change.getValue() <= 0) throw new IllegalArgumentException("Statistic increments must be positive");
                        StatisticKey key = change.getKey();
                        update.setString(1, key.player().toString());
                        update.setString(2, key.activity());
                        update.setString(3, key.item());
                        update.setLong(4, change.getValue());
                        update.addBatch();
                    }
                    update.executeBatch();
                }
            }
            db.commit();
        } catch (Exception failure) {
            try { db.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            db.setAutoCommit(true);
        }
    }

    @Override public void close() throws SQLException {
        if (connection != null) connection.close();
    }
}

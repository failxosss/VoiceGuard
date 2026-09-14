package cz.voiceguard.db;

import cz.voiceguard.config.ConfigManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All database work (SQLite, plugins/VoiceGuard/data.db) runs on a single
 * dedicated background thread and is exposed only via CompletableFuture, so
 * callers never block the main thread on disk I/O. SQLite's JDBC driver
 * itself is not safe for concurrent writes from multiple threads, which is
 * exactly why everything is funneled through one executor/one connection.
 */
public final class DatabaseManager {

    private final JavaPlugin plugin;
    private final ConfigManager config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoiceGuard-DB");
        t.setDaemon(true);
        return t;
    });

    private Connection connection;

    public DatabaseManager(JavaPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public CompletableFuture<Void> init() {
        return CompletableFuture.runAsync(() -> {
            try {
                File dataFolder = plugin.getDataFolder();
                if (!dataFolder.exists()) {
                    dataFolder.mkdirs();
                }
                File dbFile = new File(dataFolder, "data.db");
                Class.forName("org.sqlite.JDBC");
                connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

                try (Statement st = connection.createStatement()) {
                    st.execute("""
                            CREATE TABLE IF NOT EXISTS players (
                                uuid TEXT PRIMARY KEY,
                                name TEXT NOT NULL,
                                violation_count INTEGER NOT NULL DEFAULT 0,
                                last_violation INTEGER NOT NULL DEFAULT 0,
                                mute_until INTEGER NOT NULL DEFAULT 0
                            )
                            """);
                    st.execute("""
                            CREATE TABLE IF NOT EXISTS violations (
                                id INTEGER PRIMARY KEY AUTOINCREMENT,
                                uuid TEXT NOT NULL,
                                name TEXT NOT NULL,
                                timestamp INTEGER NOT NULL,
                                recognized_text TEXT,
                                detected_word TEXT NOT NULL,
                                punishment_duration INTEGER NOT NULL
                            )
                            """);
                    st.execute("CREATE INDEX IF NOT EXISTS idx_violations_uuid ON violations(uuid)");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to initialize SQLite database: " + e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<PlayerRecord> getOrCreatePlayer(UUID uuid, String name) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                try (PreparedStatement select = connection.prepareStatement(
                        "SELECT uuid, name, violation_count, last_violation, mute_until FROM players WHERE uuid = ?")) {
                    select.setString(1, uuid.toString());
                    try (ResultSet rs = select.executeQuery()) {
                        if (rs.next()) {
                            return new PlayerRecord(
                                    UUID.fromString(rs.getString("uuid")),
                                    rs.getString("name"),
                                    rs.getInt("violation_count"),
                                    rs.getLong("last_violation"),
                                    rs.getLong("mute_until")
                            );
                        }
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO players (uuid, name, violation_count, last_violation, mute_until) VALUES (?, ?, 0, 0, 0)")) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, name);
                    insert.executeUpdate();
                }
                return new PlayerRecord(uuid, name, 0, 0L, 0L);
            } catch (SQLException e) {
                plugin.getLogger().warning("DB getOrCreatePlayer failed: " + e.getMessage());
                return new PlayerRecord(uuid, name, 0, 0L, 0L);
            }
        }, executor);
    }

    public CompletableFuture<Void> recordViolationAndMute(UUID uuid, String name, String recognizedText,
                                                            String detectedWord, long muteUntil, int newViolationCount,
                                                            long punishmentDurationMillis) {
        return CompletableFuture.runAsync(() -> {
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE players SET name = ?, violation_count = ?, last_violation = ?, mute_until = ? WHERE uuid = ?")) {
                    update.setString(1, name);
                    update.setInt(2, newViolationCount);
                    update.setLong(3, System.currentTimeMillis());
                    update.setLong(4, muteUntil);
                    update.setString(5, uuid.toString());
                    update.executeUpdate();
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO violations (uuid, name, timestamp, recognized_text, detected_word, punishment_duration) VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, name);
                    insert.setLong(3, System.currentTimeMillis());
                    insert.setString(4, config.isStoreTranscriptions() ? recognizedText : null);
                    insert.setString(5, detectedWord);
                    insert.setLong(6, punishmentDurationMillis);
                    insert.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB recordViolationAndMute failed: " + e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<Void> setMuteUntil(UUID uuid, String name, long muteUntil) {
        return CompletableFuture.runAsync(() -> {
            try {
                try (PreparedStatement upsert = connection.prepareStatement(
                        "INSERT INTO players (uuid, name, violation_count, last_violation, mute_until) VALUES (?, ?, 0, 0, ?) " +
                                "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, mute_until = excluded.mute_until")) {
                    upsert.setString(1, uuid.toString());
                    upsert.setString(2, name);
                    upsert.setLong(3, muteUntil);
                    upsert.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB setMuteUntil failed: " + e.getMessage());
            }
        }, executor);
    }

    public CompletableFuture<List<ViolationRecord>> getHistory(UUID uuid, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<ViolationRecord> results = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT id, uuid, name, timestamp, recognized_text, detected_word, punishment_duration " +
                            "FROM violations WHERE uuid = ? ORDER BY timestamp DESC LIMIT ?")) {
                select.setString(1, uuid.toString());
                select.setInt(2, limit);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        results.add(new ViolationRecord(
                                rs.getLong("id"),
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("name"),
                                rs.getLong("timestamp"),
                                rs.getString("recognized_text"),
                                rs.getString("detected_word"),
                                rs.getLong("punishment_duration")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB getHistory failed: " + e.getMessage());
            }
            return results;
        }, executor);
    }

    /**
     * Loads all players whose mute_until is still in the future, used on
     * startup to restore active mutes across a server restart.
     */
    public CompletableFuture<List<PlayerRecord>> getActiveMutes() {
        return CompletableFuture.supplyAsync(() -> {
            List<PlayerRecord> results = new ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT uuid, name, violation_count, last_violation, mute_until FROM players " +
                            "WHERE mute_until > ? OR mute_until = -1")) {
                select.setLong(1, now);
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) {
                        results.add(new PlayerRecord(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("name"),
                                rs.getInt("violation_count"),
                                rs.getLong("last_violation"),
                                rs.getLong("mute_until")
                        ));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("DB getActiveMutes failed: " + e.getMessage());
            }
            return results;
        }, executor);
    }

    public void shutdown() {
        executor.execute(() -> {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
        });
        executor.shutdown();
    }

    public record PlayerRecord(UUID uuid, String name, int violationCount, long lastViolation, long muteUntil) {
    }
}

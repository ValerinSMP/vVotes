package com.valerinsmp.vvotes.db;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class DatabaseManager {
    private final VVotesPlugin plugin;
    private final ConfigService configService;
    private String jdbcUrl;

    public DatabaseManager(VVotesPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    public void initialize() {
        String relativePath = configService.get().sqliteFile();
        File dbFile = new File(plugin.getDataFolder(), relativePath.replace('/', File.separatorChar));
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear carpeta de base de datos: " + parent.getAbsolutePath());
        }

        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL;");
            statement.execute("PRAGMA synchronous=NORMAL;");
            statement.execute("PRAGMA temp_store=MEMORY;");
            statement.execute("PRAGMA foreign_keys=ON;");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                        uuid TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        total_votes REAL NOT NULL DEFAULT 0,
                        daily_votes REAL NOT NULL DEFAULT 0,
                        monthly_votes REAL NOT NULL DEFAULT 0,
                        streak_monthly INTEGER NOT NULL DEFAULT 0,
                        last_vote_day TEXT NOT NULL DEFAULT '',
                        last_month_key TEXT NOT NULL DEFAULT '',
                        last_vote_epoch INTEGER NOT NULL DEFAULT 0
                    );
                    """);

            // Migracion para bases antiguas
            try {
                statement.execute("ALTER TABLE players ADD COLUMN streak_monthly INTEGER NOT NULL DEFAULT 0;");
            } catch (SQLException ignored) {
                // Columna ya existe.
            }

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS global_stats (
                        id INTEGER PRIMARY KEY CHECK (id = 1),
                        daily_votes REAL NOT NULL DEFAULT 0,
                        last_daily_reset TEXT NOT NULL DEFAULT ''
                    );
                    """);

            statement.execute("INSERT OR IGNORE INTO global_stats(id, daily_votes, last_daily_reset) VALUES (1, 0, '');");

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS goal_claims_global (
                        goal_type TEXT NOT NULL,
                        goal_value INTEGER NOT NULL,
                        day_key TEXT NOT NULL,
                        PRIMARY KEY (goal_type, goal_value, day_key)
                    );
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS goal_claims_player (
                        uuid TEXT NOT NULL,
                        goal_type TEXT NOT NULL,
                        goal_value INTEGER NOT NULL,
                        period_key TEXT NOT NULL,
                        PRIMARY KEY (uuid, goal_type, goal_value, period_key)
                    );
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS vote_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        service_name TEXT NOT NULL,
                        amount REAL NOT NULL,
                        multiplier REAL NOT NULL,
                        created_epoch INTEGER NOT NULL
                    );
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS monthly_snapshots (
                        uuid TEXT NOT NULL,
                        player_name TEXT NOT NULL,
                        month_key TEXT NOT NULL,
                        votes REAL NOT NULL,
                        last_update_epoch INTEGER NOT NULL,
                        PRIMARY KEY (uuid, month_key)
                    );
                    """);

            statement.execute("""
                    CREATE TABLE IF NOT EXISTS monthly_draw_history (
                        month_key TEXT PRIMARY KEY,
                        winner_uuid TEXT NOT NULL,
                        winner_name TEXT NOT NULL,
                        top_votes REAL NOT NULL,
                        candidates_count INTEGER NOT NULL,
                        executed_by TEXT NOT NULL,
                        executed_epoch INTEGER NOT NULL,
                        reward_command TEXT NOT NULL
                    );
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo inicializar SQLite", exception);
        }
    }

    public Connection getConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + configService.get().busyTimeoutMs() + ";");
        }
        return connection;
    }

    public void close() {
        // No pool to close.
    }
}

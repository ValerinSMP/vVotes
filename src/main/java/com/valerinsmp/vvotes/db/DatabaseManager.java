package com.valerinsmp.vvotes.db;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Gestiona una conexión SQLite única y persistente.
 * SQLite en modo WAL soporta un único writer concurrente; una conexión
 * compartida elimina el overhead de abrir/cerrar en cada operación.
 * Todas las operaciones deben usarse desde el mismo hilo (Bukkit async scheduler).
 */
public final class DatabaseManager {
    private final VVotesPlugin plugin;
    private final ConfigService configService;
    private Connection sharedConnection;

    public DatabaseManager(VVotesPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
    }

    public void initialize() {
        close(); // cierra conexión previa si existe (ej. en reload)

        String relativePath = configService.get().sqliteFile();
        File dbFile = new File(plugin.getDataFolder(), relativePath.replace('/', File.separatorChar));
        File parent = dbFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("No se pudo crear carpeta de base de datos: " + parent.getAbsolutePath());
        }

        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try {
            sharedConnection = DriverManager.getConnection(jdbcUrl);
            try (Statement statement = sharedConnection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=" + configService.get().busyTimeoutMs() + ";");
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

                // Migración para bases antiguas
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
                        CREATE TABLE IF NOT EXISTS player_preferences (
                            uuid TEXT PRIMARY KEY,
                            mute_vote_announcements INTEGER NOT NULL DEFAULT 0
                        );
                        """);

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

                statement.execute("""
                        CREATE TABLE IF NOT EXISTS pending_votes (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            player_name TEXT NOT NULL,
                            service_name TEXT NOT NULL,
                            created_epoch INTEGER NOT NULL
                        );
                        """);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("No se pudo inicializar SQLite", exception);
        }
    }

    /**
     * Devuelve la conexión compartida. No llamar close() sobre ella;
     * usar try-with-resources solo en los PreparedStatement/ResultSet.
     */
    public Connection getConnection() throws SQLException {
        if (sharedConnection == null || sharedConnection.isClosed()) {
            throw new SQLException("La conexion a la base de datos no esta disponible.");
        }
        return sharedConnection;
    }

    public void close() {
        if (sharedConnection != null) {
            try {
                sharedConnection.close();
            } catch (SQLException ignored) {
            }
            sharedConnection = null;
        }
    }
}

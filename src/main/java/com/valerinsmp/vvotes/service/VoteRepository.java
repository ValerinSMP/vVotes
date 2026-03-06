package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.db.DatabaseManager;
import com.valerinsmp.vvotes.model.PlayerStats;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class VoteRepository {

    private final DatabaseManager database;

    VoteRepository(DatabaseManager database) {
        this.database = database;
    }

    Connection connection() throws SQLException {
        return database.getConnection();
    }

    // ── Players ──────────────────────────────────────────────────────────────

    PlayerStats fetchOrCreateStats(Connection connection, UUID uuid, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return mapPlayerStats(rs, uuid);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT OR IGNORE INTO players(uuid, name) VALUES (?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return mapPlayerStats(rs, uuid);
            }
        }
        return PlayerStats.empty(uuid, name);
    }

    private PlayerStats mapPlayerStats(ResultSet rs, UUID uuid) throws SQLException {
        return new PlayerStats(
                uuid,
                rs.getString("name"),
                rs.getDouble("total_votes"),
                rs.getDouble("daily_votes"),
                rs.getDouble("monthly_votes"),
                rs.getInt("streak_monthly"),
                rs.getString("last_vote_day"),
                rs.getString("last_month_key"),
                rs.getLong("last_vote_epoch")
        );
    }

    void updatePlayerStats(Connection connection, UUID uuid, String name, double total, double daily,
                           double monthly, int streakMonthly, DateContext context) throws SQLException {
        updatePlayerStats(connection, uuid, name, total, daily, monthly, streakMonthly,
                context.dayKey(), context.monthKey(), context.epochSeconds());
    }

    void updatePlayerStats(Connection connection, UUID uuid, String name, double total, double daily,
                           double monthly, int streakMonthly, String lastVoteDay, String monthKey, long epoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE players
                SET name = ?, total_votes = ?, daily_votes = ?, monthly_votes = ?,
                    streak_monthly = ?, last_vote_day = ?, last_month_key = ?, last_vote_epoch = ?
                WHERE uuid = ?
                """)) {
            statement.setString(1, name);
            statement.setDouble(2, total);
            statement.setDouble(3, daily);
            statement.setDouble(4, monthly);
            statement.setInt(5, streakMonthly);
            statement.setString(6, lastVoteDay);
            statement.setString(7, monthKey);
            statement.setLong(8, epoch);
            statement.setString(9, uuid.toString());
            statement.executeUpdate();
        }
    }

    // ── Global stats ─────────────────────────────────────────────────────────

    GlobalState fetchGlobalState(Connection connection, DateContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT daily_votes, last_daily_reset FROM global_stats WHERE id = 1")) {
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return new GlobalState(0, context.dayKey());
                String reset = rs.getString("last_daily_reset");
                if (!Objects.equals(reset, context.dayKey())) return new GlobalState(0, context.dayKey());
                return new GlobalState(rs.getDouble("daily_votes"), reset);
            }
        }
    }

    void updateGlobalState(Connection connection, double dailyVotes, String dayKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE global_stats SET daily_votes = ?, last_daily_reset = ? WHERE id = 1")) {
            statement.setDouble(1, dailyVotes);
            statement.setString(2, dayKey);
            statement.executeUpdate();
        }
    }

    double readGlobalDailyVotes(Connection connection, String dayKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT daily_votes, last_daily_reset FROM global_stats WHERE id = 1")) {
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return 0;
                if (!Objects.equals(rs.getString("last_daily_reset"), dayKey)) return 0;
                return rs.getDouble("daily_votes");
            }
        }
    }

    // ── Goal claims ──────────────────────────────────────────────────────────

    boolean tryClaimGlobalGoal(Connection connection, String type, int value, String dayKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO goal_claims_global(goal_type, goal_value, day_key) VALUES (?, ?, ?)")) {
            statement.setString(1, type);
            statement.setInt(2, value);
            statement.setString(3, dayKey);
            return statement.executeUpdate() > 0;
        }
    }

    boolean tryClaimPlayerGoal(Connection connection, UUID uuid, String type, int value, String period) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO goal_claims_player(uuid, goal_type, goal_value, period_key) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, type);
            statement.setInt(3, value);
            statement.setString(4, period);
            return statement.executeUpdate() > 0;
        }
    }

    // ── Vote logs ────────────────────────────────────────────────────────────

    void insertVoteLog(Connection connection, UUID uuid, String playerName, String service,
                       double amount, double multiplier) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vote_logs(uuid, player_name, service_name, amount, multiplier, created_epoch) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, service);
            statement.setDouble(4, amount);
            statement.setDouble(5, multiplier);
            statement.setLong(6, Instant.now().getEpochSecond());
            statement.executeUpdate();
        }
    }

    int countDistinctServicesToday(Connection connection, UUID uuid, DateContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(DISTINCT LOWER(service_name)) AS total
                FROM vote_logs
                WHERE uuid = ?
                  AND created_epoch >= ?
                  AND created_epoch < ?
                  AND LOWER(service_name) <> 'manual'
                """)) {
            statement.setString(1, uuid.toString());
            statement.setLong(2, context.dayStartEpoch());
            statement.setLong(3, context.nextDayEpoch());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getInt("total");
            }
        }
        return 0;
    }

    // ── Monthly snapshots ────────────────────────────────────────────────────

    void upsertMonthlySnapshot(Connection connection, UUID uuid, String playerName,
                               String monthKey, double votes, long epoch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO monthly_snapshots(uuid, player_name, month_key, votes, last_update_epoch)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid, month_key) DO UPDATE SET
                    player_name = excluded.player_name,
                    votes = excluded.votes,
                    last_update_epoch = excluded.last_update_epoch
                """)) {
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, monthKey);
            statement.setDouble(4, votes);
            statement.setLong(5, epoch);
            statement.executeUpdate();
        }
    }

    List<TopMonthEntry> fetchTopMonth(Connection connection, String monthKey, int limit) throws SQLException {
        List<TopMonthEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name, votes FROM monthly_snapshots WHERE month_key = ? ORDER BY votes DESC LIMIT ?")) {
            statement.setString(1, monthKey);
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                int pos = 1;
                while (rs.next()) {
                    entries.add(new TopMonthEntry(pos++, rs.getString("player_name"), rs.getDouble("votes")));
                }
            }
        }
        return entries;
    }

    double fetchMaxVotesForMonth(Connection connection, String monthKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(votes), 0) AS max_votes FROM monthly_snapshots WHERE month_key = ?")) {
            statement.setString(1, monthKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) return rs.getDouble("max_votes");
            }
        }
        return 0;
    }

    List<DrawCandidate> fetchCandidatesForTopVotes(Connection connection, String monthKey, double maxVotes) throws SQLException {
        List<DrawCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT uuid, player_name
                FROM monthly_snapshots
                WHERE month_key = ? AND votes = ?
                ORDER BY player_name ASC
                """)) {
            statement.setString(1, monthKey);
            statement.setDouble(2, maxVotes);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    candidates.add(new DrawCandidate(
                            UUID.fromString(rs.getString("uuid")),
                            rs.getString("player_name")
                    ));
                }
            }
        }
        return candidates;
    }

    // ── Monthly draw history ─────────────────────────────────────────────────

    boolean isMonthAlreadyDrawn(Connection connection, String monthKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM monthly_draw_history WHERE month_key = ?")) {
            statement.setString(1, monthKey);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        }
    }

    void insertMonthlyDrawHistory(Connection connection, String monthKey, DrawCandidate winner,
                                  double topVotes, int candidatesCount, String executedBy,
                                  String rewardCommand) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO monthly_draw_history(month_key, winner_uuid, winner_name, top_votes, candidates_count, executed_by, executed_epoch, reward_command)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, monthKey);
            statement.setString(2, winner.uuid().toString());
            statement.setString(3, winner.name());
            statement.setDouble(4, topVotes);
            statement.setInt(5, candidatesCount);
            statement.setString(6, executedBy);
            statement.setLong(7, Instant.now().getEpochSecond());
            statement.setString(8, rewardCommand);
            statement.executeUpdate();
        }
    }

    DrawHistoryResult fetchDrawHistory(Connection connection, String monthKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT winner_name, winner_uuid, top_votes, candidates_count, executed_by, executed_epoch FROM monthly_draw_history WHERE month_key = ?")) {
            statement.setString(1, monthKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return DrawHistoryResult.notFound(monthKey);
                return DrawHistoryResult.found(
                        monthKey,
                        rs.getString("winner_name"),
                        rs.getString("winner_uuid"),
                        rs.getDouble("top_votes"),
                        rs.getInt("candidates_count"),
                        rs.getString("executed_by"),
                        rs.getLong("executed_epoch")
                );
            }
        }
    }

    // ── Pending votes ────────────────────────────────────────────────────────

    void insertPendingVote(Connection connection, String playerName, String serviceName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pending_votes(player_name, service_name, created_epoch) VALUES (?, ?, ?)")) {
            statement.setString(1, playerName);
            statement.setString(2, serviceName);
            statement.setLong(3, Instant.now().getEpochSecond());
            statement.executeUpdate();
        }
    }

    List<PendingVoteRow> fetchPendingVotes(Connection connection, String playerName) throws SQLException {
        List<PendingVoteRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, service_name FROM pending_votes WHERE LOWER(player_name) = LOWER(?)")) {
            statement.setString(1, playerName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new PendingVoteRow(rs.getLong("id"), rs.getString("service_name")));
                }
            }
        }
        return rows;
    }

    void deletePendingVote(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM pending_votes WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    record PendingVoteRow(long id, String serviceName) {}
}

package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.model.PlayerStats;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

/** SQLite event/grant journal. All mutating entry points are serialized. */
public final class VoteLedger implements AutoCloseable {
    private static final int SCHEMA_VERSION = 2;
    private final Path databasePath;
    private final int busyTimeoutMs;
    private final Clock clock;
    private final ZoneId zoneId;

    public VoteLedger(Path databasePath, int busyTimeoutMs, Clock clock, ZoneId zoneId) {
        this.databasePath = databasePath.toAbsolutePath();
        this.busyTimeoutMs = Math.max(1, busyTimeoutMs);
        this.clock = clock;
        this.zoneId = zoneId;
    }

    public synchronized void initialize() {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            backupBeforeMigration();
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                createSchema(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE reward_grants
                    SET state = 'AMBIGUOUS', error = 'interrupted after durable claim', updated_at = ?
                    WHERE state = 'CLAIMED'
                    """)) {
                    statement.setLong(1, nowEpoch());
                    statement.executeUpdate();
                }
                connection.commit();
            }
            validateSchemaParity(databasePath, SCHEMA_VERSION);
        } catch (SQLException | IOException exception) {
            throw new IllegalStateException("Unable to initialize vote ledger", exception);
        }
    }

    public synchronized VoteEventResult accept(VoteEnvelope event, PlayerIdentity identity, VotePlan plan) {
        PeriodContext period = currentPeriod();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (!insertEvent(connection, event, plan, identity == null ? "UNRESOLVED" : "PROCESSING", period)) {
                    connection.rollback();
                    return VoteEventResult.of(VoteEventState.DUPLICATE, event.eventHash(), List.of());
                }
                if (!event.hasEconomicIdentity()) {
                    updateEventState(connection, event.eventHash(), "QUARANTINED", null, period, "missing provider identity");
                    connection.commit();
                    return VoteEventResult.of(VoteEventState.QUARANTINED, event.eventHash(), List.of());
                }
                if (identity == null) {
                    connection.commit();
                    return VoteEventResult.of(VoteEventState.UNRESOLVED, event.eventHash(), List.of());
                }
                if (!identity.normalizedName().equals(event.normalizedName())) {
                    updateEventState(connection, event.eventHash(), "QUARANTINED", null, period, "exact name mismatch");
                    connection.commit();
                    return VoteEventResult.of(VoteEventState.QUARANTINED, event.eventHash(), List.of());
                }
                VoteEventResult result = planEvent(connection, event, identity, plan, period);
                connection.commit();
                return result;
            } catch (SQLException exception) {
                connection.rollback();
                return VoteEventResult.error(event.eventHash(), exception);
            }
        } catch (SQLException exception) {
            return VoteEventResult.error(event.eventHash(), exception);
        }
    }

    public synchronized List<VoteEventResult> resolvePending(PlayerIdentity identity) {
        List<VoteEventResult> results = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT event_hash, display_name, service, provider_timestamp, plan_json, day_key, month_key
                FROM vote_events
                WHERE state IN ('UNRESOLVED', 'UNRESOLVED_LEGACY') AND normalized_name = ?
                ORDER BY created_at, event_hash
                """)) {
            statement.setString(1, identity.normalizedName());
            List<StoredEvent> events = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    events.add(new StoredEvent(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)));
                }
            }
            for (StoredEvent stored : events) {
                connection.setAutoCommit(false);
                try (PreparedStatement claim = connection.prepareStatement("""
                        UPDATE vote_events SET state = 'PROCESSING', resolved_uuid = ?, updated_at = ?
                        WHERE event_hash = ? AND state IN ('UNRESOLVED', 'UNRESOLVED_LEGACY') AND normalized_name = ?
                        """)) {
                    claim.setString(1, identity.uuid().toString());
                    claim.setLong(2, nowEpoch());
                    claim.setString(3, stored.hash());
                    claim.setString(4, identity.normalizedName());
                    if (claim.executeUpdate() == 0) {
                        connection.rollback();
                        continue;
                    }
                    VoteEnvelope event = new VoteEnvelope(stored.hash(), identity.normalizedName(), stored.displayName(),
                            stored.service(), stored.timestamp(), true, "TestVote".equalsIgnoreCase(stored.timestamp()));
                    VoteEventResult result = planEvent(connection, event, identity, VotePlan.fromJson(stored.planJson()),
                            new PeriodContext(stored.dayKey(), stored.monthKey()));
                    connection.commit();
                    results.add(result);
                } catch (SQLException exception) {
                    connection.rollback();
                } finally {
                    connection.setAutoCommit(true);
                }
            }
        } catch (SQLException exception) {
            return List.of();
        }
        return results;
    }

    public synchronized Optional<GrantClaim> claimNextGrant(UUID targetUuid) {
        return claimNext("target_uuid = ?", targetUuid.toString());
    }

    public synchronized Optional<GrantClaim> claimNextGlobalGrant() {
        return claimNext("target_uuid IS NULL", null);
    }

    public synchronized boolean releaseBeforeDispatch(String grantId, String token, String reason) {
        return transition(grantId, token, "PENDING", reason);
    }

    public synchronized boolean markDoneAfterDispatch(String grantId, String token) {
        return transition(grantId, token, "DONE", "");
    }

    public synchronized boolean markAmbiguousAfterDispatch(String grantId, String token, String reason) {
        return transition(grantId, token, "AMBIGUOUS", reason);
    }

    public synchronized List<GrantClaim> listAmbiguous() {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT grant_id, batch_key, sequence, kind, command_snapshot, executor_mode,
                       target_uuid, target_name, claim_token, state, error
                FROM reward_grants WHERE state = 'AMBIGUOUS' ORDER BY updated_at, grant_id
                """)) {
            try (ResultSet rs = statement.executeQuery()) {
                List<GrantClaim> result = new ArrayList<>();
                while (rs.next()) result.add(mapGrant(rs));
                return result;
            }
        } catch (SQLException exception) {
            return List.of();
        }
    }

    public synchronized PlayerStats readStats(UUID uuid, String name) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return PlayerStats.empty(uuid, name);
                return new PlayerStats(uuid, rs.getString("name"), rs.getDouble("total_votes"),
                        rs.getDouble("daily_votes"), rs.getDouble("monthly_votes"),
                        rs.getInt("streak_monthly"), rs.getString("last_vote_day"),
                        rs.getString("last_month_key"), rs.getLong("last_vote_epoch"));
            }
        } catch (SQLException exception) {
            return PlayerStats.empty(uuid, name);
        }
    }

    public synchronized double readGlobalDaily() {
        try (Connection connection = connection()) {
            return readGlobal(connection, currentPeriod().dayKey());
        } catch (SQLException exception) {
            return 0;
        }
    }

    public synchronized Map<UUID, PlayerStats> readAllStats() {
        TreeMap<String, PlayerStats> ordered = new TreeMap<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM players ORDER BY uuid");
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                ordered.put(uuid.toString(), new PlayerStats(uuid, rs.getString("name"), rs.getDouble("total_votes"),
                        rs.getDouble("daily_votes"), rs.getDouble("monthly_votes"), rs.getInt("streak_monthly"),
                        rs.getString("last_vote_day"), rs.getString("last_month_key"), rs.getLong("last_vote_epoch")));
            }
        } catch (SQLException ignored) { }
        TreeMap<UUID, PlayerStats> result = new TreeMap<>();
        ordered.values().forEach(stats -> result.put(stats.uuid(), stats));
        return Map.copyOf(result);
    }

    public synchronized Map<UUID, Boolean> readAllPreferences() {
        TreeMap<UUID, Boolean> result = new TreeMap<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, mute_vote_announcements FROM player_preferences"); ResultSet rs = statement.executeQuery()) {
            while (rs.next()) result.put(UUID.fromString(rs.getString(1)), rs.getInt(2) == 1);
        } catch (SQLException ignored) { }
        return Map.copyOf(result);
    }

    public synchronized boolean togglePreference(UUID uuid) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT OR IGNORE INTO player_preferences(uuid, mute_vote_announcements) VALUES(?,0)")) {
                insert.setString(1, uuid.toString()); insert.executeUpdate();
            }
            boolean current;
            try (PreparedStatement read = connection.prepareStatement(
                    "SELECT mute_vote_announcements FROM player_preferences WHERE uuid=?")) {
                read.setString(1, uuid.toString());
                try (ResultSet rs = read.executeQuery()) { current = rs.next() && rs.getInt(1) == 1; }
            }
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE player_preferences SET mute_vote_announcements=? WHERE uuid=?")) {
                update.setInt(1, current ? 0 : 1); update.setString(2, uuid.toString()); update.executeUpdate();
            }
            connection.commit();
            return !current;
        } catch (SQLException exception) { return false; }
    }

    public synchronized double adjustGlobalDaily(int delta) {
        PeriodContext period = currentPeriod();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            double updated = Math.max(0, readGlobal(connection, period.dayKey()) + delta);
            updateGlobal(connection, updated, period.dayKey());
            connection.commit();
            return updated;
        } catch (SQLException exception) { return -1; }
    }

    public synchronized void resetGlobalDaily() { adjustGlobalDaily(Integer.MIN_VALUE); }

    public synchronized double adjustPlayerDaily(PlayerIdentity identity, int delta) {
        PeriodContext period = currentPeriod();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            PlayerStats stats = fetchOrCreate(connection, identity);
            double daily = period.dayKey().equals(stats.lastVoteDay()) ? stats.dailyVotes() : 0;
            double monthly = period.monthKey().equals(stats.lastMonthKey()) ? stats.monthlyVotes() : 0;
            double updated = Math.max(0, daily + delta);
            updatePlayer(connection, identity, stats.totalVotes(), updated, monthly, stats.streakMonthly(), period);
            connection.commit();
            return updated;
        } catch (SQLException exception) { return -1; }
    }

    public synchronized void resetPlayerMonthly(PlayerIdentity identity) {
        PeriodContext period = currentPeriod();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            PlayerStats stats = fetchOrCreate(connection, identity);
            double daily = period.dayKey().equals(stats.lastVoteDay()) ? stats.dailyVotes() : 0;
            updatePlayer(connection, identity, stats.totalVotes(), daily, 0, stats.streakMonthly(), period);
            connection.commit();
        } catch (SQLException ignored) { }
    }

    public synchronized int distinctServicesToday(UUID uuid) {
        try (Connection connection = connection()) {
            return countDistinctServices(connection, uuid, currentPeriod().dayKey());
        } catch (SQLException exception) { return 0; }
    }

    public synchronized DrawHistoryResult readDrawHistory(String monthKey) {
        try {
            YearMonth.parse(monthKey);
        } catch (Exception invalid) { return DrawHistoryResult.invalidMonth(monthKey); }
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT winner_name,winner_uuid,top_votes,candidates_count,executed_by,executed_epoch FROM monthly_draw_history WHERE month_key=?")) {
            statement.setString(1, monthKey);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return DrawHistoryResult.notFound(monthKey);
                return DrawHistoryResult.found(monthKey, rs.getString(1), rs.getString(2), rs.getDouble(3),
                        rs.getInt(4), rs.getString(5), rs.getLong(6));
            }
        } catch (SQLException exception) { return DrawHistoryResult.error(monthKey, exception.getMessage()); }
    }

    public synchronized List<TopMonthEntry> readTopMonth(String monthKey, int limit) {
        List<TopMonthEntry> result = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT player_name,votes FROM monthly_snapshots WHERE month_key=? ORDER BY votes DESC,player_name LIMIT ?")) {
            statement.setString(1, monthKey); statement.setInt(2, Math.max(1, limit));
            try (ResultSet rs = statement.executeQuery()) {
                int position = 1;
                while (rs.next()) result.add(new TopMonthEntry(position++, rs.getString(1), rs.getDouble(2)));
            }
        } catch (SQLException ignored) { }
        return result;
    }

    public synchronized MonthlyDrawResult planMonthlyDraw(String monthKey, String executedBy, int minimumVotes,
                                                           String rewardCommand, IntUnaryOperator chooser) {
        try {
            YearMonth.parse(monthKey);
        } catch (Exception invalid) {
            return MonthlyDrawResult.invalidMonth(monthKey);
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT 1 FROM monthly_draw_history WHERE month_key = ?")) {
                    existing.setString(1, monthKey);
                    try (ResultSet rs = existing.executeQuery()) {
                        if (rs.next()) {
                            connection.rollback();
                            return MonthlyDrawResult.alreadyDrawn(monthKey);
                        }
                    }
                }
                double maxVotes;
                try (PreparedStatement max = connection.prepareStatement(
                        "SELECT COALESCE(MAX(votes), 0) FROM monthly_snapshots WHERE month_key = ?")) {
                    max.setString(1, monthKey);
                    try (ResultSet rs = max.executeQuery()) { maxVotes = rs.next() ? rs.getDouble(1) : 0; }
                }
                if (maxVotes < Math.max(1, minimumVotes)) {
                    connection.rollback();
                    return MonthlyDrawResult.noParticipants(monthKey, maxVotes);
                }
                List<PlayerIdentity> candidates = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement("""
                        SELECT uuid, player_name FROM monthly_snapshots
                        WHERE month_key = ? AND votes = ? ORDER BY player_name, uuid
                        """)) {
                    query.setString(1, monthKey);
                    query.setDouble(2, maxVotes);
                    try (ResultSet rs = query.executeQuery()) {
                        while (rs.next()) candidates.add(new PlayerIdentity(UUID.fromString(rs.getString(1)), rs.getString(2)));
                    }
                }
                PlayerIdentity winner = candidates.get(Math.floorMod(chooser.applyAsInt(candidates.size()), candidates.size()));
                try (PreparedStatement insert = connection.prepareStatement("""
                        INSERT INTO monthly_draw_history(month_key, winner_uuid, winner_name, top_votes,
                          candidates_count, executed_by, executed_epoch, reward_command)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, monthKey);
                    insert.setString(2, winner.uuid().toString());
                    insert.setString(3, winner.exactName());
                    insert.setDouble(4, maxVotes);
                    insert.setInt(5, candidates.size());
                    insert.setString(6, executedBy);
                    insert.setLong(7, nowEpoch());
                    insert.setString(8, rewardCommand);
                    insert.executeUpdate();
                }
                String command = materialize(rewardCommand, winner, Map.of("month", monthKey));
                insertGrant(connection, "draw:" + monthKey, null, "MONTHLY_DRAW", 0,
                        command, "CONSOLE", null, winner.exactName());
                connection.commit();
                return MonthlyDrawResult.success(monthKey, winner.exactName(), maxVotes, candidates.size());
            } catch (SQLException exception) {
                connection.rollback();
                return MonthlyDrawResult.error(monthKey, exception.getMessage());
            }
        } catch (SQLException exception) {
            return MonthlyDrawResult.error(monthKey, exception.getMessage());
        }
    }

    public synchronized int migrateLegacyPending(VotePlan frozenPlan) {
        int migrated = 0;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT p.id, p.player_name, p.service_name, p.created_epoch
                    FROM pending_votes p LEFT JOIN legacy_pending_migrations m ON m.pending_id = p.id
                    WHERE m.pending_id IS NULL ORDER BY p.id
                    """)) {
                List<LegacyRow> rows = new ArrayList<>();
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) rows.add(new LegacyRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                }
                for (LegacyRow row : rows) {
                    String normalizedName = normalize(row.playerName());
                    String service = normalize(row.serviceName());
                    String hash = VoteEnvelope.hashFields("vvotes-legacy-pending-v1", Long.toString(row.id()),
                            normalizedName, service, Long.toString(row.createdEpoch()));
                    try (PreparedStatement event = connection.prepareStatement("""
                            INSERT OR IGNORE INTO vote_events(event_hash, normalized_name, display_name, service,
                              provider_timestamp, state, plan_json, created_at, updated_at, day_key, month_key, failure)
                            VALUES (?, ?, ?, ?, '', 'UNRESOLVED_LEGACY', ?, ?, ?, ?, ?, 'legacy pending row; not provider identity')
                            """)) {
                        PeriodContext period = periodAt(row.createdEpoch());
                        event.setString(1, hash);
                        event.setString(2, normalizedName);
                        event.setString(3, row.playerName());
                        event.setString(4, service);
                        event.setString(5, frozenPlan.toJson());
                        event.setLong(6, row.createdEpoch());
                        event.setLong(7, nowEpoch());
                        event.setString(8, period.dayKey());
                        event.setString(9, period.monthKey());
                        event.executeUpdate();
                    }
                    try (PreparedStatement mapping = connection.prepareStatement(
                            "INSERT OR IGNORE INTO legacy_pending_migrations(pending_id, event_hash, migrated_at) VALUES (?, ?, ?)")) {
                        mapping.setLong(1, row.id());
                        mapping.setString(2, hash);
                        mapping.setLong(3, nowEpoch());
                        migrated += mapping.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                return 0;
            }
        } catch (SQLException exception) {
            return 0;
        }
        return migrated;
    }

    public long countEvents() { return count("vote_events", null); }
    public long countGrants() { return count("reward_grants", null); }
    public long countGrantsByKind(String kind) { return count("reward_grants", kind); }
    public long countLegacyPendingRows() { return count("pending_votes", null); }

    @Override public void close() { }

    private VoteEventResult planEvent(Connection connection, VoteEnvelope event, PlayerIdentity identity,
                                      VotePlan plan, PeriodContext period) throws SQLException {
        PlayerStats stats = fetchOrCreate(connection, identity);
        boolean newestDay = stats.lastVoteDay().isBlank() || period.dayKey().compareTo(stats.lastVoteDay()) >= 0;
        boolean newestMonth = stats.lastMonthKey().isBlank() || period.monthKey().compareTo(stats.lastMonthKey()) >= 0;
        double daily = newestDay
                ? (period.dayKey().equals(stats.lastVoteDay()) ? stats.dailyVotes() + 1 : 1)
                : stats.dailyVotes();
        double monthly = readMonthlySnapshot(connection, identity.uuid(), period.monthKey()) + 1;
        double projectedMonthly = newestMonth ? monthly : stats.monthlyVotes();
        int streak = newestMonth
                ? computeMonthlyStreak(stats.streakMonthly(), stats.lastMonthKey(), period.monthKey())
                : stats.streakMonthly();
        double total = stats.totalVotes() + 1;
        updatePlayerProjection(connection, identity, total, daily, projectedMonthly, streak,
                newestDay ? period.dayKey() : stats.lastVoteDay(),
                newestMonth ? period.monthKey() : stats.lastMonthKey());
        upsertSnapshot(connection, identity, period.monthKey(), monthly);

        double previousGlobal = readGlobal(connection, period.dayKey());
        double global = previousGlobal + 1;
        updateGlobalPeriod(connection, global, period.dayKey());
        updateEventState(connection, event.eventHash(), "PROCESSING", identity.uuid(), period, "");

        List<String> grants = new ArrayList<>();
        List<VoteNotice> notices = new ArrayList<>();
        addBatch(connection, grants, event, identity, "VOTE", plan.voteCommands(), "CONSOLE", Map.of());
        addBatch(connection, grants, event, identity, "SERVICE_PLAYER", plan.servicePlayerCommands(), "PLAYER", Map.of());

        for (Map.Entry<Integer, List<String>> goal : plan.monthlyGoals().entrySet()) {
            if (monthly >= goal.getKey() && claimPlayerGoal(connection, identity.uuid(), "monthly", goal.getKey(), period.monthKey())) {
                addBatch(connection, grants, event, identity, "MONTHLY_GOAL", goal.getValue(), "CONSOLE",
                        Map.of("goal", goal.getKey().toString()));
                notices.add(new VoteNotice("MONTHLY_GOAL", goal.getKey()));
            }
        }
        if (newestMonth) {
            for (Map.Entry<Integer, List<String>> goal : plan.monthlyStreakGoals().entrySet()) {
                if (streak >= goal.getKey() && claimPlayerGoal(connection, identity.uuid(), "monthly_streak",
                        goal.getKey(), period.monthKey())) {
                    addBatch(connection, grants, event, identity, "MONTHLY_STREAK", goal.getValue(), "CONSOLE",
                            Map.of("goal", goal.getKey().toString()));
                    notices.add(new VoteNotice("MONTHLY_STREAK", goal.getKey()));
                }
            }
        }
        for (Map.Entry<Integer, List<String>> goal : plan.globalGoals().entrySet()) {
            if (global >= goal.getKey() && claimGlobalGoal(connection, "global_daily", goal.getKey(), period.dayKey())) {
                addBatch(connection, grants, event, null, "GLOBAL_GOAL", goal.getValue(), "CONSOLE",
                        Map.of("goal", goal.getKey().toString()));
                notices.add(new VoteNotice("GLOBAL_GOAL", goal.getKey()));
            }
        }
        if (plan.recurringStart() > 0 && plan.recurringEvery() > 0) {
            int first = nextRecurring((int) Math.floor(previousGlobal), plan.recurringStart(), plan.recurringEvery());
            for (int threshold = first; threshold <= (int) Math.floor(global); threshold += plan.recurringEvery()) {
                if (claimGlobalGoal(connection, "global_recurring_" + plan.recurringEvery(), threshold, period.dayKey())) {
                    addBatch(connection, grants, event, null, "GLOBAL_RECURRING", plan.recurringCommands(), "CONSOLE",
                            Map.of("goal", Integer.toString(threshold)));
                    notices.add(new VoteNotice("GLOBAL_RECURRING", threshold));
                }
            }
        }
        if (plan.tripleSiteEnabled()
                && countDistinctServices(connection, identity.uuid(), period.dayKey()) >= plan.tripleSiteRequired()
                && claimPlayerGoal(connection, identity.uuid(), "triple_site", plan.tripleSiteRequired(), period.dayKey())) {
            addBatch(connection, grants, event, identity, "TRIPLE_SITE", plan.tripleSiteCommands(), "CONSOLE", Map.of());
            notices.add(new VoteNotice("TRIPLE_SITE", plan.tripleSiteRequired()));
        }

        updateEventState(connection, event.eventHash(), "PLANNED", identity.uuid(), period, "");
        return VoteEventResult.planned(event.eventHash(), grants, notices);
    }

    private void addBatch(Connection connection, List<String> grantIds, VoteEnvelope event, PlayerIdentity target,
                          String kind, List<String> commands, String executor, Map<String, String> extra) throws SQLException {
        String batch = event.eventHash() + ":" + kind.toLowerCase(Locale.ROOT);
        for (int i = 0; i < commands.size(); i++) {
            String command = materialize(commands.get(i), target, merge(extra, event));
            String id = insertGrant(connection, batch, event.eventHash(), kind, i, command, executor,
                    target == null ? null : target.uuid(), target == null ? null : target.exactName());
            grantIds.add(id);
        }
    }

    private String insertGrant(Connection connection, String batch, String eventHash, String kind, int sequence,
                               String command, String executor, UUID targetUuid, String targetName) throws SQLException {
        String id = VoteEnvelope.hashFields("vvotes-grant-v1", batch, Integer.toString(sequence), command, executor);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reward_grants(grant_id, batch_key, event_hash, kind, sequence, command_snapshot,
                  executor_mode, target_uuid, target_name, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, batch);
            statement.setString(3, eventHash);
            statement.setString(4, kind);
            statement.setInt(5, sequence);
            statement.setString(6, command);
            statement.setString(7, executor);
            statement.setString(8, targetUuid == null ? null : targetUuid.toString());
            statement.setString(9, targetName);
            statement.setLong(10, nowEpoch());
            statement.setLong(11, nowEpoch());
            statement.executeUpdate();
        }
        return id;
    }

    private Optional<GrantClaim> claimNext(String targetClause, String target) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            String sql = """
                    SELECT g.grant_id, g.batch_key, g.sequence, g.kind, g.command_snapshot, g.executor_mode,
                           g.target_uuid, g.target_name, g.claim_token, g.state, g.error
                    FROM reward_grants g
                    WHERE g.state = 'PENDING' AND %s
                      AND NOT EXISTS (
                        SELECT 1 FROM reward_grants prior
                        WHERE prior.batch_key = g.batch_key AND prior.sequence < g.sequence AND prior.state <> 'DONE'
                      )
                    ORDER BY g.created_at, g.batch_key, g.sequence LIMIT 1
                    """.formatted(targetClause);
            GrantClaim candidate;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (target != null) statement.setString(1, target);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        connection.rollback();
                        return Optional.empty();
                    }
                    candidate = mapGrant(rs);
                }
            }
            String token = UUID.randomUUID().toString();
            try (PreparedStatement claim = connection.prepareStatement("""
                    UPDATE reward_grants SET state = 'CLAIMED', claim_token = ?, claimed_at = ?, updated_at = ?
                    WHERE grant_id = ? AND state = 'PENDING'
                    """)) {
                claim.setString(1, token);
                claim.setLong(2, nowEpoch());
                claim.setLong(3, nowEpoch());
                claim.setString(4, candidate.grantId());
                if (claim.executeUpdate() == 0) {
                    connection.rollback();
                    return Optional.empty();
                }
            }
            connection.commit();
            return Optional.of(new GrantClaim(candidate.grantId(), candidate.batchKey(), candidate.sequence(),
                    candidate.kind(), candidate.commandSnapshot(), candidate.executorMode(), candidate.targetUuid(),
                    candidate.targetName(), token, "CLAIMED", ""));
        } catch (SQLException exception) {
            return Optional.empty();
        }
    }

    private boolean transition(String id, String token, String state, String error) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                UPDATE reward_grants SET state = ?, error = ?, claim_token = NULL,
                  completed_at = CASE WHEN ? IN ('DONE', 'AMBIGUOUS') THEN ? ELSE completed_at END,
                  updated_at = ?
                WHERE grant_id = ? AND state = 'CLAIMED' AND claim_token = ?
                """)) {
            statement.setString(1, state);
            statement.setString(2, safeError(error));
            statement.setString(3, state);
            statement.setLong(4, nowEpoch());
            statement.setLong(5, nowEpoch());
            statement.setString(6, id);
            statement.setString(7, token);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            return false;
        }
    }

    private void backupBeforeMigration() throws SQLException, IOException {
        if (!Files.exists(databasePath) || Files.size(databasePath) == 0) return;
        int version;
        try (Connection connection = rawConnection(databasePath); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
            statement.executeQuery("PRAGMA wal_checkpoint(FULL)").close();
            version = readAndValidateVersions(connection);
        }
        if (version > SCHEMA_VERSION) throw new SQLException("Database schema is newer than this plugin");
        if (version >= SCHEMA_VERSION) return;
        Path backup = databasePath.resolveSibling(databasePath.getFileName() + ".backup-v" + version);
        if (!Files.exists(backup)) Files.copy(databasePath, backup, StandardCopyOption.COPY_ATTRIBUTES);
        validateBackup(backup);
    }

    private void validateBackup(Path backup) throws SQLException {
        String url = "jdbc:sqlite:" + backup.toUri() + "?mode=ro";
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA integrity_check")) {
            if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                throw new SQLException("SQLite backup failed integrity_check: " + backup);
            }
        }
    }

    private void validateSchemaParity(Path path, int expected) throws SQLException {
        try (Connection connection = rawConnection(path)) {
            int actual = readAndValidateVersions(connection);
            if (actual != expected) throw new SQLException("Schema version mismatch after migration");
        }
    }

    private int readAndValidateVersions(Connection connection) throws SQLException {
        int userVersion;
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA user_version")) {
            userVersion = rs.next() ? rs.getInt(1) : 0;
        }
        int tableVersion = 0;
        try (PreparedStatement exists = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name='schema_version'"); ResultSet rs = exists.executeQuery()) {
            if (rs.next()) {
                try (Statement statement = connection.createStatement();
                     ResultSet versions = statement.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version")) {
                    tableVersion = versions.next() ? versions.getInt(1) : 0;
                }
            }
        }
        if (userVersion > 0 && tableVersion > 0 && userVersion != tableVersion) {
            throw new SQLException("PRAGMA user_version and schema_version disagree");
        }
        return Math.max(userVersion, tableVersion);
    }

    private void createSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS players (
                      uuid TEXT PRIMARY KEY, name TEXT NOT NULL, total_votes REAL NOT NULL DEFAULT 0,
                      daily_votes REAL NOT NULL DEFAULT 0, monthly_votes REAL NOT NULL DEFAULT 0,
                      streak_monthly INTEGER NOT NULL DEFAULT 0, last_vote_day TEXT NOT NULL DEFAULT '',
                      last_month_key TEXT NOT NULL DEFAULT '', last_vote_epoch INTEGER NOT NULL DEFAULT 0)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS global_stats (
                      id INTEGER PRIMARY KEY CHECK(id=1), daily_votes REAL NOT NULL DEFAULT 0,
                      last_daily_reset TEXT NOT NULL DEFAULT '')
                    """);
            statement.execute("INSERT OR IGNORE INTO global_stats(id, daily_votes, last_daily_reset) VALUES(1,0,'')");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS player_preferences (
                      uuid TEXT PRIMARY KEY, mute_vote_announcements INTEGER NOT NULL DEFAULT 0)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS goal_claims_global (
                      goal_type TEXT NOT NULL, goal_value INTEGER NOT NULL, day_key TEXT NOT NULL,
                      PRIMARY KEY(goal_type, goal_value, day_key))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS goal_claims_player (
                      uuid TEXT NOT NULL, goal_type TEXT NOT NULL, goal_value INTEGER NOT NULL, period_key TEXT NOT NULL,
                      PRIMARY KEY(uuid, goal_type, goal_value, period_key))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS monthly_snapshots (
                      uuid TEXT NOT NULL, player_name TEXT NOT NULL, month_key TEXT NOT NULL,
                      votes REAL NOT NULL, last_update_epoch INTEGER NOT NULL, PRIMARY KEY(uuid, month_key))
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS monthly_draw_history (
                      month_key TEXT PRIMARY KEY, winner_uuid TEXT NOT NULL, winner_name TEXT NOT NULL,
                      top_votes REAL NOT NULL, candidates_count INTEGER NOT NULL, executed_by TEXT NOT NULL,
                      executed_epoch INTEGER NOT NULL, reward_command TEXT NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pending_votes (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL,
                      service_name TEXT NOT NULL, created_epoch INTEGER NOT NULL)
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS vote_logs (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, player_name TEXT NOT NULL,
                      service_name TEXT NOT NULL, amount REAL NOT NULL, multiplier REAL NOT NULL,
                      created_epoch INTEGER NOT NULL)
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_vote_logs_uuid_epoch ON vote_logs(uuid, created_epoch)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS daily_global_snapshots (
                      day_key TEXT PRIMARY KEY, votes REAL NOT NULL)
                    """);
            statement.execute("""
                    INSERT OR IGNORE INTO daily_global_snapshots(day_key, votes)
                    SELECT last_daily_reset, daily_votes FROM global_stats WHERE id=1 AND last_daily_reset <> ''
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS vote_events (
                      event_hash TEXT PRIMARY KEY, normalized_name TEXT NOT NULL, display_name TEXT NOT NULL,
                      service TEXT NOT NULL, provider_timestamp TEXT NOT NULL, state TEXT NOT NULL,
                      resolved_uuid TEXT, day_key TEXT, month_key TEXT, plan_json TEXT NOT NULL,
                      created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, failure TEXT NOT NULL DEFAULT '')
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_vote_events_pending ON vote_events(state, normalized_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_vote_events_service_day ON vote_events(resolved_uuid, day_key, service)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS reward_grants (
                      grant_id TEXT PRIMARY KEY, batch_key TEXT NOT NULL, event_hash TEXT,
                      kind TEXT NOT NULL, sequence INTEGER NOT NULL, command_snapshot TEXT NOT NULL,
                      executor_mode TEXT NOT NULL, target_uuid TEXT, target_name TEXT,
                      state TEXT NOT NULL, claim_token TEXT, created_at INTEGER NOT NULL,
                      claimed_at INTEGER, completed_at INTEGER, updated_at INTEGER NOT NULL,
                      error TEXT NOT NULL DEFAULT '', UNIQUE(batch_key, sequence))
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_reward_grants_state_target ON reward_grants(state, target_uuid)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS legacy_pending_migrations (
                      pending_id INTEGER PRIMARY KEY, event_hash TEXT NOT NULL UNIQUE, migrated_at INTEGER NOT NULL)
                    """);
            statement.execute("CREATE TABLE IF NOT EXISTS schema_version(version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            statement.execute("INSERT OR IGNORE INTO schema_version(version, applied_at) VALUES(" + SCHEMA_VERSION + ", " + nowEpoch() + ")");
        }
        ensureColumn(connection, "players", "streak_monthly",
                "ALTER TABLE players ADD COLUMN streak_monthly INTEGER NOT NULL DEFAULT 0");
    }

    private void ensureColumn(Connection connection, String table, String column, String alteration) throws SQLException {
        boolean found = false;
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) if (column.equalsIgnoreCase(rs.getString("name"))) found = true;
        }
        if (!found) try (Statement statement = connection.createStatement()) { statement.execute(alteration); }
    }

    private boolean insertEvent(Connection connection, VoteEnvelope event, VotePlan plan,
                                String state, PeriodContext period) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO vote_events(event_hash, normalized_name, display_name, service,
                  provider_timestamp, state, plan_json, created_at, updated_at, day_key, month_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, event.eventHash());
            statement.setString(2, event.normalizedName());
            statement.setString(3, event.displayName());
            statement.setString(4, event.normalizedService());
            statement.setString(5, event.providerTimestamp());
            statement.setString(6, state);
            statement.setString(7, plan.toJson());
            statement.setLong(8, nowEpoch());
            statement.setLong(9, nowEpoch());
            statement.setString(10, period.dayKey());
            statement.setString(11, period.monthKey());
            return statement.executeUpdate() == 1;
        }
    }

    private void updateEventState(Connection connection, String hash, String state, UUID uuid,
                                  PeriodContext period, String failure) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE vote_events SET state=?, resolved_uuid=?, day_key=?, month_key=?, updated_at=?, failure=?
                WHERE event_hash=?
                """)) {
            statement.setString(1, state);
            statement.setString(2, uuid == null ? null : uuid.toString());
            statement.setString(3, period.dayKey());
            statement.setString(4, period.monthKey());
            statement.setLong(5, nowEpoch());
            statement.setString(6, safeError(failure));
            statement.setString(7, hash);
            statement.executeUpdate();
        }
    }

    private PlayerStats fetchOrCreate(Connection connection, PlayerIdentity identity) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO players(uuid, name) VALUES(?, ?)")) {
            insert.setString(1, identity.uuid().toString());
            insert.setString(2, identity.exactName());
            insert.executeUpdate();
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT * FROM players WHERE uuid=?")) {
            query.setString(1, identity.uuid().toString());
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) throw new SQLException("player row missing after insert");
                return new PlayerStats(identity.uuid(), rs.getString("name"), rs.getDouble("total_votes"),
                        rs.getDouble("daily_votes"), rs.getDouble("monthly_votes"), rs.getInt("streak_monthly"),
                        rs.getString("last_vote_day"), rs.getString("last_month_key"), rs.getLong("last_vote_epoch"));
            }
        }
    }

    private void updatePlayer(Connection connection, PlayerIdentity identity, double total, double daily,
                              double monthly, int streak, PeriodContext period) throws SQLException {
        updatePlayerProjection(connection, identity, total, daily, monthly, streak, period.dayKey(), period.monthKey());
    }

    private void updatePlayerProjection(Connection connection, PlayerIdentity identity, double total, double daily,
                                        double monthly, int streak, String dayKey, String monthKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE players SET name=?, total_votes=?, daily_votes=?, monthly_votes=?, streak_monthly=?,
                  last_vote_day=?, last_month_key=?, last_vote_epoch=? WHERE uuid=?
                """)) {
            statement.setString(1, identity.exactName()); statement.setDouble(2, total);
            statement.setDouble(3, daily); statement.setDouble(4, monthly); statement.setInt(5, streak);
            statement.setString(6, dayKey); statement.setString(7, monthKey);
            statement.setLong(8, nowEpoch()); statement.setString(9, identity.uuid().toString());
            statement.executeUpdate();
        }
    }

    private void upsertSnapshot(Connection connection, PlayerIdentity identity, String month, double votes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO monthly_snapshots(uuid, player_name, month_key, votes, last_update_epoch)
                VALUES(?,?,?,?,?) ON CONFLICT(uuid, month_key) DO UPDATE SET
                  player_name=excluded.player_name, votes=excluded.votes, last_update_epoch=excluded.last_update_epoch
                """)) {
            statement.setString(1, identity.uuid().toString()); statement.setString(2, identity.exactName());
            statement.setString(3, month); statement.setDouble(4, votes); statement.setLong(5, nowEpoch());
            statement.executeUpdate();
        }
    }

    private double readMonthlySnapshot(Connection connection, UUID uuid, String month) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT votes FROM monthly_snapshots WHERE uuid=? AND month_key=?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, month);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
        }
    }

    private double readGlobal(Connection connection, String day) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT votes FROM daily_global_snapshots WHERE day_key=?")) {
            statement.setString(1, day);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? rs.getDouble(1) : 0; }
        }
    }

    private void updateGlobal(Connection connection, double value, String day) throws SQLException {
        updateGlobalPeriod(connection, value, day);
    }

    private void updateGlobalPeriod(Connection connection, double value, String day) throws SQLException {
        try (PreparedStatement snapshot = connection.prepareStatement("""
                INSERT INTO daily_global_snapshots(day_key, votes) VALUES(?,?)
                ON CONFLICT(day_key) DO UPDATE SET votes=excluded.votes
                """)) {
            snapshot.setString(1, day);
            snapshot.setDouble(2, value);
            snapshot.executeUpdate();
        }
        String projectedDay = "";
        try (PreparedStatement read = connection.prepareStatement(
                "SELECT last_daily_reset FROM global_stats WHERE id=1"); ResultSet rs = read.executeQuery()) {
            if (rs.next()) projectedDay = rs.getString(1);
        }
        if (!projectedDay.isBlank() && day.compareTo(projectedDay) < 0) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE global_stats SET daily_votes=?, last_daily_reset=? WHERE id=1")) {
            statement.setDouble(1, value); statement.setString(2, day); statement.executeUpdate();
        }
    }

    private boolean claimPlayerGoal(Connection c, UUID uuid, String type, int value, String period) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT OR IGNORE INTO goal_claims_player(uuid,goal_type,goal_value,period_key) VALUES(?,?,?,?)")) {
            s.setString(1, uuid.toString()); s.setString(2, type); s.setInt(3, value); s.setString(4, period);
            return s.executeUpdate() == 1;
        }
    }

    private boolean claimGlobalGoal(Connection c, String type, int value, String period) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "INSERT OR IGNORE INTO goal_claims_global(goal_type,goal_value,day_key) VALUES(?,?,?)")) {
            s.setString(1, type); s.setInt(2, value); s.setString(3, period); return s.executeUpdate() == 1;
        }
    }

    private int countDistinctServices(Connection c, UUID uuid, String day) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("""
                SELECT COUNT(DISTINCT service) FROM vote_events
                WHERE resolved_uuid=? AND day_key=? AND state IN ('PROCESSING','PLANNED')
                """)) {
            s.setString(1, uuid.toString()); s.setString(2, day);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = rawConnection(databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=" + busyTimeoutMs);
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }

    private Connection rawConnection(Path path) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private long count(String table, String kind) {
        String sql = "SELECT COUNT(*) FROM " + table + (kind == null ? "" : " WHERE kind=?");
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(sql)) {
            if (kind != null) s.setString(1, kind);
            try (ResultSet rs = s.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        } catch (SQLException exception) { return -1; }
    }

    private GrantClaim mapGrant(ResultSet rs) throws SQLException {
        String uuid = rs.getString("target_uuid");
        return new GrantClaim(rs.getString("grant_id"), rs.getString("batch_key"), rs.getInt("sequence"),
                rs.getString("kind"), rs.getString("command_snapshot"), rs.getString("executor_mode"),
                uuid == null ? null : UUID.fromString(uuid), rs.getString("target_name"),
                rs.getString("claim_token"), rs.getString("state"), rs.getString("error"));
    }

    private PeriodContext currentPeriod() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zoneId));
        return new PeriodContext(now.toLocalDate().toString(), YearMonth.from(now).toString());
    }

    private PeriodContext periodAt(long epochSecond) {
        try {
            ZonedDateTime value = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSecond), zoneId);
            return new PeriodContext(value.toLocalDate().toString(), YearMonth.from(value).toString());
        } catch (RuntimeException invalidEpoch) {
            return currentPeriod();
        }
    }

    private long nowEpoch() { return clock.instant().getEpochSecond(); }

    private int computeMonthlyStreak(int prior, String priorMonth, String current) {
        if (priorMonth == null || priorMonth.isBlank()) return 1;
        if (priorMonth.equals(current)) return prior;
        return YearMonth.parse(current).minusMonths(1).toString().equals(priorMonth) ? prior + 1 : 1;
    }

    private int nextRecurring(int current, int startAfter, int step) {
        int first = startAfter + step;
        if (current < first) return first;
        int delta = current - startAfter;
        int mod = delta % step;
        return mod == 0 ? current + step : current + step - mod;
    }

    private String materialize(String command, PlayerIdentity player, Map<String, String> placeholders) {
        String result = command;
        if (player != null) {
            result = result.replace("<player>", player.exactName()).replace("%player%", player.exactName())
                    .replace("<uuid>", player.uuid().toString()).replace("%uuid%", player.uuid().toString());
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("<" + entry.getKey() + ">", entry.getValue())
                    .replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return result;
    }

    private Map<String, String> merge(Map<String, String> extra, VoteEnvelope event) {
        TreeMap<String, String> map = new TreeMap<>(extra);
        map.put("service", event.normalizedService());
        map.put("timestamp", event.providerTimestamp());
        return map;
    }

    private String safeError(String error) {
        if (error == null) return "";
        String singleLine = error.replace('\n', ' ').replace('\r', ' ').strip();
        return singleLine.length() <= 240 ? singleLine : singleLine.substring(0, 240);
    }

    private String normalize(String value) { return value == null ? "" : value.strip().toLowerCase(Locale.ROOT); }

    private record PeriodContext(String dayKey, String monthKey) {}
    private record StoredEvent(String hash, String displayName, String service, String timestamp,
                               String planJson, String dayKey, String monthKey) {}
    private record LegacyRow(long id, String playerName, String serviceName, long createdEpoch) {}
}

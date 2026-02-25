package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.config.PluginConfig;
import com.valerinsmp.vvotes.db.DatabaseManager;
import com.valerinsmp.vvotes.model.PlayerStats;
import com.valerinsmp.vvotes.reward.CommandRewardExecutor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import net.kyori.adventure.title.Title;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

public final class VoteService {
    private final VVotesPlugin plugin;
    private final ConfigService configService;
    private final MessageService messageService;
    private final SoundService soundService;
    private final DatabaseManager database;
    private final CommandRewardExecutor rewardExecutor;

    public VoteService(
            VVotesPlugin plugin,
            ConfigService configService,
            MessageService messageService,
            SoundService soundService,
            DatabaseManager database,
            CommandRewardExecutor rewardExecutor
    ) {
        this.plugin = plugin;
        this.configService = configService;
        this.messageService = messageService;
        this.soundService = soundService;
        this.database = database;
        this.rewardExecutor = rewardExecutor;
    }

    public void handleVote(Player player, String serviceName) {
        processVote(player.getUniqueId(), player.getName(), serviceName, 1.0, true);
    }

    public void handleOfflineVoteSkip(String playerName) {
        messageService.send(Bukkit.getConsoleSender(), "vote-offline-skip", Map.of("player", playerName));
    }

    public void addManualVotes(OfflinePlayer target, int amount) {
        if (amount <= 0 || target.getUniqueId() == null) {
            return;
        }
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        processVote(target.getUniqueId(), name, "manual", amount, false);
    }

    public MonthlyDrawResult runAutoMonthlyDrawIfNeeded() {
        if (!configService.get().monthlyDrawEnabled()) {
            return MonthlyDrawResult.disabled();
        }
        YearMonth month = YearMonth.now(ZoneId.of(configService.get().timezone())).minusMonths(1);
        return drawMonthly(month.toString(), "auto");
    }

    public MonthlyDrawResult drawMonthly(String monthKey, String executedBy) {
        if (!configService.get().monthlyDrawEnabled()) {
            return MonthlyDrawResult.disabled();
        }
        if (monthKey == null || monthKey.isBlank()) {
            monthKey = YearMonth.now(ZoneId.of(configService.get().timezone())).minusMonths(1).toString();
        }

        try {
            YearMonth.parse(monthKey);
        } catch (Exception exception) {
            return MonthlyDrawResult.invalidMonth(monthKey);
        }

        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (isMonthAlreadyDrawn(connection, monthKey)) {
                    connection.rollback();
                    return MonthlyDrawResult.alreadyDrawn(monthKey);
                }

                double maxVotes = fetchMaxVotesForMonth(connection, monthKey);
                if (maxVotes < configService.get().monthlyDrawMinVotes()) {
                    connection.rollback();
                    return MonthlyDrawResult.noParticipants(monthKey, maxVotes);
                }

                List<DrawCandidate> candidates = fetchCandidatesForTopVotes(connection, monthKey, maxVotes);
                if (candidates.isEmpty()) {
                    connection.rollback();
                    return MonthlyDrawResult.noParticipants(monthKey, maxVotes);
                }

                DrawCandidate winner = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                String rewardCommand = configService.get().monthlyDrawRewardCommand();
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("player", winner.name());
                placeholders.put("uuid", winner.uuid().toString());
                placeholders.put("month", monthKey);
                placeholders.put("votes", formatDouble(maxVotes));
                placeholders.put("candidates", Integer.toString(candidates.size()));

                rewardExecutor.execute(List.of(rewardCommand), placeholders);
                insertMonthlyDrawHistory(connection, monthKey, winner, maxVotes, candidates.size(), executedBy, rewardCommand);
                connection.commit();

                for (var line : messageService.messages("draw-monthly-winner-broadcast", placeholders)) {
                    Bukkit.broadcast(line);
                }
                soundService.playToAll("goal.completed");

                return MonthlyDrawResult.success(monthKey, winner.name(), maxVotes, candidates.size());
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Error en sorteo mensual: " + exception.getMessage());
            return MonthlyDrawResult.error(monthKey, exception.getMessage());
        }
    }

    public String getTimezoneId() {
        return configService.get().timezone();
    }

    public void forceResetGlobalDaily() {
        DateContext context = currentContext();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE global_stats SET daily_votes = 0, last_daily_reset = ? WHERE id = 1")) {
            statement.setString(1, context.dayKey);
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo reiniciar meta global diaria: " + exception.getMessage());
        }
    }

    public void forceResetPlayerMonthly(OfflinePlayer target) {
        if (target.getUniqueId() == null) {
            return;
        }
        DateContext context = currentContext();
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE players SET monthly_votes = 0, last_month_key = ? WHERE uuid = ?")) {
            statement.setString(1, context.monthKey);
            statement.setString(2, target.getUniqueId().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo reiniciar mensual de " + target.getName() + ": " + exception.getMessage());
        }
    }

    public PlayerStats getStats(UUID uuid, String playerName) {
        try (Connection connection = database.getConnection()) {
            PlayerStats stats = fetchOrCreateStats(connection, uuid, playerName);
            return normalizeForCurrentPeriod(connection, stats, currentContext());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Error obteniendo stats: " + exception.getMessage());
            return PlayerStats.empty(uuid, playerName);
        }
    }

    public double getGlobalDailyVotes() {
        try (Connection connection = database.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT daily_votes, last_daily_reset FROM global_stats WHERE id = 1")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0;
                }
                String resetKey = resultSet.getString("last_daily_reset");
                String dayKey = currentContext().dayKey;
                if (!Objects.equals(resetKey, dayKey)) {
                    return 0;
                }
                return resultSet.getDouble("daily_votes");
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Error leyendo votos globales: " + exception.getMessage());
            return 0;
        }
    }

    public int nextGlobalGoal(double currentValue) {
        for (Integer threshold : configService.get().globalDailyGoals().keySet()) {
            if (currentValue < threshold) {
                return threshold;
            }
        }
        return -1;
    }

    public int nextMonthlyGoal(double currentValue) {
        for (Integer threshold : configService.get().playerMonthlyGoals().keySet()) {
            if (currentValue < threshold) {
                return threshold;
            }
        }
        return -1;
    }

    public String formatDouble(double value) {
        if (value == Math.floor(value)) {
            return String.format(Locale.US, "%.0f", value);
        }
        return String.format(Locale.US, "%.2f", value);
    }

    private void processVote(UUID uuid, String playerName, String serviceName, double amount, boolean executeVoteRewards) {
        if (amount <= 0) {
            return;
        }

        PluginConfig config = configService.get();
        DateContext context = currentContext();
        List<List<String>> pendingCommands = new ArrayList<>();
        List<Integer> recurringReached = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();
        boolean playerGoalCompleted = false;
        boolean globalGoalCompleted = false;
        int highestMonthlyMilestoneTriggered = 0;

        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            try {

                PlayerStats stats = normalizeForCurrentPeriod(connection, fetchOrCreateStats(connection, uuid, playerName), context);

            long secondsSinceLast = stats.lastVoteEpoch() <= 0 ? Long.MAX_VALUE : context.epochSeconds - stats.lastVoteEpoch();
            if (secondsSinceLast <= config.suspiciousWindowSeconds()) {
                plugin.getLogger().warning("Voto sospechoso detectado: " + playerName + " servicio=" + serviceName + " diff=" + secondsSinceLast + "s");
            }

            int streakMonthly = computeMonthlyStreak(stats.streakMonthly(), stats.lastMonthKey(), context.monthKey);
            double newTotal = stats.totalVotes() + amount;
            double newDaily = stats.dailyVotes() + amount;
            double newMonthly = stats.monthlyVotes() + amount;

            updatePlayerStats(connection, uuid, playerName, newTotal, newDaily, newMonthly, streakMonthly, context);
            upsertMonthlySnapshot(connection, uuid, playerName, context.monthKey, newMonthly, context.epochSeconds);

            GlobalState globalState = fetchGlobalState(connection, context);
            double previousGlobalDaily = globalState.dailyVotes;
            double newGlobalDaily = globalState.dailyVotes + amount;
            updateGlobalState(connection, newGlobalDaily, context.dayKey);

            insertVoteLog(connection, uuid, playerName, serviceName, amount, amount);
            fillPlaceholders(placeholders, uuid, playerName, serviceName, amount, newTotal, newDaily, newMonthly, streakMonthly, newGlobalDaily);

            if (executeVoteRewards) {
                pendingCommands.add(config.voteRewards());
            }

            for (Map.Entry<Integer, List<String>> entry : config.monthlyStreakRewards().entrySet()) {
                if (streakMonthly == entry.getKey()) {
                    pendingCommands.add(entry.getValue());
                }
            }

            for (Map.Entry<Integer, List<String>> entry : config.playerMonthlyGoals().entrySet()) {
                if (newMonthly >= entry.getKey() && tryClaimPlayerGoal(connection, uuid, "monthly", entry.getKey(), context.monthKey)) {
                    pendingCommands.add(entry.getValue());
                    playerGoalCompleted = true;
                    if (entry.getKey() > highestMonthlyMilestoneTriggered) {
                        highestMonthlyMilestoneTriggered = entry.getKey();
                    }
                }
            }

            for (Map.Entry<Integer, List<String>> entry : config.globalDailyGoals().entrySet()) {
                if (newGlobalDaily >= entry.getKey() && tryClaimGlobalGoal(connection, "global_daily", entry.getKey(), context.dayKey)) {
                    pendingCommands.add(entry.getValue());
                    globalGoalCompleted = true;
                }
            }

            if (config.globalRecurringStart() > 0 && config.globalRecurringEvery() > 0 && !config.globalRecurringCommands().isEmpty()) {
                int start = config.globalRecurringStart() + config.globalRecurringEvery();
                int firstReached = Math.max(start, nextRecurringThreshold((int) Math.floor(previousGlobalDaily), config.globalRecurringEvery()));
                int lastReached = (int) Math.floor(newGlobalDaily);
                for (int threshold = firstReached; threshold <= lastReached; threshold += config.globalRecurringEvery()) {
                    if (tryClaimGlobalGoal(connection, "global_recurring_" + config.globalRecurringEvery(), threshold, context.dayKey)) {
                        recurringReached.add(threshold);
                        globalGoalCompleted = true;
                    }
                }
            }

            connection.commit();

            if (highestMonthlyMilestoneTriggered > 0) {
                placeholders.put("monthly_milestone", Integer.toString(highestMonthlyMilestoneTriggered));
                placeholders.put("monthly_bonus", messageService.text("monthly-bonus-inline", placeholders));
            } else {
                placeholders.put("monthly_bonus", "");
            }

            if (config.broadcastOnVote()) {
                for (var line : messageService.messages("vote-broadcast", placeholders)) {
                    Bukkit.broadcast(line);
                }
                soundService.playToAll("vote.announcement");
            }

            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                soundService.play(player, "vote.announcement");
                showTitle(player, "vote.title", "vote.subtitle", placeholders);
                if (playerGoalCompleted) {
                    soundService.play(player, "goal.completed");
                }
                runForcedServiceCommand(player, serviceName);
            }

            if (globalGoalCompleted) {
                soundService.playToAll("goal.completed");
            }

            for (List<String> commands : pendingCommands) {
                rewardExecutor.execute(commands, placeholders);
            }

                for (Integer threshold : recurringReached) {
                    Map<String, String> recurringPlaceholders = new HashMap<>(placeholders);
                    recurringPlaceholders.put("goal", Integer.toString(threshold));
                    rewardExecutor.execute(config.globalRecurringCommands(), recurringPlaceholders);
                }
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Error procesando voto de " + playerName + ": " + exception.getMessage());
        }
    }

    private void fillPlaceholders(
            Map<String, String> placeholders,
            UUID uuid,
            String playerName,
            String serviceName,
            double amount,
            double total,
            double daily,
            double monthly,
            int streakMonthly,
            double globalDaily
    ) {
        placeholders.put("player", playerName);
        placeholders.put("uuid", uuid.toString());
        placeholders.put("service", serviceName);
        placeholders.put("amount", formatDouble(amount));
        placeholders.put("multiplier", "1");
        placeholders.put("total", formatDouble(total));
        placeholders.put("daily", formatDouble(daily));
        placeholders.put("monthly", formatDouble(monthly));
        placeholders.put("streak_monthly", Integer.toString(streakMonthly));
        placeholders.put("streak_daily", "0");
        placeholders.put("streak_weekly", "0");
        placeholders.put("daily_global", formatDouble(globalDaily));
    }

    private PlayerStats normalizeForCurrentPeriod(Connection connection, PlayerStats stats, DateContext context) throws SQLException {
        boolean changed = false;
        double dailyVotes = stats.dailyVotes();
        double monthlyVotes = stats.monthlyVotes();

        if (!Objects.equals(stats.lastVoteDay(), context.dayKey)) {
            dailyVotes = 0;
            changed = true;
        }

        if (!Objects.equals(stats.lastMonthKey(), context.monthKey)) {
            monthlyVotes = 0;
            changed = true;
        }

        if (!changed) {
            return stats;
        }

        updatePlayerStats(
                connection,
                stats.uuid(),
                stats.name(),
                stats.totalVotes(),
                dailyVotes,
                monthlyVotes,
                stats.streakMonthly(),
                stats.lastVoteDay(),
                stats.lastMonthKey(),
                stats.lastVoteEpoch()
        );

        return new PlayerStats(
                stats.uuid(),
                stats.name(),
                stats.totalVotes(),
                dailyVotes,
                monthlyVotes,
                stats.streakMonthly(),
                stats.lastVoteDay(),
                stats.lastMonthKey(),
                stats.lastVoteEpoch()
        );
    }

    private int computeMonthlyStreak(int previousStreak, String previousMonth, String currentMonth) {
        if (previousMonth == null || previousMonth.isBlank()) {
            return 1;
        }
        if (Objects.equals(previousMonth, currentMonth)) {
            return previousStreak;
        }
        YearMonth expectedPrevious = YearMonth.parse(currentMonth).minusMonths(1);
        if (Objects.equals(previousMonth, expectedPrevious.toString())) {
            return previousStreak + 1;
        }
        return 1;
    }

    private PlayerStats fetchOrCreateStats(Connection connection, UUID uuid, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new PlayerStats(
                            uuid,
                            resultSet.getString("name"),
                            resultSet.getDouble("total_votes"),
                            resultSet.getDouble("daily_votes"),
                            resultSet.getDouble("monthly_votes"),
                            resultSet.getInt("streak_monthly"),
                            resultSet.getString("last_vote_day"),
                            resultSet.getString("last_month_key"),
                            resultSet.getLong("last_vote_epoch")
                    );
                }
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO players(uuid, name) VALUES (?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.executeUpdate();
        }

        return PlayerStats.empty(uuid, name);
    }

    private void updatePlayerStats(
            Connection connection,
            UUID uuid,
            String name,
            double total,
            double daily,
            double monthly,
            int streakMonthly,
            DateContext context
    ) throws SQLException {
        updatePlayerStats(connection, uuid, name, total, daily, monthly, streakMonthly,
                context.dayKey, context.monthKey, context.epochSeconds);
    }

    private void updatePlayerStats(
            Connection connection,
            UUID uuid,
            String name,
            double total,
            double daily,
            double monthly,
            int streakMonthly,
            String lastVoteDay,
            String monthKey,
            long epoch
    ) throws SQLException {
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

    private GlobalState fetchGlobalState(Connection connection, DateContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT daily_votes, last_daily_reset FROM global_stats WHERE id = 1")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new GlobalState(0, context.dayKey);
                }
                String reset = resultSet.getString("last_daily_reset");
                if (!Objects.equals(reset, context.dayKey)) {
                    return new GlobalState(0, context.dayKey);
                }
                return new GlobalState(resultSet.getDouble("daily_votes"), reset);
            }
        }
    }

    private void updateGlobalState(Connection connection, double dailyVotes, String dayKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE global_stats SET daily_votes = ?, last_daily_reset = ? WHERE id = 1")) {
            statement.setDouble(1, dailyVotes);
            statement.setString(2, dayKey);
            statement.executeUpdate();
        }
    }

    private boolean tryClaimGlobalGoal(Connection connection, String type, int value, String dayKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO goal_claims_global(goal_type, goal_value, day_key) VALUES (?, ?, ?)")) {
            statement.setString(1, type);
            statement.setInt(2, value);
            statement.setString(3, dayKey);
            return statement.executeUpdate() > 0;
        }
    }

    private boolean tryClaimPlayerGoal(Connection connection, UUID uuid, String type, int value, String period) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR IGNORE INTO goal_claims_player(uuid, goal_type, goal_value, period_key) VALUES (?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, type);
            statement.setInt(3, value);
            statement.setString(4, period);
            return statement.executeUpdate() > 0;
        }
    }

    private void insertVoteLog(Connection connection, UUID uuid, String playerName, String service, double amount, double multiplier) throws SQLException {
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

    private void upsertMonthlySnapshot(Connection connection, UUID uuid, String playerName, String monthKey, double votes, long epoch) throws SQLException {
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

    private boolean isMonthAlreadyDrawn(Connection connection, String monthKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM monthly_draw_history WHERE month_key = ?")) {
            statement.setString(1, monthKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private double fetchMaxVotesForMonth(Connection connection, String monthKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(votes), 0) AS max_votes FROM monthly_snapshots WHERE month_key = ?")) {
            statement.setString(1, monthKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getDouble("max_votes");
                }
                return 0;
            }
        }
    }

    private List<DrawCandidate> fetchCandidatesForTopVotes(Connection connection, String monthKey, double maxVotes) throws SQLException {
        List<DrawCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT uuid, player_name
                FROM monthly_snapshots
                WHERE month_key = ? AND votes = ?
                ORDER BY player_name ASC
                """)) {
            statement.setString(1, monthKey);
            statement.setDouble(2, maxVotes);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(new DrawCandidate(
                            UUID.fromString(resultSet.getString("uuid")),
                            resultSet.getString("player_name")
                    ));
                }
            }
        }
        return candidates;
    }

    private void insertMonthlyDrawHistory(
            Connection connection,
            String monthKey,
            DrawCandidate winner,
            double topVotes,
            int candidatesCount,
            String executedBy,
            String rewardCommand
    ) throws SQLException {
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

    private DateContext currentContext() {
        ZoneId zoneId = ZoneId.of(configService.get().timezone());
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        String dayKey = now.toLocalDate().toString();
        String monthKey = YearMonth.from(now).toString();
        return new DateContext(dayKey, monthKey, now.toEpochSecond());
    }

    private void runForcedServiceCommand(Player player, String serviceName) {
        String command = configService.get().forcedServiceCommand(serviceName);
        if (command == null || command.isBlank()) {
            return;
        }
        boolean executed = player.performCommand(command.startsWith("/") ? command.substring(1) : command);
        if (!executed) {
            plugin.getLogger().warning("No se pudo ejecutar comando forzado para servicio " + serviceName + ": " + command);
        }
    }

    private int nextRecurringThreshold(int value, int step) {
        int mod = value % step;
        return mod == 0 ? value + step : value + (step - mod);
    }

    private void showTitle(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        Title title = Title.title(
                messageService.titlePart(titleKey, placeholders),
                messageService.titlePart(subtitleKey, placeholders),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(400))
        );
        player.showTitle(title);
    }

    private record DateContext(String dayKey, String monthKey, long epochSeconds) {
    }

    private record GlobalState(double dailyVotes, String dayKey) {
    }

    private record DrawCandidate(UUID uuid, String name) {
    }

    public record MonthlyDrawResult(Status status, String monthKey, String winnerName, double topVotes, int candidatesCount, String error) {
        public enum Status {
            SUCCESS,
            NO_PARTICIPANTS,
            ALREADY_DRAWN,
            DISABLED,
            INVALID_MONTH,
            ERROR
        }

        public static MonthlyDrawResult success(String monthKey, String winnerName, double topVotes, int candidatesCount) {
            return new MonthlyDrawResult(Status.SUCCESS, monthKey, winnerName, topVotes, candidatesCount, "");
        }

        public static MonthlyDrawResult noParticipants(String monthKey, double topVotes) {
            return new MonthlyDrawResult(Status.NO_PARTICIPANTS, monthKey, "", topVotes, 0, "");
        }

        public static MonthlyDrawResult alreadyDrawn(String monthKey) {
            return new MonthlyDrawResult(Status.ALREADY_DRAWN, monthKey, "", 0, 0, "");
        }

        public static MonthlyDrawResult disabled() {
            return new MonthlyDrawResult(Status.DISABLED, "", "", 0, 0, "");
        }

        public static MonthlyDrawResult invalidMonth(String monthKey) {
            return new MonthlyDrawResult(Status.INVALID_MONTH, monthKey, "", 0, 0, "");
        }

        public static MonthlyDrawResult error(String monthKey, String error) {
            return new MonthlyDrawResult(Status.ERROR, monthKey, "", 0, 0, error);
        }
    }
}

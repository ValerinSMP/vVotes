package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.config.PluginConfig;
import com.valerinsmp.vvotes.db.DatabaseManager;
import com.valerinsmp.vvotes.model.PlayerStats;
import com.valerinsmp.vvotes.reward.CommandRewardExecutor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
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
import java.util.concurrent.ConcurrentHashMap;

public final class VoteService {

    private static final long STATS_CACHE_TTL_MS = 5_000L;

    private final VVotesPlugin plugin;
    private final ConfigService configService;
    private final MessageService messageService;
    private final SoundService soundService;
    private final VoteRepository repo;
    private final CommandRewardExecutor rewardExecutor;
    final MonthlyDrawService monthlyDrawService;
    private final Map<UUID, CachedStats> statsCache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> voteAnnouncementMuteCache = new ConcurrentHashMap<>();

    private record CachedStats(PlayerStats stats, double globalDaily, long expiresAt) {
        boolean isExpired() { return System.currentTimeMillis() > expiresAt; }
    }

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
        this.repo = new VoteRepository(database);
        this.rewardExecutor = rewardExecutor;
        this.monthlyDrawService = new MonthlyDrawService(plugin, configService, messageService, soundService, repo, rewardExecutor);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void handleVote(Player player, String serviceName) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            processVote(uuid, name, serviceName, 1.0, true);
            statsCache.remove(uuid);
        });
    }

    public void addManualVotes(OfflinePlayer target, int amount) {
        if (amount <= 0 || target.getUniqueId() == null) return;
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        processVote(target.getUniqueId(), name, "manual", amount, false);
    }

    public MonthlyDrawResult runAutoMonthlyDrawIfNeeded() {
        return monthlyDrawService.runAutoIfNeeded();
    }

    public MonthlyDrawResult drawMonthly(String monthKey, String executedBy) {
        return monthlyDrawService.draw(monthKey, executedBy);
    }

    public DrawHistoryResult getDrawHistory(String monthKey) {
        return monthlyDrawService.getHistory(monthKey);
    }

    public List<TopMonthEntry> getTopMonth(String monthKey, int limit) {
        return monthlyDrawService.getTopMonth(monthKey, limit);
    }

    public void invalidateStatsCache() {
        statsCache.clear();
    }

    public boolean toggleVoteAnnouncements(UUID uuid, String playerName) {
        if (uuid == null) {
            return false;
        }
        String safeName = (playerName == null || playerName.isBlank()) ? uuid.toString() : playerName;
        try {
            Connection connection = repo.connection();
            repo.fetchOrCreateStats(connection, uuid, safeName);
            boolean currentlyMuted = repo.isVoteAnnouncementMuted(connection, uuid);
            boolean updated = !currentlyMuted;
            repo.setVoteAnnouncementMuted(connection, uuid, updated);
            voteAnnouncementMuteCache.put(uuid, updated);
            return updated;
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo cambiar la preferencia de anuncios de voto para " + safeName + ": " + exception.getMessage());
            return false;
        }
    }

    public boolean isVoteAnnouncementMuted(UUID uuid, String playerName) {
        if (uuid == null) {
            return false;
        }
        Boolean cached = voteAnnouncementMuteCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        String safeName = (playerName == null || playerName.isBlank()) ? uuid.toString() : playerName;
        try {
            Connection connection = repo.connection();
            repo.fetchOrCreateStats(connection, uuid, safeName);
            boolean muted = repo.isVoteAnnouncementMuted(connection, uuid);
            voteAnnouncementMuteCache.put(uuid, muted);
            return muted;
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo leer la preferencia de anuncios de voto para " + safeName + ": " + exception.getMessage());
            return false;
        }
    }

    public void sealGoalsForCurrentDay() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Connection connection = repo.connection();
                DateContext context = currentContext();
                double globalDaily = repo.readGlobalDailyVotes(connection, context.dayKey());
                PluginConfig config = configService.get();

                for (int threshold : config.globalDailyGoals().keySet()) {
                    if (globalDaily >= threshold) {
                        repo.tryClaimGlobalGoal(connection, "global_daily", threshold, context.dayKey());
                    }
                }
                if (config.globalRecurringStart() > 0 && config.globalRecurringEvery() > 0) {
                    int start = config.globalRecurringStart() + config.globalRecurringEvery();
                    for (int t = start; t <= (int) Math.floor(globalDaily); t += config.globalRecurringEvery()) {
                        repo.tryClaimGlobalGoal(connection, "global_recurring_" + config.globalRecurringEvery(), t, context.dayKey());
                    }
                }
            } catch (SQLException exception) {
                plugin.getLogger().warning("Error sellando metas del dia: " + exception.getMessage());
            }
        });
    }

    public String getTimezoneId() {
        return configService.get().timezone();
    }

    public PlayerStats getStats(UUID uuid, String playerName) {
        CachedStats cached = statsCache.get(uuid);
        if (cached != null && !cached.isExpired()) return cached.stats();
        try {
            Connection connection = repo.connection();
            PlayerStats stats = repo.fetchOrCreateStats(connection, uuid, playerName);
            DateContext context = currentContext();
            PlayerStats normalized = normalizeForCurrentPeriod(connection, stats, context);
            double globalDaily = repo.readGlobalDailyVotes(connection, context.dayKey());
            statsCache.put(uuid, new CachedStats(normalized, globalDaily, System.currentTimeMillis() + STATS_CACHE_TTL_MS));
            return normalized;
        } catch (SQLException exception) {
            plugin.getLogger().warning("Error obteniendo stats: " + exception.getMessage());
            return PlayerStats.empty(uuid, playerName);
        }
    }

    public double getGlobalDailyVotes() {
        for (CachedStats cached : statsCache.values()) {
            if (!cached.isExpired()) return cached.globalDaily();
        }
        try {
            return repo.readGlobalDailyVotes(repo.connection(), currentContext().dayKey());
        } catch (SQLException exception) {
            plugin.getLogger().warning("Error leyendo votos globales: " + exception.getMessage());
            return 0;
        }
    }

    public int nextGlobalGoal(double currentValue) {
        for (Integer threshold : configService.get().globalDailyGoals().keySet()) {
            if (currentValue < threshold) return threshold;
        }
        return -1;
    }

    public int nextMonthlyGoal(double currentValue) {
        for (Integer threshold : configService.get().playerMonthlyGoals().keySet()) {
            if (currentValue < threshold) return threshold;
        }
        return -1;
    }

    public String getDoubleSiteTodayIcon(UUID uuid) {
        PluginConfig config = configService.get();
        if (!config.doubleSiteBonusEnabled() || uuid == null) return "";
        DateContext context = currentContext();
        try {
            int distinctSites = repo.countDistinctServicesToday(repo.connection(), uuid, context);
            if (distinctSites >= config.doubleSiteBonusRequiredSites()) return config.doubleSiteTodayIcon();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Error leyendo placeholder double-site: " + exception.getMessage());
        }
        return "";
    }

    public void forceResetGlobalDaily() {
        DateContext context = currentContext();
        try {
            repo.updateGlobalState(repo.connection(), 0, context.dayKey());
            invalidateStatsCache();
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo reiniciar meta global diaria: " + exception.getMessage());
        }
    }

    public double adjustGlobalDailyVotes(int delta) {
        DateContext context = currentContext();
        Connection connection = null;
        try {
            connection = repo.connection();
            connection.setAutoCommit(false);
            GlobalState state = repo.fetchGlobalState(connection, context);
            double updated = Math.max(0, state.dailyVotes() + delta);
            repo.updateGlobalState(connection, updated, context.dayKey());
            connection.commit();
            connection.setAutoCommit(true);
            return updated;
        } catch (SQLException exception) {
            if (connection != null) {
                try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            plugin.getLogger().warning("No se pudo ajustar contador global diario: " + exception.getMessage());
            return -1;
        }
    }

    public void forceResetPlayerMonthly(OfflinePlayer target) {
        if (target.getUniqueId() == null) return;
        DateContext context = currentContext();
        try {
            Connection connection = repo.connection();
            PlayerStats stats = repo.fetchOrCreateStats(connection, target.getUniqueId(),
                    target.getName() == null ? target.getUniqueId().toString() : target.getName());
            repo.updatePlayerStats(connection, target.getUniqueId(), stats.name(),
                    stats.totalVotes(), stats.dailyVotes(), 0, stats.streakMonthly(),
                    stats.lastVoteDay(), context.monthKey(), stats.lastVoteEpoch());
            statsCache.remove(target.getUniqueId());
        } catch (SQLException exception) {
            plugin.getLogger().warning("No se pudo reiniciar mensual de " + target.getName() + ": " + exception.getMessage());
        }
    }

    public double adjustPlayerDailyVotes(OfflinePlayer target, int delta) {
        if (target.getUniqueId() == null) return -1;
        UUID uuid = target.getUniqueId();
        String playerName = target.getName() == null ? uuid.toString() : target.getName();
        DateContext context = currentContext();
        Connection connection = null;
        try {
            connection = repo.connection();
            connection.setAutoCommit(false);
            PlayerStats stats = normalizeForCurrentPeriod(connection, repo.fetchOrCreateStats(connection, uuid, playerName), context);
            double updatedDaily = Math.max(0, stats.dailyVotes() + delta);
            repo.updatePlayerStats(connection, uuid, playerName, stats.totalVotes(), updatedDaily,
                    stats.monthlyVotes(), stats.streakMonthly(), context);
            connection.commit();
            connection.setAutoCommit(true);
            statsCache.remove(uuid);
            return updatedDaily;
        } catch (SQLException exception) {
            if (connection != null) {
                try { connection.rollback(); connection.setAutoCommit(true); } catch (SQLException ignored) {}
            }
            plugin.getLogger().warning("No se pudo ajustar contador diario de " + playerName + ": " + exception.getMessage());
            return -1;
        }
    }

    public static String formatDoubleStatic(double value) {
        if (value == Math.floor(value)) return String.format(Locale.US, "%.0f", value);
        return String.format(Locale.US, "%.2f", value);
    }

    public String formatDouble(double value) {
        return formatDoubleStatic(value);
    }

    // ── Core vote processing ──────────────────────────────────────────────────

    private void processVote(UUID uuid, String playerName, String serviceName, double amount, boolean executeVoteRewards) {
        if (amount <= 0) return;

        PluginConfig config = configService.get();
        DateContext context = currentContext();
        List<List<String>> pendingCommands = new ArrayList<>();
        List<Integer> recurringReached = new ArrayList<>();
        List<Integer> globalDailyReached = new ArrayList<>();
        List<Integer> monthlyGoalsReached = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();
        boolean playerGoalCompleted = false;
        boolean globalGoalCompleted = false;
        boolean doubleSiteBonusCompleted = false;
        int highestMonthlyMilestone = 0;

        try {
            Connection connection = repo.connection();
            connection.setAutoCommit(false);
            try {
                PlayerStats stats = normalizeForCurrentPeriod(connection, repo.fetchOrCreateStats(connection, uuid, playerName), context);

                long secondsSinceLast = stats.lastVoteEpoch() <= 0 ? Long.MAX_VALUE : context.epochSeconds() - stats.lastVoteEpoch();
                if (secondsSinceLast <= config.suspiciousWindowSeconds()) {
                    plugin.getLogger().warning("Voto sospechoso detectado: " + playerName + " servicio=" + serviceName + " diff=" + secondsSinceLast + "s");
                }

                int streakMonthly = computeMonthlyStreak(stats.streakMonthly(), stats.lastMonthKey(), context.monthKey());
                double newTotal = stats.totalVotes() + amount;
                double newDaily = stats.dailyVotes() + amount;
                double newMonthly = stats.monthlyVotes() + amount;

                repo.updatePlayerStats(connection, uuid, playerName, newTotal, newDaily, newMonthly, streakMonthly, context);
                repo.upsertMonthlySnapshot(connection, uuid, playerName, context.monthKey(), newMonthly, context.epochSeconds());

                GlobalState globalState = repo.fetchGlobalState(connection, context);
                double previousGlobalDaily = globalState.dailyVotes();
                double newGlobalDaily = globalState.dailyVotes() + amount;
                repo.updateGlobalState(connection, newGlobalDaily, context.dayKey());

                repo.insertVoteLog(connection, uuid, playerName, serviceName, amount, amount);
                fillPlaceholders(placeholders, uuid, playerName, serviceName, amount, newTotal, newDaily, newMonthly, streakMonthly, newGlobalDaily);

                if (executeVoteRewards && config.doubleSiteBonusEnabled()) {
                    int distinctSites = repo.countDistinctServicesToday(connection, uuid, context);
                    placeholders.put("double_site_today_count", Integer.toString(distinctSites));
                    if (distinctSites >= config.doubleSiteBonusRequiredSites()
                            && repo.tryClaimPlayerGoal(connection, uuid, "double_site_daily", config.doubleSiteBonusRequiredSites(), context.dayKey())) {
                        pendingCommands.add(config.doubleSiteBonusCommands());
                        doubleSiteBonusCompleted = true;
                    }
                }

                if (executeVoteRewards) pendingCommands.add(config.voteRewards());

                for (Map.Entry<Integer, List<String>> entry : config.monthlyStreakRewards().entrySet()) {
                    if (streakMonthly == entry.getKey()) pendingCommands.add(entry.getValue());
                }

                for (Map.Entry<Integer, List<String>> entry : config.playerMonthlyGoals().entrySet()) {
                    if (newMonthly >= entry.getKey() && repo.tryClaimPlayerGoal(connection, uuid, "monthly", entry.getKey(), context.monthKey())) {
                        pendingCommands.add(entry.getValue());
                        monthlyGoalsReached.add(entry.getKey());
                        playerGoalCompleted = true;
                        if (entry.getKey() > highestMonthlyMilestone) highestMonthlyMilestone = entry.getKey();
                    }
                }

                for (Map.Entry<Integer, List<String>> entry : config.globalDailyGoals().entrySet()) {
                    if (newGlobalDaily >= entry.getKey() && repo.tryClaimGlobalGoal(connection, "global_daily", entry.getKey(), context.dayKey())) {
                        pendingCommands.add(entry.getValue());
                        globalDailyReached.add(entry.getKey());
                        globalGoalCompleted = true;
                    }
                }

                if (config.globalRecurringStart() > 0 && config.globalRecurringEvery() > 0 && !config.globalRecurringCommands().isEmpty()) {
                    int start = config.globalRecurringStart() + config.globalRecurringEvery();
                    int firstReached = Math.max(start, nextRecurringThreshold((int) Math.floor(previousGlobalDaily), config.globalRecurringEvery()));
                    int lastReached = (int) Math.floor(newGlobalDaily);
                    for (int threshold = firstReached; threshold <= lastReached; threshold += config.globalRecurringEvery()) {
                        if (repo.tryClaimGlobalGoal(connection, "global_recurring_" + config.globalRecurringEvery(), threshold, context.dayKey())) {
                            recurringReached.add(threshold);
                            globalGoalCompleted = true;
                        }
                    }
                }

                connection.commit();
                connection.setAutoCommit(true);
            } catch (SQLException exception) {
                connection.rollback();
                connection.setAutoCommit(true);
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Error procesando voto de " + playerName + ": " + exception.getMessage());
            return;
        }

        if (highestMonthlyMilestone > 0) {
            placeholders.put("monthly_milestone", Integer.toString(highestMonthlyMilestone));
            placeholders.put("monthly_bonus", messageService.text("monthly-bonus-inline", placeholders));
        } else {
            placeholders.put("monthly_bonus", "");
        }
        placeholders.put("double_site_bonus", doubleSiteBonusCompleted
                ? messageService.text("double-site-bonus-inline", placeholders) : "");

        deliverNotificationsAndRewards(uuid, serviceName, config, placeholders,
            pendingCommands, recurringReached, globalDailyReached, monthlyGoalsReached,
            playerGoalCompleted, globalGoalCompleted, doubleSiteBonusCompleted);
    }

    private void deliverNotificationsAndRewards(
            UUID uuid, String serviceName, PluginConfig config,
            Map<String, String> placeholders, List<List<String>> pendingCommands,
            List<Integer> recurringReached, List<Integer> globalDailyReached, List<Integer> monthlyGoalsReached,
            boolean playerGoalCompleted,
            boolean globalGoalCompleted, boolean doubleSiteBonusCompleted
    ) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (config.broadcastOnVote()) {
                Player voter = Bukkit.getPlayer(uuid);
                for (var line : messageService.messages("vote-broadcast", placeholders)) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (voter != null && online.getUniqueId().equals(voter.getUniqueId())) {
                            continue;
                        }
                        if (isVoteAnnouncementMuted(online.getUniqueId(), online.getName())) {
                            continue;
                        }
                        online.sendMessage(line);
                    }
                }
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (voter != null && online.getUniqueId().equals(voter.getUniqueId())) {
                        continue;
                    }
                    if (isVoteAnnouncementMuted(online.getUniqueId(), online.getName())) {
                        continue;
                    }
                    soundService.play(online, "vote.announcement");
                }
            }

            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                soundService.play(player, "vote.announcement");
                showTitle(player, "vote.title", "vote.subtitle", placeholders);
                if (playerGoalCompleted) soundService.play(player, "goal.completed");

                for (Integer threshold : monthlyGoalsReached) {
                    Map<String, String> mp = new HashMap<>(placeholders);
                    mp.put("goal", Integer.toString(threshold));
                    for (var line : messageService.messages("player-monthly-goal-completed", mp)) {
                        player.sendMessage(line);
                    }
                }

                if (doubleSiteBonusCompleted) {
                    String bonusMessage = config.doubleSiteBonusMessage();
                    if (bonusMessage != null && !bonusMessage.isBlank()) {
                        String withPrefix = bonusMessage.replace("%prefix%", messageService.text("prefix", Map.of()));
                        player.sendMessage(messageService.parse(messageService.applyPlaceholders(withPrefix, placeholders)));
                    }
                }
                runForcedServiceCommand(player, serviceName);
            }

            if (globalGoalCompleted) soundService.playToAll("goal.completed");

            for (Integer threshold : globalDailyReached) {
                Map<String, String> gp = new HashMap<>(placeholders);
                gp.put("goal", Integer.toString(threshold));
                for (var line : messageService.messages("global-goal-completed-broadcast", gp)) {
                    Bukkit.broadcast(line);
                }
            }

            for (Integer threshold : recurringReached) {
                Map<String, String> rp = new HashMap<>(placeholders);
                rp.put("goal", Integer.toString(threshold));
                for (var line : messageService.messages("global-recurring-goal-completed-broadcast", rp)) {
                    Bukkit.broadcast(line);
                }
            }

            for (List<String> commands : pendingCommands) {
                rewardExecutor.execute(commands, placeholders);
            }
            for (Integer threshold : recurringReached) {
                Map<String, String> rp = new HashMap<>(placeholders);
                rp.put("goal", Integer.toString(threshold));
                rewardExecutor.execute(config.globalRecurringCommands(), rp);
            }
        });
    }

    // ── Stats normalization ───────────────────────────────────────────────────

    private PlayerStats normalizeForCurrentPeriod(Connection connection, PlayerStats stats, DateContext context) throws SQLException {
        double dailyVotes = stats.dailyVotes();
        double monthlyVotes = stats.monthlyVotes();
        boolean changed = false;

        if (!Objects.equals(stats.lastVoteDay(), context.dayKey())) { dailyVotes = 0; changed = true; }
        if (!Objects.equals(stats.lastMonthKey(), context.monthKey())) { monthlyVotes = 0; changed = true; }

        if (!changed) return stats;

        repo.updatePlayerStats(connection, stats.uuid(), stats.name(), stats.totalVotes(), dailyVotes,
                monthlyVotes, stats.streakMonthly(), stats.lastVoteDay(), stats.lastMonthKey(), stats.lastVoteEpoch());

        return new PlayerStats(stats.uuid(), stats.name(), stats.totalVotes(), dailyVotes, monthlyVotes,
                stats.streakMonthly(), stats.lastVoteDay(), stats.lastMonthKey(), stats.lastVoteEpoch());
    }

    private int computeMonthlyStreak(int previousStreak, String previousMonth, String currentMonth) {
        if (previousMonth == null || previousMonth.isBlank()) return 1;
        if (Objects.equals(previousMonth, currentMonth)) return previousStreak;
        YearMonth expectedPrevious = YearMonth.parse(currentMonth).minusMonths(1);
        return Objects.equals(previousMonth, expectedPrevious.toString()) ? previousStreak + 1 : 1;
    }

    // ── Placeholders ──────────────────────────────────────────────────────────

    private void fillPlaceholders(Map<String, String> placeholders, UUID uuid, String playerName,
                                   String serviceName, double amount, double total, double daily,
                                   double monthly, int streakMonthly, double globalDaily) {
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

    // ── Utils ─────────────────────────────────────────────────────────────────

    DateContext currentContext() {
        ZoneId zoneId = ZoneId.of(configService.get().timezone());
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime dayStart = now.toLocalDate().atStartOfDay(zoneId);
        return new DateContext(
                now.toLocalDate().toString(),
                java.time.YearMonth.from(now).toString(),
                now.toEpochSecond(),
                dayStart.toEpochSecond(),
                dayStart.plusDays(1).toEpochSecond()
        );
    }

    private int nextRecurringThreshold(int value, int step) {
        int mod = value % step;
        return mod == 0 ? value + step : value + (step - mod);
    }

    private void runForcedServiceCommand(Player player, String serviceName) {
        List<String> commands = configService.get().forcedServiceCommands(serviceName);
        if (commands.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            for (String raw : commands) {
                String command = raw.startsWith("/") ? raw.substring(1) : raw;
                try {
                    if (Bukkit.dispatchCommand(player, command)) {
                        plugin.getLogger().info("Comando forzado ejecutado para " + player.getName() + " (" + serviceName + "): /" + command);
                        return;
                    }
                    player.chat("/" + command);
                    plugin.getLogger().info("Comando forzado fallback chat para " + player.getName() + " (" + serviceName + "): /" + command);
                    return;
                } catch (Exception exception) {
                    plugin.getLogger().warning("Fallo comando forzado para " + player.getName() + " (" + serviceName + "): /" + command + " -> " + exception.getMessage());
                }
            }
            plugin.getLogger().warning("No se pudo ejecutar ningun comando forzado para " + player.getName() + " en servicio " + serviceName + ": " + String.join(", ", commands));
        });
    }

    private void showTitle(Player player, String titleKey, String subtitleKey, Map<String, String> placeholders) {
        Title title = Title.title(
                messageService.titlePart(titleKey, placeholders),
                messageService.titlePart(subtitleKey, placeholders),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(400))
        );
        player.showTitle(title);
    }
}

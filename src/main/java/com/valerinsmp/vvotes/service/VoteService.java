package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.config.PluginConfig;
import com.valerinsmp.vvotes.model.PlayerStats;
import com.valerinsmp.vvotes.reward.GrantDispatcher;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import net.kyori.adventure.title.Title;

import java.time.Duration;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class VoteService implements AutoCloseable {
    private final VVotesPlugin plugin;
    private final ConfigService configService;
    private final MessageService messageService;
    private final SoundService soundService;
    private final VoteLedger ledger;
    private final GrantDispatcher dispatcher;
    private final ExecutorService writer;
    private final VoteSnapshots snapshots = new VoteSnapshots();
    private final java.util.Set<UUID> drainingPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean drainingGlobal = new AtomicBoolean();
    private final AtomicBoolean accepting = new AtomicBoolean();

    public VoteService(VVotesPlugin plugin, ConfigService configService, MessageService messageService,
                       SoundService soundService, VoteLedger ledger, GrantDispatcher dispatcher) {
        this.plugin = plugin;
        this.configService = configService;
        this.messageService = messageService;
        this.soundService = soundService;
        this.ledger = ledger;
        this.dispatcher = dispatcher;
        AtomicInteger sequence = new AtomicInteger();
        this.writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "vVotes-db-writer-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        CompletableFuture.runAsync(() -> {
            ledger.migrateLegacyPending(VotePlan.from(configService.get(), "legacy"));
            snapshots.load(ledger.readAllStats(), ledger.readAllPreferences(), ledger.readGlobalDaily(), currentDay());
        }, writer).join();
        accepting.set(true);
        drainGlobalGrants();
    }

    /** Called on main after VoteListener captured provider primitives and resolved exact identity. */
    public void ingestProviderEvent(VoteEnvelope event, PlayerIdentity identity) {
        requireMainThread();
        if (!accepting.get()) return;
        PluginConfig config = configService.get();
        boolean allowTestVote = config.processTestVotes();
        VoteEnvelope acceptedEvent = applyProviderPolicy(event, allowTestVote);
        VotePlan plan = VotePlan.from(config, event.normalizedService());
        CompletableFuture.supplyAsync(() -> ledger.accept(acceptedEvent, identity, plan), writer)
                .thenAccept(result -> afterIngest(result, event, identity));
    }

    /** Called on main from PlayerJoinEvent with exact UUID/name primitives. */
    public void resolvePending(PlayerIdentity identity) {
        requireMainThread();
        if (!accepting.get()) return;
        CompletableFuture.supplyAsync(() -> ledger.resolvePending(identity), writer).thenAccept(results -> {
            refreshSnapshot(identity);
            if (!results.isEmpty()) {
                scheduleMain(() -> {
                    for (VoteEventResult result : results) notifyAccepted(identity, result);
                    Player exact = Bukkit.getPlayerExact(identity.exactName());
                    if (exact != null && exact.isOnline() && exact.getUniqueId().equals(identity.uuid())) {
                        messageService.send(exact, "vote-pending-delivered",
                                Map.of("amount", Integer.toString(results.size())));
                    }
                });
            }
            drainPlayerGrants(identity.uuid());
            drainGlobalGrants();
        });
    }

    public CompletableFuture<Integer> addManualVotes(OfflinePlayer target, int amount) {
        if (target == null || target.getUniqueId() == null || target.getName() == null || amount <= 0) {
            return CompletableFuture.completedFuture(0);
        }
        PlayerIdentity identity = new PlayerIdentity(target.getUniqueId(), target.getName());
        VotePlan plan = VotePlan.from(configService.get(), "manual").withoutVoteCommands();
        return CompletableFuture.supplyAsync(() -> {
            int planned = 0;
            for (int i = 0; i < amount; i++) {
                if (ledger.accept(VoteEnvelope.manual(identity, System.currentTimeMillis() / 1000L, UUID.randomUUID()),
                        identity, plan).state() == VoteEventState.PLANNED) planned++;
            }
            refreshSnapshot(identity);
            return planned;
        }, writer).thenApply(planned -> {
            drainPlayerGrants(identity.uuid());
            drainGlobalGrants();
            return planned;
        });
    }

    public CompletableFuture<Double> adjustGlobalDailyVotesAsync(int delta) {
        return CompletableFuture.supplyAsync(() -> {
            double updated = ledger.adjustGlobalDaily(delta);
            snapshots.setGlobal(Math.max(0, updated), currentDay());
            return updated;
        }, writer);
    }

    public CompletableFuture<Double> adjustPlayerDailyVotesAsync(OfflinePlayer target, int delta) {
        if (target == null || target.getUniqueId() == null || target.getName() == null) return CompletableFuture.completedFuture(-1D);
        PlayerIdentity identity = new PlayerIdentity(target.getUniqueId(), target.getName());
        return CompletableFuture.supplyAsync(() -> {
            double updated = ledger.adjustPlayerDaily(identity, delta);
            refreshSnapshot(identity);
            return updated;
        }, writer);
    }

    public CompletableFuture<Void> forceResetGlobalDailyAsync() {
        return CompletableFuture.runAsync(() -> {
            ledger.resetGlobalDaily();
            snapshots.setGlobal(0, currentDay());
        }, writer);
    }

    public CompletableFuture<Void> forceResetPlayerMonthlyAsync(OfflinePlayer target) {
        if (target == null || target.getUniqueId() == null || target.getName() == null) return CompletableFuture.completedFuture(null);
        PlayerIdentity identity = new PlayerIdentity(target.getUniqueId(), target.getName());
        return CompletableFuture.runAsync(() -> {
            ledger.resetPlayerMonthly(identity);
            refreshSnapshot(identity);
        }, writer);
    }

    public CompletableFuture<MonthlyDrawResult> drawMonthlyAsync(String monthKey, String executedBy) {
        String key = monthKey == null || monthKey.isBlank()
                ? YearMonth.now(ZoneId.of(configService.get().timezone())).minusMonths(1).toString() : monthKey;
        if (!configService.get().monthlyDrawEnabled()) return CompletableFuture.completedFuture(MonthlyDrawResult.disabled());
        PluginConfig config = configService.get();
        String command = config.monthlyDrawRewardCommand();
        return CompletableFuture.supplyAsync(() -> ledger.planMonthlyDraw(key, executedBy,
                config.monthlyDrawMinVotes(), command,
                bound -> java.util.concurrent.ThreadLocalRandom.current().nextInt(bound)), writer).thenApply(result -> {
            if (result.status() == MonthlyDrawResult.Status.SUCCESS) {
                scheduleMain(() -> {
                    Map<String, String> placeholders = Map.of("month", result.monthKey(),
                            "player", result.winnerName(), "votes", formatDouble(result.topVotes()),
                            "candidates", Integer.toString(result.candidatesCount()));
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        messageService.send(online, "draw-monthly-winner-broadcast", placeholders);
                    }
                    soundService.playToAll("goal.completed");
                });
                drainGlobalGrants();
            }
            return result;
        });
    }

    public CompletableFuture<DrawHistoryResult> getDrawHistoryAsync(String monthKey) {
        String key = monthKey == null || monthKey.isBlank()
                ? YearMonth.now(ZoneId.of(configService.get().timezone())).minusMonths(1).toString() : monthKey;
        return CompletableFuture.supplyAsync(() -> ledger.readDrawHistory(key), writer);
    }

    public CompletableFuture<List<TopMonthEntry>> getTopMonthAsync(String monthKey, int limit) {
        return CompletableFuture.supplyAsync(() -> ledger.readTopMonth(monthKey, limit), writer);
    }

    public CompletableFuture<Boolean> toggleVoteAnnouncementsAsync(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            boolean muted = ledger.togglePreference(uuid);
            snapshots.setMuted(uuid, muted);
            return muted;
        }, writer);
    }

    public PlayerStats getStats(UUID uuid, String playerName) {
        return snapshots.stats(uuid, playerName, currentDay(), currentMonth());
    }

    public double getGlobalDailyVotes() { return snapshots.global(currentDay()); }
    public boolean isVoteAnnouncementMuted(UUID uuid, String ignoredName) { return snapshots.muted(uuid); }

    public int nextGlobalGoal(double currentValue) {
        PluginConfig config = configService.get();
        for (Integer threshold : config.globalDailyGoals().keySet()) if (currentValue < threshold) return threshold;
        int start = config.globalRecurringStart();
        int every = config.globalRecurringEvery();
        if (start > 0 && every > 0) {
            int first = start + every;
            if (currentValue < first) return first;
            int relative = (int) Math.floor(currentValue) - start;
            int mod = relative % every;
            return start + (mod == 0 ? relative + every : relative + every - mod);
        }
        return -1;
    }

    public int nextMonthlyGoal(double currentValue) {
        for (Integer threshold : configService.get().playerMonthlyGoals().keySet()) if (currentValue < threshold) return threshold;
        return -1;
    }

    public String getDoubleSiteTodayIcon(UUID uuid) {
        return snapshots.triple(uuid, currentDay())
                ? configService.get().doubleSiteTodayIcon() : "";
    }

    public String getTimezoneId() { return configService.get().timezone(); }
    public CompletableFuture<List<GrantClaim>> getAmbiguousGrantsAsync() {
        return CompletableFuture.supplyAsync(ledger::listAmbiguous, writer);
    }
    public static String formatDoubleStatic(double value) {
        if (value == Math.floor(value)) return String.format(Locale.US, "%.0f", value);
        return String.format(Locale.US, "%.2f", value);
    }

    public String formatDouble(double value) { return formatDoubleStatic(value); }

    @Override
    public void close() {
        stopAccepting();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) plugin.getLogger().warning("DB writer did not stop cleanly");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        ledger.close();
    }

    public void stopAccepting() {
        accepting.set(false);
    }

    private void afterIngest(VoteEventResult result, VoteEnvelope event, PlayerIdentity identity) {
        if (identity != null) refreshSnapshot(identity);
        if (result.state() == VoteEventState.PLANNED && identity != null) {
            scheduleMain(() -> notifyAccepted(identity, result));
        }
        if (identity != null) drainPlayerGrants(identity.uuid());
        drainGlobalGrants();
        if (result.state() == VoteEventState.QUARANTINED) {
            plugin.getLogger().warning("Provider vote quarantined: event=" + shortId(event.eventHash()));
        }
    }

    private void refreshSnapshot(PlayerIdentity identity) {
        PlayerStats stats = ledger.readStats(identity.uuid(), identity.exactName());
        boolean triple = ledger.distinctServicesToday(identity.uuid()) >= configService.get().doubleSiteBonusRequiredSites();
        snapshots.updatePlayer(stats, triple, currentDay());
        snapshots.setGlobal(ledger.readGlobalDaily(), currentDay());
    }

    private void notifyAccepted(PlayerIdentity identity, VoteEventResult result) {
        if (!accepting.get()) return;
        Player voter = Bukkit.getPlayerExact(identity.exactName());
        if (voter == null || !voter.isOnline() || !voter.getUniqueId().equals(identity.uuid())) return;
        PlayerStats stats = getStats(identity.uuid(), identity.exactName());
        Map<String, String> placeholders = Map.of(
                "player", identity.exactName(),
                "total", formatDouble(stats.totalVotes()),
                "daily", formatDouble(stats.dailyVotes()),
                "monthly", formatDouble(stats.monthlyVotes()),
                "global_daily", formatDouble(getGlobalDailyVotes()),
                "monthly_bonus", "", "double_site_bonus", ""
        );
        soundService.play(voter, "vote.announcement");
        voter.showTitle(Title.title(messageService.titlePart("vote.title", placeholders),
                messageService.titlePart("vote.subtitle", placeholders),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
        voter.sendActionBar(messageService.actionbar("vote-progress", Map.of(
                "daily", formatDouble(stats.dailyVotes()),
                "next_global_goal", Integer.toString(nextGlobalGoal(getGlobalDailyVotes())))));
        for (VoteNotice notice : result.notices()) {
            Map<String, String> goal = Map.of("goal", Integer.toString(notice.threshold()),
                    "player", identity.exactName());
            switch (notice.kind()) {
                case "MONTHLY_GOAL", "MONTHLY_STREAK" -> {
                    messageService.send(voter, "player-monthly-goal-completed", goal);
                    soundService.play(voter, "goal.completed");
                }
                case "TRIPLE_SITE" -> {
                    voter.sendMessage(messageService.component(configService.get().doubleSiteBonusMessage(), goal));
                    soundService.play(voter, "goal.completed");
                }
                case "GLOBAL_GOAL", "GLOBAL_RECURRING" -> {
                    String key = notice.kind().equals("GLOBAL_GOAL")
                            ? "global-goal-completed-broadcast" : "global-recurring-goal-completed-broadcast";
                    for (Player online : Bukkit.getOnlinePlayers()) messageService.send(online, key, goal);
                    soundService.playToAll("goal.completed");
                }
                default -> { }
            }
        }
        if (configService.get().broadcastOnVote()) {
            for (var component : messageService.messages("vote-broadcast", placeholders)) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (!online.getUniqueId().equals(identity.uuid()) && !isVoteAnnouncementMuted(online.getUniqueId(), online.getName())) {
                        online.sendMessage(component);
                    }
                }
            }
        }
        plugin.getLogger().info("Provider vote planned: event=" + shortId(result.eventHash()));
    }

    private void drainPlayerGrants(UUID uuid) {
        if (!accepting.get() || !drainingPlayers.add(uuid)) return;
        claimAndDispatch(uuid, false);
    }

    private void drainGlobalGrants() {
        if (!accepting.get() || !drainingGlobal.compareAndSet(false, true)) return;
        claimAndDispatch(null, true);
    }

    private void claimAndDispatch(UUID uuid, boolean global) {
        CompletableFuture.supplyAsync(() -> global ? ledger.claimNextGlobalGrant() : ledger.claimNextGrant(uuid), writer)
                .thenAccept(optional -> {
                    if (optional.isEmpty()) {
                        if (global) drainingGlobal.set(false); else drainingPlayers.remove(uuid);
                        return;
                    }
                    scheduleMain(() -> dispatchClaim(optional.orElseThrow(), uuid, global));
                });
    }

    private void dispatchClaim(GrantClaim claim, UUID uuid, boolean global) {
        GrantDispatcher.DispatchResult result = dispatcher.dispatch(claim);
        CompletableFuture.runAsync(() -> {
            switch (result) {
                case DONE -> ledger.markDoneAfterDispatch(claim.grantId(), claim.claimToken());
                case NOT_DISPATCHED -> ledger.releaseBeforeDispatch(claim.grantId(), claim.claimToken(), "target unavailable before dispatch");
                case AMBIGUOUS -> ledger.markAmbiguousAfterDispatch(claim.grantId(), claim.claimToken(), "dispatch returned false or threw");
            }
        }, writer).thenRun(() -> {
            if (result == GrantDispatcher.DispatchResult.NOT_DISPATCHED) {
                if (global) drainingGlobal.set(false); else drainingPlayers.remove(uuid);
            } else {
                claimAndDispatch(uuid, global);
            }
        });
    }

    private void scheduleMain(Runnable task) {
        if (!accepting.get() || !plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (accepting.get() && plugin.isEnabled()) task.run();
        });
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("vVotes main-thread boundary violated");
    }

    private String shortId(String id) { return id == null ? "unknown" : id.substring(0, Math.min(12, id.length())); }
    private String currentDay() { return LocalDate.now(ZoneId.of(configService.get().timezone())).toString(); }
    private String currentMonth() { return YearMonth.now(ZoneId.of(configService.get().timezone())).toString(); }

    public boolean isAccepting() { return accepting.get(); }

    static VoteEnvelope applyProviderPolicy(VoteEnvelope event, boolean processTestVotes) {
        return event.testVote() && !processTestVotes ? event.quarantined() : event;
    }
}

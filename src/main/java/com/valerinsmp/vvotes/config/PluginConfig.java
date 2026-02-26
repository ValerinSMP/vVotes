package com.valerinsmp.vvotes.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class PluginConfig {
    private final String sqliteFile;
    private final int busyTimeoutMs;
    private final String timezone;
    private final boolean broadcastOnVote;
    private final int suspiciousWindowSeconds;
    private final Map<String, List<String>> forcedServiceCommands;
    private final TreeMap<Integer, List<String>> globalDailyGoals;
    private final int globalRecurringStart;
    private final int globalRecurringEvery;
    private final List<String> globalRecurringCommands;
    private final TreeMap<Integer, List<String>> playerMonthlyGoals;
    private final List<String> voteRewards;
    private final TreeMap<Integer, List<String>> monthlyStreakRewards;
    private final boolean monthlyDrawEnabled;
    private final int monthlyDrawMinVotes;
    private final String monthlyDrawRewardCommand;
    private final int monthlyDrawAutoCheckMinutes;
    private final boolean doubleSiteBonusEnabled;
    private final int doubleSiteBonusRequiredSites;
    private final String doubleSiteBonusMessage;
    private final List<String> doubleSiteBonusCommands;
    private final String doubleSiteTodayIcon;

    public PluginConfig(
            String sqliteFile,
            int busyTimeoutMs,
            String timezone,
            boolean broadcastOnVote,
            int suspiciousWindowSeconds,
            Map<String, List<String>> forcedServiceCommands,
            TreeMap<Integer, List<String>> globalDailyGoals,
            int globalRecurringStart,
            int globalRecurringEvery,
            List<String> globalRecurringCommands,
            TreeMap<Integer, List<String>> playerMonthlyGoals,
            List<String> voteRewards,
            TreeMap<Integer, List<String>> monthlyStreakRewards,
            boolean monthlyDrawEnabled,
            int monthlyDrawMinVotes,
            String monthlyDrawRewardCommand,
            int monthlyDrawAutoCheckMinutes,
            boolean doubleSiteBonusEnabled,
            int doubleSiteBonusRequiredSites,
            String doubleSiteBonusMessage,
            List<String> doubleSiteBonusCommands,
            String doubleSiteTodayIcon
    ) {
        this.sqliteFile = sqliteFile;
        this.busyTimeoutMs = busyTimeoutMs;
        this.timezone = timezone;
        this.broadcastOnVote = broadcastOnVote;
        this.suspiciousWindowSeconds = suspiciousWindowSeconds;
        this.forcedServiceCommands = forcedServiceCommands;
        this.globalDailyGoals = globalDailyGoals;
        this.globalRecurringStart = globalRecurringStart;
        this.globalRecurringEvery = globalRecurringEvery;
        this.globalRecurringCommands = globalRecurringCommands;
        this.playerMonthlyGoals = playerMonthlyGoals;
        this.voteRewards = voteRewards;
        this.monthlyStreakRewards = monthlyStreakRewards;
        this.monthlyDrawEnabled = monthlyDrawEnabled;
        this.monthlyDrawMinVotes = monthlyDrawMinVotes;
        this.monthlyDrawRewardCommand = monthlyDrawRewardCommand;
        this.monthlyDrawAutoCheckMinutes = monthlyDrawAutoCheckMinutes;
        this.doubleSiteBonusEnabled = doubleSiteBonusEnabled;
        this.doubleSiteBonusRequiredSites = doubleSiteBonusRequiredSites;
        this.doubleSiteBonusMessage = doubleSiteBonusMessage;
        this.doubleSiteBonusCommands = doubleSiteBonusCommands;
        this.doubleSiteTodayIcon = doubleSiteTodayIcon;
    }

    public String sqliteFile() {
        return sqliteFile;
    }

    public int busyTimeoutMs() {
        return busyTimeoutMs;
    }

    public String timezone() {
        return timezone;
    }

    public boolean broadcastOnVote() {
        return broadcastOnVote;
    }

    public int suspiciousWindowSeconds() {
        return suspiciousWindowSeconds;
    }

    public List<String> forcedServiceCommands(String serviceName) {
        if (serviceName == null) {
            return Collections.emptyList();
        }
        return forcedServiceCommands.getOrDefault(serviceName.toLowerCase(), Collections.emptyList());
    }

    public TreeMap<Integer, List<String>> globalDailyGoals() {
        return globalDailyGoals;
    }

    public int globalRecurringStart() {
        return globalRecurringStart;
    }

    public int globalRecurringEvery() {
        return globalRecurringEvery;
    }

    public List<String> globalRecurringCommands() {
        return globalRecurringCommands;
    }

    public TreeMap<Integer, List<String>> playerMonthlyGoals() {
        return playerMonthlyGoals;
    }

    public List<String> voteRewards() {
        return voteRewards;
    }

    public TreeMap<Integer, List<String>> monthlyStreakRewards() {
        return monthlyStreakRewards;
    }

    public boolean monthlyDrawEnabled() {
        return monthlyDrawEnabled;
    }

    public int monthlyDrawMinVotes() {
        return monthlyDrawMinVotes;
    }

    public String monthlyDrawRewardCommand() {
        return monthlyDrawRewardCommand;
    }

    public int monthlyDrawAutoCheckMinutes() {
        return monthlyDrawAutoCheckMinutes;
    }

    public boolean doubleSiteBonusEnabled() {
        return doubleSiteBonusEnabled;
    }

    public int doubleSiteBonusRequiredSites() {
        return doubleSiteBonusRequiredSites;
    }

    public String doubleSiteBonusMessage() {
        return doubleSiteBonusMessage;
    }

    public List<String> doubleSiteBonusCommands() {
        return doubleSiteBonusCommands;
    }

    public String doubleSiteTodayIcon() {
        return doubleSiteTodayIcon;
    }

    public static PluginConfig defaultConfig() {
        return new PluginConfig(
                "data/vvotes.db",
                5000,
                "America/Santiago",
                true,
                10,
                Collections.emptyMap(),
                new TreeMap<>(),
                0,
                0,
                Collections.emptyList(),
                new TreeMap<>(),
                Collections.emptyList(),
                new TreeMap<>(),
                true,
                1,
                "lp user <player> parent addtemp arcano 30d",
                5,
                true,
                2,
                "%player% &a¡Has votado en los 2 sitios y ganado Fly por 1 hora!",
                List.of("lp user %player% permission settemp protectionblocks.fly true 1h server=survival"),
                "☁"
        );
    }
}

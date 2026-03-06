package com.valerinsmp.vvotes.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record PluginConfig(
        String sqliteFile,
        int busyTimeoutMs,
        String timezone,
        boolean broadcastOnVote,
        int suspiciousWindowSeconds,
        Map<String, List<String>> forcedServiceCommandsMap,
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

    public List<String> forcedServiceCommands(String serviceName) {
        if (serviceName == null) {
            return Collections.emptyList();
        }
        return forcedServiceCommandsMap.getOrDefault(serviceName.toLowerCase(), Collections.emptyList());
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

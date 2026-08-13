package com.valerinsmp.vvotes.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

public record PluginConfig(
        String sqliteFile,
        int busyTimeoutMs,
        String timezone,
        boolean processTestVotes,
        boolean broadcastOnVote,
        int suspiciousWindowSeconds,
        Map<String, List<String>> forcedServiceCommandsMap,
        NavigableMap<Integer, List<String>> globalDailyGoals,
        int globalRecurringStart,
        int globalRecurringEvery,
        List<String> globalRecurringCommands,
        NavigableMap<Integer, List<String>> playerMonthlyGoals,
        List<String> voteRewards,
        NavigableMap<Integer, List<String>> monthlyStreakRewards,
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
    public PluginConfig {
        Map<String, List<String>> services = new HashMap<>();
        if (forcedServiceCommandsMap != null) {
            forcedServiceCommandsMap.forEach((key, value) -> services.put(key.toLowerCase(Locale.ROOT),
                    List.copyOf(value == null ? List.of() : value)));
        }
        forcedServiceCommandsMap = Map.copyOf(services);
        globalDailyGoals = immutableTree(globalDailyGoals);
        globalRecurringCommands = List.copyOf(globalRecurringCommands == null ? List.of() : globalRecurringCommands);
        playerMonthlyGoals = immutableTree(playerMonthlyGoals);
        voteRewards = List.copyOf(voteRewards == null ? List.of() : voteRewards);
        monthlyStreakRewards = immutableTree(monthlyStreakRewards);
        doubleSiteBonusCommands = List.copyOf(doubleSiteBonusCommands == null ? List.of() : doubleSiteBonusCommands);
    }

    public List<String> forcedServiceCommands(String serviceName) {
        if (serviceName == null) return Collections.emptyList();
        return forcedServiceCommandsMap.getOrDefault(serviceName.toLowerCase(Locale.ROOT), Collections.emptyList());
    }

    public static PluginConfig defaultConfig() {
        return new PluginConfig(
                "data/vvotes.db", 5_000, "America/Santiago", false, true, 10,
                Collections.emptyMap(), new TreeMap<>(), 0, 0, Collections.emptyList(),
                new TreeMap<>(), Collections.emptyList(), new TreeMap<>(), true, 1,
                "lp user <player> parent addtemp arcano 30d", 5, true, 3,
                "<player> <green>Has votado en los 3 sitios y ganado Fly por 1 hora.</green>",
                List.of("lp user <player> permission settemp protectionblocks.fly true 1h server=survival"),
                " ☁ "
        );
    }

    private static NavigableMap<Integer, List<String>> immutableTree(Map<Integer, List<String>> input) {
        TreeMap<Integer, List<String>> result = new TreeMap<>();
        if (input != null) input.forEach((key, value) ->
                result.put(key, List.copyOf(value == null ? List.of() : value)));
        return Collections.unmodifiableNavigableMap(result);
    }
}

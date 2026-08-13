package com.valerinsmp.vvotes.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PluginConfigTest {
    @Test
    void forcedServiceCommandsAreCaseInsensitiveAndSafeForUnknownServices() {
        PluginConfig defaults = PluginConfig.defaultConfig();
        PluginConfig config = new PluginConfig(
                defaults.sqliteFile(), defaults.busyTimeoutMs(), defaults.timezone(),
                defaults.processTestVotes(),
                defaults.broadcastOnVote(), defaults.suspiciousWindowSeconds(),
                Map.of("planetminecraft", List.of("reward %player%")),
                defaults.globalDailyGoals(), defaults.globalRecurringStart(),
                defaults.globalRecurringEvery(), defaults.globalRecurringCommands(),
                defaults.playerMonthlyGoals(), defaults.voteRewards(), defaults.monthlyStreakRewards(),
                defaults.monthlyDrawEnabled(), defaults.monthlyDrawMinVotes(),
                defaults.monthlyDrawRewardCommand(), defaults.monthlyDrawAutoCheckMinutes(),
                defaults.doubleSiteBonusEnabled(), defaults.doubleSiteBonusRequiredSites(),
                defaults.doubleSiteBonusMessage(), defaults.doubleSiteBonusCommands(),
                defaults.doubleSiteTodayIcon());

        assertEquals(List.of("reward %player%"), config.forcedServiceCommands("PlanetMinecraft"));
        assertTrue(config.forcedServiceCommands("unknown").isEmpty());
        assertTrue(config.forcedServiceCommands(null).isEmpty());
    }
}

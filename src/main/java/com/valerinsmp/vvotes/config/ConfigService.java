package com.valerinsmp.vvotes.config;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ConfigService {
    private final VVotesPlugin plugin;
    private PluginConfig config;

    public ConfigService(VVotesPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration file = plugin.getConfig();

        String timezone = file.getString("timezone", "America/Santiago");
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException exception) {
            plugin.getLogger().warning("Zona horaria invalida en config.yml, usando America/Santiago");
            timezone = "America/Santiago";
        }

        TreeMap<Integer, List<String>> globalDaily = parseGoalCommands(file.getConfigurationSection("goals.global-daily"));
        int recurringStart = file.getInt("goals.global-recurring.start-after", 0);
        int recurringEvery = file.getInt("goals.global-recurring.every", 0);
        List<String> recurringCommands = file.getStringList("goals.global-recurring.commands");
        Map<String, String> forcedServiceCommands = parseStringMap(file.getConfigurationSection("services.force-player-command"));
        TreeMap<Integer, List<String>> playerMonthly = parseGoalCommands(file.getConfigurationSection("goals.player-monthly"));
        TreeMap<Integer, List<String>> monthlyStreak = parseGoalCommands(file.getConfigurationSection("rewards.streak-monthly"));
        boolean monthlyDrawEnabled = file.getBoolean("monthly-draw.enabled", true);
        int monthlyDrawMinVotes = file.getInt("monthly-draw.min-votes", 1);
        String monthlyDrawRewardCommand = file.getString("monthly-draw.reward-command", "lp user <player> parent addtemp arcano 30d");
        int monthlyDrawAutoCheckMinutes = file.getInt("monthly-draw.auto-check-minutes", 5);

        this.config = new PluginConfig(
                file.getString("storage.sqlite-file", "data/vvotes.db"),
                file.getInt("storage.busy-timeout-ms", 5000),
                timezone,
                file.getBoolean("global.broadcast-on-vote", true),
                file.getInt("global.suspicious-window-seconds", 10),
                forcedServiceCommands,
                globalDaily,
                recurringStart,
                recurringEvery,
                recurringCommands,
                playerMonthly,
                file.getStringList("rewards.vote"),
                monthlyStreak,
                monthlyDrawEnabled,
                monthlyDrawMinVotes,
                monthlyDrawRewardCommand,
                monthlyDrawAutoCheckMinutes
        );
    }

    public PluginConfig get() {
        return config;
    }

    private TreeMap<Integer, List<String>> parseGoalCommands(ConfigurationSection section) {
        TreeMap<Integer, List<String>> map = new TreeMap<>();
        if (section == null) {
            return map;
        }
        for (String key : section.getKeys(false)) {
            try {
                int threshold = Integer.parseInt(key);
                List<String> commands = new ArrayList<>(section.getStringList(key));
                map.put(threshold, commands);
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Meta invalida: " + key);
            }
        }
        return map;
    }

    private Map<String, String> parseStringMap(ConfigurationSection section) {
        Map<String, String> map = new HashMap<>();
        if (section == null) {
            return map;
        }
        for (String key : section.getKeys(false)) {
            String value = section.getString(key, "").trim();
            if (!value.isBlank()) {
                map.put(key.toLowerCase(), value);
            }
        }
        return map;
    }
}

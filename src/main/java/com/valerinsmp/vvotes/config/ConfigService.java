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
        Map<String, List<String>> forcedServiceCommands = parseCommandMap(file.getConfigurationSection("services.force-player-command"));
        TreeMap<Integer, List<String>> playerMonthly = parseGoalCommands(file.getConfigurationSection("goals.player-monthly"));
        TreeMap<Integer, List<String>> monthlyStreak = parseGoalCommands(file.getConfigurationSection("rewards.streak-monthly"));
        boolean monthlyDrawEnabled = file.getBoolean("monthly-draw.enabled", true);
        int monthlyDrawMinVotes = file.getInt("monthly-draw.min-votes", 1);
        String monthlyDrawRewardCommand = file.getString("monthly-draw.reward-command", "lp user <player> parent addtemp arcano 30d");
        int monthlyDrawAutoCheckMinutes = file.getInt("monthly-draw.auto-check-minutes", 5);
        boolean doubleSiteBonusEnabled = file.getBoolean("double-site-bonus.enabled", true);
        int doubleSiteBonusRequiredSites = Math.max(1, file.getInt("double-site-bonus.required-sites", 2));
        String doubleSiteBonusMessage = file.getString(
                "double-site-bonus.message",
                "%player% &a¡Has votado en los 2 sitios y ganado Fly por 1 hora!"
        );
        List<String> doubleSiteBonusCommands = new ArrayList<>(file.getStringList("double-site-bonus.commands"));
        if (doubleSiteBonusCommands.isEmpty()) {
            String singleCommand = file.getString("double-site-bonus.command", "").trim();
            if (!singleCommand.isBlank()) {
                doubleSiteBonusCommands.add(singleCommand);
            }
        }
        String doubleSiteTodayIcon = file.getString("double-site-bonus.placeholder-icon", "☁");

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
                monthlyDrawAutoCheckMinutes,
                doubleSiteBonusEnabled,
                doubleSiteBonusRequiredSites,
                doubleSiteBonusMessage,
                doubleSiteBonusCommands,
                doubleSiteTodayIcon
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

    private Map<String, List<String>> parseCommandMap(ConfigurationSection section) {
        Map<String, List<String>> map = new HashMap<>();
        if (section == null) {
            return map;
        }
        parseCommandMapRecursive(section, "", map);
        return map;
    }

    private void parseCommandMapRecursive(ConfigurationSection section, String prefix, Map<String, List<String>> out) {
        for (String key : section.getKeys(false)) {
            String pathKey = prefix.isBlank() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                parseCommandMapRecursive(child, pathKey, out);
                continue;
            }

            List<String> commands = new ArrayList<>();
            if (section.isList(key)) {
                for (String command : section.getStringList(key)) {
                    String trimmed = command.trim();
                    if (!trimmed.isBlank()) {
                        commands.add(trimmed);
                    }
                }
            } else {
                String single = section.getString(key, "").trim();
                if (!single.isBlank()) {
                    commands.add(single);
                }
            }
            if (!commands.isEmpty()) {
                out.put(pathKey.toLowerCase(), commands);
            }
        }
    }
}

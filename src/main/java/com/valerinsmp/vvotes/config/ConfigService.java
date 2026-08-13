package com.valerinsmp.vvotes.config;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Loads and validates immutable configuration candidates before they become live. */
public final class ConfigService {
    private final VVotesPlugin plugin;
    private volatile PluginConfig config;

    public ConfigService(VVotesPlugin plugin) {
        this.plugin = plugin;
        apply(loadCandidate());
    }

    public PluginConfig loadCandidate() {
        Path path = plugin.getDataFolder().toPath().resolve("config.yml");
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(stripBom(text));
            return parse(yaml);
        } catch (Exception exception) {
            throw new IllegalStateException("config.yml invalido: " + exception.getMessage(), exception);
        }
    }

    public void apply(PluginConfig candidate) {
        this.config = candidate;
    }

    public PluginConfig get() {
        return config;
    }

    public void requireRuntimeCompatible(PluginConfig candidate) {
        PluginConfig current = get();
        if (!current.sqliteFile().equals(candidate.sqliteFile())
                || current.busyTimeoutMs() != candidate.busyTimeoutMs()
                || !current.timezone().equals(candidate.timezone())) {
            throw new IllegalArgumentException("storage.sqlite-file, storage.busy-timeout-ms y timezone requieren reinicio");
        }
    }

    private PluginConfig parse(YamlConfiguration file) {
        String timezone = file.getString("timezone", "America/Santiago").strip();
        ZoneId.of(timezone);
        String sqliteFile = file.getString("storage.sqlite-file", "data/vvotes.db").strip();
        Path sqlitePath = Path.of(sqliteFile);
        if (sqliteFile.isBlank() || sqliteFile.length() > 240 || sqlitePath.isAbsolute() || sqlitePath.normalize().startsWith("..")) {
            throw new IllegalArgumentException("storage.sqlite-file debe ser una ruta relativa segura");
        }
        int busyTimeout = bounded(file.getInt("storage.busy-timeout-ms", 5_000), 1, 60_000,
                "storage.busy-timeout-ms");
        int suspiciousWindow = bounded(file.getInt("global.suspicious-window-seconds", 10), 0, 86_400,
                "global.suspicious-window-seconds");

        TreeMap<Integer, List<String>> globalDaily = parseGoalCommands(file.getConfigurationSection("goals.global-daily"));
        int recurringStart = nonNegative(file.getInt("goals.global-recurring.start-after", 0),
                "goals.global-recurring.start-after");
        int recurringEvery = nonNegative(file.getInt("goals.global-recurring.every", 0),
                "goals.global-recurring.every");
        if ((recurringStart == 0) != (recurringEvery == 0)) {
            throw new IllegalArgumentException("La meta recurrente debe definir start-after y every juntos");
        }
        List<String> recurringCommands = commands(file.getStringList("goals.global-recurring.commands"));
        Map<String, List<String>> forcedCommands = parseCommandMap(file.getConfigurationSection("services.force-player-command"));
        TreeMap<Integer, List<String>> playerMonthly = parseGoalCommands(file.getConfigurationSection("goals.player-monthly"));
        TreeMap<Integer, List<String>> monthlyStreak = parseGoalCommands(file.getConfigurationSection("rewards.streak-monthly"));

        String drawCommand = command(file.getString("monthly-draw.reward-command",
                "lp user <player> parent addtemp arcano 30d"));
        int requiredSites = bounded(file.getInt("triple-site-bonus.required-sites", 3), 1, 64,
                "triple-site-bonus.required-sites");
        return new PluginConfig(
                sqliteFile, busyTimeout, timezone,
                file.getBoolean("provider.process-test-votes", false),
                file.getBoolean("global.broadcast-on-vote", true), suspiciousWindow,
                forcedCommands, globalDaily, recurringStart, recurringEvery, recurringCommands,
                playerMonthly, commands(file.getStringList("rewards.vote")), monthlyStreak,
                file.getBoolean("monthly-draw.enabled", true),
                bounded(file.getInt("monthly-draw.min-votes", 1), 1, 1_000_000, "monthly-draw.min-votes"),
                drawCommand,
                bounded(file.getInt("monthly-draw.auto-check-minutes", 5), 1, 1_440,
                        "monthly-draw.auto-check-minutes"),
                file.getBoolean("triple-site-bonus.enabled", true), requiredSites,
                boundedText(file.getString("triple-site-bonus.message", ""), 2_048, "triple-site-bonus.message"),
                commands(readCommands(file, "triple-site-bonus")),
                boundedText(file.getString("triple-site-bonus.placeholder-icon", " ☁ "), 64,
                        "triple-site-bonus.placeholder-icon")
        );
    }

    private List<String> readCommands(YamlConfiguration file, String parent) {
        List<String> list = new ArrayList<>(file.getStringList(parent + ".commands"));
        if (list.isEmpty()) {
            String one = file.getString(parent + ".command", "");
            if (!one.isBlank()) list.add(one);
        }
        return list;
    }

    private TreeMap<Integer, List<String>> parseGoalCommands(ConfigurationSection section) {
        TreeMap<Integer, List<String>> result = new TreeMap<>();
        if (section == null) return result;
        for (String key : section.getKeys(false)) {
            int threshold;
            try {
                threshold = Integer.parseInt(key);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Meta invalida: " + key, invalid);
            }
            if (threshold <= 0) throw new IllegalArgumentException("Meta no positiva: " + key);
            result.put(threshold, commands(section.getStringList(key)));
        }
        return result;
    }

    private Map<String, List<String>> parseCommandMap(ConfigurationSection section) {
        Map<String, List<String>> result = new HashMap<>();
        if (section != null) parseCommandMapRecursive(section, "", result);
        return result;
    }

    private void parseCommandMapRecursive(ConfigurationSection section, String prefix, Map<String, List<String>> out) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isBlank() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                parseCommandMapRecursive(child, path, out);
                continue;
            }
            if (path.length() > 128) throw new IllegalArgumentException("Nombre de servicio demasiado largo");
            List<String> values = section.isList(key)
                    ? commands(section.getStringList(key))
                    : commands(List.of(section.getString(key, "")));
            if (!values.isEmpty()) out.put(path.toLowerCase(Locale.ROOT), values);
        }
    }

    private List<String> commands(List<String> input) {
        if (input == null) return List.of();
        List<String> result = new ArrayList<>();
        for (String value : input) {
            if (value == null || value.isBlank()) continue;
            result.add(command(value));
        }
        return List.copyOf(result);
    }

    private String command(String value) {
        String command = value == null ? "" : value.strip();
        if (command.isBlank() || command.length() > 1_024 || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Comando vacio, multilinea o demasiado largo");
        }
        return command;
    }

    private int bounded(int value, int minimum, int maximum, String path) {
        if (value < minimum || value > maximum) throw new IllegalArgumentException(path + " fuera de rango");
        return value;
    }

    private int nonNegative(int value, String path) {
        if (value < 0) throw new IllegalArgumentException(path + " no puede ser negativo");
        return value;
    }

    private String boundedText(String value, int maximum, String path) {
        String result = value == null ? "" : value;
        if (result.length() > maximum) throw new IllegalArgumentException(path + " demasiado largo");
        return result;
    }

    private String stripBom(String value) {
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }
}

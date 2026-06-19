package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.platform.PlatformAdapter;
import com.valerinsmp.vvotes.util.YamlUtils;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageService {
    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private final VVotesPlugin plugin;
    private final ConfigService configService;
    private final PlatformAdapter platformAdapter;
    private YamlConfiguration messages;

    public MessageService(VVotesPlugin plugin, ConfigService configService, PlatformAdapter platformAdapter) {
        this.plugin = plugin;
        this.configService = configService;
        this.platformAdapter = platformAdapter;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        try {
            this.messages = YamlUtils.loadSanitized(file);
            plugin.getLogger().info("messages.yml cargado (" + this.messages.getKeys(true).size() + " entradas)");
        } catch (Exception e) {
            plugin.getLogger().severe("No se pudo cargar messages.yml: " + e.getMessage());
            this.messages = new YamlConfiguration();
        }
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        List<String> lines = messages(key, placeholders);
        if (lines.isEmpty()) {
            plugin.getLogger().warning("[MessageService] No hay mensaje para key: " + key);
            return;
        }
        for (String line : lines) {
            try {
                platformAdapter.send(sender, line);
            } catch (Exception e) {
                plugin.getLogger().warning("[MessageService] Error enviando mensaje '" + key + "': " + e.getMessage());
            }
        }
    }

    public String message(String key, Map<String, String> placeholders) {
        List<String> lines = messages(key, placeholders);
        return lines.isEmpty() ? key : lines.get(0);
    }

    public String text(String key, Map<String, String> placeholders) {
        String basePath = resolvePath(key);
        if (basePath == null) {
            return "";
        }

        String line;
        if (messages.isList(basePath)) {
            List<String> list = messages.getStringList(basePath);
            line = list.isEmpty() ? "" : list.get(0);
        } else {
            line = messages.getString(basePath, "");
        }

        String prefixed = line.replace("%prefix%", resolvePrefix());
        return applyPlaceholders(prefixed, placeholders);
    }

    public List<String> messages(String key, Map<String, String> placeholders) {
        String basePath = resolvePath(key);
        if (basePath == null) {
            return Collections.emptyList();
        }

        List<String> lines = new ArrayList<>();
        if (messages.isList(basePath)) {
            lines.addAll(messages.getStringList(basePath));
        } else {
            lines.add(messages.getString(basePath, key));
        }

        String prefix = resolvePrefix();
        List<String> output = new ArrayList<>();
        for (String line : lines) {
            String prefixed = line.replace("%prefix%", prefix);
            output.add(applyPlaceholders(prefixed, placeholders));
        }
        return output;
    }

    public String titlePart(String key, Map<String, String> placeholders) {
        String path = "titles." + key;
        String line = messages.getString(path, "");
        if (line.isBlank()) {
            return "";
        }
        return applyPlaceholders(line, placeholders);
    }

    public String actionbar(String key, Map<String, String> placeholders) {
        String path = "actionbar." + key;
        String line = messages.getString(path, "");
        if (line.isBlank()) {
            return "";
        }
        return applyPlaceholders(line, placeholders);
    }

    public String applyPlaceholders(String value, Map<String, String> placeholders) {
        String text = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("<" + entry.getKey() + ">", entry.getValue());
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return text;
    }

    public PlatformAdapter platformAdapter() {
        return platformAdapter;
    }

    public ConfigService configService() {
        return configService;
    }

    private String resolvePath(String key) {
        if (messages.contains(key)) {
            return key;
        }

        String nestedPath = "messages." + key;
        if (messages.contains(nestedPath)) {
            return nestedPath;
        }

        return null;
    }

    private String resolvePrefix() {
        if (messages.contains("messages.prefix")) {
            return messages.getString("messages.prefix", "");
        }
        return messages.getString("prefix", "");
    }

    public String convertHexToLegacy(String input) {
        Matcher matcher = HEX_PATTERN.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1).toUpperCase();
            StringBuilder replacement = new StringBuilder("&x");
            for (char c : hex.toCharArray()) {
                replacement.append('&').append(c);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement.toString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}

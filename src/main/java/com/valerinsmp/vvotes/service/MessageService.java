package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
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
    private final MiniMessage miniMessage;
    private final LegacyComponentSerializer legacySerializer;
    private YamlConfiguration messages;

    public MessageService(VVotesPlugin plugin, ConfigService configService) {
        this.plugin = plugin;
        this.configService = configService;
        this.miniMessage = MiniMessage.miniMessage();
        this.legacySerializer = LegacyComponentSerializer.builder()
                .character('&')
                .hexColors()
                .useUnusualXRepeatedCharacterHexFormat()
                .build();
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        this.messages = YamlConfiguration.loadConfiguration(file);
    }

    public void send(CommandSender sender, String key) {
        send(sender, key, Collections.emptyMap());
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        List<Component> components = messages(key, placeholders);
        if (components.isEmpty()) {
            return;
        }

        if (sender instanceof Player player) {
            for (Component component : components) {
                player.sendMessage(component);
            }
            return;
        }

        for (Component component : components) {
            sender.sendMessage(PlainTextComponentSerializer.plainText().serialize(component));
        }
    }

    public Component message(String key, Map<String, String> placeholders) {
        List<Component> components = messages(key, placeholders);
        if (components.isEmpty()) {
            return parse(key);
        }
        return components.get(0);
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

    public List<Component> messages(String key, Map<String, String> placeholders) {
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
        List<Component> output = new ArrayList<>();
        for (String line : lines) {
            String prefixed = line.replace("%prefix%", prefix);
            output.add(parse(applyPlaceholders(prefixed, placeholders)));
        }
        return output;
    }

    public Component titlePart(String key, Map<String, String> placeholders) {
        String path = "titles." + key;
        String line = messages.getString(path, "");
        if (line.isBlank()) {
            return parse("");
        }
        return parse(applyPlaceholders(line, placeholders));
    }

    public Component actionbar(String key, Map<String, String> placeholders) {
        String path = "actionbar." + key;
        String line = messages.getString(path, "");
        if (line.isBlank()) {
            return parse("");
        }
        return parse(applyPlaceholders(line, placeholders));
    }

    public Component parse(String line) {
        if (line.contains("<") && line.contains(">")) {
            try {
                return miniMessage.deserialize(line);
            } catch (Exception ignored) {
                // fallback legacy below
            }
        }
        return legacySerializer.deserialize(convertHexToLegacy(line));
    }

    public String applyPlaceholders(String value, Map<String, String> placeholders) {
        String text = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace("<" + entry.getKey() + ">", entry.getValue());
            text = text.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return text;
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

    private String convertHexToLegacy(String input) {
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

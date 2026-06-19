package com.valerinsmp.vvotes.paper.platform;

import com.valerinsmp.vvotes.platform.PlatformAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperAdapter implements PlatformAdapter {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Title.Times TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(200), Duration.ofMillis(2000), Duration.ofMillis(400));

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    @Override
    public void send(CommandSender sender, String text) {
        Component comp = parse(text);
        if (sender instanceof Player player) {
            player.sendMessage(comp);
        } else {
            sender.sendMessage(PlainTextComponentSerializer.plainText().serialize(comp));
        }
    }

    @Override
    public void broadcast(String text) {
        Bukkit.broadcast(parse(text));
    }

    @Override
    public void showTitle(Player player, String titleText, String subtitleText) {
        player.showTitle(Title.title(parse(titleText), parse(subtitleText), TITLE_TIMES));
    }

    private Component parse(String line) {
        if (line.contains("&")) {
            return legacy.deserialize(convertHexToLegacy(line));
        }
        if (line.contains("<") && line.contains(">")) {
            try {
                return miniMessage.deserialize(line);
            } catch (Exception ignored) {
            }
        }
        return legacy.deserialize(convertHexToLegacy(line));
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

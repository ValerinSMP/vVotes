package com.valerinsmp.vvotes.arclight.platform;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.valerinsmp.vvotes.platform.PlatformAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArclightAdapter implements PlatformAdapter {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private final JavaPlugin plugin;
    private final ProtocolManager protocolManager;
    // adventure-text-minimessage is shaded into this JAR
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    // adventure-text-serializer-legacy is available via spigot-api
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    public ArclightAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.protocolManager = ProtocolLibrary.getProtocolManager();
    }

    @Override
    public void send(CommandSender sender, String text) {
        sender.sendMessage(toLegacySection(text));
    }

    @Override
    public void broadcast(String text) {
        String legacy = toLegacySection(text);
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendMessage(legacy);
        }
        Bukkit.getConsoleSender().sendMessage(ChatColor.stripColor(legacy));
    }

    @Override
    public void showTitle(Player player, String titleText, String subtitleText) {
        try {
            String titleLegacy = toLegacySection(titleText);
            String subtitleLegacy = toLegacySection(subtitleText);

            // Times: fade_in=10t stay=40t fade_out=20t
            PacketContainer times = protocolManager.createPacket(PacketType.Play.Server.SET_TITLES_ANIMATION);
            times.getIntegers().write(0, 10);
            times.getIntegers().write(1, 40);
            times.getIntegers().write(2, 20);

            PacketContainer titlePkt = protocolManager.createPacket(PacketType.Play.Server.SET_TITLE_TEXT);
            titlePkt.getChatComponents().write(0, WrappedChatComponent.fromLegacyText(titleLegacy));

            PacketContainer subtitlePkt = protocolManager.createPacket(PacketType.Play.Server.SET_SUBTITLE_TEXT);
            subtitlePkt.getChatComponents().write(0, WrappedChatComponent.fromLegacyText(subtitleLegacy));

            protocolManager.sendServerPacket(player, times);
            protocolManager.sendServerPacket(player, titlePkt);
            protocolManager.sendServerPacket(player, subtitlePkt);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Error sending title via ProtocolLib: " + e.getMessage(), e);
        }
    }

    private String toLegacySection(String text) {
        // Parse MiniMessage/legacy-& to Component, then serialize to §-code string
        Component comp = parseComponent(text);
        String withSection = legacy.serialize(comp);
        // translateAlternateColorCodes handles any remaining &-codes
        return ChatColor.translateAlternateColorCodes('&', withSection);
    }

    private Component parseComponent(String line) {
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

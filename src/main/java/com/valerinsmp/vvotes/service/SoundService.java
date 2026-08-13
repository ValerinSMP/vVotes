package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class SoundService {
    private final VVotesPlugin plugin;
    private volatile SoundCatalog catalog;

    public SoundService(VVotesPlugin plugin) {
        this.plugin = plugin;
        apply(loadCandidate());
    }

    public SoundCatalog loadCandidate() {
        Path path = plugin.getDataFolder().toPath().resolve("sound.yml");
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(text.startsWith("\uFEFF") ? text.substring(1) : text);
            Map<String, SoundEntry> sounds = new HashMap<>();
            ConfigurationSection section = yaml.getConfigurationSection("sounds");
            if (section != null) loadSection(section, "", sounds);
            return new SoundCatalog(Map.copyOf(sounds));
        } catch (Exception exception) {
            throw new IllegalStateException("sound.yml invalido: " + exception.getMessage(), exception);
        }
    }

    public void apply(SoundCatalog candidate) {
        this.catalog = candidate;
    }

    public void play(Player player, String key) {
        if (player == null) return;
        SoundEntry entry = catalog.sounds().get(key.toLowerCase(Locale.ROOT));
        if (entry == null || !entry.enabled()) return;
        player.playSound(player.getLocation(), entry.sound(), entry.volume(), entry.pitch());
    }

    public void playToAll(String key) {
        SoundEntry entry = catalog.sounds().get(key.toLowerCase(Locale.ROOT));
        if (entry == null || !entry.enabled()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), entry.sound(), entry.volume(), entry.pitch());
        }
    }

    private void loadSection(ConfigurationSection section, String prefix, Map<String, SoundEntry> target) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) continue;
            if (!child.contains("sound")) {
                loadSection(child, path, target);
                continue;
            }
            String name = child.getString("sound", "").strip();
            Sound sound = resolveSound(name);
            if (sound == null) throw new IllegalArgumentException("Sonido invalido: " + path + " -> " + name);
            double volume = child.getDouble("volume", 1D);
            double pitch = child.getDouble("pitch", 1D);
            if (volume < 0 || volume > 4 || pitch < 0 || pitch > 2) {
                throw new IllegalArgumentException("Volumen o pitch fuera de rango: " + path);
            }
            target.put(path.toLowerCase(Locale.ROOT), new SoundEntry(child.getBoolean("enabled", true), sound,
                    (float) volume, (float) pitch));
        }
    }

    private Sound resolveSound(String raw) {
        if (raw.isBlank()) return null;
        String enumLike = raw.toUpperCase(Locale.ROOT).replace(' ', '_');
        try {
            @SuppressWarnings("removal") Sound legacy = Sound.valueOf(enumLike);
            return legacy;
        } catch (IllegalArgumentException ignored) { }
        String normalized = raw.toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        return key == null ? null : Registry.SOUNDS.get(key);
    }

    public record SoundCatalog(Map<String, SoundEntry> sounds) { }
    public record SoundEntry(boolean enabled, Sound sound, float volume, float pitch) { }
}

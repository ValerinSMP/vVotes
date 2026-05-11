package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public final class SoundService {
    private final VVotesPlugin plugin;
    private final Map<String, SoundEntry> sounds;

    public SoundService(VVotesPlugin plugin) {
        this.plugin = plugin;
        this.sounds = new HashMap<>();
        reload();
    }

    public void reload() {
        sounds.clear();
        File file = new File(plugin.getDataFolder(), "sound.yml");
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = configuration.getConfigurationSection("sounds");
        if (section == null) {
            return;
        }
        loadSection(section, "");
    }

    public void play(Player player, String key) {
        if (player == null) {
            return;
        }
        SoundEntry entry = sounds.get(key.toLowerCase());
        if (entry == null || !entry.enabled) {
            return;
        }
        player.playSound(player.getLocation(), entry.sound, entry.volume, entry.pitch);
    }

    public void playToAll(String key) {
        SoundEntry entry = sounds.get(key.toLowerCase());
        if (entry == null || !entry.enabled) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.playSound(player.getLocation(), entry.sound, entry.volume, entry.pitch);
        }
    }

    private void loadSection(ConfigurationSection section, String prefix) {
        for (String key : section.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                if (child.contains("sound")) {
                    parseEntry(path, child);
                } else {
                    loadSection(child, path);
                }
            }
        }
    }

    private void parseEntry(String path, ConfigurationSection section) {
        String soundName = section.getString("sound", "");
        if (soundName.isBlank()) {
            return;
        }
        Sound sound = resolveSound(soundName);
        if (sound == null) {
            plugin.getLogger().warning("Sonido invalido en sound.yml: " + path + " -> " + soundName);
            return;
        }
        boolean enabled = section.getBoolean("enabled", true);
        float volume = (float) section.getDouble("volume", 1.0D);
        float pitch = (float) section.getDouble("pitch", 1.0D);
        sounds.put(path.toLowerCase(), new SoundEntry(enabled, sound, volume, pitch));
    }

    private record SoundEntry(boolean enabled, Sound sound, float volume, float pitch) {
    }

    private Sound resolveSound(String raw) {
        String input = raw.trim();
        if (input.isBlank()) {
            return null;
        }

        // 1) Backward compatible enum style: BLOCK_RESPAWN_ANCHOR_CHARGE
        String enumLike = input.toUpperCase().replace(' ', '_');
        try {
            @SuppressWarnings("removal")
            Sound legacy = Sound.valueOf(enumLike);
            return legacy;
        } catch (IllegalArgumentException ignored) {
        }

        // 2) Namespaced key style: minecraft:block.respawn_anchor.charge
        String normalized = input.toLowerCase().replace(' ', '_');
        NamespacedKey key = NamespacedKey.fromString(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (key == null) {
            return null;
        }
        return Registry.SOUNDS.get(key);
    }
}

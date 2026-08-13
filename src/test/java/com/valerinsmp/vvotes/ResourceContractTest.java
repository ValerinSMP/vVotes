package com.valerinsmp.vvotes;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceContractTest {
    private static final Pattern LEGACY_COLOR = Pattern.compile("(?i)&(?:#[0-9a-f]{6}|[0-9a-fk-orx])");
    private final Path resources = Path.of(System.getProperty("user.dir"), "src/main/resources");

    @Test
    void resourcesAreStrictUtf8WithoutLegacyColorsOrKnownMojibake() throws Exception {
        for (String name : new String[]{"config.yml", "messages.yml", "sound.yml", "plugin.yml"}) {
            String text = Files.readString(resources.resolve(name), StandardCharsets.UTF_8);
            assertFalse(text.contains("Ã") || text.contains("Â") || text.contains("â€") || text.contains("�"), name);
        }
        String messages = Files.readString(resources.resolve("messages.yml"), StandardCharsets.UTF_8);
        assertFalse(LEGACY_COLOR.matcher(messages).find());
        assertArrayEquals(new byte[]{(byte) 0xE1, (byte) 0xB4, (byte) 0xA0,
                        (byte) 0xE1, (byte) 0xB4, (byte) 0x8F,
                        (byte) 0xE1, (byte) 0xB4, (byte) 0x9B,
                        (byte) 0xE1, (byte) 0xB4, (byte) 0x87, 0x73},
                "ᴠᴏᴛᴇs".getBytes(StandardCharsets.UTF_8));
        assertTrue(messages.contains("ᴠᴏᴛᴇs"));
    }

    @Test
    void everyBundledMessageIsValidYamlAndMiniMessage() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(resources.resolve("messages.yml"), StandardCharsets.UTF_8));
        parseLeaves(yaml);
    }

    @Test
    void descriptorKeepsVersionAndRequiresProvider() throws Exception {
        String descriptor = Files.readString(resources.resolve("plugin.yml"), StandardCharsets.UTF_8);
        String build = Files.readString(Path.of(System.getProperty("user.dir"), "build.gradle.kts"));
        assertTrue(descriptor.contains("version: '${version}'"));
        assertTrue(build.contains("version = \"1.0.0\""));
        assertTrue(descriptor.contains("depend: [VotifierPlus]"));
        assertTrue(descriptor.contains("softdepend: [PlaceholderAPI]"));
    }

    private void parseLeaves(ConfigurationSection section) {
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                parseLeaves(child);
            } else if (section.isList(key)) {
                section.getStringList(key).forEach(MiniMessage.miniMessage()::deserialize);
            } else {
                MiniMessage.miniMessage().deserialize(section.getString(key, ""));
            }
        }
    }
}

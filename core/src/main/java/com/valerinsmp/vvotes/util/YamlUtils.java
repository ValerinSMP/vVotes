package com.valerinsmp.vvotes.util;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class YamlUtils {

    private YamlUtils() {}

    public static YamlConfiguration loadSanitized(File file) throws IOException {
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return fromText(sanitize(text));
    }

    public static YamlConfiguration loadSanitized(InputStream input) throws IOException {
        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        return fromText(sanitize(text));
    }

    private static YamlConfiguration fromText(String text) {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException("YAML invalido tras saneamiento: " + exception.getMessage(), exception);
        }
        return yaml;
    }

    public static String sanitize(String input) {
        String text = input.startsWith("﻿") ? input.substring(1) : input;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                out.append(c);
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
}

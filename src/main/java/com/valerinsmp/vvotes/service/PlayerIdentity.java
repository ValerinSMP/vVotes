package com.valerinsmp.vvotes.service;

import java.util.Locale;
import java.util.UUID;

public record PlayerIdentity(UUID uuid, String exactName) {
    public PlayerIdentity {
        if (uuid == null || exactName == null || exactName.isBlank()) {
            throw new IllegalArgumentException("Player identity requires UUID and exact name");
        }
    }

    String normalizedName() {
        return exactName.strip().toLowerCase(Locale.ROOT);
    }
}

package com.valerinsmp.vvotes.model;

import java.util.UUID;

public record PlayerStats(
        UUID uuid,
        String name,
        double totalVotes,
        double dailyVotes,
        double monthlyVotes,
        int streakMonthly,
        String lastVoteDay,
        String lastMonthKey,
        long lastVoteEpoch
) {
    public static PlayerStats empty(UUID uuid, String name) {
        return new PlayerStats(uuid, name, 0.0, 0.0, 0.0, 0, "", "", 0L);
    }
}

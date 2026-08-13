package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.model.PlayerStats;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lock-free read model used by commands and PlaceholderAPI; it never opens SQLite. */
final class VoteSnapshots {
    private final Map<UUID, PlayerStats> stats = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> muted = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> triple = new ConcurrentHashMap<>();
    private volatile double globalDaily;
    private volatile String globalDay = "";
    private volatile String tripleDay = "";

    void load(Map<UUID, PlayerStats> allStats, Map<UUID, Boolean> allMuted, double global, String day) {
        stats.clear(); stats.putAll(allStats);
        muted.clear(); muted.putAll(allMuted);
        globalDaily = global; globalDay = day; tripleDay = day;
    }

    void updatePlayer(PlayerStats value, boolean tripleToday, String day) {
        stats.put(value.uuid(), value);
        if (!day.equals(tripleDay)) triple.clear();
        tripleDay = day;
        if (tripleToday) triple.put(value.uuid(), true); else triple.remove(value.uuid());
    }

    PlayerStats stats(UUID uuid, String name, String day, String month) {
        PlayerStats stored = stats.getOrDefault(uuid, PlayerStats.empty(uuid, name));
        return new PlayerStats(stored.uuid(), stored.name(), stored.totalVotes(),
                day.equals(stored.lastVoteDay()) ? stored.dailyVotes() : 0,
                month.equals(stored.lastMonthKey()) ? stored.monthlyVotes() : 0,
                stored.streakMonthly(), stored.lastVoteDay(), stored.lastMonthKey(), stored.lastVoteEpoch());
    }

    void setGlobal(double value, String day) { globalDaily = value; globalDay = day; }
    double global(String day) { return day.equals(globalDay) ? globalDaily : 0; }
    void setMuted(UUID uuid, boolean value) { muted.put(uuid, value); }
    boolean muted(UUID uuid) { return muted.getOrDefault(uuid, false); }
    boolean triple(UUID uuid, String day) { return day.equals(tripleDay) && triple.containsKey(uuid); }
}

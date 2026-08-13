package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.model.PlayerStats;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoteSnapshotsTest {
    @Test
    void concurrentPlaceholderReadsNeverTouchMutableOrPartialState() throws Exception {
        UUID uuid = UUID.fromString("11111111-1111-1111-1111-111111111111");
        VoteSnapshots snapshots = new VoteSnapshots();
        snapshots.load(Map.of(uuid, stats(uuid, 1)), Map.of(uuid, false), 1, "2026-08-11");
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker;
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < 5_000; i++) {
                        if ((i + offset) % 5 == 0) {
                            snapshots.updatePlayer(stats(uuid, i), i % 2 == 0, "2026-08-11");
                            snapshots.setGlobal(i, "2026-08-11");
                        } else {
                            PlayerStats value = snapshots.stats(uuid, "Steve", "2026-08-11", "2026-08");
                            value.name().length();
                            snapshots.global("2026-08-11");
                            snapshots.muted(uuid);
                            snapshots.triple(uuid, "2026-08-11");
                        }
                    }
                }));
            }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            if (!pool.awaitTermination(20, TimeUnit.SECONDS)) pool.shutdownNow();
        }
        assertEquals(0, snapshots.global("2026-08-12"));
        assertEquals(0, snapshots.stats(uuid, "Steve", "2026-09-01", "2026-09").dailyVotes());
    }

    private PlayerStats stats(UUID uuid, double votes) {
        return new PlayerStats(uuid, "Steve", votes, votes, votes, 1,
                "2026-08-11", "2026-08", 1);
    }
}

package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.model.PlayerStats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class VoteLedgerTest {
    private static final ZoneId ZONE = ZoneId.of("America/Santiago");
    private static final UUID STEVE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir Path tempDir;

    @Test
    void exactDuplicateIsRejectedSameRunAfterRestartAndConcurrently() throws Exception {
        Path db = tempDir.resolve("votes.db");
        VoteEnvelope first = vote("site-a", "Steve", "100");
        VotePlan plan = VotePlan.simple(List.of("reward <player>"));

        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:00Z")) {
            assertEquals(VoteEventState.PLANNED, ledger.accept(first, new PlayerIdentity(STEVE, "Steve"), plan).state());
            assertEquals(VoteEventState.DUPLICATE, ledger.accept(first, new PlayerIdentity(STEVE, "Steve"), plan).state());
        }

        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:01Z")) {
            assertEquals(VoteEventState.DUPLICATE, ledger.accept(first, new PlayerIdentity(STEVE, "Steve"), plan).state());

            VoteEnvelope concurrent = vote("site-b", "Steve", "101");
            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> { start.await(); return ledger.accept(concurrent, new PlayerIdentity(STEVE, "Steve"), plan).state(); });
                var b = pool.submit(() -> { start.await(); return ledger.accept(concurrent, new PlayerIdentity(STEVE, "Steve"), plan).state(); });
                start.countDown();
                assertEquals(1, List.of(a.get(), b.get()).stream().filter(state -> state == VoteEventState.PLANNED).count());
                assertEquals(1, List.of(a.get(), b.get()).stream().filter(state -> state == VoteEventState.DUPLICATE).count());
            } finally {
                pool.shutdownNow();
            }

            assertEquals(2, ledger.readStats(STEVE, "Steve").totalVotes());
        }
    }

    @Test
    void missingTimestampIsQuarantinedWithoutStatsOrGrants() throws Exception {
        try (VoteLedger ledger = ledger(tempDir.resolve("quarantine.db"), "2026-08-11T12:00:00Z")) {
            VoteEnvelope invalid = VoteEnvelope.capture("site", "Steve", "address", "", "source");

            assertEquals(VoteEventState.QUARANTINED, ledger.accept(invalid, new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of("reward"))).state());
            assertEquals(0, ledger.readStats(STEVE, "Steve").totalVotes());
            assertEquals(0, ledger.countGrants());
        }
    }

    @Test
    void offlineDoubleJoinClaimsOnceAndKeepsOriginalCommandSnapshot() throws Exception {
        try (VoteLedger ledger = ledger(tempDir.resolve("offline.db"), "2026-08-11T12:00:00Z")) {
            VoteEnvelope event = vote("site", "Steve", "100");
            assertEquals(VoteEventState.UNRESOLVED, ledger.accept(event, null, VotePlan.simple(List.of("old <player>"))).state());
            assertEquals(VoteEventState.DUPLICATE, ledger.accept(event, null, VotePlan.simple(List.of("new <player>"))).state());

            var pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                var a = pool.submit(() -> { start.await(); return ledger.resolvePending(new PlayerIdentity(STEVE, "Steve")); });
                var b = pool.submit(() -> { start.await(); return ledger.resolvePending(new PlayerIdentity(STEVE, "Steve")); });
                start.countDown();
                assertEquals(1, a.get().size() + b.get().size());
            } finally {
                pool.shutdownNow();
            }

            assertEquals(1, ledger.readStats(STEVE, "Steve").totalVotes());
            GrantClaim claim = ledger.claimNextGrant(STEVE).orElseThrow();
            assertEquals("old Steve", claim.commandSnapshot());
        }
    }

    @Test
    void exactIdentityMismatchFailsClosedWithoutPartialNameResolution() throws Exception {
        try (VoteLedger ledger = ledger(tempDir.resolve("identity.db"), "2026-08-11T12:00:00Z")) {
            VoteEnvelope event = vote("site", "Steve", "100");

            assertEquals(VoteEventState.QUARANTINED,
                    ledger.accept(event, new PlayerIdentity(ALEX, "SteveSuffix"), VotePlan.simple(List.of("reward"))).state());
            assertEquals(0, ledger.readStats(ALEX, "SteveSuffix").totalVotes());
            assertEquals(0, ledger.countGrants());
        }
    }

    @Test
    void eventAndAllDerivedStateRollBackTogether() throws Exception {
        Path db = tempDir.resolve("rollback.db");
        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:00Z")) {
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER fail_grant BEFORE INSERT ON reward_grants BEGIN SELECT RAISE(ABORT, 'test rollback'); END;");
            }

            assertEquals(VoteEventState.ERROR, ledger.accept(vote("site", "Steve", "100"), new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of("reward"))).state());
            assertEquals(0, ledger.readStats(STEVE, "Steve").totalVotes());
            assertEquals(0, ledger.countEvents());
        }
    }

    @Test
    void grantTransitionsAreCrashSafeAndOrdered() throws Exception {
        Path db = tempDir.resolve("grants.db");
        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:00Z")) {
            ledger.accept(vote("site", "Steve", "100"), new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of("first", "second")));

            GrantClaim first = ledger.claimNextGrant(STEVE).orElseThrow();
            assertEquals("first", first.commandSnapshot());
            assertTrue(ledger.releaseBeforeDispatch(first.grantId(), first.claimToken(), "player offline"));
            first = ledger.claimNextGrant(STEVE).orElseThrow();
            assertTrue(ledger.markDoneAfterDispatch(first.grantId(), first.claimToken()));

            GrantClaim second = ledger.claimNextGrant(STEVE).orElseThrow();
            assertEquals("second", second.commandSnapshot());
            assertTrue(ledger.markAmbiguousAfterDispatch(second.grantId(), second.claimToken(), "process interrupted"));
            assertTrue(ledger.claimNextGrant(STEVE).isEmpty());
            assertEquals(1, ledger.listAmbiguous().size());
        }

        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:10Z")) {
            assertEquals(1, ledger.listAmbiguous().size());
        }
    }

    @Test
    void claimedAtRestartBecomesAmbiguousAndPendingBeforeClaimRemainsRetryable() throws Exception {
        Path db = tempDir.resolve("restart-grants.db");
        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:00Z")) {
            ledger.accept(vote("site", "Steve", "100"), new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of("first", "second")));
            GrantClaim first = ledger.claimNextGrant(STEVE).orElseThrow();
            assertEquals("first", first.commandSnapshot());
        }

        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:10Z")) {
            assertEquals(1, ledger.listAmbiguous().size());
            assertTrue(ledger.claimNextGrant(STEVE).isEmpty(), "later batch commands stop behind an ambiguous command");
        }
    }

    @Test
    void goalsAndTripleSiteAreClaimedOnceWithNormalizedServices() throws Exception {
        VotePlan plan = new VotePlan(
                List.of(),
                new TreeMap<>(Map.of(1, List.of("monthly"))),
                new TreeMap<>(Map.of(1, List.of("global"))),
                0, 0, List.of(),
                true, 3, List.of("triple"),
                List.of()
        );
        try (VoteLedger ledger = ledger(tempDir.resolve("goals.db"), "2026-08-11T12:00:00Z")) {
            ledger.accept(vote(" Site-A ", "Steve", "100"), new PlayerIdentity(STEVE, "Steve"), plan);
            ledger.accept(vote("site-a", "Steve", "101"), new PlayerIdentity(STEVE, "Steve"), plan);
            ledger.accept(vote("SITE-B", "Steve", "102"), new PlayerIdentity(STEVE, "Steve"), plan);
            ledger.accept(vote("site-c", "Steve", "103"), new PlayerIdentity(STEVE, "Steve"), plan);

            assertEquals(1, ledger.countGrantsByKind("MONTHLY_GOAL"));
            assertEquals(1, ledger.countGrantsByKind("GLOBAL_GOAL"));
            assertEquals(1, ledger.countGrantsByKind("TRIPLE_SITE"));
        }
    }

    @Test
    void consecutiveMonthStreakGrantIsJournaledOnce() throws Exception {
        Path db = tempDir.resolve("streak.db");
        VotePlan plan = new VotePlan(List.of(), new TreeMap<>(), new TreeMap<>(), 0, 0, List.of(),
                false, 3, List.of(), List.of(), new TreeMap<>(Map.of(2, List.of("streak <player>"))));
        try (VoteLedger august = ledger(db, "2026-08-11T12:00:00Z")) {
            august.accept(vote("site", "Steve", "august"), new PlayerIdentity(STEVE, "Steve"), plan);
        }
        try (VoteLedger september = ledger(db, "2026-09-11T12:00:00Z")) {
            september.accept(vote("site-a", "Steve", "september-a"), new PlayerIdentity(STEVE, "Steve"), plan);
            september.accept(vote("site-b", "Steve", "september-b"), new PlayerIdentity(STEVE, "Steve"), plan);
            assertEquals(2, september.readStats(STEVE, "Steve").streakMonthly());
            assertEquals(1, september.countGrantsByKind("MONTHLY_STREAK"));
        }
    }

    @Test
    void clockControlsDayAndMonthBoundariesIndependentlyOfOsTimezone() throws Exception {
        Path db = tempDir.resolve("clock.db");
        try (VoteLedger first = ledger(db, "2026-08-31T23:59:59-04:00")) {
            first.accept(vote("site-a", "Steve", "100"), new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of()));
            assertEquals(1, first.readStats(STEVE, "Steve").dailyVotes());
            assertEquals(1, first.readStats(STEVE, "Steve").monthlyVotes());
        }
        try (VoteLedger next = ledger(db, "2026-09-01T00:00:01-04:00")) {
            next.accept(vote("site-b", "Steve", "101"), new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of()));
            assertEquals(1, next.readStats(STEVE, "Steve").dailyVotes());
            assertEquals(1, next.readStats(STEVE, "Steve").monthlyVotes());
            assertEquals(2, next.readStats(STEVE, "Steve").totalVotes());
        }
    }

    @Test
    void lateOfflineResolutionUpdatesFrozenPeriodWithoutRewindingCurrentProjection() throws Exception {
        Path db = tempDir.resolve("late-offline.db");
        VoteEnvelope august = vote("site-a", "Steve", "august");
        try (VoteLedger ledger = ledger(db, "2026-08-31T20:00:00-04:00")) {
            assertEquals(VoteEventState.UNRESOLVED,
                    ledger.accept(august, null, VotePlan.simple(List.of())).state());
        }
        try (VoteLedger ledger = ledger(db, "2026-09-02T20:00:00-04:00")) {
            ledger.accept(vote("site-b", "Steve", "september"),
                    new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of()));
            assertEquals(1, ledger.resolvePending(new PlayerIdentity(STEVE, "Steve")).size());

            PlayerStats stats = ledger.readStats(STEVE, "Steve");
            assertEquals(2, stats.totalVotes());
            assertEquals(1, stats.dailyVotes());
            assertEquals(1, stats.monthlyVotes());
            assertEquals("2026-09", stats.lastMonthKey());
            assertEquals(1, ledger.readGlobalDaily());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 var statement = connection.prepareStatement(
                         "SELECT month_key,votes FROM monthly_snapshots WHERE uuid=? ORDER BY month_key")) {
                statement.setString(1, STEVE.toString());
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next()); assertEquals("2026-08", result.getString(1)); assertEquals(1, result.getDouble(2));
                    assertTrue(result.next()); assertEquals("2026-09", result.getString(1)); assertEquals(1, result.getDouble(2));
                }
            }
        }
    }

    @Test
    void monthlyDrawPersistsResultAndGrantOnceThenUsesAmbiguousRecovery() throws Exception {
        Path db = tempDir.resolve("draw.db");
        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:00Z")) {
            ledger.accept(vote("site", "Steve", "100"), new PlayerIdentity(STEVE, "Steve"), VotePlan.simple(List.of()));
            ledger.accept(vote("site", "Alex", "101"), new PlayerIdentity(ALEX, "Alex"), VotePlan.simple(List.of()));

            MonthlyDrawResult result = ledger.planMonthlyDraw("2026-08", "console", 1, "draw <player>", bound -> bound - 1);
            assertEquals(MonthlyDrawResult.Status.SUCCESS, result.status());
            assertEquals(MonthlyDrawResult.Status.ALREADY_DRAWN, ledger.planMonthlyDraw("2026-08", "console", 1, "changed", bound -> 0).status());

            GrantClaim claim = ledger.claimNextGlobalGrant().orElseThrow();
            assertTrue(ledger.markAmbiguousAfterDispatch(claim.grantId(), claim.claimToken(), "crash before DONE"));
            assertEquals(1, ledger.listAmbiguous().size());
        }
    }

    @Test
    void legacyPendingMigrationIsIdempotentAndKeepsSourceRow() throws Exception {
        Path db = tempDir.resolve("legacy.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pending_votes(id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, service_name TEXT NOT NULL, created_epoch INTEGER NOT NULL)");
            statement.execute("INSERT INTO pending_votes(player_name, service_name, created_epoch) VALUES ('Steve', 'site', 123)");
        }

        try (VoteLedger ledger = ledger(db, "2026-08-11T12:00:00Z")) {
            assertEquals(1, ledger.migrateLegacyPending(VotePlan.simple(List.of("legacy <player>"))));
            assertEquals(0, ledger.migrateLegacyPending(VotePlan.simple(List.of("changed"))));
            assertEquals(1, ledger.countEvents());
            assertEquals(1, ledger.countLegacyPendingRows());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
                 var statement = connection.prepareStatement("SELECT day_key, month_key FROM vote_events");
                 var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("1969-12-31", result.getString(1));
                assertEquals("1969-12", result.getString(2));
            }
        }
    }

    @Test
    void migrationCreatesIntegrityCheckedBackupAndRejectsVersionDisagreement() throws Exception {
        Path db = tempDir.resolve("migration.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pending_votes(id INTEGER PRIMARY KEY AUTOINCREMENT, player_name TEXT NOT NULL, service_name TEXT NOT NULL, created_epoch INTEGER NOT NULL)");
            statement.execute("INSERT INTO pending_votes(player_name, service_name, created_epoch) VALUES ('Steve', 'site', 123)");
        }
        try (VoteLedger ignored = ledger(db, "2026-08-11T12:00:00Z")) {
            assertTrue(Files.isRegularFile(tempDir.resolve("migration.db.backup-v0")));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version=1");
        }
        VoteLedger incompatible = new VoteLedger(db, 5_000,
                Clock.fixed(Instant.parse("2026-08-11T12:00:01Z"), ZONE), ZONE);
        assertThrows(IllegalStateException.class, incompatible::initialize);
    }

    @Test
    void planDocumentIsVersionedAndCommandsAreBounded() {
        VotePlan plan = VotePlan.simple(List.of("reward <player>"));

        assertTrue(plan.toJson().contains("\"schemaVersion\":2"));
        assertEquals(plan, VotePlan.fromJson(plan.toJson()));
        assertThrows(IllegalArgumentException.class,
                () -> VotePlan.simple(List.of("x".repeat(1_025))));
        assertThrows(IllegalArgumentException.class,
                () -> VotePlan.fromJson("{\"schemaVersion\":99,\"plan\":{}}"));
    }

    private VoteLedger ledger(Path db, String instant) {
        VoteLedger ledger = new VoteLedger(db, 5_000, Clock.fixed(Instant.parse(instant), ZONE), ZONE);
        ledger.initialize();
        return ledger;
    }

    private VoteEnvelope vote(String service, String player, String timestamp) {
        return VoteEnvelope.capture(service, player, "203.0.113.1", timestamp, "198.51.100.2");
    }
}

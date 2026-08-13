package com.valerinsmp.vvotes;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSafetyContractTest {
    private final Path project = Path.of(System.getProperty("user.dir"));

    @Test
    void grantDispatcherIsTheOnlyRuntimeCommandWriter() throws Exception {
        List<Path> writers = javaSourcesContaining("Bukkit.dispatchCommand(");

        assertEquals(List.of(project.resolve("src/main/java/com/valerinsmp/vvotes/reward/GrantDispatcher.java")), writers);
        String dispatcher = Files.readString(writers.getFirst());
        assertEquals(2, occurrences(dispatcher, "Bukkit.dispatchCommand("));
        assertTrue(javaSourcesContaining("runTaskAsynchronously").isEmpty());
    }

    @Test
    void legacyWritersStayDeletedAndPapiReadsSnapshotsOnly() throws Exception {
        for (String deleted : List.of("DatabaseManager.java", "VoteRepository.java", "MonthlyDrawService.java",
                "CommandRewardExecutor.java")) {
            assertTrue(Files.walk(project.resolve("src/main/java")).noneMatch(path -> path.getFileName().toString().equals(deleted)));
        }
        String expansion = Files.readString(project.resolve("src/main/java/com/valerinsmp/vvotes/papi/VVotesExpansion.java"));
        assertFalse(expansion.contains("DriverManager"));
        assertFalse(expansion.contains("VoteLedger"));
        assertFalse(expansion.contains("getConnection"));
    }

    @Test
    void providerListenerCapturesPrimitivesBeforeItsOnlyMainThreadHop() throws Exception {
        String listener = Files.readString(project.resolve("src/main/java/com/valerinsmp/vvotes/listener/VoteListener.java"));
        int capture = listener.indexOf("VoteEnvelope.capture(");
        int mainHop = listener.indexOf("Bukkit.getScheduler().runTask");
        assertTrue(capture >= 0 && mainHop > capture);
        assertEquals(1, occurrences(listener, "Bukkit.getScheduler().runTask"));
        assertFalse(listener.substring(0, mainHop).contains("getPlayer"));
        assertFalse(listener.contains("Bukkit.getPlayer("), "partial player lookup must never return");
    }

    @Test
    void startupAndReloadPreserveLifecycleOrdering() throws Exception {
        String plugin = Files.readString(project.resolve("src/main/java/com/valerinsmp/vvotes/VVotesPlugin.java"));
        assertTrue(plugin.indexOf("voteLedger.initialize()") < plugin.indexOf("registerListeners()"));
        assertTrue(plugin.indexOf("voteService.start()") < plugin.indexOf("registerListeners()"));
        int reloadStart = plugin.indexOf("public void reloadPlugin()");
        int reloadEnd = plugin.indexOf("public VoteService getVoteService()", reloadStart);
        String reload = plugin.substring(reloadStart, reloadEnd);
        int configCandidate = reload.indexOf("configCandidate =");
        int messagesCandidate = reload.indexOf("messagesCandidate =");
        int soundsCandidate = reload.indexOf("soundsCandidate =");
        int firstApply = reload.indexOf("configService.apply(");
        assertTrue(configCandidate >= 0 && messagesCandidate > configCandidate && soundsCandidate > messagesCandidate);
        assertTrue(firstApply > soundsCandidate);
        assertFalse(reload.contains("registerPlaceholderExpansion"));
        assertFalse(reload.contains("registerListeners"));
        assertFalse(reload.contains("voteLedger.initialize"));

        int disable = plugin.indexOf("public void onDisable()");
        assertTrue(plugin.indexOf("voteService.stopAccepting()", disable)
                < plugin.indexOf("stopMonthlyDrawTask()", disable));
    }

    private List<Path> javaSourcesContaining(String needle) throws Exception {
        List<Path> result = new ArrayList<>();
        try (var paths = Files.walk(project.resolve("src/main/java"))) {
            for (Path path : paths.filter(value -> value.toString().endsWith(".java")).sorted().toList()) {
                if (Files.readString(path).contains(needle)) result.add(path);
            }
        }
        return result;
    }

    private int occurrences(String value, String needle) {
        int count = 0;
        for (int at = 0; (at = value.indexOf(needle, at)) >= 0; at += needle.length()) count++;
        return count;
    }
}

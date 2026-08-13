package com.valerinsmp.vvotes.service;

import com.google.gson.Gson;
import com.valerinsmp.vvotes.config.PluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Frozen command/rule snapshot stored with an unresolved provider event. */
public record VotePlan(
        List<String> voteCommands,
        TreeMap<Integer, List<String>> monthlyGoals,
        TreeMap<Integer, List<String>> globalGoals,
        int recurringStart,
        int recurringEvery,
        List<String> recurringCommands,
        boolean tripleSiteEnabled,
        int tripleSiteRequired,
        List<String> tripleSiteCommands,
        List<String> servicePlayerCommands,
        TreeMap<Integer, List<String>> monthlyStreakGoals
) {
    private static final int DOCUMENT_SCHEMA_VERSION = 2;
    private static final int MAX_COMMANDS = 256;
    private static final int MAX_COMMAND_LENGTH = 1_024;
    private static final int MAX_JSON_LENGTH = 262_144;
    private static final Gson GSON = new Gson();

    public VotePlan {
        voteCommands = copy(voteCommands);
        monthlyGoals = copyMap(monthlyGoals);
        globalGoals = copyMap(globalGoals);
        recurringCommands = copy(recurringCommands);
        tripleSiteCommands = copy(tripleSiteCommands);
        servicePlayerCommands = copy(servicePlayerCommands);
        monthlyStreakGoals = copyMap(monthlyStreakGoals);
        if (recurringStart < 0 || recurringEvery < 0) {
            throw new IllegalArgumentException("Recurring goal values cannot be negative");
        }
        tripleSiteRequired = Math.max(1, tripleSiteRequired);
        validateThresholds(monthlyGoals);
        validateThresholds(globalGoals);
        validateThresholds(monthlyStreakGoals);
        int commandCount = voteCommands.size() + recurringCommands.size() + tripleSiteCommands.size()
                + servicePlayerCommands.size();
        commandCount += monthlyGoals.values().stream().mapToInt(List::size).sum();
        commandCount += globalGoals.values().stream().mapToInt(List::size).sum();
        commandCount += monthlyStreakGoals.values().stream().mapToInt(List::size).sum();
        if (commandCount > MAX_COMMANDS) {
            throw new IllegalArgumentException("Vote plan has too many commands");
        }
    }

    public VotePlan(List<String> voteCommands, TreeMap<Integer, List<String>> monthlyGoals,
                    TreeMap<Integer, List<String>> globalGoals, int recurringStart, int recurringEvery,
                    List<String> recurringCommands, boolean tripleSiteEnabled, int tripleSiteRequired,
                    List<String> tripleSiteCommands, List<String> servicePlayerCommands) {
        this(voteCommands, monthlyGoals, globalGoals, recurringStart, recurringEvery, recurringCommands,
                tripleSiteEnabled, tripleSiteRequired, tripleSiteCommands, servicePlayerCommands, new TreeMap<>());
    }

    public static VotePlan simple(List<String> commands) {
        return new VotePlan(commands, new TreeMap<>(), new TreeMap<>(), 0, 0,
                List.of(), false, 3, List.of(), List.of(), new TreeMap<>());
    }

    public static VotePlan from(PluginConfig config, String serviceName) {
        return new VotePlan(config.voteRewards(), new TreeMap<>(config.playerMonthlyGoals()),
                new TreeMap<>(config.globalDailyGoals()),
                config.globalRecurringStart(), config.globalRecurringEvery(), config.globalRecurringCommands(),
                config.doubleSiteBonusEnabled(), config.doubleSiteBonusRequiredSites(),
                config.doubleSiteBonusCommands(), config.forcedServiceCommands(serviceName),
                new TreeMap<>(config.monthlyStreakRewards()));
    }

    public VotePlan withoutVoteCommands() {
        return new VotePlan(List.of(), monthlyGoals, globalGoals, recurringStart, recurringEvery,
                recurringCommands, false, tripleSiteRequired, List.of(), List.of(), monthlyStreakGoals);
    }

    static VotePlan fromJson(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("Invalid vote plan document size");
        }
        PlanDocument document = GSON.fromJson(json, PlanDocument.class);
        if (document == null || document.schemaVersion != DOCUMENT_SCHEMA_VERSION || document.plan == null) {
            throw new IllegalArgumentException("Unsupported vote plan schema");
        }
        VotePlan plan = document.plan;
        return new VotePlan(plan.voteCommands, plan.monthlyGoals, plan.globalGoals,
                plan.recurringStart, plan.recurringEvery, plan.recurringCommands,
                plan.tripleSiteEnabled, plan.tripleSiteRequired,
                plan.tripleSiteCommands, plan.servicePlayerCommands, plan.monthlyStreakGoals);
    }

    String toJson() {
        String json = GSON.toJson(new PlanDocument(DOCUMENT_SCHEMA_VERSION, this));
        if (json.length() > MAX_JSON_LENGTH) {
            throw new IllegalArgumentException("Vote plan document is too large");
        }
        return json;
    }

    private static List<String> copy(List<String> input) {
        if (input == null) return List.of();
        return input.stream().filter(value -> value != null && !value.isBlank()).map(String::strip)
                .peek(command -> {
                    if (command.length() > MAX_COMMAND_LENGTH) {
                        throw new IllegalArgumentException("Reward command is too long");
                    }
                }).toList();
    }

    private static TreeMap<Integer, List<String>> copyMap(Map<Integer, List<String>> input) {
        TreeMap<Integer, List<String>> result = new TreeMap<>();
        if (input != null) input.forEach((key, value) -> result.put(key, new ArrayList<>(copy(value))));
        return result;
    }

    private static void validateThresholds(Map<Integer, List<String>> goals) {
        if (goals.keySet().stream().anyMatch(key -> key == null || key <= 0)) {
            throw new IllegalArgumentException("Goal thresholds must be positive");
        }
    }

    private record PlanDocument(int schemaVersion, VotePlan plan) { }
}

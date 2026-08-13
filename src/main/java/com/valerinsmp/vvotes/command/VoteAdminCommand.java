package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class VoteAdminCommand implements CommandExecutor, TabCompleter {
    private final VVotesPlugin plugin;

    public VoteAdminCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Bukkit.isPrimaryThread()) {
            String[] snapshot = args.clone();
            String commandName = command.getName();
            String labelSnapshot = label;
            replyTo(sender).sync(resolved -> {
                Command resolvedCommand = plugin.getCommand(commandName);
                if (resolvedCommand != null) onCommand(resolved, resolvedCommand, labelSnapshot, snapshot);
            });
            return true;
        }
        if (!sender.hasPermission("vvotes.admin")) {
            plugin.getMessageService().send(sender, "no-permission");
            return true;
        }
        Reply reply = replyTo(sender);

        if (args.length == 0) {
            plugin.getMessageService().sendAdminHelp(sender, 1);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help", "ayuda" -> plugin.getMessageService().sendAdminHelp(sender,
                    args.length >= 2 ? positiveInt(args[1], 1) : 1);
            case "about", "info" -> plugin.getMessageService().sendAbout(sender);
            case "reload" -> {
                try {
                    plugin.reloadPlugin();
                    plugin.getMessageService().send(sender, "reload-ok");
                } catch (RuntimeException failure) {
                    plugin.getMessageService().send(sender, "reload-failed", Map.of("error", safeError(failure)));
                }
            }
            case "add" -> {
                if (args.length < 3) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-add");
                    return true;
                }
                OfflinePlayer target = resolveKnownPlayer(args[1]);
                if (target == null) { plugin.getMessageService().send(sender, "player-not-found", Map.of("player", args[1])); return true; }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException exception) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-add");
                    return true;
                }
                if (amount <= 0) { plugin.getMessageService().send(sender, "usage-voteadmin-add"); return true; }

                String targetName = target.getName() == null ? args[1] : target.getName();
                plugin.getVoteService().addManualVotes(target, amount).whenComplete((planned, failure) -> reply.sync(resolved -> {
                    if (failure != null) { sendOperationError(resolved, failure); return; }
                    plugin.getMessageService().send(resolved, "admin-add-ok", Map.of(
                            "player", targetName,
                            "amount", Integer.toString(planned == null ? 0 : planned)));
                }));
            }
            case "resetdaily" -> {
                plugin.getVoteService().forceResetGlobalDailyAsync().whenComplete((unused, failure) -> reply.sync(resolved -> {
                    if (failure != null) { sendOperationError(resolved, failure); return; }
                    plugin.getMessageService().send(resolved, "admin-reset-daily-ok");
                }));
            }
            case "resetmonthly" -> {
                if (args.length < 2) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-reset-monthly");
                    return true;
                }
                OfflinePlayer target = resolveKnownPlayer(args[1]);
                if (target == null) { plugin.getMessageService().send(sender, "player-not-found", Map.of("player", args[1])); return true; }
                String targetName = target.getName() == null ? args[1] : target.getName();
                plugin.getVoteService().forceResetPlayerMonthlyAsync(target).whenComplete((unused, failure) -> reply.sync(resolved -> {
                    if (failure != null) { sendOperationError(resolved, failure); return; }
                    plugin.getMessageService().send(resolved, "admin-reset-monthly-ok", Map.of("player", targetName));
                }));
            }
            case "adddaily" -> {
                if (args.length < 3) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-adddaily");
                    return true;
                }
                OfflinePlayer target = resolveKnownPlayer(args[1]);
                if (target == null) { plugin.getMessageService().send(sender, "player-not-found", Map.of("player", args[1])); return true; }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException exception) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-adddaily");
                    return true;
                }
                if (amount <= 0) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-adddaily");
                    return true;
                }
                String targetName = target.getName() == null ? args[1] : target.getName();
                plugin.getVoteService().adjustPlayerDailyVotesAsync(target, amount).whenComplete((updated, failure) -> reply.sync(resolved -> {
                    if (failure != null || updated == null || updated < 0) { sendOperationError(resolved, failure == null ? new IllegalStateException("DB update failed") : failure); return; }
                    plugin.getMessageService().send(resolved, "admin-adddaily-ok", Map.of(
                                "player", targetName,
                                "amount", Integer.toString(amount),
                                "daily", plugin.getVoteService().formatDouble(updated)));
                }));
            }
            case "removedaily" -> {
                if (args.length < 3) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-removedaily");
                    return true;
                }
                OfflinePlayer target = resolveKnownPlayer(args[1]);
                if (target == null) { plugin.getMessageService().send(sender, "player-not-found", Map.of("player", args[1])); return true; }
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException exception) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-removedaily");
                    return true;
                }
                if (amount <= 0) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-removedaily");
                    return true;
                }
                String targetName = target.getName() == null ? args[1] : target.getName();
                plugin.getVoteService().adjustPlayerDailyVotesAsync(target, -amount).whenComplete((updated, failure) -> reply.sync(resolved -> {
                    if (failure != null || updated == null || updated < 0) { sendOperationError(resolved, failure == null ? new IllegalStateException("DB update failed") : failure); return; }
                    plugin.getMessageService().send(resolved, "admin-removedaily-ok", Map.of(
                                "player", targetName,
                                "amount", Integer.toString(amount),
                                "daily", plugin.getVoteService().formatDouble(updated)));
                }));
            }
            case "addglobaldaily" -> {
                if (args.length < 2) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-addglobaldaily");
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException exception) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-addglobaldaily");
                    return true;
                }
                if (amount <= 0) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-addglobaldaily");
                    return true;
                }
                plugin.getVoteService().adjustGlobalDailyVotesAsync(amount).whenComplete((updated, failure) -> reply.sync(resolved -> {
                    if (failure != null || updated == null || updated < 0) { sendOperationError(resolved, failure == null ? new IllegalStateException("DB update failed") : failure); return; }
                    plugin.getMessageService().send(resolved, "admin-addglobaldaily-ok", Map.of(
                                "amount", Integer.toString(amount),
                                "daily_global", plugin.getVoteService().formatDouble(updated)));
                }));
            }
            case "removeglobaldaily" -> {
                if (args.length < 2) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-removeglobaldaily");
                    return true;
                }
                int amount;
                try {
                    amount = Integer.parseInt(args[1]);
                } catch (NumberFormatException exception) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-removeglobaldaily");
                    return true;
                }
                if (amount <= 0) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-removeglobaldaily");
                    return true;
                }
                plugin.getVoteService().adjustGlobalDailyVotesAsync(-amount).whenComplete((updated, failure) -> reply.sync(resolved -> {
                    if (failure != null || updated == null || updated < 0) { sendOperationError(resolved, failure == null ? new IllegalStateException("DB update failed") : failure); return; }
                    plugin.getMessageService().send(resolved, "admin-removeglobaldaily-ok", Map.of(
                                "amount", Integer.toString(amount),
                                "daily_global", plugin.getVoteService().formatDouble(updated)));
                }));
            }
            case "drawmonthly" -> {
                String monthKey = args.length >= 2 ? args[1] : null;
                String executor = sender.getName() == null || sender.getName().isBlank() ? "console" : sender.getName();
                plugin.getVoteService().drawMonthlyAsync(monthKey, executor).whenComplete((result, throwable) -> reply.sync(resolved -> {
                    if (result == null) {
                        plugin.getMessageService().send(resolved, "admin-drawmonthly-error", Map.of("error", "unknown"));
                        return;
                    }
                    switch (result.status()) {
                        case SUCCESS -> plugin.getMessageService().send(resolved, "admin-drawmonthly-success", Map.of(
                                "month", result.monthKey(),
                                "winner", result.winnerName(),
                                "votes", plugin.getVoteService().formatDouble(result.topVotes()),
                                "candidates", Integer.toString(result.candidatesCount())
                        ));
                        case NO_PARTICIPANTS -> plugin.getMessageService().send(resolved, "admin-drawmonthly-no-participants", Map.of(
                                "month", result.monthKey(),
                                "votes", plugin.getVoteService().formatDouble(result.topVotes())
                        ));
                        case ALREADY_DRAWN -> plugin.getMessageService().send(resolved, "admin-drawmonthly-already-drawn", Map.of(
                                "month", result.monthKey()
                        ));
                        case DISABLED -> plugin.getMessageService().send(resolved, "admin-drawmonthly-disabled");
                        case INVALID_MONTH -> plugin.getMessageService().send(resolved, "admin-drawmonthly-invalid-month", Map.of(
                                "month", result.monthKey()
                        ));
                        case ERROR -> plugin.getMessageService().send(resolved, "admin-drawmonthly-error", Map.of(
                                "error", result.error()
                        ));
                    }
                }));
            }
            case "topmonth" -> {
                java.time.ZoneId zone = java.time.ZoneId.of(plugin.getVoteService().getTimezoneId());
                String monthKey = args.length >= 2 ? args[1] : java.time.YearMonth.now(zone).toString();
                plugin.getVoteService().getTopMonthAsync(monthKey, 10).whenComplete((top, throwable) -> reply.sync(resolved -> {
                    if (top == null || top.isEmpty()) {
                        plugin.getMessageService().send(resolved, "admin-topmonth-empty", Map.of("month", monthKey));
                    } else {
                        plugin.getMessageService().send(resolved, "admin-topmonth-header", Map.of("month", monthKey));
                        for (var entry : top) {
                            plugin.getMessageService().send(resolved, "admin-topmonth-entry", Map.of(
                                    "pos", Integer.toString(entry.position()),
                                    "player", entry.playerName(),
                                    "votes", plugin.getVoteService().formatDouble(entry.votes())
                            ));
                        }
                    }
                }));
            }
            case "drawhistory" -> {
                String monthKey = args.length >= 2 ? args[1] : null;
                plugin.getVoteService().getDrawHistoryAsync(monthKey).whenComplete((result, throwable) -> reply.sync(resolved -> {
                    if (result == null) {
                        plugin.getMessageService().send(resolved, "admin-drawmonthly-error", Map.of("error", "unknown"));
                        return;
                    }
                    switch (result.status()) {
                        case FOUND -> plugin.getMessageService().send(resolved, "admin-drawhistory-found", Map.of(
                                "month", result.monthKey(),
                                "winner", result.winnerName(),
                                "uuid", result.winnerUuid(),
                                "votes", plugin.getVoteService().formatDouble(result.topVotes()),
                                "candidates", Integer.toString(result.candidatesCount()),
                                "executed_by", result.executedBy(),
                                "date", java.time.Instant.ofEpochSecond(result.executedEpoch())
                                        .atZone(java.time.ZoneId.of(plugin.getVoteService().getTimezoneId()))
                                        .toLocalDate().toString()
                        ));
                        case NOT_FOUND -> plugin.getMessageService().send(resolved, "admin-drawhistory-not-found", Map.of(
                                "month", result.monthKey()
                        ));
                        case INVALID_MONTH -> plugin.getMessageService().send(resolved, "admin-drawmonthly-invalid-month", Map.of(
                                "month", result.monthKey()
                        ));
                        case ERROR -> plugin.getMessageService().send(resolved, "admin-drawmonthly-error", Map.of(
                                "error", result.error()
                        ));
                    }
                }));
            }
            case "ambiguous" -> plugin.getVoteService().getAmbiguousGrantsAsync().whenComplete((grants, failure) -> reply.sync(resolved -> {
                if (failure != null) { sendOperationError(resolved, failure); return; }
                if (grants == null || grants.isEmpty()) {
                    plugin.getMessageService().send(resolved, "admin-ambiguous-empty");
                    return;
                }
                plugin.getMessageService().send(resolved, "admin-ambiguous-header", Map.of("amount", Integer.toString(grants.size())));
                grants.stream().limit(50).forEach(grant -> plugin.getMessageService().send(resolved,
                        "admin-ambiguous-entry", Map.of(
                                "grant", grant.grantId(), "kind", grant.kind(), "batch", grant.batchKey(),
                                "sequence", Integer.toString(grant.sequence()))));
            }));
            default -> plugin.getMessageService().sendAdminHelp(sender, 1);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!Bukkit.isPrimaryThread()) return Collections.emptyList();
        if (!sender.hasPermission("vvotes.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(List.of("help", "about", "reload", "add", "resetdaily", "resetmonthly", "adddaily", "removedaily", "addglobaldaily", "removeglobaldaily", "drawmonthly", "drawhistory", "topmonth", "ambiguous"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("resetmonthly")
                || args[0].equalsIgnoreCase("adddaily") || args[0].equalsIgnoreCase("removedaily"))) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filter(names, args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("drawmonthly") || args[0].equalsIgnoreCase("drawhistory") || args[0].equalsIgnoreCase("topmonth"))) {
            java.time.ZoneId zone = java.time.ZoneId.of(plugin.getVoteService().getTimezoneId());
            java.time.YearMonth now = java.time.YearMonth.now(zone);
            return filter(List.of(now.minusMonths(1).toString(), now.toString()), args[1]);
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("add")
                || args[0].equalsIgnoreCase("adddaily")
                || args[0].equalsIgnoreCase("removedaily"))) {
            return filter(List.of("1", "5", "10", "25", "50"), args[2]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("addglobaldaily")
                || args[0].equalsIgnoreCase("removeglobaldaily"))) {
            return filter(List.of("1", "5", "10", "25", "50"), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> source, String input) {
        String lower = input.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String entry : source) {
            if (entry.toLowerCase().startsWith(lower)) {
                result.add(entry);
            }
        }
        return result;
    }

    private Reply replyTo(CommandSender sender) {
        if (sender instanceof Player player) {
            return new Reply(player.getUniqueId(), player.getName(), false);
        }
        return new Reply(null, "", true);
    }

    private final class Reply {
        private final UUID playerId;
        private final String exactName;
        private final boolean console;

        private Reply(UUID playerId, String exactName, boolean console) {
            this.playerId = playerId;
            this.exactName = exactName;
            this.console = console;
        }

        private void sync(Consumer<CommandSender> action) {
            if (!plugin.isEnabled()) return;
            Runnable resolvedAction = () -> {
                if (!plugin.isEnabled()) return;
                if (console) {
                    action.accept(Bukkit.getConsoleSender());
                    return;
                }
                Player player = Bukkit.getPlayerExact(exactName);
                if (player != null && player.getUniqueId().equals(playerId)) action.accept(player);
            };
            if (Bukkit.isPrimaryThread()) resolvedAction.run();
            else Bukkit.getScheduler().runTask(plugin, resolvedAction);
        }
    }

    private OfflinePlayer resolveKnownPlayer(String exactName) {
        Player online = Bukkit.getPlayerExact(exactName);
        if (online != null) return online;
        OfflinePlayer match = null;
        for (OfflinePlayer candidate : Bukkit.getOfflinePlayers()) {
            if (!exactName.equals(candidate.getName())) continue;
            if (match != null && !match.getUniqueId().equals(candidate.getUniqueId())) return null;
            match = candidate;
        }
        return match;
    }

    private int positiveInt(String value, int fallback) {
        try { return Math.max(1, Integer.parseInt(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private String safeError(Throwable failure) {
        Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        String message = cause.getMessage();
        if (message == null || message.isBlank()) return cause.getClass().getSimpleName();
        String oneLine = message.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160);
    }

    private void sendOperationError(CommandSender sender, Throwable failure) {
        plugin.getMessageService().send(sender, "admin-operation-error", Map.of("error", safeError(failure)));
    }
}

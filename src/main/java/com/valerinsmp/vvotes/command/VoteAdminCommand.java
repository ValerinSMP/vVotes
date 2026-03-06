package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class VoteAdminCommand implements CommandExecutor, TabCompleter {
    private final VVotesPlugin plugin;

    public VoteAdminCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vvotes.admin")) {
            plugin.getMessageService().send(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            plugin.getMessageService().send(sender, "usage-voteadmin");
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload" -> {
                plugin.reloadPlugin();
                plugin.getMessageService().send(sender, "reload-ok");
            }
            case "add" -> {
                if (args.length < 3) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-add");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                int amount;
                try {
                    amount = Integer.parseInt(args[2]);
                } catch (NumberFormatException exception) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-add");
                    return true;
                }

                plugin.getVoteService().addManualVotes(target, amount);
                plugin.getMessageService().send(sender, "admin-add-ok", Map.of(
                        "player", target.getName() == null ? args[1] : target.getName(),
                        "amount", Integer.toString(amount)
                ));
            }
            case "resetdaily" -> {
                plugin.getVoteService().forceResetGlobalDaily();
                plugin.getMessageService().send(sender, "admin-reset-daily-ok");
            }
            case "resetmonthly" -> {
                if (args.length < 2) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-reset-monthly");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
                plugin.getVoteService().forceResetPlayerMonthly(target);
                plugin.getMessageService().send(sender, "admin-reset-monthly-ok", Map.of(
                        "player", target.getName() == null ? args[1] : target.getName()
                ));
            }
            case "adddaily" -> {
                if (args.length < 3) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-adddaily");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
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
                double updated = plugin.getVoteService().adjustPlayerDailyVotes(target, amount);
                plugin.getMessageService().send(sender, "admin-adddaily-ok", Map.of(
                        "player", target.getName() == null ? args[1] : target.getName(),
                        "amount", Integer.toString(amount),
                        "daily", plugin.getVoteService().formatDouble(Math.max(0, updated))
                ));
            }
            case "removedaily" -> {
                if (args.length < 3) {
                    plugin.getMessageService().send(sender, "usage-voteadmin-removedaily");
                    return true;
                }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
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
                double updated = plugin.getVoteService().adjustPlayerDailyVotes(target, -amount);
                plugin.getMessageService().send(sender, "admin-removedaily-ok", Map.of(
                        "player", target.getName() == null ? args[1] : target.getName(),
                        "amount", Integer.toString(amount),
                        "daily", plugin.getVoteService().formatDouble(Math.max(0, updated))
                ));
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
                double updated = plugin.getVoteService().adjustGlobalDailyVotes(amount);
                plugin.getMessageService().send(sender, "admin-addglobaldaily-ok", Map.of(
                        "amount", Integer.toString(amount),
                        "daily_global", plugin.getVoteService().formatDouble(Math.max(0, updated))
                ));
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
                double updated = plugin.getVoteService().adjustGlobalDailyVotes(-amount);
                plugin.getMessageService().send(sender, "admin-removeglobaldaily-ok", Map.of(
                        "amount", Integer.toString(amount),
                        "daily_global", plugin.getVoteService().formatDouble(Math.max(0, updated))
                ));
            }
            case "drawmonthly" -> {
                String monthKey = args.length >= 2 ? args[1] : null;
                String executor = sender.getName() == null || sender.getName().isBlank() ? "console" : sender.getName();
                var result = plugin.getVoteService().drawMonthly(monthKey, executor);
                switch (result.status()) {
                    case SUCCESS -> plugin.getMessageService().send(sender, "admin-drawmonthly-success", Map.of(
                            "month", result.monthKey(),
                            "winner", result.winnerName(),
                            "votes", plugin.getVoteService().formatDouble(result.topVotes()),
                            "candidates", Integer.toString(result.candidatesCount())
                    ));
                    case NO_PARTICIPANTS -> plugin.getMessageService().send(sender, "admin-drawmonthly-no-participants", Map.of(
                            "month", result.monthKey(),
                            "votes", plugin.getVoteService().formatDouble(result.topVotes())
                    ));
                    case ALREADY_DRAWN -> plugin.getMessageService().send(sender, "admin-drawmonthly-already-drawn", Map.of(
                            "month", result.monthKey()
                    ));
                    case DISABLED -> plugin.getMessageService().send(sender, "admin-drawmonthly-disabled");
                    case INVALID_MONTH -> plugin.getMessageService().send(sender, "admin-drawmonthly-invalid-month", Map.of(
                            "month", result.monthKey()
                    ));
                    case ERROR -> plugin.getMessageService().send(sender, "admin-drawmonthly-error", Map.of(
                            "error", result.error()
                    ));
                }
            }
            case "topmonth" -> {
                java.time.ZoneId zone = java.time.ZoneId.of(plugin.getVoteService().getTimezoneId());
                String monthKey = args.length >= 2 ? args[1] : java.time.YearMonth.now(zone).toString();
                var top = plugin.getVoteService().getTopMonth(monthKey, 10);
                if (top.isEmpty()) {
                    plugin.getMessageService().send(sender, "admin-topmonth-empty", Map.of("month", monthKey));
                } else {
                    plugin.getMessageService().send(sender, "admin-topmonth-header", Map.of("month", monthKey));
                    for (var entry : top) {
                        plugin.getMessageService().send(sender, "admin-topmonth-entry", Map.of(
                                "pos", Integer.toString(entry.position()),
                                "player", entry.playerName(),
                                "votes", plugin.getVoteService().formatDouble(entry.votes())
                        ));
                    }
                }
            }
            case "drawhistory" -> {
                String monthKey = args.length >= 2 ? args[1] : null;
                var result = plugin.getVoteService().getDrawHistory(monthKey);
                switch (result.status()) {
                    case FOUND -> plugin.getMessageService().send(sender, "admin-drawhistory-found", Map.of(
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
                    case NOT_FOUND -> plugin.getMessageService().send(sender, "admin-drawhistory-not-found", Map.of(
                            "month", result.monthKey()
                    ));
                    case INVALID_MONTH -> plugin.getMessageService().send(sender, "admin-drawmonthly-invalid-month", Map.of(
                            "month", result.monthKey()
                    ));
                    case ERROR -> plugin.getMessageService().send(sender, "admin-drawmonthly-error", Map.of(
                            "error", result.error()
                    ));
                }
            }
            default -> plugin.getMessageService().send(sender, "usage-voteadmin");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vvotes.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(List.of("reload", "add", "resetdaily", "resetmonthly", "adddaily", "removedaily", "addglobaldaily", "removeglobaldaily", "drawmonthly", "drawhistory", "topmonth"), args[0]);
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
}

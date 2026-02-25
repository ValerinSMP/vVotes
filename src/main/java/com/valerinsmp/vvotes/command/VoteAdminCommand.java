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
            return filter(List.of("reload", "add", "resetdaily", "resetmonthly", "drawmonthly"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("resetmonthly"))) {
            List<String> names = new ArrayList<>();
            Bukkit.getOnlinePlayers().forEach(player -> names.add(player.getName()));
            return filter(names, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("drawmonthly")) {
            java.time.ZoneId zone = java.time.ZoneId.of(plugin.getVoteService().getTimezoneId());
            java.time.YearMonth now = java.time.YearMonth.now(zone);
            return filter(List.of(now.minusMonths(1).toString(), now.toString()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            return filter(List.of("1", "5", "10"), args[2]);
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

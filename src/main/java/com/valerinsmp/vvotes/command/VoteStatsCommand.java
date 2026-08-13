package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.model.PlayerStats;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

import java.util.Map;

public final class VoteStatsCommand implements CommandExecutor {
    private final VVotesPlugin plugin;

    public VoteStatsCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Bukkit.isPrimaryThread()) {
            String[] snapshot = args.clone();
            scheduleOnMain(sender, command.getName(), label, snapshot);
            return true;
        }
        show(plugin, sender);
        return true;
    }

    static void show(VVotesPlugin plugin, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageService().send(sender, "player-only");
            return;
        }

        PlayerStats stats = plugin.getVoteService().getStats(player.getUniqueId(), player.getName());
        double globalDaily = plugin.getVoteService().getGlobalDailyVotes();
        Map<String, String> placeholders = Map.of(
                "player", player.getName(),
                "total", plugin.getVoteService().formatDouble(stats.totalVotes()),
                "daily", plugin.getVoteService().formatDouble(stats.dailyVotes()),
                "monthly", plugin.getVoteService().formatDouble(stats.monthlyVotes()),
                "streak_monthly", Integer.toString(stats.streakMonthly()),
                "global_daily", plugin.getVoteService().formatDouble(globalDaily),
                "next_global_goal", Integer.toString(plugin.getVoteService().nextGlobalGoal(globalDaily)),
                "next_monthly_goal", Integer.toString(plugin.getVoteService().nextMonthlyGoal(stats.monthlyVotes()))
        );

        plugin.getMessageService().send(player, "vote-status", placeholders);
    }

    private void scheduleOnMain(CommandSender sender, String commandName, String label, String[] args) {
        java.util.UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        String exactName = sender instanceof Player player ? player.getName() : "";
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) return;
            Command resolvedCommand = plugin.getCommand(commandName);
            if (resolvedCommand == null) return;
            if (playerId == null) {
                onCommand(Bukkit.getConsoleSender(), resolvedCommand, label, args);
                return;
            }
            Player player = Bukkit.getPlayerExact(exactName);
            if (player != null && player.getUniqueId().equals(playerId)) onCommand(player, resolvedCommand, label, args);
        });
    }
}

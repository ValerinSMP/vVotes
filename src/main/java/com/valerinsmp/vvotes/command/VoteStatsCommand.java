package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.model.PlayerStats;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public final class VoteStatsCommand implements CommandExecutor {
    private final VVotesPlugin plugin;

    public VoteStatsCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageService().send(sender, "player-only");
            return true;
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
        return true;
    }
}

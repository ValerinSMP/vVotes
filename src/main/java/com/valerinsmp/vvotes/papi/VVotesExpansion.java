package com.valerinsmp.vvotes.papi;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.model.PlayerStats;
import com.valerinsmp.vvotes.service.VoteService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class VVotesExpansion extends PlaceholderExpansion {
    private final VVotesPlugin plugin;
    private final VoteService voteService;

    public VVotesExpansion(VVotesPlugin plugin, VoteService voteService) {
        this.plugin = plugin;
        this.voteService = voteService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "vvotes";
    }

    @Override
    public @NotNull String getAuthor() {
        return "ValerinSMP";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null || player.getUniqueId() == null || player.getName() == null) {
            return "0";
        }

        PlayerStats stats = voteService.getStats(player.getUniqueId(), player.getName());
        return switch (params.toLowerCase()) {
            case "total", "votes_total" -> voteService.formatDouble(stats.totalVotes());
            case "daily", "votes_daily" -> voteService.formatDouble(stats.dailyVotes());
            case "monthly", "votes_monthly" -> voteService.formatDouble(stats.monthlyVotes());
            case "streak_monthly" -> Integer.toString(stats.streakMonthly());
            case "streak_daily", "streak_weekly" -> "0";
            case "global_daily" -> voteService.formatDouble(voteService.getGlobalDailyVotes());
            case "next_global_goal" -> Integer.toString(voteService.nextGlobalGoal(voteService.getGlobalDailyVotes()));
            case "next_monthly_goal" -> Integer.toString(voteService.nextMonthlyGoal(stats.monthlyVotes()));
            default -> null;
        };
    }
}

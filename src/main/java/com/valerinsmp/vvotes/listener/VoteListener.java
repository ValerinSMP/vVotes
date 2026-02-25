package com.valerinsmp.vvotes.listener;

import com.valerinsmp.vvotes.service.VoteService;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public final class VoteListener implements Listener {
    private final VoteService voteService;

    public VoteListener(VoteService voteService) {
        this.voteService = voteService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVote(VotifierEvent event) {
        Vote vote = event.getVote();
        Player player = Bukkit.getPlayer(vote.getUsername());
        if (player == null || !player.isOnline()) {
            voteService.handleOfflineVoteSkip(vote.getUsername());
            return;
        }
        voteService.handleVote(player, vote.getServiceName());
    }
}

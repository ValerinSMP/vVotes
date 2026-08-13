package com.valerinsmp.vvotes.listener;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.service.VoteService;
import com.valerinsmp.vvotes.service.VoteEnvelope;
import com.valerinsmp.vvotes.service.PlayerIdentity;
import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class VoteListener implements Listener {
    private final VVotesPlugin plugin;
    private final VoteService voteService;

    public VoteListener(VVotesPlugin plugin, VoteService voteService) {
        this.plugin = plugin;
        this.voteService = voteService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVote(VotifierEvent event) {
        Vote vote = event.getVote();
        VoteEnvelope envelope = VoteEnvelope.capture(vote.getServiceName(), vote.getUsername(),
                vote.getAddress(), vote.getTimeStamp(), vote.getSourceAddress());
        if (!voteService.isAccepting()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                var player = Bukkit.getPlayerExact(envelope.displayName());
                PlayerIdentity identity = player != null && player.isOnline()
                        ? new PlayerIdentity(player.getUniqueId(), player.getName()) : null;
                voteService.ingestProviderEvent(envelope, identity);
            });
        } catch (RuntimeException disabling) {
            // Shutdown raced the provider callback; no event was accepted by the ledger.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        voteService.resolvePending(new PlayerIdentity(player.getUniqueId(), player.getName()));
    }
}

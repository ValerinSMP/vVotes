package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

public final class VVotesCommand implements CommandExecutor {
    private final VVotesPlugin plugin;

    public VVotesCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageService().send(sender, "player-only");
            return true;
        }

        if (args.length == 0) {
            plugin.getMessageService().send(player, "usage-vvotes");
            return true;
        }

        if (!args[0].equalsIgnoreCase("toggle")) {
            plugin.getMessageService().send(player, "usage-vvotes");
            return true;
        }

        boolean muted = plugin.getVoteService().toggleVoteAnnouncements(player.getUniqueId(), player.getName());
        plugin.getMessageService().send(player,
                muted ? "vvotes-toggle-off" : "vvotes-toggle-on",
                Map.of("state", muted ? "off" : "on"));
        return true;
    }
}

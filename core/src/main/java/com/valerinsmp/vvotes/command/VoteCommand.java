package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class VoteCommand implements CommandExecutor {
    private final VVotesPlugin plugin;

    public VoteCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageService().send(sender, "player-only");
            return true;
        }
        plugin.getMessageService().send(player, "vote-help");
        return true;
    }
}

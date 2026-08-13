package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class VoteCommand implements CommandExecutor {
    private final VVotesPlugin plugin;

    public VoteCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Bukkit.isPrimaryThread()) {
            String[] snapshot = args.clone();
            scheduleOnMain(sender, command.getName(), label, snapshot);
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("about") || args[0].equalsIgnoreCase("info"))) {
            plugin.getMessageService().sendAbout(sender);
        } else if (args.length > 0 && args[0].equalsIgnoreCase("stats")) {
            VoteStatsCommand.show(plugin, sender);
        } else {
            plugin.getMessageService().sendPublicHelp(sender);
        }
        return true;
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

package com.valerinsmp.vvotes.command;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.List;
import java.util.Collections;
import org.bukkit.Bukkit;

public final class VVotesCommand implements CommandExecutor, TabCompleter {
    private final VVotesPlugin plugin;

    public VVotesCommand(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!Bukkit.isPrimaryThread()) {
            String[] snapshot = args.clone();
            scheduleOnMain(sender, command.getName(), label, snapshot);
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("ayuda")) {
            plugin.getMessageService().sendPublicHelp(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("about") || args[0].equalsIgnoreCase("info")) {
            plugin.getMessageService().sendAbout(sender);
            return true;
        }
        if (args[0].equalsIgnoreCase("stats")) {
            VoteStatsCommand.show(plugin, sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            plugin.getMessageService().send(sender, "player-only");
            return true;
        }

        if (!args[0].equalsIgnoreCase("toggle")) {
            plugin.getMessageService().send(player, "usage-vvotes");
            return true;
        }

        java.util.UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        plugin.getVoteService().toggleVoteAnnouncementsAsync(playerId).thenAccept(muted ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Player exact = Bukkit.getPlayerExact(playerName);
                    if (exact == null || !exact.getUniqueId().equals(playerId)) return;
                    plugin.getMessageService().send(exact,
                            muted ? "vvotes-toggle-off" : "vvotes-toggle-on",
                            Map.of("state", muted ? "off" : "on"));
                }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!Bukkit.isPrimaryThread() || args.length != 1) return Collections.emptyList();
        String input = args[0].toLowerCase(java.util.Locale.ROOT);
        return List.of("help", "about", "stats", "toggle").stream()
                .filter(value -> value.startsWith(input)).toList();
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

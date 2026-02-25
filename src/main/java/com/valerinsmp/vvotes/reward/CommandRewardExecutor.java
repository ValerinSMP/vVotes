package com.valerinsmp.vvotes.reward;

import com.valerinsmp.vvotes.VVotesPlugin;
import org.bukkit.Bukkit;

import java.util.List;
import java.util.Map;

public final class CommandRewardExecutor {
    private final VVotesPlugin plugin;

    public CommandRewardExecutor(VVotesPlugin plugin) {
        this.plugin = plugin;
    }

    public void execute(List<String> commands, Map<String, String> placeholders) {
        for (String command : commands) {
            String parsed = command;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                parsed = parsed.replace("<" + entry.getKey() + ">", entry.getValue());
            }
            if (parsed.isBlank()) {
                continue;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
        }
    }
}

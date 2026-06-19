package com.valerinsmp.vvotes.platform;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public interface PlatformAdapter {
    void send(CommandSender sender, String text);
    void broadcast(String text);
    void showTitle(Player player, String titleText, String subtitleText);
}

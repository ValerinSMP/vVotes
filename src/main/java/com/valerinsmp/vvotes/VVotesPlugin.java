package com.valerinsmp.vvotes;

import com.valerinsmp.vvotes.command.VoteAdminCommand;
import com.valerinsmp.vvotes.command.VoteCommand;
import com.valerinsmp.vvotes.command.VoteStatsCommand;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.db.DatabaseManager;
import com.valerinsmp.vvotes.listener.VoteListener;
import com.valerinsmp.vvotes.papi.VVotesExpansion;
import com.valerinsmp.vvotes.reward.CommandRewardExecutor;
import com.valerinsmp.vvotes.service.MessageService;
import com.valerinsmp.vvotes.service.SoundService;
import com.valerinsmp.vvotes.service.VoteService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class VVotesPlugin extends JavaPlugin {

    private ConfigService configService;
    private MessageService messageService;
    private SoundService soundService;
    private DatabaseManager databaseManager;
    private VoteService voteService;
    private VVotesExpansion placeholderExpansion;
    private BukkitTask monthlyDrawTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("sound.yml");

        this.configService = new ConfigService(this);
        this.messageService = new MessageService(this, configService);
        this.soundService = new SoundService(this);
        this.databaseManager = new DatabaseManager(this, configService);
        this.databaseManager.initialize();

        CommandRewardExecutor rewardExecutor = new CommandRewardExecutor(this);
        this.voteService = new VoteService(this, configService, messageService, soundService, databaseManager, rewardExecutor);

        registerCommands();
        registerListeners();
        registerPlaceholderExpansion();
        startMonthlyDrawTask();
    }

    @Override
    public void onDisable() {
        unregisterPlaceholderExpansion();
        stopMonthlyDrawTask();
        HandlerList.unregisterAll(this);
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    public void reloadPlugin() {
        try {
            stopMonthlyDrawTask();
            if (databaseManager != null) {
                databaseManager.close();
            }

            reloadConfig();
            configService.reload();
            messageService.reload();
            soundService.reload();
            databaseManager.initialize();
            registerPlaceholderExpansion();
            startMonthlyDrawTask();
        } catch (Exception exception) {
            getLogger().severe("Error recargando plugin: " + exception.getMessage());
            throw exception;
        }
    }

    public VoteService getVoteService() {
        return voteService;
    }

    public MessageService getMessageService() {
        return messageService;
    }

    private void saveResourceIfMissing(String name) {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("No se pudo crear la carpeta del plugin");
        }
        java.io.File file = new java.io.File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
    }

    private void registerCommands() {
        PluginCommand vote = getCommand("vote");
        if (vote != null) {
            vote.setExecutor(new VoteCommand(this));
        }
        PluginCommand voteStats = getCommand("votestats");
        if (voteStats != null) {
            voteStats.setExecutor(new VoteStatsCommand(this));
        }

        PluginCommand voteAdmin = getCommand("voteadmin");
        if (voteAdmin != null) {
            VoteAdminCommand command = new VoteAdminCommand(this);
            voteAdmin.setExecutor(command);
            voteAdmin.setTabCompleter(command);
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new VoteListener(voteService), this);
    }

    private void registerPlaceholderExpansion() {
        unregisterPlaceholderExpansion();
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        placeholderExpansion = new VVotesExpansion(this, voteService);
        placeholderExpansion.register();
        getLogger().info("PlaceholderAPI detectado, placeholders registrados.");
    }

    private void unregisterPlaceholderExpansion() {
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }
    }

    private void startMonthlyDrawTask() {
        stopMonthlyDrawTask();
        if (!configService.get().monthlyDrawEnabled()) {
            return;
        }
        int everyMinutes = Math.max(1, configService.get().monthlyDrawAutoCheckMinutes());
        long periodTicks = everyMinutes * 60L * 20L;
        monthlyDrawTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            VoteService.MonthlyDrawResult result = voteService.runAutoMonthlyDrawIfNeeded();
            if (result.status() == VoteService.MonthlyDrawResult.Status.SUCCESS) {
                getLogger().info("Sorteo mensual automatico ejecutado para " + result.monthKey() + ", ganador: " + result.winnerName());
            }
        }, 20L, periodTicks);
    }

    private void stopMonthlyDrawTask() {
        if (monthlyDrawTask != null) {
            monthlyDrawTask.cancel();
            monthlyDrawTask = null;
        }
    }
}

package com.valerinsmp.vvotes;

import com.valerinsmp.vvotes.command.VoteAdminCommand;
import com.valerinsmp.vvotes.command.VoteCommand;
import com.valerinsmp.vvotes.command.VoteStatsCommand;
import com.valerinsmp.vvotes.command.VVotesCommand;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.db.DatabaseManager;
import com.valerinsmp.vvotes.listener.VoteListener;
import com.valerinsmp.vvotes.papi.VVotesExpansion;
import com.valerinsmp.vvotes.reward.CommandRewardExecutor;
import com.valerinsmp.vvotes.service.MessageService;
import com.valerinsmp.vvotes.service.SoundService;
import com.valerinsmp.vvotes.service.MonthlyDrawResult;
import com.valerinsmp.vvotes.service.VoteService;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        ensureYamlDefaults();

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
            ensureYamlDefaults();
            configService.reload();
            messageService.reload();
            soundService.reload();
            databaseManager.initialize();
            voteService.invalidateStatsCache();
            voteService.sealGoalsForCurrentDay();
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

        PluginCommand vvotes = getCommand("vvotes");
        if (vvotes != null) {
            vvotes.setExecutor(new VVotesCommand(this));
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new VoteListener(this, voteService), this);
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
        monthlyDrawTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            MonthlyDrawResult result = voteService.runAutoMonthlyDrawIfNeeded();
            if (result.status() == MonthlyDrawResult.Status.SUCCESS) {
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

    private void ensureYamlDefaults() {
        mergeYamlDefaults("config.yml");
        reloadConfig();
        mergeYamlDefaults("messages.yml");
        mergeYamlDefaults("sound.yml");
    }

    private void mergeYamlDefaults(String resourceName) {
        File file = new File(getDataFolder(), resourceName);
        if (!file.exists()) {
            saveResource(resourceName, false);
            return;
        }

        try (InputStream input = getResource(resourceName)) {
            if (input == null) {
                return;
            }

            YamlConfiguration current = loadYamlSafely(file);
            YamlConfiguration defaults = loadYamlSafely(input);

            boolean changed = false;
            for (String path : defaults.getKeys(true)) {
                if (!current.contains(path)) {
                    current.set(path, defaults.get(path));
                    changed = true;
                }
            }

            if (changed) {
                current.save(file);
                getLogger().info("Se agregaron nuevas claves por defecto en " + resourceName);
            }
        } catch (Exception exception) {
            getLogger().warning("No se pudieron fusionar defaults de " + resourceName + ": " + exception.getMessage());
        }
    }

    private YamlConfiguration loadYamlSafely(File file) throws IOException {
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        return loadYamlFromText(text);
    }

    private YamlConfiguration loadYamlSafely(InputStream input) throws IOException {
        String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        return loadYamlFromText(text);
    }

    private YamlConfiguration loadYamlFromText(String rawText) {
        String text = sanitizeYamlText(rawText);
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (Exception exception) {
            throw new IllegalStateException("YAML invalido tras saneamiento: " + exception.getMessage(), exception);
        }
        return yaml;
    }

    private String sanitizeYamlText(String input) {
        String text = input.startsWith("\uFEFF") ? input.substring(1) : input;
        StringBuilder sanitized = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                sanitized.append(c);
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            sanitized.append(c);
        }
        return sanitized.toString();
    }
}

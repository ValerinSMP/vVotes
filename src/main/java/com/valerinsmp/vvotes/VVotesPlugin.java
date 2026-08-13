package com.valerinsmp.vvotes;

import com.valerinsmp.vvotes.command.VoteAdminCommand;
import com.valerinsmp.vvotes.command.VoteCommand;
import com.valerinsmp.vvotes.command.VoteStatsCommand;
import com.valerinsmp.vvotes.command.VVotesCommand;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.config.PluginConfig;
import com.valerinsmp.vvotes.listener.VoteListener;
import com.valerinsmp.vvotes.papi.VVotesExpansion;
import com.valerinsmp.vvotes.reward.GrantDispatcher;
import com.valerinsmp.vvotes.service.MessageService;
import com.valerinsmp.vvotes.service.SoundService;
import com.valerinsmp.vvotes.service.MonthlyDrawResult;
import com.valerinsmp.vvotes.service.VoteService;
import com.valerinsmp.vvotes.service.VoteLedger;
import com.valerinsmp.vvotes.service.VotePlan;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.ZoneId;

public final class VVotesPlugin extends JavaPlugin {

    private ConfigService configService;
    private MessageService messageService;
    private SoundService soundService;
    private VoteService voteService;
    private VVotesExpansion placeholderExpansion;
    private BukkitTask monthlyDrawTask;
    private VoteLedger voteLedger;

    @Override
    public void onEnable() {
        long startedAt = System.nanoTime();
        getLogger().info("Starting vVotes v" + getDescription().getVersion() + "...");
        getLogger().info("Platform: Paper 1.21.11+ | Java 21 bytecode");
        saveDefaultConfig();
        saveResourceIfMissing("messages.yml");
        saveResourceIfMissing("sound.yml");
        migrateExactLegacyDefault("config.yml", "2FE58537EC25FB5C7190C6B35DDA313D77BF5E7DDF8C6C8166A1AF00CF7E9D2D");
        migrateExactLegacyDefault("messages.yml", "C38BF335EEF48C7E1573F19DE7485D956EE102B0D7AFBD58A133D3B765F0D5CC");

        this.configService = new ConfigService(this);
        this.messageService = new MessageService(this);
        this.soundService = new SoundService(this);
        VotePlan.from(configService.get(), "startup-validation");
        this.voteLedger = new VoteLedger(resolveDatabasePath(configService.get()), configService.get().busyTimeoutMs(),
                Clock.systemUTC(), ZoneId.of(configService.get().timezone()));
        this.voteLedger.initialize();
        this.voteService = new VoteService(this, configService, messageService, soundService,
                voteLedger, new GrantDispatcher());
        this.voteService.start();

        registerCommands();
        registerListeners();
        registerPlaceholderExpansion();
        startMonthlyDrawTask();
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        getLogger().info("Enabled successfully in " + elapsedMs + " ms.");
    }

    @Override
    public void onDisable() {
        long startedAt = System.nanoTime();
        getLogger().info("Stopping vVotes...");
        if (voteService != null) voteService.stopAccepting();
        stopMonthlyDrawTask();
        unregisterPlaceholderExpansion();
        HandlerList.unregisterAll(this);
        if (voteService != null) {
            voteService.close();
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
        getLogger().info("Disabled successfully in " + elapsedMs + " ms.");
    }

    public void reloadPlugin() {
        try {
            PluginConfig configCandidate = configService.loadCandidate();
            configService.requireRuntimeCompatible(configCandidate);
            VotePlan.from(configCandidate, "reload-validation");
            MessageService.MessageCatalog messagesCandidate = messageService.loadCandidate();
            SoundService.SoundCatalog soundsCandidate = soundService.loadCandidate();
            stopMonthlyDrawTask();
            configService.apply(configCandidate);
            messageService.apply(messagesCandidate);
            soundService.apply(soundsCandidate);
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

        PluginCommand voteAdmin = getCommand("vvotesadmin");
        if (voteAdmin != null) {
            VoteAdminCommand command = new VoteAdminCommand(this);
            voteAdmin.setExecutor(command);
            voteAdmin.setTabCompleter(command);
        }

        PluginCommand vvotes = getCommand("vvotes");
        if (vvotes != null) {
            VVotesCommand command = new VVotesCommand(this);
            vvotes.setExecutor(command);
            vvotes.setTabCompleter(command);
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new VoteListener(this, voteService), this);
        getLogger().info("Integration enabled: VotifierPlus");
    }

    private Path resolveDatabasePath(PluginConfig config) {
        Path data = getDataFolder().toPath().toAbsolutePath().normalize();
        Path path = data.resolve(config.sqliteFile()).normalize();
        if (!path.startsWith(data)) throw new IllegalArgumentException("SQLite path escapes plugin data folder");
        return path;
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
            voteService.drawMonthlyAsync(null, "auto").thenAccept(result -> {
                if (result.status() == MonthlyDrawResult.Status.SUCCESS) {
                    getLogger().info("Sorteo mensual automatico ejecutado para " + result.monthKey());
                }
            });
        }, 20L, periodTicks);
    }

    private void stopMonthlyDrawTask() {
        if (monthlyDrawTask != null) {
            monthlyDrawTask.cancel();
            monthlyDrawTask = null;
        }
    }

    private void migrateExactLegacyDefault(String name, String expectedHash) {
        Path path = getDataFolder().toPath().resolve(name);
        try (var input = getResource(name)) {
            if (input == null || !Files.isRegularFile(path)) return;
            String actual = java.util.HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            if (!actual.equals(expectedHash)) return;
            Files.write(path, input.readAllBytes());
            getLogger().info("Migrated unchanged legacy default: " + name);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to migrate legacy default " + name, exception);
        }
    }
}

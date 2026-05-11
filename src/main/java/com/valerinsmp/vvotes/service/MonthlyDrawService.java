package com.valerinsmp.vvotes.service;

import com.valerinsmp.vvotes.VVotesPlugin;
import com.valerinsmp.vvotes.config.ConfigService;
import com.valerinsmp.vvotes.reward.CommandRewardExecutor;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public final class MonthlyDrawService {

    private final VVotesPlugin plugin;
    private final ConfigService configService;
    private final MessageService messageService;
    private final SoundService soundService;
    private final VoteRepository repo;
    private final CommandRewardExecutor rewardExecutor;

    MonthlyDrawService(VVotesPlugin plugin, ConfigService configService,
                       MessageService messageService, SoundService soundService,
                       VoteRepository repo, CommandRewardExecutor rewardExecutor) {
        this.plugin = plugin;
        this.configService = configService;
        this.messageService = messageService;
        this.soundService = soundService;
        this.repo = repo;
        this.rewardExecutor = rewardExecutor;
    }

    public MonthlyDrawResult runAutoIfNeeded() {
        if (!configService.get().monthlyDrawEnabled()) return MonthlyDrawResult.disabled();
        YearMonth month = YearMonth.now(ZoneId.of(configService.get().timezone())).minusMonths(1);
        return draw(month.toString(), "auto");
    }

    public MonthlyDrawResult draw(String monthKey, String executedBy) {
        if (!configService.get().monthlyDrawEnabled()) return MonthlyDrawResult.disabled();
        final String key = (monthKey == null || monthKey.isBlank())
                ? YearMonth.now(ZoneId.of(configService.get().timezone())).minusMonths(1).toString()
                : monthKey;
        try {
            YearMonth.parse(key);
        } catch (Exception ignored) {
            return MonthlyDrawResult.invalidMonth(key);
        }

        try (Connection connection = repo.connection()) {
            connection.setAutoCommit(false);

            if (repo.isMonthAlreadyDrawn(connection, key)) {
                connection.rollback();
                connection.setAutoCommit(true);
                return MonthlyDrawResult.alreadyDrawn(key);
            }

            double maxVotes = repo.fetchMaxVotesForMonth(connection, key);
            if (maxVotes < configService.get().monthlyDrawMinVotes()) {
                connection.rollback();
                connection.setAutoCommit(true);
                return MonthlyDrawResult.noParticipants(key, maxVotes);
            }

            List<DrawCandidate> candidates = repo.fetchCandidatesForTopVotes(connection, key, maxVotes);
            if (candidates.isEmpty()) {
                connection.rollback();
                connection.setAutoCommit(true);
                return MonthlyDrawResult.noParticipants(key, maxVotes);
            }

            DrawCandidate winner = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            String rewardCommand = configService.get().monthlyDrawRewardCommand();
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("player", winner.name());
            placeholders.put("uuid", winner.uuid().toString());
            placeholders.put("month", key);
            placeholders.put("votes", VoteService.formatDoubleStatic(maxVotes));
            placeholders.put("candidates", Integer.toString(candidates.size()));

            repo.insertMonthlyDrawHistory(connection, key, winner, maxVotes, candidates.size(), executedBy, rewardCommand);
            connection.commit();
            connection.setAutoCommit(true);

            Bukkit.getScheduler().runTask(plugin, () -> {
                rewardExecutor.execute(List.of(rewardCommand), placeholders);
                for (var line : messageService.messages("draw-monthly-winner-broadcast", placeholders)) {
                    Bukkit.broadcast(line);
                }
                soundService.playToAll("goal.completed");
            });

            return MonthlyDrawResult.success(key, winner.name(), maxVotes, candidates.size());
        } catch (SQLException exception) {
            plugin.getLogger().severe("Error en sorteo mensual: " + exception.getMessage());
            return MonthlyDrawResult.error(key, exception.getMessage());
        }
    }

    public DrawHistoryResult getHistory(String monthKey) {
        final String key = (monthKey == null || monthKey.isBlank())
                ? YearMonth.now(ZoneId.of(configService.get().timezone())).minusMonths(1).toString()
                : monthKey;
        try {
            YearMonth.parse(key);
        } catch (Exception ignored) {
            return DrawHistoryResult.invalidMonth(key);
        }
        try (Connection connection = repo.connection()) {
            return repo.fetchDrawHistory(connection, key);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Error consultando historial sorteo: " + exception.getMessage());
            return DrawHistoryResult.error(key, exception.getMessage());
        }
    }

    public List<TopMonthEntry> getTopMonth(String monthKey, int limit) {
        final String key = (monthKey == null || monthKey.isBlank())
                ? YearMonth.now(ZoneId.of(configService.get().timezone())).toString()
                : monthKey;
        try (Connection connection = repo.connection()) {
            return repo.fetchTopMonth(connection, key, limit);
        } catch (SQLException exception) {
            plugin.getLogger().warning("Error consultando top mensual: " + exception.getMessage());
            return List.of();
        }
    }
}

package com.valerinsmp.vvotes.reward;

import com.valerinsmp.vvotes.service.GrantClaim;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** The only runtime boundary allowed to invoke external reward commands. */
public final class GrantDispatcher {
    private final CommandRuntime runtime;

    public GrantDispatcher() {
        this(new BukkitCommandRuntime());
    }

    GrantDispatcher(CommandRuntime runtime) {
        this.runtime = runtime;
    }

    public DispatchResult dispatch(GrantClaim claim) {
        if (!runtime.isPrimaryThread()) {
            throw new IllegalStateException("Reward dispatch must run on the main thread");
        }
        String command = claim.commandSnapshot().strip();
        if (command.isBlank()) return DispatchResult.NOT_DISPATCHED;
        if (command.startsWith("/")) command = command.substring(1);

        try {
            boolean accepted;
            if ("PLAYER".equals(claim.executorMode())) {
                if (!runtime.isExactPlayerOnline(claim)) {
                    return DispatchResult.NOT_DISPATCHED;
                }
                accepted = runtime.dispatchPlayer(claim, command);
            } else {
                accepted = runtime.dispatchConsole(command);
            }
            return accepted ? DispatchResult.DONE : DispatchResult.AMBIGUOUS;
        } catch (RuntimeException exception) {
            return DispatchResult.AMBIGUOUS;
        }
    }

    public enum DispatchResult { DONE, NOT_DISPATCHED, AMBIGUOUS }

    interface CommandRuntime {
        boolean isPrimaryThread();
        boolean isExactPlayerOnline(GrantClaim claim);
        boolean dispatchPlayer(GrantClaim claim, String command);
        boolean dispatchConsole(String command);
    }

    private static final class BukkitCommandRuntime implements CommandRuntime {
        @Override public boolean isPrimaryThread() { return Bukkit.isPrimaryThread(); }

        @Override
        public boolean isExactPlayerOnline(GrantClaim claim) {
            Player player = Bukkit.getPlayerExact(claim.targetName());
            return player != null && player.isOnline() && player.getUniqueId().equals(claim.targetUuid());
        }

        @Override
        public boolean dispatchPlayer(GrantClaim claim, String command) {
            return Bukkit.dispatchCommand(Bukkit.getPlayerExact(claim.targetName()), command);
        }

        @Override
        public boolean dispatchConsole(String command) {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
    }
}

package com.valerinsmp.vvotes.reward;

import com.valerinsmp.vvotes.service.GrantClaim;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrantDispatcherTest {
    private static final GrantClaim CONSOLE = claim("CONSOLE");
    private static final GrantClaim PLAYER = claim("PLAYER");

    @Test
    void falseAndThrowAfterDispatchAreTerminalAmbiguous() {
        FakeRuntime rejected = new FakeRuntime();
        rejected.accepted = false;
        assertEquals(GrantDispatcher.DispatchResult.AMBIGUOUS, new GrantDispatcher(rejected).dispatch(CONSOLE));
        assertEquals(1, rejected.dispatchCalls);

        FakeRuntime throwing = new FakeRuntime();
        throwing.failure = new IllegalStateException("provider failed");
        assertEquals(GrantDispatcher.DispatchResult.AMBIGUOUS, new GrantDispatcher(throwing).dispatch(CONSOLE));
        assertEquals(1, throwing.dispatchCalls);
    }

    @Test
    void provenUnavailablePlayerDoesNotDispatchAndCanBeReleased() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.playerOnline = false;

        assertEquals(GrantDispatcher.DispatchResult.NOT_DISPATCHED, new GrantDispatcher(runtime).dispatch(PLAYER));
        assertEquals(0, runtime.dispatchCalls);
    }

    @Test
    void mainThreadBoundaryIsEnforcedBeforeAnySideEffect() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.primary = false;

        assertThrows(IllegalStateException.class, () -> new GrantDispatcher(runtime).dispatch(CONSOLE));
        assertEquals(0, runtime.dispatchCalls);
    }

    private static GrantClaim claim(String mode) {
        return new GrantClaim("grant", "batch", 0, "VOTE", "/reward Steve", mode,
                UUID.fromString("11111111-1111-1111-1111-111111111111"), "Steve", "token", "CLAIMED", "");
    }

    private static final class FakeRuntime implements GrantDispatcher.CommandRuntime {
        private boolean primary = true;
        private boolean playerOnline = true;
        private boolean accepted = true;
        private RuntimeException failure;
        private int dispatchCalls;

        @Override public boolean isPrimaryThread() { return primary; }
        @Override public boolean isExactPlayerOnline(GrantClaim claim) { return playerOnline; }
        @Override public boolean dispatchPlayer(GrantClaim claim, String command) { return dispatch(); }
        @Override public boolean dispatchConsole(String command) { return dispatch(); }

        private boolean dispatch() {
            dispatchCalls++;
            if (failure != null) throw failure;
            return accepted;
        }
    }
}

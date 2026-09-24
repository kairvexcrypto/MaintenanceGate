package ru.warndev.maintenancegate;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GateStateTest {
    private final UUID actor = new UUID(1, 2);
    private final Instant start = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void windowUsesInclusiveStartExclusiveEnd() {
        GateWindow window = new GateWindow(start, start.plusSeconds(60), "Update");
        assertEquals(GateWindow.Phase.SCHEDULED, window.phase(start.minusNanos(1)));
        assertEquals(GateWindow.Phase.ACTIVE, window.phase(start));
        assertEquals(GateWindow.Phase.ACTIVE, window.phase(start.plusSeconds(59)));
        assertEquals(GateWindow.Phase.EXPIRED, window.phase(start.plusSeconds(60)));
    }

    @Test
    void indefiniteWindowDoesNotExpire() {
        GateWindow window = new GateWindow(start, null, "Update");
        assertEquals(GateWindow.Phase.ACTIVE, window.phase(start.plusSeconds(10000000)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "reason\nline", "reason\u202E", "§aColored"})
    void rejectsUnsafeReasons(String value) {
        assertThrows(IllegalArgumentException.class, () -> new GateWindow(start, null, value));
    }

    @Test
    void rejectsEmptyOrReversedWindow() {
        assertThrows(IllegalArgumentException.class, () -> new GateWindow(start, start, "test"));
        assertThrows(IllegalArgumentException.class, () -> new GateWindow(start, start.minusSeconds(1), "test"));
    }

    @Test
    void explicitOperatorPolicyAndBypass() {
        var state = GateState.empty().changeWindow(new GateWindow(start, null, "test"), actor, start, AuditEntry.Action.CLOSE);
        UUID player = new UUID(3, 4);
        assertFalse(state.admits(player, false, false, true, start));
        assertFalse(state.admits(player, false, true, false, start));
        assertTrue(state.admits(player, false, true, true, start));
        assertTrue(state.admits(player, true, false, false, start));
        assertTrue(state.allow(player, actor, start).admits(player, false, false, false, start));
        assertTrue(state.admits(player, false, false, false, start.minusSeconds(1)));
    }

    @Test
    void immutableAllowlistAndAudit() {
        UUID player = new UUID(3, 4);
        var initial = GateState.empty();
        var allowed = initial.allow(player, actor, start);
        assertTrue(initial.allowed().isEmpty());
        assertEquals(1, allowed.revision());
        assertEquals(AuditEntry.Action.ALLOW, allowed.audit().getFirst().action());
        assertThrows(UnsupportedOperationException.class, () -> allowed.allowed().clear());
        assertThrows(UnsupportedOperationException.class, () -> allowed.audit().clear());
        assertThrows(IllegalArgumentException.class, () -> allowed.allow(player, actor, start));
        assertTrue(allowed.deny(player, actor, start).allowed().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> initial.deny(player, actor, start));
    }

    @Test
    void auditRetainsOnlyLastHundred() {
        GateState state = GateState.empty();
        for (int i = 0; i < 150; i++) {
            state = state.allow(new UUID(0, i), actor, start);
        }
        assertEquals(150, state.revision());
        assertEquals(100, state.audit().size());
        assertEquals(51, state.audit().getFirst().revision());
        assertEquals(150, state.audit().getLast().revision());
    }

    @Test
    void allowlistIsBoundedAndClearIsAudited() {
        GateState state = GateState.empty();
        for (int i = 0; i < 512; i++) {
            state = state.allow(new UUID(0, i), actor, start);
        }
        GateState full = state;
        assertThrows(IllegalArgumentException.class, () -> full.allow(new UUID(0, 513), actor, start));
        GateState cleared = full.clear(actor, start);
        assertTrue(cleared.allowed().isEmpty());
        assertEquals(AuditEntry.Action.CLEAR, cleared.audit().getLast().action());
    }

    @Test
    void transitionNotifiesExactlyOncePerPhaseChange() {
        var state = GateState.empty().changeWindow(new GateWindow(start, start.plusSeconds(60), "test"), actor,
                start.minusSeconds(10), AuditEntry.Action.SCHEDULE);
        var tracker = new GateTransitions(state, start.minusSeconds(10));
        assertEquals(GateTransitions.Change.NONE, tracker.observe(state, start.minusSeconds(1)));
        assertEquals(GateTransitions.Change.CLOSED, tracker.observe(state, start));
        assertEquals(GateTransitions.Change.NONE, tracker.observe(state, start.plusSeconds(1)));
        assertEquals(GateTransitions.Change.OPENED, tracker.observe(state, start.plusSeconds(60)));
        assertEquals(GateTransitions.Change.NONE, tracker.observe(state, start.plusSeconds(61)));
    }
}

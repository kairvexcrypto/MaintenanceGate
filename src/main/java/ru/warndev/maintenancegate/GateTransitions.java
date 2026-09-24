package ru.warndev.maintenancegate;

import java.time.Instant;

public final class GateTransitions {
    private boolean active;

    public GateTransitions(GateState initial, Instant now) {
        active = initial.phase(now) == GateWindow.Phase.ACTIVE;
    }

    public Change observe(GateState state, Instant now) {
        boolean current = state.phase(now) == GateWindow.Phase.ACTIVE;
        if (current == active) {
            return Change.NONE;
        }
        active = current;
        return current ? Change.CLOSED : Change.OPENED;
    }

    public enum Change {
        NONE,
        CLOSED,
        OPENED
    }
}

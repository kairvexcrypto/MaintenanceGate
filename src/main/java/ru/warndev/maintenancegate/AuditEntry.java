package ru.warndev.maintenancegate;

import java.time.Instant;
import java.util.UUID;

public record AuditEntry(long revision, Instant time, UUID actor, Action action, UUID subject) {
    public AuditEntry {
        if (revision < 1 || time == null || actor == null || action == null) {
            throw new IllegalArgumentException("Неверная запись аудита");
        }
        if ((action == Action.ALLOW || action == Action.DENY) != (subject != null)) {
            throw new IllegalArgumentException("Неверная цель действия");
        }
    }

    public enum Action {
        CLOSE,
        OPEN,
        SCHEDULE,
        ALLOW,
        DENY,
        CLEAR
    }
}

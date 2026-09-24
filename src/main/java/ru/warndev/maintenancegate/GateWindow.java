package ru.warndev.maintenancegate;

import java.time.Instant;
import java.util.Objects;

public record GateWindow(Instant start, Instant end, String reason) {
    public GateWindow {
        Objects.requireNonNull(start);
        Objects.requireNonNull(reason);
        if (end != null && !end.isAfter(start)) {
            throw new IllegalArgumentException("Окончание должно быть позже начала");
        }
        if (reason.isBlank() || reason.length() > 160 || reason.codePoints().anyMatch(GateWindow::unsafe)) {
            throw new IllegalArgumentException("Причина: от 1 до 160 символов без управляющих знаков");
        }
    }

    public Phase phase(Instant now) {
        if (now.isBefore(start)) {
            return Phase.SCHEDULED;
        }
        if (end != null && !now.isBefore(end)) {
            return Phase.EXPIRED;
        }
        return Phase.ACTIVE;
    }

    private static boolean unsafe(int code) {
        return Character.isISOControl(code) || Character.getType(code) == Character.FORMAT || code == 167;
    }

    public enum Phase {
        OPEN,
        SCHEDULED,
        ACTIVE,
        EXPIRED
    }
}

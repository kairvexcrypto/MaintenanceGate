package ru.warndev.maintenancegate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record GateState(long revision, GateWindow window, Set<UUID> allowed, List<AuditEntry> audit) {
    public GateState {
        if (revision < 0 || allowed.size() > 512 || audit.size() > 100) {
            throw new IllegalArgumentException("Неверный размер состояния");
        }
        if (allowed.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Пустой UUID");
        }
        long previous = 0;
        for (AuditEntry entry : audit) {
            if (entry.revision() <= previous || entry.revision() > revision) {
                throw new IllegalArgumentException("Неверная последовательность аудита");
            }
            previous = entry.revision();
        }
        allowed = Collections.unmodifiableSet(new LinkedHashSet<>(allowed));
        audit = List.copyOf(audit);
    }

    public static GateState empty() {
        return new GateState(0, null, new LinkedHashSet<>(), List.of());
    }

    public GateWindow.Phase phase(Instant now) {
        return window == null ? GateWindow.Phase.OPEN : window.phase(now);
    }

    public boolean admits(UUID player, boolean permissionBypass, boolean operator, boolean operatorBypass, Instant now) {
        return phase(now) != GateWindow.Phase.ACTIVE || permissionBypass || allowed.contains(player)
                || operator && operatorBypass;
    }

    public GateState changeWindow(GateWindow replacement, UUID actor, Instant now, AuditEntry.Action action) {
        if (action != AuditEntry.Action.OPEN && action != AuditEntry.Action.CLOSE && action != AuditEntry.Action.SCHEDULE) {
            throw new IllegalArgumentException("Неверное действие окна");
        }
        if ((action == AuditEntry.Action.OPEN) != (replacement == null)) {
            throw new IllegalArgumentException("Неверное окно");
        }
        return next(replacement, allowed, actor, now, action, null);
    }

    public GateState allow(UUID target, UUID actor, Instant now) {
        if (allowed.contains(target)) {
            throw new IllegalArgumentException("UUID уже допущен");
        }
        if (allowed.size() >= 512) {
            throw new IllegalArgumentException("Список допуска заполнен: 512 UUID");
        }
        Set<UUID> changed = new LinkedHashSet<>(allowed);
        changed.add(target);
        return next(window, changed, actor, now, AuditEntry.Action.ALLOW, target);
    }

    public GateState deny(UUID target, UUID actor, Instant now) {
        if (!allowed.contains(target)) {
            throw new IllegalArgumentException("UUID отсутствует в списке допуска");
        }
        Set<UUID> changed = new LinkedHashSet<>(allowed);
        changed.remove(target);
        return next(window, changed, actor, now, AuditEntry.Action.DENY, target);
    }

    public GateState clear(UUID actor, Instant now) {
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("Список допуска уже пуст");
        }
        return next(window, Set.of(), actor, now, AuditEntry.Action.CLEAR, null);
    }

    private GateState next(GateWindow replacement, Set<UUID> allowlist, UUID actor, Instant now,
                           AuditEntry.Action action, UUID target) {
        long nextRevision = Math.addExact(revision, 1);
        List<AuditEntry> entries = new ArrayList<>(audit);
        if (entries.size() == 100) {
            entries.removeFirst();
        }
        entries.add(new AuditEntry(nextRevision, now, actor, action, target));
        return new GateState(nextRevision, replacement, allowlist, entries);
    }
}

package ru.warndev.maintenancegate;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateStoreTest {
    @TempDir Path root;
    private final UUID actor = new UUID(1, 2);
    private final Instant now = Instant.parse("2026-09-25T10:00:00Z");

    private GateState closed(GateState state) {
        return state.changeWindow(new GateWindow(now, now.plusSeconds(60), "Update"), actor, now, AuditEntry.Action.CLOSE);
    }

    @Test
    void savesPrimaryAndPreviousBackup() throws Exception {
        GateStore store = new GateStore(root);
        assertEquals(GateState.empty(), store.load());
        GateState first = closed(GateState.empty());
        store.save(first);
        assertEquals(first, store.load());
        GateState second = first.allow(new UUID(3, 4), actor, now);
        store.save(second);
        assertEquals(second, store.load());
        assertEquals(first, new GateCodec().decode(Files.readAllBytes(root.resolve("gate.backup"))));
    }

    @Test
    void rejectsRevisionConflictWithoutChangingPrimary() throws Exception {
        GateStore store = new GateStore(root);
        GateState first = closed(GateState.empty());
        store.save(first);
        assertThrows(IOException.class, () -> store.save(first));
        assertEquals(first, store.load());
    }

    @Test
    void missingPrimaryWithBackupRequiresRecovery() throws Exception {
        GateStore store = new GateStore(root);
        store.save(closed(GateState.empty()));
        store.save(store.load().allow(actor, actor, now));
        Files.delete(root.resolve("gate.dat"));
        assertThrows(IOException.class, store::load);
    }

    @Test
    void rejectsSymlinkState() throws Exception {
        Files.writeString(root.resolve("other.dat"), "secret");
        Files.createSymbolicLink(root.resolve("gate.dat"), root.resolve("other.dat"));
        assertThrows(IOException.class, () -> new GateStore(root).load());
    }

    @Test
    void corruptedStateGuardsAdmissionAndRejectsMutations() throws Exception {
        Files.writeString(root.resolve("gate.dat"), "broken");
        try (GateService service = new GateService(new GateStore(root))) {
            assertTrue(service.recoveryRequired());
            assertFalse(service.snapshot().admits(actor, false, false, false, now));
            assertTrue(service.snapshot().admits(actor, true, false, false, now));
            assertThrows(ExecutionException.class, () -> service.change(0, this::closed).get(5, TimeUnit.SECONDS));
        }
        assertEquals("broken", Files.readString(root.resolve("gate.dat")));
    }

    @Test
    void invalidConfigurationGuardsEvenWhenStateWasOpen() throws Exception {
        try (GateService service = new GateService(new GateStore(root), true)) {
            assertTrue(service.recoveryRequired());
            assertFalse(service.snapshot().admits(actor, false, false, false, now));
        }
        assertFalse(Files.exists(root.resolve("gate.dat")));
    }

    @Test
    void publishesOnlyCommittedStateAndSurvivesRestart() throws Exception {
        GateStore store = new GateStore(root);
        try (GateService service = new GateService(store)) {
            GateState committed = service.change(0, this::closed).get(5, TimeUnit.SECONDS);
            assertEquals(committed, store.load());
            assertEquals(committed, service.snapshot());
            assertThrows(ExecutionException.class, () -> service.change(0, this::closed).get(5, TimeUnit.SECONDS));
        }
        try (GateService restarted = new GateService(new GateStore(root))) {
            assertEquals(GateWindow.Phase.ACTIVE, restarted.snapshot().phase(now));
            assertEquals(GateWindow.Phase.EXPIRED, restarted.snapshot().phase(now.plusSeconds(60)));
        }
    }

    @Test
    void diskFailureDoesNotPublishNewState() throws Exception {
        try (GateService service = new GateService(new GateStore(root))) {
            Files.createDirectory(root.resolve("gate.dat"));
            assertThrows(ExecutionException.class, () -> service.change(0, this::closed).get(5, TimeUnit.SECONDS));
            assertEquals(0, service.snapshot().revision());
            assertEquals(GateWindow.Phase.OPEN, service.snapshot().phase(now));
        }
    }

    @Test
    void rejectsAfterClose() throws Exception {
        GateService service = new GateService(new GateStore(root));
        service.close();
        assertThrows(ExecutionException.class, () -> service.change(0, this::closed).get(5, TimeUnit.SECONDS));
    }
}

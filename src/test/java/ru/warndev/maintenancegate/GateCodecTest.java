package ru.warndev.maintenancegate;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GateCodecTest {
    private final GateCodec codec = new GateCodec();
    private final UUID actor = new UUID(1, 2);
    private final Instant now = Instant.parse("2026-09-25T10:00:00.123456789Z");

    @Test
    void roundTripsEmptyState() throws Exception {
        assertEquals(GateState.empty(), codec.decode(codec.encode(GateState.empty())));
    }

    @Test
    void roundTripsUnicodeWindowAllowlistAndAudit() throws Exception {
        GateState state = GateState.empty().changeWindow(new GateWindow(now, now.plusSeconds(60), "Обновление 😀"),
                actor, now, AuditEntry.Action.CLOSE).allow(new UUID(3, 4), actor, now);
        assertEquals(state, codec.decode(codec.encode(state)));
    }

    @Test
    void rejectsEveryTruncation() throws Exception {
        byte[] bytes = codec.encode(GateState.empty());
        for (int i = 0; i < bytes.length; i++) {
            byte[] prefix = Arrays.copyOf(bytes, i);
            assertThrows(IOException.class, () -> codec.decode(prefix));
        }
    }

    @Test
    void detectsEverySingleByteCorruption() throws Exception {
        byte[] original = codec.encode(GateState.empty());
        for (int i = 0; i < original.length; i++) {
            byte[] damaged = original.clone();
            damaged[i] ^= 1;
            assertThrows(IOException.class, () -> codec.decode(damaged));
        }
    }

    @Test
    void refusesExtraDataEvenWithValidChecksum() throws Exception {
        byte[] original = codec.encode(GateState.empty());
        byte[] extra = new byte[original.length + 1];
        System.arraycopy(original, 0, extra, 0, original.length - 8);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(extra, 0, extra.length - 8);
        java.nio.ByteBuffer.wrap(extra, extra.length - 8, 8).putLong(crc.getValue());
        assertThrows(IOException.class, () -> codec.decode(extra));
    }

    @Test
    void refusesOversizedInput() {
        assertThrows(IOException.class, () -> codec.decode(new byte[GateCodec.MAX_BYTES + 1]));
    }
}

package ru.warndev.maintenancegate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.zip.CRC32;

public final class GateCodec {
    private static final int MAGIC = 0x57444754;
    private static final int VERSION = 1;
    public static final int MAX_BYTES = 131072;

    public byte[] encode(GateState state) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(state.revision());
            out.writeBoolean(state.window() != null);
            if (state.window() != null) {
                instant(out, state.window().start());
                out.writeBoolean(state.window().end() != null);
                if (state.window().end() != null) {
                    instant(out, state.window().end());
                }
                out.writeUTF(state.window().reason());
            }
            out.writeInt(state.allowed().size());
            for (UUID uuid : state.allowed()) {
                uuid(out, uuid);
            }
            out.writeInt(state.audit().size());
            for (AuditEntry entry : state.audit()) {
                out.writeLong(entry.revision());
                instant(out, entry.time());
                uuid(out, entry.actor());
                out.writeUTF(entry.action().name());
                out.writeBoolean(entry.subject() != null);
                if (entry.subject() != null) {
                    uuid(out, entry.subject());
                }
            }
        }
        CRC32 crc = new CRC32();
        byte[] bytes = body.toByteArray();
        crc.update(bytes);
        try (DataOutputStream out = new DataOutputStream(body)) {
            out.writeLong(crc.getValue());
        }
        return body.toByteArray();
    }

    public GateState decode(byte[] bytes) throws IOException {
        if (bytes.length < 33 || bytes.length > MAX_BYTES) {
            throw new IOException("State size is invalid");
        }
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length - 8);
        try (DataInputStream trailer = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 8, 8))) {
            if (trailer.readLong() != crc.getValue()) {
                throw new IOException("State checksum mismatch");
            }
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 8))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unsupported state format");
            }
            long revision = in.readLong();
            GateWindow window = null;
            if (in.readBoolean()) {
                Instant start = instant(in);
                Instant end = in.readBoolean() ? instant(in) : null;
                window = new GateWindow(start, end, in.readUTF());
            }
            int count = bounded(in.readInt(), 512);
            var allowed = new LinkedHashSet<UUID>();
            for (int i = 0; i < count; i++) {
                if (!allowed.add(uuid(in))) {
                    throw new IOException("Duplicate UUID");
                }
            }
            int auditCount = bounded(in.readInt(), 100);
            var audit = new ArrayList<AuditEntry>();
            for (int i = 0; i < auditCount; i++) {
                long entryRevision = in.readLong();
                Instant time = instant(in);
                UUID actor = uuid(in);
                AuditEntry.Action action = AuditEntry.Action.valueOf(in.readUTF());
                UUID subject = in.readBoolean() ? uuid(in) : null;
                audit.add(new AuditEntry(entryRevision, time, actor, action, subject));
            }
            if (in.available() != 0) {
                throw new IOException("Unexpected trailing data");
            }
            return new GateState(revision, window, allowed, audit);
        } catch (IllegalArgumentException | java.time.DateTimeException error) {
            throw new IOException("Invalid state data", error);
        }
    }

    private static int bounded(int value, int max) throws IOException {
        if (value < 0 || value > max) {
            throw new IOException("Invalid collection size");
        }
        return value;
    }

    private static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void instant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }

    private static Instant instant(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos > 999999999) {
            throw new IOException("Invalid nanosecond field");
        }
        return Instant.ofEpochSecond(seconds, nanos);
    }
}

package ru.warndev.maintenancegate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class GateStore {
    private final Path directory;
    private final GateCodec codec = new GateCodec();

    public GateStore(Path directory) {
        this.directory = directory;
    }

    public synchronized GateState load() throws IOException {
        prepare();
        Path state = directory.resolve("gate.dat");
        if (!Files.exists(state, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(directory.resolve("gate.backup"), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Primary state missing while backup exists");
            }
            return GateState.empty();
        }
        return codec.decode(read(state));
    }

    public synchronized void save(GateState state) throws IOException {
        prepare();
        Path primary = directory.resolve("gate.dat");
        if (Files.exists(primary, LinkOption.NOFOLLOW_LINKS)) {
            byte[] previous = read(primary);
            GateState prior = codec.decode(previous);
            if (state.revision() != Math.addExact(prior.revision(), 1)) {
                throw new IOException("State revision conflict");
            }
            writeAtomic(directory.resolve("gate.backup"), previous);
        } else if (state.revision() != 1 || Files.exists(directory.resolve("gate.backup"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Initial state revision conflict");
        }
        writeAtomic(primary, codec.encode(state));
    }

    private void prepare() throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("State directory is invalid");
        }
    }

    private byte[] read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > GateCodec.MAX_BYTES) {
            throw new IOException("State file is invalid");
        }
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(GateCodec.MAX_BYTES + 1);
            if (bytes.length > GateCodec.MAX_BYTES) {
                throw new IOException("State file too large");
            }
            return bytes;
        }
    }

    private void writeAtomic(Path destination, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(destination)) {
            throw new IOException("Symbolic state file not allowed");
        }
        Path temporary = Files.createTempFile(directory, ".gate-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

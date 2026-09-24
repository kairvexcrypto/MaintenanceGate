package ru.warndev.maintenancegate;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GateSettingsTest {
    @TempDir Path root;

    private Path config() throws IOException {
        Path file = root.resolve("config.yml");
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            Files.write(file, input.readAllBytes());
        }
        return file;
    }

    @Test
    void loadsBundledConfiguration() throws Exception {
        var settings = GateSettings.load(config());
        assertTrue(settings.operatorBypass());
        assertTrue(settings.showMotd());
        assertTrue(settings.broadcastTransitions());
    }

    @Test
    void refusesUnknownKey() throws Exception {
        Path file = config();
        Files.writeString(file, Files.readString(file) + "typo: true\n");
        assertThrows(IOException.class, () -> GateSettings.load(file));
    }

    @Test
    void refusesWrongBooleanType() throws Exception {
        Path file = config();
        Files.writeString(file, Files.readString(file).replace("operator-bypass: true", "operator-bypass: 'true'"));
        assertThrows(IOException.class, () -> GateSettings.load(file));
    }

    @Test
    void refusesMalformedUtf8() throws Exception {
        Path file = root.resolve("config.yml");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> GateSettings.load(file));
    }
}

package ru.warndev.maintenancegate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public record GateSettings(boolean operatorBypass, boolean showMotd, boolean broadcastTransitions,
                           String rejection, String motd, String opening, String closing) {
    private static final Set<String> KEYS = Set.of("operator-bypass", "show-motd", "broadcast-transitions",
            "maintenance-message", "maintenance-motd", "opening-message", "closing-message");

    public static GateSettings load(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > 65536) {
            throw new IOException("Configuration file unavailable or exceeds 64 KiB");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(65537);
        }
        if (bytes.length > 65536) {
            throw new IOException("Configuration exceeds 64 KiB");
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            yaml.loadFromString(text);
            if (!KEYS.containsAll(yaml.getKeys(false))) {
                throw new IllegalArgumentException("Неизвестный параметр конфигурации");
            }
            return new GateSettings(bool(yaml, "operator-bypass"), bool(yaml, "show-motd"),
                    bool(yaml, "broadcast-transitions"), text(yaml, "maintenance-message"),
                    text(yaml, "maintenance-motd"), text(yaml, "opening-message"), text(yaml, "closing-message"));
        } catch (InvalidConfigurationException | IllegalArgumentException error) {
            throw new IOException("Invalid MaintenanceGate configuration", error);
        }
    }

    private static boolean bool(YamlConfiguration yaml, String key) {
        if (!(yaml.get(key) instanceof Boolean result)) {
            throw new IllegalArgumentException("Ожидается boolean: " + key);
        }
        return result;
    }

    private static String text(YamlConfiguration yaml, String key) {
        if (!(yaml.get(key) instanceof String result) || result.isBlank() || result.length() > 240
                || result.codePoints().anyMatch(code -> Character.isISOControl(code)
                || Character.getType(code) == Character.FORMAT || code == 167)) {
            throw new IllegalArgumentException("Неверный текст сообщения: " + key);
        }
        return result;
    }

    public String render(String template, GateWindow window) {
        String reason = window == null ? "—" : window.reason();
        String end = window == null || window.end() == null ? "не указано" : window.end().toString();
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < template.length();) {
            if (template.startsWith("{reason}", i)) {
                result.append(reason);
                i += 8;
            } else if (template.startsWith("{end}", i)) {
                result.append(end);
                i += 5;
            } else {
                result.append(template.charAt(i++));
            }
        }
        return result.toString();
    }

    public static GateSettings emergency() {
        return new GateSettings(true, true, false,
                "Сервер временно закрыт: {reason}", "Технические работы", "Вход открыт", "Вход ограничен");
    }
}

package ru.warndev.maintenancegate;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GateArguments {
    private static final Pattern DURATION = Pattern.compile("([1-9][0-9]{0,6})([smhd])");
    private static final Duration MAX_DURATION = Duration.ofDays(30);

    private GateArguments() {
    }

    public static Duration duration(String value) {
        Matcher matcher = DURATION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Длительность: 30s, 10m, 2h или 1d");
        }
        long count = Long.parseLong(matcher.group(1));
        long multiplier = switch (matcher.group(2)) {
            case "s" -> 1;
            case "m" -> 60;
            case "h" -> 3600;
            case "d" -> 86400;
            default -> throw new IllegalArgumentException("Неверная единица времени");
        };
        Duration duration = Duration.ofSeconds(Math.multiplyExact(count, multiplier));
        if (duration.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("Максимальная длительность: 30 дней");
        }
        return duration;
    }

    public static Instant timestamp(String value, Instant now) {
        try {
            Instant result = Instant.parse(value);
            if (!result.isAfter(now) || result.isAfter(now.plus(Duration.ofDays(30)))) {
                throw new IllegalArgumentException("Начало должно быть в ближайшие 30 дней и позже текущего времени");
            }
            return result;
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException("Начало: ISO-8601 UTC, например 2026-10-01T18:00:00Z");
        }
    }

    public static UUID uuid(String value) {
        if (!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("Укажите полный UUID либо точный ник игрока онлайн");
        }
        return UUID.fromString(value);
    }

    public static String reason(String[] args, int from) {
        if (from >= args.length) {
            throw new IllegalArgumentException("Укажите причину технических работ");
        }
        String result = String.join(" ", Arrays.copyOfRange(args, from, args.length)).strip();
        new GateWindow(Instant.EPOCH, null, result);
        return result;
    }

    public static int page(String value, int pages) {
        try {
            int page = Integer.parseInt(value);
            if (page < 1 || page > pages) {
                throw new IllegalArgumentException("Допустимые страницы: 1.." + pages);
            }
            return page;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Номер страницы должен быть целым числом");
        }
    }
}

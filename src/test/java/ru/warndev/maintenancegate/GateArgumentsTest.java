package ru.warndev.maintenancegate;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GateArgumentsTest {
    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1m", "1.5h", "31d", "9999999d", "10", "10w", "01h", "1H"})
    void refusesInvalidDurations(String input) {
        assertThrows(IllegalArgumentException.class, () -> GateArguments.duration(input));
    }

    @Test
    void parsesBoundedDurations() {
        assertEquals(Duration.ofSeconds(30), GateArguments.duration("30s"));
        assertEquals(Duration.ofMinutes(10), GateArguments.duration("10m"));
        assertEquals(Duration.ofHours(2), GateArguments.duration("2h"));
        assertEquals(Duration.ofDays(30), GateArguments.duration("30d"));
    }

    @Test
    void requiresCanonicalUuid() {
        UUID uuid = UUID.randomUUID();
        assertEquals(uuid, GateArguments.uuid(uuid.toString()));
        assertThrows(IllegalArgumentException.class, () -> GateArguments.uuid("1-1-1-1-1"));
    }

    @Test
    void acceptsOnlyFutureScheduleWithinThirtyDays() {
        Instant now = Instant.parse("2026-09-25T00:00:00Z");
        assertEquals(now.plusSeconds(60), GateArguments.timestamp("2026-09-25T00:01:00Z", now));
        assertThrows(IllegalArgumentException.class, () -> GateArguments.timestamp(now.toString(), now));
        assertThrows(IllegalArgumentException.class, () -> GateArguments.timestamp(now.plus(Duration.ofDays(31)).toString(), now));
        assertThrows(IllegalArgumentException.class, () -> GateArguments.timestamp("tomorrow", now));
    }

    @Test
    void boundsPageNumbers() {
        assertEquals(2, GateArguments.page("2", 2));
        assertThrows(IllegalArgumentException.class, () -> GateArguments.page("0", 2));
        assertThrows(IllegalArgumentException.class, () -> GateArguments.page("2147483648", 2));
    }

    @Test
    void doesNotInterpretPlaceholdersInsideReason() {
        GateSettings settings = GateSettings.emergency();
        GateWindow window = new GateWindow(Instant.EPOCH, null, "literal {end}");
        assertEquals("literal {end} / не указано", settings.render("{reason} / {end}", window));
    }
}

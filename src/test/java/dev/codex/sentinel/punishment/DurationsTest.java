package dev.codex.sentinel.punishment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationsTest {
    @Test void parsesUnits() {
        assertEquals(30_000L, Durations.parseMillis("30s"));
        assertEquals(7 * 86_400_000L, Durations.parseMillis("7d"));
        assertEquals(3_600_000L, Durations.parseMillis("1H"));
    }
    @Test void rejectsBadInput() {
        assertEquals(-1, Durations.parseMillis(null));
        assertEquals(-1, Durations.parseMillis("d"));
        assertEquals(-1, Durations.parseMillis("0d"));
        assertEquals(-1, Durations.parseMillis("-5m"));
        assertEquals(-1, Durations.parseMillis("5w"));
        assertEquals(-1, Durations.parseMillis("99999999999999999d"));
    }
}

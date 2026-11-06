package dev.codex.sentinel.data;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDataTest {
    private final UUID id = UUID.randomUUID();

    @Test void flagsOutsideWindowDoNotAccumulate() {
        var d = new PlayerData(id, 0);
        assertEquals(1, d.recordFlag("speed", "movement", 0, 10_000));
        assertEquals(2, d.recordFlag("speed", "movement", 5_000, 10_000));
        assertEquals(1, d.recordFlag("speed", "movement", 20_000, 10_000));
    }

    @Test void decayWorksInWholeMinutesAndDropsEmptyEntries() {
        var d = new PlayerData(id, 0);
        d.add("speed", 5);
        d.decay(1.0, 30_000);
        assertEquals(5.0, d.vl("speed"), 1e-9);
        d.decay(1.0, 120_000);
        assertEquals(3.0, d.vl("speed"), 1e-9);
        d.decay(10.0, 180_000);
        assertEquals(0.0, d.vl("speed"), 1e-9);
        assertTrue(d.violations().isEmpty());
    }

    @Test void speedWindowAveragesOverElapsedTimeAndIgnoresOpeningStep() {
        var d = new PlayerData(id, 0);
        assertTrue(Double.isNaN(d.addHorizontal(0, 9, 1000)));
        assertTrue(Double.isNaN(d.addHorizontal(500, 3, 1000)));
        assertEquals(6.5, d.addHorizontal(1000, 3.5, 1000), 1e-9);
        d.resetMovementWindow();
        assertTrue(Double.isNaN(d.addHorizontal(1500, 5, 1000)));
    }

    @Test void independentCategoriesCountOncePerCategory() {
        var d = new PlayerData(id, 0);
        d.recordFlag("movement-speed", "movement", 0, 10_000);
        d.recordFlag("vertical-movement", "movement", 0, 10_000);
        assertEquals(Set.of("movement"), d.confirmedCategories(1000, 10_000, c -> 1));
        d.recordFlag("reach", "combat", 0, 10_000);
        assertEquals(Set.of("movement", "combat"), d.confirmedCategories(1000, 10_000, c -> 1));
        assertEquals(Set.of(), d.confirmedCategories(1000, 10_000, c -> 2));
    }

    @Test void graceOnlyExtends() {
        var d = new PlayerData(id, 0);
        d.grace(100, 20);
        d.grace(100, 5);
        assertTrue(d.inGrace(120));
        assertFalse(d.inGrace(121));
    }

    @Test void throttleBlocksRepeatsInsideInterval() {
        var d = new PlayerData(id, 0);
        assertTrue(d.allowLog("speed", 0, 1000));
        assertFalse(d.allowLog("speed", 500, 1000));
        assertTrue(d.allowLog("speed", 1000, 1000));
    }

    @Test void bufferNeedsRepeatedViolationsAndRelaxesWhenClean() {
        var d = new PlayerData(id, 0);
        assertFalse(d.bump("speed", 1, 3));
        assertFalse(d.bump("speed", 1, 3));
        d.relax("speed", 1);
        assertFalse(d.bump("speed", 1, 3));
        assertTrue(d.bump("speed", 1, 3));
        assertFalse(d.bump("speed", 1, 3));   // halved after firing, so a sustained cheat keeps flagging
    }
}

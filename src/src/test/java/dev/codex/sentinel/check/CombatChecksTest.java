package dev.codex.sentinel.check;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CombatChecksTest {
    private final BoundingBox box = new BoundingBox(0, 0, 3, 1, 2, 4);      // a target 3 blocks ahead, 1 wide

    @Test void rayThroughHitboxIsAZeroMiss() {
        assertEquals(0, CombatChecks.rayMiss(new Vector(0.5, 1, 0), new Vector(0, 0, 1), box, 5), 1e-9);
    }

    @Test void slightlyOffRayIsASmallMiss() {
        double miss = CombatChecks.rayMiss(new Vector(1.3, 1, 0), new Vector(0, 0, 1), box, 5);   // passes 0.3 beside the box
        assertEquals(0.3, miss, 0.06);
    }

    @Test void lookingAwayIsAnObviousMiss() {
        assertTrue(CombatChecks.rayMiss(new Vector(0.5, 1, 0), new Vector(1, 0, 0), box, 5) > 2.5);
    }
}

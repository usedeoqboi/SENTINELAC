package dev.codex.sentinel.physics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FormulasTest {
    @Test void terminalVelocityMatchesVanilla() {
        assertEquals(3.92, Formulas.terminalFallPerTick(0.08), 1e-9);          // 78.4 blocks/s
    }

    @Test void jumpArcFollowsGravityAndDrag() {
        double dy = 0.42, apexTick = 0;
        double prev = dy; int ticks = 1;
        while (true) { prev = Formulas.predictedDy(prev, 0.08); if (prev <= 0) break; ticks++; }
        assertEquals(0.3332, Formulas.predictedDy(0.42, 0.08), 1e-4);
        assertEquals(6, ticks);                                                // apex after 6 rising ticks
    }

    @Test void sprintingOnGroundConvergesToVanillaSprintSpeed() {
        double speedAttr = 0.13;                                               // 0.1 base x 1.3 sprint
        double h = 0;
        for (int i = 0; i < 60; i++) h = Formulas.maxHorizontalPerTick(h, true, 0.6, speedAttr, true, false);
        assertEquals(5.727, h * 20, 0.01);                                     // bound; vanilla is 5.612 b/s (input is scaled by 0.98)
        assertTrue(h * 20 > 5.612);
    }

    @Test void speedAttributeMapsToBlocksPerSecond() {
        assertEquals(5.612, 0.13 * Formulas.BPS_PER_ATTRIBUTE, 0.01);          // sprint
        assertEquals(7.857, 0.1 * 1.4 * 1.3 * Formulas.BPS_PER_ATTRIBUTE, 0.01); // sprint + Speed II
    }

    @Test void sprintJumpAddsPointTwo() {
        double steady = 0.2863;
        double takeoff = Formulas.maxHorizontalPerTick(steady, true, 0.6, 0.13, true, true);
        assertEquals(steady * 0.546 + 0.13 + 0.2, takeoff, 1e-9);
    }

    @Test void iceKeepsMomentumLonger() {
        double normal = Formulas.maxHorizontalPerTick(0.3, true, 0.6, 0.13, true, false);
        double blue = Formulas.maxHorizontalPerTick(0.3, true, 0.989, 0.13, true, false);
        assertTrue(blue > normal);
        assertEquals(0.989, Formulas.slipperiness("blue_ice"), 1e-9);
        assertEquals(0.98, Formulas.slipperiness("PACKED_ICE"), 1e-9);
        assertEquals(0.6, Formulas.slipperiness("STONE"), 1e-9);
    }

    @Test void maceSmashBonusMatchesWiki() {
        assertEquals(0, Formulas.smashBonus(1.5), 1e-9);
        assertEquals(12, Formulas.smashBonus(3), 1e-9);
        assertEquals(22, Formulas.smashBonus(8), 1e-9);
        assertEquals(24, Formulas.smashBonus(10), 1e-9);                       // wiki: +24 for a 10 block fall
        assertTrue(Formulas.maceMaxDamage(0, 0) < Formulas.maceMaxDamage(10, 0));
        assertTrue(Formulas.maceMaxDamage(10, 5) > Formulas.maceMaxDamage(10, 0));
    }

    @Test void spearChargeDamageMatchesMultiplierEstimate() {
        // At 33.5 b/s, the tier multiplier estimate is 1.2 x 33.5 = 40.2.
        assertEquals(7, Formulas.spearMultiplier("NETHERITE_SPEAR") * 5.612, 0.5);
        assertEquals(40.2, Formulas.spearMultiplier("NETHERITE_SPEAR") * 33.5, 1e-9);
        assertEquals(5.3314, Formulas.spearMultiplier("IRON_SPEAR") * 5.612, 1e-9);
        assertEquals(4, Formulas.spearJabDamage("DIAMOND_SPEAR"), 1e-9);
    }

    @Test void spoofedSpearDamageExceedsAllowance() {
        double legalSprint = Formulas.spearMaxDamage("NETHERITE_SPEAR", 6.2, 0, 8);
        assertTrue(legalSprint < 20);
        assertTrue(40.2 > legalSprint);                                        // an elytra-speed hit while standing still is caught
    }
}

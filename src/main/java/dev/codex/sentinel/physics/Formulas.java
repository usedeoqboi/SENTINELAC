package dev.codex.sentinel.physics;

import java.util.Locale;

/**
 * Pure vanilla-physics formulas (no Bukkit types) so they can be unit tested. Numbers and sources are listed in DETECTIONS.md.
 * Everything here is an UPPER BOUND: legitimate play must never exceed it, cheats usually do.
 */
public final class Formulas {
    private Formulas() { }

    public static final double GRAVITY = 0.08;
    public static final double VERTICAL_DRAG = 0.98;
    public static final double AIR_FRICTION = 0.91;
    /** movement_speed attribute 0.1 equals 4.317 blocks per second. */
    public static final double BPS_PER_ATTRIBUTE = 43.17;
    public static final double BOAT_WATER_PER_TICK = 0.4;       // 8 b/s
    public static final double BOAT_ICE_PER_TICK = 2.0;         // 40 b/s
    public static final double BOAT_BLUE_ICE_PER_TICK = 3.64;   // 72.73 b/s
    public static final double LUNGE_III_BPS = 27.48;

    /** Terminal fall speed in blocks per tick: 3.92 for default gravity (78.4 b/s). */
    public static double terminalFallPerTick(double gravity) { return gravity * VERTICAL_DRAG / (1.0 - VERTICAL_DRAG); }

    /** Vertical displacement the next tick can have at most, given the previous tick's displacement, while airborne. */
    public static double predictedDy(double prevDy, double gravity) { return (prevDy - gravity) * VERTICAL_DRAG; }

    public static double slipperiness(String material) {
        return switch (material.toUpperCase(Locale.ROOT)) {
            case "ICE", "PACKED_ICE", "FROSTED_ICE" -> 0.98;
            case "BLUE_ICE" -> 0.989;
            case "SLIME_BLOCK" -> 0.8;
            default -> 0.6;
        };
    }

    /**
     * Upper bound for this tick's horizontal displacement. Carried momentum decays by friction, then the player adds at most
     * one tick of acceleration. Friction and acceleration both depend on the state at the END of the previous tick.
     * A sprint-jump adds 0.2 in the facing direction.
     */
    public static double maxHorizontalPerTick(double prevH, boolean prevGround, double slip, double speedAttr, boolean sprinting, boolean jumped) {
        double friction = prevGround ? slip * AIR_FRICTION : AIR_FRICTION;
        double accel = prevGround ? speedAttr * (0.216 / (slip * slip * slip)) : (sprinting ? 0.026 : 0.02);
        return prevH * friction + accel + (jumped && sprinting ? 0.2 : 0.0);
    }

    // ------------------------------------------------------------------ mace
    /** Smash bonus: +4/block for the first 3, +2/block for the next 5, +1/block after that. Needs more than 1.5 blocks fallen. */
    public static double smashBonus(double fall) {
        if (fall <= 1.5) return 0;
        if (fall <= 3) return 4 * fall;
        if (fall <= 8) return 12 + 2 * (fall - 3);
        return 22 + (fall - 8);
    }

    /** Highest pre-armor damage a mace hit can do after {@code observedFall} blocks of server-observed falling. */
    public static double maceMaxDamage(double observedFall, int densityLevel) {
        double fall = Math.max(0, observedFall);
        // 6 base + 6 margin for Sharpness/Strength, x1.5 crit, + Density, + small slack
        return (6.0 + 6.0 + smashBonus(fall)) * 1.5 + (fall > 1.5 ? 0.5 * densityLevel * fall : 0) + 2.0;
    }

    // ------------------------------------------------------------------ spear
    public static double spearMultiplier(String item) {
        String n = item.toUpperCase(Locale.ROOT);
        if (n.startsWith("WOODEN") || n.startsWith("GOLDEN")) return 0.7;
        if (n.startsWith("STONE") || n.startsWith("COPPER")) return 0.82;
        if (n.startsWith("IRON")) return 0.95;
        if (n.startsWith("DIAMOND")) return 1.075;
        return 1.2;
    }

    public static double spearJabDamage(String item) {
        String n = item.toUpperCase(Locale.ROOT);
        if (n.startsWith("WOODEN") || n.startsWith("GOLDEN")) return 1;
        if (n.startsWith("STONE") || n.startsWith("COPPER")) return 2;
        if (n.startsWith("IRON")) return 3;
        if (n.startsWith("DIAMOND")) return 4;
        return 5;
    }

    /** Charge damage is roughly tier multiplier x closing speed in blocks/second. */
    public static double spearMaxDamage(String item, double ownBps, double targetBps, double margin) {
        return Math.max(spearJabDamage(item) + margin, spearMultiplier(item) * (ownBps + targetBps) + margin);
    }
}

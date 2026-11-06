package dev.codex.sentinel.data;

import java.util.*;
import java.util.function.ToIntFunction;

/**
 * Per-player state. Only touched from the server thread. All time arguments are monotonic
 * milliseconds (see {@link #nowMillis()}) so wall-clock changes cannot corrupt windows or decay.
 */
public final class PlayerData {
    private static final int MAX_FLAG_HISTORY = 256;

    private final UUID uuid;
    private final Map<String, Double> violations = new HashMap<>();
    private final Map<String, ArrayDeque<Long>> flagTimes = new HashMap<>();
    private final Map<String, String> categories = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final Map<String, Long> lastLog = new HashMap<>();

    public final MoveState move = new MoveState();
    private final Map<String, Double> buffers = new HashMap<>();

    private long graceUntilTick;
    private long lastDecayMillis;

    private boolean moveWindowOpen;
    private long moveWindowStart;
    private double moveWindowDistance;

    private int placements;
    private long placementWindow = Long.MIN_VALUE;

    public PlayerData(UUID uuid) { this(uuid, nowMillis()); }
    public PlayerData(UUID uuid, long nowMillis) { this.uuid = uuid; this.lastDecayMillis = nowMillis; }

    public static long nowMillis() { return System.nanoTime() / 1_000_000L; }

    public UUID uuid() { return uuid; }

    // ---- violation levels ----
    public double vl(String check) { return violations.getOrDefault(check, 0.0); }
    public double add(String check, double amount) { return violations.merge(check, amount, Double::sum); }
    public Map<String, Double> violations() { return Map.copyOf(violations); }

    public void clear(String check) { violations.remove(check); flagTimes.remove(check); }
    public void reset() {
        violations.clear(); flagTimes.clear(); categories.clear();
        lastAlert.clear(); lastLog.clear(); buffers.clear(); resetMovementWindow(); move.resetAll();
    }

    /** Linear decay, applied in whole-minute steps. Fully decayed entries are dropped. */
    public void decay(double amountPerMinute, long nowMillis) {
        long elapsed = nowMillis - lastDecayMillis;
        if (elapsed < 60_000L) return;
        long periods = elapsed / 60_000L;
        lastDecayMillis += periods * 60_000L;
        double loss = amountPerMinute * periods;
        violations.replaceAll((k, v) -> Math.max(0.0, v - loss));
        violations.values().removeIf(v -> v <= 0.0);
    }

    // ---- confirmation: flags must repeat inside a rolling window ----
    /** Records a flag and returns how many flags for this check fall inside the window (including this one). */
    public int recordFlag(String check, String category, long now, long windowMs) {
        categories.put(check, category);
        ArrayDeque<Long> q = flagTimes.computeIfAbsent(check, k -> new ArrayDeque<>());
        q.addLast(now);
        while (q.size() > MAX_FLAG_HISTORY) q.pollFirst();
        return prune(q, now, windowMs);
    }

    public int recentFlags(String check, long now, long windowMs) {
        ArrayDeque<Long> q = flagTimes.get(check);
        return q == null ? 0 : prune(q, now, windowMs);
    }

    /** Distinct categories that currently have at least {@code needed(check)} flags inside the window. */
    public Set<String> confirmedCategories(long now, long windowMs, ToIntFunction<String> needed) {
        Set<String> out = new HashSet<>();
        for (String check : flagTimes.keySet())
            if (recentFlags(check, now, windowMs) >= needed.applyAsInt(check))
                out.add(categories.getOrDefault(check, check));
        return out;
    }

    private static int prune(ArrayDeque<Long> q, long now, long windowMs) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) q.pollFirst();
        return q.size();
    }

    // ---- violation buffers: a physical bound must be broken repeatedly before it counts ----
    /** Adds to a named buffer; returns true (and halves the buffer) once it reaches {@code limit}. */
    public boolean bump(String key, double amount, double limit) {
        double v = buffers.merge(key, amount, Double::sum);
        if (v < limit) return false;
        buffers.put(key, limit * 0.5);
        return true;
    }
    public void relax(String key, double amount) { buffers.computeIfPresent(key, (k, v) -> Math.max(0.0, v - amount)); }

    // ---- alert / log throttling ----
    public boolean allowAlert(String check, long now, long cooldownMs) { return allow(lastAlert, check, now, cooldownMs); }
    public boolean allowLog(String check, long now, long intervalMs) { return allow(lastLog, check, now, intervalMs); }
    private static boolean allow(Map<String, Long> map, String key, long now, long gap) {
        Long last = map.get(key);
        if (last != null && now - last < gap) return false;
        map.put(key, now);
        return true;
    }

    // ---- grace (server ticks) ----
    public void grace(long currentTick, int ticks) { graceUntilTick = Math.max(graceUntilTick, currentTick + ticks); }
    public boolean inGrace(long currentTick) { return currentTick <= graceUntilTick; }

    // ---- horizontal speed window ----
    /**
     * Adds a horizontal step. Returns average blocks/second once the window has elapsed, otherwise NaN.
     * The step that opens a window is ignored: its distance was covered before the window started.
     */
    public double addHorizontal(long now, double distance, long windowMs) {
        if (!moveWindowOpen) { moveWindowOpen = true; moveWindowStart = now; moveWindowDistance = 0; return Double.NaN; }
        moveWindowDistance += distance;
        long elapsed = now - moveWindowStart;
        if (elapsed < windowMs) return Double.NaN;
        double bps = moveWindowDistance / (elapsed / 1000.0);
        moveWindowStart = now; moveWindowDistance = 0;
        return bps;
    }
    public void resetMovementWindow() { moveWindowOpen = false; moveWindowDistance = 0; }

    // ---- placement rate ----
    public int countPlacement(long now) {
        if (placementWindow == Long.MIN_VALUE || now - placementWindow >= 1000) { placementWindow = now; placements = 0; }
        return ++placements;
    }
}

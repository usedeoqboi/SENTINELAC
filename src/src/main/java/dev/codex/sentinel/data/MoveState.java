package dev.codex.sentinel.data;

import org.bukkit.Location;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Mutable per-player tracking used by the movement, combat and vehicle checks. Server thread only. */
public final class MoveState {
    // ---- event-to-event prediction ----
    public boolean hasPrev;
    public long prevMs;
    public double prevH, prevDy;
    public boolean prevGround;

    // ---- allowance for server-applied velocity (knockback, explosions, wind charges); decays per move event ----
    public double kbH, kbV;
    public int kbUntilTick;

    // ---- timer ----
    public boolean timerPrimed;
    public long timerLastMs;
    public double timerBalance;

    // ---- per-tick sampler ----
    public int airTicks;
    public double groundY, maxY;
    public Location lastGround;
    private final ArrayDeque<double[]> samples = new ArrayDeque<>();

    // ---- anti-knockback tracker ----
    public boolean kbActive, kbGround;
    public int kbTick, kbEvents;
    public double kbH0, kbVy, kbDirX, kbDirZ, kbProj, kbMaxDy;

    // ---- combat / placement ----
    public int attackTick = -1;
    public final Set<UUID> attackTargets = new HashSet<>();
    public int placeTick = -1, placeCount;

    // ---- boats ----
    public int boatAir, boatWaterTick, boatCapTick;
    public double boatCap;

    public void resetPrediction() { hasPrev = false; prevH = 0; prevDy = 0; }

    public void resetAll() {
        resetPrediction();
        kbH = 0; kbV = 0; kbActive = false;
        timerPrimed = false; timerBalance = 0;
        airTicks = 0; samples.clear();
        boatAir = 0;
    }

    /** Server-applied launch (explosion, wind charge, mace wind burst): widen the bounds instead of exempting the player. */
    public void allowLaunch(int tick, double horizontal, double vertical) {
        kbH = Math.max(kbH, horizontal);
        kbV = Math.max(kbV, vertical);
        kbUntilTick = Math.max(kbUntilTick, tick + 30);
    }

    public void addSample(int tick, double x, double y, double z) {
        if (!samples.isEmpty() && samples.peekLast()[0] == tick) return;
        samples.addLast(new double[] {tick, x, y, z});
        while (samples.size() > 21) samples.pollFirst();
    }

    /** Highest observed 3D speed (blocks/second) over spans of 2+ ticks within the last second. Jitter only ever overestimates. */
    public double peakBps(int nowTick) {
        double[][] a = samples.toArray(new double[0][]);
        double peak = 0;
        for (int i = 0; i < a.length; i++) for (int j = i + 1; j < a.length; j++) {
            double dt = a[j][0] - a[i][0];
            if (dt < 2 || nowTick - a[i][0] > 20) continue;
            double dx = a[j][1] - a[i][1], dy = a[j][2] - a[i][2], dz = a[j][3] - a[i][3];
            peak = Math.max(peak, Math.sqrt(dx * dx + dy * dy + dz * dz) / (dt / 20.0));
        }
        return peak;
    }
}

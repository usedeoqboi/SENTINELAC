package dev.codex.sentinel.check;

import dev.codex.sentinel.SentinelPlugin;
import dev.codex.sentinel.data.MoveState;
import dev.codex.sentinel.data.PlayerData;
import dev.codex.sentinel.engine.DetectionEngine;
import dev.codex.sentinel.physics.Formulas;
import dev.codex.sentinel.physics.Physics;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Movement family: speed, fly, no-fall, timer, anti-knockback and invalid actions. Every check is always on.
 * Checks run at HIGH priority so a violation can cancel the move, which makes the server put the player back.
 *
 * All limits are vanilla physics upper bounds (see Formulas / DETECTIONS.md), not flat caps: anything a vanilla client can
 * produce passes, anything it cannot is flagged after a short buffer.
 */
public final class MovementChecks implements Listener {
    private final SentinelPlugin plugin;
    public MovementChecks(SentinelPlugin plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private DetectionEngine engine() { return plugin.engine(); }

    // ================================================================= exemptions
    /** States whose physics this predictor does not model. Not an "off switch": each has its own bound elsewhere. */
    public static boolean movementExempt(Player p, PlayerData d, int tick) {
        return !Physics.survival(p) || p.isDead() || p.isFlying() || p.getAllowFlight() || p.isInsideVehicle()
                || p.isGliding() || p.isRiptiding() || p.isClimbing() || p.isSleeping()
                || p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)
                || Math.abs(Physics.attr(p, Attribute.SCALE, 1.0) - 1.0) > 1e-6
                || d.inGrace(tick);
    }

    private void grace(Player p, String key, int def) {
        engine().data(p).grace(Bukkit.getCurrentTick(), cfg().getInt("lag." + key, def));
    }

    // ================================================================= per-move checks
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location from = e.getFrom(), to = e.getTo();
        PlayerData d = engine().data(p);
        MoveState s = d.move;

        float pitch = to.getPitch();
        if (Float.isNaN(pitch) || Math.abs(pitch) > 90.01f) {
            plugin.flag(p, "invalid-action", "protocol", "illegal pitch " + pitch);
            e.setCancelled(true);
            s.resetPrediction();
            return;
        }
        if (!e.hasChangedPosition()) return;
        if (from.getWorld() != to.getWorld()) { s.resetAll(); return; }

        long nowMs = PlayerData.nowMillis();
        int tick = Bukkit.getCurrentTick();
        boolean cancel = false;

        // ---- timer: movement packets must not arrive faster than 20 per second over time ----
        if (Physics.survival(p) && !d.inGrace(tick) && Bukkit.getTPS()[0] >= cfg().getDouble("lag.minimum-tps", 18.0))
            cancel |= timer(p, d, s, nowMs);
        else s.timerPrimed = false;

        if (movementExempt(p, d, tick)) {
            s.resetPrediction();
            s.kbActive = false;
            if (cancel) e.setCancelled(true);
            return;
        }

        World w = to.getWorld();
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double h = Math.hypot(dx, dz);

        boolean inWater = p.isInWater();
        boolean special = Physics.touchesSpecial(w, to.getX(), to.getY(), to.getZ());
        if (special) d.grace(tick, 20);

        boolean sameStep = s.hasPrev && nowMs - s.prevMs <= 200;
        boolean prevGround = sameStep ? s.prevGround : Physics.onGround(w, from.getX(), from.getY(), from.getZ());
        boolean groundNow = Physics.onGround(w, to.getX(), to.getY(), to.getZ());

        // ---- speed: horizontal displacement bounded by momentum*friction + one tick of acceleration ----
        if (sameStep) {
            double maxH;
            if (inWater) {
                maxH = cfg().getDouble("checks.speed.max-water-per-tick", 0.95);
            } else {
                double slip = prevGround ? Physics.slipAt(w, from.getX(), from.getY(), from.getZ()) : 0.6;
                maxH = Formulas.maxHorizontalPerTick(s.prevH, prevGround, slip,
                        Physics.attr(p, Attribute.MOVEMENT_SPEED, 0.1), p.isSprinting(), prevGround && dy > 0.1);
            }
            maxH = maxH * 1.02 + cfg().getDouble("checks.speed.tolerance", 0.05) + s.kbH;
            if (h > maxH) {
                if (d.bump("speed", 1, cfg().getDouble("checks.speed.buffer", 3))) {
                    plugin.flag(p, "speed", "movement", String.format(Locale.US,
                            "%.3f b/tick (%.1f b/s) > vanilla max %.3f (%.1f b/s)", h, h * 20, maxH, maxH * 20));
                    cancel = true;
                }
            } else d.relax("speed", 0.25);
        }

        // ---- fly / vertical: airborne displacement must follow gravity; takeoff is bounded by jump strength ----
        if (sameStep && !special && !inWater) {
            double g = Physics.attr(p, Attribute.GRAVITY, Formulas.GRAVITY);
            double jump = Physics.attr(p, Attribute.JUMP_STRENGTH, 0.42) + 0.1 * Physics.effectLevel(p, PotionEffectType.JUMP_BOOST);
            double step = Physics.attr(p, Attribute.STEP_HEIGHT, 0.6);
            boolean landing = !prevGround && groundNow;
            double upLimit = (prevGround ? Math.max(jump, step) + 0.08
                    : Formulas.predictedDy(s.prevDy, g) + cfg().getDouble("checks.fly.tolerance", 0.03)) + s.kbV;
            if (!landing && dy > upLimit && !(prevGround == false && Physics.entitySupport(w, from.getX(), from.getY(), from.getZ()))) {
                if (d.bump("fly", 1, cfg().getDouble("checks.fly.buffer", 3))) {
                    plugin.flag(p, "fly", "movement", String.format(Locale.US,
                            "rose %.3f, vanilla max %.3f (%s)", dy, upLimit, prevGround ? "takeoff" : "airborne, gravity not applied"));
                    cancel = true;
                }
            } else d.relax("fly", 0.25);

            double down = Formulas.terminalFallPerTick(g) + 0.1;
            if (-dy > down && d.bump("fly-down", 1, 2)) {
                plugin.flag(p, "fly", "movement", String.format(Locale.US, "fell %.3f/tick, terminal velocity is %.3f", -dy, down - 0.1));
                cancel = true;
            }
        }

        // ---- no-fall: the client claims "on ground" while neither end of the move has anything solid below ----
        if (sameStep && p.isOnGround() && !prevGround && !groundNow && !special && !inWater
                && !Physics.entitySupport(w, from.getX(), from.getY(), from.getZ())) {
            if (d.bump("nofall", 1, cfg().getDouble("checks.nofall.buffer", 2))) {
                plugin.flag(p, "nofall", "movement", "client reports onGround with no solid block or entity below");
                p.setFallDistance((float) Math.max(p.getFallDistance(), s.maxY - from.getY())); // restore the fall the client hid
                cancel = true;
            }
        } else d.relax("nofall", 0.5);

        // ---- anti-knockback: judge the first few moves after the server applied a velocity ----
        if (s.kbActive) antiKnockback(p, d, s, e, to, dx, dy, dz, tick);

        // ---- bookkeeping ----
        s.prevMs = nowMs; s.prevH = h; s.prevDy = dy; s.prevGround = groundNow; s.hasPrev = true;
        s.kbH *= 0.91; s.kbV *= 0.91;
        if (s.kbH < 0.02) s.kbH = 0;
        if (s.kbV < 0.02) s.kbV = 0;

        if (cancel) {
            e.setCancelled(true);   // server teleports the player back to the last accepted position
            s.resetPrediction();
        }
    }

    private boolean timer(Player p, PlayerData d, MoveState s, long nowMs) {
        boolean primed = s.timerPrimed;
        long last = s.timerLastMs;
        s.timerLastMs = nowMs;
        s.timerPrimed = true;
        if (!primed) { s.timerBalance = 0; return false; }
        // Each move packet "costs" 50ms. Banking is capped so a lag spike's catch-up burst is not punished.
        double bank = cfg().getDouble("checks.timer.bank-ms", 400) + Math.min(p.getPing(), 500);
        s.timerBalance = Math.max(-bank, s.timerBalance + 50.0 - (nowMs - last));
        double limit = cfg().getDouble("checks.timer.threshold-ms", 80);
        if (s.timerBalance > limit) {
            if (d.bump("timer", 1, cfg().getDouble("checks.timer.buffer", 3))) {
                plugin.flag(p, "timer", "packet", String.format(Locale.US,
                        "movement packets running %.0fms ahead of real time (limit %.0fms)", s.timerBalance, limit));
                s.timerBalance = limit * 0.5;
                return true;
            }
        } else d.relax("timer", 0.25);
        return false;
    }

    // ================================================================= anti-knockback
    private void antiKnockback(Player p, PlayerData d, MoveState s, PlayerMoveEvent e, Location to,
                               double dx, double dy, double dz, int tick) {
        s.kbEvents++;
        s.kbProj += dx * s.kbDirX + dz * s.kbDirZ;
        s.kbMaxDy = Math.max(s.kbMaxDy, dy);
        if (s.kbEvents < cfg().getInt("checks.antiknockback.window-events", 5) && tick - s.kbTick <= 12) return;
        s.kbActive = false;

        World w = to.getWorld();
        BoundingBox box = Physics.playerBox(to.getX(), to.getY(), to.getZ());

        // Vertical: a grounded player that was knocked up MUST lift off; nothing a client can do reduces that, except a ceiling.
        if (s.kbVy >= 0.25 && s.kbGround && s.kbMaxDy < s.kbVy * 0.5 && !Physics.collides(w, box.clone().shift(0, 0.5, 0))) {
            plugin.flag(p, "antiknockback", "combat", String.format(Locale.US,
                    "server velocity y=%.2f but the player rose at most %.2f", s.kbVy, s.kbMaxDy));
            return;
        }
        // Horizontal: in the air a player can barely steer (0.02-0.026/tick), so most of the push must show up.
        if (s.kbH0 >= 0.2 && !s.kbGround) {
            double expected = s.kbH0 * (1 - Math.pow(Formulas.AIR_FRICTION, s.kbEvents)) / (1 - Formulas.AIR_FRICTION);
            boolean wall = Physics.collides(w, box.clone().shift(s.kbDirX * 0.6, 0, s.kbDirZ * 0.6));
            if (!wall && s.kbProj < expected * 0.5) {
                plugin.flag(p, "antiknockback", "combat", String.format(Locale.US,
                        "airborne knockback %.2f b/tick: moved only %.2f of the expected %.2f blocks", s.kbH0, s.kbProj, expected));
                // enforce: move the player to where the knockback would have put them, if that spot is free
                double missing = (expected - s.kbProj) * 0.8;
                Location target = to.clone().add(s.kbDirX * missing, 0, s.kbDirZ * missing);
                if (!Physics.collides(w, Physics.playerBox(target.getX(), target.getY(), target.getZ()))) e.setTo(target);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent e) {
        Player p = e.getPlayer();
        MoveState s = engine().data(p).move;
        int tick = Bukkit.getCurrentTick();
        Vector v = e.getVelocity();
        double hv = Math.hypot(v.getX(), v.getZ());
        s.kbH = Math.max(s.kbH, hv + 0.05);
        s.kbV = Math.max(s.kbV, Math.max(0, v.getY()) + 0.05);
        s.kbUntilTick = tick + 20;
        if (!Physics.survival(p) || p.isGliding() || p.isInsideVehicle() || p.isInWater()) return;
        if (hv >= 0.2 || v.getY() >= 0.25) {
            Location l = p.getLocation();
            s.kbActive = true; s.kbTick = tick; s.kbEvents = 0; s.kbProj = 0; s.kbMaxDy = -9;
            s.kbH0 = hv; s.kbVy = v.getY();
            s.kbDirX = hv > 1e-6 ? v.getX() / hv : 0;
            s.kbDirZ = hv > 1e-6 ? v.getZ() / hv : 0;
            s.kbGround = Physics.onGround(l.getWorld(), l.getX(), l.getY(), l.getZ());
        }
    }

    // ================================================================= per-tick sampler
    /** Runs every server tick. Catches what never produces a move event (exact hovering) and tracks fall height for the mace check. */
    public void tick() {
        int tick = Bukkit.getCurrentTick();
        for (Player p : Bukkit.getOnlinePlayers()) {
            PlayerData d = engine().data(p);
            MoveState s = d.move;
            Location l = p.getLocation();
            s.addSample(tick, l.getX(), l.getY(), l.getZ());
            World w = l.getWorld();

            boolean special = Physics.touchesSpecial(w, l.getX(), l.getY(), l.getZ());
            if (special) d.grace(tick, 20);
            if (movementExempt(p, d, tick) || p.isInWater() || tick <= s.kbUntilTick) {
                s.airTicks = 0; s.groundY = l.getY(); s.maxY = l.getY(); s.lastGround = l;
                continue;
            }
            if (Physics.onGround(w, l.getX(), l.getY(), l.getZ()) || Physics.entitySupport(w, l.getX(), l.getY(), l.getZ())) {
                s.airTicks = 0; s.groundY = l.getY(); s.maxY = l.getY(); s.lastGround = l.clone();
                continue;
            }
            s.airTicks++;
            s.maxY = Math.max(s.maxY, l.getY());
            // A flat jump lasts 12 ticks. Past that the player must be falling; hovering keeps y near the takeoff height.
            int limit = cfg().getInt("checks.fly.max-air-ticks", 16) + 8 * Physics.effectLevel(p, PotionEffectType.JUMP_BOOST);
            if (s.airTicks >= limit && l.getY() > s.groundY - cfg().getDouble("checks.fly.min-descent", 1.0)) {
                plugin.flag(p, "fly", "movement", "airborne " + s.airTicks + " ticks without descending (flat jump is 12)");
                if (s.lastGround != null) p.teleport(s.lastGround);
                s.airTicks = 0;
                s.resetPrediction();
            }
        }
    }

    // ================================================================= invalid actions
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent e) {
        Player p = e.getPlayer();
        // Vanilla clients cannot start sprinting at 6 or fewer food points (3 shanks) or while blind.
        if (e.isSprinting() && Physics.survival(p) && (p.getFoodLevel() <= 6 || p.hasPotionEffect(PotionEffectType.BLINDNESS))) {
            plugin.flag(p, "invalid-action", "protocol", "started sprinting with food " + p.getFoodLevel()
                    + (p.hasPotionEffect(PotionEffectType.BLINDNESS) ? " and blindness" : ""));
            e.setCancelled(true);
        }
    }

    // ================================================================= grace / lifecycle
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) { grace(e.getPlayer(), "teleport-grace-ticks", 20); engine().data(e.getPlayer()).move.resetAll(); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { grace(e.getPlayer(), "join-grace-ticks", 40); engine().data(e.getPlayer()).move.resetAll(); }
    @EventHandler public void onGameMode(PlayerGameModeChangeEvent e) { grace(e.getPlayer(), "teleport-grace-ticks", 20); }
    @EventHandler public void onToggleFlight(PlayerToggleFlightEvent e) { grace(e.getPlayer(), "teleport-grace-ticks", 20); }
    @EventHandler public void onRiptide(PlayerRiptideEvent e) { grace(e.getPlayer(), "riptide-grace-ticks", 60); }
    @EventHandler public void onGlide(EntityToggleGlideEvent e) { if (e.getEntity() instanceof Player p) grace(p, "riptide-grace-ticks", 60); }
    @EventHandler public void onVehicleExit(VehicleExitEvent e) { if (e.getExited() instanceof Player p) grace(p, "teleport-grace-ticks", 20); }

    @EventHandler
    public void onExplosionDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && (e.getCause() == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || e.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION))
            engine().data(p).move.allowLaunch(Bukkit.getCurrentTick(), 2.0, 2.0);
    }

    /** Wind charges launch players without damage; widen the movement bounds for everyone near the burst. */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!e.getEntity().getType().name().contains("WIND_CHARGE")) return;
        Location at = e.getEntity().getLocation();
        for (Player p : at.getWorld().getNearbyPlayers(at, 6))
            engine().data(p).move.allowLaunch(Bukkit.getCurrentTick(), 1.8, 1.8);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        grace(p, "join-grace-ticks", 40);
        if (p.isOp()) plugin.setAlerts(p.getUniqueId(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        engine().remove(e.getPlayer().getUniqueId());
        plugin.forget(e.getPlayer().getUniqueId());
    }
}

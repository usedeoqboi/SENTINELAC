package dev.codex.sentinel.check;

import dev.codex.sentinel.SentinelPlugin;
import dev.codex.sentinel.data.MoveState;
import dev.codex.sentinel.data.PlayerData;
import dev.codex.sentinel.engine.DetectionEngine;
import dev.codex.sentinel.physics.Formulas;
import dev.codex.sentinel.physics.Physics;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Combat family: reach, kill-aura (aim, through-wall, multi-target, attacking while using an item), mace kill and spear kill.
 * Runs at HIGH priority; a flagged hit is cancelled so it does no damage.
 */
public final class CombatChecks implements Listener {
    private record PendingAttack(UUID target, int tick, float cooldown, float cooldownPeriod) { }
    private record TriggerSample(int tick, double distance, float cooldown, float cooldownPeriod,
                                 boolean exactRay, String weapon) { }

    private final SentinelPlugin plugin;
    private final Map<UUID, ArrayDeque<Boolean>> preciseAimHits = new HashMap<>();
    private final Map<UUID, Long> lastAimNotice = new HashMap<>();
    private final Map<UUID, PendingAttack> pendingAttacks = new HashMap<>();
    private final Map<UUID, ArrayDeque<TriggerSample>> triggerSamples = new HashMap<>();
    private final Map<UUID, Long> lastTriggerNotice = new HashMap<>();
    public CombatChecks(SentinelPlugin plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private DetectionEngine engine() { return plugin.engine(); }

    private static Vector nearestPoint(Vector from, BoundingBox b) {
        return new Vector(
                Math.max(b.getMinX(), Math.min(from.getX(), b.getMaxX())),
                Math.max(b.getMinY(), Math.min(from.getY(), b.getMaxY())),
                Math.max(b.getMinZ(), Math.min(from.getZ(), b.getMaxZ())));
    }

    /** Closest approach (blocks) between the look ray and a hitbox; 0 means the ray passes through it. Sampled every 0.1 blocks. */
    static double rayMiss(Vector origin, Vector dir, BoundingBox box, double maxDist) {
        double best = Double.MAX_VALUE;
        for (double t = 0; t <= maxDist; t += 0.1) {
            double x = origin.getX() + dir.getX() * t, y = origin.getY() + dir.getY() * t, z = origin.getZ() + dir.getZ() * t;
            double cx = Math.max(box.getMinX(), Math.min(x, box.getMaxX()));
            double cy = Math.max(box.getMinY(), Math.min(y, box.getMaxY()));
            double cz = Math.max(box.getMinZ(), Math.min(z, box.getMaxZ()));
            best = Math.min(best, Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz)));
            if (best == 0) break;
        }
        return best;
    }

    private static boolean isChargeItem(ItemStack it) {
        String n = it.getType().name();
        return n.endsWith("_SPEAR") || n.equals("TRIDENT");
    }

    /** Capture attack timing before Paper runs the damage logic and resets the attack ticker. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack() || !(event.getAttacked() instanceof Player)) return;
        Player attacker = event.getPlayer();
        if (!Physics.survival(attacker)) return;
        pendingAttacks.put(attacker.getUniqueId(), new PendingAttack(
                event.getAttacked().getUniqueId(), Bukkit.getCurrentTick(),
                attacker.getAttackCooldown(), attacker.getCooldownPeriod()));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent e) {
        // Sweeps, thorns and other secondary damage legitimately reach beyond normal range.
        if (!(e.getDamager() instanceof Player p) || e.getCause() != DamageCause.ENTITY_ATTACK) return;
        if (!Physics.survival(p)) return;

        FileConfiguration c = cfg();
        PlayerData d = engine().data(p);
        MoveState s = d.move;
        Entity target = e.getEntity();
        int tick = Bukkit.getCurrentTick();
        PendingAttack pending = pendingAttacks.remove(p.getUniqueId());
        boolean hasPreAttackSample = pending != null && pending.tick() == tick
                && pending.target().equals(target.getUniqueId());
        int ping = Math.min(p.getPing(), 400);
        ItemStack hand = p.getInventory().getItemInMainHand();
        String item = hand.getType().name();
        boolean spear = item.endsWith("_SPEAR");
        boolean cancel = false;

        // ---- impossible states: a vanilla client discards attack clicks while eating/drinking/drawing a bow ----
        if (p.isDead() || p.isSleeping()) {
            plugin.flag(p, "killaura", "combat", "attacked while " + (p.isDead() ? "dead" : "asleep"));
            cancel = true;
        } else if (p.isHandRaised() && !isChargeItem(p.getActiveItem())) {
            if (d.bump("ka-using", 1, 2)) {
                plugin.flag(p, "killaura", "combat", "attacked while using " + p.getActiveItem().getType().name().toLowerCase(Locale.ROOT));
                cancel = true;
            }
        }

        // ---- reach: eye to nearest point of the hitbox, against the player's real interaction range ----
        Location eye = p.getEyeLocation();
        Vector eyeV = eye.toVector();
        BoundingBox box = target.getBoundingBox();
        double dist = nearestPoint(eyeV, box).distance(eyeV);
        double range = spear ? 4.5 + 0.125 : Physics.attr(p, Attribute.ENTITY_INTERACTION_RANGE, 3.0);
        double allowed = range + c.getDouble("checks.reach.base-tolerance", 0.4) + c.getDouble("checks.reach.per-ms-tolerance", 0.006) * ping;
        if (dist > allowed) {
            // Obvious = far beyond the lag allowance: flagged at once. Borderline overshoots need a repeat (buffer). Spears keep the old rule.
            boolean obvious = !spear && dist > allowed + c.getDouble("checks.reach.obvious-extra", 1.0);
            if (obvious || d.bump("reach", 1, 2)) {
                plugin.flag(p, "reach", "combat", String.format(Locale.US, "%.2f blocks to hitbox, allowed %.2f (range %.2f, ping %dms%s)",
                        dist, allowed, range, ping, obvious ? ", obvious" : ""));
                cancel = true;
            }
        } else d.relax("reach", 0.25);

        // ---- aim: the crosshair ray must pass through the (lag-expanded) hitbox ----
        double expand = c.getDouble("checks.killaura.base-expand", 0.3) + c.getDouble("checks.killaura.per-ms-expand", 0.006) * ping;
        Vector dir = eye.getDirection();
        if (target instanceof Player) recordAimSample(p, target, eyeV, dir);

        // A client-side enlarged hitbox can make the client send an attack while its view ray
        // misses the server's real player box. Allow capped target-motion compensation for latency.
        if (target instanceof Player victim) {
            if (spear) {
                double targetBps = victim.getVelocity().length() * 20.0;
                double latencyExpansion = c.getDouble("checks.hitbox.base-lag-expansion", 0.12)
                        + Math.min(c.getDouble("checks.hitbox.max-lag-expansion", 0.65), targetBps * ping / 1000.0);
                RayTraceResult serverBoxRay = box.clone().expand(latencyExpansion)
                        .rayTrace(eyeV, dir, allowed + 1.0);
                if (serverBoxRay == null) {
                    if (d.bump("hitbox", 1, c.getDouble("checks.hitbox.buffer", 3))) {
                        plugin.flag(p, "hitbox", "combat", String.format(Locale.US,
                                "PvP attack ray missed the server hitbox (ping %dms, allowance %.2f blocks)",
                                ping, latencyExpansion));
                        cancel = true;
                    }
                } else {
                    d.relax("hitbox", 0.5);
                }
            } else {
                cancel |= hitbox(p, d, victim, eyeV, dir, box, allowed, ping);
            }
        }

        RayTraceResult ray = box.clone().expand(expand).rayTrace(eyeV, dir, allowed + 1.0);
        RayTraceResult exactServerRay = box.rayTrace(eyeV, dir, allowed + 1.0);
        if (ray == null) {
            if ((spear || !(target instanceof Player)) && d.bump("ka-aim", 1, c.getDouble("checks.killaura.aim-buffer", 3))) {
                plugin.flag(p, "killaura", "combat", String.format(Locale.US, "hit a target that is not under the crosshair (hitbox expanded %.2f for %dms ping)", expand, ping));
                cancel = true;
            }
        } else {
            d.relax("ka-aim", 0.25);
            // ---- through walls: vanilla stops the crosshair ray at the first block ----
            double hitDist = ray.getHitPosition().distance(eyeV);
            if (hitDist > 0.6) {
                RayTraceResult wall = eye.getWorld().rayTraceBlocks(eye, dir, hitDist - 0.25, FluidCollisionMode.NEVER, true);
                if (wall != null && wall.getHitBlock() != null && d.bump("ka-wall", 1, 2)) {
                    plugin.flag(p, "killaura", "combat", "hit through " + wall.getHitBlock().getType().name().toLowerCase(Locale.ROOT));
                    cancel = true;
                }
            }
        }

        // ---- multi-aura: one swing cannot hit two different entities in the same tick (spears pierce on purpose) ----
        if (!spear) {
            if (s.attackTick == tick) {
                if (!s.attackTargets.contains(target.getUniqueId()) && !s.attackTargets.isEmpty()) {
                    plugin.flag(p, "killaura", "combat", "hit " + (s.attackTargets.size() + 1) + " different entities in one tick");
                    cancel = true;
                }
            } else { s.attackTick = tick; s.attackTargets.clear(); }
            s.attackTargets.add(target.getUniqueId());
        }

        // ---- mace / spear: damage is derived from motion, so verify it against server-observed motion ----
        if (hand.getType() == Material.MACE && !cancel) cancel = maceKill(p, d, s, e, hand, tick);
        if (spear && !cancel) cancel = spearKill(p, s, e, hand, item, target, tick);
        if (!cancel) cancel = criticals(p, d, s, e, hand);

        // Triggerbot is a pattern alert only: exact range and timing patterns can still be produced by skilled players.
        if (!cancel && target instanceof Player && hasPreAttackSample) {
            recordTriggerSample(p, dist, pending.cooldown(), pending.cooldownPeriod(),
                    exactServerRay != null, item, tick);
        }

        if (cancel) e.setCancelled(true);
    }

    /**
     * Hitbox: the look ray must pass through the target player's hitbox, widened for the target's movement during the attacker's
     * ping. Only an OBVIOUS miss (far outside that allowance) flags at once; smaller misses must repeat (buffer). A client that
     * enlarges hitboxes lands hits whose ray passes well beside the real box, so it falls into the obvious range.
     */
    private boolean hitbox(Player p, PlayerData d, Player victim, Vector eyeV, Vector dir, BoundingBox box, double allowed, int ping) {
        FileConfiguration c = cfg();
        // A player target can always be running at sprint speed, even when the server-side velocity reads zero.
        double targetBps = Math.max(victim.getVelocity().length() * 20.0, c.getDouble("checks.hitbox.assumed-target-bps", 5.6));
        double lag = c.getDouble("checks.hitbox.base-lag-expansion", 0.12)
                + Math.min(c.getDouble("checks.hitbox.max-lag-expansion", 0.65), targetBps * ping / 1000.0);
        double allowance = 0.1 + lag;                                    // 0.1 = client hitbox pick margin
        double miss = rayMiss(eyeV, dir, box, allowed + 1.0);
        if (miss <= allowance) { d.relax("hitbox", 0.5); return false; }
        boolean obvious = miss > allowance + c.getDouble("checks.hitbox.obvious-extra", 0.75);
        if (obvious || d.bump("hitbox", 1, c.getDouble("checks.hitbox.buffer", 3))) {
            plugin.flag(p, "hitbox", "combat", String.format(Locale.US,
                    "attack ray passed %.2f blocks from the target hitbox (allowed %.2f at %dms ping%s)",
                    miss, allowance, ping, obvious ? ", obvious" : ""));
            return true;
        }
        return false;
    }

    /** Cancels repeated server-marked critical hits only when independent server state shows stable ground contact. */
    private boolean criticals(Player player, PlayerData data, MoveState move, EntityDamageByEntityEvent event, ItemStack weapon) {
        if (player.isInsideVehicle() || player.isInWater() || player.isClimbing()
                || player.isGliding() || player.isRiptiding()
                || weapon.getType() == Material.MACE || isChargeItem(weapon)) return false;

        Location location = player.getLocation();
        boolean physicallyGrounded = Physics.onGround(player.getWorld(),
                location.getX(), location.getY(), location.getZ())
                || Physics.entitySupport(player.getWorld(), location.getX(), location.getY(), location.getZ());
        boolean serverObservedFalling = move.airTicks > 0
                && move.maxY > location.getY() + 0.01
                && move.prevDy < 0;
        boolean stableGrounded = physicallyGrounded
                && move.airTicks == 0
                && move.prevDy >= -0.01
                && player.getVelocity().getY() >= -0.01
                && player.getFallDistance() <= 0.0f
                && !serverObservedFalling;

        // Paper marks whether this melee damage was actually critical; damage totals are affected by armor/plugins.
        if (!event.isCritical() || !stableGrounded) {
            data.relax("criticals", 0.5);
            return false;
        }
        if (data.bump("criticals", 1, cfg().getDouble("checks.criticals.buffer", 3))) {
            plugin.flag(player, "criticals", "combat", String.format(Locale.US,
                    "Paper marked a critical while server state showed stable ground contact (airTicks=%d, fallDistance=%.2f, verticalSpeed=%.3f)",
                    move.airTicks, player.getFallDistance(), player.getVelocity().getY()));
            return true;
        }
        return false;
    }

    /** Looks for repeated near-three-block hits at full cooldown and a nearly mechanical attack cadence. */
    private void recordTriggerSample(Player attacker, double distance, float cooldown, float cooldownPeriod,
                                     boolean exactRay, String weapon, int tick) {
        FileConfiguration c = cfg();
        String path = "checks.triggerbot.";
        int sampleCount = Math.max(6, c.getInt(path + "sample-count", 12));
        int windowTicks = Math.max(sampleCount, c.getInt(path + "sample-window-ticks", 300));
        ArrayDeque<TriggerSample> samples = triggerSamples.computeIfAbsent(attacker.getUniqueId(), ignored -> new ArrayDeque<>());
        while (!samples.isEmpty() && tick - samples.peekFirst().tick() > windowTicks) samples.removeFirst();
        samples.addLast(new TriggerSample(tick, distance, cooldown, cooldownPeriod, exactRay, weapon));
        while (samples.size() > sampleCount) samples.removeFirst();
        if (samples.size() < sampleCount) return;

        double minDistance = c.getDouble(path + "minimum-distance", 2.85);
        double maxDistance = c.getDouble(path + "maximum-distance", 3.15);
        double minCooldown = c.getDouble(path + "minimum-attack-cooldown", 0.99);
        double timingTolerance = c.getDouble(path + "timing-tolerance-ticks", 1.0);
        int minimumPreciseHits = Math.min(sampleCount,
                Math.max(1, c.getInt(path + "minimum-precise-hits", 10)));
        int minimumPreciseIntervals = Math.min(sampleCount - 1,
                Math.max(1, c.getInt(path + "minimum-precise-intervals", 9)));

        int preciseHits = 0;
        int preciseIntervals = 0;
        TriggerSample previous = null;
        for (TriggerSample sample : samples) {
            boolean precise = sample.exactRay()
                    && sample.distance() >= minDistance && sample.distance() <= maxDistance
                    && sample.cooldown() >= minCooldown;
            if (precise) preciseHits++;
            if (previous != null) {
                boolean previousPrecise = previous.exactRay()
                        && previous.distance() >= minDistance && previous.distance() <= maxDistance
                        && previous.cooldown() >= minCooldown;
                double expectedTicks = (sample.cooldownPeriod() + previous.cooldownPeriod()) / 2.0;
                if (precise && previousPrecise && sample.weapon().equals(previous.weapon())
                        && Math.abs((sample.tick() - previous.tick()) - expectedTicks) <= timingTolerance) {
                    preciseIntervals++;
                }
            }
            previous = sample;
        }

        if (preciseHits < minimumPreciseHits || preciseIntervals < minimumPreciseIntervals) return;
        long now = System.currentTimeMillis();
        UUID id = attacker.getUniqueId();
        if (now - lastTriggerNotice.getOrDefault(id, 0L) < 60_000L) return;
        lastTriggerNotice.put(id, now);
        String text = String.format(Locale.US,
                "[SentinelAC] Possible triggerbot pattern: %s had %d/%d precise PvP hits at %.2f-%.2f blocks with full cooldown and %d regular attack intervals. Review manually; this is not proof.",
                attacker.getName(), preciseHits, sampleCount, minDistance, maxDistance, preciseIntervals);
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.isOp()) viewer.sendMessage(net.kyori.adventure.text.Component.text(text));
        }
    }

    /** Emits a suspicion notice only; repeated accurate hits alone never cancel attacks or punish players. */
    private void recordAimSample(Player attacker, Entity target, Vector eye, Vector direction) {
        Vector toTarget = target.getBoundingBox().getCenter().subtract(eye);
        if (toTarget.lengthSquared() < 1.0e-8) return;
        double errorDegrees = Math.toDegrees(direction.angle(toTarget.normalize()));
        UUID id = attacker.getUniqueId();
        ArrayDeque<Boolean> samples = preciseAimHits.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        samples.addLast(errorDegrees <= 0.45);
        while (samples.size() > 12) samples.removeFirst();
        if (samples.size() < 12) return;
        int precise = 0;
        for (boolean hit : samples) if (hit) precise++;
        long now = System.currentTimeMillis();
        if (precise >= 10 && now - lastAimNotice.getOrDefault(id, 0L) >= 60_000L) {
            lastAimNotice.put(id, now);
            String text = "[SentinelAC] Possible aim assist: " + attacker.getName()
                    + " had " + precise + "/12 PvP hits within 0.45 degrees of target center.";
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (viewer.isOp()) viewer.sendMessage(net.kyori.adventure.text.Component.text(text));
            }
        }
    }

    @EventHandler
    public void onCombatQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        preciseAimHits.remove(id);
        lastAimNotice.remove(id);
        pendingAttacks.remove(id);
        triggerSamples.remove(id);
        lastTriggerNotice.remove(id);
    }

    /** Mace smash damage grows with fall distance. Compare the server's fall distance with the fall the server actually watched. */
    private boolean maceKill(Player p, PlayerData d, MoveState s, EntityDamageByEntityEvent e, ItemStack hand, int tick) {
        double observed = Math.max(0, s.maxY - p.getLocation().getY());
        double serverFall = p.getFallDistance();
        double tolerance = cfg().getDouble("checks.macekill.fall-tolerance", 1.5);
        double maxDamage = Formulas.maceMaxDamage(observed, hand.getEnchantmentLevel(Enchantment.DENSITY));
        boolean spoofedFall = serverFall > observed + tolerance;
        boolean spoofedDamage = e.getDamage() > maxDamage;
        if (spoofedFall || spoofedDamage) {
            plugin.flag(p, "macekill", "combat", String.format(Locale.US,
                    "mace hit for %.1f (max %.1f) with fall distance %.1f but only %.1f blocks observed", e.getDamage(), maxDamage, serverFall, observed));
            p.setFallDistance((float) observed);
            return true;
        }
        if (serverFall > 1.5) d.move.allowLaunch(tick, 1.8, 1.8); // Wind Burst relaunches the attacker after a smash
        return false;
    }

    /** Spear charge damage is tier multiplier x closing speed. Compare it with the fastest the player can legitimately be moving. */
    private boolean spearKill(Player p, MoveState s, EntityDamageByEntityEvent e, ItemStack hand, String item, Entity target, int tick) {
        double own;
        Entity vehicle = p.getVehicle();
        if (vehicle instanceof LivingEntity mount) own = Physics.attr(mount, Attribute.MOVEMENT_SPEED, 0.3) * Formulas.BPS_PER_ATTRIBUTE * 1.5;
        else if (vehicle instanceof Boat) own = 74;
        else if (vehicle instanceof Minecart) own = 12;
        else {
            double cap = Physics.attr(p, Attribute.MOVEMENT_SPEED, 0.1) * Formulas.BPS_PER_ATTRIBUTE * 1.1;
            if (p.isGliding()) cap = Math.max(cap, 90);
            if (hasLunge(hand)) cap = Math.max(cap, Formulas.LUNGE_III_BPS * 1.1);
            if (!p.isOnGround()) cap = Math.max(cap, 80);                        // falling can legitimately reach 78.4 b/s
            own = Math.min(cap, s.peakBps(tick) + 1.0);                           // never more than what the server watched
        }
        double targetBps = Math.min(target.getVelocity().length() * 20.0, 40.0);
        double allowed = Formulas.spearMaxDamage(item, own, targetBps, cfg().getDouble("checks.spearkill.damage-margin", 8));
        if (e.getDamage() > allowed) {
            plugin.flag(p, "spearkill", "combat", String.format(Locale.US,
                    "spear hit for %.1f but %.1f b/s own + %.1f b/s target allows %.1f", e.getDamage(), own, targetBps, allowed));
            return true;
        }
        return false;
    }

    private static boolean hasLunge(ItemStack it) {
        return it.getEnchantments().keySet().stream().anyMatch(en -> en.getKey().getKey().equals("lunge"));
    }
}


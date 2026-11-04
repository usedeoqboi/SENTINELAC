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
    private final SentinelPlugin plugin;
    private final Map<UUID, ArrayDeque<Boolean>> preciseAimHits = new HashMap<>();
    private final Map<UUID, Long> lastAimNotice = new HashMap<>();
    public CombatChecks(SentinelPlugin plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private DetectionEngine engine() { return plugin.engine(); }

    private static Vector nearestPoint(Vector from, BoundingBox b) {
        return new Vector(
                Math.max(b.getMinX(), Math.min(from.getX(), b.getMaxX())),
                Math.max(b.getMinY(), Math.min(from.getY(), b.getMaxY())),
                Math.max(b.getMinZ(), Math.min(from.getZ(), b.getMaxZ())));
    }

    private static boolean isChargeItem(ItemStack it) {
        String n = it.getType().name();
        return n.endsWith("_SPEAR") || n.equals("TRIDENT");
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
            if (d.bump("reach", 1, 2)) {
                plugin.flag(p, "reach", "combat", String.format(Locale.US, "%.2f blocks to hitbox, allowed %.2f (range %.2f, ping %dms)", dist, allowed, range, ping));
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
        }

        RayTraceResult ray = box.clone().expand(expand).rayTrace(eyeV, dir, allowed + 1.0);
        if (ray == null) {
            if (d.bump("ka-aim", 1, c.getDouble("checks.killaura.aim-buffer", 3))) {
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

        if (cancel) e.setCancelled(true);
    }

    /** Detects impossible critical bonus damage while server-side ground state says the attacker is grounded. */
    private boolean criticals(Player player, PlayerData data, MoveState move, EntityDamageByEntityEvent event, ItemStack weapon) {
        if (player.isInsideVehicle() || player.isInWater() || player.isClimbing()
                || player.isGliding() || player.isRiptiding()
                || weapon.getType() == Material.MACE || isChargeItem(weapon)) return false;

        Location location = player.getLocation();
        boolean physicallyGrounded = Physics.onGround(player.getWorld(),
                location.getX(), location.getY(), location.getZ());
        boolean serverObservedFalling = move.airTicks > 0
                && move.maxY > location.getY() + 0.01
                && move.prevDy < 0;
        boolean validFallingCrit = !physicallyGrounded
                && serverObservedFalling
                && player.getFallDistance() > 0.0f
                && player.getVelocity().getY() < -0.08;

        double normalDamage = Physics.attr(player, Attribute.ATTACK_DAMAGE, 1.0);
        int sharpness = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharpness > 0) normalDamage += 0.5 * sharpness + 0.5;
        int strength = Physics.effectLevel(player, PotionEffectType.STRENGTH);
        if (strength > 0) normalDamage += 3.0 * strength;

        // Extra headroom prevents ordinary rounding and attribute variation from being called a critical.
        double groundedMaximum = normalDamage * 1.35 + 0.25;
        if (validFallingCrit || event.getDamage() <= groundedMaximum) {
            data.relax("criticals", 0.5);
            return false;
        }
        if (data.bump("criticals", 1, cfg().getDouble("checks.criticals.buffer", 3))) {
            plugin.flag(player, "criticals", "combat", String.format(Locale.US,
                    "grounded hit dealt %.2f damage; conservative non-critical ceiling %.2f",
                    event.getDamage(), groundedMaximum));
            return true;
        }
        return false;
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

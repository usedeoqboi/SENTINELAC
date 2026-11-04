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
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

import java.util.List;
import java.util.Locale;

/** World-interaction family: scaffold (block placement) and vehicle checks (boat fly, boat speed, minecart and mount speed). */
public final class WorldChecks implements Listener {
    private final SentinelPlugin plugin;
    public WorldChecks(SentinelPlugin plugin) { this.plugin = plugin; }

    private FileConfiguration cfg() { return plugin.getConfig(); }
    private DetectionEngine engine() { return plugin.engine(); }

    // ================================================================= scaffold
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (!Physics.survival(p)) return;
        FileConfiguration c = cfg();
        PlayerData d = engine().data(p);
        MoveState s = d.move;
        int tick = Bukkit.getCurrentTick();
        boolean cancel = false;

        // rate: per second and per tick
        int perSecond = d.countPlacement(PlayerData.nowMillis());
        int max = c.getInt("checks.scaffold.max-per-second", 20);
        if (perSecond > max) {
            plugin.flag(p, "scaffold", "interaction", "placed " + perSecond + " blocks in one second (limit " + max + ")");
            cancel = true;
        }
        if (s.placeTick == tick) s.placeCount++; else { s.placeTick = tick; s.placeCount = 1; }
        if (s.placeCount > c.getInt("checks.scaffold.max-per-tick", 4)) {
            plugin.flag(p, "scaffold", "interaction", s.placeCount + " blocks placed in a single tick");
            cancel = true;
        }

        // geometry: a vanilla client can only place against the block under its crosshair, within block reach
        Block placed = e.getBlockPlaced(), against = e.getBlockAgainst();
        if (!placed.equals(against) && !against.isLiquid()) {
            Location eye = p.getEyeLocation();
            double reach = Physics.attr(p, Attribute.BLOCK_INTERACTION_RANGE, 4.5) + c.getDouble("checks.scaffold.reach-tolerance", 0.8);
            BoundingBox ab = against.getBoundingBox();
            double nx = Math.max(ab.getMinX(), Math.min(eye.getX(), ab.getMaxX()));
            double ny = Math.max(ab.getMinY(), Math.min(eye.getY(), ab.getMaxY()));
            double nz = Math.max(ab.getMinZ(), Math.min(eye.getZ(), ab.getMaxZ()));
            double dist = Math.sqrt(Math.pow(eye.getX() - nx, 2) + Math.pow(eye.getY() - ny, 2) + Math.pow(eye.getZ() - nz, 2));
            if (dist > reach) {
                plugin.flag(p, "scaffold", "interaction", String.format(Locale.US, "placed against a block %.2f away (reach %.2f)", dist, reach));
                cancel = true;
            }
            RayTraceResult r = p.rayTraceBlocks(reach + 1.0, FluidCollisionMode.NEVER);
            boolean looking = r != null && r.getHitBlock() != null && (r.getHitBlock().equals(against) || r.getHitBlock().equals(placed));
            if (!looking) looking = ab.clone().expand(0.2).rayTrace(eye.toVector(), eye.getDirection(), reach + 1.0) != null;
            if (!looking) {
                if (d.bump("scaffold-aim", 1, c.getDouble("checks.scaffold.aim-buffer", 2))) {
                    plugin.flag(p, "scaffold", "interaction", "placed against a block the player was not looking at");
                    cancel = true;
                }
            } else d.relax("scaffold-aim", 0.25);
        }
        if (cancel) { e.setCancelled(true); e.setBuild(false); }
    }

    // ================================================================= vehicles
    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleMove(VehicleMoveEvent e) {
        Vehicle v = e.getVehicle();
        List<Entity> riders = v.getPassengers();
        if (riders.isEmpty() || !(riders.get(0) instanceof Player p) || !Physics.survival(p)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getWorld() != to.getWorld()) return;
        PlayerData d = engine().data(p);
        MoveState s = d.move;
        int tick = Bukkit.getCurrentTick();
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double h = Math.hypot(dx, dz);
        World w = to.getWorld();

        if (v instanceof Boat boat) {
            // speed: 8 b/s on water, 40 on ice, 72.73 on blue ice; momentum carries for 3 seconds after leaving ice
            double slip = Formulas.slipperiness(w.getBlockAt(to.getBlockX(), (int) Math.floor(to.getY() - 0.2), to.getBlockZ()).getType().name());
            double surface = slip >= 0.985 ? Formulas.BOAT_BLUE_ICE_PER_TICK : slip >= 0.97 ? Formulas.BOAT_ICE_PER_TICK : Formulas.BOAT_WATER_PER_TICK;
            if (surface > Formulas.BOAT_WATER_PER_TICK) { s.boatCap = surface; s.boatCapTick = tick; }
            double cap = Math.max(surface, tick - s.boatCapTick <= 60 ? s.boatCap : 0) * 1.15 + 0.1;
            if (h > cap && d.bump("boatspeed", 1, cfg().getDouble("checks.boatspeed.buffer", 3))) {
                plugin.flag(p, "boatspeed", "vehicle", String.format(Locale.US, "boat moved %.2f b/tick (%.1f b/s), surface allows %.2f", h, h * 20, cap));
                undo(v, p, from);
                return;
            }

            // fly: a boat out of water and off the ground has no source of lift; it can only fall
            boolean inWater = boat.isInWater();
            if (inWater) s.boatWaterTick = tick;
            boolean air = !inWater && !boat.isOnGround() && tick - s.boatWaterTick > 30 && !Physics.touchesSpecial(w, to.getX(), to.getY(), to.getZ());
            if (air) {
                s.boatAir++;
                boolean rising = s.boatAir >= 3 && dy > 0.03;
                boolean hovering = s.boatAir >= cfg().getInt("checks.boatfly.hover-ticks", 6) && dy > -0.02;
                if (rising || hovering) {
                    plugin.flag(p, "boatfly", "vehicle", String.format(Locale.US, "boat airborne %d ticks, dy %.3f (%s)", s.boatAir, dy, rising ? "rising" : "hovering"));
                    undo(v, p, from);
                    s.boatAir = 0;
                }
            } else s.boatAir = 0;
        } else if (v instanceof Minecart) {
            double cap = cfg().getDouble("checks.vehiclespeed.max-minecart-per-tick", 1.8);
            if (h > cap && d.bump("vehiclespeed", 1, 3)) {
                plugin.flag(p, "vehiclespeed", "vehicle", String.format(Locale.US, "minecart moved %.2f b/tick, vanilla max is 0.4 (cap %.2f)", h, cap));
                undo(v, p, from);
            }
        } else if (v instanceof LivingEntity mount) {
            // horses, camels, striders, pigs: movement_speed attribute 0.1 == 4.317 b/s; allow 2x for gallop/dash/jump
            double cap = Physics.attr(mount, Attribute.MOVEMENT_SPEED, 0.3) * Formulas.BPS_PER_ATTRIBUTE / 20.0 * 2.0 + 0.2;
            if (h > cap && d.bump("vehiclespeed", 1, 3)) {
                plugin.flag(p, "vehiclespeed", "vehicle", String.format(Locale.US, "mount moved %.2f b/tick (%.1f b/s), cap %.2f", h, h * 20, cap));
                undo(v, p, from);
            }
        }
    }

    /** Vehicle moves cannot be cancelled: dismount the rider and put both back where the move started. */
    private void undo(Vehicle v, Player p, Location from) {
        v.eject();
        v.teleport(from);
        p.teleport(from);
    }
}

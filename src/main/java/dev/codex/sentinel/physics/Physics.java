package dev.codex.sentinel.physics;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.Set;

/** Bukkit-side helpers: server-computed ground/collision tests so we never trust the client's onGround flag. */
public final class Physics {
    private static final double HALF_WIDTH = 0.3;
    private static final Set<String> SPECIAL = Set.of("COBWEB", "SWEET_BERRY_BUSH", "POWDER_SNOW", "HONEY_BLOCK", "SLIME_BLOCK",
            "LADDER", "SCAFFOLDING", "WATER", "LAVA", "BUBBLE_COLUMN", "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS");

    private Physics() { }

    public static boolean survival(Player p) {
        GameMode g = p.getGameMode();
        return g == GameMode.SURVIVAL || g == GameMode.ADVENTURE;
    }

    public static double attr(LivingEntity e, Attribute a, double def) {
        AttributeInstance i = e.getAttribute(a);
        return i == null ? def : i.getValue();
    }

    /** Effect level (amplifier + 1), 0 when absent. */
    public static int effectLevel(Player p, PotionEffectType t) {
        PotionEffect e = p.getPotionEffect(t);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    private static int floor(double v) { return (int) Math.floor(v); }

    public static BoundingBox playerBox(double x, double y, double z) {
        return new BoundingBox(x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + 1.8, z + HALF_WIDTH);
    }

    /** True when a collision box of any solid block overlaps {@code box}. Unloaded chunks are skipped, never loaded. */
    public static boolean collides(World w, BoundingBox box) {
        int x0 = floor(box.getMinX()), x1 = floor(box.getMaxX());
        int y0 = floor(box.getMinY()), y1 = floor(box.getMaxY());
        int z0 = floor(box.getMinZ()), z1 = floor(box.getMaxZ());
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int y = y0; y <= y1; y++) {
                Block b = w.getBlockAt(x, y, z);
                if (b.isPassable()) continue;
                for (BoundingBox bb : b.getCollisionShape().getBoundingBoxes())
                    if (bb.clone().shift(x, y, z).overlaps(box)) return true;
            }
        }
        return false;
    }

    /** Server-side "standing on something solid" test for feet at (x, y, z). */
    public static boolean onGround(World w, double x, double y, double z) {
        return collides(w, new BoundingBox(x - HALF_WIDTH - 0.001, y - 0.01, z - HALF_WIDTH - 0.001,
                x + HALF_WIDTH + 0.001, y + 0.001, z + HALF_WIDTH + 0.001));
    }

    /** Boats, minecarts and shulkers can be stood on; they are entities, not blocks. */
    public static boolean entitySupport(World w, double x, double y, double z) {
        BoundingBox box = new BoundingBox(x - 0.4, y - 0.6, z - 0.4, x + 0.4, y + 0.1, z + 0.4);
        return !w.getNearbyEntities(box, e -> e instanceof Boat || e instanceof Minecart || e instanceof Shulker).isEmpty();
    }

    public static double slipAt(World w, double x, double y, double z) {
        int bx = floor(x), bz = floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return 0.6;
        return Formulas.slipperiness(w.getBlockAt(bx, floor(y - 0.500001), bz).getType().name());
    }

    /** Blocks that rewrite vanilla movement (webs, honey, slime, beds, vines, water...). Checks that assume plain gravity skip these. */
    public static boolean touchesSpecial(World w, double x, double y, double z) {
        return special(w, x, y, z) || special(w, x, y + 1.0, z) || special(w, x, y - 0.5, z);
    }

    private static boolean special(World w, double x, double y, double z) {
        int bx = floor(x), bz = floor(z);
        if (!w.isChunkLoaded(bx >> 4, bz >> 4)) return false;
        String n = w.getBlockAt(bx, floor(y), bz).getType().name();
        return SPECIAL.contains(n) || n.endsWith("_BED") || n.contains("VINE") || n.contains("DRIPLEAF");
    }
}

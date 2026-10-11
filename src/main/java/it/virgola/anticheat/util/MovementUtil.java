package it.virgola.anticheat.util;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;

import java.util.Locale;

/**
 * Small, shared movement helpers used across multiple checks.
 * Goal: reduce duplicated "material gates" and keep FP-prone rules consistent.
 * Updated for Paper 1.21.x compatibility and improved false-positive prevention.
 */
public final class MovementUtil {

    private MovementUtil() {}
    
    // ------------------------ constants ------------------------
    
    /** Player hitbox width (0.6 blocks) */
    public static final double PLAYER_WIDTH = 0.6;
    /** Player hitbox height standing (1.8 blocks) */
    public static final double PLAYER_HEIGHT = 1.8;
    /** Player hitbox height sneaking (1.5 blocks) */
    public static final double PLAYER_HEIGHT_SNEAKING = 1.5;
    /** Player hitbox height swimming/crawling (0.6 blocks) */
    public static final double PLAYER_HEIGHT_SWIMMING = 0.6;
    
    /** Vanilla gravity acceleration (blocks/tick²) */
    public static final double GRAVITY = 0.08;
    /** Vanilla air drag (multiplier per tick) */
    public static final double AIR_DRAG = 0.98;
    /** Vanilla jump velocity (blocks/tick) */
    public static final double JUMP_VELOCITY = 0.42;
    /** Max vanilla fall speed (terminal velocity) */
    public static final double TERMINAL_VELOCITY = 3.92;

    // ------------------------ safe access ------------------------

    public static Material safeType(Block b) {
        try { return b.getType(); } catch (Throwable t) { return Material.AIR; }
    }

    // ------------------------ material tags ------------------------

    public static boolean isLiquid(Material m) {
        return m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN;
    }

    public static boolean isWater(Material m) {
        return m == Material.WATER || m == Material.BUBBLE_COLUMN;
    }

    public static boolean isClimbable(Material m) {
        if (m == null) return false;
        return m == Material.LADDER
                || m == Material.VINE
                || m == Material.SCAFFOLDING
                || m.name().contains("TWISTING_VINES")
                || m.name().contains("WEEPING_VINES");
    }

    /**
     * Blocks that can heavily affect movement/physics and commonly cause FPs.
     * NOTE: ICE is handled elsewhere for Speed, but we still treat it as "weird" for Fly/Jesus.
     */
    public static boolean isWeirdMovementBlock(Material m) {
        if (m == null) return false;
        return m == Material.COBWEB
                || m == Material.SLIME_BLOCK
                || m == Material.HONEY_BLOCK
                || m.name().contains("ICE")
                || m == Material.SOUL_SAND
                || m == Material.SOUL_SOIL;
    }

    /** Very thin collision supports (player can be "not onGround" for a tick in some edge cases). */
    public static boolean isThinSupport(Material m) {
        if (m == null) return false;
        String n = m.name();
        if (n.endsWith("_CARPET")) return true;
        if (m == Material.MOSS_CARPET) return true;
        if (m == Material.LILY_PAD) return true;
        if (m == Material.SNOW) return true; // snow layer
        if (n.endsWith("_PRESSURE_PLATE")) return true;
        if (n.endsWith("_BUTTON")) return true;
        if (n.endsWith("_RAIL") || n.contains("RAIL")) return true;
        if (m == Material.TRIPWIRE || m == Material.TRIPWIRE_HOOK) return true;
        if (m == Material.REDSTONE_WIRE) return true;
        if (m == Material.STRING) return true;
        if (m == Material.POINTED_DRIPSTONE) return true;
        if (m == Material.PINK_PETALS) return true;
        return false;
    }

    /** Surface-only supports that are commonly on water (so Fly/Jesus must not treat as "floating"). */
    public static boolean isLiquidSurfaceSupport(Material m) {
        return m == Material.LILY_PAD;
    }

    // ------------------------ liquid proximity ------------------------

    /** Checks liquid presence by sampling feet and a few depths below. */
    public static boolean isInLiquidRobust(Location to, double... ySamples) {
        if (to == null) return false;
        for (double ys : ySamples) {
            Material t = safeType(to.clone().add(0, ys, 0).getBlock());
            if (isLiquid(t)) return true;
        }
        return false;
    }

    /**
     * Returns how far player feet are below the top surface of the nearest sampled liquid block.
     * 0 = exactly at surface; + = below surface.
     * If no liquid is found in sample, returns +Inf.
     */
    public static double surfaceDistanceToLiquid(Location to, double... ySamples) {
        if (to == null) return Double.POSITIVE_INFINITY;
        Block liquid = null;
        for (double ys : ySamples) {
            Block b = to.clone().add(0, ys, 0).getBlock();
            if (isLiquid(safeType(b))) { liquid = b; break; }
        }
        if (liquid == null) return Double.POSITIVE_INFINITY;

        double surfaceY = liquid.getY() + 1.0;
        double dist = surfaceY - to.getY();
        if (dist < 0) dist = 0;
        return dist;
    }

    /** Head-air + liquid nearby (more robust than a single-depth sample). */
    public static boolean isFloatingAtSurface(Player p, Location to, double headYOffset, double... liquidSamples) {
        if (p == null || to == null) return false;

        Location head = to.clone().add(0, headYOffset, 0);
        Material headType = safeType(head.getBlock());
        if (headType != Material.AIR && !headType.isAir()) return false;

        return isInLiquidRobust(to, liquidSamples);
    }

    // ------------------------ solid support scanning ------------------------

    public static double supportDistanceToSolid(Location to, double maxEps, int maxDepth) {
        if (to == null) return Double.POSITIVE_INFINITY;
        World w = to.getWorld();
        if (w == null) return Double.POSITIVE_INFINITY;

        double x = to.getX();
        double z = to.getZ();

        double[] xs = { x - 0.30, x + 0.30 };
        double[] zs = { z - 0.30, z + 0.30 };

        double best = Double.POSITIVE_INFINITY;

        for (double sx : xs) {
            for (double sz : zs) {
                int bx = (int) Math.floor(sx);
                int bz = (int) Math.floor(sz);

                double feetY = to.getY();
                int startY = (int) Math.floor(feetY);

                for (int dy = 0; dy <= maxDepth; dy++) {
                    int by = startY - dy;

                    Material m;
                    try {
                        m = w.getBlockAt(bx, by, bz).getType();
                    } catch (Throwable t) {
                        m = Material.AIR;
                    }

                    if (m == Material.AIR) continue;
                    if (isLiquid(m)) continue;
                    if (isPassableSupport(m)) continue;

                    double top = by + 1.0;
                    double dist = feetY - top;
                    if (dist < 0) dist = 0;

                    if (dist < best) best = dist;
                    if (best <= maxEps) return best;

                    break;
                }
            }
        }

        return best;
    }

    public static boolean isPassableSupport(Material m) {
        return m == Material.SEAGRASS
                || m == Material.TALL_SEAGRASS
                || m == Material.KELP
                || m == Material.KELP_PLANT
                || m == Material.WATER
                || m == Material.LAVA
                || m == Material.BUBBLE_COLUMN;
    }

    // ------------------------ entity support ------------------------

    /**
     * Detects if the player is standing on top of another entity (e.g., boat) without being inside it.
     * Uses bounding boxes when available.
     */
    public static boolean isStandingOnEntitySupport(Player p, Location to, double yEps, double xzRadius) {
        if (p == null || to == null) return false;
        World w = to.getWorld();
        if (w == null) return false;

        // Player AABB approximation at "to"
        BoundingBox playerBox = new BoundingBox(
                to.getX() - 0.30, to.getY(), to.getZ() - 0.30,
                to.getX() + 0.30, to.getY() + 1.80, to.getZ() + 0.30
        );

        for (Entity e : w.getNearbyEntities(to, xzRadius, 1.8, xzRadius)) {
            if (e == null) continue;
            if (e == p) continue;
            if (e.isDead()) continue;

            BoundingBox eb;
            try {
                eb = e.getBoundingBox();
            } catch (Throwable t) {
                // fallback rough box
                Location el = e.getLocation();
                eb = new BoundingBox(el.getX() - 0.35, el.getY(), el.getZ() - 0.35, el.getX() + 0.35, el.getY() + 1.2, el.getZ() + 0.35);
            }

            // horizontal overlap
            boolean xzOverlap = eb.getMaxX() > playerBox.getMinX()
                    && eb.getMinX() < playerBox.getMaxX()
                    && eb.getMaxZ() > playerBox.getMinZ()
                    && eb.getMinZ() < playerBox.getMaxZ();

            if (!xzOverlap) continue;

            double feetY = to.getY();
            double topY = eb.getMaxY();

            // feet close to entity top (standing on it)
            if (feetY >= (topY - yEps) && feetY <= (topY + 0.20)) {
                return true;
            }
        }
        return false;
    }
    
    // ------------------------ advanced block checks ------------------------
    
    /**
     * Checks if player is near a block edge (can cause 1-tick airborne glitches).
     * Returns true if player is within edgeThreshold of any block edge.
     */
    public static boolean isNearBlockEdge(Location loc, double edgeThreshold) {
        if (loc == null) return false;
        
        double x = loc.getX();
        double z = loc.getZ();
        
        double fracX = x - Math.floor(x);
        double fracZ = z - Math.floor(z);
        
        // Near any block edge (0 or 1 boundaries)
        return fracX < edgeThreshold || fracX > (1.0 - edgeThreshold)
            || fracZ < edgeThreshold || fracZ > (1.0 - edgeThreshold);
    }
    
    /**
     * Checks if there's a trapdoor nearby that could affect movement.
     * Open trapdoors can be climbed like ladders in certain conditions.
     */
    public static boolean hasNearbyOpenTrapdoor(Location loc, int radius) {
        if (loc == null || loc.getWorld() == null) return false;
        
        int cx = loc.getBlockX();
        int cy = loc.getBlockY();
        int cz = loc.getBlockZ();
        
        for (int x = -radius; x <= radius; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = loc.getWorld().getBlockAt(cx + x, cy + y, cz + z);
                    Material type = safeType(b);
                    if (type.name().contains("TRAPDOOR")) {
                        try {
                            TrapDoor td = (TrapDoor) b.getBlockData();
                            if (td.isOpen()) return true;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Checks if the player is on a slab or stairs (half blocks that affect step/jump).
     */
    public static boolean isOnHalfBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        
        Block under = loc.clone().add(0, -0.1, 0).getBlock();
        Material type = safeType(under);
        
        if (type.name().endsWith("_SLAB")) {
            try {
                Slab slab = (Slab) under.getBlockData();
                return slab.getType() == Slab.Type.BOTTOM || slab.getType() == Slab.Type.TOP;
            } catch (Throwable ignored) {}
        }
        
        return type.name().endsWith("_STAIRS");
    }
    
    /**
     * Checks if player is inside a non-full collision block (fences, walls, etc.).
     * These can cause weird collision/movement behavior.
     */
    public static boolean isInNonFullBlock(Location loc) {
        if (loc == null) return false;
        
        Material feet = safeType(loc.getBlock());
        String name = feet.name();
        
        return name.contains("FENCE") 
            || name.contains("WALL") 
            || name.contains("PANE")
            || name.contains("BARS")
            || name.contains("CHAIN")
            || feet == Material.END_ROD
            || feet == Material.LIGHTNING_ROD;
    }
    
    /**
     * Detects bounce blocks (slime, bed) that can give extra vertical velocity.
     */
    public static boolean isBounceBlock(Material m) {
        if (m == null) return false;
        return m == Material.SLIME_BLOCK 
            || m.name().contains("_BED");
    }
    
    /**
     * Checks if block is a powder snow (causes slow sinking).
     */
    public static boolean isPowderSnow(Material m) {
        return m == Material.POWDER_SNOW;
    }
    
    /**
     * Checks if player is standing on or near a bounce block.
     */
    public static boolean isNearBounceBlock(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        
        Material under = safeType(loc.clone().add(0, -0.5, 0).getBlock());
        Material below = safeType(loc.clone().add(0, -1.0, 0).getBlock());
        
        return isBounceBlock(under) || isBounceBlock(below);
    }
    
    /**
     * Returns the water level at a location (0-8, 8 being full block).
     * Returns -1 if not water.
     */
    public static int getWaterLevel(Block block) {
        if (block == null) return -1;
        Material type = safeType(block);
        if (type != Material.WATER) return -1;
        
        try {
            Levelled level = (Levelled) block.getBlockData();
            return level.getMaximumLevel() - level.getLevel();
        } catch (Throwable t) {
            return 8; // assume full if can't read
        }
    }
    
    /**
     * Checks if player is in flowing water (can push player).
     */
    public static boolean isInFlowingWater(Location loc) {
        if (loc == null) return false;
        Block b = loc.getBlock();
        int level = getWaterLevel(b);
        return level > 0 && level < 8;
    }
    
    /**
     * Checks if player has frost walker boots (can walk on water).
     */
    public static boolean hasFrostWalker(Player p) {
        if (p == null) return false;
        try {
            var boots = p.getInventory().getBoots();
            if (boots == null) return false;
            return boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FROST_WALKER) > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    /**
     * Gets depth strider level (0-3).
     */
    public static int getDepthStriderLevel(Player p) {
        if (p == null) return 0;
        try {
            var boots = p.getInventory().getBoots();
            if (boots == null) return 0;
            int level = boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
            return Math.min(3, Math.max(0, level));
        } catch (Throwable ignored) {
            return 0;
        }
    }
    
    /**
     * Gets soul speed level (0-3).
     */
    public static int getSoulSpeedLevel(Player p) {
        if (p == null) return 0;
        try {
            var boots = p.getInventory().getBoots();
            if (boots == null) return 0;
            int level = boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SOUL_SPEED);
            return Math.min(3, Math.max(0, level));
        } catch (Throwable ignored) {
            return 0;
        }
    }
    
    /**
     * Gets swift sneak level (0-3).
     */
    public static int getSwiftSneakLevel(Player p) {
        if (p == null) return 0;
        try {
            var leggings = p.getInventory().getLeggings();
            if (leggings == null) return 0;
            int level = leggings.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SWIFT_SNEAK);
            return Math.min(3, Math.max(0, level));
        } catch (Throwable ignored) {
            return 0;
        }
    }
    
    /**
     * Returns potion amplifier + 1 (so Speed I = 1, Speed II = 2, etc.).
     * Returns 0 if player doesn't have the effect.
     */
    public static int getPotionLevel(Player p, PotionEffectType type) {
        if (p == null || type == null) return 0;
        try {
            PotionEffect eff = p.getPotionEffect(type);
            if (eff == null) return 0;
            return eff.getAmplifier() + 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }
    
    /**
     * Calculates expected max horizontal speed for a player based on their state.
     * This is a helper for Speed checks.
     */
    public static double calculateMaxHorizontalSpeed(Player p, boolean onGround, boolean sprinting, boolean sneaking) {
        if (p == null) return 10.0; // fail-open
        
        // Base speeds (blocks/sec)
        double base;
        if (sneaking) {
            base = 1.31; // vanilla sneak speed
            int swiftSneak = getSwiftSneakLevel(p);
            if (swiftSneak > 0) {
                base *= (1.0 + 0.15 * swiftSneak); // swift sneak bonus
            }
        } else if (sprinting) {
            base = 5.61; // vanilla sprint speed
        } else {
            base = 4.32; // vanilla walk speed
        }
        
        // Speed potion
        int speedLevel = getPotionLevel(p, PotionEffectType.SPEED);
        if (speedLevel > 0) {
            base *= (1.0 + 0.20 * speedLevel);
        }
        
        // Slowness potion
        int slowLevel = getPotionLevel(p, PotionEffectType.SLOWNESS);
        if (slowLevel > 0) {
            base *= Math.max(0.0, 1.0 - 0.15 * slowLevel);
        }
        
        // Air bonus (strafing is slightly faster)
        if (!onGround) {
            base *= 1.05;
        }
        
        return base;
    }
    
    /**
     * Checks if the player is in a crawling pose (swimming on land, or in 1-block gaps).
     */
    public static boolean isCrawling(Player p) {
        if (p == null) return false;
        try {
            // In 1.21+, there's a SWIMMING pose that can happen on land
            return p.getPose().name().equals("SWIMMING") && !p.isInWater();
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    /**
     * Checks if player is in a 1.5-block ceiling (forces sneak height).
     */
    public static boolean isInLowCeiling(Player p, Location loc) {
        if (p == null || loc == null || loc.getWorld() == null) return false;
        
        // Check blocks at head height for standing
        Location head = loc.clone().add(0, 1.8, 0);
        if (safeType(head.getBlock()) != Material.AIR) return true;
        
        // Check slightly above (2.0)
        Location above = loc.clone().add(0, 2.0, 0);
        return safeType(above.getBlock()) != Material.AIR;
    }
    
    /**
     * Returns true if the player recently took damage (for knockback grace).
     */
    public static boolean isRecentlyDamaged(Player p, long maxAgeMs) {
        if (p == null) return false;
        try {
            // NoDamageTicks is in game ticks (20/sec)
            int ndt = p.getNoDamageTicks();
            // Default invulnerability is 10 ticks (500ms)
            return ndt > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    /**
     * Checks for berry bushes (slow movement).
     */
    public static boolean isBerryBush(Material m) {
        return m == Material.SWEET_BERRY_BUSH || m == Material.CAVE_VINES || m == Material.CAVE_VINES_PLANT;
    }
    
    /**
     * Checks if player is affected by any movement-slowing block.
     */
    public static boolean isInSlowingBlock(Location loc) {
        if (loc == null) return false;
        Material feet = safeType(loc.getBlock());
        return feet == Material.COBWEB 
            || feet == Material.HONEY_BLOCK 
            || isBerryBush(feet)
            || isPowderSnow(feet);
    }

    public static String fmt2(double d) { return String.format(Locale.US, "%.2f", d); }
    public static String fmt3(double d) { return String.format(Locale.US, "%.3f", d); }
}

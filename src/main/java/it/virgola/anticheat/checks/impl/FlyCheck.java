package it.virgola.anticheat.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import it.virgola.anticheat.checks.Check;
import it.virgola.anticheat.checks.CheckId;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.LagCompensation;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.ViolationService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FLY - Flight Detection (Air + Water)
 * 
 * Handles:
 * - Air fly detection (hover, ascend, no gravity)
 * - Water fly detection (only if player is truly stationary in water)
 * 
 * Does NOT flag for:
 * - Swimming/moving horizontally in water
 * - Ascending/descending in water currents or waterfalls
 * - Dolphin's Grace effect
 * - Players partially submerged or at water surface
 */
public final class FlyCheck implements Check {

    private final Configs configs;
    private final ViolationService violations;
    private final LagCompensation lag;

    private volatile boolean enabled = true;

    private final Map<UUID, Double> lastX = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastY = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastZ = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> airTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> stableAirTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> waterStableTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> waterNoMoveTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Double> buffer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlagTime = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastDy = new ConcurrentHashMap<>();
    private final Map<UUID, Long> elytraEndTime = new ConcurrentHashMap<>();

    public FlyCheck(Configs configs, ViolationService violations, LagCompensation lag) {
        this.configs = configs;
        this.violations = violations;
        this.lag = lag;
    }

    @Override
    public CheckId id() {
        return CheckId.FLY;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void reload() {
        this.enabled = configs.checks().getBoolean("checks.FLY.enabled", true);
    }

    @Override
    public void onPacketReceive(PlayerData data, PacketReceiveEvent event) {
        if (!enabled) return;
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;

        Player player = data.player();
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Exemptions
        if (data.isExempt() || player.isFlying() || player.getAllowFlight() ||
            player.isInsideVehicle() || player.isRiptiding()) {
            reset(uuid);
            return;
        }

        // Elytra grace
        if (player.isGliding()) {
            elytraEndTime.put(uuid, now);
            reset(uuid);
            return;
        }
        if (now - elytraEndTime.getOrDefault(uuid, 0L) < 1500) {
            return;
        }

        // Parse packet
        WrapperPlayClientPlayerFlying wrapper;
        try {
            wrapper = new WrapperPlayClientPlayerFlying(event);
        } catch (Exception e) {
            return;
        }
        if (!wrapper.hasPositionChanged()) return;

        double x = wrapper.getLocation().getX();
        double y = wrapper.getLocation().getY();
        double z = wrapper.getLocation().getZ();
        boolean onGround = wrapper.isOnGround();

        // Get previous position
        Double prevX = lastX.get(uuid);
        Double prevY = lastY.get(uuid);
        Double prevZ = lastZ.get(uuid);
        Long prevTime = lastPacketTime.get(uuid);

        // Update tracking
        lastX.put(uuid, x);
        lastY.put(uuid, y);
        lastZ.put(uuid, z);
        lastPacketTime.put(uuid, now);

        if (prevX == null || prevY == null || prevZ == null || prevTime == null) {
            return;
        }

        double dx = x - prevX;
        double dy = y - prevY;
        double dz = z - prevZ;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        long dt = now - prevTime;

        double m = lag.multiplier(player);

        if (dt < 10 || dt > 300) return;

        World world = player.getWorld();
        Location loc = new Location(world, x, y, z);

        // On ground = reset
        if (onGround || isOnGround(loc, world)) {
            airTicks.put(uuid, 0);
            stableAirTicks.put(uuid, 0);
            waterStableTicks.put(uuid, 0);
            waterNoMoveTicks.put(uuid, 0);
            decay(uuid, 0.3);
            lastDy.put(uuid, dy);
            return;
        }

        // Environment
        boolean inWater = isInWater(loc, world);
        boolean inLava = isInLava(loc, world);
        boolean hasClimbable = hasClimbable(loc, world);

        // Water fly detection
        if (inWater && !player.isSwimming()) {
            checkWaterFly(data, uuid, now, dy, hDist, m, player, loc, world);
            lastDy.put(uuid, dy);
            return;
        }

        // Swimming = legit
        if (player.isSwimming()) {
            reset(uuid);
            lastDy.put(uuid, dy);
            return;
        }

        // Air fly detection
        if (!inWater && !inLava && !hasClimbable) {
            checkAirFly(data, uuid, now, dy, m, player);
        }

        lastDy.put(uuid, dy);
    }

    private void checkAirFly(PlayerData data, UUID uuid, long now, double dy, double m, Player player) {
        int air = airTicks.merge(uuid, 1, (a, b) -> a + b);

        if (air < 6) return;

        // Levitation/Slow Falling
        if (player.hasPotionEffect(PotionEffectType.LEVITATION) ||
            player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            decay(uuid, 0.2);
            return;
        }

        Double prevDy = lastDy.getOrDefault(uuid, -0.08);
        double expectedDy = prevDy - 0.08;
        double tolerance = 0.15 * m;

        boolean suspicious = false;
        String reason = "";

        // Ascending without jump
        if (dy > 0.3 && air > 10) {
            suspicious = true;
            reason = "ASCEND";
        }

        // Hover
        if (!suspicious && Math.abs(dy) < 0.05 * m && air > 12) {
            int stable = stableAirTicks.merge(uuid, 1, (a, b) -> a + b);
            if (stable >= getInt("stable_air_min", 6)) {
                suspicious = true;
                reason = "HOVER";
            }
        } else {
            stableAirTicks.put(uuid, 0);
        }

        // No gravity
        if (!suspicious && dy > expectedDy + tolerance && air > 15 && dy > -0.3) {
            suspicious = true;
            reason = "NO_GRAVITY";
        }

        if (!suspicious) {
            decay(uuid, 0.15);
            return;
        }

        double weight = getDouble("air_weight", 1.0);
        double buf = buffer.merge(uuid, weight, (a, b) -> a + b);

        double threshold = getDouble("air_threshold", 4.0);
        long cooldown = (long) (getLong("alert_cooldown_ms", 1000) * m);
        long lastFlag = lastFlagTime.getOrDefault(uuid, 0L);

        if (buf >= threshold && (now - lastFlag) >= cooldown) {
            lastFlagTime.put(uuid, now);
            violations.flag(data, id().name() + " AIR", 1,
                    reason + " dy=" + fmt(dy) + " air=" + air + " buf=" + fmt(buf));
            buffer.put(uuid, Math.max(0, buf - getDouble("post_flag_reduce", 2.0)));
        }
    }

    private void checkWaterFly(PlayerData data, UUID uuid, long now, double dy, double hDist, double m, Player player, Location loc, World world) {
        // === EXEMPTIONS FOR WATER ===
        
        // Dolphin's Grace = very fast swimming, exempt
        if (player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
            resetWater(uuid);
            decay(uuid, 0.3);
            return;
        }
        
        // Conduit Power also affects water movement
        if (player.hasPotionEffect(PotionEffectType.CONDUIT_POWER)) {
            resetWater(uuid);
            decay(uuid, 0.2);
            return;
        }
        
        // Check for water currents (flowing water / waterfall)
        if (hasWaterCurrent(loc, world)) {
            resetWater(uuid);
            decay(uuid, 0.3);
            return;
        }
        
        // At water surface = bobbing is normal
        if (isAtWaterSurface(loc, world)) {
            resetWater(uuid);
            decay(uuid, 0.2);
            return;
        }
        
        // === WATER FLY DETECTION ===
        // In water without swimming: player should sink (~0.02 per tick)
        // BUT player can actively swim up by holding space - this is legit
        // Fly allows: hovering, impossibly fast movement, rising faster than swim allows
        
        // Check for Depth Strider
        int depthStrider = getDepthStriderLevel(player);
        double maxHSpeed = getDouble("water_max_h_speed", 0.18) + (depthStrider * 0.06);
        maxHSpeed *= m;
        
        // Max legit rise speed when swimming up: ~0.3 blocks/tick
        // With certain conditions can be slightly higher
        double maxRiseSpeed = getDouble("water_max_rise_speed", 0.35) * m;
        
        boolean suspicious = false;
        String reason = "";
        double weight = 0;
        
        // Detection 1: Hovering in deep water (very stable Y)
        // Normal: you sink, or swim which causes Y changes
        // Fly: perfectly stable Y
        double hoverThreshold = getDouble("water_hover_threshold", 0.005) * m;
        if (Math.abs(dy) < hoverThreshold && hDist < 0.02) {
            // Almost completely still - suspicious
            int stable = waterStableTicks.merge(uuid, 1, (a, b) -> a + b);
            int minStable = getInt("water_hover_min_ticks", 20);
            if (stable >= minStable) {
                suspicious = true;
                reason = "HOVER";
                weight = getDouble("water_hover_weight", 0.9);
            }
        } else {
            waterStableTicks.put(uuid, 0);
        }
        
        // Detection 2: Moving horizontally with unnaturally stable Y
        // Normal swimming: you move but Y varies (you sink a bit or swim up)
        // Fly: horizontal movement with perfectly stable Y (not sinking, not rising)
        if (!suspicious && hDist > getDouble("water_h_move_check_min", 0.06)) {
            double dyAbs = Math.abs(dy);
            // If moving horizontally but Y is suspiciously stable (not swimming up, not sinking)
            // Normal range: either sinking (dy < -0.01) or actively swimming up (dy > 0.03)
            if (dyAbs < getDouble("water_stable_while_moving", 0.015) * m) {
                int noChange = waterNoMoveTicks.merge(uuid, 1, (a, b) -> a + b);
                int minNoChange = getInt("water_no_y_change_min", 15);
                if (noChange >= minNoChange) {
                    suspicious = true;
                    reason = "STABLE_MOVE";
                    weight = getDouble("water_stable_move_weight", 0.8);
                }
            } else {
                // Y is changing (sinking or swimming up) - this is normal
                waterNoMoveTicks.put(uuid, 0);
            }
        }
        
        // Detection 3: Excessive horizontal speed in water
        if (!suspicious && hDist > maxHSpeed * 1.4) {
            suspicious = true;
            reason = "H_SPEED";
            weight = getDouble("water_speed_weight", 1.0);
        }
        
        // Detection 4: Rising faster than maximum swim speed
        // Normal swim up: max ~0.3-0.35 blocks/tick
        // Fly: can go faster
        if (!suspicious && dy > maxRiseSpeed) {
            suspicious = true;
            reason = "FAST_RISE";
            weight = getDouble("water_fast_rise_weight", 1.0);
        }
        
        if (!suspicious) {
            decay(uuid, 0.2);
            return;
        }
        
        // Add to buffer
        double buf = buffer.merge(uuid, weight, (a, b) -> a + b);

        double threshold = getDouble("water_threshold", 5.0);
        long cooldown = (long) (getLong("alert_cooldown_ms", 1200) * m);
        long lastFlag = lastFlagTime.getOrDefault(uuid, 0L);

        if (buf >= threshold && (now - lastFlag) >= cooldown) {
            lastFlagTime.put(uuid, now);
            violations.flag(data, id().name() + " WATER", 1,
                    reason + " dy=" + fmt(dy) + " hDist=" + fmt(hDist) + " buf=" + fmt(buf));
            buffer.put(uuid, Math.max(0, buf - getDouble("post_flag_reduce", 2.0)));
        }
    }
    
    private int getDepthStriderLevel(Player player) {
        var boots = player.getInventory().getBoots();
        if (boots == null) return 0;
        return boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.DEPTH_STRIDER);
    }
    
    /**
     * Check if there's flowing water (current) at the location
     */
    private boolean hasWaterCurrent(Location loc, World world) {
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        
        // Check surrounding water blocks for flow
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                for (int oz = -1; oz <= 1; oz++) {
                    Block b = world.getBlockAt(bx + ox, by + oy, bz + oz);
                    if (b.getType() == Material.WATER) {
                        BlockData bd = b.getBlockData();
                        if (bd instanceof Levelled) {
                            Levelled levelled = (Levelled) bd;
                            // Level 0 = source block, 1-7 = flowing, 8+ = falling
                            int level = levelled.getLevel();
                            if (level > 0) {
                                return true; // Flowing water detected
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Check if player is at water surface (bobbing area)
     */
    private boolean isAtWaterSurface(Location loc, World world) {
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        
        // Check if there's air above and water at/below player
        Block above = world.getBlockAt(bx, by + 1, bz);
        Block at = world.getBlockAt(bx, by, bz);
        Block below = world.getBlockAt(bx, by - 1, bz);
        
        boolean airAbove = above.getType() == Material.AIR || above.getType() == Material.CAVE_AIR;
        boolean waterAt = at.getType() == Material.WATER;
        boolean waterBelow = below.getType() == Material.WATER;
        
        // At surface = air above, water at feet or below
        if (airAbove && (waterAt || waterBelow)) {
            return true;
        }
        
        // Also check if player's head is just above water
        Block atHead = world.getBlockAt(bx, by + 1, bz);
        Block atFeet = world.getBlockAt(bx, by, bz);
        if (atHead.getType() != Material.WATER && atFeet.getType() == Material.WATER) {
            return true;
        }
        
        return false;
    }
    
    private void resetWater(UUID uuid) {
        waterStableTicks.put(uuid, 0);
        waterNoMoveTicks.put(uuid, 0);
    }

    private boolean isOnGround(Location loc, World world) {
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                if (world.getBlockAt(bx + ox, by - 1, bz + oz).getType().isSolid()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInWater(Location loc, World world) {
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int oy = -1; oy <= 1; oy++) {
            if (world.getBlockAt(bx, by + oy, bz).getType() == Material.WATER) {
                return true;
            }
        }
        return false;
    }

    private boolean isInLava(Location loc, World world) {
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        for (int oy = -1; oy <= 1; oy++) {
            if (world.getBlockAt(bx, by + oy, bz).getType() == Material.LAVA) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClimbable(Location loc, World world) {
        Block at = world.getBlockAt(loc);
        Material mat = at.getType();
        return mat == Material.LADDER || mat == Material.VINE ||
               mat == Material.TWISTING_VINES || mat == Material.WEEPING_VINES ||
               mat == Material.SCAFFOLDING;
    }

    private void decay(UUID uuid, double amount) {
        buffer.merge(uuid, -amount, (old, delta) -> Math.max(0, old + delta));
    }

    private void reset(UUID uuid) {
        airTicks.put(uuid, 0);
        stableAirTicks.put(uuid, 0);
        waterStableTicks.put(uuid, 0);
        waterNoMoveTicks.put(uuid, 0);
        buffer.put(uuid, 0.0);
    }

    @Override
    public void onQuit(UUID uuid) {
        lastX.remove(uuid);
        lastY.remove(uuid);
        lastZ.remove(uuid);
        lastPacketTime.remove(uuid);
        airTicks.remove(uuid);
        stableAirTicks.remove(uuid);
        waterStableTicks.remove(uuid);
        waterNoMoveTicks.remove(uuid);
        buffer.remove(uuid);
        lastFlagTime.remove(uuid);
        lastDy.remove(uuid);
        elytraEndTime.remove(uuid);
    }

    private double getDouble(String key, double def) {
        return configs.checks().getDouble("checks.FLY.params." + key, def);
    }

    private int getInt(String key, int def) {
        return configs.checks().getInt("checks.FLY.params." + key, def);
    }

    private long getLong(String key, long def) {
        return configs.checks().getLong("checks.FLY.params." + key, def);
    }

    private static String fmt(double d) {
        return String.format("%.3f", d);
    }
}

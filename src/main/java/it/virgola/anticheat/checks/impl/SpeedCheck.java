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
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPEED - Speed Hack Detection (Packet-Based)
 *
 * Uses official Minecraft movement values:
 * - Walking: 4.317 m/s
 * - Sprinting: 5.612 m/s (+30%)
 * - Sneaking: 1.3 m/s (30% of walking)
 * - Speed potion: +20% per level
 * - Soul Speed: (Level * 0.105 + 1.3) multiplier
 * - Swift Sneak: +15% per level of sneaking speed
 * - Various block modifiers (ice, soul sand, honey, slime, etc.)
 */
public final class SpeedCheck implements Check {

    // === OFFICIAL MINECRAFT SPEED VALUES (blocks per second) ===
    private static final double WALK_SPEED = 4.317;
    private static final double SPRINT_SPEED = 5.612;  // 4.317 * 1.3
    private static final double SNEAK_SPEED = 1.3;     // 30% of walking
    private static final double DIAGONAL_MULTIPLIER = 1.45;  // ~sqrt(2) for diagonal movement

    // Block-based speeds (without enchants)
    private static final double SOUL_SAND_SPEED = 2.508;
    private static final double HONEY_SPEED = 2.508;
    private static final double SLIME_SPEED = 3.040;

    // Potion multipliers
    private static final double SPEED_POTION_PER_LEVEL = 0.20;  // +20% per level
    private static final double SLOWNESS_PER_LEVEL = 0.15;      // -15% per level

    // Enchantment multipliers
    private static final double SWIFT_SNEAK_PER_LEVEL = 0.15;   // +15% of WALK speed per level

    // Fixed grace windows (NO new config keys)
    private static final long SOUL_SPEED_EQUIP_GRACE_MS = 650;

    // Fixed ratio tuning (NO new config keys)
    private static final double IGNORE_RATIO_DEFAULT = 1.10;   // ignore tiny deviations
    private static final double BORDERLINE_RATIO_DEFAULT = 1.15; // accumulate slowly
    private static final double IGNORE_RATIO_SOUL = 1.07;      // tighter on recent soul blocks
    private static final double BORDERLINE_RATIO_SOUL = 1.12;

    private final Configs configs;
    private final ViolationService violations;
    private final LagCompensation lag;

    private volatile boolean enabled = true;

    // Tracking per player
    private final Map<UUID, Double> lastX = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastY = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastZ = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> groundTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Double> buffer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlagTime = new ConcurrentHashMap<>();

    // Grace tracking
    private final Map<UUID, Long> lastIceTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBounceTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSoulBlockTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastBlockChangeTime = new ConcurrentHashMap<>();
    private final Map<UUID, Material> lastBlockBelow = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSneakStartTime = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> lastSneakState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotSneakingTime = new ConcurrentHashMap<>();

    // Soul Speed equip/unequip tracking (no config)
    private final Map<UUID, Integer> lastSoulSpeedLevel = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSoulSpeedChangeTime = new ConcurrentHashMap<>();

    public SpeedCheck(Configs configs, ViolationService violations, LagCompensation lag) {
        this.configs = configs;
        this.violations = violations;
        this.lag = lag;
    }

    @Override
    public CheckId id() {
        return CheckId.SPEED;
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
        this.enabled = configs.checks().getBoolean("checks.SPEED.enabled", true);
    }

    @Override
    public void onPacketReceive(PlayerData data, PacketReceiveEvent event) {
        if (!enabled) return;
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;

        Player player = data.player();
        if (player == null || !player.isOnline()) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // === EXEMPTIONS ===
        if (data.isExempt()) {
            reset(uuid);
            return;
        }

        // Creative/Spectator/Flying
        if (player.isFlying() || player.getAllowFlight()) {
            reset(uuid);
            return;
        }

        // Elytra
        if (player.isGliding()) {
            reset(uuid);
            return;
        }

        // Vehicle
        if (player.isInsideVehicle()) {
            reset(uuid);
            return;
        }

        // Riptide
        if (player.isRiptiding()) {
            reset(uuid);
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

        // Get previous data
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

        // Calculate deltas
        double dx = x - prevX;
        double dz = z - prevZ;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        long dt = now - prevTime;

        // Skip weird packet timing
        double m = lag.multiplier(player);
        if (dt < 20 || dt > (200 * m)) {
            return;
        }

        // Convert to blocks per second
        double bps = (hDist / dt) * 1000.0;

        // === DOMAIN SEPARATION ===
        // Air handled by FLY
        if (!onGround) {
            int gt = groundTicks.getOrDefault(uuid, 0) - 1;
            groundTicks.put(uuid, Math.max(0, gt));
            decay(uuid, getDouble("decay_air", 0.15));
            return;
        }

        int gt = groundTicks.getOrDefault(uuid, 0) + 1;
        groundTicks.put(uuid, gt);

        // Skip early ground ticks (just landed)
        if (gt < getInt("min_ground_ticks", 3)) {
            return;
        }

        World world = player.getWorld();
        Location loc = new Location(world, x, y, z);

        // Liquid handled by JESUS
        if (isInLiquid(loc, world)) {
            reset(uuid);
            return;
        }

        // Skip negligible movement
        if (bps < getDouble("min_bps", 0.5)) {
            decay(uuid, getDouble("decay_still", 0.1));
            return;
        }

        // === CALCULATE MAX LEGITIMATE SPEED ===
        double maxBps = calculateMaxSpeed(player, loc, world, now, uuid, dx, dz);

        // === SOUL SPEED EQUIP/UNEQUIP GRACE (fixed window, no config) ===
        Long ssChange = lastSoulSpeedChangeTime.get(uuid);
        if (ssChange != null && (now - ssChange) < SOUL_SPEED_EQUIP_GRACE_MS) {
            // Aggressive decay to avoid carrying over buffer across legit speed changes
            decay(uuid, 1.25);
            return;
        }

        // === SNEAK TRANSITION GRACE (uses existing config key sneak_grace_ms) ===
        boolean currentSneak = player.isSneaking();
        Boolean lastSneak = lastSneakState.get(uuid);

        if (!currentSneak) {
            lastNotSneakingTime.put(uuid, now);
        }
        if (lastSneak != null && !lastSneak && currentSneak) {
            lastSneakStartTime.put(uuid, now);
        }
        lastSneakState.put(uuid, currentSneak);

        Long lastNotSneak = lastNotSneakingTime.get(uuid);
        long sneakGraceMs = getLong("sneak_grace_ms", 800);
        if (currentSneak && lastNotSneak != null && (now - lastNotSneak) < sneakGraceMs) {
            // IMPORTANT: don't multiply by lag here; lag applied below once.
            maxBps = Math.max(maxBps, WALK_SPEED);
        }

        // Apply lag compensation (once)
        maxBps *= Math.max(1.0, m);

        // === CHECK ===
        if (bps <= maxBps) {
            decay(uuid, getDouble("decay_ok", 0.25));
            return;
        }

        double excessRatio = bps / maxBps;

        // Tighter ratios if (recently) on soul blocks to catch small-margin hacks there
        boolean recentSoul = false;
        Long lastSoul = lastSoulBlockTime.get(uuid);
        if (lastSoul != null && (now - lastSoul) < 250) {
            recentSoul = true;
        }

        double ignoreRatio = recentSoul ? IGNORE_RATIO_SOUL : IGNORE_RATIO_DEFAULT;
        double borderlineRatio = recentSoul ? BORDERLINE_RATIO_SOUL : BORDERLINE_RATIO_DEFAULT;

        if (excessRatio < ignoreRatio) {
            decay(uuid, getDouble("decay_slight_over", 0.15));
            return;
        }

        double weight;
        if (excessRatio < borderlineRatio) {
            weight = getDouble("weight_borderline", 0.25);
        } else if (excessRatio < 1.20) {
            weight = getDouble("weight_low", 0.5);
        } else if (excessRatio < 1.35) {
            weight = getDouble("weight_medium", 0.8);
        } else {
            weight = getDouble("weight_high", 1.2);
        }

        double buf = buffer.getOrDefault(uuid, 0.0) + weight;
        buffer.put(uuid, buf);

        double threshold = getDouble("threshold", 4.5);
        long cooldown = (long) (getLong("alert_cooldown_ms", 1500) * m);
        long lastFlag = lastFlagTime.getOrDefault(uuid, 0L);

        if (buf >= threshold && (now - lastFlag) >= cooldown) {
            lastFlagTime.put(uuid, now);

            violations.flag(data, id().name(), 1,
                    "bps=" + fmt(bps) +
                            " max=" + fmt(maxBps) +
                            " ratio=" + fmt(excessRatio) +
                            " buf=" + fmt(buf));

            buffer.put(uuid, buf - getDouble("post_flag_reduce", 2.5));
        }
    }

    /**
     * Calculate maximum legitimate horizontal speed for current conditions.
     * Handles Soul Speed and soul-sand slowdown correctly (including sprint).
     */
    private double calculateMaxSpeed(Player player, Location loc, World world, long now, UUID uuid, double dx, double dz) {
        // === DETERMINE IF PLAYER CAN ACTUALLY SPRINT ===
        float yaw = player.getLocation().getYaw();
        double facingX = -Math.sin(Math.toRadians(yaw));
        double facingZ = Math.cos(Math.toRadians(yaw));

        double movementMagnitude = Math.sqrt(dx * dx + dz * dz);
        boolean movingForward = false;
        if (movementMagnitude > 0.01) {
            double dotProduct = (dx * facingX + dz * facingZ) / movementMagnitude;
            movingForward = dotProduct > 0.5; // within ~60 degrees
        }

        // === CHECK ALL BLOCKS UNDER PLAYER HITBOX ===
        double px = loc.getX();
        double py = loc.getY();
        double pz = loc.getZ();
        double halfWidth = 0.3;

        int yBelow = (int) Math.floor(py - 0.1);
        int yAt = (int) Math.floor(py);

        Block[] blocksBelow = new Block[]{
                world.getBlockAt((int) Math.floor(px - halfWidth), yBelow, (int) Math.floor(pz - halfWidth)),
                world.getBlockAt((int) Math.floor(px + halfWidth), yBelow, (int) Math.floor(pz - halfWidth)),
                world.getBlockAt((int) Math.floor(px - halfWidth), yBelow, (int) Math.floor(pz + halfWidth)),
                world.getBlockAt((int) Math.floor(px + halfWidth), yBelow, (int) Math.floor(pz + halfWidth))
        };

        Block[] blocksAt = new Block[]{
                world.getBlockAt((int) Math.floor(px - halfWidth), yAt, (int) Math.floor(pz - halfWidth)),
                world.getBlockAt((int) Math.floor(px + halfWidth), yAt, (int) Math.floor(pz - halfWidth)),
                world.getBlockAt((int) Math.floor(px - halfWidth), yAt, (int) Math.floor(pz + halfWidth)),
                world.getBlockAt((int) Math.floor(px + halfWidth), yAt, (int) Math.floor(pz + halfWidth))
        };

        boolean onSoulBlock = false;
        boolean onIce = false;
        boolean onBlueIce = false;
        boolean onPackedIce = false;
        boolean onBouncy = false;
        boolean onHoney = false;
        boolean onSlime = false;
        boolean inCobweb = false;
        boolean inBerryBush = false;
        boolean onNormalBlock = false;

        for (Block below : blocksBelow) {
            Material mat = below.getType();
            if (mat == Material.SOUL_SAND || mat == Material.SOUL_SOIL) onSoulBlock = true;
            if (mat == Material.BLUE_ICE) {
                onIce = true;
                onBlueIce = true;
            }
            if (mat == Material.PACKED_ICE) {
                onIce = true;
                onPackedIce = true;
            }
            if (mat == Material.ICE || mat == Material.FROSTED_ICE) onIce = true;
            if (mat == Material.SLIME_BLOCK) {
                onBouncy = true;
                onSlime = true;
            }
            if (mat.name().endsWith("_BED")) onBouncy = true;
            if (mat == Material.HONEY_BLOCK) onHoney = true;

            if (mat.isSolid() && !isSoulBlock(mat) && !isIce(mat)
                    && mat != Material.SLIME_BLOCK && mat != Material.HONEY_BLOCK) {
                onNormalBlock = true;
            }
        }

        for (Block at : blocksAt) {
            Material mat = at.getType();
            if (mat == Material.SOUL_SAND || mat == Material.SOUL_SOIL) onSoulBlock = true;
            if (mat == Material.HONEY_BLOCK) onHoney = true;
            if (mat == Material.COBWEB) inCobweb = true;
            if (mat == Material.SWEET_BERRY_BUSH) inBerryBush = true;
        }

        int soulSpeed = getSoulSpeedLevel(player);
        trackSoulSpeedChange(uuid, soulSpeed, now);

        // If touching ANY soul block, Soul Speed bonus applies.
        boolean soulSpeedApplies = onSoulBlock && soulSpeed > 0;

        // Soul sand slowdown applies only if FULLY on soul block without Soul Speed
        boolean soulSlowdownApplies = onSoulBlock && !onNormalBlock && soulSpeed == 0;

        if (onSoulBlock) {
            lastSoulBlockTime.put(uuid, now);
        }

        double base = WALK_SPEED;
        boolean soulBlockHandled = false;

        // === SOUL BLOCK WITH SOUL SPEED ===
        if (soulSpeedApplies) {
            soulBlockHandled = true;

            double soulBonus = (soulSpeed * 0.105) + 1.3;

            if (player.isSneaking()) {
                base = (SNEAK_SPEED * DIAGONAL_MULTIPLIER) * soulBonus;
            } else if (player.isSprinting() && movingForward) {
                base = SPRINT_SPEED * soulBonus;
            } else {
                base = WALK_SPEED * soulBonus;
            }
        }
        // === SOUL SAND/SOIL WITHOUT SOUL SPEED ===
        else if (soulSlowdownApplies) {
            soulBlockHandled = true;

            if (player.isSneaking()) {
                base = SNEAK_SPEED * DIAGONAL_MULTIPLIER * getDouble("soul_no_enchant_sneak_mult", 0.7);
            } else if (player.isSprinting() && movingForward) {
                // FIX: sprint is still possible under slowdown; apply +30% to slowed walk
                base = SOUL_SAND_SPEED * 1.30;
            } else {
                base = SOUL_SAND_SPEED;
            }
        }
        // Edge case: on edge between soul block and normal block w/out Soul Speed -> fall through

        // === NORMAL BASE SPEED ===
        if (!soulBlockHandled) {
            if (player.isSneaking()) {
                base = SNEAK_SPEED * DIAGONAL_MULTIPLIER;

                int swiftSneak = getSwiftSneakLevel(player);
                if (swiftSneak > 0) {
                    double sneakPercent = 0.30 + (swiftSneak * SWIFT_SNEAK_PER_LEVEL);
                    sneakPercent = Math.min(sneakPercent, 1.0);
                    base = WALK_SPEED * sneakPercent * DIAGONAL_MULTIPLIER;
                }
            } else {
                boolean serverSprinting = player.isSprinting() && movingForward;
                boolean clientLikelySprinting = movingForward && movementMagnitude > 0.2;

                if (serverSprinting || clientLikelySprinting) {
                    base = SPRINT_SPEED;
                } else {
                    base = WALK_SPEED;
                }
            }
        }

        // === POTION EFFECTS ===
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        if (speed != null) {
            int amp = speed.getAmplifier() + 1;
            base *= (1.0 + amp * SPEED_POTION_PER_LEVEL);
        }

        PotionEffect slow = player.getPotionEffect(PotionEffectType.SLOWNESS);
        if (slow != null) {
            int amp = slow.getAmplifier() + 1;
            double reduction = amp * SLOWNESS_PER_LEVEL;
            base *= Math.max(0.0, 1.0 - reduction);
        }

        // === BLOCK-BASED MODIFIERS ===
        Block centerBelow = world.getBlockAt((int) Math.floor(px), (int) Math.floor(py) - 1, (int) Math.floor(pz));
        Material belowMat = centerBelow.getType();
        Material prevBlock = lastBlockBelow.get(uuid);
        if (prevBlock != null && prevBlock != belowMat) {
            lastBlockChangeTime.put(uuid, now);
        }
        lastBlockBelow.put(uuid, belowMat);

        Long lastChange = lastBlockChangeTime.get(uuid);
        boolean inBlockTransition = lastChange != null && (now - lastChange) < getLong("block_change_grace_ms", 600);

        double blockModifier = 1.0;
        boolean hasSpecialBlock = soulBlockHandled;

        // Soul Speed momentum grace when leaving soul blocks (existing config key)
        if (!hasSpecialBlock && soulSpeed > 0) {
            Long lastSoul = lastSoulBlockTime.get(uuid);
            if (lastSoul != null && (now - lastSoul) < getLong("soul_grace_ms", 400)) {
                hasSpecialBlock = true;
                blockModifier = 1.0 + (soulSpeed * 0.03);
            }
        }

        // Ice blocks
        if (!hasSpecialBlock && onIce) {
            lastIceTime.put(uuid, now);
            hasSpecialBlock = true;
            if (onBlueIce) {
                blockModifier = getDouble("blue_ice_mult", 1.4);
            } else if (onPackedIce) {
                blockModifier = getDouble("packed_ice_mult", 1.3);
            } else {
                blockModifier = getDouble("ice_mult", 1.2);
            }
        } else if (!hasSpecialBlock) {
            Long lastIce = lastIceTime.get(uuid);
            if (lastIce != null && (now - lastIce) < getLong("ice_grace_ms", 500)) {
                hasSpecialBlock = true;
                blockModifier = getDouble("ice_grace_mult", 1.2);
            }
        }

        // Slime
        if (!hasSpecialBlock && onSlime) {
            blockModifier = SLIME_SPEED / WALK_SPEED;
        }

        // Honey
        if (!hasSpecialBlock && onHoney) {
            blockModifier = HONEY_SPEED / WALK_SPEED;
        }

        base *= blockModifier;

        // Bounce grace
        if (onBouncy) {
            lastBounceTime.put(uuid, now);
        }
        Long lastBounce = lastBounceTime.get(uuid);
        if (lastBounce != null && (now - lastBounce) < getLong("bounce_grace_ms", 800)) {
            base *= getDouble("bounce_grace_mult", 1.3);
        }

        // Cobweb
        if (inCobweb) {
            base *= getDouble("cobweb_mult", 0.15);
        }

        // Berry bush
        if (inBerryBush) {
            base *= getDouble("berry_mult", 0.25);
        }

        // Block transition grace (only if not special block)
        if (inBlockTransition && !hasSpecialBlock) {
            base *= getDouble("block_transition_mult", 1.25);
        }

        // Damage grace
        Long lastDmg = lastDamageTime.get(uuid);
        if (lastDmg != null && (now - lastDmg) < getLong("damage_grace_ms", 600)) {
            base *= getDouble("damage_grace_mult", 1.4);
        }

        // Hard cap
        double hardCap = getDouble("hard_cap_bps", 20.0);
        base = Math.min(base, hardCap);

        return Math.max(base, getDouble("min_max_bps", 1.5));
    }

    private void trackSoulSpeedChange(UUID uuid, int level, long now) {
        Integer prev = lastSoulSpeedLevel.put(uuid, level);
        if (prev != null && prev != level) {
            lastSoulSpeedChangeTime.put(uuid, now);
        }
    }

    private boolean isInLiquid(Location loc, World world) {
        Block at = world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Block below = world.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        Material m1 = at.getType();
        Material m2 = below.getType();
        return m1 == Material.WATER || m1 == Material.LAVA ||
                m2 == Material.WATER || m2 == Material.LAVA;
    }

    private boolean isIce(Material mat) {
        return mat == Material.ICE || mat == Material.PACKED_ICE ||
                mat == Material.BLUE_ICE || mat == Material.FROSTED_ICE;
    }

    private boolean isSoulBlock(Material mat) {
        return mat == Material.SOUL_SAND || mat == Material.SOUL_SOIL;
    }

    private int getSwiftSneakLevel(Player player) {
        var leggings = player.getInventory().getLeggings();
        if (leggings == null) return 0;
        try {
            @SuppressWarnings("deprecation")
            var ench = org.bukkit.enchantments.Enchantment.getByName("SWIFT_SNEAK");
            if (ench != null) {
                return leggings.getEnchantmentLevel(ench);
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int getSoulSpeedLevel(Player player) {
        var boots = player.getInventory().getBoots();
        if (boots == null) return 0;
        return boots.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.SOUL_SPEED);
    }

    /**
     * Called by damage listener to track knockback
     */
    public void onDamage(UUID uuid) {
        lastDamageTime.put(uuid, System.currentTimeMillis());
    }

    private void decay(UUID uuid, double amount) {
        double current = buffer.getOrDefault(uuid, 0.0);
        buffer.put(uuid, Math.max(0.0, current - amount));
    }

    private void reset(UUID uuid) {
        groundTicks.put(uuid, 0);
        buffer.put(uuid, 0.0);
    }

    @Override
    public void onQuit(UUID uuid) {
        lastX.remove(uuid);
        lastY.remove(uuid);
        lastZ.remove(uuid);
        lastPacketTime.remove(uuid);
        groundTicks.remove(uuid);
        buffer.remove(uuid);
        lastFlagTime.remove(uuid);

        lastIceTime.remove(uuid);
        lastBounceTime.remove(uuid);
        lastDamageTime.remove(uuid);
        lastSoulBlockTime.remove(uuid);
        lastBlockChangeTime.remove(uuid);
        lastBlockBelow.remove(uuid);

        lastSneakStartTime.remove(uuid);
        lastSneakState.remove(uuid);
        lastNotSneakingTime.remove(uuid);

        lastSoulSpeedLevel.remove(uuid);
        lastSoulSpeedChangeTime.remove(uuid);
    }

    // Config helpers (unchanged)
    private double getDouble(String key, double def) {
        return configs.checks().getDouble("checks.SPEED.params." + key, def);
    }

    private int getInt(String key, int def) {
        return configs.checks().getInt("checks.SPEED.params." + key, def);
    }

    private long getLong(String key, long def) {
        return configs.checks().getLong("checks.SPEED.params." + key, def);
    }

    private static String fmt(double d) {
        return String.format("%.2f", d);
    }
}

package it.virgola.anticheat.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import it.virgola.anticheat.checks.Check;
import it.virgola.anticheat.checks.CheckId;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.LagCompensation;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.ViolationService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffectType;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * NOFALL - NoFall Hack Detection (Packet + Damage Event Based)
 * 
 * ASYNC-OPTIMIZED: Block checks are cached and updated periodically via main thread
 * to avoid blocking the packet processing thread.
 * 
 * Detection Method:
 * 1. Track real fall distance via packets (Y position changes)
 * 2. When player lands from height that should cause damage, expect EntityDamageEvent(FALL)
 * 3. If no fall damage event received within grace period -> NoFall hack detected
 * 
 * Legitimate exemptions:
 * - Landing in water/lava/bubble column/waterlogged blocks
 * - Landing on slime/hay/honey/bed/powder snow
 * - Climbing (ladder, vine, scaffolding)
 * - Mace hit during fall (negates fall damage in 1.21+)
 * - Slow Falling / Levitation effects
 * - Elytra gliding / Riptide / Swimming
 * - Creative/Spectator/Flying
 */
public final class NoFallCheck implements Check {

    // Blocks that completely negate fall damage
    private static final Set<Material> FALL_NEGATING_BLOCKS = Set.of(
        Material.SLIME_BLOCK,
        Material.HAY_BLOCK,
        Material.HONEY_BLOCK,
        Material.POWDER_SNOW,
        Material.COBWEB
    );
    
    // Climbable blocks
    private static final Set<Material> CLIMBABLE_BLOCKS = Set.of(
        Material.LADDER, Material.VINE, Material.SCAFFOLDING,
        Material.CAVE_VINES, Material.CAVE_VINES_PLANT,
        Material.WEEPING_VINES, Material.WEEPING_VINES_PLANT,
        Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT
    );
    
    // Beds (bounce)
    private static final Set<Material> BEDS = Set.of(
        Material.WHITE_BED, Material.ORANGE_BED, Material.MAGENTA_BED,
        Material.LIGHT_BLUE_BED, Material.YELLOW_BED, Material.LIME_BED,
        Material.PINK_BED, Material.GRAY_BED, Material.LIGHT_GRAY_BED,
        Material.CYAN_BED, Material.PURPLE_BED, Material.BLUE_BED,
        Material.BROWN_BED, Material.GREEN_BED, Material.RED_BED,
        Material.BLACK_BED
    );

    private Configs configs;
    private ViolationService violations;
    private LagCompensation lag;

    // Async executor for non-blocking operations (scheduled)
    private final java.util.concurrent.ScheduledExecutorService asyncExecutor = Executors.newSingleThreadScheduledExecutor();

    // Plugin reference for scheduling sync tasks
    private Plugin plugin;

    private volatile boolean enabled = true;

    // Fall tracking (from packet coordinates - no Bukkit API needed)
    private final Map<UUID, Double> lastX = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastY = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastZ = new ConcurrentHashMap<>();
    private final Map<UUID, Double> fallStartY = new ConcurrentHashMap<>();
    private final Map<UUID, Double> maxFallDistance = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> isFalling = new ConcurrentHashMap<>();
    
    // Expectation / damage tracking
    private final Map<UUID, Long> expectingDamageUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Double> expectedFallDistance = new ConcurrentHashMap<>();
    private final Map<UUID, Double> expectedDamageAmount = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> expectingDripstone = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> receivedFallDamage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastDamageTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlagTime = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> hitWithMaceDuringFall = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMaceHitTime = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastGlideTime = new ConcurrentHashMap<>();
    private boolean debugLogs = false;

    // Block exemption cache (updated via scheduled main thread task)
    private final Map<UUID, Long> lastBlockCheckTime = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> isNearExemptBlock = new ConcurrentHashMap<>();
    // Cache whether there's pointed dripstone under last known position
    private final Map<UUID, Boolean> hasDripstoneBelow = new ConcurrentHashMap<>();
    private static final long BLOCK_CHECK_INTERVAL_MS = 100; // Check blocks every 100ms
    private org.bukkit.scheduler.BukkitTask cacheUpdaterTask = null;
    
    public NoFallCheck(Configs configs, ViolationService violations, LagCompensation lag) {
        this.configs = configs;
        this.violations = violations;
        this.lag = lag;
        if (this.configs != null) {
            this.debugLogs = this.configs.checks().getBoolean("checks.NOFALL.params.debug_logs", false);
        }
    }

    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
        // Start a lightweight main-thread cache updater for block state under players.
        try {
            if (this.cacheUpdaterTask != null) {
                this.cacheUpdaterTask.cancel();
            }
            if (this.plugin != null) {
                this.cacheUpdaterTask = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> {
                    try {
                        for (UUID uuid : lastX.keySet()) {
                            Player p = Bukkit.getPlayer(uuid);
                            if (p == null || !p.isOnline()) continue;
                            Double lx = lastX.get(uuid);
                            Double ly = lastY.get(uuid);
                            Double lz = lastZ.get(uuid);
                            if (lx == null || ly == null || lz == null) continue;
                            int bx = (int) Math.floor(lx);
                            int by = (int) Math.floor(ly);
                            int bz = (int) Math.floor(lz);
                            World w = p.getWorld();
                            try {
                                Block below = w.getBlockAt(bx, by - 1, bz);
                                Material mat = below.getType();
                                hasDripstoneBelow.put(uuid, mat == Material.POINTED_DRIPSTONE);
                                boolean exempt = checkExemptBlocks(w, bx, by, bz);
                                isNearExemptBlock.put(uuid, exempt);
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }, 1L, 2L);
            }
        } catch (Exception ignored) {}
    }
    
    @SuppressWarnings("unused")
    @Override
    public void onPacketReceive(PlayerData data, PacketReceiveEvent event) {
        if (!enabled) return;
        if (data == null) return;
        Player player = data.player();
        if (player == null) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Only interested in player position packets
        if (!WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) return;

        // NOTE: expiry checks are scheduled when expectations are set to avoid races.
        // === BASIC EXEMPTIONS (player state - some need main thread) ===
        if (shouldExemptCompletely(data, player)) {
            resetFall(uuid);
            return;
        }

        // Parse packet (no Bukkit API, pure PacketEvents)
        WrapperPlayClientPlayerFlying wrapper;
        try {
            wrapper = new WrapperPlayClientPlayerFlying(event);
        } catch (Exception e) {
            return;
        }

        if (!wrapper.hasPositionChanged()) return;

        // Get position from PACKET (not player.getLocation() which needs sync)
        double newX = wrapper.getLocation().getX();
        double newY = wrapper.getLocation().getY();
        double newZ = wrapper.getLocation().getZ();
        boolean clientOnGround = wrapper.isOnGround();
        
        Double prevY = lastY.get(uuid);

        // Store packet position
        lastX.put(uuid, newX);
        lastY.put(uuid, newY);
        lastZ.put(uuid, newZ);

        if (prevY == null) return;

        // Lag check (async-safe)
        if (lag != null && lag.multiplier(player) > 1.5) {
            resetFall(uuid);
            return;
        }

        double deltaY = newY - prevY;
        
        // Detect teleport (async-safe)
        if (Math.abs(deltaY) > 10) {
            if (deltaY < 0) {
                resetFall(uuid);
            }
            return;
        }

        boolean wasAlreadyFalling = isFalling.getOrDefault(uuid, false);
        boolean currentlyDescending = deltaY < -0.1;

        // === ASYNC BLOCK CHECKS ===
        // Schedule block check periodically on main thread to update cache
        if (now - lastBlockCheckTime.getOrDefault(uuid, 0L) > BLOCK_CHECK_INTERVAL_MS) {
            lastBlockCheckTime.put(uuid, now);
            
            final int bx = (int) Math.floor(newX);
            final int by = (int) Math.floor(newY);
            final int bz = (int) Math.floor(newZ);
            
            scheduleSync(() -> {
                try {
                    World world = player.getWorld();
                    boolean exempt = checkExemptBlocks(world, bx, by, bz);
                    isNearExemptBlock.put(uuid, exempt);
                } catch (Exception ignored) {}
            });
        }

        // Use cached block exemption result
        if (isNearExemptBlock.getOrDefault(uuid, false)) {
            resetFall(uuid);
            return;
        }
        
        // Mace grace period config
        long maceGrace = configs != null ? configs.checks().getLong("checks.NOFALL.params.mace_grace_ms", 2000) : 2000;
        long elytraGrace = configs != null ? configs.checks().getLong("checks.NOFALL.params.elytra_land_grace_ms", 1000) : 1000;

        // Elytra grace period
        if (now - lastGlideTime.getOrDefault(uuid, 0L) < elytraGrace) {
            resetFall(uuid);
            return;
        }

        // === FALL TRACKING (pure computation, no Bukkit API) ===
        if (currentlyDescending) {
            if (!wasAlreadyFalling) {
                // Started falling
                fallStartY.put(uuid, prevY);
                maxFallDistance.put(uuid, 0.0);
                isFalling.put(uuid, true);
                hitWithMaceDuringFall.put(uuid, false);
            }
            
            // Update max fall
            double currentFall = fallStartY.getOrDefault(uuid, newY) - newY;
            double maxFall = maxFallDistance.getOrDefault(uuid, 0.0);
            if (currentFall > maxFall) {
                maxFallDistance.put(uuid, currentFall);
            }
        }

        // === LANDING DETECTION ===
        if (clientOnGround && wasAlreadyFalling) {
            double realFallDist = maxFallDistance.getOrDefault(uuid, 0.0);
            double minFallForDamage = configs != null ? configs.checks().getDouble("checks.NOFALL.params.min_fall_blocks", 3.5) : 3.5;
            
            // Check if player used Mace during this fall
            boolean usedMace = hitWithMaceDuringFall.getOrDefault(uuid, false) && 
                              (now - lastMaceHitTime.getOrDefault(uuid, 0L) < maceGrace);
            
            long damageGraceMs = configs != null ? configs.checks().getLong("checks.NOFALL.params.damage_grace_ms", 200) : 200;
            double minDripstoneFall = configs != null ? configs.checks().getDouble("checks.NOFALL.params.min_dripstone_fall", 1.3) : 1.3;
            double expectedNormal = computeExpectedFallDamage(realFallDist, false);
            if (realFallDist >= minFallForDamage && !usedMace && expectedNormal >= 1.0) {
                // Player should receive fall damage (normal fall) — only if expected damage >= 1
                expectingDamageUntil.put(uuid, now + damageGraceMs);
                expectedFallDistance.put(uuid, realFallDist);
                expectedDamageAmount.put(uuid, expectedNormal);
                expectingDripstone.put(uuid, false);
                receivedFallDamage.put(uuid, false);
                // Ensure expiry check runs even if player stops sending packets
                scheduleExpectationExpiry(uuid, damageGraceMs);
                logDebug(String.format("set normal-fall expectation player=%s uuid=%s fall=%.2f expected=%.2f grace=%d", player.getName(), uuid, realFallDist, expectedNormal, damageGraceMs));
                // For normal falls we can reset immediately (no main-thread scan needed)
                resetFall(uuid);
            } else {
                // Check the block under player on main thread: if POINTED_DRIPSTONE and fall >= minDripstoneFall,
                // expect dripstone damage (small falls can still damage)
                final double rfd = realFallDist;
                final long grace = damageGraceMs;
                final double nx = newX;
                final double ny = newY;
                final double nz = newZ;
                // Use cached dripstone result updated by a lightweight main-thread updater.
                boolean dripBelow = hasDripstoneBelow.getOrDefault(uuid, false);
                double startY = fallStartY.getOrDefault(uuid, ny);
                double fallToTip = startY - (ny - 1.0); // approximate
                double slack = configs != null ? configs.checks().getDouble("checks.NOFALL.params.dripstone_slack", 0.15) : 0.15;
                boolean meetsByRfd = rfd + slack >= minDripstoneFall;
                if (dripBelow && (meetsByRfd)) {
                    long landingTime = System.currentTimeMillis();
                    expectingDamageUntil.put(uuid, landingTime + grace);
                    expectedFallDistance.put(uuid, rfd);
                    double expected = computeExpectedFallDamage(Math.max(rfd, fallToTip), true);
                    expectedDamageAmount.put(uuid, expected);
                    expectingDripstone.put(uuid, true);
                    receivedFallDamage.put(uuid, false);

                    // Schedule expiry asynchronously
                    scheduleExpectationExpiry(uuid, grace);
                    logDebug(String.format("set dripstone expectation (cached) player=%s uuid=%s rfd=%.3f expected=%.2f grace=%d", player.getName(), uuid, rfd, expected, grace));
                }
                // Clear fall tracking now that scan decision is made
                resetFall(uuid);
            }
            
        }
        
        // Reset if going up
        if (!currentlyDescending && !clientOnGround && deltaY > 0.1) {
            resetFall(uuid);
        }
    }

    /**
     * Called when player receives damage - check if it's fall damage we were expecting.
     * This runs on main thread (Bukkit event).
     */
    @Override
    public void onDamage(PlayerData data, EntityDamageEvent event) {
        if (!enabled) return;

        Player player = data.player();
        if (player == null) return;

        UUID uuid = player.getUniqueId();

        // Only handle if we were expecting damage
        if (!expectingDamageUntil.containsKey(uuid)) return;

        // Any damage event during the expectation window counts as satisfying the fall expectation.
        long now = System.currentTimeMillis();
        lastDamageTime.put(uuid, now);
        logDebug(String.format("damage event (marking received) player=%s uuid=%s cause=%s dmg=%.2f", player.getName(), uuid, event.getCause().name(), event.getDamage()));
        // Mark received; do NOT clear the expectation maps here so the scheduled expiry can see it
        // and will clear the expectation without flagging.
        receivedFallDamage.put(uuid, true);
    }

    /**
     * Check player state exemptions.
     * Note: Some of these methods may access Bukkit API, but they're fast cached values.
     */
    private boolean shouldExemptCompletely(PlayerData data, Player player) {
        if (data.isExempt()) return true;
        if (player.isFlying() || player.getAllowFlight()) return true;
        if (player.isGliding()) {
            lastGlideTime.put(player.getUniqueId(), System.currentTimeMillis());
            return true;
        }
        if (player.isInsideVehicle()) return true;
        if (player.isRiptiding()) return true;
        if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        if (player.hasPotionEffect(PotionEffectType.LEVITATION)) return true;
        if (player.isSwimming()) return true;
        return false;
    }

    @SuppressWarnings("unused")
    private boolean isHoldingMace(Player player) {
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        return mainHand != null && mainHand.getType().name().equals("MACE");
    }
    
    /**
     * Unified block exemption check - MUST be called from main thread.
     * Checks for liquids, climbables, and fall-negating blocks in one optimized pass.
     */
    private boolean checkExemptBlocks(World world, int bx, int by, int bz) {
        // Check liquids (vertical range only - less iterations)
        for (int dy = -1; dy <= 1; dy++) {
            Block block = world.getBlockAt(bx, by + dy, bz);
            if (block.isLiquid() || block.getType() == Material.BUBBLE_COLUMN) return true;
            if (block.getBlockData() instanceof Waterlogged && ((Waterlogged) block.getBlockData()).isWaterlogged()) {
                return true;
            }
        }
        
        // Check climbables and fall-negating blocks (3x3 area)
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                // Current level - climbables
                Block block = world.getBlockAt(bx + dx, by, bz + dz);
                Material mat = block.getType();
                if (CLIMBABLE_BLOCKS.contains(mat) || mat.name().contains("VINE")) return true;
                
                // Below level - climbables and fall negating
                Block below = world.getBlockAt(bx + dx, by - 1, bz + dz);
                Material belowMat = below.getType();
                if (CLIMBABLE_BLOCKS.contains(belowMat)) return true;
                if (FALL_NEGATING_BLOCKS.contains(belowMat) || BEDS.contains(belowMat)) return true;
                
                // Two blocks below - fall negating only
                Block below2 = world.getBlockAt(bx + dx, by - 2, bz + dz);
                Material below2Mat = below2.getType();
                if (FALL_NEGATING_BLOCKS.contains(below2Mat) || BEDS.contains(below2Mat)) return true;
            }
        }
        
        return false;
    }
    
    /**
     * Schedule a task to run on the main server thread.
     */
    private void scheduleSync(Runnable task) {
        Plugin p = getPluginOrFallback();
        if (p != null && p.isEnabled()) {
            Bukkit.getScheduler().runTask(p, task);
        }
    }

    private void logDebug(String msg) {
        if (!debugLogs) return;
        Plugin p = getPluginOrFallback();
        if (p != null && p.isEnabled()) {
            p.getLogger().info("[NoFallDebug] " + msg);
        }
    }

    /**
     * Schedule an expiry check for an expected damage event on the main thread.
     * This ensures we flag even if the player stops sending packets after landing.
     */
    private void scheduleExpectationExpiry(UUID uuid, long graceMs) {
        // Schedule expiry asynchronously; only flag on main thread if needed.
        asyncExecutor.schedule(() -> {
            try {
                boolean received = receivedFallDamage.getOrDefault(uuid, false);
                double fallDist = expectedFallDistance.getOrDefault(uuid, 0.0);
                Boolean isDrip = expectingDripstone.getOrDefault(uuid, false);
                long lastDmg = lastDamageTime.getOrDefault(uuid, 0L);
                long cooldown = configs != null ? configs.checks().getLong("checks.NOFALL.params.alert_cooldown_ms", 1500) : 1500;

                logDebug(String.format("expiry running async uuid=%s received=%b lastDamage=%d fall=%.3f drip=%b now=%d", uuid, received, lastDmg, fallDist, isDrip, System.currentTimeMillis()));

                if (!received) {
                    long now = System.currentTimeMillis();
                    if (now - lastFlagTime.getOrDefault(uuid, 0L) >= cooldown) {
                        lastFlagTime.put(uuid, now);
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            String detail = String.format("no fall damage received, fall=%.1f drip=%b", fallDist, isDrip);
                            Plugin p = getPluginOrFallback();
                            if (p != null) {
                                final Player fp = player;
                                final String fdetail = detail;
                                Bukkit.getScheduler().runTask(p, () -> {
                                    try {
                                        violations.flag(fp, id().name(), 1, fdetail);
                                        logDebug(String.format("flagged player=%s uuid=%s fall=%.3f drip=%b (sync)", fp.getName(), uuid, fallDist, isDrip));
                                    } catch (Exception ignored) {}
                                });
                            }
                        }
                    } else {
                        logDebug(String.format("expiry skipped due cooldown for uuid=%s", uuid));
                    }
                } else {
                    logDebug(String.format("expiry saw damage for uuid=%s, clearing without flag", uuid));
                }

                // Clear expectation data
                expectingDamageUntil.remove(uuid);
                expectedFallDistance.remove(uuid);
                expectedDamageAmount.remove(uuid);
                expectingDripstone.remove(uuid);
                receivedFallDamage.remove(uuid);
            } catch (Exception ignored) {}
        }, Math.max(1, graceMs), TimeUnit.MILLISECONDS);
    }

    /**
     * Return the configured plugin or attempt to find the plugin by name as a fallback.
     */
    private Plugin getPluginOrFallback() {
        if (this.plugin != null) return this.plugin;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("VirgolaAntiCheat");
            if (p != null && p.isEnabled()) return p;
        } catch (Exception ignored) {}
        return null;
    }

    private double computeExpectedFallDamage(double fallDistance, boolean dripstone) {
        // Vanilla fall damage: damage = floor(fallDistance - 3)
        // For dripstone, small falls can still deal 1 damage starting from ~1.3 blocks.
        if (dripstone) {
            double base = Math.floor(fallDistance - 3.0);
            double dmg = Math.max(1.0, base);
            return dmg;
        } else {
            return Math.max(0.0, Math.floor(fallDistance - 3.0));
        }
    }

    private void resetFall(UUID uuid) {
        isFalling.put(uuid, false);
        fallStartY.remove(uuid);
        maxFallDistance.put(uuid, 0.0);
        hitWithMaceDuringFall.put(uuid, false);
    }

    @Override
    public void onQuit(UUID uuid) {
        lastX.remove(uuid);
        lastY.remove(uuid);
        lastZ.remove(uuid);
        fallStartY.remove(uuid);
        maxFallDistance.remove(uuid);
        isFalling.remove(uuid);
        expectingDamageUntil.remove(uuid);
        expectedFallDistance.remove(uuid);
        receivedFallDamage.remove(uuid);
        lastMaceHitTime.remove(uuid);
        hitWithMaceDuringFall.remove(uuid);
        lastFlagTime.remove(uuid);
        lastBlockCheckTime.remove(uuid);
        isNearExemptBlock.remove(uuid);
        lastGlideTime.remove(uuid);
    }
    
    /**
     * Shutdown the async executor when plugin disables.
     */
    public void shutdown() {
        asyncExecutor.shutdown();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            asyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            if (cacheUpdaterTask != null) cacheUpdaterTask.cancel();
        } catch (Exception ignored) {}
    }

    @Override
    public CheckId id() {
        return CheckId.NOFALL;
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
        // Reload config-driven flags if available
        if (configs != null) {
            this.debugLogs = configs.checks().getBoolean("checks.NOFALL.params.debug_logs", false);
        }
    }

    /**
     * Mark that the player hit an entity with a mace during a fall.
     * This will be used to ignore fall damage expectations for a short grace window.
     */
    public void markMaceHit(UUID uuid) {
        if (uuid == null) return;
        hitWithMaceDuringFall.put(uuid, true);
        lastMaceHitTime.put(uuid, System.currentTimeMillis());
    }
}

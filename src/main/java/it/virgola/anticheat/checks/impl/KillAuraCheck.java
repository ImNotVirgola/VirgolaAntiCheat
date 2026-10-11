package it.virgola.anticheat.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import it.virgola.anticheat.checks.Check;
import it.virgola.anticheat.checks.CheckId;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.LagCompensation;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.ViolationService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * KILLAURA - Combat Hack Detection (Packet-Based, Async-Optimized)
 * 
 * Sub-checks:
 * - REACH: Detects hits from distances beyond vanilla 3.0 blocks
 * - ANGLE: Detects hits when player is not looking at target
 * - NOSWING: Detects hits without arm swing animation packet
 * - GCD: Detects rotations that bypass Minecraft's GCD (sensitivity divisor)
 * - MULTIAURA: Detects hitting multiple entities in impossible timeframes
 * - PATTERN: Detects robotic consistency in rotation/timing patterns
 * 
 * All thresholds and messages are configurable.
 * Lag compensation is applied to all distance/timing calculations.
 */
public final class KillAuraCheck implements Check {

    // Sub-check types for specific alerts
    public enum SubCheck {
        REACH("Reach", "Hit distance exceeds maximum"),
        ANGLE("Angle", "Hit direction doesn't match aim"),
        NOSWING("NoSwing", "Attack without swing animation"),
        GCD("GCD", "Invalid rotation sensitivity pattern"),
        MULTIAURA("MultiAura", "Multiple targets in short timeframe"),
        PATTERN("Pattern", "Robotic rotation consistency");
        
        private final String name;
        private final String description;
        
        SubCheck(String name, String description) {
            this.name = name;
            this.description = description;
        }
        
        public String getName() { return name; }
        public String getDescription() { return description; }
    }

    private final Configs configs;
    private final ViolationService violations;
    private final LagCompensation lag;
    
    private Plugin plugin;
    private volatile boolean enabled = true;
    
    // Sub-check enable flags
    private volatile boolean reachEnabled = true;
    private volatile boolean angleEnabled = true;
    private volatile boolean noSwingEnabled = true;
    private volatile boolean gcdEnabled = true;
    private volatile boolean multiAuraEnabled = true;
    private volatile boolean patternEnabled = true;

    // ═══════════════════════════════════════════════════════════════
    // TRACKING DATA (all ConcurrentHashMap for thread safety)
    // ═══════════════════════════════════════════════════════════════
    
    // Rotation tracking (from packets)
    private final Map<UUID, Float> lastYaw = new ConcurrentHashMap<>();
    private final Map<UUID, Float> lastPitch = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRotationTime = new ConcurrentHashMap<>();
    private final Map<UUID, List<Float>> recentYawChanges = new ConcurrentHashMap<>();
    private final Map<UUID, List<Float>> recentPitchChanges = new ConcurrentHashMap<>();
    
    // Position tracking (from packets)
    private final Map<UUID, Double> lastX = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastY = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastZ = new ConcurrentHashMap<>();
    
    // Swing tracking
    private final Map<UUID, Long> lastSwingTime = new ConcurrentHashMap<>();
    
    // Hit tracking
    private final Map<UUID, Long> lastHitTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> lastHitEntityId = new ConcurrentHashMap<>();
    private final Map<UUID, List<Long>> recentHitTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> entitiesHitInWindow = new ConcurrentHashMap<>();
    
    // GCD analysis
    private final Map<UUID, List<Double>> gcdSamples = new ConcurrentHashMap<>();
    @SuppressWarnings("unused")
    private static final double MINECRAFT_GCD = 0.15; // Minecraft's rotation GCD factor
    
    // Buffer tracking per sub-check
    private final Map<UUID, Map<SubCheck, Double>> buffers = new ConcurrentHashMap<>();
    
    // Alert cooldowns per sub-check
    private final Map<UUID, Map<SubCheck, Long>> lastAlertTime = new ConcurrentHashMap<>();

    public KillAuraCheck(Configs configs, ViolationService violations, LagCompensation lag) {
        this.configs = configs;
        this.violations = violations;
        this.lag = lag;
    }
    
    public void setPlugin(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CheckId id() {
        return CheckId.KILLAURA;
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
        this.enabled = configs.checks().getBoolean("checks.KILLAURA.enabled", true);
        
        // Load sub-check enables
        this.reachEnabled = configs.checks().getBoolean("checks.KILLAURA.sub_checks.reach.enabled", true);
        this.angleEnabled = configs.checks().getBoolean("checks.KILLAURA.sub_checks.angle.enabled", true);
        this.noSwingEnabled = configs.checks().getBoolean("checks.KILLAURA.sub_checks.noswing.enabled", true);
        this.gcdEnabled = configs.checks().getBoolean("checks.KILLAURA.sub_checks.gcd.enabled", true);
        this.multiAuraEnabled = configs.checks().getBoolean("checks.KILLAURA.sub_checks.multiaura.enabled", true);
        this.patternEnabled = configs.checks().getBoolean("checks.KILLAURA.sub_checks.pattern.enabled", true);
    }

    @Override
    public void onPacketReceive(PlayerData data, PacketReceiveEvent event) {
        if (!enabled) return;
        
        Player player = data.player();
        if (player == null || !player.isOnline()) return;
        if (data.isExempt()) return;
        
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        
        // ═══════════════════════════════════════════════════════════════
        // SWING ARM PACKET - Track swing timing
        // ═══════════════════════════════════════════════════════════════
        if (event.getPacketType() == PacketType.Play.Client.ANIMATION) {
            lastSwingTime.put(uuid, now);
            return;
        }
        
        // ═══════════════════════════════════════════════════════════════
        // FLYING PACKET - Track rotation and position
        // ═══════════════════════════════════════════════════════════════
        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying wrapper;
            try {
                wrapper = new WrapperPlayClientPlayerFlying(event);
            } catch (Exception e) {
                return;
            }
            
            // Track position from packet
            if (wrapper.hasPositionChanged()) {
                lastX.put(uuid, wrapper.getLocation().getX());
                lastY.put(uuid, wrapper.getLocation().getY());
                lastZ.put(uuid, wrapper.getLocation().getZ());
            }
            
            // Track rotation from packet
            if (wrapper.hasRotationChanged()) {
                float newYaw = wrapper.getLocation().getYaw();
                float newPitch = wrapper.getLocation().getPitch();
                
                Float oldYaw = lastYaw.get(uuid);
                Float oldPitch = lastPitch.get(uuid);
                
                if (oldYaw != null && oldPitch != null) {
                    float deltaYaw = Math.abs(wrapAngle(newYaw - oldYaw));
                    float deltaPitch = Math.abs(newPitch - oldPitch);
                    
                    // Store recent rotation changes for pattern analysis
                    addToRotationHistory(uuid, deltaYaw, deltaPitch);
                    
                    // GCD Analysis - ONLY during active combat (within 3 seconds of attack)
                    // This prevents false positives from normal mouse movement
                    Long lastHit = lastHitTime.get(uuid);
                    boolean inCombat = lastHit != null && (now - lastHit) < 3000;
                    
                    if (gcdEnabled && inCombat && (deltaYaw > 0.1 || deltaPitch > 0.1)) {
                        analyzeGCD(uuid, deltaYaw, deltaPitch, player, now);
                    }
                }
                
                lastYaw.put(uuid, newYaw);
                lastPitch.put(uuid, newPitch);
                lastRotationTime.put(uuid, now);
            }
            return;
        }
        
        // ═══════════════════════════════════════════════════════════════
        // INTERACT ENTITY PACKET - Main combat analysis
        // ═══════════════════════════════════════════════════════════════
        if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity wrapper;
            try {
                wrapper = new WrapperPlayClientInteractEntity(event);
            } catch (Exception e) {
                return;
            }
            
            // Only check ATTACK action
            if (wrapper.getAction() != WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                return;
            }
            
            int entityId = wrapper.getEntityId();
            
            // Get lag multiplier for this player
            double lagMult = lag.multiplier(player);
            
            // Schedule entity lookup and checks on main thread
            scheduleSync(() -> {
                Entity target = findEntityById(player.getWorld(), entityId);
                if (target == null || !(target instanceof LivingEntity)) return;
                if (target.equals(player)) return; // Self-hit
                
                LivingEntity livingTarget = (LivingEntity) target;
                
                // Get stored packet position (more accurate than player.getLocation())
                Double px = lastX.get(uuid);
                Double py = lastY.get(uuid);
                Double pz = lastZ.get(uuid);
                
                if (px == null || py == null || pz == null) {
                    // Fallback to player location
                    Location loc = player.getLocation();
                    px = loc.getX();
                    py = loc.getY();
                    pz = loc.getZ();
                }
                
                // Create position vectors
                Vector playerPos = new Vector(px, py + player.getEyeHeight(), pz);
                Vector targetPos = target.getLocation().toVector().add(new Vector(0, livingTarget.getEyeHeight() * 0.5, 0));
                
                // Run all sub-checks
                checkReach(uuid, player, playerPos, targetPos, lagMult, now);
                checkAngle(uuid, player, playerPos, targetPos, lagMult, now);
                checkNoSwing(uuid, player, now);
                checkMultiAura(uuid, player, entityId, now);
                checkPattern(uuid, player, now);
                
                // Update hit tracking
                lastHitTime.put(uuid, now);
                lastHitEntityId.put(uuid, entityId);
                addHitToHistory(uuid, now);
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUB-CHECK: REACH
    // ═══════════════════════════════════════════════════════════════
    private void checkReach(UUID uuid, Player player, Vector playerPos, Vector targetPos, double lagMult, long now) {
        if (!reachEnabled) return;
        if (isSubCheckIgnoredForMode(player, SubCheck.REACH)) return;
        
        double distance = playerPos.distance(targetPos);

        // Base max reach + lag compensation
        double maxReach = getModeDouble(player, SubCheck.REACH, "max_distance", 3.0);
        double lagBuffer = getModeDouble(player, SubCheck.REACH, "lag_buffer", 0.3);
        double allowedReach = maxReach + (lagBuffer * lagMult);

        if (player.getGameMode() == GameMode.CREATIVE) {
            double creativeMaxReach = getModeDouble(player, SubCheck.REACH, "creative_max_distance", 5.0);
            double creativeReach = creativeMaxReach + (lagBuffer * lagMult);
            allowedReach = Math.max(allowedReach, creativeReach);
        }

        if (distance > allowedReach) {
            double excess = distance - allowedReach;
            double weight = getModeDouble(player, SubCheck.REACH, "weight", 1.0);
            double threshold = getModeDouble(player, SubCheck.REACH, "threshold", 3.0);
            
            // Weight increases with excess distance
            double points = weight * (1 + excess);
            
            if (addBuffer(uuid, SubCheck.REACH, points, threshold)) {
                String detail = String.format("dist=%.2f max=%.2f", distance, allowedReach);
                flagSubCheck(player, SubCheck.REACH, detail, now);
            }
        } else {
            // Decay buffer
            double decay = getModeDouble(player, SubCheck.REACH, "decay", 0.5);
            decayBuffer(uuid, SubCheck.REACH, decay);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUB-CHECK: ANGLE
    // ═══════════════════════════════════════════════════════════════
    private void checkAngle(UUID uuid, Player player, Vector playerPos, Vector targetPos, double lagMult, long now) {
        if (!angleEnabled) return;
        if (isSubCheckIgnoredForMode(player, SubCheck.ANGLE)) return;
        
        Float yaw = lastYaw.get(uuid);
        Float pitch = lastPitch.get(uuid);
        if (yaw == null || pitch == null) return;
        
        // Calculate direction player is looking
        Vector lookDir = getDirectionFromRotation(yaw, pitch);
        
        // Calculate direction to target
        Vector toTarget = targetPos.clone().subtract(playerPos).normalize();
        
        // Calculate angle between look direction and target direction
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, lookDir.dot(toTarget)))));
        
        // Max allowed angle + lag compensation
        double maxAngle = getModeDouble(player, SubCheck.ANGLE, "max_angle", 60.0);
        double lagAngleBuffer = getModeDouble(player, SubCheck.ANGLE, "lag_buffer", 10.0);
        double allowedAngle = maxAngle + (lagAngleBuffer * lagMult);
        
        if (angle > allowedAngle) {
            double excess = angle - allowedAngle;
            double weight = getModeDouble(player, SubCheck.ANGLE, "weight", 1.5);
            double threshold = getModeDouble(player, SubCheck.ANGLE, "threshold", 4.0);
            
            double points = weight * (1 + excess / 30.0);
            
            if (addBuffer(uuid, SubCheck.ANGLE, points, threshold)) {
                String detail = String.format("angle=%.1f° max=%.1f°", angle, allowedAngle);
                flagSubCheck(player, SubCheck.ANGLE, detail, now);
            }
        } else {
            double decay = getModeDouble(player, SubCheck.ANGLE, "decay", 0.4);
            decayBuffer(uuid, SubCheck.ANGLE, decay);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUB-CHECK: NOSWING
    // ═══════════════════════════════════════════════════════════════
    private void checkNoSwing(UUID uuid, Player player, long now) {
        if (!noSwingEnabled) return;
        if (isSubCheckIgnoredForMode(player, SubCheck.NOSWING)) return;
        
        Long lastSwing = lastSwingTime.get(uuid);
        long maxSwingDelay = getModeLong(player, SubCheck.NOSWING, "max_delay_ms", 100);
        
        boolean hasRecentSwing = lastSwing != null && (now - lastSwing) <= maxSwingDelay;
        
        if (!hasRecentSwing) {
            double weight = getModeDouble(player, SubCheck.NOSWING, "weight", 2.0);
            double threshold = getModeDouble(player, SubCheck.NOSWING, "threshold", 4.0);
            
            if (addBuffer(uuid, SubCheck.NOSWING, weight, threshold)) {
                long timeSinceSwing = lastSwing == null ? -1 : (now - lastSwing);
                String detail = String.format("no_swing_in=%dms", timeSinceSwing);
                flagSubCheck(player, SubCheck.NOSWING, detail, now);
            }
        } else {
            double decay = getModeDouble(player, SubCheck.NOSWING, "decay", 1.0);
            decayBuffer(uuid, SubCheck.NOSWING, decay);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUB-CHECK: GCD (Rotation Sensitivity)
    // Only runs during active combat to avoid false positives
    // ═══════════════════════════════════════════════════════════════
    private void analyzeGCD(UUID uuid, float deltaYaw, float deltaPitch, Player player, long now) {
        if (isSubCheckIgnoredForMode(player, SubCheck.GCD)) return;
        List<Double> samples = gcdSamples.computeIfAbsent(uuid, k -> new ArrayList<>());

        // Calculate GCD of the rotation
        double gcd = gcd(deltaYaw, deltaPitch);
        
        // Filter out very small rotations (likely normal aiming)
        if (gcd < 0.001) return;
        
        samples.add(gcd);
        
        // Keep only recent samples
        int maxSamples = getModeInt(player, SubCheck.GCD, "sample_size", 30);
        while (samples.size() > maxSamples) {
            samples.remove(0);
        }
        
        // Need more samples for accurate analysis (reduces false positives)
        int minSamples = getModeInt(player, SubCheck.GCD, "min_samples", 20);
        if (samples.size() < minSamples) return;
        
        // Analyze GCD consistency
        double avgGcd = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = samples.stream().mapToDouble(d -> Math.pow(d - avgGcd, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        
        // Valid Minecraft rotations have some natural variance
        // Aimbots have extremely low GCD and very consistent (low variance)
        double minValidGcd = getModeDouble(player, SubCheck.GCD, "min_valid_gcd", 0.005);
        double maxStdDev = getModeDouble(player, SubCheck.GCD, "max_std_dev", 0.0005);
        
        // Flag only if GCD is suspiciously low AND variance is robotically consistent
        boolean invalidGcd = avgGcd < minValidGcd && stdDev < maxStdDev;
        
        if (invalidGcd) {
            double weight = getModeDouble(player, SubCheck.GCD, "weight", 1.0);
            double threshold = getModeDouble(player, SubCheck.GCD, "threshold", 6.0);
            
            if (addBuffer(uuid, SubCheck.GCD, weight, threshold)) {
                String detail = String.format("gcd=%.4f stdDev=%.6f", avgGcd, stdDev);
                scheduleSync(() -> flagSubCheck(player, SubCheck.GCD, detail, now));
                // Clear samples after flag to require fresh evidence
                samples.clear();
            }
        } else {
            double decay = getModeDouble(player, SubCheck.GCD, "decay", 0.5);
            decayBuffer(uuid, SubCheck.GCD, decay);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUB-CHECK: MULTIAURA
    // ═══════════════════════════════════════════════════════════════
    private void checkMultiAura(UUID uuid, Player player, int entityId, long now) {
        if (!multiAuraEnabled) return;
        if (isSubCheckIgnoredForMode(player, SubCheck.MULTIAURA)) return;
        
        long windowMs = getModeLong(player, SubCheck.MULTIAURA, "window_ms", 500);
        int maxTargets = getModeInt(player, SubCheck.MULTIAURA, "max_targets", 2);
        
        Set<Integer> entities = entitiesHitInWindow.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        
        // Clean old entities from window
        Long lastHit = lastHitTime.get(uuid);
        if (lastHit != null && (now - lastHit) > windowMs) {
            entities.clear();
        }
        
        entities.add(entityId);
        
        if (entities.size() > maxTargets) {
            double weight = getModeDouble(player, SubCheck.MULTIAURA, "weight", 2.0);
            double threshold = getModeDouble(player, SubCheck.MULTIAURA, "threshold", 3.0);
            
            if (addBuffer(uuid, SubCheck.MULTIAURA, weight, threshold)) {
                String detail = String.format("targets=%d in %dms", entities.size(), windowMs);
                flagSubCheck(player, SubCheck.MULTIAURA, detail, now);
            }
            entities.clear();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // SUB-CHECK: PATTERN (Rotation Consistency)
    // ═══════════════════════════════════════════════════════════════
    private void checkPattern(UUID uuid, Player player, long now) {
        if (!patternEnabled) return;
        if (isSubCheckIgnoredForMode(player, SubCheck.PATTERN)) return;
        
        List<Float> yawChanges = recentYawChanges.get(uuid);
        List<Float> pitchChanges = recentPitchChanges.get(uuid);
        
        if (yawChanges == null || pitchChanges == null) return;
        
        int minSamples = getModeInt(player, SubCheck.PATTERN, "min_samples", 10);
        if (yawChanges.size() < minSamples) return;
        
        // Calculate variance of rotation changes
        double yawVariance = calculateVariance(yawChanges);
        double pitchVariance = calculateVariance(pitchChanges);
        
        // Humans have natural variance, bots are consistent
        double minVariance = getModeDouble(player, SubCheck.PATTERN, "min_variance", 0.5);
        
        // Both yaw AND pitch must be robotic
        boolean robotic = yawVariance < minVariance && pitchVariance < minVariance;
        
        // Also check if rotations are "too perfect" (exactly same values repeating)
        boolean repeating = hasRepeatingPattern(yawChanges) || hasRepeatingPattern(pitchChanges);
        
        if (robotic || repeating) {
            double weight = getModeDouble(player, SubCheck.PATTERN, "weight", 1.0);
            double threshold = getModeDouble(player, SubCheck.PATTERN, "threshold", 5.0);
            
            if (addBuffer(uuid, SubCheck.PATTERN, weight, threshold)) {
                String detail = String.format("yaw_var=%.3f pitch_var=%.3f repeat=%s", 
                    yawVariance, pitchVariance, repeating);
                flagSubCheck(player, SubCheck.PATTERN, detail, now);
            }
        } else {
            double decay = getModeDouble(player, SubCheck.PATTERN, "decay", 0.2);
            decayBuffer(uuid, SubCheck.PATTERN, decay);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════
    private boolean isSubCheckIgnoredForMode(Player player, SubCheck subCheck) {
        if (player == null) return false;
        // Always ignore in spectator
        if (player.getGameMode() == GameMode.SPECTATOR) return true;

        String key = String.format("checks.KILLAURA.sub_checks.%s.ignore_in_modes", subCheck.name().toLowerCase());
        java.util.List<String> modes = configs.checks().getStringList(key);
        if (modes == null || modes.isEmpty()) return false;
        String current = player.getGameMode().name();
        for (String m : modes) {
            if (m == null) continue;
            if (m.trim().equalsIgnoreCase(current)) return true;
        }
        return false;
    }

    private double getModeDouble(Player player, SubCheck sub, String key, double def) {
        if (player == null) return def;
        String mode = player.getGameMode().name();
        String modePath = String.format("checks.KILLAURA.mode_overrides.%s.sub_checks.%s.%s", mode, sub.name().toLowerCase(), key);
        double v = configs.checks().getDouble(modePath, Double.NaN);
        if (!Double.isNaN(v)) return v;
        return configs.checks().getDouble(String.format("checks.KILLAURA.sub_checks.%s.%s", sub.name().toLowerCase(), key), def);
    }

    private int getModeInt(Player player, SubCheck sub, String key, int def) {
        if (player == null) return def;
        String mode = player.getGameMode().name();
        String modePath = String.format("checks.KILLAURA.mode_overrides.%s.sub_checks.%s.%s", mode, sub.name().toLowerCase(), key);
        int v = configs.checks().getInt(modePath, Integer.MIN_VALUE);
        if (v != Integer.MIN_VALUE) return v;
        return configs.checks().getInt(String.format("checks.KILLAURA.sub_checks.%s.%s", sub.name().toLowerCase(), key), def);
    }

    private long getModeLong(Player player, SubCheck sub, String key, long def) {
        if (player == null) return def;
        String mode = player.getGameMode().name();
        String modePath = String.format("checks.KILLAURA.mode_overrides.%s.sub_checks.%s.%s", mode, sub.name().toLowerCase(), key);
        long v = configs.checks().getLong(modePath, Long.MIN_VALUE);
        if (v != Long.MIN_VALUE) return v;
        return configs.checks().getLong(String.format("checks.KILLAURA.sub_checks.%s.%s", sub.name().toLowerCase(), key), def);
    }
    
    private void flagSubCheck(Player player, SubCheck subCheck, String detail, long now) {
        // Check alert cooldown for this specific sub-check
        UUID uuid = player.getUniqueId();
        Map<SubCheck, Long> cooldowns = lastAlertTime.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        
        long cooldownMs = configs.checks().getLong("checks.KILLAURA.alert_cooldown_ms", 1000);
        Long lastAlert = cooldowns.get(subCheck);
        
        if (lastAlert != null && (now - lastAlert) < cooldownMs) {
            return;
        }
        
        cooldowns.put(subCheck, now);
        
        // Format: "KillAura (Reach)" or "KillAura (NoSwing)" etc.
        String checkName = String.format("KillAura (%s)", subCheck.getName());
        violations.flag(player, checkName, 1, detail);
        
        // Reset buffer after flag
        double postFlagReduce = configs.checks().getDouble("checks.KILLAURA.post_flag_reduce", 2.0);
        Map<SubCheck, Double> playerBuffers = buffers.get(uuid);
        if (playerBuffers != null) {
            playerBuffers.merge(subCheck, 0.0, (old, v) -> Math.max(0, old - postFlagReduce));
        }
    }
    
    private boolean addBuffer(UUID uuid, SubCheck subCheck, double amount, double threshold) {
        Map<SubCheck, Double> playerBuffers = buffers.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        @SuppressWarnings("null")
        double newValue = playerBuffers.merge(subCheck, amount, Double::sum);
        return newValue >= threshold;
    }
    
    private void decayBuffer(UUID uuid, SubCheck subCheck, double amount) {
        Map<SubCheck, Double> playerBuffers = buffers.get(uuid);
        if (playerBuffers != null) {
            playerBuffers.merge(subCheck, 0.0, (old, v) -> Math.max(0, old - amount));
        }
    }
    
    private void addToRotationHistory(UUID uuid, float deltaYaw, float deltaPitch) {
        int maxHistory = 20;
        
        List<Float> yawList = recentYawChanges.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()));
        List<Float> pitchList = recentPitchChanges.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()));
        
        synchronized (yawList) {
            yawList.add(deltaYaw);
            while (yawList.size() > maxHistory) yawList.remove(0);
        }
        
        synchronized (pitchList) {
            pitchList.add(deltaPitch);
            while (pitchList.size() > maxHistory) pitchList.remove(0);
        }
    }
    
    private void addHitToHistory(UUID uuid, long time) {
        List<Long> hits = recentHitTimes.computeIfAbsent(uuid, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (hits) {
            hits.add(time);
            // Keep last 20 hits
            while (hits.size() > 20) hits.remove(0);
        }
    }
    
    private Entity findEntityById(org.bukkit.World world, int entityId) {
        for (Entity entity : world.getEntities()) {
            if (entity.getEntityId() == entityId) {
                return entity;
            }
        }
        return null;
    }
    
    private Vector getDirectionFromRotation(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        
        double x = -Math.sin(yawRad) * Math.cos(pitchRad);
        double y = -Math.sin(pitchRad);
        double z = Math.cos(yawRad) * Math.cos(pitchRad);
        
        return new Vector(x, y, z).normalize();
    }
    
    private float wrapAngle(float angle) {
        angle = angle % 360.0f;
        if (angle >= 180.0f) angle -= 360.0f;
        if (angle < -180.0f) angle += 360.0f;
        return angle;
    }
    
    private double gcd(double a, double b) {
        a = Math.abs(a);
        b = Math.abs(b);
        
        while (b > 0.0001) {
            double temp = b;
            b = a % b;
            a = temp;
        }
        return a;
    }
    
    private double calculateVariance(List<Float> values) {
        if (values == null || values.isEmpty()) return Double.MAX_VALUE;
        
        double mean;
        double sumSquares;
        
        synchronized (values) {
            if (values.isEmpty()) return Double.MAX_VALUE;
            mean = values.stream().mapToDouble(Float::doubleValue).average().orElse(0);
            sumSquares = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();
        }
        
        return sumSquares / values.size();
    }
    
    private boolean hasRepeatingPattern(List<Float> values) {
        if (values == null || values.size() < 4) return false;
        
        synchronized (values) {
            int repeats = 0;
            for (int i = 1; i < values.size(); i++) {
                if (Math.abs(values.get(i) - values.get(i-1)) < 0.001) {
                    repeats++;
                }
            }
            // More than 50% identical = suspicious
            return repeats > values.size() / 2;
        }
    }
    
    private void scheduleSync(Runnable task) {
        if (plugin != null && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    @Override
    public void onQuit(UUID uuid) {
        lastYaw.remove(uuid);
        lastPitch.remove(uuid);
        lastRotationTime.remove(uuid);
        recentYawChanges.remove(uuid);
        recentPitchChanges.remove(uuid);
        lastX.remove(uuid);
        lastY.remove(uuid);
        lastZ.remove(uuid);
        lastSwingTime.remove(uuid);
        lastHitTime.remove(uuid);
        lastHitEntityId.remove(uuid);
        recentHitTimes.remove(uuid);
        entitiesHitInWindow.remove(uuid);
        gcdSamples.remove(uuid);
        buffers.remove(uuid);
        lastAlertTime.remove(uuid);
    }
}

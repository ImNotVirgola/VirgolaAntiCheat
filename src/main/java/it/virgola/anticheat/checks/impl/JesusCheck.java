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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JESUS - Water Walking Detection
 * Detects players walking on water without sinking
 */
public final class JesusCheck implements Check {

    private final Configs configs;
    private final ViolationService violations;
    private final LagCompensation lag;

    private volatile boolean enabled = true;

    private final Map<UUID, Double> lastY = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> flatTicks = new ConcurrentHashMap<>();
    private final Map<UUID, Double> buffer = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastFlagTime = new ConcurrentHashMap<>();

    public JesusCheck(Configs configs, ViolationService violations, LagCompensation lag) {
        this.configs = configs;
        this.violations = violations;
        this.lag = lag;
    }

    @Override
    public CheckId id() {
        return CheckId.JESUS;
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
        this.enabled = configs.checks().getBoolean("checks.JESUS.enabled", true);
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
            player.isInsideVehicle() || player.isRiptiding() || player.isSwimming()) {
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

        Double prevY = lastY.put(uuid, y);
        Long prevTime = lastPacketTime.put(uuid, now);
        if (prevY == null || prevTime == null) return;

        double dy = y - prevY;
        long dt = now - prevTime;
        double m = lag.multiplier(player);

        if (dt < 10 || dt > 300) return;

        World world = player.getWorld();
        Location loc = new Location(world, x, y, z);

        // Check water conditions
        WaterInfo water = checkWater(loc, world);
        
        if (!water.nearWater) {
            reset(uuid);
            return;
        }

        // Frost Walker exemption
        if (hasFrostWalker(player)) {
            reset(uuid);
            return;
        }

        // Has solid support = legit
        if (water.hasSolidBelow) {
            decay(uuid, 0.3);
            return;
        }

        // Detection: onGround=true on water OR very stable Y on water surface
        boolean suspicious = false;
        String reason = "";

        // Check 1: onGround=true while on water
        if (onGround && water.atSurface) {
            suspicious = true;
            reason = "GROUND_ON_WATER";
        }

        // Check 2: Stable Y on water surface
        if (!suspicious && water.atSurface) {
            double dyAbs = Math.abs(dy);
            if (dyAbs < 0.005 * m) {
                int flat = flatTicks.merge(uuid, 1, (a, b) -> a + b);
                if (flat >= getInt("min_flat_ticks", 8)) {
                    suspicious = true;
                    reason = "FLAT_Y";
                }
            } else {
                flatTicks.put(uuid, 0);
            }
        }

        if (!suspicious) {
            decay(uuid, 0.2);
            return;
        }

        // Add to buffer
        double weight = getDouble("weight", 1.2);
        double buf = buffer.merge(uuid, weight, (a, b) -> a + b);

        double threshold = getDouble("threshold", 3.0);
        long cooldown = (long) (getLong("alert_cooldown_ms", 1200) * m);
        long lastFlag = lastFlagTime.getOrDefault(uuid, 0L);

        if (buf >= threshold && (now - lastFlag) >= cooldown) {
            lastFlagTime.put(uuid, now);
            violations.flag(data, id().name(), 1,
                    reason + " dy=" + fmt(dy) + " ground=" + onGround + " buf=" + fmt(buf));
            buffer.put(uuid, Math.max(0, buf - getDouble("post_flag_reduce", 1.5)));
        }
    }

    private WaterInfo checkWater(Location loc, World world) {
        WaterInfo info = new WaterInfo();
        
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();
        double fracY = loc.getY() - by;

        Block atFeet = world.getBlockAt(bx, by, bz);
        Block below = world.getBlockAt(bx, by - 1, bz);
        Block above = world.getBlockAt(bx, by + 1, bz);

        boolean waterAtFeet = isWater(atFeet.getType());
        boolean waterBelow = isWater(below.getType());

        info.nearWater = waterAtFeet || waterBelow;
        if (!info.nearWater) return info;

        boolean airAbove = above.getType().isAir() || above.getType() == Material.LILY_PAD;

        // At surface check
        if (waterBelow && fracY >= 0.0 && airAbove) {
            info.atSurface = true;
        } else if (waterAtFeet && fracY >= 0.8 && airAbove) {
            info.atSurface = true;
        }

        // Check solid support
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                Block check = world.getBlockAt(bx + ox, by - 1, bz + oz);
                Material mat = check.getType();
                if (mat.isSolid() && !isWater(mat) && mat != Material.LAVA) {
                    info.hasSolidBelow = true;
                    break;
                }
                Block checkAbove = world.getBlockAt(bx + ox, by, bz + oz);
                if (checkAbove.getType() == Material.LILY_PAD) {
                    info.hasSolidBelow = true;
                    break;
                }
            }
        }

        return info;
    }

    private static class WaterInfo {
        boolean nearWater = false;
        boolean atSurface = false;
        boolean hasSolidBelow = false;
    }

    private boolean isWater(Material mat) {
        return mat == Material.WATER;
    }

    private boolean hasFrostWalker(Player player) {
        var boots = player.getInventory().getBoots();
        if (boots == null) return false;
        return boots.containsEnchantment(Enchantment.FROST_WALKER);
    }

    private void decay(UUID uuid, double amount) {
        buffer.merge(uuid, -amount, (old, delta) -> Math.max(0, old + delta));
    }

    private void reset(UUID uuid) {
        flatTicks.put(uuid, 0);
        buffer.put(uuid, 0.0);
    }

    @Override
    public void onQuit(UUID uuid) {
        lastY.remove(uuid);
        lastPacketTime.remove(uuid);
        flatTicks.remove(uuid);
        buffer.remove(uuid);
        lastFlagTime.remove(uuid);
    }

    private double getDouble(String key, double def) {
        return configs.checks().getDouble("checks.JESUS.params." + key, def);
    }

    private int getInt(String key, int def) {
        return configs.checks().getInt("checks.JESUS.params." + key, def);
    }

    private long getLong(String key, long def) {
        return configs.checks().getLong("checks.JESUS.params." + key, def);
    }

    private static String fmt(double d) {
        return String.format("%.4f", d);
    }
}

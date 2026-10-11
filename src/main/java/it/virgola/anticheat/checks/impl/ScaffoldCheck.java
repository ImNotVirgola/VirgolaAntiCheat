package it.virgola.anticheat.checks.impl;

import it.virgola.anticheat.checks.Check;
import it.virgola.anticheat.checks.CheckId;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.LagCompensation;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.ViolationService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SCAFFOLD - Scaffold/Tower Hack Detection
 * 
 * Logica semplice:
 * 1. Il blocco è stato piazzato SOTTO al player?
 * 2. Se sì: il player stava guardando la faccia TOP del blocco "against"?
 * 3. Se non stava guardando la faccia (angolo troppo alto / pitch troppo basso) = CHEAT
 */
public final class ScaffoldCheck implements Check {

    private final Configs configs;
    private final ViolationService violations;
    private final LagCompensation lag;

    private volatile boolean enabled = true;

    private final Map<UUID, Long> lastFlagMs = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> violationCount = new ConcurrentHashMap<>();

    public ScaffoldCheck(Configs configs, ViolationService violations, LagCompensation lag) {
        this.configs = configs;
        this.violations = violations;
        this.lag = lag;
    }

    @Override
    public CheckId id() {
        return CheckId.SCAFFOLD;
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
        this.enabled = configs.checks().getBoolean("checks.SCAFFOLD.enabled", true);
    }

    @Override
    public void onBlockPlace(PlayerData data, BlockPlaceEvent event) {
        if (!enabled) return;
        if (data == null || event == null) return;

        Player player = event.getPlayer();
        if (player == null) return;

        if (data.isExempt()) return;

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        
        if (placed == null || against == null) return;

        Material type = placed.getType();
        if (!type.isBlock() || type.isAir() || !type.isSolid()) return;

        if (placed.getLocation().equals(against.getLocation())) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Lag multiplier
        double lagMult = 1.0;
        try {
            lagMult = lag != null ? lag.multiplier(player) : 1.0;
        } catch (Throwable ignored) {}
        lagMult = Math.max(1.0, Math.min(lagMult, 2.5));

        Location loc = player.getLocation();
        Location eyeLoc = player.getEyeLocation();
        float pitch = loc.getPitch();
        
        int placedY = placed.getY();
        int playerFeetY = (int) Math.floor(loc.getY());

        // CHECK 1: Il blocco è SOTTO al player?
        boolean isBlockUnderPlayer = (placedY == playerFeetY - 1);
        
        double hDistX = loc.getX() - (placed.getX() + 0.5);
        double hDistZ = loc.getZ() - (placed.getZ() + 0.5);
        double hDistSq = hDistX * hDistX + hDistZ * hDistZ;
        boolean isPlayerAboveBlock = hDistSq <= 2.25;
        
        if (!isBlockUnderPlayer || !isPlayerAboveBlock) {
            return;
        }

        // Determina la faccia cliccata
        BlockFace clickedFace = against.getFace(placed);
        if (clickedFace == null) {
            clickedFace = calculateClickedFace(against, placed);
        }

        // CHECK 2: Verifica se il player stava GUARDANDO la faccia cliccata
        boolean suspicious = false;
        String reason = "";

        Vector faceCenter = getBlockFaceCenter(against, clickedFace);
        Vector eyePos = eyeLoc.toVector();
        Vector lookDir = eyeLoc.getDirection().normalize();
        
        Vector toFaceCenter = faceCenter.clone().subtract(eyePos);
        double distToFace = toFaceCenter.length();
        
        if (distToFace > 0.01) {
            Vector toFaceDir = toFaceCenter.clone().multiply(1.0 / distToFace);
            
            double dot = lookDir.dot(toFaceDir);
            dot = Math.max(-1.0, Math.min(1.0, dot));
            double angleDeg = Math.toDegrees(Math.acos(dot));
            
            Vector faceNormal = getFaceNormal(clickedFace);
            double lookDotNormal = lookDir.dot(faceNormal);
            
            double maxAngle = getDouble("max_angle", 80.0) * lagMult;
            
            if (angleDeg > maxAngle) {
                suspicious = true;
                reason = "angle=" + fmt(angleDeg) + "°";
            }
            
            if (lookDotNormal > 0.1) {
                suspicious = true;
                reason += " wrongSide";
            }
            
            if (clickedFace == BlockFace.UP && pitch < 0) {
                suspicious = true;
                reason += " pitch=" + fmt(pitch) + "°(UP)";
            }
            
            if (clickedFace == BlockFace.DOWN && pitch > 0) {
                suspicious = true;
                reason += " pitch=" + fmt(pitch) + "°(DOWN)";
            }
            
            boolean isLateralFace = (clickedFace == BlockFace.NORTH || clickedFace == BlockFace.SOUTH ||
                                     clickedFace == BlockFace.EAST || clickedFace == BlockFace.WEST);
            
            if (isLateralFace) {
                double minLookDotForLateral = getDouble("min_look_dot_lateral", -0.1);
                
                if (lookDotNormal > minLookDotForLateral) {
                    suspicious = true;
                    reason += " lateralFace=" + clickedFace.name() + "+lookDot=" + fmt(lookDotNormal);
                }
            }
        }

        if (!suspicious) {
            int current = violationCount.getOrDefault(uuid, 0);
            if (current > 0) {
                violationCount.put(uuid, current - 1);
            }
            return;
        }

        int vl = violationCount.getOrDefault(uuid, 0) + 1;
        violationCount.put(uuid, vl);

        int minViolations = getInt("min_violations", 2);
        if (vl < minViolations) {
            return;
        }

        long cooldown = getLong("alert_cooldown_ms", 1000L);
        long lastFlag = lastFlagMs.getOrDefault(uuid, 0L);
        
        if ((now - lastFlag) < cooldown) {
            return;
        }

        lastFlagMs.put(uuid, now);
        violationCount.put(uuid, 0);

        violations.flag(data, id().name(), 1, reason.trim());
    }

    private BlockFace calculateClickedFace(Block against, Block placed) {
        int dx = placed.getX() - against.getX();
        int dy = placed.getY() - against.getY();
        int dz = placed.getZ() - against.getZ();
        
        if (dy == 1) return BlockFace.UP;
        if (dy == -1) return BlockFace.DOWN;
        if (dx == 1) return BlockFace.EAST;
        if (dx == -1) return BlockFace.WEST;
        if (dz == 1) return BlockFace.SOUTH;
        if (dz == -1) return BlockFace.NORTH;
        
        return BlockFace.UP;
    }
    
    private Vector getBlockFaceCenter(Block block, BlockFace face) {
        double x = block.getX() + 0.5;
        double y = block.getY() + 0.5;
        double z = block.getZ() + 0.5;
        
        switch (face) {
            case UP:    return new Vector(x, block.getY() + 1.0, z);
            case DOWN:  return new Vector(x, block.getY(), z);
            case NORTH: return new Vector(x, y, block.getZ());
            case SOUTH: return new Vector(x, y, block.getZ() + 1.0);
            case WEST:  return new Vector(block.getX(), y, z);
            case EAST:  return new Vector(block.getX() + 1.0, y, z);
            default:    return new Vector(x, y, z);
        }
    }
    
    private Vector getFaceNormal(BlockFace face) {
        switch (face) {
            case UP:    return new Vector(0, 1, 0);
            case DOWN:  return new Vector(0, -1, 0);
            case NORTH: return new Vector(0, 0, -1);
            case SOUTH: return new Vector(0, 0, 1);
            case WEST:  return new Vector(-1, 0, 0);
            case EAST:  return new Vector(1, 0, 0);
            default:    return new Vector(0, 1, 0);
        }
    }

    @Override
    public void onQuit(UUID uuid) {
        lastFlagMs.remove(uuid);
        violationCount.remove(uuid);
    }

    private String base(String key) {
        return "checks." + id().name() + ".params." + key;
    }

    private long getLong(String key, long def) {
        return configs.checks().getLong(base(key), def);
    }

    private double getDouble(String key, double def) {
        return configs.checks().getDouble(base(key), def);
    }

    private int getInt(String key, int def) {
        return configs.checks().getInt(base(key), def);
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.US, "%.1f", d);
    }
}

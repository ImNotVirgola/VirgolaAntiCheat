package it.virgola.anticheat.core;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;

public final class PlayerData {

    public record RotSample(long timeMs, float yaw, float pitch) {}

    private final Player player;

    // recent rotation samples (packet-based) for analysis
    private final Deque<RotSample> rots = new ArrayDeque<>();

    // packet-sampled horizontal speed using last server Location snapshots
    private volatile Location lastServerLoc;
    private volatile long lastServerLocMs;
    private volatile double lastHSpeed;

    // last packet rotation deltas
    private volatile float lastYaw;
    private volatile float lastPitch;
    private volatile float lastDYaw;
    private volatile float lastDPitch;
    private volatile long lastFlyingMs;

    // exemptions
    private volatile long exemptUntilMs;

    public PlayerData(Player player) {
        this.player = player;
        Location l = player.getLocation();
        this.lastServerLoc = l;
        this.lastServerLocMs = System.currentTimeMillis();
        this.lastYaw = l.getYaw();
        this.lastPitch = l.getPitch();
        this.lastFlyingMs = 0L;
    }

    public Player player() { return player; }

    public void addRotationSample(float yaw, float pitch) {
        long now = System.currentTimeMillis();
        rots.addLast(new RotSample(now, yaw, pitch));
        while (rots.size() > 40) rots.pollFirst();

        float dy = angleDelta(yaw, lastYaw);
        float dp = pitch - lastPitch;
        lastDYaw = Math.abs(dy);
        lastDPitch = Math.abs(dp);
        lastYaw = yaw;
        lastPitch = pitch;
        lastFlyingMs = now;
    }

    public Deque<RotSample> rots() { return rots; }

    public void updateServerLocation(Location loc) {
        long now = System.currentTimeMillis();
        Location prev = lastServerLoc;
        long prevMs = lastServerLocMs;
        lastServerLoc = loc;
        lastServerLocMs = now;

        if (prev != null && prevMs > 0L) {
            double dx = loc.getX() - prev.getX();
            double dz = loc.getZ() - prev.getZ();
            double dist = Math.sqrt(dx*dx + dz*dz);
            double dt = Math.max(1, now - prevMs);
            // blocks per millisecond * 50 -> blocks per tick (approx)
            lastHSpeed = dist / dt * 50.0;
        }
    }

    public double lastHSpeed() { return lastHSpeed; }
    public float lastDYaw() { return lastDYaw; }
    public float lastDPitch() { return lastDPitch; }
    public long lastFlyingMs() { return lastFlyingMs; }

    public float lastYaw() { return lastYaw; }
    public float lastPitch() { return lastPitch; }

    /** Alias used by listeners. */
    public void pushServerLocation(Location loc) {
        updateServerLocation(loc);
    }

    /** Alias used by packet pipeline. */
    public void pushRotation(float yaw, float pitch) {
        addRotationSample(yaw, pitch);
    }

    public void setLastFlyingPacketTimeMs(long timeMs) {
        this.lastFlyingMs = timeMs;
    }


    public void exemptFor(long ms) {
        exemptUntilMs = Math.max(exemptUntilMs, System.currentTimeMillis() + ms);
    }

    public boolean isExempt() {
        return System.currentTimeMillis() < exemptUntilMs;
    }

    private static float angleDelta(float a, float b) {
        float d = a - b;
        while (d <= -180f) d += 360f;
        while (d > 180f) d -= 360f;
        return d;
    }
}

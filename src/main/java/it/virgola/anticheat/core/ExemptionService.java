package it.virgola.anticheat.core;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized exemption windows (fail-open).
 * Listeners set short grace periods for legit mechanics (teleports, riptide, velocity, etc.).
 * Checks query this service and skip detection while exempt is active.
 */
public final class ExemptionService {

    // per-player exemptions (reason -> untilMs)
    private final Map<UUID, Map<String, Long>> untilByReason = new ConcurrentHashMap<>();

    public void exempt(Player player, String reason, long durationMs) {
        if (player == null || reason == null) return;
        UUID uuid = player.getUniqueId();
        long until = System.currentTimeMillis() + Math.max(0L, durationMs);
        untilByReason
                .computeIfAbsent(uuid, k -> new ConcurrentHashMap<>())
                .merge(reason, until, (a, b) -> Math.max(a, b));
    }

    public boolean isExempt(Player player) {
        if (player == null) return false;
        return isExempt(player.getUniqueId());
    }

    public boolean isExempt(UUID uuid) {
        Map<String, Long> m = untilByReason.get(uuid);
        if (m == null || m.isEmpty()) return false;

        long now = System.currentTimeMillis();
        boolean any = false;

        // cleanup expired + check
        for (var it = m.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue() <= now) it.remove();
            else any = true;
        }

        if (m.isEmpty()) untilByReason.remove(uuid);
        return any;
    }

    public void clear(UUID uuid) {
        if (uuid == null) return;
        untilByReason.remove(uuid);
    }
}

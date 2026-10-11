package it.virgola.anticheat.core;

import it.virgola.anticheat.config.Configs;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ViolationService {

    @SuppressWarnings("unused")
	private final Configs configs;
    private final AlertService alertService;

    // uuid -> (checkId -> vl)
    private final Map<UUID, Map<String, Integer>> vls = new ConcurrentHashMap<>();

    public ViolationService(Configs configs, AlertService alertService) {
        this.configs = configs;
        this.alertService = alertService;
    }

    public int addVl(PlayerData data, String checkId, int add, String info) {
        Map<String, Integer> m = vls.computeIfAbsent(data.player().getUniqueId(), u -> new ConcurrentHashMap<>());
        int newVl = m.merge(checkId, add, (a, b) -> a + b);

        // alert always (you can gate by config later)
        alertService.alert(data.player(), checkId, add, info);

        return newVl;
    }

    /** Backwards-compatible alias for checks. */
    public int flag(PlayerData data, String checkId, int add, String info) {
        return addVl(data, checkId, add, info);
    }

    /** Backwards-compatible alias for checks that only have a Player reference. */
    public int flag(org.bukkit.entity.Player player, String checkId, int add, String info) {
        Map<String, Integer> m = vls.computeIfAbsent(player.getUniqueId(), u -> new ConcurrentHashMap<>());
        int newVl = m.merge(checkId, add, (a, b) -> a + b);
        alertService.alert(player, checkId, add, info);
        return newVl;
    }

    public int getVl(UUID uuid, String checkId) {
        Map<String, Integer> m = vls.get(uuid);
        if (m == null) return 0;
        return m.getOrDefault(checkId, 0);
    }
    
    /** Get all violations for a player */
    public Map<String, Integer> getViolations(UUID uuid) {
        Map<String, Integer> m = vls.get(uuid);
        if (m == null) return Collections.emptyMap();
        return Collections.unmodifiableMap(m);
    }
    
    /** Get total VL for a player across all checks */
    public int getTotalVl(UUID uuid) {
        Map<String, Integer> m = vls.get(uuid);
        if (m == null) return 0;
        return m.values().stream().mapToInt(Integer::intValue).sum();
    }

    public void reset(UUID uuid) {
        vls.remove(uuid);
    }
    
    /** Reset all violations for all players */
    public void resetAll() {
        vls.clear();
    }
}

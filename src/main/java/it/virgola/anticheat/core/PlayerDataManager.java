package it.virgola.anticheat.core;

import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerDataManager {

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();

    public PlayerData get(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), id -> new PlayerData(p));
    }

    public void remove(UUID uuid) {
        data.remove(uuid);
    }

    public void remove(Player p) {
        if (p != null) remove(p.getUniqueId());
    }

}

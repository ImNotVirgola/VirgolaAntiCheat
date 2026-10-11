package it.virgola.anticheat.checks;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import it.virgola.anticheat.core.PlayerData;

import java.util.UUID;

import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Contract for a single anti-cheat check.
 */
public interface Check {

    CheckId id();

    boolean enabled();

    void setEnabled(boolean enabled);


    void reload();

    default void onPacketReceive(PlayerData data, PacketReceiveEvent event) {
        // default no-op
    }

    default void onBlockPlace(PlayerData data, BlockPlaceEvent event) {
        // default no-op
    }
    
    default void onMove(PlayerData data, PlayerMoveEvent event) {
        // default no-op
    }
    
    default void onDamage(PlayerData data, EntityDamageEvent event) {
        // default no-op
    }

    default void onQuit(UUID uuid) {
        // default no-op
    }
}

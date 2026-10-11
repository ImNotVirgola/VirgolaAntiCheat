package it.virgola.anticheat.listeners;

import it.virgola.anticheat.checks.CheckManager;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.PlayerDataManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class MovementListener implements Listener {

    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;

    public MovementListener(PlayerDataManager dataManager, CheckManager checkManager) {
        this.dataManager = dataManager;
        this.checkManager = checkManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        PlayerData data = dataManager.get(player);
        checkManager.handleMove(data, event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        checkManager.handleQuit(event.getPlayer().getUniqueId());
    }
}

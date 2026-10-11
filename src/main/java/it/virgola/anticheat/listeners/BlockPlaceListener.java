package it.virgola.anticheat.listeners;

import it.virgola.anticheat.checks.CheckManager;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.PlayerDataManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

public final class BlockPlaceListener implements Listener {

    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;

    public BlockPlaceListener(PlayerDataManager dataManager, CheckManager checkManager) {
        this.dataManager = dataManager;
        this.checkManager = checkManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        if (p == null) return;
        PlayerData data = dataManager.get(p);
        checkManager.handleBlockPlace(data, event);
    }
}

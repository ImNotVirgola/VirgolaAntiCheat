package it.virgola.anticheat.listeners;

import it.virgola.anticheat.checks.CheckManager;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.PlayerDataManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

public final class DamageListener implements Listener {

    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;

    public DamageListener(PlayerDataManager dataManager, CheckManager checkManager) {
        this.dataManager = dataManager;
        this.checkManager = checkManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        
        PlayerData data = dataManager.get(player);
        checkManager.handleDamage(data, event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player damager)) return;

        // If damager holds a MACE, notify NoFall check so it can ignore fall damage shortly after.
        ItemStack main = damager.getInventory().getItemInMainHand();
        if (main != null && "MACE".equals(main.getType().name())) {
            var check = checkManager.get(it.virgola.anticheat.checks.CheckId.NOFALL);
            if (check instanceof it.virgola.anticheat.checks.impl.NoFallCheck noFall) {
                noFall.markMaceHit(damager.getUniqueId());
            }
        }
    }
}

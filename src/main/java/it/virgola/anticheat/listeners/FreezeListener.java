package it.virgola.anticheat.listeners;

import it.virgola.anticheat.commands.AcCommand;
import it.virgola.anticheat.util.ColorUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Handles freeze functionality - prevents frozen players from moving/interacting
 */
public final class FreezeListener implements Listener {

    private final AcCommand acCommand;

    public FreezeListener(AcCommand acCommand) {
        this.acCommand = acCommand;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (!acCommand.isFrozen(event.getPlayer().getUniqueId())) return;
        
        // Allow head rotation, block position change
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
            event.getFrom().getBlockY() != event.getTo().getBlockY() ||
            event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!acCommand.isFrozen(event.getPlayer().getUniqueId())) return;
        
        // Allow only basic commands
        String cmd = event.getMessage().toLowerCase();
        if (!cmd.startsWith("/msg") && !cmd.startsWith("/r") && !cmd.startsWith("/reply")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ColorUtil.color("&c&lSei freezato! Non puoi usare comandi."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (!acCommand.isFrozen(event.getPlayer().getUniqueId())) return;
        
        // Player quit while frozen - this is suspicious
        String msg = ColorUtil.color("&c[AC] &f" + event.getPlayer().getName() + " &7si è disconnesso mentre era &cfreezato&7!");
        for (Player p : event.getPlayer().getServer().getOnlinePlayers()) {
            if (p.hasPermission("virgolaac.staff")) {
                p.sendMessage(msg);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) return;
        if (!acCommand.isFrozen(p.getUniqueId())) return;
        
        event.setCancelled(true);
        p.sendMessage(ColorUtil.color("&c&lSei freezato! Non puoi attaccare."));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (!acCommand.isFrozen(p.getUniqueId())) return;
        
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (!acCommand.isFrozen(event.getPlayer().getUniqueId())) return;
        
        event.setCancelled(true);
        event.getPlayer().sendMessage(ColorUtil.color("&c&lSei freezato! Non puoi droppare oggetti."));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!acCommand.isFrozen(event.getPlayer().getUniqueId())) return;
        
        event.setCancelled(true);
    }
}

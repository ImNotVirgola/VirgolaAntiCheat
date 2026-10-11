package it.virgola.anticheat.listeners;

import it.virgola.anticheat.core.ExemptionService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;

public final class MovementExemptionListener implements Listener {

    private final ExemptionService exemptions;

    public MovementExemptionListener(ExemptionService exemptions) {
        this.exemptions = exemptions;
    }

    // Teleport / change world / respawn -> big desync windows
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        long ms = 1800;
        // Ender pearls give extra velocity
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            ms = 2200;
        }
        // Chorus fruit has weird landing (using string comparison for compatibility)
        String causeName = e.getCause().name();
        if (causeName.contains("CHORUS")) {
            ms = 1500;
        }
        exemptions.exempt(e.getPlayer(), "teleport", ms);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorld(PlayerChangedWorldEvent e) {
        exemptions.exempt(e.getPlayer(), "worldchange", 2500);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        exemptions.exempt(e.getPlayer(), "respawn", 2500);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        exemptions.exempt(e.getEntity(), "death", 2000);
    }

    // Elytra toggle glide (can look like fly/hover)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.getEntity() instanceof Player p) {
            // Longer grace for glide stop (can have weird landing momentum)
            long ms = e.isGliding() ? 1500 : 2000;
            exemptions.exempt(p, "elytra", ms);
        }
    }

    // Riptide (trident) gives massive movement
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRiptide(PlayerRiptideEvent e) {
        // Riptide can give very high velocity for a while
        exemptions.exempt(e.getPlayer(), "riptide", 2000);
    }

    // Velocity changes (knockback, explosions, plugin velocity)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;

        long ms = 900;

        switch (e.getCause()) {
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> ms = 2000;
            case ENTITY_ATTACK -> ms = 1100;
            case ENTITY_SWEEP_ATTACK -> ms = 1200;
            default -> ms = 900;
        }

        // Projectiles can add extra weird velocity
        if (e.getDamager() instanceof Projectile) {
            ms = Math.max(ms, 1200);
        }

        exemptions.exempt(p, "damagebyentity", ms);
    }

    // Damage (especially explosion / entity attack) often correlates with knockback & weird air ticks
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        
        long ms = switch (e.getCause()) {
            case ENTITY_EXPLOSION, BLOCK_EXPLOSION -> 2000;
            case FALL -> 600; // Fall damage can have weird landing
            case FLY_INTO_WALL -> 800;
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> 1100;
            default -> 900;
        };
        
        exemptions.exempt(p, "damage", ms);
    }

    // Vehicle enter/exit can also cause desync
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p) {
            exemptions.exempt(p, "vehicle_enter", 1200);
        }
    }
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent e) {
        if (e.getExited() instanceof Player p) {
            // Exit can have weird ejection velocity
            exemptions.exempt(p, "vehicle_exit", 1500);
        }
    }
    
    // Sneak toggle (for dismount and other mechanics)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent e) {
        // Very short grace for sneak state changes
        exemptions.exempt(e.getPlayer(), "sneak_toggle", 150);
    }
    
    // Sprint toggle
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprint(PlayerToggleSprintEvent e) {
        // Short grace for sprint state changes
        exemptions.exempt(e.getPlayer(), "sprint_toggle", 200);
    }
    
    // Flight toggle
    @EventHandler(priority = EventPriority.MONITOR)
    public void onFlight(PlayerToggleFlightEvent e) {
        exemptions.exempt(e.getPlayer(), "flight_toggle", 1800);
    }
    
    // Join grace (client is syncing)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        exemptions.exempt(e.getPlayer(), "join", 3000);
    }
    
    // Item consume (potions can give instant movement effects)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemConsume(PlayerItemConsumeEvent e) {
        Player p = e.getPlayer();
        Material type = e.getItem().getType();
        
        // Milk bucket removes effects instantly
        if (type == Material.MILK_BUCKET) {
            exemptions.exempt(p, "milk", 600);
            return;
        }
        
        // Potions can give instant speed/slow changes
        if (type == Material.POTION || type == Material.SPLASH_POTION || type == Material.LINGERING_POTION) {
            exemptions.exempt(p, "potion_consume", 800);
        }
        
        // Chorus fruit teleports
        if (type == Material.CHORUS_FRUIT) {
            exemptions.exempt(p, "chorus_fruit", 1500);
        }
        
        // Golden apple effects
        if (type == Material.GOLDEN_APPLE || type == Material.ENCHANTED_GOLDEN_APPLE) {
            exemptions.exempt(p, "golden_apple", 500);
        }
    }
    
    // Bed enter/leave
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent e) {
        exemptions.exempt(e.getPlayer(), "bed_enter", 1000);
    }
    
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent e) {
        exemptions.exempt(e.getPlayer(), "bed_leave", 1200);
    }
    
    // Game mode change
    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameModeChange(PlayerGameModeChangeEvent e) {
        exemptions.exempt(e.getPlayer(), "gamemode_change", 1500);
    }
}

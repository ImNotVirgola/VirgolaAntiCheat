package it.virgola.anticheat.checks;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import it.virgola.anticheat.checks.impl.*;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.ExemptionService;
import it.virgola.anticheat.core.LagCompensation;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.ViolationService;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Central registry + router for all checks.
 */
public class CheckManager {

    private final Configs configs;
    private final Map<CheckId, Check> checks = new EnumMap<>(CheckId.class);
    @SuppressWarnings("unused")
    private final Plugin plugin;

    public CheckManager(Configs configs, ViolationService violations, LagCompensation lag, ExemptionService exemptionService, Plugin plugin) {
        this.configs = configs;
        this.plugin = plugin;

        register(new ScaffoldCheck(configs, violations, lag));
        register(new BadPacketsCheck(configs, violations));
        register(new FlyCheck(configs, violations, lag));
        register(new SpeedCheck(configs, violations, lag));
        register(new JesusCheck(configs, violations, lag));
        
        // NoFallCheck needs plugin reference for async scheduling
        NoFallCheck noFallCheck = new NoFallCheck(configs, violations, lag);
        noFallCheck.setPlugin(plugin);
        register(noFallCheck);
        
        // KillAuraCheck needs plugin reference for async scheduling
        KillAuraCheck killAuraCheck = new KillAuraCheck(configs, violations, lag);
        killAuraCheck.setPlugin(plugin);
        register(killAuraCheck);

        reloadAll();
    }

    private void register(Check check) {
        checks.put(check.id(), check);
    }

    public Collection<Check> all() {
        return Collections.unmodifiableCollection(checks.values());
    }

    public Check get(CheckId id) {
        return checks.get(id);
    }

    public boolean isEnabled(CheckId id) {
        Check c = checks.get(id);
        return c != null && c.enabled();
    }

    public int enabledCount() {
        int n = 0;
        for (Check c : checks.values()) {
            if (c.enabled()) n++;
        }
        return n;
    }

    public boolean toggle(CheckId id) {
        Check c = checks.get(id);
        if (c == null) return false;

        boolean newState = !c.enabled();
        c.setEnabled(newState);

        // Persist to checks.yml
        configs.checks().set(id.path() + ".enabled", newState);
        configs.checks().save();
        return newState;
    }

    public void reloadAll() {
        for (Check c : checks.values()) {
            c.reload();
        }
    }

    public void handlePacket(PlayerData data, PacketReceiveEvent event) {
        for (Check c : checks.values()) {
            if (!c.enabled()) continue;
            try {
                c.onPacketReceive(data, event);
            } catch (Throwable t) {
                // Never allow a single check to kill packet pipeline.
                // Any real anti-cheat should log this; we keep it silent for now.
                t.printStackTrace();
            }
        }
    }
    
    public void handleMove(PlayerData data, org.bukkit.event.player.PlayerMoveEvent event) {
        if (data == null || event == null) return;

        for (Check check : checks.values()) {
            if (!check.enabled()) continue;
            try {
                check.onMove(data, event);
            } catch (Throwable ignored) {
                // fail-safe: non deve mai crashare un check
            }
        }
    }

    public void handleQuit(java.util.UUID uuid) {
        if (uuid == null) return;

        for (Check check : checks.values()) {
            try {
                check.onQuit(uuid);
            } catch (Throwable ignored) {
            }
        }
    }


    public void handleBlockPlace(PlayerData data, BlockPlaceEvent event) {
        for (Check c : checks.values()) {
            if (!c.enabled()) continue;
            try {
                c.onBlockPlace(data, event);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
    
    public void handleDamage(PlayerData data, org.bukkit.event.entity.EntityDamageEvent event) {
        for (Check c : checks.values()) {
            if (!c.enabled()) continue;
            try {
                c.onDamage(data, event);
            } catch (Throwable t) {
                t.printStackTrace();
            }
        }
    }
    
    /**
     * Shutdown all checks that have async resources.
     * Should be called when the plugin disables.
     */
    public void shutdown() {
        for (Check c : checks.values()) {
            if (c instanceof NoFallCheck noFall) {
                noFall.shutdown();
            }
        }
    }
}

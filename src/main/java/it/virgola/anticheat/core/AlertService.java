package it.virgola.anticheat.core;

import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.util.ColorUtil;
import it.virgola.anticheat.util.MsgUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AlertService {

    private final Configs configs;
    private final DiscordService discordService;
    
    // Players who have disabled alerts
    private final Set<UUID> alertsDisabled = new HashSet<>();
    // Players who have enabled verbose/debug mode
    private final Set<UUID> verboseEnabled = new HashSet<>();

    public AlertService(Configs configs) {
        this.configs = configs;
        this.discordService = new DiscordService(configs);
    }

    public void alert(Player suspect, String check, int addVl, String info) {
        String prefix = configs.plugin().getConfig().getString("prefix", "&d&lVirgolaAC &8» &7");
        String fmt = configs.messages().getString("alerts.format", "{prefix}[AC] {player} -> {check} (+{vl}) {info}");

        Map<String, String> vars = new HashMap<>();
        vars.put("prefix", prefix);
        vars.put("player", suspect.getName());
        vars.put("check", check);
        vars.put("vl", String.valueOf(addVl));
        vars.put("info", info == null ? "" : info);

        String msg = MsgUtil.format(fmt, vars);

        String perm = configs.plugin().getConfig().getString("alerts.permission", "virgolaac.alerts");
        boolean console = configs.plugin().getConfig().getBoolean("alerts.console", true);

        if (console) {
            Bukkit.getLogger().info(stripColor(msg));
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(perm) && !alertsDisabled.contains(p.getUniqueId())) {
                p.sendMessage(msg);
            }
        }

        // Send to Discord if enabled
        try {
            discordService.sendAlert(suspect, check, addVl, info);
        } catch (Throwable t) {
            // Prevent any exceptions in Discord integration from affecting server
        }
    }
    
    /**
     * Toggle alerts for a player.
     * @return true if alerts are now enabled, false if disabled
     */
    public boolean toggleAlerts(UUID uuid) {
        if (alertsDisabled.contains(uuid)) {
            alertsDisabled.remove(uuid);
            return true;
        } else {
            alertsDisabled.add(uuid);
            return false;
        }
    }
    
    /**
     * Check if player has alerts enabled
     */
    public boolean hasAlertsEnabled(UUID uuid) {
        return !alertsDisabled.contains(uuid);
    }
    
    /**
     * Toggle verbose/debug mode for a player.
     * @return true if verbose is now enabled, false if disabled
     */
    public boolean toggleVerbose(UUID uuid) {
        if (verboseEnabled.contains(uuid)) {
            verboseEnabled.remove(uuid);
            return false;
        } else {
            verboseEnabled.add(uuid);
            return true;
        }
    }
    
    /**
     * Check if player has verbose mode enabled
     */
    public boolean hasVerboseEnabled(UUID uuid) {
        return verboseEnabled.contains(uuid);
    }
    
    /**
     * Send a verbose/debug message to players with verbose enabled
     */
    public void verbose(String message) {
        String prefix = configs.plugin().getConfig().getString("prefix", "&d&lVirgolaAC &8» &7");
        String msg = ColorUtil.color(prefix + "&7[DEBUG] " + message);
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (verboseEnabled.contains(p.getUniqueId())) {
                p.sendMessage(msg);
            }
        }
    }

    private static String stripColor(String s) {
        return s.replaceAll("§.", "");
    }
}

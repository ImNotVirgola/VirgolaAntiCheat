package it.virgola.anticheat.commands;

import it.virgola.anticheat.checks.CheckManager;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.AlertService;
import it.virgola.anticheat.core.TickService;
import it.virgola.anticheat.core.ViolationService;
import it.virgola.anticheat.gui.AcGui;
import it.virgola.anticheat.util.ColorUtil;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.*;

public final class AcCommand implements CommandExecutor, TabCompleter {

    private final Configs configs;
    private final CheckManager checkManager;
    @SuppressWarnings("unused")
    private final TickService tickService;
    private final ViolationService violationService;
    private final AlertService alertService;
    private final AcGui gui;
    
    // Frozen players
    private final Set<UUID> frozenPlayers = new HashSet<>();
    // Staff spectating (staff UUID -> target UUID)
    private final Map<UUID, UUID> spectating = new HashMap<>();
    // Original gamemode before spectate
    private final Map<UUID, GameMode> originalGameMode = new HashMap<>();
    // Original location before spectate
    private final Map<UUID, Location> originalLocation = new HashMap<>();
    
    // Command info storage - solo nomi e permessi (il resto viene dal config)
    private static final Map<String, String> COMMAND_PERMISSIONS = new LinkedHashMap<>();
    
    static {
        COMMAND_PERMISSIONS.put("help", "virgolaac.alerts");
        COMMAND_PERMISSIONS.put("alerts", "virgolaac.alerts");
        COMMAND_PERMISSIONS.put("info", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("list", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("top", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("freeze", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("unfreeze", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("spectate", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("unspectate", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("tp", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("broadcast", "virgolaac.staff");
        COMMAND_PERMISSIONS.put("panel", "virgolaac.admin");
        COMMAND_PERMISSIONS.put("reset", "virgolaac.admin");
        COMMAND_PERMISSIONS.put("verbose", "virgolaac.admin");
        COMMAND_PERMISSIONS.put("kick", "virgolaac.admin");
        COMMAND_PERMISSIONS.put("ban", "virgolaac.admin");
        COMMAND_PERMISSIONS.put("reload", "virgolaac.admin");
        COMMAND_PERMISSIONS.put("discord", "virgolaac.admin");
    }

    public AcCommand(Configs configs, CheckManager checkManager, TickService tickService, 
                     ViolationService violationService, AlertService alertService, AcGui gui) {
        this.configs = configs;
        this.checkManager = checkManager;
        this.tickService = tickService;
        this.violationService = violationService;
        this.alertService = alertService;
        this.gui = gui;
    }

    // Extended discord subcommands and usages for tab-complete and help
    private static final java.util.List<String> DISCORD_SUBS = java.util.List.of(
            "set", "add", "remove", "list", "setcheck", "unsetcheck", "test", "disable"
    );

    private static final java.util.Map<String, String> DISCORD_USAGES = new java.util.HashMap<>() {{
        put("set", "/ac discord set <webhook>");
        put("add", "/ac discord add <webhook>");
        put("remove", "/ac discord remove <webhook>");
        put("list", "/ac discord list");
        put("setcheck", "/ac discord setcheck <check> <webhook>");
        put("unsetcheck", "/ac discord unsetcheck <check>");
        put("test", "/ac discord test");
        put("disable", "/ac discord disable");
    }};

    // Legge un messaggio dal config con sostituzione variabili
    private String msg(String path, Map<String, String> vars) {
        String prefix = ColorUtil.color(configs.plugin().getConfig().getString("prefix", ""));
        String t = configs.messages().getString(path, "&cMessaggio mancante: " + path);
        t = t.replace("{prefix}", prefix);
        for (Map.Entry<String, String> e : vars.entrySet()) {
            t = t.replace("{" + e.getKey() + "}", e.getValue());
        }
        return ColorUtil.color(t);
    }
    
    // Shortcut senza variabili
    private String msg(String path) {
        return msg(path, Map.of());
    }
    
//    private String prefix() {
//        return ColorUtil.color(configs.plugin().getConfig().getString("prefix", "&d&lVirgolaAC &8» &7"));
//    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> {
                if (args.length >= 2) {
                    sendCommandHelp(sender, args[1].toLowerCase(Locale.ROOT));
                } else {
                    sendHelp(sender);
                }
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("virgolaac.admin")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                configs.reloadAll();
                checkManager.reloadAll();
                sender.sendMessage(msg("general.reloaded"));
                return true;
            }
            case "panel", "gui" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(msg("general.only-ingame"));
                    return true;
                }
                if (!sender.hasPermission("virgolaac.admin")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                gui.open(p);
                return true;
            }
            case "alerts", "notify" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(msg("general.only-ingame"));
                    return true;
                }
                if (!sender.hasPermission("virgolaac.alerts")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                boolean newState = alertService.toggleAlerts(p.getUniqueId());
                sender.sendMessage(msg("toggle.alerts", Map.of("state", newState ? msg("toggle.state-on") : msg("toggle.state-off"))));
                return true;
            }
            case "info" -> {
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.info"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                    return true;
                }
                sendPlayerInfo(sender, target);
                return true;
            }
            case "reset" -> {
                if (!sender.hasPermission("virgolaac.admin")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.reset"));
                    return true;
                }
                if (args[1].equalsIgnoreCase("all")) {
                    violationService.resetAll();
                    sender.sendMessage(msg("violations.reset-all"));
                } else {
                    Player target = Bukkit.getPlayer(args[1]);
                    if (target == null) {
                        sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                        return true;
                    }
                    violationService.reset(target.getUniqueId());
                    sender.sendMessage(msg("violations.reset-player", Map.of("player", target.getName())));
                }
                return true;
            }
            case "verbose", "debug" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(msg("general.only-ingame"));
                    return true;
                }
                if (!sender.hasPermission("virgolaac.admin")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                boolean newState = alertService.toggleVerbose(p.getUniqueId());
                sender.sendMessage(msg("toggle.verbose", Map.of("state", newState ? msg("toggle.state-on") : msg("toggle.state-off"))));
                return true;
            }
            case "kick" -> {
                if (!sender.hasPermission("virgolaac.admin")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.kick"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                    return true;
                }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) 
                        : msg("punishment.default-reason");
                target.kickPlayer(msg("punishment.kick-screen", Map.of("reason", reason)));
                sender.sendMessage(msg("punishment.kicked", Map.of("player", target.getName(), "reason", reason)));
                // Broadcast to staff
                String broadcast = msg("punishment.kick-broadcast", Map.of(
                        "player", target.getName(),
                        "staff", sender.getName(),
                        "reason", reason
                ));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("virgolaac.alerts")) {
                        p.sendMessage(broadcast);
                    }
                }
                return true;
            }
            case "ban" -> {
                if (!sender.hasPermission("virgolaac.admin")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.ban"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                    return true;
                }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) 
                        : msg("punishment.default-reason");
                // Ban the player (using Bukkit's ban system)
                Bukkit.getBanList(org.bukkit.BanList.Type.NAME).addBan(target.getName(), 
                        ColorUtil.color("&cVirgolaAC: " + reason), null, sender.getName());
                target.kickPlayer(msg("punishment.ban-screen", Map.of("reason", reason)));
                sender.sendMessage(msg("punishment.banned", Map.of("player", target.getName(), "reason", reason)));
                // Broadcast to staff
                String broadcast = msg("punishment.ban-broadcast", Map.of(
                        "player", target.getName(),
                        "staff", sender.getName(),
                        "reason", reason
                ));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("virgolaac.alerts")) {
                        p.sendMessage(broadcast);
                    }
                }
                return true;
            }
            case "freeze" -> {
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.freeze"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                    return true;
                }
                if (frozenPlayers.contains(target.getUniqueId())) {
                    sender.sendMessage(msg("freeze.already-frozen", Map.of("player", target.getName())));
                    return true;
                }
                frozenPlayers.add(target.getUniqueId());
                target.sendMessage(msg("freeze.frozen-screen"));
                sender.sendMessage(msg("freeze.frozen", Map.of("player", target.getName())));
                // Broadcast to staff
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("virgolaac.staff") && !p.equals(sender)) {
                        p.sendMessage(msg("freeze.broadcast", Map.of("player", target.getName(), "staff", sender.getName())));
                    }
                }
                return true;
            }
            case "unfreeze" -> {
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.unfreeze"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                    return true;
                }
                if (!frozenPlayers.contains(target.getUniqueId())) {
                    sender.sendMessage(msg("freeze.not-frozen", Map.of("player", target.getName())));
                    return true;
                }
                frozenPlayers.remove(target.getUniqueId());
                target.sendMessage(msg("freeze.unfrozen-screen"));
                sender.sendMessage(msg("freeze.unfrozen", Map.of("player", target.getName())));
                return true;
            }
            case "spectate", "spec" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(msg("general.only-ingame"));
                    return true;
                }
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                // If already spectating, stop
                if (spectating.containsKey(p.getUniqueId())) {
                    stopSpectating(p);
                    if (args.length < 2) {
                        return true;
                    }
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.spectate"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                    return true;
                }
                if (target.equals(p)) {
                    sender.sendMessage(msg("spectate.cannot-self"));
                    return true;
                }
                startSpectating(p, target);
                return true;
            }
            case "unspectate", "unspec" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(msg("general.only-ingame"));
                    return true;
                }
                if (!spectating.containsKey(p.getUniqueId())) {
                    sender.sendMessage(msg("spectate.not-spectating"));
                    return true;
                }
                stopSpectating(p);
                return true;
            }
            case "top" -> {
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                sendTopViolations(sender);
                return true;
            }
            case "broadcast", "bc" -> {
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.broadcast"));
                    return true;
                }
                String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                String formatted = msg("staff.broadcast-format", Map.of("staff", sender.getName(), "message", message));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("virgolaac.staff")) {
                        p.sendMessage(formatted);
                    }
                }
                Bukkit.getConsoleSender().sendMessage(formatted);
                return true;
            }
            case "tp", "goto" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(msg("general.only-ingame"));
                    return true;
                }
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage.tp"));
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(msg("general.player-not-found", Map.of("player", args[1])));
                    return true;
                }
                p.teleport(target.getLocation());
                sender.sendMessage(msg("teleport.success", Map.of("player", target.getName())));
                return true;
            }
            case "discord" -> {
                if (!sender.hasPermission("virgolaac.admin")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("discord.usage"));
                    return true;
                }
                String sub2 = args[1].toLowerCase(Locale.ROOT);
                switch (sub2) {
                    case "set" -> {
                        if (args.length < 3) {
                            sender.sendMessage(msg("discord.usage"));
                            return true;
                        }
                        String webhook = args[2];
                        configs.plugin().getConfig().set("discord.webhook-url", webhook);
                        configs.plugin().getConfig().set("discord.enabled", true);
                        configs.plugin().saveConfig();
                        sender.sendMessage(msg("discord.set-success"));
                        return true;
                    }
                    case "add" -> {
                        if (args.length < 3) {
                            sender.sendMessage(msg("discord.usage"));
                            return true;
                        }
                        String webhook = args[2];
                        java.util.List<String> list = configs.plugin().getConfig().getStringList("discord.webhooks");
                        list.add(webhook);
                        configs.plugin().getConfig().set("discord.webhooks", list);
                        configs.plugin().getConfig().set("discord.enabled", true);
                        configs.plugin().saveConfig();
                        sender.sendMessage(msg("discord.set-success"));
                        return true;
                    }
                    case "remove" -> {
                        if (args.length < 3) {
                            sender.sendMessage(msg("discord.usage"));
                            return true;
                        }
                        String webhook = args[2];
                        java.util.List<String> list = configs.plugin().getConfig().getStringList("discord.webhooks");
                        if (list.remove(webhook)) {
                            configs.plugin().getConfig().set("discord.webhooks", list);
                            configs.plugin().saveConfig();
                            sender.sendMessage(msg("discord.set-success"));
                        } else {
                            sender.sendMessage(msg("general.player-not-found", Map.of("player", webhook)));
                        }
                        return true;
                    }
                    case "list" -> {
                        java.util.List<String> list = configs.plugin().getConfig().getStringList("discord.webhooks");
                        String legacy = configs.plugin().getConfig().getString("discord.webhook-url", "");
                        sender.sendMessage("Discord webhooks:");
                        boolean any = false;

                        // Show legacy single webhook if present
                        if (legacy != null && !legacy.isEmpty()) {
                            sender.sendMessage(" - (legacy) " + legacy);
                            any = true;
                        }

                        // Show global list
                        if (list != null && !list.isEmpty()) {
                            for (String w : list) {
                                sender.sendMessage(" - " + w);
                                any = true;
                            }
                        }

                        // Show per-check webhooks
                        try {
                            Object sec = configs.plugin().getConfig().getConfigurationSection("discord.check-webhooks");
                            if (sec != null) {
                                org.bukkit.configuration.ConfigurationSection cs = (org.bukkit.configuration.ConfigurationSection) sec;
                                for (String check : cs.getKeys(false)) {
                                    java.util.List<String> cl = configs.plugin().getConfig().getStringList("discord.check-webhooks." + check);
                                    if (cl != null) {
                                        for (String w : cl) {
                                            sender.sendMessage(" - (check:" + check + ") " + w);
                                            any = true;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {}

                        if (!any) sender.sendMessage(" - (none)");
                        return true;
                    }
                    case "setcheck" -> {
                        if (args.length < 4) {
                            sender.sendMessage(msg("discord.usage"));
                            return true;
                        }
                        String checkName = args[2];
                        String webhook = args[3];
                        java.util.List<String> list = new java.util.ArrayList<>();
                        list.add(webhook);
                        configs.plugin().getConfig().set("discord.check-webhooks." + checkName, list);
                        configs.plugin().getConfig().set("discord.enabled", true);
                        configs.plugin().saveConfig();
                        sender.sendMessage(msg("discord.set-success"));
                        return true;
                    }
                    case "unsetcheck" -> {
                        if (args.length < 3) {
                            sender.sendMessage(msg("discord.usage"));
                            return true;
                        }
                        String checkName = args[2];
                        configs.plugin().getConfig().set("discord.check-webhooks." + checkName, null);
                        configs.plugin().saveConfig();
                        sender.sendMessage(msg("discord.disable-success"));
                        return true;
                    }
                    case "disable" -> {
                        configs.plugin().getConfig().set("discord.enabled", false);
                        configs.plugin().saveConfig();
                        sender.sendMessage(msg("discord.disable-success"));
                        return true;
                    }
                    case "test" -> {
                        Player suspect = null;
                        if (sender instanceof Player p) suspect = p;
                        else {
                            for (Player pl : Bukkit.getOnlinePlayers()) { suspect = pl; break; }
                        }
                        if (suspect == null) {
                            sender.sendMessage(msg("discord.test-no-player"));
                            return true;
                        }
                        // Send a test alert and report HTTP result back to sender
                        it.virgola.anticheat.core.DiscordService ds = new it.virgola.anticheat.core.DiscordService(configs);
                        ds.sendAlert(suspect, "TEST", 1, "Test alert from server", (code) -> {
                            // run back on main thread to send message to sender
                            Bukkit.getScheduler().runTask(configs.plugin(), () -> {
                                if (code == 0) {
                                    sender.sendMessage(msg("discord.test-sent"));
                                } else if (code == -1) {
                                    sender.sendMessage(msg("prefix", Map.of()));
                                    sender.sendMessage("Discord test failed (no webhook configured or error)");
                                } else {
                                    sender.sendMessage(msg("discord.test-sent") + " (HTTP: " + code + ")");
                                }
                            });
                        });
                        return true;
                    }
                    default -> {
                        // Unknown subcommand: suggest closest match and print specific usage
                        String given = sub2;
                        String suggestion = null;
                        for (String s : DISCORD_SUBS) {
                            if (s.startsWith(given)) { suggestion = s; break; }
                        }
                        if (suggestion == null) {
                            // try Levenshtein-like fallback: shortest edit by prefix/contains
                            for (String s : DISCORD_SUBS) {
                                if (s.contains(given) || given.contains(s)) { suggestion = s; break; }
                            }
                        }
                        sender.sendMessage(msg("discord.usage"));
                        if (suggestion != null) {
                            String usage = DISCORD_USAGES.getOrDefault(suggestion, "/ac discord " + suggestion);
                            sender.sendMessage(ColorUtil.color("&7Did you mean: &d" + usage));
                        }
                        return true;
                    }
                }
            }
            case "list" -> {
                if (!sender.hasPermission("virgolaac.staff")) {
                    sender.sendMessage(msg("general.no-permission"));
                    return true;
                }
                sender.sendMessage(msg("list.header"));
                boolean found = false;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    int totalVl = violationService.getTotalVl(p.getUniqueId());
                    if (totalVl > 0) {
                        found = true;
                        String vlColor = totalVl >= 10 ? msg("info.vl-high") : (totalVl >= 5 ? msg("info.vl-medium") : msg("info.vl-low"));
                        sender.sendMessage(msg("list.player-line", Map.of("player", p.getName(), "vl", String.valueOf(totalVl), "vl_color", vlColor)));
                    }
                }
                if (!found) {
                    sender.sendMessage(msg("list.empty"));
                }
                sender.sendMessage(msg("list.footer"));
                return true;
            }
            default -> {
                // Unknown top-level command: suggest closest match and show usage if available
                String given = sub;
                String suggestion = findClosestCommand(given);
                sender.sendMessage(msg("general.unknown-command"));
                if (suggestion != null) {
                    String usage = getUsageForCommand(suggestion);
                    sender.sendMessage(ColorUtil.color("&7Did you mean: &d" + usage));
                }
                return true;
            }
        }
    }
    
    private void startSpectating(Player staff, Player target) {
        // Save original state
        originalGameMode.put(staff.getUniqueId(), staff.getGameMode());
        originalLocation.put(staff.getUniqueId(), staff.getLocation().clone());
        spectating.put(staff.getUniqueId(), target.getUniqueId());
        
        // Enter spectator mode
        staff.setGameMode(GameMode.SPECTATOR);
        staff.teleport(target.getLocation());
        staff.setSpectatorTarget(target);
        
        staff.sendMessage(msg("spectate.start", Map.of("player", target.getName())));
    }
    
    private void stopSpectating(Player staff) {
        UUID staffUuid = staff.getUniqueId();
        
        // Remove spectator target first
        staff.setSpectatorTarget(null);
        
        // Restore original state
        GameMode original = originalGameMode.remove(staffUuid);
        Location originalLoc = originalLocation.remove(staffUuid);
        spectating.remove(staffUuid);
        
        if (original != null) {
            staff.setGameMode(original);
        } else {
            staff.setGameMode(GameMode.SURVIVAL);
        }
        
        if (originalLoc != null) {
            staff.teleport(originalLoc);
        }
        
        staff.sendMessage(msg("spectate.stop"));
    }
    
    private void sendTopViolations(CommandSender sender) {
        sender.sendMessage(msg("top.header"));
        
        // Collect all online players violations
        List<Map.Entry<String, Integer>> entries = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            int total = violationService.getTotalVl(p.getUniqueId());
            if (total > 0) {
                entries.add(Map.entry(p.getName(), total));
            }
        }
        
        // Sort by violations descending
        entries.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        if (entries.isEmpty()) {
            sender.sendMessage(msg("top.empty"));
        } else {
            int rank = 1;
            for (Map.Entry<String, Integer> e : entries) {
                if (rank > 10) break;
                String medal = rank == 1 ? msg("top.medal-1") : (rank == 2 ? msg("top.medal-2") : (rank == 3 ? msg("top.medal-3") : msg("top.medal-other")));
                String vlColor = rank <= 3 ? msg("info.vl-high") : (rank <= 5 ? msg("info.vl-medium") : msg("info.vl-low"));
                sender.sendMessage(msg("top.player-line", Map.of(
                    "medal", medal,
                    "rank", String.valueOf(rank),
                    "player", e.getKey(),
                    "vl", String.valueOf(e.getValue()),
                    "vl_color", vlColor
                )));
                rank++;
            }
        }
        sender.sendMessage(msg("top.footer"));
    }
    
    // Getter for frozen players (for listener integration)
    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }
    
    @SuppressWarnings("deprecation")
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(msg("help.header"));
        sender.sendMessage(msg("help.subtitle"));
        sender.sendMessage("");
        
        if (sender instanceof Player p) {
            // Interactive help with hover text
            for (String cmdName : COMMAND_PERMISSIONS.keySet()) {
                String permission = COMMAND_PERMISSIONS.get(cmdName);
                
                // Check permission
                if (!p.hasPermission(permission)) continue;
                
                // Get info from config
                String usage = configs.messages().getString("help.commands." + cmdName + ".usage", "/ac " + cmdName);
                String description = configs.messages().getString("help.commands." + cmdName + ".description", "");
                // Debug: log loaded help description for troubleshooting
                try {
                    configs.plugin().getLogger().info("[VirgolaAC] help.commands." + cmdName + " => description='" + description + "'");
                } catch (Exception ignored) {}
                
                // Build hover text
                StringBuilder hoverText = new StringBuilder();
                hoverText.append("§d§l").append(usage).append("\n\n");
                hoverText.append("§7").append(description).append("\n\n");
                hoverText.append("§e§lPermesso: §f").append(permission).append("\n\n");
                hoverText.append("§a➤ Clicca per dettagli");
                
                // Create component
                TextComponent cmd = new TextComponent(ColorUtil.color("&d" + usage));
                cmd.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                    new Text(hoverText.toString())));
                cmd.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, 
                    "/ac help " + cmdName));
                
                TextComponent separator = new TextComponent(ColorUtil.color(" &8- &7"));
                TextComponent desc = new TextComponent(ColorUtil.color(description));
                
                p.spigot().sendMessage(cmd, separator, desc);
            }
        } else {
            // Console fallback - simple text
            for (String cmdName : COMMAND_PERMISSIONS.keySet()) {
                String usage = configs.messages().getString("help.commands." + cmdName + ".usage", "/ac " + cmdName);
                String description = configs.messages().getString("help.commands." + cmdName + ".description", "");
                sender.sendMessage(ColorUtil.color("&d" + usage + " &8- &7" + description));
            }
        }
        
        sender.sendMessage("");
        sender.sendMessage(msg("help.footer"));
    }
    
    @SuppressWarnings("deprecation")
    private void sendCommandHelp(CommandSender sender, String cmdName) {
        String permission = COMMAND_PERMISSIONS.get(cmdName);
        
        if (permission == null) {
            sender.sendMessage(msg("help.not-found", Map.of("command", cmdName)));
            sender.sendMessage(msg("help.not-found-hint"));
            return;
        }
        
        // Check permission
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(msg("general.no-permission"));
            return;
        }
        
        // Get info from config
        String basePath = "help.commands." + cmdName;
        String usage = configs.messages().getString(basePath + ".usage", "/ac " + cmdName);
        String description = configs.messages().getString(basePath + ".description", "");
        List<String> details = configs.messages().getStringList(basePath + ".details");
        List<String> examples = configs.messages().getStringList(basePath + ".examples");
        
        sender.sendMessage(msg("help.command-header", Map.of("command", cmdName)));
        sender.sendMessage("");
        
        // Usage
        sender.sendMessage(msg("help.section-usage"));
        sender.sendMessage(ColorUtil.color("  &f" + usage));
        sender.sendMessage("");
        
        // Permission
        sender.sendMessage(msg("help.section-permission"));
        sender.sendMessage(ColorUtil.color("  &f" + permission));
        sender.sendMessage("");
        
        // Description
        sender.sendMessage(msg("help.section-description"));
        sender.sendMessage(ColorUtil.color("  &7" + description));
        sender.sendMessage("");
        
        // Details
        if (!details.isEmpty()) {
            sender.sendMessage(msg("help.section-details"));
            String bullet = msg("help.detail-bullet");
            for (String detail : details) {
                sender.sendMessage(ColorUtil.color(bullet + detail));
            }
            sender.sendMessage("");
        }
        
        // Examples
        if (!examples.isEmpty()) {
            sender.sendMessage(msg("help.section-examples"));
            String exampleColor = msg("help.example-color");
            String exampleHover = msg("help.example-hover");
            if (sender instanceof Player p) {
                for (String example : examples) {
                    TextComponent exampleComp = new TextComponent(ColorUtil.color("  " + exampleColor + example));
                    exampleComp.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                        new Text(ColorUtil.color(exampleHover))));
                    exampleComp.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, example));
                    p.spigot().sendMessage(exampleComp);
                }
            } else {
                for (String example : examples) {
                    sender.sendMessage(ColorUtil.color("  " + exampleColor + example));
                }
            }
        }
        
        sender.sendMessage("");
        
        // Back button (for players)
        if (sender instanceof Player p) {
            TextComponent back = new TextComponent(msg("help.back-button"));
            back.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
                new Text(ColorUtil.color(msg("help.back-hover")))));
            back.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/ac help"));
            p.spigot().sendMessage(back);
        }
        
        sender.sendMessage(msg("help.command-footer"));
    }
    
    private void sendPlayerInfo(CommandSender sender, Player target) {
        sender.sendMessage(msg("info.header", Map.of("player", target.getName())));
        
        // Status bar
        int ping = target.getPing();
        String pingColor = ping < 80 ? msg("info.ping-good") : (ping < 150 ? msg("info.ping-medium") : msg("info.ping-bad"));
        sender.sendMessage(msg("info.status-line", Map.of(
            "ping", String.valueOf(ping),
            "ping_color", pingColor,
            "gamemode", target.getGameMode().name()
        )));
        sender.sendMessage("");
        
        Map<String, Integer> vls = violationService.getViolations(target.getUniqueId());
        if (vls == null || vls.isEmpty()) {
            sender.sendMessage(msg("info.no-violations"));
        } else {
            int total = 0;
            for (Map.Entry<String, Integer> e : vls.entrySet()) {
                int vl = e.getValue();
                String vlColor = vl >= 10 ? msg("info.vl-high") : (vl >= 5 ? msg("info.vl-medium") : msg("info.vl-low"));
                sender.sendMessage(msg("info.violation-line", Map.of(
                    "check", e.getKey(),
                    "vl", String.valueOf(vl),
                    "vl_color", vlColor
                )));
                total += vl;
            }
            sender.sendMessage("");
            String totalColor = total >= 20 ? msg("info.total-high") : (total >= 10 ? msg("info.total-medium") : msg("info.total-low"));
            sender.sendMessage(msg("info.total-line", Map.of("total", String.valueOf(total), "total_color", totalColor)));
        }
        
        sender.sendMessage(msg("info.footer"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
            List<String> subs = new ArrayList<>(Arrays.asList("help", "alerts"));
            if (sender.hasPermission("virgolaac.staff")) {
                subs.addAll(Arrays.asList("info", "list", "top", "freeze", "unfreeze", "spectate", "unspectate", "tp", "broadcast"));
            }
            if (sender.hasPermission("virgolaac.admin")) {
                subs.addAll(Arrays.asList("panel", "reset", "verbose", "kick", "ban", "reload", "discord"));
            }
            return prefix(subs, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "help" -> {
                    // Show available commands for help
                    List<String> cmds = new ArrayList<>();
                    for (Map.Entry<String, String> entry : COMMAND_PERMISSIONS.entrySet()) {
                        if (sender.hasPermission(entry.getValue())) {
                            cmds.add(entry.getKey());
                        }
                    }
                    return prefix(cmds, args[1]);
                }
                case "info", "kick", "ban", "freeze", "unfreeze", "spectate", "spec", "tp", "goto" -> {
                    List<String> out = new ArrayList<>();
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                    return prefix(out, args[1]);
                }
                case "reset" -> {
                    List<String> out = new ArrayList<>();
                    out.add("all");
                    for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
                    return prefix(out, args[1]);
                }
                case "discord" -> {
                    // suggest discord subcommands
                    return prefix(DISCORD_SUBS, args[1]);
                }
            }
        }

        // Handle discord deeper tab completion: /ac discord <sub> <...>
        if (args.length >= 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("discord")) {
                if (args.length == 2) {
                    return prefix(DISCORD_SUBS, args[1]);
                }
                String sub = args[1].toLowerCase(Locale.ROOT);
                if (args.length == 3) {
                    switch (sub) {
                        case "setcheck", "unsetcheck" -> {
                            // suggest check ids
                            List<String> checks = new ArrayList<>();
                            for (it.virgola.anticheat.checks.CheckId id : it.virgola.anticheat.checks.CheckId.values()) checks.add(id.name());
                            return prefix(checks, args[2]);
                        }
                        case "add", "remove", "set" -> {
                            // webhook url - no sensible completion
                            return Collections.emptyList();
                        }
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    private static List<String> prefix(List<String> options, String start) {
        String s = start == null ? "" : start.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(s)) out.add(o);
        }
        return out;
    }

    // Find the closest command name from COMMAND_PERMISSIONS keys (or DISCORD_SUBS)
    private String findClosestCommand(String input) {
        if (input == null || input.isBlank()) return null;
        String in = input.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>(COMMAND_PERMISSIONS.keySet());
        // include discord subs
        candidates.addAll(DISCORD_SUBS);

        // 1) exact startsWith match
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).startsWith(in)) return c;
        }
        // 2) contains
        for (String c : candidates) {
            if (c.toLowerCase(Locale.ROOT).contains(in)) return c;
        }
        // 3) Levenshtein minimal distance
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = levenshtein(in, c.toLowerCase(Locale.ROOT));
            if (d < bestScore) { bestScore = d; best = c; }
        }
        // only suggest if reasonably close
        if (bestScore <= Math.max(1, in.length() / 2)) return best;
        return null;
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i-1) == b.charAt(j-1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1), dp[i-1][j-1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // Return a usage string for a command; prefer messages.yml help.commands.<cmd>.usage, else DISCORD_USAGES, else fallback
    private String getUsageForCommand(String cmd) {
        if (cmd == null) return "/ac help";
        String usage = configs.messages().getString("help.commands." + cmd + ".usage", null);
        if (usage != null && !usage.isEmpty()) return usage;
        if ("discord".equalsIgnoreCase(cmd)) return DISCORD_USAGES.getOrDefault("set", "/ac discord");
        String alt = DISCORD_USAGES.get(cmd);
        if (alt != null) return alt;
        return "/ac " + cmd;
    }
}

package it.virgola.anticheat.gui;

import it.virgola.anticheat.checks.Check;
import it.virgola.anticheat.checks.CheckId;
import it.virgola.anticheat.checks.CheckManager;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.TickService;
import it.virgola.anticheat.util.ColorUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class AcGui implements Listener {

    private final Configs configs;
    private final CheckManager checkManager;
    private final TickService tickService;
    private final Plugin plugin;
    
    // Track open inventories and their update tasks
    private final Map<UUID, BukkitTask> updateTasks = new HashMap<>();
    private final Set<UUID> openGuis = new HashSet<>();
    // Settings view contexts keyed by Inventory to avoid timing races when switching inventories
    private final Map<Inventory, SettingsContext> invSettings = new WeakHashMap<>();
    // Pending chat-input contexts for editing config values (player -> chat context)
    private final Map<UUID, ChatInputContext> chatInputContexts = new HashMap<>();
    
    // Slot for server status (bottom row, center)
    private static final int STATUS_SLOT = 49;
    // Slots for decorative glass panes (bottom row except center)
    @SuppressWarnings("unused")
    private static final int[] GLASS_SLOTS = {45, 46, 47, 48, 50, 51, 52, 53};

    public AcGui(Configs configs, CheckManager checkManager, TickService tickService, Plugin plugin) {
        this.configs = configs;
        this.checkManager = checkManager;
        this.tickService = tickService;
        this.plugin = plugin;
    }
    
    // Backwards compatibility constructor
    public AcGui(Configs configs, CheckManager checkManager) {
        this.configs = configs;
        this.checkManager = checkManager;
        this.tickService = null;
        this.plugin = null;
    }

    private record Holder(CheckId id) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
    
    private void hideItemAttributes(ItemMeta meta) {
        if (meta == null) return;
        meta.addItemFlags(
            ItemFlag.HIDE_ATTRIBUTES,
            ItemFlag.HIDE_ENCHANTS,
            ItemFlag.HIDE_UNBREAKABLE
        );
    }
    
    @SuppressWarnings("deprecation")
    public void open(Player p) {
        int size = 54;
        String title = configs.messages().getString("gui.panel.title", "&d&lVirgolaAC");
        Inventory inv = Bukkit.createInventory(new Holder(null), size, ColorUtil.color(title));

        // Fill decoration (top and bottom rows with glass panes)
        ItemStack glass = makeGlassPane();
        for (int i = 0; i < 9; i++) inv.setItem(i, glass);
        for (int i = 45; i < 54; i++) {
            if (i == STATUS_SLOT) continue;
            inv.setItem(i, glass);
        }

        // Place check items in the main area (first 45 slots)
        CheckId[] ids = CheckId.values();
        for (int i = 0; i < ids.length && i < 45; i++) {
            CheckId id = ids[i];
            boolean enabled = checkManager.isEnabled(id);
            inv.setItem(i, makeCheckItem(p, id, enabled));
        }

        // Server status item
        inv.setItem(STATUS_SLOT, makeStatusItem(p));

        p.openInventory(inv);
        openGuis.add(p.getUniqueId());

        // Start dynamic update task (every 20 ticks = 1 second)
        if (plugin != null && tickService != null) {
            BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                if (!openGuis.contains(p.getUniqueId())) {
                    return;
                }
                Player player = Bukkit.getPlayer(p.getUniqueId());
                if (player == null || player.getOpenInventory() == null || player.getOpenInventory().getTopInventory() == null
                        || player.getOpenInventory().getTopInventory().getHolder() == null
                        || !(player.getOpenInventory().getTopInventory().getHolder() instanceof Holder)) {
                    return;
                }
                // Update only the status item
                player.getOpenInventory().getTopInventory().setItem(STATUS_SLOT, makeStatusItem(player));
            }, 20L, 20L);

            updateTasks.put(p.getUniqueId(), task);
        }
    }

    @SuppressWarnings("deprecation")
    public void openDetail(Player p, CheckId id) {
        Check c = checkManager.get(id);
        if (c == null) return;

        String title = configs.messages().getString("gui.detail.title", "&d&lVirgolaAC &8- &f{check}")
                .replace("{check}", id.name());
        Inventory inv = Bukkit.createInventory(new Holder(id), 27, ColorUtil.color(title));

        // Info item
        inv.setItem(11, makeDetailInfoItem(p, id, c));

        // Toggle item
        inv.setItem(13, makeDetailToggleItem(p, id, c.enabled()));

        // Placeholder: settings / violations
        inv.setItem(15, makeDetailSettingsItem(p, id));

        // Back button
        inv.setItem(22, makeBackItem());

        p.openInventory(inv);
    }

    private static final class SettingsContext {
        final CheckId id;
        final Map<Integer, String> slotToPath;

        SettingsContext(CheckId id, Map<Integer, String> slotToPath) {
            this.id = id;
            this.slotToPath = slotToPath;
        }
    }

    private static final class ChatInputContext {
        final CheckId id;
        final String path;
        final Class<?> type;

        ChatInputContext(CheckId id, String path, Class<?> type) {
            this.id = id;
            this.path = path;
            this.type = type;
        }
    }

    @SuppressWarnings({ "deprecation", "unused" })
    private ItemStack makeCheckItem(Player p, CheckId id, boolean enabled) {
        // Determine wrap length
        int wrap = configs.messages().getInt("gui.panel.wrap-length", 35);

        // Prefer per-check material, then role material, then colored wool
        String perMat = configs.messages().getString("gui.panel.checks." + id.name() + ".material", null);
        String role = getRole(p);
        String roleMat = configs.messages().getString("gui.roles." + role + ".material", null);

        Material mat = null;
        if (perMat != null) {
            try { mat = Material.valueOf(perMat); } catch (IllegalArgumentException ignored) { mat = null; }
        }
        if (mat == null && roleMat != null) {
            try { mat = Material.valueOf(roleMat); } catch (IllegalArgumentException ignored) { mat = null; }
        }
        if (mat == null) mat = enabled ? Material.LIME_WOOL : Material.RED_WOOL;

        // If disabled, prefer disabled-material replacement
        if (!enabled) {
            String disMat = configs.messages().getString("gui.panel.checks." + id.name() + ".disabled-material", null);
            if (disMat != null) {
                try { mat = Material.valueOf(disMat); } catch (IllegalArgumentException ignored) { }
            }
        }

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            // Display name
            String namePath = enabled ? "gui.panel.check-item.name-enabled" : "gui.panel.check-item.name-disabled";
            String displayName = configs.messages().getString(namePath, (enabled ? "&a&l" : "&c&l") + id.name());
            displayName = displayName.replace("{check}", id.name());
            meta.setDisplayName(ColorUtil.color(displayName));

            // Only short lore in main panel — full description lives in the detail Info.
            List<String> lore = new ArrayList<>();
            // If disabled and a dedicated disabled-item config exists, use it (but do not inject full descriptions)
            List<String> disabledCfg = configs.messages().getStringList("gui.panel.checks." + id.name() + ".disabled-item.lore");
            String disabledName = configs.messages().getString("gui.panel.checks." + id.name() + ".disabled-item.name", null);
            if (!enabled && ((disabledCfg != null && !disabledCfg.isEmpty()) || disabledName != null)) {
                if (disabledName != null) meta.setDisplayName(ColorUtil.color(disabledName.replace("{check}", id.name())));
                if (disabledCfg != null) {
                    for (String s : disabledCfg) {
                        if (s == null) continue;
                        // avoid inserting long descriptions here — replace {description} with a hint
                        String line = s.replace("{check}", id.name()).replace("{description}", configs.messages().getString("gui.panel.check-item.description-hint", "(see details)"));
                        // use configured line as-is (messages.yml controls color/format)
                        lore.add(ColorUtil.color(line));
                    }
                }
            } else {
                List<String> shortCfg = configs.messages().getStringList("gui.panel.check-item.short-lore");
                if (shortCfg != null && !shortCfg.isEmpty()) {
                    for (String s : shortCfg) {
                        if (s == null) continue;
                        String line = s.replace("{check}", id.name()).replace("{type}", id.type()).replace("{state}", enabled ? "&a✔ Attivo" : "&c✘ Disattivo");
                        // use configured line as-is (messages.yml controls color/format)
                        lore.add(ColorUtil.color(line));
                    }
                } else {
                    lore.add(ColorUtil.color("&e&lClick per maggiori informazioni"));
                }
            }

            meta.setLore(lore);
            hideItemAttributes(meta);
            it.setItemMeta(meta);
        }
        return it;
    }
    
        /** Format a long description into a list of lore lines with bullets and continuation prefix. */
        private List<String> formatDescription(String desc, int wrap) {
            List<String> out = new ArrayList<>();
            if (desc == null || desc.isEmpty()) return out;

            String bullet = configs.messages().getString("gui.detail.bullet", "&e- ");
            String cont = configs.messages().getString("gui.detail.continuation", "  ");

            String[] paragraphs = desc.split("\\n");
            for (String para : paragraphs) {
                List<String> lines = wrap(para.trim(), wrap);
                if (lines.isEmpty()) continue;
                // first line with bullet
                out.add(ColorUtil.color(bullet + lines.get(0)));
                // continuation lines
                for (int i = 1; i < lines.size(); i++) {
                    out.add(ColorUtil.color(cont + lines.get(i)));
                }
            }

            return out;
        }

    /** Simple word-wrap returning lines not longer than max (tries to respect word boundaries). */
    private List<String> wrap(String text, int max) {
        if (text == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        String[] words = text.split(" ");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            if (cur.length() == 0) {
                cur.append(w);
            } else if (cur.length() + 1 + w.length() <= max) {
                cur.append(' ').append(w);
            } else {
                out.add(cur.toString());
                cur = new StringBuilder(w);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
    
    @SuppressWarnings("deprecation")
    private ItemStack makeStatusItem(Player p) {
        ItemStack it = new ItemStack(Material.BEACON);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = configs.messages().getString("gui.panel.status-item.name", "&b&lStato Server");
            meta.setDisplayName(ColorUtil.color(name));

            List<String> lore = new ArrayList<>();

            // TPS
            double tps = tickService != null ? tickService.getTps() : 20.0;
            String tpsColor = tps >= 19 ? "&a" : (tps >= 15 ? "&e" : "&c");
            String tpsTpl = configs.messages().getString("gui.panel.status-item.tps", "&7TPS: {color}{tps}");
            lore.add(ColorUtil.color(tpsTpl.replace("{color}", tpsColor).replace("{tps}", String.format(Locale.US, "%.2f", tps))));

            // Online players
            int online = Bukkit.getOnlinePlayers().size();
            int max = Bukkit.getMaxPlayers();
            String playersTpl = configs.messages().getString("gui.panel.status-item.players", "&7Players: &f{online}&7/&f{max}");
            lore.add(ColorUtil.color(playersTpl.replace("{online}", String.valueOf(online)).replace("{max}", String.valueOf(max))));

            // Active checks
            int activeChecks = checkManager.enabledCount();
            int totalChecks = CheckId.values().length;
            String checksTpl = configs.messages().getString("gui.panel.status-item.active-checks", "&7Checks attivi: &a{active}&7/&f{total}");
            lore.add(ColorUtil.color(checksTpl.replace("{active}", String.valueOf(activeChecks)).replace("{total}", String.valueOf(totalChecks))));

            // Memory usage
            Runtime runtime = Runtime.getRuntime();
            long usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
            long maxMem = runtime.maxMemory() / 1024 / 1024;
            double memPercent = (double) usedMem / maxMem * 100;
            String memColor = memPercent < 70 ? "&a" : (memPercent < 85 ? "&e" : "&c");
            String memTpl = configs.messages().getString("gui.panel.status-item.memory", "&7RAM: {color}{used}MB&7/&f{max}MB");
            lore.add(ColorUtil.color(memTpl.replace("{color}", memColor).replace("{used}", String.valueOf(usedMem)).replace("{max}", String.valueOf(maxMem))));

            meta.setLore(lore);
            hideItemAttributes(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    @SuppressWarnings("deprecation")
    private ItemStack makeDetailInfoItem(Player p, CheckId id, Check c) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = configs.messages().getString("gui.detail.info-item.name", "&b&lInfo &8- &f{check}")
                    .replace("{check}", id.name());
            meta.setDisplayName(ColorUtil.color(name));

            List<String> cfg = configs.messages().getStringList("gui.detail.info-item.lore");
            List<String> lore = new ArrayList<>();
            int wrap = configs.messages().getInt("gui.panel.wrap-length", 35);

            if (cfg != null && !cfg.isEmpty()) {
                for (String s : cfg) {
                    if (s == null) continue;
                    if (s.contains("{description}")) {
                        String extrasHeader = configs.messages().getString("gui.detail.extras-header", "&6&lDettagli:");
                        lore.add(ColorUtil.color(extrasHeader));
                        String customDesc = configs.messages().getString("gui.templates." + id.name() + ".description", id.description());
                        List<String> descLines = formatDescription(customDesc, wrap);
                        lore.addAll(descLines);
                    } else {
                        String replaced = s.replace("{type}", id.type())
                                           .replace("{check}", id.name())
                                           .replace("{state}", c.enabled() ? "&a✔ Attivo" : "&c✘ Disattivo");
                        lore.add(ColorUtil.color(replaced));
                    }
                }
            } else {
                String sep = configs.messages().getString("gui.detail.info-item.separator", "&7──────────────────");
                String typeLine = configs.messages().getString("gui.detail.info-item.type", "&eTipo: &f{type}").replace("{type}", id.type());
                String stateLine = configs.messages().getString("gui.detail.info-item.state", "&eStato: &f{state}").replace("{state}", (c.enabled() ? "&a✔ Attivo" : "&c✘ Disattivo"));
                String extrasHeader = configs.messages().getString("gui.detail.extras-header", "&6&lDettagli:");
                lore.add(ColorUtil.color(sep));
                lore.add(ColorUtil.color(typeLine));
                lore.add("");
                lore.add(ColorUtil.color(extrasHeader));
                String customDesc = configs.messages().getString("gui.templates." + id.name() + ".description", id.description());
                lore.addAll(formatDescription(customDesc, wrap));
                lore.add("");
                lore.add(ColorUtil.color(stateLine));
            }

            // Remove duplicate header if messages.yml placed the name as first lore line
            String disp = ColorUtil.color(configs.messages().getString("gui.detail.info-item.name", "&d&l{check}").replace("{check}", id.name()));
            if (!lore.isEmpty() && lore.get(0).equals(disp)) {
                lore.remove(0);
            }

            // Append optional template extras for this check
            List<String> extras = configs.messages().getStringList("gui.templates." + id.name() + ".extra");
            if (extras != null && !extras.isEmpty()) {
                lore.add("");
                for (String ex : extras) lore.add(ColorUtil.color(ex.replace("{check}", id.name())));
            }

            meta.setLore(lore);
            hideItemAttributes(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    @SuppressWarnings("deprecation")
    private ItemStack makeDetailToggleItem(Player p, CheckId id, boolean enabled) {
        String namePath = enabled ? "gui.detail.toggle-item.name-enabled" : "gui.detail.toggle-item.name-disabled";
        String name = configs.messages().getString(namePath, (enabled ? "&a&lDisattiva {check}" : "&c&lAttiva {check}"))
                .replace("{check}", id.name());
        Material mat = enabled ? Material.LIME_WOOL : Material.RED_WOOL;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ColorUtil.color(name));
            List<String> cfg = configs.messages().getStringList("gui.detail.toggle-item.lore");
            List<String> lore = new ArrayList<>();
            if (cfg != null && !cfg.isEmpty()) {
                for (String s : cfg) lore.add(ColorUtil.color(s.replace("{action}", enabled ? "disattivare" : "attivare").replace("{check}", id.name())));
            }
            meta.setLore(lore);
            hideItemAttributes(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    @SuppressWarnings("deprecation")
    private ItemStack makeDetailSettingsItem(Player p, CheckId id) {
        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = configs.messages().getString("gui.detail.settings-item.name", "&e&lImpostazioni &8- &f{check}").replace("{check}", id.name());
            meta.setDisplayName(ColorUtil.color(name));
            List<String> cfg = configs.messages().getStringList("gui.detail.settings-item.lore");
            List<String> lore = new ArrayList<>();
            if (cfg != null && !cfg.isEmpty()) {
                for (String s : cfg) lore.add(ColorUtil.color(s.replace("{check}", id.name())));
            }
            meta.setLore(lore);
            hideItemAttributes(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    @SuppressWarnings("deprecation")
    private ItemStack makeBackItem() {
        ItemStack it = new ItemStack(Material.ARROW);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String name = configs.messages().getString("gui.detail.back-item.name", "&c&lIndietro");
            meta.setDisplayName(ColorUtil.color(name));
            List<String> cfg = configs.messages().getStringList("gui.detail.back-item.lore");
            List<String> lore = new ArrayList<>();
            if (cfg != null && !cfg.isEmpty()) {
                for (String s : cfg) lore.add(ColorUtil.color(s));
            }
            meta.setLore(lore);
            hideItemAttributes(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    private String getRole(Player p) {
        if (p.hasPermission("virgolaac.admin")) return "admin";
        if (p.hasPermission("virgolaac.mod")) return "mod";
        return "default";
    }
    
    @SuppressWarnings("deprecation")
    private ItemStack makeGlassPane() {
        ItemStack it = new ItemStack(Material.MAGENTA_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            String glassName = configs.messages().getString("gui.panel.glass-name", "&d ");
            meta.setDisplayName(ColorUtil.color(glassName));
            hideItemAttributes(meta);
            it.setItemMeta(meta);
        }
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        if (event.getInventory() == null || !(event.getInventory().getHolder() instanceof Holder)) return;

        event.setCancelled(true);



        Holder holder = (Holder) event.getInventory().getHolder();
        CheckId holderId = holder.id();

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        // If this inventory has a settings context, handle settings interactions
        SettingsContext sctx = invSettings.get(event.getInventory());
        if (sctx != null) {
            handleSettingsClick(p, event, sctx);
            return;
        }

        // If main panel
        if (holderId == null) {
            // Ignore clicks on glass panes and status item
            if (slot >= 45) return;

            CheckId[] ids = CheckId.values();
            if (slot >= ids.length) return;

            CheckId id = ids[slot];
            // Open detail view
            openDetail(p, id);
            return;
        }

        // Detail inventory interactions
        CheckId id = holderId;
        // Toggle slot (13)
        if (slot == 13) {
            boolean newState = checkManager.toggle(id);
            // update toggle and info
            event.getInventory().setItem(13, makeDetailToggleItem(p, id, newState));
            event.getInventory().setItem(11, makeDetailInfoItem(p, id, checkManager.get(id)));
            p.updateInventory();
            return;
        }

        // Settings / violations slot (15) -> open settings viewer
        if (slot == 15) {
            openSettings(p, id);
            return;
        }

        // Back button (22)
        if (slot == 22) {
            p.closeInventory();
            open(p);
            return;
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (!chatInputContexts.containsKey(uuid)) return;

        // We expect this chat message as the next value for a config param
        event.setCancelled(true);
        ChatInputContext ctx = chatInputContexts.remove(uuid);
        if (ctx == null) return;

        String msg = event.getMessage().trim();
        Player p = event.getPlayer();

        if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("annulla")) {
            p.sendMessage(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.cancelled", "&cModifica annullata.")));
            // Reopen settings inventory
            Bukkit.getScheduler().runTask(plugin, () -> openSettings(p, ctx.id));
            return;
        }

        // Parse based on expected type
        Object parsed = null;
        try {
            if (ctx.type == Boolean.class || ctx.type == boolean.class) {
                String v = msg.toLowerCase(Locale.ROOT);
                if (v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("si")) parsed = true;
                else if (v.equals("false") || v.equals("off") || v.equals("no")) parsed = false;
                else {
                    p.sendMessage(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.invalid-boolean", "&cValore booleano non valido. Usa true/false o on/off.")));
                    Bukkit.getScheduler().runTask(plugin, () -> openSettings(p, ctx.id));
                    return;
                }
            } else if (Number.class.isAssignableFrom(ctx.type) || ctx.type == int.class || ctx.type == double.class || ctx.type == float.class || ctx.type == long.class) {
                // Try parse double; we'll store as integer if original was integer
                double d = Double.parseDouble(msg.replace(',', '.'));
                // Determine original type by inspecting current config value
                Object cur = configs.checks().cfg().get(ctx.path);
                if (cur instanceof Integer) parsed = (int) Math.round(d);
                else if (cur instanceof Long) parsed = (long) Math.round(d);
                else if (cur instanceof Float) parsed = (float) d;
                else parsed = d;
            } else if (List.class.isAssignableFrom(ctx.type) || configs.checks().cfg().get(ctx.path) instanceof java.util.List) {
                // parse comma-separated list of strings
                String[] parts = msg.split(",");
                List<String> list = new ArrayList<>();
                for (String s : parts) {
                    String t = s.trim();
                    if (!t.isEmpty()) list.add(t);
                }
                parsed = list;
            } else {
                // treat as string
                parsed = msg;
            }
        } catch (NumberFormatException ex) {
            p.sendMessage(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.invalid-number", "&cValore numerico non valido.")));
            Bukkit.getScheduler().runTask(plugin, () -> openSettings(p, ctx.id));
            return;
        }

        // Save value
        configs.checks().set(ctx.path, parsed);
        if (plugin != null) Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> configs.checks().save());
        else configs.checks().save();

        p.sendMessage(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.saved", "&aValore salvato.")));
        // Reopen settings inventory on main thread
        // If this was the enabled flag for the check, ensure CheckManager reflects it
        String enabledPath = "checks." + ctx.id.name() + ".enabled";
        if (enabledPath.equals(ctx.path) && parsed instanceof Boolean) {
            Check ch = checkManager.get(ctx.id);
            if (ch != null) ch.setEnabled((Boolean) parsed);
            // persist already done via configs
            refreshMainPanelForCheck(ctx.id, (Boolean) parsed);
        }

        Bukkit.getScheduler().runTask(plugin, () -> openSettings(p, ctx.id));
    }

    @SuppressWarnings("deprecation")
    private void openSettings(Player p, CheckId id) {
        // Build list of parameters for this check
        String base = "checks." + id.name();
        org.bukkit.configuration.file.YamlConfiguration cfg = configs.checks().cfg();

        // First look for params section
        org.bukkit.configuration.ConfigurationSection params = cfg.getConfigurationSection(base + ".params");
        Map<String, Object> map = new LinkedHashMap<>();
        if (params != null) {
            for (String key : params.getKeys(false)) {
                map.put(key, params.get(key));
            }
        } else {
            // fallback: include top-level keys and sub_checks if present
            org.bukkit.configuration.ConfigurationSection root = cfg.getConfigurationSection(base);
            if (root != null) {
                for (String key : root.getKeys(false)) {
                    if ("sub_checks".equals(key)) continue;
                    map.put(key, root.get(key));
                }
                org.bukkit.configuration.ConfigurationSection subs = root.getConfigurationSection("sub_checks");
                if (subs != null) {
                    for (String sub : subs.getKeys(false)) {
                        org.bukkit.configuration.ConfigurationSection sc = subs.getConfigurationSection(sub);
                        if (sc == null) continue;
                        for (String k : sc.getKeys(false)) {
                            map.put("sub_checks." + sub + "." + k, sc.get(k));
                        }
                    }
                }
            }
        }

        if (map.isEmpty()) {
            p.sendMessage(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.not-implemented", "&eNessun parametro configurabile.")));
            return;
        }

        int count = map.size();
        int rows = Math.max(1, (int) Math.ceil(count / 9.0));
        int size = rows * 9;
        Inventory inv = Bukkit.createInventory(new Holder(id), size, ColorUtil.color(configs.messages().getString("gui.detail.settings-inventory-title", "&e&lSettings &8- &f{check}").replace("{check}", id.name())));

        int slot = 0;
        Map<Integer, String> slotToPath = new HashMap<>();
        boolean hasParamsSection = cfg.getConfigurationSection(base + ".params") != null;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            String fullPath;
            // If this entry came from sub_checks we kept the prefix
            if (key.startsWith("sub_checks.")) {
                fullPath = base + "." + key;
            } else if (hasParamsSection) {
                // Most checks store values under checks.<CHECK>.params.<key>
                fullPath = base + ".params." + key;
            } else {
                fullPath = base + "." + key;
            }

            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                String paramNameFmt = configs.messages().getString("gui.detail.settings-item.param-name-format", "&6{param}");
                meta.setDisplayName(ColorUtil.color(paramNameFmt.replace("{param}", key)));
                List<String> lore = new ArrayList<>();
                Object val = cfg.get(fullPath);

                // Add a user-friendly description if available in messages.yml
                // fallback attempts
                String desc = configs.messages().getString("gui.detail.settings-item.descriptions." + id.name() + "." + key, null);
                if (desc == null) desc = configs.messages().getString("gui.detail.settings-item.descriptions." + id.name() + "." + key.replace('.', '_'), null);
                if (desc == null) desc = configs.messages().getString("gui.detail.settings-item.descriptions." + key, null);
                int wrapLen = configs.messages().getInt("gui.panel.wrap-length", 35);
                if (desc != null) {
                    List<String> dlines = wrap(desc, wrapLen);
                    for (String dl : dlines) lore.add(ColorUtil.color(dl));
                    lore.add("");
                }

                String valueTpl = configs.messages().getString("gui.detail.settings-item.value", "&7Value: &f{value}");
                lore.add(ColorUtil.color(valueTpl.replace("{value}", String.valueOf(val))));
                lore.add("");
                if (val instanceof Boolean) {
                    lore.add(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.click-toggle", "&eModifica in chat (true/false)")));
                } else if (val instanceof Number) {
                    lore.add(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.left-right", "&eInserisci un numero in chat")));
                } else if (val instanceof java.util.List) {
                    lore.add(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.list-hint", "&eInserisci valori separati da virgola")));
                } else {
                    String typeTpl = configs.messages().getString("gui.detail.settings-item.type", "&7Type: &f{type}");
                    lore.add(ColorUtil.color(typeTpl.replace("{type}", (val == null ? "null" : val.getClass().getSimpleName()))));
                }
                meta.setLore(lore);
                hideItemAttributes(meta);
                it.setItemMeta(meta);
            }

            inv.setItem(slot, it);
            slotToPath.put(slot, fullPath);
            slot++;
        }

        // Register the settings context for this inventory and open it.
        invSettings.put(inv, new SettingsContext(id, slotToPath));
        p.openInventory(inv);
    }

    private void handleSettingsClick(Player p, InventoryClickEvent event, SettingsContext ctx) {
        int slot = event.getRawSlot();
        String path = ctx.slotToPath.get(slot);
        if (path == null) return;
        org.bukkit.configuration.file.YamlConfiguration cfg = configs.checks().cfg();
        Object val = cfg.get(path);

        // Permission check for editing settings
        if (!p.hasPermission("virgolaac.panel.editsettings")) {
            p.sendMessage(ColorUtil.color(configs.messages().getString("gui.detail.settings-item.no-permission", "&cNon hai il permesso per modificare le impostazioni.")));
            return;
        }



        // Always prompt via chat for an explicit new value (provides consistent UX and validation)
        p.closeInventory();
        Class<?> type = val == null ? String.class : val.getClass();
        chatInputContexts.put(p.getUniqueId(), new ChatInputContext(ctx.id, path, type));
        String prompt = configs.messages().getString("gui.detail.settings-item.prompt", "&eInserisci il nuovo valore per {param} in chat. Scrivi 'cancel' per annullare.");
        String human = path.substring(path.lastIndexOf('.') + 1);
        p.sendMessage(ColorUtil.color(prompt.replace("{param}", human).replace("{value}", String.valueOf(val == null ? "null" : val))));
        return;
    }

    private void refreshMainPanelForCheck(CheckId id, boolean newState) {
        // Update any open main panel inventories for all tracked players
        CheckId[] ids = CheckId.values();
        int idx = -1;
        for (int i = 0; i < ids.length; i++) if (ids[i] == id) { idx = i; break; }
        if (idx < 0) return;

        for (UUID u : new HashSet<>(openGuis)) {
            Player pl = Bukkit.getPlayer(u);
            if (pl == null) continue;
            if (pl.getOpenInventory() == null) continue;
            Inventory top = pl.getOpenInventory().getTopInventory();
            if (top == null || !(top.getHolder() instanceof Holder) || ((Holder) top.getHolder()).id() != null) continue;
            // Recreate item for this player role
            Player player = pl;
            ItemStack item = makeCheckItem(player, id, newState);
            int slot = idx;
            if (slot < top.getSize()) top.setItem(slot, item);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder)) return;

        Holder holder = (Holder) event.getInventory().getHolder();
        UUID uuid = event.getPlayer().getUniqueId();
        openGuis.remove(uuid);

        // Cancel update task only if closing main panel
        if (holder.id() == null) {
            BukkitTask task = updateTasks.remove(uuid);
            if (task != null) task.cancel();
        }

        // Remove any settings context associated with this inventory
        invSettings.remove(event.getInventory());
    }
}

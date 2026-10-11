package it.virgola.anticheat;

import com.github.retrooper.packetevents.PacketEvents;
import it.virgola.anticheat.checks.CheckManager;
import it.virgola.anticheat.commands.AcCommand;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.*;
import it.virgola.anticheat.gui.AcGui;
import it.virgola.anticheat.listeners.BlockPlaceListener;
import it.virgola.anticheat.listeners.CoreListener;
import it.virgola.anticheat.listeners.DamageListener;
import it.virgola.anticheat.listeners.FreezeListener;
import it.virgola.anticheat.listeners.MovementExemptionListener;
import it.virgola.anticheat.listeners.MovementListener;
import it.virgola.anticheat.packets.PacketIngestor;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class VirgolaAntiCheat extends JavaPlugin {

    private Configs configs;

    private TickService tickService;
    private LagCompensation lag;

    private PlayerDataManager playerDataManager;
    private AlertService alertService;
    private ViolationService violationService;
    private CheckManager checkManager;

    private PacketIngestor packetIngestor;
    private AcGui gui;

    @Override
    public void onEnable() {
        // Configs
        saveDefaultConfig();
        this.configs = new Configs(this);
        configs.init();

        // Core services
        this.tickService = new TickService();
        tickService.start(this);

        double pingBase = getConfig().getDouble("lag-compensation.ping_multiplier.base", 1.0);
        double pingPer100 = getConfig().getDouble("lag-compensation.ping_multiplier.per_100ms", 0.15);
        double tpsBase = getConfig().getDouble("lag-compensation.tps_multiplier.base", 1.0);
        double tpsPerBelow = getConfig().getDouble("lag-compensation.tps_multiplier.per_tps_below_20", 0.10);
        this.lag = new LagCompensation(tickService, pingBase, pingPer100, tpsBase, tpsPerBelow);

        this.playerDataManager = new PlayerDataManager();
        
        ExemptionService exemptionService = new ExemptionService();

        this.alertService = new AlertService(configs);
        this.violationService = new ViolationService(configs, alertService);

        // Pass 'this' plugin reference for async scheduling
        this.checkManager = new CheckManager(configs, violationService, lag, exemptionService, this);

        this.packetIngestor = new PacketIngestor(configs, playerDataManager, checkManager);

        // PacketEvents: we do NOT create an instance here (PacketEvents is an external plugin).
        PacketEvents.getAPI().getEventManager().registerListener(packetIngestor);

        // Listeners
        Bukkit.getPluginManager().registerEvents(new CoreListener(playerDataManager), this);
        Bukkit.getPluginManager().registerEvents(new BlockPlaceListener(playerDataManager, checkManager), this);
        Bukkit.getPluginManager().registerEvents(new MovementListener(playerDataManager, checkManager), this);
        Bukkit.getPluginManager().registerEvents(new DamageListener(playerDataManager, checkManager), this);
        
        getServer().getPluginManager().registerEvents(new MovementExemptionListener(exemptionService), this);


        // GUI
        this.gui = new AcGui(configs, checkManager, tickService, this);
        Bukkit.getPluginManager().registerEvents(gui, this);

        // Command
        AcCommand ac = new AcCommand(configs, checkManager, tickService, violationService, alertService, gui);
        getCommand("ac").setExecutor(ac);
        getCommand("ac").setTabCompleter(ac);
        
        // Freeze listener (needs AcCommand reference)
        Bukkit.getPluginManager().registerEvents(new FreezeListener(ac), this);

        getLogger().info("VirgolaAntiCheat enabled. Paper 1.21.11 - PacketEvents hooked.");
    }

    @Override
    public void onDisable() {
        // Shutdown check async resources
        if (checkManager != null) {
            checkManager.shutdown();
        }
        
        try {
            if (packetIngestor != null) {
                PacketEvents.getAPI().getEventManager().unregisterListener(packetIngestor);
            }
        } catch (Throwable ignored) {
            // If PacketEvents is shutting down, unregister may fail.
        }
        getLogger().info("VirgolaAntiCheat disabled.");
    }
}

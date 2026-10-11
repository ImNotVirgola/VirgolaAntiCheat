package it.virgola.anticheat.config;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Arrays;

public final class Configs {

    private final JavaPlugin plugin;

    private Messages messages;
    private YamlFile checks;

    public Configs(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        // Ensure folder + defaults
        plugin.saveDefaultConfig();
        
        // Save/update the main configuration files
        saveOrUpdateResource("messages.yml");
        saveOrUpdateResource("checks.yml");
        
        // Load the configurations from the files
        messages = new Messages(new File(plugin.getDataFolder(), "messages.yml"));
        checks = new YamlFile(new File(plugin.getDataFolder(), "checks.yml"));
    }

    public void reloadAll() {
        plugin.reloadConfig();
        messages.reload();
        checks.reload();
    }

    public JavaPlugin plugin() { return plugin; }

    public Messages messages() { return messages; }
    public YamlFile checks() { return checks; }

    @SuppressWarnings("unused")
    private void saveResourceIfMissing(String name) {
        File out = new File(plugin.getDataFolder(), name);
        if (out.exists()) return;
        plugin.saveResource(name, false);
    }

    private void saveOrUpdateResource(String name) {
        try {
            File out = new File(plugin.getDataFolder(), name);
            java.io.InputStream in = plugin.getResource(name);
            if (in == null) {
                plugin.getLogger().warning("Failed to find resource " + name + " in JAR.");
                return; // resource not bundled
            }

            if (out.exists()) {
                // compare contents
                byte[] existing = java.nio.file.Files.readAllBytes(out.toPath());
                byte[] fresh = in.readAllBytes();
                in.close();
                if (Arrays.equals(existing, fresh)) return; // identical, skip
                // overwrite with packaged resource
                plugin.getLogger().info("Configuration file " + name + " is outdated. Overwriting with the new version.");
                plugin.saveResource(name, true);
            } else {
                in.close();
                plugin.getLogger().info("Configuration file " + name + " not found. Creating a new one.");
                plugin.saveResource(name, false);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to copy resource " + name + ": " + e.getMessage());
        }
    }
}

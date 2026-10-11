package it.virgola.anticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class Configs {

    private final JavaPlugin plugin;
    private Messages messages;
    private YamlFile checks;

    public Configs(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init() {
        plugin.saveDefaultConfig();
        saveIfMissing("messages.yml");
        saveIfMissing("checks.yml");

        messages = new Messages(new File(plugin.getDataFolder(), "messages.yml"),
                loadBundledDefaults("messages.yml"));
        checks = new YamlFile(new File(plugin.getDataFolder(), "checks.yml"),
                loadBundledDefaults("checks.yml"));
    }

    public void reloadAll() {
        plugin.reloadConfig();
        messages.reload();
        checks.reload();
    }

    public JavaPlugin plugin() { return plugin; }
    public Messages messages() { return messages; }
    public YamlFile checks() { return checks; }

    // Existing configurations belong to the server administrator.
    private void saveIfMissing(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
        }
    }

    // Missing settings can use packaged defaults without modifying the user's file.
    private YamlConfiguration loadBundledDefaults(String name) {
        try (InputStream stream = plugin.getResource(name)) {
            if (stream == null) {
                plugin.getLogger().warning("Bundled configuration not found: " + name);
                return new YamlConfiguration();
            }
            return YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("Unable to read bundled defaults for " + name + ": " + e.getMessage());
            return new YamlConfiguration();
        }
    }
}

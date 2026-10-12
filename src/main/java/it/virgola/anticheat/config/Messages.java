package it.virgola.anticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

public final class Messages {

    private final YamlFile yaml;

    public Messages(File file) {
        this(file, new YamlConfiguration());
    }

    public Messages(File file, YamlConfiguration defaults) {
        yaml = new YamlFile(file, defaults);
    }

    public void reload() { yaml.reload(); }
    public String getString(String path, String def) { return yaml.getString(path, def); }
    public List<String> getStringList(String path) { return yaml.getStringList(path); }
    public int getInt(String path, int def) { return yaml.getInt(path, def); }
    public long getLong(String path, long def) { return yaml.getLong(path, def); }
    public double getDouble(String path, double def) { return yaml.getDouble(path, def); }
    public YamlConfiguration cfg() { return yaml.cfg(); }
}

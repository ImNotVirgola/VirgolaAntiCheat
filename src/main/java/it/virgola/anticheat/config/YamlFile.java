package it.virgola.anticheat.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public final class YamlFile {

    private final File file;
    private final YamlConfiguration defaults;
    private YamlConfiguration cfg;

    public YamlFile(File file) {
        this(file, new YamlConfiguration());
    }

    public YamlFile(File file, YamlConfiguration defaults) {
        this.file = file;
        this.defaults = defaults == null ? new YamlConfiguration() : defaults;
        reload();
    }

    public void reload() {
        YamlConfiguration loaded = new YamlConfiguration();
        // Parse comments before reading the file, so settings changed by the GUI
        // retain comments where supported by Bukkit's YAML serializer.
        loaded.options().parseComments(true);
        try {
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("Unable to load configuration " + file.getName()
                    + ". The original file has not been changed.", e);
        }
        loaded.setDefaults(defaults);
        loaded.options().copyDefaults(false);
        cfg = loaded;
    }

    public void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save YAML: " + file.getName(), e);
        }
    }

    public YamlConfiguration cfg() { return cfg; }

    public void set(String path, boolean value) { if (path != null) cfg.set(path, value); }
    public void set(String path, int value) { if (path != null) cfg.set(path, value); }
    public void set(String path, long value) { if (path != null) cfg.set(path, value); }
    public void set(String path, double value) { if (path != null) cfg.set(path, value); }
    public void set(String path, String value) { if (path != null) cfg.set(path, value); }
    public void set(String path, Object value) { if (path != null) cfg.set(path, value); }

    public boolean getBoolean(String path, boolean def) { return cfg.getBoolean(path, def); }
    public String getString(String path, String def) {
        String value = cfg.getString(path);
        return value == null ? def : value;
    }
    public List<String> getStringList(String path) {
        return path == null ? Collections.emptyList() : cfg.getStringList(path);
    }
    public int getInt(String path, int def) { return cfg.getInt(path, def); }
    public long getLong(String path, long def) {
        Object value = cfg.get(path);
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String string) {
            try { return Long.parseLong(string); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
    public double getDouble(String path, double def) {
        Object value = cfg.get(path);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String string) {
            try { return Double.parseDouble(string); } catch (NumberFormatException ignored) {}
        }
        return def;
    }
    public File file() { return file; }
}

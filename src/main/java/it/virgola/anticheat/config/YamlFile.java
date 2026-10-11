package it.virgola.anticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

public final class YamlFile {

    private final File file;
    private YamlConfiguration cfg;

    public YamlFile(File file) {
        this.file = file;
        reload();
    }

    public void reload() {
        this.cfg = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            cfg.save(file);
        } catch (IOException e) {
            throw new RuntimeException("Unable to save YAML: " + file.getName(), e);
        }
    }

    public YamlConfiguration cfg() {
        return cfg;
    }
    
    public void set(String path, boolean value) {
        if (path == null) return;
        cfg.set(path, value);
    }

    public void set(String path, int value) {
        if (path == null) return;
        cfg.set(path, value);
    }

    public void set(String path, long value) {
        if (path == null) return;
        cfg.set(path, value);
    }

    public void set(String path, double value) {
        if (path == null) return;
        cfg.set(path, value);
    }

    public void set(String path, String value) {
        if (path == null) return;
        cfg.set(path, value);
    }

    public void set(String path, Object value) {
        if (path == null) return;
        cfg.set(path, value);
    }


    public boolean getBoolean(String path, boolean def) {
        return cfg.getBoolean(path, def);
    }

    public String getString(String path, String def) {
        String v = cfg.getString(path);
        return v != null ? v : def;
    }
    public List<String> getStringList(String path) {
        if (path == null) return Collections.emptyList();
        List<String> list = cfg.getStringList(path); // "yaml" è la tua YamlConfiguration interna
        return list == null ? Collections.emptyList() : list;
    }

    public int getInt(String path, int def) {
        return cfg.getInt(path, def);
    }

    public long getLong(String path, long def) {
        Object o = cfg.get(path);
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public double getDouble(String path, double def) {
        Object o = cfg.get(path);
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public File file() { return file; }
}

package it.virgola.anticheat.config;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;

public final class Messages {

    private final File file;
    private YamlConfiguration config;

    public Messages(File file) {
        this.file = file;
        reload();
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
    }

    public String getString(String path, String def) {
        String v = config.getString(path);
        return v != null ? v : def;
    }

    public List<String> getStringList(String path) {
        List<String> list = config.getStringList(path);
        return list == null ? java.util.Collections.emptyList() : list;
    }

    public int getInt(String path, int def) {
        return config.getInt(path, def);
    }

    public long getLong(String path, long def) {
        Object o = config.get(path);
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    public double getDouble(String path, double def) {
        Object o = config.get(path);
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    // expose underlying merged for advanced uses
    public YamlConfiguration cfg() { return config; }
}

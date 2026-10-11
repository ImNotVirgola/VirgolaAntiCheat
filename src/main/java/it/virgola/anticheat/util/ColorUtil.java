package it.virgola.anticheat.util;

import net.md_5.bungee.api.ChatColor;

public final class ColorUtil {
    private ColorUtil() {}

    public static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}

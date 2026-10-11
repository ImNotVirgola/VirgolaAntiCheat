package it.virgola.anticheat.util;

import java.util.Map;

public final class MsgUtil {
    private MsgUtil() {}

    public static String format(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return ColorUtil.color(out);
    }
}

package it.virgola.anticheat.core;

import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.util.MsgUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class DiscordService {

    private final Configs configs;

    // Cache Crafatar availability to avoid repeated checks (milliseconds)
    private volatile boolean crafatarAvailable = true;
    private volatile long crafatarLastChecked = 0L;
    private static final long CRAFATAR_CHECK_INTERVAL_MS = 5 * 60 * 1000L; // 5 minutes

    public DiscordService(Configs configs) {
        this.configs = configs;
    }

    public void sendAlert(Player suspect, String check, int addVl, String info) {
        sendAlert(suspect, check, addVl, info, null);
    }

    /**
     * Send an alert and notify a callback with overall result code.
     * callback.accept(0) means at least one webhook succeeded (HTTP < 400).
     * Otherwise callback.accept(lastCode) with the last HTTP code or -1 on exception.
     */
    public void sendAlert(Player suspect, String check, int addVl, String info, java.util.function.IntConsumer callback) {
        if (!configs.plugin().getConfig().getBoolean("discord.enabled", false)) return;
        // Determine webhooks to post to (per-check override > list > legacy single URL)
        java.util.List<String> webhooks = getWebhooksFor(check);
        if (webhooks == null || webhooks.isEmpty()) {
            if (callback != null) callback.accept(-1);
            return;
        }

        String fmt = configs.messages().getString("discord.format",
                "**{player}** was detected by **{check}** (+{vl}) - {info}");

        Map<String, String> vars = new HashMap<>();
        vars.put("player", suspect.getName());
        vars.put("check", check);
        vars.put("vl", String.valueOf(addVl));
        vars.put("info", info == null ? "" : info);

        String content = MsgUtil.format(fmt, vars);
        // Remove color codes (Minecraft § codes) for Discord
        content = content.replaceAll("§.", "");

        final boolean embedEnabled = configs.plugin().getConfig().getBoolean("discord.embed.enabled", true);
        final String username = configs.plugin().getConfig().getString("discord.username", "VirgolaAC");
        final String avatar = configs.plugin().getConfig().getString("discord.avatar-url", "");

        final String payload = embedEnabled ? jsonEncodeEmbed(suspect, check, addVl, info, content, username, avatar)
                : jsonEncodeContent(content);

        // Send asynchronously to each configured webhook with retry/backoff for 429
        Bukkit.getScheduler().runTaskAsynchronously(configs.plugin(), () -> {
            int lastCode = -1;
            boolean anySuccess = false;
            for (String webhook : webhooks) {
                int code = sendWithRetries(webhook, payload);
                lastCode = code;
                if (code >= 0 && code < 400) anySuccess = true;
            }
            if (callback != null) {
                if (anySuccess) callback.accept(0);
                else callback.accept(lastCode);
            }
        });
    }

    @SuppressWarnings("deprecation")
    private int sendWithRetries(String webhook, String payload) {
        final int maxRetries = 3;
        int attempt = 0;
        int lastCode = -1;
        while (attempt < maxRetries) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(webhook);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);

                byte[] out = payload.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(out.length);
                conn.connect();
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }

                int code = conn.getResponseCode();
                lastCode = code;
                if (code == 429) {
                    // Rate limited: try to read Retry-After
                    String ra = conn.getHeaderField("Retry-After");
                    long waitMs = 2000L * (1L << attempt); // exponential backoff
                    if (ra != null) {
                        try {
                            waitMs = (long) (Double.parseDouble(ra) * 1000L);
                        } catch (NumberFormatException ignored) {}
                    }
                    try { Thread.sleep(waitMs); } catch (InterruptedException ignored) {}
                    attempt++;
                    continue;
                }
                if (code >= 400) {
                    Bukkit.getLogger().warning("VirgolaAC: Discord webhook returned HTTP " + code + " for " + webhook);
                }
                return code;
            } catch (Exception e) {
                Bukkit.getLogger().warning("VirgolaAC: Failed to post to Discord webhook: " + e.getMessage());
                lastCode = -1;
                try { Thread.sleep(1000L * (attempt + 1)); } catch (InterruptedException ignored) {}
                attempt++;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return lastCode;
    }

    private java.util.List<String> getWebhooksFor(String check) {
        // Check-specific webhooks first
        try {
            Object mapObj = configs.plugin().getConfig().getConfigurationSection("discord.check-webhooks");
            if (mapObj != null) {
                java.util.List<String> list = configs.plugin().getConfig().getStringList("discord.check-webhooks." + check);
                if (list != null && !list.isEmpty()) return list;
            }
        } catch (Exception ignored) {}

        // List of webhooks
        try {
            java.util.List<String> list = configs.plugin().getConfig().getStringList("discord.webhooks");
            if (list != null && !list.isEmpty()) return list;
        } catch (Exception ignored) {}

        // Legacy single webhook
        String legacy = configs.plugin().getConfig().getString("discord.webhook-url", "");
        if (legacy != null && !legacy.isEmpty()) return java.util.List.of(legacy);
        return java.util.List.of();
    }

    /**
     * Check (cached) whether Crafatar is reachable. Performs a lightweight HEAD request
     * no more often than CRAFATAR_CHECK_INTERVAL_MS.
     */
    @SuppressWarnings("deprecation")
    private boolean isCrafatarAvailable() {
        long now = System.currentTimeMillis();
        if (now - crafatarLastChecked < CRAFATAR_CHECK_INTERVAL_MS) return crafatarAvailable;
        crafatarLastChecked = now;
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://crafatar.com/avatars/00000000000000000000000000000000?size=16");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();
            crafatarAvailable = (code >= 200 && code < 400);
        } catch (Exception e) {
            crafatarAvailable = false;
        } finally {
            if (conn != null) conn.disconnect();
        }
        return crafatarAvailable;
    }

    private String jsonEncodeEmbed(org.bukkit.entity.Player suspect, String check, int vl, String info, String content, String username, String avatarUrl) {
        // Read embed templates
        String titleT = configs.messages().getString("discord.embed.title", "{check} detected");
        String descT = configs.messages().getString("discord.embed.description", "{player} was flagged for {check} (+{vl}) {info}");

        java.util.Map<String, String> vars = new java.util.HashMap<>();
        vars.put("player", suspect.getName());
        vars.put("check", check);
        vars.put("vl", String.valueOf(vl));
        vars.put("info", info == null ? "" : info);
        vars.put("world", suspect.getWorld().getName());
        vars.put("x", String.valueOf(suspect.getLocation().getBlockX()));
        vars.put("y", String.valueOf(suspect.getLocation().getBlockY()));
        vars.put("z", String.valueOf(suspect.getLocation().getBlockZ()));
        vars.put("ping", String.valueOf(suspect.getPing()));

        String title = MsgUtil.format(titleT, vars).replaceAll("§.", "");
        String description = MsgUtil.format(descT, vars).replaceAll("§.", "");

        int color = configs.plugin().getConfig().getInt("discord.embed.color", 16711680); // default red

        // Thumbnail (player head) using Crafatar if available, otherwise fallback to Minotar by username
        String uuidNoDash = suspect.getUniqueId().toString().replace("-", "");
        String thumbnail;
        if (isCrafatarAvailable()) {
            thumbnail = "https://crafatar.com/avatars/" + uuidNoDash + "?size=128&overlay=true&default=steve";
        } else {
            // Fallback service (Minotar) uses username; slightly less robust but usable when Crafatar is down
            thumbnail = "https://minotar.net/avatar/" + suspect.getName() + "/128.png";
        }

        // Debug: log avatar URL and related info to help diagnose missing skins
        try {
            Bukkit.getLogger().info("VirgolaAC: Discord avatar for " + suspect.getName() + " (uuid=" + suspect.getUniqueId() + ") -> " + thumbnail + " (crafatar=" + crafatarAvailable + ")");
            if (avatarUrl != null && !avatarUrl.isEmpty()) {
                Bukkit.getLogger().info("VirgolaAC: Configured discord.avatar-url = " + avatarUrl);
            }
        } catch (Exception ignored) {}

        String serverName = configs.plugin().getServer().getName();
        String footerText = "VirgolaAC • " + serverName;

        StringBuilder sb = new StringBuilder();
        sb.append('{');
        if (username != null && !username.isEmpty()) {
            sb.append("\"username\":\"").append(escapeJson(username)).append("\",");
        }
        if (avatarUrl != null && !avatarUrl.isEmpty()) {
            sb.append("\"avatar_url\":\"").append(escapeJson(avatarUrl)).append("\",");
        }
        sb.append("\"embeds\":[{");
        sb.append("\"title\":\"").append(escapeJson(title)).append("\",");

        // Description: use the description template, then add the info block if present
        StringBuilder desc = new StringBuilder();
        if (description != null && !description.isEmpty()) {
            desc.append(description);
        }
        if (info != null && !info.isEmpty()) {
            if (desc.length() > 0) desc.append("\n\n");
            desc.append("**Details:**\n```").append(escapeJson(info)).append("```");
        }
        sb.append("\"description\":\"").append(escapeJson(desc.toString())).append("\",");

        sb.append("\"color\":").append(color).append(',');

        // Author = player with head thumbnail
        sb.append("\"author\":{\"name\":\"").append(escapeJson(suspect.getName())).append("\",\"icon_url\":\"")
          .append(escapeJson(thumbnail)).append("\"},");

        // Fields: compact and grouped for readability
        sb.append("\"fields\":[");
        // First row: Check and VL
        sb.append(fieldJson("Check", check, true)).append(',');
        sb.append(fieldJson("VL", String.valueOf(vl), true)).append(',');
        // Second row: World and Ping
        sb.append(fieldJson("World", suspect.getWorld().getName(), true)).append(',');
        sb.append(fieldJson("Ping (ms)", String.valueOf(suspect.getPing()), true)).append(',');
        // Location on its own row (not inline)
        String loc = suspect.getLocation().getBlockX() + ", " + suspect.getLocation().getBlockY() + ", " + suspect.getLocation().getBlockZ();
        sb.append(fieldJson("Location", loc, false));
        sb.append("]");

        // Timestamp and footer
        sb.append(",\"timestamp\":\"").append(java.time.Instant.now().toString()).append('\"');
        sb.append(",\"footer\":{\"text\":\"").append(escapeJson(footerText)).append("\"}");

        sb.append("}]}");
        return sb.toString();
    }

    private static String fieldJson(String name, String value, boolean inline) {
        StringBuilder b = new StringBuilder();
        b.append("{\"name\":\"").append(escapeJson(name)).append("\",\"value\":\"")
         .append(escapeJson(value)).append("\",\"inline\":").append(inline ? "true" : "false").append('}');
        return b.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                default: out.append(c); break;
            }
        }
        return out.toString();
    }

    private static String jsonEncodeContent(String content) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"content\":\"");
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                default: sb.append(c); break;
            }
        }
        sb.append('\"');
        sb.append('}');
        return sb.toString();
    }
}

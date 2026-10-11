package it.virgola.anticheat.checks.impl;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import it.virgola.anticheat.checks.Check;
import it.virgola.anticheat.checks.CheckId;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.ViolationService;

public final class BadPacketsCheck implements Check {

    private final Configs configs;
    private final ViolationService violations;

    private volatile boolean enabled;

    public BadPacketsCheck(Configs configs, ViolationService violations) {
        this.configs = configs;
        this.violations = violations;
        this.enabled = true;
    }

    @Override
    public CheckId id() {
        return CheckId.BADPACKETS;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void reload() {
        // niente di speciale: la configurazione viene letta live dai file
    }

    @Override
    public void onPacketReceive(PlayerData data, PacketReceiveEvent event) {
        if (!enabled) return;
        if (data == null || event == null) return;

        PacketTypeCommon type = event.getPacketType();

        // IMPORTANT: only handle flying-related packets
        if (!WrapperPlayClientPlayerFlying.isFlying(type)) return;

        try {
            WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);

            // DO NOT call w.read() on PacketEvents 2.11.1 (can cause IndexOutOfBounds on some variants)

            if (!w.hasRotationChanged()) return;

            float pitch = w.getLocation().getPitch();

            // Minecraft pitch legit range is [-90, +90]
            // Allow a tiny tolerance to avoid float weirdness.
            double min = configs.checks().getDouble(id().path() + ".params.min_pitch", -90.0);
            double max = configs.checks().getDouble(id().path() + ".params.max_pitch", 90.0);
            double tol = configs.checks().getDouble(id().path() + ".params.tolerance", 0.001);

            if (pitch < (min - tol) || pitch > (max + tol)) {
                violations.flag(data, id().name(), 1,
                        "pitch=" + pitch + " range=[" + min + "," + max + "]");
            }
        } catch (Throwable ignored) {
            // Fail-safe: never break PacketEvents pipeline
        }
    }
}

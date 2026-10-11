package it.virgola.anticheat.packets;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import it.virgola.anticheat.checks.CheckManager;
import it.virgola.anticheat.config.Configs;
import it.virgola.anticheat.core.PlayerData;
import it.virgola.anticheat.core.PlayerDataManager;
import org.bukkit.entity.Player;

public final class PacketIngestor extends PacketListenerAbstract {

    @SuppressWarnings("unused")
    private final Configs configs;

    private final PlayerDataManager dataManager;
    private final CheckManager checkManager;

    public PacketIngestor(Configs configs, PlayerDataManager dataManager, CheckManager checkManager) {
        super(PacketListenerPriority.NORMAL);
        this.configs = configs;
        this.dataManager = dataManager;
        this.checkManager = checkManager;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Object obj = event.getPlayer();
        if (!(obj instanceof Player player)) return;

        PlayerData data = dataManager.get(player);

        PacketTypeCommon type = event.getPacketType();

        // Track movement/rotation only on Flying-related packets.
        // This is crucial: creating/reading the wrapper on non-flying packets causes IndexOutOfBounds.
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            try {
                WrapperPlayClientPlayerFlying w = new WrapperPlayClientPlayerFlying(event);

                // IMPORTANT:
                // In PacketEvents 2.11.x the wrapper reads from the buffer lazily.
                // Calling w.read() manually is not needed and can break if the buffer isn't in the expected state.
                // So: do NOT call w.read().

                if (w.hasRotationChanged()) {
                    // Use location accessors (safe for the flying wrapper).
                    float yaw = w.getLocation().getYaw();
                    float pitch = w.getLocation().getPitch();
                    data.pushRotation(yaw, pitch);
                }

                data.setLastFlyingPacketTimeMs(System.currentTimeMillis());
            } catch (Throwable ignored) {
                // Fail-safe: never allow a packet decode issue to break PacketEvents pipeline.
                // Optional: log only if you have a debug switch.
            }
        }

        // Dispatch to checks (always last)
        checkManager.handlePacket(data, event);
    }
}

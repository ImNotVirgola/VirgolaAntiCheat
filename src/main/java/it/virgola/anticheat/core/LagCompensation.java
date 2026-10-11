package it.virgola.anticheat.core;

import org.bukkit.entity.Player;

public final class LagCompensation {

    private final TickService tickService;
    private final double pingBase;
    private final double pingPer100;
    private final double tpsBase;
    private final double tpsPerBelow20;

    public LagCompensation(TickService tickService,
                           double pingBase,
                           double pingPer100,
                           double tpsBase,
                           double tpsPerBelow20) {
        this.tickService = tickService;
        this.pingBase = pingBase;
        this.pingPer100 = pingPer100;
        this.tpsBase = tpsBase;
        this.tpsPerBelow20 = tpsPerBelow20;
    }

    public double timingMultiplier(Player p) {
        int ping = Math.max(0, p.getPing());
        double pingMul = pingBase + (ping / 100.0) * pingPer100;

        double tps = tickService.getTps();
        double below = Math.max(0.0, 20.0 - tps);
        double tpsMul = tpsBase + below * tpsPerBelow20;

        return Math.max(0.5, Math.min(3.0, pingMul * tpsMul));
    }
    /** Backwards-compatible alias for checks. */
    public double multiplier(Player p) {
        return timingMultiplier(p);
    }

}

package it.virgola.anticheat.core;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayDeque;
import java.util.Deque;

public final class TickService {

    private final Deque<Long> samples = new ArrayDeque<>();
    private volatile double tps = 20.0;

    public void start(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long now = System.nanoTime();
            samples.addLast(now);
            while (samples.size() > 100) samples.pollFirst();
            if (samples.size() >= 2) {
                long first = samples.peekFirst();
                long last = samples.peekLast();
                long dt = last - first;
                if (dt > 0) {
                    double seconds = dt / 1_000_000_000.0;
                    double ticks = samples.size() - 1;
                    double calc = ticks / seconds;
                    tps = Math.min(20.0, Math.max(0.0, calc));
                }
            }
        }, 1L, 1L);
    }

    public double getTps() {
        return tps;
    }
}

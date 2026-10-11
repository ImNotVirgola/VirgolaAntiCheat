package it.virgola.anticheat.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Asynchronous block cache to avoid blocking the packet thread with world.getBlockAt() calls.
 * 
 * This service maintains a short-lived cache of block types and provides async methods
 * to check block conditions without blocking the calling thread.
 */
public final class AsyncBlockCache {

    // Cache key: "worldUUID:x:y:z" -> Material
    private final Map<String, CachedBlock> blockCache = new ConcurrentHashMap<>();
    
    // Cache expiration time in milliseconds
    private static final long CACHE_TTL_MS = 500;
    
    // Thread pool for async block lookups
    private final ExecutorService executor;
    
    // Plugin reference for scheduling sync tasks
    private final Plugin plugin;
    
    public AsyncBlockCache(Plugin plugin) {
        this.plugin = plugin;
        // Use a cached thread pool that can handle bursts but shrinks when idle
        this.executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "VAC-BlockCache");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Get block type from cache or schedule async lookup.
     * Returns cached value if available, otherwise returns null and schedules lookup.
     */
    public Material getCached(World world, int x, int y, int z) {
        String key = cacheKey(world.getUID(), x, y, z);
        CachedBlock cached = blockCache.get(key);
        
        if (cached != null && !cached.isExpired()) {
            return cached.material;
        }
        
        return null;
    }
    
    /**
     * Check block conditions asynchronously.
     * The callback will be invoked on the main thread with the result.
     * 
     * @param world The world
     * @param x Block X coordinate
     * @param y Block Y coordinate
     * @param z Block Z coordinate
     * @param callback Called with the Material when available
     */
    public void getBlockAsync(World world, int x, int y, int z, Consumer<Material> callback) {
        String key = cacheKey(world.getUID(), x, y, z);
        CachedBlock cached = blockCache.get(key);
        
        if (cached != null && !cached.isExpired()) {
            callback.accept(cached.material);
            return;
        }
        
        // Schedule sync task to read block (Bukkit API must be called from main thread)
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                Block block = world.getBlockAt(x, y, z);
                Material mat = block.getType();
                
                // Cache the result
                blockCache.put(key, new CachedBlock(mat, System.currentTimeMillis()));
                
                // Invoke callback
                callback.accept(mat);
            } catch (Exception e) {
                callback.accept(Material.AIR);
            }
        });
    }
    
    /**
     * Check multiple blocks and return result via callback.
     * Useful for checking area around player.
     */
    public void checkBlocksInArea(World world, int centerX, int centerY, int centerZ,
                                   int radiusX, int radiusY, int radiusZ,
                                   Set<Material> targetMaterials,
                                   Consumer<Boolean> callback) {
        
        // First check cache for all positions
        boolean allCached = true;
        for (int dx = -radiusX; dx <= radiusX && allCached; dx++) {
            for (int dy = -radiusY; dy <= radiusY && allCached; dy++) {
                for (int dz = -radiusZ; dz <= radiusZ && allCached; dz++) {
                    Material cached = getCached(world, centerX + dx, centerY + dy, centerZ + dz);
                    if (cached == null) {
                        allCached = false;
                    } else if (targetMaterials.contains(cached)) {
                        callback.accept(true);
                        return;
                    }
                }
            }
        }
        
        if (allCached) {
            callback.accept(false);
            return;
        }
        
        // Need to fetch from world - schedule sync
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                for (int dx = -radiusX; dx <= radiusX; dx++) {
                    for (int dy = -radiusY; dy <= radiusY; dy++) {
                        for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                            int bx = centerX + dx;
                            int by = centerY + dy;
                            int bz = centerZ + dz;
                            
                            Block block = world.getBlockAt(bx, by, bz);
                            Material mat = block.getType();
                            
                            // Cache it
                            String key = cacheKey(world.getUID(), bx, by, bz);
                            blockCache.put(key, new CachedBlock(mat, System.currentTimeMillis()));
                            
                            if (targetMaterials.contains(mat)) {
                                callback.accept(true);
                                return;
                            }
                        }
                    }
                }
                callback.accept(false);
            } catch (Exception e) {
                callback.accept(false);
            }
        });
    }
    
    /**
     * Check if any block in area is liquid (async).
     */
    public void checkLiquidInArea(World world, int centerX, int centerY, int centerZ,
                                   int radiusY, Consumer<Boolean> callback) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                for (int dy = -radiusY; dy <= radiusY; dy++) {
                    Block block = world.getBlockAt(centerX, centerY + dy, centerZ);
                    if (block.isLiquid()) {
                        callback.accept(true);
                        return;
                    }
                }
                callback.accept(false);
            } catch (Exception e) {
                callback.accept(false);
            }
        });
    }
    
    /**
     * Pre-cache blocks around a location (call this periodically or on movement).
     */
    public void preCacheArea(World world, int centerX, int centerY, int centerZ, int radius) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                long now = System.currentTimeMillis();
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -2; dy <= 1; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            int bx = centerX + dx;
                            int by = centerY + dy;
                            int bz = centerZ + dz;
                            
                            String key = cacheKey(world.getUID(), bx, by, bz);
                            CachedBlock cached = blockCache.get(key);
                            
                            if (cached == null || cached.isExpired()) {
                                Block block = world.getBlockAt(bx, by, bz);
                                blockCache.put(key, new CachedBlock(block.getType(), now));
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        });
    }
    
    /**
     * Cleanup expired cache entries.
     */
    public void cleanup() {
        long now = System.currentTimeMillis();
        blockCache.entrySet().removeIf(e -> e.getValue().isExpiredAt(now));
    }
    
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        blockCache.clear();
    }
    
    private String cacheKey(UUID worldId, int x, int y, int z) {
        return worldId.toString() + ":" + x + ":" + y + ":" + z;
    }
    
    private static class CachedBlock {
        final Material material;
        final long timestamp;
        
        CachedBlock(Material material, long timestamp) {
            this.material = material;
            this.timestamp = timestamp;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
        
        boolean isExpiredAt(long now) {
            return now - timestamp > CACHE_TTL_MS;
        }
    }
}

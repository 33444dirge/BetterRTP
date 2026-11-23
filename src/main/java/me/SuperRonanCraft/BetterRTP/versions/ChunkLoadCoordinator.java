package me.SuperRonanCraft.BetterRTP.versions;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class ChunkLoadCoordinator {

    private static final int MAX_CONCURRENT_PLAYER_LOADS = 4;
    private static final int MAX_CONCURRENT_BACKGROUND_LOADS = 1;
    private static final long LOAD_TIMEOUT_TICKS = 30L * 20L;
    private static final Map<ChunkKey, LoadRequest> inFlight = new ConcurrentHashMap<>();
    private static final Queue<LoadRequest> playerQueue = new ConcurrentLinkedQueue<>();
    private static final Queue<LoadRequest> backgroundQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicInteger activePlayerLoads = new AtomicInteger();
    private static final AtomicInteger activeBackgroundLoads = new AtomicInteger();
    private static final AtomicBoolean pumping = new AtomicBoolean();
    private static volatile boolean stopped = true;

    private ChunkLoadCoordinator() {
    }

    static void initialize() {
        stopped = false;
        activePlayerLoads.set(0);
        activeBackgroundLoads.set(0);
        playerQueue.clear();
        backgroundQueue.clear();
        inFlight.clear();
    }

    static CompletableFuture<Chunk> load(Location source, boolean background) {
        if (stopped)
            return cancelledFuture();

        Location location = source.clone();
        World world = location.getWorld();
        if (world == null)
            return CompletableFuture.failedFuture(new IllegalArgumentException("Location has no world"));

        ChunkKey key = new ChunkKey(world, location.getBlockX() >> 4, location.getBlockZ() >> 4);
        LoadRequest request = new LoadRequest(key, location, background);
        LoadRequest existing = inFlight.putIfAbsent(key, request);
        if (existing != null)
            return existing.future.thenApply(chunk -> chunk);

        (background ? backgroundQueue : playerQueue).offer(request);
        pump();
        return request.future.thenApply(chunk -> chunk);
    }

    static void shutdown() {
        stopped = true;
        playerQueue.clear();
        backgroundQueue.clear();
        inFlight.forEach((key, request) -> {
            if (request.finished.compareAndSet(false, true))
                request.future.cancel(false);
        });
        inFlight.clear();
        activePlayerLoads.set(0);
        activeBackgroundLoads.set(0);
    }

    private static void pump() {
        if (stopped || !pumping.compareAndSet(false, true))
            return;

        try {
            while (!stopped) {
                LoadRequest request = null;
                if (activePlayerLoads.get() < MAX_CONCURRENT_PLAYER_LOADS)
                    request = playerQueue.poll();
                if (request == null && activeBackgroundLoads.get() < MAX_CONCURRENT_BACKGROUND_LOADS
                        && activePlayerLoads.get() == 0 && playerQueue.isEmpty())
                    request = backgroundQueue.poll();
                if (request == null)
                    break;
                if (!request.started.compareAndSet(false, true))
                    continue;

                activeCounter(request).incrementAndGet();
                start(request);
            }
        } finally {
            pumping.set(false);
            if (!stopped && canStartQueuedRequest())
                pump();
        }
    }

    private static void start(LoadRequest request) {
        request.timeoutTask = AsyncHandler.asyncLater(
                () -> finish(request, null, new TimeoutException("Chunk load timed out after 30 seconds")),
                LOAD_TIMEOUT_TICKS);
        AsyncHandler.syncAtLocation(request.location, () -> {
            if (stopped) {
                finish(request, null, new IllegalStateException("Plugin is shutting down"));
                return;
            }

            try {
                World world = request.key.world;
                if (world.isChunkLoaded(request.key.x, request.key.z)) {
                    finish(request, world.getChunkAt(request.key.x, request.key.z), null);
                    return;
                }

                world.getChunkAtAsync(request.key.x, request.key.z, true)
                        .whenComplete((chunk, error) -> completeInOwningRegion(request, chunk, error));
            } catch (Throwable error) {
                finish(request, null, error);
            }
        });
    }

    private static void completeInOwningRegion(LoadRequest request, Chunk chunk, Throwable error) {
        if (request.finished.get())
            return;
        if (AsyncHandler.isOwnedByCurrentRegion(request.location)) {
            finish(request, chunk, error);
            return;
        }
        AsyncHandler.syncAtLocation(request.location, () -> finish(request, chunk, error));
    }

    private static void finish(LoadRequest request, Chunk chunk, Throwable error) {
        if (!request.finished.compareAndSet(false, true))
            return;

        inFlight.remove(request.key, request);
        if (request.timeoutTask != null)
            request.timeoutTask.cancel();
        if (!stopped)
            activeCounter(request).updateAndGet(active -> Math.max(0, active - 1));

        if (error != null)
            request.future.completeExceptionally(error);
        else if (chunk == null)
            request.future.completeExceptionally(new IllegalStateException("Chunk load returned no chunk"));
        else
            request.future.complete(chunk);

        pump();
    }

    private static <T> CompletableFuture<T> cancelledFuture() {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.cancel(false);
        return future;
    }

    private static AtomicInteger activeCounter(LoadRequest request) {
        return request.background ? activeBackgroundLoads : activePlayerLoads;
    }

    private static boolean canStartQueuedRequest() {
        return (!playerQueue.isEmpty() && activePlayerLoads.get() < MAX_CONCURRENT_PLAYER_LOADS)
                || (activePlayerLoads.get() == 0 && playerQueue.isEmpty() && !backgroundQueue.isEmpty()
                && activeBackgroundLoads.get() < MAX_CONCURRENT_BACKGROUND_LOADS);
    }

    private record ChunkKey(World world, int x, int z) {
    }

    private static final class LoadRequest {
        private final ChunkKey key;
        private final Location location;
        private final boolean background;
        private final CompletableFuture<Chunk> future = new CompletableFuture<>();
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();
        private volatile TaskHandle timeoutTask;

        private LoadRequest(ChunkKey key, Location location, boolean background) {
            this.key = key;
            this.location = location;
            this.background = background;
        }
    }
}

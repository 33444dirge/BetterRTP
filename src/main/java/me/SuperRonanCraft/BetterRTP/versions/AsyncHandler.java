package me.SuperRonanCraft.BetterRTP.versions;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.SuperRonanCraft.BetterRTP.BetterRTP;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.command.CommandSender;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AsyncHandler {

    private static boolean folia;
    private static boolean initialized;
    private static volatile boolean shuttingDown;
    private static final Set<TaskHandle> trackedTasks = ConcurrentHashMap.newKeySet();

    private AsyncHandler() {
    }

    public static void initialize() {
        if (initialized) return;
        folia = detectFolia();
        initialized = true;
        shuttingDown = false;
        ChunkLoadCoordinator.initialize();
    }

    public static boolean isFolia() {
        initialize();
        return folia;
    }

    public static void async(Runnable runnable) {
        if (shuttingDown) return;
        if (isFolia())
            Bukkit.getAsyncScheduler().runNow(plugin(), task -> runnable.run());
        else
            Bukkit.getScheduler().runTaskAsynchronously(plugin(), runnable);
    }

    public static void sync(Runnable runnable) {
        if (shuttingDown) return;
        if (isFolia())
            Bukkit.getGlobalRegionScheduler().run(plugin(), task -> runnable.run());
        else
            Bukkit.getScheduler().runTask(plugin(), runnable);
    }

    public static void syncAtEntity(Entity entity, Runnable runnable) {
        if (shuttingDown) return;
        if (isOwnedByCurrentRegion(entity)) {
            runnable.run();
            return;
        }
        if (isFolia())
            entity.getScheduler().run(plugin(), task -> runnable.run(), null);
        else
            Bukkit.getScheduler().runTask(plugin(), runnable);
    }

    public static void syncAtSender(CommandSender sender, Runnable runnable) {
        if (sender instanceof Entity)
            syncAtEntity((Entity) sender, runnable);
        else
            sync(runnable);
    }

    public static TaskHandle syncAtEntityLater(Entity entity, Runnable runnable, long ticks) {
        if (shuttingDown) return cancelledHandle();
        long delay = Math.max(1L, ticks);
        TrackedTask tracked = new TrackedTask();
        if (isFolia()) {
            trackedTasks.add(tracked);
            ScheduledTask task = entity.getScheduler().runDelayed(
                    plugin(), scheduled -> tracked.run(runnable), tracked::complete, delay);
            if (task == null) {
                tracked.complete();
                return cancelledHandle();
            }
            tracked.setDelegate(task::cancel);
            return tracked;
        }
        trackedTasks.add(tracked);
        tracked.setDelegate(wrap(Bukkit.getScheduler().runTaskLater(plugin(), () -> tracked.run(runnable), delay)));
        return tracked;
    }

    public static void syncAtLocation(Location location, Runnable runnable) {
        if (shuttingDown) return;
        if (isOwnedByCurrentRegion(location)) {
            runnable.run();
            return;
        }
        if (isFolia())
            Bukkit.getRegionScheduler().run(plugin(), location, task -> runnable.run());
        else
            Bukkit.getScheduler().runTask(plugin(), runnable);
    }

    public static CompletableFuture<Chunk> loadChunk(Location location) {
        return ChunkLoadCoordinator.load(location, false);
    }

    public static CompletableFuture<Chunk> loadChunkBackground(Location location) {
        return ChunkLoadCoordinator.load(location, true);
    }

    public static boolean isOwnedByCurrentRegion(Location location) {
        return isFolia() ? Bukkit.isOwnedByCurrentRegion(location) : Bukkit.isPrimaryThread();
    }

    public static boolean isOwnedByCurrentRegion(Entity entity) {
        return isFolia() ? Bukkit.isOwnedByCurrentRegion(entity) : Bukkit.isPrimaryThread();
    }

    public static CompletableFuture<Boolean> teleportAsync(Entity entity, Location location) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (shuttingDown) {
            result.cancel(false);
            return result;
        }

        Location destination = location.clone();
        Runnable teleport = () -> {
            if (shuttingDown || !entity.isValid()) {
                result.complete(false);
                return;
            }
            try {
                entity.teleportAsync(destination).whenComplete((success, error) -> {
                    if (error != null)
                        result.completeExceptionally(error);
                    else
                        result.complete(Boolean.TRUE.equals(success));
                });
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        };

        if (isFolia()) {
            ScheduledTask task = entity.getScheduler().run(
                    plugin(), scheduled -> teleport.run(), () -> result.complete(false));
            if (task == null)
                result.complete(false);
        } else if (Bukkit.isPrimaryThread()) {
            teleport.run();
        } else {
            Bukkit.getScheduler().runTask(plugin(), teleport);
        }
        return result;
    }

    public static TaskHandle asyncLater(Runnable runnable, long ticks) {
        if (shuttingDown) return cancelledHandle();
        long delay = Math.max(1L, ticks);
        TrackedTask tracked = new TrackedTask();
        trackedTasks.add(tracked);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getAsyncScheduler().runDelayed(
                    plugin(), scheduled -> tracked.run(runnable), ticksToMillis(delay), TimeUnit.MILLISECONDS);
            tracked.setDelegate(task::cancel);
            return tracked;
        }
        tracked.setDelegate(wrap(Bukkit.getScheduler().runTaskLaterAsynchronously(
                plugin(), () -> tracked.run(runnable), delay)));
        return tracked;
    }

    public static TaskHandle syncLater(Runnable runnable, long ticks) {
        if (shuttingDown) return cancelledHandle();
        long delay = Math.max(1L, ticks);
        TrackedTask tracked = new TrackedTask();
        trackedTasks.add(tracked);
        if (isFolia()) {
            ScheduledTask task = Bukkit.getGlobalRegionScheduler().runDelayed(
                    plugin(), scheduled -> tracked.run(runnable), delay);
            tracked.setDelegate(task::cancel);
            return tracked;
        }
        tracked.setDelegate(wrap(Bukkit.getScheduler().runTaskLater(plugin(), () -> tracked.run(runnable), delay)));
        return tracked;
    }

    public static void cancelAll() {
        if (!initialized) return;
        shuttingDown = true;
        ChunkLoadCoordinator.shutdown();
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin());
            Bukkit.getAsyncScheduler().cancelTasks(plugin());
        } else {
            Bukkit.getScheduler().cancelTasks(plugin());
        }
        trackedTasks.forEach(TaskHandle::cancel);
        trackedTasks.clear();
    }

    private static TaskHandle wrap(BukkitTask task) {
        return task::cancel;
    }

    private static TaskHandle cancelledHandle() {
        return () -> { };
    }

    private static long ticksToMillis(long ticks) {
        return Math.multiplyExact(ticks, 50L);
    }

    private static BetterRTP plugin() {
        return BetterRTP.getInstance();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static final class TrackedTask implements TaskHandle {
        private volatile TaskHandle delegate = cancelledHandle();

        private void setDelegate(TaskHandle delegate) {
            this.delegate = delegate;
        }

        private void run(Runnable runnable) {
            try {
                runnable.run();
            } finally {
                complete();
            }
        }

        private void complete() {
            trackedTasks.remove(this);
        }

        @Override
        public void cancel() {
            delegate.cancel();
            complete();
        }
    }
}

package me.SuperRonanCraft.BetterRTP.references.rtpinfo;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.Location;
import org.bukkit.ChunkSnapshot;

import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.references.database.DatabaseHandler;
import me.SuperRonanCraft.BetterRTP.references.helpers.HelperRTP;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.RTPWorld;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.WORLD_TYPE;
import me.SuperRonanCraft.BetterRTP.versions.AsyncHandler;
import me.SuperRonanCraft.BetterRTP.versions.TaskHandle;

public class QueueGenerator {


    public static final int queueMax = 32, queueMin = 2; //Amount to ready up for each rtp world
    private final int queueMaxAttempts = 50;
    private final AtomicBoolean generating = new AtomicBoolean();
    private final AtomicLong lifecycle = new AtomicLong();
    private volatile TaskHandle task;

    public void unload() {
        lifecycle.incrementAndGet();
        if (task != null)
            task.cancel();
        task = null;
        generating.set(false);
    }

    public void load() {
        unload();
    }

    void generate(RTPWorld rtpWorld) {
        if (!QueueHandler.isEnabled() || rtpWorld == null || !generating.compareAndSet(false, true))
            return;
        long generation = lifecycle.get();
        waitForDatabase(rtpWorld, generation);
    }

    boolean isGenerating() {
        return generating.get();
    }

    private void waitForDatabase(RTPWorld rtpWorld, long generation) {
        task = AsyncHandler.asyncLater(() -> {
            if (!isCurrent(generation))
                return;
            if (!DatabaseHandler.getQueue().isLoaded()) {
                waitForDatabase(rtpWorld, generation);
                return;
            }
            BetterRTP.debug("Attempting to queue safe locations for " + rtpWorld.getWorld().getName() + "...");
            queueGenerator(new ReQueueData(rtpWorld, 0, generation));
        }, 10L);
    }

    private void queueGenerator(ReQueueData data) {
        task = AsyncHandler.asyncLater(() -> {
            if (!isCurrent(data.generation))
                return;

            List<QueueData> applicable = QueueHandler.getApplicableAsync(data.rtpWorld);
            String type = "rtp_" + (data.rtpWorld.getID() != null
                    ? data.rtpWorld.getID() : data.rtpWorld.getWorld().getName());
            if (applicable.size() >= queueMin || applicable.size() >= queueMax) {
                finish(data.generation);
                BetterRTP.debug("Queue ready for " + type + " (amount: " + applicable.size() + ")");
                return;
            }
            if (data.attempts >= queueMaxAttempts) {
                finish(data.generation);
                BetterRTP.debug("Max attempts to create a Queue reached for " + type
                        + " (amount: " + applicable.size() + ")");
                return;
            }

            addQueue(data.rtpWorld, type, new ReQueueData(data.rtpWorld, data.attempts + 1, data.generation));
        }, 20L);
    }

    static class ReQueueData {

        RTPWorld rtpWorld;
        int attempts;
        long generation;
        ReQueueData(RTPWorld rtpWorld, int attempts, long generation) {
            this.rtpWorld = rtpWorld;
            this.attempts = attempts;
            this.generation = generation;
        }
    }

    private void addQueue(RTPWorld rtpWorld, String id, ReQueueData reQueueData) {
        Location loc = RandomLocation.generateLocation(rtpWorld);
        if (loc != null) {
            AsyncHandler.loadChunkBackground(loc).whenComplete((chunk, error) -> {
                if (!isCurrent(reQueueData.generation))
                    return;
                if (error != null) {
                    BetterRTP.getInstance().getLogger().warning("Unable to load queued RTP chunk at " + loc + ": " + error.getMessage());
                    queueGenerator(reQueueData);
                    return;
                }
                //BetterRTP.debug("Queued up a new position, attempts " + reQueueData.attempts);
                try {
                    ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
                    WORLD_TYPE worldType = HelperRTP.getWorldType(rtpWorld.getWorld());
                    org.bukkit.World world = loc.getWorld();
                    AsyncHandler.async(() -> {
                        try {
                            Location safeLoc = RandomLocation.getSafeLocation(
                                    worldType, world, snapshot, loc,
                                    rtpWorld.getMinY(), rtpWorld.getMaxY(), rtpWorld.getBiomes());
                            if (safeLoc != null) {
                                if (!isCurrent(reQueueData.generation))
                                    return;
                                QueueData data = DatabaseHandler.getQueue().addQueue(safeLoc);
                                if (data != null) {
                                    String _x = String.valueOf(data.getLocation().getBlockX());
                                    String _y = String.valueOf(data.getLocation().getBlockY());
                                    String _z = String.valueOf(data.getLocation().getBlockZ());
                                    String _world = data.getLocation().getWorld().getName();
                                    BetterRTP.debug("Queue position generated"
                                            + ": id= " + id + ", database_ID= " + data.database_id
                                            + ", location= x: " + _x + ", y: " + _y + ", z: " + _z + ", world: " + _world);
                                } else
                                    BetterRTP.debug("Database error occurred for a queue when trying to save: " + safeLoc);
                            }
                            queueGenerator(reQueueData);
                        } catch (Throwable scanError) {
                            BetterRTP.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                                    "Unable to scan queued RTP chunk at " + loc, scanError);
                            queueGenerator(reQueueData);
                        }
                    });
                } catch (Throwable snapshotError) {
                    BetterRTP.getInstance().getLogger().log(java.util.logging.Level.WARNING,
                            "Unable to snapshot queued RTP chunk at " + loc, snapshotError);
                    queueGenerator(reQueueData);
                }
            });
        } else {
            BetterRTP.debug("Queue position wasn't able to generate a location!");
            queueGenerator(reQueueData);
        }
    }

    private boolean isCurrent(long generation) {
        return QueueHandler.isEnabled() && lifecycle.get() == generation && generating.get();
    }

    private void finish(long generation) {
        if (lifecycle.get() == generation)
            generating.set(false);
    }
}

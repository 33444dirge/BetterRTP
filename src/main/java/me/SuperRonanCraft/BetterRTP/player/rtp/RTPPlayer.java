package me.SuperRonanCraft.BetterRTP.player.rtp;

import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_FailedEvent;
import me.SuperRonanCraft.BetterRTP.references.customEvents.RTP_FindLocationEvent;
import me.SuperRonanCraft.BetterRTP.references.helpers.HelperRTP_Check;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.QueueData;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.QueueHandler;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.RandomLocation;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.WORLD_TYPE;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.WorldPlayer;
import me.SuperRonanCraft.BetterRTP.versions.AsyncHandler;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.Getter;

public class RTPPlayer {

    @Getter private final Player player;
    private final RTP settings;
    @Getter WorldPlayer worldPlayer;
    @Getter RTP_TYPE type;
    private final World targetWorld;
    private final WORLD_TYPE worldType;
    private final int minY;
    private final int maxY;
    private final List<String> biomes;
    private final AtomicInteger attempts = new AtomicInteger();
    private final Set<Long> attemptedChunks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Map<String, AtomicInteger> failureReasons = new ConcurrentHashMap<>();
    private final AtomicReference<String> lastFailure = new AtomicReference<>("none");

    RTPPlayer(Player player, RTP settings, WorldPlayer worldPlayer, RTP_TYPE type) {
        this.player = player;
        this.settings = settings;
        this.worldPlayer = worldPlayer;
        this.type = type;
        this.targetWorld = worldPlayer.getWorld();
        this.worldType = worldPlayer.getWorldtype() == null ? WORLD_TYPE.NORMAL : worldPlayer.getWorldtype();
        this.minY = worldPlayer.getMinY();
        this.maxY = worldPlayer.getMaxY();
        this.biomes = worldPlayer.getBiomes() == null ? List.of() : List.copyOf(worldPlayer.getBiomes());
    }

    public int getAttempts() {
        return attempts.get();
    }

    void randomlyTeleport(CommandSender sendi) {
        if (finished.get())
            return;
        AsyncHandler.syncAtEntity(player, () -> randomlyTeleportOwned(sendi));
    }

    private void randomlyTeleportOwned(CommandSender sendi) {
        try {
            if (finished.get())
                return;
            if (!player.isOnline()) {
                finish();
                return;
            }
            if (attempts.get() >= settings.maxAttempts) {
                metMax(sendi);
                return;
            }

            RTP_FindLocationEvent event = new RTP_FindLocationEvent(this);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                attempts.incrementAndGet();
                recordFailure("EVENT_CANCELLED", null);
                retry(sendi);
                return;
            }

            Location eventLocation = event.getLocation();
            if (eventLocation != null)
                eventLocation = eventLocation.clone();
            final Location requestedLocation = eventLocation;
            AsyncHandler.async(() -> findCandidate(sendi, requestedLocation));
        } catch (Throwable error) {
            recordFailure("START_ERROR", error);
            retry(sendi);
        }
    }

    private void findCandidate(CommandSender sendi, Location requestedLocation) {
        if (finished.get())
            return;
        Location candidate = null;
        try {
            if (attempts.get() >= settings.maxAttempts) {
                returnToPlayer(sendi);
                return;
            }

            Location location = requestedLocation;
            if (location == null) {
                QueueData queueData = QueueHandler.getRandomAsync(worldPlayer);
                location = queueData == null ? RandomLocation.generateLocation(worldPlayer) : queueData.getLocation();
            }
            if (location == null) {
                rejectCandidate(sendi, null, "NO_COORDINATE", null);
                return;
            }

            candidate = location.clone();
            if (candidate.getWorld() == null || !targetWorld.getUID().equals(candidate.getWorld().getUID())) {
                rejectCandidate(sendi, candidate, "WRONG_WORLD", null);
                return;
            }
            attempts.incrementAndGet();
            if (!attemptedChunks.add(chunkKey(candidate))) {
                rejectCandidate(sendi, candidate, "DUPLICATE_CHUNK", null);
                return;
            }

            final Location selected = candidate;
            AsyncHandler.loadChunk(selected).whenComplete((chunk, error) -> {
                if (finished.get())
                    return;
                if (error != null || chunk == null) {
                    rejectCandidate(sendi, selected, "LOAD_ERROR", error);
                    return;
                }

                try {
                    AsyncHandler.syncAtLocation(selected, () -> {
                        if (finished.get())
                            return;
                        try {
                            ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
                            AsyncHandler.async(() -> evaluateSnapshot(sendi, selected, snapshot));
                        } catch (Throwable snapshotError) {
                            rejectCandidate(sendi, selected, "SNAPSHOT_ERROR", snapshotError);
                        }
                    });
                } catch (Throwable scheduleError) {
                    rejectCandidate(sendi, selected, "REGION_SCHEDULE_ERROR", scheduleError);
                }
            });
        } catch (Throwable error) {
            rejectCandidate(sendi, candidate, "CANDIDATE_ERROR", error);
        }
    }

    private void evaluateSnapshot(CommandSender sendi, Location candidate, ChunkSnapshot snapshot) {
        if (finished.get())
            return;
        try {
            Location safe = RandomLocation.getSafeLocation(
                    worldType, targetWorld, snapshot, candidate,
                    minY, maxY, biomes);
            try {
                AsyncHandler.syncAtLocation(candidate, () -> {
                    if (finished.get())
                        return;
                    try {
                        Location resolvedSafe = safe;
                        if (resolvedSafe == null) {
                            // Some production generators expose an incomplete snapshot
                            // heightmap. Re-check this already-loaded chunk synchronously
                            // on its owning Region before discarding the candidate.
                            resolvedSafe = RandomLocation.getSafeLocation(
                                    worldType, targetWorld, candidate, minY, maxY, biomes);
                        }
                        if (resolvedSafe == null) {
                            rejectCandidate(sendi, candidate, "NO_SAFE_BLOCK", null);
                            return;
                        }
                        if (!isWithinBounds(resolvedSafe)) {
                            rejectCandidate(sendi, candidate, "OUT_OF_RANGE", null);
                            return;
                        }
                        if (!checkDepends(resolvedSafe)) {
                            rejectCandidate(sendi, candidate, "CLAIMED", null);
                            return;
                        }
                        resolvedSafe.add(0.5, 0, 0.5);
                        Location finalSafe = resolvedSafe;
                        AsyncHandler.syncAtEntity(player, () -> completeAttempt(sendi, finalSafe));
                    } catch (Throwable validationError) {
                        rejectCandidate(sendi, candidate, "VALIDATION_ERROR", validationError);
                    }
                });
            } catch (Throwable scheduleError) {
                rejectCandidate(sendi, candidate, "REGION_SCHEDULE_ERROR", scheduleError);
            }
        } catch (Throwable snapshotError) {
            rejectCandidate(sendi, candidate, "SNAPSHOT_SCAN_ERROR", snapshotError);
        }
    }

    private void rejectCandidate(CommandSender sendi, Location candidate, String reason, Throwable error) {
        if (finished.get())
            return;
        recordFailure(reason, error);
        if (candidate != null)
            QueueHandler.remove(candidate);
        if (error != null)
            getPl().getLogger().log(java.util.logging.Level.WARNING,
                    "RTP candidate failed for " + player.getName() + " at " + candidate
                            + " (attempt " + attempts.get() + ", reason=" + reason + ")", error);
        else
            BetterRTP.debug("RTP candidate rejected for " + player.getName() + " (attempt "
                    + attempts.get() + ", reason=" + reason + ")");
        returnToPlayer(sendi);
    }

    private void recordFailure(String reason, Throwable error) {
        failureReasons.computeIfAbsent(reason, ignored -> new AtomicInteger()).incrementAndGet();
        lastFailure.set(reason);
        if (error != null)
            BetterRTP.debug("RTP " + reason + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private void returnToPlayer(CommandSender sendi) {
        if (finished.get())
            return;
        AsyncHandler.syncAtEntityLater(player, () -> randomlyTeleportOwned(sendi), 1L);
    }

    private void retry(CommandSender sendi) {
        if (attempts.get() >= settings.maxAttempts)
            metMax(sendi);
        else
            returnToPlayer(sendi);
    }

    private void completeAttempt(CommandSender sendi, Location tpLoc) {
        if (!finished.compareAndSet(false, true))
            return;
        try {
            if (!player.isOnline()) {
                getPl().getPInfo().getRtping().remove(player);
                return;
            }
            if (getPl().getEco().charge(player, worldPlayer)) {
                if (worldPlayer.getPlayerInfo().isApplyCooldown() && HelperRTP_Check.applyCooldown(player))
                    getPl().getCooldowns().add(player, worldPlayer.getWorld());
                Location oldLocation = player.getLocation();
                tpLoc.setYaw(oldLocation.getYaw());
                tpLoc.setPitch(oldLocation.getPitch());
                settings.teleport.sendPlayer(sendi, player, tpLoc, worldPlayer, attempts.get(), type);
            } else {
                if (worldPlayer.getPlayerInfo().applyCooldown)
                    getPl().getCooldowns().removeCooldown(player, worldPlayer.getWorld());
                getPl().getPInfo().getRtping().remove(player);
            }
        } catch (Throwable error) {
            getPl().getLogger().log(java.util.logging.Level.SEVERE,
                    "RTP completion failed for " + player.getName(), error);
            getPl().getPInfo().getRtping().remove(player);
        }
    }

    private void metMax(CommandSender sendi) {
        if (!finished.compareAndSet(false, true))
            return;
        settings.teleport.failedTeleport(player, sendi);
        getPl().getCooldowns().removeCooldown(player, worldPlayer.getWorld());
        getPl().getPInfo().getRtping().remove(player);
        Bukkit.getPluginManager().callEvent(new RTP_FailedEvent(this));
        getPl().getLogger().warning("RTP failed for " + player.getName() + " after " + attempts.get()
                + " attempts; last reason=" + lastFailure.get() + ", reasons=" + failureReasons);
    }

    private void finish() {
        finished.set(true);
        getPl().getPInfo().getRtping().remove(player);
    }

    private static long chunkKey(Location location) {
        long x = location.getBlockX() >> 4;
        long z = location.getBlockZ() >> 4;
        return (x << 32) ^ (z & 0xffffffffL);
    }

    public static boolean checkDepends(Location loc) {
        return RTPPluginValidation.checkLocation(loc);
    }

    private boolean isWithinBounds(Location location) {
        if (worldPlayer.getShape() == RTP_SHAPE.CIRCLE)
            return QueueHandler.isInCircle(location, worldPlayer);
        return QueueHandler.isInSquare(location, worldPlayer);
    }

    private BetterRTP getPl() {
        return BetterRTP.getInstance();
    }
}

package me.SuperRonanCraft.BetterRTP.player.events;

import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.versions.AsyncHandler;
import me.SuperRonanCraft.BetterRTP.versions.TaskHandle;
import org.bukkit.event.world.WorldLoadEvent;

public class WorldLoad {

    TaskHandle loader;

    void load(WorldLoadEvent e) {
        //BetterRTP.getInstance().getLogger().info("NEW WORLD!");
        if (loader != null)
            loader.cancel();
        loader = AsyncHandler.syncLater(() -> {
            BetterRTP.debug("New world `" + e.getWorld().getName() + "` detected! Reloaded Databases!");
            BetterRTP.getInstance().getDatabaseHandler().load();
        }, 20L * 5);
    }
}

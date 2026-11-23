package me.SuperRonanCraft.BetterRTP.references.rtpinfo;

import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.RTPWorld;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.worlds.WORLD_TYPE;
import me.SuperRonanCraft.BetterRTP.versions.AsyncHandler;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.ChunkSnapshot;
import org.bukkit.block.data.Waterlogged;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class RandomLocation {

    public static Location generateLocation(RTPWorld rtpWorld) {
        Location loc;
        switch (rtpWorld.getShape()) {
            case CIRCLE: loc = generateRound(rtpWorld); break;
            case SQUARE:
            default: loc = generateSquare(rtpWorld); break;
        }
        return loc;
    }

    private static Location generateSquare(RTPWorld rtpWorld) {
        //Generate a random X and Z based off the quadrant selected
        int min = rtpWorld.getMinRadius();
        int max = rtpWorld.getMaxRadius() - min;
        int x, z;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int quadrant = random.nextInt(4);
        try {
            switch (quadrant) {
                case 0: // Positive X and Z
                    x = random.nextInt(max) + min;
                    z = random.nextInt(max) + min;
                    break;
                case 1: // Negative X and Z
                    x = -random.nextInt(max) - min;
                    z = -(random.nextInt(max) + min);
                    break;
                case 2: // Negative X and Positive Z
                    x = -random.nextInt(max) - min;
                    z = random.nextInt(max) + min;
                    break;
                default: // Positive X and Negative Z
                    x = random.nextInt(max) + min;
                    z = -(random.nextInt(max) + min);
                    break;
            }
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            BetterRTP.getInstance().getLogger().warning("A bounding location was negative! Please check your config only has positive x/z for max/min radius!");
            BetterRTP.getInstance().getLogger().warning("Max: " + rtpWorld.getMaxRadius() + " Min: " + rtpWorld.getMinRadius());
            return null;
        }
        x += rtpWorld.getCenterX();
        z += rtpWorld.getCenterZ();
        //System.out.println(quadrant);
        return new Location(rtpWorld.getWorld(), x, 69, z);
    }

    private static Location generateRound(RTPWorld rtpWorld) {
        // Generate an area-uniform point inside the configured ring.
        int min = rtpWorld.getMinRadius();
        int max = rtpWorld.getMaxRadius();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double radiusSquared = random.nextDouble((double) min * min, (double) max * max);
        double radius = Math.sqrt(radiusSquared);
        double theta = random.nextDouble(Math.PI * 2);

        // polar to cartesian
        int x = (int) (radius * Math.cos(theta));
        int z = (int) (radius * Math.sin(theta));
        x += rtpWorld.getCenterX();
        z += rtpWorld.getCenterZ();
        return new Location(rtpWorld.getWorld(), x, 69, z);
    }

    public static Location getSafeLocation(WORLD_TYPE type, World world, Location loc, int minY, int maxY, List<String> biomes) {
        switch (type) { //Get a Y position and check for bad blocks
            case NETHER: return getLocAtNether(loc.getBlockX(), loc.getBlockZ(), minY, maxY, world, biomes);
            case NORMAL:
            default: return getLocAtNormal(loc.getBlockX(), loc.getBlockZ(), minY, maxY, world, biomes);
        }
    }

    public static Location getSafeLocation(WORLD_TYPE type, World world, ChunkSnapshot snapshot,
                                           Location loc, int minY, int maxY, List<String> biomes) {
        // A generated chunk can contain ocean, leaves, or an unusable heightmap column.
        // Try a small deterministic set of columns before asking the coordinator to load
        // another chunk. This keeps chunk I/O bounded while avoiding false failures.
        int baseX = loc.getBlockX() & 15;
        int baseZ = loc.getBlockZ() & 15;
        int[][] offsets = {
                {0, 0}, {8, 8}, {8, 0}, {0, 8}, {4, 4}, {12, 4}, {4, 12}, {12, 12}
        };
        for (int[] offset : offsets) {
            int localX = (baseX + offset[0]) & 15;
            int localZ = (baseZ + offset[1]) & 15;
            int worldX = (loc.getBlockX() & ~15) + localX;
            int worldZ = (loc.getBlockZ() & ~15) + localZ;
            int biomeY = Math.max(minY, Math.min(maxY - 1,
                    snapshot.getHighestBlockYAt(localX, localZ)));
            String biome = snapshot.getBiome(localX, biomeY, localZ).getKey().toString();
            Location result;
            switch (type) {
                case NETHER:
                    result = getLocAtNether(snapshot, localX, localZ, worldX, worldZ,
                            minY, maxY, world, biomes);
                    break;
                case NORMAL:
                default:
                    result = getLocAtNormal(snapshot, localX, localZ, worldX, worldZ,
                            minY, maxY, world, biome, biomes);
                    break;
            }
            if (result != null)
                return result;
        }
        return null;
    }

    private static Location getLocAtNormal(ChunkSnapshot snapshot, int localX, int localZ,
                                           int worldX, int worldZ, int minY, int maxY,
                                           World world, String biome, List<String> biomes) {
        int highest = snapshot.getHighestBlockYAt(localX, localZ);
        int start = Math.min(maxY, highest);
        for (int y = start; y >= minY; y--) {
            String ground = snapshot.getBlockType(localX, y, localZ).name();
            // Never tunnel through a water/lava column to find a cave below it.
            // A fluid at or above the candidate surface rejects this column.
            if (isFluid(snapshot, localX, y, localZ))
                return null;
            if (!isSolid(ground) || badBlock(ground, biome, biomes))
                continue;

            // Require two usable blocks above the ground. This rejects water columns,
            // leaves, and terrain where the heightmap points at a non-solid block.
            try {
                if (isFluid(snapshot, localX, y + 1, localZ)
                        || isFluid(snapshot, localX, y + 2, localZ))
                    return null;
                if (isUsableSpace(snapshot, localX, y + 1, localZ, biome, biomes)
                        && isUsableSpace(snapshot, localX, y + 2, localZ, biome, biomes))
                    return new Location(world, worldX, y + 1, worldZ);
            } catch (RuntimeException ignored) {
                // Heightmap can point at the top edge of a custom dimension.
            }
        }
        return null;
    }

    private static Location getLocAtNether(ChunkSnapshot snapshot, int localX, int localZ,
                                           int worldX, int worldZ, int minY, int maxY,
                                           World world, List<String> biomes) {
        int upperY = Math.min(maxY - 1, snapshot.getHighestBlockYAt(localX, localZ) + 8);
        for (int y = minY + 1; y <= upperY; y++) {
            String biomeAtY = snapshot.getBiome(localX, y, localZ).getKey().toString();
            if (isUsableSpace(snapshot, localX, y, localZ, biomeAtY, null)) {
                String below = snapshot.getBlockType(localX, y - 1, localZ).name();
                if (!isAir(below) && !isLiquidLike(below)
                        && !isWaterlogged(snapshot, localX, y - 1, localZ)
                        && isUsableSpace(snapshot, localX, y + 1, localZ, biomeAtY, null)
                        && !badBlock(below, biomeAtY, biomes))
                    return new Location(world, worldX, y, worldZ);
            }
        }
        return null;
    }

    private static boolean isUsableSpace(ChunkSnapshot snapshot, int x, int y, int z,
                                         String biome, List<String> biomes) {
        String block = snapshot.getBlockType(x, y, z).name();
        // `air` is commonly present in the ground blacklist, but it must remain
        // valid head/feet space. The blacklist applies to the block we stand on.
        return !isLiquidLike(block) && !isWaterlogged(snapshot, x, y, z)
                && (isAir(block) || !isSolid(block))
                && (isAir(block) || !badBlock(block, biome, biomes));
    }

    private static boolean isWaterlogged(ChunkSnapshot snapshot, int x, int y, int z) {
        return snapshot.getBlockData(x, y, z) instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    private static boolean isFluid(ChunkSnapshot snapshot, int x, int y, int z) {
        return isLiquidLike(snapshot.getBlockType(x, y, z).name())
                || isWaterlogged(snapshot, x, y, z);
    }

    private static boolean isLiquidLike(String block) {
        String normalized = block.toUpperCase();
        return normalized.contains("WATER")
                || normalized.contains("LAVA")
                || normalized.contains("POWDER_SNOW")
                || normalized.contains("SEAGRASS")
                || normalized.contains("KELP")
                || normalized.contains("BUBBLE_COLUMN");
    }

    private static boolean isAir(String block) {
        return block.endsWith("AIR");
    }

    private static boolean isSolid(String block) {
        try {
            return org.bukkit.Material.valueOf(block).isSolid();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }
    private static Location getLocAtNormal(int x, int z, int minY, int maxY, World world, List<String> biomes) {
        Block b = getHighestBlock(x, z, world);
        if (!b.getType().isSolid()) { //Water, lava, shrubs...
            if (!badBlock(b.getType().name(), x, z, world, null)) { //Make sure it's not an invalid block (ex: water, lava...)
                //int y = world.getHighestBlockYAt(x, z);
                b = world.getBlockAt(x, b.getY() - 1, z);
            }
        }
        //Between max and min y
        if (b.getY() >= minY
                && b.getY() <= maxY
                && !isLiquidLike(b.getType().name())
                && (!(b.getBlockData() instanceof Waterlogged waterlogged) || !waterlogged.isWaterlogged())
                && !badBlock(b.getType().name(), x, z, world, biomes)) {
            Block feet = world.getBlockAt(x, b.getY() + 1, z);
            Block head = world.getBlockAt(x, b.getY() + 2, z);
            if (isUsableSpaceLive(feet, x, z, world) && isUsableSpaceLive(head, x, z, world))
                return new Location(world, x, b.getY() + 1, z);
        }
        return null;
    }

    public static Block getHighestBlock(int x, int z, World world) {
        Block b = world.getHighestBlockAt(x, z);
        if (b.getType().toString().endsWith("AIR")) //1.15.1 or less
            b = world.getBlockAt(x, b.getY() - 1, z);
        return b;
    }

    private static Location getLocAtNether(int x, int z, int minY, int maxY, World world, List<String> biomes) {
        //Max and Min Y
        for (int y = minY + 1; y < maxY/*world.getMaxHeight()*/; y++) {
            Block feet = world.getBlockAt(x, y, z);
            if (!isUsableSpaceLive(feet, x, z, world))
                continue;
            Block ground = world.getBlockAt(x, y - 1, z);
            Block head = world.getBlockAt(x, y + 1, z);
            String groundType = ground.getType().name();
            if (ground.getType().isSolid()
                    && !isLiquidLike(groundType)
                    && (!(ground.getBlockData() instanceof Waterlogged waterlogged) || !waterlogged.isWaterlogged())
                    && !badBlock(groundType, x, z, world, biomes)
                    && isUsableSpaceLive(head, x, z, world))
                return new Location(world, x, y, z);
        }
        return null;
    }

    private static boolean isUsableSpaceLive(Block block, int x, int z, World world) {
        String type = block.getType().name();
        return !isLiquidLike(type)
                && (!(block.getBlockData() instanceof Waterlogged waterlogged) || !waterlogged.isWaterlogged())
                && (type.endsWith("AIR") || !block.getType().isSolid())
                && (type.endsWith("AIR") || !badBlock(type, x, z, world, null));
    }

    // Bad blocks, or bad biome
    public static boolean badBlock(String block, int x, int z, World world, List<String> biomes) {
        for (String currentBlock : BetterRTP.getInstance().getRTP().getBlockList()) //Check Block
            if (currentBlock.equalsIgnoreCase(block))
                return true;
        //Check Biomes
        if (biomes == null || biomes.isEmpty())
            return false;
        String biomeCurrent = world.getBiome(x, z).getKey().toString();
        return badBiome(biomeCurrent, biomes);
    }

    private static boolean badBlock(String block, String biomeCurrent, List<String> biomes) {
        for (String currentBlock : BetterRTP.getInstance().getRTP().getBlockList())
            if (currentBlock.equalsIgnoreCase(block))
                return true;
        if (biomes == null || biomes.isEmpty())
            return false;
        return badBiome(biomeCurrent, biomes);
    }

    private static boolean badBiome(String biomeCurrent, List<String> biomes) {
        if (biomeCurrent == null || biomes == null || biomes.isEmpty())
            return false;

        String current = normalizeBiome(biomeCurrent);
        boolean hasAllowList = false;
        boolean explicitlyAllowed = false;
        for (String entry : biomes) {
            if (entry == null)
                continue;
            String value = entry.trim();
            if (value.isEmpty())
                continue;
            boolean denied = value.charAt(0) == '!';
            if (denied)
                value = value.substring(1).trim();
            if (value.isEmpty())
                continue;

            String configured = normalizeBiome(value);
            if (denied && current.contains(configured))
                return true;
            if (!denied) {
                hasAllowList = true;
                if (current.contains(configured))
                    explicitlyAllowed = true;
            }
        }

        // Positive entries retain the original whitelist behavior. If the list
        // contains only !entries, every other biome remains allowed.
        return hasAllowList && !explicitlyAllowed;
    }

    private static String normalizeBiome(String biome) {
        return biome.toUpperCase().replace("MINECRAFT:", "").trim();
    }

    public static void runChunkTest() {
        BetterRTP.getInstance().getLogger().info("---------------- Starting chunk test!");
        World world = Bukkit.getWorld("world");
        cacheChunkAt(world, 32, -32, -32, -32);
    }

    private static void cacheTask(World world, int goal, int start, int xat, int zat) {
        zat += 1;
        if (zat > goal) {
            zat = start;
            xat += 1;
        }
        if (xat <= goal)
            cacheChunkAt(world, goal, start, xat, zat);
    }

    private static void cacheChunkAt(World world, int goal, int start, int xat, int zat) {
        Location location = new Location(world, xat * 16, 0, zat * 16);
        AsyncHandler.loadChunk(location).thenAccept(chunk -> {
            try {
                ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
                int maxy = snapshot.getHighestBlockYAt(8, 8);
                Biome biome = snapshot.getBiome(8, 8);
                String worldName = chunk.getWorld().getName();
                int chunkX = chunk.getX();
                int chunkZ = chunk.getZ();
                String biomeName = biome.getKey().toString();
                //BetterRTP.getInstance().getLogger().info("Added " + chunk.getX() + " " + chunk.getZ());
                AsyncHandler.async(() -> {
                    BetterRTP.getInstance().getDatabaseHandler().getDatabaseChunks()
                            .addChunk(worldName, chunkX, chunkZ, maxy, biomeName);
                    cacheTask(world, goal, start, xat, zat);
                });
            } catch (Throwable e) {
                e.printStackTrace();
                throw new RuntimeException();
                //BetterRTP.getInstance().getLogger().info("Tried Adding " + chunk.getX() + " " + chunk.getZ());
            }
        });
    }

}

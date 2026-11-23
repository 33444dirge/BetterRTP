package me.SuperRonanCraft.BetterRTP.references.database;

import java.util.ArrayList;
import java.util.List;

public class DatabaseChunkData extends SQLite {

    public DatabaseChunkData() {
        super(DATABASE_TYPE.CHUNK_DATA);
    }

    @Override
    public List<String> getTables() {
        List<String> list = new ArrayList<>();
        list.add("ChunkData");
        return list;
    }

    public enum COLUMNS {
        ID("id", "integer PRIMARY KEY AUTOINCREMENT"),
        //Chunk Data
        WORLD("world", "varchar(32)"),
        X("x", "long"),
        Z("z", "long"),
        BIOME("biome", "string"),
        MAX_Y("max_y", "integer"),
        ;

        public final String name;
        public final String type;

        COLUMNS(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }


    public void addChunk(String worldName, int chunkX, int chunkZ, int maxY, String biomeName) {
        String pre = "INSERT OR REPLACE INTO ";
        String sql = pre + tables.get(0) + " ("
                + COLUMNS.WORLD.name + ", "
                + COLUMNS.X.name + ", "
                + COLUMNS.Z.name + ", "
                + COLUMNS.BIOME.name + ", "
                + COLUMNS.MAX_Y.name + " "
                + ") VALUES(?, ?, ?, ?, ?)";
        List<Object> params = new ArrayList<>();
        params.add(worldName);
        params.add(chunkX);
        params.add(chunkZ);
        params.add(biomeName);
        params.add(maxY);
        sqlUpdate(sql, params);
    }

}

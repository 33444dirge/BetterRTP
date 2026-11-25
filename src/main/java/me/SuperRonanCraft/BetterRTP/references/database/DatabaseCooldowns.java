package me.SuperRonanCraft.BetterRTP.references.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.World;

import me.SuperRonanCraft.BetterRTP.BetterRTP;
import me.SuperRonanCraft.BetterRTP.references.rtpinfo.CooldownData;

public class DatabaseCooldowns extends SQLite {

    public DatabaseCooldowns() {
        super(DATABASE_TYPE.COOLDOWN);
    }

    @Override
    public List<String> getTables() {
        List<String> list = new ArrayList<>();

        // Ignore loaded world names if cooldowns are disabled
        if (!BetterRTP.getInstance().getCooldowns().isEnabled())
            return list;

        for (World world : Bukkit.getWorlds()) {
            if (allowsWorld(world.getName()))
                list.add(world.getName());
        }
        return list;
    }

    private boolean allowsWorld(String name) {
        List<String> disabled = BetterRTP.getInstance().getRTP().getDisabledWorlds();
        return disabled == null || !disabled.contains(name);
    }

    private boolean tableExists(Connection conn, String name) throws SQLException {
        String sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? COLLATE NOCASE";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public enum COLUMNS {
        UUID("uuid", "varchar(32) PRIMARY KEY"),
        //COOLDOWN DATA
        COOLDOWN_DATE("date", "long"),
        //USES("uses", "integer"),
        ;

        public final String name;
        public final String type;

        COLUMNS(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    public void removePlayer(UUID uuid, World world) {
        String name = world.getName();
        if (!allowsWorld(name)) return;
        try (Connection conn = getSQLConnection()) {
            if (conn == null || !tableExists(conn, name)) return;
            String sql = "DELETE FROM " + quoteIdentifier(name) + " WHERE " + COLUMNS.UUID.name + " = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            BetterRTP.getInstance().getLogger().log(Level.SEVERE, Errors.sqlConnectionExecute(), ex);
        }
    }

    public CooldownData getCooldown(UUID uuid, World world) {
        String name = world.getName();
        if (!allowsWorld(name)) return null;
        try (Connection conn = getSQLConnection()) {
            if (conn == null || !tableExists(conn, name)) return null;
            String sql = "SELECT " + COLUMNS.COOLDOWN_DATE.name + " FROM " + quoteIdentifier(name)
                    + " WHERE " + COLUMNS.UUID.name + " = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new CooldownData(uuid, rs.getLong(1));
                }
            }
        } catch (SQLException ex) {
            BetterRTP.getInstance().getLogger().log(Level.SEVERE, Errors.sqlConnectionExecute(), ex);
        }
        return null;
    }

    public void setCooldown(World world, CooldownData data) {
        String name = world.getName();
        if (!allowsWorld(name)) return;
        try (Connection conn = getSQLConnection()) {
            if (conn == null) return;
            try (Statement ddl = conn.createStatement()) {
                ddl.executeUpdate(getCreateTable(name));
            }
            String sql = "INSERT OR REPLACE INTO " + quoteIdentifier(name) + " ("
                    + COLUMNS.UUID.name + ", " + COLUMNS.COOLDOWN_DATE.name + ") VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, data.getUuid().toString());
                ps.setLong(2, data.getTime());
                ps.executeUpdate();
            }
        } catch (SQLException ex) {
            BetterRTP.getInstance().getLogger().log(Level.SEVERE, Errors.sqlConnectionExecute(), ex);
        }
    }

    //Update multiple players cooldowns
    /*public void setCooldown(List<CooldownData> cooldownData) {
        String pre = "INSERT OR REPLACE INTO ";
        String sql = pre + table + " ("
                + COLUMNS.UUID.name + ", "
                + COLUMNS.COOLDOWN_DATE.name + ", "
                + COLUMNS.USES.name + " "
                + ") VALUES(?, ?, ?)";
        for (CooldownData data : cooldownData) {
            List<Object> param = new ArrayList<Object>() {{
                add(data.getUuid().toString());
                add(data.getTime());
                add(data.getUses());
            }};
            sqlUpdate(sql, param);
        }
    }*/
}
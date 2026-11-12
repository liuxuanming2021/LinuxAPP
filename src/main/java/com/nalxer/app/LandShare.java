package com.nalxer.app;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 队伍领地共享（Residence / Res 插件）。
 *
 * <p>不走 Residence 的 Java API：它把 WorldGuard / Kingdoms 写进了类里，
 * 服务器没装这两个的时候，只要一碰它的类就 NoClassDefFoundError。
 * 这里改成两条最稳的路：</p>
 * <ul>
 *   <li>读 {@code plugins/Residence/Save/Worlds/res_*.yml}，按 OwnerLastKnownName 找某人的领地；</li>
 *   <li>用控制台执行 {@code resadmin pset <领地> <玩家> <flag> true|remove} 改权限。</li>
 * </ul>
 */
final class LandShare {

    /** 基础权限：建造 / 破坏 / 使用 / 容器 / 按钮 / 拉杆 / 中继器 / 门 / 压力板 / 音符盒 / 附魔 / 酿造 / 铁砧 / 蛋糕 / 龙蛋 / 床 / 工作台 */
    private static final String[] FLAGS = {
            "build", "destroy", "use", "container", "button", "lever", "diode", "door",
            "pressure", "note", "enchant", "brew", "anvil", "cake", "egg", "bed", "craft"
    };

    private LandShare() {
    }

    static boolean available() {
        return Bukkit.getPluginManager().getPlugin("Residence") != null;
    }

    /** 从 {@code 1. 领地 - world Owner: ...} 这种回显里取领地名。 */
    private static List<String> parseListed(List<String> lines) {
        List<String> out = new ArrayList<String>();
        for (String raw : lines) {
            String line = raw == null ? "" : raw;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("^\\s*\\d+\\.\\s*(.+?)\\s+-\\s+\\S+\\s+Owner:")
                    .matcher(line);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty() && !out.contains(name)) out.add(name);
            }
        }
        return out;
    }

    private static File saveDir() {
        File dir = new File(Bukkit.getPluginManager().getPlugin("Residence").getDataFolder(), "Save/Worlds");
        return dir.isDirectory() ? dir : null;
    }

    /** 某个玩家名下的领地名字（读存档，跨世界）。 */
    static List<String> ownedResidences(String playerName) {
        List<String> viaCmd = parseListed(listViaCommand(playerName));
        if (!viaCmd.isEmpty()) return viaCmd;

        List<String> out = new ArrayList<String>();
        File dir = saveDir();
        if (dir == null) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase().endsWith(".yml")) continue;
            if (f.getName().startsWith("res_") == false) continue;
            try {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
                ConfigurationSection root = cfg.getConfigurationSection("Residences");
                if (root == null) continue;
                for (String key : root.getKeys(false)) {
                    ConfigurationSection one = root.getConfigurationSection(key);
                    if (one == null) continue;
                    String owner = one.getString("Permissions.OwnerLastKnownName");
                    if (owner != null && owner.equalsIgnoreCase(playerName)) out.add(key);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** 用控制台身份跑 resadmin list，把回显抓下来（读的是内存里的实时数据，不受存档落盘延迟影响）。 */
    static List<String> listViaCommand(String playerName) {
        final List<String> lines = new ArrayList<String>();
        org.bukkit.command.CommandSender capture = new org.bukkit.command.CommandSender() {
            public void sendMessage(String msg) { if (msg != null) lines.add(org.bukkit.ChatColor.stripColor(msg)); }
            public void sendMessage(String[] msgs) { if (msgs != null) for (String m : msgs) sendMessage(m); }
            public org.bukkit.Server getServer() { return Bukkit.getServer(); }
            public String getName() { return "CONSOLE"; }
            public boolean isPermissionSet(String name) { return true; }
            public boolean isPermissionSet(org.bukkit.permissions.Permission perm) { return true; }
            public boolean hasPermission(String name) { return true; }
            public boolean hasPermission(org.bukkit.permissions.Permission perm) { return true; }
            public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() { return java.util.Collections.emptySet(); }
            public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) { return null; }
            public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) { return null; }
            public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) { return null; }
            public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) { return null; }
            public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) { }
            public void recalculatePermissions() { }
            public boolean isOp() { return true; }
            public void setOp(boolean value) { }
            public org.bukkit.command.CommandSender.Spigot spigot() { return null; }
        };
        for (int page = 1; page <= 5; page++) {
            int before = lines.size();
            try {
                Bukkit.dispatchCommand(capture, "resadmin list " + playerName + " " + page);
            } catch (Throwable ignored) {
            }
            // 没有新内容就说明翻到头了
            if (lines.size() == before || lines.size() < 2) break;
        }
        return lines;
    }

    private static void pset(String res, String player, String flag, boolean grant) {
        String cmd = "resadmin pset " + res + " " + player + " " + flag + " " + (grant ? "true" : "remove");
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (Throwable ignored) {
        }
    }

    /** 打开共享：每个成员的领地对其他成员开权限。返回处理过的领地数。 */
    static int grant(List<String> members) {
        int touched = 0;
        for (String owner : members) {
            for (String res : ownedResidences(owner)) {
                for (String other : members) {
                    if (other.equalsIgnoreCase(owner)) continue;
                    for (String flag : FLAGS) pset(res, other, flag, true);
                }
                touched++;
            }
        }
        return touched;
    }

    /** 关闭共享：把成员之间的玩家 flag 收回。 */
    static int revoke(List<String> members) {
        int touched = 0;
        for (String owner : members) {
            for (String res : ownedResidences(owner)) {
                for (String other : members) {
                    if (other.equalsIgnoreCase(owner)) continue;
                    for (String flag : FLAGS) pset(res, other, flag, false);
                }
                touched++;
            }
        }
        return touched;
    }

    /** 只收回某一个人（退队 / 被踢时用）。 */
    static int revokePlayer(List<String> members, String who) {
        int touched = 0;
        for (String owner : members) {
            if (owner.equalsIgnoreCase(who)) continue;
            for (String res : ownedResidences(owner)) {
                for (String flag : FLAGS) pset(res, who, flag, false);
                touched++;
            }
        }
        for (String res : ownedResidences(who)) {
            for (String other : members) {
                if (other.equalsIgnoreCase(who)) continue;
                for (String flag : FLAGS) pset(res, other, flag, false);
                touched++;
            }
        }
        return touched;
    }
}

package com.nalxer.app;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 拼出玩家数据快照（金币 / 点券 / 等级 / 经验 / 在线时长 / 队伍 / 头衔 / 封禁）。
 *
 * 等级和经验这一块之前是空的：离线玩家读的是 stats.yml 缓存，而缓存只有在玩家在线
 * 时才写，所以离线看永远是 0。现在离线直接读存档 world/playerdata/&lt;uuid&gt;.dat
 * （{@link Nbt}），在线时长离线读 world/stats/&lt;uuid&gt;.json 的 stat.playOneMinute。
 */
public final class PlayerStats {

    private final NalxerApp plugin;

    public PlayerStats(NalxerApp plugin) {
        this.plugin = plugin;
    }

    public Map<String, Object> snapshot(String name) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Player online = Bukkit.getPlayerExact(name);
        OfflinePlayer off = online != null ? online : Bukkit.getOfflinePlayer(name);
        UUID id = off.getUniqueId();
        boolean isOnline = online != null;
        String realName = off.getName() == null ? name : off.getName();

        out.put("name", realName);
        out.put("uuid", id == null ? null : id.toString());
        out.put("online", isOnline);
        out.put("server", "Nalxer");
        // 玩家身份：xlcore 的服主 / 副服主 / 市长 / 协管（没有 xlcore 就退回 OP）
        out.put("identity", plugin.hooks().identityOf(realName));
        String roleLabel = String.valueOf(plugin.hooks().identityOf(realName).get("label"));
        out.put("role", roleLabel);

        // ── 金币 / 点券 ──────────────────────────────────────
        if (plugin.getConfig().getBoolean("data.coins", true)) {
            Double coins = plugin.hooks().coins(off);
            out.put("coins", coins);
            out.put("hasCoins", coins != null);
        }
        if (plugin.getConfig().getBoolean("data.points", true)) {
            int p = id == null ? -1 : plugin.hooks().points(id);
            out.put("points", p < 0 ? null : Integer.valueOf(p));
            out.put("hasPoints", p >= 0);
        }

        // ── 等级 / 经验 ─────────────────────────────────────
        if (plugin.getConfig().getBoolean("data.level", true)) {
            int level;
            double progress;
            long total;
            boolean live;
            if (isOnline) {
                level = online.getLevel();
                progress = online.getExp();
                total = online.getTotalExperience();
                live = true;
                plugin.cacheLevel(realName, level, (float) progress, total);
            } else {
                int[] saved = readSaveLevel(id);
                if (saved != null) {
                    level = saved[0];
                    progress = saved[1] / 100.0D;
                    total = saved[2];
                    live = false;
                } else {
                    int[] cached = plugin.cachedLevel(realName);
                    level = cached == null ? 0 : cached[0];
                    progress = cached == null ? 0.0D : cached[1] / 10000.0D;
                    total = cached == null ? 0L : cached[2];
                    live = false;
                }
            }
            if (level < 0) level = 0;
            if (progress < 0) progress = 0;
            if (progress >= 1) progress = 0.999D;
            long need = expToNext(level);
            out.put("level", Integer.valueOf(level));
            out.put("exp", Double.valueOf(round3(progress)));
            out.put("expIntoLevel", Long.valueOf(Math.round(need * progress)));
            out.put("expToNext", Long.valueOf(need));
            out.put("totalExp", Long.valueOf(total));
            out.put("levelLive", Boolean.valueOf(live));
        }

        // ── 在线时长 / 首末进服 ─────────────────────────────
        if (plugin.getConfig().getBoolean("data.playtime", true)) {
            long ticks = playTicks(off, id);
            long seconds = ticks / 20L;
            if (seconds < 0) seconds = 0;
            out.put("playtimeSeconds", Long.valueOf(seconds));
            out.put("playtimeHours", Double.valueOf(Math.round(seconds / 36.0D) / 100.0D));
            Long first = off.getFirstPlayed();
            Long last = off.getLastPlayed();
            out.put("firstPlayed", first == null || first <= 0 ? null : first);
            out.put("lastPlayed", last == null || last <= 0 ? null : last);
        }

        // ── 队伍 / 头衔（离线也能查） ────────────────────────
        if (plugin.getConfig().getBoolean("data.team", true)) {
            Map<String, Object> team = plugin.hooks().teamInfo(realName);
            String colored = team == null ? null : String.valueOf(team.get("coloredName"));
            if (colored != null && colored.isEmpty()) colored = null;
            out.put("team", colored);
            out.put("teamTag", team == null ? null : team.get("coloredTag"));
            out.put("teamInfo", team);
            if (isOnline) plugin.cacheTeam(realName, colored);
        }
        if (plugin.getConfig().getBoolean("data.title", true)) {
            // ★ 走名字查配置，不再依赖 Player 对象 —— 离线也要能看见自己戴着的头衔
            Map<String, Object> row = plugin.hooks().activeTitleRow(realName);
            String activeId = row == null ? "" : String.valueOf(row.get("id"));
            String prefix = row == null ? null : String.valueOf(row.get("prefix"));
            if (prefix != null && prefix.isEmpty()) prefix = null;
            out.put("title", prefix);
            out.put("titleId", activeId);
            out.put("titleName", row == null ? null : row.get("display"));
            if (isOnline && prefix != null) plugin.cacheTitle(realName, prefix);
        }

        // ── 在线时的实时状态 ────────────────────────────────
        if (isOnline) {
            out.put("health", Double.valueOf(round1(online.getHealth())));
            out.put("maxHealth", Double.valueOf(round1(online.getMaxHealth())));
            out.put("food", Integer.valueOf(online.getFoodLevel()));
            out.put("gamemode", online.getGameMode() == null ? null : online.getGameMode().name());
            World w = online.getWorld();
            out.put("world", w == null ? null : w.getName());
            out.put("ping", Integer.valueOf(pingOf(online)));
            out.put("muted", Boolean.valueOf(plugin.hooks().isMuted(realName)));
        }

        // ── 封禁 ────────────────────────────────────────────
        try {
            BanList bans = Bukkit.getBanList(BanList.Type.NAME);
            boolean banned = bans.isBanned(realName);
            out.put("banned", Boolean.valueOf(banned));
            if (banned) {
                BanEntry entry = bans.getBanEntry(realName);
                String reason = entry == null ? null : cleanReason(entry.getReason(), null);
                out.put("banReason", reason);
                out.put("banUntil", entry == null || entry.getExpiration() == null
                        ? null : Long.valueOf(entry.getExpiration().getTime()));
            } else {
                out.put("banReason", null);
                out.put("banUntil", null);
            }
        } catch (Throwable t) {
            out.put("banned", Boolean.FALSE);
            out.put("banReason", null);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    // ── 存档读取 ────────────────────────────────────────────

    /** 读 playerdata 里的 {等级, 经验百分比*100, 总经验}；读不到返回 null。 */
    private int[] readSaveLevel(UUID id) {
        if (id == null) return null;
        File file = new File(worldFolder(), "playerdata" + File.separator + id.toString() + ".dat");
        Map<String, Object> data = Nbt.readPlayerData(file);
        if (data == null || data.isEmpty()) return null;
        int level = data.get("XpLevel") instanceof Number ? ((Number) data.get("XpLevel")).intValue() : 0;
        double progress = data.get("XpP") instanceof Number ? ((Number) data.get("XpP")).doubleValue() : 0.0D;
        long total = data.get("XpTotal") instanceof Number ? ((Number) data.get("XpTotal")).longValue() : 0L;
        if (progress < 0) progress = 0;
        if (progress > 1) progress = 1;
        return new int[]{level, (int) Math.round(progress * 100.0D), (int) Math.min(Integer.MAX_VALUE, total)};
    }

    private File worldFolder() {
        try {
            World main = Bukkit.getWorlds().get(0);
            if (main != null) return main.getWorldFolder();
        } catch (Throwable ignored) {
        }
        return new File(plugin.getServer().getWorldContainer(), plugin.getServer().getWorlds().isEmpty()
                ? "world" : plugin.getServer().getWorlds().get(0).getName());
    }

    /**
     * 在线时长（ticks）。
     * 在线：直接读 Statistic.PLAY_ONE_TICK（注意服务端里这个统计每 tick +1，
     * 名字虽然叫 oneMinute，单位其实是 tick）。
     * 离线：读 world/stats/&lt;uuid&gt;.json 的 stat.playOneMinute，同样是 tick。
     */
    private long playTicks(OfflinePlayer off, UUID id) {
        if (off instanceof Player) {
            try {
                return ((Player) off).getStatistic(Statistic.PLAY_ONE_TICK);
            } catch (Throwable ignored) {
                // 落到离线逻辑
            }
        }
        if (id != null) {
            File stats = new File(worldFolder(), "stats" + File.separator + id.toString() + ".json");
            long v = Nbt.readStat(stats, "stat.playOneMinute");
            if (v >= 0) return v;
        }
        try {
            Object r = OfflinePlayer.class.getMethod("getStatistic", Statistic.class)
                    .invoke(off, Statistic.PLAY_ONE_TICK);
            return r instanceof Number ? ((Number) r).longValue() : 0L;
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    // ── 小工具 ──────────────────────────────────────────────

    /** 1.12.2 的升级所需经验：0-15 级 2L+7，16-30 级 5L-38，31+ 级 9L-158。 */
    public static long expToNext(int level) {
        if (level <= 15) return 2L * level + 7L;
        if (level <= 30) return 5L * level - 38L;
        return 9L * level - 158L;
    }

    /** 把「你已被此服务器封禁！ §4管理员」这种模板原因洗成一句人话。 */
    public static String cleanReason(String reason, String operator) {
        if (reason == null) return null;
        String text = org.bukkit.ChatColor.stripColor(reason).trim();
        text = text.replaceAll("\\s+", " ").trim();
        String prefix = "你已被此服务器封禁！";
        if (text.startsWith(prefix)) {
            text = text.substring(prefix.length()).trim();
        }
        if (operator != null && !operator.isEmpty() && text.equalsIgnoreCase(operator.trim())) {
            text = "";
        }
        if (text.isEmpty()) return "未填写原因";
        return text;
    }

    private static int pingOf(Player p) {
        try {
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Field f = handle.getClass().getField("ping");
            Object v = f.get(handle);
            return v instanceof Number ? ((Number) v).intValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0D) / 10.0D;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0D) / 1000.0D;
    }
}

package com.nalxer.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * 没有 InTheNameOfLinux 时的本地兜底：
 * 禁言（聊天拦截）、封禁（Bukkit 封禁名单）、举报（本地文件）、惩罚记录（本地文件）。
 * 只实现「任何服务器都能用」的最低一套；装了 ITNOL 时优先用 ITNOL。
 */
final class Local implements Listener {

    private final NalxerApp plugin;
    private final File muteFile;
    private final File reportFile;

    /** 玩家名小写 → 解禁时间（毫秒；0 表示永久）。 */
    private final Map<String, Long> mutes = new LinkedHashMap<String, Long>();
    /** 举报行，按提交顺序。 */
    private final List<Map<String, String>> reports = new ArrayList<Map<String, String>>();
    private int nextReportId = 1;

    Local(NalxerApp plugin) {
        this.plugin = plugin;
        File dir = plugin.getDataFolder();
        if (!dir.exists()) dir.mkdirs();
        this.muteFile = new File(dir, "mutes.txt");
        this.reportFile = new File(dir, "reports-local.txt");
        loadMutes();
        loadReports();
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    // ── 禁言 ────────────────────────────────────────────────

    synchronized void mute(String player, long until) {
        mutes.put(key(player), Long.valueOf(until));
        saveMutes();
    }

    synchronized boolean unmute(String player) {
        boolean had = mutes.remove(key(player)) != null;
        if (had) saveMutes();
        return had;
    }

    synchronized boolean isMuted(String player) {
        Long until = mutes.get(key(player));
        if (until == null) return false;
        long u = until.longValue();
        if (u > 0L && u <= System.currentTimeMillis()) {
            mutes.remove(key(player));
            saveMutes();
            return false;
        }
        return true;
    }

    synchronized Map<String, Object> muteInfo(String player) {
        Long until = mutes.get(key(player));
        if (until == null) return null;
        long u = until.longValue();
        if (u > 0L && u <= System.currentTimeMillis()) return null;
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("player", player);
        row.put("type", "banmsg");
        row.put("until", Long.valueOf(u));
        row.put("permanent", Boolean.valueOf(u <= 0L));
        return row;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        final Player p = event.getPlayer();
        if (!isMuted(p.getName())) return;
        event.setCancelled(true);
        String msg = plugin.getConfig().getString("moderation.mute-message",
                "&c你被禁言了，安静一会儿吧。");
        final String text = msg == null ? "" : msg.replace('&', '\u00a7');
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            public void run() {
                p.sendMessage(text);
            }
        });
    }

    private void loadMutes() {
        try {
            if (!muteFile.isFile()) return;
            for (String line : Files.readAllLines(muteFile.toPath(), StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("[|]", 3);
                if (p.length < 2) continue;
                long until = 0L;
                try {
                    until = Long.parseLong(p[1].trim());
                } catch (Throwable ignore) {
                    // 坏行跳过
                }
                mutes.put(key(p[0]), Long.valueOf(until));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[本地] 读禁言记录失败：" + t.getMessage());
        }
    }

    private void saveMutes() {
        StringBuilder sb = new StringBuilder("# 玩家|解禁毫秒(0=永久)\n");
        for (Map.Entry<String, Long> e : mutes.entrySet()) {
            sb.append(e.getKey()).append('|').append(e.getValue().longValue()).append('\n');
        }
        write(muteFile, sb.toString());
    }

    // ── 封禁（Bukkit 自带名单） ────────────────────────────

    void ban(String player, long until, String reason) {
        org.bukkit.BanList list = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
        Date expires = until <= 0L ? null : new Date(until);
        list.addBan(player, reason == null ? "" : reason, expires, "LinuxAPP");
        Player online = Bukkit.getPlayerExact(player);
        if (online != null) {
            online.kickPlayer(reason == null || reason.isEmpty() ? "你被服务器封禁了" : reason);
        }
    }

    boolean unban(String player) {
        org.bukkit.BanList list = Bukkit.getBanList(org.bukkit.BanList.Type.NAME);
        if (list.isBanned(player)) {
            list.pardon(player);
            return true;
        }
        return false;
    }

    boolean isBanned(String player) {
        return Bukkit.getBanList(org.bukkit.BanList.Type.NAME).isBanned(player);
    }

    // ── 惩罚列表 ────────────────────────────────────────────

    synchronized List<Map<String, Object>> penalties() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (Map.Entry<String, Long> e : new LinkedHashMap<String, Long>(mutes).entrySet()) {
            long u = e.getValue().longValue();
            if (u > 0L && u <= System.currentTimeMillis()) continue;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("player", e.getKey());
            row.put("type", "banmsg");
            row.put("until", Long.valueOf(u));
            out.add(row);
        }
        for (org.bukkit.BanEntry entry : Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntries()) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("player", entry.getTarget());
            row.put("type", "ban");
            row.put("until", Long.valueOf(entry.getExpiration() == null ? 0L : entry.getExpiration().getTime()));
            row.put("reason", entry.getReason() == null ? "" : entry.getReason());
            out.add(row);
        }
        return out;
    }

    synchronized Map<String, Object> penaltiesOf(String player) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        Map<String, Object> mute = muteInfo(player);
        if (mute != null) out.put("banmsg", mute);
        if (isBanned(player)) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("player", player);
            row.put("type", "ban");
            for (org.bukkit.BanEntry entry : Bukkit.getBanList(org.bukkit.BanList.Type.NAME).getBanEntries()) {
                if (entry.getTarget() != null && entry.getTarget().equalsIgnoreCase(player)) {
                    row.put("until", Long.valueOf(entry.getExpiration() == null ? 0L : entry.getExpiration().getTime()));
                    row.put("reason", entry.getReason() == null ? "" : entry.getReason());
                    break;
                }
            }
            out.put("ban", row);
        }
        return out;
    }

    // ── 举报 ────────────────────────────────────────────────

    synchronized String createReport(String reporter, String target, String reason) {
        Map<String, String> row = new LinkedHashMap<String, String>();
        int id = nextReportId++;
        row.put("id", String.valueOf(id));
        row.put("reporter", reporter == null ? "" : reporter);
        row.put("target", target == null ? "" : target);
        row.put("reason", reason == null ? "" : reason);
        row.put("time", String.valueOf(System.currentTimeMillis()));
        row.put("handled", "0");
        row.put("handler", "");
        row.put("result", "");
        row.put("note", "");
        reports.add(row);
        saveReports();
        return "举报已提交，编号 #" + id + "，处理结果会在这里显示";
    }

    synchronized List<Map<String, Object>> reports(boolean pendingOnly, String reporter) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (int i = reports.size() - 1; i >= 0; i--) {
            Map<String, String> r = reports.get(i);
            if (pendingOnly && !"0".equals(r.get("handled"))) continue;
            if (reporter != null && !reporter.isEmpty()
                    && !reporter.equalsIgnoreCase(r.get("reporter"))) continue;
            out.add(reportRow(r));
        }
        return out;
    }

    synchronized Map<String, Object> report(int id) {
        for (Map<String, String> r : reports) {
            if (String.valueOf(id).equals(r.get("id"))) return reportRow(r);
        }
        return null;
    }

    synchronized String handleReport(int id, boolean accept, String note, String handler) {
        for (Map<String, String> r : reports) {
            if (!String.valueOf(id).equals(r.get("id"))) continue;
            if (!"0".equals(r.get("handled"))) return "这条举报已经处理过了";
            r.put("handled", "1");
            r.put("handler", handler == null ? "" : handler);
            r.put("result", accept ? "accepted" : "rejected");
            r.put("note", note == null ? "" : note);
            saveReports();
            return accept ? "已接受这条举报" : "已驳回这条举报";
        }
        return "没有编号 #" + id + " 的举报";
    }

    private static Map<String, Object> reportRow(Map<String, String> r) {
        Map<String, Object> row = new LinkedHashMap<String, Object>();
        row.put("id", Integer.valueOf(parseInt(r.get("id"))));
        row.put("reporter", nz(r.get("reporter")));
        row.put("target", nz(r.get("target")));
        row.put("reason", nz(r.get("reason")));
        row.put("time", Long.valueOf(parseLong(r.get("time"))));
        row.put("handled", Boolean.valueOf(!"0".equals(r.get("handled"))));
        row.put("handler", nz(r.get("handler")));
        row.put("result", nz(r.get("result")));
        row.put("note", nz(r.get("note")));
        return row;
    }

    private void loadReports() {
        try {
            if (!reportFile.isFile()) return;
            int max = 0;
            for (String line : Files.readAllLines(reportFile.toPath(), StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("[|]", 9);
                if (p.length < 6) continue;
                Map<String, String> row = new LinkedHashMap<String, String>();
                row.put("id", p[0]);
                row.put("time", p[1]);
                row.put("reporter", p[2]);
                row.put("target", p[3]);
                row.put("reason", p[4]);
                row.put("handled", p[5]);
                row.put("handler", p.length > 6 ? p[6] : "");
                row.put("result", p.length > 7 ? p[7] : "");
                row.put("note", p.length > 8 ? p[8] : "");
                reports.add(row);
                max = Math.max(max, parseInt(p[0]));
            }
            nextReportId = max + 1;
        } catch (Throwable t) {
            plugin.getLogger().warning("[本地] 读举报记录失败：" + t.getMessage());
        }
    }

    private void saveReports() {
        StringBuilder sb = new StringBuilder("# id|时间|举报人|被举报人|理由|已处理|处理人|结果|备注\n");
        for (Map<String, String> r : reports) {
            sb.append(nz(r.get("id"))).append('|')
                    .append(nz(r.get("time"))).append('|')
                    .append(nz(r.get("reporter"))).append('|')
                    .append(nz(r.get("target"))).append('|')
                    .append(nz(r.get("reason")).replace('|', '/')).append('|')
                    .append(nz(r.get("handled"))).append('|')
                    .append(nz(r.get("handler"))).append('|')
                    .append(nz(r.get("result"))).append('|')
                    .append(nz(r.get("note")).replace('|', '/')).append('\n');
        }
        write(reportFile, sb.toString());
    }

    private void write(File target, String content) {
        try {
            File dir = target.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File tmp = new File(target.getAbsolutePath() + ".tmp");
            Files.write(tmp.toPath(), content.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable atomicFailed) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[本地] 写 " + target.getName() + " 失败：" + t.getMessage());
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s == null ? "" : s.trim());
        } catch (Throwable t) {
            return 0L;
        }
    }
}

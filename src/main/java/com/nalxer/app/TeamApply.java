package com.nalxer.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 队伍加入申请（只存「需要申请」的队伍）。
 *
 * <p>LinuxTeam 自己的 requestJoin 只是给玩家记一个 5 分钟的邀请，没有「队长审批」这回事，
 * 所以申请队列由 NalxerApp 自己存：一行一条 {@code teamId|teamName|player|epoch}。</p>
 */
final class TeamApply {

    private final File file;
    /** teamId|player(小写) → 行数据 */
    private final Map<String, String[]> rows = new LinkedHashMap<String, String[]>();

    TeamApply(File dataFolder) {
        this.file = new File(dataFolder, "team-apply.txt");
        load();
    }

    private static String key(String teamId, String player) {
        return String.valueOf(teamId) + "|" + String.valueOf(player).toLowerCase(Locale.ROOT);
    }

    private synchronized void load() {
        rows.clear();
        if (!file.isFile()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|", 4);
                if (p.length < 3) continue;
                rows.put(key(p[0], p[2]), new String[]{p[0], p[1], p[2],
                        p.length > 3 ? p[3] : String.valueOf(System.currentTimeMillis())});
            }
        } catch (Throwable ignored) {
            // 读不出来就当空队列
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) { }
        }
    }

    private synchronized void save() {
        OutputStreamWriter w = null;
        try {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            w = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            for (String[] row : rows.values()) {
                w.write(row[0] + "|" + row[1] + "|" + row[2] + "|" + row[3]);
                w.write("\n");
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.flush(); } catch (Throwable ignored) { }
            try { if (w != null) w.close(); } catch (Throwable ignored) { }
        }
    }

    synchronized boolean has(String teamId, String player) {
        return rows.containsKey(key(teamId, player));
    }

    synchronized void add(String teamId, String teamName, String player) {
        rows.put(key(teamId, player), new String[]{teamId, teamName, player,
                String.valueOf(System.currentTimeMillis())});
        save();
    }

    synchronized void remove(String teamId, String player) {
        if (rows.remove(key(teamId, player)) != null) save();
    }

    synchronized void removeTeam(String teamId) {
        boolean changed = false;
        List<String> drop = new ArrayList<String>();
        for (Map.Entry<String, String[]> e : rows.entrySet()) {
            if (teamId.equals(e.getValue()[0])) drop.add(e.getKey());
        }
        for (String k : drop) {
            rows.remove(k);
            changed = true;
        }
        if (changed) save();
    }

    /** 某支队伍的申请，新的在前。 */
    synchronized List<Map<String, Object>> byTeam(String teamId) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String[] row : rows.values()) {
            if (!String.valueOf(teamId).equals(row[0])) continue;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("team", row[0]);
            m.put("teamName", row[1]);
            m.put("player", row[2]);
            m.put("at", row[3]);
            out.add(m);
        }
        java.util.Collections.reverse(out);
        return out;
    }
}

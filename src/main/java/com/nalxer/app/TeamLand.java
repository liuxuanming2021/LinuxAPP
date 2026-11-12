package com.nalxer.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 队伍的「领地共享」开关。
 *
 * <p>LinuxTeam 的 teams.yml 只认它自己的几个键（chat / friendly-fire / public-join），
 * 我们写进去会被它的保存流程丢掉，所以开关自己存：
 * 一行一支队伍 {@code teamId|enabled|epoch}，只有开启的队伍才落盘。</p>
 */
final class TeamLand {

    private final File file;
    /** teamId → 行数据 {teamId, "true", epoch} */
    private final Map<String, String[]> rows = new LinkedHashMap<String, String[]>();

    TeamLand(File dataFolder) {
        this.file = new File(dataFolder, "team-land.txt");
        load();
    }

    private synchronized void load() {
        rows.clear();
        if (!file.isFile()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#")) continue;
                String[] p = line.split("[|]", 3);
                if (p.length < 2 || !"true".equalsIgnoreCase(p[1])) continue;
                rows.put(p[0], new String[]{p[0], "true",
                        p.length > 2 ? p[2] : String.valueOf(System.currentTimeMillis())});
            }
        } catch (Throwable ignored) {
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
            w.write("# teamId|enabled|epoch\n");
            for (String[] row : rows.values()) {
                w.write(row[0] + "|" + row[1] + "|" + row[2]);
                w.write("\n");
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (w != null) w.flush(); } catch (Throwable ignored) { }
            try { if (w != null) w.close(); } catch (Throwable ignored) { }
        }
    }

    synchronized boolean isOn(String teamId) {
        return rows.containsKey(String.valueOf(teamId));
    }

    synchronized void set(String teamId, boolean on) {
        if (on) {
            rows.put(String.valueOf(teamId), new String[]{String.valueOf(teamId), "true",
                    String.valueOf(System.currentTimeMillis())});
        } else {
            rows.remove(String.valueOf(teamId));
        }
        save();
    }

    synchronized void removeTeam(String teamId) {
        if (rows.remove(String.valueOf(teamId)) != null) save();
    }
}

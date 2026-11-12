package com.nalxer.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单设备登录：记住每个账号当前有效的那枚令牌编号。
 * <p>第二个设备登录时会因为这里已经有一枚令牌而被拒绝；原设备退出登录后会清空，
 * 换设备才能再登。数据落在 plugins/NalxerApp/sessions.txt，重启不掉。</p>
 */
final class Sessions {

    private final File file;
    /** 账号 -> "令牌编号|过期时间(毫秒)" */
    private final Map<String, String> active = new LinkedHashMap<String, String>();

    Sessions(File dataFolder) {
        this.file = new File(dataFolder, "sessions.txt");
        load();
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int i = line.indexOf('|');
                if (i <= 0 || i >= line.length() - 1) {
                    continue;
                }
                active.put(line.substring(0, i), line.substring(i + 1));
            }
        } catch (Exception ignored) {
            // 读不出来就当没有，下次登录会重建
        } finally {
            close(r);
        }
    }

    private void save() {
        Writer w = null;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            w.write("# 账号|令牌编号，一行一个；退出登录会删掉\n");
            for (Map.Entry<String, String> e : active.entrySet()) {
                w.write(e.getKey() + "|" + e.getValue() + "\n");
            }
            w.flush();
        } catch (Exception ignored) {
            // 写失败不影响本次登录
        } finally {
            close(w);
        }
    }

    /** 这个账号现在有没有在别的设备上登录（返回是否被占用）；过期的自动清掉 */
    synchronized boolean isBusy(String name) {
        String key = key(name);
        String cur = active.get(key);
        if (cur == null || cur.isEmpty()) {
            return false;
        }
        if (expired(cur)) {
            active.remove(key);
            save();
            return false;
        }
        return true;
    }

    /** 登录成功：占位，并记下这枚令牌什么时候过期 */
    synchronized void begin(String name, String id, long expiresAt) {
        active.put(key(name), (id == null ? "" : id) + "|" + expiresAt);
        save();
    }

    /** 退出登录：释放 */
    synchronized void end(String name) {
        if (active.remove(key(name)) != null) {
            save();
        }
    }

    /** 这枚令牌还算不算当前有效的那枚；过期的一律作废并释放账号 */
    synchronized boolean matches(String name, String id) {
        String key = key(name);
        String cur = active.get(key);
        if (cur != null && !cur.isEmpty() && expired(cur)) {
            active.remove(key);
            save();
            return false;
        }
        if (cur == null || cur.isEmpty()) {
            // 没有记录：可能是过期被清掉，也可能是文件被删；一律当成已失效，
            // 让这台设备重新登录一次，避免「过期之后还能接着用」。
            return false;
        }
        return idOf(cur).equals(id);
    }

    private static String idOf(String value) {
        int i = value.indexOf('|');
        return i < 0 ? value : value.substring(0, i);
    }

    private static boolean expired(String value) {
        int i = value.indexOf('|');
        if (i < 0 || i >= value.length() - 1) {
            return false;
        }
        try {
            long at = Long.parseLong(value.substring(i + 1).trim());
            return at > 0L && at <= System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase();
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }
}

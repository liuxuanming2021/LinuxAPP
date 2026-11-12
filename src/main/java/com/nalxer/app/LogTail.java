package com.nalxer.app;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 兜底：从 logs/latest.log 里捞「控制台说的话」和「别的客户端 App 说的话」。
 * <p>
 * 为什么要这么干：1.12.2 里 {@code say} 既不走聊天事件，也不一定走命令事件 ——
 * 面板（RCON / stdin / 插件 dispatch）各派发各的，插件层的命令事件只能覆盖一部分。
 * 但 {@code MinecraftServer.sendMessage()} 一定会把这条公告写进服务器日志
 * （形如 {@code [12:00:00 INFO]: [Server] 今晚八点维护}），所以盯日志最稳。
 * <p>
 * 另外 NalxerLink（给安卓客户端用的接口插件）收到 App 发来的聊天时会
 * {@code Bukkit.broadcastMessage("§7[§bApp§7] §f名字§7: §f内容")}，这行同样只落在日志里，
 * 聊天事件和命令事件都抓不到。所以这里把 {@code [App] 名字: 内容} 单独认出来，
 * 记成来源 {@code link}，App 里就显示成「FS1安卓端」。
 */
class LogTail {

    /** 广播输出长这样：[Server] 文本 / [Rcon] 文本 / [CONSOLE] 文本 —— 谁发的就用谁的名字。 */
    private static final Pattern SPEAKER = Pattern.compile("^\\[([^\\]]{1,24})\\]\\s*(.+)$");
    /** NalxerLink 的 App 聊天行：[APP] 玩家名：内容（按第一个冒号切，名字里不会再有冒号）。 */
    private static final Pattern LINK_CHAT = Pattern.compile("^([^:：]{1,40})\\s*[:：]\\s*(.+)$");

    /** 只认这些「发言者」；插件自己的日志一律不认，免得把 [NalxerApp] 也灌进聊天。 */
    private static final java.util.Set<String> SPEAKERS = new java.util.HashSet<String>(
            java.util.Arrays.asList("server", "rcon", "console", "控制台", "服务器"));

    /** NalxerLink 广播用的名字，认这些的当「FS1安卓端」发的话。 */
    private static final java.util.Set<String> LINK_SPEAKERS = new java.util.HashSet<String>(
            java.util.Arrays.asList("app", "fs1安卓端"));

    /** 插件自己的日志前缀，这些一律不当聊天。 */
    private static final java.util.Set<String> IGNORED_SPEAKERS = new java.util.HashSet<String>(
            java.util.Arrays.asList("nalxerlink", "link", "nalxersgs", "nalxerpdr", "nalxereco",
                    "nalxerapp", "authme", "luckperms", "essentials"));


    private final NalxerApp plugin;
    private final File file;
    private long offset = -1L;
    private long lastSeen = 0L;
    private String lastText = "";

    LogTail(NalxerApp plugin) {
        this.plugin = plugin;
        this.file = logFile(plugin);
    }

    /** 正常是 &lt;服务端&gt;/logs/latest.log；worldContainer 有时已经是世界目录，再往上一层兜底。 */
    private static File logFile(NalxerApp plugin) {
        try {
            File root = plugin.getServer().getWorldContainer();
            File f = new File(root, "logs/latest.log");
            if (!f.isFile()) {
                f = new File(root.getParentFile(), "logs/latest.log");
            }
            return f;
        } catch (Throwable t) {
            return new File("logs/latest.log");
        }
    }

    /** 每次跑只读新增的那一段，文件被轮转（变小）就从头来。 */
    void poll() {
        if (!plugin.getConfig().getBoolean("chat.capture-log", true)) return;
        if (!this.file.isFile()) return;
        long length = this.file.length();
        if (this.offset < 0 || length < this.offset) {
            // 第一次：直接跳到末尾，别把历史日志全灌进聊天
            this.offset = length;
            if (this.lastSeen == 0L) return;
        }
        if (length == this.offset) return;

        try {
            RandomAccessFile raf = new RandomAccessFile(this.file, "r");
            try {
                raf.seek(this.offset);
                byte[] buf = new byte[(int) Math.min(65536L, length - this.offset)];
                int read = raf.read(buf);
                if (read <= 0) return;
                this.offset += read;
                String chunk = new String(buf, 0, read, "UTF-8");
                int nl = chunk.lastIndexOf('\n');
                if (nl < 0) {
                    // 半行，退回去下次再读
                    this.offset -= read;
                    return;
                }
                this.offset -= chunk.length() - nl - 1;
                handleChunk(chunk.substring(0, nl));
            } finally {
                raf.close();
            }
        } catch (Throwable t) {
            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().warning("[聊天] 读日志失败：" + t);
            }
        }
    }

    private void handleChunk(String chunk) {
        String[] lines = chunk.split("\n");
        for (String line : lines) {
            String[] parts = split(line);
            if (parts == null) continue;
            boolean link = "link".equals(parts[0]);
            String who = parts[1];
            String text = parts[2];
            if (text.isEmpty()) continue;
            String key = who + "\u0000" + text;
            long now = System.currentTimeMillis();
            // 命令侧捕获过同一句（5 秒内）就跳过，避免重复
            if (key.equals(this.lastText) && now - this.lastSeen < 5000L) continue;
            plugin.chat().add(who, text, link ? "link" : "console");
            this.lastText = key;
            this.lastSeen = now;
        }
    }

    /**
     * 拆一行日志，返回 {来源, 名字, 正文}：来源是 {@code link}（NalxerLink 的 App 聊天）
     * 或 {@code console}（控制台广播）；不认识的发言者返回 null。
     */
    private String[] split(String raw) {
        if (raw == null) return null;
        String line = org.bukkit.ChatColor.stripColor(stripHeads(raw.trim()));
        if (line.isEmpty()) return null;
        Matcher m = SPEAKER.matcher(line);
        if (!m.matches()) return null;
        String speaker = m.group(1).trim().toLowerCase(Locale.ROOT);
        String body = m.group(2).trim();
        // 插件自己的日志（[NalxerLink] [Link] 有效 token：0 这种）永远不是玩家消息
        if (IGNORED_SPEAKERS.contains(speaker) || speaker.startsWith("nalxer")) return null;
        if (linkSpeaker(speaker)) {
            Matcher chat = LINK_CHAT.matcher(body);
            if (!chat.matches()) return null;
            String who = chat.group(1).trim();
            String what = chat.group(2).trim();
            if (who.isEmpty() || what.isEmpty()) return null;
            return new String[]{"link", who, what};
        }
        if (!SPEAKERS.contains(speaker)
                && !plugin.getConfig().getStringList("chat.log-speakers").contains(speaker)) {
            return null;
        }
        return new String[]{"console", "控制台", body};
    }

    /**
     * 把行首的 log4j 前缀一层层剥掉：
     * {@code [22:24:01] [Server thread/INFO]: [App] 名字: 内容} → {@code [App] 名字: 内容}。
     * 只剥「时间」和「线程/级别」这两种方括号，遇到 {@code [App]}、{@code [Server]} 这种就停。
     */
    private static String stripHeads(String line) {
        String s = line;
        while (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) break;
            String head = s.substring(1, close).trim();
            boolean logHead = head.matches("\\d{2}:\\d{2}:\\d{2}(\\s+[A-Z]+)?") || head.contains("/");
            if (!logHead) break;
            String rest = s.substring(close + 1).trim();
            if (rest.startsWith(":")) rest = rest.substring(1).trim();
            if (rest.isEmpty()) break;
            s = rest;
        }
        return s;
    }

    /** 这行是不是 NalxerLink 的 App 聊天（可以在 config 里用 chat.link-speakers 加名字）。 */
    private boolean linkSpeaker(String speaker) {
        if (!plugin.getConfig().getBoolean("chat.capture-link", true)) return false;
        if (LINK_SPEAKERS.contains(speaker)) return true;
        for (String s : plugin.getConfig().getStringList("chat.link-speakers")) {
            if (s != null && s.trim().toLowerCase(Locale.ROOT).equals(speaker)) return true;
        }
        return false;
    }
}

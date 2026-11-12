package com.nalxer.app;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理端「命令控制台」：把 App 里点的按钮变成服务器指令。
 *
 * <p>设计取舍：与其把 ITNOL 的三十来个子命令一条条包成 REST 接口（永远包不全、
 * 每次插件加功能都得跟着改），不如做一个受限的通用执行器——
 * <ul>
 *   <li>只允许白名单里的根命令（{@code admin.commands.allow}），默认就是 ITNOL 与 Linux 系插件；</li>
 *   <li>永远拦截危险根命令（stop / op / deop / whitelist …），防止 App 令牌泄露就等于丢服；</li>
 *   <li>以「收集器」身份执行（op 权限、不是玩家），把插件回显的每一行原样收回来；</li>
 *   <li>整次执行在服务器主线程上跑，最多等 {@value #TIMEOUT_MS} 毫秒，超时按失败返回。</li>
 * </ul>
 */
public final class Console {

    /** 单次执行最长等待 */
    static final int TIMEOUT_MS = 5000;
    /** 回传上限，防止某条指令刷屏把手机打爆 */
    private static final int MAX_LINES = 40;
    private static final int MAX_CHARS = 4000;

    /** 无论怎么配置都不许执行的根命令 */
    private static final Set<String> BLOCKED = new HashSet<String>(Arrays.asList(
            "stop", "restart", "reload", "op", "deop", "whitelist", "dewhitelist",
            "plugins", "pl", "version", "ver", "spigot", "bukkit", "jar",
            "rl", "ess", "essentials", "sudo", "pex", "permissions", "manuadd",
            "manudel", "manuaddp", "manudelp", "gm", "gamemode", "give",
            "kick"
    ));

    private final NalxerApp plugin;

    Console(NalxerApp plugin) {
        this.plugin = plugin;
    }

    /** 一条命令的执行结果 */
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final List<String> lines;

        Result(boolean ok, String message, List<String> lines) {
            this.ok = ok;
            this.message = message;
            this.lines = lines;
        }

        static Result fail(String msg) {
            return new Result(false, msg, Collections.<String>emptyList());
        }
    }

    /** 这个根命令能不能执行 */
    public boolean allowed(String root) {
        String r = root.toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(r)) {
            return false;
        }
        for (String a : plugin.getConfig().getStringList("admin.commands.allow")) {
            if (a == null) {
                continue;
            }
            String x = a.trim().toLowerCase(Locale.ROOT);
            if (!x.isEmpty() && x.equals(r)) {
                return true;
            }
        }
        return false;
    }

    /**
     * su 模式下仍然不给执行的命令。
     *
     * <p>「任意命令」指的是能改服务器状态的活（给东西、传送、刷怪、改权限…），
     * 但关服这类一条指令就让所有人掉线的，App 里按下去没有任何补救手段，
     * 所以留着最后一道保险，并在 UI 上写明。
     */
    private static final Set<String> SU_BLOCKED = new HashSet<String>(Arrays.asList(
            // 关服类
            "stop", "restart", "end",
            // 会把主线程卡住 / 重载整个服务端的（老版本 Paper 的 /reload 尤其毒）
            "reload", "rl",
            // 提权类：su 账号也不该在 App 里改 OP 名单，真要加请去控制台
            "op", "deop"
    ));

    /**
     * 执行一条控制台命令（白名单模式）。
     *
     * @param raw 完整命令，可以带或不带开头的 /（例如 {@code linux ban Steve 3d 作弊}）
     */
    public Result run(String raw) {
        return run(raw, false);
    }

    /**
     * su 模式：验证过 Linux 身份之后可以执行白名单外的命令。
     *
     * @param raw 完整命令
     */

    /**
     * su：以「服务器控制台」身份执行（和后台敲命令完全一样），
     * 回显从 logs/latest.log 里抓 —— 控制台的输出本来就只往日志写。
     */
    public Result runSu(final String raw) {
        String cmd = raw == null ? "" : raw.trim();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        if (cmd.isEmpty()) {
            return new Result(false, "写点东西再执行", new java.util.ArrayList<String>());
        }
        if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0) {
            return new Result(false, "一条一条来，别塞换行", new java.util.ArrayList<String>());
        }
        if (cmd.length() > 400) {
            return new Result(false, "命令太长了（最多 400 字）", new java.util.ArrayList<String>());
        }
        for (String bad : SU_BLOCKED) {
            if (cmd.equalsIgnoreCase(bad) || cmd.toLowerCase().startsWith(bad + " ")) {
                String why;
                if ("op".equalsIgnoreCase(bad) || "deop".equalsIgnoreCase(bad)) {
                    why = "「" + bad + "」能改 OP 名单，App 里不给执行（要改请去控制台）";
                } else if ("reload".equalsIgnoreCase(bad) || "rl".equalsIgnoreCase(bad)) {
                    why = "「" + bad + "」会卡住主线程，App 里不给执行（要重载请去控制台）";
                } else {
                    why = "「" + bad + "」会把服务器关掉，App 里始终不给执行（要关服请去控制台）";
                }
                return new Result(false, why,
                        new java.util.ArrayList<String>());
            }
        }

        final String finalCmd = cmd;
        final java.io.File log = logFile();
        final long from = log == null ? -1L : log.length();
        final AtomicBoolean dispatched = new AtomicBoolean(false);
        final CountDownLatch done = new CountDownLatch(1);

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    dispatched.set(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCmd));
                } catch (Throwable t) {
                    plugin.audit("su 执行异常：" + finalCmd + " -> " + t);
                } finally {
                    done.countDown();
                }
            }
        });

        try {
            done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        java.util.List<String> lines = new java.util.ArrayList<String>();
        if (log != null) {
            // 控制台输出是异步写日志的，多读几次，别漏了最后几行
            for (int i = 0; i < 8; i++) {
                lines = readNewLines(log, from);
                if (!lines.isEmpty()) {
                    break;
                }
                try {
                    Thread.sleep(120L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        // 去掉「xxx issued server command: /cmd」这种噪音
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String line : lines) {
            if (line.toLowerCase().contains("issued server command")) {
                continue;
            }
            if (line.startsWith("[" + finalCmd) || line.equals(finalCmd)) {
                continue;
            }
            out.add(line);
            if (out.size() >= MAX_LINES) {
                break;
            }
        }
        if (!dispatched.get()) {
            return new Result(false, "这条命令服务器没认（看回显）", out);
        }
        return new Result(true, "已以控制台身份执行", out);
    }

    /** logs/latest.log 里从 from 开始新增的行，去掉时间戳前缀。 */
    private static java.util.List<String> readNewLines(java.io.File log, long from) {
        java.util.List<String> lines = new java.util.ArrayList<String>();
        if (log == null || !log.isFile()) {
            return lines;
        }
        try {
            long len = log.length();
            if (from < 0 || from > len) {
                from = Math.max(0L, len - 64 * 1024L);
            }
            long want = Math.min(len - from, 64L * 1024L);
            if (want <= 0) {
                return lines;
            }
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(log, "r");
            try {
                raf.seek(from);
                byte[] buf = new byte[(int) want];
                int n = raf.read(buf);
                if (n <= 0) {
                    return lines;
                }
                String text = new String(buf, 0, n, "UTF-8");
                int cut = text.lastIndexOf('\n');
                if (cut >= 0) {
                    text = text.substring(0, cut);
                }
                for (String raw : text.split("\n")) {
                    String line = raw.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    // 去掉 [13:22:01] [Server thread/INFO]: 这类前缀
                    while (line.startsWith("[")) {
                        int close = line.indexOf(']');
                        if (close < 0) {
                            break;
                        }
                        line = line.substring(close + 1).trim();
                    }
                    while (line.startsWith(":")) {
                        line = line.substring(1).trim();
                    }
                    if (line.isEmpty()) {
                        continue;
                    }
                    lines.add(ChatColor.stripColor(line));
                }
            } finally {
                raf.close();
            }
        } catch (Throwable ignored) {
            // 读不到就算了，别让 su 因为日志失败
        }
        return lines;
    }

    private static java.io.File logFile() {
        try {
            java.io.File root = Bukkit.getWorldContainer();
            // 正常是 <服务端>/logs/latest.log；worldContainer 有时已经是世界目录，再往上一层兜底
            java.io.File f = new java.io.File(root, "logs/latest.log");
            if (!f.isFile()) {
                f = new java.io.File(root.getParentFile(), "logs/latest.log");
            }
            return f.isFile() ? f.getCanonicalFile() : null;
        } catch (Throwable t) {
            return null;
        }
    }


    private Result run(String raw, boolean su) {
        if (!plugin.getConfig().getBoolean("admin.commands.enabled", true)) {
            return Result.fail("命令控制台已在 config.yml 里关闭（admin.commands.enabled: false）");
        }
        String cmd = raw == null ? "" : raw.trim();
        while (cmd.startsWith("/")) {
            cmd = cmd.substring(1).trim();
        }
        if (cmd.isEmpty()) {
            return Result.fail("命令是空的");
        }
        if (cmd.length() > 200) {
            return Result.fail("命令太长了（上限 200 字符）");
        }
        // 聊天/命令里不允许出现换行，避免一次请求塞多条指令
        if (cmd.indexOf('\n') >= 0 || cmd.indexOf('\r') >= 0 || cmd.indexOf('\u00a7') >= 0) {
            return Result.fail("命令里不能有换行或颜色符号");
        }
        String root = cmd.split("\\s+")[0];
        if (su) {
            if (SU_BLOCKED.contains(root.toLowerCase(Locale.ROOT))) {
                return Result.fail("「" + root + "」会把服务器关掉，App 里始终不给执行（要关服请去控制台）");
            }
        } else {
            if (BLOCKED.contains(root.toLowerCase(Locale.ROOT))) {
                return Result.fail("「" + root + "」是危险命令，App 里永远不能执行");
            }
            if (!allowed(root)) {
                return Result.fail("「" + root + "」不在允许列表里（看 config.yml 的 admin.commands.allow）");
            }
        }

        final String finalCmd = cmd;
        final Sink sink = new Sink("NalxerApp");
        final AtomicBoolean dispatched = new AtomicBoolean(false);
        final CountDownLatch done = new CountDownLatch(1);

        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    dispatched.set(Bukkit.dispatchCommand(sink, finalCmd));
                } catch (Throwable t) {
                    sink.sendMessage(ChatColor.RED + "执行出错：" + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : " " + t.getMessage()));
                    plugin.audit("命令执行异常：" + finalCmd + " -> " + t);
                } finally {
                    done.countDown();
                }
            }
        });

        boolean finished;
        try {
            finished = done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            return Result.fail("服务器主线程忙，命令没能在 " + (TIMEOUT_MS / 1000) + " 秒内跑完，稍后再试");
        }

        List<String> lines = sink.lines();
        if (!dispatched.get() && lines.isEmpty()) {
            return Result.fail("找不到命令「" + root + "」——插件没装、没启用，或者名字写错了");
        }
        if (lines.isEmpty()) {
            lines = Collections.singletonList("（命令执行完了，插件没有回显）");
        }
        // 插件把「失败」写在回显里（找不到玩家、Unknown command…）：把 ok 翻转过来，
        // 免得 App 上一律显示「已执行」而用户看不出到底成没成
        String first = trim(lines.get(0));
        for (String bad : BAD_HEADS) {
            if (first.startsWith(bad)) {
                return new Result(false, first, lines);
            }
        }
        return new Result(true, "已执行", lines);
    }

    /** 回显以这些开头的，当失败处理 */
    private static final String[] BAD_HEADS = {
            "错误", "Unknown command", "执行出错", "找不到", "你没有", "你不能", "使用方式", "用法"
    };

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 收集器：以 op 身份接指令，把每一行回显攒下来。
     * 只在主线程里用，不需要线程安全。
     */
    private static final class Sink implements CommandSender {
        private final List<String> lines = new ArrayList<String>();
        private final String name;

        Sink(String name) {
            this.name = name;
        }

        @Override
        public void sendMessage(String message) {
            if (message == null) {
                return;
            }
            String plain = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', message)).trim();
            if (!plain.isEmpty()) {
                lines.add(plain);
            }
        }

        @Override
        public void sendMessage(String[] messages) {
            if (messages != null) {
                for (String m : messages) {
                    sendMessage(m);
                }
            }
        }

        List<String> lines() {
            if (lines.size() > MAX_LINES) {
                List<String> cut = new ArrayList<String>(lines.subList(0, MAX_LINES));
                cut.add("……（还有 " + (lines.size() - MAX_LINES) + " 行没显示）");
                return trim(cut);
            }
            return trim(lines);
        }

        private List<String> trim(List<String> src) {
            List<String> out = new ArrayList<String>();
            int used = 0;
            for (String s : src) {
                if (s == null) {
                    continue;
                }
                if (used + s.length() > MAX_CHARS) {
                    out.add("……（输出太长，后面的没显示）");
                    break;
                }
                used += s.length();
                out.add(s);
            }
            return out;
        }

        @Override
        public org.bukkit.Server getServer() {
            return Bukkit.getServer();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public org.bukkit.command.CommandSender.Spigot spigot() {
            return new org.bukkit.command.CommandSender.Spigot();
        }

        @Override
        public Set<PermissionAttachmentInfo> getEffectivePermissions() {
            return Collections.emptySet();
        }

        @Override
        public void recalculatePermissions() {
        }

        @Override
        public PermissionAttachment addAttachment(Plugin p, String n, boolean v) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin p) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin p, String n, boolean v, int t) {
            return null;
        }

        @Override
        public PermissionAttachment addAttachment(Plugin p, int t) {
            return null;
        }

        @Override
        public void removeAttachment(PermissionAttachment a) {
        }

        @Override
        public boolean isPermissionSet(String n) {
            return true;
        }

        @Override
        public boolean isPermissionSet(Permission p) {
            return true;
        }

        @Override
        public boolean hasPermission(String n) {
            return true;
        }

        @Override
        public boolean hasPermission(Permission p) {
            return true;
        }

        @Override
        public boolean isOp() {
            return true;
        }

        @Override
        public void setOp(boolean v) {
        }
    }
}

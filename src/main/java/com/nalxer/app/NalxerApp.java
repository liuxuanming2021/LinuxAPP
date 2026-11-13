package com.nalxer.app;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * NalxerApp —— 手机 App 的服务端接口。
 * <p>用 AuthMe 的游戏密码登录，返回金币 / 点券 / 等级 / 在线时长 / 队伍 / 头衔，
 * 管理员可以看在线列表、封禁、解封（走 InTheNameOfLinux 的封禁系统）。</p>
 */
public final class NalxerApp extends JavaPlugin implements Listener {

    private Hooks hooks;
    private Console console;
    private ChatBus chat;
    private LogTail logTail;
    private CheckIn checkIn;
    private PlayerStats stats;
    private Tokens tokens;
    private Sessions sessions;
    private ApiServer api;

    private File statsFile;
    private YamlConfiguration statsConfig;
    private volatile boolean statsDirty;

    /** 老版本的数据目录叫 plugins/NalxerApp，改名之后第一次启动就把文件搬过来。 */
    private void migrateDataFolder() {
        java.io.File now = getDataFolder();
        java.io.File old = new java.io.File(now.getParentFile(), "NalxerApp");
        if (!old.isDirectory() || now.exists()) return;
        try {
            copyDir(old, now);
            getLogger().info("已把 plugins/NalxerApp 的数据迁移到 plugins/LinuxAPP");
        } catch (Throwable t) {
            getLogger().warning("数据目录迁移失败（可以手动搬）: " + t);
        }
    }

    private static void copyDir(java.io.File from, java.io.File to) throws java.io.IOException {
        if (!to.isDirectory() && !to.mkdirs()) throw new java.io.IOException("建目录失败: " + to);
        java.io.File[] files = from.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            java.io.File target = new java.io.File(to, f.getName());
            if (f.isDirectory()) copyDir(f, target);
            else java.nio.file.Files.copy(f.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfigKeys();
        ensureSecret();

        hooks = new Hooks(this);
        hooks.bindAll();
        console = new Console(this);
        chat = new ChatBus(this);
        checkIn = new CheckIn(this);
        logTail = new LogTail(this);
        stats = new PlayerStats(this);
        tokens = new Tokens(getConfig().getString("token.secret", ""));
        sessions = new Sessions(getDataFolder());
        loadStats();

        api = new ApiServer(this);
        try {
            api.start();
        } catch (Exception e) {
            getLogger().severe("接口启动失败（端口可能被占用）：" + e.getMessage());
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(chat, this);

        // 定时把缓存的等级/队伍/头衔写盘，也顺手刷新在线玩家的缓存
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                refreshOnlineCache();
                if (statsDirty) {
                    saveStats();
                }
            }
        }, 1200L, 1200L); // 60 秒

        // 控制台 say 兜底：盯 logs/latest.log 里的 [Server] 公告（见 LogTail 注释）
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                if (logTail != null) logTail.poll();
            }
        }, 100L, 20L);

        migrateDataFolder();
        getLogger().info("LinuxAPP 已启用 —— 手机 App 接口就绪（AuthMe 登录："
                + (hooks.hasAuthMe() ? "是" : "否")
                + "，金币：" + (hooks.hasEconomy() ? "是" : "否")
                + "，点券：" + (hooks.hasPoints() ? "是" : "否")
                + "，封禁：" + (hooks.hasBans() ? "是" : "否")
                + "，队伍：" + (hooks.hasTeamApi() ? "是" : "否")
                + "，头衔：" + (hooks.hasTitleApi() ? "是" : "否") + "）");
        getLogger().info("聊天通道已开启 —— 游戏里说话会同步到 App，App 发的消息带 "
                + getConfig().getString("chat.prefix", "§b[LinuxAPP] §f") + " 前缀广播进游戏");
        logGateState();
    }

    /** 把「二次验证 / su 名单」的实际生效情况打出来，排查「为什么没弹验证」时先看这行 */
    private void logGateState() {
        boolean on = getConfig().getBoolean("verify.enabled", true);
        List<String> who = getConfig().getStringList("verify.players");
        if (who == null || who.isEmpty()) {
            String t = hooks.identityTarget();
            who = (t == null || t.isEmpty()) ? java.util.Collections.<String>emptyList()
                    : java.util.Collections.singletonList(t);
        }
        List<String> su = getConfig().getStringList("su.players");
        if (su == null || su.isEmpty()) {
            su = java.util.Arrays.asList("Linux582021", "fs1");
        }
        if (!on) {
            getLogger().info("二次身份验证：关闭（verify.enabled: false）");
        } else if (!hooks.hasIdentity()) {
            getLogger().warning("二次身份验证：想开，但 ITNOL 身份验证没接上 —— "
                    + "检查 InTheNameOfLinux 是不是比 26.11.6 旧（老版本没有 verifyPasswordFromApp）");
        } else {
            getLogger().info("二次身份验证：开启 —— 这些号登录 App 后要先验 ITNOL 口令：" + who
                    + "（口令比对对象：" + hooks.identityTarget() + "）");
        }
        getLogger().info("命令控制台 / su 名单：" + su + "（不在名单里的管理员只能封禁 / 惩罚 / 处理举报 / 管假人）");
        logLinkState();
    }

    /** NalxerLink（安卓客户端接口插件）在不在：在的话它广播的 App 聊天会进公屏，标成「FS1安卓端」 */
    private void logLinkState() {
        if (!getConfig().getBoolean("chat.capture-link", true)) {
            getLogger().info("NalxerLink 的 App 聊天：不抓（chat.capture-link: false）");
            return;
        }
        org.bukkit.plugin.Plugin link = getServer().getPluginManager().getPlugin("NalxerLink");
        if (link == null) {
            getLogger().info("NalxerLink 没装 —— 装了之后它广播的 [App] 名字: 内容 会自动进公屏，标「FS1安卓端」");
        } else {
            getLogger().info("[联动] 已接上 NalxerLink " + link.getDescription().getVersion()
                    + " —— 安卓客户端发的消息会进公屏，标「FS1安卓端」");
        }
    }

    @Override
    public void onDisable() {
        if (api != null) {
            api.stop();
        }
        refreshOnlineCache();
        saveStats();
        getLogger().info("LinuxAPP 已关闭");
    }

    // ── 配置 ────────────────────────────────────────────────

    /** 老服务器第一次升级时把新增配置项补进 config.yml（保留原有注释） */
    /**
     * 老版本默认是 127.0.0.1:8787 / 0.0.0.0:49985，插件只在本机监听，手机连不上。
     * 检测到这种「老默认值」就改成 0.0.0.0:49282，并打一条看得懂的日志。
     */
    private void migrateLegacyHttpEndpoint(File file) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            java.util.regex.Matcher mh = java.util.regex.Pattern
                    .compile("(?m)^(\\s*host\\s*:\\s*)(\\S+)\\s*$").matcher(text);
            java.util.regex.Matcher mp = java.util.regex.Pattern
                    .compile("(?m)^(\\s*port\\s*:\\s*)(\\d+)\\s*$").matcher(text);
            if (!mh.find() || !mp.find()) {
                return;
            }
            String host = mh.group(2);
            String port = mp.group(2);
            boolean legacyPort = port.equals("8787") || port.equals("49985");
            boolean localOnly = host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost");
            if (!legacyPort && !localOnly) {
                return;
            }
            String updated = mh.replaceFirst("$1" + "0.0.0.0");
            mp = java.util.regex.Pattern.compile("(?m)^(\\s*port\\s*:\\s*)(\\d+)\\s*$").matcher(updated);
            updated = mp.replaceFirst("$1" + "49282");
            java.nio.file.Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
            reloadConfig();
            getLogger().info("config.yml 的接口地址还是老默认值（" + host + ":" + port
                    + "），已自动改成 0.0.0.0:49282 —— 手机可以直接连 http://服务器IP:49282");
        } catch (Exception e) {
            getLogger().warning("迁移 http.host/port 失败（不影响启动）：" + e.getMessage());
        }
    }

    /** 老的 config.yml 没有 verify 段（二次身份验证），补一份。 */
    private void upgradeVerifyKey(File file) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.contains("\nverify:") || text.startsWith("verify:")) return;
            String block = "\nverify:\n  enabled: true\n  players:\n    - Linux582021\n    - fs1\n";
            int at = text.indexOf("\nfeatures:");
            if (at < 0) at = text.length();
            text = text.substring(0, at) + block + text.substring(at);
            java.nio.file.Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            getLogger().info("config.yml 补上了「二次身份验证」段（verify）");
        } catch (Throwable t) {
            getLogger().warning("补 verify 段失败：" + t);
        }
    }

    /** 老的 config.yml 没有 su 段（哪些账号能用命令控制台 / su），补一份。 */
    private void upgradeSuKey(File file) {
        try {
            String text = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (text.contains("\nsu:") || text.startsWith("su:")) return;
            String block = "\nsu:\n  players:\n    - Linux582021\n    - fs1\n";
            int at = text.indexOf("\nfeatures:");
            if (at < 0) at = text.length();
            text = text.substring(0, at) + block + text.substring(at);
            java.nio.file.Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
            getLogger().info("config.yml 补上了「su 名单」段（su.players）");
        } catch (Throwable t) {
            getLogger().warning("补 su 段失败：" + t);
        }
    }

    private void upgradeConfigKeys() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            return;
        }
        migrateLegacyHttpEndpoint(file);
        upgradeVerifyKey(file);
        upgradeSuKey(file);
        String[] lines;
        try {
            List<String> all = java.nio.file.Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            lines = all.toArray(new String[0]);
        } catch (Exception e) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        String text = sb.toString();
        StringBuilder add = new StringBuilder();
        if (!text.contains("requests-per-minute")) {
            add.append("  # 同一 IP 每分钟最多请求几次接口\n")
               .append("  requests-per-minute: 180\n");
        }
        // ---- 内置 HTTPS 的三行（老服务器自动补） ----
        if (!text.contains("  tls:") && text.contains("  threads:")) {
            String tlsBlock = "  # 插件自带 HTTPS：true 且下面两行配好，接口本身就是 https（不用再挂 Nginx）\n"
                    + "  #   先把证书转成 PKCS12：\n"
                    + "  #     openssl pkcs12 -export -out keystore.p12 -inkey privkey.pem -in fullchain.pem -passout pass:你的密码\n"
                    + "  #   再把 keystore.p12 放进 plugins/LinuxAPP/\n"
                    + "  tls: false\n"
                    + "  keystore: keystore.p12\n"
                    + "  keystore-password: ''\n";
            int idx = text.indexOf("  threads:");
            int end = text.indexOf('\n', idx);
            if (end > 0) {
                String updated = text.substring(0, end + 1) + tlsBlock + text.substring(end + 1);
                try {
                    java.nio.file.Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
                    reloadConfig();
                    getLogger().info("config.yml 已补上 http.tls（内置 HTTPS）配置项");
                } catch (Exception e) {
                    getLogger().warning("补 http.tls 失败：" + e.getMessage());
                }
                text = updated;
            }
        }

        // ---- 管理页「命令」配置段（老服务器自动补上，注释保留） ----
        if (!text.contains("    allow:") && text.contains("# 展示哪些数据")) {
            StringBuilder block = new StringBuilder();
            block.append("  # 管理页的「命令」功能：App 里可以由管理员执行下面这些插件命令\n");
            block.append("  commands:\n");
            block.append("    # 整个功能的总开关；关掉后 App 里只剩封禁 / 解封\n");
            block.append("    enabled: true\n");
            block.append("    # 允许执行的根命令（不区分大小写）；stop / op / whitelist / give 等永远拦截\n");
            block.append("    allow:\n");
            String[] roots = {"linux", "banlist", "unban", "tempban", "banip", "unbanip", "nalxerapp",
                    "ltab", "lchest", "title", "team",
                    "fp", "fplist", "fpr", "fpreload", "fpsave", "linuxsync"};
            for (String r : roots) {
                block.append("      - ").append(r).append('\n');
            }
            block.append('\n');

            String updated = text.replace("# 展示哪些数据", block.toString() + "# 展示哪些数据");
            if (!updated.equals(text)) {
                try {
                    java.nio.file.Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
                    reloadConfig();
                    getLogger().info("config.yml 已补上 admin.commands（管理页命令）配置段");
                } catch (Exception e) {
                    getLogger().warning("补 admin.commands 失败：" + e.getMessage());
                }
                text = updated;
            }
        }

        // ---- 功能开关（1.3.0） ----
        if (!text.contains("features:") && text.contains("# 展示哪些数据")) {
            StringBuilder fb = new StringBuilder();
            fb.append("# 功能开关：关掉之后 App 里对应页签直接不显示（服务端接口也会拒）\n");
            fb.append("features:\n");
            fb.append("  chat: true\n  team: true\n  titles: true\n  checkin: true\n");
            fb.append("  report: true\n  penalty: true\n  fakes: true\n  console: true\n\n");
            String updated3 = text.replace("# 展示哪些数据", fb.toString() + "# 展示哪些数据");
            if (!updated3.equals(text)) {
                try {
                    java.nio.file.Files.write(file.toPath(), updated3.getBytes(StandardCharsets.UTF_8));
                    reloadConfig();
                    getLogger().info("config.yml 已补上 features（功能开关）配置段");
                } catch (Exception e) {
                    getLogger().warning("补 features 失败：" + e.getMessage());
                }
                text = updated3;
            }
        }

        if (add.length() > 0) {
            try {
                String updated = text.replace("  # 同一 IP 每分钟最多尝试登录几次\n", "  # 同一 IP 每分钟最多尝试登录几次\n");
                if (updated.contains("  attempts-per-minute:")) {
                    int idx = updated.indexOf("  attempts-per-minute:");
                    int end = updated.indexOf('\n', idx);
                    updated = updated.substring(0, end + 1) + add + updated.substring(end + 1);
                }
                if (!updated.equals(text)) {
                    java.nio.file.Files.write(file.toPath(), updated.getBytes(StandardCharsets.UTF_8));
                    reloadConfig();
                    getLogger().info("config.yml 已补上新增配置项");
                }
            } catch (Exception e) {
                getLogger().warning("配置补齐失败：" + e.getMessage());
            }
        }
    }

    /** 第一次启动生成签名密钥并写回配置 */
    private void ensureSecret() {
        String secret = getConfig().getString("token.secret", "");
        if (secret == null || secret.trim().isEmpty()) {
            secret = Tokens.randomSecret();
            getConfig().set("token.secret", secret);
            saveConfig();
            reloadConfig();
            getLogger().info("已生成新的登录令牌密钥（写回 config.yml）");
        }
    }

    public long tokenTtl() {
        long hours = getConfig().getLong("token.ttl-hours", 168L);
        return Math.max(1L, hours) * 3600L;
    }

    // ── 数据缓存（等级 / 队伍 / 头衔，离线时要用） ──────────

    private void loadStats() {
        statsFile = new File(getDataFolder(), "stats.yml");
        if (!statsFile.exists()) {
            try {
                getDataFolder().mkdirs();
                statsFile.createNewFile();
            } catch (Exception e) {
                getLogger().warning("创建 stats.yml 失败：" + e.getMessage());
            }
        }
        statsConfig = YamlConfiguration.loadConfiguration(statsFile);
    }

    public void saveStats() {
        if (statsConfig == null || statsFile == null) {
            return;
        }
        try {
            statsConfig.save(statsFile);
            statsDirty = false;
        } catch (Exception e) {
            getLogger().warning("保存 stats.yml 失败：" + e.getMessage());
        }
    }

    public void cacheLevel(String name, int level, float exp, long totalExp) {
        if (name == null || statsConfig == null) {
            return;
        }
        String path = "levels." + name.toLowerCase();
        int expOnce = Math.round(exp * 10000.0F);
        if (statsConfig.getInt(path + ".level", -1) == level
                && statsConfig.getInt(path + ".exp", -1) == expOnce
                && statsConfig.getLong(path + ".total", -1L) == totalExp) {
            return;
        }
        statsConfig.set(path + ".level", Integer.valueOf(level));
        statsConfig.set(path + ".exp", Integer.valueOf(expOnce));
        statsConfig.set(path + ".total", Long.valueOf(totalExp));
        statsDirty = true;
    }

    /** @return {level, exp*10000, totalExp}，没有缓存返回 null */
    public int[] cachedLevel(String name) {
        if (name == null || statsConfig == null) {
            return null;
        }
        String path = "levels." + name.toLowerCase();
        if (!statsConfig.isSet(path + ".level")) {
            return null;
        }
        return new int[]{
                statsConfig.getInt(path + ".level"),
                statsConfig.getInt(path + ".exp"),
                (int) Math.min(Integer.MAX_VALUE, statsConfig.getLong(path + ".total"))
        };
    }

    public void cacheText(String kind, String name, String value) {
        if (name == null || statsConfig == null) {
            return;
        }
        String path = kind + "." + name.toLowerCase();
        if (statsConfig.isString(path) && statsConfig.getString(path, "").equals(value)) {
            return;
        }
        if (value == null) {
            statsConfig.set(path, null);
        } else {
            statsConfig.set(path, value);
        }
        statsDirty = true;
    }

    public String cachedTeam(String name) {
        return name == null || statsConfig == null ? null : statsConfig.getString("team." + name.toLowerCase());
    }

    public String cachedTitle(String name) {
        return name == null || statsConfig == null ? null : statsConfig.getString("title." + name.toLowerCase());
    }

    private void refreshOnlineCache() {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            cacheLevel(p.getName(), p.getLevel(), p.getExp(), p.getTotalExperience());
            cacheText("team", p.getName(), hooks.team(p));
            cacheText("title", p.getName(), hooks.title(p));
        }
    }

    // ── 事件 ────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        cacheLevel(e.getPlayer().getName(), e.getPlayer().getLevel(), e.getPlayer().getExp(),
                e.getPlayer().getTotalExperience());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cacheLevel(e.getPlayer().getName(), e.getPlayer().getLevel(), e.getPlayer().getExp(),
                e.getPlayer().getTotalExperience());
        cacheText("team", e.getPlayer().getName(), hooks.team(e.getPlayer()));
        cacheText("title", e.getPlayer().getName(), hooks.title(e.getPlayer()));
        saveStats();
    }

    // ── 命令 ────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender.hasPermission("linuxapp.admin") || sender.hasPermission("nalxerapp.admin"))) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行这个命令。");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        if ("status".equals(sub)) {
            sender.sendMessage(ChatColor.DARK_GRAY + "-------- " + ChatColor.AQUA + "LinuxAPP"
                    + ChatColor.DARK_GRAY + " --------");
            sender.sendMessage(ChatColor.GRAY + "接口: " + ChatColor.WHITE
                    + (api != null && api.isRunning() ? "运行中" : "未启动")
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "地址: " + ChatColor.WHITE
                    + "http://" + getConfig().getString("http.host", "0.0.0.0") + ":"
                    + getConfig().getInt("http.port", 49282));
            sender.sendMessage(ChatColor.GRAY + "AuthMe: " + flag(hooks.hasAuthMe())
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "金币: " + flag(hooks.hasEconomy())
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "点券: " + flag(hooks.hasPoints())
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "封禁: " + flag(hooks.hasBans()));
            boolean strict = getConfig().getBoolean("security.strict-encryption", false);
            sender.sendMessage(ChatColor.GRAY + "传输加密: " + (strict
                    ? ChatColor.GREEN + "强制（App 必须建立 AES-256-GCM 会话）"
                    : ChatColor.YELLOW + "按需（登录前明文，登录后能握手就加密）")
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "HTTPS: "
                    + (getConfig().getBoolean("http.tls", false) ? ChatColor.GREEN + "开" : ChatColor.GRAY + "关"));
            sender.sendMessage(ChatColor.GRAY + "在线玩家: " + ChatColor.WHITE + Bukkit.getOnlinePlayers().size()
                    + ChatColor.DARK_GRAY + " | " + ChatColor.GRAY + "管理员: " + ChatColor.WHITE
                    + getConfig().getStringList("admin.players").size() + " 个");
            return true;
        }
        if ("reload".equals(sub)) {
            reloadConfig();
            ensureSecret();
            hooks.bindAll();
            sender.sendMessage(ChatColor.GREEN + "[LinuxAPP] 配置已重载（密钥与接口端口需要重启才生效）。");
            return true;
        }
        if ("token".equals(sub)) {
            String name = args.length > 1 ? args[1] : (sender instanceof org.bukkit.entity.Player
                    ? sender.getName() : null);
            if (name == null) {
                sender.sendMessage(ChatColor.RED + "控制台请写玩家名：/linuxapp token <玩家名>");
                return true;
            }
            String t = tokens.issue(name, isAdminName(name), tokenTtl());
            sender.sendMessage(ChatColor.GREEN + "[LinuxAPP] " + name + " 的测试令牌（"
                    + (tokenTtl() / 3600L) + " 小时有效）：");
            sender.sendMessage(ChatColor.WHITE + t);
            return true;
        }
        if ("logout".equals(sub)) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "用法: /linuxapp logout <玩家名>");
                return true;
            }
            String who = args[1];
            boolean was = sessions.isBusy(who);
            sessions.end(who);
            sender.sendMessage(was
                    ? ChatColor.GREEN + "[LinuxAPP] 已强制 " + who + " 退出登录，该账号现在可以在任意设备重新登录。"
                    : ChatColor.YELLOW + "[LinuxAPP] " + who + " 当前没有登录记录。");
            getLogger().info(sender.getName() + " 执行了 /linuxapp logout " + who);
            return true;
        }
        if ("cert".equals(sub)) {
            if (args.length > 1 && "regen".equalsIgnoreCase(args[1])) {
                api.regenerateCertificate();
                sender.sendMessage(ChatColor.GREEN + "[LinuxAPP] 证书已重新生成，把 plugins/LinuxAPP/server.crt 装到手机上。");
                return true;
            }
            for (String line : api.certificateInfo().split("\n")) {
                sender.sendMessage(ChatColor.GRAY + line);
            }
            sender.sendMessage(ChatColor.YELLOW + "想按当前 cert-dn / cert-san 重新签一张：/linuxapp cert regen");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "用法: /linuxapp <status|reload|token [玩家名]|logout <玩家名>|cert [regen]>");
        return true;
    }

    private static String flag(boolean b) {
        return b ? ChatColor.GREEN + "✔" : ChatColor.RED + "✘";
    }

    private boolean isAdminName(String name) {
        if (name == null) {
            return false;
        }
        for (String s : getConfig().getStringList("admin.players")) {
            if (s != null && s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        try {
            // 离线也给判：op 存在 ops.json，getOfflinePlayer 读得到
            return Bukkit.getOfflinePlayer(name).isOp();
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ── 给接口用 ────────────────────────────────────────────

    public Hooks hooks() {
        return hooks;
    }

    public PlayerStats stats() {
        return stats;
    }

    public Tokens tokens() {
        return tokens;
    }

    /** 单设备登录：每个账号同一时间只允许一枚有效令牌。 */
    public Sessions sessions() {
        return sessions;
    }

    /** 签到。 */
    public CheckIn checkIn() {
        return checkIn;
    }

    public ChatBus chat() {
        return chat;
    }

    /** 记住队伍名（离线且没有 LinuxTeam 数据时兜底） */
    public void cacheTeam(String name, String value) {
        cacheText("team", name, value);
    }

    /** 记住头衔前缀 */
    public void cacheTitle(String name, String value) {
        cacheText("title", name, value);
    }

    /** 管理端命令执行器（App 的「命令」页用） */
    public Console console() {
        return console;
    }

    public ConsoleCommandSender consoleSender() {
        return Bukkit.getConsoleSender();
    }

    /** 审计日志：控制台 + plugins/LinuxAPP/audit.log */
    public void audit(String line) {
        getLogger().info("[审计] " + line);
        if (!getConfig().getBoolean("audit.log-file", true)) {
            return;
        }
        try {
            File f = new File(getDataFolder(), "audit.log");
            String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8);
            try {
                w.write(stamp + "  " + line + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
            // 审计写盘失败不影响接口
        }
    }
}

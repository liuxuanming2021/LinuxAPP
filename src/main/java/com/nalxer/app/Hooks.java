package com.nalxer.app;

import com.inthenameoflinux.BanManager;
import com.inthenameoflinux.InTheNameOfLinux;
import com.inthenameoflinux.LinuxPenalty;
import com.inthenameoflinux.LoginManager;
import com.inthenameoflinux.ReportManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用反射去接服务器上已有的插件（编译期不依赖它们，装没装都能启动）。
 * <p>AuthMe 校验密码、Vault/Essentials 金币、PlayerPoints 点券、ITNOL 封禁、
 * LinuxTeam 队伍、LinuxTitle 头衔。</p>
 */
public final class Hooks {

    /** 硬依赖：ITNOL 必装，直接拿它的管理器用，不再靠反射猜方法名 */
    private InTheNameOfLinux itnol;
    /** 没装 ITNOL 时用的本地兜底（禁言 / 封禁 / 举报 / 惩罚记录）。 */
    private Local local;

    private final NalxerApp plugin;

    // AuthMe
    private Object authMeApi;
    private Method authMeCheck;
    private Method authMeRegistered;

    // Vault 经济
    private Object economy;
    private Method econBalance;

    // PlayerPoints
    private java.lang.reflect.Method econDeposit;
    private java.lang.reflect.Method pointsGive;
    private Object pointsApi;

    // 举报（ITNOL ReportManager）
    private Object reportManager;
    private java.lang.reflect.Method rmList;
    private java.lang.reflect.Method rmGet;
    private java.lang.reflect.Method rmCreateByName;
    private java.lang.reflect.Method rmAccept;
    private java.lang.reflect.Method rmReject;
    private Class<?> reportClass;

    // 假人（FakePlayerPlugin）
    private Object fakePlugin;
    private java.lang.reflect.Method fakeList;
    private java.lang.reflect.Method fakeSaved;
    private java.lang.reflect.Method fakeInfo;
    private Method pointsLook;

    // InTheNameOfLinux 封禁
    private Object banManager;
    private Method banPlayer;
    private Method tempBanPlayer;
    private Method unbanPlayer;
    private Method getBanList;
    private Method getKnownNames;
    private Method resolveKnownName;
    private java.util.Map<String, String> knownNameMap = new java.util.concurrent.ConcurrentHashMap<String, String>();
    private long knownNamesAt = 0L;

    // LinuxTeam
    private Object teamManager;
    private TeamApply teamApply;

    private TeamApply teamApps() {
        if (teamApply == null) teamApply = new TeamApply(plugin.getDataFolder());
        return teamApply;
    }

    // 队伍领地共享（Residence）
    private TeamLand teamLand;

    private TeamLand teamLands() {
        if (teamLand == null) teamLand = new TeamLand(plugin.getDataFolder());
        return teamLand;
    }
    /** LinuxTeam 插件实例（isChatChannelOn / getChatTeam 要用） */
    private Object ltInstance;
    private Method teamByPlayer;

    // LinuxTitle
    private Object titleManager;
    private Method titleByPlayer;
    private Field titlePrefix;

    // LinuxTeam 扩展：队伍信息 / 操作
    private Method teamByUuid;
    // ── NalxerECO 转账（只反射调，不动那个插件）──────────────
    private boolean neoReady;
    private Method neoCoinPay;      // EconomyHook.pay(OfflinePlayer, OfflinePlayer, double) -> String
    private Method neoPointsPay;    // PointsHook.pay(UUID, UUID, int) -> boolean
    private Method neoCoinBalance;  // EconomyHook.balance(OfflinePlayer) -> double
    private Method neoPointsLook;   // PointsHook.look(UUID) -> int
    private Method neoCoinFormat;   // EconomyHook.format(double) -> String

    private Method teamGetTeams;
    private Method teamByName;
    private Method teamAll;
    private Method teamCreate;
    private Method teamDisband;
    private Method teamJoin;
    private Method teamLeave;
    private Method teamKick;
    private Method teamTransfer;
    private Method teamInvite;
    private Method teamLeaveOne;
    private Method teamLimitOf;
    private Method teamCanJoinMore;
    private Method teamRefresh;
    private Method teamSave;
    private Method teamGet;

    // LinuxTitle 扩展：全部头衔 / 解锁列表 / 佩戴
    private Object titleData;
    private Object titleConfig;
    private Method pdataUnlocked;
    private Method pdataActive;
    private Method titleSetActive;
    private Method titlePlaytime;
    private Method titleTodayPlaytime;
    private Method titleAllTitles;
    private Method titleConfigGet;
    private Class<?> titleDefClass;

    // InTheNameOfLinux 禁言
    private Object penalty;
    // InTheNameOfLinux 身份验证（口令）
    private Object itnolLogin;
    private java.lang.reflect.Method itnolVerify;
    private java.lang.reflect.Method itnolTarget;
    private java.lang.reflect.Method itnolDefaultPw;
    private Method penaltyBannedReadOnly;
    private String typeBanMsg = "banmsg";

    public Hooks(NalxerApp plugin) {
        this.plugin = plugin;
    }

    // ── 初始化 ──────────────────────────────────────────────

    public void bindAll() {
        itnol = (InTheNameOfLinux) Bukkit.getPluginManager().getPlugin("InTheNameOfLinux");
        if (itnol == null) {
            local = new Local(plugin);
            Bukkit.getPluginManager().registerEvents(local, plugin);
            plugin.getLogger().info("[联动] 没装 InTheNameOfLinux —— 禁言/封禁/举报改用本地实现");
        }

        bindAuthMe();
        bindEconomy();
        bindPoints();
        bindBans();
        bindTeams();
        bindTitles();
        if (itnol != null) {
            bindPenalty();
            bindIdentity();
            bindReports();
        }
        bindFakes();
        bindXland();
        bindNalxerEco();
    }

    private void bindAuthMe() {
        try {
            Class<?> api = Class.forName("fr.xephi.authme.api.v3.AuthMeApi");
            authMeApi = api.getMethod("getInstance").invoke(null);
            authMeCheck = api.getMethod("checkPassword", String.class, String.class);
            try {
                authMeRegistered = api.getMethod("isRegistered", String.class);
            } catch (NoSuchMethodException ignored) {
                authMeRegistered = null;
            }
            plugin.getLogger().info("[联动] 已接上 AuthMe —— 用游戏密码登录");
        } catch (Throwable t) {
            authMeApi = null;
            plugin.getLogger().warning("[联动] 没接上 AuthMe：手机 App 将无法登录（" + t.getClass().getSimpleName() + "）");
        }
    }

    private void bindEconomy() {
        try {
            Class<?> econClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object reg = Bukkit.getServicesManager().getRegistration(econClass);
            if (reg == null) {
                throw new IllegalStateException("Vault 里没有经济插件注册");
            }
            economy = reg.getClass().getMethod("getProvider").invoke(reg);
            econBalance = econClass.getMethod("getBalance", OfflinePlayer.class);
            try {
                econDeposit = econClass.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            } catch (NoSuchMethodException ignored) {
                econDeposit = null;
            }
            plugin.getLogger().info("[联动] 已接上 Vault 经济 —— 金币数据");
        } catch (Throwable t) {
            economy = null;
            plugin.getLogger().warning("[联动] 没接上 Vault 经济，金币显示为不可用（" + t.getClass().getSimpleName() + "）");
        }
    }

    private void bindPoints() {
        try {
            Class<?> pp = Class.forName("org.black_ixx.playerpoints.PlayerPoints");
            Object inst = pp.getMethod("getInstance").invoke(null);
            pointsApi = pp.getMethod("getAPI").invoke(inst);
            pointsLook = pointsApi.getClass().getMethod("look", UUID.class);
            try {
                pointsGive = pointsApi.getClass().getMethod("give", UUID.class, int.class);
            } catch (NoSuchMethodException ignored) {
                pointsGive = null;
            }
            plugin.getLogger().info("[联动] 已接上 PlayerPoints —— 点券数据");
        } catch (Throwable t) {
            pointsApi = null;
            plugin.getLogger().warning("[联动] 没接上 PlayerPoints，点券显示为不可用（" + t.getClass().getSimpleName() + "）");
        }
    }

    private void bindBans() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("InTheNameOfLinux");
            if (p == null) {
                throw new IllegalStateException("没装 InTheNameOfLinux");
            }
            banManager = p.getClass().getMethod("getBanManager").invoke(p);
            Class<?> c = banManager.getClass();
            banPlayer = c.getMethod("banPlayer", CommandSender.class, String.class, String.class);
            tempBanPlayer = c.getMethod("tempBanPlayer", CommandSender.class, String.class, long.class, String.class);
            unbanPlayer = c.getMethod("unbanPlayer", CommandSender.class, String.class);
            getBanList = c.getMethod("getBanList");
            try {
                getKnownNames = c.getMethod("getKnownPlayerNames");
            } catch (NoSuchMethodException ignored) {
                getKnownNames = null;
            }
            try {
                resolveKnownName = c.getMethod("resolveKnownName", String.class);
            } catch (NoSuchMethodException ignored) {
                resolveKnownName = null;
            }
            plugin.getLogger().info("[联动] 已接上 InTheNameOfLinux —— 封禁 / 解封");
        } catch (Throwable t) {
            banManager = null;
            plugin.getLogger().warning("[联动] 没接上 InTheNameOfLinux，管理页的封禁功能不可用（" + t.getClass().getSimpleName() + "）");
        }
    }

    private void bindTeams() {
        try {
            Class<?> lt = Class.forName("com.linuxteam.LinuxTeam");
            Object inst = lt.getMethod("get").invoke(null);
            ltInstance = inst;
            teamManager = lt.getMethod("getTeamManager").invoke(inst);
            Class<?> tm = teamManager.getClass();
            teamByPlayer = tm.getMethod("getPrimaryTeam", Player.class);
            teamGet = tm.getMethod("getTeam", Player.class);
            teamByUuid = method(tm, "getPrimaryTeam", UUID.class);
            teamByName = method(tm, "getTeamByName", String.class);
            teamAll = tm.getMethod("getTeams");
            teamGetTeams = method(tm, "getTeams", UUID.class);
            teamCreate = tm.getMethod("createTeam", Player.class, String.class, int.class);
            teamDisband = method(tm, "disband", Class.forName("com.linuxteam.team.Team"));
            teamJoin = method(tm, "join", Class.forName("com.linuxteam.team.Team"), Player.class);
            teamLeave = tm.getMethod("leave", Player.class);
            teamKick = method(tm, "kick", Class.forName("com.linuxteam.team.Team"), UUID.class);
            teamTransfer = method(tm, "transferLeader", Class.forName("com.linuxteam.team.Team"), UUID.class);
            teamInvite = method(tm, "invite", Class.forName("com.linuxteam.team.Team"), UUID.class,
                    org.bukkit.entity.Player.class, int.class);
            teamLeaveOne = method(tm, "leave", org.bukkit.entity.Player.class,
                    Class.forName("com.linuxteam.team.Team"));
            teamLimitOf = tm.getMethod("teamLimit", UUID.class);
            teamCanJoinMore = tm.getMethod("canJoinMore", UUID.class);
            teamRefresh = method(tm, "refreshTags", java.util.Collection.class);
            teamSave = tm.getMethod("save");
            plugin.getLogger().info("[联动] 已接上 LinuxTeam 队伍（信息 / 建队 / 加入 / 退出 / 踢人 / 转让）");
        } catch (Throwable t) {
            teamManager = null;
            plugin.getLogger().warning("[联动] LinuxTeam 队伍接口不全：" + t);
        }
    }

    /** 拿方法，拿不到返回 null（不同版本可能少了某些方法，不能整块放弃联动）。 */
    private static Method method(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    private void bindTitles() {
        try {
            Class<?> lt = Class.forName("com.linuxtitle.LinuxTitle");
            Object inst = lt.getMethod("getInstance").invoke(null);
            titleManager = lt.getMethod("getTitleManager").invoke(inst);
            Class<?> tm = titleManager.getClass();
            titleByPlayer = tm.getMethod("getActiveTitleDef", Player.class);
            titleSetActive = tm.getMethod("setActive", String.class, String.class);
            try {
                titlePlaytime = tm.getMethod("getPlaytime", org.bukkit.entity.Player.class);
                titleTodayPlaytime = tm.getMethod("getTodayPlaytime", org.bukkit.entity.Player.class);
            } catch (NoSuchMethodException ignored) {
                titlePlaytime = null;
            }
            titleData = lt.getMethod("getPlayerDataManager").invoke(inst);
            titleConfig = lt.getMethod("getTitleConfig").invoke(inst);
            pdataUnlocked = titleData.getClass().getMethod("getUnlockedTitles", String.class);
            pdataActive = titleData.getClass().getMethod("getActiveTitle", String.class);
            titleAllTitles = titleConfig.getClass().getMethod("getAllTitles");
            titleConfigGet = titleConfig.getClass().getMethod("getTitle", String.class);
            titleDefClass = Class.forName("com.linuxtitle.TitleConfig$TitleDef");
            titlePrefix = titleDefClass.getField("prefix");
            plugin.getLogger().info("[联动] 已接上 LinuxTitle 头衔（列表 / 佩戴）");
        } catch (Throwable t) {
            titleManager = null;
            plugin.getLogger().warning("[联动] LinuxTitle 头衔接口不全：" + t);
        }
    }

    private void bindPenalty() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("InTheNameOfLinux");
            if (p == null) return;
            penalty = p.getClass().getMethod("getPenaltyManager").invoke(p);
            Class<?> c = penalty.getClass();
            penaltyBannedReadOnly = c.getMethod("isBannedReadOnly", String.class, String.class);
            Field f = c.getField("TYPE_BANMSG");
            Object v = f.get(null);
            if (v != null) typeBanMsg = String.valueOf(v);
            plugin.getLogger().info("[联动] 已接上 ITNOL 禁言判定（App 里发聊天前会先查）");
        } catch (Throwable t) {
            penalty = null;
        }
    }

    /** ITNOL 的身份验证（口令哈希）——手机端二次验证用。 */
    /** XlandCore（xlcore）：服主 / 副服主 / 市长 / 协管 的身份判定 */
    private Object xlandCore;
    private java.lang.reflect.Method xlIsOwner;
    private java.lang.reflect.Method xlIsCoOwner;
    private java.lang.reflect.Method xlIsMayor;
    private java.lang.reflect.Method xlIsModerator;
    private java.lang.reflect.Method xlPrefixOwner;
    private java.lang.reflect.Method xlPrefixCoOwner;
    private java.lang.reflect.Method xlPrefixMayor;
    private java.lang.reflect.Method xlPrefixModerator;

    /** 绑定 NalxerECO（经济中枢）的转账 API；它没装就只是没有转账入口，不影响别的功能。 */
    private void bindNalxerEco() {
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("NalxerECO");
            if (p == null || !p.isEnabled()) {
                neoReady = false;
                return;
            }
            Class<?> eco = Class.forName("com.nalxer.eco.EconomyHook");
            Class<?> pts = Class.forName("com.nalxer.eco.PointsHook");
            neoCoinPay = eco.getMethod("pay", org.bukkit.OfflinePlayer.class, org.bukkit.OfflinePlayer.class, double.class);
            neoCoinBalance = eco.getMethod("balance", org.bukkit.OfflinePlayer.class);
            neoCoinFormat = eco.getMethod("format", double.class);
            neoPointsPay = pts.getMethod("pay", java.util.UUID.class, java.util.UUID.class, int.class);
            neoPointsLook = pts.getMethod("look", java.util.UUID.class);
            neoReady = true;
            plugin.getLogger().info("[联动] 已接上 NalxerECO 转账（金币 / 点券）");
        } catch (Throwable t) {
            neoReady = false;
            plugin.getLogger().warning("[联动] NalxerECO 转账接口没接上：" + t);
        }
    }

    /** 有没有转账能力（App 用来决定要不要显示转账按钮）。 */
    public boolean hasTransfer() {
        return neoReady;
    }

    /**
     * 转账。type=coins 走 EconomyHook.pay，type=points 走 PointsHook.pay，
     * 规则、税、下限全部沿用 NalxerECO 那边的实现，App 只负责把人名和金额递过去。
     *
     * @return 给人看的回执（成功 / 失败原因）
     */
    public String transfer(String type, String fromName, String toName, double amount) {
        if (!neoReady) return "服务器没接上 NalxerECO，转不了账";
        if (toName == null || toName.trim().isEmpty()) return "要转给谁？写个玩家 ID";
        String target = toName.trim();
        if (target.equalsIgnoreCase(fromName)) return "不能转给自己";
        if (amount <= 0) return "金额要大于 0";
        org.bukkit.OfflinePlayer from = Bukkit.getOfflinePlayer(resolveName(fromName));
        org.bukkit.OfflinePlayer to = Bukkit.getOfflinePlayer(resolveName(target));
        if (from == null || from.getUniqueId() == null) return "找不到你自己的账号";
        if (to == null || to.getUniqueId() == null) return "找不到玩家 " + target;
        try {
            if ("points".equals(type)) {
                int n = (int) Math.floor(amount);
                if (n <= 0) return "点券要按整数转";
                Object ok = neoPointsPay.invoke(null, from.getUniqueId(), to.getUniqueId(), Integer.valueOf(n));
                if (Boolean.TRUE.equals(ok)) {
                    return "已转给 " + target + " " + n + " 点券";
                }
                Object left = neoPointsLook == null ? null : neoPointsLook.invoke(null, from.getUniqueId());
                return "点券转账失败：余额不够（你现在有 " + (left == null ? "?" : left) + " 点券）";
            }
            // 金币
            String err = (String) neoCoinPay.invoke(null, from, to, Double.valueOf(amount));
            if (err == null) {
                String show;
                try {
                    show = String.valueOf(neoCoinFormat.invoke(null, Double.valueOf(amount)));
                } catch (Throwable t) {
                    show = String.valueOf(amount);
                }
                return "已转给 " + target + " " + show;
            }
            return "金币转账失败：" + err;
        } catch (Throwable t) {
            return "转账出错：" + t.getMessage();
        }
    }

    /** 自己的余额（金币 / 点券），App 转账弹窗上显示一行。 */
    public java.util.Map<String, Object> transferBalance(String playerName) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        out.put("available", Boolean.valueOf(neoReady));
        if (!neoReady) return out;
        org.bukkit.OfflinePlayer p = Bukkit.getOfflinePlayer(resolveName(playerName));
        try {
            Object coins = neoCoinBalance.invoke(null, p);
            out.put("coins", coins);
        } catch (Throwable ignored) {
        }
        try {
            Object pts = neoPointsLook.invoke(null, p.getUniqueId());
            out.put("points", pts);
        } catch (Throwable ignored) {
        }
        return out;
    }

    private void bindXland() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("XlandCore");
            if (p == null) {
                p = Bukkit.getPluginManager().getPlugin("xlcore");
            }
            if (p == null) {
                plugin.getLogger().info("[联动] 没找到 XlandCore（xlcore），玩家身份只按 OP / 管理员算");
                return;
            }
            Class<?> c = p.getClass();
            xlandCore = p;
            xlIsOwner = method(c, "isOwner", String.class);
            xlIsCoOwner = method(c, "isCoOwner", String.class);
            xlIsMayor = method(c, "isMayor", String.class);
            xlIsModerator = method(c, "isModerator", String.class);
            xlPrefixOwner = method(c, "getPrefixOwner");
            xlPrefixCoOwner = method(c, "getPrefixCoOwner");
            xlPrefixMayor = method(c, "getPrefixMayor");
            xlPrefixModerator = method(c, "getPrefixModerator");
            plugin.getLogger().info("[联动] 已接上 XlandCore 身份（服主 / 副服主 / 市长 / 协管）");
        } catch (Throwable t) {
            xlandCore = null;
            plugin.getLogger().warning("[联动] XlandCore 身份没接上：" + t);
        }
    }

    public boolean hasXland() {
        return xlandCore != null;
    }

    private boolean xlBool(java.lang.reflect.Method m, String name) {
        if (m == null || xlandCore == null || name == null) return false;
        try {
            Object v = m.invoke(xlandCore, name);
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private String xlPrefix(java.lang.reflect.Method m) {
        if (m == null || xlandCore == null) return "";
        try {
            Object v = m.invoke(xlandCore);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * 玩家身份：xlcore 的四种身份 + 兜底。
     *
     * @return {role, label, prefix, roles[]}，role 取最高的一档
     */
    public Map<String, Object> identityOf(String name) {
        Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        java.util.List<String> roles = new java.util.ArrayList<String>();
        String role = "player";
        String label = "玩家";
        String prefix = "";

        if (xlBool(xlIsOwner, name)) {
            roles.add("服主");
            if ("player".equals(role)) { role = "owner"; label = "服主"; prefix = xlPrefix(xlPrefixOwner); }
        }
        if (xlBool(xlIsCoOwner, name)) {
            roles.add("副服主");
            if ("player".equals(role)) { role = "coowner"; label = "副服主"; prefix = xlPrefix(xlPrefixCoOwner); }
        }
        if (xlBool(xlIsMayor, name)) {
            roles.add("市长");
            if ("player".equals(role)) { role = "mayor"; label = "市长"; prefix = xlPrefix(xlPrefixMayor); }
        }
        if (xlBool(xlIsModerator, name)) {
            roles.add("协管");
            if ("player".equals(role)) { role = "moderator"; label = "协管"; prefix = xlPrefix(xlPrefixModerator); }
        }
        if ("player".equals(role)) {
            // xlcore 没有的话，按 OP / 管理员兜个底，别让管理员显示成普通玩家
            Object op = null;
            try {
                op = Bukkit.getOfflinePlayer(resolveName(name)).isOp();
            } catch (Throwable ignored) {
            }
            if (Boolean.TRUE.equals(op)) {
                role = "admin";
                label = "管理员";
                roles.add("管理员");
            }
        }
        out.put("role", role);
        out.put("label", label);
        out.put("prefix", prefix);
        out.put("roles", roles);
        out.put("hasXland", Boolean.valueOf(hasXland()));
        return out;
    }

    private void bindIdentity() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("InTheNameOfLinux");
            if (p == null) return;
            itnolLogin = p.getClass().getMethod("getLoginManager").invoke(p);
            if (itnolLogin == null) return;
            Class<?> c = itnolLogin.getClass();
            itnolVerify = c.getMethod("verifyPasswordFromApp", String.class);
            itnolTarget = c.getMethod("getTargetPlayer");
            itnolDefaultPw = c.getMethod("isUsingDefaultPassword");
            Object who = itnolTarget.invoke(itnolLogin);
            plugin.getLogger().info("[联动] 已接上 ITNOL 身份验证（受保护账号 " + who + " 在 App 里要验口令）");
        } catch (Throwable t) {
            itnolLogin = null;
            itnolVerify = null;
            plugin.getLogger().warning("[联动] ITNOL 身份验证没接上：" + t);
        }
    }

    public boolean hasIdentity() {
        return itnol != null && itnol.getLoginManager() != null;
    }

    /** ITNOL 保护的那个玩家名（一般是 Linux582021）。 */
    public String identityTarget() {
        if (itnol == null) return "";
        LoginManager lm = itnol.getLoginManager();
        String t = lm == null ? null : lm.getTargetPlayer();
        return t == null ? "" : t;
    }

    /** 这个口令对不对（只比哈希，不碰玩家的登录状态）。 */
    public boolean identityCheck(String password) {
        if (itnol == null) return false;
        LoginManager lm = itnol.getLoginManager();
        if (lm == null || password == null || password.isEmpty()) return false;
        return lm.verifyPasswordFromApp(password);
    }

    /** ITNOL 是否还在用默认口令（默认口令要在游戏里改掉）。 */
    public boolean identityDefaultPassword() {
        if (itnol == null) return false;
        LoginManager lm = itnol.getLoginManager();
        return lm != null && lm.isUsingDefaultPassword();
    }

    // ── 能力查询 ────────────────────────────────────────────

    public boolean hasAuthMe() {
        return authMeApi != null && authMeCheck != null;
    }

    public boolean hasEconomy() {
        return economy != null && econBalance != null;
    }

    public boolean hasPoints() {
        return pointsApi != null && pointsLook != null;
    }

    /** 仅当服务器没装 InTheNameOfLinux 时才走本地实现。 */
    private boolean useLocal() {
        return itnol == null && local != null;
    }

    public boolean hasBans() {
        if (useLocal()) return true;
        return itnol != null && itnol.getBanManager() != null;
    }

    public boolean hasTeams() {
        return teamManager != null && teamByPlayer != null;
    }

    public boolean hasTitles() {
        return titleManager != null && titleByPlayer != null;
    }

    // ── 具体取值 ────────────────────────────────────────────

    /** AuthMe 校验密码；出错一律当失败（fail-closed） */
    public boolean checkPassword(String name, String password) {
        if (!hasAuthMe()) {
            return false;
        }
        try {
            Object r = authMeCheck.invoke(authMeApi, name, password);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            plugin.getLogger().warning("[登录] 校验 " + name + " 的密码时出错：" + t);
            return false;
        }
    }

    /** 该名字在 AuthMe 里注册过没有（没装 AuthMe 返回 false） */
    public boolean isRegistered(String name) {
        if (authMeRegistered == null || authMeApi == null) {
            return false;
        }
        try {
            Object r = authMeRegistered.invoke(authMeApi, name);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 金币；不可用返回 null */
    public Double coins(OfflinePlayer p) {
        if (!hasEconomy()) {
            return null;
        }
        try {
            Object r = econBalance.invoke(economy, p);
            return r instanceof Number ? ((Number) r).doubleValue() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 点券；不可用返回 -1 */
    public int points(UUID id) {
        if (!hasPoints()) {
            return -1;
        }
        try {
            Object r = pointsLook.invoke(pointsApi, id);
            return r instanceof Number ? ((Number) r).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 给玩家加金币（签到奖励用）。没人接经济插件时返回 false。 */
    public boolean depositCoins(String playerName, int amount) {
        if (economy == null || econDeposit == null || amount <= 0) return false;
        try {
            OfflinePlayer off = Bukkit.getOfflinePlayer(resolveName(playerName));
            econDeposit.invoke(economy, off, Double.valueOf(amount));
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("[签到] 发金币失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 给玩家加点券（签到奖励用）。没接 PlayerPoints 时返回 false。 */
    public boolean givePoints(String playerName, int amount) {
        if (pointsApi == null || pointsGive == null || amount <= 0) return false;
        try {
            OfflinePlayer off = Bukkit.getOfflinePlayer(resolveName(playerName));
            Object r = pointsGive.invoke(pointsApi, off.getUniqueId(), Integer.valueOf(amount));
            return !(r instanceof Boolean) || Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            plugin.getLogger().warning("[签到] 发点券失败：" + t.getClass().getSimpleName());
            return false;
        }
    }

    /** 排头衔：佩戴中的在前，其次已解锁，最后未解锁；同组按 priority 倒序。 */
    public static void sortTitles(java.util.List<java.util.Map<String, Object>> rows) {
        java.util.Collections.sort(rows, new java.util.Comparator<java.util.Map<String, Object>>() {
            @Override
            public int compare(java.util.Map<String, Object> a, java.util.Map<String, Object> b) {
                int ra = rank(a), rb = rank(b);
                if (ra != rb) return Integer.compare(ra, rb);
                int pa = a.get("priority") instanceof Number ? ((Number) a.get("priority")).intValue() : 0;
                int pb = b.get("priority") instanceof Number ? ((Number) b.get("priority")).intValue() : 0;
                if (pa != pb) return Integer.compare(pb, pa);
                return String.valueOf(a.get("id")).compareTo(String.valueOf(b.get("id")));
            }

            private int rank(java.util.Map<String, Object> row) {
                if (Boolean.TRUE.equals(row.get("active"))) return 0;
                if (Boolean.TRUE.equals(row.get("unlocked"))) return 1;
                return 2;
            }
        });
    }

    /** 队伍名（带颜色）；没有队伍或没装 LinuxTeam 返回 null */
    public String team(Player p) {
        if (!hasTeams()) {
            return null;
        }
        try {
            Object team = teamByPlayer.invoke(teamManager, p);
            if (team == null) {
                return null;
            }
            Object name = team.getClass().getMethod("getColoredName").invoke(team);
            return name == null ? null : String.valueOf(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 头衔前缀（带颜色）；没有头衔返回 null */
    public String title(Player p) {
        if (!hasTitles()) {
            return null;
        }
        try {
            Object def = titleByPlayer.invoke(titleManager, p);
            if (def == null) {
                return null;
            }
            Object prefix = titlePrefix.get(def);
            return prefix == null ? null : String.valueOf(prefix);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把玩家输入的名字（可能大小写不对）解析成服务器认识的名字 */
    /** 从 ITNOL 拉一次「记录过的玩家名」，建小写 → 标准写法的索引。 */
    private void refreshKnownNameMap() {
        if (getKnownNames == null || banManager == null) return;
        try {
            Object r = getKnownNames.invoke(banManager);
            if (r instanceof java.util.Collection) {
                java.util.Map<String, String> m = new java.util.concurrent.ConcurrentHashMap<String, String>();
                for (Object o : (java.util.Collection<?>) r) {
                    if (o == null) continue;
                    String n = String.valueOf(o).trim();
                    if (!n.isEmpty()) m.put(n.toLowerCase(java.util.Locale.ROOT), n);
                }
                knownNameMap = m;
                knownNamesAt = System.currentTimeMillis();
            }
        } catch (Throwable ignored) {
            // 拿不到就继续用在线玩家与离线名单
        }
    }

    /** 名字 → 服务器上的标准写法（大小写不敏感），找不到就原样返回。 */
    private final java.util.Map<String, String> nameCache = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile java.util.Map<String, String> offlineIndex =
            java.util.Collections.emptyMap();
    private volatile long offlineIndexAt = 0L;

    /** 离线玩家名索引，最多 10 分钟重建一次；空结果也带时间戳，不会每次重扫。 */
    private java.util.Map<String, String> offlineNameIndex() {
        long now = System.currentTimeMillis();
        if (now - offlineIndexAt < 600000L && !offlineIndex.isEmpty()) {
            return offlineIndex;
        }
        java.util.Map<String, String> built = new java.util.HashMap<String, String>();
        try {
            for (OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                String n = off.getName();
                if (n != null && !n.isEmpty()) {
                    built.put(n.toLowerCase(java.util.Locale.ROOT), n);
                }
            }
        } catch (Throwable ignored) {
        }
        offlineIndex = built;
        offlineIndexAt = now;
        return built;
    }

    public String resolveName(String input) {
        if (input == null) return null;
        String key = input.trim();
        if (key.isEmpty()) return key;
        String lower = key.toLowerCase(java.util.Locale.ROOT);

        // 在线玩家：大小写不敏感匹配
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(key)) {
                nameCache.put(lower, p.getName());
                return p.getName();
            }
        }
        String cached = nameCache.get(lower);
        if (cached != null) return cached;

        // ITNOL 记录过的真实玩家名（最权威）
        if (System.currentTimeMillis() - knownNamesAt > 300000L) {
            refreshKnownNameMap();
        }
        String known = knownNameMap.get(lower);
        if (known != null) {
            nameCache.put(lower, known);
            return known;
        }

        // ITNOL 的封禁库里有更全的历史名字
        if (resolveKnownName != null && banManager != null) {
            try {
                Object r = resolveKnownName.invoke(banManager, key);
                if (r != null && !String.valueOf(r).isEmpty()) {
                    String v = String.valueOf(r);
                    nameCache.put(lower, v);
                    return v;
                }
            } catch (Throwable ignored) {
                // 落到下面继续找
            }
        }
        // 离线名单：整表扫一次太贵（几千人 + 每次请求都扫会拖主线程），
        // 所以放进带 TTL 的离线索引里，并且失败也缓存一条「查不到」，
        // 避免同一个错名字被反复扫。
        String fromOffline = offlineNameIndex().get(lower);
        if (fromOffline != null) {
            nameCache.put(lower, fromOffline);
            return fromOffline;
        }
        nameCache.put(lower, key);   // 记住「这个名字就是查不到」
        return key;
    }

    /**
     * 封禁玩家。
     *
     * @param durationMs 0 表示永久封禁
     * @return 真为成功
     */
    public boolean ban(String name, String reason, long durationMs, CommandSender operator) {
        lastMessage = null;
        if (banManager == null) {
            if (local == null) {
                lastMessage = "服务器没装 InTheNameOfLinux，封禁功能不可用";
                return false;
            }
            local.ban(name, durationMs <= 0L ? 0L : System.currentTimeMillis() + durationMs,
                    reason == null ? "" : reason);
            lastMessage = "已封禁 " + name;
            return true;
        }
        try {
            // 用「收集回执」的代发器：ITNOL 失败时会 sendMessage 说明原因（例如
            // 「玩家 xxx 不存在或从未游玩」），这里收下来给 App 显示
            CollectSender collector = new CollectSender(operator);
            Object r;
            if (durationMs > 0 && tempBanPlayer != null) {
                r = tempBanPlayer.invoke(banManager, collector, name, durationMs, reason);
            } else {
                r = banPlayer.invoke(banManager, collector, name, reason);
            }
            boolean ok = Boolean.TRUE.equals(r);
            lastMessage = ok ? collector.plain() : collector.plain();
            return ok;
        } catch (Throwable t) {
            plugin.getLogger().warning("[封禁] " + name + " 失败：" + t);
            lastMessage = "封禁失败：" + t.getMessage();
            return false;
        }
    }

    /** 给 App 用：operatorName 会显示成「APP端（用户名）」。 */
    public boolean ban(String name, String reason, long durationMs, String operatorName) {
        return ban(name, reason, durationMs, new CollectSender(appActor(operatorName)));
    }

    public boolean unban(String name, String operatorName) {
        return unban(name, new CollectSender(appActor(operatorName)));
    }

    public boolean unban(String name, CommandSender operator) {
        lastMessage = null;
        if (banManager == null || unbanPlayer == null) {
            if (local == null) {
                lastMessage = "服务器没装 InTheNameOfLinux，解封功能不可用";
                return false;
            }
            boolean ok = local.unban(name);
            lastMessage = ok ? ("已解封 " + name) : (name + " 没有被封禁");
            return ok;
        }
        try {
            CollectSender collector = new CollectSender(operator);
            Object r = unbanPlayer.invoke(banManager, collector, name);
            boolean ok = Boolean.TRUE.equals(r);
            lastMessage = collector.plain();
            return ok;
        } catch (Throwable t) {
            plugin.getLogger().warning("[解封] " + name + " 失败：" + t);
            lastMessage = "解封失败：" + t.getMessage();
            return false;
        }
    }

    /** 最近一次封禁 / 解封的回执文字（给接口回错误提示用）。 */
    private volatile String lastMessage;

    public String lastMessage() {
        return lastMessage;
    }

    /**
     * 代发器：把 ITNOL 发给指令发送者的提示收集成纯文本。
     * 只实现 sendMessage(CharSequence)，其余方法抛不支持（ITNOL 不会用到）。
     */
    private static final class CollectSender implements CommandSender {
        private final StringBuilder sb = new StringBuilder();
        private final String name;

        CollectSender(CommandSender base) {
            this.name = base == null ? "NalxerApp" : base.getName();
        }

        /** 直接指定回执里显示的执行人（App 操作用「APP端（用户名）」）。 */
        CollectSender(String displayName) {
            this.name = displayName == null || displayName.isEmpty() ? "APP端" : displayName;
        }

        @Override
        public void sendMessage(String message) {
            if (message != null && message.length() > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(message);
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

        String plain() {
            return org.bukkit.ChatColor.stripColor(sb.toString()).trim();
        }

        @Override
        public org.bukkit.Server getServer() {
            return org.bukkit.Bukkit.getServer();
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
        public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() {
            return java.util.Collections.emptySet();
        }

        @Override
        public void recalculatePermissions() {
            // 假代发器，不需要重算权限
        }

        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) {
            return null;
        }

        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) {
            return null;
        }

        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) {
            return null;
        }

        @Override
        public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) {
            return null;
        }

        @Override
        public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) {
            // 假代发器，不挂权限
        }

        @Override
        public boolean isPermissionSet(String name) {
            return true;
        }

        @Override
        public boolean isPermissionSet(org.bukkit.permissions.Permission perm) {
            return true;
        }

        @Override
        public boolean hasPermission(String name) {
            return true;
        }

        @Override
        public boolean hasPermission(org.bukkit.permissions.Permission perm) {
            return true;
        }

        @Override
        public boolean isOp() {
            return true;
        }

        @Override
        public void setOp(boolean value) {
        }
    }


    // ── LinuxTeam：信息与操作 ────────────────────────────────

    public boolean hasTeamApi() {
        return teamManager != null && teamByUuid != null;
    }

    private Object teamOfName(String name) {
        if (teamManager == null) return null;
        try {
            if (teamByName != null) {
                Object t = teamByName.invoke(teamManager, name);
                if (t != null) return t;
            }
            Player p = Bukkit.getPlayerExact(name);
            if (p != null && teamByPlayer != null) return teamByPlayer.invoke(teamManager, p);
            OfflinePlayer off = Bukkit.getOfflinePlayer(name);
            if (off != null && teamByUuid != null) return teamByUuid.invoke(teamManager, off.getUniqueId());
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 队伍完整信息；没队伍返回 null。 */
    public java.util.Map<String, Object> teamInfo(String playerName) {
        return teamRow(teamOfName(playerName));
    }

    /**
     * 玩家身处的所有队伍：LinuxTeam 允许一个人同时在多个队里，
     * 只读 getPrimaryTeam 会把第二个队藏起来。
     */
    public java.util.List<java.util.Map<String, Object>> myTeams(String playerName) {
        java.util.List<java.util.Map<String, Object>> out =
                new java.util.ArrayList<java.util.Map<String, Object>>();
        if (!hasTeamApi() || teamGetTeams == null) return out;
        try {
            java.util.UUID uuid = Bukkit.getOfflinePlayer(playerName).getUniqueId();
            Object raw = teamGetTeams.invoke(teamManager, uuid);
            if (!(raw instanceof Iterable)) return out;
            for (Object team : (Iterable<?>) raw) {
                java.util.Map<String, Object> row = teamRow(team);
                if (row != null) out.add(row);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] 读我的队伍列表失败：" + t);
        }
        return out;
    }

    // ── LinuxTeam 队伍频道 ─────────────────────────────────

    /** 玩家现在是否开着 /team chat，开着就返回目标队伍（Map：id / name / tag）。 */
    public java.util.Map<String, Object> teamChatOf(String playerName) {
        org.bukkit.entity.Player p = Bukkit.getPlayerExact(playerName);
        if (p == null || ltInstance == null) return null;
        try {
            Object on = ltInstance.getClass().getMethod("isChatChannelOn", org.bukkit.entity.Player.class)
                    .invoke(ltInstance, p);
            if (!Boolean.TRUE.equals(on)) return null;
            Object team = ltInstance.getClass().getMethod("getChatTeam", org.bukkit.entity.Player.class)
                    .invoke(ltInstance, p);
            if (team == null) return null;
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
            out.put("id", str(team.getClass(), team, "getId"));
            out.put("name", str(team.getClass(), team, "getName"));
            out.put("tag", str(team.getClass(), team, "getTag"));
            return out;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按 id 直接拿 LinuxTeam 的 Team 对象（找不到返回 null）。 */
    private Object teamObjectById(String id) {
        if (id == null || id.isEmpty() || teamAll == null) return null;
        try {
            Object raw = teamAll.invoke(teamManager);
            if (!(raw instanceof Iterable)) return null;
            for (Object t : (Iterable<?>) raw) {
                if (t == null) continue;
                Object tid = t.getClass().getMethod("getId").invoke(t);
                if (tid != null && id.equalsIgnoreCase(String.valueOf(tid))) return t;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 按队伍 id 找队伍（找不到返回 null）。 */
    public java.util.Map<String, Object> teamById(String id) {
        if (id == null || id.trim().isEmpty() || !hasTeamApi() || teamAll == null) return null;
        try {
            Object raw = teamAll.invoke(teamManager);
            if (!(raw instanceof Iterable)) return null;
            for (Object t : (Iterable<?>) raw) {
                if (t == null) continue;
                Object tid = t.getClass().getMethod("getId").invoke(t);
                if (tid != null && id.trim().equalsIgnoreCase(String.valueOf(tid))) {
                    return teamRow(t);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 这个人在不在队里。 */
    public boolean teamHasMember(java.util.Map<String, Object> team, String playerName) {
        if (team == null || playerName == null) return false;
        Object members = team.get("members");
        if (!(members instanceof Iterable)) return false;
        for (Object o : (Iterable<?>) members) {
            if (!(o instanceof java.util.Map)) continue;
            Object n = ((java.util.Map<?, ?>) o).get("name");
            if (n != null && playerName.equalsIgnoreCase(String.valueOf(n))) return true;
        }
        return false;
    }

    /** 队伍的聊天开关（LinuxTeam 里队长可以关掉）。 */
    public boolean teamChatAllowed(java.util.Map<String, Object> team) {
        if (team == null || !hasTeamApi() || teamAll == null) return false;
        try {
            Object raw = teamAll.invoke(teamManager);
            if (!(raw instanceof Iterable)) return true;
            for (Object t : (Iterable<?>) raw) {
                if (t == null) continue;
                Object tid = t.getClass().getMethod("getId").invoke(t);
                if (tid == null || !String.valueOf(tid).equalsIgnoreCase(String.valueOf(team.get("id")))) continue;
                Object on = t.getClass().getMethod("getSettingBool", String.class, boolean.class)
                        .invoke(t, "chat", Boolean.TRUE);
                return !Boolean.FALSE.equals(on);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    /** 把一条文本发给该队所有在线成员。 */
    public void teamSendToOnline(String teamId, String text) {
        if (text == null || teamId == null || teamId.isEmpty() || !hasTeamApi() || teamAll == null) return;
        try {
            Object raw = teamAll.invoke(teamManager);
            if (!(raw instanceof Iterable)) return;
            for (Object t : (Iterable<?>) raw) {
                if (t == null) continue;
                Object tid = t.getClass().getMethod("getId").invoke(t);
                if (tid == null || !teamId.equalsIgnoreCase(String.valueOf(tid))) continue;
                Object online = t.getClass().getMethod("getOnlinePlayers").invoke(t);
                if (!(online instanceof Iterable)) return;
                for (Object p : (Iterable<?>) online) {
                    if (p instanceof org.bukkit.entity.Player) {
                        ((org.bukkit.entity.Player) p).sendMessage(text);
                    }
                }
                return;
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] 发队伍消息失败：" + t);
        }
    }

    /** 把一个 Team 对象摊成 Map（列表、详情、我的队伍都用它）。 */
    private org.bukkit.configuration.file.YamlConfiguration teamFileCfg;
    private long teamFileStamp = -1L;

    /** LinuxTeam 的 teams.yml（找不到就返回 null）。 */
    private java.io.File teamFile() {
        try {
            org.bukkit.plugin.Plugin lt = Bukkit.getPluginManager().getPlugin("LinuxTeam");
            if (lt != null) {
                java.io.File f = new java.io.File(lt.getDataFolder(), "teams.yml");
                if (f.isFile()) return f;
            }
        } catch (Throwable ignored) {
        }
        java.io.File f = new java.io.File(plugin.getDataFolder().getParentFile(), "LinuxTeam/teams.yml");
        return f.isFile() ? f : null;
    }

    /**
     * 直接读 teams.yml 里某个队伍的开关：teams.<id>.settings.<key>。
     * 文件里没有这个键（老队伍没写过）就返回 null，交给调用方用插件默认值。
     */
    public Boolean teamSettingFromFile(String teamId, String key) {
        java.io.File f = teamFile();
        if (f == null || teamId == null || teamId.isEmpty()) return null;
        try {
            long stamp = f.lastModified() * 31L + f.length();
            if (teamFileCfg == null || stamp != teamFileStamp) {
                teamFileCfg = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
                teamFileStamp = stamp;
            }
            String path = "teams." + teamId + ".settings." + key;
            if (!teamFileCfg.isSet(path)) return null;
            Object raw = teamFileCfg.get(path);
            if (raw == null) return null;
            String v = String.valueOf(raw).trim();
            if (v.isEmpty()) return null;
            return Boolean.valueOf("true".equalsIgnoreCase(v));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 队伍开关：优先读 teams.yml，读不到再问插件内存，最后用默认值。 */
    public boolean teamSetting(String teamId, Object team, String key, boolean def) {
        Boolean fromFile = teamSettingFromFile(teamId, key);
        if (fromFile != null) return fromFile.booleanValue();
        if (team != null) {
            try {
                Object v = team.getClass().getMethod("getSettingBool", String.class, boolean.class)
                        .invoke(team, key, Boolean.valueOf(def));
                if (v instanceof Boolean) return ((Boolean) v).booleanValue();
            } catch (Throwable ignored) {
            }
        }
        return def;
    }

    public java.util.Map<String, Object> teamRow(Object team) {
        if (team == null) return null;
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        try {
            Class<?> c = team.getClass();
            out.put("id", str(c, team, "getId"));
            out.put("name", str(c, team, "getName"));
            out.put("coloredName", str(c, team, "getColoredName"));
            out.put("tag", str(c, team, "getTag"));
            out.put("coloredTag", str(c, team, "getColoredTag"));
            out.put("leaderName", str(c, team, "getLeaderName"));
            Object leader = c.getMethod("getLeader").invoke(team);
            out.put("leader", leader == null ? "" : String.valueOf(leader));
            Object max = c.getMethod("getMaxSize").invoke(team);
            out.put("maxSize", max instanceof Number ? ((Number) max).intValue() : 0);
            Object size = c.getMethod("size").invoke(team);
            out.put("size", size instanceof Number ? ((Number) size).intValue() : 0);
            Object created = c.getMethod("getCreateTime").invoke(team);
            out.put("createTime", created == null ? "" : String.valueOf(created));
            try {
                Object st = c.getMethod("getSettings").invoke(team);
                if (st instanceof java.util.Map) {
                    java.util.Map<String, Object> settings = new java.util.LinkedHashMap<String, Object>();
                    for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) st).entrySet()) {
                        settings.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                    }
                    out.put("settings", settings);
                }
                String rowId = String.valueOf(out.get("id"));
                boolean chatOn = teamSetting(rowId, team, "chat", true);
                boolean pub = teamSetting(rowId, team, "public-join", true);
                boolean ff = teamSetting(rowId, team, "friendly-fire", false);
                out.put("chatOn", Boolean.valueOf(chatOn));
                out.put("publicJoin", Boolean.valueOf(pub));
                out.put("friendlyFire", Boolean.valueOf(ff));
                out.put("applyNeeded", Boolean.valueOf(!pub));
                try {
                    out.put("landShare", Boolean.valueOf(teamLands().isOn(rowId)));
                } catch (Throwable ignored) {
                    out.put("landShare", Boolean.FALSE);
                }
            } catch (Throwable ignored) {
            }

            java.util.List<java.util.Map<String, Object>> members = new java.util.ArrayList<java.util.Map<String, Object>>();
            Object raw = c.getMethod("getSortedMembers").invoke(team);
            if (raw instanceof Iterable) {
                for (Object m : (Iterable<?>) raw) {
                    if (m == null) continue;
                    Class<?> mc = m.getClass();
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                    row.put("name", str(mc, m, "getName"));
                    Object online = mc.getMethod("isOnline").invoke(m);
                    row.put("online", Boolean.TRUE.equals(online));
                    Object contrib = mc.getMethod("getContribution").invoke(m);
                    row.put("contribution", contrib instanceof Number ? ((Number) contrib).intValue() : 0);
                    Object days = mc.getMethod("getDaysInTeam").invoke(m);
                    row.put("days", days instanceof Number ? ((Number) days).intValue() : 0);
                    Object uuid = mc.getMethod("getUuid").invoke(m);
                    row.put("uuid", uuid == null ? "" : String.valueOf(uuid));
                    members.add(row);
                }
            }
            out.put("members", members);
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] 读取信息失败：" + t);
        }
        return out;
    }

    /** 服务器上的队伍列表（按人数排序，最多 limit 个）。 */
    public java.util.List<java.util.Map<String, Object>> teamList(String query, int limit) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (teamManager == null || teamAll == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            Object raw = teamAll.invoke(teamManager);
            if (!(raw instanceof Iterable)) return out;
            for (Object team : (Iterable<?>) raw) {
                if (team == null) continue;
                Class<?> c = team.getClass();
                String name = str(c, team, "getName");
                String tag = str(c, team, "getTag");
                if (!q.isEmpty()
                        && !name.toLowerCase(java.util.Locale.ROOT).contains(q)
                        && !tag.toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    continue;
                }
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                row.put("id", str(c, team, "getId"));
                row.put("name", name);
                row.put("coloredName", str(c, team, "getColoredName"));
                row.put("tag", tag);
                Object size = c.getMethod("size").invoke(team);
                row.put("size", size instanceof Number ? ((Number) size).intValue() : 0);
                Object max = c.getMethod("getMaxSize").invoke(team);
                row.put("maxSize", max instanceof Number ? ((Number) max).intValue() : 0);
                row.put("leaderName", str(c, team, "getLeaderName"));
                row.put("publicJoin", Boolean.valueOf(teamSetting(String.valueOf(row.get("id")), team, "public-join", true)));
                out.add(row);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] 读取列表失败：" + t);
        }
        java.util.Collections.sort(out, new java.util.Comparator<java.util.Map<String, Object>>() {
            @Override
            public int compare(java.util.Map<String, Object> a, java.util.Map<String, Object> b) {
                boolean pa = Boolean.FALSE.equals(a.get("publicJoin"));
                boolean pb = Boolean.FALSE.equals(b.get("publicJoin"));
                if (pa != pb) return pa ? -1 : 1;   // 需要申请的队伍放前面，玩家一眼能看到
                int sa = a.get("size") instanceof Number ? ((Number) a.get("size")).intValue() : 0;
                int sb = b.get("size") instanceof Number ? ((Number) b.get("size")).intValue() : 0;
                return Integer.compare(sb, sa);
            }
        });
        if (limit > 0 && out.size() > limit) {
            return new java.util.ArrayList<java.util.Map<String, Object>>(out.subList(0, limit));
        }
        return out;
    }

    /** 申请加入「需要申请」的队伍；公开队直接提示去点加入。 */
    public String teamApplyJoin(String playerName, String teamId) {
        if (teamManager == null) return "LinuxTeam 没装，队伍功能不可用";
        Object team = teamObjectById(teamId);
        if (team == null) return "找不到这个队伍";
        java.util.Map<String, Object> row = teamRow(team);
        String tname = String.valueOf(row.get("name"));
        if (Boolean.TRUE.equals(row.get("publicJoin"))) {
            return "「" + tname + "」是公开队，直接点加入就行";
        }
        if (teamHasMember(row, playerName)) return "你已经在「" + tname + "」里了";
        if (teamApps().has(teamId, playerName)) return "已经申请过了，等队长处理";
        Object full = null;
        try { full = team.getClass().getMethod("isFull").invoke(team); } catch (Throwable ignored) { }
        if (Boolean.TRUE.equals(full)) return "「" + tname + "」已经满员了";
        teamApps().add(teamId, tname, playerName);
        // 顺手通知在线队长
        String leader = String.valueOf(row.get("leaderName"));
        Player lp = Bukkit.getPlayerExact(resolveName(leader));
        if (lp != null) {
            lp.sendMessage("§7[§b队伍§7] §f" + playerName + " §7申请加入 §f" + tname + "§7，可在 App 队伍页处理");
        }
        return "已申请「" + tname + "」，等队长批准";
    }

    /** 领地共享开了没。 */
    public boolean teamLandOn(String teamId) {
        return teamLands().isOn(teamId);
    }

    /** 队伍成员名列表（含队长）。 */
    private java.util.List<String> teamMemberNames(Object team) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            java.util.Map<String, Object> row = teamRow(team);
            Object list = row == null ? null : row.get("members");
            if (list instanceof Iterable) {
                for (Object m : (Iterable<?>) list) {
                    if (!(m instanceof java.util.Map)) continue;
                    Object n = ((java.util.Map<?, ?>) m).get("name");
                    if (n != null && !String.valueOf(n).isEmpty()) out.add(String.valueOf(n));
                }
            }
            if (row != null) {
                Object leader = row.get("leaderName");
                if (leader != null && !String.valueOf(leader).isEmpty() && !out.contains(String.valueOf(leader))) {
                    out.add(String.valueOf(leader));
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] 读成员名单失败：" + t);
        }
        return out;
    }

    /** 开关队伍领地共享。只有队长能改，且必须先开「进队验证」。 */
    public String teamLandToggle(String actorName, String teamId, boolean on) {
        if (teamManager == null) return "LinuxTeam 没装，队伍功能不可用";
        Object team = teamObjectById(teamId);
        if (team == null) return "找不到这个队伍";
        java.util.Map<String, Object> row = teamRow(team);
        String tname = String.valueOf(row.get("name"));
        Object leader = row.get("leaderName");
        if (leader == null || !leader.toString().equalsIgnoreCase(actorName)) {
            return "只有队长「" + (leader == null ? "" : leader) + "」能改领地共享";
        }
        boolean applyNeeded = Boolean.TRUE.equals(row.get("applyNeeded"));
        if (on && !applyNeeded) {
            return "「" + tname + "」没开进队验证，先打开「队伍申请」，再开领地共享";
        }
        if (!LandShare.available()) {
            return "服务器没装 Residence（Res 领地），领地共享用不了";
        }
        java.util.List<String> members = teamMemberNames(team);
        int touched;
        try {
            touched = on ? LandShare.grant(members) : LandShare.revoke(members);
        } catch (Throwable t) {
            return "领地共享" + (on ? "开启" : "关闭") + "失败：" + t;
        }
        teamLands().set(String.valueOf(row.get("id")), on);
        return (on ? "已开启" : "已关闭") + "「" + tname + "」的领地共享，"
                + touched + " 处领地已" + (on ? "对队员开放" : "收回队员权限");
    }

    /** 成员变动后重算：开了共享就整队重新铺一遍，关了就只收回这一个人。 */
    private void teamLandResync(Object team) {
        if (teamManager == null || !LandShare.available()) return;
        try {
            java.util.Map<String, Object> row = teamRow(team);
            if (row == null) return;
            if (!teamLands().isOn(String.valueOf(row.get("id")))) return;
            LandShare.grant(teamMemberNames(team));
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] 领地共享重算失败：" + t);
        }
    }

    private void teamLandRevoke(Object team, String who) {
        if (teamManager == null || !LandShare.available()) return;
        try {
            java.util.Map<String, Object> row = teamRow(team);
            if (row == null || !teamLands().isOn(String.valueOf(row.get("id")))) return;
            LandShare.revokePlayer(teamMemberNames(team), who);
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] 领地权限收回失败：" + t);
        }
    }

    /** 某支队伍待处理的申请（只有队长能看）。 */
    public java.util.List<java.util.Map<String, Object>> teamApplications(String teamId) {
        return teamApps().byTeam(teamId);
    }

    /** 队长处理申请：同意就拉进队，拒绝就丢掉。 */
    public String teamDecision(String actorName, String teamId, String applicant, boolean accept) {
        if (teamManager == null) return "LinuxTeam 没装，队伍功能不可用";
        Object team = teamObjectById(teamId);
        if (team == null) return "找不到这个队伍";
        java.util.Map<String, Object> row = teamRow(team);
        String tname = String.valueOf(row.get("name"));
        String leader = String.valueOf(row.get("leaderName"));
        if (!leader.equalsIgnoreCase(actorName)) return "只有队长「" + leader + "」能处理申请";
        if (!teamApps().has(teamId, applicant)) return "这条申请已经处理过了";
        if (!accept) {
            teamApps().remove(teamId, applicant);
            return "已拒绝 " + applicant + " 加入「" + tname + "」";
        }
        String canon = resolveName(applicant);
        Player p = Bukkit.getPlayerExact(canon);
        boolean offline = false;
        if (p == null) {
            p = ghostPlayer(canon);   // 离线也能先批准，进队记录先落盘
            offline = true;
        }
        if (teamHasMember(teamRow(team), applicant)) {
            teamApps().remove(teamId, applicant);
            return applicant + " 已经在队里了";
        }
        try {
            teamJoin.invoke(teamManager, team, p);
            if (teamSave != null) teamSave.invoke(teamManager);
            refreshTag(p);
        } catch (Throwable t) {
            return "同意失败：" + t;
        }
        teamApps().remove(teamId, applicant);
        teamLandResync(team);
        try {
            team.getClass().getMethod("broadcast", String.class)
                    .invoke(team, "§7[§b队伍§7] §f" + applicant + " §7加入了队伍");
        } catch (Throwable ignored) { }
        return "已同意 " + canon + " 加入「" + tname + "」" + (offline ? "（他现在不在线，上线后就在队里）" : "");
    }

    /**
     * 队伍操作。全部走 LinuxTeam 自己的方法，规则（人数上限、队长权限、是否已有队伍）
     * 由它判定，我们只把结果翻译成人话。
     *
     * @param action create / join / leave / kick / disband / transfer
     */
    public String teamAction(String playerName, String action, String arg) {
        return teamAction(playerName, action, arg, null);
    }

    /** 带队伍 id 的版本：多队玩家要能指定操作哪一支。 */
    public String teamAction(String playerName, String action, String arg, String teamId) {
        if (teamManager == null) return "LinuxTeam 没装，队伍功能不可用";
        String a = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
        // 改队标 / 队伍开关 / 踢人 / 转让 / 解散都不碰 Player，离线也能做
        boolean needsPlayer = !("tag".equals(a) || "setting".equals(a)
                || "kick".equals(a) || "transfer".equals(a) || "disband".equals(a));
        Player p = Bukkit.getPlayerExact(playerName);
        if (p == null && needsPlayer) {
            // 建队 / 加入 / 退出只用到 UUID 和名字，用代理顶一下，离线照样能操作
            p = ghostPlayer(playerName);
        }
        // 指定了队伍就按 id 找，否则用主队伍
        Object picked = null;
        if (teamId != null && !teamId.trim().isEmpty()) {
            picked = teamObjectById(teamId.trim());
            if (picked == null) return "找不到这个队伍";
            java.util.Map<String, Object> row = teamRow(picked);
            if (!teamHasMember(row, playerName) && !"join".equals(a)) return "你不在这个队伍里";
        }
        try {
            if ("create".equals(a)) {
                String name = arg == null ? "" : arg.trim();
                if (name.isEmpty()) return "队伍名不能为空";
                if (name.length() > 12) return "队伍名太长了（最多 12 个字）";
                if (teamOfName(playerName) != null) return "你已经有队伍了，先退出再建";
                if (teamByName != null && teamByName.invoke(teamManager, name) != null) return "这个队名已经被占了";
                int max = 10;
                if (teamLimitOf != null) {
                    Object v = teamLimitOf.invoke(teamManager, p.getUniqueId());
                    if (v instanceof Number && ((Number) v).intValue() > 0) max = ((Number) v).intValue();
                }
                Object team = teamCreate.invoke(teamManager, p, name, max);
                if (team == null) return "建队失败：可能队名重复或人数上限";
                if (teamSave != null) teamSave.invoke(teamManager);
                return "已创建队伍「" + name + "」";
            }
            if ("join".equals(a)) {
                String want = arg == null ? "" : arg.trim();
                Object team = picked;   // App 里点「加入」带的是队伍 id，直接用它最稳
                if (team == null && teamByName != null && !want.isEmpty()) team = teamByName.invoke(teamManager, want);
                if (team == null && !want.isEmpty()) team = teamObjectById(want);
                if (team == null) {
                    // 再宽松一次：忽略颜色码、忽略大小写，在全部队伍里找同名的
                    for (Object t : teamObjects()) {
                        String n = str(t.getClass(), t, "getPlainName");
                        if (n == null || n.isEmpty()) n = str(t.getClass(), t, "getName");
                        if (n != null && n.equalsIgnoreCase(want)) { team = t; break; }
                    }
                }
                if (team == null) return "找不到队伍「" + want + "」";
                if (teamJoin == null) return "这个版本的 LinuxTeam 不支持直接加入";
                String tname = str(team.getClass(), team, "getName");
                if (!teamSetting(str(team.getClass(), team, "getId"), team, "public-join", true)) {
                    return "「" + tname + "」需要队长批准：点「申请加入」，等队长处理";
                }
                // LinuxTeam 1.12.2 这版 join(Team, Player) 是 void，拿不到返回值，
                // 所以前置条件自己判断，调完就当成功（以前拿 null 当 false，永远回「加入失败」）
                if (teamHasMember(teamRow(team), playerName)) return "你已经在「" + tname + "」里了";
                try {
                    Object full = team.getClass().getMethod("isFull").invoke(team);
                    if (Boolean.TRUE.equals(full)) return "「" + tname + "」已经满员了";
                } catch (Throwable ignored) {
                }
                if (teamCanJoinMore != null && teamLimitOf != null) {
                    try {
                        Object can = teamCanJoinMore.invoke(teamManager, p.getUniqueId());
                        if (Boolean.FALSE.equals(can)) {
                            Object lim = teamLimitOf.invoke(teamManager, p.getUniqueId());
                            return "你最多只能加 " + lim + " 支队伍，先退一个再加";
                        }
                    } catch (Throwable ignored) {
                    }
                }
                teamJoin.invoke(teamManager, team, p);
                if (teamSave != null) teamSave.invoke(teamManager);
                refreshTag(p);
                teamLandResync(team);
                return "已加入「" + tname + "」";
            }
            if ("leave".equals(a)) {
                Object team = picked != null ? picked : teamOfName(playerName);
                if (team == null) return "你还没有队伍";
                Object ok = (picked != null && teamLeaveOne != null)
                        ? teamLeaveOne.invoke(teamManager, p, team)
                        : teamLeave.invoke(teamManager, p);
                if (!Boolean.TRUE.equals(ok)) return "退出失败：队长要先转让或解散队伍";
                teamLandRevoke(team, playerName);
                if (teamSave != null) teamSave.invoke(teamManager);
                refreshTag(p);
                return "已退出「" + str(team.getClass(), team, "getName") + "」";
            }
            if ("kick".equals(a)) {
                Object team = picked != null ? picked : teamOfName(playerName);
                if (team == null) return "你还没有队伍";
                String target = arg == null ? "" : arg.trim();
                if (target.isEmpty()) return "要踢谁？";
                OfflinePlayer off = Bukkit.getOfflinePlayer(target);
                if (off == null || off.getUniqueId() == null) return "找不到玩家 " + target;
                if (!teamHasName(team, target)) return "「" + target + "」不在这个队伍里";
                Object ok = teamKick.invoke(teamManager, team, off.getUniqueId());
                if (ok instanceof Boolean && !((Boolean) ok).booleanValue() && !isVoid(teamKick)) {
                    return "踢人失败：只有队长能踢";
                }
                teamLandRevoke(team, target);
                if (teamSave != null) teamSave.invoke(teamManager);
                Player tp = Bukkit.getPlayerExact(target);
                if (tp != null) refreshTag(tp);
                return "已把 " + target + " 踢出队伍";
            }
            if ("disband".equals(a)) {
                Object team = picked != null ? picked : teamOfName(playerName);
                if (team == null) return "你还没有队伍";
                Object ok = teamDisband.invoke(teamManager, team);
                if (ok instanceof Boolean && !((Boolean) ok).booleanValue() && !isVoid(teamDisband)) {
                    return "解散失败：只有队长能解散";
                }
                if (teamSave != null) teamSave.invoke(teamManager);
                refreshTag(null);
                return "队伍已解散";
            }
            if ("transfer".equals(a)) {
                Object team = picked != null ? picked : teamOfName(playerName);
                if (team == null) return "你还没有队伍";
                String target = arg == null ? "" : arg.trim();
                OfflinePlayer off = Bukkit.getOfflinePlayer(target);
                if (off == null || off.getUniqueId() == null) return "找不到玩家 " + target;
                if (!teamHasName(team, target)) return "「" + target + "」不在这个队伍里，转让不了";
                Object ok = teamTransfer.invoke(teamManager, team, off.getUniqueId());
                if (ok instanceof Boolean && !((Boolean) ok).booleanValue() && !isVoid(teamTransfer)) {
                    return "转让失败：只有队长能转让";
                }
                if (teamSave != null) teamSave.invoke(teamManager);
                return "队长已转给 " + target;
            }
            if ("invite".equals(a)) {
                Object team = picked != null ? picked : teamOfName(playerName);
                if (team == null) return "你还没有队伍";
                String target = arg == null ? "" : arg.trim();
                if (target.isEmpty()) return "要邀请谁？";
                Player tp = Bukkit.getPlayerExact(target);
                if (tp == null) return target + " 现在不在线，等他在线再邀请";
                if (teamInvite == null) return "这个版本的 LinuxTeam 不支持邀请";
                Object leader = team.getClass().getMethod("getLeader").invoke(team);
                teamInvite.invoke(teamManager, team, p.getUniqueId(), tp, Integer.valueOf(60));
                if (teamSave != null) teamSave.invoke(teamManager);
                return "已邀请 " + target + "（60 秒内在游戏里输入 /team accept 接受）";
            }
            if ("tag".equals(a)) {
                Object team = picked != null ? picked : teamOfName(playerName);
                if (team == null) return "你还没有队伍";
                String tag = arg == null ? "" : arg.trim();
                if (tag.isEmpty()) return "队标不能为空";
                if (tag.length() > 4) return "队标最多 4 个字";
                Object leader = team.getClass().getMethod("getLeader").invoke(team);
                if (!leaderOf(playerName).equals(leader)) return "只有队长能改队标";
                team.getClass().getMethod("setTag", String.class).invoke(team, tag);
                if (teamSave != null) teamSave.invoke(teamManager);
                refreshTag(null);
                return "队标改成「" + tag + "」";
            }
            if ("setting".equals(a)) {
                Object team = picked != null ? picked : teamOfName(playerName);
                if (team == null) return "你还没有队伍";
                // arg 形如 chat:on / chat:off / invite:on
                String[] kv = (arg == null ? "" : arg.trim()).split(":", 2);
                if (kv.length != 2) return "要看哪个开关？";
                String key = kv[0].trim().toLowerCase(java.util.Locale.ROOT);
                if (!"chat".equals(key) && !"invite".equals(key)
                        && !"friendly-fire".equals(key) && !"apply".equals(key)) {
                    return "只支持 chat / invite / friendly-fire / apply 四个开关";
                }
                Object leader = team.getClass().getMethod("getLeader").invoke(team);
                if (!leaderOf(playerName).equals(leader)) return "只有队长能改队伍设置";
                boolean on = "on".equalsIgnoreCase(kv[1].trim()) || "true".equalsIgnoreCase(kv[1].trim());
                // 「队伍申请」开关和 LinuxTeam 的 public-join 是反的：开=需要申请
                String storeKey = "apply".equals(key) ? "public-join" : key;
                boolean storeVal = "apply".equals(key) ? !on : on;
                team.getClass().getMethod("setSetting", String.class, String.class)
                        .invoke(team, storeKey, storeVal ? "true" : "false");
                if (teamSave != null) teamSave.invoke(teamManager);
                String label = "chat".equals(key) ? "队伍聊天"
                        : "friendly-fire".equals(key) ? "队伍伤害"
                        : "apply".equals(key) ? "加入需要申请" : "队伍邀请";
                return label + (on ? "已打开" : "已关闭");
            }
            return "不认识的操作：" + a;
        } catch (Throwable t) {
            plugin.getLogger().warning("[队伍] " + a + " 失败：" + t);
            return "操作失败：" + t.getClass().getSimpleName();
        }
    }

    /**
     * 造一个「影子 Player」：只把 getUniqueId() / getName() 做到位，别的方法一律给安全默认值。
     * <p>LinuxTeam 的 createTeam / join / leave 只用这两个方法，所以离线也能建队、加入、退出；
     * 但千万别把这个对象传给需要真实玩家的地方（比如发消息、传送）。</p>
     */
    private Player ghostPlayer(String name) {
        final java.util.UUID id = Bukkit.getOfflinePlayer(name).getUniqueId();
        final String nm = name;
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                        String m = method.getName();
                        if ("getUniqueId".equals(m)) return id;
                        if ("getName".equals(m)) return nm;
                        if ("isOnline".equals(m)) return Boolean.FALSE;
                        if ("isOp".equals(m)) return Boolean.FALSE;
                        if ("hasPermission".equals(m)) return Boolean.FALSE;
                        if ("equals".equals(m)) return Boolean.valueOf(proxy == (args == null ? null : args[0]));
                        if ("hashCode".equals(m)) return Integer.valueOf(nm.hashCode());
                        if ("toString".equals(m)) return "NalxerGhost(" + nm + ")";
                        if ("getServer".equals(m)) return Bukkit.getServer();
                        Class<?> rt = method.getReturnType();
                        if (rt == boolean.class) return Boolean.FALSE;
                        if (rt == int.class) return Integer.valueOf(0);
                        if (rt == long.class) return Long.valueOf(0L);
                        if (rt == float.class) return Float.valueOf(0f);
                        if (rt == double.class) return Double.valueOf(0d);
                        return null;
                    }
                });
    }

    /** void 方法：调完没抛异常就算成功（LinuxTeam 的 kick / disband / transferLeader 都是 void）。 */
    private static boolean isVoid(java.lang.reflect.Method m) {
        return m != null && m.getReturnType() == void.class;
    }

    /** 服务器上的全部队伍对象。 */
    private java.util.List<Object> teamObjects() {
        java.util.List<Object> list = new java.util.ArrayList<Object>();
        try {
            Object all = teamAll.invoke(teamManager);
            if (all instanceof java.util.Collection) {
                for (Object o : (java.util.Collection<?>) all) list.add(o);
            }
        } catch (Throwable t) {
            // 忽略
        }
        return list;
    }

    /** 队伍里有没有这个人（按名字拿 UUID 再查 members 表）。 */
    private boolean teamHasName(Object team, String name) {
        try {
            Object members = team.getClass().getMethod("getMembers").invoke(team);
            if (!(members instanceof java.util.Map)) return false;
            return ((java.util.Map<?, ?>) members).containsKey(leaderOf(name));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按名字拿 UUID：在线用在线对象，离线用 Bukkit 的离线档案。 */
    private java.util.UUID leaderOf(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    private void refreshTag(Player p) {
        if (teamRefresh == null) return;
        try {
            java.util.List<UUID> ids = new java.util.ArrayList<UUID>();
            if (p != null) {
                ids.add(p.getUniqueId());
            } else {
                for (Player online : Bukkit.getOnlinePlayers()) ids.add(online.getUniqueId());
            }
            teamRefresh.invoke(teamManager, ids);
        } catch (Throwable ignored) {
        }
    }

    // ── LinuxTitle：列表与佩戴 ──────────────────────────────

    public boolean hasTitleApi() {
        return titleManager != null && pdataUnlocked != null;
    }

    /** 某个玩家解锁的头衔 id 集合。 */
    public java.util.Set<String> unlockedTitles(String playerName) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (pdataUnlocked == null) return out;
        try {
            Object raw = pdataUnlocked.invoke(titleData, playerName);
            if (raw instanceof Iterable) {
                for (Object o : (Iterable<?>) raw) {
                    if (o != null) out.add(String.valueOf(o));
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 当前佩戴的头衔 id（空串表示没戴）。 */
    public String activeTitleId(String playerName) {
        if (pdataActive == null) return "";
        try {
            Object v = pdataActive.invoke(titleData, playerName);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读 public 字段，读不到再退回 getter（LinuxTitle 的 TitleDef 是 public 字段）。 */
    private static Object fieldOrDefault(Class<?> c, Object o, String name, Object fallback) {
        try {
            return c.getField(name).get(o);
        } catch (Throwable ignored) {
        }
        try {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            java.lang.reflect.Method m = c.getMethod(getter);
            return m.invoke(o);
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String fieldText(Class<?> c, Object o, String name) {
        Object v = fieldOrDefault(c, o, name, null);
        return v == null ? "" : String.valueOf(v);
    }

    /** 一条头衔的展示信息。 */
    private java.util.Map<String, Object> titleRow(Object def) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        if (def == null) return row;
        try {
            Class<?> c = def.getClass();
            row.put("id", fieldText(c, def, "id"));
            row.put("display", fieldText(c, def, "display"));
            row.put("prefix", fieldText(c, def, "prefix"));
            row.put("conditionType", fieldText(c, def, "conditionType"));
            row.put("statisticType", fieldText(c, def, "statisticType"));
            row.put("festivalId", fieldText(c, def, "festivalId"));
            Object cv = fieldOrDefault(c, def, "conditionValue", null);
            row.put("conditionValue", cv instanceof Number ? ((Number) cv).longValue() : 0L);
            row.put("permission", fieldText(c, def, "permission"));
            Object pr = fieldOrDefault(c, def, "priority", null);
            row.put("priority", pr instanceof Number ? ((Number) pr).intValue() : 0);
        } catch (Throwable ignored) {
        }
        return row;
    }

    /** 已累计的进度（毫秒或次数）；算不出来返回 -1。 */
    private long progressOf(java.util.Map<String, Object> row, String playerName) {
        String type = String.valueOf(row.get("conditionType"));
        if ("playtime".equals(type) || "daily_playtime".equals(type)) {
            org.bukkit.entity.Player p = Bukkit.getPlayerExact(playerName);
            if (p != null && titlePlaytime != null) {
                try {
                    Object v = "playtime".equals(type)
                            ? titlePlaytime.invoke(titleManager, p)
                            : titleTodayPlaytime.invoke(titleManager, p);
                    if (v instanceof Number) return ((Number) v).longValue();
                } catch (Throwable ignored) {
                }
            }
            return -1L;
        }
        return -1L;
    }

    /** 头衔总览：全部头衔 + 该玩家解锁的 + 当前佩戴。 */
    public java.util.Map<String, Object> titleOverview(String playerName) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        java.util.Set<String> unlockedRaw = unlockedTitles(playerName);
        // ★ LinuxTitle 存的是小写 id，titles.yml 里的 id 可能大小写不一样 —— 统一转小写再比
        java.util.Set<String> unlocked = new java.util.LinkedHashSet<String>();
        for (String id : unlockedRaw) unlocked.add(id == null ? "" : id.toLowerCase(java.util.Locale.ROOT));
        String activeId = activeTitleId(playerName);
        String activeLower = activeId == null ? "" : activeId.toLowerCase(java.util.Locale.ROOT);
        java.util.List<java.util.Map<String, Object>> all = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (titleAllTitles != null && titleConfig != null) {
            try {
                Object raw = titleAllTitles.invoke(titleConfig);
                if (raw instanceof Iterable) {
                    for (Object def : (Iterable<?>) raw) {
                        if (def == null) continue;
                        java.util.Map<String, Object> row = titleRow(def);
                        String rowId = String.valueOf(row.get("id"));
                        String rowLower = rowId.toLowerCase(java.util.Locale.ROOT);
                        row.put("unlocked", unlocked.contains(rowLower));
                        row.put("progress", Long.valueOf(progressOf(row, playerName)));
                        row.put("active", !activeLower.isEmpty() && rowLower.equals(activeLower));
                        all.add(row);
                    }
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[头衔] 读取全部头衔失败：" + t);
            }
        }
        sortTitles(all);
        out.put("active", activeId);
        out.put("unlockedCount", unlocked.size());
        out.put("total", all.size());
        out.put("titles", all);
        Object activeDef = null;
        for (java.util.Map<String, Object> row : all) {
            if (Boolean.TRUE.equals(row.get("active"))) {
                activeDef = row;
                break;
            }
        }
        out.put("activeTitle", activeDef);
        return out;
    }

    /**
     * 只靠名字读「当前佩戴的头衔」，不需要 Player 对象。
     * <p>
     * 原来 /api/me 走 {@code getActiveTitleDef(Player)}：离线玩家没有 Player →
     * 明明戴着「元老」，App 里却什么都不显示。这里改成读 PlayerDataManager 的
     * active id，再用 TitleConfig 查 display / prefix，离线在线一个结果。
     */
    public java.util.Map<String, Object> activeTitleRow(String playerName) {
        String id = activeTitleId(playerName);
        if (id == null || id.isEmpty()) return null;
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        out.put("id", id);
        out.put("display", "");
        out.put("prefix", "");
        if (titleConfig != null && titleConfigGet != null) {
            try {
                Object def = titleConfigGet.invoke(titleConfig, id);
                if (def != null) {
                    Object display = fieldOrDefault(def.getClass(), def, "display", "");
                    Object prefix = fieldOrDefault(def.getClass(), def, "prefix", "");
                    out.put("display", display == null ? "" : String.valueOf(display));
                    out.put("prefix", prefix == null ? "" : String.valueOf(prefix));
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("[头衔] 读佩戴头衔失败：" + t);
            }
        }
        return out;
    }

    /** 佩戴 / 卸下头衔。id 为空表示卸下。 */
    public String equipTitle(String playerName, String id) {
        if (titleManager == null || titleSetActive == null) return "LinuxTitle 没装，头衔功能不可用";
        String target = id == null ? "" : id.trim();
        if (!target.isEmpty()) {
            boolean has = false;
            for (String owned : unlockedTitles(playerName)) {
                if (owned != null && owned.equalsIgnoreCase(target)) { has = true; break; }
            }
            if (!has) return "你还没解锁这个头衔";
        }
        try {
            Object ok = titleSetActive.invoke(titleManager, playerName, target);
            if (!Boolean.TRUE.equals(ok)) return "佩戴失败：没解锁、或者没有权限";
            return target.isEmpty() ? "已卸下头衔" : "已佩戴这个头衔，进服/重进后可见";
        } catch (Throwable t) {
            plugin.getLogger().warning("[头衔] 佩戴失败：" + t);
            return "佩戴失败：" + t.getClass().getSimpleName();
        }
    }

    // ── ITNOL：禁言判定 ────────────────────────────────────

    public boolean hasMuteApi() {
        return penalty != null && penaltyBannedReadOnly != null;
    }

    /** 该玩家是否被禁言（App 发聊天前查一次）。 */
    public boolean isMuted(String playerName) {
        if (!hasMuteApi()) return false;
        try {
            Object r = penaltyBannedReadOnly.invoke(penalty, typeBanMsg, playerName);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 某个玩家当前是否在封禁名单里（名字不区分大小写）。
     */
    public boolean isBanned(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        try {
            for (String[] e : banList()) {
                if (e[0] != null && e[0].equalsIgnoreCase(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 读不到就当没封
        }
        return false;
    }

    /**
     * 当前封禁列表，每项 {player, reason, operator, expire, time}。
     * 结构由 InTheNameOfLinux 的 BanManager.BanEntry 提供（public 字段）。
     */
    public List<String[]> banList() {
        if (banManager == null || getBanList == null) {
            return Collections.emptyList();
        }
        List<String[]> out = new ArrayList<String[]>();
        try {
            Object raw = getBanList.invoke(banManager);
            if (!(raw instanceof Iterable)) {
                return out;
            }
            for (Object e : (Iterable<?>) raw) {
                String player = str(e, "player");
                String reason = str(e, "reason");
                String operator = str(e, "operator");
                String expire = str(e, "expire");
                String time = str(e, "time");
                out.add(new String[]{player, reason, operator, expire, time});
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[封禁] 读取封禁列表失败：" + t);
        }
        return out;
    }

    /** InTheNameOfLinux 记下的所有玩家名（用于管理页搜索） */
    public List<String> knownNames() {
        List<String> out = new ArrayList<String>();
        if (banManager != null && getKnownNames != null) {
            try {
                Object raw = getKnownNames.invoke(banManager);
                if (raw instanceof Iterable) {
                    for (Object o : (Iterable<?>) raw) {
                        if (o != null) {
                            out.add(String.valueOf(o));
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 拿不到就只返回在线玩家
            }
        }
        return out;
    }

    private static String str(Class<?> c, Object target, String method) {
        try {
            Object v = c.getMethod(method).invoke(target);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String str(Object o, String field) {        try {
            Field f = o.getClass().getField(field);
            Object v = f.get(o);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable t) {
            return "";
        }
    }

    // ============================================================
    // 举报（1.3.0）
    // ============================================================

    private void bindReports() {
        try {
            Class<?> inl = Class.forName("com.inthenameoflinux.InTheNameOfLinux");
            Object pluginInst = Bukkit.getPluginManager().getPlugin("InTheNameOfLinux");
            if (pluginInst == null) throw new IllegalStateException("ITNOL 没加载");
            reportManager = inl.getMethod("getReportManager").invoke(pluginInst);
            if (reportManager == null) throw new IllegalStateException("ReportManager 为 null");

            reportClass = Class.forName("com.inthenameoflinux.ReportManager$Report");
            rmList = reportManager.getClass().getMethod("listReportsNewestFirst");
            rmGet = reportManager.getClass().getMethod("getReport", int.class);
            rmCreateByName = reportManager.getClass().getMethod("createReportByName",
                    String.class, String.class, String.class, String[].class);
            rmAccept = reportManager.getClass().getMethod("acceptReport", CommandSender.class, int.class, String.class);
            rmReject = reportManager.getClass().getMethod("rejectReport", CommandSender.class, int.class, String.class);
            plugin.getLogger().info("[联动] 已接上 ITNOL 举报（列表 / 提交 / 接受 / 驳回）");
        } catch (Throwable t) {
            reportManager = null;
            plugin.getLogger().warning("[联动] 没接上 ITNOL 举报：" + t.getClass().getSimpleName());
        }
    }

    public boolean hasReports() {
        if (useLocal()) return true;
        return itnol != null && itnol.getReportManager() != null;
    }

    /** 举报列表；pendingOnly 只返回没处理的，reporter 非空只返回该玩家提交的。 */
    public java.util.List<java.util.Map<String, Object>> reports(boolean pendingOnly, String reporter) {
        if (useLocal()) return local.reports(pendingOnly, reporter);
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
        ReportManager rm = itnol == null ? null : itnol.getReportManager();
        if (rm == null) return out;
        for (ReportManager.Report r : rm.listReportsNewestFirst()) {
            if (r == null) continue;
            if (pendingOnly && r.handled) continue;
            if (reporter != null && !reporter.isEmpty() && !r.reporter.equalsIgnoreCase(reporter)) continue;
            out.add(reportRow(r));
        }
        return out;
    }

    /** 单条举报。 */
    public java.util.Map<String, Object> report(int id) {
        if (useLocal()) return local.report(id);
        ReportManager rm = itnol == null ? null : itnol.getReportManager();
        if (rm == null) return null;
        ReportManager.Report r = rm.getReport(id);
        return r == null ? null : reportRow(r);
    }

    private java.util.Map<String, Object> reportRow(ReportManager.Report r) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("id", Integer.valueOf(r.id));
        row.put("reporter", r.reporter == null ? "" : r.reporter);
        row.put("target", r.target == null ? "" : r.target);
        row.put("reason", r.reason == null ? "" : r.reason);
        row.put("time", Long.valueOf(r.time));
        row.put("handled", Boolean.valueOf(r.handled));
        row.put("handler", r.handler == null ? "" : r.handler);
        row.put("result", r.result == null ? "" : r.result);
        row.put("note", r.note == null ? "" : r.note);
        return row;
    }

    /** App 提交举报，返回给玩家看的一句话。 */
    /** App 提交举报，返回给玩家看的一句话。 */
    public String createReport(String reporter, String target, String reason) {
        if (useLocal()) return local.createReport(reporter, target, reason);
        ReportManager rm = itnol == null ? null : itnol.getReportManager();
        if (rm == null) return "服务器没接上举报系统（ITNOL 没装或版本太旧）";
        String[] err = new String[1];
        ReportManager.Report r = rm.createReportByName(reporter, target, reason, err);
        if (r == null) return err[0] == null ? "提交失败" : err[0];
        return "举报已提交，编号 #" + r.id + "，处理结果会在这里显示";
    }

    /** 处理举报；accept=false 表示驳回。返回给管理员看的一句话。 */
    /** 处理举报；accept=false 表示驳回。返回给管理员看的一句话。 */
    public String handleReport(int id, boolean accept, String note, String handlerName) {
        if (useLocal()) return local.handleReport(id, accept, note, appActor(handlerName));
        ReportManager rm = itnol == null ? null : itnol.getReportManager();
        if (rm == null) return "服务器没接上举报系统";
        CollectSender sink = new CollectSender(appActor(handlerName));
        boolean ok = accept
                ? rm.acceptReport(sink, id, note == null ? "" : note)
                : rm.rejectReport(sink, id, note == null ? "" : note);
        String msg = sink.plain();
        if (!msg.isEmpty()) return msg;
        return ok ? (accept ? "已接受这条举报" : "已驳回这条举报") : "处理失败";
    }

    /** App 里做的操作，记录里统一显示「APP端（用户名）」。 */
    public static String appActor(String name) {
        String n = name == null ? "" : name.trim();
        return n.isEmpty() ? "APP端" : "APP端（" + n + "）";
    }

    // ============================================================
    // 惩罚（ITNOL LinuxPenalty）
    // ============================================================

    /** 全部惩罚记录。 */
    public java.util.List<java.util.Map<String, Object>> penalties() {
        if (useLocal()) return local.penalties();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
        LinuxPenalty lp = itnol == null ? null : itnol.getPenaltyManager();
        if (lp == null) return out;
        for (java.util.Map<String, String> m : lp.getAllPenalties()) {
            if (m == null) continue;
            out.add(new java.util.LinkedHashMap<String, Object>(m));
        }
        return out;
    }

    /** 某人的惩罚情况（banmsg / banmove / banblock）。 */
    public java.util.Map<String, Object> penaltiesOf(String playerName) {
        if (useLocal()) return local.penaltiesOf(playerName);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        LinuxPenalty lp = itnol == null ? null : itnol.getPenaltyManager();
        if (lp == null) return out;
        java.util.Map<String, java.util.Map<String, Object>> raw = lp.getAllRecords(playerName);
        if (raw != null) out.putAll(raw);
        return out;
    }

    /**
     * 上惩罚。type 取 banmsg / banmove / banblock / all，minutes<=0 表示永久。
     */
    /** ITNOL 的保护对象（protected-player）：对它施加功能惩罚会被 ITNOL 静默拒绝，App 得先说清楚。 */
    public boolean isProtectedTarget(String playerName, String operator) {
        try {
            String target = com.inthenameoflinux.PluginConfig.protectedPlayer;
            if (target == null || target.trim().isEmpty() || playerName == null) return false;
            if (operator != null && operator.trim().equalsIgnoreCase(target.trim())) return false;
            return target.trim().equalsIgnoreCase(playerName.trim());
        } catch (Throwable t) {
            return false;
        }
    }

    public String penalize(String type, String playerName, int minutes, String reason, String operator) {
        String t0 = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        String why = reason == null ? "" : reason;
        String dur = minutes <= 0 ? "永久" : minutes + " 分钟";
        long duration = minutes <= 0 ? -1L : minutes * 60_000L;

        String who = appActor(operator);
        if (isProtectedTarget(playerName, operator)) {
            return "「" + playerName + "」是服务器的保护对象，ITNOL 会拦住这类惩罚，App 里不给执行";
        }
        if (itnol == null) {
            long until = minutes <= 0 ? 0L : System.currentTimeMillis() + minutes * 60_000L;
            if ("ban".equals(t0) || "tempban".equals(t0)) {
                local.ban(playerName, until, why);
                return "已封禁 " + playerName + "（" + dur + "）";
            }
            if ("banmsg".equals(t0)) {
                local.mute(playerName, until);
                return "已对 " + playerName + " 开 禁言（" + dur + "）";
            }
            return "服务器没装 InTheNameOfLinux，只能禁言和封禁";
        }
        BanManager bm = itnol.getBanManager();
        if ("ban".equals(t0) || "tempban".equals(t0)) {
            if (bm == null) return "服务器没接上封禁系统（ITNOL 没装）";
            CollectSender sink = new CollectSender(who);
            boolean ok;
            if (minutes <= 0) {
                ok = bm.banPlayer(sink, playerName, why);
            } else {
                ok = bm.tempBanPlayer(sink, playerName, duration, why);
            }
            String msg = sink.plain();
            if (ok) {
                String extra = msg == null || msg.trim().isEmpty() ? "" : "：" + msg.trim();
                return "已封禁 " + playerName + "（" + dur + "）" + extra;
            }
            return msg == null || msg.trim().isEmpty() ? ("封禁 " + playerName + " 失败") : msg;
        }

        LinuxPenalty lp = itnol.getPenaltyManager();
        if (lp == null) return "服务器没接上惩罚系统（ITNOL 没装）";
        if ("banmsg".equals(t0)) {
            lp.banMsg(who, playerName, duration, why);
            return "已对 " + playerName + " 开 禁言（" + dur + "）";
        }
        if ("banmove".equals(t0)) {
            lp.banMove(who, playerName, duration, why);
            return "已对 " + playerName + " 开 禁移动（" + dur + "）";
        }
        if ("banblock".equals(t0)) {
            lp.banBlock(who, playerName, duration, why);
            return "已对 " + playerName + " 开 禁破坏方块（" + dur + "）";
        }
        if ("all".equals(t0) || "banall".equals(t0)) {
            lp.banAll(who, playerName, duration, why);
            return "已对 " + playerName + " 开 全部惩罚（" + dur + "）";
        }
        return "不认识的惩罚类型：" + type;
    }

    /** 解除某类惩罚（老入口，没有执行人）。 */
    public String releasePenalty(String type, String playerName) {
        return releasePenalty(type, playerName, null);
    }

    /** 解除某类惩罚；operator 是 App 里点的人，记录里显示「APP端（用户名）」。 */
    public String releasePenalty(String type, String playerName, String operator) {
        String t0 = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        if (itnol == null) {
            if ("ban".equals(t0) || "tempban".equals(t0)) {
                return local.unban(playerName) ? ("已解封 " + playerName) : (playerName + " 没有被封禁");
            }
            if ("banmsg".equals(t0)) {
                return local.unmute(playerName) ? ("已解除 " + playerName + " 的禁言") : ("这人没有被禁言");
            }
            return "服务器没装 InTheNameOfLinux，只能解禁言和解封";
        }
        BanManager bm = itnol.getBanManager();
        if ("ban".equals(t0) || "tempban".equals(t0)) {
            if (bm == null) return "服务器没接上封禁系统（ITNOL 没装）";
            CollectSender sink = new CollectSender(appActor(operator));
            boolean ok = bm.unbanPlayer(sink, playerName);
            String msg = sink.plain();
            if (ok) {
                String extra = msg == null || msg.trim().isEmpty() ? "" : "：" + msg.trim();
                return "已解封 " + playerName + extra;
            }
            return msg == null || msg.trim().isEmpty() ? ("解封 " + playerName + " 失败") : msg;
        }
        LinuxPenalty lp = itnol.getPenaltyManager();
        if (lp == null) return "服务器没接上惩罚系统";
        boolean ok;
        String cn;
        if ("banmsg".equals(t0)) {
            ok = lp.unbanMsg(playerName); cn = "禁言";
        } else if ("banmove".equals(t0)) {
            ok = lp.unbanMove(playerName); cn = "禁移动";
        } else if ("banblock".equals(t0)) {
            ok = lp.unbanBlock(playerName); cn = "禁破坏方块";
        } else if ("all".equals(t0) || "banall".equals(t0)) {
            ok = lp.unbanAll(playerName);
            if (!ok) ok = lp.releaseRecord("banall", playerName);
            if (!ok) ok = lp.releaseRecord("all", playerName);
            cn = "全部惩罚";
        } else {
            ok = lp.releaseRecord(t0, playerName); cn = "惩罚";
        }
        return ok ? ("已解除 " + playerName + " 的" + cn) : "这人没有被" + cn;
    }

    // ============================================================
    // 假人（FakePlayerPlugin）
    // ============================================================

    private void bindFakes() {
        try {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin("FakePlayerPlugin");
            if (p == null) throw new IllegalStateException("没装 FakePlayerPlugin");
            fakePlugin = p;
            fakeList = p.getClass().getMethod("getFakePlayers");
            fakeInfo = method(p.getClass(), "getFakePlayerInfo", String.class);
            fakeSaved = method(p.getClass(), "getSavedLocations");
            plugin.getLogger().info("[联动] 已接上 FakePlayerPlugin 假人（列表 / 召唤 / 移除 / 启用停用）");
        } catch (Throwable t) {
            fakePlugin = null;
            plugin.getLogger().warning("[联动] 没接上假人管理：" + t.getClass().getSimpleName());
        }
    }

    public boolean hasFakes() {
        return fakePlugin != null && fakeList != null;
    }

    public java.util.List<java.util.Map<String, Object>> fakes() {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
        if (!hasFakes()) return out;
        try {
            Object raw = fakeList.invoke(fakePlugin);
            if (!(raw instanceof java.util.Map)) return out;
            for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) raw).entrySet()) {
                Object fp = e.getValue();
                if (fp == null) continue;
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                row.put("name", String.valueOf(e.getKey()));
                try {
                    Object uuid = fp.getClass().getMethod("getUUID").invoke(fp);
                    row.put("uuid", uuid == null ? "" : String.valueOf(uuid));
                    Object world = fp.getClass().getMethod("getWorldName").invoke(fp);
                    row.put("world", world == null ? "" : String.valueOf(world));
                    Object active = fp.getClass().getMethod("isActive").invoke(fp);
                    row.put("active", active);
                    if (fakeInfo != null) {
                        try {
                            Object info = fakeInfo.invoke(fakePlugin, String.valueOf(e.getKey()));
                            if (info != null) {
                                Object creator = info.getClass().getMethod("getCreator").invoke(info);
                                row.put("creator", creator == null ? "" : String.valueOf(creator));
                                Object created = info.getClass().getMethod("getCreateTime").invoke(info);
                                row.put("createTime", created instanceof Number
                                        ? Long.valueOf(((Number) created).longValue()) : Long.valueOf(0L));
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    // 这个假人整体是开还是关（关掉的不在世界里，但记录还在）
                    try {
                        Object enabled = fakePlugin.getClass().getMethod("isFakeEnabled", String.class)
                                .invoke(fakePlugin, String.valueOf(e.getKey()));
                        row.put("enabled", enabled);
                        Object running = fakePlugin.getClass().getMethod("isFakeLoaded", String.class)
                                .invoke(fakePlugin, String.valueOf(e.getKey()));
                        row.put("running", running);
                    } catch (Throwable ignored) {
                    }
                    // 每个假人自己的开关：区块常驻（加载）、刷怪、只刷黑屋
                    try {
                        Object loaded = fakePlugin.getClass().getMethod("isFakeChunkLoaded", String.class)
                                .invoke(fakePlugin, String.valueOf(e.getKey()));
                        row.put("loaded", loaded);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object spawn = fakePlugin.getClass().getMethod("isFakeMobSpawning", String.class)
                                .invoke(fakePlugin, String.valueOf(e.getKey()));
                        row.put("mobSpawning", spawn);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object covered = fakePlugin.getClass().getMethod("isFakeRequireCovered", String.class)
                                .invoke(fakePlugin, String.valueOf(e.getKey()));
                        row.put("requireCovered", covered);
                    } catch (Throwable ignored) {
                    }
                    Object loc = fp.getClass().getMethod("getLocation").invoke(fp);
                    if (loc instanceof org.bukkit.Location) {
                        org.bukkit.Location l = (org.bukkit.Location) loc;
                        row.put("x", Integer.valueOf(l.getBlockX()));
                        row.put("y", Integer.valueOf(l.getBlockY()));
                        row.put("z", Integer.valueOf(l.getBlockZ()));
                    }
                } catch (Throwable ignored) {
                }
                out.add(row);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("[假人] 读列表失败：" + t);
        }
        // 停用的假人不在 getFakePlayers() 里，从 savedLocations 补出来，
        // 不然 App 上根本看不到这个假人，也就没法再启用它
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (java.util.Map<String, Object> row : out) {
            seen.add(String.valueOf(row.get("name")).toLowerCase(java.util.Locale.ROOT));
        }
        try {
            Object saved = fakeSaved == null ? null : fakeSaved.invoke(fakePlugin);
            if (saved instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) saved).entrySet()) {
                    String nm = String.valueOf(e.getKey());
                    if (seen.contains(nm.toLowerCase(java.util.Locale.ROOT))) continue;
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
                    row.put("name", nm);
                    row.put("uuid", "");
                    row.put("active", Boolean.FALSE);
                    row.put("enabled", Boolean.FALSE);
                    row.put("running", Boolean.FALSE);
                    Object v = e.getValue();
                    if (v instanceof org.bukkit.Location) {
                        org.bukkit.Location l = (org.bukkit.Location) v;
                        org.bukkit.World w = l.getWorld();
                        row.put("world", w == null ? "" : w.getName());
                        row.put("x", Integer.valueOf(l.getBlockX()));
                        row.put("y", Integer.valueOf(l.getBlockY()));
                        row.put("z", Integer.valueOf(l.getBlockZ()));
                    }
                    out.add(row);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 假人插件里那几个「设置项」（就是 config.yml 的 spawn 段）。 */
    public java.util.Map<String, Object> fakeSettings() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<String, Object>();
        if (!hasFakes()) return out;
        try {
            Object cfg = fakePlugin.getClass().getMethod("getConfig").invoke(fakePlugin);
            java.lang.reflect.Method getString = cfg.getClass().getMethod("getString", String.class);
            java.lang.reflect.Method getBool = cfg.getClass().getMethod("getBoolean", String.class);
            Object gamemode = getString.invoke(cfg, "spawn.gamemode");
            Object invuln = getBool.invoke(cfg, "spawn.invulnerable");
            out.put("gamemode", gamemode == null ? "CREATIVE" : String.valueOf(gamemode));
            out.put("invulnerable", Boolean.valueOf(Boolean.TRUE.equals(invuln)));
            Object prefix = fakePlugin.getClass().getMethod("getFakeNamePrefix").invoke(fakePlugin);
            out.put("prefix", prefix == null ? "" : String.valueOf(prefix));
        } catch (Throwable t) {
            plugin.getLogger().warning("[假人] 读设置失败：" + t);
        }
        return out;
    }

    /** 改假人设置：gamemode（CREATIVE/SURVIVAL/ADVENTURE/SPECTATOR）或 invulnerable（true/false）。 */
    public String fakeSet(String key, String value) {
        if (!hasFakes()) return "服务器没装 FakePlayerPlugin";
        String k = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        String v = value == null ? "" : value.trim();
        if (!"gamemode".equals(k) && !"invulnerable".equals(k)) {
            return "只能设置 gamemode 或 invulnerable";
        }
        if ("gamemode".equals(k)) {
            String up = v.toUpperCase(java.util.Locale.ROOT);
            if (!up.equals("CREATIVE") && !up.equals("SURVIVAL")
                    && !up.equals("ADVENTURE") && !up.equals("SPECTATOR")) {
                return "游戏模式只能是 CREATIVE / SURVIVAL / ADVENTURE / SPECTATOR";
            }
            v = up;
        } else {
            if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                return "invulnerable 只能是 true 或 false";
            }
            v = v.toLowerCase(java.util.Locale.ROOT);
        }
        try {
            Object cfg = fakePlugin.getClass().getMethod("getConfig").invoke(fakePlugin);
            cfg.getClass().getMethod("set", String.class, Object.class).invoke(cfg, "spawn." + k, v);
            fakePlugin.getClass().getMethod("saveConfig").invoke(fakePlugin);
            return "已把假人的 " + k + " 设为 " + v + "（新召唤的假人生效，已有的会重生）";
        } catch (Throwable t) {
            return "改设置失败：" + t.getMessage();
        }
    }


    /** 单个假人的开关：load=区块常驻、spawn=模拟刷怪、covered=只刷黑屋。 */
    public String fakeToggle(String name, String key, boolean on) {
        if (name == null || name.trim().isEmpty()) return "要设置哪个假人？";
        if (!hasFakes()) return "服务器没装 FakePlayerPlugin";
        String who = name.trim();
        String k = key == null ? "" : key.trim().toLowerCase(java.util.Locale.ROOT);
        String method;
        String label;
        if ("enabled".equals(k) || "enable".equals(k) || "on".equals(k) || "active".equals(k)) {
            // 停用 = 把假人从服务器上撤下来（实体销毁、不刷怪），坐标和各项开关都留着
            try {
                Object changed = fakePlugin.getClass().getMethod("setFakeEnabled", String.class, boolean.class)
                        .invoke(fakePlugin, who, Boolean.valueOf(on));
                if (!Boolean.TRUE.equals(changed)) {
                    Object now = fakePlugin.getClass().getMethod("isFakeEnabled", String.class)
                            .invoke(fakePlugin, who);
                    if (Boolean.valueOf(on).equals(now)) return who + " 现在已经是" + (on ? "启用" : "停用") + "状态";
                    return "找不到假人 " + who;
                }
                return "已" + (on ? "启用" : "停用") + "假人 " + who
                        + (on ? "，它已经回到原来的位置" : "，服务器上不再加载它（记录还在，随时能再打开）");
            } catch (Throwable t) {
                return "设置失败：" + t.getMessage();
            }
        }
        if ("load".equals(k) || "loaded".equals(k) || "chunk".equals(k)) {
            method = "setFakeChunkLoaded";
            label = "区块常驻";
        } else if ("spawn".equals(k) || "mobspawning".equals(k) || "mob".equals(k)) {
            method = "setFakeMobSpawning";
            label = "模拟刷怪";
        } else if ("covered".equals(k) || "requirecovered".equals(k)) {
            method = "setFakeRequireCovered";
            label = "只刷黑屋";
        } else {
            return "只能设置 load / spawn / covered";
        }
        try {
            fakePlugin.getClass().getMethod(method, String.class, boolean.class)
                    .invoke(fakePlugin, who, Boolean.valueOf(on));
            return "已把 " + who + " 的" + label + (on ? "打开" : "关掉");
        } catch (Throwable t) {
            return "设置失败：" + t.getMessage();
        }
    }

    /** 让假人重生一次（换皮肤 / 应用新的游戏模式）。 */
    public String fakeRespawn(String name) {
        if (name == null || name.trim().isEmpty()) return "要重生哪个假人？";
        if (!hasFakes()) return "服务器没装 FakePlayerPlugin";
        try {
            Object raw = fakeList.invoke(fakePlugin);
            if (raw instanceof java.util.Map) {
                for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) raw).entrySet()) {
                    if (!name.equalsIgnoreCase(String.valueOf(e.getKey()))) continue;
                    Object fp = e.getValue();
                    if (fp == null) break;
                    fp.getClass().getMethod("respawn").invoke(fp);
                    return "已让假人 " + e.getKey() + " 重生";
                }
            }
            return "找不到假人 " + name;
        } catch (Throwable t) {
            return "重生失败：" + t.getMessage();
        }
    }

    /** 召一个假人到自己脚下；成功返回名字。 */
    public String spawnFake(String rawName, org.bukkit.entity.Player creator) {
        if (!hasFakes()) return "服务器没装 FakePlayerPlugin";
        if (creator == null) return "召唤假人要在游戏里（或者用命令控制台）";
        try {
            Object ok = fakePlugin.getClass().getMethod("createFakePlayer",
                    String.class, org.bukkit.Location.class, org.bukkit.entity.Player.class)
                    .invoke(fakePlugin, rawName, creator.getLocation(), creator);
            return Boolean.TRUE.equals(ok) ? ("已召唤假人 " + rawName) : "召唤失败（名字不合规或已存在）";
        } catch (Throwable t) {
            return "召唤假人出错：" + t.getMessage();
        }
    }

    /** 移除假人。 */
    public String removeFake(String name) {
        if (name == null || name.trim().isEmpty()) return "要移除哪个假人？";
        boolean exists = false;
        for (java.util.Map<String, Object> row : fakes()) {
            if (name.equalsIgnoreCase(String.valueOf(row.get("name")))) {
                exists = true;
                break;
            }
        }
        if (!exists) return "找不到假人 " + name;
        if (!hasFakes()) return "服务器没装 FakePlayerPlugin";
        try {
            fakePlugin.getClass().getMethod("removeFakePlayer", String.class).invoke(fakePlugin, name);
            return "已移除假人 " + name;
        } catch (Throwable t) {
            return "移除失败：" + t.getMessage();
        }
    }

    // ============================================================
    // 插件状态 & 玩家搜索
    // ============================================================

    /** Linux 系插件 + 关键前置的加载状态与版本。 */
    public java.util.List<java.util.Map<String, Object>> pluginStatus() {
        String[][] want = {
                {"InTheNameOfLinux", "ITNOL"},
                {"AuthMe", "登录"},
                {"Essentials", "基础功能"},
                {"Vault", "经济桥"},
                {"PlayerPoints", "点券"},
                {"LinuxTeam", "队伍"},
                {"LinuxTitle", "头衔"},
                {"LinuxTAB", "TAB 列表"},
                {"LinuxChestGUI", "箱子面板"},
                {"LinuxACE", "反作弊"},
                {"FakePlayerPlugin", "假人"},
        };
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (String[] pair : want) {
            org.bukkit.plugin.Plugin p = Bukkit.getPluginManager().getPlugin(pair[0]);
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
            row.put("name", pair[0]);
            row.put("label", pair[1]);
            row.put("loaded", Boolean.valueOf(p != null && p.isEnabled()));
            row.put("version", p == null ? "" : p.getDescription().getVersion());
            out.add(row);
        }
        return out;
    }

    /**
     * 协管：XlandCore 的 moderators 名单 → NalxerApp 自己的 admin.moderators →
     * 游戏里带 nalxerapp.moderator 权限的人。
     */
    /** 这个玩家在服务器上有没有某个权限节点。
     *  在线的直接问 Bukkit；离线的走 LuckPerms 的缓存（很多人是离线用 App 的）。 */
    private final java.util.Map<String, Long> permCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Boolean> permCacheValue = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean hasPerm(String name, String node) {
        if (name == null || node == null) return false;
        String key = name.toLowerCase(java.util.Locale.ROOT) + '|' + node;
        Long at = permCache.get(key);
        long now = System.currentTimeMillis();
        if (at != null && now - at < 60000L) {
            Boolean cached = permCacheValue.get(key);
            return cached != null && cached;
        }
        boolean value = hasPermUncached(name, node);
        permCache.put(key, now);
        permCacheValue.put(key, Boolean.valueOf(value));
        return value;
    }

    private boolean hasPermUncached(String name, String node) {
        if (name == null || name.isEmpty() || node == null || node.isEmpty()) return false;
        String fixed = resolveName(name);
        if (fixed == null || fixed.isEmpty()) fixed = name;
        org.bukkit.entity.Player p = Bukkit.getPlayerExact(fixed);
        if (p != null) {
            try {
                return p.hasPermission(node) || p.isOp();
            } catch (Throwable t) {
                return false;
            }
        }
        return offlinePerm(fixed, node);
    }

    /** 离线玩家：用 LuckPerms 的缓存数据判断；没有 LuckPerms 就返回 false。 */
    private boolean offlinePerm(String name, String node) {
        try {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(name);
            if (op == null || op.getUniqueId() == null) return false;
            Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object lp = provider.getMethod("get").invoke(null);
            Object users = lp.getClass().getMethod("getUserManager").invoke(lp);
            Object user = users.getClass().getMethod("getUser", java.util.UUID.class).invoke(users, op.getUniqueId());
            if (user == null) return false;
            Object data = user.getClass().getMethod("getCachedData").invoke(user);
            Object perms = data.getClass().getMethod("getPermissionData").invoke(data);
            Object tri = perms.getClass().getMethod("checkPermission", String.class).invoke(perms, node);
            Object val = tri.getClass().getMethod("asBoolean").invoke(tri);
            return Boolean.TRUE.equals(val);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isModerator(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String want = name.trim();
        for (String n : xlandNames("moderators", "moderator")) {
            if (n.equalsIgnoreCase(want)) {
                return true;
            }
        }
        for (String n : plugin.getConfig().getStringList("admin.moderators")) {
            if (n != null && n.trim().equalsIgnoreCase(want)) {
                return true;
            }
        }
        try {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(want);
            return op.isOnline() && op.getPlayer() != null
                    && op.getPlayer().hasPermission("nalxerapp.moderator");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** XlandCore 配置里的名单（列表键 + 兼容的单值键）。 */
    private java.util.List<String> xlandNames(String listKey, String singleKey) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        try {
            if (xlandCore != null) {
                java.util.List<String> list = ((Plugin) xlandCore).getConfig().getStringList(listKey);
                if (list != null) for (String n : list) if (n != null && !n.trim().isEmpty()) out.add(n.trim());
                if (out.isEmpty() && singleKey != null) {
                    String one = ((Plugin) xlandCore).getConfig().getString(singleKey, "");
                    if (one != null && !one.trim().isEmpty()) out.add(one.trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 管理层名单：服主 / 副服主 / 管理员（OP）/ 协管，**在不在线都列**。
     * 排序：先按身份等级（服主 1 &gt; 副服主 2 &gt; 管理员 3 &gt; 协管 4），同级在线优先，再按名字。
     */
    public java.util.List<java.util.Map<String, Object>> staffList() {
        java.util.LinkedHashMap<String, Integer> ranks = new java.util.LinkedHashMap<String, Integer>();
        java.util.LinkedHashMap<String, String> roles = new java.util.LinkedHashMap<String, String>();
        java.util.LinkedHashMap<String, String> names = new java.util.LinkedHashMap<String, String>();

        addStaff(names, ranks, roles, xlandNames("owners", "owner"), 1, "owner", "服主");
        addStaff(names, ranks, roles, xlandNames("co-owners", "co-owner"), 2, "coowner", "副服主");
        // 管理员 = OP（XlandCore 没有单独的 admin 名单，isAdmin 是权限判断）
        java.util.List<String> ops = new java.util.ArrayList<String>();
        try {
            for (OfflinePlayer op : Bukkit.getOperators()) {
                if (op != null && op.getName() != null && !op.getName().trim().isEmpty()) ops.add(op.getName().trim());
            }
        } catch (Throwable ignored) {
        }
        addStaff(names, ranks, roles, ops, 3, "admin", "管理员");
        addStaff(names, ranks, roles, xlandNames("moderators", null), 4, "moderator", "协管");

        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (java.util.Map.Entry<String, Integer> e : ranks.entrySet()) {
            String key = e.getKey();
            String name = names.get(key);
            boolean online = false;
            try {
                org.bukkit.entity.Player p = Bukkit.getPlayerExact(name);
                online = p != null && p.isOnline();
            } catch (Throwable ignored) {
            }
            java.util.Map<String, Object> row = playerRow(name, online);
            row.put("role", roles.get(key));
            row.put("rank", e.getValue());
            row.put("online", Boolean.valueOf(online));
            out.add(row);
        }
        java.util.Collections.sort(out, new java.util.Comparator<java.util.Map<String, Object>>() {
            @Override
            public int compare(java.util.Map<String, Object> a, java.util.Map<String, Object> b) {
                int ra = ((Number) a.get("rank")).intValue();
                int rb = ((Number) b.get("rank")).intValue();
                if (ra != rb) return ra - rb;
                boolean oa = Boolean.TRUE.equals(a.get("online"));
                boolean ob = Boolean.TRUE.equals(b.get("online"));
                if (oa != ob) return oa ? -1 : 1;
                return String.valueOf(a.get("name")).compareToIgnoreCase(String.valueOf(b.get("name")));
            }
        });
        return out;
    }

    private void addStaff(java.util.LinkedHashMap<String, String> names,
                          java.util.LinkedHashMap<String, Integer> ranks,
                          java.util.LinkedHashMap<String, String> roles,
                          java.util.List<String> who, int rank, String role, String label) {
        if (who == null) return;
        for (String n : who) {
            if (n == null) continue;
            String key = n.toLowerCase(java.util.Locale.ROOT);
            Integer old = ranks.get(key);
            if (old != null && old.intValue() <= rank) continue;
            names.put(key, n);
            ranks.put(key, Integer.valueOf(rank));
            roles.put(key, role);
        }
    }

    /** 在线 + 离线玩家一起搜（名字前缀匹配），最多返回 limit 个。 */
    public java.util.List<java.util.Map<String, Object>> searchPlayers(String q, int limit) {
        String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<java.util.Map<String, Object>>();
        java.util.Set<String> seen = new java.util.HashSet<String>();

        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (out.size() >= limit) break;
            String name = p.getName();
            if (!needle.isEmpty() && !name.toLowerCase(java.util.Locale.ROOT).contains(needle)) continue;
            seen.add(name.toLowerCase(java.util.Locale.ROOT));
            out.add(playerRow(name, true));
        }

        if (out.size() < limit) {
            java.util.List<String> names = knownOfflineNames();
            for (String name : names) {
                if (out.size() >= limit) break;
                String low = name.toLowerCase(java.util.Locale.ROOT);
                if (seen.contains(low)) continue;
                if (!needle.isEmpty() && !low.contains(needle)) continue;
                seen.add(low);
                out.add(playerRow(name, false));
            }
        }
        return out;
    }

    private java.util.Map<String, Object> playerRow(String name, boolean online) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<String, Object>();
        row.put("name", name);
        row.put("online", Boolean.valueOf(online));
        try {
            row.put("banned", Boolean.valueOf(isBanned(name)));
        } catch (Throwable t) {
            row.put("banned", Boolean.FALSE);
        }
        return row;
    }

    /** 离线玩家名（ITNOL 的已知名单 + Bukkit 的离线缓存），去重后返回。 */
    private java.util.List<String> knownOfflineNames() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<String>();
        try {
            Object inl = Bukkit.getPluginManager().getPlugin("InTheNameOfLinux");
            if (inl != null) {
                Object names = inl.getClass().getMethod("getKnownPlayerNames").invoke(inl);
                if (names instanceof Iterable) {
                    for (Object n : (Iterable<?>) names) {
                        if (n != null) set.add(String.valueOf(n));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (set.isEmpty()) {
            for (org.bukkit.OfflinePlayer off : Bukkit.getOfflinePlayers()) {
                if (off != null && off.getName() != null) set.add(off.getName());
            }
        }
        return new java.util.ArrayList<String>(set);
    }

    /** 让其它类也能用反射 getter/字段读取。 */
    public static Object readField(Class<?> c, Object o, String name, Object fallback) {
        return fieldOrDefault(c, o, name, fallback);
    }

    public static String readText(Class<?> c, Object o, String name, String fallback) {
        return fieldText(c, o, name);
    }
}

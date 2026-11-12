package com.nalxer.app;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Random;

/**
 * 每日签到。
 *
 * 规则：一天只能签一次（按 {@code Asia/Shanghai} 的日期算，0 点重置），
 * 连续签到会累加 streak，跨天没签就归 1；total 是累计签到天数，不归零。
 * 奖励随机二选一：5000 金币，或者 1~5 点券。
 *
 * 数据存在 {@code plugins/NalxerApp/checkins.yml}：
 * <pre>
 * players:
 *   steve:
 *     last: 2026-11-03
 *     streak: 4
 *     total: 17
 * </pre>
 */
public class CheckIn {

    public static final ZoneId CN = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 5000 金币的概率（其余情况给点券）。 */
    private static final double COIN_CHANCE = 0.5D;

    private final NalxerApp plugin;
    private final File file;
    private final Random random = new Random();

    public CheckIn(NalxerApp plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "checkins.yml");
    }

    /** 签到状态（不含奖励）。 */
    public static final class Info {
        public boolean claimed;
        public int streak;
        public int total;
        public String lastDate;

        public java.util.Map<String, Object> json() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("claimed", Boolean.valueOf(claimed));
            m.put("streak", Integer.valueOf(streak));
            m.put("total", Integer.valueOf(total));
            m.put("lastDate", lastDate == null ? "" : lastDate);
            m.put("resetAt", LocalDate.now(CN).plusDays(1).atStartOfDay(CN).toInstant().toEpochMilli());
            return m;
        }
    }

    /** 签到结果。 */
    public static final class Result {
        public boolean ok;
        public String message;
        public String reward;
        public Info info;

        public java.util.Map<String, Object> json() {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("ok", Boolean.valueOf(ok));
            m.put("message", message == null ? "" : message);
            m.put("reward", reward == null ? "" : reward);
            m.put("info", info == null ? new Info().json() : info.json());
            return m;
        }
    }

    // ── 存储 ────────────────────────────────────────────────

    private YamlConfiguration load() {
        if (!file.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                file.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("[签到] 建文件失败：" + e.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void save(YamlConfiguration cfg) {
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[签到] 存文件失败：" + e.getMessage());
        }
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }

    private static String today() {
        return LocalDate.now(CN).format(FMT);
    }

    // ── 对外 ────────────────────────────────────────────────

    /** 查状态，不领取。 */
    public Info info(String playerName) {
        YamlConfiguration cfg = load();
        String k = key(playerName);
        Info out = new Info();
        String last = cfg.getString("players." + k + ".last", "");
        out.lastDate = last;
        out.streak = cfg.getInt("players." + k + ".streak", 0);
        out.total = cfg.getInt("players." + k + ".total", 0);
        out.claimed = today().equals(last);
        return out;
    }

    /** 领今天的签到奖励。 */
    public Result claim(String playerName) {
        Result r = new Result();
        YamlConfiguration cfg = load();
        String k = key(playerName);
        String now = today();
        String last = cfg.getString("players." + k + ".last", "");

        if (now.equals(last)) {
            r.ok = false;
            r.info = info(playerName);
            r.message = "今天已经签过了，明天 0 点再来（连续 " + r.info.streak + " 天，累计 " + r.info.total + " 天）";
            return r;
        }

        String yesterday = LocalDate.now(CN).minusDays(1).format(FMT);
        int streak = yesterday.equals(last) ? cfg.getInt("players." + k + ".streak", 0) + 1 : 1;
        int total = cfg.getInt("players." + k + ".total", 0) + 1;

        boolean coin = random.nextDouble() < COIN_CHANCE;
        String rewardText;
        if (coin) {
            boolean ok = plugin.hooks().depositCoins(playerName, 5000);
            if (ok) {
                rewardText = "5000 金币";
            } else {
                // 经济插件没接上就退回点券，别让玩家白签
                int pts = 1 + random.nextInt(5);
                boolean ok2 = plugin.hooks().givePoints(playerName, pts);
                rewardText = ok2 ? (pts + " 点券") : "无（服务器没接经济插件）";
            }
        } else {
            int pts = 1 + random.nextInt(5);
            boolean ok = plugin.hooks().givePoints(playerName, pts);
            if (ok) {
                rewardText = pts + " 点券";
            } else {
                boolean ok2 = plugin.hooks().depositCoins(playerName, 5000);
                rewardText = ok2 ? "5000 金币" : "无（服务器没接经济插件）";
            }
        }

        cfg.set("players." + k + ".last", now);
        cfg.set("players." + k + ".streak", Integer.valueOf(streak));
        cfg.set("players." + k + ".total", Integer.valueOf(total));
        cfg.set("players." + k + ".name", playerName);
        save(cfg);

        Info info = new Info();
        info.claimed = true;
        info.streak = streak;
        info.total = total;
        info.lastDate = now;

        r.ok = true;
        r.info = info;
        r.reward = rewardText;
        r.message = "签到成功，拿到 " + rewardText + "（连续 " + streak + " 天，累计 " + total + " 天）";
        plugin.audit(playerName + " 签到 → " + rewardText);
        return r;
    }
}

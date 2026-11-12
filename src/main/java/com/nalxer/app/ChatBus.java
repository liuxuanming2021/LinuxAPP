package com.nalxer.app;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import java.util.Locale;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App 与游戏内互通的聊天总线。
 *
 * 游戏 → App：监听 {@link AsyncPlayerChatEvent}（MONITOR 且 ignoreCancelled，被别人取消的
 * 消息——比如被禁言——不会进缓冲区），只留纯文本，客户端自己排版。
 *
 * App → 游戏：主线程广播 {@code §b[LinuxAPP] §f名字§7： §f内容}。不走 player.chat()，
 * 因为那样会被聊天格式插件改写、也拿不到统一前缀；禁言另判（见 Hooks#isMuted）。
 */
public class ChatBus implements Listener {

    /** 缓冲多少条历史，够 App 打开时看到最近的对话。 */
    private static final int MAX_HISTORY = 120;

    /** 单条消息最大长度，超过直接拒绝（服务端也要挡，不能只靠前端）。 */
    public static final int MAX_LENGTH = 200;

    private final NalxerApp plugin;
    private final Deque<Msg> history = new ArrayDeque<Msg>();
    private final AtomicLong seq = new AtomicLong(System.currentTimeMillis());

    public ChatBus(NalxerApp plugin) {
        this.plugin = plugin;
    }

    /** 一条聊天记录。 */
    public static final class Msg {
        public final long id;
        public final long time;
        public final String name;
        public final String text;
        public final String source;
        /** world = 公屏；team = 队伍频道 */
        public final String channel;
        /** 队伍频道才有：队伍 id 与名字 */
        public final String teamId;
        public final String teamName;

        Msg(long id, long time, String name, String text, String source,
            String channel, String teamId, String teamName) {
            this.id = id;
            this.time = time;
            this.name = name;
            this.text = text;
            this.source = source;
            this.channel = channel == null ? "world" : channel;
            this.teamId = teamId == null ? "" : teamId;
            this.teamName = teamName == null ? "" : teamName;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!plugin.getConfig().getBoolean("chat.capture-game", true)) return;
        String who = event.getPlayer().getName();
        // 开着 /team chat 的人，这条消息只发给队友 —— App 里也该归到队伍频道
        java.util.Map<String, Object> team = plugin.hooks().teamChatOf(who);
        if (team != null) {
            add(who, event.getMessage(), "game", "team",
                    String.valueOf(team.get("id")), String.valueOf(team.get("name")));
            return;
        }
        add(who, event.getMessage(), "game");
    }

    /**
     * 控制台敲的 {@code say} / {@code broadcast} 也进聊天。
     *
     * 这两种广播走的是 {@code Bukkit.broadcastMessage}，不触发
     * {@link AsyncPlayerChatEvent}，所以必须从命令这一侧捞。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        captureBroadcast("控制台", event.getCommand());
    }

    /**
     * RCON 敲的命令是 {@link RemoteServerCommandEvent}，它有自己的 HandlerList，
     * 只听 {@link ServerCommandEvent} 收不到 —— 所以单独再挂一个。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        captureBroadcast("控制台", event.getCommand());
    }

    /** 玩家自己敲的 /say 也从命令侧捞一份，免得只有控制台发言才看得见。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String who = event.getPlayer().getName();
        String raw = event.getMessage() == null ? "" : event.getMessage().trim();
        // /tc 内容 = 单条队伍消息，不进公屏
        String low = raw.toLowerCase(Locale.ROOT);
        if (low.startsWith("/tc ") || low.startsWith("/teamchat ")) {
            int sp = low.indexOf(' ');
            String text = raw.substring(sp + 1).trim();
            java.util.Map<String, Object> team = plugin.hooks().teamChatOf(who);
            if (team == null) team = plugin.hooks().teamInfo(who);
            if (team != null && !text.isEmpty()) {
                add(who, text, "game", "team",
                        String.valueOf(team.get("id")), String.valueOf(team.get("name")));
            }
            return;
        }
        captureBroadcast(who, raw);
    }

    /**
     * 上下线提示：不进公屏聊天流，单独记一条 source=notice 的系统消息，
     * App 里画成居中灰条（微信那种「消息已撤回」的样式）。
     */
    public void notice(String text) {
        if (!plugin.getConfig().getBoolean("chat.capture-join-quit", true)) return;
        if (text == null || text.trim().isEmpty()) return;
        add("", text.trim(), "notice");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        notice(event.getPlayer().getName() + " 上线了");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        notice(event.getPlayer().getName() + " 下线了");
    }

    /** 解析 say / broadcast / me，把正文记进缓冲区。 */
    private void captureBroadcast(String who, String raw) {
        if (!plugin.getConfig().getBoolean("chat.capture-console", true)) return;
        if (raw == null) return;
        String s = raw.trim();
        if (s.startsWith("/")) s = s.substring(1);
        // 有些面板 / 插件派发时会带命名空间（minecraft:say、bukkit:broadcast）—— 先剥掉
        int colon = s.indexOf(':');
        if (colon > 0 && colon < 16 && !s.substring(0, colon).contains(" ")) {
            s = s.substring(colon + 1).trim();
        }
        String lower = s.toLowerCase(Locale.ROOT);
        String text;
        if (lower.startsWith("say ")) {
            text = s.substring(4);
        } else if (lower.startsWith("broadcast ")) {
            text = s.substring(10);
        } else if (lower.startsWith("bc ")) {
            text = s.substring(3);
        } else if (lower.startsWith("me ")) {
            text = who + " " + s.substring(3);
        } else {
            return;
        }
        text = org.bukkit.ChatColor.stripColor(
                org.bukkit.ChatColor.translateAlternateColorCodes('&', text)).trim();
        if (text.isEmpty()) return;
        add(who, text, "console");
    }

    /** 记一条进缓冲区（游戏内或 App 发来的）。 */
    public Msg add(String name, String text, String source) {
        return add(name, text, source, "world", "", "");
    }

    /** 记一条带频道的消息（channel=team 时带队伍 id / 名字）。 */
    public Msg add(String name, String text, String source, String channel, String teamId, String teamName) {
        String nm = name == null ? "" : name;
        String tx = text == null ? "" : text;
        synchronized (history) {
            // 同一个人、同一句话、同一个频道，1.5 秒内只留一条
            // （监听被注册两遍、say 同时被命令侧和日志侧捞到，都会走到这里被丢掉）
            Msg last = history.peekLast();
            if (last != null
                    && nm.equals(last.name) && tx.equals(last.text) && source.equals(last.source)
                    && (channel == null ? "world" : channel).equals(last.channel)
                    && System.currentTimeMillis() - last.time < 1500L) {
                return last;
            }
            Msg msg = new Msg(seq.incrementAndGet(), System.currentTimeMillis(),
                    nm, tx, source, channel, teamId, teamName);
            history.addLast(msg);
            while (history.size() > MAX_HISTORY) history.pollFirst();
            return msg;
        }
    }

    /**
     * App 发一条消息到游戏里。
     *
     * @return 记录；内容不合法时返回 null
     */
    public Msg post(final String name, String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
        // 聊天里塞换行会把广播搞成两行，也会被人用来伪造系统消息
        text = text.replace('\n', ' ').replace('\r', ' ').replace('§', '&');
        final Msg msg = add(name, text, "app");
        final String prefix = plugin.getConfig().getString("chat.prefix", "§b[LinuxAPP] §f");
        final String body = prefix + name + "§7: §f" + text;
        if (!plugin.isEnabled()) return msg;
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Bukkit.broadcastMessage(body);
            }
        });
        return msg;
    }

    /**
     * App 发一条队伍消息。
     * <p>
     * 只发给这个队的在线成员（跟游戏里 /tc 一个效果），同时记进 team 频道缓冲区，
     * 让 App 里同队的其他人也看得到。
     */
    public Msg postTeam(final String name, String teamId, String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;
        if (text.length() > MAX_LENGTH) text = text.substring(0, MAX_LENGTH);
        text = text.replace('\n', ' ').replace('\r', ' ').replace('§', '&');

        java.util.Map<String, Object> team = plugin.hooks().teamById(teamId);
        if (team == null) return null;
        if (!plugin.hooks().teamHasMember(team, name)) return null;
        if (!plugin.hooks().teamChatAllowed(team)) return null;

        final Msg msg = add(name, text, "app", "team", teamId, String.valueOf(team.get("name")));
        final String body = "§b[队伍] §f" + String.valueOf(team.get("tag")) + " §7" + name + " §8» §f" + text;
        if (!plugin.isEnabled()) return msg;
        plugin.getServer().getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                plugin.hooks().teamSendToOnline(teamId, body);
            }
        });
        return msg;
    }

    /** 取 id 大于 since 的记录，最多 limit 条（按时间正序）。 */
    public List<Msg> since(long since, int limit) {
        return since(since, limit, null, null);
    }

    /**
     * 按频道取记录。
     *
     * @param channel "world" / "team"；null = 全部
     * @param teamId  队伍频道时限定队伍
     * @param mineOnly true 时连自己发的也算（队伍频道需要）
     */
    public List<Msg> since(long since, int limit, String channel, String teamId) {
        List<Msg> out = new ArrayList<Msg>();
        synchronized (history) {
            for (Msg m : history) {
                if (m.id <= since) continue;
                if (channel != null && !channel.equals(m.channel)) continue;
                if (teamId != null && !teamId.isEmpty() && !teamId.equals(m.teamId)) continue;
                out.add(m);
            }
        }
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<Msg>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    /** 最新一条的 id，客户端首次拉取时用来对齐。 */
    public long lastId() {
        synchronized (history) {
            Msg m = history.peekLast();
            return m == null ? 0L : m.id;
        }
    }
}

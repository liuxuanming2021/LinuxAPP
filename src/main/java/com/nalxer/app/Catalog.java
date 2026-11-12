package com.nalxer.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App 管理页「命令」列表的数据源。
 *
 * <p>每一条都是一句可以直接执行的服务器命令；App 里点一下就把 {@code cmd} 填进输入框，
 * 管理员补完参数按执行。之所以做「先填后执行」而不是「点一下直接跑」，
 * 是因为这些命令里有封全服、重载插件这种按下去就回不了头的操作。
 */
final class Catalog {

    private Catalog() {
    }

    static List<Object> groups() {
        List<Object> groups = new ArrayList<Object>();

        // ── 封禁 ──
        List<Object> ban = new ArrayList<Object>();
        ban.add(item("封禁玩家", "linux ban", "<玩家> [原因]",
                "永久封禁，进 ITNOL 的封禁名单并留历史", 1));
        ban.add(item("临时封禁", "tempban", "<玩家> <时间> [原因]",
                "时间写 30m / 12h / 7d；到点自动解封", 1));
        ban.add(item("解封玩家", "unban", "<玩家>",
                "解封并删掉最近一条封禁历史", 0));
        ban.add(item("封禁 IP", "banip", "<玩家或IP> [原因]",
                "填玩家名会自动查他最近登录的 IP", 1));
        ban.add(item("解封 IP", "unbanip", "<IP>", "", 0));
        ban.add(item("封禁名单", "banlist", "[页码]", "分页看当前所有封禁", 0));
        groups.add(group("封禁与解封", ban));

        // ── 查询 ──
        List<Object> look = new ArrayList<Object>();
        look.add(item("查玩家", "linux lookup", "<玩家>",
                "等级、金币、点券、在线时长、上次登录、IP 归属", 0));
        look.add(item("查封禁历史", "linux history", "<玩家>",
                "这个号以前被封过几次、谁封的、什么原因", 0));
        look.add(item("查登录 IP", "linux seenip", "<玩家>", "看这个号用过哪些 IP", 0));
        look.add(item("清空封禁历史", "linux clearhistory", "<玩家> confirm",
                "连历史一起删，删了查不回来", 1));
        groups.add(group("查人", look));

        // ── 服务器 ──
        List<Object> server = new ArrayList<Object>();
        server.add(item("插件联动状态", "linux plugins",
                "", "ITNOL 的联动插件 + 拓展插件，和你游戏里敲的一样", 0));
        server.add(item("审计日志", "linux audit", "[条数]", "最近的管理操作记录", 0));
        server.add(item("服务器日志", "linux logs", "[条数]", "聊天 / 命令日志", 0));
        server.add(item("重载本插件", "nalxerapp reload", "", "改完 config.yml 用", 1));
        server.add(item("NBT 扫描", "linux nbtview", "[玩家]",
                "扫非法附魔 / 非法 NBT 物品", 0));
        server.add(item("重载 ITNOL", "linux reload",
                "", "改完配置后用；只重载本插件，不踢人", 1));
        groups.add(group("服务器", server));

        // ── Linux 系插件 ──
        List<Object> linux = new ArrayList<Object>();
        linux.add(item("TAB 状态", "ltab info", "", "列数、条目数、延迟列、设计的套数", 0));
        linux.add(item("重载 TAB", "ltab reload", "", "改完 LinuxTAB/config.yml 用", 0));
        linux.add(item("假人名单", "fplist", "", "当前所有假人 + 在线状态", 0));
        linux.add(item("新建假人", "fp add", "<名字>",
                "名字会自动套上 FP_ 前缀，占一个名额", 0));
        linux.add(item("删除假人", "fpr", "<名字>", "", 1));
        linux.add(item("重载假人插件", "fpreload", "", "改完 config.yml / data.yml 用", 1));
        linux.add(item("保存假人数据", "fpsave", "", "手动写一次 data.yml", 0));
        linux.add(item("同步存档", "linuxsync", "", "手动触发一次 LinuxSync 保存与备份", 1));
        linux.add(item("头衔帮助", "title help", "", "LinuxTitle 的用法（看回显就懂了）", 0));
        linux.add(item("队伍帮助", "team help", "", "LinuxTeam 的用法", 0));
        groups.add(group("Linux 系插件", linux));

        return groups;
    }

    private static Map<String, Object> group(String name, List<Object> items) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("name", name);
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> item(String label, String cmd, String args, String desc, int danger) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("label", label);
        m.put("cmd", cmd);
        m.put("args", args);
        m.put("desc", desc);
        m.put("danger", Integer.valueOf(danger));
        return m;
    }
}

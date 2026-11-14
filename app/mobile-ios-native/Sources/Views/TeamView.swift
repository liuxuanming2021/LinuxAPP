import SwiftUI
import UIKit

struct TeamView: View {
    @EnvironmentObject var store: AppState
    @State private var newName = ""

    private var accent: Color { store.accent.color }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if store.myTeams.isEmpty { createCard }
                ForEach(store.myTeams.indices, id: \.self) { i in
                    teamCard(store.myTeams[i])
                }
                serverCard
                Spacer().frame(height: 90)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
        }
        .onAppear { store.loadTeam() }
    }

    /// 用系统 UIAlertController：标题和输入框都是苹果原生样式。
    /// （iOS 13 的 SwiftUI alert 放不了输入框，自绘 sheet 又会显示成一片空白）
    private func ask(_ title: String, _ hint: String, _ action: @escaping (String) -> Void) {
        guard let root = UIApplication.shared.windows.first(where: { $0.isKeyWindow })?.rootViewController else { return }
        let alert = UIAlertController(title: title, message: nil, preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = hint
            tf.autocapitalizationType = .none
            tf.autocorrectionType = .no
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: "确定", style: .default) { _ in
            action(alert.textFields?.first?.text ?? "")
        })
        var top = root
        while let p = top.presentedViewController { top = p }
        top.present(alert, animated: true)
    }

    // MARK: 建队

    private var createCard: some View {
        Card {
            Text("还没有队伍").font(.system(size: 16, weight: .semibold))
            HStack(spacing: 8) {
                TextField("新队伍名字（最多 12 字）", text: $newName)
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .background(DS.chip)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .autocapitalization(.none)
                    .disableAutocorrection(true)
                Button {
                    let n = newName
                    newName = ""
                    store.teamAct("create", arg: n)
                } label: {
                    Text("建队").font(.system(size: 14, weight: .semibold))
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .background(accent).foregroundColor(.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(PlainButtonStyle())
            }
            Text("建队后你就是队长，可以邀请、踢人、转让、解散。")
                .font(.system(size: 12)).foregroundColor(DS.muted)
        }
    }

    // MARK: 我的队伍

    private func teamCard(_ team: Map) -> some View {
        let tid = team.s("id")
        let isLeader = team.s("leaderName").lowercased() == store.name.lowercased()
        let members = team.a("members")
        let apps = team.a("applications")

        return Card {
            HStack(alignment: .firstTextBaseline) {
                Text(Fmt.plain(team.s("coloredName", team.s("name"))))
                    .font(.system(size: 17, weight: .semibold))
                if !team.s("tag").isEmpty {
                    Text("（\(Fmt.plain(team.s("coloredTag", team.s("tag"))))）")
                        .font(.system(size: 13)).foregroundColor(DS.muted)
                }
                Spacer()
                Text("\(team.i("size")) / \(team.i("maxSize")) 人")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
            }
            Text("队长 \(team.s("leaderName", "—"))")
                .font(.system(size: 12)).foregroundColor(DS.muted)

            Divider().background(DS.cardLine)

            ForEach(members.indices, id: \.self) { i in
                memberRow(members[i], isLeader: isLeader, tid: tid)
            }

            // 队伍设置：队长能改；其他人只看到状态，不给开关
            ToggleRow(title: "队伍聊天", subtitle: "队员之间收发队伍消息",
                      on: team.b("chatOn", true), editable: isLeader) { on in
                store.teamAct("setting", arg: on ? "chat:on" : "chat:off", team: tid)
            }
            ToggleRow(title: "加入需要申请", subtitle: "打开后新成员进队要你同意",
                      on: team.b("applyNeeded", false), editable: isLeader) { on in
                store.teamAct("setting", arg: on ? "apply:on" : "apply:off", team: tid)
            }
            ToggleRow(title: "队伍伤害", subtitle: "队员之间能不能互相攻击",
                      on: team.b("friendlyFire", false), editable: isLeader) { on in
                store.teamAct("setting", arg: on ? "friendly-fire:on" : "friendly-fire:off", team: tid)
            }
            ToggleRow(title: "共享领地", subtitle: "队内领地开放给所有队员",
                      on: team.b("landShare", false), editable: isLeader) { on in
                store.teamAct("land", team: tid, on: on)
            }

            if isLeader && !apps.isEmpty {
                Divider().background(DS.cardLine)
                Text("加入申请 · \(apps.count) 条")
                    .font(.system(size: 13, weight: .semibold))
                ForEach(apps.indices, id: \.self) { i in
                    let a = apps[i]
                    HStack {
                        Text(a.s("player")).font(.system(size: 14))
                        Spacer()
                        smallButton("同意", tint: accent) {
                            store.teamAct("decide", arg: a.s("player"), team: tid, accept: true)
                        }
                        smallButton("拒绝", tint: DS.muted) {
                            store.teamAct("decide", arg: a.s("player"), team: tid, accept: false)
                        }
                    }
                }
            }

            Divider().background(DS.cardLine)
            actionGrid(tid, isLeader)
        }
    }

    private func memberRow(_ m: Map, isLeader: Bool, tid: String) -> some View {
        HStack(spacing: 8) {
            Circle().fill(m.b("online") ? DS.green : DS.muted).frame(width: 7, height: 7)
            VStack(alignment: .leading, spacing: 2) {
                Text(m.s("name")).font(.system(size: 14))
                Text("贡献 \(m.i("contribution")) · 在队 \(m.i("days")) 天")
                    .font(.system(size: 11)).foregroundColor(DS.muted)
            }
            Spacer()
            if isLeader && m.s("name").lowercased() != store.name.lowercased() {
                smallButton("踢出", tint: DS.muted) {
                    store.teamAct("kick", arg: m.s("name"), team: tid)
                }
            }
        }
        .padding(.vertical, 3)
    }

    private func actionGrid(_ tid: String, _ isLeader: Bool) -> some View {
        VStack(spacing: 8) {
            if isLeader {
                HStack(spacing: 8) {
                    gridButton("邀请") { ask("邀请谁", "填玩家名字") { store.teamAct("invite", arg: $0, team: tid) } }
                    gridButton("改队标") { ask("新队标", "最多 6 个字") { store.teamAct("tag", arg: $0, team: tid) } }
                }
                HStack(spacing: 8) {
                    gridButton("转让队长") { ask("转让给谁", "填队员名字") { store.teamAct("transfer", arg: $0, team: tid) } }
                    gridButton("解散", tint: .red) { store.teamAct("disband", team: tid) }
                }
            }
            gridButton("退出", tint: .red) { store.teamAct("leave", team: tid) }
        }
    }

    private func gridButton(_ title: String, tint: Color? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundColor(tint ?? DS.text)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .background(DS.chip)
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))
        }
        .buttonStyle(PlainButtonStyle())
    }

    private func smallButton(_ title: String, tint: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.system(size: 12, weight: .semibold))
                .foregroundColor(tint)
                .padding(.horizontal, 10).padding(.vertical, 6)
                .background(tint.opacity(0.12))
                .clipShape(Capsule())
        }
        .buttonStyle(PlainButtonStyle())
    }

    /// 这个队伍我自己是不是已经在里面（多队玩家按 id 比）
    private func joined(_ t: Map) -> Bool {
        let id = t.s("id")
        if id.isEmpty { return false }
        for mine in store.myTeams where mine.s("id") == id { return true }
        return false
    }

    // MARK: 服务器上的队伍

    private var serverCard: some View {
        Card {
            HStack {
                Text("服务器上的队伍").font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("\(store.serverTeams.count) 个")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
            }
            if !store.teamAvailable {
                Text("服务器没装 LinuxTeam，队伍功能用不了")
                    .font(.system(size: 13)).foregroundColor(DS.muted)
                    .padding(.vertical, 10)
            } else if store.serverTeams.isEmpty {
                Text("服务器上还没有队伍").font(.system(size: 13)).foregroundColor(DS.muted)
                    .padding(.vertical, 10)
            } else {
                ForEach(store.serverTeams.indices, id: \.self) { i in
                    let t = store.serverTeams[i]
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(Fmt.plain(t.s("coloredName", t.s("name"))))
                                .font(.system(size: 15, weight: .medium))
                            Text("队长 \(t.s("leaderName")) · \(t.i("size"))/\(t.i("maxSize")) 人")
                                .font(.system(size: 11.5)).foregroundColor(DS.muted)
                        }
                        Spacer()
                        if joined(t) {
                            Text("已在队伍").font(.system(size: 12, weight: .medium))
                                .foregroundColor(DS.muted)
                        } else {
                            smallButton(t.b("applyNeeded") ? "申请加入" : "加入", tint: accent) {
                                store.teamAct(t.b("applyNeeded") ? "apply" : "join", arg: t.s("id"))
                            }
                        }
                    }
                    .padding(.vertical, 6)
                    if i < store.serverTeams.count - 1 {
                        Divider().background(DS.cardLine)
                    }
                }
            }
        }
    }
}

/// 开关行
struct ToggleRow: View {
    let title: String
    let subtitle: String
    let on: Bool
    var editable: Bool = true
    var changed: (Bool) -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .medium))
                Text(subtitle).font(.system(size: 11.5)).foregroundColor(DS.muted)
            }
            Spacer()
            if editable {
                Toggle("", isOn: Binding(get: { on }, set: { v in changed(v) }))
                    .labelsHidden()
            } else {
                Text(on ? "已开启" : "已关闭")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundColor(on ? DS.green : Color.red)
                    .padding(.horizontal, 9).padding(.vertical, 4)
                    .background((on ? DS.green : Color.red).opacity(0.12))
                    .clipShape(Capsule())
            }
        }
        .padding(.vertical, 3)
    }
}

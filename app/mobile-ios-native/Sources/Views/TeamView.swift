import SwiftUI

struct TeamView: View {
    @EnvironmentObject var store: AppState
    @State private var newName = ""
    @State private var promptTitle = ""
    @State private var promptHint = ""
    @State private var showPrompt = false
    @State private var promptAction: (String) -> Void = { _ in }

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
            .padding(.top, 4)
        }
        .onAppear { store.loadTeam() }
        .sheet(isPresented: $showPrompt) {
            PromptSheet(title: self.promptTitle, hint: self.promptHint) { text in
                self.promptAction(text)
            }
        }
    }

    private func ask(_ title: String, _ hint: String, _ action: @escaping (String) -> Void) {
        promptTitle = title
        promptHint = hint
        promptAction = action
        showPrompt = true
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
        let chatOn = team["chatOn"] as? Bool ?? true

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

            // 开关
            ToggleRow(title: "队伍聊天", subtitle: "队员之间收发队伍消息",
                      on: chatOn) { on in
                store.teamAct("chat", arg: on ? "on" : "off", team: tid)
            }
            if let land = team["land"] as? Bool {
                ToggleRow(title: "共享领地", subtitle: "把队内领地开放给队员",
                          on: land) { on in
                    store.teamAct("land", team: tid, on: on)
                }
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
                        smallButton(t.b("applyMode") ? "申请加入" : "加入", tint: accent) {
                            store.teamAct(t.b("applyMode") ? "apply" : "join", arg: t.s("id"))
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
    var changed: (Bool) -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 14, weight: .medium))
                Text(subtitle).font(.system(size: 11.5)).foregroundColor(DS.muted)
            }
            Spacer()
            Toggle("", isOn: Binding(get: { on }, set: { v in changed(v) }))
                .labelsHidden()
        }
        .padding(.vertical, 3)
    }
}

/// 弹出一个单行输入框（iOS 13 的 alert 里不能放 TextField）
struct PromptSheet: View {
    let title: String
    let hint: String
    var onOK: (String) -> Void

    @Environment(\.presentationMode) private var mode
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.system(size: 18, weight: .semibold))
            TextField(hint, text: $text)
                .padding(.horizontal, 12).padding(.vertical, 11)
                .background(DS.chip)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .autocapitalization(.none)
                .disableAutocorrection(true)
            HStack(spacing: 10) {
                Button { mode.wrappedValue.dismiss() } label: {
                    Text("取消").frame(maxWidth: .infinity).padding(.vertical, 11)
                        .background(DS.chip)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(PlainButtonStyle())
                Button {
                    let t = text
                    mode.wrappedValue.dismiss()
                    onOK(t)
                } label: {
                    Text("确定").frame(maxWidth: .infinity).padding(.vertical, 11)
                        .foregroundColor(.white)
                        .background(Color(hex: 0x2E8BFF))
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(PlainButtonStyle())
            }
            Spacer()
        }
        .padding(20)
        .background(DS.bg.edgesIgnoringSafeArea(.all))
    }
}

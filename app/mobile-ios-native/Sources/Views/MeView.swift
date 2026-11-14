import SwiftUI

struct MeView: View {
    @EnvironmentObject var store: AppState
    @State private var reportTarget = ""
    @State private var showServers = false
    @State private var reportReason = ""

    private var accent: Color { store.accent.color }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                if store.multiServer { serverBar }
                header
                stats
                expCard
                checkInCard
                reportCard
                Spacer().frame(height: 90)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
        }
        .onAppear {
            Task { await store.loadMe() }
            Task { await store.loadCheckIn() }
            store.loadMyReports()
        }
    }

    /// 多服务器：当前这台 + 切换
    private var serverBar: some View {
        Button(action: { showServers = true }) {
            HStack(spacing: 8) {
                Image(systemName: "server.rack")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(accent)
                Text(store.servers.indices.contains(store.activeServer)
                     ? displayName(store.activeServer) : store.serverText)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundColor(DS.text)
                    .lineLimit(1)
                Spacer()
                Text("切换").font(.system(size: 12)).foregroundColor(DS.muted)
                Image(systemName: "chevron.right")
                    .font(.system(size: 11, weight: .semibold)).foregroundColor(DS.muted)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .background(DS.card)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PlainButtonStyle())
        .sheet(isPresented: $showServers) {
            NavigationView {
                List {
                    ForEach(store.servers.indices, id: \.self) { i in
                        Button(action: {
                            showServers = false
                            store.switchServer(to: i) { ok in
                                store.toast(ok ? "已切到 \(displayName(i))" : "已切到 \(displayName(i))，这台要重新登录")
                            }
                        }) {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(displayName(i)).foregroundColor(DS.text)
                                    Text(store.servers[i]["url"] ?? "")
                                        .font(.system(size: 12)).foregroundColor(DS.muted)
                                }
                                Spacer()
                                if i == store.activeServer {
                                    Image(systemName: "checkmark").foregroundColor(accent)
                                }
                            }
                        }
                    }
                }
                .listStyle(GroupedListStyle())
                .navigationBarTitle(Text("切换服务器"), displayMode: .inline)
                .navigationBarItems(trailing: Button("关闭") { showServers = false })
            }
            .navigationViewStyle(StackNavigationViewStyle())
        }
    }

    private func displayName(_ i: Int) -> String {
        guard store.servers.indices.contains(i) else { return store.serverText }
        let n = store.servers[i]["name"] ?? ""
        return n.isEmpty ? (store.servers[i]["url"] ?? "") : n
    }

    private var header: some View {
        HStack(spacing: 14) {
            SkinHead(name: store.name, uuid: store.uuid, size: 62, radius: 14)

            VStack(alignment: .leading, spacing: 6) {
                HStack(spacing: 6) {
                    Text(store.name).font(.system(size: 21, weight: .bold))
                    Circle().fill(store.online.contains(where: { $0.id == store.name }) ? DS.green : DS.muted)
                        .frame(width: 7, height: 7)
                }
                HStack(spacing: 6) {
                    Tag(text: store.roleText, tint: accent)
                    if !store.teamName.isEmpty {
                        Tag(text: Fmt.plain(store.teamName), tint: DS.muted)
                    }
                    if !store.titleName.isEmpty {
                        Tag(text: Fmt.plain(store.titleName), tint: DS.muted)
                    }
                }
            }
            Spacer()
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var stats: some View {
        HStack(spacing: 10) {
            StatCard(title: "金币", value: store.hasCoins ? Fmt.short(store.coins) : "—")
            StatCard(title: "点券", value: store.hasPoints ? Fmt.short(Double(store.points)) : "—")
            StatCard(title: "等级", value: "\(store.level)")
        }
    }

    private var expCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("经验进度").font(.system(size: 16, weight: .semibold))
                Spacer()
                Text(expLabel).font(.system(size: 12)).foregroundColor(DS.muted)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(DS.chip)
                    Capsule().fill(accent)
                        .frame(width: max(0, geo.size.width * progress))
                }
            }
            .frame(height: 7)
            Text("总经验 \(Fmt.short(Double(store.expTotal)))")
                .font(.system(size: 12)).foregroundColor(DS.muted)
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    /// 服务端 exp 是 0~1 的小数，expToNext 是这一级需要的经验；满级时 expToNext = 0。
    private var progress: CGFloat {
        if store.expNext <= 0 {
            return store.expPct > 0 ? min(1, CGFloat(store.expPct)) : 0
        }
        return min(1, CGFloat(store.expInto) / CGFloat(store.expNext))
    }

    private var expLabel: String {
        if store.expNext <= 0 {
            return store.level <= 0 ? "还没有经验数据" : "已满级"
        }
        let pct = Int((progress * 100).rounded())
        return "\(store.expInto) / \(store.expNext) 经验（\(pct)%）"
    }

    private var checkInCard: some View {
        HStack(spacing: 12) {
            Text("📅").font(.system(size: 26))
                .frame(width: 44, height: 44)
                .background(accent.opacity(0.16))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text("连续 \(store.signStreak) 天").font(.system(size: 15, weight: .semibold))
                Text("每天 0 点重置，随机发金币或点券")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
            }
            Spacer()
            Button { store.signIn() } label: {
                Text(store.signedToday ? "已签到" : "签到")
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 18).padding(.vertical, 9)
                    .background(store.signedToday ? DS.chip : accent)
                    .foregroundColor(store.signedToday ? DS.muted : .white)
                    .clipShape(Capsule())
            }
            .disabled(store.signedToday)
        }
        .padding(14)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var reportCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("举报玩家").font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("管理员会在服务器里收到").font(.system(size: 12)).foregroundColor(DS.muted)
            }
            TextField("被举报玩家的游戏 ID", text: $reportTarget)
                .padding(.horizontal, 14).padding(.vertical, 13)
                .background(DS.chip)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .autocapitalization(.none)
                .disableAutocorrection(true)
            TextField("理由（刷屏 / 外挂 / 骂人…）", text: $reportReason)
                .padding(.horizontal, 14).padding(.vertical, 13)
                .background(DS.chip)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            Button {
                store.submitReport(target: reportTarget, reason: reportReason)
            } label: {
                Text("提交举报")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(accent).foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(PlainButtonStyle())

            if !store.myReports.isEmpty {
                Divider().background(DS.cardLine)
                Text("我提交的举报").font(.system(size: 13, weight: .semibold))
                ForEach(store.myReports.prefix(6).indices, id: \.self) { i in
                    let r = store.myReports[i]
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(alignment: .top, spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("#\(r.i("id")) \(r.s("target", "-"))")
                                    .font(.system(size: 13.5, weight: .medium))
                                Text(r.s("reason")).font(.system(size: 11.5)).foregroundColor(DS.muted)
                            }
                            Spacer()
                            Tag(text: reportState(r), tint: reportTint(r))
                        }
                        if r.b("handled") {
                            Text(reportFooter(r))
                                .font(.system(size: 11)).foregroundColor(DS.muted)
                        }
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func reportState(_ r: Map) -> String {
        if !r.b("handled") { return "待处理" }
        return r.s("result") == "reject" ? "已驳回" : "已接受"
    }

    private func reportTint(_ r: Map) -> Color {
        if !r.b("handled") { return DS.muted }
        return r.s("result") == "reject" ? Color.red : DS.green
    }

    private func reportFooter(_ r: Map) -> String {
        let who = r.s("handler")
        let note = r.s("note")
        var parts: [String] = []
        if !who.isEmpty { parts.append("处理人 \(who)") }
        if !note.isEmpty { parts.append(note) }
        return parts.isEmpty ? "已处理" : parts.joined(separator: " · ")
    }
}

struct StatCard: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 12)).foregroundColor(DS.muted)
            Text(value)
                .font(.system(size: 20, weight: .bold))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

struct Tag: View {
    let text: String
    var tint: Color = DS.muted

    var body: some View {
        Text(text)
            .font(.system(size: 11.5, weight: .medium))
            .lineLimit(1)
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(tint.opacity(0.13))
            .foregroundColor(tint)
            .clipShape(Capsule())
    }
}

struct Hint: View {
    let text: String
    init(_ t: String) { text = t }
    var body: some View {
        Text(text)
            .font(.system(size: 14)).foregroundColor(DS.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 13)
            .background(DS.chip)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

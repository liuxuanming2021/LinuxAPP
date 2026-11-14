import SwiftUI

struct AdminView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0
    @State private var query = ""
    @State private var showDetail = false
    @State private var promptTitle = ""
    @State private var promptHint = ""
    @State private var showPrompt = false
    @State private var promptAction: (String) -> Void = { _ in }

    private var accent: Color { store.accent.color }
    private let tabs = ["在线", "玩家", "举报", "惩罚", "假人"]

    var body: some View {
        VStack(spacing: 10) {
            Segmented(titles: tabs, index: tabBinding, accent: accent)
            content
            Spacer(minLength: 78)
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .onAppear { store.loadOnline() }
        .sheet(isPresented: $showDetail) {
            PlayerDetailSheet().environmentObject(self.store)
        }
        .sheet(isPresented: $showPrompt) {
            PromptSheet(title: self.promptTitle, hint: self.promptHint) { text in
                self.promptAction(text)
            }
        }
    }

    private var header: some View {
        HStack {
            Text("服务器管理").font(.system(size: 22, weight: .bold))
            Spacer()
            Text(store.canBan ? "管理员" : "协管")
                .font(.system(size: 12)).foregroundColor(DS.green)
        }
    }

    private var tabBinding: Binding<Int> {
        Binding(get: { tab }, set: { v in
            tab = v
            store.searchResults = []
            if v == 0 { store.loadOnline() }
            if v == 2 { store.loadAdminReports(store.reportFilter) }
            if v == 3 { store.loadPenalties() }
            if v == 4 { store.loadFakes() }
        })
    }

    @ViewBuilder
    private var content: some View {
        if tab == 0 {
            onlineList
        } else if tab == 1 {
            playersTab
        } else if tab == 2 {
            reportsTab
        } else if tab == 3 {
            penaltiesTab
        } else {
            fakesTab
        }
    }

    // MARK: 在线

    private var onlineList: some View {
        ScrollView {
            Card {
                HStack {
                    Text("在线玩家").font(.system(size: 16, weight: .semibold))
                    Spacer()
                    smallButton("刷新", tint: accent) { store.loadOnline() }
                }
                if store.online.isEmpty {
                    Text("现在没人在线").font(.system(size: 13)).foregroundColor(DS.muted)
                        .frame(maxWidth: .infinity).padding(.vertical, 18)
                } else {
                    ForEach(store.online.indices, id: \.self) { i in
                        let p = store.online[i]
                        HStack(spacing: 8) {
                            Circle().fill(DS.green).frame(width: 7, height: 7)
                            Text(p.id).font(.system(size: 15))
                            Spacer()
                            smallButton("详情", tint: accent) { openDetail(p.id) }
                        }
                        .padding(.vertical, 8)
                        if i < store.online.count - 1 { Divider().background(DS.cardLine) }
                    }
                }
            }
        }
    }

    // MARK: 玩家

    private var playersTab: some View {
        ScrollView {
            VStack(spacing: 12) {
                Card {
                    HStack(spacing: 8) {
                        TextField("搜玩家名字", text: $query)
                            .padding(.horizontal, 12).padding(.vertical, 10)
                            .background(DS.chip)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .autocapitalization(.none)
                            .disableAutocorrection(true)
                        smallButton("搜索", tint: accent) { store.searchPlayers(query) }
                    }
                }
                if !store.searchResults.isEmpty {
                    Card {
                        ForEach(store.searchResults.indices, id: \.self) { i in
                            let p = store.searchResults[i]
                            HStack(spacing: 8) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(p.s("name")).font(.system(size: 15))
                                    Text(p.b("online") ? "在线" : "离线")
                                        .font(.system(size: 11.5)).foregroundColor(DS.muted)
                                }
                                Spacer()
                                smallButton("详情", tint: accent) { openDetail(p.s("name")) }
                            }
                            .padding(.vertical, 7)
                            if i < store.searchResults.count - 1 { Divider().background(DS.cardLine) }
                        }
                    }
                }
            }
        }
    }

    // MARK: 举报

    private var reportsTab: some View {
        ScrollView {
            VStack(spacing: 12) {
                Card {
                    HStack {
                        Text("举报").font(.system(size: 16, weight: .semibold))
                        Spacer()
                        Text("\(store.reportTotal) 条 · 已处理 \(store.reportHandled)")
                            .font(.system(size: 12)).foregroundColor(DS.muted)
                    }
                    Segmented(titles: ["全部", "待处理", "已处理"], index: reportFilterBinding, accent: accent)
                }
                Card {
                    if store.adminReports.isEmpty {
                        Text("没有举报记录").font(.system(size: 13)).foregroundColor(DS.muted)
                            .frame(maxWidth: .infinity).padding(.vertical, 18)
                    } else {
                        ForEach(store.adminReports.indices, id: \.self) { i in
                            reportRow(store.adminReports[i])
                            if i < store.adminReports.count - 1 { Divider().background(DS.cardLine) }
                        }
                    }
                }
            }
        }
    }

    private var reportFilterBinding: Binding<Int> {
        Binding(get: {
            store.reportFilter == "pending" ? 1 : (store.reportFilter == "handled" ? 2 : 0)
        }, set: { v in
            store.reportFilter = v == 1 ? "pending" : (v == 2 ? "handled" : "all")
            store.loadAdminReports(store.reportFilter)
        })
    }

    private func reportRow(_ r: Map) -> some View {
        let handled = r.b("handled")
        return VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text("#\(r.i("id")) \(r.s("target", "—"))")
                    .font(.system(size: 15, weight: .medium))
                Spacer()
                Text(handled ? "已处理" : "待处理")
                    .font(.system(size: 11))
                    .foregroundColor(handled ? DS.muted : accent)
            }
            Text(r.s("reason")).font(.system(size: 13)).foregroundColor(DS.text2)
            Text("举报人 \(r.s("reporter")) · \(Fmt.day(r.d("time")))")
                .font(.system(size: 11)).foregroundColor(DS.muted)
            if handled {
                Text("处理：\(r.s("result") == "reject" ? "驳回" : "受理") · \(r.s("handler"))")
                    .font(.system(size: 11)).foregroundColor(DS.muted)
            } else {
                HStack(spacing: 8) {
                    smallButton("受理", tint: accent) {
                        ask("处理说明", "可选，填一句话") { note in
                            store.handleReport(op: "accept", id: r.i("id"), note: note)
                        }
                    }
                    smallButton("驳回", tint: DS.muted) {
                        ask("驳回理由", "可选，填一句话") { note in
                            store.handleReport(op: "reject", id: r.i("id"), note: note)
                        }
                    }
                }
                .padding(.top, 2)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: 惩罚

    private var penaltiesTab: some View {
        ScrollView {
            Card {
                HStack {
                    Text("生效中的惩罚").font(.system(size: 16, weight: .semibold))
                    Spacer()
                    smallButton("刷新", tint: accent) { store.loadPenalties() }
                }
                if store.penalties.isEmpty {
                    Text("现在没有生效中的惩罚").font(.system(size: 13)).foregroundColor(DS.muted)
                        .frame(maxWidth: .infinity).padding(.vertical, 18)
                } else {
                    ForEach(store.penalties.indices, id: \.self) { i in
                        let p = store.penalties[i]
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.s("name", p.s("player"))).font(.system(size: 15))
                                Text("\(p.s("type")) · \(p.s("reason", "未填写"))")
                                    .font(.system(size: 11.5)).foregroundColor(DS.muted)
                            }
                            Spacer()
                            smallButton("解除", tint: .red) {
                                store.penalty(op: "release", type: p.s("type"),
                                              name: p.s("name", p.s("player")))
                            }
                        }
                        .padding(.vertical, 7)
                        if i < store.penalties.count - 1 { Divider().background(DS.cardLine) }
                    }
                }
            }
        }
    }

    // MARK: 假人

    private var fakesTab: some View {
        ScrollView {
            Card {
                HStack {
                    Text("假人").font(.system(size: 16, weight: .semibold))
                    Spacer()
                    smallButton("刷新", tint: accent) { store.loadFakes() }
                }
                if !store.fakesAvailable {
                    Text("服务器没装 FakePlayerPlugin").font(.system(size: 13)).foregroundColor(DS.muted)
                        .padding(.vertical, 12)
                } else {
                    TextField("假人名字", text: $query)
                        .padding(.horizontal, 12).padding(.vertical, 10)
                        .background(DS.chip)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    HStack(spacing: 8) {
                        smallButton("添加", tint: accent) { store.fakeOp("add", name: query) }
                        smallButton("移除", tint: .red) { store.fakeOp("remove", name: query) }
                        smallButton("重生", tint: DS.muted) { store.fakeOp("respawn", name: query) }
                    }
                }
                ForEach(store.fakes.indices, id: \.self) { i in
                    let f = store.fakes[i]
                    HStack(spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(f.s("name")).font(.system(size: 15))
                            Text(f.b("online") ? "在线" : "离线").font(.system(size: 11)).foregroundColor(DS.muted)
                        }
                        Spacer()
                        smallButton("移除", tint: .red) { store.fakeOp("remove", name: f.s("name")) }
                    }
                    .padding(.vertical, 6)
                }
            }
        }
    }

    // MARK: 工具

    private func openDetail(_ name: String) {
        store.loadPlayerDetail(name)
        showDetail = true
    }

    private func ask(_ title: String, _ hint: String, _ action: @escaping (String) -> Void) {
        promptTitle = title
        promptHint = hint
        promptAction = action
        showPrompt = true
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
}

/// 玩家详情：惩罚、解除、最近被举报
struct PlayerDetailSheet: View {
    @EnvironmentObject var store: AppState
    @Environment(\.presentationMode) private var mode

    @State private var showPrompt = false
    @State private var promptTitle = ""
    @State private var promptHint = ""
    @State private var promptAction: (String) -> Void = { _ in }

    private var accent: Color { store.accent.color }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                HStack {
                    Text(store.detailName).font(.system(size: 20, weight: .bold))
                    Spacer()
                    Button { mode.wrappedValue.dismiss() } label: {
                        Text("关闭").font(.system(size: 14)).foregroundColor(accent)
                    }
                    .buttonStyle(PlainButtonStyle())
                }

                Card {
                    InfoRow(label: "状态", value: store.detail.b("online") ? "在线" : "离线")
                    InfoRow(label: "金币", value: store.detail.b("hasCoins") ? Fmt.money(store.detail.d("coins")) : "—")
                    InfoRow(label: "点券", value: store.detail.b("hasPoints") ? "\(store.detail.i("points"))" : "—")
                    InfoRow(label: "等级", value: "\(store.detail.i("level"))")
                    InfoRow(label: "在线时长", value: Fmt.hours(store.detail.d("playtimeHours")))
                    InfoRow(label: "身份", value: store.detail.m("identity").s("label", store.detail.s("role", "无")))
                    InfoRow(label: "队伍", value: Fmt.plain(store.detail.s("team", "无")))
                    InfoRow(label: "头衔", value: store.detail.s("titleName", "无"))
                    InfoRow(label: "封禁",
                            value: store.detail.b("banned") ? "是 · \(store.detail.s("banReason", "未填写"))" : "否",
                            accent: store.detail.b("banned") ? .red : nil)
                }

                if store.canMute || store.canBan {
                    Card {
                        Text("惩罚").font(.system(size: 15, weight: .semibold))
                        Text(store.isModeratorOnly ? "协管：分钟数必填，1 ~ 43200（最多 30 天）"
                                                   : "分钟数留空 = 永久")
                            .font(.system(size: 11.5)).foregroundColor(DS.muted)
                        penaltyButtons
                        Divider().background(DS.cardLine)
                        Text("解除").font(.system(size: 15, weight: .semibold))
                        releaseButtons
                    }
                }

                if !store.detailReports.isEmpty {
                    Card {
                        Text("最近被举报").font(.system(size: 15, weight: .semibold))
                        ForEach(store.detailReports.prefix(5).indices, id: \.self) { i in
                            let r = store.detailReports[i]
                            HStack {
                                Text("#\(r.i("id")) \(r.s("reason"))").font(.system(size: 13))
                                Spacer()
                                Text(r.b("handled") ? "已处理" : "待处理")
                                    .font(.system(size: 11)).foregroundColor(DS.muted)
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
                Spacer().frame(height: 20)
            }
            .padding(16)
        }
        .onAppear { if store.detail.isEmpty { store.loadPlayerDetail(store.detailName) } }
        .sheet(isPresented: $showPrompt) {
            PromptSheet(title: self.promptTitle, hint: self.promptHint) { text in
                self.promptAction(text)
            }
        }
    }

    private var penaltyButtons: some View {
        VStack(spacing: 8) {
            if store.canMute {
                HStack(spacing: 8) {
                    actionButton("禁言") { applyPenalty("banmsg") }
                    actionButton("解除禁言", tint: DS.muted) { releasePenalty("banmsg") }
                }
            }
            if store.canBan {
                HStack(spacing: 8) {
                    actionButton("禁止移动") { applyPenalty("banmove") }
                    actionButton("禁止交互") { applyPenalty("banblock") }
                }
                HStack(spacing: 8) {
                    actionButton("全部禁止") { applyPenalty("all") }
                    actionButton("封禁", tint: .red) { applyPenalty("ban") }
                }
            }
        }
    }

    private var releaseButtons: some View {
        VStack(spacing: 8) {
            if store.canMute {
                actionButton("解除禁言", tint: DS.muted) { releasePenalty("banmsg") }
            }
            if store.canBan {
                HStack(spacing: 8) {
                    actionButton("解除移动", tint: DS.muted) { releasePenalty("banmove") }
                    actionButton("解除交互", tint: DS.muted) { releasePenalty("banblock") }
                }
                HStack(spacing: 8) {
                    actionButton("解除全部", tint: DS.muted) { releasePenalty("all") }
                    actionButton("解除封禁", tint: DS.muted) { releasePenalty("ban") }
                }
            }
        }
    }

    private func applyPenalty(_ type: String) {
        promptTitle = "处罚时长"
        promptHint = store.isModeratorOnly ? "分钟数（1 ~ 43200）" : "分钟数（留空 = 永久）"
        promptAction = { text in
            let mins = Int(text.trimmingCharacters(in: .whitespaces)) ?? 0
            store.penalty(op: "apply", type: type, name: store.detailName, minutes: mins, reason: "")
        }
        showPrompt = true
    }

    private func releasePenalty(_ type: String) {
        store.penalty(op: "release", type: type, name: store.detailName)
    }

    private func actionButton(_ title: String, tint: Color? = nil, action: @escaping () -> Void) -> some View {
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
}

import SwiftUI

/// 服务器管理：在线 / 玩家 / 举报 / 惩罚 / 假人 / 控制台。
/// 分段用系统 Picker，列表用系统 List，表单弹层用系统 Form + Picker + TextField。
struct AdminView: View {
    @EnvironmentObject var store: AppState

    @State private var tab = 0
    @State private var query = ""
    @State private var detailName: String? = nil
    @State private var reportSheet: Map? = nil
    @State private var reportNote = ""
    @State private var newFake = ""
    @State private var cmd = ""

    private var tabs: [String] {
        store.canBan
            ? ["在线", "玩家", "举报", "惩罚", "假人", "控制台"]
            : ["在线", "玩家", "举报", "惩罚", "控制台"]
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { item in
                    Text(item.element).tag(item.offset)
                }
            }
            .pickerStyle(SegmentedPickerStyle())
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            content
        }
        .onAppear { reload() }
        .sheet(item: Binding(get: { detailName.map { NameBox(id: $0) } },
                             set: { detailName = $0?.id })) { box in
            PlayerDetailSheet(name: box.id).environmentObject(self.store)
        }
        .sheet(item: Binding(get: { reportSheet.map { ReportBox(row: $0) } },
                             set: { if $0 == nil { reportSheet = nil } })) { box in
            handleSheet(box.row)
        }
    }

    private var current: String {
        let i = min(max(tab, 0), tabs.count - 1)
        return tabs[i]
    }

    @ViewBuilder
    private var content: some View {
        if current == "在线" { onlineTab }
        else if current == "玩家" { playersTab }
        else if current == "举报" { reportsTab }
        else if current == "惩罚" { penaltiesTab }
        else if current == "假人" { fakesTab }
        else { consoleTab }
    }

    private func reload() {
        Task { await store.loadOnline() }
        store.loadStaff()
    }

    // MARK: - 在线

    private var onlineTab: some View {
        List {
            Section(header: Text("在线玩家（\(store.online.count)）")) {
                if store.online.isEmpty {
                    Text("现在没人在线").foregroundColor(DS.muted)
                }
                ForEach(store.online) { p in
                    Button(action: { detailName = p.id }) {
                        HStack {
                            Circle().fill(DS.green).frame(width: 8, height: 8)
                            Text(p.id).foregroundColor(DS.text)
                            Spacer()
                            Text(p.role).font(.system(size: 12)).foregroundColor(DS.muted)
                            Image(systemName: "chevron.right")
                                .font(.system(size: 12)).foregroundColor(DS.muted)
                        }
                    }
                }
            }

            Section(header: Text("管理层（\(store.staff.count) 人，在不在线都列）")) {
                if store.staff.isEmpty {
                    Text("没拿到管理层名单").foregroundColor(DS.muted)
                }
                ForEach(store.staff.indices, id: \.self) { i in
                    let row = store.staff[i]
                    let n = row.s("name")
                    Button(action: { detailName = n }) {
                        HStack(spacing: 8) {
                            Text(roleIcon(row.s("role")))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(n).foregroundColor(DS.text)
                                Text(row.s("role"))
                                    .font(.system(size: 11)).foregroundColor(DS.muted)
                            }
                            Spacer()
                            Text(row.b("online") ? "在线" : "离线")
                                .font(.system(size: 12))
                                .foregroundColor(row.b("online") ? DS.green : DS.muted)
                        }
                    }
                }
            }
        }
        .listStyle(GroupedListStyle())
    }

    private func roleIcon(_ role: String) -> String {
        if role.contains("副") { return "🎖" }
        if role.contains("服主") { return "👑" }
        if role.contains("管理员") { return "🛡" }
        return "🔧"
    }

    // MARK: - 玩家

    private var playersTab: some View {
        List {
            Section {
                HStack {
                    TextField("搜玩家名字", text: $query)
                        .textFieldStyle(RoundedBorderTextFieldStyle())
                        .disableAutocorrection(true)
                        .autocapitalization(.none)
                    Button("搜") { store.searchPlayers(query) }
                }
            }
            Section(header: Text("搜索结果（\(store.searchResults.count)）")) {
                if store.searchResults.isEmpty {
                    Text("输入名字搜一下").foregroundColor(DS.muted)
                }
                ForEach(store.searchResults.indices, id: \.self) { i in
                    let r = store.searchResults[i]
                    let n = r.s("name")
                    Button(action: { detailName = n }) {
                        HStack {
                            Text(n).foregroundColor(DS.text)
                            Spacer()
                            if r.b("banned") {
                                Text("已封禁").font(.system(size: 11)).foregroundColor(.red)
                            }
                            Text(r.b("online") ? "在线" : "离线")
                                .font(.system(size: 12))
                                .foregroundColor(r.b("online") ? DS.green : DS.muted)
                        }
                    }
                }
            }
        }
        .listStyle(GroupedListStyle())
    }

    // MARK: - 举报

    private var reportsTab: some View {
        List {
            Section {
                Picker("", selection: Binding(get: { store.reportFilter }, set: { f in
                    store.reportFilter = f
                    store.loadAdminReports(f)
                })) {
                    Text("全部").tag("all")
                    Text("待处理").tag("pending")
                    Text("已处理").tag("handled")
                }
                .pickerStyle(SegmentedPickerStyle())
            }
            Section(header: Text("共 \(store.reportTotal) 条 · 已处理 \(store.reportHandled)")) {
                if store.adminReports.isEmpty {
                    Text("没有举报").foregroundColor(DS.muted)
                }
                ForEach(store.adminReports.indices, id: \.self) { i in
                    let r = store.adminReports[i]
                    let handled = r.b("handled")
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("#\(r.i("id")) \(r.s("target"))")
                                .font(.system(size: 14, weight: .semibold))
                            Spacer()
                            Text(handled ? "已处理" : "待处理")
                                .font(.system(size: 11))
                                .foregroundColor(handled ? DS.muted : Color.orange)
                        }
                        Text(r.s("reason")).font(.system(size: 13)).foregroundColor(DS.text)
                        Text("\(r.s("reporter")) · \(r.s("time"))")
                            .font(.system(size: 11)).foregroundColor(DS.muted)
                        if handled, !r.s("note").isEmpty {
                            Text("处理说明：\(r.s("note"))")
                                .font(.system(size: 11)).foregroundColor(DS.muted)
                        }
                        if !handled {
                            HStack(spacing: 14) {
                                Button("受理") {
                                    reportNote = ""
                                    reportSheet = r
                                }
                                Button("驳回") {
                                    reportNote = ""
                                    reportSheet = r
                                }
                            }
                            .font(.system(size: 13))
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .listStyle(GroupedListStyle())
        .onAppear { store.loadAdminReports(store.reportFilter) }
    }

    private func handleSheet(_ row: Map) -> some View {
        NavigationView {
            Form {
                Section(header: Text("举报 #\(row.i("id")) · \(row.s("target"))")) {
                    Text(row.s("reason"))
                    Text("举报人：\(row.s("reporter"))").font(.system(size: 12))
                }
                Section(header: Text("处理说明（可不填）")) {
                    TextField("比如：已警告、已封禁 3 天", text: $reportNote)
                }
                Section {
                    Button("受理这条举报") {
                        store.handleReport(op: "accept", id: row.i("id"), note: reportNote)
                        reportSheet = nil
                    }
                    Button("驳回这条举报") {
                        store.handleReport(op: "reject", id: row.i("id"), note: reportNote)
                        reportSheet = nil
                    }
                }
            }
            .navigationBarTitle(Text("处理举报"), displayMode: .inline)
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    // MARK: - 惩罚

    private var penaltiesTab: some View {
        List {
            Section(header: Text("生效中的惩罚（\(store.penalties.count)）")) {
                if store.penalties.isEmpty {
                    Text("现在没有生效中的惩罚").foregroundColor(DS.muted)
                }
                ForEach(store.penalties.indices, id: \.self) { i in
                    let p = store.penalties[i]
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(p.s("name")).font(.system(size: 14, weight: .semibold))
                            Text("\(p.s("type")) \(p.s("reason"))")
                                .font(.system(size: 11)).foregroundColor(DS.muted)
                        }
                        Spacer()
                        Button("解除") {
                            store.penalty(op: "release", type: p.s("type"), name: p.s("name"))
                        }
                        .font(.system(size: 13))
                    }
                }
            }
            Section {
                Button("刷新惩罚列表") { store.loadPenalties() }
            }
        }
        .listStyle(GroupedListStyle())
        .onAppear { store.loadPenalties() }
    }

    // MARK: - 假人

    private var fakesTab: some View {
        List {
            if !store.fakesAvailable {
                Text("服务器没装假人插件，这一页暂时没用").foregroundColor(DS.muted)
            } else {
                Section(header: Text("配置")) {
                    Picker("游戏模式", selection: Binding(
                        get: { store.fakeSettings.s("gamemode", "CREATIVE") },
                        set: { store.setFakeGamemode($0) }
                    )) {
                        Text("创造").tag("CREATIVE")
                        Text("生存").tag("SURVIVAL")
                        Text("冒险").tag("ADVENTURE")
                        Text("旁观").tag("SPECTATOR")
                    }
                    Toggle("无敌", isOn: Binding(
                        get: { store.fakeSettings.b("invulnerable") },
                        set: { store.setFakeInvulnerable($0) }
                    ))
                }
                Section(header: Text("假人（\(store.fakes.count)）")) {
                    ForEach(store.fakes.indices, id: \.self) { i in
                    let f = store.fakes[i]
                        let n = f.s("name")
                        HStack {
                            Text(n)
                            Spacer()
                            Button("移除") { store.fakeOp("remove", name: n) }
                                .font(.system(size: 13)).foregroundColor(.red)
                            Button("重生") { store.fakeOp("respawn", name: n) }
                                .font(.system(size: 13))
                        }
                    }
                }
                Section(header: Text("加一个假人")) {
                    HStack {
                        TextField("假人名字", text: $newFake)
                            .textFieldStyle(RoundedBorderTextFieldStyle())
                            .disableAutocorrection(true)
                        Button("添加") {
                            let n = newFake.trimmingCharacters(in: .whitespaces)
                            guard !n.isEmpty else { return }
                            store.fakeOp("add", name: n)
                            newFake = ""
                        }
                    }
                }
            }
        }
        .listStyle(GroupedListStyle())
        .onAppear { store.loadFakes() }
    }

    // MARK: - 控制台

    private var consoleTab: some View {
        List {
            Section(header: Text("服务器控制台（以服务器身份执行，仅服主）")) {
                HStack {
                    TextField("比如：say 大家好", text: $cmd)
                        .textFieldStyle(RoundedBorderTextFieldStyle())
                        .disableAutocorrection(true)
                        .autocapitalization(.none)
                    Button("执行") {
                        store.runCommand(cmd, su: true)
                        cmd = ""
                    }
                }
                Text("输出").font(.system(size: 12)).foregroundColor(DS.muted)
                Text(store.suOutput.isEmpty ? "（还没有输出）" : store.suOutput)
                    .font(.system(size: 12, design: .monospaced))
                    .foregroundColor(store.suOk ? DS.text : Color.red)
            }
        }
        .listStyle(GroupedListStyle())
    }
}

// MARK: - sheet(item:) 需要 Identifiable

struct NameBox: Identifiable {
    let id: String
}

struct ReportBox: Identifiable {
    let row: Map
    var id: String { "\(row.i("id"))-\(row.s("target"))" }
}

// MARK: - 玩家详情

struct PlayerDetailSheet: View {
    @EnvironmentObject var store: AppState
    @Environment(\.presentationMode) var presentation
    let name: String

    @State private var showPenalty = false
    @State private var showBan = false
    @State private var banReason = ""

    var body: some View {
        NavigationView {
            List {
                Section(header: Text(name)) {
                    row("状态", store.detail.b("online") ? "在线" : "离线",
                        color: store.detail.b("online") ? DS.green : DS.muted)
                    row("身份", store.detail.m("identity").s("label", store.detail.s("role")))
                    row("金币", store.detail.b("hasCoins")
                        ? Fmt.money(store.detail.d("coins")) : "—")
                    row("点券", store.detail.b("hasPoints") ? "\(store.detail.i("points"))" : "—")
                    row("等级", "\(store.detail.i("level"))")
                    row("在线时长", "\(store.detail.i("playtimeHours")) 小时")
                    if !store.detail.s("team").isEmpty {
                        row("队伍", store.detail.s("team"))
                    }
                    if !store.detail.s("titleName").isEmpty {
                        row("头衔", store.detail.s("titleName"))
                    }
                    if store.detail.b("banned") {
                        row("封禁", store.detail.s("banReason"), color: Color.red)
                    }
                }

                Section(header: Text("惩罚")) {
                    ForEach(penaltyTypes, id: \.0) { t in
                        HStack {
                            Text(t.1)
                            Spacer()
                            if store.detailPenalties.b(t.0) {
                                Text("生效中").font(.system(size: 12)).foregroundColor(Color.orange)
                                Button("解除") {
                                    store.penalty(op: "release", type: t.0, name: name)
                                }.font(.system(size: 13))
                            } else {
                                Button("执行") {
                                    showPenalty = true
                                }.font(.system(size: 13))
                            }
                        }
                    }
                    Button("自定义惩罚（时长 / 原因）") {
                        showPenalty = true
                    }
                    if store.canBan {
                        Button("封禁 / 解封这个玩家") { showBan = true }
                    }
                }

                if !store.detailReports.isEmpty {
                    Section(header: Text("最近被举报")) {
                        ForEach(store.detailReports.indices, id: \.self) { i in
                        let r = store.detailReports[i]
                            VStack(alignment: .leading, spacing: 2) {
                                Text("#\(r.i("id")) \(r.s("reason"))").font(.system(size: 13))
                                Text(r.s("time")).font(.system(size: 11)).foregroundColor(DS.muted)
                            }
                        }
                    }
                }
            }
            .listStyle(GroupedListStyle())
            .navigationBarTitle(Text("玩家详情"), displayMode: .inline)
            .navigationBarItems(trailing: Button("关闭") { presentation.wrappedValue.dismiss() })
            .onAppear { store.loadPlayerDetail(name) }
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .sheet(isPresented: $showPenalty) {
            PenaltySheet(name: name).environmentObject(self.store)
        }
        .sheet(isPresented: $showBan) {
            NavigationView {
                Form {
                    Section(header: Text("封禁 \(name)")) {
                        TextField("原因（必填）", text: $banReason)
                    }
                    Section(footer: Text("0 分钟表示永久封禁（只有管理员能永久）。")) {
                        Button("封禁") {
                            store.ban(name,
                                      reason: banReason.isEmpty ? "违反服务器规则" : banReason,
                                      minutes: 0)
                            showBan = false
                        }
                        Button("解封") {
                            store.unban(name)
                            showBan = false
                        }
                    }
                }
                .navigationBarTitle(Text("封禁"), displayMode: .inline)
                .navigationBarItems(trailing: Button("关闭") { showBan = false })
            }
            .navigationViewStyle(StackNavigationViewStyle())
        }
    }

    private var penaltyTypes: [(String, String)] {
        [("banmsg", "禁言"), ("banmove", "禁止移动"), ("banblock", "禁止交互"), ("all", "全部限制")]
    }

    private func row(_ k: String, _ v: String, color: Color = DS.text) -> some View {
        HStack {
            Text(k).foregroundColor(DS.muted)
            Spacer()
            Text(v).foregroundColor(color)
        }
        .font(.system(size: 14))
    }
}

// MARK: - 惩罚表单

struct PenaltySheet: View {
    @EnvironmentObject var store: AppState
    @Environment(\.presentationMode) var presentation
    let name: String

    @State private var type = "banmsg"
    @State private var minutes = "10"
    @State private var reason = ""

    private var minuteValue: Int { Int(minutes) ?? -1 }
    private var valid: Bool {
        if store.canBan {
            return (minuteValue == 0) || (minuteValue >= 1 && minuteValue <= 43200)
        }
        return minuteValue >= 1 && minuteValue <= 43200 && !reason.isEmpty
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("对 \(name) 执行")) {
                    Picker("惩罚类型", selection: $type) {
                        Text("禁言").tag("banmsg")
                        Text("禁止移动").tag("banmove")
                        Text("禁止交互").tag("banblock")
                        Text("全部限制").tag("all")
                    }
                    HStack {
                        Text("时长（分钟）")
                        Spacer()
                        TextField("10", text: $minutes)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .frame(width: 90)
                    }
                    TextField("原因，比如：刷屏骂人", text: $reason)
                }
                Section(footer: Text(store.canBan
                    ? "管理员可填 1~43200 分钟；填 0 表示永久。"
                    : "协管只能填 1~43200 分钟（最长 30 天），原因必须写。")) {
                    Button("确定执行") {
                        store.penalty(op: "apply", type: type, name: name,
                                      minutes: minuteValue,
                                      reason: reason.isEmpty ? "App 操作" : reason)
                        presentation.wrappedValue.dismiss()
                    }
                    .disabled(!valid)
                }
            }
            .navigationBarTitle(Text("惩罚"), displayMode: .inline)
            .navigationBarItems(trailing: Button("取消") { presentation.wrappedValue.dismiss() })
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
}

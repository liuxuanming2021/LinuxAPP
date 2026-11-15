import SwiftUI

/// 服务器管理：在线 / 玩家 / 举报 / 惩罚 / 假人 / 控制台。
/// 分段用系统 Picker，列表用系统 List，表单弹层用系统 Form + Picker + TextField。
struct AdminView: View {
    @EnvironmentObject var store: AppState

    @State private var tab = 0
    @State private var query = ""
    @State private var searchMode = false
    @State private var detailName: String? = nil
    @State private var reportSheet: Map? = nil
    @State private var reportNote = ""
    @State private var newFake = ""
    @State private var fakeName: String? = nil
    @State private var cmd = ""
    @State private var spawnEdit: SpawnEdit? = nil

    /// 页签按权限给：假人要 fake 权限，控制台要 su（只有服主）。协管看到前两个。
    private var tabs: [String] {
        var list = ["玩家", "举报与惩罚"]
        if store.canFake { list.append("假人") }
        if store.canManage { list.append("刷怪") }
        if store.canSu { list.append("控制台") }
        if store.isSuperUser { list.append("插件") }
        return list
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: Binding(get: { tab }, set: { v in
                tab = v
                store.adminTab = v
                range()
            })) {
                ForEach(Array(tabs.enumerated()), id: \.offset) { item in
                    Text(item.element).tag(item.offset)
                }
            }
            .pickerStyle(SegmentedPickerStyle())
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            content
        }
        .onAppear { range(); store.adminTab = tab }
        .sheet(item: Binding(get: { detailName.map { NameBox(id: $0) } },
                             set: { detailName = $0?.id })) { box in
            PlayerDetailSheet(name: box.id).environmentObject(self.store)
        }
        .sheet(item: Binding(get: { reportSheet.map { ReportBox(row: $0) } },
                             set: { if $0 == nil { reportSheet = nil } })) { box in
            handleSheet(box.row)
        }
        .sheet(item: Binding(get: { fakeName.map { NameBox(id: $0) } },
                             set: { fakeName = $0?.id })) { box in
            FakeDetailSheet(name: box.id).environmentObject(self.store)
        }
    }

    private var current: String {
        let i = min(max(tab, 0), tabs.count - 1)
        return tabs[i]
    }

    @ViewBuilder
    private var content: some View {
        if current == "玩家" { playersTab }
        else if current == "举报与惩罚" { casesTab }
        else if current == "假人" { fakesTab }
        else if current == "刷怪" { spawnTab }
        else if current == "控制台" { consoleTab }
        else { LinuxTab().environmentObject(store) }
    }

    private func range() {
        switch current {
        case "玩家":
            Task { await store.loadOnline() }
            store.loadStaff()
        case "举报与惩罚":
            store.loadAdminReports()
            store.loadPenalties()
        case "假人":
            store.loadFakes()
        case "刷怪":
            store.loadSpawn()
        case "插件":
            store.loadLinux()
        default:
            break
        }
    }

    // MARK: - 玩家（搜索 / 在线 / 管理层）

    private var playersTab: some View {
        List {
            Section {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 13))
                        .foregroundColor(DS.muted)
                    TextField("搜玩家名字", text: $query)
                        .disableAutocorrection(true)
                        .autocapitalization(.none)
                    if searchMode {
                        Button("返回") { exitSearch() }
                            .font(.system(size: 14))
                    } else {
                        Button("搜索") { enterSearch() }
                            .font(.system(size: 14))
                            .disabled(query.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }

            if searchMode {
                Section(header: Text("搜索结果（\(store.searchResults.count)）")) {
                    if store.searchResults.isEmpty {
                        Text("没有找到这个名字").foregroundColor(DS.muted)
                    }
                    ForEach(store.searchResults.indices, id: \.self) { i in
                        searchRow(store.searchResults[i])
                    }
                }
            } else {
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
                                Text(AppState.roleLabel(p.role))
                                    .font(.system(size: 12)).foregroundColor(DS.muted)
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
                                Text(roleIcon(row))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(n).foregroundColor(DS.text)
                                    Text(AppState.roleLabel(row.s("role")))
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
        }
        .listStyle(GroupedListStyle())
    }

    private func searchRow(_ r: Map) -> some View {
        let n = r.s("name")
        return Button(action: { detailName = n }) {
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

    private func enterSearch() {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return }
        searchMode = true
        store.searchPlayers(q)
    }

    private func exitSearch() {
        searchMode = false
        query = ""
        store.searchResults = []
    }

    private func roleIcon(_ row: Map) -> String {
        switch row.i("rank") {
        case 1: return "👑"
        case 2: return "🎖"
        case 3: return "🛡"
        default: return "🔧"
        }
    }

    // MARK: - 举报与惩罚

    private var casesTab: some View {
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

            Section(header: Text("举报 · 共 \(store.reportTotal) 条 · 已处理 \(store.reportHandled)")) {
                if store.adminReports.isEmpty {
                    Text("没有举报").foregroundColor(DS.muted)
                }
                ForEach(store.adminReports.indices, id: \.self) { i in
                    reportRow(store.adminReports[i])
                }
            }

            Section(header: Text("生效中的惩罚（\(store.penalties.count)）")) {
                if store.penalties.isEmpty {
                    Text("现在没有生效中的惩罚").foregroundColor(DS.muted)
                }
                ForEach(store.penalties.indices, id: \.self) { i in
                    penaltyRow(store.penalties[i])
                }
            }
        }
        .listStyle(GroupedListStyle())
        .onAppear {
            store.loadAdminReports(store.reportFilter)
            store.loadPenalties()
        }
    }

    private func reportRow(_ r: Map) -> some View {
        let handled = r.b("handled")
        return VStack(alignment: .leading, spacing: 4) {
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
                HStack(spacing: 16) {
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

    private func penaltyRow(_ p: Map) -> some View {
        let who = p.s("player")
        return HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(who).font(.system(size: 14, weight: .semibold))
                Text("\(penaltyName(p.s("type"))) · \(p.s("reason"))")
                    .font(.system(size: 11.5)).foregroundColor(DS.muted)
                Text("执行者 \(p.s("operator", "—")) · \(expireText(p))")
                    .font(.system(size: 11)).foregroundColor(DS.muted)
            }
            Spacer()
            Button("解除") {
                store.penalty(op: "release", type: p.s("type"), name: who)
            }
            .font(.system(size: 13))
        }
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

    private func penaltyName(_ t: String) -> String {
        switch t {
        case "banmsg": return "禁言"
        case "banmove": return "禁止移动"
        case "banblock": return "禁止交互"
        case "all": return "全部限制"
        case "ban": return "封禁"
        default: return t
        }
    }

    /// 服务端给的是已经格式化好的字符串（"2026-11-14 15:20:18"），不是时间戳
    private func expireText(_ p: Map) -> String {
        let e = p.s("expire")
        if e.isEmpty { return "永久" }
        return "到期 " + e
    }

    // MARK: - 刷怪（LinuxSpawn 联动）

    private var spawnWorlds: [Map] {
        store.spawnInfo.a("worlds").map { $0 as? Map ?? [:] }.filter { !$0.isEmpty }
    }

    private var spawnTab: some View {
        List {
            if !store.spawnAvailable {
                Section {
                    Text("服务器没装 LinuxSpawn，或者版本低于 26.11.16（没有联动接口）")
                        .foregroundColor(DS.muted)
                }
            } else {
                spawnSummary
                spawnWorldSections
                spawnActions
            }
        }
        .listStyle(GroupedListStyle())
        .sheet(item: Binding(get: { spawnEdit }, set: { spawnEdit = $0 })) { edit in
            SpawnEditSheet(edit: edit).environmentObject(store)
        }
    }

    private var spawnSummary: some View {
        Section(header: Text("总览")) {
            InfoRow(label: "接管状态", value: store.spawnInfo.b("enabled") ? "已接管原版刷怪" : "已关闭")
            InfoRow(label: "服务器 TPS", value: String(format: "%.1f", store.spawnInfo.d("tps")))
            InfoRow(label: "单 tick 上限", value: "\(store.spawnInfo.i("maxCycles")) 轮")
            InfoRow(label: "低 TPS 保护", value: safetyText())
            InfoRow(label: "自动清理", value: cellCleaner())
        }
    }

    private var spawnWorldSections: some View {
        ForEach(Array(spawnWorlds.enumerated()), id: \.offset) { item in
            spawnSection(item.element)
        }
    }

    private func spawnSection(_ world: Map) -> some View {
        Section(header: Text(spawnHeader(world)), footer: Text(spawnFooter(world))) {
            ForEach(Array(spawnCategories.enumerated()), id: \.offset) { cat in
                spawnRow(world, cat.element[0], cat.element[1])
            }
            Button(action: { store.cleanSpawn(world: world.s("name")) }) {
                Text("清理这个世界的自然怪物").foregroundColor(store.accent.color)
            }
        }
    }

    private func spawnRow(_ world: Map, _ key: String, _ label: String) -> some View {
        Button(action: {
            spawnEdit = SpawnEdit(world: world.s("name"),
                                  category: key,
                                  label: label,
                                  value: mul(world, key))
        }) {
            HStack {
                Text(label).foregroundColor(DS.text)
                Spacer()
                Text(spawnValue(mul(world, key))).foregroundColor(DS.text2)
                Image(systemName: "chevron.right")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
            }
        }
    }

    private var spawnActions: some View {
        Section {
            Button(action: { cleanEverything() }) {
                Text("全部世界各清理一次").foregroundColor(store.accent.color)
            }
            Button(action: { store.reloadSpawn() }) {
                Text("让 LinuxSpawn 重读 config.yml").foregroundColor(store.accent.color)
            }
        }
    }

    private func cleanEverything() {
        for w in spawnWorlds { store.cleanSpawn(world: w.s("name")) }
    }

    private func safetyText() -> String {
        if !store.spawnInfo.b("safetyEnabled") { return "已关闭" }
        return "低于 \(store.spawnInfo.d("minTps")) 自动暂停补刷"
    }

    private var spawnCategories: [[String]] {
        [["monster", "怪物"], ["creature", "动物"], ["water-creature", "水生"], ["ambient", "环境"]]
    }

    private func mul(_ world: Map, _ key: String) -> Double {
        world.m("multipliers").d(key, 1.0)
    }

    private func spawnValue(_ value: Double) -> String {
        value == value.rounded() ? String(format: "%.0f ×", value) : String(format: "%.2f ×", value)
    }

    private func cellCleaner() -> String {
        let cleaner = store.spawnInfo.m("cleaner")
        if !cleaner.b("auto") { return "已关闭" }
        let left = cleaner.i("remaining")
        return "每 \(cleaner.i("interval")) 秒，剩 \(left < 0 ? 0 : left) 秒"
    }

    private func spawnHeader(_ world: Map) -> String {
        let name = world.s("name")
        let managed = world.b("managed") ? "接管中" : "不接管"
        return "\(name) · \(managed) · \(world.i("players")) 人在线"
    }

    private func spawnFooter(_ world: Map) -> String {
        let stats = world.m("stats").m("monster")
        if stats.isEmpty { return "" }
        return "怪物：每秒 \(String(format: "%.2f", stats.d("cyclesPerSecond"))) 轮原版循环，"
            + "生成 \(String(format: "%.2f", stats.d("mobsPerSecond"))) 只/秒，"
            + "单轮 \(String(format: "%.2f", stats.d("millisPerCycle"))) ms"
    }

    // MARK: - 假人

    private var fakesTab: some View {
        List {
            if !store.fakesAvailable {
                Text("服务器没装假人插件，这一页暂时没用").foregroundColor(DS.muted)
            } else {
                Section(header: Text("配置"),
                        footer: Text("改完会自动把已经召唤出来的假人各重生一次，设置马上生效。")) {
                    Picker(selection: Binding(
                        get: { store.fakeGamemode },
                        set: { store.setFakeGamemode($0) }
                    )) {
                        Text("创造").tag("CREATIVE")
                        Text("生存").tag("SURVIVAL")
                        Text("冒险").tag("ADVENTURE")
                        Text("旁观").tag("SPECTATOR")
                    } label: {
                        Text("游戏模式")
                    }
                    Toggle(isOn: Binding(
                        get: { store.fakeInvuln },
                        set: { store.setFakeInvulnerable($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("无敌")
                            Text("打开后打不掉血（新假人生效，已召唤的自动重生）")
                                .font(.system(size: 11)).foregroundColor(DS.muted)
                        }
                    }
                }
                Section(header: Text("假人（\(store.fakes.count)）"),
                        footer: Text("点一个假人进去看详情：坐标、创建信息、它自己的开关，还有重生 / 移除。")) {
                    ForEach(store.fakes.indices, id: \.self) { i in
                        let f = store.fakes[i]
                        let n = f.s("name")
                        Button(action: { fakeName = n }) {
                            HStack(spacing: 10) {
                                SkinHead(name: n, uuid: f.s("uuid"), size: 34, radius: 9)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(n).foregroundColor(DS.text)
                                    Text(f.b("running") ? "运行中" : (f.b("enabled") ? "已启用，未加载" : "已停用"))
                                        .font(.system(size: 11))
                                        .foregroundColor(f.b("running") ? DS.green : DS.muted)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.system(size: 12)).foregroundColor(DS.muted)
                            }
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
    @State private var banMinutes = ""

    var body: some View {
        NavigationView {
            List {
                Section(header: Text(name)) {
                    row("状态", store.detail.b("online") ? "在线" : "离线",
                        color: store.detail.b("online") ? DS.green : DS.muted)
                    row("身份", AppState.roleLabel(store.detail.m("identity").s("label", store.detail.s("role"))))
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
                    if activePenaltyKeys.isEmpty {
                        Button("惩罚该玩家") { showPenalty = true }
                            .font(.system(size: 15))
                    } else {
                        HStack {
                            Text("生效中：\(activePenalties.joined(separator: "、"))")
                                .font(.system(size: 13))
                                .foregroundColor(Color.orange)
                            Spacer()
                        }
                        Button("解除惩罚") {
                            for t in activePenaltyKeys {
                                store.penalty(op: "release", type: t, name: name)
                            }
                        }
                        .font(.system(size: 15))
                        Button("再惩罚一次") { showPenalty = true }
                            .font(.system(size: 15))
                    }
                    if store.canBan {
                        Button(store.detail.b("banned") ? "解封这个玩家" : "封禁这个玩家") { showBan = true }
                            .font(.system(size: 15))
                    }
                }

                if !store.detailReports.isEmpty {
                    Section(header: Text("最近被举报")) {
                        ForEach(store.detailReports.indices, id: \.self) { i in
                        let r = store.detailReports[i]
                            VStack(alignment: .leading, spacing: 2) {
                                HStack {
                                    Text("#\(r.i("id")) \(r.s("reason"))").font(.system(size: 13))
                                    Spacer()
                                    Text(reportLabel(r))
                                        .font(.system(size: 11.5))
                                        .foregroundColor(reportColor(r))
                                }
                                Text(reportMeta(r)).font(.system(size: 11)).foregroundColor(DS.muted)
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
                        TextField("时长（分钟），0 = 永久", text: $banMinutes)
                            .keyboardType(.numberPad)
                    }
                    Section(footer: Text("分钟数留空按 0 算：0 表示永久封禁（只有管理员能永久，协管只能 1~43200 分钟）。")) {
                        Button("封禁") {
                            store.ban(name,
                                      reason: banReason.isEmpty ? "违反服务器规则" : banReason,
                                      minutes: Int(banMinutes.trimmingCharacters(in: .whitespaces)) ?? 0)
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

    private var activePenaltyKeys: [String] {
        ["banmsg", "banmove", "banblock", "all"].filter { store.detailPenalties.b($0) }
    }

    private func penaltyName(_ t: String) -> String { penaltyTitle(t) }

    private var activePenalties: [String] {
        activePenaltyKeys.map { penaltyName($0) }
    }

    private func reportLabel(_ r: Map) -> String {
        if !r.b("handled") { return "待处理" }
        return r.s("result") == "reject" ? "已驳回" : "已接受"
    }

    private func reportColor(_ r: Map) -> Color {
        if !r.b("handled") { return DS.muted }
        return r.s("result") == "reject" ? Color.red : DS.green
    }

    private func reportMeta(_ r: Map) -> String {
        var parts: [String] = [r.s("time")]
        let h = r.s("handler")
        if !h.isEmpty { parts.append("处理人 " + h) }
        let n = r.s("note")
        if !n.isEmpty { parts.append(n) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
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

/// 惩罚类型的英文 key → 中文
func penaltyTitle(_ t: String) -> String {
    switch t {
    case "banmsg": return "禁言"
    case "banmove": return "禁止移动"
    case "banblock": return "禁止交互"
    case "all": return "全部限制"
    case "ban": return "封禁"
    default: return t
    }
}

/// 改倍率用得着的三个字段
struct SpawnEdit: Identifiable {
    let world: String
    let category: String
    let label: String
    let value: Double
    var id: String { world + "/" + category }
}

/// 改一个「世界 × 类别」的倍率（系统 Form + TextField）
struct SpawnEditSheet: View {
    @EnvironmentObject var store: AppState
    @Environment(\.presentationMode) var presentation
    let edit: SpawnEdit

    @State private var text: String

    init(edit: SpawnEdit) {
        self.edit = edit
        _text = State(initialValue: edit.value == edit.value.rounded()
                      ? String(format: "%.0f", edit.value) : String(format: "%.2f", edit.value))
    }

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("\(edit.world) 的\(edit.label)")) {
                    TextField("倍率，1 = 原版，0 = 不刷", text: $text)
                        .keyboardType(.decimalPad)
                }
                Section(footer: Text("1.0 = 原版频率，2.0 = 两倍，0.5 = 一半，0 = 该类别完全不刷。"
                                     + "改完立即生效，配置会写回 LinuxSpawn 的 config.yml。")) {
                    Button("保存") {
                        let value = Double(text.trimmingCharacters(in: .whitespaces)) ?? 1.0
                        store.setSpawn(world: edit.world, category: edit.category, value: value)
                        presentation.wrappedValue.dismiss()
                    }
                }
            }
            .navigationBarTitle("刷怪倍率", displayMode: .inline)
            .navigationBarItems(leading: Button("取消") { presentation.wrappedValue.dismiss() })
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }
}

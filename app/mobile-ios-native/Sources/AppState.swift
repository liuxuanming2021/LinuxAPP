import SwiftUI

struct ChatMessage: Identifiable {
    let id: Int
    let name: String
    let text: String
    let source: String
    let channel: String
    let time: Date
}

struct OnlinePlayer: Identifiable {
    let id: String
    let role: String
}

/// 全局状态。
/// iOS 13 没有 @StateObject / .task，所以用单例 + environmentObject 注入，
/// 所有网络回调都切回主线程再改 @Published，避免 UI 在后台线程刷新。
final class AppState: ObservableObject {
    static let shared = AppState()

    // 登录态
    @Published var loggedIn = false
    @Published var serverText = UserDefaults.standard.string(forKey: "server") ?? "https://api.nalxer.top:49283"
    @Published var name = ""
    @Published var role = "player"
    @Published var caps: [String: Bool] = [:]
    @Published var busy = false
    @Published var errorText: String?

    // 我的
    @Published var coins: Double = 0
    @Published var points: Int = 0
    @Published var level: Int = 0
    @Published var teamName: String = ""
    @Published var hasCoins = false
    @Published var hasPoints = false
    @Published var expPct: Double = 0      // 0~1，当前等级内的进度
    @Published var expInto = 0             // 当前等级已获得的经验
    @Published var expNext = 0             // 升到下一级需要的经验
    @Published var expTotal = 0            // 总经验
    @Published var checkInDays = 0
    @Published var signedToday = false
    @Published var signStreak = 0

    // 社交
    /// world / team。切频道先清空，否则会先看到上一个频道的消息（队伍频道"乱跳"就是这个）
    @Published var chatSince = 0
    @Published var channel = "world" {
        didSet {
            if oldValue != channel {
                messages = []
                chatSince = 0
            }
        }
    }
    @Published var messages: [ChatMessage] = []
    @Published var online: [OnlinePlayer] = []

    // 主题（选过的颜色存本地，冷启动要还原）
    @Published var accent: DS.Accent = .sky {
        didSet { UserDefaults.standard.set(accent.rawValue, forKey: "accent") }
    }

    /// 冷启动时先等 restore() 有结果，避免"明明登录了却闪一下登录页"
    @Published var booting = true

    // 提示条：发一条就把 seq +1，视图监听它弹提示
    @Published var toastText = ""
    @Published var toastSeq = 0

    // 头衔
    @Published var titles: [Map] = []
    @Published var activeTitle = ""      // 显示用的名字，比如「元老」
    @Published var activeTitleId = ""    // 服务端 data.active（小写 id）
    @Published var titleUnlocked = 0
    @Published var titleTotal = 0

    // 头像 / 身份 / 头衔 / 服务器版本
    @Published var uuid = ""
    @Published var titleName = ""
    @Published var teamTag = ""
    @Published var secure = false
    @Published var serverVersion = ""
    @Published var serverName = "LinuxAPP"

    // 队伍
    @Published var teamId = ""
    @Published var teamAvailable = false
    @Published var myTeams: [Map] = []
    @Published var serverTeams: [Map] = []

    // 管理：玩家 / 举报 / 惩罚 / 假人
    @Published var searchResults: [Map] = []
    @Published var detail: Map = [:]
    @Published var detailName = ""
    @Published var detailPenalties: Map = [:]
    @Published var detailReports: [Map] = []
    @Published var penalties: [Map] = []
    @Published var adminReports: [Map] = []
    @Published var reportFilter = "all"
    @Published var reportTotal = 0
    @Published var reportHandled = 0
    @Published var fakes: [Map] = []
    @Published var fakeSettings: Map = [:]
    @Published var fakesAvailable = false
    @Published var staff: [Map] = []
    @Published var tabIndex = 0
    // 多服务器（最多 5 台，可切换）
    @Published var multiServer = UserDefaults.standard.bool(forKey: "multiServer")
    @Published var servers: [[String: String]] = []
    @Published var activeServer = 0
    // 提醒开关
    @Published var notifyInApp = UserDefaults.standard.object(forKey: "notifyInApp") as? Bool ?? true
    @Published var notifyOut = UserDefaults.standard.object(forKey: "notifyOut") as? Bool ?? true            // 当前底部页签（0 我的 / 1 社交 / 2 头衔 / 3 管理 / 4 更多）
    @Published var adminTab = 0            // 当前管理页签（0 在线 / 1 玩家 / 2 举报 / 3 惩罚 / 4 假人 / 5 控制台）
    @Published var suOutput = ""
    @Published var suOk = true

    // 玩家侧举报
    @Published var myReports: [Map] = []
    @Published var reportsAvailable = false

    // 转账
    @Published var transferAvailable = false

    /// 角色英文 key → 中文（服务端 login 返回的是 owner / coowner / admin / moderator / player）
    var roleText: String { AppState.roleLabel(role) }

    /// 服务端有的地方给英文 key（owner/coowner/…），有的地方已经给中文，统一翻成中文
    static func roleLabel(_ raw: String) -> String {
        let t = raw.trimmingCharacters(in: .whitespaces)
        switch t.lowercased() {
        case "owner", "su": return "服主"
        case "coowner", "co-owner": return "副服主"
        case "mayor": return "市长"
        case "admin": return "管理员"
        case "moderator": return "协管"
        case "player": return "玩家"
        default: return t.isEmpty ? "玩家" : t
        }
    }

    var canManage: Bool { caps["view"] == true }
    var canMute: Bool { caps["mute"] == true }
    var canBan: Bool { caps["ban"] == true }
    var isModeratorOnly: Bool { canManage && !canBan }

    init() {
        if let raw = UserDefaults.standard.string(forKey: "accent"),
           let a = DS.Accent(rawValue: raw) { accent = a }
        applyServer()
        loadServers()
    }

    func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }

    func bootstrap() { applyServer() }

    // MARK: 服务器地址

    func applyServer() {
        var s = serverText.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { s = "https://api.nalxer.top:49283" }
        if !s.lowercased().hasPrefix("http") { s = "https://" + s }
        serverText = s
        UserDefaults.standard.set(s, forKey: "server")
        APIClient.shared.base = URL(string: s)
    }

    // MARK: 登录

    func login(name: String, password: String, done: @escaping (String?) -> Void) {
        applyServer()
        onMain { self.busy = true; self.errorText = nil }

        Task {
            do {
                try await APIClient.shared.handshake()
                self.onMain { self.secure = APIClient.shared.encrypted }
                let r = try await APIClient.shared.post("/api/login",
                                                       ["name": name, "password": password])
                guard (r["ok"] as? Bool) == true, let token = r["token"] as? String else {
                    let msg = (r["message"] as? String) ?? "登录失败"
                    self.onMain { self.busy = false; self.errorText = msg; done(msg) }
                    return
                }
                APIClient.shared.setToken(token)
                UserDefaults.standard.set(token, forKey: "token")
                self.saveToken(token, for: self.serverText)

                self.onMain {
                    self.name = (r["name"] as? String) ?? name
                    self.role = (r["role"] as? String) ?? "player"
                    if let c = r["caps"] as? [String: Bool] { self.caps = c }
                    self.loggedIn = true
                    self.busy = false
                    done(nil)
                }
                await self.refreshAll()
                self.startPolling()
            } catch {
                let msg = NetMsg.zh(error)
                self.onMain { self.busy = false; self.errorText = msg; done(msg) }
            }
        }
    }

    func logout() {
        // 退出要让服务端释放令牌，否则第二台设备登录时会被判成"已被占用"
        if let base = APIClient.shared.base,
           let token = UserDefaults.standard.string(forKey: "token"),
           let url = URL(string: "/api/logout", relativeTo: base) {
            var req = URLRequest(url: url)
            req.httpMethod = "POST"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
            req.httpBody = Data("{}".utf8)
            URLSession.shared.dataTask(with: req).resume()
        }
        UserDefaults.standard.removeObject(forKey: "token")
        APIClient.shared.setToken(nil)
        onMain {
            self.loggedIn = false
            self.messages = []
            self.name = ""
            self.caps = [:]
            self.secure = false
        }
    }

    func warmSounds() {
        AlertSound.shared.prepare()
    }

    func restore() async {
        defer { onMain { self.booting = false } }
        guard let token = UserDefaults.standard.string(forKey: "token") else { return }
        APIClient.shared.setToken(token)
        do {
            try await APIClient.shared.handshake()
            self.onMain { self.secure = APIClient.shared.encrypted }
            let me = try await APIClient.shared.get("/api/me")
            guard (me["ok"] as? Bool) == true else { throw APIError.status(401, "") }
            self.onMain {
                self.parseMe(me)
                self.loggedIn = true
            }
            await refreshAll()
            startPolling()
        } catch {
            APIClient.shared.setToken(nil)
            UserDefaults.standard.removeObject(forKey: "token")
        }
    }

    // MARK: 我的

    func refreshAll() async {
        await loadMe()
        await loadChat()
        await loadOnline()
        loadHealth()
    }

    func loadMe() async {
        guard loggedIn else { return }
        do {
            let r = try await APIClient.shared.get("/api/me")
            self.onMain { self.parseMe(r) }
        } catch { }
    }

    /// JSON 里的数字可能是 Int 也可能是 Double，统一转 Double
    private func num(_ v: Any?) -> Double {
        if let d = v as? Double { return d }
        if let i = v as? Int { return Double(i) }
        if let n = v as? NSNumber { return n.doubleValue }
        return 0
    }

    private func parseMe(_ r: [String: Any]) {
        let d = (r["data"] as? [String: Any]) ?? [:]
        if let n = d["name"] as? String, !n.isEmpty { name = n }
        if let ro = r["role"] as? String { role = ro }
        if let c = r["caps"] as? [String: Bool] { caps = c }
        if let c = d["coins"] as? Double { coins = c; hasCoins = true }
        if let p = d["points"] as? Int { points = p; hasPoints = true }
        if let l = d["level"] as? Int { level = l }
        if let t = d["team"] as? String { teamName = t }
        if let t2 = d["teamTag"] as? String { teamTag = t2 }
        if let u = d["uuid"] as? String { uuid = u }
        if let tn = d["titleName"] as? String { titleName = tn }
        if let days = d["checkInDays"] as? Int { checkInDays = days }
        expPct = num(d["exp"])
        expInto = Int(num(d["expIntoLevel"]))
        expNext = Int(num(d["expToNext"]))
        expTotal = Int(num(d["totalExp"]))
    }

    func loadCheckIn() async {
        guard loggedIn else { return }
        do {
            let r = try await APIClient.shared.get("/api/checkin")
            let info = (r["info"] as? [String: Any]) ?? [:]
            let claimed = (info["claimed"] as? Bool) ?? false
            let streak = (info["streak"] as? Int) ?? 0
            self.onMain {
                self.signedToday = claimed
                self.signStreak = streak
                self.checkInDays = streak
            }
        } catch { /* 静默：签到信息拉不到不影响主页面 */ }
    }

    func signIn() {
        Task {
            do {
                let r = try await APIClient.shared.post("/api/checkin", [:])
                let ok = (r["ok"] as? Bool) ?? false
                let msg = (r["message"] as? String) ?? (ok ? "签到成功" : "今天已经签过了")
                self.toast(msg)
                self.onMain { if ok { self.signedToday = true } }
                await self.loadCheckIn()
                self.loadMeAsync()
            } catch {
                self.toast(NetMsg.zh(error))
            }
        }
    }

    private func loadMeAsync() {
        Task { await loadMe() }
    }

    // MARK: 聊天

    private var chatBusy = false

    func loadChat() async {
        guard loggedIn, !chatBusy else { return }
        chatBusy = true
        defer { chatBusy = false }
        do {
            var q = "/api/chat?since=\(chatSince)&limit=80"
            if channel == "team" {
                if teamId.isEmpty {
                    // 还没拿到队伍 id 就先问一次，队伍频道必须带 team 参数
                    if let t = try? await APIClient.shared.get("/api/team") {
                        let first = (t.a("myTeams").first)?.s("id") ?? ""
                        let tid = first
                        self.onMain { self.teamId = tid; self.myTeams = t.a("myTeams") }
                        if tid.isEmpty { return }
                        q += "&channel=team&team=" + tid
                    } else {
                        return
                    }
                } else {
                    q += "&channel=team&team=" + teamId
                }
            }
            let r = try await APIClient.shared.get(q)
            guard let arr = r["messages"] as? [[String: Any]] else { return }
            let list: [ChatMessage] = arr.compactMap { m in
                guard let id = m["id"] as? Int else { return nil }
                let t = (m["time"] as? Double) ?? 0
                return ChatMessage(id: id,
                                   name: (m["name"] as? String) ?? "",
                                   text: (m["text"] as? String) ?? "",
                                   source: (m["source"] as? String) ?? "app",
                                   channel: (m["channel"] as? String) ?? "world",
                                   time: Date(timeIntervalSince1970: t / 1000))
            }
            // 服务端已经给了真消息，把本地那条占位的（id = -1）去掉。
            // 内容没变就不动数组，否则 SwiftUI 每 2 秒重排一次，滚动会跳。
            let fresh = list.filter { $0.id != -1 }
            let reset = (r["reset"] as? Bool) ?? false
            let last = (r["last"] as? Int) ?? 0
            self.onMain {
                if reset || self.messages.filter({ $0.id != -1 }).isEmpty {
                    self.messages = fresh
                } else {
                    // 先把我刚发的占位（id = -1）丢掉：服务端已经把它回过来了，
                    // 留着就会"发一条显示两条"。
                    let base = self.messages.filter { m in
                        guard m.id == -1 else { return true }
                        return !fresh.contains { f in
                            f.name.caseInsensitiveCompare(m.name) == .orderedSame
                                && f.text == m.text
                                && f.channel == m.channel
                        }
                    }
                    let known = Set(base.map { $0.id })
                    let add = fresh.filter { f in
                        guard !known.contains(f.id) else { return false }
                        // 同一个人在同一频道、2 秒内发的同样的内容，只留一条
                        return !base.contains { b in
                            b.name.caseInsensitiveCompare(f.name) == .orderedSame
                                && b.text == f.text
                                && b.channel == f.channel
                                && abs(b.time.timeIntervalSince(f.time)) < 2
                        }
                    }
                    self.messages = base
                    if !add.isEmpty {
                        self.messages.append(contentsOf: add)
                        self.remind(add)
                    }
                }
                if self.messages.count > 200 { self.messages.removeFirst(self.messages.count - 200) }
                if last > 0 { self.chatSince = last }
            }
        } catch { }
    }

    func loadOnline() async {
        guard loggedIn else { return }
        do {
            let r = try await APIClient.shared.get("/api/online")
            guard let arr = r["players"] as? [[String: Any]] else { return }
            let list: [OnlinePlayer] = arr.compactMap { p in
                guard let n = p["name"] as? String else { return nil }
                return OnlinePlayer(id: n, role: (p["role"] as? String) ?? "player")
            }
            self.onMain { self.online = list }
        } catch { }
    }

    func send(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        // 先把自己刚发的挂上去，等服务端回了再换成真的（不然要等下一轮轮询才看得见）
        let temp = ChatMessage(id: -1, name: name, text: t,
                               source: "app", channel: channel,
                               time: Date())
        onMain { self.messages.append(contentsOf: [temp]) }
        Task {
            do {
                _ = try await APIClient.shared.post("/api/chat", ["text": t, "channel": channel, "team": teamId])
                await self.loadChat()
            } catch {
                let msg = NetMsg.zh(error)
                self.onMain {
                    self.messages.removeAll { $0.id == -1 }
                    self.errorText = msg
                    self.toast(msg)
                }
            }
        }
    }

    private var timer: Timer?
    private var ticks = 0
    private func startPolling() {
        timer?.invalidate()
        ticks = 0
        timer = Timer(timeInterval: 1.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            Task {
                await self.loadChat()
                self.ticks += 1
                if self.ticks % 3 == 0 { await self.loadOnline() }
                // 「我的」页的金币点券等级也跟着刷，不用手动下拉
                if self.tabIndex == 0, self.ticks % 2 == 0 { await self.loadMe() }
                // 管理页：当前页的数据跟着一起刷，不用手动点刷新
                if self.canManage {
                    switch self.adminTab {
                    case 0:
                        self.loadStaff()
                    case 2:
                        if self.ticks % 2 == 0 { self.loadAdminReports() }
                    case 3:
                        if self.ticks % 2 == 0 { self.loadPenalties() }
                    case 4:
                        if self.ticks % 4 == 0 { self.loadFakes() }
                    default:
                        break
                    }
                }
            }
        }
        // 默认模式下滚动/弹键盘时定时器会被暂停，这就是"必须手动刷新才有新消息"的根因
        if let t = timer { RunLoop.main.add(t, forMode: .common) }
    }
}

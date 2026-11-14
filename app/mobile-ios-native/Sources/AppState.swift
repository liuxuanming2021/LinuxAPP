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
    @Published var expCurrent = 0
    @Published var expNeeded = 0
    @Published var checkInDays = 0
    @Published var signedToday = false
    @Published var signStreak = 0

    // 社交
    @Published var channel = "world"      // world / team
    @Published var messages: [ChatMessage] = []
    @Published var online: [OnlinePlayer] = []

    // 主题
    @Published var accent: DS.Accent = .sky

    // 提示条：发一条就把 seq +1，视图监听它弹提示
    @Published var toastText = ""
    @Published var toastSeq = 0

    // 头衔
    @Published var titles: [Map] = []
    @Published var activeTitle = ""
    @Published var titleUnlocked = 0
    @Published var titleTotal = 0

    // 队伍
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

    // 玩家侧举报
    @Published var myReports: [Map] = []
    @Published var reportsAvailable = false

    // 转账
    @Published var transferAvailable = false

    var canManage: Bool { caps["view"] == true }
    var canMute: Bool { caps["mute"] == true }
    var canBan: Bool { caps["ban"] == true }
    var isModeratorOnly: Bool { canManage && !canBan }

    init() { applyServer() }

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
                let r = try await APIClient.shared.post("/api/login",
                                                       ["name": name, "password": password])
                guard (r["ok"] as? Bool) == true, let token = r["token"] as? String else {
                    let msg = (r["message"] as? String) ?? "登录失败"
                    self.onMain { self.busy = false; self.errorText = msg; done(msg) }
                    return
                }
                APIClient.shared.setToken(token)
                UserDefaults.standard.set(token, forKey: "token")

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
                let msg = error.localizedDescription
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
        }
    }

    func restore() async {
        guard let token = UserDefaults.standard.string(forKey: "token") else { return }
        APIClient.shared.setToken(token)
        do {
            try await APIClient.shared.handshake()
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
    }

    func loadMe() async {
        guard loggedIn else { return }
        do {
            let r = try await APIClient.shared.get("/api/me")
            self.onMain { self.parseMe(r) }
        } catch { }
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
        if let days = d["checkInDays"] as? Int { checkInDays = days }
        if let e = d["exp"] as? [String: Any] {
            expCurrent = (e["current"] as? Int) ?? 0
            expNeeded = (e["needed"] as? Int) ?? 0
        }
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
                self.toast(error.localizedDescription)
            }
        }
    }

    private func loadMeAsync() {
        Task { await loadMe() }
    }

    // MARK: 聊天

    func loadChat() async {
        guard loggedIn else { return }
        do {
            let q = channel == "world" ? "/api/chat?since=0&limit=80"
                                       : "/api/chat?channel=team&since=0&limit=80"
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
            self.onMain { self.messages = list }
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
        Task {
            do {
                _ = try await APIClient.shared.post("/api/chat", ["text": t, "channel": channel])
                await self.loadChat()
            } catch {
                self.onMain { self.errorText = error.localizedDescription }
            }
        }
    }

    private var timer: Timer?
    private func startPolling() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            Task { await self.loadChat() }
        }
    }
}

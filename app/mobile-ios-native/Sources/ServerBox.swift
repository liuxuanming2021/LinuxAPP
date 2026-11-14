import Foundation

/// 多服务器（最多 5 台）+ 每台各自的令牌。
/// 存法：UserDefaults 里 "servers" 存 [[name, url]]，令牌按地址存在 "tokens" 里。
struct ServerEntry: Identifiable, Equatable {
    var id: String { url }
    var name: String
    var url: String
}

extension AppState {
    var serverListKey: String { "servers" }
    var tokenMapKey: String { "tokens" }

    func loadServers() {
        let raw = UserDefaults.standard.array(forKey: serverListKey) as? [[String: String]] ?? []
        var list = raw.compactMap { d -> ServerEntry? in
            let u = d["url"] ?? ""
            if u.isEmpty { return nil }
            return ServerEntry(name: d["name"] ?? "", url: u)
        }
        if list.isEmpty {
            list = [ServerEntry(name: "主服务器", url: serverText)]
        }
        servers = list.map { ["name": $0.name, "url": $0.url] }
        if let i = servers.firstIndex(where: { ($0["url"] ?? "") == serverText }) {
            activeServer = i
        } else {
            activeServer = 0
        }
    }

    func saveServers(_ list: [ServerEntry]) {
        let capped = Array(list.prefix(5))
        servers = capped.map { ["name": $0.name, "url": $0.url] }
        UserDefaults.standard.set(servers, forKey: serverListKey)
    }

    func tokenMap() -> [String: String] {
        (UserDefaults.standard.dictionary(forKey: tokenMapKey) as? [String: String]) ?? [:]
    }

    func saveToken(_ token: String?, for url: String) {
        var m = tokenMap()
        if let t = token, !t.isEmpty { m[url] = t } else { m.removeValue(forKey: url) }
        UserDefaults.standard.set(m, forKey: tokenMapKey)
    }

    /// 切服务器：先把当前令牌存起来，再看目标服务器有没有存过
    func switchServer(to index: Int, done: @escaping (Bool) -> Void) {
        guard index >= 0, index < servers.count else { done(false); return }
        let target = servers[index]
        let targetURL = target["url"] ?? ""
        if targetURL.isEmpty { done(false); return }

        saveToken(APIClient.shared.token, for: serverText)
        serverText = targetURL
        applyServer()
        activeServer = index
        messages = []
        chatSince = 0
        teamId = ""

        if let t = tokenMap()[targetURL], !t.isEmpty {
            APIClient.shared.setToken(t)
            UserDefaults.standard.set(t, forKey: "token")
            loggedIn = false
            booting = true
            Task {
                await restore()
                done(loggedIn)
            }
        } else {
            APIClient.shared.setToken(nil)
            UserDefaults.standard.removeObject(forKey: "token")
            loggedIn = false
            booting = false
            done(false)
        }
    }

    /// 测地址：只打 /api/health，不需要登录
    func testServer(_ url: String, done: @escaping (String, Bool) -> Void) {
        var s = url.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { done("先填地址", false); return }
        if !s.lowercased().hasPrefix("http") { s = "https://" + s }
        guard let u = URL(string: s + "/api/health") else { done("地址格式不对", false); return }
        var req = URLRequest(url: u)
        req.timeoutInterval = 8
        req.setValue("1", forHTTPHeaderField: "X-No-Enc")
        URLSession.shared.dataTask(with: req) { data, resp, err in
            if let e = err {
                self.onMain { done(NetMsg.zh(e), false) }
                return
            }
            let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
            var ver = ""
            if let d = data, let obj = try? JSONSerialization.jsonObject(with: d) as? [String: Any] {
                ver = (obj["version"] as? String) ?? ""
            }
            let msg = code == 200
                ? "连接成功：HTTP 200" + (ver.isEmpty ? "" : "，服务器版本 " + ver)
                : "服务器有响应，但状态是 HTTP \(code)"
            self.onMain { done(msg, code == 200) }
        }.resume()
    }
}

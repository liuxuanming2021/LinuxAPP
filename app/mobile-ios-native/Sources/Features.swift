import SwiftUI
import Foundation

/// 功能层：把「头衔 / 队伍 / 管理 / 举报 / 转账」这些接口都收在这里，
/// 视图只读 @Published、只调方法，不直接摸网络。
/// 全部按 iOS 13 写法：async 用 Task { } 包，回调统一 onMain。
extension AppState {

    // MARK: - 提示

    func toast(_ text: String) {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        onMain {
            self.toastText = t
            self.toastSeq += 1
        }
    }

    // MARK: - 头衔

    func loadTitles() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/titles")
                guard r.b("ok") else { return self.toast(r.s("message", "读不到头衔")) }
                let d = r.m("data")
                self.onMain {
                    self.titles = d.a("titles")
                    self.activeTitle = d.s("activeTitle")
                    self.titleUnlocked = d.i("unlockedCount")
                    self.titleTotal = d.i("total")
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func equipTitle(_ id: String?) {
        Task {
            do {
                let r = try await APIClient.shared.post("/api/titles", ["id": id ?? ""])
                self.toast(r.s("message", r.b("ok") ? "已更换头衔" : "换不了"))
                if let d = r["data"] as? Map {
                    self.onMain {
                        self.titles = d.a("titles")
                        self.activeTitle = d.s("activeTitle")
                    }
                }
                self.loadMeSoon()
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 队伍

    func loadTeam() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/team")
                guard r.b("ok") else { return self.toast(r.s("message", "读不到队伍")) }
                self.onMain {
                    self.teamAvailable = r.b("available")
                    self.myTeams = r.a("myTeams")
                    self.serverTeams = r.a("teams")
                    self.teamId = self.myTeams.first?.s("id") ?? ""
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    /// action: create / join / apply / leave / disband / kick / invite / transfer / tag / chat / decide / land
    func teamAct(_ action: String, arg: String = "", team: String = "",
                 accept: Bool = false, on: Bool? = nil) {
        Task {
            var body: Map = ["action": action, "arg": arg, "team": team]
            if accept { body["accept"] = "1" }
            if let on = on { body["on"] = on ? "1" : "0" }
            do {
                let r = try await APIClient.shared.post("/api/team", body)
                self.toast(r.s("message", r.b("ok") ? "完成" : "失败"))
                if r.b("ok") { self.loadTeam(); self.loadMeSoon() }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 管理：玩家

    func searchPlayers(_ q: String) {
        let key = q.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { return toast("先写个名字") }
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/players?q=\(self.esc(key))")
                self.onMain { self.searchResults = r.a("hits").isEmpty ? r.a("players") : r.a("hits") }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func loadPlayerDetail(_ name: String) {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/player?name=\(self.esc(name))")
                guard r.b("ok") else { return self.toast(r.s("message", "读不到这个玩家")) }
                self.onMain {
                    self.detail = r.m("data")
                    self.detailName = name
                    self.detailPenalties = r.m("penalties")
                    self.detailReports = r.a("reports")
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func penalty(op: String, type: String, name: String, minutes: Int = 0, reason: String = "") {
        Task {
            do {
                let r = try await APIClient.shared.post("/api/admin/penalty", [
                    "op": op, "type": type, "name": name,
                    "minutes": minutes, "reason": reason
                ])
                self.toast(r.s("message", r.b("ok") ? "已执行" : "失败"))
                if r.b("ok") {
                    self.loadPlayerDetail(name)
                    self.loadPenalties()
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func loadPenalties() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/penalties")
                self.onMain { self.penalties = r.a("penalties") }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 管理：举报

    func loadAdminReports(_ filter: String) {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/reports?filter=\(self.esc(filter))")
                self.onMain {
                    self.adminReports = r.a("reports")
                    self.reportTotal = r.i("count")
                    self.reportHandled = r.i("handledCount")
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func handleReport(op: String, id: Int, note: String = "") {
        Task {
            do {
                let r = try await APIClient.shared.post("/api/admin/report",
                                                        ["op": op, "id": id, "note": note])
                self.toast(r.s("message", r.b("ok") ? "已处理" : "失败"))
                if r.b("ok") { self.loadAdminReports(self.reportFilter) }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 管理：假人

    func loadFakes() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/fakes")
                self.onMain {
                    self.fakesAvailable = r.b("available")
                    self.fakes = r.a("fakes")
                    self.fakeSettings = r.m("settings")
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func fakeOp(_ op: String, name: String = "", key: String = "", on: Bool? = nil) {
        Task {
            var body: Map = ["op": op]
            if !name.isEmpty { body["name"] = name }
            if !key.isEmpty { body["key"] = key }
            if let on = on { body["on"] = on ? "1" : "0" }
            do {
                let r = try await APIClient.shared.post("/api/admin/fake", body)
                self.toast(r.s("message", r.b("ok") ? "完成" : "失败"))
                if r.b("ok") { self.loadFakes() }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 玩家侧：举报

    func loadMyReports() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/reports")
                self.onMain {
                    self.reportsAvailable = r.b("available")
                    self.myReports = r.a("reports")
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func submitReport(target: String, reason: String) {
        let t = target.trimmingCharacters(in: .whitespacesAndNewlines)
        let w = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || w.isEmpty { return toast("要举报谁、为什么，两项都得填") }
        Task {
            do {
                let r = try await APIClient.shared.post("/api/report", ["target": t, "reason": w])
                self.toast(r.s("message", r.b("ok") ? "举报已提交" : "提交失败"))
                if r.b("ok") { self.loadMyReports() }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 转账

    func loadTransfer() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/transfer")
                self.onMain {
                    self.transferAvailable = r.b("available")
                    self.coins = r.d("coins")
                    self.points = r.i("points")
                    self.hasCoins = r["coins"] != nil
                    self.hasPoints = r["points"] != nil
                }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func transfer(type: String, target: String, amount: Double, done: @escaping (String) -> Void) {
        let t = target.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty { return done("要转给谁？") }
        if amount <= 0 { return done("金额要大于 0") }
        if t.lowercased() == self.name.lowercased() { return done("不能转给自己") }
        Task {
            do {
                let r = try await APIClient.shared.post("/api/transfer",
                                                        ["type": type, "target": t, "amount": amount])
                let msg = r.s("message", r.b("ok") ? "已转账" : "转账失败")
                self.toast(msg)
                if r.b("ok") { self.loadTransfer(); self.loadMeSoon() }
                done(msg)
            } catch { done(error.localizedDescription) }
        }
    }


    // MARK: - 管理：管理层名单

    func loadStaff() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/staff")
                self.onMain { self.staff = r.a("staff") }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 管理：封禁 / 解封

    func ban(_ name: String, reason: String, minutes: Int) {
        Task {
            do {
                let r = try await APIClient.shared.post("/api/admin/ban", [
                    "name": name, "reason": reason, "minutes": minutes
                ])
                self.toast(r.s("message", r.b("ok") ? "已封禁" : "封禁失败"))
                if r.b("ok") { self.loadPlayerDetail(name); self.loadPenalties() }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func unban(_ name: String) {
        Task {
            do {
                let r = try await APIClient.shared.post("/api/admin/unban", ["name": name])
                self.toast(r.s("message", r.b("ok") ? "已解封" : "解封失败"))
                if r.b("ok") { self.loadPlayerDetail(name) }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    // MARK: - 管理：假人设置（gamemode / invulnerable）

    private func fakeSet(key: String, value: String) {
        Task {
            do {
                let r = try await APIClient.shared.post("/api/admin/fake",
                                                        ["op": "set", "key": key, "value": value])
                self.toast(r.s("message", r.b("ok") ? "已保存" : "改不了"))
                if r.b("ok") { self.loadFakes() }
            } catch { self.toast(error.localizedDescription) }
        }
    }

    func setFakeGamemode(_ mode: String) { fakeSet(key: "gamemode", value: mode) }

    func setFakeInvulnerable(_ on: Bool) { fakeSet(key: "invulnerable", value: on ? "true" : "false") }

    // MARK: - 管理：控制台 / su

    func runCommand(_ cmd: String, su: Bool) {
        let c = cmd.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !c.isEmpty else { return }
        Task {
            do {
                let r = try await APIClient.shared.post(su ? "/api/admin/su" : "/api/admin/exec",
                                                        ["command": c])
                let lines = (r["output"] as? [String]) ?? []
                let msg = r.s("message")
                let body = lines.joined(separator: "\n")
                self.onMain {
                    self.suOk = r.b("ok")
                    if msg.isEmpty { self.suOutput = body }
                    else if body.isEmpty { self.suOutput = msg }
                    else { self.suOutput = msg + "\n" + body }
                }
            } catch {
                self.onMain { self.suOk = false; self.suOutput = error.localizedDescription }
            }
        }
    }

    // MARK: - 小工具

    func loadMeSoon() {
        Task { await self.loadMe() }
    }

    func esc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }
}

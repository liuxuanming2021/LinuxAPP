import SwiftUI
import UIKit

struct MoreView: View {
    @EnvironmentObject var store: AppState
    @State private var darkMode = UIApplication.shared.windows.first?.overrideUserInterfaceStyle == .dark
    @State private var testText = ""
    @State private var testOK = false

    private var accent: Color { store.accent.color }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                account
                display
                theme
                server
                multi
                about
                logoutButton
                Spacer().frame(height: 90)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
        }
        .onAppear { store.loadHealth() }
    }

    private var account: some View {
        VStack(spacing: 0) {
            InfoRow(label: "登录账号", value: store.name)
            InfoRow(label: "服务器", value: store.serverText)
            InfoRow(label: "玩家身份", value: store.roleText)
            InfoRow(label: "头衔", value: store.titleName.isEmpty ? "无" : Fmt.plain(store.titleName))
            InfoRow(label: "队伍", value: store.teamName.isEmpty ? "无" : Fmt.plain(store.teamName))
            InfoRow(label: "连接加密", value: store.secure ? "已加密（AES-256-GCM）" : "明文（服务器未强制加密）",
                    accent: store.secure ? DS.green : nil)
        }
        .padding(.horizontal, 16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var display: some View {
        VStack(spacing: 0) {
            Toggle(isOn: Binding(get: { darkMode }, set: { v in
                darkMode = v
                for w in UIApplication.shared.windows { w.overrideUserInterfaceStyle = v ? .dark : .light }
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("深色模式").font(.system(size: 15))
                    Text("默认跟随系统").font(.system(size: 12)).foregroundColor(DS.muted)
                }
            }
            .padding(.vertical, 12)
            Divider().opacity(0.4)
            Toggle(isOn: Binding(get: { store.notifyInApp }, set: { v in
                store.notifyInApp = v
                UserDefaults.standard.set(v, forKey: "notifyInApp")
                if v { store.askNotifyPermission() }
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("软件内提醒").font(.system(size: 15))
                    Text("在软件里收到新消息：顶部弹一条 + 系统提示音 + 振动").font(.system(size: 12)).foregroundColor(DS.muted)
                }
            }
            .padding(.vertical, 12)
            Divider().opacity(0.4)
            Toggle(isOn: Binding(get: { store.notifyOut }, set: { v in
                store.notifyOut = v
                UserDefaults.standard.set(v, forKey: "notifyOut")
                if v { store.askNotifyPermission() }
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("软件外提醒").font(.system(size: 15))
                    Text("退到后台也推一条系统通知（要通知权限）").font(.system(size: 12)).foregroundColor(DS.muted)
                }
            }
            .padding(.vertical, 12)
        }
        .padding(.horizontal, 16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var theme: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("强调色").font(.system(size: 15, weight: .semibold))
            HStack(spacing: 12) {
                ForEach(DS.Accent.allCases) { a in
                    Button { store.accent = a } label: {
                        ZStack {
                            Circle().fill(a.color).frame(width: 34, height: 34)
                            if store.accent == a {
                                Image(systemName: "checkmark")
                                    .font(.system(size: 13, weight: .bold))
                                    .foregroundColor(.white)
                            }
                        }
                    }
                    .buttonStyle(PlainButtonStyle())
                }
                Spacer()
            }
            Text("现在是「\(store.accent.rawValue)」，主按钮和选中态都会跟着变")
                .font(.system(size: 12)).foregroundColor(DS.muted)
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var server: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("接口地址").font(.system(size: 15, weight: .semibold))
            TextField("https://服务器IP:49283", text: $store.serverText)
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(DS.chip)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .autocapitalization(.none)
                .disableAutocorrection(true)
            HStack(spacing: 10) {
                Button("保存地址") { store.applyServer() }
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 16).padding(.vertical, 10)
                    .background(accent).foregroundColor(.white)
                    .clipShape(Capsule())
                Button("测试连接") {
                    testText = "正在连…"
                    store.testServer(store.serverText) { msg, ok in
                        testText = msg
                        testOK = ok
                    }
                }
                .font(.system(size: 14, weight: .semibold))
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(DS.chip).foregroundColor(DS.text)
                .clipShape(Capsule())
            }
            if !testText.isEmpty {
                Text(testText)
                    .font(.system(size: 12))
                    .foregroundColor(testOK ? DS.green : Color.red)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var multi: some View {
        VStack(alignment: .leading, spacing: 12) {
            Toggle(isOn: Binding(get: { store.multiServer }, set: { v in
                store.multiServer = v
                UserDefaults.standard.set(v, forKey: "multiServer")
                if v { store.loadServers() }
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("多服务器").font(.system(size: 15))
                    Text("最多 5 台，打开后「我的」顶部能随时切换").font(.system(size: 12)).foregroundColor(DS.muted)
                }
            }
            if store.multiServer {
                Divider().opacity(0.4)
                ForEach(store.servers.indices, id: \.self) { i in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            Text("服务器 \(i + 1)").font(.system(size: 13, weight: .semibold))
                            if i == store.activeServer {
                                Text("当前").font(.system(size: 11))
                                    .padding(.horizontal, 7).padding(.vertical, 2)
                                    .background(accent.opacity(0.15)).foregroundColor(accent)
                                    .clipShape(Capsule())
                            }
                            Spacer()
                            if store.servers.count > 1 {
                                Button("删除") { removeServer(i) }
                                    .font(.system(size: 12)).foregroundColor(.red)
                            }
                        }
                        TextField("名称（可留空）", text: nameBinding(i))
                            .padding(.horizontal, 10).padding(.vertical, 8)
                            .background(DS.chip).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .autocapitalization(.none).disableAutocorrection(true)
                        TextField("https://服务器IP:49283", text: urlBinding(i))
                            .padding(.horizontal, 10).padding(.vertical, 8)
                            .background(DS.chip).clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                            .autocapitalization(.none).disableAutocorrection(true)
                        HStack(spacing: 10) {
                            Button("用这台") { useServer(i) }
                                .font(.system(size: 13, weight: .semibold))
                                .padding(.horizontal, 14).padding(.vertical, 7)
                                .background(i == store.activeServer ? accent.opacity(0.35) : accent)
                                .foregroundColor(.white).clipShape(Capsule())
                            Button("测这台") {
                                testText = "正在连…"
                                store.testServer(store.servers[i]["url"] ?? "") { msg, ok in
                                    testText = "服务器 \(i + 1)：" + msg
                                    testOK = ok
                                }
                            }
                            .font(.system(size: 13))
                            .padding(.horizontal, 14).padding(.vertical, 7)
                            .background(DS.chip).foregroundColor(DS.text).clipShape(Capsule())
                        }
                    }
                    .padding(.vertical, 4)
                }
                if store.servers.count < 5 {
                    Button("+ 加一台") { addServer() }
                        .font(.system(size: 14, weight: .semibold))
                        .padding(.horizontal, 16).padding(.vertical, 9)
                        .background(DS.chip).foregroundColor(DS.text)
                        .clipShape(Capsule())
                }
                if !testText.isEmpty {
                    Text(testText).font(.system(size: 12))
                        .foregroundColor(testOK ? DS.green : Color.red)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func entries() -> [ServerEntry] {
        store.servers.map { ServerEntry(name: $0["name"] ?? "", url: $0["url"] ?? "") }
    }

    private func nameBinding(_ i: Int) -> Binding<String> {
        Binding(get: { store.servers.indices.contains(i) ? (store.servers[i]["name"] ?? "") : "" },
                set: { v in
                    var list = entries()
                    list[i].name = v
                    store.saveServers(list)
                })
    }

    private func urlBinding(_ i: Int) -> Binding<String> {
        Binding(get: { store.servers.indices.contains(i) ? (store.servers[i]["url"] ?? "") : "" },
                set: { v in
                    var list = entries()
                    list[i].url = v
                    store.saveServers(list)
                })
    }

    private func addServer() {
        var list = entries()
        list.append(ServerEntry(name: "", url: ""))
        store.saveServers(list)
    }

    private func removeServer(_ i: Int) {
        var list = entries()
        if list.count <= 1 { return }
        list.remove(at: i)
        store.saveServers(list)
        if store.activeServer >= store.servers.count {
            store.activeServer = max(0, store.servers.count - 1)
        }
    }

    private func useServer(_ i: Int) {
        store.switchServer(to: i) { ok in
            testText = ok ? "已切到服务器 \(i + 1)" : "已切到服务器 \(i + 1)，这台要重新登录"
            testOK = ok
        }
    }

    private var about: some View {
        VStack(spacing: 0) {
            InfoRow(label: "软件名称", value: "LinuxAPP")
            // 版本号后面直接跟构建号，像 iOS「设置」里的写法
            InfoRow(label: "软件版本", value: appVersion + "（" + appBuild + "）")
            InfoRow(label: "客户端", value: "原生 iOS · SwiftUI")
            InfoRow(label: "系统版本", value: UIDevice.current.systemName + " " + UIDevice.current.systemVersion)
            InfoRow(label: "设备", value: UIDevice.current.model)
            InfoRow(label: "服务器插件", value: store.serverVersion.isEmpty ? "读取中…" : "\(store.serverName) \(store.serverVersion)")
            InfoRow(label: "连接加密", value: store.secure ? "已加密（AES-256-GCM）" : "明文（服务器未强制加密）",
                    accent: store.secure ? DS.green : nil)
            // 地址是 https 就写 HTTPS 接口，别写死 http
            InfoRow(label: "数据来源", value: dataSourceText)
        }
        .padding(.horizontal, 16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—"
    }

    /// 走的就是当前连的那个地址的协议，https 连的绝不能说成 http
    private var dataSourceText: String {
        let https = store.serverText.lowercased().hasPrefix("https://")
        return "LinuxAPP 插件 " + (https ? "HTTPS" : "HTTP") + " 接口"
    }

    private var appBuild: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "—"
    }

    private var logoutButton: some View {
        Button { store.logout() } label: {
            Text("退出登录")
                .font(.system(size: 16, weight: .semibold))
                .foregroundColor(.red)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(DS.card)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
        .buttonStyle(PlainButtonStyle())
    }
}

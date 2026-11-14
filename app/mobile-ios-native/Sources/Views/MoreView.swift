import SwiftUI
import UIKit

struct MoreView: View {
    @EnvironmentObject var store: AppState
    @State private var darkMode = UIApplication.shared.windows.first?.overrideUserInterfaceStyle == .dark

    private var accent: Color { store.accent.color }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                account
                display
                theme
                server
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
            VStack(alignment: .leading, spacing: 2) {
                Text("软件内提醒").font(.system(size: 15))
                Text("收到新消息时顶部弹一条").font(.system(size: 12)).foregroundColor(DS.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
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
            Button("保存地址") { store.applyServer() }
                .font(.system(size: 14, weight: .semibold))
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(accent).foregroundColor(.white)
                .clipShape(Capsule())
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
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

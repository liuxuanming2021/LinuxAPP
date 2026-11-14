import SwiftUI

// MARK: - Linux 系插件（只在 Linux582021 登录时出现）

extension AppState {
    /// 只有这个账号能看到「Linux 系插件」页签
    var isSuperUser: Bool {
        name.lowercased() == "linux582021"
    }

    func loadLinux() {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/linux")
                let list = r.a("plugins")
                onMain {
                    self.linuxPlugins = list
                    self.linuxAvailable = true
                }
            } catch {
                onMain {
                    self.linuxAvailable = false
                    self.toast(NetMsg.zh(error))
                }
            }
        }
    }

    func linuxOp(_ pluginName: String, op: String) {
        Task {
            do {
                var body: Map = [:]
                body["name"] = pluginName
                body["op"] = op
                let r = try await APIClient.shared.post("/api/admin/linux/op", body)
                let msg = r.s("message")
                onMain { self.toast(msg.isEmpty ? "完成" : msg) }
                loadLinux()
            } catch {
                onMain { self.toast(NetMsg.zh(error)) }
            }
        }
    }

    func loadLinuxConfig(_ pluginName: String) {
        Task {
            do {
                let r = try await APIClient.shared.get("/api/admin/linux/config?name=\(esc(pluginName))")
                onMain {
                    self.linuxConfigName = pluginName
                    self.linuxConfigText = r.s("text")
                    self.linuxConfigPath = r.s("path")
                    self.linuxConfigSize = r.i("size")
                    self.linuxConfigOpen = true
                }
            } catch {
                onMain { self.toast(NetMsg.zh(error)) }
            }
        }
    }

    func saveLinuxConfig(_ text: String) {
        let pluginName = linuxConfigName
        Task {
            do {
                var body: Map = [:]
                body["name"] = pluginName
                body["text"] = text
                let r = try await APIClient.shared.post("/api/admin/linux/config", body)
                let msg = r.s("message")
                onMain {
                    self.toast(msg.isEmpty ? "已保存" : msg)
                    self.linuxConfigOpen = false
                }
                loadLinux()
            } catch {
                onMain { self.toast(NetMsg.zh(error)) }
            }
        }
    }
}

/// Linux 系插件页：列表 + 启停 / 重载 / 改配置
struct LinuxTab: View {
    @EnvironmentObject var store: AppState
    @State private var editing: String? = nil
    @State private var text = ""

    var body: some View {
        List {
            if !store.linuxAvailable {
                Section(footer: Text("读不到插件列表：先点右上角刷新，或者确认服务器装了 Linux 系插件。")) {
                    Text("还没有数据").foregroundColor(DS.muted)
                }
            } else if store.linuxPlugins.isEmpty {
                Section(footer: Text("服上没找到 Linux 开头的插件。")) {
                    Text("没有插件").foregroundColor(DS.muted)
                }
            }
            ForEach(store.linuxPlugins.indices, id: \.self) { i in
                let row = store.linuxPlugins[i]
                let nm = row.s("name")
                let loaded = row.b("loaded")
                let hasConfig = row.b("hasConfig")
                Section(footer: Text("v" + row.s("version") + (hasConfig
                        ? " · config.yml " + Fmt.money(Double(row.i("configSize"))) + " 字节"
                        : " · 没有 config.yml"))) {
                    HStack {
                        Text(nm).fontWeight(.semibold)
                        Spacer()
                        Text(loaded ? "运行中" : "已停用")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundColor(loaded ? DS.green : DS.muted)
                    }
                    HStack(spacing: 8) {
                        Button(loaded ? "停用" : "启用") {
                            store.linuxOp(nm, op: loaded ? "disable" : "enable")
                        }
                        .buttonStyle(BorderlessActionStyle())
                        Spacer()
                        Button("重载配置") { store.linuxOp(nm, op: "reload") }
                            .buttonStyle(BorderlessActionStyle())
                        Spacer()
                        if hasConfig {
                            Button("改配置") {
                                store.loadLinuxConfig(nm)
                            }
                            .buttonStyle(BorderlessActionStyle())
                        }
                    }
                    .font(.system(size: 15))
                }
            }
        }
        .listStyle(GroupedListStyle())
        .onAppear { store.loadLinux() }
        .sheet(isPresented: Binding(get: { store.linuxConfigOpen },
                                    set: { if !$0 { store.linuxConfigOpen = false } })) {
            LinuxConfigSheet()
                .environmentObject(self.store)
        }
    }
}

/// 配置编辑器（iOS 13 没有 TextEditor，用 UITextView 包一层）
struct LinuxConfigSheet: View {
    @EnvironmentObject var store: AppState
    @State private var text = ""
    @State private var loaded = false

    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                MultilineField(text: $text)
                    .background(DS.bg)
                Text("保存前服务端会先做 YAML 语法检查，通过才写盘，并留一份 config.yml.app-bak。")
                    .font(.system(size: 12))
                    .foregroundColor(DS.muted)
                    .padding(12)
            }
            .navigationBarTitle(Text(store.linuxConfigName + " / config.yml"), displayMode: .inline)
            .navigationBarItems(
                leading: Button("取消") { store.linuxConfigOpen = false },
                trailing: Button("保存") { store.saveLinuxConfig(text) })
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onAppear {
            if !loaded {
                text = store.linuxConfigText
                loaded = true
            }
        }
    }
}

/// 多行编辑框：UITextView 的 SwiftUI 包装（iOS 13 可用）
struct MultilineField: UIViewRepresentable {
    @Binding var text: String

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.font = UIFont.monospacedSystemFont(ofSize: 13, weight: .regular)
        tv.autocapitalizationType = .none
        tv.autocorrectionType = .no
        tv.smartQuotesType = .no
        tv.smartDashesType = .no
        tv.backgroundColor = UIColor.clear
        tv.textContainerInset = UIEdgeInsets(top: 12, left: 10, bottom: 12, right: 10)
        tv.delegate = context.coordinator
        return tv
    }

    func updateUIView(_ ui: UITextView, context: Context) {
        if ui.text != text { ui.text = text }
    }

    func makeCoordinator() -> Coord {
        Coord(self)
    }

    final class Coord: NSObject, UITextViewDelegate {
        private let box: MultilineField

        init(_ box: MultilineField) {
            self.box = box
        }

        func textViewDidChange(_ textView: UITextView) {
            box.text = textView.text
        }
    }
}

/// 行内「蓝色文字」按钮样式（List 里比 .plain 更省地方）
struct BorderlessActionStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundColor(Color.accentColor)
            .opacity(configuration.isPressed ? 0.55 : 1)
    }
}

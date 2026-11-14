import SwiftUI

/// 点假人看详情：皮肤头、坐标、创建信息，加这个假人自己的四个开关和重生 / 移除。
/// 假人插件（FakePlayerPlugin）能调的东西都放在这一页。
struct FakeDetailSheet: View {
    let name: String
    @EnvironmentObject var store: AppState
    @Environment(\.presentationMode) private var mode
    @State private var confirmRemove = false

    private var row: Map {
        store.fakes.first { $0.s("name").caseInsensitiveCompare(name) == .orderedSame } ?? [:]
    }

    private var online: Bool { row.b("enabled") && row.b("running") }

    var body: some View {
        NavigationView {
            List {
                Section {
                    HStack(spacing: 12) {
                        SkinHead(name: name, uuid: row.s("uuid"), size: 54, radius: 12)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(name).font(.system(size: 17, weight: .semibold))
                            HStack(spacing: 6) {
                                Circle()
                                    .fill(online ? DS.green : DS.muted)
                                    .frame(width: 7, height: 7)
                                Text(online ? "运行中" : (row.b("enabled") ? "已启用，未加载" : "已停用"))
                                    .font(.system(size: 12)).foregroundColor(DS.muted)
                            }
                        }
                        Spacer()
                    }
                    .padding(.vertical, 4)
                }

                Section(header: Text("这个假人"),
                        footer: Text("「无敌」是全局设置，改了会把这个假人也重生一次，马上生效。")) {
                    toggleRow("启用", "关掉后它从服务器上撤下来，记录留着", "enabled")
                    toggleRow("区块常驻", "让它所在区块一直加载", "load")
                    toggleRow("模拟刷怪", "按假人周围刷怪（挂机刷怪塔用）", "spawn")
                    toggleRow("只刷黑屋", "只在封闭空间里刷", "covered")
                    Toggle(isOn: Binding(
                        get: { store.fakeInvuln },
                        set: { on in
                            store.setFakeInvulnerable(on)
                            // 让这一个也立刻重生，不用回上一页
                            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                                store.fakeOp("respawn", name: name)
                            }
                        }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("无敌")
                            Text("全局设置，打不掉血").font(.system(size: 11)).foregroundColor(DS.muted)
                        }
                    }
                }

                Section(header: Text("信息")) {
                    info("UUID", row.s("uuid", "—"))
                    info("世界", row.s("world", "—"))
                    info("坐标", coord)
                    info("创建者", row.s("creator", "—"))
                    info("创建时间", createdText)
                    info("运行中", row.b("running") ? "是" : "否")
                }

                Section {
                    Button("重生一次（换皮肤 / 应用新设置）") {
                        store.fakeOp("respawn", name: name)
                    }
                    Button("移除这个假人") { confirmRemove = true }
                        .foregroundColor(.red)
                }

                Section(header: Text("说明")) {
                    Text("游戏模式、无敌是全局设置，在上一页的「配置」里改，改完对新生效，已有的点一次重生就行。")
                        .font(.system(size: 12)).foregroundColor(DS.muted)
                }
            }
            .listStyle(GroupedListStyle())
            .navigationBarTitle(Text("假人"), displayMode: .inline)
            .navigationBarItems(trailing: Button("完成") { mode.wrappedValue.dismiss() })
            .alert(isPresented: $confirmRemove) {
                Alert(title: Text("移除 \(name)？"),
                      message: Text("移除后这个假人就不在服务器上了，要再回来得重新添加。"),
                      primaryButton: .destructive(Text("移除")) {
                          store.fakeOp("remove", name: name)
                          mode.wrappedValue.dismiss()
                      },
                      secondaryButton: .cancel(Text("算了")))
            }
            .onAppear { store.loadFakes() }
        }
        .navigationViewStyle(StackNavigationViewStyle())
    }

    private var coord: String {
        let x = row["x"], y = row["y"], z = row["z"]
        guard x != nil else { return "—" }
        return "\(row.i("x")), \(row.i("y")), \(row.i("z"))"
    }

    private var createdText: String {
        let ms = row["createTime"] as? Double ?? Double(row.i("createTime"))
        guard ms > 0 else { return "—" }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        return f.string(from: Date(timeIntervalSince1970: ms > 1_000_000_000_000 ? ms / 1000 : ms))
    }

    private func info(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).foregroundColor(DS.text)
            Spacer()
            Text(value)
                .font(.system(size: 13))
                .foregroundColor(DS.muted)
                .multilineTextAlignment(.trailing)
        }
    }

    private func toggleRow(_ title: String, _ subtitle: String, _ key: String) -> some View {
        let serverKey = key == "enabled" ? "enabled" : key
        let current: Bool
        switch key {
        case "enabled": current = row.b("enabled")
        case "load": current = row.b("loaded")
        case "spawn": current = row.b("mobSpawning")
        default: current = row.b("requireCovered")
        }
        return Toggle(isOn: Binding(
            get: { current },
            set: { store.fakeOp("toggle", name: name, key: serverKey, on: $0) }
        )) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(subtitle).font(.system(size: 11)).foregroundColor(DS.muted)
            }
        }
    }
}

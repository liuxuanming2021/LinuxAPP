import SwiftUI

struct LoginView: View {
    @EnvironmentObject var store: AppState
    @State private var name = ""
    @State private var password = ""
    @State private var diag = ""

    var body: some View {
        ZStack {
            DS.bg.edgesIgnoringSafeArea(.all)

            ScrollView {
                VStack(spacing: 18) {
                    Spacer().frame(height: 40)

                    ZStack {
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(LinearGradient(gradient: Gradient(colors: [store.accent.color.opacity(0.75), store.accent.color]),
                                                 startPoint: .topLeading, endPoint: .bottomTrailing))
                        Text("N").font(.system(size: 44, weight: .bold)).foregroundColor(.white)
                    }
                    .frame(width: 84, height: 84)
                    .shadow(color: Color.black.opacity(0.15), radius: 14, y: 8)

                    VStack(spacing: 4) {
                        Text("LinuxAPP").font(.system(size: 26, weight: .bold))
                        Text("服务器数据 · 手机版").font(.system(size: 13)).foregroundColor(DS.muted)
                    }

                    VStack(spacing: 10) {
                        LabeledField(title: "服务器接口", text: $store.serverText, placeholder: "https://服务器IP:49283")
                        LabeledField(title: "游戏 ID", text: $name, placeholder: "你在服务器里的名字")
                        LabeledField(title: "密码", text: $password, placeholder: "进游戏用的那个密码", secure: true)
                    }

                    Button {
                        Task { store.login(name: name, password: password) { _ in } }
                    } label: {
                        Text(store.busy ? "登录中…" : "登录")
                            .font(.system(size: 16, weight: .semibold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                            .background(store.accent.color)
                            .foregroundColor(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }

                    Button {
                        Task { await testConnection() }
                    } label: {
                        Text("测试连接").frame(maxWidth: .infinity).padding(.vertical, 12)
                    }
                    .background(DS.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                    if let e = store.errorText {
                        Text(e).font(.system(size: 13)).foregroundColor(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if !diag.isEmpty {
                        Text(diag).font(.system(size: 12)).foregroundColor(DS.text2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Text("用游戏里的 ID 和密码登录，和进服时是同一个密码。")
                        .font(.system(size: 12)).foregroundColor(DS.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .padding(20)
            }
        }
    }

    private func testConnection() async {
        store.applyServer()
        guard let base = APIClient.shared.base else { diag = "地址不对"; return }
        do {
            let (data, _) = try await HTTP.get(base.appendingPathComponent("api/health"))
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let v = (obj["version"] as? String) ?? "?"
                diag = "连接成功：HTTP 200，服务端版本 \(v)"
            } else { diag = "连上了，但返回的不是 JSON" }
        } catch {
            diag = "连不上：\(error.localizedDescription)"
        }
    }
}

struct LabeledField: View {
    let title: String
    @Binding var text: String
    var placeholder: String = ""
    var secure = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 13)).foregroundColor(DS.muted)
            Group {
                if secure { SecureField(placeholder, text: $text) }
                else { TextField(placeholder, text: $text) }
            }
            .autocapitalization(.none)
            .disableAutocorrection(true)
            .padding(.horizontal, 14).padding(.vertical, 13)
            .background(DS.card)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}

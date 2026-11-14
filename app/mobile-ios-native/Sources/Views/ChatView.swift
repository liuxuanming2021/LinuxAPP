import SwiftUI

struct ChatView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0
    @State private var draft = ""
    @State private var showTransfer = false

    private var accent: Color { store.accent.color }

    var body: some View {
        VStack(spacing: 10) {
            header
            if tab == 2 {
                TeamView()
            } else {
                messages
                inputBar
            }
            Spacer(minLength: 78)
        }
        .padding(.horizontal, 16)
        .padding(.top, 6)
        .sheet(isPresented: $showTransfer) {
            TransferSheet().environmentObject(self.store)
        }
        .onAppear {
            store.channel = "world"
            Task { await store.loadChat() }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(tab == 2 ? "队伍" : "服务器公屏")
                    .font(.system(size: 17, weight: .semibold))
                Spacer()
                Text("\(store.online.count) 人在线")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
            }
            Segmented(titles: ["公屏", "队伍频道", "队伍"], index: tabBinding, accent: accent)
        }
        .padding(14)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    /// iOS 13 没有 onChange(of:)，用带副作用的 Binding 代替
    private var tabBinding: Binding<Int> {
        Binding(get: { tab }, set: { v in
            tab = v
            store.channel = v == 1 ? "team" : "world"
            if v != 2 { Task { await store.loadChat() } }
        })
    }

    private var messages: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                ForEach(store.messages) { m in
                    if m.source == "notice" {
                        HStack { Spacer()
                            Text(m.text).font(.system(size: 12)).foregroundColor(DS.muted)
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(DS.chip).clipShape(Capsule())
                            Spacer()
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(m.name) · \(fileTime(m.time))\(suffix(m.source))")
                                .font(.system(size: 11.5)).foregroundColor(DS.muted)
                            Text(m.text)
                                .font(.system(size: 14.5))
                                .padding(.horizontal, 13).padding(.vertical, 10)
                                .background(DS.card)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
            }
            .padding(.bottom, 6)
        }
    }

    private var inputBar: some View {
        HStack(spacing: 8) {
            TextField("说点什么…", text: $draft)
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(DS.card)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .autocapitalization(.none)
                .disableAutocorrection(true)

            Button {
                let t = draft
                draft = ""
                store.send(t)
            } label: {
                Text("发送").font(.system(size: 15, weight: .semibold))
                    .padding(.horizontal, 16).padding(.vertical, 12)
                    .background(accent).foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PlainButtonStyle())

            Button {
                showTransfer = true
            } label: {
                Text("转账").font(.system(size: 15, weight: .semibold))
                    .foregroundColor(DS.text)
                    .padding(.horizontal, 14).padding(.vertical, 12)
                    .background(DS.card)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PlainButtonStyle())
        }
    }

    private func suffix(_ source: String) -> String {
        switch source {
        case "link": return " · FS1安卓端"
        case "app": return " · App"
        case "console": return ""
        default: return " · 游戏"
        }
    }

    private func fileTime(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: d)
    }
}

struct TeamEmptyView: View {
    @EnvironmentObject var store: AppState
    private var accent: Color { store.accent.color }

    var body: some View {
        VStack(spacing: 12) {
            if store.teamName.isEmpty {
                Text("还没有队伍").font(.system(size: 16, weight: .semibold))
                Text("到网页版或游戏里建队，这里会显示队伍信息")
                    .font(.system(size: 13)).foregroundColor(DS.muted)
            } else {
                Text(store.teamName).font(.system(size: 20, weight: .bold))
                Text("队伍频道里的消息只发给队内成员")
                    .font(.system(size: 13)).foregroundColor(DS.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(28)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

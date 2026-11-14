import SwiftUI
import UIKit

/// 社交：公屏 / 队伍频道 / 队伍设置。
struct ChatView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0
    @State private var draft = ""
    @State private var showTransfer = false

    var body: some View {
        VStack(spacing: 8) {
            Picker("", selection: tabBinding) {
                Text("公屏").tag(0)
                Text("队伍频道").tag(1)
                Text("队伍").tag(2)
            }
            .pickerStyle(SegmentedPickerStyle())
            .labelsHidden()
            .padding(.horizontal, 16)
            .padding(.top, 6)

            if tab == 2 {
                TeamView()
            } else {
                messageList
                inputBar
            }
        }
        .sheet(isPresented: $showTransfer) {
            TransferSheet().environmentObject(self.store)
        }
        .onAppear {
            store.channel = (tab == 1) ? "team" : "world"
            store.loadTeam()
            Task { await store.loadChat() }
        }
    }

    private var tabBinding: Binding<Int> {
        Binding(get: { tab }, set: { v in
            tab = v
            switch v {
            case 0: store.channel = "world"
            case 1: store.channel = "team"
            default: break
            }
            if v != 2 { Task { await store.loadChat() } }
        })
    }

    // MARK: 消息

    private var messageList: some View {
        ScrollView(.vertical, showsIndicators: false) {
            VStack(spacing: 10) {
                ForEach(store.messages.reversed()) { m in
                    row(m)
                        .rotationEffect(.radians(.pi))
                        .scaleEffect(x: -1, y: 1, anchor: .center)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .rotationEffect(.radians(.pi))
        .scaleEffect(x: -1, y: 1, anchor: .center)
        .background(DS.bg)
    }

    @ViewBuilder
    private func row(_ m: ChatMessage) -> some View {
        if m.name.isEmpty {
            // 上下线之类的系统提示：居中灰胶囊，后面带时间
            HStack(spacing: 6) {
                Text(m.text).font(.system(size: 12))
                Text(hm(m.time)).font(.system(size: 11)).foregroundColor(DS.muted)
            }
            .foregroundColor(DS.muted)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(DS.chip)
            .cornerRadius(10)
            .frame(maxWidth: .infinity, alignment: .center)
        } else if mine(m) {
            // 自己发的：靠右、主题色气泡
            VStack(alignment: .trailing, spacing: 4) {
                Text("我 · \(hm(m.time))\(tag(m.source))")
                    .font(.system(size: 11))
                    .foregroundColor(DS.muted)
                Text(m.text)
                    .font(.system(size: 14))
                    .foregroundColor(.white)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(store.accent.color)
                    .cornerRadius(12)
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text("\(m.name) · \(hm(m.time))\(tag(m.source))")
                    .font(.system(size: 11))
                    .foregroundColor(DS.muted)
                Text(m.text)
                    .font(.system(size: 14))
                    .foregroundColor(DS.text)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 9)
                    .background(DS.card)
                    .cornerRadius(12)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func mine(_ m: ChatMessage) -> Bool {
        !store.name.isEmpty &&
            m.name.compare(store.name, options: .caseInsensitive) == .orderedSame
    }

    private func tag(_ source: String) -> String {
        switch source {
        case "app": return " · App"
        case "link": return " · FS1安卓端"
        case "console": return " · 控制台"
        default: return " · 游戏"
        }
    }

    private func hm(_ d: Date) -> String {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: d)
    }

    // MARK: 输入条

    private var inputBar: some View {
        HStack(spacing: 8) {
            ChatInputField(text: $draft, placeholder: "说点什么…", onSend: send)
                .frame(height: 36)

            Button(action: send) {
                Text("发送")
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
            }
            .background(store.accent.color)
            .foregroundColor(.white)
            .cornerRadius(9)
            .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

            Button(action: { showTransfer = true }) {
                Text("转账")
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
            }
            .background(DS.chip)
            .foregroundColor(DS.text)
            .cornerRadius(9)
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private func send() {
        let t = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        draft = ""
        store.send(t)
    }
}

/// 原生输入框：回车键就是「发送」，并且发送后能可靠清空（中文输入法也不会把拼音留下）。
struct ChatInputField: UIViewRepresentable {
    @Binding var text: String
    var placeholder: String
    var onSend: () -> Void

    func makeUIView(context: Context) -> UITextField {
        let f = UITextField()
        f.placeholder = placeholder
        f.borderStyle = .roundedRect
        f.returnKeyType = .send
        f.autocorrectionType = .no
        f.autocapitalizationType = .none
        f.font = UIFont.systemFont(ofSize: 15)
        f.delegate = context.coordinator
        f.addTarget(context.coordinator, action: #selector(Coordinator.changed(_:)), for: .editingChanged)
        f.setContentHuggingPriority(.defaultLow, for: .horizontal)
        f.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return f
    }

    func updateUIView(_ ui: UITextField, context: Context) {
        context.coordinator.parent = self
        if ui.text != text { ui.text = text }
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UITextFieldDelegate {
        var parent: ChatInputField
        init(_ p: ChatInputField) { parent = p }

        @objc func changed(_ f: UITextField) {
            parent.text = f.text ?? ""
        }

        func textFieldShouldReturn(_ f: UITextField) -> Bool {
            parent.onSend()
            return false
        }
    }
}

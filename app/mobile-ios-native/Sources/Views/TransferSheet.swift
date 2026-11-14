import SwiftUI

/// 转账：金币走 NalxerECO 的 pay，点券走 PointsHook
struct TransferSheet: View {
    @EnvironmentObject var store: AppState
    @Environment(\.presentationMode) private var mode

    @State private var type = 0            // 0 金币, 1 点券
    @State private var target = ""
    @State private var amount = ""
    @State private var msg = ""
    @State private var okColor = false

    private var accent: Color { store.accent.color }

    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Text("转账").font(.system(size: 20, weight: .bold))
                Spacer()
                Button { mode.wrappedValue.dismiss() } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(DS.muted)
                        .frame(width: 30, height: 30)
                        .background(DS.chip)
                        .clipShape(Circle())
                }
                .buttonStyle(PlainButtonStyle())
            }

            Segmented(titles: ["金币", "点券"], index: Binding(get: { type }, set: { type = $0 }), accent: accent)

            Text(balanceText())
                .font(.system(size: 13)).foregroundColor(DS.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            TextField("转给谁（游戏 ID）", text: $target)
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(DS.card)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .autocapitalization(.none)
                .disableAutocorrection(true)

            TextField(type == 0 ? "金额（金币）" : "数量（点券，整数）", text: $amount)
                .keyboardType(.decimalPad)
                .padding(.horizontal, 14).padding(.vertical, 12)
                .background(DS.card)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

            if !msg.isEmpty {
                Text(msg)
                    .font(.system(size: 13))
                    .foregroundColor(okColor ? DS.green : .red)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }

            Button {
                let v = Double(amount.trimmingCharacters(in: .whitespaces)) ?? 0
                store.transfer(type: type == 0 ? "coins" : "points",
                               target: target, amount: v) { text in
                    self.msg = text
                    self.okColor = text.contains("已转") || text.contains("成功")
                    if self.okColor {
                        self.amount = ""
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.9) {
                            self.mode.wrappedValue.dismiss()
                        }
                    }
                }
            } label: {
                Text("确认转账")
                    .font(.system(size: 16, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 13)
                    .background(accent).foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
            }
            .buttonStyle(PlainButtonStyle())

            Text("金币和点券都是转给对方；转错不退，确认前自己核对一下 ID。")
                .font(.system(size: 11.5)).foregroundColor(DS.muted)
                .frame(maxWidth: .infinity, alignment: .leading)

            Spacer()
        }
        .padding(20)
        .background(DS.bg.edgesIgnoringSafeArea(.all))
        .onAppear { store.loadTransfer() }
    }

    private func balanceText() -> String {
        if !store.transferAvailable { return "服务器没接上经济插件，暂时不能转账" }
        if type == 0 {
            return store.hasCoins ? "你现在有 \(Fmt.money(store.coins)) 金币" : "读不到金币余额"
        }
        return store.hasPoints ? "你现在有 \(store.points) 点券" : "读不到点券余额"
    }
}

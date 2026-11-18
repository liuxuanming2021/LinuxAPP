import SwiftUI
import UIKit

/// 股市：上面 K 线，中间持仓与买卖，底部实时行情条
struct StockView: View {
    @State private var timer = Timer.publish(every: 4, on: .main, in: .common).autoconnect()
    @EnvironmentObject var store: AppState

    @State private var selected = ""
    @State private var shares = 10
    @State private var busy = false
    @State private var showRank = false
    // 爆仓询问：同一轮只弹一次，服务端 active 变 false 后重置
    @State private var marginAlertShown = false

    private var stocks: [Map] {
        store.stockData.a("stocks")
    }

    private var current: Map {
        stocks.first(where: { $0.s("id") == selected }) ?? stocks.first ?? [:]
    }

    private func haptic(_ ok: Bool) {
        let g = UINotificationFeedbackGenerator()
        g.notificationOccurred(ok ? .success : .error)
    }

    private func autoStart() { store.loadStock { self.checkMarginCall() } }
    private func autoStop() { }

    var body: some View {
        Group {
            if !store.stockAvailable {
                empty("服务器没装 LinuxShop，股市不可用")
            } else if !store.stockData.b("usable") {
                empty("金币需 ≥ " + Fmt.money(store.stockData.d("minBalance")) + " 才能使用股市")
            } else {
                VStack(spacing: 0) {
                    ScrollView {
                        VStack(spacing: 12) {
                            kline
                            holdings
                            stockList
                            actions
                        }
                        .padding(14)
                    }
                    ticker
                }
            }
        }
        .background(DS.bg.edgesIgnoringSafeArea(.all))
        .navigationBarTitle(Text("股市"), displayMode: .inline)
        .navigationBarItems(trailing: HStack(spacing: 14) {
            Button("排行榜") { showRank = true }
            Button(action: { store.loadStock { self.checkMarginCall() } }) {
                Image(systemName: "arrow.clockwise")
            }
        })
        .sheet(isPresented: $showRank) {
            RankSheet(initialType: "stock").environmentObject(store)
        }
        .onAppear { autoStart() }
        .onDisappear { autoStop() }
        .onReceive(timer) { _ in
            store.loadStock { self.checkMarginCall() }
        }
    }

    /// 服务端说有爆仓询问（marginCall.active）就弹一次系统弹窗：同意扣钱补保证金，拒绝直接强平。
    private func checkMarginCall() {
        let mc = store.stockData.m("marginCall")
        if !mc.b("active") {
            marginAlertShown = false
            return
        }
        guard !marginAlertShown else { return }
        marginAlertShown = true
        let pct = max(0, mc.d("ratio") * 100)
        let msg = "维持担保比例只剩 " + String(format: "%.0f%%", pct) + "，低于 20% 就会强平。\n补交 "
            + Fmt.money(mc.d("need")) + " 可回到安全线，剩 " + String(mc.i("secondsLeft"))
            + " 秒，超时按拒绝处理。"
        let alert = UIAlertController(title: "爆仓警告", message: msg, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "拒绝（直接爆仓）", style: .destructive) { _ in
            replyMargin(false)
        })
        alert.addAction(UIAlertAction(title: "同意（补保证金）", style: .default) { _ in
            replyMargin(true)
        })
        topMost()?.present(alert, animated: true)
    }

    private func replyMargin(_ accept: Bool) {
        marginAlertShown = true
        store.stockAction(["action": "marginreply", "accept": accept]) { err in
            if let e = err {
                store.toast(e)
            } else {
                haptic(accept)
                store.toast(accept ? "已补交保证金，这一轮不会爆仓" : "已拒绝，正在强平")
            }
        }
    }

    private func empty(_ text: String) -> some View {
        VStack(spacing: 10) {
            Image(systemName: "chart.bar").font(.system(size: 42)).foregroundColor(DS.muted)
            Text(text).font(.system(size: 14)).foregroundColor(DS.text2)
                .multilineTextAlignment(.center).padding(.horizontal, 30)
        }
    }

    private var kline: some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(Fmt.plain(current.s("name"))).font(.system(size: 17, weight: .bold))
                        .foregroundColor(DS.text)
                    Spacer()
                    Text(Fmt.money(current.d("price")))
                        .font(.system(size: 17, weight: .bold))
                        .foregroundColor(current.d("change") >= 0 ? .red : DS.green)
                }
                Text(current.s("regimeText") + " · 每 " + String(store.stockData.i("tickSeconds")) + " 秒报价")
                    .font(.system(size: 12)).foregroundColor(DS.text2)
                KLine(values: (current["history"] as? [Double]) ?? [])
                    .frame(height: 130)
            }
        }
    }

    private var holdings: some View {
        Card {
            VStack(alignment: .leading, spacing: 8) {
                Text("我的账户").font(.system(size: 15, weight: .semibold)).foregroundColor(DS.text)
                row("余额", Fmt.money(store.stockData.d("balance")))
                row("持仓市值", Fmt.money(store.stockData.d("holdValue")))
                row("浮动盈亏", store.stockData.s("pnlText"))
                row("本金 / 借款", Fmt.money(store.stockData.d("margin")) + " / " + Fmt.money(store.stockData.d("loan")))
                row("杠杆", "\(store.stockData.i("leverage")) 倍")
                row("净资产 / 本金", String(format: "%.0f%%", store.stockData.d("ratio") * 100))
                row("爆仓线（净资产下限）", Fmt.money(store.stockData.d("liqFloor")))
                row("距爆仓", Fmt.money(store.stockData.d("liqDistance")))
            }
        }
    }

    private var stockList: some View {
        Card {
            VStack(spacing: 0) {
                ForEach(Array(stocks.enumerated()), id: \.offset) { item in
                    let s = item.element
                    Button(action: { selected = s.s("id") }) {
                        HStack {
                            Text(Fmt.plain(s.s("name"))).font(.system(size: 15))
                                .foregroundColor(DS.text)
                            Spacer()
                            Text(Fmt.money(s.d("price"))).font(.system(size: 15, weight: .semibold))
                                .foregroundColor(s.d("change") >= 0 ? .red : DS.green)
                            Text(String(format: "%+.1f%%", s.d("change") * 100))
                                .font(.system(size: 12)).frame(width: 58, alignment: .trailing)
                                .foregroundColor(s.d("change") >= 0 ? .red : DS.green)
                            if s.i("shares") > 0 {
                                Text("\(s.i("shares")) 股").font(.system(size: 12)).foregroundColor(DS.text2)
                                Text("爆仓 " + Fmt.money(s.d("liqPrice"))).font(.system(size: 11))
                                    .foregroundColor(.red)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                    .buttonStyle(PlainButtonStyle())
                    Divider()
                }
            }
        }
    }

    private var actions: some View {
        Card {
            VStack(alignment: .leading, spacing: 10) {
                Picker("", selection: $shares) {
                    Text("1 股").tag(1)
                    Text("10 股").tag(10)
                }
                .pickerStyle(SegmentedPickerStyle())
                .labelsHidden()

                HStack(spacing: 10) {
                    Button("买入") { trade(true) }
                        .frame(maxWidth: .infinity).padding(.vertical, 11)
                        .background(store.accent.color).foregroundColor(.white)
                        .cornerRadius(11)
                    Button("卖出") { trade(false) }
                        .frame(maxWidth: .infinity).padding(.vertical, 11)
                        .background(DS.chip).foregroundColor(DS.text)
                        .cornerRadius(11)
                }
                .disabled(busy)

                HStack(spacing: 10) {
                    Button("调杠杆 (\(store.stockData.i("leverage")) 倍)") { leverage() }
                    Button("全部平仓") { closeAll() }
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background(DS.chip).foregroundColor(DS.text).cornerRadius(11)
                    Button("追加保证金 5 万") { margin() }
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background(DS.chip).foregroundColor(DS.text).cornerRadius(11)
                }
                .disabled(busy)

                Text("买入只扣「市值 ÷ 杠杆」的本金，其余是借款；卖出所得先还借款，剩下才进余额。")
                Text("净资产（市值 − 借款）低于本金的 20% 会被强制平仓，低于 50% 会提醒补保证金。")
                Text("追加保证金会先拿去还借款，不会让本金越滚越大。")
                    .font(.system(size: 11)).foregroundColor(DS.text2)
            }
        }
    }

    private var ticker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 16) {
                ForEach(Array(stocks.enumerated()), id: \.offset) { item in
                    let s = item.element
                    HStack(spacing: 5) {
                        Text(Fmt.plain(s.s("name"))).font(.system(size: 12)).foregroundColor(DS.text2)
                        Text(Fmt.money(s.d("price"))).font(.system(size: 12, weight: .semibold))
                            .foregroundColor(s.d("change") >= 0 ? .red : DS.green)
                        Text(String(format: "%+.1f%%", s.d("change") * 100))
                            .font(.system(size: 11)).foregroundColor(s.d("change") >= 0 ? .red : DS.green)
                    }
                }
            }
            .padding(.horizontal, 14).padding(.vertical, 8)
        }
        .background(DS.card)
        .overlay(Rectangle().frame(height: 0.5).foregroundColor(DS.cardLine), alignment: .top)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.system(size: 13)).foregroundColor(DS.text2)
            Spacer()
            Text(value).font(.system(size: 13, weight: .medium)).foregroundColor(DS.text)
        }
    }

    private func trade(_ buying: Bool) {
        guard !selected.isEmpty || !stocks.isEmpty else { return }
        busy = true
        let id = current.s("id")
        let body: Map = ["action": buying ? "buy" : "sell", "id": id, "shares": shares]
        store.stockAction(body) { err in
            self.busy = false
            haptic(err == nil)
            if let e = err { store.toast(e) } else { store.toast(buying ? "买入成功" : "卖出成功") }
        }
    }

    private func leverage() {
        let alert = UIAlertController(title: "设置杠杆", message: "1 ~ 100 倍", preferredStyle: .alert)
        alert.addTextField { tf in
            tf.placeholder = "杠杆倍数"
            tf.keyboardType = .numberPad
            tf.text = String(store.stockData.i("leverage"))
        }
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        alert.addAction(UIAlertAction(title: "确定", style: .default) { _ in
            let raw = alert.textFields?.first?.text ?? ""
            let lev = max(1, min(100, Int(raw) ?? 10))
            busy = true
            store.stockAction(["action": "leverage", "leverage": lev]) { err in
                busy = false
                haptic(err == nil)
                store.toast(err ?? "杠杆已设为 \(lev) 倍")
            }
        })
        topMost()?.present(alert, animated: true)
    }

    private func topMost() -> UIViewController? {
        var vc = UIApplication.shared.keyWindow?.rootViewController
        while vc?.presentedViewController != nil { vc = vc?.presentedViewController }
        return vc
    }

    private func closeAll() {
        let id = current.s("id") // 只平当前正在看的这只股票
        busy = true
        var body: Map = ["action": "closeall"]
        if !id.isEmpty { body["id"] = id }
        store.stockAction(body) { err in
            busy = false
            haptic(err == nil)
            store.toast(err ?? "已全部平仓")
        }
    }

    private func margin() {
        busy = true
        store.stockAction(["action": "margin", "amount": 50000]) { err in
            self.busy = false
            store.toast(err ?? "已追加 5 万保证金")
        }
    }
}

/// 极简 K 线：每根蜡烛 = 一根竖线 + 实体
struct KLine: View {
    let values: [Double]

    var body: some View {
        GeometryReader { geo in
            let n = max(values.count, 1)
            let w = geo.size.width / CGFloat(n)
            let lo = values.min() ?? 0
            let hi = values.max() ?? 1
            let span = max(hi - lo, 0.01)
            Path { p in
                for (i, v) in values.enumerated() {
                    let x = w * (CGFloat(i) + 0.5)
                    let y = geo.size.height * (1 - CGFloat((v - lo) / span))
                    p.move(to: CGPoint(x: x, y: y))
                    p.addLine(to: CGPoint(x: x, y: geo.size.height))
                }
            }
            .stroke(Color.gray.opacity(0.25), lineWidth: 1)

            Path { p in
                guard values.count > 1 else { return }
                for (i, v) in values.enumerated() {
                    let x = w * (CGFloat(i) + 0.5)
                    let y = geo.size.height * (1 - CGFloat((v - lo) / span))
                    if i == 0 { p.move(to: CGPoint(x: x, y: y)) } else { p.addLine(to: CGPoint(x: x, y: y)) }
                }
            }
            .stroke(DS.text.opacity(0.75), lineWidth: 1.6)
        }
    }
}

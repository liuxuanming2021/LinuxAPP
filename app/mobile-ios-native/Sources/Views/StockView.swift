import SwiftUI
import UIKit

/// 股市：上面 K 线，中间持仓与买卖，底部实时行情条
struct StockView: View {
    @State private var timer = Timer.publish(every: 4, on: .main, in: .common).autoconnect()
    @State private var blinkTimer = Timer.publish(every: 0.7, on: .main, in: .common).autoconnect()
    @EnvironmentObject var store: AppState

    @State private var selected = ""
    @State private var shares = 10
    @State private var busy = false
    @State private var showRank = false
    // 红档强制确认弹窗：每次快照仍在红档就重弹，30 秒冷却避免刷屏
    @State private var lastDanger = Date.distantPast
    // 红档比例数字闪烁
    @State private var blink = false

    private var stocks: [Map] {
        store.stockData.a("stocks")
    }

    private var current: Map {
        stocks.first(where: { $0.s("id") == selected }) ?? stocks.first ?? [:]
    }

    /// 风险档位：0 安全 / 1 黄色预警 / 2 红色高危 / 3 爆仓（爆仓由服务端直接强平）
    private var risk: Int { store.stockData.i("risk") }
    /// 空仓 / 没有借款：所有比率类显示 "--"
    private var isEmptyAccount: Bool {
        store.stockData.d("value") <= 0.01 || store.stockData.d("loan") <= 0.01
    }

    private var riskColor: Color {
        if risk >= 2 { return .red }
        if risk == 1 { return .yellow }
        return DS.green
    }

    private var ratioText: String {
        isEmptyAccount ? "--" : String(format: "%.2f%%", store.stockData.d("ratio"))
    }

    private var liqValueText: String {
        isEmptyAccount ? "--" : Fmt.money(store.stockData.d("liqValue"))
    }

    private var liqDropText: String {
        isEmptyAccount ? "--" : String(format: "%.2f%%", store.stockData.d("liqDropPct"))
    }

    /// 空仓 / 没借款时「追加保证金」没有意义
    private var canAddMargin: Bool { !isEmptyAccount }

    private func haptic(_ ok: Bool) {
        let g = UINotificationFeedbackGenerator()
        g.notificationOccurred(ok ? .success : .error)
    }

    private func autoStart() { store.loadStock { self.checkRisk() } }
    private func autoStop() { }

    var body: some View {
        Group {
            if !store.stockAvailable {
                empty("服务器没装 LinuxShop，股市不可用")
            } else if !store.stockData.b("usable") {
                empty("金币需 ≥ " + Fmt.money(store.stockData.d("minBalance")) + " 才能使用股市")
            } else {
                VStack(spacing: 0) {
                    // 黄档 / 红档横幅常驻在页面顶部，比例回到安全区就消失
                    if risk >= 1 {
                        riskBanner
                            .padding(.horizontal, 14)
                            .padding(.top, 10)
                    }
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
                // 红档：屏幕边缘红色光晕（随闪烁一起呼吸）
                .overlay(
                    Rectangle()
                        .stroke(riskColor, lineWidth: risk >= 2 ? 14 : 0)
                        .blur(radius: 18)
                        .opacity(risk >= 2 ? (blink ? 0.85 : 0.35) : 0)
                        .allowsHitTesting(false)
                        .edgesIgnoringSafeArea(.all)
                )
            }
        }
        .background(DS.bg.edgesIgnoringSafeArea(.all))
        .navigationBarTitle(Text("股市"), displayMode: .inline)
        .navigationBarItems(trailing: HStack(spacing: 14) {
            Button("排行榜") { showRank = true }
            Button(action: { store.loadStock { self.checkRisk() } }) {
                Image(systemName: "arrow.clockwise")
            }
        })
        .sheet(isPresented: $showRank) {
            RankSheet(initialType: "stock").environmentObject(store)
        }
        .onAppear { autoStart() }
        .onDisappear { autoStop() }
        .onReceive(timer) { _ in
            store.loadStock { self.checkRisk() }
        }
        .onReceive(blinkTimer) { _ in
            if risk >= 2 { blink.toggle() } else { blink = false }
        }
    }

    /// 红色高危区：强制确认弹窗，只有「我知道了」一个按钮，关掉后只要还在红档就 30 秒再弹一次。
    private func checkRisk() {
        guard risk >= 2 else { return }
        guard Date().timeIntervalSince(lastDanger) > 30 else { return }
        lastDanger = Date()
        let msg = "维持担保比例 " + String(format: "%.2f%%", store.stockData.d("ratio"))
            + "，距离强制平仓仅剩 " + liqDropText + " 的跌幅！\n\n"
            + "系统随时可能自动卖出您的全部持仓，请立即追加保证金。"
        let alert = UIAlertController(title: "【极度危险】", message: msg, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "我知道了", style: .destructive))
        topMost()?.present(alert, animated: true)
    }

    /// 黄档常驻横幅：比例回到 100.5% 以上自动消失
    private var riskBanner: some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: risk >= 2 ? "exclamationmark.triangle.fill" : "exclamationmark.triangle")
                .font(.system(size: 14)).foregroundColor(.white)
            Text(risk >= 2
                ? "【极度危险】距离强制平仓仅剩最后 0.1% 的跌幅！系统随时可能自动卖出您的全部持仓，请立即追加保证金！"
                : "【风险预警】维持担保比例已低于 100.5%，股价稍作下跌即触发强平，请及时追加保证金或减仓。")
                .font(.system(size: 12, weight: .medium)).foregroundColor(.white)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(risk >= 2 ? Color.red : Color.orange)
        .cornerRadius(11)
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
                HStack {
                    Text("我的账户").font(.system(size: 15, weight: .semibold)).foregroundColor(DS.text)
                    Spacer()
                    if risk >= 1 {
                        Text(risk >= 2 ? "极度危险" : "预警中")
                            .font(.system(size: 11, weight: .bold)).foregroundColor(.white)
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(riskColor).cornerRadius(8)
                    }
                }
                row("余额", Fmt.money(store.stockData.d("balance")))
                row("本金", Fmt.money(store.stockData.d("margin")))
                row("借款", Fmt.money(store.stockData.d("loan")))
                row("总市值", Fmt.money(store.stockData.d("value")), risk >= 1 ? riskColor : nil)
                // 维持担保比例 = 总市值 ÷ 借款；空仓没有借款，显示 --
                row("维持担保比例", ratioText, isEmptyAccount ? DS.text2 : riskColor)
                    .opacity(risk >= 2 && blink ? 0.3 : 1)
                // 爆仓线直接给触发市值（借款 × 1.002），空仓显示 --
                row("爆仓触发市值", liqValueText, risk >= 2 ? .red : nil)
                row("距爆仓还需下跌", liqDropText, risk >= 2 ? .red : nil)
                row("浮动盈亏", store.stockData.s("pnlText"),
                    store.stockData.d("pnl") >= 0 ? .red : DS.green)
                row("杠杆", "\(store.stockData.i("leverage")) 倍")
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .stroke(riskColor.opacity(risk >= 1 ? 0.9 : 0), lineWidth: 1.5)
            .shadow(color: riskColor.opacity(risk >= 2 ? 0.7 : 0), radius: risk >= 2 ? 10 : 0))
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
                        .background(risk >= 1 ? DS.chip : store.accent.color)
                        .foregroundColor(risk >= 1 ? DS.muted : .white)
                        .cornerRadius(11)
                        .disabled(risk >= 1)
                    Button("卖出") { trade(false) }
                        .frame(maxWidth: .infinity).padding(.vertical, 11)
                        .background(DS.chip).foregroundColor(DS.text)
                        .cornerRadius(11)
                }
                .disabled(busy)

                if risk >= 1 {
                    Text(risk >= 2
                        ? "⚠ 极度危险：买入已锁定，只能卖出或追加保证金，随时可能被强制平仓。"
                        : "⚠ 风险预警：买入已锁定，只能卖出或追加保证金。")
                        .font(.system(size: 11, weight: .medium)).foregroundColor(riskColor)
                }

                HStack(spacing: 10) {
                    Button("调杠杆 (\(store.stockData.i("leverage")) 倍)") { leverage() }
                    Button("全部平仓") { closeAll() }
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background(DS.chip).foregroundColor(DS.text).cornerRadius(11)
                    // 空仓（没持仓 / 没借款）时追加保证金没有意义，直接禁用变灰
                    Button("追加保证金 5 万") { margin() }
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background(DS.chip)
                        .foregroundColor(canAddMargin ? DS.text : DS.muted)
                        .cornerRadius(11)
                        .disabled(!canAddMargin)
                }
                .disabled(busy)

                Text("买入仅扣「市值÷100」的本金，其余99%为系统借款。100倍杠杆下，标的每跌1%，您的本金将亏损100%。")
                Text("当「总市值 ÷ 借款」低于100.2%时，系统将强制平仓。追加保证金将直接增加您的本金，绝不用作偿还借款，以降低杠杆率。")
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

    private func row(_ label: String, _ value: String, _ color: Color? = nil) -> some View {
        HStack {
            Text(label).font(.system(size: 13)).foregroundColor(DS.text2)
            Spacer()
            Text(value).font(.system(size: 13, weight: .medium)).foregroundColor(color ?? DS.text)
        }
    }

    private func trade(_ buying: Bool) {
        guard !selected.isEmpty || !stocks.isEmpty else { return }
        if buying && risk >= 1 {
            store.toast("已进入风险预警区（维持担保比例 ≤ 100.5%），只能卖出或追加保证金")
            return
        }
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
        guard canAddMargin else {
            store.toast("空仓不用追加保证金，直接买入就行")
            return
        }
        busy = true
        store.stockAction(["action": "margin", "amount": 50000]) { err in
            self.busy = false
            haptic(err == nil)
            store.toast(err ?? "已追加 5 万：全额进本金、借款不变，杠杆降下来了")
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

import SwiftUI

/// 股市：上面 K 线，中间持仓与买卖，底部实时行情条
struct StockView: View {
    @State private var timer = Timer.publish(every: 4, on: .main, in: .common).autoconnect()
    @EnvironmentObject var store: AppState

    @State private var selected = ""
    @State private var shares = 10
    @State private var busy = false

    private var stocks: [Map] {
        store.stockData.a("stocks")
    }

    private var current: Map {
        stocks.first(where: { $0.s("id") == selected }) ?? stocks.first ?? [:]
    }

    private func autoStart() { store.loadStock() }
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
        .navigationBarItems(trailing: Button(action: { store.loadStock() }) {
            Image(systemName: "arrow.clockwise")
        })
        .onAppear { store.loadStock(); autoStart() }
        .onDisappear { autoStop() }
        .onReceive(timer) { _ in
            store.loadStock()
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
                row("保证金 / 借款", Fmt.money(store.stockData.d("margin")) + " / " + Fmt.money(store.stockData.d("loan")))
                row("杠杆 / 维持比例", "\(store.stockData.i("leverage")) 倍 / " + String(format: "%.2f", store.stockData.d("ratio")))
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
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background(DS.chip).foregroundColor(DS.text).cornerRadius(11)
                    Button("追加保证金 5 万") { margin() }
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                        .background(DS.chip).foregroundColor(DS.text).cornerRadius(11)
                }
                .disabled(busy)

                Text("买入只扣「市值 ÷ 杠杆」的保证金；维持担保比例低于 1.00 会被强制平仓。")
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
            if let e = err { store.toast(e) } else { store.toast(buying ? "买入成功" : "卖出成功") }
        }
    }

    private func leverage() {
        let cur = store.stockData.i("leverage")
        let steps = [1, 2, 5, 10]
        var next = 1
        for (i, v) in steps.enumerated() where v == cur { next = steps[(i + 1) % steps.count] }
        busy = true
        store.stockAction(["action": "leverage", "leverage": next]) { err in
            self.busy = false
            store.toast(err ?? "杠杆已调到 \(next) 倍")
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

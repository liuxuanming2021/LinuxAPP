import SwiftUI

/// 排行榜卡片：金币 / 点券 / 股市 / 等级 四榜切换，下面列前 10 名。
/// 「更多」页直接放一张，股市页用 RankSheet 包成弹窗，两边是同一份数据。
struct RankCard: View {
    @EnvironmentObject var store: AppState
    @State private var timer = Timer.publish(every: 10, on: .main, in: .common).autoconnect()

    private let types: [[String]] = [["coins", "金币"], ["points", "点券"], ["stock", "股市"], ["level", "等级"]]

    private var rows: [Map] { store.rankList }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("排行榜").font(.system(size: 15, weight: .semibold)).foregroundColor(DS.text)
                Spacer()
                if store.rankTime > 0 {
                    Text(Fmt.time(store.rankTime) + " 更新")
                        .font(.system(size: 11)).foregroundColor(DS.muted)
                }
            }

            Picker("", selection: Binding(get: { store.rankType }, set: { v in
                store.rankType = v
                store.loadRank(v)
            })) {
                ForEach(0..<types.count, id: \.self) { i in
                    Text(types[i][1]).tag(types[i][0])
                }
            }
            .pickerStyle(SegmentedPickerStyle())
            .labelsHidden()

            if !store.rankAvailable {
                Text("服务器没装 LinuxShop，这个榜暂时没有数据")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 8)
            } else if rows.isEmpty {
                Text("还没有人上榜，10 秒后自动刷新")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 8)
            } else {
                VStack(spacing: 0) {
                    ForEach(Array(rows.prefix(10).enumerated()), id: \.offset) { item in
                        row(item.offset, item.element)
                        if item.offset < min(rows.count, 10) - 1 {
                            Divider().opacity(0.3)
                        }
                    }
                }
            }
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .onAppear { store.loadRank(store.rankType) }
        .onReceive(timer) { _ in store.loadRank(store.rankType) }
    }

    private func row(_ i: Int, _ r: Map) -> some View {
        HStack(spacing: 10) {
            Text("\(i + 1)")
                .font(.system(size: 13, weight: .bold))
                .foregroundColor(rankColor(i))
                .frame(width: 26, alignment: .center)
            Text(Fmt.plain(r.s("name")))
                .font(.system(size: 14)).foregroundColor(DS.text)
                .lineLimit(1)
            Spacer()
            Text(valueText(r))
                .font(.system(size: 14, weight: .semibold)).foregroundColor(DS.text)
        }
        .padding(.vertical, 7)
    }

    private func valueText(_ r: Map) -> String {
        let v = r.d("value")
        if store.rankType == "level" { return "Lv." + Fmt.short(v) }
        return Fmt.short(v)
    }

    /// 前三名金银铜
    private func rankColor(_ i: Int) -> Color {
        if i == 0 { return Color(red: 0.95, green: 0.72, blue: 0.20) }
        if i == 1 { return Color(red: 0.72, green: 0.75, blue: 0.80) }
        if i == 2 { return Color(red: 0.80, green: 0.52, blue: 0.26) }
        return DS.muted
    }
}

/// 股市页右上角「排行榜」按钮弹出来的弹窗，默认选中「股市」。
struct RankSheet: View {
    @EnvironmentObject var store: AppState
    @Environment(\.presentationMode) private var presentation
    var initialType = "coins"

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 14) {
                    RankCard()
                    Spacer().frame(height: 20)
                }
                .padding(16)
            }
            .background(DS.bg.edgesIgnoringSafeArea(.all))
            .navigationBarTitle(Text("排行榜"), displayMode: .inline)
            .navigationBarItems(trailing: Button("关闭") { presentation.wrappedValue.dismiss() })
        }
        .navigationViewStyle(StackNavigationViewStyle())
        .onAppear {
            store.rankType = initialType
            store.loadRank(initialType)
        }
    }
}

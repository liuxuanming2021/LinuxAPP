import SwiftUI

struct TitlesView: View {
    @EnvironmentObject var store: AppState
    private var accent: Color { store.accent.color }

    private let statName: [String: String] = [
        "mining": "挖矿", "farming": "收获", "deaths": "死亡",
        "fishing": "钓鱼", "kills": "击杀", "mobkills": "杀怪"
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                activeCard
                listCard
                Spacer().frame(height: 90)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
        }
        .onAppear { store.loadTitles() }
    }

    private var header: some View {
        HStack {
            Text("头衔").font(.system(size: 22, weight: .bold))
            Spacer()
            if store.titleTotal > 0 {
                Text("\(store.titleUnlocked) / \(store.titleTotal)")
                    .font(.system(size: 13)).foregroundColor(DS.muted)
            }
        }
        .padding(.top, 4)
    }

    private func activeName() -> String {
        if let t = store.titles.first(where: { $0.b("active") }) {
            return Fmt.plain(t.s("display", t.s("id")))
        }
        return store.activeTitle.isEmpty ? "还没戴头衔" : Fmt.plain(store.activeTitle)
    }

    private var activeCard: some View {
        Card {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text(activeName()).font(.system(size: 18, weight: .semibold))
                    Text(store.activeTitle.isEmpty ? "在下面挑一个戴上" : "当前佩戴中")
                        .font(.system(size: 12)).foregroundColor(DS.muted)
                }
                Spacer()
                if !store.activeTitle.isEmpty {
                    Button(action: { store.equipTitle(nil) }) {
                        Text("取下")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundColor(accent)
                            .padding(.horizontal, 12).padding(.vertical, 7)
                            .background(accent.opacity(0.12))
                            .clipShape(Capsule())
                    }
                    .buttonStyle(PlainButtonStyle())
                }
            }
        }
    }

    private var listCard: some View {
        Card {
            if store.titles.isEmpty {
                Text("服务器上没有配头衔，或者没装 LinuxTitle")
                    .font(.system(size: 13)).foregroundColor(DS.muted)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 18)
            } else {
                ForEach(store.titles.indices, id: \.self) { i in
                    self.row(store.titles[i])
                    if i < store.titles.count - 1 {
                        Divider().background(DS.cardLine)
                    }
                }
            }
        }
    }

    private func row(_ t: Map) -> some View {
        let unlocked = t.b("unlocked")
        let active = t.b("active")
        return HStack(alignment: .top, spacing: 10) {
            VStack(alignment: .leading, spacing: 5) {
                Text(Fmt.plain(t.s("display", t.s("id"))))
                    .font(.system(size: 15, weight: active ? .semibold : .regular))
                    .foregroundColor(unlocked || active ? DS.text : DS.text2)
                Text(condText(t))
                    .font(.system(size: 12)).foregroundColor(DS.muted)
                if !unlocked && t.d("progress") > 0 && t.d("conditionValue") > 0 {
                    let pct = min(1.0, t.d("progress") / max(1, t.d("conditionValue")))
                    ProgressBar(pct: pct, accent: accent)
                        .frame(height: 6)
                    Text("进度 \(Int(pct * 100))%")
                        .font(.system(size: 11)).foregroundColor(DS.muted)
                }
            }
            Spacer(minLength: 8)
            if active {
                Tag(text: "佩戴中", tint: accent)
            } else if unlocked {
                Button(action: { store.equipTitle(t.s("id")) }) {
                    Text("戴上")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundColor(accent)
                        .padding(.horizontal, 12).padding(.vertical, 7)
                        .background(accent.opacity(0.12))
                        .clipShape(Capsule())
                }
                .buttonStyle(PlainButtonStyle())
            } else {
                Tag(text: "未解锁", tint: DS.muted)
            }
        }
        .padding(.vertical, 11)
    }

    private func condText(_ t: Map) -> String {
        let perm = t.s("permission")
        if !perm.isEmpty { return "需要权限 \(perm)" }
        let v = t.d("conditionValue")
        switch t.s("conditionType") {
        case "playtime":       return "累计在线 \(Int(v / 3_600_000)) 小时"
        case "daily_playtime": return "今日在线 \(Int(v / 3_600_000)) 小时"
        case "statistic":      return "\(statName[t.s("statisticType")] ?? t.s("statisticType")) \(Int(v)) 次"
        case "festival":       return "节日活动"
        case "manual":         return "管理员发放"
        case "":               return "—"
        case let other:        return other
        }
    }
}

/// 细进度条（经验、头衔解锁进度都用它）
struct ProgressBar: View {
    var pct: Double
    var accent: Color

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(DS.chip)
                Capsule().fill(accent)
                    .frame(width: max(0, min(1, pct)) * geo.size.width)
            }
        }
    }
}

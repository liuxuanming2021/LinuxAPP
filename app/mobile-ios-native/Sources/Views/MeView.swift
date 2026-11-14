import SwiftUI

struct MeView: View {
    @EnvironmentObject var store: AppState
    @State private var reportTarget = ""
    @State private var reportReason = ""

    private var accent: Color { store.accent.color }

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                header
                stats
                expCard
                checkInCard
                reportCard
                Spacer().frame(height: 90)
            }
            .padding(.horizontal, 16)
            .padding(.top, 6)
        }
        .onAppear {
            Task { await store.loadMe() }
            Task { await store.loadCheckIn() }
            store.loadMyReports()
        }
    }

    private var header: some View {
        HStack(spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(accent.opacity(0.18))
                Text(String(store.name.prefix(1)).uppercased())
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(accent)
            }
            .frame(width: 62, height: 62)

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 6) {
                    Text(store.name).font(.system(size: 21, weight: .bold))
                    Circle().fill(store.online.contains(where: { $0.id == store.name }) ? DS.green : DS.muted)
                        .frame(width: 7, height: 7)
                }
                HStack(spacing: 6) {
                    Tag(text: store.role == "player" ? "玩家" : store.role, tint: accent)
                    if !store.teamName.isEmpty { Tag(text: store.teamName, tint: DS.muted) }
                }
            }
            Spacer()
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var stats: some View {
        HStack(spacing: 10) {
            StatCard(title: "金币", value: store.hasCoins ? shortNumber(store.coins) : "—")
            StatCard(title: "点券", value: store.hasPoints ? "\(store.points)" : "—")
            StatCard(title: "等级", value: "\(store.level)")
        }
    }

    private var expCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("经验进度").font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("\(store.expCurrent) / \(store.expNeeded) 经验")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(DS.chip)
                    Capsule().fill(accent)
                        .frame(width: geo.size.width * progress)
                }
            }
            .frame(height: 7)
            Text("总经验 \(store.expCurrent)").font(.system(size: 12)).foregroundColor(DS.muted)
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var progress: CGFloat {
        guard store.expNeeded > 0 else { return 0 }
        return min(1, CGFloat(store.expCurrent) / CGFloat(store.expNeeded))
    }

    private var checkInCard: some View {
        HStack(spacing: 12) {
            Text("📅").font(.system(size: 26))
                .frame(width: 44, height: 44)
                .background(accent.opacity(0.16))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 3) {
                Text("连续 \(store.signStreak) 天").font(.system(size: 15, weight: .semibold))
                Text("每天 0 点重置，随机发金币或点券")
                    .font(.system(size: 12)).foregroundColor(DS.muted)
            }
            Spacer()
            Button { store.signIn() } label: {
                Text(store.signedToday ? "已签到" : "签到")
                    .font(.system(size: 14, weight: .semibold))
                    .padding(.horizontal, 18).padding(.vertical, 9)
                    .background(store.signedToday ? DS.chip : accent)
                    .foregroundColor(store.signedToday ? DS.muted : .white)
                    .clipShape(Capsule())
            }
            .disabled(store.signedToday)
        }
        .padding(14)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private var reportCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("举报玩家").font(.system(size: 16, weight: .semibold))
                Spacer()
                Text("管理员会在服务器里收到").font(.system(size: 12)).foregroundColor(DS.muted)
            }
            TextField("被举报玩家的游戏 ID", text: $reportTarget)
                .padding(.horizontal, 14).padding(.vertical, 13)
                .background(DS.chip)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .autocapitalization(.none)
                .disableAutocorrection(true)
            TextField("理由（刷屏 / 外挂 / 骂人…）", text: $reportReason)
                .padding(.horizontal, 14).padding(.vertical, 13)
                .background(DS.chip)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            Button {
                store.submitReport(target: reportTarget, reason: reportReason)
            } label: {
                Text("提交举报")
                    .font(.system(size: 15, weight: .semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .background(accent).foregroundColor(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(PlainButtonStyle())

            if !store.myReports.isEmpty {
                Divider().background(DS.cardLine)
                Text("我提交的举报").font(.system(size: 13, weight: .semibold))
                ForEach(store.myReports.prefix(6).indices, id: \.self) { i in
                    let r = store.myReports[i]
                    HStack(alignment: .top, spacing: 8) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("#\(r.i("id")) \(r.s("target", "-"))")
                                .font(.system(size: 13.5, weight: .medium))
                            Text(r.s("reason")).font(.system(size: 11.5)).foregroundColor(DS.muted)
                        }
                        Spacer()
                        Tag(text: reportState(r), tint: r.b("handled") ? DS.green : DS.muted)
                    }
                    .padding(.vertical, 4)
                }
            }
        }
        .padding(16)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }

    private func reportState(_ r: Map) -> String {
        if !r.b("handled") { return "待处理" }
        return r.s("result") == "reject" ? "已驳回" : "已受理"
    }

    private func shortNumber(_ v: Double) -> String {
        if v >= 1_000_000 { return String(format: "%.1fM", v / 1_000_000) }
        if v >= 1_000 { return String(format: "%.0f", v) }
        return String(format: "%.0f", v)
    }
}

struct StatCard: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(size: 12)).foregroundColor(DS.muted)
            Text(value).font(.system(size: 20, weight: .bold)).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(DS.card)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

struct Tag: View {
    let text: String
    var tint: Color = DS.muted

    var body: some View {
        Text(text)
            .font(.system(size: 11.5, weight: .medium))
            .padding(.horizontal, 9).padding(.vertical, 4)
            .background(tint.opacity(0.13))
            .foregroundColor(tint)
            .clipShape(Capsule())
    }
}

struct Hint: View {
    let text: String
    init(_ t: String) { text = t }
    var body: some View {
        Text(text)
            .font(.system(size: 14)).foregroundColor(DS.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14).padding(.vertical, 13)
            .background(DS.chip)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

import SwiftUI

struct AdminView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0

    private var accent: Color { store.accent.color }

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text("服务器管理").font(.system(size: 22, weight: .bold))
                Spacer()
                Text("有权限").font(.system(size: 12)).foregroundColor(DS.green)
            }
            .padding(.top, 6)

            Segmented(titles: ["在线", "举报"], index: $tab, accent: accent)

            if tab == 0 { onlineList } else { reportHint }

            Spacer(minLength: 78)
        }
        .padding(.horizontal, 16)
        .onAppear { Task { await store.loadOnline() } }
    }

    private var onlineList: some View {
        ScrollView {
            VStack(spacing: 0) {
                if store.online.isEmpty {
                    Text("现在没人在线").font(.system(size: 13)).foregroundColor(DS.muted)
                        .padding(.vertical, 26)
                } else {
                    ForEach(store.online, id: \.id) { p in
                        HStack {
                            Circle().fill(DS.green).frame(width: 7, height: 7)
                            Text(p.name).font(.system(size: 15))
                            Spacer()
                            Text(p.role).font(.system(size: 12)).foregroundColor(DS.muted)
                        }
                        .padding(.vertical, 12)
                        Divider().opacity(0.4)
                    }
                }
            }
            .padding(.horizontal, 16)
            .background(DS.card)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        }
    }

    private var reportHint: some View {
        Text("举报处理、玩家详情、惩罚操作在网页版里更完整，原生端先做只读列表。")
            .font(.system(size: 13)).foregroundColor(DS.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(DS.card)
            .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

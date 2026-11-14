import SwiftUI
import Combine

struct RootView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0
    @State private var toastVisible = false
    @State private var toastWork: DispatchWorkItem?

    private var accent: Color { store.accent.color }

    var body: some View {
        Group {
            if store.loggedIn {
                ZStack(alignment: .bottom) {
                    DS.bg.edgesIgnoringSafeArea(.all)

                    Group {
                        switch tab {
                        case 0: MeView()
                        case 1: ChatView()
                        case 2: TitlesView()
                        case 3: AdminView()
                        default: MoreView()
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.top, 54)

                    GlassTabBar(tab: $tab, items: tabs, accent: accent)
                        .padding(.bottom, 8)

                    // 顶部玻璃导航条：后加的在最上层
                    VStack(spacing: 0) {
                        GlassNavBar(title: pageTitle, caption: pageCaption, accent: accent,
                                    onRefresh: refreshNow)
                            .padding(.horizontal, 14)
                            .padding(.top, 4)
                        if toastVisible {
                            Text(store.toastText)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.white)
                                .padding(.horizontal, 14).padding(.vertical, 9)
                                .background(Color.black.opacity(0.82))
                                .clipShape(Capsule())
                                .padding(.top, 8)
                                .transition(.opacity)
                        }
                        Spacer()
                    }
                }
                .onReceive(store.$toastSeq) { _ in showToast() }
            } else {
                LoginView()
            }
        }
    }

    private func showToast() {
        toastWork?.cancel()
        withAnimation(.easeOut(duration: 0.18)) { toastVisible = true }
        let work = DispatchWorkItem {
            withAnimation(.easeIn(duration: 0.2)) { self.toastVisible = false }
        }
        toastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0, execute: work)
    }

    private var pageTitle: String {
        let t = tabs[min(tab, tabs.count - 1)].1
        switch t {
        case "我的": return "我的数据"
        case "社交": return "社交"
        case "头衔": return "头衔"
        case "管理": return "服务器管理"
        default:     return "更多"
        }
    }

    private var pageCaption: String {
        switch pageTitle {
        case "我的数据": return store.name.isEmpty ? "服务器上的你" : store.name
        case "社交":     return "公屏 / 队伍频道 / 队伍"
        case "头衔":     return "LinuxTitle"
        case "服务器管理": return "在线 / 玩家 / 举报 / 惩罚 / 假人"
        default:         return "设置 / 接口地址 / 关于"
        }
    }

    private func refreshNow() {
        switch tab {
        case 0: store.loadMeSoon(); store.loadMyReports()
        case 1: Task { await store.loadChat() }; store.loadTeam()
        case 2: store.loadTitles()
        case 3: Task { await store.loadOnline() }
        default: store.loadMeSoon()
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    private var tabs: [(String, String)] {
        var t: [(String, String)] = [
            ("person.fill", "我的"),
            ("bubble.left.and.bubble.right.fill", "社交"),
            ("ticket.fill", "头衔")
        ]
        if store.canManage { t.append(("hammer.fill", "管理")) }
        t.append(("ellipsis", "更多"))
        return t
    }
}

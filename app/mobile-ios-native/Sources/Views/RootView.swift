import SwiftUI

struct RootView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0

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

                    GlassTabBar(tab: $tab, items: tabs, accent: accent)
                        .padding(.bottom, 8)
                }
            } else {
                LoginView()
            }
        }
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

import SwiftUI
import Combine

/// 根视图：登录后是系统 TabView + NavigationView。
/// 底部标签栏、顶部导航栏全部是苹果原生控件（iOS 26 上系统自动渲染成液态玻璃）。
struct RootView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0
    @State private var toastOn = false
    @State private var toastWork: DispatchWorkItem?

    var body: some View {
        Group {
            if store.loggedIn {
                TabView(selection: $tab) {
                    NavigationView {
                        MeView()
                            .navigationBarTitle(Text("我的数据"), displayMode: .inline)
                            .navigationBarItems(trailing: refreshButton)
                    }
                    .navigationViewStyle(StackNavigationViewStyle())
                    .tabItem {
                        Image(systemName: "person.fill")
                        Text("我的")
                    }
                    .tag(0)

                    NavigationView {
                        ChatView()
                            .navigationBarTitle(Text("社交"), displayMode: .inline)
                            .navigationBarItems(trailing: refreshButton)
                    }
                    .navigationViewStyle(StackNavigationViewStyle())
                    .tabItem {
                        Image(systemName: "bubble.left.and.bubble.right.fill")
                        Text("社交")
                    }
                    .tag(1)

                    NavigationView {
                        TitlesView()
                            .navigationBarTitle(Text("头衔"), displayMode: .inline)
                            .navigationBarItems(trailing: refreshButton)
                    }
                    .navigationViewStyle(StackNavigationViewStyle())
                    .tabItem {
                        Image(systemName: "rosette")
                        Text("头衔")
                    }
                    .tag(2)

                    if store.canManage {
                        NavigationView {
                            AdminView()
                                .navigationBarTitle(Text("服务器管理"), displayMode: .inline)
                                .navigationBarItems(trailing: refreshButton)
                        }
                        .navigationViewStyle(StackNavigationViewStyle())
                        .tabItem {
                            Image(systemName: "hammer.fill")
                            Text("管理")
                        }
                        .tag(3)
                    }

                    NavigationView {
                        MoreView()
                            .navigationBarTitle(Text("更多"), displayMode: .inline)
                            .navigationBarItems(trailing: refreshButton)
                    }
                    .navigationViewStyle(StackNavigationViewStyle())
                    .tabItem {
                        Image(systemName: "ellipsis")
                        Text("更多")
                    }
                    .tag(4)
                }
                .accentColor(store.accent.color)
                .onReceive(store.$toastSeq) { seq in
                    if seq > 0 { showToast() }
                }
                .overlay(
                    Group {
                        if toastOn {
                            Text(store.toastText)
                                .font(.system(size: 13, weight: .medium))
                                .foregroundColor(.white)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 10)
                                .background(Color.black.opacity(0.84))
                                .cornerRadius(14)
                                .padding(.horizontal, 28)
                                .padding(.bottom, 14)
                                .transition(.opacity)
                        }
                    },
                    alignment: .bottom
                )
            } else {
                LoginView()
            }
        }
    }

    private var refreshButton: some View {
        Button(action: refreshNow) {
            Image(systemName: "arrow.clockwise")
        }
    }

    private func showToast() {
        toastWork?.cancel()
        withAnimation(.easeOut(duration: 0.18)) { toastOn = true }
        let work = DispatchWorkItem {
            withAnimation(.easeIn(duration: 0.2)) { toastOn = false }
        }
        toastWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.1, execute: work)
    }

    private func refreshNow() {
        switch tab {
        case 0:
            store.loadMeSoon()
            store.loadMyReports()
            Task { await store.loadCheckIn() }
        case 1:
            Task { await store.loadChat() }
            store.loadTeam()
        case 2:
            store.loadTitles()
        case 3:
            Task { await store.loadOnline() }
            store.loadStaff()
        default:
            store.loadMeSoon()
        }
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }
}

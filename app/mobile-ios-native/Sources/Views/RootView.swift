import SwiftUI
import Combine
import UIKit

/// 根视图：登录后是系统 TabView + NavigationView。
/// 底部标签栏、顶部导航栏全部是苹果原生控件（iOS 26 上系统自动渲染成液态玻璃）。
struct RootView: View {
    @EnvironmentObject var store: AppState
    @State private var tab = 0
    @State private var scrollKey = 0
    @State private var toastOn = false
    @State private var toastWork: DispatchWorkItem?

    var body: some View {
        Group {
            if store.booting {
                SplashView()
            } else if store.loggedIn {
                TabView(selection: Binding(get: { tab }, set: { v in
                tab = v
                store.tabIndex = v
                if v != 1 { scrollKey += 1 }
                refreshTab(v)
            })) {
                    NavigationView {
                        MeView()
                            .id("top-\(scrollKey)")
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
                            .id("top-\(scrollKey)")
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
                            .id("top-\(scrollKey)")
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
                            .id("top-\(scrollKey)")
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
                .onReceive(NotificationCenter.default.publisher(for: UIApplication.willEnterForegroundNotification)) { _ in
                    // 从后台回来：所有数据重新拉一遍
                    Task { await store.refreshAll() }
                    store.loadTeam()
                    store.loadTitles()
                    store.loadStaff()
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
                                .padding(.bottom, 74)
                                .transition(.opacity)
                        }
                    },
                    alignment: .bottom
                )
                .onReceive(store.$loggedIn) { on in
                    // 登录成功 / 冷启动恢复登录后，一律落在「我的」页，并从页面顶部开始
                    if on {
                        tab = 0
                        store.tabIndex = 0
                        scrollKey += 1
                        refreshTab(0)
                    }
                }
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

    private func refreshTab(_ v: Int) {
        switch v {
        case 0:
            Task { await store.loadMe() }
            Task { await store.loadCheckIn() }
            store.loadMyReports()
        case 1:
            store.loadTeam()
            Task { await store.loadChat() }
        case 2:
            store.loadTitles()
        case 4:
            store.loadHealth()
        default:
            break
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

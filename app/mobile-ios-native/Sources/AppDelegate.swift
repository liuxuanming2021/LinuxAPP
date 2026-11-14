import UIKit
import SwiftUI
import UserNotifications

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // 启动就把地址和令牌恢复好，界面首次渲染就能用
        AppState.shared.bootstrap()
        // 通知：前台也要弹横幅、响系统默认提示音，所以自己当代理
        UNUserNotificationCenter.current().delegate = self
        AppState.shared.askNotifyPermission()
        return true
    }

    /// App 在前台时也显示横幅并播放提示音（默认就是系统设置里的"新通知"声音）
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if #available(iOS 14.0, *) {
            completionHandler([.banner, .sound, .list])
        } else {
            completionHandler([.alert, .sound])
        }
    }

    func application(_ application: UIApplication,
                     configurationForConnecting connectingSceneSession: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let cfg = UISceneConfiguration(name: "Default", sessionRole: connectingSceneSession.role)
        cfg.delegateClass = SceneDelegate.self
        return cfg
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let win = (scene as? UIWindowScene) else { return }
        let window = UIWindow(windowScene: win)
        let host = UIHostingController(rootView: RootView().environmentObject(AppState.shared))
        host.view.backgroundColor = .clear
        window.rootViewController = host
        window.makeKeyAndVisible()
        self.window = window

        Task { await AppState.shared.restore() }
    }
}

import UIKit

@UIApplicationMain
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // 启动就把地址和令牌恢复好，界面首次渲染就能用
        AppState.shared.bootstrap()
        return true
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

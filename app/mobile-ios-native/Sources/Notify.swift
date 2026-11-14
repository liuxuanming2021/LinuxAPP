import Foundation
import UIKit
import UserNotifications

/// 软件内 / 软件外提醒。
/// 一律走系统本地通知：跟平时收到 iOS 通知一样（横幅 + 提示音），
/// 不自绘提示条、不额外震动，避免"不像系统通知"。
extension AppState {

    func askNotifyPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// 来了别人的新消息时调用
    func remind(_ fresh: [ChatMessage]) {
        let mine = fresh.filter { !$0.name.isEmpty && $0.name.lowercased() != name.lowercased() }
        guard let last = mine.last else { return }
        let who = last.name.isEmpty ? "服务器" : last.name
        let body = Fmt.plain(last.text)

        let active = UIApplication.shared.applicationState == .active
        if active && !notifyInApp { return }
        if !active && !notifyOut { return }

        let c = UNMutableNotificationContent()
        c.title = who
        c.body = body
        c.sound = .default
        c.threadIdentifier = "linuxapp-chat"
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil),
            withCompletionHandler: nil)
    }
}

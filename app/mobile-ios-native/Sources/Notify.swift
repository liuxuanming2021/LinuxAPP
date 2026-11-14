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

        // 前台：提示音永远响（哪怕「软件内提醒」关了，也只是不弹通知）
        if active {
            AlertSound.shared.play()
            // 正停在社交页（公屏 / 队伍频道）：只有声音，不弹通知
            if tabIndex == 1 && channel != "manage" { return }
            if !notifyInApp { return }
        } else {
            // 后台：要「软件外提醒」开着，并且「软件内提醒」也没关——关了就意味着不要通知
            if !notifyOut || !notifyInApp { return }
        }

        let c = UNMutableNotificationContent()
        c.title = who
        c.body = body
        // 前台刚自己响过了，通知再来一声就成双响；后台才用系统提示音
        c.sound = active ? nil : .default
        c.threadIdentifier = "linuxapp-chat"
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil),
            withCompletionHandler: nil)
    }
}

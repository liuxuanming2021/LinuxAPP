import Foundation
import AudioToolbox
import UIKit
import UserNotifications

/// 软件内 / 软件外提醒。
/// 两种情况都发一条本地通知，这样响的是系统默认提示音（iOS 17 就是"反弹"那个），
/// 不用自带音频文件；软件内再补一次振动和顶部提示条。
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
        c.title = "\(who) 在公屏说话"
        c.body = body
        c.sound = .default
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil),
            withCompletionHandler: nil)

        if active {
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
            toast(who + "：" + body)
        }
    }
}

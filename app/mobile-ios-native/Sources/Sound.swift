import Foundation
import AVFoundation
import UIKit

/// 提示音：和网页版（老客户端）一模一样的「叮—咚」两声，
/// 880Hz 与 1174Hz 各 0.13 秒、间隔 0.15 秒的 sine。
/// 自己算 PCM 生成 WAV，省得往包里塞音频文件。
enum ToneWAV {

    static func make(freqs: [Double], duration: Double = 0.13, gap: Double = 0.15, rate: Double = 44100) -> Data {
        let per = Int(rate * duration)
        let gapSamples = Int(rate * gap)
        let total = per * freqs.count + gapSamples * max(0, freqs.count - 1)
        var samples = [Int16](repeating: 0, count: total)
        var cursor = 0
        for f in freqs {
            for i in 0..<per {
                let t = Double(i) / rate
                // 头尾各 20ms 淡入淡出，避免爆音
                let fadeIn = min(1.0, t / 0.02)
                let fadeOut = min(1.0, max(0.0, (duration - t)) / 0.02)
                let env = fadeIn * fadeOut
                let v = sin(2 * Double.pi * f * t) * env * 0.35
                samples[cursor + i] = Int16(max(-1, min(1, v)) * 32767)
            }
            cursor += per + gapSamples
        }
        return wav(samples: samples, rate: Int(rate))
    }

    /// 静音（后台保活用，音量为 0 时系统可能把播放停掉，所以给一点极小的底噪）
    static func silence(seconds: Double = 1, rate: Double = 44100) -> Data {
        let n = Int(rate * seconds)
        var samples = [Int16](repeating: 0, count: n)
        for i in stride(from: 0, to: n, by: 4800) {
            samples[i] = 12
        }
        return wav(samples: samples, rate: Int(rate))
    }

    private static func wav(samples: [Int16], rate: Int) -> Data {
        var d = Data()
        let dataBytes = UInt32(samples.count * 2)
        func u32(_ v: UInt32) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        func u16(_ v: UInt16) { var x = v.littleEndian; withUnsafeBytes(of: &x) { d.append(contentsOf: $0) } }
        d.append(contentsOf: Array("RIFF".utf8))
        u32(36 + dataBytes)
        d.append(contentsOf: Array("WAVEfmt ".utf8))
        u32(16)
        u16(1)                       // PCM
        u16(1)                       // mono
        u32(UInt32(rate))
        u32(UInt32(rate * 2))        // byte rate
        u16(2)                       // block align
        u16(16)                      // bits
        d.append(contentsOf: Array("data".utf8))
        u32(dataBytes)
        for s in samples { u16(UInt16(bitPattern: s)) }
        return d
    }
}

/// 聊天提示音：只在前台、在社交页时响，不弹通知
final class AlertSound {
    static let shared = AlertSound()
    private var player: AVAudioPlayer?
    private var prepared = false

    func prepare() {
        guard !prepared else { return }
        prepared = true
        let data = ToneWAV.make(freqs: [880, 1174])
        player = try? AVAudioPlayer(data: data)
        player?.volume = 0.85
        player?.prepareToPlay()
    }

    func play() {
        prepare()
        let session = AVAudioSession.sharedInstance()
        // 前台用 ambient，不打断用户正在听的歌；后台保活时会话已经是 playback
        if UIApplication.shared.applicationState != .active {
            try? session.setCategory(.playback, options: [.mixWithOthers])
        } else {
            try? session.setCategory(.ambient, options: [.mixWithOthers])
        }
        try? session.setActive(true)
        player?.currentTime = 0
        player?.play()
    }
}

/// 退到后台还继续收消息：用一条几乎听不见的循环音频把 App 吊住，
/// 定时器就能继续轮询，来消息照样发系统通知。
final class BackgroundKeeper {
    static let shared = BackgroundKeeper()
    private var player: AVAudioPlayer?
    private(set) var running = false

    func start() {
        guard !running else { return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, options: [.mixWithOthers])
            try session.setActive(true)
            let p = try AVAudioPlayer(data: ToneWAV.silence(seconds: 1))
            p.numberOfLoops = -1
            p.volume = 0.02
            p.play()
            player = p
            running = true
        } catch {
            running = false
        }
    }

    func stop() {
        player?.stop()
        player = nil
        running = false
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }
}

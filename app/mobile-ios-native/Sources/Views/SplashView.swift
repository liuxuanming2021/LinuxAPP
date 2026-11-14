import SwiftUI
import UIKit

/// 冷启动时先盖住界面：等 restore() 判定「已登录 / 没登录」再决定进哪个页面，
/// 否则已经登录的人会先看到一下登录页。
struct SplashView: View {
    @State private var pulse = false

    var body: some View {
        ZStack {
            DS.bg.edgesIgnoringSafeArea(.all)
            VStack(spacing: 16) {
                ZStack {
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .fill(Color.white)
                        .frame(width: 84, height: 84)
                        .shadow(color: Color.black.opacity(0.16), radius: 18, x: 0, y: 8)
                    Image("AppMark")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 66, height: 66)
                }
                .scaleEffect(pulse ? 1.04 : 0.98)
                .animation(Animation.easeInOut(duration: 0.9).repeatForever(autoreverses: true))

                Text("LinuxAPP")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundColor(DS.text)

                Text("服务器数据 · 手机版")
                    .font(.system(size: 13))
                    .foregroundColor(DS.muted)

                ActivityIndicator(style: .medium)
                    .frame(width: 24, height: 24)
                    .padding(.top, 6)
            }
        }
        .onAppear { pulse = true }
    }
}

/// iOS 13 上没有 SwiftUI 的 ProgressView，这里包一层 UIActivityIndicatorView
struct ActivityIndicator: UIViewRepresentable {
    var style: UIActivityIndicatorView.Style = .medium

    func makeUIView(context: Context) -> UIActivityIndicatorView {
        let v = UIActivityIndicatorView(style: style)
        v.hidesWhenStopped = false
        v.startAnimating()
        return v
    }

    func updateUIView(_ ui: UIActivityIndicatorView, context: Context) {
        if !ui.isAnimating { ui.startAnimating() }
    }
}

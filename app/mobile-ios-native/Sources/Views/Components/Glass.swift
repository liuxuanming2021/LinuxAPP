import SwiftUI

/// 毛玻璃底：iOS 13 起可用。真正的「液态玻璃」是系统给的
/// UIVisualEffectView，这里把它包成 SwiftUI 视图，再叠一层高光。
struct VisualEffectBlur: UIViewRepresentable {
    var style: UIBlurEffect.Style = .systemUltraThinMaterial

    func makeUIView(context: Context) -> UIVisualEffectView {
        let v = UIVisualEffectView(effect: UIBlurEffect(style: style))
        v.isUserInteractionEnabled = false
        return v
    }

    func updateUIView(_ view: UIVisualEffectView, context: Context) {
        view.effect = UIBlurEffect(style: style)
    }
}

/// 一层玻璃：模糊 + 一点点白 + 顶部高光线 + 细边
struct GlassSurface: View {
    var cornerRadius: CGFloat = 26
    var tint: Color = .white
    var tintOpacity: Double = 0.07
    var strokeOpacity: Double = 0.30

    var body: some View {
        ZStack {
            VisualEffectBlur(style: .systemUltraThinMaterial)
            tint.opacity(tintOpacity)

            // 顶部一抹高光，让玻璃有厚度
            LinearGradient(
                gradient: Gradient(colors: [Color.white.opacity(0.22), Color.white.opacity(0.0)]),
                startPoint: .top,
                endPoint: .center
            )
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .stroke(
                    LinearGradient(
                        gradient: Gradient(colors: [Color.white.opacity(strokeOpacity),
                                                    Color.white.opacity(0.04)]),
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
        )
    }
}

/// 底部导航：悬浮玻璃胶囊 + 选中项的「玻璃透镜」
/// 参考 iOS 26 的样子：整条半透明，选中项下面浮起一颗放大的玻璃圆，
/// 圆里能透出后面的内容，图标略微放大。
struct GlassTabBar: View {
    @Binding var tab: Int
    let items: [(String, String)]          // (图标, 标题)
    var accent: Color

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { i in
                Button(action: {
                    if tab != i {
                        withAnimation(.spring(response: 0.34, dampingFraction: 0.78)) { tab = i }
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    }
                }) {
                    ZStack {
                        if tab == i {
                            lens
                                .transition(.scale(scale: 0.72).combined(with: .opacity))
                        }
                        VStack(spacing: 3) {
                            Image(systemName: items[i].0)
                                .font(.system(size: tab == i ? 20 : 18,
                                              weight: tab == i ? .semibold : .regular))
                            Text(items[i].1)
                                .font(.system(size: 10, weight: tab == i ? .semibold : .regular))
                        }
                        .foregroundColor(tab == i ? accent : DS.muted)
                        .offset(y: tab == i ? -1 : 0)
                        .scaleEffect(tab == i ? 1.04 : 1)
                    }
                    .frame(maxWidth: .infinity)
                    .frame(height: 54)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PlainButtonStyle())
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(
            GlassSurface(cornerRadius: 28, tint: .white, tintOpacity: 0.05, strokeOpacity: 0.34)
        )
        .shadow(color: Color.black.opacity(0.20), radius: 26, x: 0, y: 12)
        .padding(.horizontal, 14)
    }

    /// 选中项的玻璃透镜：外面一圈亮边，里面是模糊过的背景
    private var lens: some View {
        ZStack {
            VisualEffectBlur(style: .systemUltraThinMaterial)
            RadialGradient(
                gradient: Gradient(colors: [Color.white.opacity(0.38), Color.white.opacity(0.05)]),
                center: .topLeading,
                startRadius: 2,
                endRadius: 46
            )
            accent.opacity(0.07)
        }
        .frame(width: 56, height: 46)
        .clipShape(Capsule())
        .overlay(
            Capsule()
                .stroke(
                    LinearGradient(
                        gradient: Gradient(colors: [Color.white.opacity(0.62),
                                                    Color.white.opacity(0.08)]),
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
        )
        .shadow(color: Color.white.opacity(0.18), radius: 5, x: -1, y: -1)
        .shadow(color: Color.black.opacity(0.10), radius: 6, x: 0, y: 3)
    }
}

/// 顶部导航：悬浮的玻璃条，滚动时内容从它下面过
struct GlassNavBar: View {
    let title: String
    let caption: String
    var accent: Color
    var onRefresh: () -> Void = {}

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 17, weight: .bold))
                    .foregroundColor(DS.text)
                if !caption.isEmpty {
                    Text(caption)
                        .font(.system(size: 11))
                        .foregroundColor(DS.muted)
                }
            }
            Spacer()
            Button(action: onRefresh) {
                Image(systemName: "arrow.clockwise")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundColor(accent)
                    .frame(width: 32, height: 32)
                    .background(
                        Circle().fill(accent.opacity(0.12))
                    )
            }
            .buttonStyle(PlainButtonStyle())
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(GlassSurface(cornerRadius: 22, tint: .white, tintOpacity: 0.06, strokeOpacity: 0.32))
        .shadow(color: Color.black.opacity(0.10), radius: 16, x: 0, y: 6)
    }
}

/// 分段控件：灰底 + 白色滑块（和网页版同款交互）
struct Segmented: View {
    let titles: [String]
    @Binding var index: Int
    var accent: Color

    var body: some View {
        HStack(spacing: 2) {
            ForEach(titles.indices, id: \.self) { i in
                Button(action: {
                    withAnimation(.easeOut(duration: 0.22)) { index = i }
                }) {
                    Text(titles[i])
                        .font(.system(size: 13.5, weight: index == i ? .semibold : .regular))
                        .foregroundColor(index == i ? DS.text : DS.text2)
                        .lineLimit(1)
                        .minimumScaleFactor(0.85)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .fill(index == i ? DS.card : Color.clear)
                                .shadow(color: Color.black.opacity(index == i ? 0.12 : 0.0),
                                        radius: 5, x: 0, y: 2)
                        )
                }
                .buttonStyle(PlainButtonStyle())
            }
        }
        .padding(2)
        .background(DS.chip)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

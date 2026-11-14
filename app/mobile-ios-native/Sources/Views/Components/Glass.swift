import SwiftUI

/// 毛玻璃底：iOS 13 起可用，效果就是系统自带的「液态玻璃」那种半透明模糊。
struct VisualEffectBlur: UIViewRepresentable {
    var style: UIBlurEffect.Style = .systemUltraThinMaterial

    func makeUIView(context: Context) -> UIVisualEffectView {
        UIVisualEffectView(effect: UIBlurEffect(style: style))
    }
    func updateUIView(_ view: UIVisualEffectView, context: Context) {
        view.effect = UIBlurEffect(style: style)
    }
}

/// 底部导航：一块悬浮的玻璃胶囊，选中项下面有一层高亮。
struct GlassTabBar: View {
    @Binding var tab: Int
    let items: [(String, String)]      // (图标, 标题)
    var accent: Color

    var body: some View {
        HStack(spacing: 0) {
            ForEach(items.indices, id: \.self) { i in
                Button(action: {
                    if tab != i {
                        tab = i
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    }
                }) {
                    VStack(spacing: 3) {
                        Image(systemName: items[i].0)
                            .font(.system(size: 19, weight: tab == i ? .semibold : .regular))
                        Text(items[i].1).font(.system(size: 10.5, weight: tab == i ? .semibold : .regular))
                    }
                    .foregroundColor(tab == i ? accent : DS.muted)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                    .background(
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(tab == i ? accent.opacity(0.14) : Color.clear)
                    )
                }
                .buttonStyle(PlainButtonStyle())
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 6)
        .background(
            ZStack {
                VisualEffectBlur(style: .systemUltraThinMaterial)
                DS.card.opacity(0.35)
            }
        )
        .clipShape(RoundedRectangle(cornerRadius: 26, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous)
                    .stroke(Color.white.opacity(0.25), lineWidth: 1))
        .shadow(color: Color.black.opacity(0.18), radius: 22, x: 0, y: 10)
        .padding(.horizontal, 14)
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
                        .font(.system(size: 14, weight: index == i ? .semibold : .regular))
                        .foregroundColor(index == i ? DS.text : DS.text2)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 8)
                        .background(
                            RoundedRectangle(cornerRadius: 9, style: .continuous)
                                .fill(index == i ? DS.card : Color.clear)
                                .shadow(color: Color.black.opacity(index == i ? 0.10 : 0.0),
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

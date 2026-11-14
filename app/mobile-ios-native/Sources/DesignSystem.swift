import SwiftUI

/// 设计令牌：颜色 / 圆角 / 间距 / 玻璃层
/// iOS 端所有页面都从这里取值，换皮只改这里。
enum DS {
    // 强调色（和 App 的六种一致）
    enum Accent: String, CaseIterable, Identifiable {
        case sky = "天蓝", pink = "淡粉", green = "淡绿"
        case cream = "古董白", ink = "黑", gray = "灰"
        var id: String { rawValue }

        var color: Color {
            switch self {
            case .sky:   return Color(hex: 0x2E8BFF)
            case .pink:  return Color(hex: 0xE75A8D)
            case .green: return Color(hex: 0x2FAE74)
            case .cream: return Color(hex: 0xB9A07A)
            case .ink:   return Color(hex: 0x22262E)
            case .gray:  return Color(hex: 0x8A919E)
            }
        }
        var soft: Color { color.opacity(0.13) }
    }

    // 底色 / 卡片 / 分隔线（跟随系统深浅色）
    static let bg        = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: 0x0F1216) : UIColor(hex: 0xF3F5F9) })
    static let card      = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: 0x171B21) : UIColor.white })
    static let cardLine  = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor.white.withAlphaComponent(0.08) : UIColor.black.withAlphaComponent(0.06) })
    static let chip      = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor.white.withAlphaComponent(0.08) : UIColor.black.withAlphaComponent(0.05) })
    static let text      = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: 0xEEF1F6) : UIColor(hex: 0x14161A) })
    static let text2     = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: 0xC3CAD6) : UIColor(hex: 0x4A5060) })
    static let muted     = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: 0x8B93A1) : UIColor(hex: 0x8A919E) })
    static let green     = Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: 0x3DD68C) : UIColor(hex: 0x2FAE74) })

    // 圆角
    static let rCard: CGFloat = 22
    static let rCtl: CGFloat = 12
    static let rPill: CGFloat = 999

    // 间距
    static let pad: CGFloat = 16
}

extension Color {
    init(hex: UInt32) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: 1)
    }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255,
                  green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255,
                  alpha: 1)
    }
}

/// 卡片容器：圆角 + 背景 + 细边
struct Card<Content: View>: View {
    var padding: CGFloat = 16
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12, content: content)
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(DS.card)
            .clipShape(RoundedRectangle(cornerRadius: DS.rCard, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: DS.rCard, style: .continuous)
                        .stroke(DS.cardLine, lineWidth: 1))
    }
}

/// 列表里的一行：左标签、右值
struct InfoRow: View {
    let label: String
    let value: String
    var accent: Color? = nil

    var body: some View {
        HStack {
            Text(label).foregroundColor(DS.text2)
            Spacer(minLength: 12)
            Text(value)
                .foregroundColor(accent ?? DS.text)
                .fontWeight(.semibold)
        }
        .font(.system(size: 15))
        .padding(.vertical, 10)
        .overlay(Divider().background(DS.cardLine), alignment: .bottom)
    }
}

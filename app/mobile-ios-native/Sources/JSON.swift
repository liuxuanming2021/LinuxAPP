import Foundation

/// 服务端返回的是自由格式 JSON，字段随插件有无而变化，
/// 所以这里不做强类型 Codable，统一按字典取值，缺字段就给默认值。
typealias Map = [String: Any]

extension Dictionary where Key == String, Value == Any {
    func s(_ k: String, _ def: String = "") -> String {
        if let v = self[k] as? String { return v }
        if let v = self[k] as? NSNumber { return v.stringValue }
        return def
    }

    func i(_ k: String, _ def: Int = 0) -> Int {
        if let v = self[k] as? Int { return v }
        if let v = self[k] as? Double { return Int(v) }
        if let v = self[k] as? NSNumber { return v.intValue }
        if let v = self[k] as? String { return Int(v) ?? def }
        return def
    }

    func d(_ k: String, _ def: Double = 0) -> Double {
        if let v = self[k] as? Double { return v }
        if let v = self[k] as? Int { return Double(v) }
        if let v = self[k] as? NSNumber { return v.doubleValue }
        if let v = self[k] as? String { return Double(v) ?? def }
        return def
    }

    func b(_ k: String, _ def: Bool = false) -> Bool {
        if let v = self[k] as? Bool { return v }
        if let v = self[k] as? NSNumber { return v.boolValue }
        if let v = self[k] as? String { return v == "1" || v.lowercased() == "true" }
        return def
    }

    func a(_ k: String) -> [Map] { (self[k] as? [Map]) ?? [] }

    func m(_ k: String) -> Map { (self[k] as? Map) ?? [:] }
}

enum Fmt {
    /// 金币那种大数字：86,422,175 → 86.4M
    static func short(_ value: Double) -> String {
        let v = abs(value)
        if v >= 100_000_000 { return String(format: "%.1f亿", value / 100_000_000) }
        if v >= 10_000 { return String(format: "%.2f万", value / 10_000) }
        if value == value.rounded() { return String(Int(value)) }
        return String(format: "%.2f", value)
    }

    static func money(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = 2
        f.groupingSeparator = ","
        return f.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    static func time(_ ms: Double) -> String {
        if ms <= 0 { return "" }
        let d = Date(timeIntervalSince1970: ms / 1000)
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        return f.string(from: d)
    }

    static func day(_ ms: Double) -> String {
        if ms <= 0 { return "" }
        let d = Date(timeIntervalSince1970: ms / 1000)
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f.string(from: d)
    }

    static func hours(_ h: Double) -> String {
        if h <= 0 { return "0 小时" }
        if h < 1 { return "\(Int(h * 60)) 分钟" }
        if h < 24 { return String(format: "%.1f 小时", h) }
        return String(format: "%.1f 天", h / 24)
    }

    /// 去掉 § 颜色码
    static func plain(_ s: String) -> String {
        guard s.contains("§") else { return s }
        var out = ""
        var skip = false
        for ch in s {
            if skip { skip = false; continue }
            if ch == "§" { skip = true; continue }
            out.append(ch)
        }
        return out
    }
}

import SwiftUI
import UIKit

/// 玩家头像：优先按玩家名去 Mojang 官方接口查正版 UUID，再用这个 UUID 取正版皮肤的头。
/// 查不到（离线服 / 没买正版 / 网络不通）就退到 minotar 的名字头像，再不行显示首字母。
/// 图片和「名字 → UUID」的查询结果都缓存在内存里，列表滚动不会重复请求。
final class SkinCache {
    static let shared = SkinCache()
    private var cache: [String: UIImage] = [:]          // key -> 图片
    private var uuidByName: [String: String] = [:]      // 小写名字 -> UUID
    private var nameBusy: Set<String> = []
    private var busy: Set<String> = []

    func cached(_ key: String) -> UIImage? { cache[key] }

    /// 名字转 UUID（Mojang 官方接口，正版玩家才有）
    func resolveUUID(_ name: String, done: @escaping (String) -> Void) {
        let low = name.lowercased()
        if let hit = uuidByName[low] { done(hit); return }
        if nameBusy.contains(low) { return }
        guard let u = URL(string: "https://api.mojang.com/users/profiles/minecraft/\(name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name)") else {
            done(""); return
        }
        nameBusy.insert(low)
        var req = URLRequest(url: u)
        req.timeoutInterval = 6
        URLSession.shared.dataTask(with: req) { data, resp, _ in
            var id = ""
            if let data = data,
               let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let raw = obj["id"] as? String {
                // Mojang 给的是不带连字符的 32 位，crafatar 也认这种
                id = raw
            }
            DispatchQueue.main.async {
                self.nameBusy.remove(low)
                if !id.isEmpty { self.uuidByName[low] = id }
                done(id)
            }
        }.resume()
    }

    /// 取头像：有 uuid 走 crafatar（正版皮肤 + overlay），否则按名字走 minotar
    func load(_ key: String, uuid: String, name: String, size: Int, done: @escaping (UIImage?) -> Void) {
        if let img = cache[key] { done(img); return }
        if busy.contains(key) { return }
        busy.insert(key)
        fetch(uuid: uuid, name: name, size: size) { img in
            DispatchQueue.main.async {
                self.busy.remove(key)
                if let img = img { self.cache[key] = img }
                done(img)
            }
        }
    }

    private func fetch(uuid: String, name: String, size: Int, done: @escaping (UIImage?) -> Void) {
        // 1) 已经有 UUID：直接拿正版皮肤
        if !uuid.isEmpty {
            if let u = URL(string: "https://crafatar.com/avatars/\(uuid)?size=\(size)&overlay") {
                download(u) { img in
                    if let img = img { done(img); return }
                    self.fetchByName(name, size: size, done: done)
                }
                return
            }
        }
        // 2) 只知道名字：先问 Mojang 要 UUID，再拿正版皮肤；查不到用 minotar
        guard !name.isEmpty else { done(nil); return }
        resolveUUID(name) { id in
            if !id.isEmpty, let u = URL(string: "https://crafatar.com/avatars/\(id)?size=\(size)&overlay") {
                self.download(u) { img in
                    if let img = img { done(img); return }
                    self.fetchByName(name, size: size, done: done)
                }
            } else {
                self.fetchByName(name, size: size, done: done)
            }
        }
    }

    private func fetchByName(_ name: String, size: Int, done: @escaping (UIImage?) -> Void) {
        guard !name.isEmpty,
              let u = URL(string: "https://minotar.net/helm/\(name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name)/\(size).png") else {
            done(nil); return
        }
        download(u, done: done)
    }

    private func download(_ u: URL, done: @escaping (UIImage?) -> Void) {
        var req = URLRequest(url: u)
        req.timeoutInterval = 8
        URLSession.shared.dataTask(with: req) { data, _, _ in
            done(data.flatMap { UIImage(data: $0) })
        }.resume()
    }
}

/// 方形圆角的皮肤头像，加载中显示首字母。
struct SkinHead: View {
    let name: String
    var uuid: String = ""
    var size: CGFloat = 62
    var radius: CGFloat = 14

    @State private var image: UIImage?

    private var key: String { (uuid.isEmpty ? name.lowercased() : uuid) + "@\(Int(size))" }

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(DS.chip)
            if let img = image {
                Image(uiImage: img)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                Text(String(name.prefix(1)).uppercased())
                    .font(.system(size: size * 0.42, weight: .bold))
                    .foregroundColor(DS.muted)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
        .onAppear(perform: load)
        .onReceive(NotificationCenter.default.publisher(for: .skinReloaded)) { _ in
            if let hit = SkinCache.shared.cached(key) { image = hit }
        }
    }

    private func load() {
        if let hit = SkinCache.shared.cached(key) { image = hit; return }
        SkinCache.shared.load(key, uuid: uuid, name: name, size: Int(size * 3)) { img in
            if let img = img { self.image = img }
        }
    }
}

extension Notification.Name {
    /// 名字 -> UUID 解析完一批之后可以让已经显示过的头像再取一次
    static let skinReloaded = Notification.Name("LinuxAPP.skinReloaded")
}

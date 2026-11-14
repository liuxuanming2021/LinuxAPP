import SwiftUI

/// 玩家头像：直接用玩家名 / UUID 去公开皮肤站取正版皮肤的头，取不到就退回首字母。
/// 图片缓存在内存里，列表滚动不会重复请求。
final class SkinCache {
    static let shared = SkinCache()
    private var cache: [String: UIImage] = [:]
    private var busy: Set<String> = []

    func cached(_ key: String) -> UIImage? { cache[key] }
    func loading(_ key: String) -> Bool { busy.contains(key) }

    /// uuid 优先（正版皮肤），没有 uuid 就用名字。
    func url(uuid: String, name: String, size: Int) -> URL? {
        if !uuid.isEmpty {
            return URL(string: "https://crafatar.com/avatars/\(uuid)?size=\(size)&overlay")
        }
        if !name.isEmpty {
            return URL(string: "https://minotar.net/helm/\(name)/\(size).png")
        }
        return nil
    }

    func load(_ key: String, uuid: String, name: String, size: Int, done: @escaping (UIImage?) -> Void) {
        if let img = cache[key] { done(img); return }
        if busy.contains(key) { return }
        guard let u = url(uuid: uuid, name: name, size: size) else { done(nil); return }
        busy.insert(key)
        var req = URLRequest(url: u)
        req.timeoutInterval = 8
        URLSession.shared.dataTask(with: req) { data, _, _ in
            let img = data.flatMap { UIImage(data: $0) }
            DispatchQueue.main.async {
                self.busy.remove(key)
                if let img = img {
                    self.cache[key] = img
                    done(img)
                } else {
                    done(nil)
                }
            }
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

    private var key: String { (uuid.isEmpty ? name : uuid) + "@\(Int(size))" }

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
    }

    private func load() {
        if let hit = SkinCache.shared.cached(key) { image = hit; return }
        SkinCache.shared.load(key, uuid: uuid, name: name, size: Int(size * 2)) { img in
            if let img = img { self.image = img }
        }
    }
}

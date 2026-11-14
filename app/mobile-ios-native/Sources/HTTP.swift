import Foundation

/// iOS 13 兼容的最小 GET（URLSession 的 async 版本要 iOS 15）
enum HTTP {
    static func get(_ url: URL) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { cont in
            URLSession.shared.dataTask(with: url) { data, resp, err in
                if let err = err { cont.resume(throwing: err); return }
                guard let data = data, let resp = resp else {
                    cont.resume(throwing: URLError(.badServerResponse)); return
                }
                cont.resume(returning: (data, resp))
            }.resume()
        }
    }
}

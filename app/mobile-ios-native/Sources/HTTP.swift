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

/// 把系统英文报错翻成中文（用户看到的是中文提示，不是 "An SSL error has occurred"）
enum NetMsg {
    static func zh(_ error: Error) -> String {
        if let e = error as? LocalizedError, let d = e.errorDescription, !d.isEmpty { return d }
        let n = error as NSError
        guard n.domain == NSURLErrorDomain else { return n.localizedDescription }
        switch n.code {
        case NSURLErrorCancelled:
            return "请求被取消"
        case NSURLErrorTimedOut:
            return "请求超时：服务器没有响应（可能正在重启或网络不通），过一会儿再试"
        case NSURLErrorCannotFindHost:
            return "找不到这个服务器地址：检查地址里的域名 / IP 有没有写错"
        case NSURLErrorCannotConnectToHost:
            return "连不上服务器：端口没开、被防火墙拦了，或者插件没启动"
        case NSURLErrorNetworkConnectionLost:
            return "网络断了：请检查 WiFi / 移动数据"
        case NSURLErrorNotConnectedToInternet, NSURLErrorDataNotAllowed:
            return "手机当前没有网络"
        case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted, NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid, NSURLErrorClientCertificateRejected,
             NSURLErrorClientCertificateRequired:
            return "安全连接失败（SSL / 证书问题）：确认地址是 https、端口对，服务器证书没过期；如果服务器正在重启，等它起来再试"
        case NSURLErrorAppTransportSecurityRequiresSecureConnection:
            return "系统要求必须走 HTTPS 才能连接"
        default:
            return "网络错误（\(n.code)）：\(n.localizedDescription)"
        }
    }
}

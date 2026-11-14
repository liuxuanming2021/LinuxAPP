import Foundation
import Security
import CryptoKit

/// 与 LinuxAPP 插件通信的客户端。
/// 握手流程（和网页版一致）：
///   1. App 本地生成 RSA-2048 密钥对，把公钥（SPKI 格式）发给 /api/session
///   2. 服务端生成 32 字节 AES 密钥，用这把公钥包起来返回
///   3. App 用私钥解出 AES 密钥，之后所有请求体 AES-256-GCM 加密
///   4. 断线/服务端重启后会话失效 → 自动重新握手
final class APIClient {
    static let shared = APIClient()

    var base: URL?
    private(set) var token: String?

    private var privKey: SecKey?
    private var aesKey: SymmetricKey?
    private var sessionId: String?

    private let session: URLSession

    init() {
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 12
        cfg.timeoutIntervalForResource = 20
        session = URLSession(configuration: cfg)
    }

    func setToken(_ t: String?) { token = t }

    // MARK: - 握手

    func handshake() async throws {
        guard base != nil else { throw APIError.noServer }

        if privKey == nil {
            privKey = try makeRSAKey()
        }
        guard let priv = privKey,
              let spki = publicSPKI(priv) else { throw APIError.crypto("无法生成密钥") }

        let sid = randomHex(8)
        let payload: [String: Any] = ["id": sid, "pub": spki.base64EncodedString()]
        let data = try await rawRequest(path: "/api/session", method: "POST",
                                        json: payload, encrypted: false)

        guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let keyB64 = obj["key"] as? String,
              let wrapped = Data(base64Encoded: keyB64) else {
            throw APIError.crypto("服务端没有返回会话密钥")
        }

        var err: Unmanaged<CFError>?
        guard let plainCF = SecKeyCreateDecryptedData(priv,
                                                      .rsaEncryptionOAEPSHA256,
                                                      wrapped as CFData,
                                                      &err) else {
            throw APIError.crypto("解不开会话密钥")
        }
        aesKey = SymmetricKey(data: plainCF as Data)
        sessionId = sid
    }

    private func makeRSAKey() throws -> SecKey {
        let attrs: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeRSA,
            kSecAttrKeySizeInBits as String: 2048,
            kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: false]
        ]
        var err: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attrs as CFDictionary, &err) else {
            throw APIError.crypto("生成密钥失败")
        }
        return key
    }

    /// PKCS#1 → SPKI（2048 位 RSA 的固定 DER 头）
    private func publicSPKI(_ priv: SecKey) -> Data? {
        guard let pub = SecKeyCopyPublicKey(priv),
              let pkcs1 = SecKeyCopyExternalRepresentation(pub, nil) as Data? else { return nil }
        let header: [UInt8] = [
            0x30, 0x82, 0x01, 0x22, 0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86, 0x48, 0x86,
            0xF7, 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00, 0x03, 0x82, 0x01, 0x0F, 0x00
        ]
        var out = Data(header)
        out.append(pkcs1)
        return out
    }

    // MARK: - 请求

    /// 当前是否已经建立加密通道（握手成功才为 true）
    var encrypted: Bool { aesKey != nil && sessionId != nil }

    func get(_ path: String) async throws -> [String: Any] {
        try await call(path: path, method: "GET", body: nil)
    }

    func post(_ path: String, _ body: [String: Any]) async throws -> [String: Any] {
        try await call(path: path, method: "POST", body: body)
    }

    private func call(path: String, method: String, body: [String: Any]?) async throws -> [String: Any] {
        var lastError: Error?
        for attempt in 0..<2 {
            do {
                let enc = (aesKey != nil && sessionId != nil)
                let data = try await rawRequest(path: path, method: method, json: body, encrypted: enc)
                guard let obj = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                    throw APIError.badJSON
                }
                if (obj["code"] as? String) == "session_expired", attempt == 0 {
                    aesKey = nil; sessionId = nil
                    try await handshake()
                    continue
                }
                return obj
            } catch {
                lastError = error
                if case APIError.status(409, _) = error, attempt == 0 {
                    aesKey = nil; sessionId = nil
                    try? await handshake()
                    continue
                }
                break
            }
        }
        throw lastError ?? APIError.unknown
    }

    private func rawRequest(path: String, method: String, json: [String: Any]?, encrypted: Bool) async throws -> Data {
        guard let base = base, let url = URL(string: path, relativeTo: base) else { throw APIError.noServer }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("LinuxAPP-iOS", forHTTPHeaderField: "X-Client")
        if let t = token { req.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }

        if let json = json {
            let plain = try JSONSerialization.data(withJSONObject: json)
            if encrypted, let key = aesKey, let sid = sessionId {
                let sealed = try AES.GCM.seal(plain, using: key)
                req.httpBody = (sealed.combined ?? Data()).base64EncodedData()
                req.setValue("1", forHTTPHeaderField: "X-Enc")
                req.setValue(sid, forHTTPHeaderField: "X-Session")
            } else {
                req.httpBody = plain
            }
        }

        // 没走加密就明确告诉服务端「我这个端没法加密」，否则服务端开了 strict-encryption 会回 426
        if !encrypted { req.setValue("1", forHTTPHeaderField: "X-No-Enc") }

        let (data, resp) = try await send(req)
        guard let http = resp as? HTTPURLResponse else { throw APIError.unknown }

        if http.value(forHTTPHeaderField: "X-Enc") == "1", let key = aesKey {
            guard let raw = Data(base64Encoded: data),
                  let box = try? AES.GCM.SealedBox(combined: raw),
                  let clear = try? AES.GCM.open(box, using: key) else {
                throw APIError.crypto("响应解密失败")
            }
            return clear
        }

        if http.statusCode >= 400 {
            var msg = "HTTP \(http.statusCode)"
            if let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let m = obj["message"] as? String, !m.isEmpty { msg = m }
            throw APIError.status(http.statusCode, msg)
        }
        return data
    }

    /// iOS 13 没有 URLSession 的 async 版本，这里用 continuation 包一层
    private func send(_ req: URLRequest) async throws -> (Data, URLResponse) {
        try await withCheckedThrowingContinuation { cont in
            session.dataTask(with: req) { data, resp, err in
                if let err = err { cont.resume(throwing: err); return }
                guard let data = data, let resp = resp else {
                    cont.resume(throwing: APIError.unknown); return
                }
                cont.resume(returning: (data, resp))
            }.resume()
        }
    }

    private func randomHex(_ n: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: n)
        _ = SecRandomCopyBytes(kSecRandomDefault, n, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}

enum APIError: LocalizedError {
    case noServer
    case badJSON
    case crypto(String)
    case status(Int, String)
    case unknown

    var errorDescription: String? {
        switch self {
        case .noServer: return "还没填服务器地址"
        case .badJSON: return "服务器返回的不是 JSON"
        case .crypto(let s): return s
        case .status(let code, let msg):
            switch code {
            case 401: return msg.isEmpty ? "登录已过期，请重新登录" : msg
            case 403: return msg.isEmpty ? "没有权限" : msg
            case 426: return "服务器要求加密通道，正在重试…"
            default: return msg.isEmpty ? "请求失败（\(code)）" : msg
            }
        case .unknown: return "网络不通"
        }
    }
}

import Foundation
import CryptoKit

/// Native SpaceXAI OAuth (auth.x.ai) for Grok Web. Same public grok-cli client
/// the desktop CLI uses: authorization-code + PKCE, loopback redirect, then a
/// Bearer token against grok.com/rest. The browser step runs in
/// `ASWebAuthenticationSession` so passkeys / password managers work.
enum IOSGrokOAuthConstants {
    /// Public grok-cli installed-app client. Token endpoint allows `none`.
    static let clientId = "b1a00492-073a-47ea-816f-4c329264a828"
    static let authorizationEndpoint = "https://auth.x.ai/oauth2/authorize"
    static let tokenEndpoint = "https://auth.x.ai/oauth2/token"
    static let loopbackPort: UInt16 = 8787
    static let callbackPath = "/callback"
    static let redirectUri = "http://127.0.0.1:\(loopbackPort)\(callbackPath)"
    static let scope = [
        "openid",
        "profile",
        "email",
        "offline_access",
        "grok-cli:access",
        "api:access",
    ].joined(separator: " ")
    static let tokenAuthHeader = "xai-grok-cli"
    static let refreshSkewMillis: Int64 = 2 * 60 * 1000
    static let fallbackTokenLifetimeMillis: Int64 = 60 * 60 * 1000
}

struct IOSGrokOAuthTokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String?
    let expiresAtMillis: Int64
    var idToken: String?
    var email: String?
    var providerBackup: IOSGrokWebProviderBackup? = nil
}

enum IOSGrokOAuthAuthStore {
    static func credentialKey(providerId: String) -> String { "grokweb.\(providerId).oauth" }

    static func load(providerId: String) -> IOSGrokOAuthTokens? {
        guard let raw = IOSCredentialSideTable.load(key: credentialKey(providerId: providerId)),
              let data = raw.data(using: .utf8),
              let tokens = try? JSONDecoder().decode(IOSGrokOAuthTokens.self, from: data) else {
            return nil
        }
        return tokens
    }

    @discardableResult
    static func save(providerId: String, tokens: IOSGrokOAuthTokens) -> Bool {
        guard let data = try? JSONEncoder().encode(tokens),
              let raw = String(data: data, encoding: .utf8) else { return false }
        return IOSCredentialSideTable.store(key: credentialKey(providerId: providerId), value: raw)
    }

    static func clear(providerId: String) {
        IOSCredentialSideTable.delete(key: credentialKey(providerId: providerId))
    }
}

enum IOSGrokOAuthClients {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var clients: [String: IOSGrokOAuthClient] = [:]
    nonisolated(unsafe) private static var generations: [String: Int] = [:]

    static func shared(providerId: String) -> IOSGrokOAuthClient {
        lock.lock()
        defer { lock.unlock() }
        if let existing = clients[providerId] { return existing }
        let created = IOSGrokOAuthClient(providerId: providerId)
        clients[providerId] = created
        return created
    }

    static func logout(providerId: String) {
        lock.lock()
        generations[providerId, default: 0] += 1
        lock.unlock()
        IOSGrokOAuthAuthStore.clear(providerId: providerId)
    }

    static func generation(providerId: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return generations[providerId, default: 0]
    }
}

struct IOSGrokOAuthError: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

enum IOSGrokOAuthPKCE {
    static func generateCodeVerifier() -> String {
        let symbols = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~"
        let bytes = (0..<64).map { _ in UInt8.random(in: 0...255) }
        let source = bytes.map { byte in
            symbols[symbols.index(symbols.startIndex, offsetBy: Int(byte) % symbols.count)]
        }
        return String(source)
    }

    static func s256Challenge(verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return Data(digest).base64URLEncodedString()
    }

    static func randomState() -> String {
        let bytes = (0..<16).map { _ in UInt8.random(in: 0...255) }
        return Data(bytes).base64URLEncodedString()
    }

    static func buildAuthorizationURL(state: String, nonce: String, codeChallenge: String) -> URL {
        var components = URLComponents(string: IOSGrokOAuthConstants.authorizationEndpoint)!
        components.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: IOSGrokOAuthConstants.clientId),
            URLQueryItem(name: "redirect_uri", value: IOSGrokOAuthConstants.redirectUri),
            URLQueryItem(name: "scope", value: IOSGrokOAuthConstants.scope),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "nonce", value: nonce),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "referrer", value: "grok-cli"),
        ]
        return components.url!
    }
}

actor IOSGrokOAuthClient {
    private let providerId: String
    private let session: URLSession

    init(providerId: String, session: URLSession = .shared) {
        self.providerId = providerId
        self.session = session
    }

    nonisolated func cached() -> IOSGrokOAuthTokens? {
        IOSGrokOAuthAuthStore.load(providerId: providerId)
    }

    nonisolated func logout() {
        IOSGrokOAuthClients.logout(providerId: providerId)
    }

    /// Returns a usable access token, refreshing if needed. `nil` when this
    /// provider has never completed SpaceXAI OAuth (cookie sessions still apply).
    /// Refresh/network failures throw so the chat path does not masquerade as
    /// "not signed in" while tokens are still on disk.
    func resolveAccessToken() async throws -> String? {
        guard cached() != nil else { return nil }
        return try await getValidAccessToken()
    }

    @discardableResult
    func attachProviderBackup(_ backup: IOSGrokWebProviderBackup?) -> IOSGrokOAuthTokens? {
        guard let backup, var current = cached(), current.providerBackup == nil else {
            return cached()
        }
        current.providerBackup = backup
        return persist(current, generation: IOSGrokOAuthClients.generation(providerId: providerId))
    }

    func exchangeCode(code: String, codeVerifier: String) async throws -> IOSGrokOAuthTokens {
        let generation = IOSGrokOAuthClients.generation(providerId: providerId)
        let form = Self.formEncode([
            "grant_type": "authorization_code",
            "code": code,
            "code_verifier": codeVerifier,
            "redirect_uri": IOSGrokOAuthConstants.redirectUri,
            "client_id": IOSGrokOAuthConstants.clientId,
        ])
        let (data, status) = try await postForm(form: form)
        guard (200..<300).contains(status) else {
            throw oauthError("Grok OAuth 令牌交换失败", status, data)
        }
        return persist(try parseTokens(data, fallback: cached()), generation: generation)
    }

    func getValidAccessToken(forceRefresh: Bool = false) async throws -> String {
        guard let current = cached() else {
            throw IOSGrokOAuthError(message: "尚未登录 Grok，请先在 xAI 服务商设置里登录。")
        }
        let now = Self.nowMillis()
        if !forceRefresh, current.expiresAtMillis - IOSGrokOAuthConstants.refreshSkewMillis > now {
            return current.accessToken
        }
        return try await refresh().accessToken
    }

    @discardableResult
    func refresh() async throws -> IOSGrokOAuthTokens {
        guard let current = cached() else {
            throw IOSGrokOAuthError(message: "尚未登录 Grok，请先在 xAI 服务商设置里登录。")
        }
        guard let refreshToken = current.refreshToken, !refreshToken.isEmpty else {
            throw IOSGrokOAuthError(message: "Grok OAuth 没有 refresh_token，请重新登录。")
        }
        let form = Self.formEncode([
            "grant_type": "refresh_token",
            "refresh_token": refreshToken,
            "client_id": IOSGrokOAuthConstants.clientId,
        ])
        let generation = IOSGrokOAuthClients.generation(providerId: providerId)
        let (data, status) = try await postForm(form: form)
        guard (200..<300).contains(status) else {
            if Self.oauthErrorCode(data) == "invalid_grant" {
                IOSGrokOAuthClients.logout(providerId: providerId)
            }
            throw oauthError("Grok OAuth 刷新失败", status, data)
        }
        return persist(try parseTokens(data, fallback: current), generation: generation)
    }

    private func persist(_ tokens: IOSGrokOAuthTokens, generation: Int) -> IOSGrokOAuthTokens {
        guard generation == IOSGrokOAuthClients.generation(providerId: providerId) else { return tokens }
        _ = IOSGrokOAuthAuthStore.save(providerId: providerId, tokens: tokens)
        return tokens
    }

    private func parseTokens(_ data: Data, fallback: IOSGrokOAuthTokens?) throws -> IOSGrokOAuthTokens {
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard let accessToken = (obj["access_token"] as? String)?.nonBlank else {
            throw IOSGrokOAuthError(message: "Grok OAuth 响应缺少 access_token。")
        }
        let idToken = (obj["id_token"] as? String)?.nonBlank
        let expiresInSeconds = (obj["expires_in"] as? NSNumber)?.int64Value
        let expFromToken = Self.jwtPayload(accessToken)?["exp"] as? NSNumber
        let now = Self.nowMillis()
        let expiresAt = (expFromToken.map { $0.int64Value * 1000 })
            ?? (expiresInSeconds.map { now + $0 * 1000 })
            ?? (now + IOSGrokOAuthConstants.fallbackTokenLifetimeMillis)
        return IOSGrokOAuthTokens(
            accessToken: accessToken,
            refreshToken: (obj["refresh_token"] as? String)?.nonBlank ?? fallback?.refreshToken,
            expiresAtMillis: max(expiresAt, now + IOSGrokOAuthConstants.refreshSkewMillis),
            idToken: idToken ?? fallback?.idToken,
            email: idToken.flatMap { Self.jwtPayload($0)?["email"] as? String } ?? fallback?.email,
            providerBackup: fallback?.providerBackup
        )
    }

    private func postForm(form: String) async throws -> (Data, Int) {
        var request = URLRequest(url: URL(string: IOSGrokOAuthConstants.tokenEndpoint)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = form.data(using: .utf8)
        let (data, urlResponse) = try await session.data(for: request)
        return (data, (urlResponse as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private func oauthError(_ prefix: String, _ status: Int, _ body: Data) -> IOSGrokOAuthError {
        let detail = Self.safeOAuthError(body)
        return IOSGrokOAuthError(message: detail.isEmpty ? "\(prefix): HTTP \(status)" : "\(prefix): \(detail)")
    }

    static func jwtPayload(_ jwt: String) -> [String: Any]? {
        let segments = jwt.split(separator: ".", omittingEmptySubsequences: false)
        guard segments.count >= 2 else { return nil }
        var base64 = String(segments[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padding = (4 - base64.count % 4) % 4
        if padding > 0 { base64 += String(repeating: "=", count: padding) }
        guard let data = Data(base64Encoded: base64),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return obj
    }

    private static func oauthErrorCode(_ body: Data) -> String? {
        guard let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] else { return nil }
        return (obj["error"] as? String)?.nonBlank
    }

    private static func safeOAuthError(_ body: Data) -> String {
        guard !body.isEmpty else { return "" }
        if let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] {
            if let v = obj["error_description"] as? String { return v }
            if let v = obj["error"] as? String { return v }
            if let v = obj["message"] as? String { return v }
        }
        let text = String(data: body, encoding: .utf8) ?? ""
        return String(text.prefix(240))
    }

    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private static func formEncode(_ params: [String: String]) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return params.map { key, value in
            let k = key.addingPercentEncoding(withAllowedCharacters: allowed) ?? key
            let v = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(k)=\(v)"
        }.joined(separator: "&")
    }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private extension String {
    var nonBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

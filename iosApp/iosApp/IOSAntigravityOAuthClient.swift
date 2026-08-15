import Foundation
import CryptoKit
import Darwin

/// Native Swift port of the Android `GoogleGeminiOAuth` authorization-code + PKCE
/// flow, but for Google's **Antigravity** route (antigravity.google — Google's
/// agentic IDE, same cloudcode-pa Gemini backend the Gemini CLI uses).
///
/// Differences from the Android Code Assist route:
///  - OAuth client is the public gemini-cli Antigravity CLI client id (same
///    "public client_id" ToS bucket documented in `GoogleGeminiOAuth.kt`).
///  - The browser step runs inside `ASWebAuthenticationSession` with a loopback
///    callback (`http://localhost:8085/oauth/callback`), so no hand-rolled
///    socket server is needed on iOS.
///  - Requests to cloudcode-pa carry Antigravity origin headers.
///
/// The client only performs OAuth + token persistence + the cloudcode-pa
/// onboard (`loadCodeAssist` / `onboardUser`) that resolves the account's
/// ghost `projectId`. Chat calls live in `IOSGeminiProvider`.
enum IOSAntigravityOAuthConstants {
    /// Public Antigravity CLI installed-app client. The old `857794902390-…`
    /// id is gone (`invalid_client`); the gemini-cli `681255809395-…` id still
    /// exists but is the retired product. Secret is the published installed-app
    /// value (not a secret); PKCE still binds the code.
    static let clientId = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
    static let clientSecret = "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"
    static let authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
    static let tokenEndpoint = "https://oauth2.googleapis.com/token"
    /// Loopback callback registered for the gemini-cli Antigravity client.
    /// ASWebAuthenticationSession natively supports HTTP loopback redirects.
    static let redirectUri = "http://localhost:8085/oauth/callback"
    static let scope = [
        "openid",
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
    ].joined(separator: " ")

    /// Official `agy` 1.1.13 talks to daily-cloudcode, not prod.
    static let cloudcodePaBaseUrl = "https://daily-cloudcode-pa.googleapis.com"
    static let antigravityOrigin = "https://antigravity.google"

    /// Fingerprint from official `agy` 1.1.13 (`%s/%s (%s)` + ClientMetadata).
    /// Hub UA (`antigravity/hub/…`) and Gemini-CLI metadata (`IDE_UNSPECIFIED` /
    /// `pluginType=GEMINI`) are a different product and come back 429.
    static let userAgent = "antigravity/1.1.13 (darwin; arm64)"
    static let ideMetadata: [String: String] = [
        "ideType": "ANTIGRAVITY",
        "platform": "DARWIN_ARM64",
        "pluginType": "CLOUD_CODE",
    ]
    static let clientMetadata = "ideType=ANTIGRAVITY,platform=DARWIN_ARM64,pluginType=CLOUD_CODE"

    static let refreshSkewMillis: Int64 = 2 * 60 * 1000
    static let authTimeoutMillis: Int64 = 5 * 60 * 1000
    static let fallbackTokenLifetimeMillis: Int64 = 60 * 60 * 1000
    static let lroPollIntervalMillis: Int64 = 5 * 1000
    static let maxLroPollIterations = 12
}

struct IOSAntigravityAuthTokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String?
    let expiresAtMillis: Int64
    var idToken: String?
    var email: String?
    var projectId: String?
    var onboardedTier: String?
}

enum IOSAntigravityAuthStore {
    static func credentialKey(providerId: String) -> String { "antigravity.\(providerId).tokens" }

    static func load(providerId: String) -> IOSAntigravityAuthTokens? {
        guard let raw = IOSCredentialSideTable.load(key: credentialKey(providerId: providerId)),
              let data = raw.data(using: .utf8),
              let tokens = try? JSONDecoder().decode(IOSAntigravityAuthTokens.self, from: data) else {
            return nil
        }
        return tokens
    }

    @discardableResult
    static func save(providerId: String, tokens: IOSAntigravityAuthTokens) -> Bool {
        guard let data = try? JSONEncoder().encode(tokens),
              let raw = String(data: data, encoding: .utf8) else { return false }
        return IOSCredentialSideTable.store(key: credentialKey(providerId: providerId), value: raw)
    }

    static func clear(providerId: String) {
        IOSCredentialSideTable.delete(key: credentialKey(providerId: providerId))
    }
}

/// One OAuth actor per provider id so refresh/onboard/logout share a generation.
enum IOSAntigravityOAuthClients {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var clients: [String: IOSAntigravityOAuthClient] = [:]
    nonisolated(unsafe) private static var generations: [String: Int] = [:]

    static func shared(providerId: String) -> IOSAntigravityOAuthClient {
        lock.lock()
        defer { lock.unlock() }
        if let existing = clients[providerId] { return existing }
        let created = IOSAntigravityOAuthClient(providerId: providerId)
        clients[providerId] = created
        return created
    }

    static func logout(providerId: String) {
        lock.lock()
        generations[providerId, default: 0] += 1
        lock.unlock()
        IOSAntigravityAuthStore.clear(providerId: providerId)
    }

    static func generation(providerId: String) -> Int {
        lock.lock()
        defer { lock.unlock() }
        return generations[providerId, default: 0]
    }
}

struct IOSAntigravityOAuthError: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

/// PKCE helpers shared by the login flow and the tests.
enum IOSAntigravityPKCE {
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

    static func buildAuthorizationURL(state: String, codeChallenge: String) -> URL {
        var components = URLComponents(string: IOSAntigravityOAuthConstants.authorizationEndpoint)!
        components.queryItems = [
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "client_id", value: IOSAntigravityOAuthConstants.clientId),
            URLQueryItem(name: "redirect_uri", value: IOSAntigravityOAuthConstants.redirectUri),
            URLQueryItem(name: "scope", value: IOSAntigravityOAuthConstants.scope),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            // access_type=offline + prompt=consent forces Google to issue a
            // refresh_token on every consent (otherwise re-consent returns an
            // access token only and refresh() has nothing to work with).
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "prompt", value: "consent"),
            URLQueryItem(name: "include_granted_scopes", value: "true"),
        ]
        return components.url!
    }
}

/// Parses the first line of an HTTP request into the loopback callback URL.
enum IOSLoopbackOAuthRequest {
    static func callbackURL(from request: String, port: Int = 8085) -> URL? {
        let firstLine = request.split(separator: "\r\n", maxSplits: 1, omittingEmptySubsequences: false)
            .first.map(String.init) ?? ""
        let parts = firstLine.split(separator: " ")
        guard parts.count >= 2, parts[0] == "GET" else { return nil }
        let path = String(parts[1])
        guard path.hasPrefix("/oauth/callback") else { return nil }
        return URL(string: "http://127.0.0.1:\(port)\(path)")
    }
}

/// Listens on 127.0.0.1:8085 so Safari can complete the Antigravity loopback
/// redirect. ASWebAuthenticationSession does not intercept `http://localhost`
/// on current iOS, so the page otherwise dies with "无法连接服务器".
@MainActor
final class IOSLoopbackOAuthCallbackServer {
    private var listenFD: Int32 = -1
    private var source: DispatchSourceRead?
    private var delivered = false
    var onCallback: ((URL) -> Void)?

    func start(port: UInt16 = 8085) throws {
        stop()
        delivered = false
        let fd = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        guard fd >= 0 else {
            throw IOSAntigravityOAuthError(message: "无法创建本机回调套接字（errno \(errno)）。")
        }
        var reuse: Int32 = 1
        _ = setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuse, socklen_t(MemoryLayout<Int32>.size))
        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr = in_addr(s_addr: inet_addr("127.0.0.1"))
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else {
            let code = errno
            close(fd)
            throw IOSAntigravityOAuthError(message: "无法绑定 127.0.0.1:\(port)（errno \(code)）。")
        }
        guard listen(fd, 4) == 0 else {
            let code = errno
            close(fd)
            throw IOSAntigravityOAuthError(message: "无法监听 127.0.0.1:\(port)（errno \(code)）。")
        }
        let source = DispatchSource.makeReadSource(fileDescriptor: fd, queue: .main)
        source.setEventHandler { [weak self] in
            self?.acceptOne()
        }
        source.setCancelHandler {
            close(fd)
        }
        source.resume()
        self.listenFD = fd
        self.source = source
    }

    func stop() {
        source?.cancel()
        source = nil
        listenFD = -1
    }

    private func acceptOne() {
        guard listenFD >= 0 else { return }
        let client = accept(listenFD, nil, nil)
        guard client >= 0 else { return }
        var buffer = [UInt8](repeating: 0, count: 16_384)
        let readCount = recv(client, &buffer, buffer.count, 0)
        let request = readCount > 0 ? String(bytes: buffer.prefix(Int(readCount)), encoding: .utf8) : nil
        if let request, let url = IOSLoopbackOAuthRequest.callbackURL(from: request) {
            let html = "<!doctype html><meta charset=utf-8><title>已登录</title><body>可以关闭此页并回到 Amber。</body>"
            Self.reply(client, status: "200 OK", body: Data(html.utf8), contentType: "text/html; charset=utf-8")
            if !delivered {
                delivered = true
                onCallback?(url)
            }
        } else {
            Self.reply(client, status: "404 Not Found", body: Data())
        }
        close(client)
    }

    private static func reply(_ fd: Int32, status: String, body: Data, contentType: String = "text/plain") {
        var header = "HTTP/1.1 \(status)\r\nContent-Length: \(body.count)\r\nConnection: close\r\n"
        if !body.isEmpty {
            header += "Content-Type: \(contentType)\r\n"
        }
        header += "\r\n"
        var payload = Data(header.utf8)
        payload.append(body)
        payload.withUnsafeBytes { raw in
            guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
            _ = send(fd, base, payload.count, 0)
        }
    }
}

/// Serializes refresh/onboard and owns the OAuth network calls. An `actor` so
/// concurrent chat requests can't trigger overlapping refreshes (mirrors the
/// Android `refreshMutex`). One instance per provider id.
actor IOSAntigravityOAuthClient {
    private let providerId: String
    private let session: URLSession

    init(providerId: String, session: URLSession = .shared) {
        self.providerId = providerId
        self.session = session
    }

    nonisolated func cached() -> IOSAntigravityAuthTokens? {
        IOSAntigravityAuthStore.load(providerId: providerId)
    }

    nonisolated func logout() {
        IOSAntigravityOAuthClients.logout(providerId: providerId)
    }

    private func persist(_ tokens: IOSAntigravityAuthTokens, generation: Int) -> IOSAntigravityAuthTokens {
        guard generation == IOSAntigravityOAuthClients.generation(providerId: providerId) else { return tokens }
        _ = IOSAntigravityAuthStore.save(providerId: providerId, tokens: tokens)
        return tokens
    }

    // MARK: - Authorization code exchange

    /// Exchanges the browser authorization code for tokens and persists them.
    func exchangeCode(code: String, codeVerifier: String) async throws -> IOSAntigravityAuthTokens {
        let generation = IOSAntigravityOAuthClients.generation(providerId: providerId)
        let form = Self.formEncode([
            "grant_type": "authorization_code",
            "code": code,
            "code_verifier": codeVerifier,
            "redirect_uri": IOSAntigravityOAuthConstants.redirectUri,
            "client_id": IOSAntigravityOAuthConstants.clientId,
            "client_secret": IOSAntigravityOAuthConstants.clientSecret,
        ])
        let (data, status) = try await postForm(url: IOSAntigravityOAuthConstants.tokenEndpoint, form: form)
        guard status.isHTTPSuccess else {
            throw oauthError("Google OAuth 令牌交换失败", status, data)
        }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard let accessToken = (obj["access_token"] as? String).nonBlank else {
            throw IOSAntigravityOAuthError(message: "Google OAuth 响应缺少 access_token。")
        }
        let tokens = Self.buildTokens(
            accessToken: accessToken,
            refreshToken: (obj["refresh_token"] as? String).nonBlank,
            idToken: (obj["id_token"] as? String).nonBlank,
            expiresInSeconds: (obj["expires_in"] as? NSNumber)?.int64Value,
            fallback: cached()
        )
        return persist(tokens, generation: generation)
    }

    /// Runs `loadCodeAssist` + (`onboardUser` + LRO poll) against cloudcode-pa
    /// so the account's ghost project id is persisted before the first chat.
    /// Idempotent — skips when tokens already carry a projectId + tier.
    /// Onboarding failure does NOT invalidate the login: the token stays, and a
    /// later call (or a chat request) retries.
    func ensureOnboarded() async throws -> IOSAntigravityAuthTokens {
        guard var current = cached() else {
            throw IOSAntigravityOAuthError(message: "尚未登录 Antigravity，请先在服务商设置里登录。")
        }
        if !(current.projectId ?? "").isEmpty, !(current.onboardedTier ?? "").isEmpty {
            return current
        }
        let accessToken = try await getValidAccessToken()

        // 1) loadCodeAssist — mirror gemini-cli setup.ts exactly: NO
        // `cloudaicompanionProject` field at all when we don't have one (an
        // explicit empty string would irreversibly classify the account as
        // user-defined-project and mask the Pro/Free subscription tier).
        let loadBody = ["metadata": IOSAntigravityOAuthConstants.ideMetadata]
        let loadObj = try await cloudCodeAssistJSON(accessToken, method: ":loadCodeAssist", body: loadBody)
        let currentProject = (loadObj["cloudaicompanionProject"] as? String).nonBlank
        let currentTier = ((loadObj["currentTier"] as? [String: Any])?["id"] as? String).nonBlank
        if let currentProject, let currentTier {
            current.projectId = currentProject
            current.onboardedTier = currentTier
            return persist(current, generation: IOSAntigravityOAuthClients.generation(providerId: providerId))
        }

        // 2) onboardUser — FREE tier passes no project so the server assigns one.
        let allowedTiers = (loadObj["allowedTiers"] as? [[String: Any]])?
            .compactMap { ($0["id"] as? String).nonBlank } ?? []
        let chosenTier = allowedTiers.first { $0 == "FREE" } ?? allowedTiers.first ?? "FREE"
        var onboardBody: [String: Any] = [
            "tierId": chosenTier,
            "metadata": IOSAntigravityOAuthConstants.ideMetadata,
        ]
        if chosenTier != "FREE", let currentProject {
            onboardBody["cloudaicompanionProject"] = currentProject
        }
        let onboardResp = try await cloudCodeAssistJSON(accessToken, method: ":onboardUser", body: onboardBody)

        guard let resolved = try await pollOnboardOperation(accessToken: accessToken, initialResponse: onboardResp) else {
            throw IOSAntigravityOAuthError(message: "onboardUser 没有返回 cloudaicompanionProject。")
        }
        current.projectId = resolved
        current.onboardedTier = chosenTier
        return persist(current, generation: IOSAntigravityOAuthClients.generation(providerId: providerId))
    }

    private func pollOnboardOperation(
        accessToken: String,
        initialResponse: [String: Any]
    ) async throws -> String? {
        // Mirrors gemini-cli: poll uses GET against /v1internal/{name} where
        // `name` already contains the operations/ prefix. `done` is a boolean.
        var current = initialResponse
        for _ in 0..<IOSAntigravityOAuthConstants.maxLroPollIterations {
            if current["done"] as? Bool == true {
                if let errorObject = current["error"] as? [String: Any] {
                    throw IOSAntigravityOAuthError(
                        message: "onboardUser 报错：\((errorObject["message"] as? String) ?? "未知错误")"
                    )
                }
                return ((current["response"] as? [String: Any])?["cloudaicompanionProject"] as? [String: Any])?["id"] as? String
            }
            guard let name = (current["name"] as? String).nonBlank else { return nil }
            try await Task.sleep(nanoseconds: UInt64(IOSAntigravityOAuthConstants.lroPollIntervalMillis) * 1_000_000)
            current = try await cloudCodeAssistGetJSON(accessToken, operationName: name)
        }
        throw IOSAntigravityOAuthError(message: "onboardUser 长轮询超时，请稍后重试。")
    }

    // MARK: - Token resolution / refresh

    func getValidAccessToken(forceRefresh: Bool = false) async throws -> String {
        guard let current = cached() else {
            throw IOSAntigravityOAuthError(message: "尚未登录 Antigravity，请先在服务商设置里用 Google 账号登录。")
        }
        let now = Self.nowMillis()
        if !forceRefresh, current.expiresAtMillis - IOSAntigravityOAuthConstants.refreshSkewMillis > now {
            return current.accessToken
        }
        return try await refresh().accessToken
    }

    @discardableResult
    func refresh() async throws -> IOSAntigravityAuthTokens {
        guard let current = cached() else {
            throw IOSAntigravityOAuthError(message: "尚未登录 Antigravity，请先在服务商设置里用 Google 账号登录。")
        }
        guard let refreshToken = current.refreshToken else {
            throw IOSAntigravityOAuthError(message: "Google OAuth 没有 refresh_token，请重新登录。")
        }
        let form = Self.formEncode([
            "grant_type": "refresh_token",
            "refresh_token": refreshToken,
            "client_id": IOSAntigravityOAuthConstants.clientId,
            "client_secret": IOSAntigravityOAuthConstants.clientSecret,
        ])
        let generation = IOSAntigravityOAuthClients.generation(providerId: providerId)
        let (data, status) = try await postForm(url: IOSAntigravityOAuthConstants.tokenEndpoint, form: form)
        guard status.isHTTPSuccess else {
            if Self.oauthErrorCode(data) == "invalid_grant" {
                IOSAntigravityOAuthClients.logout(providerId: providerId)
            }
            throw oauthError("Google OAuth 刷新失败", status, data)
        }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard let accessToken = (obj["access_token"] as? String).nonBlank else {
            throw IOSAntigravityOAuthError(message: "Google OAuth 刷新响应缺少 access_token。")
        }
        let merged = Self.buildTokens(
            accessToken: accessToken,
            refreshToken: (obj["refresh_token"] as? String).nonBlank ?? refreshToken,
            idToken: (obj["id_token"] as? String).nonBlank,
            expiresInSeconds: (obj["expires_in"] as? NSNumber)?.int64Value,
            fallback: current
        )
        return persist(merged, generation: generation)
    }

    // MARK: - cloudcode-pa helpers

    private func cloudCodeAssistJSON(
        _ accessToken: String,
        method: String,
        body: [String: Any]
    ) async throws -> [String: Any] {
        let url = IOSAntigravityOAuthConstants.cloudcodePaBaseUrl + "/v1internal" + method
        let (data, status) = try await postJSON(url: url, body: body, bearer: accessToken)
        guard status.isHTTPSuccess else {
            throw oauthError("cloudcode-pa \(method) 失败", status, data)
        }
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw IOSAntigravityOAuthError(message: "cloudcode-pa \(method) 响应不是 JSON。")
        }
        return obj
    }

    private func cloudCodeAssistGetJSON(
        _ accessToken: String,
        operationName: String
    ) async throws -> [String: Any] {
        let url = IOSAntigravityOAuthConstants.cloudcodePaBaseUrl + "/v1internal/" + operationName
        var request = URLRequest(url: URL(string: url)!)
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(IOSAntigravityOAuthConstants.userAgent, forHTTPHeaderField: "User-Agent")
        let (data, urlResponse) = try await dataWithRetry(for: request)
        let status = (urlResponse as? HTTPURLResponse)?.statusCode ?? 0
        guard status.isHTTPSuccess else {
            throw oauthError("cloudcode-pa 轮询失败", status, data)
        }
        guard let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw IOSAntigravityOAuthError(message: "cloudcode-pa 轮询响应不是 JSON。")
        }
        return obj
    }

    // MARK: - HTTP plumbing

    private func postJSON(
        url: String,
        body: [String: Any],
        bearer: String
    ) async throws -> (Data, Int) {
        guard let data = try? JSONSerialization.data(withJSONObject: body) else {
            throw IOSAntigravityOAuthError(message: "无法编码请求体。")
        }
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.setValue(IOSAntigravityOAuthConstants.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(IOSAntigravityOAuthConstants.clientMetadata, forHTTPHeaderField: "Client-Metadata")
        request.setValue(UUID().uuidString, forHTTPHeaderField: "x-activity-request-id")
        request.httpBody = data
        let (responseData, urlResponse) = try await dataWithRetry(for: request)
        return (responseData, (urlResponse as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private func postForm(url: String, form: String) async throws -> (Data, Int) {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = form.data(using: .utf8)
        let (data, urlResponse) = try await dataWithRetry(for: request)
        return (data, (urlResponse as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private func dataWithRetry(for request: URLRequest, attempts: Int = 3) async throws -> (Data, URLResponse) {
        var lastError: Error?
        for attempt in 0..<max(attempts, 1) {
            do {
                return try await session.data(for: request)
            } catch let error as URLError where Self.isTransientTransport(error) {
                lastError = error
                if attempt < attempts - 1 {
                    try await Task.sleep(nanoseconds: 800_000_000)
                }
            }
        }
        throw lastError ?? IOSAntigravityOAuthError(message: "网络请求失败")
    }

    private static func isTransientTransport(_ error: URLError) -> Bool {
        switch error.code {
        case .networkConnectionLost, .timedOut, .cannotConnectToHost,
             .cannotFindHost, .dnsLookupFailed, .notConnectedToInternet,
             .secureConnectionFailed:
            return true
        default:
            return false
        }
    }

    private func oauthError(_ prefix: String, _ status: Int, _ body: Data) -> IOSAntigravityOAuthError {
        let detail = Self.safeOAuthError(body)
        return IOSAntigravityOAuthError(message: detail.isEmpty ? "\(prefix): HTTP \(status)" : "\(prefix): \(detail)")
    }

    // MARK: - Token building

    private static func buildTokens(
        accessToken: String,
        refreshToken: String?,
        idToken: String?,
        expiresInSeconds: Int64?,
        fallback: IOSAntigravityAuthTokens?
    ) -> IOSAntigravityAuthTokens {
        let expFromToken = (jwtPayload(accessToken)?["exp"] as? NSNumber)?.int64Value
        let now = nowMillis()
        let expiresAt = (expFromToken.map { $0 * 1000 })
            ?? (expiresInSeconds.map { now + $0 * 1000 })
            ?? (now + IOSAntigravityOAuthConstants.fallbackTokenLifetimeMillis)
        return IOSAntigravityAuthTokens(
            accessToken: accessToken,
            refreshToken: refreshToken ?? fallback?.refreshToken,
            expiresAtMillis: max(expiresAt, now + IOSAntigravityOAuthConstants.refreshSkewMillis),
            idToken: idToken ?? fallback?.idToken,
            email: idToken.flatMap { jwtPayload($0)?["email"] as? String } ?? fallback?.email,
            projectId: fallback?.projectId,
            onboardedTier: fallback?.onboardedTier
        )
    }

    /// Decodes a JWT payload (middle segment, base64url) into a claims dictionary.
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
        return (obj["error"] as? String).nonBlank
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

private extension Int {
    var isHTTPSuccess: Bool { (200..<300).contains(self) }
}

private extension Data {
    func base64URLEncodedString() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

private extension Optional where Wrapped == String {
    /// Returns the string only if present and non-blank.
    var nonBlank: String? {
        guard let self, !self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return self
    }
}

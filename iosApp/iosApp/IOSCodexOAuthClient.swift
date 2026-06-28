import Foundation

/// Native Swift port of the Android `OpenAICodexOAuth` device-authorization flow
/// (`ai/.../provider/providers/openai/OpenAICodexOAuth.kt`).
///
/// iOS chat requests run through the KMP `OpenAIKmpProvider`, so this client does
/// NOT make chat calls — it only performs the OAuth dance (device login + token
/// refresh) and persists tokens in the Keychain side-table. At request time the
/// resolved access token is injected into the codex `ProviderSetting` as the
/// bearer (see codex bearer resolution in the chat path), and `accountId` is sent
/// as the `ChatGPT-Account-Id` header.
///
/// The flow (mirrors the official Codex CLI device auth):
///   1. `requestDeviceCode()` → POST `/api/accounts/deviceauth/usercode`, returns a
///      short user code + the verification URL `auth.openai.com/codex/device`.
///   2. User opens the URL in a browser, enters the code, signs in with ChatGPT.
///   3. `pollDeviceCode(_:)` polls `/api/accounts/deviceauth/token` until the auth
///      server hands back an `authorization_code` + `code_verifier` (PKCE pair is
///      generated server-side), then exchanges it for tokens.
///   4. `getValidAccessToken(forceRefresh:)` returns a cached token or refreshes.
enum IOSCodexOAuthConstants {
    static let clientId = "app_EMoamEEZ73f0CkXaXp7hrann"
    static let issuer = "https://auth.openai.com"
    static let deviceVerificationUrl = issuer + "/codex/device"
    static let chatGptBackendBaseUrl = "https://chatgpt.com/backend-api"
    static let codexBackendBaseUrl = "https://chatgpt.com/backend-api/codex"
    static let clientVersion = "0.128.0"
    static let originator = "amberagent_android"
    /// Synthetic model id for the codex image model (matches Android
    /// `CODEX_OAUTH_IMAGE_MODEL_ID`); generation runs via the Responses
    /// `image_generation` tool, not a real `/images/generations` endpoint.
    static let imageModelId = "codex-oauth-image"
    /// Routing model sent in Codex Responses image-generation requests
    /// (matches Android `CODEX_IMAGE_ROUTING_MODEL`).
    static let imageRoutingModel = "gpt-5.4"

    static let refreshSkewMillis: Int64 = 2 * 60 * 1000
    static let deviceLoginTimeoutMillis: Int64 = 15 * 60 * 1000
    static let fallbackTokenLifetimeMillis: Int64 = 45 * 60 * 1000
}

struct IOSCodexAuthTokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String
    let expiresAtMillis: Int64
    var accountId: String?
    var email: String?
    var planType: String?
    var idToken: String?
}

struct IOSCodexDeviceAuthorization: Equatable {
    let verificationUrl: String
    let userCode: String
    let intervalSeconds: Int
    let deviceAuthId: String
}

struct IOSCodexOAuthError: LocalizedError, Equatable {
    let message: String
    var errorDescription: String? { message }
}

/// Keychain-backed token store (one record per provider id), reusing the generic
/// `IOSCredentialSideTable` so codex tokens live alongside other iOS credentials.
enum IOSCodexAuthStore {
    static func credentialKey(providerId: String) -> String { "codex.\(providerId).tokens" }

    static func load(providerId: String) -> IOSCodexAuthTokens? {
        guard let raw = IOSCredentialSideTable.load(key: credentialKey(providerId: providerId)),
              let data = raw.data(using: .utf8),
              let tokens = try? JSONDecoder().decode(IOSCodexAuthTokens.self, from: data) else {
            return nil
        }
        return tokens
    }

    static func save(providerId: String, tokens: IOSCodexAuthTokens) {
        guard let data = try? JSONEncoder().encode(tokens),
              let raw = String(data: data, encoding: .utf8) else { return }
        IOSCredentialSideTable.store(key: credentialKey(providerId: providerId), value: raw)
    }

    static func clear(providerId: String) {
        IOSCredentialSideTable.delete(key: credentialKey(providerId: providerId))
    }
}

/// Serializes refresh and exposes the device-auth flow. An `actor` so concurrent
/// chat requests can't trigger overlapping refreshes (mirrors the Android
/// `refreshMutex`). One instance per provider id.
actor IOSCodexOAuthClient {
    private let providerId: String
    private let session: URLSession

    init(providerId: String, session: URLSession = .shared) {
        self.providerId = providerId
        self.session = session
    }

    nonisolated func cached() -> IOSCodexAuthTokens? {
        IOSCodexAuthStore.load(providerId: providerId)
    }

    nonisolated func logout() {
        IOSCodexAuthStore.clear(providerId: providerId)
    }

    // MARK: - Device authorization

    func requestDeviceCode() async throws -> IOSCodexDeviceAuthorization {
        let body = try JSONSerialization.data(withJSONObject: ["client_id": IOSCodexOAuthConstants.clientId])
        let (data, response) = try await postJSON(
            url: IOSCodexOAuthConstants.issuer + "/api/accounts/deviceauth/usercode",
            body: body
        )
        guard response.statusCode.isHTTPSuccess else {
            throw oauthError("设备登录请求失败", response.statusCode, data)
        }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard let deviceAuthId = obj["device_auth_id"] as? String, !deviceAuthId.isEmpty else {
            throw IOSCodexOAuthError(message: "设备登录响应缺少 device_auth_id")
        }
        let userCode = (obj["user_code"] as? String).nonBlank
            ?? (obj["usercode"] as? String).nonBlank
            ?? ""
        let interval = Self.parseInterval(obj["interval"])
        return IOSCodexDeviceAuthorization(
            verificationUrl: IOSCodexOAuthConstants.deviceVerificationUrl,
            userCode: userCode,
            intervalSeconds: interval,
            deviceAuthId: deviceAuthId
        )
    }

    /// Polls until the user completes the browser sign-in, then exchanges the
    /// returned authorization code for tokens and persists them. Throws on
    /// timeout, hard error, or cancellation.
    func pollDeviceCode(_ authorization: IOSCodexDeviceAuthorization) async throws -> IOSCodexAuthTokens {
        let startedAt = Self.nowMillis()
        let intervalNanos = UInt64(max(authorization.intervalSeconds, 1)) * 1_000_000_000
        while Self.nowMillis() - startedAt < IOSCodexOAuthConstants.deviceLoginTimeoutMillis {
            try Task.checkCancellation()
            do {
                let body = try JSONSerialization.data(withJSONObject: [
                    "device_auth_id": authorization.deviceAuthId,
                    "user_code": authorization.userCode,
                ])
                let (data, response) = try await postJSON(
                    url: IOSCodexOAuthConstants.issuer + "/api/accounts/deviceauth/token",
                    body: body
                )
                switch response.statusCode {
                case let code where code.isHTTPSuccess:
                    let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
                    guard let authorizationCode = obj["authorization_code"] as? String,
                          let codeVerifier = obj["code_verifier"] as? String else {
                        throw IOSCodexOAuthError(message: "设备登录响应缺少授权码")
                    }
                    let tokens = try await exchangeAuthorizationCode(
                        authorizationCode: authorizationCode,
                        codeVerifier: codeVerifier
                    )
                    IOSCodexAuthStore.save(providerId: providerId, tokens: tokens)
                    return tokens
                case 403, 404:
                    // Still pending — keep polling.
                    try await Task.sleep(nanoseconds: intervalNanos)
                case let code where code >= 500:
                    // Transient server error — back off and keep polling.
                    try await Task.sleep(nanoseconds: intervalNanos)
                default:
                    throw oauthError("设备登录轮询失败", response.statusCode, data)
                }
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as IOSCodexOAuthError {
                // Definitive OAuth/server failure — abort.
                throw error
            } catch {
                // Transient transport error (e.g. "The network connection was lost",
                // common when a pooled connection goes stale across the poll interval
                // or over a VPN). A retry opens a fresh connection — back off and keep
                // polling instead of failing the whole login.
                try await Task.sleep(nanoseconds: intervalNanos)
            }
        }
        throw IOSCodexOAuthError(message: "设备登录超时(15 分钟未完成),请重试。")
    }

    // MARK: - Token resolution / refresh

    func getValidAccessToken(forceRefresh: Bool = false) async throws -> String {
        guard let current = IOSCodexAuthStore.load(providerId: providerId) else {
            throw IOSCodexOAuthError(message: "尚未登录 Codex,请先在服务商设置里用 ChatGPT 账号登录。")
        }
        let now = Self.nowMillis()
        if !forceRefresh, current.expiresAtMillis - IOSCodexOAuthConstants.refreshSkewMillis > now {
            return current.accessToken
        }
        return try await refresh().accessToken
    }

    @discardableResult
    func refresh() async throws -> IOSCodexAuthTokens {
        guard let current = IOSCodexAuthStore.load(providerId: providerId) else {
            throw IOSCodexOAuthError(message: "尚未登录 Codex,请先在服务商设置里用 ChatGPT 账号登录。")
        }
        let body = try JSONSerialization.data(withJSONObject: [
            "client_id": IOSCodexOAuthConstants.clientId,
            "grant_type": "refresh_token",
            "refresh_token": current.refreshToken,
        ])
        let (data, response) = try await postJSON(
            url: IOSCodexOAuthConstants.issuer + "/oauth/token",
            body: body
        )
        guard response.statusCode.isHTTPSuccess else {
            throw oauthError("Codex 令牌刷新失败", response.statusCode, data)
        }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let accessToken = (obj["access_token"] as? String).nonBlank ?? current.accessToken
        let idToken = (obj["id_token"] as? String).nonBlank ?? current.idToken
        let merged = Self.buildTokens(
            accessToken: accessToken,
            refreshToken: (obj["refresh_token"] as? String).nonBlank ?? current.refreshToken,
            idToken: idToken,
            fallback: current
        )
        IOSCodexAuthStore.save(providerId: providerId, tokens: merged)
        return merged
    }

    private func exchangeAuthorizationCode(
        authorizationCode: String,
        codeVerifier: String
    ) async throws -> IOSCodexAuthTokens {
        let form = Self.formEncode([
            "grant_type": "authorization_code",
            "code": authorizationCode,
            "redirect_uri": IOSCodexOAuthConstants.issuer + "/deviceauth/callback",
            "client_id": IOSCodexOAuthConstants.clientId,
            "code_verifier": codeVerifier,
        ])
        var request = URLRequest(url: URL(string: IOSCodexOAuthConstants.issuer + "/oauth/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(IOSCodexOAuthConstants.originator, forHTTPHeaderField: "originator")
        request.httpBody = form.data(using: .utf8)
        let (data, urlResponse) = try await dataWithRetry(for: request)
        let status = (urlResponse as? HTTPURLResponse)?.statusCode ?? 0
        guard status.isHTTPSuccess else {
            throw oauthError("Codex 令牌交换失败", status, data)
        }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard let accessToken = (obj["access_token"] as? String).nonBlank,
              let refreshToken = (obj["refresh_token"] as? String).nonBlank else {
            throw IOSCodexOAuthError(message: "Codex 令牌交换响应缺少 access/refresh token")
        }
        return Self.buildTokens(
            accessToken: accessToken,
            refreshToken: refreshToken,
            idToken: (obj["id_token"] as? String).nonBlank,
            fallback: nil
        )
    }

    // MARK: - Model listing

    /// Bundled fallback codex model ids (mirrors Android
    /// `OPENAI_CODEX_OAUTH_FALLBACK_MODEL_IDS`), used when the live `/models`
    /// fetch fails so the user always has something to pick.
    /// Bundled fallback codex chat models, used when the live `/models` fetch
    /// fails so the user always has something to pick.
    static let fallbackModels: [(modelId: String, displayName: String)] = [
        (modelId: "gpt-5.4", displayName: "gpt-5.4"),
        (modelId: "gpt-5.3-codex", displayName: "gpt-5.3-codex"),
        (modelId: "gpt-5.3-codex-spark", displayName: "gpt-5.3-codex-spark"),
        (modelId: "gpt-5.2", displayName: "gpt-5.2"),
        (modelId: "gpt-5.1", displayName: "gpt-5.1"),
        (modelId: "gpt-5.1-codex", displayName: "gpt-5.1-codex"),
        (modelId: "gpt-5.1-codex-max", displayName: "gpt-5.1-codex-max"),
    ]

    /// Fetches codex chat models (Bearer + ChatGPT-Account-Id; 401 → refresh +
    /// retry). Never throws — falls back to the bundled defaults on any failure.
    /// Mirrors Android `listCodexModels`.
    func fetchCodexModels() async -> [(modelId: String, displayName: String)] {
        guard let token = try? await getValidAccessToken() else {
            return Self.fallbackModels
        }
        var attempt = await modelsRequest(bearer: token)
        if attempt?.status == 401, let retry = try? await getValidAccessToken(forceRefresh: true) {
            attempt = await modelsRequest(bearer: retry)
        }
        guard let attempt, attempt.status.isHTTPSuccess else {
            return Self.fallbackModels
        }
        let ids = Self.parseModelIds(attempt.data)
            .filter { !$0.localizedCaseInsensitiveContains("review") }
        return ids.isEmpty ? Self.fallbackModels : ids.map { (modelId: $0, displayName: $0) }
    }

    private func modelsRequest(bearer: String) async -> (data: Data, status: Int)? {
        guard let url = URL(string: IOSCodexOAuthConstants.codexBackendBaseUrl
            + "/models?client_version=" + IOSCodexOAuthConstants.clientVersion) else {
            return nil
        }
        var request = URLRequest(url: url)
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(IOSCodexOAuthConstants.originator, forHTTPHeaderField: "originator")
        if let accountId = IOSCodexAuthStore.load(providerId: providerId)?.accountId, !accountId.isEmpty {
            request.setValue(accountId, forHTTPHeaderField: "ChatGPT-Account-Id")
        }
        guard let (data, response) = try? await session.data(for: request) else { return nil }
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private static func parseModelIds(_ data: Data) -> [String] {
        guard let root = try? JSONSerialization.jsonObject(with: data) else { return [] }
        let array: [Any]
        if let obj = root as? [String: Any] {
            if let dataArray = obj["data"] as? [Any] {
                array = dataArray
            } else if let modelsArray = obj["models"] as? [Any] {
                array = modelsArray
            } else {
                return []
            }
        } else if let rootArray = root as? [Any] {
            array = rootArray
        } else {
            return []
        }
        return array.compactMap { ($0 as? [String: Any])?["id"] as? String }
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    // MARK: - HTTP helper

    private func postJSON(url: String, body: Data) async throws -> (Data, HTTPURLResponse) {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(IOSCodexOAuthConstants.originator, forHTTPHeaderField: "originator")
        request.httpBody = body
        let (data, urlResponse) = try await dataWithRetry(for: request)
        guard let http = urlResponse as? HTTPURLResponse else {
            throw IOSCodexOAuthError(message: "无效的网络响应")
        }
        return (data, http)
    }

    /// Runs the request, retrying a few times on transient transport failures
    /// (notably "The network connection was lost", which happens when a pooled
    /// keep-alive connection goes stale across the poll interval or over a VPN —
    /// a retry opens a fresh connection).
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
        throw lastError ?? IOSCodexOAuthError(message: "网络请求失败")
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

    private func oauthError(_ prefix: String, _ status: Int, _ body: Data) -> IOSCodexOAuthError {
        let detail = Self.safeOAuthError(body)
        return IOSCodexOAuthError(message: detail.isEmpty ? "\(prefix): HTTP \(status)" : "\(prefix): \(detail)")
    }

    // MARK: - Token building / JWT

    private static func buildTokens(
        accessToken: String,
        refreshToken: String,
        idToken: String?,
        fallback: IOSCodexAuthTokens?
    ) -> IOSCodexAuthTokens {
        let accessClaims = jwtPayload(accessToken)
        let idClaims = idToken.flatMap { jwtPayload($0) }
        let authClaims = idClaims?["https://api.openai.com/auth"] as? [String: Any]
        let profileClaims = idClaims?["https://api.openai.com/profile"] as? [String: Any]

        let expFromToken = (accessClaims?["exp"] as? NSNumber)?.int64Value
        let expiresAt = expFromToken.map { $0 * 1000 }
            ?? (nowMillis() + IOSCodexOAuthConstants.fallbackTokenLifetimeMillis)

        return IOSCodexAuthTokens(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAtMillis: max(expiresAt, nowMillis() + IOSCodexOAuthConstants.refreshSkewMillis),
            accountId: (authClaims?["chatgpt_account_id"] as? String) ?? fallback?.accountId,
            email: (idClaims?["email"] as? String)
                ?? (profileClaims?["email"] as? String)
                ?? fallback?.email,
            planType: (authClaims?["chatgpt_plan_type"] as? String) ?? fallback?.planType,
            idToken: idToken ?? fallback?.idToken
        )
    }

    /// Decodes a JWT payload (middle segment, base64url) into a claims dictionary.
    private static func jwtPayload(_ jwt: String) -> [String: Any]? {
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

    // MARK: - Small utilities

    private static func nowMillis() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private static func parseInterval(_ raw: Any?) -> Int {
        if let n = raw as? NSNumber { return max(Int(truncating: n), 1) }
        if let s = raw as? String, let v = Int(s.trimmingCharacters(in: .whitespaces)) { return max(v, 1) }
        return 5
    }

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

private extension Optional where Wrapped == String {
    /// Returns the string only if present and non-blank.
    var nonBlank: String? {
        guard let self, !self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return self
    }
}

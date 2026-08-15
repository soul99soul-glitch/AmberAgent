import SwiftUI
import AuthenticationServices
import Shared

/// Drives the Antigravity (Google) OAuth login for one Gemini provider.
/// ASWebAuthenticationSession runs the Google consent page and captures the
/// loopback redirect; token exchange + cloudcode-pa onboarding are delegated
/// to the native `IOSAntigravityOAuthClient`.
@MainActor
final class AntigravityLoginModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case requesting
        case awaitingAuthorization
        case signingIn
        case signedIn(email: String?, tier: String?)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle

    let providerId: String
    private let client: IOSAntigravityOAuthClient
    private var authSession: ASWebAuthenticationSession?
    private var loopback: IOSLoopbackOAuthCallbackServer?
    private var pendingVerifier: String?
    private var pendingState: String?
    private var loginTask: Task<Void, Never>?
    private var acceptedCallback = false

    init(providerId: String) {
        self.providerId = providerId
        self.client = IOSAntigravityOAuthClients.shared(providerId: providerId)
        if let tokens = IOSAntigravityAuthStore.load(providerId: providerId) {
            phase = .signedIn(email: tokens.email, tier: tokens.onboardedTier)
        }
    }

    var isSignedIn: Bool {
        if case .signedIn = phase { return true }
        return false
    }

    /// Opens the Google consent page. `onSignedIn` fires once tokens are
    /// persisted (onboarding is best-effort and does not block the callback).
    func startLogin(onSignedIn: @escaping () -> Void) {
        loginTask?.cancel()
        phase = .requesting

        let state = IOSAntigravityPKCE.randomState()
        let verifier = IOSAntigravityPKCE.generateCodeVerifier()
        let challenge = IOSAntigravityPKCE.s256Challenge(verifier: verifier)
        pendingVerifier = verifier
        pendingState = state
        acceptedCallback = false

        let server = IOSLoopbackOAuthCallbackServer()
        server.onCallback = { [weak self] url in
            Task { @MainActor in
                self?.handleCallback(url: url, error: nil, onSignedIn: onSignedIn)
            }
        }
        do {
            try server.start()
        } catch {
            phase = .failed((error as? LocalizedError)?.errorDescription ?? "无法监听本机 8085 回调端口，请重试。")
            return
        }
        loopback = server

        let session = ASWebAuthenticationSession(
            url: IOSAntigravityPKCE.buildAuthorizationURL(state: state, codeChallenge: challenge),
            callbackURLScheme: "http"
        ) { [weak self] callbackURL, error in
            Task { @MainActor in
                self?.handleCallback(url: callbackURL, error: error, onSignedIn: onSignedIn)
            }
        }
        session.presentationContextProvider = AntigravityAuthPresentationProvider.shared
        session.prefersEphemeralWebBrowserSession = false
        authSession = session
        phase = .awaitingAuthorization
        if !session.start() {
            authSession = nil
            loopback?.stop()
            loopback = nil
            phase = .failed("无法打开 Google 登录页，请重试。")
        }
    }

    func cancelLogin() {
        authSession?.cancel()
        authSession = nil
        loopback?.stop()
        loopback = nil
        loginTask?.cancel()
        loginTask = nil
        pendingVerifier = nil
        pendingState = nil
        acceptedCallback = false
        restorePersistedPhase()
    }

    func logout(onLoggedOut: () -> Void) {
        loginTask?.cancel()
        loginTask = nil
        authSession?.cancel()
        authSession = nil
        loopback?.stop()
        loopback = nil
        client.logout()
        phase = .idle
        onLoggedOut()
    }

    private func handleCallback(
        url: URL?,
        error: Error?,
        onSignedIn: @escaping () -> Void
    ) {
        if acceptedCallback {
            return
        }
        if let error {
            if (error as NSError).code == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                restorePersistedPhase()
            } else {
                phase = .failed((error as NSError).localizedDescription)
            }
            return
        }
        acceptedCallback = true
        loopback?.stop()
        loopback = nil
        authSession?.cancel()
        authSession = nil
        guard let url,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let query = components.queryItems else {
            phase = .failed("Google 授权回调无效，请重试。")
            return
        }
        let host = components.host?.lowercased()
        let portMatches = components.port == nil || components.port == 8085
        guard (host == "localhost" || host == "127.0.0.1"),
              components.path == "/oauth/callback",
              portMatches else {
            phase = .failed("Google 授权回调地址无效，请重试。")
            return
        }
        let values = Dictionary(uniqueKeysWithValues: query.map { ($0.name, $0.value ?? "") })
        if let errorValue = values["error"], !errorValue.isEmpty {
            phase = .failed("Google 授权失败：\(errorValue)")
            return
        }
        guard let code = values["code"], !code.isEmpty else {
            phase = .failed("Google 授权回调缺少 code。")
            return
        }
        guard values["state"] == pendingState else {
            phase = .failed("Google 授权 state 不一致，请重新登录。")
            return
        }
        let verifier = pendingVerifier ?? ""
        pendingVerifier = nil
        pendingState = nil

        phase = .signingIn
        loginTask = Task { [client] in
            do {
                _ = try await client.exchangeCode(code: code, codeVerifier: verifier)
                // Onboarding is best-effort here (same policy as Android): the
                // login succeeds even if the ghost-project dance fails; a chat
                // request retries it idempotently.
                let onboarded = try? await client.ensureOnboarded()
                let email = onboarded?.email ?? IOSAntigravityAuthStore.load(providerId: providerId)?.email
                let tier = onboarded?.onboardedTier ?? IOSAntigravityAuthStore.load(providerId: providerId)?.onboardedTier
                self.phase = .signedIn(email: email, tier: tier)
                onSignedIn()
            } catch is CancellationError {
                self.restorePersistedPhase()
            } catch {
                if Task.isCancelled {
                    self.restorePersistedPhase()
                } else {
                    self.phase = .failed((error as NSError).localizedDescription)
                }
            }
        }
    }

    private func restorePersistedPhase() {
        if let tokens = IOSAntigravityAuthStore.load(providerId: providerId) {
            phase = .signedIn(email: tokens.email, tier: tokens.onboardedTier)
        } else {
            phase = .idle
        }
    }
}

/// Presentation context provider for ASWebAuthenticationSession.
private final class AntigravityAuthPresentationProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = AntigravityAuthPresentationProvider()

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        if let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
           let window = scene.windows.first(where: \.isKeyWindow) ?? scene.windows.first {
            return window
        }
        return UIWindow()
    }
}

/// Antigravity (Google account) sign-in sheet for the Gemini provider.
struct AntigravityLoginView: View {
    let providerId: String
    /// Called with `.antigravityOauth` after a successful sign-in and `.apiKey`
    /// after logout, so the caller can persist the provider's auth mode.
    let onAuthModeChange: (GoogleAuthMode) -> Void
    let onSignedIn: () -> Void
    let onLoggedOut: () -> Void

    @StateObject private var model: AntigravityLoginModel
    @Environment(\.dismiss) private var dismiss

    init(
        providerId: String,
        onAuthModeChange: @escaping (GoogleAuthMode) -> Void,
        onSignedIn: @escaping () -> Void,
        onLoggedOut: @escaping () -> Void
    ) {
        self.providerId = providerId
        self.onAuthModeChange = onAuthModeChange
        self.onSignedIn = onSignedIn
        self.onLoggedOut = onLoggedOut
        _model = StateObject(wrappedValue: AntigravityLoginModel(providerId: providerId))
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    header
                    content
                }
                .padding(20)
            }
            .background(AmberTheme.background.ignoresSafeArea())
            .navigationTitle("Antigravity 登录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") {
                        model.cancelLogin()
                        dismiss()
                    }
                }
            }
        }
    }

    private var header: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.crop.circle.badge.checkmark")
                .font(.system(size: 40))
                .foregroundStyle(AmberTheme.accent)
            Text("用 Google 账号登录 Antigravity")
                .font(.headline)
            Text("Antigravity 是 Google 的 Gemini 产品。登录后无需 API Key，即可在聊天里调用 Gemini 模型。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Text("会复用 Gemini CLI 的公开 OAuth 客户端，授权范围包含 Google Cloud。")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .idle:
            primaryButton(title: "开始登录") {
                model.startLogin(onSignedIn: {
                    onAuthModeChange(GoogleAuthMode.antigravityOauth)
                    onSignedIn()
                })
            }
        case .requesting, .awaitingAuthorization, .signingIn:
            VStack(spacing: 12) {
                ProgressView()
                Text("正在等待 Google 授权…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("取消") { model.cancelLogin() }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
        case let .signedIn(email, tier):
            signedInView(email: email, tier: tier)
        case let .failed(message):
            VStack(spacing: 14) {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                primaryButton(title: "重试") {
                    model.startLogin(onSignedIn: {
                        onAuthModeChange(GoogleAuthMode.antigravityOauth)
                        onSignedIn()
                    })
                }
            }
        }
    }

    private func signedInView(email: String?, tier: String?) -> some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                Label("已登录", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
                    .font(.headline)
                if let email, !email.isEmpty {
                    Text(email).font(.subheadline)
                }
                if let tier, !tier.isEmpty {
                    Text("Gemini 套餐：\(tier)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            Button(role: .destructive) {
                model.logout(onLoggedOut: {
                    onAuthModeChange(GoogleAuthMode.apiKey)
                    onLoggedOut()
                })
            } label: {
                Text("退出登录")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
    }

    private func primaryButton(title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .fontWeight(.semibold)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
        }
        .buttonStyle(.borderedProminent)
    }
}

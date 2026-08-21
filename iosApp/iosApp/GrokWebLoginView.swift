import SwiftUI
import AuthenticationServices

/// Drives SpaceXAI OAuth for one xAI / Grok Web provider.
/// `ASWebAuthenticationSession` opens the system browser so passkeys and
/// password managers work; the loopback server captures the auth-code redirect.
@MainActor
final class GrokWebLoginModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case requesting
        case awaitingAuthorization
        case signingIn
        case signedIn(email: String?)
        case failed(String)
    }

    @Published private(set) var phase: Phase
    let providerId: String
    private let providerBackup: IOSGrokWebProviderBackup?
    private let client: IOSGrokOAuthClient
    private var authSession: ASWebAuthenticationSession?
    private var loopback: IOSLoopbackOAuthCallbackServer?
    private var pendingVerifier: String?
    private var pendingState: String?
    private var loginTask: Task<Void, Never>?
    private var acceptedCallback = false

    init(providerId: String, providerBackup: IOSGrokWebProviderBackup?) {
        self.providerId = providerId
        self.providerBackup = providerBackup
        self.client = IOSGrokOAuthClients.shared(providerId: providerId)
        if Self.hasPersistedSession(providerId: providerId) {
            self.phase = .signedIn(email: IOSGrokOAuthAuthStore.load(providerId: providerId)?.email)
        } else {
            self.phase = .idle
        }
    }

    var isSignedIn: Bool {
        if case .signedIn = phase { return true }
        return false
    }

    func startLogin(onSignedIn: @escaping () -> Void) {
        loginTask?.cancel()
        authSession?.cancel()
        authSession = nil
        loopback?.stop()
        loopback = nil
        phase = .requesting

        let state = IOSGrokOAuthPKCE.randomState()
        let nonce = IOSGrokOAuthPKCE.randomState()
        let verifier = IOSGrokOAuthPKCE.generateCodeVerifier()
        let challenge = IOSGrokOAuthPKCE.s256Challenge(verifier: verifier)
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
            try server.start(
                port: IOSGrokOAuthConstants.loopbackPort,
                pathPrefix: IOSGrokOAuthConstants.callbackPath
            )
        } catch {
            phase = .failed((error as? LocalizedError)?.errorDescription ?? "无法监听本机回调端口，请重试。")
            return
        }
        loopback = server

        let session = ASWebAuthenticationSession(
            url: IOSGrokOAuthPKCE.buildAuthorizationURL(
                state: state,
                nonce: nonce,
                codeChallenge: challenge
            ),
            callbackURLScheme: "http"
        ) { [weak self] callbackURL, error in
            Task { @MainActor in
                self?.handleCallback(url: callbackURL, error: error, onSignedIn: onSignedIn)
            }
        }
        session.presentationContextProvider = GrokAuthPresentationProvider.shared
        session.prefersEphemeralWebBrowserSession = false
        authSession = session
        phase = .awaitingAuthorization
        if !session.start() {
            authSession = nil
            loopback?.stop()
            loopback = nil
            phase = .failed("无法打开系统浏览器登录页，请重试。")
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

    func logout(onLoggedOut: (IOSGrokWebProviderBackup?) -> Void) {
        loginTask?.cancel()
        loginTask = nil
        authSession?.cancel()
        authSession = nil
        loopback?.stop()
        loopback = nil
        let backup = IOSGrokOAuthAuthStore.load(providerId: providerId)?.providerBackup
            ?? IOSGrokWebAuthStore.load(providerId: providerId)?.providerBackup
            ?? providerBackup
        client.logout()
        IOSGrokWebAuthStore.clear(providerId: providerId)
        phase = .idle
        onLoggedOut(backup)
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
            loopback?.stop()
            loopback = nil
            authSession = nil
            pendingVerifier = nil
            pendingState = nil
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
            phase = .failed("Grok 授权回调无效，请重试。")
            return
        }
        let host = components.host?.lowercased()
        let portMatches = components.port == nil || components.port == Int(IOSGrokOAuthConstants.loopbackPort)
        guard (host == "localhost" || host == "127.0.0.1"),
              components.path == IOSGrokOAuthConstants.callbackPath,
              portMatches else {
            phase = .failed("Grok 授权回调地址无效，请重试。")
            return
        }
        let values = Dictionary(uniqueKeysWithValues: query.map { ($0.name, $0.value ?? "") })
        if let errorValue = values["error"], !errorValue.isEmpty {
            phase = .failed("Grok 授权失败：\(errorValue)")
            return
        }
        guard let code = values["code"], !code.isEmpty else {
            phase = .failed("Grok 授权回调缺少 code。")
            return
        }
        guard values["state"] == pendingState else {
            phase = .failed("Grok 授权 state 不一致，请重新登录。")
            return
        }
        let verifier = pendingVerifier ?? ""
        pendingVerifier = nil
        pendingState = nil

        phase = .signingIn
        loginTask = Task { [client] in
            do {
                let tokens = try await client.exchangeCode(code: code, codeVerifier: verifier)
                if Task.isCancelled {
                    self.restorePersistedPhase()
                    return
                }
                _ = await client.attachProviderBackup(self.providerBackup)
                self.phase = .signedIn(email: tokens.email)
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
        if Self.hasPersistedSession(providerId: providerId) {
            phase = .signedIn(email: IOSGrokOAuthAuthStore.load(providerId: providerId)?.email)
        } else {
            phase = .idle
        }
    }

    static func hasPersistedSession(providerId: String) -> Bool {
        if let tokens = IOSGrokOAuthAuthStore.load(providerId: providerId),
           !tokens.accessToken.isEmpty {
            return true
        }
        guard let session = IOSGrokWebAuthStore.load(providerId: providerId) else { return false }
        return session.isInvalidated != true
            && IOSGrokWebCookieValidator.hasSSOCookie(in: session.cookieHeader)
    }
}

private final class GrokAuthPresentationProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = GrokAuthPresentationProvider()

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

struct GrokWebLoginView: View {
    let providerId: String
    let providerBackup: IOSGrokWebProviderBackup?
    let onSignedIn: () -> Void
    let onLoggedOut: (IOSGrokWebProviderBackup?) -> Void

    @StateObject private var model: GrokWebLoginModel
    @Environment(\.dismiss) private var dismiss

    init(
        providerId: String,
        providerBackup: IOSGrokWebProviderBackup?,
        onSignedIn: @escaping () -> Void,
        onLoggedOut: @escaping (IOSGrokWebProviderBackup?) -> Void
    ) {
        self.providerId = providerId
        self.providerBackup = providerBackup
        self.onSignedIn = onSignedIn
        self.onLoggedOut = onLoggedOut
        _model = StateObject(wrappedValue: GrokWebLoginModel(
            providerId: providerId,
            providerBackup: providerBackup
        ))
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
            .navigationTitle("Grok 登录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") {
                        model.cancelLogin()
                        dismiss()
                    }
                }
            }
            .onDisappear { model.cancelLogin() }
        }
    }

    private var header: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.crop.circle.badge.checkmark")
                .font(.system(size: 40))
                .foregroundStyle(AmberTheme.accent)
            Text("用 Grok 账号登录")
                .font(.headline)
                .multilineTextAlignment(.center)
            Text("登录后无需 xAI API Key，即可在聊天里调用 Grok。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Text("会打开系统浏览器完成 xAI 授权。")
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
                model.startLogin(onSignedIn: onSignedIn)
            }
        case .requesting, .awaitingAuthorization, .signingIn:
            VStack(spacing: 12) {
                ProgressView()
                Text("正在等待 Grok 授权…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Button("取消") { model.cancelLogin() }
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 24)
        case let .signedIn(email):
            signedInView(email: email)
        case let .failed(message):
            VStack(spacing: 14) {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                primaryButton(title: "重试") {
                    model.startLogin(onSignedIn: onSignedIn)
                }
            }
        }
    }

    private func signedInView(email: String?) -> some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                Label("已登录", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
                    .font(.headline)
                if let email, !email.isEmpty {
                    Text(email).font(.subheadline)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            Button(role: .destructive) {
                model.logout(onLoggedOut: onLoggedOut)
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

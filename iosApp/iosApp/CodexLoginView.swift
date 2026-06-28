import SwiftUI
import Shared

/// Drives the Codex device-authorization login for one provider and exposes the
/// phases the UI renders. Wraps the native `IOSCodexOAuthClient`.
@MainActor
final class CodexLoginModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case requesting
        case awaitingAuthorization(userCode: String, url: String)
        case signedIn(email: String?, plan: String?)
        case failed(String)
    }

    @Published private(set) var phase: Phase = .idle

    let providerId: String
    private let client: IOSCodexOAuthClient
    private let persistModels: ([(modelId: String, displayName: String)]) -> Void
    private var loginTask: Task<Void, Never>?

    init(
        providerId: String,
        persistModels: @escaping ([(modelId: String, displayName: String)]) -> Void
    ) {
        self.providerId = providerId
        self.persistModels = persistModels
        self.client = IOSCodexOAuthClient(providerId: providerId)
        if let tokens = IOSCodexAuthStore.load(providerId: providerId) {
            phase = .signedIn(email: tokens.email, plan: tokens.planType)
        }
    }

    var isSignedIn: Bool {
        if case .signedIn = phase { return true }
        return false
    }

    /// Requests a device code and polls until the user finishes the browser
    /// sign-in. `onSignedIn` fires once tokens are persisted.
    func startLogin(onSignedIn: @escaping () -> Void) {
        loginTask?.cancel()
        phase = .requesting
        loginTask = Task { [client] in
            do {
                let authorization = try await client.requestDeviceCode()
                self.phase = .awaitingAuthorization(
                    userCode: authorization.userCode,
                    url: authorization.verificationUrl
                )
                let tokens = try await client.pollDeviceCode(authorization)
                self.phase = .signedIn(email: tokens.email, plan: tokens.planType)
                onSignedIn()
                await self.applyModels()
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

    func cancelLogin() {
        loginTask?.cancel()
        loginTask = nil
        restorePersistedPhase()
    }

    /// Refetches codex models and persists them (signed-in "刷新模型").
    func refreshModels() {
        Task { await applyModels() }
    }

    private func applyModels() async {
        let models = await client.fetchCodexModels()
        persistModels(models)
    }

    func logout(onLoggedOut: () -> Void) {
        loginTask?.cancel()
        loginTask = nil
        client.logout()
        phase = .idle
        onLoggedOut()
    }

    private func restorePersistedPhase() {
        if let tokens = IOSCodexAuthStore.load(providerId: providerId) {
            phase = .signedIn(email: tokens.email, plan: tokens.planType)
        } else {
            phase = .idle
        }
    }
}

/// Codex (ChatGPT account) sign-in sheet. Shows the device user code, opens the
/// verification page in the browser, polls for completion, and reports the auth
/// mode change back so the provider routes through the codex backend.
struct CodexLoginView: View {
    let providerId: String
    /// Called with `.codexOauth` after a successful sign-in and `.apiKey` after
    /// logout, so the caller can persist the provider's auth mode.
    let onAuthModeChange: (OpenAIAuthMode) -> Void

    @StateObject private var model: CodexLoginModel
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss

    /// Persists the fetched codex chat models onto the provider.
    let persistModels: ([(modelId: String, displayName: String)]) -> Void

    init(
        providerId: String,
        onAuthModeChange: @escaping (OpenAIAuthMode) -> Void,
        persistModels: @escaping ([(modelId: String, displayName: String)]) -> Void
    ) {
        self.providerId = providerId
        self.onAuthModeChange = onAuthModeChange
        self.persistModels = persistModels
        _model = StateObject(wrappedValue: CodexLoginModel(providerId: providerId, persistModels: persistModels))
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
            .navigationTitle("Codex 登录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }

    private var header: some View {
        VStack(spacing: 8) {
            Image(systemName: "person.crop.circle.badge.checkmark")
                .font(.system(size: 40))
                .foregroundStyle(AmberTheme.accent)
            Text("用 ChatGPT 账号登录")
                .font(.headline)
            Text("使用 ChatGPT Plus / Pro / Team 订阅,无需 API Key 即可在聊天里调用 Codex 模型。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .idle:
            primaryButton(title: "开始登录") {
                model.startLogin(onSignedIn: { onAuthModeChange(OpenAIAuthMode.codexOauth) })
            }
        case .requesting:
            ProgressView("正在获取登录码…")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
        case let .awaitingAuthorization(userCode, url):
            awaitingView(userCode: userCode, url: url)
        case let .signedIn(email, plan):
            signedInView(email: email, plan: plan)
        case let .failed(message):
            VStack(spacing: 14) {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                primaryButton(title: "重试") {
                    model.startLogin(onSignedIn: { onAuthModeChange(OpenAIAuthMode.codexOauth) })
                }
            }
        }
    }

    private func awaitingView(userCode: String, url: String) -> some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                Text("你的登录码")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(userCode)
                    .font(.system(size: 30, weight: .bold, design: .monospaced))
                    .textSelection(.enabled)
                Button("复制登录码") { UIPasteboard.general.string = userCode }
                    .font(.footnote)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            Text("1. 打开下方页面并用 ChatGPT 登录\n2. 输入上面的登录码\n3. 完成后这里会自动登录")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            primaryButton(title: "打开登录页") {
                if let target = URL(string: url) { openURL(target) }
            }

            HStack(spacing: 8) {
                ProgressView()
                Text("正在等待授权…")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            Button("取消") { model.cancelLogin() }
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    private func signedInView(email: String?, plan: String?) -> some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                Label("已登录", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
                    .font(.headline)
                if let email, !email.isEmpty {
                    Text(email).font(.subheadline)
                }
                if let plan, !plan.isEmpty {
                    Text("订阅:\(plan)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
            .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            Button {
                model.refreshModels()
            } label: {
                Text("刷新模型列表")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button(role: .destructive) {
                model.logout(onLoggedOut: { onAuthModeChange(OpenAIAuthMode.apiKey) })
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

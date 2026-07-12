import SwiftUI
import WebKit
import Shared

@MainActor
final class GrokWebLoginModel: ObservableObject {
    enum Phase: Equatable {
        case idle
        case signedIn
        case failed(String)
    }

    @Published private(set) var phase: Phase
    let providerId: String
    private let providerBackup: IOSGrokWebProviderBackup?

    init(providerId: String, providerBackup: IOSGrokWebProviderBackup?) {
        self.providerId = providerId
        self.providerBackup = providerBackup
        let session = IOSGrokWebAuthStore.load(providerId: providerId)
        self.phase = session.map {
            $0.isInvalidated != true && IOSGrokWebCookieValidator.hasSSOCookie(in: $0.cookieHeader)
        } == true
            ? .signedIn
            : .idle
    }

    func saveCookies(from store: WKHTTPCookieStore, onSignedIn: @escaping () -> Void) {
        store.getAllCookies { [weak self] cookies in
            Task { @MainActor in
                guard let self else { return }
                guard let cookieHeader = IOSGrokWebCookieValidator.ssoCookieHeader(from: cookies) else {
                    self.phase = .failed("没有读取到 Grok 登录凭据。请在页面里完成登录后再点“保存登录”。")
                    return
                }
                guard IOSGrokWebAuthStore.save(
                    providerId: self.providerId,
                    cookieHeader: cookieHeader,
                    providerBackup: self.providerBackup
                ) else {
                    self.phase = .failed("Grok 登录凭据保存失败，请重试。")
                    return
                }
                self.phase = .signedIn
                onSignedIn()
            }
        }
    }

    func logout(
        from store: WKHTTPCookieStore,
        onLoggedOut: @escaping (IOSGrokWebProviderBackup?) -> Void
    ) {
        store.getAllCookies { [weak self] cookies in
            guard let self else { return }
            for cookie in cookies where IOSGrokWebCookieValidator.isGrokDomain(cookie.domain) {
                store.delete(cookie)
            }
            Task { @MainActor in
                let backup = IOSGrokWebAuthStore.load(providerId: self.providerId)?.providerBackup
                IOSGrokWebAuthStore.clear(providerId: self.providerId)
                self.phase = .idle
                onLoggedOut(backup)
            }
        }
    }
}

struct GrokWebLoginView: View {
    let providerId: String
    let providerBackup: IOSGrokWebProviderBackup?
    let onSignedIn: () -> Void
    let onLoggedOut: (IOSGrokWebProviderBackup?) -> Void

    @StateObject private var model: GrokWebLoginModel
    @State private var webView = WKWebView()
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
            VStack(spacing: 0) {
                statusBanner
                GrokWebLoginWebView(webView: webView)
            }
            .background(AmberTheme.background.ignoresSafeArea())
            .navigationTitle("Grok 登录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if model.phase == .signedIn {
                        Button(role: .destructive) {
                            model.logout(
                                from: webView.configuration.websiteDataStore.httpCookieStore,
                                onLoggedOut: onLoggedOut
                            )
                        } label: {
                            Text("退出")
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("完成") { dismiss() }
                }
                ToolbarItem(placement: .bottomBar) {
                    Button {
                        model.saveCookies(from: webView.configuration.websiteDataStore.httpCookieStore, onSignedIn: onSignedIn)
                    } label: {
                        Text("保存登录")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                }
            }
        }
    }

    @ViewBuilder
    private var statusBanner: some View {
        switch model.phase {
        case .idle:
            Text("在下方页面登录 grok.com，完成后点“保存登录”。此功能使用 Grok Web 私有 Cookie 链路，可能随网页端改版失效。")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AmberTheme.surface)
        case .signedIn:
            Label("已保存 Grok 登录", systemImage: "checkmark.seal.fill")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.green)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AmberTheme.surface)
        case let .failed(message):
            Text(message)
                .font(.footnote)
                .foregroundStyle(.red)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AmberTheme.surface)
        }
    }
}

struct GrokWebLoginWebView: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView {
        webView.load(URLRequest(url: URL(string: IOSGrokWebConstants.origin)!))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

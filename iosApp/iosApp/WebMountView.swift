import SwiftUI
import WebKit

struct WebMountSiteRoute: Hashable, Identifiable {
    let siteId: String
    let name: String
    let host: String

    var id: String { siteId }

    init(siteId: String, name: String, host: String) {
        self.siteId = siteId
        self.name = name
        self.host = host
    }

    init(site: IOSWebMountSite) {
        self.siteId = site.id
        self.name = site.displayName
        self.host = site.homepageHost
    }
}

enum IOSDeepReadWebMountAdapter {
    @MainActor
    static func currentPageSource(
        controller: IOSWebMountController = .shared,
        maxChars: Int = 20_000
    ) async -> Result<IOSDeepReadSource, IOSDeepReadSourceNormalizationError> {
        let snapshot = controller.runtime.snapshot
        guard snapshot.status == .ready else {
            let reason = snapshot.error?.nilIfBlank
                ?? "WebMount 当前没有已加载完成的页面；请先打开站点并停留在要深读的页面。"
            return .failure(.unsupported(reason))
        }
        do {
            let extracted = try await controller.runtime.extract(mode: "readable", maxChars: maxChars, maxLinks: 20)
            let result = extracted["text"] as? String
                ?? (extracted["result"] as? [String: Any])?["text"] as? String
                ?? ""
            let title = extracted["title"] as? String
                ?? (extracted["result"] as? [String: Any])?["title"] as? String
                ?? snapshot.title
                ?? "WebMount 页面"
            let url = extracted["url"] as? String
                ?? (extracted["result"] as? [String: Any])?["url"] as? String
                ?? snapshot.currentURL
            let source = try IOSDeepReadSourceNormalizer.webMountSource(title: title, url: url, text: result)
            return .success(source)
        } catch let error as IOSDeepReadSourceNormalizationError {
            return .failure(error)
        } catch {
            return .failure(.unsupported("WebMount 页面正文读取失败：\(error.localizedDescription)"))
        }
    }
}

struct IOSWebMountContentHandoff: Equatable, Identifiable {
    let id: String
    let siteId: String
    let siteName: String
    let title: String
    let sourceURL: String
    let text: String
    let linkCount: Int
    let createdAtMillis: Int64

    var chatPrompt: String {
        """
        请基于以下 WebMount 网页内容继续帮我处理。

        来源：\(siteName)
        标题：\(title.nilIfBlank ?? siteName)
        URL：\(sourceURL)
        链接数：\(linkCount)

        正文：
        \(String(text.prefix(12_000)))
        """
    }

    var boardSignal: IOSRawBoardSignal {
        IOSRawBoardSignal(
            sourceType: IOSBoardSignalSourceType.webmount,
            sourceRef: "webmount:\(siteId):\(sourceURL)",
            title: title.nilIfBlank ?? siteName,
            content: String(text.prefix(4_000)),
            signalTime: createdAtMillis,
            metadataJson: IOSWebMountController.json([
                "site_id": siteId,
                "site_name": siteName,
                "source_url": sourceURL,
                "link_count": linkCount,
                "redacted": true
            ])
        )
    }

    static func from(
        site: IOSWebMountSite,
        snapshot: IOSWebMountRuntimeSnapshot,
        extraction: [String: Any]
    ) -> IOSWebMountContentHandoff? {
        let rawText = (extraction["text"] as? String)?.nilIfBlank
        guard let rawText else { return nil }
        let redactedText = IOSWebMountRedactor.redactedText(rawText).trimmingCharacters(in: .whitespacesAndNewlines)
        guard !redactedText.isEmpty else { return nil }
        let sourceURL = IOSWebMountRedactor.redactedURL(extraction["url"] as? String)
            ?? snapshot.currentURL
            ?? IOSWebMountRedactor.redactedURL(site.homepageURL)
            ?? site.homepageHost
        let links = extraction["links"] as? [[String: Any]] ?? []
        return IOSWebMountContentHandoff(
            id: UUID().uuidString,
            siteId: site.id,
            siteName: site.displayName,
            title: (extraction["title"] as? String)?.nilIfBlank ?? snapshot.title ?? site.displayName,
            sourceURL: sourceURL,
            text: redactedText,
            linkCount: links.count,
            createdAtMillis: IOSWebMountClock.nowMillis()
        )
    }
}

@MainActor
final class IOSWebMountContentHandoffStore {
    static let shared = IOSWebMountContentHandoffStore()

    private var pendingChatHandoff: IOSWebMountContentHandoff?
    private var pendingDeepReadHandoff: IOSWebMountContentHandoff?

    private init() {}

    func prepareChat(_ handoff: IOSWebMountContentHandoff) {
        pendingChatHandoff = handoff
    }

    func consumeChatHandoff() -> IOSWebMountContentHandoff? {
        defer { pendingChatHandoff = nil }
        return pendingChatHandoff
    }

    func prepareDeepRead(_ handoff: IOSWebMountContentHandoff) {
        pendingDeepReadHandoff = handoff
    }

    func consumeDeepReadHandoff() -> IOSWebMountContentHandoff? {
        defer { pendingDeepReadHandoff = nil }
        return pendingDeepReadHandoff
    }
}

@MainActor
@Observable
final class IOSWebMountActivityStore {
    static let shared = IOSWebMountActivityStore()

    var recentExtractionTitle = "—"
    var recentExtractionDetail = "暂无"
    var recentExtractionAtMillis: Int64 = 0

    private init() {}

    func recordExtraction(siteName: String, title: String?, characterCount: Int, linkCount: Int) {
        recentExtractionTitle = title?.nilIfBlank ?? siteName
        recentExtractionDetail = "\(siteName) · \(characterCount) 字符 · \(linkCount) 链接"
        recentExtractionAtMillis = IOSWebMountClock.nowMillis()
    }
}

private enum WebMountResultTab: String, CaseIterable, Identifiable {
    case text
    case links
    case interactive
    case visual
    case debug

    var id: String { rawValue }

    var title: String {
        switch self {
        case .text: "正文"
        case .links: "链接"
        case .interactive: "交互"
        case .visual: "视觉"
        case .debug: "调试"
        }
    }
}

@MainActor
struct WebMountView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    @State private var controller: IOSWebMountController
    @State private var showAddSite = false
    @State private var addName = ""
    @State private var addURL = ""
    @State private var addNeedsLogin = true
    @State private var addCookieName = ""
    @State private var banner: String?

    private var registry: IOSWebMountRegistry { controller.registry }
    private var settings: IOSWebMountSettings { controller.settings }

    init(controller: IOSWebMountController = .shared) {
        _controller = State(initialValue: controller)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        if let banner {
                            WebMountBanner(text: banner)
                                .padding(.top, 4)
                        }
                        stationSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showAddSite) {
            addSiteSheet
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("WebMount")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("\(registry.sites.count) 个站点")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            Button {
                showAddSite = true
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .frame(width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: 22)
            .accessibilityLabel("添加 WebMount 站点")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var stationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "站点")
            AmberFormGroup {
                if registry.sites.isEmpty {
                    Text("还没有 WebMount 站点。添加一个 http(s) 网站，或恢复内置站点。")
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                } else {
                    ForEach(Array(registry.sites.enumerated()), id: \.element.id) { index, site in
                        WebMountStationRow(
                            site: site,
                            onOpen: {
                                router.navigate(to: .webMountSite(site: WebMountSiteRoute(site: site)))
                            },
                            onToggle: { enabled in
                                registry.setEnabled(id: site.id, enabled: enabled)
                            },
                            onDelete: {
                                delete(site)
                            }
                        )
                        if index < registry.sites.count - 1 {
                            WebMountDivider()
                        }
                    }
                }
            }

            HStack(spacing: 10) {
                Button {
                    let restored = registry.restoreMissingSeeds()
                    settings.syncAllowedHosts(registry.sites.flatMap(\.allowedHosts))
                    banner = restored == 0 ? "内置站点已是最新。" : "已恢复 \(restored) 个内置站点。"
                } label: {
                    Label("恢复内置站点", systemImage: "arrow.clockwise")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.bordered)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
        }
    }

    private var addSiteSheet: some View {
        NavigationStack {
            ZStack {
                AmberTheme.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 14) {
                        AmberFormGroup {
                            WebMountTextFieldRow(title: "名称", text: $addName, placeholder: "示例")
                            WebMountDivider()
                            WebMountTextFieldRow(title: "网址", text: $addURL, placeholder: "https://example.com")
                            WebMountDivider()
                            WebMountToggleRow(
                                title: "需要登录",
                                subtitle: "保存登录提示，不会自动打开登录流程。",
                                systemImage: "person.badge.key",
                                tint: AmberTheme.accentAmber,
                                isOn: $addNeedsLogin
                            )
                            if addNeedsLogin {
                                WebMountDivider()
                                WebMountTextFieldRow(title: "Cookie 提示", text: $addCookieName, placeholder: "可选")
                            }
                        }

                        Text("自定义站点会保存在本机，Cookie 内容不会在页面中显示。")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                    }
                    .padding(.top, 16)
                }
            }
            .navigationTitle("添加站点")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showAddSite = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("添加") { addSite() }
                }
            }
        }
    }

    private func addSite() {
        do {
            let site = try registry.addCustomSite(
                displayName: addName,
                homepageURL: addURL,
                needsLogin: addNeedsLogin,
                loginCookieName: addCookieName
            )
            settings.syncAllowedHosts(registry.sites.flatMap(\.allowedHosts))
            addName = ""
            addURL = ""
            addCookieName = ""
            addNeedsLogin = true
            showAddSite = false
            banner = "已添加 \(site.displayName)。"
        } catch {
            banner = "添加失败。请填写名称，并使用 http(s) 网址。"
            showAddSite = false
        }
    }

    private func delete(_ site: IOSWebMountSite) {
        if registry.remove(id: site.id) {
            settings.syncAllowedHosts(registry.sites.flatMap(\.allowedHosts))
            banner = "已移除 \(site.displayName)。"
        }
    }

    private static func jsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}

@MainActor
struct WebMountSiteView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    let site: WebMountSiteRoute

    @State private var controller: IOSWebMountController
    @ObservedObject private var runtime: IOSWebMountWKRuntime
    @State private var cookieSummary: IOSWebMountCookieSummary?
    @State private var bridgeState = ""
    @State private var extractText = ""
    @State private var linksText = ""
    @State private var interactiveText = ""
    @State private var visualSnapshotText = ""
    @State private var debugJSONText = ""
    @State private var selectedResultTab: WebMountResultTab = .text
    @State private var getSelector = "body"
    @State private var getText = ""
    @State private var openURLText = ""
    @State private var contentHandoff: IOSWebMountContentHandoff?
    @State private var banner: String?
    @State private var isLoading = false

    private var registry: IOSWebMountRegistry { controller.registry }
    private var resolvedSite: IOSWebMountSite {
        registry.site(id: site.siteId) ?? IOSWebMountSite(
            id: site.siteId,
            displayName: site.name,
            homepageURL: "https://\(site.host)",
            authKind: .anonymous,
            loginCookieName: nil,
            nativeAdapterId: nil,
            iconKey: nil,
            oauthProviderId: nil,
            allowedHosts: [site.host],
            enabled: false,
            addedAtMillis: IOSWebMountClock.nowMillis()
        )
    }

    init(site: WebMountSiteRoute, controller: IOSWebMountController = .shared) {
        self.site = site
        _controller = State(initialValue: controller)
        _runtime = ObservedObject(wrappedValue: controller.visibleRuntime ?? IOSWebMountWKRuntime())
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        if let banner {
                            WebMountBanner(text: banner)
                                .padding(.top, 4)
                        }
                        runtimeSection
                        webViewSection
                        bridgeSection
                        cookieSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task {
            if openURLText.isEmpty {
                openURLText = resolvedSite.homepageURL
            }
            await refreshCookieSummary()
            if runtime.snapshot.status == .idle {
                await openSite()
            }
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回 WebMount", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text(resolvedSite.displayName)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text(resolvedSite.homepageHost)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            AmberGlassCircleButton(systemImage: "arrow.clockwise", accessibilityLabel: "重新加载", size: 44, symbolSize: 17) {
                Task { await openSite() }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var runtimeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "网页状态")
            AmberFormGroup {
                WebMountInfoRow(
                    title: runtime.snapshot.status.rawValue,
                    subtitle: runtime.snapshot.currentURL ?? runtime.snapshot.requestedURL ?? resolvedSite.homepageURL,
                    systemImage: statusImage,
                    tint: statusTint,
                    trailing: runtime.snapshot.title?.nilIfBlank ?? "\(Int(runtime.snapshot.estimatedProgress * 100))%"
                )
                WebMountDivider()
                browserChromeRow
                WebMountDivider()
                AmberGlassGroup(spacing: 12) {
                    HStack(spacing: 8) {
                        TextField("https://example.com/path", text: $openURLText)
                            .font(.system(size: 13, design: .monospaced))
                            .textFieldStyle(.plain)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium))
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                        Button("打开") { Task { await openTypedURL() } }
                            .buttonStyle(.glassProminent)
                            .disabled(isLoading)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                WebMountDivider()
                AmberGlassGroup(spacing: 12) {
                    HStack(spacing: 10) {
                        Button {
                            Task { _ = await controller.runtime.back() }
                        } label: {
                            Label("后退", systemImage: "chevron.left")
                        }
                        .buttonStyle(.glass)
                        .disabled(!runtime.snapshot.canGoBack)

                        Button {
                            Task { _ = await controller.runtime.forward() }
                        } label: {
                            Label("前进", systemImage: "chevron.right")
                        }
                        .buttonStyle(.glass)
                        .disabled(!runtime.snapshot.canGoForward)

                        Spacer()
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
            }
        }
    }

    private var browserChromeRow: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                WebMountBadge(text: resolvedSite.homepageHost, systemImage: "network", tint: AmberTheme.accentCyan)
                WebMountBadge(text: loginBadgeText, systemImage: "person.crop.circle", tint: loginBadgeTint)
                WebMountBadge(text: resolvedSite.enabled ? "allowlisted" : "blocked", systemImage: resolvedSite.enabled ? "checkmark.shield" : "xmark.shield", tint: resolvedSite.enabled ? AmberTheme.accentGreen : AmberTheme.accentRed)
                WebMountBadge(text: runtime.snapshot.sessionId, systemImage: "rectangle.stack", tint: AmberTheme.accentIndigo)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
        .scrollIndicators(.hidden)
    }

    private var webViewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "网页")
            WebMountRuntimeWebView(runtime: runtime)
                .frame(height: 420)
                .clipShape(RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: AmberTheme.radiusMedium, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }
                .padding(.horizontal, 16)
        }
    }

    private var bridgeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "页面内容")
            AmberFormGroup {
                AmberGlassGroup(spacing: 12) {
                    HStack(spacing: 8) {
                        Button("状态") { Task { await readState() } }
                            .buttonStyle(.glass)
                        Button("提取正文") { Task { await extractReadable() } }
                            .buttonStyle(.glass)
                        Button("观察") { Task { await self.observePage() } }
                            .buttonStyle(.glass)
                        Button("视觉快照") { Task { await visualSnapshot() } }
                            .buttonStyle(.glass)
                        Spacer()
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)

                Picker("页面内容", selection: $selectedResultTab) {
                    ForEach(WebMountResultTab.allCases) { tab in
                        Text(tab.title).tag(tab)
                    }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 14)
                .padding(.bottom, 8)

                if let contentHandoff, selectedResultTab == .text {
                    WebMountHandoffActions(
                        handoff: contentHandoff,
                        onChat: {
                            IOSWebMountContentHandoffStore.shared.prepareChat(contentHandoff)
                            router.navigate(to: .chat)
                        },
                        onDeepRead: {
                            IOSWebMountContentHandoffStore.shared.prepareDeepRead(contentHandoff)
                            router.navigate(to: .board)
                        }
                    )
                    WebMountDivider()
                }
                WebMountCodeBlock(text: self.selectedResultText)
                WebMountDivider()
                AmberGlassGroup(spacing: 12) {
                    HStack(spacing: 8) {
                        TextField("选择器", text: $getSelector)
                            .font(.system(size: 13, design: .monospaced))
                            .textFieldStyle(.plain)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 8)
                            .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium))
                            .autocorrectionDisabled()
                        Button("读取") { Task { await getElement() } }
                            .buttonStyle(.glass)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                if !getText.isEmpty {
                    WebMountDivider()
                    WebMountCodeBlock(text: getText)
                }
            }
        }
    }

    private var cookieSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "登录状态")
            AmberFormGroup {
                WebMountInfoRow(
                    title: "Cookie",
                    subtitle: cookieSubtitle,
                    systemImage: "circle.grid.cross",
                    tint: AmberTheme.accentAmber,
                    trailing: cookieSummary.map { "\($0.cookieCount)" } ?? "..."
                )
                WebMountDivider()
                AmberGlassGroup(spacing: 12) {
                    HStack(spacing: 8) {
                        Button {
                            Task { await openSite() }
                        } label: {
                            Label("打开网页登录", systemImage: "person.badge.key")
                        }
                        .buttonStyle(.glass)

                        Button {
                            Task { await refreshCookieSummary() }
                        } label: {
                            Label("重新检测 Cookie", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(.glass)

                        Spacer()
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                WebMountDivider()
                Button(role: .destructive) {
                    Task { await clearSession() }
                } label: {
                    HStack {
                        Image(systemName: "trash")
                        Text("清除本站登录状态")
                        Spacer()
                    }
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.accentRed)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var statusImage: String {
        switch runtime.snapshot.status {
        case .idle: "circle"
        case .loading: "arrow.triangle.2.circlepath"
        case .ready: "checkmark.circle"
        case .failed: "exclamationmark.triangle"
        }
    }

    private var statusTint: Color {
        switch runtime.snapshot.status {
        case .idle: AmberTheme.muted2
        case .loading: AmberTheme.accentAmber
        case .ready: AmberTheme.accentGreen
        case .failed: AmberTheme.accentRed
        }
    }

    private var cookieSubtitle: String {
        guard let cookieSummary else { return "正在读取 Cookie 摘要" }
        let names = cookieSummary.cookieNames.isEmpty ? "无" : cookieSummary.cookieNames.joined(separator: ", ")
        let login = cookieSummary.hasLoginCookie.map { $0 ? "已检测到登录 Cookie" : "未检测到登录 Cookie" } ?? "登录状态未知"
        return "\(login) · \(names)"
    }

    private var selectedResultText: String {
        switch selectedResultTab {
        case .text:
            return extractText.nilIfBlank ?? "尚未提取正文。"
        case .links:
            return linksText.nilIfBlank ?? "尚未读取链接。"
        case .interactive:
            return interactiveText.nilIfBlank ?? "尚未观察可交互元素。"
        case .visual:
            return visualSnapshotText.nilIfBlank ?? "尚未读取视觉快照。"
        case .debug:
            return debugJSONText.nilIfBlank ?? bridgeState.nilIfBlank ?? "尚无调试 JSON。"
        }
    }

    private var loginBadgeText: String {
        guard let cookieSummary else { return "login unknown" }
        if cookieSummary.hasLoginCookie == true { return "logged in" }
        if cookieSummary.hasLoginCookie == false { return "logged out" }
        return "login unknown"
    }

    private var loginBadgeTint: Color {
        guard let cookieSummary else { return AmberTheme.muted2 }
        if cookieSummary.hasLoginCookie == true { return AmberTheme.accentGreen }
        if cookieSummary.hasLoginCookie == false { return AmberTheme.accentRed }
        return AmberTheme.accentAmber
    }

    private func openSite() async {
        isLoading = true
        _ = await controller.openForUser(site: resolvedSite)
        openURLText = runtime.snapshot.currentURL ?? resolvedSite.homepageURL
        isLoading = false
        if let error = runtime.snapshot.error?.nilIfBlank {
            banner = error
        }
    }

    private func openTypedURL() async {
        isLoading = true
        let output = await controller.execute(
            toolName: "wm_open",
            input: IOSWebMountController.json([
                "site_id": resolvedSite.id,
                "url": openURLText
            ]),
            isUserInitiated: true
        )
        isLoading = false
        if let object = Self.jsonObject(output),
           object["ok"] as? Bool == false {
            banner = object["reason"] as? String ?? object["error"] as? String ?? "打开失败。"
        } else {
            banner = nil
            openURLText = runtime.snapshot.currentURL ?? openURLText
        }
    }

    private func refreshCookieSummary() async {
        cookieSummary = await controller.cookieStore.summary(for: resolvedSite)
    }

    private func readState() async {
        do {
            let state = try await controller.runtime.state()
            bridgeState = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(state))
            debugJSONText = bridgeState
            selectedResultTab = .debug
        } catch {
            bridgeState = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
            debugJSONText = bridgeState
            selectedResultTab = .debug
        }
    }

    private func extractReadable() async {
        do {
            let result = try await controller.runtime.extract(mode: "readable", maxChars: 4_000, maxLinks: 12)
            let redacted = IOSWebMountRedactor.redactedJSONObject(result)
            extractText = IOSWebMountRedactor.redactedText((result["text"] as? String) ?? "")
            linksText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(result["links"] ?? []))
            debugJSONText = IOSWebMountController.json(redacted)
            contentHandoff = IOSWebMountContentHandoff.from(
                site: resolvedSite,
                snapshot: runtime.snapshot,
                extraction: result
            )
            IOSWebMountActivityStore.shared.recordExtraction(
                siteName: resolvedSite.displayName,
                title: result["title"] as? String,
                characterCount: extractText.count,
                linkCount: (result["links"] as? [[String: Any]])?.count ?? 0
            )
            selectedResultTab = .text
        } catch {
            extractText = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
            debugJSONText = extractText
            contentHandoff = nil
            selectedResultTab = .debug
        }
    }

    private func observePage() async {
        do {
            let readable = try await controller.runtime.extract(mode: "readable", maxChars: 2_000, maxLinks: 20)
            let interactive = try await controller.runtime.extract(mode: "interactive", maxChars: 0, maxLinks: 80)
            let visual = try await controller.runtime.extract(mode: "snapshot", maxChars: 0, maxLinks: 80)
            extractText = IOSWebMountRedactor.redactedText((readable["text"] as? String) ?? extractText)
            linksText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(readable["links"] ?? []))
            interactiveText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(interactive["nodes"] ?? []))
            visualSnapshotText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(visual["visual_candidates"] ?? visual))
            debugJSONText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject([
                "readable": readable,
                "interactive": interactive,
                "visual": visual
            ]))
            selectedResultTab = .interactive
        } catch {
            debugJSONText = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
            selectedResultTab = .debug
        }
    }

    private func visualSnapshot() async {
        do {
            let result = try await controller.runtime.extract(mode: "snapshot", maxChars: 4_000, maxLinks: 24)
            visualSnapshotText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(result["visual_candidates"] ?? result))
            interactiveText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(result["interactive_nodes"] ?? []))
            debugJSONText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(result))
            selectedResultTab = .visual
        } catch {
            visualSnapshotText = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
            debugJSONText = visualSnapshotText
            selectedResultTab = .debug
        }
    }

    private func getElement() async {
        do {
            let result = try await controller.runtime.get(
                selector: getSelector,
                target: nil,
                kind: "text",
                attrName: nil,
                maxChars: 4_000
            )
            getText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(result))
            debugJSONText = getText
        } catch {
            getText = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
            debugJSONText = getText
        }
    }

    private func clearSession() async {
        let result = await controller.cookieStore.clearSession(for: resolvedSite)
        banner = "已清除 \(result.deletedCookieCount) 个 Cookie 和 \(result.clearedWebsiteDataRecords) 条网页数据。"
        await refreshCookieSummary()
    }

    private static func jsonObject(_ text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }
}

@MainActor
private struct WebMountRuntimeWebView: UIViewRepresentable {
    let runtime: IOSWebMountWKRuntime

    func makeUIView(context: Context) -> WKWebView {
        runtime.webView ?? WKWebView()
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

private struct WebMountBadge: View {
    let text: String
    let systemImage: String
    let tint: Color

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(tint)
            .lineLimit(1)
            .padding(.horizontal, 9)
            .padding(.vertical, 6)
            .background(tint.opacity(0.12), in: Capsule())
    }
}

private struct WebMountStationRow: View {
    let site: IOSWebMountSite
    let onOpen: () -> Void
    let onToggle: (Bool) -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Button(action: onOpen) {
                HStack(spacing: 12) {
                    Image(systemName: iconName)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(iconColor)
                        .frame(width: 28, height: 28)

                    VStack(alignment: .leading, spacing: 3) {
                        Text(site.displayName)
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                            .lineLimit(1)
                        Text("\(site.authKind.rawValue) · \(IOSWebMountRedactor.redactedURL(site.homepageURL) ?? site.homepageURL)")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(2)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .buttonStyle(.plain)

            Toggle("", isOn: Binding(
                get: { site.enabled },
                set: { value in
                    Task { @MainActor in onToggle(value) }
                }
            ))
                .labelsHidden()
                .toggleStyle(.switch)

            Button(role: .destructive, action: onDelete) {
                Image(systemName: "trash")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }

    private var iconName: String {
        switch site.authKind {
        case .anonymous: "globe"
        case .cookie: "person.badge.key"
        case .oauth: "key"
        }
    }

    private var iconColor: Color {
        switch site.authKind {
        case .anonymous: AmberTheme.accentGreen
        case .cookie: AmberTheme.accentAmber
        case .oauth: AmberTheme.accentIndigo
        }
    }
}

private struct WebMountToggleRow: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let tint: Color
    @Binding var isOn: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Toggle("", isOn: $isOn)
                .labelsHidden()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

private struct WebMountInfoRow: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let tint: Color
    let trailing: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(tint)
                .frame(width: 28, height: 28)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(trailing)
                .font(.caption.weight(.semibold))
                .foregroundStyle(tint)
                .multilineTextAlignment(.trailing)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
    }
}

private struct WebMountHandoffActions: View {
    let handoff: IOSWebMountContentHandoff
    let onChat: () -> Void
    let onDeepRead: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("已提取 \(handoff.text.count) 个字符，可转入下一步。")
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(2)

            AmberGlassGroup(spacing: 12) {
                HStack(spacing: 8) {
                    Button(action: onChat) {
                        Label("转入聊天", systemImage: "bubble.left.and.text.bubble.right")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.glass)

                    Button(action: onDeepRead) {
                        Label("转入深度阅读", systemImage: "book.pages")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.glass)

                    Spacer(minLength: 0)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct WebMountTextFieldRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(width: 88, alignment: .leading)

            TextField(placeholder, text: $text)
                .font(.system(size: 14, design: .monospaced))
                .textFieldStyle(.plain)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

private struct WebMountCodeBlock: View {
    let text: String

    var body: some View {
        ScrollView(.horizontal) {
            Text(text)
                .font(.system(size: 12, design: .monospaced))
                .foregroundStyle(AmberTheme.foreground2)
                .textSelection(.enabled)
                .padding(10)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmberTheme.surface2.opacity(0.65), in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium))
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct WebMountDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 58)
    }
}

private struct WebMountBanner: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .foregroundStyle(AmberTheme.foreground2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12)
            .padding(.vertical, 9)
            .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium))
            .padding(.horizontal, 16)
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

#Preview {
    NavigationStack {
        WebMountView()
    }
}

#Preview("WebMount Site") {
    NavigationStack {
        WebMountSiteView(site: .init(siteId: "hackernews", name: "Hacker News", host: "news.ycombinator.com"))
    }
}

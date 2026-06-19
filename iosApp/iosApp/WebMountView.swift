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
                        settingsSection
                        stationSection
                        toolSection
                        unsupportedSection
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
                Text("\(registry.sites.count) stations · WKWebView")
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

    private var settingsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Agent Gate")
            AmberFormGroup {
                WebMountToggleRow(
                    title: "Global enabled",
                    subtitle: settings.globalEnabled ? "wm tools can run for enabled, allowlisted stations" : "wm tools are not exposed to unattended execution",
                    systemImage: "power",
                    tint: settings.globalEnabled ? AmberTheme.accentGreen : AmberTheme.muted2,
                    isOn: Binding(
                        get: { settings.globalEnabled },
                        set: { settings.globalEnabled = $0 }
                    )
                )
                WebMountDivider()
                WebMountToggleRow(
                    title: "JS eval",
                    subtitle: "Tracked separately; arbitrary eval remains blocked from model tools",
                    systemImage: "curlybraces",
                    tint: settings.evalEnabled ? AmberTheme.accentRed : AmberTheme.muted2,
                    isOn: Binding(
                        get: { settings.evalEnabled },
                        set: { settings.evalEnabled = $0 }
                    )
                )
                .disabled(!settings.globalEnabled)
                .opacity(settings.globalEnabled ? 1 : 0.55)
                WebMountDivider()
                WebMountInfoRow(
                    title: "Allowlist",
                    subtitle: "\(settings.allowedHosts.count) hosts · \(settings.allowedSchemes.sorted().joined(separator: ", "))",
                    systemImage: "checkmark.shield",
                    tint: AmberTheme.accentCyan,
                    trailing: "active"
                )
            }
        }
    }

    private var stationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Stations")
            AmberFormGroup {
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

            HStack(spacing: 10) {
                Button {
                    let restored = registry.restoreMissingSeeds()
                    settings.addAllowedHosts(registry.sites.flatMap(\.allowedHosts))
                    banner = restored == 0 ? "No missing seed stations." : "Restored \(restored) seed station(s)."
                } label: {
                    Label("Restore Seeds", systemImage: "arrow.clockwise")
                        .font(.caption.weight(.semibold))
                }
                .buttonStyle(.bordered)

                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
        }
    }

    private var toolSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Tool Catalog")
            AmberFormGroup {
                ForEach(Array(IOSWebMountToolCatalog.descriptors.enumerated()), id: \.element.name) { index, descriptor in
                    WebMountInfoRow(
                        title: descriptor.name,
                        subtitle: descriptor.description,
                        systemImage: descriptor.requiresUserAction ? "hand.raised" : "checkmark.circle",
                        tint: descriptor.requiresUserAction ? AmberTheme.accentAmber : AmberTheme.accentGreen,
                        trailing: descriptor.requiresUserAction ? "user" : "safe"
                    )
                    if index < IOSWebMountToolCatalog.descriptors.count - 1 {
                        WebMountDivider()
                    }
                }
            }
        }
    }

    private var unsupportedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Blocked")
            AmberFormGroup {
                WebMountInfoRow(
                    title: "High-risk or adapter-only tools",
                    subtitle: IOSWebMountToolCatalog.unsupportedToolNames.sorted().joined(separator: ", "),
                    systemImage: "nosign",
                    tint: AmberTheme.accentRed,
                    trailing: "not exposed"
                )
            }
        }
    }

    private var addSiteSheet: some View {
        NavigationStack {
            ZStack {
                AmberTheme.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 14) {
                        AmberFormGroup {
                            WebMountTextFieldRow(title: "Name", text: $addName, placeholder: "Example")
                            WebMountDivider()
                            WebMountTextFieldRow(title: "URL", text: $addURL, placeholder: "https://example.com")
                            WebMountDivider()
                            WebMountToggleRow(
                                title: "Needs login",
                                subtitle: "Adds a cookie-based login hint; no login is opened automatically",
                                systemImage: "person.badge.key",
                                tint: AmberTheme.accentAmber,
                                isOn: $addNeedsLogin
                            )
                            if addNeedsLogin {
                                WebMountDivider()
                                WebMountTextFieldRow(title: "Cookie hint", text: $addCookieName, placeholder: "optional")
                            }
                        }

                        Text("Custom sites are persisted locally and added to the URL allowlist. Cookie values are never displayed.")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                    }
                    .padding(.top, 16)
                }
            }
            .navigationTitle("Add Station")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { showAddSite = false }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { addSite() }
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
            settings.addAllowedHosts(site.allowedHosts)
            addName = ""
            addURL = ""
            addCookieName = ""
            addNeedsLogin = true
            showAddSite = false
            banner = "Added \(site.displayName)."
        } catch {
            banner = "Could not add site. Use an http(s) URL and a non-empty name."
            showAddSite = false
        }
    }

    private func delete(_ site: IOSWebMountSite) {
        if registry.remove(id: site.id) {
            banner = "Removed \(site.displayName). Cookies were not cleared."
        }
    }
}

@MainActor
struct WebMountSiteView: View {
    @Environment(\.dismiss) private var dismiss

    let site: WebMountSiteRoute

    @State private var controller: IOSWebMountController
    @ObservedObject private var runtime: IOSWebMountWKRuntime
    @State private var cookieSummary: IOSWebMountCookieSummary?
    @State private var bridgeState = ""
    @State private var extractText = ""
    @State private var getSelector = "body"
    @State private var getText = ""
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
            AmberSectionLabel(text: "Runtime")
            AmberFormGroup {
                WebMountInfoRow(
                    title: runtime.snapshot.status.rawValue,
                    subtitle: runtime.snapshot.currentURL ?? runtime.snapshot.requestedURL ?? resolvedSite.homepageURL,
                    systemImage: statusImage,
                    tint: statusTint,
                    trailing: runtime.snapshot.title?.nilIfBlank ?? "\(Int(runtime.snapshot.estimatedProgress * 100))%"
                )
                WebMountDivider()
                HStack(spacing: 10) {
                    Button {
                        Task { _ = await controller.runtime.back() }
                    } label: {
                        Label("Back", systemImage: "chevron.left")
                    }
                    .buttonStyle(.bordered)

                    Button {
                        Task { _ = await controller.runtime.forward() }
                    } label: {
                        Label("Forward", systemImage: "chevron.right")
                    }
                    .buttonStyle(.bordered)

                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 11)
            }
        }
    }

    private var webViewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "WKWebView")
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
            AmberSectionLabel(text: "Bridge")
            AmberFormGroup {
                WebMountInfoRow(
                    title: "Read-only bridge",
                    subtitle: "state / extract / get only; arbitrary eval is not exposed",
                    systemImage: "link",
                    tint: AmberTheme.accentCyan,
                    trailing: "restricted"
                )
                WebMountDivider()
                HStack(spacing: 8) {
                    Button("State") { Task { await readState() } }
                        .buttonStyle(.bordered)
                    Button("Extract") { Task { await extractReadable() } }
                        .buttonStyle(.bordered)
                    Spacer()
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)

                if !bridgeState.isEmpty {
                    WebMountCodeBlock(text: bridgeState)
                    WebMountDivider()
                }
                if !extractText.isEmpty {
                    WebMountCodeBlock(text: extractText)
                    WebMountDivider()
                }
                HStack(spacing: 8) {
                    TextField("selector", text: $getSelector)
                        .font(.system(size: 13, design: .monospaced))
                        .textFieldStyle(.plain)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 8)
                        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: AmberTheme.radiusMedium))
                        .autocorrectionDisabled()
                    Button("Get") { Task { await getElement() } }
                        .buttonStyle(.bordered)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                if !getText.isEmpty {
                    WebMountCodeBlock(text: getText)
                }
            }
        }
    }

    private var cookieSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Cookies")
            AmberFormGroup {
                WebMountInfoRow(
                    title: "Summary",
                    subtitle: cookieSubtitle,
                    systemImage: "circle.grid.cross",
                    tint: AmberTheme.accentAmber,
                    trailing: cookieSummary.map { "\($0.cookieCount)" } ?? "..."
                )
                WebMountDivider()
                Button(role: .destructive) {
                    Task { await clearSession() }
                } label: {
                    HStack {
                        Image(systemName: "trash")
                        Text("Clear This Station Session")
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
        guard let cookieSummary else { return "Loading redacted cookie metadata" }
        let names = cookieSummary.cookieNames.isEmpty ? "none" : cookieSummary.cookieNames.joined(separator: ", ")
        let login = cookieSummary.hasLoginCookie.map { $0 ? "login cookie present" : "login cookie missing" } ?? "login cookie unknown"
        return "\(login) · names: \(names)"
    }

    private func openSite() async {
        isLoading = true
        _ = await controller.openForUser(site: resolvedSite)
        isLoading = false
        if let error = runtime.snapshot.error?.nilIfBlank {
            banner = error
        }
    }

    private func refreshCookieSummary() async {
        cookieSummary = await controller.cookieStore.summary(for: resolvedSite)
    }

    private func readState() async {
        do {
            let state = try await controller.runtime.state()
            bridgeState = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(state))
        } catch {
            bridgeState = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
        }
    }

    private func extractReadable() async {
        do {
            let result = try await controller.runtime.extract(mode: "readable", maxChars: 4_000, maxLinks: 12)
            extractText = IOSWebMountController.json(IOSWebMountRedactor.redactedJSONObject(result))
        } catch {
            extractText = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
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
        } catch {
            getText = IOSWebMountController.json(["ok": false, "error": error.localizedDescription])
        }
    }

    private func clearSession() async {
        let result = await controller.cookieStore.clearSession(for: resolvedSite)
        banner = "Cleared \(result.deletedCookieCount) cookie(s) and \(result.clearedWebsiteDataRecords) website data record(s)."
        await refreshCookieSummary()
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

            Toggle("", isOn: Binding(get: { site.enabled }, set: onToggle))
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

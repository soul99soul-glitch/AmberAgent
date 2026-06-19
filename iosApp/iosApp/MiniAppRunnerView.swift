import SwiftUI

@MainActor
struct MiniAppRunnerView: View {
    @Environment(\.dismiss) private var dismiss

    let appId: String
    let settingsStore: SettingsStore?
    let sharedSettings: IOSSharedSettingsStore?

    @State private var repository = IOSMiniAppRepository.shared
    @State private var generatedHtml: String = ""
    @State private var loadedHtml: String = ""
    @State private var runnerError: String?
    @State private var actionMessage: String?
    @State private var bridgeLog: [String] = []
    @State private var didInitialLoad = false
    @State private var didMarkRun = false

    init(appId: String, settingsStore: SettingsStore? = nil, sharedSettings: IOSSharedSettingsStore? = nil) {
        self.appId = appId
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
    }

    static let sampleHtml = IOSMiniAppFixtures.sampleHtml

    @MainActor
    static func initialHtml(appId: String, repository: IOSMiniAppRepository? = nil) -> String {
        let repository = repository ?? IOSMiniAppRepository.shared
        return repository.get(appId)?.htmlContent ?? missingAppHtml(appId: appId)
    }

    static func missingAppHtml(appId: String) -> String {
        """
        <!DOCTYPE html><html><head><meta charset="utf-8"><style>body{font-family:-apple-system;padding:16px;color:#555}</style></head>
        <body><h3>MiniApp 未找到</h3><p>repository 中没有 appId：<code>\(appId)</code></p></body></html>
        """
    }

    private var app: IOSMiniAppRecord? {
        repository.get(appId)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        if let app {
                            intro(app)
                            metadataSection(app)
                            runnerSection(app)
                            grantsSection(app)
                            versionsSection(app)
                            auditSection(app)
                            bridgeLogSection
                        } else {
                            missingSection
                        }
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task(id: appId) {
            loadApp(markRun: true)
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回小应用", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text(app?.title ?? "MiniApp 未找到")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text(app.map { "v\($0.version) · \($0.runCount) 次运行" } ?? "appId 未找到")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(AmberTheme.muted)
            }

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private func intro(_ app: IOSMiniAppRecord) -> some View {
        Text("Runner 正在读取 Documents 持久化记录。HTML 每次加载前都会经过 MiniAppHtmlValidator；bridge 调用会检查声明权限、grant 决策和 iOS 设置。")
            .font(.footnote)
            .lineSpacing(3)
            .foregroundStyle(AmberTheme.muted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 16)
    }

    private func metadataSection(_ app: IOSMiniAppRecord) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "记录状态")
            AmberFormGroup {
                MiniAppCapabilityStatusRow(row: .init(
                    title: app.description,
                    subtitle: "category: \(app.category) · hash: \(app.htmlHash.prefix(12)) · updated: \(dateText(app.updatedAt))",
                    status: app.pinned ? "已置顶" : "普通",
                    tint: app.pinned ? AmberTheme.accentAmber : AmberTheme.accent
                ))
                MiniAppCapabilityDivider()
                MiniAppCapabilityStatusRow(row: .init(
                    title: "版本与运行",
                    subtitle: "当前 v\(app.version)，历史 \(repository.versions(appId: app.id).count) 个版本；最后运行：\(app.lastRunAt.map(dateText) ?? "尚未记录")。",
                    status: "\(app.runCount) 次",
                    tint: AmberTheme.accentGreen
                ))
                if let summary = app.boardSummary, !summary.isEmpty {
                    MiniAppCapabilityDivider()
                    MiniAppCapabilityStatusRow(row: .init(
                        title: "Board Summary",
                        subtitle: summary,
                        status: "host 写回",
                        tint: AmberTheme.accentCyan
                    ))
                }
            }

            if let actionMessage {
                MiniAppCapabilityNote(actionMessage)
                    .padding(.top, 8)
            }
        }
    }

    private func runnerSection(_ app: IOSMiniAppRecord) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "MiniApp Runner")

            VStack(alignment: .leading, spacing: 6) {
                Text("HTML（保存会创建新版本）")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                TextEditor(text: $generatedHtml)
                    .font(.system(size: 11, design: .monospaced))
                    .frame(height: 132)
                    .padding(6)
                    .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10))
                    .scrollContentBackground(.hidden)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            HStack(spacing: 8) {
                Button {
                    loadEditedHtml()
                } label: {
                    Label("加载并校验", systemImage: "play.circle.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(AmberTheme.accent, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)

                Button {
                    saveEditedHtml(app)
                } label: {
                    Label("保存新版本", systemImage: "tray.and.arrow.down")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 8)

            if let runnerError {
                Text(runnerError)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
            }

            if !loadedHtml.isEmpty {
                MiniAppRunnerWebView(
                    html: loadedHtml,
                    appId: app.id,
                    repository: repository,
                    policy: bridgePolicy,
                    apiKeyProvider: { settingsStore?.currentApiKey ?? "" },
                    onValidationError: { runnerError = $0 },
                    onBridgeLog: { bridgeLog = $0 },
                    onToast: { message in
                        actionMessage = "toast: \(message)"
                    }
                )
                .frame(height: 300)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
        }
    }

    private func grantsSection(_ app: IOSMiniAppRecord) -> some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Grant 状态")
            AmberFormGroup {
                if app.permissions.isEmpty {
                    MiniAppCapabilityStatusRow(row: .init(
                        title: "未声明权限",
                        subtitle: "bridge 只能使用 app.info、log、echo 这类无需 grant 的能力。",
                        status: "无",
                        tint: AmberTheme.muted
                    ))
                } else {
                    ForEach(Array(app.permissions.enumerated()), id: \.element) { index, permission in
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(permission)
                                    .font(.system(size: 15, weight: .semibold))
                                    .foregroundStyle(AmberTheme.foreground)
                                Text(grantDescription(permission))
                                    .font(.system(size: 12.5))
                                    .foregroundStyle(AmberTheme.muted)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            Menu {
                                Button {
                                    setGrant(appId: app.id, permission: permission, decision: .allow)
                                } label: {
                                    Label("允许", systemImage: "checkmark.circle")
                                }
                                Button(role: .destructive) {
                                    setGrant(appId: app.id, permission: permission, decision: .deny)
                                } label: {
                                    Label("拒绝", systemImage: "xmark.circle")
                                }
                            } label: {
                                Text(grantTitle(appId: app.id, permission: permission))
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(grantTint(appId: app.id, permission: permission))
                            }
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)

                        if index < app.permissions.count - 1 {
                            MiniAppCapabilityDivider()
                        }
                    }
                }
            }
        }
    }

    private func versionsSection(_ app: IOSMiniAppRecord) -> some View {
        let versions = repository.versions(appId: app.id)
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "版本历史")
            AmberFormGroup {
                ForEach(Array(versions.enumerated()), id: \.element.id) { index, version in
                    HStack(alignment: .top, spacing: 12) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("v\(version.versionNumber)")
                                .font(.system(size: 15, weight: .semibold))
                                .foregroundStyle(AmberTheme.foreground)
                            Text("\(version.changeNote ?? "MiniApp version") · \(dateText(version.createdAt)) · hash \(version.htmlHash.prefix(12))")
                                .font(.system(size: 12.5))
                                .foregroundStyle(AmberTheme.muted)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        Button {
                            restore(version: version)
                        } label: {
                            Image(systemName: "arrow.uturn.backward.circle")
                                .font(.system(size: 19, weight: .semibold))
                                .foregroundStyle(AmberTheme.accent)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("恢复 v\(version.versionNumber)")
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 11)

                    if index < versions.count - 1 {
                        MiniAppCapabilityDivider()
                    }
                }
            }
        }
    }

    private func auditSection(_ app: IOSMiniAppRecord) -> some View {
        let logs = repository.auditLogs(appId: app.id, limit: 6)
        return VStack(spacing: 0) {
            AmberSectionLabel(text: "Audit metadata")
            AmberFormGroup {
                if logs.isEmpty {
                    MiniAppCapabilityStatusRow(row: .init(
                        title: "暂无 bridge audit",
                        subtitle: "storage/sharedStore/search/fetch/host 写回等调用会记录 method、permission、payloadHash。",
                        status: "空",
                        tint: AmberTheme.muted
                    ))
                } else {
                    ForEach(Array(logs.enumerated()), id: \.element.id) { index, log in
                        MiniAppCapabilityStatusRow(row: .init(
                            title: log.method,
                            subtitle: "\(log.summary) · \(dateText(log.createdAt)) · payload \(log.payloadHash.prefix(12))",
                            status: log.permission,
                            tint: AmberTheme.accentCyan
                        ))
                        if index < logs.count - 1 {
                            MiniAppCapabilityDivider()
                        }
                    }
                }
            }
        }
    }

    private var bridgeLogSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "Bridge 日志")
            if bridgeLog.isEmpty {
                MiniAppCapabilityNote("暂无 bridge 消息。点击 WebView 中的小应用按钮后会显示最近日志。")
            } else {
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(bridgeLog.suffix(8).enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.system(size: 10, design: .monospaced))
                            .foregroundStyle(AmberTheme.muted2)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
        }
    }

    private var missingSection: some View {
        VStack(spacing: 0) {
            MiniAppCapabilityNote("repository 中没有 appId：\(appId)。它可能已被删除，或 store 读取失败。")
                .padding(.top, 12)
            TextEditor(text: .constant(Self.missingAppHtml(appId: appId)))
                .font(.system(size: 11, design: .monospaced))
                .frame(height: 180)
                .padding(6)
                .background(AmberTheme.surface2, in: RoundedRectangle(cornerRadius: 10))
                .scrollContentBackground(.hidden)
                .padding(.horizontal, 16)
                .padding(.top, 12)
        }
    }

    private var bridgePolicy: IOSMiniAppBridgePolicy {
        guard let sharedSettings else {
            return IOSMiniAppBridgePolicy()
        }
        let miniApp = sharedSettings.agentRuntime.miniApp
        return IOSMiniAppBridgePolicy(
            miniAppEnabled: miniApp.enabled,
            storageEnabled: true,
            toastEnabled: true,
            themeEnabled: true,
            networkEnabled: miniApp.networkEnabled,
            searchEnabled: miniApp.searchEnabled && sharedSettings.enableWebSearch,
            clipboardCopyEnabled: miniApp.clipboardCopyEnabled,
            boardSummaryUpdateEnabled: miniApp.boardSummaryUpdateEnabled,
            aiEnabled: miniApp.aiEnabled,
            sharedStoreEnabled: miniApp.sharedStoreEnabled,
            eventBusEnabled: miniApp.eventBusEnabled
        )
    }

    private func loadApp(markRun: Bool) {
        guard let app else {
            generatedHtml = Self.missingAppHtml(appId: appId)
            loadedHtml = ""
            return
        }
        generatedHtml = app.htmlContent
        loadedHtml = app.htmlContent
        runnerError = nil
        didInitialLoad = true
        guard markRun, !didMarkRun else { return }
        do {
            try repository.markRun(id: app.id)
            didMarkRun = true
        } catch {
            actionMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func loadEditedHtml() {
        do {
            try MiniAppHtmlValidator.validate(generatedHtml)
            loadedHtml = generatedHtml
            runnerError = nil
            actionMessage = "HTML 校验通过，已加载到 WKWebView。"
        } catch {
            runnerError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func saveEditedHtml(_ app: IOSMiniAppRecord) {
        do {
            guard let updated = try repository.saveNewVersion(
                appId: app.id,
                htmlContent: generatedHtml,
                changeNote: "Saved from iOS Runner"
            ) else { return }
            generatedHtml = updated.htmlContent
            loadedHtml = updated.htmlContent
            runnerError = nil
            actionMessage = "已保存 v\(updated.version)。"
        } catch {
            runnerError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func restore(version: IOSMiniAppVersionRecord) {
        do {
            guard let updated = try repository.restoreVersion(appId: version.appId, versionNumber: version.versionNumber) else { return }
            generatedHtml = updated.htmlContent
            loadedHtml = updated.htmlContent
            runnerError = nil
            actionMessage = "已从 v\(version.versionNumber) 恢复为 v\(updated.version)。"
        } catch {
            actionMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func setGrant(appId: String, permission: String, decision: IOSMiniAppGrantDecision) {
        do {
            try repository.setGrant(appId: appId, permission: permission, decision: decision)
            actionMessage = "\(permission) 已\(decision.title)。"
        } catch {
            actionMessage = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func grantTitle(appId: String, permission: String) -> String {
        repository.grantDecision(appId: appId, permission: permission)?.title ?? "未设置"
    }

    private func grantTint(appId: String, permission: String) -> Color {
        switch repository.grantDecision(appId: appId, permission: permission) {
        case .allow:
            return AmberTheme.accentGreen
        case .deny:
            return AmberTheme.accentRed
        case nil:
            return AmberTheme.accentAmber
        }
    }

    private func grantDescription(_ permission: String) -> String {
        switch IOSMiniAppPermission(rawValue: permission) {
        case .network:
            return "仅 HTTPS fetch，受 MiniApp 设置控制。"
        case .search:
            return "使用 iOS DuckDuckGo Lite 搜索执行器，受全局搜索开关控制。"
        case .aiGenerate:
            return "需要 API Key；iOS MiniApp AI bridge 仍返回诚实错误。"
        case .clipboardCopy:
            return "只写剪贴板，不读取。"
        case .hostUpdateBoardSummary:
            return "写入当前 MiniApp 的 boardSummary metadata。"
        case .sharedStore:
            return "只允许自身 appId namespace。"
        case .eventBus:
            return "仅 Runner 生命周期内的本地事件。"
        case nil:
            return "未知权限会被 bridge 拒绝。"
        default:
            return "调用前必须显式允许；拒绝后 bridge 会返回错误。"
        }
    }

    private func dateText(_ millis: Int64) -> String {
        Date(timeIntervalSince1970: Double(millis) / 1_000)
            .formatted(date: .abbreviated, time: .shortened)
    }
}

#Preview {
    NavigationStack {
        MiniAppRunnerView(appId: IOSMiniAppFixtures.sampleId)
    }
}

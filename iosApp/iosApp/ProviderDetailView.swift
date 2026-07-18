import SwiftUI
import Shared

struct ProviderDetailView: View {
    @Bindable var settingsStore: SettingsStore
    let providerRegistry: ProviderRegistryStore
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss

    let providerId: String

    @State private var selectedTab: ProviderDetailTab = .config
    @State private var alert: ProviderDetailAlert?
    @State private var connectionStatus: ProviderConnectionStatus = .idle
    @State private var fetchState: ProviderModelFetchState = .idle
    @State private var availableModels: [Model] = []
    @State private var modelDraft: ProviderModelDraft?

    @State private var draftName = ""
    @State private var draftEnabled = true
    @State private var draftApiKey = ""
    @State private var draftBaseURL = ""
    @State private var draftChatPath = "/chat/completions"
    @State private var draftUseResponseAPI = false
    @State private var draftPromptCaching = false
    @State private var showCodexLogin = false
    @State private var showGrokLogin = false

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                chrome

                ZStack {
                    if provider == nil {
                        missingProviderPanel
                    } else {
                        switch selectedTab {
                        case .config:
                            configPanel
                                .transition(.move(edge: .leading).combined(with: .opacity))
                        case .models:
                            modelsPanel
                                .transition(.move(edge: .trailing).combined(with: .opacity))
                        }
                    }
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear(perform: loadDraft)
        .onChange(of: sharedSettings.revision) { _, _ in loadDraft() }
        .alert(item: $alert, content: makeAlert)
        .sheet(item: $modelDraft) { draft in
            ProviderModelEditorSheet(
                draft: draft,
                onSave: saveModelDraft
            )
        }
        .sheet(isPresented: $showCodexLogin) {
            CodexLoginView(
                providerId: providerId,
                onAuthModeChange: { mode in
                    _ = sharedSettings.setOpenAIAuthMode(providerId: providerId, authMode: mode)
                    if isCurrentProvider {
                        sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                    }
                },
                persistModels: { models in
                    _ = sharedSettings.updateProviderChatModels(providerId: providerId, models: models)
                    _ = sharedSettings.upsertProviderImageModel(
                        providerId: providerId,
                        modelId: IOSCodexOAuthConstants.imageModelId,
                        displayName: "Codex 生图 (ChatGPT)"
                    )
                    if isCurrentProvider {
                        sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                    }
                }
            )
        }
        .sheet(isPresented: $showGrokLogin) {
            GrokWebLoginView(
                providerId: providerId,
                providerBackup: grokProviderBackup,
                onSignedIn: {
                    let shouldSeedModels = provider?.models.contains { $0.type == ModelType.chat } != true
                    _ = sharedSettings.updateProviderEndpoint(
                        providerId: providerId,
                        baseUrl: IOSGrokWebConstants.webBaseUrl,
                        chatCompletionsPath: "/conversations/new",
                        useResponseApi: true,
                        promptCaching: false
                    )
                    if shouldSeedModels {
                        _ = sharedSettings.updateProviderChatModels(
                            providerId: providerId,
                            models: IOSGrokWebConstants.fallbackModels
                        )
                    }
                    if isCurrentProvider {
                        sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                    }
                    loadDraft()
                },
                onLoggedOut: { backup in
                    if let backup {
                        _ = sharedSettings.updateProviderEndpoint(
                            providerId: providerId,
                            baseUrl: backup.baseUrl,
                            chatCompletionsPath: backup.chatCompletionsPath,
                            useResponseApi: backup.useResponseApi,
                            promptCaching: false
                        )
                    }
                    if isCurrentProvider {
                        sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                    }
                    loadDraft()
                }
            )
        }
    }

    private var provider: ProviderSetting? {
        _ = sharedSettings.revision
        return sharedSettings.snapshot.providers.first { $0.id.description() == providerId }
    }

    private var providerName: String {
        provider?.name ?? "服务商"
    }

    private var currentModel: Model? {
        _ = sharedSettings.revision
        return sharedSettings.snapshot.getCurrentChatModel()
    }

    private var isCurrentProvider: Bool {
        guard let provider, let currentModel else { return false }
        return currentModel.findProvider(providers: sharedSettings.snapshot.providers, checkOverwrite: true)?.id == provider.id
    }

    private var protocolOption: ProviderProtocolOption? {
        ProviderProtocolOption.option(for: provider)
    }

    private var chatModels: [Model] {
        provider?.models.filter { $0.type == ModelType.chat } ?? []
    }

    private var chrome: some View {
        VStack(spacing: 10) {
            HStack {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回服务商", size: 44, symbolSize: 20) {
                    dismiss()
                }

                Spacer()

                Text(providerName)
                    .font(.title2.weight(.bold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)

                Spacer()

                AmberGlassIconButton(
                    systemImage: "checkmark",
                    accessibilityLabel: "保存服务商",
                    size: 44,
                    symbolSize: 17,
                    tint: AmberTheme.accent,
                    prominent: true
                ) {
                    commitPendingTextInput {
                        _ = saveConfig(showSuccess: true)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)

            ProviderSegmentedControl(selection: $selectedTab)
                .padding(.horizontal, 16)
                .padding(.bottom, 10)
        }
    }

    private var missingProviderPanel: some View {
        VStack(spacing: 10) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 24, weight: .semibold))
                .foregroundStyle(AmberTheme.accentAmber)
            Text("找不到这个服务商")
                .font(.headline)
                .foregroundStyle(AmberTheme.foreground)
            Text("它可能已经被删除，请返回服务商列表重新选择。")
                .font(.footnote)
                .foregroundStyle(AmberTheme.muted)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(24)
    }

    private var configPanel: some View {
        ScrollView {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "配置")
                AmberFormGroup {
                    ProviderToggleRow(title: "启用", isOn: $draftEnabled)
                    ProviderDetailDivider()
                    ProviderEditableTextFieldRow(
                        title: "名称",
                        text: $draftName,
                        placeholder: "Provider"
                    )
                    ProviderDetailDivider()
                    protocolRow
                }

                grokSection

                AmberSectionLabel(text: "连接")
                AmberFormGroup {
                    ProviderEditableTextFieldRow(
                        title: "API Key",
                        text: $draftApiKey,
                        placeholder: protocolOption == .anthropic ? "sk-ant-..." : "sk-...",
                        isSecure: true,
                        monospace: true
                    )
                    ProviderDetailDivider()
                    ProviderEditableTextFieldRow(
                        title: "API 地址",
                        text: $draftBaseURL,
                        placeholder: protocolOption?.defaultBaseURL ?? "https://api.openai.com/v1",
                        monospace: true
                    )
                    if provider is ProviderSetting.OpenAI {
                        ProviderDetailDivider()
                        ProviderEditableTextFieldRow(
                            title: "路径",
                            text: $draftChatPath,
                            placeholder: "/chat/completions",
                            monospace: true
                        )
                    }
                }

                legacyKeyImportSection

                codexSection

                if provider is ProviderSetting.Claude {
                    AmberSectionLabel(text: "选项")
                    AmberFormGroup {
                        ProviderToggleRow(title: "Prompt Caching", isOn: $draftPromptCaching)
                    }
                }

                configActions
            }
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    private var protocolRow: some View {
        Menu {
            ForEach(ProviderProtocolOption.switchableCases, id: \.self) { option in
                Button {
                    switchProtocol(to: option)
                } label: {
                    if option == protocolOption {
                        Label(option.title, systemImage: "checkmark")
                    } else {
                        Text(option.title)
                    }
                }
            }
        } label: {
            ProviderRowContent(
                title: "接口协议",
                subtitle: "",
                value: protocolOption?.title ?? "待移植",
                valueStyle: .body,
                showsChevron: provider is ProviderSetting.OpenAI || provider is ProviderSetting.Claude
            )
        }
        .buttonStyle(.plain)
        .disabled(!(provider is ProviderSetting.OpenAI || provider is ProviderSetting.Claude))
    }

    /// Codex (ChatGPT account) sign-in entry, shown for the official OpenAI brand
    /// or a provider already switched to Codex OAuth.
    @ViewBuilder
    private var codexSection: some View {
        if let openAI = provider as? ProviderSetting.OpenAI,
           openAI.brand == OpenAIBrand.openai || openAI.authMode == OpenAIAuthMode.codexOauth {
            AmberSectionLabel(text: "ChatGPT 登录 (Codex)")
            AmberFormGroup {
                Button {
                    commitPendingTextInput {
                        guard saveConfig(showSuccess: false) else { return }
                        showCodexLogin = true
                    }
                } label: {
                    ProviderRowContent(
                        title: codexSignedIn ? "已登录 Codex" : "用 ChatGPT 登录",
                        subtitle: codexSubtitle,
                        value: codexSignedIn ? "管理" : "登录",
                        valueStyle: .accent,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var codexSignedIn: Bool {
        _ = sharedSettings.revision
        return IOSCodexAuthStore.load(providerId: providerId) != nil
    }

    private var codexSubtitle: String {
        if let tokens = IOSCodexAuthStore.load(providerId: providerId) {
            let email = tokens.email ?? ""
            let plan = tokens.planType.map { " · \($0)" } ?? ""
            return email.isEmpty ? "已登录\(plan)" : "\(email)\(plan)"
        }
        return "用 ChatGPT Plus/Pro/Team 账号,无需 API Key"
    }

    @ViewBuilder
    private var grokSection: some View {
        if IOSGrokWebProviderResolver.isXAIProvider(provider) {
            AmberSectionLabel(text: "Grok 登录 (Web)")
            AmberFormGroup {
                Button {
                    commitPendingTextInput {
                        guard saveConfig(showSuccess: false) else { return }
                        showGrokLogin = true
                    }
                } label: {
                    ProviderRowContent(
                        title: grokSignedIn ? "已登录 Grok" : "用 grok.com 登录",
                        subtitle: grokSubtitle,
                        value: grokSignedIn ? "管理" : "登录",
                        valueStyle: .accent,
                        showsChevron: true
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var grokSignedIn: Bool {
        _ = sharedSettings.revision
        guard let session = IOSGrokWebAuthStore.load(providerId: providerId) else { return false }
        return session.isInvalidated != true
            && IOSGrokWebCookieValidator.hasSSOCookie(in: session.cookieHeader)
    }

    private var grokSubtitle: String {
        if grokSignedIn {
            return "使用 grok.com Cookie 私有链路，仅支持文本聊天"
        }
        return "使用 Grok Web 账号，无需 xAI API Key"
    }

    private var grokProviderBackup: IOSGrokWebProviderBackup? {
        guard let openAI = provider as? ProviderSetting.OpenAI else { return nil }
        return IOSGrokWebProviderBackup(
            baseUrl: openAI.baseUrl,
            chatCompletionsPath: openAI.chatCompletionsPath,
            useResponseApi: openAI.useResponseApi
        )
    }

    @ViewBuilder
    private var legacyKeyImportSection: some View {
        let oldKey = settingsStore.currentApiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let providerKey = apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines)
        if !oldKey.isEmpty, providerKey.isEmpty {
            Button {
                draftApiKey = oldKey
                saveConfig(showSuccess: false)
                alert = .legacyKeyImported
            } label: {
                ProviderRowContent(
                    title: "导入旧 API Key",
                    subtitle: "",
                    value: "导入",
                    valueStyle: .accent,
                    showsChevron: false
                )
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)
            .padding(.top, 10)
        }
    }

    private var configActions: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button {
                    commitPendingTextInput {
                        testConnection()
                    }
                } label: {
                    ProviderActionRow(
                        systemImage: connectionStatus.isTesting ? "hourglass" : "bolt.horizontal",
                        title: connectionStatus.isTesting ? "正在测试" : "测试连接",
                        tint: AmberTheme.accentAmber
                    )
                }
                .buttonStyle(.plain)
                .disabled(connectionStatus.isTesting)
                .opacity(connectionStatus.isTesting ? 0.65 : 1)

                if sharedSettings.canRemoveProvider(providerId: providerId) {
                    Button(role: .destructive) {
                        alert = .deleteProvider(providerName)
                    } label: {
                        ProviderActionRow(
                            systemImage: "trash",
                            title: "删除",
                            tint: .red
                        )
                    }
                    .buttonStyle(.plain)
                }
            }

            if let message = connectionStatus.message {
                ProviderConnectionResultRow(status: connectionStatus, message: message)
            }
        }
        .padding(.top, 16)
        .padding(.horizontal, 16)
    }

    private func makeAlert(_ alert: ProviderDetailAlert) -> Alert {
        switch alert {
        case .deleteProvider:
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                primaryButton: .destructive(Text("删除"), action: deleteProvider),
                secondaryButton: .cancel(Text("取消"))
            )
        default:
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private func deleteProvider() {
        guard sharedSettings.removeProvider(providerId: providerId) else {
            alert = .providerDeleteFailed
            return
        }
        sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
        dismiss()
    }

    private var modelsPanel: some View {
        ScrollView {
            VStack(spacing: 0) {
                AmberSectionLabel(text: "已启用模型")
                AmberFormGroup {
                    if chatModels.isEmpty {
                        ProviderStaticRow(
                            title: "没有模型",
                            subtitle: "可自动获取模型，也可手动添加服务商文档中的 Model ID。",
                            value: "空"
                        )
                    } else {
                        ForEach(Array(chatModels.enumerated()), id: \.offset) { index, model in
                            let isCurrent = model.id == currentModel?.id
                            ProviderModelRow(
                                systemImage: isCurrent ? "checkmark.circle.fill" : "cpu",
                                name: displayName(for: model),
                                badge: isCurrent ? "当前" : model.modelId,
                                summary: modelSummary(model),
                                isCurrent: isCurrent,
                                onEdit: {
                                    modelDraft = ProviderModelDraft(model: model)
                                },
                                onSetCurrent: {
                                    setCurrent(model)
                                },
                                onDelete: {
                                    deleteModel(model)
                                }
                            )
                            if index < chatModels.count - 1 {
                                ProviderDetailDivider()
                            }
                        }
                    }
                }

                modelActions

                if !availableModels.isEmpty {
                    AmberSectionLabel(text: "可用模型")
                    AmberFormGroup {
                        ForEach(Array(availableModels.enumerated()), id: \.offset) { index, model in
                            let enabledModel = chatModels.first { $0.modelId == model.modelId }
                            // Only "current" when the model is actually enabled AND is the
                            // current one. Guard the nil==nil trap: an un-enabled model
                            // (enabledModel == nil) with no current model (currentModel == nil)
                            // would otherwise compare nil == nil → true, mislabel every row
                            // "当前" and disable it, making models impossible to add.
                            let isCurrent = enabledModel != nil && enabledModel?.id == currentModel?.id
                            Button {
                                if let enabledModel {
                                    setCurrent(enabledModel)
                                } else {
                                    addFetchedModel(model)
                                }
                            } label: {
                                ProviderRowContent(
                                    // Drop the duplicate ID line when the model has no
                                    // custom display name (name == id) — the big title
                                    // already shows it; printing it twice is pure clutter.
                                    title: displayName(for: model),
                                    subtitle: displayName(for: model) == model.modelId ? "" : model.modelId,
                                    value: isCurrent ? "当前" : (enabledModel == nil ? "添加" : "使用"),
                                    valueStyle: isCurrent ? .body : .accent,
                                    showsChevron: false
                                )
                            }
                            .buttonStyle(.plain)
                            .disabled(isCurrent)

                            if index < availableModels.count - 1 {
                                ProviderDetailDivider()
                            }
                        }
                    }
                }
            }
            .padding(.bottom, 36)
        }
        .scrollIndicators(.hidden)
    }

    private var modelActions: some View {
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button {
                    fetchModels()
                } label: {
                    ProviderActionRow(
                        systemImage: fetchState.isLoading ? "hourglass" : "arrow.down.circle",
                        title: fetchState.isLoading ? "正在获取" : "自动获取",
                        tint: AmberTheme.accent
                    )
                }
                .buttonStyle(.plain)
                .disabled(fetchState.isLoading)

                Button {
                    modelDraft = ProviderModelDraft()
                } label: {
                    ProviderActionRow(systemImage: "plus.circle", title: "手动添加", tint: AmberTheme.accentAmber)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)

            if let message = fetchState.message {
                ProviderDetailFooter(message)
            }
        }
    }

    private func loadDraft() {
        guard let provider else { return }
        draftName = provider.name
        draftEnabled = provider.enabled
        if let openAI = provider as? ProviderSetting.OpenAI {
            draftApiKey = openAI.apiKey
            draftBaseURL = openAI.baseUrl
            draftChatPath = openAI.chatCompletionsPath
            draftUseResponseAPI = openAI.useResponseApi
            draftPromptCaching = false
        } else if let claude = provider as? ProviderSetting.Claude {
            draftApiKey = claude.apiKey
            draftBaseURL = claude.baseUrl
            draftChatPath = "/messages"
            draftUseResponseAPI = false
            draftPromptCaching = claude.promptCaching
        } else if let google = provider as? ProviderSetting.Google {
            draftApiKey = google.apiKey
            draftBaseURL = google.baseUrl
            draftChatPath = ""
            draftUseResponseAPI = false
            draftPromptCaching = false
        }
    }

    @discardableResult
    private func saveConfig(showSuccess: Bool, forceEnabled: Bool = false) -> Bool {
        guard let provider else { return false }
        let name = draftName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? provider.name : draftName.trimmingCharacters(in: .whitespacesAndNewlines)
        let baseURL = normalizedBaseURL(draftBaseURL)
        guard isValidHTTPBaseURL(baseURL) else {
            alert = .invalidBaseURL
            return false
        }
        let path = draftChatPath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "/chat/completions" : draftChatPath.trimmingCharacters(in: .whitespacesAndNewlines)
        let enabled = forceEnabled || draftEnabled
        _ = sharedSettings.updateProviderBasics(providerId: providerId, name: name, enabled: enabled)
        if forceEnabled {
            draftEnabled = true
        }
        _ = sharedSettings.updateProviderApiKey(providerId: providerId, apiKey: draftApiKey.trimmingCharacters(in: .whitespacesAndNewlines))
        _ = sharedSettings.updateProviderEndpoint(
            providerId: providerId,
            baseUrl: baseURL,
            chatCompletionsPath: path,
            useResponseApi: draftUseResponseAPI,
            promptCaching: draftPromptCaching
        )
        if isCurrentProvider {
            sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
        }
        if showSuccess {
            alert = .saved
        }
        return true
    }

    private func commitPendingTextInput(then action: @escaping () -> Void) {
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
        DispatchQueue.main.async(execute: action)
    }

    private func switchProtocol(to option: ProviderProtocolOption) {
        guard option != protocolOption else { return }
        guard provider is ProviderSetting.OpenAI || provider is ProviderSetting.Claude else {
            alert = .unsupportedProtocol
            return
        }
        guard saveConfig(showSuccess: false) else { return }
        guard sharedSettings.switchProviderProtocol(providerId: providerId, protocolOption: option) != nil else {
            alert = .protocolSwitchFailed
            return
        }
        connectionStatus = .idle
        availableModels = []
        fetchState = .idle
        loadDraft()
    }

    private func fetchModels(
        saveBeforeFetch: Bool = true,
        revealModels: Bool = false,
        reportsConnectionStatus: Bool = false
    ) {
        if saveBeforeFetch {
            guard saveConfig(showSuccess: false) else { return }
        }
        guard let provider else { return }
        if reportsConnectionStatus {
            connectionStatus = .testing
        }
        if revealModels {
            selectedTab = .models
        }
        guard ChatProviderConfiguration.supportsChatStreaming(provider) else {
            let message = "这个 Provider 类型的 iOS 模型获取尚未移植。"
            fetchState = .failure(message)
            if reportsConnectionStatus { connectionStatus = .failure(message) }
            return
        }
        guard !apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            let message = ChatConfigurationIssue.missingAPIKey.message
            fetchState = .failure(message)
            if reportsConnectionStatus { connectionStatus = .failure(message) }
            return
        }
        fetchState = .loading
        Task { @MainActor in
            do {
                let models: [Model]
                if let openAI = provider as? ProviderSetting.OpenAI {
                    models = try await OpenAIKmpProvider().listModels(providerSetting: openAI)
                } else if let claude = provider as? ProviderSetting.Claude {
                    models = try await ClaudeKmpProvider().listModels(providerSetting: claude)
                } else {
                    models = []
                }
                availableModels = models
                let message = models.isEmpty
                    ? "连接成功，但服务商没有返回可列出的模型。可手动添加 Model ID。"
                    : "连接成功，服务商返回 \(models.count) 个模型。"
                fetchState = .success(message)
                if reportsConnectionStatus { connectionStatus = .success(message) }
            } catch {
                let message = ChatViewModel.userFacingGenerationError(error.localizedDescription, modelId: nil)
                fetchState = .failure(message)
                if reportsConnectionStatus { connectionStatus = .failure(message) }
            }
        }
    }

    private func addFetchedModel(_ model: Model) {
        guard saveConfig(showSuccess: false, forceEnabled: true) else { return }
        let displayName = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? model.modelId : model.displayName
        guard let updatedProvider = sharedSettings.upsertProviderChatModel(
            providerId: providerId,
            modelUuid: nil,
            modelId: model.modelId,
            displayName: displayName,
            contextWindowTokens: intValue(model.contextWindowTokens),
            modelType: model.type,
            headers: model.customHeaders.map { ($0.name, $0.value) }
        ) else { return }
        guard let savedModel = updatedProvider.models.first(where: { $0.type == .chat && $0.modelId == model.modelId }) else { return }
        selectCurrent(savedModel, showAlert: true)
    }

    @discardableResult
    private func saveModelDraft(_ draft: ProviderModelDraft) -> Bool {
        let modelId = draft.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !modelId.isEmpty else {
            alert = .modelRequired
            return false
        }
        let wasCurrent = currentModel?.id.description() == draft.modelUuid
        let shouldSelect = draft.modelUuid == nil || wasCurrent
        guard saveConfig(showSuccess: false, forceEnabled: shouldSelect) else { return false }
        let displayName = draft.displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? modelId : draft.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let updatedProvider = sharedSettings.upsertProviderChatModel(
            providerId: providerId,
            modelUuid: draft.modelUuid,
            modelId: modelId,
            displayName: displayName,
            contextWindowTokens: draft.contextWindowTokens,
            modelType: draft.type,
            headers: draft.headers.map { ($0.name, $0.value) }
        ) else { return false }
        if shouldSelect,
           let savedModel = updatedProvider.models.first(where: { $0.type == .chat && $0.modelId == modelId }) {
            selectCurrent(savedModel, showAlert: draft.modelUuid == nil)
        }
        modelDraft = nil
        return true
    }

    private func setCurrent(_ model: Model) {
        guard saveConfig(showSuccess: false, forceEnabled: true) else { return }
        selectCurrent(model, showAlert: true)
    }

    private func selectCurrent(_ model: Model, showAlert: Bool) {
        sharedSettings.setCurrentChatModelId(model.id.description())
        sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
        if showAlert {
            alert = .currentModelSet(model.modelId)
        }
    }

    private func deleteModel(_ model: Model) {
        _ = sharedSettings.removeProviderChatModel(providerId: providerId, modelUuid: model.id.description())
        if currentModel?.id == model.id {
            alert = .currentModelDeleted
        }
    }

    private func testConnection() {
        guard saveConfig(showSuccess: false) else { return }
        guard let provider else { return }
        guard ChatProviderConfiguration.supportsChatStreaming(provider) else {
            connectionStatus = .failure(ChatConfigurationIssue.unsupportedProvider.message)
            return
        }
        guard apiKey(of: provider).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            connectionStatus = .failure(ChatConfigurationIssue.missingAPIKey.message)
            return
        }
        guard isValidHTTPBaseURL(baseURL(of: provider)) else {
            connectionStatus = .failure(ChatConfigurationIssue.invalidBaseURL.message)
            return
        }
        fetchModels(saveBeforeFetch: false, reportsConnectionStatus: true)
    }

    private func displayName(for model: Model) -> String {
        let name = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? model.modelId : name
    }

    private func modelSummary(_ model: Model) -> String {
        var parts: [String] = [model.modelId]
        if let context = intValue(model.contextWindowTokens) {
            parts.append("\(context) ctx")
        }
        if !model.customHeaders.isEmpty {
            parts.append("\(model.customHeaders.count) headers")
        }
        return parts.joined(separator: " · ")
    }

    private func apiKey(of provider: ProviderSetting?) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.apiKey }
        if let claude = provider as? ProviderSetting.Claude { return claude.apiKey }
        if let google = provider as? ProviderSetting.Google { return google.apiKey }
        return ""
    }

    private func baseURL(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.baseUrl }
        if let claude = provider as? ProviderSetting.Claude { return claude.baseUrl }
        if let google = provider as? ProviderSetting.Google { return google.baseUrl }
        return ""
    }

    private func normalizedBaseURL(_ value: String) -> String {
        var baseURL = value.trimmingCharacters(in: .whitespacesAndNewlines)
        while baseURL.hasSuffix("/") {
            baseURL.removeLast()
        }
        return baseURL
    }

    private func isValidHTTPBaseURL(_ value: String) -> Bool {
        IOSProviderEndpointPolicy.isValidBaseURL(value)
    }

    private func intValue(_ value: KotlinInt?) -> Int? {
        value.map { Int(truncating: $0) }
    }
}

private enum ProviderDetailTab: String, CaseIterable, Identifiable {
    case config
    case models

    var id: String { rawValue }

    var title: String {
        switch self {
        case .config: "配置"
        case .models: "模型"
        }
    }
}

private enum ProviderConnectionStatus: Equatable {
    case idle
    case testing
    case success(String)
    case failure(String)

    var isTesting: Bool {
        self == .testing
    }

    var message: String? {
        switch self {
        case .idle, .testing:
            nil
        case .success(let message), .failure(let message):
            message
        }
    }
}

private enum ProviderModelFetchState: Equatable {
    case idle
    case loading
    case success(String)
    case failure(String)

    var isLoading: Bool {
        self == .loading
    }

    var message: String? {
        switch self {
        case .idle, .loading:
            nil
        case .success(let message), .failure(let message):
            message
        }
    }
}

private enum ProviderDetailAlert: Identifiable {
    case saved
    case invalidBaseURL
    case protocolSwitchFailed
    case unsupportedProtocol
    case modelRequired
    case currentModelSet(String)
    case currentModelDeleted
    case legacyKeyImported
    case deleteProvider(String)
    case providerDeleteFailed

    var id: String {
        switch self {
        case .saved: "saved"
        case .invalidBaseURL: "invalid-base-url"
        case .protocolSwitchFailed: "protocol-switch-failed"
        case .unsupportedProtocol: "unsupported-protocol"
        case .modelRequired: "model-required"
        case .currentModelSet(let model): "current-model-\(model)"
        case .currentModelDeleted: "current-model-deleted"
        case .legacyKeyImported: "legacy-key-imported"
        case .deleteProvider: "delete-provider"
        case .providerDeleteFailed: "provider-delete-failed"
        }
    }

    var title: String {
        switch self {
        case .saved: "已保存"
        case .invalidBaseURL: "API 地址无效"
        case .protocolSwitchFailed: "协议切换失败"
        case .unsupportedProtocol: "暂不支持"
        case .modelRequired: "需要 Model ID"
        case .currentModelSet: "已设为当前"
        case .currentModelDeleted: "当前模型已删除"
        case .legacyKeyImported: "已导入"
        case .deleteProvider: "删除服务商？"
        case .providerDeleteFailed: "无法删除服务商"
        }
    }

    var message: String {
        switch self {
        case .saved:
            "服务商配置已写入当前设置。"
        case .invalidBaseURL:
            "请输入 HTTPS 地址，或使用 http://IP:端口形式的 API 地址。"
        case .protocolSwitchFailed:
            "没有找到可切换的服务商配置。"
        case .unsupportedProtocol:
            "这个 Provider 类型的 iOS 聊天执行器尚未移植。"
        case .modelRequired:
            "请填写服务商文档中的 Model ID。"
        case .currentModelSet(let model):
            "新的聊天会默认使用 \(model)。"
        case .currentModelDeleted:
            "你删除的是当前聊天模型，请在模型页重新选择一个当前模型。"
        case .legacyKeyImported:
            "旧 API Key 已写入当前服务商。"
        case .deleteProvider(let name):
            "\(name) 的配置、模型和 API Key 将被删除，此操作无法撤销。"
        case .providerDeleteFailed:
            "内置服务商不能删除；当前服务商没有可切换的备用聊天模型时也不能删除。"
        }
    }
}

private struct ProviderModelDraft: Identifiable {
    let id = UUID()
    var modelUuid: String?
    var modelId: String
    var displayName: String
    var contextWindowText: String
    var type: ModelType
    var headers: [ProviderHeaderDraft]

    init() {
        self.modelUuid = nil
        self.modelId = ""
        self.displayName = ""
        self.contextWindowText = ""
        self.type = .chat
        self.headers = []
    }

    init(model: Model) {
        self.modelUuid = model.id.description()
        self.modelId = model.modelId
        self.displayName = model.displayName
        if let context = model.contextWindowTokens {
            self.contextWindowText = "\(Int(truncating: context))"
        } else {
            self.contextWindowText = ""
        }
        self.type = model.type
        self.headers = model.customHeaders.map { ProviderHeaderDraft(name: $0.name, value: $0.value) }
    }

    var contextWindowTokens: Int? {
        let trimmed = contextWindowText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return Int(trimmed.replacingOccurrences(of: ",", with: ""))
    }
}

private struct ProviderHeaderDraft: Identifiable, Equatable {
    let id = UUID()
    var name: String
    var value: String
}

private struct ProviderSegmentedControl: View {
    @Binding var selection: ProviderDetailTab
    @Namespace private var selectionIndicator

    var body: some View {
        HStack(spacing: 4) {
            ForEach(ProviderDetailTab.allCases) { tab in
                Button {
                    withAnimation(.smooth(duration: 0.2)) {
                        selection = tab
                    }
                } label: {
                    Text(tab.title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(selection == tab ? AmberTheme.foreground : AmberTheme.foreground2)
                        .frame(maxWidth: .infinity)
                        .frame(height: 38)
                        .background {
                            if selection == tab {
                                RoundedRectangle(cornerRadius: 11, style: .continuous)
                                    .fill(AmberTheme.background.opacity(0.92))
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 11, style: .continuous)
                                            .stroke(AmberTheme.borderSoft.opacity(0.9), lineWidth: 0.5)
                                    }
                                    .matchedGeometryEffect(id: "selection", in: selectionIndicator)
                            }
                        }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(4)
        .background(
            AmberTheme.surface.opacity(0.72),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }
}

private struct ProviderModelEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: ProviderModelDraft
    let onSave: (ProviderModelDraft) -> Bool

    init(draft: ProviderModelDraft, onSave: @escaping (ProviderModelDraft) -> Bool) {
        self._draft = State(initialValue: draft)
        self.onSave = onSave
    }

    // Custom segmented control (Kotlin `ModelType` isn't Hashable in Swift, so a
    // SwiftUI Picker can't bind to it; compare with `==` instead).
    @ViewBuilder
    private func modelTypeButton(_ title: String, _ type: ModelType) -> some View {
        let selected = draft.type == type
        Button {
            draft.type = type
        } label: {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .foregroundStyle(selected ? AmberTheme.foreground : AmberTheme.muted)
                .frame(maxWidth: .infinity)
                .frame(height: 34)
                .background(
                    selected ? AmberTheme.surface : Color.clear,
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous)
                )
        }
        .buttonStyle(.plain)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                AmberTheme.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 0) {
                        AmberSectionLabel(text: "模型")
                        AmberFormGroup {
                            ProviderEditableTextFieldRow(
                                title: "Model ID",
                                text: $draft.modelId,
                                placeholder: "例如 deepseek-chat",
                                monospace: true
                            )
                            ProviderDetailDivider()
                            ProviderEditableTextFieldRow(
                                title: "显示名称",
                                text: $draft.displayName,
                                placeholder: "留空时使用 Model ID"
                            )
                            ProviderDetailDivider()
                            VStack(alignment: .leading, spacing: 8) {
                                Text("类型")
                                    .font(.subheadline)
                                    .foregroundStyle(AmberTheme.muted)
                                HStack(spacing: 0) {
                                    modelTypeButton("聊天", .chat)
                                    modelTypeButton("生图", .image)
                                    modelTypeButton("嵌入", .embedding)
                                }
                                .padding(3)
                                .background(
                                    AmberTheme.surface2.opacity(0.88),
                                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                                )
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 12)
                            ProviderDetailDivider()
                            ProviderEditableTextFieldRow(
                                title: "上下文",
                                text: $draft.contextWindowText,
                                placeholder: "例如 128000",
                                monospace: true
                            )
                        }

                        AmberSectionLabel(text: "Headers")
                        AmberFormGroup {
                            if draft.headers.isEmpty {
                                ProviderStaticRow(title: "自定义 Header", subtitle: "当前没有模型级请求头。", value: "无")
                            } else {
                                ForEach($draft.headers) { $header in
                                    VStack(spacing: 8) {
                                        ProviderEditableTextFieldRow(
                                            title: "名称",
                                            text: $header.name,
                                            placeholder: "Header name",
                                            monospace: true
                                        )
                                        ProviderEditableTextFieldRow(
                                            title: "值",
                                            text: $header.value,
                                            placeholder: "Header value",
                                            monospace: true
                                        )
                                    }
                                    .padding(.vertical, 6)
                                }
                            }
                        }

                        Button {
                            draft.headers.append(ProviderHeaderDraft(name: "", value: ""))
                        } label: {
                            ProviderActionRow(systemImage: "plus.circle", title: "添加 Header", tint: AmberTheme.accent)
                        }
                        .buttonStyle(.plain)
                        .padding(.horizontal, 16)
                        .padding(.top, 16)
                    }
                    .padding(.bottom, 30)
                }
            }
            .navigationTitle(draft.modelUuid == nil ? "添加模型" : "编辑模型")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        if onSave(draft) {
                            dismiss()
                        }
                    }
                }
            }
        }
    }
}

private struct ProviderActionRow: View {
    let systemImage: String
    let title: String
    let tint: Color

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 28, height: 28)
                .background(tint.opacity(0.11), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(AmberTheme.foreground2)
            Spacer()
        }
        .frame(maxWidth: .infinity)
        .frame(height: 50)
        .padding(.horizontal, 12)
        .background(tint.opacity(0.075), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(tint.opacity(0.13), lineWidth: 0.5)
        }
    }
}

private struct ProviderDetailDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct ProviderStaticRow: View {
    let title: String
    let subtitle: String
    let value: String
    var valueStyle: ProviderRowValueStyle = .body

    var body: some View {
        ProviderRowContent(title: title, subtitle: subtitle, value: value, valueStyle: valueStyle, showsChevron: false)
    }
}

private struct ProviderEditableTextFieldRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var isSecure = false
    var monospace = false

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(width: 86, alignment: .leading)

            Group {
                if isSecure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text, axis: .vertical)
                }
            }
            .font(monospace ? .system(size: 13, weight: .regular, design: .monospaced) : .body)
            .foregroundStyle(AmberTheme.foreground)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            .multilineTextAlignment(.trailing)
        }
        .frame(minHeight: 50)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct ProviderToggleRow: View {
    let title: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
        }
        .toggleStyle(.switch)
        .frame(minHeight: 50)
        .padding(.horizontal, 14)
    }
}

private struct ProviderConnectionResultRow: View {
    let status: ProviderConnectionStatus
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(color)
            Text(message)
                .font(.footnote)
                .foregroundStyle(AmberTheme.foreground2)
                .fixedSize(horizontal: false, vertical: true)
            Spacer()
        }
        .padding(12)
        .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
    }

    private var icon: String {
        switch status {
        case .success: "checkmark.circle.fill"
        case .failure: "xmark.octagon.fill"
        default: "info.circle"
        }
    }

    private var color: Color {
        switch status {
        case .success: AmberTheme.accentGreen
        case .failure: AmberTheme.accentAmber
        default: AmberTheme.muted
        }
    }
}

private struct ProviderDetailFooter: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 10)
    }
}

private enum ProviderRowValueStyle {
    case body
    case mono
    case accent
}

private struct ProviderRowContent: View {
    let title: String
    let subtitle: String
    let value: String
    var valueStyle: ProviderRowValueStyle = .body
    var showsChevron = true

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(2)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(font)
                .foregroundStyle(color)
                .lineLimit(1)
                .minimumScaleFactor(0.78)

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }

    private var font: Font {
        switch valueStyle {
        case .body, .accent:
            .body
        case .mono:
            .system(size: 13, weight: .regular, design: .monospaced)
        }
    }

    private var color: Color {
        switch valueStyle {
        case .body, .mono:
            AmberTheme.muted
        case .accent:
            AmberTheme.accent
        }
    }
}

private struct ProviderModelRow: View {
    let systemImage: String
    let name: String
    let badge: String
    let summary: String
    let isCurrent: Bool
    let onEdit: () -> Void
    let onSetCurrent: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(isCurrent ? AmberTheme.accentGreen : AmberTheme.accent)
                .frame(width: 36, height: 36)
                .background(
                    isCurrent ? AmberTheme.accentGreen.opacity(0.12) : AmberTheme.accentTint,
                    in: RoundedRectangle(cornerRadius: 10, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .stroke((isCurrent ? AmberTheme.accentGreen : AmberTheme.accent).opacity(0.13), lineWidth: 0.5)
                }

            VStack(alignment: .leading, spacing: 3) {
                Text(name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text(summary)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            ProviderModelBadge(text: badge, isCurrent: isCurrent)

            Menu {
                Button("编辑", action: onEdit)
                if !isCurrent {
                    Button("设为当前", action: onSetCurrent)
                }
                Button("删除", role: .destructive, action: onDelete)
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(AmberTheme.muted)
                    .frame(width: 32, height: 32)
                    .background(AmberTheme.surface2.opacity(0.8), in: Circle())
            }
            .buttonStyle(.plain)
        }
        .frame(minHeight: 64)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
        .background {
            if isCurrent {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(AmberTheme.accentGreen.opacity(0.045))
            }
        }
        .contentShape(Rectangle())
        .onTapGesture {
            onEdit()
        }
    }
}

private struct ProviderModelBadge: View {
    let text: String
    let isCurrent: Bool

    var body: some View {
        Text(text)
            .font(isCurrent ? .caption2.weight(.semibold) : .system(size: 11, weight: .semibold, design: .monospaced))
            .foregroundStyle(isCurrent ? AmberTheme.accentGreen : AmberTheme.muted)
            .lineLimit(1)
            .minimumScaleFactor(0.72)
            .padding(.horizontal, 8)
            .frame(height: 24)
            .background(
                (isCurrent ? AmberTheme.accentGreen : AmberTheme.muted).opacity(0.10),
                in: Capsule()
            )
    }
}

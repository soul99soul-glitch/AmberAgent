import SwiftUI
import Shared

struct BoardSettingsView: View {
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    var providerRegistry: ProviderRegistryStore? = nil

    @State private var templateStore = IOSDeepReadTemplateStore.shared
    @State private var focusKeywordsText = ""
    @State private var editorSeed: BoardTemplateEditorSeed?
    @State private var banner: String?
    @State private var showCreateSheet = false

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        createSourceSection
                        modelSection
                        refreshSection
                        hotListSourceSection
                        focusSection
                        fontSection
                        templateSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .onAppear {
            focusKeywordsText = sharedSettings.todayBoard.hotListFocusKeywords.joined(separator: "\n")
        }
        .sheet(item: $editorSeed) { seed in
            BoardTemplateWorkbenchSheet(
                seed: seed,
                settingsStore: settingsStore,
                sharedSettings: sharedSettings,
                providerRegistry: providerRegistry,
                templateStore: templateStore
            ) { template in
                sharedSettings.updateTodayBoard { _ in
                    TodayBoardSettingPatch(deepReadTemplateId: template.id)
                }
                banner = "已保存并选择模板：\(template.name)"
            }
        }
        .sheet(isPresented: $showCreateSheet) {
            DeepReadCreateView(sharedSettings: sharedSettings)
        }
    }

    private var board: TodayBoardSetting {
        sharedSettings.todayBoard
    }

    private var createSourceSection: some View {
        BoardSettingsSection(title: "自定义来源") {
            Button {
                showCreateSheet = true
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "square.and.pencil")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(AmberTheme.accent)
                        .frame(width: 30, height: 30)
                        .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                    VStack(alignment: .leading, spacing: 2) {
                        Text("手动创建深度阅读")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text("手动文本 / 搜索 / 文件 / WebMount 页面")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回深度阅读", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            VStack(spacing: 2) {
                Text("深度阅读设置")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(AmberTheme.foreground)

                Text("模型 · 热榜 · 模板")
                    .font(.system(size: 11.5))
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)

            Spacer()

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var modelSection: some View {
        BoardSettingsSection(title: "模型") {
            if let banner {
                Text(banner)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.accent)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                BoardCapabilityDivider()
            }

            BoardSettingsActionRow(
                systemImage: "cpu",
                iconColor: AmberTheme.accent,
                title: "深度阅读模型",
                subtitle: modelSubtitle,
                value: currentModelLabel
            ) {
                Menu {
                    Button("跟随当前聊天模型") {
                        sharedSettings.updateTodayBoard { _ in
                            TodayBoardSettingPatch(clearBoardModelId: true)
                        }
                    }
                    if !modelOptions.isEmpty {
                        Divider()
                    }
                    ForEach(modelOptions) { option in
                        Button(option.displayName) {
                            sharedSettings.updateTodayBoard { _ in
                                TodayBoardSettingPatch(boardModelId: option.id)
                            }
                        }
                    }
                } label: {
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(AmberTheme.muted)
                        .frame(width: 30, height: 30)
                        .background(AmberTheme.surface2.opacity(0.7), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
            }
        }
    }

    private var refreshSection: some View {
        BoardSettingsSection(title: "刷新") {
            VStack(alignment: .leading, spacing: 10) {
                Text("前台刷新间隔")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                HStack(spacing: 8) {
                    ForEach([30, 60, 120, 240], id: \.self) { minutes in
                        BoardSettingsChip(
                            title: minutes == 60 ? "1 小时" : "\(minutes) 分钟",
                            selected: Int(board.hotListRefreshIntervalMinutes) == minutes
                        ) {
                            sharedSettings.updateTodayBoard { _ in
                                TodayBoardSettingPatch(hotListRefreshIntervalMinutes: minutes)
                            }
                        }
                    }
                }
                Text("当前没有后台 entitlement；iOS 仅在前台刷新，并在启动时做补偿刷新。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)

            BoardCapabilityDivider()

            BoardSettingsToggleRow(
                title: "仅 Wi‑Fi 刷新",
                subtitle: "前台热榜刷新会遵守这个偏好；不会伪装完整后台调度。",
                isOn: board.hotListWifiOnly
            ) { enabled in
                sharedSettings.updateTodayBoard { _ in
                    TodayBoardSettingPatch(hotListWifiOnly: enabled)
                }
            }
        }
    }

    private var hotListSourceSection: some View {
        BoardSettingsSection(title: "热榜来源") {
            ForEach(Array(IOSHotlistProviders.descriptors.enumerated()), id: \.element.id) { index, descriptor in
                BoardSettingsToggleRow(
                    title: descriptor.displayName,
                    subtitle: descriptor.providerId,
                    isOn: effectiveEnabledSources.contains(descriptor.providerId)
                ) { enabled in
                    toggleHotListSource(descriptor.providerId, enabled: enabled)
                }
                if index < IOSHotlistProviders.descriptors.count - 1 {
                    BoardCapabilityDivider()
                }
            }
        }
    }

    private var focusSection: some View {
        BoardSettingsSection(title: "关注筛选") {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    BoardSettingsChip(title: "全部", selected: board.hotListFilterMode.wireName == "all") {
                        setFilterMode("all")
                    }
                    BoardSettingsChip(title: "关注优先", selected: board.hotListFilterMode.wireName == "focus_first") {
                        setFilterMode("focus_first")
                    }
                    BoardSettingsChip(title: "只看关注", selected: board.hotListFilterMode.wireName == "focus_only") {
                        setFilterMode("focus_only")
                    }
                }

                TextEditor(text: $focusKeywordsText)
                    .font(.footnote)
                    .scrollContentBackground(.hidden)
                    .frame(minHeight: 110)
                    .padding(8)
                    .background(AmberTheme.surface2.opacity(0.65), in: RoundedRectangle(cornerRadius: 10, style: .continuous))

                HStack {
                    Button("保存关键词") {
                        saveFocusKeywords()
                    }
                    .buttonStyle(.borderedProminent)

                    Button("恢复推荐") {
                        let defaults = defaultFocusKeywords
                        focusKeywordsText = defaults.joined(separator: "\n")
                        sharedSettings.updateTodayBoard { _ in
                            TodayBoardSettingPatch(hotListFocusKeywords: defaults)
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
    }

    private var fontSection: some View {
        BoardSettingsSection(title: "阅读字体") {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    BoardSettingsChip(title: "系统", selected: board.boardReadingFontMode.wireName == "system") {
                        sharedSettings.updateTodayBoard { _ in
                            TodayBoardSettingPatch(boardReadingFontModeWireName: "system", clearBoardReadingFontPackId: true)
                        }
                    }
                    BoardSettingsChip(title: "衬线", selected: board.boardReadingFontMode.wireName == "serif") {
                        sharedSettings.updateTodayBoard { _ in
                            TodayBoardSettingPatch(boardReadingFontModeWireName: "serif", clearBoardReadingFontPackId: true)
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("字号比例 \(String(format: "%.2f", board.deepReadFontScale))")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    Slider(
                        value: Binding(
                            get: { Double(board.deepReadFontScale) },
                            set: { value in
                                sharedSettings.updateTodayBoard { _ in
                                    TodayBoardSettingPatch(deepReadFontScale: Float(value))
                                }
                            }
                        ),
                        in: 0.85...1.25,
                        step: 0.05
                    )
                }

                Text("iOS 当前只提供系统与衬线两种真实可用字体，不显示未打包字体包。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
    }

    private var templateSection: some View {
        BoardSettingsSection(title: "模板") {
            ForEach(Array(IOSDeepReadTemplate.builtIns.enumerated()), id: \.element.id) { index, template in
                BoardSettingsActionRow(
                    systemImage: iconName(for: template.id),
                    iconColor: AmberTheme.accent,
                    title: template.name,
                    subtitle: template.description,
                    value: IOSDeepReadTemplate.normalizedTemplateId(board.deepReadTemplateId) == template.id ? "已选择" : nil
                ) {
                    Button {
                        sharedSettings.updateTodayBoard { _ in
                            TodayBoardSettingPatch(deepReadTemplateId: template.id)
                        }
                    } label: {
                        Image(systemName: "checkmark")
                            .font(.system(size: 13, weight: .bold))
                            .foregroundStyle(IOSDeepReadTemplate.normalizedTemplateId(board.deepReadTemplateId) == template.id ? AmberTheme.accent : AmberTheme.muted2)
                            .frame(width: 30, height: 30)
                    }
                    .buttonStyle(.plain)
                }
                if index < IOSDeepReadTemplate.builtIns.count - 1 || !templateStore.templates.isEmpty {
                    BoardCapabilityDivider()
                }
            }

            ForEach(Array(templateStore.templates.enumerated()), id: \.element.id) { index, template in
                BoardSettingsActionRow(
                    systemImage: "doc.richtext",
                    iconColor: AmberTheme.accentGreen,
                    title: template.name,
                    subtitle: template.description.isEmpty ? (template.createdByAI ? "AI 生成草稿" : "自定义 HTML") : template.description,
                    value: board.deepReadTemplateId == template.id ? "已选择" : nil
                ) {
                    HStack(spacing: 6) {
                        Button {
                            sharedSettings.updateTodayBoard { _ in
                                TodayBoardSettingPatch(deepReadTemplateId: template.id)
                            }
                        } label: {
                            Image(systemName: "checkmark")
                                .frame(width: 30, height: 30)
                        }
                        .buttonStyle(.plain)

                        Button {
                            editorSeed = .edit(template)
                        } label: {
                            Image(systemName: "square.and.pencil")
                                .frame(width: 30, height: 30)
                        }
                        .buttonStyle(.plain)

                        Button(role: .destructive) {
                            templateStore.delete(id: template.id)
                            if board.deepReadTemplateId == template.id {
                                sharedSettings.updateTodayBoard { _ in
                                    TodayBoardSettingPatch(deepReadTemplateId: IOSDeepReadTemplate.defaultId)
                                }
                            }
                        } label: {
                            Image(systemName: "trash")
                                .frame(width: 30, height: 30)
                        }
                        .buttonStyle(.plain)
                    }
                    .foregroundStyle(AmberTheme.muted)
                }
                if index < templateStore.templates.count - 1 {
                    BoardCapabilityDivider()
                }
            }

            BoardCapabilityDivider()

            HStack(spacing: 10) {
                Button {
                    editorSeed = .newTemplate
                } label: {
                    Label("新建模板", systemImage: "plus")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)

                Button {
                    editorSeed = .aiDraft
                } label: {
                    Label("AI 草稿", systemImage: "sparkles")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
        }
    }

    private var modelOptions: [BoardModelOption] {
        // All chat models across every configured provider (shared settings),
        // not just the legacy registry's single selected provider.
        sharedSettings.availableChatModels().map { option in
            BoardModelOption(
                id: option.id,
                displayName: option.displayName,
                modelId: option.modelId
            )
        }
    }

    private var currentModelLabel: String {
        guard let boardModelId = board.boardModelId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !boardModelId.isEmpty else {
            let current = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
            return current.isEmpty ? "跟随聊天模型" : "跟随：\(current)"
        }
        return modelOptions.first { $0.id.caseInsensitiveCompare(boardModelId) == .orderedSame }?.displayName ?? "已失效，回退聊天模型"
    }

    private var modelSubtitle: String {
        if modelOptions.isEmpty {
            return "没有可读取的聊天模型，生成时会回退当前聊天模型或本地草稿。"
        }
        return "显示所有已配置服务商的聊天模型。"
    }

    private var effectiveEnabledSources: Set<String> {
        IOSHotlistProviders.effectiveEnabledProviderIds(setting: board)
    }

    private var defaultFocusKeywords: [String] {
        [
            "AI", "人工智能", "大模型", "LLM", "Agent", "机器人", "具身智能", "自动驾驶",
            "数码", "3C", "智能硬件", "芯片", "半导体", "OpenAI", "Claude", "DeepSeek",
            "Gemini", "NVIDIA", "小米", "华为", "特斯拉"
        ]
    }

    private func toggleHotListSource(_ providerId: String, enabled: Bool) {
        var next = effectiveEnabledSources
        if enabled {
            next.insert(providerId)
        } else {
            next.remove(providerId)
        }
        sharedSettings.updateTodayBoard { _ in
            TodayBoardSettingPatch(hotListEnabledSources: Array(next).sorted())
        }
    }

    private func setFilterMode(_ wireName: String) {
        sharedSettings.updateTodayBoard { _ in
            TodayBoardSettingPatch(hotListFilterModeWireName: wireName)
        }
    }

    private func saveFocusKeywords() {
        let keywords = IOSHotListAggregator.normalizeKeywords([focusKeywordsText])
        sharedSettings.updateTodayBoard { _ in
            TodayBoardSettingPatch(hotListFocusKeywords: keywords)
        }
    }

    private func iconName(for templateId: String) -> String {
        switch templateId {
        case IOSDeepReadTemplate.editorial.id: "newspaper"
        default: "doc.richtext"
        }
    }
}

private struct BoardModelOption: Identifiable, Equatable {
    var id: String
    var displayName: String
    var modelId: String
}

private struct BoardSettingsSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: title)
            AmberFormGroup {
                content
            }
        }
    }
}

private struct BoardSettingsActionRow<Trailing: View>: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let value: String?
    @ViewBuilder let trailing: Trailing

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(iconColor)
                .frame(width: 32, height: 32)
                .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 8, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(title)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(AmberTheme.foreground)
                    if let value {
                        Text(value)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.accent)
                            .lineLimit(1)
                    }
                }
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            trailing
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct BoardSettingsToggleRow: View {
    let title: String
    let subtitle: String
    let isOn: Bool
    let onChange: @MainActor @Sendable (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: { newValue in
            onChange(newValue)
        })) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .toggleStyle(.switch)
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
    }
}

private struct BoardSettingsChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(selected ? .white : AmberTheme.foreground2)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
                .background(selected ? AmberTheme.accent : AmberTheme.surface2.opacity(0.75), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct BoardTemplateEditorSeed: Identifiable {
    var id: String
    var template: IOSDeepReadCustomTemplate?
    var wantsAIDraft: Bool

    static var newTemplate: BoardTemplateEditorSeed {
        BoardTemplateEditorSeed(id: UUID().uuidString, template: nil, wantsAIDraft: false)
    }

    static var aiDraft: BoardTemplateEditorSeed {
        BoardTemplateEditorSeed(id: UUID().uuidString, template: nil, wantsAIDraft: true)
    }

    static func edit(_ template: IOSDeepReadCustomTemplate) -> BoardTemplateEditorSeed {
        BoardTemplateEditorSeed(id: template.id, template: template, wantsAIDraft: false)
    }
}

private struct BoardTemplateWorkbenchSheet: View {
    let seed: BoardTemplateEditorSeed
    let settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    var providerRegistry: ProviderRegistryStore?
    let templateStore: IOSDeepReadTemplateStore
    let onSaved: (IOSDeepReadCustomTemplate) -> Void

    @State private var name: String
    @State private var description: String
    @State private var brief: String
    @State private var html: String
    @State private var message: String?
    @State private var messageIsError = false
    @State private var isGenerating = false
    @State private var previewHTML: String?

    @Environment(\.dismiss) private var dismiss

    init(
        seed: BoardTemplateEditorSeed,
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore?,
        templateStore: IOSDeepReadTemplateStore,
        onSaved: @escaping (IOSDeepReadCustomTemplate) -> Void
    ) {
        self.seed = seed
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
        self.templateStore = templateStore
        self.onSaved = onSaved
        _name = State(initialValue: seed.template?.name ?? "深度阅读模板")
        _description = State(initialValue: seed.template?.description ?? "")
        _brief = State(initialValue: seed.wantsAIDraft ? "做一个适合长文阅读的清爽中文模板，突出标题、摘要、分析和来源。" : "")
        _html = State(initialValue: seed.template?.html ?? IOSDeepReadHTMLTemplateRenderer.starterHTML())
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    editorFields
                    actionBar
                    if let message {
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(messageIsError ? AmberTheme.accentAmber : AmberTheme.muted)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if let previewHTML {
                        IOSDeepReadTemplateWebView(html: previewHTML)
                            .frame(minHeight: 420)
                            .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    }
                }
                .padding(16)
            }
            .background(AmberTheme.background.ignoresSafeArea())
            .navigationTitle(seed.template == nil ? "模板工坊" : "编辑模板")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private var editorFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("模板名称", text: $name)
                .textFieldStyle(.roundedBorder)
            TextField("说明", text: $description)
                .textFieldStyle(.roundedBorder)
            TextEditor(text: $brief)
                .font(.footnote)
                .scrollContentBackground(.hidden)
                .frame(minHeight: 70)
                .padding(8)
                .background(AmberTheme.surface2.opacity(0.65), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            TextEditor(text: $html)
                .font(.system(size: 12, design: .monospaced))
                .scrollContentBackground(.hidden)
                .frame(minHeight: 300)
                .padding(8)
                .background(AmberTheme.surface2.opacity(0.65), in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        }
    }

    private var actionBar: some View {
        HStack(spacing: 10) {
            Button {
                Task { await generateDraft() }
            } label: {
                Label(isGenerating ? "生成中" : "AI 生成", systemImage: "sparkles")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(isGenerating)

            Button {
                validate()
            } label: {
                Label("校验", systemImage: "checkmark.shield")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button {
                preview()
            } label: {
                Label("预览", systemImage: "eye")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)

            Button {
                save()
            } label: {
                Label("保存", systemImage: "square.and.arrow.down")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
        }
    }

    private func generateDraft() async {
        guard !isGenerating else { return }
        let apiKey = settingsStore.currentApiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !apiKey.isEmpty else {
            setError("没有配置 API Key，无法生成模板草稿。")
            return
        }
        let modelId = resolvedModelId()
        guard !modelId.isEmpty else {
            setError("没有可用聊天模型，无法生成模板草稿。")
            return
        }
        isGenerating = true
        defer { isGenerating = false }
        let providerSetting = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "DeepReadTemplate",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: settingsStore.apiKey,
            baseUrl: settingsStore.baseUrl,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        do {
            let draft = try await IOSDeepReadTemplateDraftGenerator.generateDraft(
                name: name,
                brief: brief,
                providerSetting: providerSetting,
                modelId: modelId
            )
            name = draft.name
            description = draft.description
            html = draft.html
            setMessage("已生成草稿，请校验或预览后保存。")
        } catch {
            setError(error.localizedDescription)
        }
    }

    private func validate() {
        let result = IOSDeepReadTemplateValidator.validateHTML(html)
        if result.ok {
            setMessage("模板校验通过。")
        } else {
            setError(result.error ?? "模板校验失败。")
        }
    }

    private func preview() {
        let template = IOSDeepReadCustomTemplate(
            id: seed.template?.id ?? IOSDeepReadTemplate.customPrefix + "preview",
            name: name,
            description: description,
            html: html,
            createdByAI: false
        )
        do {
            previewHTML = try IOSDeepReadHTMLTemplateRenderer.render(
                task: previewTask(templateId: template.id),
                template: template,
                fontScale: sharedSettings.todayBoard.deepReadFontScale,
                fontModeWireName: sharedSettings.todayBoard.boardReadingFontMode.wireName
            )
            setMessage("预览已更新。")
        } catch {
            previewHTML = nil
            setError(error.localizedDescription)
        }
    }

    private func save() {
        do {
            let template = IOSDeepReadCustomTemplate(
                id: seed.template?.id ?? IOSDeepReadTemplate.customPrefix + UUID().uuidString.lowercased(),
                name: name,
                description: description,
                html: html,
                createdByAI: seed.wantsAIDraft
            )
            let saved = try templateStore.save(template)
            onSaved(saved)
            dismiss()
        } catch {
            setError(error.localizedDescription)
        }
    }

    private func resolvedModelId() -> String {
        let fallback = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let boardModelId = sharedSettings.todayBoard.boardModelId?.trimmingCharacters(in: .whitespacesAndNewlines),
              !boardModelId.isEmpty else {
            return fallback
        }
        // Resolve the board model across all configured providers (shared
        // settings), not just the legacy registry's selected provider.
        return sharedSettings.availableChatModels()
            .first { $0.id.caseInsensitiveCompare(boardModelId) == .orderedSame }?.modelId
            ?? fallback
    }

    private func previewTask(templateId: String) -> IOSDeepReadTask {
        IOSDeepReadTask(
            id: "preview",
            title: "模板预览",
            status: .succeeded,
            templateId: templateId,
            sources: [
                IOSDeepReadSource(
                    kind: .manualText,
                    title: "预览来源",
                    content: "这是模板预览用的本地示例来源，只用于检查版式。"
                )
            ],
            resultMarkdown: "# 模板预览\n\n## 摘要\n这是一段预览文本，用来检查标题、摘要、分析和来源区块是否正常显示。\n\n## 分析\n- 保持正文可读。\n- 来源列表应清晰可见。",
            failureMessage: nil,
            createdAt: IOSBoardSignalRepository.currentEpochMs(),
            updatedAt: IOSBoardSignalRepository.currentEpochMs(),
            completedAt: IOSBoardSignalRepository.currentEpochMs(),
            retryCount: 0
        )
    }

    private func setMessage(_ text: String) {
        message = text
        messageIsError = false
    }

    private func setError(_ text: String) {
        message = text
        messageIsError = true
    }
}

#Preview {
    NavigationStack {
        BoardSettingsView(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(),
            providerRegistry: nil
        )
    }
}

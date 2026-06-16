import SwiftUI
import AVFoundation

struct TTSSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @State private var ttsPlayer = IOSTTSPlayer()
    @Environment(RouterPath.self) private var router

    @State private var selectedEngine: TTSEngine = .system
    @State private var alert: TTSSettingsAlert?

    @State private var miniMaxName = "MiniMax TTS"
    @State private var miniMaxAPIKey = ""
    @State private var miniMaxModel = "speech-2.6-turbo"
    @State private var miniMaxVoice = "female-shaonv"
    @State private var miniMaxEmotion: TTSEmotion = .calm
    @State private var miniMaxSpeed: TTSSpeed = .normal

    @State private var systemName = "系统 TTS"
    @State private var systemSpeechRate: TTSSpeed = .normal
    @State private var systemPitch: TTSSpeed = .normal

    @State private var openAIName = "OpenAI TTS"
    @State private var openAIAPIKey = ""
    @State private var openAIModel = "gpt-4o-mini-tts"
    @State private var openAIVoice = "alloy"

    @State private var geminiName = "Gemini TTS"
    @State private var geminiAPIKey = ""
    @State private var geminiModel = "2.5-flash-tts"
    @State private var geminiVoice = "Kore"

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        presetProvidersSection
                        engineSection
                        configurationSection
                        previewSection
                        deleteSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $alert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回设置", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("语音合成")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            AmberGlassCircleButton(systemImage: "plus", accessibilityLabel: "添加 TTS 提供商", size: 44, symbolSize: 17) {
                router.navigate(to: .ttsAdd)
            }
            .foregroundStyle(AmberTheme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("KMP 默认提供系统 TTS；当前 iOS 页面仅确认系统 TTS 作为真实默认。云端引擎字段保留为本地预览，不会保存或请求。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    /// Read-only list of the REAL KMP default TTS providers (DEFAULT_TTS_PROVIDERS),
    /// sourced from `IOSSharedSettingsStore` (which calls the KMP
    /// `IosSettingsDefaults.defaultSeededSettings()`). This proves the "real seeded
    /// Settings read" data path is wired end-to-end (UI -> IOSSharedSettingsStore
    /// -> IosSettingsDefaults -> applyBackfillAndSeedShared ->
    /// applyCrossDomainConsistencyShared -> seeded Settings). It does NOT make these
    /// engines editable/playable — the cloud engine editing below stays draft-only.
    private var presetProvidersSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "预置 TTS（KMP 默认 · 只读）")

            AmberFormGroup {
                ForEach(Array(sharedSettings.ttsProviders.enumerated()), id: \.offset) { index, provider in
                    TTSPresetProviderRow(
                        name: provider.name,
                        isSelected: provider.id == sharedSettings.selectedTTSProviderId
                    )

                    if index < sharedSettings.ttsProviders.count - 1 {
                        TTSSettingsDivider()
                    }
                }
            }

            TTSSettingsNote("这些行来自 Android/KMP DEFAULT_TTS_PROVIDERS，经 IosSettingsDefaults 真实 seed/去重后只读展示。当前选中的默认是 \(sharedSettings.ttsProviders.first { $0.id == sharedSettings.selectedTTSProviderId }?.name ?? "系统 TTS")。下方引擎编辑区仍是本地预览，不保存。")
        }
    }

    private var engineSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "语音引擎")
            AmberFormGroup {
                ForEach(Array(TTSEngine.allCases.enumerated()), id: \.element.id) { index, engine in
                    TTSEngineRow(engine: engine, isSelected: selectedEngine == engine) {
                        selectedEngine = engine
                    }

                    if index < TTSEngine.allCases.count - 1 {
                        TTSSettingsDivider()
                    }
                }
            }

            TTSSettingsNote("当前 iOS 仅确认系统 TTS 为真实默认。点按云端引擎只查看本地预览字段，不会切换默认、保存配置或写入 Keychain。")
        }
    }

    @ViewBuilder
    private var configurationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "\(selectedEngine.displayName) 设置")
            AmberFormGroup {
                switch selectedEngine {
                case .system:
                    TTSTextFieldRow(title: "名称", text: $systemName, placeholder: "输入提供商名称")
                    TTSSettingsDivider()
                    TTSMenuRow(title: "语速", value: systemSpeechRate.title) {
                        ForEach(TTSSpeed.allCases) { speed in
                            Button(speed.title) { systemSpeechRate = speed }
                        }
                    }
                    TTSSettingsDivider()
                    TTSMenuRow(title: "音调", value: systemPitch.title) {
                        ForEach(TTSSpeed.allCases) { pitch in
                            Button(pitch.title) { systemPitch = pitch }
                        }
                    }
                case .miniMax:
                    TTSTextFieldRow(title: "名称", text: $miniMaxName, placeholder: "输入提供商名称")
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "API Key", text: $miniMaxAPIKey, placeholder: "粘贴 API Key", monospace: true, isSecure: true)
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "模型", text: $miniMaxModel, placeholder: "要使用的 TTS 模型", monospace: true)
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "语音 ID", text: $miniMaxVoice, placeholder: "语音合成使用的语音 ID", monospace: true)
                    TTSSettingsDivider()
                    TTSMenuRow(title: "情感", value: miniMaxEmotion.title) {
                        ForEach(TTSEmotion.allCases) { emotion in
                            Button(emotion.title) { miniMaxEmotion = emotion }
                        }
                    }
                    TTSSettingsDivider()
                    TTSMenuRow(title: "语速", value: miniMaxSpeed.title) {
                        ForEach(TTSSpeed.allCases) { speed in
                            Button(speed.title) { miniMaxSpeed = speed }
                        }
                    }
                case .openAI:
                    TTSTextFieldRow(title: "名称", text: $openAIName, placeholder: "输入提供商名称")
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "API Key", text: $openAIAPIKey, placeholder: "粘贴 API Key", monospace: true, isSecure: true)
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "模型", text: $openAIModel, placeholder: "要使用的 TTS 模型", monospace: true)
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "语音", text: $openAIVoice, placeholder: "语音名称或 ID", monospace: true)
                case .gemini:
                    TTSTextFieldRow(title: "名称", text: $geminiName, placeholder: "输入提供商名称")
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "API Key", text: $geminiAPIKey, placeholder: "粘贴 API Key", monospace: true, isSecure: true)
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "模型", text: $geminiModel, placeholder: "要使用的 TTS 模型", monospace: true)
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "语音名称", text: $geminiVoice, placeholder: "语音名称", monospace: true)
                }
            }

            TTSSettingsNote(configurationNote)
        }
    }

    private var previewSection: some View {
        AmberFormGroup {
            Button {
                if ttsPlayer.isSpeaking {
                    ttsPlayer.stop()
                } else {
                    ttsPlayer.speak(text: "你好，这是 AmberAgent 的语音试听。系统 TTS 已接入。", language: "zh-CN")
                }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: ttsPlayer.isSpeaking ? "speaker.wave.3.fill" : "speaker.wave.2.fill")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 30, height: 30)
                        .background(ttsPlayer.isSpeaking ? AmberTheme.accentRed : AmberTheme.accent, in: Circle())

                    VStack(alignment: .leading, spacing: 2) {
                        Text(ttsPlayer.isSpeaking ? "正在播放（点击停止）" : "系统 TTS 试听")
                            .font(.body)
                            .foregroundStyle(AmberTheme.foreground)
                        Text(ttsPlayer.isSpeaking ? "AVSpeechSynthesizer 正在合成语音" : "使用 iOS 系统语音朗读测试文本")
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(minHeight: 58)
                .padding(.horizontal, 14)
                .padding(.vertical, 5)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(ttsPlayer.isSpeaking ? "停止试听" : "系统 TTS 试听")
        }
        .padding(.top, 20)
    }

    private var deleteSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "已保存云端引擎（UserDefaults · 可读写）")
            AmberFormGroup {
                let engines = sharedSettings.savedTtsEngines
                if engines.isEmpty {
                    Text("暂无自定义云端引擎。选择引擎类型后点保存引擎添加。")
                        .font(.caption).foregroundStyle(AmberTheme.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14).padding(.vertical, 12)
                } else {
                    ForEach(Array(engines.enumerated()), id: \.offset) { index, engine in
                        HStack(spacing: 10) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(engine["name"] ?? "?").font(.body.weight(.semibold))
                                Text("\(engine["engineType"] ?? "?") · \(engine["model"] ?? "?")")
                                    .font(.system(size: 11, design: .monospaced)).foregroundStyle(AmberTheme.muted2)
                            }.frame(maxWidth: .infinity, alignment: .leading)
                            Button { sharedSettings.removeTtsEngine(at: index) } label: {
                                Image(systemName: "minus.circle.fill").font(.system(size: 18)).foregroundStyle(AmberTheme.accentRed)
                            }.buttonStyle(.plain)
                        }.frame(minHeight: 48).padding(.horizontal, 14).padding(.vertical, 4)
                        if index < engines.count - 1 { TTSSettingsDivider() }
                    }
                }
                TTSSettingsDivider()
                Button {
                    let name: String
                    switch selectedEngine { case .system: name = "System"; case .miniMax: name = "MiniMax"; case .openAI: name = "OpenAI"; case .gemini: name = "Gemini" }
                    sharedSettings.addTtsEngine(name: name, engineType: name)
                } label: {
                    Label("保存引擎", systemImage: "plus.circle.fill").font(.body.weight(.semibold)).foregroundStyle(AmberTheme.accent)
                }.buttonStyle(.plain).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 14).padding(.vertical, 8)
            }
        }
        .padding(.top, 20)
    }

    private var configurationNote: String {
        switch selectedEngine {
        case .system:
            "系统 TTS 是 KMP 默认提供商，当前 iOS 页面只展示本地预览参数；尚未接入真实 iOS TTS settings store。"
        case .miniMax, .openAI, .gemini:
            "云端 TTS 配置待接（需 Provider 持久化）；这些字段不会保存、不会写入 Keychain，也不会用于试听或朗读。"
        }
    }
}

struct TTSAddView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var providerType: TTSProviderDraftType = .miniMax
    @State private var name = TTSProviderDraftType.miniMax.defaultName
    @State private var apiKey = ""
    @State private var baseURL = ""
    @State private var model = TTSProviderDraftType.miniMax.defaultModel
    @State private var voice = TTSProviderDraftType.miniMax.defaultVoice
    @State private var emotion: TTSEmotion = .calm
    @State private var speed: TTSSpeed = .normal
    @State private var speechRate: TTSSpeed = .normal
    @State private var pitch: TTSSpeed = .normal

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        typeSection
                        configurationSection
                        previewSection
                    }
                    .padding(.bottom, 36)
                }
                .scrollIndicators(.hidden)
            }
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回语音合成", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("添加引擎")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            Button {
                dismiss()
            } label: {
                Text("关闭")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.accent)
                    .frame(height: 36)
                    .padding(.horizontal, 14)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .amberGlass(cornerRadius: AmberTheme.radiusPill)
            .accessibilityLabel("关闭")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("先填写 TTS Provider 本地预览。当前页面不会保存引擎、不会写入 Keychain，也不会发起试听请求。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    private var typeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "类型")
            AmberFormGroup {
                TTSMenuRow(title: "服务类型", value: providerType.title) {
                    ForEach(TTSProviderDraftType.allCases) { type in
                        Button(type.title) {
                            applyProviderType(type)
                        }
                    }
                }
            }

            TTSSettingsNote(providerType.note)
        }
    }

    @ViewBuilder
    private var configurationSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "\(providerType.title) 设置")
            AmberFormGroup {
                TTSTextFieldRow(title: "名称", text: $name, placeholder: providerType.defaultName)

                if providerType.requiresAPIKey {
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "API Key", text: $apiKey, placeholder: "粘贴 API Key", monospace: true, isSecure: true)
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "Base URL", text: $baseURL, placeholder: "可选", monospace: true)
                }

                if providerType.usesModel {
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: "模型", text: $model, placeholder: providerType.defaultModel, monospace: true)
                }

                if providerType.usesVoice {
                    TTSSettingsDivider()
                    TTSTextFieldRow(title: providerType.voiceLabel, text: $voice, placeholder: providerType.defaultVoice, monospace: true)
                }

                switch providerType {
                case .system:
                    TTSSettingsDivider()
                    TTSMenuRow(title: "语速", value: speechRate.title) {
                        ForEach(TTSSpeed.allCases) { value in
                            Button(value.title) { speechRate = value }
                        }
                    }
                    TTSSettingsDivider()
                    TTSMenuRow(title: "音调", value: pitch.title) {
                        ForEach(TTSSpeed.allCases) { value in
                            Button(value.title) { pitch = value }
                        }
                    }
                case .miniMax:
                    TTSSettingsDivider()
                    TTSMenuRow(title: "情感", value: emotion.title) {
                        ForEach(TTSEmotion.allCases) { value in
                            Button(value.title) { emotion = value }
                        }
                    }
                    TTSSettingsDivider()
                    TTSMenuRow(title: "语速", value: speed.title) {
                        ForEach(TTSSpeed.allCases) { value in
                            Button(value.title) { speed = value }
                        }
                    }
                case .openAI, .gemini, .qwen, .groq:
                    EmptyView()
                }
            }

            TTSSettingsNote(validationNote)
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "预览")
            AmberFormGroup {
                TTSPreviewLine(label: "类型", value: providerType.title)
                TTSSettingsDivider()
                TTSPreviewLine(label: "名称", value: name.trimmedForTTS.isEmpty ? "未命名" : name.trimmedForTTS)
                TTSSettingsDivider()
                TTSPreviewLine(label: "模型", value: providerType.usesModel ? draftModel : "系统离线")
                TTSSettingsDivider()
                TTSPreviewLine(label: "凭据", value: providerType.requiresAPIKey ? credentialPreview : "无需 Key")
                TTSSettingsDivider()
                TTSPreviewLine(label: "保存方式", value: "本地本地预览")
            }
        }
    }

    private var draftModel: String {
        model.trimmedForTTS.isEmpty ? providerType.defaultModel : model.trimmedForTTS
    }

    private var credentialPreview: String {
        apiKey.trimmedForTTS.isEmpty ? "待填写" : "已填写，未保存"
    }

    private var validationNote: String {
        if name.trimmedForTTS.isEmpty {
            return "名称为空；真实保存前需要一个显示名称。"
        }

        if providerType.requiresAPIKey, apiKey.trimmedForTTS.isEmpty {
            return "API Key 为空；当前仍可预览本地预览，不会写入 Keychain。"
        }

        return "本地预览结构完整；当前不会写入真实 TTS 配置。"
    }

    private func applyProviderType(_ type: TTSProviderDraftType) {
        providerType = type
        name = type.defaultName
        model = type.defaultModel
        voice = type.defaultVoice
        apiKey = ""
        baseURL = ""
        emotion = .calm
        speed = .normal
        speechRate = .normal
        pitch = .normal
    }
}

private enum TTSEngine: String, CaseIterable, Identifiable {
    case system
    case miniMax
    case openAI
    case gemini

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .system: "系统 TTS"
        case .miniMax: "MiniMax"
        case .openAI: "OpenAI"
        case .gemini: "Gemini"
        }
    }

    var rowTitle: String {
        switch self {
        case .system: "系统 TTS"
        case .miniMax: "MiniMax TTS"
        case .openAI: "OpenAI TTS"
        case .gemini: "Gemini TTS"
        }
    }

    var detail: String {
        switch self {
        case .system: "system · 默认"
        case .miniMax: "minimax · 本地预览"
        case .openAI: "openai · 本地预览"
        case .gemini: "gemini · 本地预览"
        }
    }
}

private enum TTSEmotion: String, CaseIterable, Identifiable {
    case auto
    case calm
    case happy
    case sad
    case serious

    var id: String { rawValue }

    var title: String {
        switch self {
        case .auto: "自动"
        case .calm: "平静"
        case .happy: "高兴"
        case .sad: "伤感"
        case .serious: "严肃"
        }
    }
}

private enum TTSSpeed: String, CaseIterable, Identifiable {
    case half
    case slow
    case normal
    case quick
    case fast
    case double

    var id: String { rawValue }

    var title: String {
        switch self {
        case .half: "0.5×"
        case .slow: "0.75×"
        case .normal: "1.0×"
        case .quick: "1.25×"
        case .fast: "1.5×"
        case .double: "2.0×"
        }
    }
}

private enum TTSSettingsAlert: Identifiable {
    case delete
    case deleteSystem
    case previewUnavailable

    var id: String {
        switch self {
        case .delete: "delete"
        case .deleteSystem: "delete-system"
        case .previewUnavailable: "preview-unavailable"
        }
    }

    var title: String {
        switch self {
        case .delete: "删除引擎待接（需 Keychain 持久化）"
        case .deleteSystem: "系统 TTS 不可删除"
        case .previewUnavailable: "系统 TTS 试听"
        }
    }

    var message: String {
        switch self {
        case .delete:
            "当前页面不会删除真实 TTS 配置，避免迁移期间误改已有语音引擎。"
        case .deleteSystem:
            "Android 端默认系统 TTS 也不可删除；iOS 端保留同样的兜底策略。"
        case .previewUnavailable:
            "当前仓库还没有 iOS TTS 合成执行链。不会调用系统朗读，也不会向云端 TTS 服务发请求。"
        }
    }
}

private enum TTSProviderDraftType: String, CaseIterable, Identifiable {
    case system
    case miniMax
    case openAI
    case gemini
    case qwen
    case groq

    var id: String { rawValue }

    var title: String {
        switch self {
        case .system: "系统 TTS"
        case .miniMax: "MiniMax"
        case .openAI: "OpenAI"
        case .gemini: "Gemini"
        case .qwen: "Qwen"
        case .groq: "Groq"
        }
    }

    var defaultName: String {
        switch self {
        case .system: "系统 TTS"
        case .miniMax: "MiniMax TTS"
        case .openAI: "OpenAI TTS"
        case .gemini: "Gemini TTS"
        case .qwen: "Qwen TTS"
        case .groq: "Groq TTS"
        }
    }

    var defaultModel: String {
        switch self {
        case .system: ""
        case .miniMax: "speech-2.6-turbo"
        case .openAI: "gpt-4o-mini-tts"
        case .gemini: "2.5-flash-tts"
        case .qwen: "qwen-tts"
        case .groq: "playai-tts"
        }
    }

    var defaultVoice: String {
        switch self {
        case .system: ""
        case .miniMax: "female-shaonv"
        case .openAI: "alloy"
        case .gemini: "Kore"
        case .qwen: "Cherry"
        case .groq: "Aaliyah-PlayAI"
        }
    }

    var voiceLabel: String {
        switch self {
        case .miniMax: "语音 ID"
        case .gemini: "语音名称"
        case .system, .openAI, .qwen, .groq: "语音"
        }
    }

    var requiresAPIKey: Bool {
        self != .system
    }

    var usesModel: Bool {
        self != .system
    }

    var usesVoice: Bool {
        self != .system
    }

    var note: String {
        switch self {
        case .system:
            "系统 TTS 是默认类型；当前添加页仅保留本地预览配置，不会创建真实提供商。"
        case .miniMax:
            "MiniMax 支持语音 ID、情感和语速参数。"
        case .openAI:
            "OpenAI 使用 voice 字段选择音色。"
        case .gemini:
            "Gemini 使用 voiceName 风格的语音名称。"
        case .qwen:
            "Qwen 可配置模型、语音和语言相关参数，当前先保留核心字段。"
        case .groq:
            "Groq TTS 与 OpenAI 风格相近，当前先保留模型和 voice 字段。"
        }
    }
}

private struct TTSEngineRow: View {
    let engine: TTSEngine
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(engine.rowTitle)
                        .font(.body.weight(isSelected ? .semibold : .regular))
                        .foregroundStyle(isSelected ? AmberTheme.accent : AmberTheme.foreground)

                    Text(engine.detail)
                        .font(.system(size: 11.5, weight: .regular, design: .monospaced))
                        .foregroundStyle(isSelected ? AmberTheme.accent.opacity(0.74) : AmberTheme.muted2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(AmberTheme.accent)
                }
            }
            .frame(minHeight: 56)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .background(isSelected ? AmberTheme.accentTint : .clear)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(engine.rowTitle)，\(engine.detail)")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private struct TTSTextFieldRow: View {
    let title: String
    @Binding var text: String
    let placeholder: String
    var monospace = false
    var isSecure = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)

            Group {
                if isSecure {
                    SecureField(placeholder, text: $text)
                } else {
                    TextField(placeholder, text: $text)
                }
            }
            .font(monospace ? .system(size: 14, weight: .regular, design: .monospaced) : .body)
            .foregroundStyle(AmberTheme.foreground)
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(minHeight: 58)
        .padding(.horizontal, 15)
        .padding(.vertical, 8)
    }
}

private struct TTSMenuRow<MenuContent: View>: View {
    let title: String
    let value: String
    @ViewBuilder let menuContent: MenuContent

    var body: some View {
        Menu {
            menuContent
        } label: {
            HStack(spacing: 10) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                    .frame(maxWidth: .infinity, alignment: .leading)

                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(minHeight: 52)
            .padding(.horizontal, 14)
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
    }
}

private struct TTSSettingsDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 14)
    }
}

private struct TTSPresetProviderRow: View {
    let name: String
    let isSelected: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "speaker.wave.2")
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(AmberTheme.accent)
                .frame(width: 30, height: 30)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            Text(name)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)

            if isSelected {
                Text("默认")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(AmberTheme.accentGreen)
            } else {
                Text("预置")
                    .font(.caption2)
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 48)
        .padding(.horizontal, 14)
        .padding(.vertical, 3)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(name)\(isSelected ? "，当前默认" : "")")
    }
}

private struct TTSSettingsNote: View {
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
            .padding(.top, 7)
    }
}

private struct TTSPreviewLine: View {
    let label: String
    let value: String

    var body: some View {
        HStack(spacing: 12) {
            Text(label)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)

            Text(value)
                .font(.system(size: 14, weight: .medium, design: .monospaced))
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private extension String {
    var trimmedForTTS: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

#Preview {
    NavigationStack {
        TTSSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}

#Preview("TTS Add") {
    NavigationStack {
        TTSAddView()
    }
}

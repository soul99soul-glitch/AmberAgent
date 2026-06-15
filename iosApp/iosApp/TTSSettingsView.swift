import SwiftUI

struct TTSSettingsView: View {
    @Environment(\.dismiss) private var dismiss

    @State private var selectedEngine: TTSEngine = .miniMax
    @State private var isPreviewing = false
    @State private var alert: TTSSettingsAlert?

    @State private var miniMaxName = "MiniMax TTS"
    @State private var miniMaxAPIKey = "········"
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
                alert = .add
            }
            .foregroundStyle(AmberTheme.accent)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("朗读回复与语音播报使用的合成引擎。系统 TTS 离线可用、无需 Key；接入云端引擎可获得更自然的音色。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    private var engineSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "语音引擎")
            AmberFormGroup {
                ForEach(Array(TTSEngine.allCases.enumerated()), id: \.element.id) { index, engine in
                    TTSEngineRow(engine: engine, isSelected: selectedEngine == engine) {
                        selectedEngine = engine
                        isPreviewing = false
                    }

                    if index < TTSEngine.allCases.count - 1 {
                        TTSSettingsDivider()
                    }
                }
            }

            TTSSettingsNote("点按切换默认语音引擎；右上角 + 添加新引擎。")
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

            TTSSettingsNote("API Key 存入 Keychain；留空保存不会覆盖已存 Key。")
        }
    }

    private var previewSection: some View {
        AmberFormGroup {
            Button {
                isPreviewing.toggle()
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: isPreviewing ? "stop.fill" : "play.fill")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 30, height: 30)
                        .background(AmberTheme.accent, in: Circle())

                    VStack(alignment: .leading, spacing: 2) {
                        Text(isPreviewing ? "停止试听" : "试听当前语音")
                            .font(.body)
                            .foregroundStyle(AmberTheme.accent)
                        Text("你好，这是文字转语音测试。")
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
            .accessibilityLabel(isPreviewing ? "停止试听" : "试听当前语音")
        }
        .padding(.top, 20)
    }

    private var deleteSection: some View {
        AmberFormGroup {
            Button {
                alert = selectedEngine == .system ? .deleteSystem : .delete
            } label: {
                Text("删除此引擎")
                    .font(.body.weight(.medium))
                    .foregroundStyle(AmberTheme.accentRed)
                    .frame(maxWidth: .infinity)
                    .frame(minHeight: 52)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.top, 20)
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
        case .system: "system · 离线"
        case .miniMax: "minimax · speech-2.6-turbo"
        case .openAI: "openai · gpt-4o-mini-tts"
        case .gemini: "gemini · 2.5-flash-tts"
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
    case add
    case delete
    case deleteSystem

    var id: String {
        switch self {
        case .add: "add"
        case .delete: "delete"
        case .deleteSystem: "delete-system"
        }
    }

    var title: String {
        switch self {
        case .add: "添加引擎尚未接线"
        case .delete: "删除引擎尚未接线"
        case .deleteSystem: "系统 TTS 不可删除"
        }
    }

    var message: String {
        switch self {
        case .add:
            "当前先还原语音合成设置原型；新增引擎会在 TTS Provider 写入与 Keychain 接通后补上。"
        case .delete:
            "当前页面不会删除真实 TTS 配置，避免迁移期间误改已有语音引擎。"
        case .deleteSystem:
            "Android 端默认系统 TTS 也不可删除；iOS 端保留同样的兜底策略。"
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

#Preview {
    NavigationStack {
        TTSSettingsView()
    }
}

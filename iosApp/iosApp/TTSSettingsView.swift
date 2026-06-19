import SwiftUI
import AVFoundation

struct TTSSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore

    @Environment(\.dismiss) private var dismiss
    @State private var ttsPlayer = IOSTTSPlayer()

    @State private var systemSpeechRate: TTSSpeed = .normal

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
                        systemSettingsSection
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

            Color.clear
                .frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 10)
    }

    private var intro: some View {
        Text("当前可使用系统语音进行试听。云端语音服务暂未开放。")
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
            AmberSectionLabel(text: "语音服务")

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

            TTSSettingsNote("当前默认使用 \(sharedSettings.ttsProviders.first { $0.id == sharedSettings.selectedTTSProviderId }?.name ?? "系统 TTS")。")
        }
    }

    private var engineSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "语音引擎")
            AmberFormGroup {
                TTSPresetProviderRow(name: "系统 TTS", isSelected: true)
            }

            TTSSettingsNote("当前仅系统 TTS 可试听；云端 TTS 暂未开放。")
        }
    }

    private var systemSettingsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "系统 TTS 设置")
            AmberFormGroup {
                TTSMenuRow(title: "试听语速", value: systemSpeechRate.title) {
                    ForEach(TTSSpeed.allCases) { speed in
                        Button(speed.title) { systemSpeechRate = speed }
                    }
                }
            }

            TTSSettingsNote("语速只影响本机试听。")
        }
    }

    private var previewSection: some View {
        AmberFormGroup {
            Button {
                if ttsPlayer.isSpeaking {
                    ttsPlayer.stop()
                } else {
                    ttsPlayer.speak(
                        text: "你好，这是 AmberAgent 的语音试听。系统 TTS 可用。",
                        language: "zh-CN",
                        rate: systemSpeechRate.avSpeechRate
                    )
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
            AmberSectionLabel(text: "已有自定义 TTS 配置")
            AmberFormGroup {
                let engines = sharedSettings.savedTtsEngines
                if engines.isEmpty {
                    Text("暂无自定义云端引擎。云端 TTS 暂未开放。")
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
            }

            TTSSettingsNote("这里仅管理历史自定义记录。")
        }
        .padding(.top, 20)
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

    var avSpeechRate: Float {
        let defaultRate = AVSpeechUtteranceDefaultSpeechRate
        let rate: Float
        switch self {
        case .half:
            rate = defaultRate * 0.5
        case .slow:
            rate = defaultRate * 0.75
        case .normal:
            rate = defaultRate
        case .quick:
            rate = defaultRate * 1.25
        case .fast:
            rate = defaultRate * 1.5
        case .double:
            rate = defaultRate * 2
        }

        return min(max(rate, AVSpeechUtteranceMinimumSpeechRate), AVSpeechUtteranceMaximumSpeechRate)
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

#Preview {
    NavigationStack {
        TTSSettingsView(sharedSettings: IOSSharedSettingsStore())
    }
}

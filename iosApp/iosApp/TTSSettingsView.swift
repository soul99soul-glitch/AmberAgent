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
                        currentEngineSection
                        previewSection
                        cloudSection
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
        Text("iOS 当前使用系统语音合成。云端 TTS 配置会保留，但暂不参与试听或聊天朗读。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    private var currentEngineSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "当前引擎")

            AmberFormGroup {
                TTSCurrentEngineRow(
                    title: "系统 TTS",
                    subtitle: "使用 iOS AVSpeechSynthesizer，本机可直接试听",
                    status: "可用"
                )
            }
        }
    }

    private var previewSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "试听")
            AmberFormGroup {
                TTSMenuRow(title: "语速", value: systemSpeechRate.title) {
                    ForEach(TTSSpeed.allCases) { speed in
                        Button(speed.title) { systemSpeechRate = speed }
                    }
                }

                TTSSettingsDivider()

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
                    TTSPreviewRow(isSpeaking: ttsPlayer.isSpeaking)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(ttsPlayer.isSpeaking ? "停止试听" : "系统 TTS 试听")
            }
        }
    }

    private var cloudSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "云端 TTS")
            AmberFormGroup {
                TTSUnavailableCloudRow()

                let engines = sharedSettings.savedTtsEngines
                if !engines.isEmpty {
                    TTSSettingsDivider()
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

            TTSSettingsNote("这里仅保留历史自定义记录；接入播放链路后再启用选择和试听。")
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
                    .font(.body.weight(.semibold))
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

private struct TTSCurrentEngineRow: View {
    let title: String
    let subtitle: String
    let status: String

    var body: some View {
        HStack(spacing: 12) {
            TTSIcon(systemName: "speaker.wave.2.fill", tint: AmberTheme.accent)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(status)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.accentGreen)
                .padding(.horizontal, 8)
                .frame(height: 24)
                .background(AmberTheme.accentGreen.opacity(0.10), in: Capsule())
        }
        .frame(minHeight: 64)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
    }
}

private struct TTSPreviewRow: View {
    let isSpeaking: Bool

    var body: some View {
        HStack(spacing: 12) {
            TTSIcon(
                systemName: isSpeaking ? "stop.fill" : "play.fill",
                tint: isSpeaking ? AmberTheme.accentRed : AmberTheme.accent
            )

            VStack(alignment: .leading, spacing: 3) {
                Text(isSpeaking ? "停止试听" : "播放试听")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text(isSpeaking ? "系统语音正在朗读测试文本" : "用当前语速朗读一段中文测试文本")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 62)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct TTSUnavailableCloudRow: View {
    var body: some View {
        HStack(spacing: 12) {
            TTSIcon(systemName: "cloud.slash.fill", tint: AmberTheme.muted2)

            VStack(alignment: .leading, spacing: 3) {
                Text("云端 TTS 暂未接入")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("不会参与当前试听或聊天朗读；历史配置仅用于保留记录。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text("待接入")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(AmberTheme.muted)
                .padding(.horizontal, 8)
                .frame(height: 24)
                .background(AmberTheme.surface2.opacity(0.85), in: Capsule())
        }
        .frame(minHeight: 64)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
    }
}

private struct TTSIcon: View {
    let systemName: String
    let tint: Color

    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: 13, weight: .bold))
            .foregroundStyle(tint)
            .frame(width: 34, height: 34)
            .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(tint.opacity(0.13), lineWidth: 0.5)
            }
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

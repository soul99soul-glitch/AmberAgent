import SwiftUI
import Shared

struct ModelDefaultsView: View {
    @Bindable var settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    @Environment(\.dismiss) private var dismiss

    @State private var alert: ModelDefaultsAlert?

    /// Render a real seeded modelId pointer (Uuid) as a short prefix so the row
    /// shows actual KMP data (not a hardcoded label). The seed value is a random
    /// Uuid that does not resolve to a readable model name — shown as a prefix
    /// only, honestly labeled in the section note.
    private func modelIdPreview(_ id: KotlinUuid) -> String {
        let full = id.toHexDashString()
        return String(full.prefix(8)) + "…"
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            VStack(spacing: 0) {
                header

                ScrollView {
                    VStack(spacing: 0) {
                        intro
                        chatSection
                        auxiliarySection
                        advancedSection
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

            Text("默认模型")
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
        Text("当前 iOS 只接入全局默认聊天模型；标题、建议、识图、压缩、生图、默认思考和上下文策略尚未暴露真实设置桥。")
            .font(.footnote)
            .foregroundStyle(AmberTheme.muted)
            .lineSpacing(2)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 2)
    }

    private var chatSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "聊天")
            AmberFormGroup {
                ModelDefaultMenuRow(
                    systemImage: "cpu",
                    iconColor: AmberTheme.accent,
                    title: "聊天模型",
                    subtitle: "全局默认的聊天模型",
                    value: currentChatModel
                ) {
                    ForEach(chatModelOptions, id: \.self) { modelID in
                        Button(modelID) {
                            settingsStore.modelId = modelID
                        }
                    }
                }

                ModelDefaultsDivider()

                ModelDefaultStaticRow(
                    systemImage: "photo",
                    iconColor: AmberTheme.accentAmber,
                    title: "生图模型",
                    subtitle: "KMP Settings 已有 imageGenerationModelId 默认指针；iOS 生成工具入口未实现",
                    value: modelIdPreview(sharedSettings.imageGenerationModelId)
                )
            }
        }
    }

    private var auxiliarySection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "辅助任务")
            AmberFormGroup {
                auxiliaryRow(
                    systemImage: "text.alignleft",
                    title: "标题总结模型",
                    subtitle: "KMP Settings 已有 titleModelId 默认指针；iOS 标题生成入口未实现",
                    value: modelIdPreview(sharedSettings.titleModelId)
                )

                ModelDefaultsDivider()

                auxiliaryRow(
                    systemImage: "lightbulb",
                    title: "聊天建议模型",
                    subtitle: "KMP Settings 已有 suggestionModelId 默认指针；iOS 建议生成入口未实现",
                    value: modelIdPreview(sharedSettings.suggestionModelId)
                )

                ModelDefaultsDivider()

                auxiliaryRow(
                    systemImage: "eye",
                    title: "视觉识别模型",
                    subtitle: "KMP Settings 已有 ocrModelId 默认指针；iOS OCR 入口未实现",
                    value: modelIdPreview(sharedSettings.ocrModelId)
                )

                ModelDefaultsDivider()

                auxiliaryRow(
                    systemImage: "arrow.down.left.and.arrow.up.right",
                    title: "压缩模型",
                    subtitle: "KMP Settings 已有 compressModelId 默认指针；iOS 压缩入口未实现",
                    value: modelIdPreview(sharedSettings.compressModelId)
                )
            }

            ModelDefaultsNote("聊天模型菜单读取真实 Provider 模型并写回 SettingsStore.modelId。辅助任务 modelId 来自 KMP Settings seed（只读展示前缀），当前没有对应的 iOS 执行入口。")
        }
    }

    private var advancedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "高级")
            AmberFormGroup {
                ModelDefaultStaticRow(
                    systemImage: "clock",
                    iconColor: AmberTheme.accent,
                    title: "思考预算",
                    subtitle: "默认推理强度尚未持久化；Chat composer 使用当前会话状态",
                    value: "需渲染器"
                )

                ModelDefaultsDivider()

                ModelDefaultStaticRow(
                    systemImage: "bubble.left",
                    iconColor: AmberTheme.accentCyan,
                    title: "上下文消息数量",
                    subtitle: "真实上下文窗口/消息数策略还没有进入 iOS 请求构造",
                    value: "未消费"
                )

                ModelDefaultsDivider()

                Button {
                    alert = .groupDefaults
                } label: {
                    ModelDefaultRowContent(
                        systemImage: "point.3.connected.trianglepath.dotted",
                        iconColor: AmberTheme.accentGreen,
                        title: "模型组默认规则",
                        subtitle: "Android modelGroupSessionDefaults 尚未桥接到 iOS",
                        value: "缺设置桥"
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func auxiliaryRow(
        systemImage: String,
        title: String,
        subtitle: String,
        value: String,
        valueStyle: Color = AmberTheme.muted
    ) -> some View {
        ModelDefaultStaticRow(
            systemImage: systemImage,
            iconColor: AmberTheme.accentIndigo,
            title: title,
            subtitle: subtitle,
            value: value,
            valueStyle: valueStyle
        )
    }

    private var currentChatModel: String {
        let trimmed = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "gpt-4o" : trimmed
    }

    private var chatModelOptions: [String] {
        ([currentChatModel] + sharedSettings.chatModelIds + ["gpt-4o"]).reduce(into: [String]()) { result, modelID in
            if !result.contains(modelID) {
                result.append(modelID)
            }
        }
    }
}

private enum ModelDefaultsAlert: Identifiable {
    case groupDefaults

    var id: String { "group-defaults" }

    var title: String { "模型组默认规则需 Settings 持久化" }

    var message: String {
        "Android/KMP 有 modelGroupSessionDefaults；当前 iOS 未暴露对应设置桥，因此这里不会保存或应用模型组默认规则。"
    }
}

private struct ModelDefaultMenuRow<MenuContent: View>: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let value: String
    var showsChevron = true
    @ViewBuilder let menuContent: MenuContent

    var body: some View {
        Menu {
            menuContent
        } label: {
            ModelDefaultRowContent(
                systemImage: systemImage,
                iconColor: iconColor,
                title: title,
                subtitle: subtitle,
                value: value,
                showsChevron: showsChevron
            )
        }
    }
}

private struct ModelDefaultStaticRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let value: String
    var valueStyle: Color = AmberTheme.muted

    var body: some View {
        ModelDefaultRowContent(
            systemImage: systemImage,
            iconColor: iconColor,
            title: title,
            subtitle: subtitle,
            value: value,
            valueStyle: valueStyle,
            showsChevron: false
        )
    }
}

private struct ModelDefaultRowContent: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let value: String?
    var valueStyle: Color = AmberTheme.muted
    var showsChevron = true

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 32, height: 32)
                .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if let value {
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(valueStyle)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted2)
            }
        }
        .frame(minHeight: 58)
        .padding(.horizontal, 14)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
    }
}

private struct ModelDefaultsDivider: View {
    var body: some View {
        Divider()
            .overlay(AmberTheme.borderSoft)
            .padding(.leading, 58)
    }
}

private struct ModelDefaultsNote: View {
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
        ModelDefaultsView(settingsStore: SettingsStore(), sharedSettings: IOSSharedSettingsStore())
    }
}

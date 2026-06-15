import SwiftUI

struct ModelDefaultsView: View {
    @Bindable var settingsStore: SettingsStore
    @Environment(\.dismiss) private var dismiss

    @State private var imageModel = "未设置"
    @State private var titleModel = "跟随聊天模型"
    @State private var suggestionModel = "跟随聊天模型"
    @State private var ocrModel = "跟随聊天模型"
    @State private var compressModel = "跟随聊天模型"
    @State private var thinkingBudget = "自动"
    @State private var contextMessages = "无限制"
    @State private var alert: ModelDefaultsAlert?

    private let chatModelIDs = ["gpt-4o"]
    private let imageModels = ["未设置", "GPT-Image-1", "FLUX.1 Kontext", "Seedream 4.0"]
    private let auxiliaryModels = ["跟随聊天模型", "MiMo V2.5 Flash", "DeepSeek V4 Flash", "gpt-4o-mini"]
    private let thinkingOptions = ["关闭", "自动", "低", "中等", "高", "超高", "最大"]
    private let contextOptions = ["无限制", "10 条", "20 条", "40 条", "80 条"]

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
        Text("设定全局默认聊天模型，以及标题、建议、识图、压缩等辅助任务用哪个模型。辅助任务默认跟随聊天模型，也可单独指定更快、更省的模型。")
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

                ModelDefaultMenuRow(
                    systemImage: "photo",
                    iconColor: AmberTheme.accentAmber,
                    title: "生图模型",
                    subtitle: "用于在聊天中生成图片",
                    value: imageModel
                ) {
                    ForEach(imageModels, id: \.self) { model in
                        Button(model) {
                            imageModel = model
                        }
                    }
                }
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
                    subtitle: "用于自动生成会话标题",
                    value: titleModel,
                    selection: $titleModel
                )

                ModelDefaultsDivider()

                auxiliaryRow(
                    systemImage: "lightbulb",
                    title: "聊天建议模型",
                    subtitle: "生成对话建议，推荐快速、便宜的模型",
                    value: suggestionModel,
                    selection: $suggestionModel
                )

                ModelDefaultsDivider()

                auxiliaryRow(
                    systemImage: "eye",
                    title: "视觉识别模型",
                    subtitle: "聊天模型不能读图时用它理解图片",
                    value: ocrModel,
                    selection: $ocrModel
                )

                ModelDefaultsDivider()

                auxiliaryRow(
                    systemImage: "arrow.down.left.and.arrow.up.right",
                    title: "压缩模型",
                    subtitle: "压缩较长的对话历史",
                    value: compressModel,
                    selection: $compressModel
                )
            }

            ModelDefaultsNote("辅助任务默认跟随当前聊天模型；选「跟随聊天模型」即随主模型变化，也可固定到更快或更省的模型。每个辅助模型还可单独配置提示词与参数。")
        }
    }

    private var advancedSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "高级")
            AmberFormGroup {
                ModelDefaultMenuRow(
                    systemImage: "clock",
                    iconColor: AmberTheme.accent,
                    title: "思考预算",
                    subtitle: "控制推理强度",
                    value: thinkingBudget
                ) {
                    ForEach(thinkingOptions, id: \.self) { option in
                        Button(option) {
                            thinkingBudget = option
                        }
                    }
                }

                ModelDefaultsDivider()

                ModelDefaultMenuRow(
                    systemImage: "bubble.left",
                    iconColor: AmberTheme.accentCyan,
                    title: "上下文消息数量",
                    subtitle: "单次请求携带的历史消息条数",
                    value: contextMessages
                ) {
                    ForEach(contextOptions, id: \.self) { option in
                        Button(option) {
                            contextMessages = option
                        }
                    }
                }

                ModelDefaultsDivider()

                Button {
                    alert = .groupDefaults
                } label: {
                    ModelDefaultRowContent(
                        systemImage: "point.3.connected.trianglepath.dotted",
                        iconColor: AmberTheme.accentGreen,
                        title: "模型组默认规则",
                        subtitle: "新会话的模型组合默认规则",
                        value: nil
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
        selection: Binding<String>
    ) -> some View {
        ModelDefaultMenuRow(
            systemImage: systemImage,
            iconColor: AmberTheme.accentIndigo,
            title: title,
            subtitle: subtitle,
            value: value
        ) {
            ForEach(auxiliaryModels, id: \.self) { model in
                Button(model) {
                    selection.wrappedValue = model
                }
            }
        }
    }

    private var currentChatModel: String {
        settingsStore.modelId.isEmpty ? "gpt-4o" : settingsStore.modelId
    }

    private var chatModelOptions: [String] {
        ([currentChatModel] + chatModelIDs).reduce(into: [String]()) { result, modelID in
            if !result.contains(modelID) {
                result.append(modelID)
            }
        }
    }
}

private enum ModelDefaultsAlert: Identifiable {
    case groupDefaults

    var id: String { "group-defaults" }

    var title: String { "模型组默认规则尚未接线" }

    var message: String {
        "当前先还原默认模型原型；模型组 Session Defaults 会在真实模型选择器和参数表迁移后接入。"
    }
}

private struct ModelDefaultMenuRow<MenuContent: View>: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let value: String
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
                value: value
            )
        }
    }
}

private struct ModelDefaultRowContent: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    let value: String?

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
                    .foregroundStyle(AmberTheme.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
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
        ModelDefaultsView(settingsStore: SettingsStore())
    }
}

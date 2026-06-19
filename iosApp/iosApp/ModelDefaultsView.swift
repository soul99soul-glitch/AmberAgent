import SwiftUI
import Shared

enum ModelDefaultsChatModelSource {
    static func chatModelIds(for provider: ProviderSetting?) -> [String] {
        guard let provider else { return [] }
        var seen = Set<String>()
        var ids: [String] = []
        for model in provider.models where model.type == ModelType.chat {
            let modelID = model.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !modelID.isEmpty else { continue }
            guard seen.insert(modelID).inserted else { continue }
            ids.append(modelID)
        }
        return ids
    }
}

struct ModelDefaultsView: View {
    @Bindable var settingsStore: SettingsStore
    let sharedSettings: IOSSharedSettingsStore
    let providerRegistry: ProviderRegistryStore?
    @Environment(\.dismiss) private var dismiss

    init(
        settingsStore: SettingsStore,
        sharedSettings: IOSSharedSettingsStore,
        providerRegistry: ProviderRegistryStore? = nil
    ) {
        self.settingsStore = settingsStore
        self.sharedSettings = sharedSettings
        self.providerRegistry = providerRegistry
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
        Text("选择聊天默认使用的模型。这里会影响新的聊天生成；已有会话仍保留当时的模型记录。")
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
                if !hasConfiguredProvider {
                    ModelDefaultStaticRow(
                        systemImage: "key",
                        iconColor: AmberTheme.accentAmber,
                        title: "聊天模型",
                        subtitle: "先添加 API Key 并设为当前服务商",
                        value: "需要配置",
                        valueStyle: AmberTheme.accentAmber
                    )
                } else if chatModelOptions.isEmpty {
                    ModelDefaultTextFieldRow(
                        systemImage: "cpu",
                        iconColor: AmberTheme.accent,
                        title: "聊天模型",
                        subtitle: "当前服务商未提供模型列表，请填写服务商文档中的 Model ID",
                        text: $settingsStore.modelId,
                        placeholder: "例如 gpt-4o-mini"
                    )
                } else {
                    ModelDefaultMenuRow(
                        systemImage: "cpu",
                        iconColor: AmberTheme.accent,
                        title: "聊天模型",
                        subtitle: "只显示当前服务商可用的聊天模型",
                        value: currentChatModel
                    ) {
                        ForEach(chatModelOptions, id: \.self) { modelID in
                            Button(modelID) {
                                settingsStore.modelId = modelID
                            }
                        }
                    }
                }
            }

            ModelDefaultsNote(modelListNote)
        }
    }

    private var currentChatModel: String {
        let trimmed = settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "未选择" : trimmed
    }

    private var chatModelOptions: [String] {
        ModelDefaultsChatModelSource.chatModelIds(for: providerRegistry?.selectedProvider)
    }

    private var hasConfiguredProvider: Bool {
        if let providerRegistry, let selected = providerRegistry.selectedProvider {
            return providerRegistry.canSelect(selected)
        }
        return !settingsStore.apiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var modelListNote: String {
        guard hasConfiguredProvider else {
            return "添加 API Key 后，再选择当前服务商支持的聊天模型。"
        }
        guard let selected = providerRegistry?.selectedProvider else {
            return "当前配置没有可读取的服务商模型列表；请手动填写 Model ID。"
        }
        if chatModelOptions.isEmpty {
            return "\(selected.name) 没有提供可读取的聊天模型列表；请手动填写 Model ID。"
        }
        if !chatModelOptions.contains(settingsStore.modelId.trimmingCharacters(in: .whitespacesAndNewlines)) {
            return "当前 Model ID 不在 \(selected.name) 的模型列表中，请选择列表里的模型。"
        }
        return "模型列表来自当前已配置服务商：\(selected.name)。"
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

private struct ModelDefaultTextFieldRow: View {
    let systemImage: String
    let iconColor: Color
    let title: String
    let subtitle: String
    @Binding var text: String
    let placeholder: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(iconColor)
                .frame(width: 32, height: 32)
                .background(iconColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 9, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body)
                    .foregroundStyle(AmberTheme.foreground)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
                TextField(placeholder, text: $text)
                    .font(.system(size: 14, weight: .regular, design: .monospaced))
                    .foregroundStyle(AmberTheme.foreground)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: 78)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
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

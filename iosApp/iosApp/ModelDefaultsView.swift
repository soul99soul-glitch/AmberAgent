import SwiftUI
import Shared

private struct ModelDefaultOption: Identifiable {
    let id: String
    let providerName: String
    let modelId: String
    let displayName: String

    var title: String {
        displayName.isEmpty ? modelId : displayName
    }

    var menuTitle: String {
        "\(providerName) · \(title)"
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
                        AssistantParamsSection(sharedSettings: sharedSettings)
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
                if chatModelOptions.isEmpty {
                    ModelDefaultStaticRow(
                        systemImage: "cpu",
                        iconColor: AmberTheme.accent,
                        title: "聊天模型",
                        subtitle: "先在服务商详情自动获取或手动添加模型",
                        value: "未添加",
                        valueStyle: AmberTheme.accentAmber
                    )
                } else {
                    ModelDefaultMenuRow(
                        systemImage: "cpu",
                        iconColor: AmberTheme.accent,
                        title: "聊天模型",
                        subtitle: "来自已启用服务商的聊天模型",
                        value: currentChatModel
                    ) {
                        ForEach(chatModelOptions) { option in
                            Button(option.menuTitle) {
                                sharedSettings.setCurrentChatModelId(option.id)
                                sharedSettings.syncLegacySettingsStoreForCurrentChat(settingsStore)
                            }
                        }
                    }
                }
            }

            ModelDefaultsNote(modelListNote)
        }
    }

    private var currentChatModel: String {
        _ = sharedSettings.revision
        guard let model = sharedSettings.snapshot.getCurrentChatModel() else {
            return "未选择"
        }
        let displayName = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return displayName.isEmpty ? model.modelId : displayName
    }

    private var chatModelOptions: [ModelDefaultOption] {
        _ = sharedSettings.revision
        var seen = Set<String>()
        var options: [ModelDefaultOption] = []
        for provider in sharedSettings.snapshot.providers where provider.enabled && ChatProviderConfiguration.supportsChatStreaming(provider) {
            for model in provider.models where model.type == ModelType.chat {
                let uuid = model.id.description()
                guard seen.insert(uuid).inserted else { continue }
                let modelID = model.modelId.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !modelID.isEmpty else { continue }
                options.append(
                    ModelDefaultOption(
                        id: uuid,
                        providerName: provider.name,
                        modelId: modelID,
                        displayName: model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                    )
                )
            }
        }
        return options
    }

    private var enabledChatProviderCount: Int {
        _ = sharedSettings.revision
        return sharedSettings.snapshot.providers.filter {
            $0.enabled && ChatProviderConfiguration.supportsChatStreaming($0)
        }.count
    }

    private var modelListNote: String {
        if enabledChatProviderCount == 0 {
            return "先添加或启用 OpenAI-compatible / Anthropic-compatible 服务商。"
        }
        if chatModelOptions.isEmpty {
            return "进入服务商详情后，可自动获取模型；获取失败时也可以手动添加 Model ID。"
        }
        guard let model = sharedSettings.snapshot.getCurrentChatModel() else {
            return "选择一个模型后，新的聊天会默认使用它。"
        }
        if chatModelOptions.contains(where: { $0.id == model.id.description() }) {
            return "新的聊天会默认使用当前选择的模型。"
        }
        return "当前保存的模型不在已启用服务商列表中，请重新选择。"
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

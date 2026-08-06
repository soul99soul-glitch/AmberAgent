import SwiftUI

struct NovelCreationSettingsView: View {
    let sharedSettings: IOSSharedSettingsStore
    let viewModel: NovelCreationViewModel?
    var preferences: NovelCreationModelPreferences

    @State private var activePicker: NovelModelRole?
    @State private var revision = 0

    init(
        sharedSettings: IOSSharedSettingsStore,
        viewModel: NovelCreationViewModel?,
        preferences: NovelCreationModelPreferences = .shared
    ) {
        self.sharedSettings = sharedSettings
        self.viewModel = viewModel
        self.preferences = preferences
    }

    var body: some View {
        Form {
            Section {
                modelRow(for: .creation)
                modelRow(for: .stateSync)
                modelRow(for: .review)
            } header: {
                Text("默认模型")
            } footer: {
                Text("创作偏长文与文风；剧情同步偏稳定与结构化输出；审稿用来核对是否按计划写、前后是否打架。")
            }

            if let viewModel {
                Section("项目管理") {
                    NavigationLink {
                        NovelProjectManagementView(
                            sharedSettings: sharedSettings,
                            viewModel: viewModel
                        )
                    } label: {
                        NovelSettingsRow(
                            systemImage: "folder",
                            title: "管理项目",
                            value: "\(viewModel.projects.count) 个",
                            showsChevron: true
                        )
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(AmberTheme.background)
        .navigationTitle("小说创作设置")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $activePicker) { purpose in
            ComposerModelSheet(
                sharedSettings: sharedSettings,
                currentModel: selectedModelID(for: purpose),
                title: purpose.pickerTitle,
                fallbackTitle: "跟随当前聊天模型",
                onFallback: { setModelPolicy(.global, for: purpose) }
            ) { option in
                setFixedModel(option, for: purpose)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .task {
            guard let viewModel, viewModel.projects.isEmpty else { return }
            await viewModel.loadProjects()
        }
    }

    private func modelRow(for purpose: NovelModelRole) -> some View {
        NovelModelPolicyRow(
            purpose: purpose,
            value: modelName(for: purpose),
            action: { activePicker = purpose }
        )
    }

    private func modelName(for purpose: NovelModelRole) -> String {
        _ = revision
        _ = sharedSettings.revision
        let policy = preferences.policy(for: purpose)
        let name = NovelPresentation.modelDisplayName(for: policy, sharedSettings: sharedSettings)
        if case .global = policy { return "跟随聊天 · \(name)" }
        return name
    }

    private func selectedModelID(for purpose: NovelModelRole) -> String {
        _ = revision
        return NovelPresentation.selectedModelID(
            for: preferences.policy(for: purpose),
            sharedSettings: sharedSettings
        )
    }

    private func setFixedModel(_ option: ComposerModelOption, for purpose: NovelModelRole) {
        guard let providerID = NovelPresentation.providerID(
            forModelID: option.id,
            sharedSettings: sharedSettings
        ) else { return }
        setModelPolicy(.fixed(providerID: providerID, modelID: option.id), for: purpose)
    }

    private func setModelPolicy(_ policy: NovelProjectModelPolicy, for purpose: NovelModelRole) {
        preferences.set(policy, for: purpose)
        revision += 1
        activePicker = nil
    }
}

struct NovelModelPolicyRow: View {
    let purpose: NovelModelRole
    let value: String
    var isDisabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(purpose.title)
                        .foregroundStyle(AmberTheme.foreground)
                    Spacer(minLength: 12)
                    Text(value)
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                        .lineLimit(1)
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(AmberTheme.muted2)
                }
                Text(purpose.guidance)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isDisabled)
    }
}

extension NovelModelRole: Identifiable {
    var id: String { rawValue }

    var title: String {
        switch self {
        case .creation: "创作模型"
        case .stateSync: "剧情同步模型"
        case .review: "审稿模型"
        }
    }

    var guidance: String {
        switch self {
        case .creation: "优先选择擅长长文与创意写作的模型"
        case .stateSync: "优先选择稳定、便宜、结构化输出可靠的模型"
        case .review: "核对是否按计划写、查前后是否打架"
        }
    }

    var pickerTitle: String { "选择\(title)" }
}

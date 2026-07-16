import SwiftUI

struct NovelProjectManagementView: View {
    let sharedSettings: IOSSharedSettingsStore
    let viewModel: NovelCreationViewModel

    var body: some View {
        List {
            ForEach(viewModel.projects, id: \.id) { project in
                NavigationLink {
                    NovelProjectSettingsDetailView(
                        sharedSettings: sharedSettings,
                        viewModel: viewModel,
                        projectID: project.id
                    )
                } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(project.loadError == nil ? project.name : "无法读取的项目")
                            .foregroundStyle(AmberTheme.foreground)
                        Text(project.updatedAt, format: .relative(presentation: .named))
                            .font(.caption)
                            .foregroundStyle(AmberTheme.muted)
                    }
                }
                .disabled(
                    project.loadError != nil ||
                        (viewModel.isProjectSelectionBlocked &&
                            viewModel.selectedProjectID != project.id)
                )
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(AmberTheme.background)
        .navigationTitle("项目管理")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            if viewModel.projects.isEmpty {
                await viewModel.loadProjects()
            }
        }
    }
}

struct NovelProjectSettingsDetailView: View {
    let sharedSettings: IOSSharedSettingsStore
    let viewModel: NovelCreationViewModel
    let projectID: NovelProjectID

    @State private var activeSheet: NovelProjectSettingsDetailSheet?
    @State private var markdownDocument: NovelMarkdownFileDocument?
    @State private var markdownFileName = "Novel.md"
    @State private var isExportingMarkdown = false
    @State private var projectDocument: NovelProjectFileDocument?
    @State private var projectFileName = "Novel.ambernovel"
    @State private var isExportingProject = false

    var body: some View {
        Form {
            Section {
                modelRow(for: .creation)
                modelRow(for: .stateSync)
            } header: {
                Text("项目模型覆盖")
            } footer: {
                Text("不单独指定时使用小说创作设置中的默认模型。")
            }

            Section("项目") {
                if let project = currentProject {
                    Button { activeSheet = .renameProject } label: {
                        NovelSettingsRow(
                            systemImage: "pencil",
                            title: "项目名称",
                            value: project.name,
                            showsChevron: true
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(!viewModel.canMutate)

                    Button { activeSheet = .branches } label: {
                        NovelSettingsRow(
                            systemImage: "arrow.triangle.branch",
                            title: "当前分支",
                            value: viewModel.branchSnapshot?.branch.name ?? "读取分支",
                            showsChevron: true
                        )
                    }
                    .buttonStyle(.plain)
                } else {
                    ProgressView("正在读取项目")
                }
            }

            Section("管理") {
                Button(action: exportProject) {
                    Label("导出项目包", systemImage: "archivebox")
                }
                .disabled(currentProject == nil || viewModel.isPerforming)

                Button(action: exportMarkdown) {
                    Label("导出正文", systemImage: "square.and.arrow.up")
                }
                .disabled(currentProject == nil || viewModel.isPerforming)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AmberTheme.background)
        .navigationTitle(currentProject?.name ?? "项目设置")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $activeSheet, content: sheetContent)
        .fileExporter(
            isPresented: $isExportingProject,
            document: projectDocument,
            contentType: .amberNovelProject,
            defaultFilename: projectFileName,
            onCompletion: handleExportResult
        )
        .fileExporter(
            isPresented: $isExportingMarkdown,
            document: markdownDocument,
            contentType: .amberMarkdown,
            defaultFilename: markdownFileName,
            onCompletion: handleExportResult
        )
        .task(id: projectID) {
            guard viewModel.selectedProjectID != projectID || viewModel.projectSnapshot == nil else {
                return
            }
            await viewModel.selectProject(projectID)
        }
    }

    private var currentProject: NovelProjectRecord? {
        guard let project = viewModel.projectSnapshot?.project, project.id == projectID else {
            return nil
        }
        return project
    }

    private func modelRow(for purpose: NovelModelRole) -> some View {
        NovelModelPolicyRow(
            purpose: purpose,
            value: modelName(for: purpose),
            isDisabled: currentProject == nil || !viewModel.canMutate,
            action: { activeSheet = .modelPicker(purpose) }
        )
    }

    @ViewBuilder
    private func sheetContent(_ sheet: NovelProjectSettingsDetailSheet) -> some View {
        switch sheet {
        case .modelPicker(let purpose):
            ComposerModelSheet(
                sharedSettings: sharedSettings,
                currentModel: selectedModelID(for: purpose),
                title: purpose.pickerTitle,
                fallbackTitle: "跟随小说默认",
                onFallback: { setModelPolicy(.global, for: purpose) }
            ) { option in
                setFixedModel(option, for: purpose)
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)

        case .renameProject:
            if let project = currentProject {
                NovelProjectRenameSheet(
                    viewModel: viewModel,
                    currentName: project.name,
                    canRename: viewModel.canMutate
                )
            }

        case .branches:
            NavigationStack {
                NovelBranchesView(
                    viewModel: viewModel,
                    isSelectionDisabled: viewModel.isPerforming,
                    onSelect: { branchID in
                        Task { @MainActor in await viewModel.selectBranch(branchID) }
                    },
                    onRename: { transition(to: .renameBranch($0)) },
                    onFork: { transition(to: .forkBranch($0)) },
                    onEditOverride: { transition(to: .branchOverride($0)) }
                )
                .navigationTitle("分支管理")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { activeSheet = nil }
                    }
                }
            }

        case .renameBranch(let branch):
            NovelBranchRenameSheet(viewModel: viewModel, branch: branch)

        case .forkBranch(let branch):
            NovelBranchForkSheet(viewModel: viewModel, branch: branch)

        case .branchOverride(let material):
            NovelBranchOverrideEditorSheet(viewModel: viewModel, material: material)
        }
    }

    private func configuredPolicy(for purpose: NovelModelRole) -> NovelProjectModelPolicy {
        currentProject?.configuredModelPolicy(for: purpose) ?? .global
    }

    private func effectivePolicy(for purpose: NovelModelRole) -> NovelProjectModelPolicy {
        let configured = configuredPolicy(for: purpose)
        guard case .global = configured else { return configured }
        return NovelCreationModelPreferences.shared.policy(for: purpose)
    }

    private func modelName(for purpose: NovelModelRole) -> String {
        _ = sharedSettings.revision
        let configured = configuredPolicy(for: purpose)
        let name = NovelPresentation.modelDisplayName(
            for: effectivePolicy(for: purpose),
            sharedSettings: sharedSettings
        )
        if case .global = configured { return "小说默认 · \(name)" }
        return name
    }

    private func selectedModelID(for purpose: NovelModelRole) -> String {
        NovelPresentation.selectedModelID(
            for: effectivePolicy(for: purpose),
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
        activeSheet = nil
        Task { @MainActor in
            await viewModel.setModelPolicy(policy, for: purpose)
        }
    }

    private func transition(to sheet: NovelProjectSettingsDetailSheet) {
        activeSheet = nil
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(250))
            activeSheet = sheet
        }
    }

    private func exportMarkdown() {
        Task { @MainActor in
            guard let artifact = await viewModel.exportBranchMarkdown() else {
                return
            }
            markdownDocument = NovelMarkdownFileDocument(markdown: artifact.markdown)
            markdownFileName = artifact.fileName
            isExportingMarkdown = true
        }
    }

    private func exportProject() {
        Task { @MainActor in
            guard let artifact = await viewModel.exportProjectPackage() else { return }
            projectDocument = NovelProjectFileDocument(data: artifact.data)
            let stem = NovelPresentation.fileName(artifact.projectName, fallback: "Novel")
            projectFileName = "\(stem).ambernovel"
            isExportingProject = true
        }
    }

    private func handleExportResult(_ result: Result<URL, Error>) {
        if case .failure(let error) = result {
            viewModel.presentError(error)
        }
    }
}

private enum NovelProjectSettingsDetailSheet: Identifiable {
    case modelPicker(NovelModelRole)
    case renameProject
    case branches
    case renameBranch(NovelBranchRecord)
    case forkBranch(NovelBranchRecord)
    case branchOverride(NovelMaterialRecord)

    var id: String {
        switch self {
        case .modelPicker(let purpose): "model-\(purpose.rawValue)"
        case .renameProject: "rename-project"
        case .branches: "branches"
        case .renameBranch(let branch): "rename-branch-\(branch.id)"
        case .forkBranch(let branch): "fork-branch-\(branch.id)"
        case .branchOverride(let material): "branch-override-\(material.id)"
        }
    }
}

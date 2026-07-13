import SwiftUI
import UniformTypeIdentifiers

extension View {
    func novelCreationErrorAlert(viewModel: NovelCreationViewModel) -> some View {
        modifier(NovelCreationErrorAlertModifier(viewModel: viewModel))
    }
}

private struct NovelCreationErrorAlertModifier: ViewModifier {
    let viewModel: NovelCreationViewModel

    func body(content: Content) -> some View {
        content.alert(
            viewModel.errorMessage == nil && viewModel.hasReloadRequirement
                ? "操作已完成"
                : "无法完成操作",
            isPresented: errorBinding
        ) {
            if viewModel.errorMessage == nil && viewModel.hasReloadRequirement {
                Button("重新载入") {
                    Task { @MainActor in
                        await viewModel.retryCommittedMutationReload()
                    }
                }
                Button("稍后", role: .cancel) { viewModel.clearError() }
            } else {
                Button("好") { viewModel.clearError() }
            }
        } message: {
            Text(viewModel.presentedMessage ?? "未知错误")
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.presentedMessage != nil },
            set: { isPresented in
                if !isPresented { viewModel.clearError() }
            }
        )
    }
}

struct NovelProjectListView: View {
    let viewModel: NovelCreationViewModel
    var loadsOnAppear = true
    let onOpen: (NovelProjectID) -> Void

    @State private var activeSheet: NovelProjectListSheet?
    @State private var pendingDelete: NovelProjectDeleteCandidate?
    @State private var isImportingPackage = false

    var body: some View {
        Group {
            if viewModel.projects.isEmpty && !viewModel.isLoading {
                emptyState
            } else {
                projectList
            }
        }
        .background(AmberTheme.background.ignoresSafeArea())
        .safeAreaInset(edge: .top, spacing: 0) {
            if viewModel.hasReloadRequirement {
                reloadRequirementBanner
            }
        }
        .navigationTitle("小说创作")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    isImportingPackage = true
                } label: {
                    Image(systemName: "square.and.arrow.down")
                }
                .accessibilityLabel("导入小说项目")

                Button {
                    activeSheet = .create
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("新建小说项目")
            }
        }
        .overlay {
            if viewModel.isLoading && viewModel.projects.isEmpty {
                ProgressView("正在读取项目")
                    .padding(18)
                    .amberGlass(cornerRadius: AmberTheme.radiusLarge, interactive: false)
            }
        }
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .create:
                NovelProjectCreateSheet(viewModel: viewModel) { projectID in
                    onOpen(projectID)
                }
            case .rename(let project):
                NovelProjectRenameSheet(viewModel: viewModel, project: project)
            case .importPackage(let data, let preview):
                NovelProjectImportSheet(
                    viewModel: viewModel,
                    packageData: data,
                    preview: preview
                ) { projectID in
                    onOpen(projectID)
                }
            }
        }
        .fileImporter(
            isPresented: $isImportingPackage,
            allowedContentTypes: [.amberNovelProject],
            allowsMultipleSelection: false,
            onCompletion: handlePackageImport
        )
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text("删除《\(candidate.project.name)》？"),
                message: Text(deleteConfirmationMessage(for: candidate.project)),
                primaryButton: .destructive(Text(
                    hasRunningRun(for: candidate.project.id) ? "停止生成并删除" : "删除"
                )) {
                    delete(candidate.project)
                },
                secondaryButton: .cancel()
            )
        }
        .task {
            guard loadsOnAppear else { return }
            await viewModel.loadProjects()
        }
        .refreshable {
            await viewModel.loadProjects()
        }
    }

    private var reloadRequirementBanner: some View {
        HStack(spacing: 12) {
            Label("有已提交的操作等待重新载入", systemImage: "arrow.clockwise.circle")
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.accentAmber)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("重新载入") {
                Task { @MainActor in
                    await viewModel.retryCommittedMutationReload()
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .disabled(viewModel.isPerforming)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
        .background(AmberTheme.surface)
    }

    private var projectList: some View {
        List {
            Section {
                ForEach(viewModel.projects, id: \.id) { project in
                    Button {
                        onOpen(project.id)
                    } label: {
                        NovelProjectRow(project: project)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(AmberTheme.surface)
                    .listRowSeparatorTint(AmberTheme.borderSoft)
                    .contextMenu {
                        if !project.isDegraded {
                            Button {
                                prepareRename(project)
                            } label: {
                                Label("重命名", systemImage: "pencil")
                            }
                        }

                        Button(role: .destructive) {
                            prepareDelete(project)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }
                    }
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button(role: .destructive) {
                            prepareDelete(project)
                        } label: {
                            Label("删除", systemImage: "trash")
                        }

                        if !project.isDegraded {
                            Button {
                                prepareRename(project)
                            } label: {
                                Label("重命名", systemImage: "pencil")
                            }
                            .tint(AmberTheme.accentIndigo)
                        }
                    }
                }
            } header: {
                Text("项目")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground2)
                    .textCase(nil)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 8, for: .scrollContent)
    }

    private var emptyState: some View {
        ContentUnavailableView {
            Label("开始一本新小说", systemImage: "text.book.closed")
        } description: {
            Text("项目会独立保存设定、正文、分支和创作记录。")
        } actions: {
            Button("新建项目") {
                activeSheet = .create
            }
            .buttonStyle(.borderedProminent)

            Button("导入项目") {
                isImportingPackage = true
            }
            .buttonStyle(.bordered)
        }
    }

    private func prepareRename(_ project: NovelProjectSummary) {
        Task { @MainActor in
            await viewModel.selectProject(project.id)
            guard viewModel.projectSnapshot?.project.id == project.id else { return }
            activeSheet = .rename(project)
        }
    }

    private func prepareDelete(_ project: NovelProjectSummary) {
        Task { @MainActor in
            await viewModel.selectProject(project.id)
            guard viewModel.projectSnapshot?.project.id == project.id else { return }
            pendingDelete = NovelProjectDeleteCandidate(project: project)
        }
    }

    private func delete(_ project: NovelProjectSummary) {
        Task { @MainActor in
            guard viewModel.selectedProjectID == project.id else { return }
            guard await viewModel.stopActiveRunsForProjectOperation(projectID: project.id) else { return }
            await viewModel.deleteProject()
        }
    }

    private func hasRunningRun(for projectID: NovelProjectID) -> Bool {
        guard viewModel.projectSnapshot?.project.id == projectID else { return false }
        return viewModel.projectSnapshot?.activeRuns.contains(where: { $0.status == .running }) == true ||
            (viewModel.quickStartStartingProjectID == projectID && viewModel.quickStartStartingRun != nil)
    }

    private func deleteConfirmationMessage(for project: NovelProjectSummary) -> String {
        if hasRunningRun(for: project.id) {
            return "Agent 正在生成。将先停止生成并保存终止状态，再删除项目。项目包未导出时无法恢复。"
        }
        return "项目包未导出时无法从应用内恢复。"
    }

    private func handlePackageImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else {
            if case .failure(let error) = result { viewModel.presentError(error) }
            return
        }
        Task { @MainActor in
            do {
                let data = try await Task.detached(priority: .userInitiated) {
                    try NovelProjectFileReader.readPackage(from: url)
                }.value
                guard let preview = await viewModel.previewImport(data) else { return }
                activeSheet = .importPackage(data: data, preview: preview)
            } catch {
                viewModel.presentError(error)
            }
        }
    }
}

private struct NovelProjectRow: View {
    let project: NovelProjectSummary

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: project.isDegraded ? "exclamationmark.book.closed" : "text.book.closed")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(project.isDegraded ? AmberTheme.accentAmber : AmberTheme.accent)
                .frame(width: 36, height: 36)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 4) {
                Text(project.name)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)

                HStack(spacing: 6) {
                    Text(project.updatedAt, format: .relative(presentation: .named))
                    if project.isDegraded {
                        Text("只读恢复")
                            .foregroundStyle(AmberTheme.accentAmber)
                    }
                }
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.semibold))
                .foregroundStyle(AmberTheme.muted2)
        }
        .frame(minHeight: 58)
        .padding(.vertical, 3)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

private enum NovelProjectListSheet: Identifiable {
    case create
    case rename(NovelProjectSummary)
    case importPackage(data: Data, preview: NovelProjectImportPreview)

    var id: String {
        switch self {
        case .create: "create"
        case .rename(let project): "rename-\(project.id)"
        case .importPackage(_, let preview): "import-\(preview.sourceProjectID)-\(preview.projectSHA256)"
        }
    }
}

private struct NovelProjectDeleteCandidate: Identifiable {
    let project: NovelProjectSummary
    var id: NovelProjectID { project.id }
}

private struct NovelProjectCreateSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let onCreated: (NovelProjectID) -> Void

    @State private var mode: NovelProjectCreationMode = .blank
    @State private var name = ""
    @State private var genre = ""
    @State private var coreIdea = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("创建方式") {
                    Picker("创建方式", selection: $mode) {
                        Text("空白项目").tag(NovelProjectCreationMode.blank)
                        Text("快速开始").tag(NovelProjectCreationMode.quickStart)
                    }
                    .pickerStyle(.segmented)
                }

                Section("项目") {
                    TextField("小说名称", text: $name)
                        .textInputAutocapitalization(.never)
                }

                if mode == .quickStart {
                    Section {
                        TextField("题材，例如：近未来悬疑", text: $genre)
                        TextEditor(text: $coreIdea)
                            .frame(minHeight: 120)
                            .overlay(alignment: .topLeading) {
                                if coreIdea.isEmpty {
                                    Text("核心想法")
                                        .foregroundStyle(.secondary)
                                        .padding(.horizontal, 5)
                                        .padding(.vertical, 8)
                                        .allowsHitTesting(false)
                                }
                            }
                    } header: {
                        Text("故事种子")
                    } footer: {
                        Text("AI 只会生成建议，确认后才写入项目设定。")
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("新建小说")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(viewModel.isPerforming)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") { create() }
                        .disabled(!canCreate || viewModel.isPerforming)
                }
            }
        }
        .interactiveDismissDisabled(viewModel.isPerforming)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private var canCreate: Bool {
        let hasName = !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let hasSeed = mode == .blank || (
            !genre.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
                !coreIdea.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        )
        return hasName && hasSeed
    }

    private func create() {
        Task { @MainActor in
            guard let projectID = await viewModel.createProject(
                name: name,
                branchName: "主线",
                mode: mode,
                genre: genre,
                coreIdea: coreIdea
            ) else { return }
            dismiss()
            onCreated(projectID)
        }
    }
}

private struct NovelProjectRenameSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let project: NovelProjectSummary
    @State private var name: String

    init(viewModel: NovelCreationViewModel, project: NovelProjectSummary) {
        self.viewModel = viewModel
        self.project = project
        self._name = State(initialValue: project.name)
    }

    var body: some View {
        NavigationStack {
            Form {
                TextField("小说名称", text: $name)
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("重命名项目")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                        .disabled(
                            project.isDegraded ||
                                name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        )
                }
            }
        }
        .presentationDetents([.height(220)])
        .presentationDragIndicator(.visible)
    }

    private func save() {
        Task { @MainActor in
            await viewModel.renameProject(name)
            guard viewModel.errorMessage == nil else { return }
            dismiss()
        }
    }
}

struct NovelProjectImportSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let packageData: Data
    let preview: NovelProjectImportPreview
    let onImported: (NovelProjectID) -> Void
    @State private var isConfirmingReplace = false

    var body: some View {
        NavigationStack {
            List {
                Section("项目包") {
                    LabeledContent("名称", value: preview.projectName)
                    LabeledContent("项目版本", value: "\(preview.projectRevision)")
                    LabeledContent(
                        "项目大小",
                        value: ByteCountFormatter.string(
                            fromByteCount: Int64(preview.projectByteCount),
                            countStyle: .file
                        )
                    )
                    if preview.runningRunCount > 0 {
                        LabeledContent("未完成生成", value: "\(preview.runningRunCount)")
                            .foregroundStyle(AmberTheme.accentAmber)
                    }
                }

                if let existing = preview.existingProject {
                    Section("本地冲突") {
                        Text("本地已有《\(existing.name)》。可替换它，或作为副本保留两份。")
                            .font(.subheadline)
                            .foregroundStyle(AmberTheme.foreground2)

                        Button(role: .destructive) {
                            isConfirmingReplace = true
                        } label: {
                            Label("替换本地项目", systemImage: "arrow.triangle.2.circlepath")
                        }

                        Button {
                            importProject(.keepBoth)
                        } label: {
                            Label("保留两份", systemImage: "plus.square.on.square")
                        }
                    }
                } else {
                    Section {
                        Button {
                            importProject(.reject)
                        } label: {
                            Label("导入项目", systemImage: "square.and.arrow.down")
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("导入预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(viewModel.isPerforming)
                }
            }
            .disabled(viewModel.isPerforming)
            .overlay {
                if viewModel.isPerforming { ProgressView() }
            }
        }
        .interactiveDismissDisabled(viewModel.isPerforming)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .confirmationDialog(
            "替换本地项目？",
            isPresented: $isConfirmingReplace,
            titleVisibility: .visible
        ) {
            Button("停止生成并替换", role: .destructive) {
                importProject(.replace, stoppingActiveRun: true)
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("如果本地项目正在生成，会先停止并保存终止状态，再用导入包替换。")
        }
    }

    private func importProject(
        _ choice: NovelProjectImportChoice,
        stoppingActiveRun: Bool = false
    ) {
        Task { @MainActor in
            viewModel.clearError()
            if stoppingActiveRun {
                guard await viewModel.stopActiveRunsForProjectOperation(
                    projectID: preview.sourceProjectID
                ) else { return }
            }
            switch await viewModel.importProject(packageData, choice: choice) {
            case .selected(let projectID):
                dismiss()
                onImported(projectID)
            case .committedNeedsReload:
                dismiss()
            case nil:
                break
            }
        }
    }
}

#if DEBUG
private actor NovelProjectListPreviewCreation: NovelCreation {
    func snapshot(_ scope: NovelSnapshotScope) async throws -> NovelSnapshot {
        _ = scope
        throw NovelError.generationUnavailable
    }

    func perform(_ action: NovelAction) async throws -> NovelOutcome {
        _ = action
        throw NovelError.generationUnavailable
    }

    func start(_ request: NovelRunRequest) async throws -> NovelRun {
        _ = request
        throw NovelError.generationUnavailable
    }

    func interruptForBackground(
        projectID: NovelProjectID,
        deadline: Date,
        runID: NovelRunID?
    ) async {
        _ = projectID
        _ = deadline
        _ = runID
    }

    func retryPendingTerminal(runID: NovelRunID) async throws {
        _ = runID
        throw NovelError.generationUnavailable
    }
}

@MainActor
private enum NovelProjectListPreviewFixtures {
    static func viewModel(
        populated: Bool = false,
        degraded: Bool = false,
        error: String? = nil
    ) -> NovelCreationViewModel {
        let viewModel = NovelCreationViewModel(
            creation: NovelProjectListPreviewCreation()
        )
        if populated || degraded {
            if degraded {
                viewModel.projects = [NovelProjectSummary(
                    unavailableProjectID: NovelProjectID(),
                    error: NovelError.storageUnavailable("主文件损坏，已载入上一个版本")
                )]
            } else if let document = try? NovelReducer.createProject(
                NovelCreateProjectCommand(
                    context: NovelMutationContext(
                        operationID: NovelOperationID(),
                        expectedProjectRevision: nil,
                        expectedConfigRevision: nil,
                        expectedBranchHeadRevision: nil
                    ),
                    projectID: NovelProjectID(),
                    branchID: NovelBranchID(),
                    sessionID: NovelSessionID(),
                    initialStateSnapshotID: NovelStateSnapshotID(),
                    initialCheckpointID: NovelCheckpointID(),
                    name: "潮汐城没有月亮",
                    branchName: "主线",
                    creationMode: .blank,
                    quickStartSeed: nil
                ),
                now: Date()
            ).document {
                viewModel.projects = [NovelProjectSummary(document: document)]
            }
        }
        viewModel.errorMessage = error
        return viewModel
    }
}

@MainActor
private struct NovelProjectListPreviewScreen: View {
    let viewModel: NovelCreationViewModel

    var body: some View {
        NavigationStack {
            NovelProjectListView(
                viewModel: viewModel,
                loadsOnAppear: false,
                onOpen: { _ in }
            )
        }
        .tint(AmberTheme.accent)
        .novelCreationErrorAlert(viewModel: viewModel)
    }
}

#Preview("Novel Projects - Empty") {
    NovelProjectListPreviewScreen(viewModel: NovelProjectListPreviewFixtures.viewModel())
}

#Preview("Novel Projects - Populated") {
    NovelProjectListPreviewScreen(
        viewModel: NovelProjectListPreviewFixtures.viewModel(populated: true)
    )
}

#Preview("Novel Projects - Degraded") {
    NovelProjectListPreviewScreen(
        viewModel: NovelProjectListPreviewFixtures.viewModel(degraded: true)
    )
}

#Preview("Novel Projects - Error") {
    NovelProjectListPreviewScreen(
        viewModel: NovelProjectListPreviewFixtures.viewModel(error: "无法读取小说项目目录。")
    )
}

#Preview("Novel Projects - Accessibility") {
    NovelProjectListPreviewScreen(
        viewModel: NovelProjectListPreviewFixtures.viewModel(populated: true)
    )
    .environment(\.dynamicTypeSize, .accessibility3)
}
#endif

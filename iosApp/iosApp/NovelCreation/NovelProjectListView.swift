import SwiftUI
import UniformTypeIdentifiers

enum NovelCreationToolbarID {
    static let importProject = "novel-creation.import"
    static let settings = "novel-creation.settings"
}

struct NovelCreationSettingsToolbarButton: View {
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "gearshape")
                // Chrome icons stay neutral (label/ink). Accent is for primary CTAs only.
                .foregroundStyle(AmberTheme.foreground)
        }
        .accessibilityLabel("小说创作设置")
    }
}

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
    let onOpenSettings: () -> Void

    @State private var activeSheet: NovelProjectListSheet?
    @State private var pendingDelete: NovelProjectDeleteCandidate?
    @State private var isImportingPackage = false
    @State private var isPreparingImportPreview = false
    @State private var deletingProjectID: NovelProjectID?

    var body: some View {
        Group {
            if viewModel.projects.isEmpty && !viewModel.isLoading {
                emptyState
            } else {
                projectList
            }
        }
        .background { AmberThemePageBackground(surface: .app) }
        .safeAreaInset(edge: .top, spacing: 0) {
            if viewModel.isContinuityOperationRunning {
                continuityOperationBanner
            } else if viewModel.isStateSyncOperationRunning {
                stateSyncSelectionBanner
            } else if viewModel.hasReloadRequirement {
                reloadRequirementBanner
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !viewModel.projects.isEmpty {
                createProjectAction
            }
        }
        .navigationTitle("小说创作")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(id: NovelCreationToolbarID.importProject, placement: .topBarTrailing) {
                Button {
                    isImportingPackage = true
                } label: {
                    Image(systemName: "square.and.arrow.down")
                        .foregroundStyle(AmberTheme.foreground)
                }
                .accessibilityLabel("导入小说项目或工作区")
                .disabled(viewModel.isProjectSelectionBlocked || isPreparingImportPreview)
            }

            ToolbarSpacer(.fixed, placement: .topBarTrailing)

            ToolbarItem(id: NovelCreationToolbarID.settings, placement: .topBarTrailing) {
                NovelCreationSettingsToolbarButton(action: onOpenSettings)
            }
        }
        .overlay {
            if isPreparingImportPreview {
                ProgressView("正在检查导入包")
                    .font(.subheadline)
                    .foregroundStyle(AmberTheme.muted)
                    .tint(AmberTheme.accent)
            } else if viewModel.projects.isEmpty && viewModel.isLoading {
                ProgressView {
                    Text("正在读取项目")
                        .font(.subheadline)
                        .foregroundStyle(AmberTheme.muted)
                }
                .tint(AmberTheme.accent)
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
            allowedContentTypes: [.amberNovelProject, .folder],
            allowsMultipleSelection: false,
            onCompletion: handlePackageImport
        )
        .alert(item: $pendingDelete) { candidate in
            Alert(
                title: Text(deleteConfirmationTitle(for: candidate.project)),
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
            await viewModel.loadProjects(restoresSelection: false)
        }
        .refreshable {
            await viewModel.loadProjects(restoresSelection: false)
        }
    }

    private var reloadRequirementBanner: some View {
        HStack(spacing: 12) {
            Label("有已提交的操作等待重新载入", systemImage: "arrow.clockwise.circle")
                .font(.footnote.weight(.medium))
                .foregroundStyle(AmberTheme.foreground2)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("重新载入") {
                Task { @MainActor in
                    await viewModel.retryCommittedMutationReload()
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
            .disabled(viewModel.isPerforming)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
        .background(AmberTheme.surface)
    }

    private var createProjectAction: some View {
        HStack {
            Spacer()

            Button {
                activeSheet = .create
            } label: {
                Label("新建", systemImage: "plus")
                    .font(.body.weight(.semibold))
                    .padding(.horizontal, 6)
            }
            .buttonStyle(.glassProminent)
            .buttonBorderShape(.capsule)
            .controlSize(.large)
            .accessibilityLabel("新建小说项目")
            .disabled(viewModel.isProjectSelectionBlocked)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
    }

    private var stateSyncSelectionBanner: some View {
        NovelStateSyncProgressBanner(
            title: stateSyncSelectionBannerTitle,
            activity: isCurrentStateSyncStopping ? nil : viewModel.stateSyncActivity,
            secondaryHint: isCurrentStateSyncStopping
                ? "停止完成后即可切换项目。"
                : "完成前暂不能切换项目。",
            canStop: {
                guard let projectID = viewModel.selectedProjectID,
                      let branchID = viewModel.selectedBranchID else { return false }
                return viewModel.canCancelAutomaticStateSync(
                    projectID: projectID,
                    branchID: branchID
                )
            }(),
            onStop: {
                guard let projectID = viewModel.selectedProjectID,
                      let branchID = viewModel.selectedBranchID else { return }
                viewModel.cancelAutomaticStateSync(
                    projectID: projectID,
                    branchID: branchID
                )
            }
        )
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
        .background(AmberTheme.surface)
    }

    private var isCurrentStateSyncStopping: Bool {
        guard let projectID = viewModel.selectedProjectID,
              let branchID = viewModel.selectedBranchID else { return false }
        return viewModel.isStateSyncStopping(projectID: projectID, branchID: branchID)
    }

    private var stateSyncSelectionBannerTitle: String {
        if let projectID = viewModel.selectedProjectID,
           let branchID = viewModel.selectedBranchID,
           let title = viewModel.stateSyncStatusTitle(
               projectID: projectID,
               branchID: branchID
           ) {
            return title
        }
        return viewModel.stateSyncActivity == nil
            ? "正在准备剧情状态同步"
            : "正在同步剧情状态"
    }

    private var continuityOperationBanner: some View {
        HStack(spacing: 10) {
            ProgressView()
                .controlSize(.small)
                .tint(AmberTheme.accent)
            VStack(alignment: .leading, spacing: 2) {
                Text(viewModel.continuityOperationTitle)
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(AmberTheme.foreground2)
                Text("完成前暂不能切换项目。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button("停止") {
                viewModel.cancelContinuityAudit()
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 9)
        .background(AmberTheme.surface)
    }

    private var projectList: some View {
        List {
            Section {
                ForEach(viewModel.projects, id: \.id) { project in
                    HStack(spacing: 10) {
                        Button {
                            if project.loadError != nil {
                                retryOpening(project)
                            } else {
                                onOpen(project.id)
                            }
                        } label: {
                            NovelProjectRow(project: project)
                        }
                        .buttonStyle(.plain)

                        if deletingProjectID == project.id {
                            ProgressView()
                                .controlSize(.small)
                                .tint(AmberTheme.accent)
                                .accessibilityLabel("正在删除项目")
                        } else if project.loadError != nil {
                            Button("重新读取") {
                                retryOpening(project)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                    }
                    .disabled(viewModel.isProjectSelectionBlocked)
                    .listRowInsets(EdgeInsets(top: 6, leading: 22, bottom: 6, trailing: 22))
                    .listRowBackground(AmberTheme.background)
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
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .contentMargins(.top, 0, for: .scrollContent)
    }

    private var emptyState: some View {
        Group {
            if let loadError = viewModel.projectListLoadError {
                ContentUnavailableView {
                    Label("项目没有读取出来", systemImage: "exclamationmark.arrow.triangle.2.circlepath")
                } description: {
                    Text(loadError)
                } actions: {
                    Button("重新读取") {
                        Task { @MainActor in
                            await viewModel.loadProjects(restoresSelection: false)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.isLoading)
                }
            } else {
                ContentUnavailableView {
                    Label("开始一本新小说", systemImage: "text.book.closed")
                } description: {
                    Text("项目会独立保存设定、正文、分支和创作记录。")
                } actions: {
                    Button("新建项目") {
                        activeSheet = .create
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.isProjectSelectionBlocked)

                    Button("导入项目") {
                        isImportingPackage = true
                    }
                    .buttonStyle(.bordered)
                    .disabled(viewModel.isProjectSelectionBlocked)
                }
            }
        }
    }

    private func prepareRename(_ project: NovelProjectSummary) {
        Task { @MainActor in
            await viewModel.selectProject(project.id)
            guard viewModel.projectSnapshot?.project.id == project.id else { return }
            activeSheet = .rename(project)
        }
    }

    private func retryOpening(_ project: NovelProjectSummary) {
        Task { @MainActor in
            await viewModel.loadProjects(selecting: project.id)
            guard viewModel.projects.first(where: { $0.id == project.id })?.loadError == nil,
                  viewModel.projectSnapshot?.project.id == project.id else { return }
            onOpen(project.id)
        }
    }

    private func prepareDelete(_ project: NovelProjectSummary) {
        if project.loadError != nil {
            pendingDelete = NovelProjectDeleteCandidate(project: project)
            return
        }
        Task { @MainActor in
            await viewModel.selectProject(project.id)
            guard viewModel.projectSnapshot?.project.id == project.id else { return }
            pendingDelete = NovelProjectDeleteCandidate(project: project)
        }
    }

    private func delete(_ project: NovelProjectSummary) {
        Task { @MainActor in
            guard deletingProjectID == nil else { return }
            deletingProjectID = project.id
            defer { deletingProjectID = nil }
            if project.loadError == nil {
                guard viewModel.selectedProjectID == project.id else { return }
                if hasRunningRun(for: project.id) {
                    guard await viewModel.stopActiveRunsForProjectOperation(
                        projectID: project.id
                    ) else { return }
                }
            }
            await viewModel.deleteProject(project)
        }
    }

    private func hasRunningRun(for projectID: NovelProjectID) -> Bool {
        guard viewModel.projectSnapshot?.project.id == projectID else { return false }
        return viewModel.projectSnapshot?.activeRuns.contains(where: { $0.status == .running }) == true ||
            (viewModel.quickStartStartingProjectID == projectID && viewModel.quickStartStartingRun != nil)
    }

    private func deleteConfirmationMessage(for project: NovelProjectSummary) -> String {
        if project.loadError != nil {
            return "项目当前无法读取。删除后无法从应用内恢复，请确认不再需要重新读取或导入备份。"
        }
        if hasRunningRun(for: project.id) {
            return "Agent 正在生成。将先停止生成并保存终止状态，再删除项目。项目包未导出时无法恢复。"
        }
        return "项目包未导出时无法从应用内恢复。"
    }

    private func deleteConfirmationTitle(for project: NovelProjectSummary) -> String {
        project.loadError == nil ? "删除《\(project.name)》？" : "删除无法读取的项目？"
    }

    private func handlePackageImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else {
            if case .failure(let error) = result { viewModel.presentError(error) }
            return
        }
        Task { @MainActor in
            isPreparingImportPreview = true
            defer { isPreparingImportPreview = false }
            do {
                let data = try await Task.detached(priority: .userInitiated) {
                    let accessed = url.startAccessingSecurityScopedResource()
                    defer {
                        if accessed {
                            url.stopAccessingSecurityScopedResource()
                        }
                    }
                    var isDirectory: ObjCBool = false
                    FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory)
                    if isDirectory.boolValue {
                        return try NovelWorkspaceImporter.packageData(fromDirectory: url)
                    }
                    return try NovelProjectFileReader.readPackage(from: url)
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
            Image(systemName: project.loadError != nil
                ? "exclamationmark.triangle"
                : project.isDegraded ? "exclamationmark.book.closed" : "text.book.closed")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(project.isDegraded ? AmberTheme.accentAmber : AmberTheme.accent)
                .frame(width: 36, height: 36)
                .background(AmberTheme.accentTint, in: RoundedRectangle(cornerRadius: 8))

            VStack(alignment: .leading, spacing: 2) {
                Text(project.loadError == nil ? project.name : "无法读取的项目")
                    .font(.body.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                    .lineLimit(1)
                    // Error rows share the line with a trailing button;
                    // long names shrink before truncating.
                    .minimumScaleFactor(0.85)

                HStack(spacing: 6) {
                    if project.loadError != nil {
                        Text("读取失败")
                            .foregroundStyle(AmberTheme.accentRed)
                    } else {
                        Text(project.updatedAt, format: .relative(presentation: .named))
                        if project.isDegraded {
                            Text("只读恢复")
                                .foregroundStyle(AmberTheme.foreground2)
                        }
                    }
                }
                .font(.caption)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: 44)
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
    @State private var isConfirmingDiscard = false
    @State private var validationMessage: String?
    @State private var imeBank = NovelIMEFieldBank()

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
                    NovelIMETextField(
                        text: $name,
                        placeholder: "小说名称",
                        bank: imeBank
                    )
                    .frame(minHeight: 36)
                }

                if mode == .quickStart {
                    Section {
                        NovelIMETextField(
                            text: $genre,
                            placeholder: "题材，例如：近未来悬疑",
                            bank: imeBank
                        )
                        .frame(minHeight: 36)
                        NovelIMETextEditor(
                            text: $coreIdea,
                            placeholder: "核心想法",
                            minHeight: 120,
                            bank: imeBank
                        )
                        .frame(minHeight: 120)
                    } header: {
                        Text("故事种子")
                    } footer: {
                        Text("AI 只会生成建议，确认后才写入项目设定。")
                    }
                }

                if let validationMessage {
                    Section {
                        Label(validationMessage, systemImage: "exclamationmark.triangle")
                            .foregroundStyle(AmberTheme.accentRed)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AmberTheme.background)
            .navigationTitle("新建小说")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { requestDismiss() }
                    }
                        .disabled(viewModel.isPerforming)
                        .confirmationDialog(
                            "放弃新建小说？",
                            isPresented: $isConfirmingDiscard,
                            titleVisibility: .visible
                        ) {
                            Button("放弃更改", role: .destructive) { dismiss() }
                            Button("继续编辑", role: .cancel) {}
                        } message: {
                            Text("已填写的项目名称和故事种子会丢失。")
                        }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        NovelTextInputCommitter.perform(fieldBank: imeBank) { create() }
                    }
                        .disabled(viewModel.isProjectSelectionBlocked)
                }
            }
        }
        .interactiveDismissDisabled()
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
    }

    private var hasUnsavedChanges: Bool {
        mode != .blank || !name.isEmpty || !genre.isEmpty || !coreIdea.isEmpty
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
        guard canCreate else {
            validationMessage = mode == .quickStart
                ? "请填写小说名称、题材和核心想法。"
                : "请填写小说名称。"
            return
        }
        validationMessage = nil
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

    private func requestDismiss() {
        if hasUnsavedChanges {
            isConfirmingDiscard = true
        } else {
            dismiss()
        }
    }
}

struct NovelProjectRenameSheet: View {
    @Environment(\.dismiss) private var dismiss

    let viewModel: NovelCreationViewModel
    let currentName: String
    let canRename: Bool
    @State private var name: String
    @State private var isSubmitting = false
    @State private var failureMessage: String?
    @State private var imeBank = NovelIMEFieldBank()

    init(viewModel: NovelCreationViewModel, project: NovelProjectSummary) {
        self.viewModel = viewModel
        self.currentName = project.name
        self.canRename = !project.isDegraded
        self._name = State(initialValue: project.name)
    }

    init(viewModel: NovelCreationViewModel, currentName: String, canRename: Bool) {
        self.viewModel = viewModel
        self.currentName = currentName
        self.canRename = canRename
        self._name = State(initialValue: currentName)
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 8) {
                Text("小说名称")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(AmberTheme.muted)
                NovelIMETextField(
                    text: $name,
                    placeholder: "小说名称",
                    isEnabled: !isSubmitting && canRename,
                    bank: imeBank
                )
                .frame(minHeight: 36)
                .padding(.horizontal, 8)
                .padding(.vertical, 6)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: 8))
                if let failureMessage {
                    Label(failureMessage, systemImage: "exclamationmark.triangle")
                        .font(.footnote)
                        .foregroundStyle(AmberTheme.accentRed)
                }
            }
            .disabled(isSubmitting)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .background(AmberTheme.background)
            .navigationTitle("重命名项目")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(isSubmitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { commitNameAndSave() }
                        .disabled(
                            !canRename ||
                                name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                isSubmitting
                        )
                }
            }
            .overlay {
                if isSubmitting { ProgressView("正在保存项目名称") }
            }
        }
        .interactiveDismissDisabled(isSubmitting)
        .presentationSizing(.fitted)
        .presentationDragIndicator(.visible)
    }

    private func commitNameAndSave() {
        NovelTextInputCommitter.perform(fieldBank: imeBank) {
            save(name.trimmingCharacters(in: .whitespacesAndNewlines))
        }
    }

    private func save(_ committedName: String) {
        guard !committedName.isEmpty, !isSubmitting else { return }
        guard committedName != currentName else {
            dismiss()
            return
        }
        isSubmitting = true
        failureMessage = nil
        Task { @MainActor in
            viewModel.clearError()
            let saved = await viewModel.renameProject(committedName)
            isSubmitting = false
            guard saved else {
                failureMessage = viewModel.errorMessage ?? "项目名称没有保存，请稍后重试。"
                return
            }
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
                            .foregroundStyle(AmberTheme.foreground2)
                    }
                }

                if let existing = preview.existingProject {
                    Section("本地冲突") {
                        if existing.loadError == nil {
                            Text("本地已有《\(existing.name)》。可替换它，或作为副本保留两份。")
                                .font(.subheadline)
                                .foregroundStyle(AmberTheme.foreground2)

                            Button(role: .destructive) {
                                isConfirmingReplace = true
                            } label: {
                                Label("替换本地项目", systemImage: "arrow.triangle.2.circlepath")
                            }
                        } else {
                            Text("本地项目当前无法读取，只能将导入包保留为副本。")
                                .font(.subheadline)
                                .foregroundStyle(AmberTheme.foreground2)
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
            .disabled(viewModel.isProjectSelectionBlocked)
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
            let acceptedPreview = stoppingActiveRun ? nil : preview
            switch await viewModel.importProject(
                packageData,
                choice: choice,
                preview: acceptedPreview
            ) {
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
                onOpen: { _ in },
                onOpenSettings: {}
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

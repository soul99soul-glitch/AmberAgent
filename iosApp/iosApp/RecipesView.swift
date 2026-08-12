import SwiftUI
import Shared

// MARK: - Recipes 管理（Wave B2；§14.1 / §18.1）
//
// 复用 Skill 管理页的视觉结构（AmberFormGroup / AmberSectionLabel /
// amberGlass 详情），展示 active recipe：版本、description、步骤摘要、回退
// 入口。回退复用 store 的 rollback（再验所见 manifest），无 previous 时明确
// 展示不可回退状态。数据来自真实 store + registry snapshot，不用源码锚点。

private func recipeStoreBaseDirectory() -> URL {
    (try? FileManager.default.url(
        for: .documentDirectory,
        in: .userDomainMask,
        appropriateFor: nil,
        create: true
    )) ?? FileManager.default.temporaryDirectory
}

struct RecipesView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(RouterPath.self) private var router

    @State private var snapshot: IOSDynamicToolCatalogSnapshot?
    @State private var rollbackStates: [String: IOSRecipeRollbackAvailability] = [:]

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    recipesSection
                    footnoteSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .task { await reload() }
    }

    private var header: some View {
        HStack {
            AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回技能", size: 44, symbolSize: 20) {
                dismiss()
            }

            Spacer()

            Text("Recipes")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            Spacer()

            // 占位：与技能页标题对称，保持布局稳定。
            Color.clear.frame(width: 44, height: 44)
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var recipesSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "本机 Recipes")
            AmberFormGroup {
                let recipes = snapshot?.recipeTools ?? []
                if recipes.isEmpty {
                    RecipeEmptyState()
                } else {
                    ForEach(Array(recipes.enumerated()), id: \.offset) { index, recipe in
                        Button {
                            router.navigate(to: .recipeDetail(name: recipe.recipeName))
                        } label: {
                            HStack(spacing: 10) {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("\(recipe.recipeName) · v\(recipe.version)")
                                        .font(.body.weight(.semibold))
                                        .foregroundStyle(AmberTheme.foreground)
                                    if !recipe.description.isEmpty {
                                        Text(recipe.description)
                                            .font(.caption)
                                            .foregroundStyle(AmberTheme.muted)
                                            .lineLimit(2)
                                    }
                                    Text(recipeStepsSummary(recipe))
                                        .font(.caption2)
                                        .foregroundStyle(AmberTheme.muted2)
                                        .lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(AmberTheme.muted2)
                            }
                            .frame(minHeight: 60)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        if index < recipes.count - 1 {
                            Divider().overlay(AmberTheme.borderSoft).padding(.leading, 14)
                        }
                    }
                }
            }
        }
    }

    private var footnoteSection: some View {
        Text("Recipe 是声明式工具组合：模型通过 tool_search 发现 recipe__<名称> 并调用；导入/回退都在「下一模型轮」生效。")
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }

    private func recipeStepsSummary(_ recipe: IOSDynamicRecipeToolDescriptor) -> String {
        let steps = recipe.manifest.steps.map { $0.tool }
        if steps.isEmpty { return "（无步骤）" }
        if steps.count <= 3 { return steps.joined(separator: " → ") }
        return steps.prefix(3).joined(separator: " → ") + " …（共 \(steps.count) 步）"
    }

    private func reload() async {
        // 展示 registry 的最新发布态（round-boundary refresh 同源）。
        snapshot = await IOSDynamicToolRegistry.shared.refresh()
    }
}

private struct RecipeEmptyState: View {
    var body: some View {
        VStack(spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(AmberTheme.surface2.opacity(0.82))
                Image(systemName: "point.3.connected.trianglepath.dotted")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(AmberTheme.muted2)
            }
            .frame(width: 52, height: 52)

            VStack(spacing: 4) {
                Text("暂无 Recipes")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AmberTheme.foreground)
                Text("Agent 在对话中通过 recipe_import 导入候选 Recipe；批准后从下一模型轮生效。")
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.vertical, 24)
    }
}

// MARK: - Recipe 详情（active 版本、步骤摘要、一键回退）

struct RecipeDetailView: View {
    @Environment(\.dismiss) private var dismiss
    /// §14.1: 「用最近失败生成候选」入口使用的演化工作流（AppShell 注入）。
    @Environment(IOSEvolutionWorkflow.self) private var evolutionWorkflow

    let recipeName: String
    private let store: IOSRecipeFileStore
    private let feedbackService: IOSRecipeExperienceFeedbackService

    @State private var manifest: IOSRecipeManifest?
    @State private var packageHash: String?
    @State private var loadError: String?
    @State private var rollbackAvailability: IOSRecipeRollbackAvailability = .unavailable(
        "正在检查可回退版本。"
    )
    @State private var rollbackConfirmationPresented = false
    @State private var pendingAlert: RecipeDetailAlert?
    @State private var feedbackAvailability: IOSRecipeExperienceFeedbackAvailability = .unavailable(
        "正在查找这个版本的执行记录。"
    )
    @State private var feedbackMessage: String?
    @State private var isSubmittingFeedback = false

    init(recipeName: String) {
        let baseDirectory = recipeStoreBaseDirectory()
        self.recipeName = recipeName
        self.store = IOSRecipeFileStore(baseDirectory: baseDirectory)
        self.feedbackService = .production(baseDirectory: baseDirectory)
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 0) {
                    header
                    hero
                    descriptionSection
                    stepsSection
                    envelopeSection
                    infoSection
                    evolutionSection
                    experienceFeedbackSection
                    rollbackSection
                }
                .padding(.bottom, 36)
            }
            .scrollIndicators(.hidden)
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
        .alert(item: $pendingAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("知道了"))
            )
        }
        .confirmationDialog(
            "回退上一次导入",
            isPresented: $rollbackConfirmationPresented,
            titleVisibility: .visible
        ) {
            Button("确认回退", role: .destructive) {
                rollbackLastImport()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text(rollbackAvailability.reason)
        }
        .task {
            loadSnapshot()
            await refreshFeedbackAvailability()
        }
    }

    private var header: some View {
        ZStack {
            Text("Recipe 详情")
                .font(.title2.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            HStack {
                AmberGlassCircleButton(systemImage: "chevron.left", accessibilityLabel: "返回 Recipes", size: 44, symbolSize: 20) {
                    dismiss()
                }

                Spacer()
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 18)
    }

    private var hero: some View {
        VStack(spacing: 8) {
            Image(systemName: "point.3.connected.trianglepath.dotted")
                .font(.system(size: 27, weight: .medium))
                .foregroundStyle(AmberTheme.accentAmber)
                .frame(width: 64, height: 64)
                .background(AmberTheme.accentAmber.opacity(0.13), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: 20, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }

            Text(recipeName)
                .font(.title3.weight(.bold))
                .foregroundStyle(AmberTheme.foreground)

            HStack(spacing: 6) {
                Circle()
                    .fill(AmberTheme.muted2)
                    .frame(width: 7, height: 7)
                Text(statusText)
                    .font(.caption)
                    .foregroundStyle(AmberTheme.muted)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 2)
        .padding(.bottom, 22)
    }

    private var descriptionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "描述")
            Text(manifest?.description ?? "未能读取这个 Recipe；请从 Recipes 列表重新进入。")
                .font(.subheadline)
                .foregroundStyle(AmberTheme.foreground2)
                .lineSpacing(3)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(14)
                .background(AmberTheme.surface, in: RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous))
                .overlay {
                    RoundedRectangle(cornerRadius: AmberTheme.radiusXLarge, style: .continuous)
                        .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
                }
                .padding(.horizontal, 16)
        }
    }

    private var stepsSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "步骤")
            AmberFormGroup {
                let steps = manifest?.steps ?? []
                if steps.isEmpty {
                    RecipeDetailRow(title: "步骤", value: "未能读取")
                } else {
                    ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                        RecipeDetailRow(
                            title: "\(index + 1). \(step.id)",
                            value: step.tool,
                            monospace: true
                        )
                        if index < steps.count - 1 {
                            RecipeDetailDivider()
                        }
                    }
                }
            }

            RecipeDetailFooter("步骤按顺序执行；含副作用步骤时，每次调用都会逐步骤请求批准。")
        }
    }

    private var envelopeSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "权限包络")
            AmberFormGroup {
                RecipeDetailRow(title: "效果类别", value: envelopeTitle)
            }

            RecipeDetailFooter("包络是所有步骤效果类别的保守上界；不会绕过既有审批。")
        }
    }

    private var infoSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "信息")
            AmberFormGroup {
                RecipeDetailRow(title: "版本", value: manifest?.version ?? "未读取", monospace: true)
                RecipeDetailDivider()
                RecipeDetailRow(title: "输入", value: inputsSummary, monospace: true)
                RecipeDetailDivider()
                RecipeDetailRow(title: "包哈希", value: packageHash.map { String($0.prefix(12)) } ?? "未读取", monospace: true)
            }

            RecipeDetailFooter(
                loadError
                    ?? "Recipe 只能组合 App 已发布的工具；不会执行任意代码。"
            )
        }
    }

    private var rollbackSection: some View {
        VStack(spacing: 0) {
            AmberFormGroup {
                Button {
                    rollbackConfirmationPresented = true
                } label: {
                    Text("回退上一次导入")
                        .font(.body.weight(.medium))
                        .foregroundStyle(
                            rollbackAvailability.canRollback ? AmberTheme.accent : AmberTheme.muted2
                        )
                        .frame(maxWidth: .infinity)
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(!rollbackAvailability.canRollback)
            }
            .padding(.top, 20)

            RecipeDetailFooter(rollbackAvailability.reason)
        }
    }

    private var evolutionSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "自进化")
            AmberFormGroup {
                Button {
                    // §14.1「用最近失败生成候选」：从最近 7 天失败证据出发，
                    // 为这个 Recipe 生成修订候选。结果通过通知卡 / 批准卡展示。
                    evolutionWorkflow.analyzeAndImprove(
                        conversationHex: nil,
                        userHint: "为 Recipe「\(recipeName)」生成修订候选：最近失败表明它仍不够稳定。"
                    )
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "wand.and.stars")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(AmberTheme.foreground2)
                            .frame(width: 28, height: 28)

                        VStack(alignment: .leading, spacing: 2) {
                            Text("用最近失败生成候选")
                                .font(.body)
                                .foregroundStyle(AmberTheme.foreground)
                            Text("投影最近失败 → 诊断 → 生成修订候选 → 独立评测 → 分级授权")
                                .font(.caption)
                                .foregroundStyle(AmberTheme.muted)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(AmberTheme.muted2)
                    }
                    .frame(minHeight: 58)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 4)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(evolutionWorkflow.isRunning)
                .accessibilityLabel("用最近失败生成候选")
            }

            RecipeDetailFooter("不会静默发布：T0/T1 自动发布有通知卡与一键回退，T2 始终人工批准。")
        }
        .padding(.top, 12)
    }

    private var experienceFeedbackSection: some View {
        VStack(spacing: 0) {
            AmberSectionLabel(text: "使用反馈")
            AmberFormGroup {
                RecipeDetailRow(
                    title: "反馈版本",
                    value: manifest.map { "recipe__\(recipeName)@\($0.version)" } ?? "未读取",
                    monospace: true
                )
                RecipeDetailDivider()

                HStack(spacing: 10) {
                    feedbackButton(
                        title: "这次有帮助",
                        systemImage: "hand.thumbsup",
                        kind: .helpful
                    )
                    feedbackButton(
                        title: "没有解决",
                        systemImage: "hand.thumbsdown",
                        kind: .harmful
                    )
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
            }

            RecipeDetailFooter(
                feedbackMessage
                    ?? "\(feedbackAvailability.reason) 负反馈只记证据；达到既有阈值后仍需人工批准才能停用经验。"
            )
        }
        .padding(.top, 12)
    }

    private func feedbackButton(
        title: String,
        systemImage: String,
        kind: IOSRecipeExperienceFeedbackKind
    ) -> some View {
        Button {
            submitFeedback(kind)
        } label: {
            Label(title, systemImage: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(
                    canSubmitFeedback ? AmberTheme.foreground2 : AmberTheme.muted2
                )
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(AmberTheme.surface2.opacity(0.86), in: Capsule())
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canSubmitFeedback)
    }

    private var canSubmitFeedback: Bool {
        manifest != nil && feedbackAvailability.canSubmit && !isSubmittingFeedback
    }

    private var statusText: String {
        if manifest != nil {
            return "已激活 v\(manifest?.version ?? "?")"
        }
        if loadError != nil {
            return "读取失败"
        }
        return "读取中"
    }

    private var inputsSummary: String {
        guard let manifest, !manifest.inputs.isEmpty else { return "无" }
        return manifest.inputs.sorted(by: { $0.key < $1.key })
            .map { "\($0.key):\($0.value.rawValue)" }
            .joined(separator: ", ")
    }

    private var envelopeTitle: String {
        guard let manifest else { return "未读取" }
        let validation = IOSRecipeValidator.validate(
            manifest: manifest,
            catalog: IOSDynamicToolRegistry.primitiveCatalogEntry
        )
        guard let envelope = validation.permissionEnvelope else { return "校验失败" }
        return IOSDynamicToolRegistry.permissionSummary(for: envelope)
    }

    private func loadSnapshot() {
        refreshRollbackAvailability()
        do {
            let package = try store.readLiveRecipe(name: recipeName)
            let decoded = try IOSRecipeManifest.decode(package.canonicalJSON)
            manifest = decoded
            packageHash = package.hash
            loadError = nil
        } catch {
            manifest = nil
            packageHash = nil
            loadError = "读取 Recipe 失败：\(error.localizedDescription)"
        }
    }

    private func refreshFeedbackAvailability() async {
        guard let version = manifest?.version else {
            feedbackAvailability = .unavailable("Recipe 未读取，暂不能评价。")
            return
        }
        let availability = await feedbackService.availability(
            recipeName: recipeName,
            version: version
        )
        guard manifest?.version == version else { return }
        feedbackAvailability = availability
    }

    private func submitFeedback(_ kind: IOSRecipeExperienceFeedbackKind) {
        guard let manifest, !isSubmittingFeedback else { return }
        let version = manifest.version
        isSubmittingFeedback = true
        feedbackMessage = nil
        Task { @MainActor in
            let outcome = await feedbackService.record(
                kind,
                recipeName: recipeName,
                version: version,
                description: manifest.description
            )
            guard self.manifest?.version == version else {
                isSubmittingFeedback = false
                return
            }
            isSubmittingFeedback = false
            switch outcome {
            case .recorded(let receipt):
                switch receipt.kind {
                case .helpful:
                    feedbackMessage = "已把这次确认写入 Experience；后续匹配任务可检索这条有限规则。"
                case .harmful:
                    feedbackMessage = receipt.suggestion == nil
                        ? "已记录“没有解决”；不会自动重试或停用。"
                        : "已记录“没有解决”；经验管理中已出现待人工批准的处理建议。"
                }
            case .unavailable(let reason), .failed(let reason):
                feedbackMessage = reason
                pendingAlert = .operationFailed(reason)
            }
            await refreshFeedbackAvailability()
        }
    }

    private func refreshRollbackAvailability() {
        do {
            rollbackAvailability = try store.rollbackAvailability(name: recipeName)
        } catch {
            rollbackAvailability = .unavailable("检查可回退版本失败：\(error.localizedDescription)")
        }
    }

    private func rollbackLastImport() {
        guard case .available(let expectedManifest) = rollbackAvailability else {
            refreshRollbackAvailability()
            pendingAlert = .operationFailed("可回退版本已经失效，请刷新后重试。")
            return
        }
        do {
            // 再验所见 manifest：store 内部会核对 live hash 与槽位，更新的
            // 导入会替换槽位 → 此处 fail closed（§13.1 / §18.1）。
            _ = try store.rollbackRecipe(name: recipeName, expectedManifest: expectedManifest)
            // §13.4 / 不变量 17：与通知卡回退共用同一熔断/指标通道——补记
            // §19 rollback 指标；policy state 返回 tripped 时由 workflow
            // push 熔断通知（详情页没有自己的通知 UI，复用 workflow 的
            // 通知卡通道，不新造 UI）。
            evolutionWorkflow.recordRollback(artifactId: recipeName)
            loadSnapshot()
            Task { @MainActor in
                await refreshFeedbackAvailability()
                // 下一模型轮可见（round-boundary refresh 同源）。
                _ = await IOSDynamicToolRegistry.shared.refresh()
            }
        } catch {
            refreshRollbackAvailability()
            pendingAlert = .operationFailed(error.localizedDescription)
        }
    }
}

private struct RecipeDetailRow: View {
    let title: String
    let value: String
    var monospace = false

    var body: some View {
        HStack(spacing: 12) {
            Text(title)
                .font(.body)
                .foregroundStyle(AmberTheme.foreground)
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(2)

            Text(value)
                .font(monospace ? .system(.subheadline, design: .monospaced) : .subheadline)
                .foregroundStyle(AmberTheme.muted)
                .lineLimit(2)
        }
        .frame(minHeight: 52)
        .padding(.horizontal, 14)
        .padding(.vertical, 4)
    }
}

private struct RecipeDetailDivider: View {
    var body: some View {
        Rectangle()
            .fill(AmberTheme.borderSoft)
            .frame(height: 0.5)
            .padding(.leading, 14)
    }
}

private struct RecipeDetailFooter: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(AmberTheme.muted2)
            .lineSpacing(2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.top, 7)
    }
}

private enum RecipeDetailAlert: Identifiable {
    case operationFailed(String)

    var id: String {
        switch self {
        case .operationFailed(let message): "operationFailed-\(message)"
        }
    }

    var title: String {
        "操作失败"
    }

    var message: String {
        switch self {
        case .operationFailed(let message): message
        }
    }
}

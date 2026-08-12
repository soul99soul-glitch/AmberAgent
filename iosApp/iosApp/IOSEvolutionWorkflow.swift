import Foundation
import Observation
@preconcurrency import Shared

// MARK: - IOSEvolutionWorkflow (Phase 2 Wave C; §13.1 / §14.1-14.2 / §15 Phase 2)
//
// User-triggered evolution orchestration:
//   evidence 投影 → diagnose → build → evaluate → policy 评估 →
//   (T0/T1 自动发布 + 通知卡) / (T2 或「仅通知」人工批准卡带评测报告) /
//   (草稿降级: 无 holdout 或 stop 条件时只开人工草稿，§15 停止条件).
//
// UI 状态经本对象（@Observable）暴露：ChatView / RecipesView 直接渲染
// `pendingApproval`（T2 批准卡）与 `notifications`（T0/T1 通知卡等）。
// `ChatGenerationCoordinator` 不参与本流程——发布只做
// `IOSDynamicToolRegistry.refresh()`，前台 round 边界（
// `refreshDynamicCatalogAtRoundBoundary`）自然让下一模型轮看到新制品
// （§13.2 / §16.1），generation loop 零改动（"只接最小入口状态"）。
//
// Invariants: I-1 (evidence from durable owners only), I-3 (no evidence →
// no candidate), I-5 (evaluated bytes == hashed bytes), I-12 (sealed holdout
// content never reaches proposer prompts — only `proposerView` is passed to
// the builder), I-15 (prompts carry only redacted summaries), I-16 (policy
// engine owns autonomous authorization), I-17 (autonomous actions are
// reversible + visible: receipt + notification card + one-tap rollback).

// MARK: - One-shot model wiring (production)

/// Reuses the lightest existing one-shot completion path — the same
/// auxiliary-text generation the conversation-title / list-preview chain uses
/// (`ChatViewModel.runAuxModel` / `ConversationListPreviewGenerator`):
/// `IOSAgentTextProvider.generateText` with `tools: []` and reasoning off,
/// resolved through the settings snapshot's auxiliary model (titleModelId →
/// current chat model fallback). No new provider channel is created for
/// evolution (§任务书: 一次性模型调用的最轻现有路径).
enum IOSEvolutionModelError: Error, LocalizedError {
    case noModelConfigured
    case emptyResponse

    var errorDescription: String? {
        switch self {
        case .noModelConfigured: "没有可用的辅助模型（未配置模型或服务商不可用）。"
        case .emptyResponse: "模型返回为空。"
        }
    }
}

@MainActor
enum IOSEvolutionModelGateway {
    /// Production model closure for diagnoser/builder. `textProvider` is
    /// injectable for tests (defaults to the real provider adapter).
    ///
    /// 返回 `@MainActor @Sendable` 闭包：诊断/构建都在 MainActor 上调用，
    /// 调用点会把隔离闭包隐式转换为 `@Sendable (String) async throws -> String`。
    /// 不能用 `MainActor.run { ... }` 包裹主体——Swift 的重载决议对含 await 的
    /// 尾随闭包会误选同步 overload（"expecting synchronous function type"）；
    /// 也不能用 `Task { @MainActor in }`——其闭包是 @Sendable，无法捕获非
    /// Sendable 的 `IOSSharedSettingsStore`。隔离闭包本身即可直接捕获 store。
    static func productionModel(
        sharedSettings: IOSSharedSettingsStore,
        textProvider: any IOSAgentTextProvider = OpenAIKmpProviderAdapter()
    ) -> @MainActor @Sendable (String) async throws -> String {
        { prompt in
            let snapshot = sharedSettings.snapshot
            guard let model = snapshot.findModelById(uuid: snapshot.titleModelId)
                    ?? snapshot.getCurrentChatModel(),
                  let provider = ChatProviderConfiguration.provider(
                      for: model,
                      providers: snapshot.providers
                  ),
                  ChatProviderConfiguration.issue(for: model, provider: provider) == nil else {
                throw IOSEvolutionModelError.noModelConfigured
            }
            let assistant = snapshot.getCurrentAssistant()
            let params = TextGenerationParams(
                model: model,
                temperature: nil,
                topP: nil,
                maxTokens: nil,
                tools: [],
                reasoningLevel: ReasoningLevel.off,
                customHeaders: assistant.customHeaders + model.customHeaders,
                customBody: assistant.customBodies + model.customBodies
            )
            let chunk = try await textProvider.generateText(
                providerSetting: provider,
                messages: [UIMessage.companion.user(prompt: prompt)],
                params: params
            )
            guard let text = chunk.choices.first?.message?.toText()
                .trimmingCharacters(in: .whitespacesAndNewlines),
                !text.isEmpty else {
                throw IOSEvolutionModelError.emptyResponse
            }
            return text
        }
    }
}

// MARK: - Catalog summary (prompt context, §11.2/§6.2)

/// Assembles the human-readable primitive catalog summary the diagnosis and
/// drafting prompts receive. Every entry is verified against the real catalog
/// oracle (`exists` + effect class); the name list is DERIVED at runtime from
/// the real declaration source (`iosToolDeclarationNames()` in ai-core/Tool.kt
/// — the declaration table `iosToolDeclaration` resolves through), NOT a
/// hand-mirrored list (drift regression: `IOSEvolutionSuiteProviderTests`
/// asserts the runtime list equals the declaration source).
enum IOSEvolutionCatalogSummary {
    /// Every tool name the KMP iOS declaration table can declare — derived,
    /// never mirrored.
    static var knownToolNames: [String] {
        ToolKt.iosToolDeclarationNames()
    }

    static func assemble(catalog: @escaping IOSRecipeCatalogLookup) -> String {
        knownToolNames.compactMap { name in
            guard let entry = catalog(name), entry.exists else { return nil }
            return "\(name) (\(entry.effectClass.rawValue))"
        }.joined(separator: ", ")
    }
}

// MARK: - UI state models (§14.1 / §14.2)

/// T2 人工批准卡（或「仅通知」档下附带完整自动评估结论的 T0/T1 人工卡）——
/// §14.2 全要素。
struct IOSEvolutionApprovalCardModel: Identifiable, Equatable {
    /// 观察到了什么（evidence 摘要，脱敏）。
    let observedSummary: String
    /// 引用的 run/case（稳定标识）。
    let evidenceRefs: [String]
    /// 诊断 claim。
    let diagnosis: String
    /// 替代解释。
    let alternatives: [String]
    /// 反证。
    let falsifier: String
    let artifactKind: IOSArtifactKind
    let artifactName: String
    let artifactVersion: String
    /// 新建 / 更新。
    let mutationKind: IOSRecipeMutationKind
    /// 变更摘要（description）。
    let changeSummary: String
    /// 步骤摘要（"<stepId> → <tool>"）。
    let stepsSummary: [String]
    /// 权限包络标题。
    let permissionSummary: String
    /// 权限包络 raw value。
    let permissionEnvelopeRaw: String
    /// 三短 hash：base / candidate / report。
    let baseHash: String?
    let candidateHash: String
    let reportHash: String?
    /// failure replay / protected / holdout 结果（逐条）。
    let evaluationResultsText: String
    /// 未验证项 / 残余风险。
    let unresolvedRisks: [String]
    /// 本版未跑的评测层（v1: llmJudge / stochasticRepeat）。
    let skippedTiersText: String
    /// 完整候选 JSON（可滚动 disclosure 的「diff 入口」）。
    let candidateJSONPreview: String
    /// 「仅通知」档下 policy engine 的完整自动评估结论；nil = 普通 T2 人工卡。
    let policyAssessment: String?
    /// 评估结论里的证据硬门禁是否全部通过——决定结论文案用通过（绿）还是
    /// 警示（amber）色；`policyAssessment` 为 nil 时此字段不被读取。
    let policyAssessmentEvidenceGatesPassed: Bool
    /// §18.2：进入本套件的真实数据范围（哪些 run / 工具输入）。nil = 套件
    /// 未构造（人工草稿路径）。
    let dataScopeSummary: String?
    /// 稳定 id（candidate id）。
    let id: String

    var title: String {
        mutationKind == .new ? "批准新 Recipe" : "批准 Recipe 更新"
    }

    var reportResultsSummary: String {
        let lines = evaluationResultsText
            .split(separator: "\n")
            .map(String.init)
        let passed = lines.filter { $0.contains("✓") }.count
        let failed = lines.count - passed
        return "评测 \(lines.count) 条（通过 \(passed) / 失败 \(failed)）"
    }
}

/// T0/T1 自动发布通知卡（发生了什么 + 评测摘要 + 短 hash + 一键回退），
/// 以及 no-op / 草稿 / 熔断等过程通知。`canRollback` 仅自动发布卡为 true。
struct IOSEvolutionNotificationModel: Identifiable, Equatable {
    enum Kind: Equatable {
        case autoPromoted
        case circuitBreakerTripped
        case draftOnly
        case noOp
        case capabilityRequest
        case harnessProposal
        case failed
    }

    let id: UUID
    let kind: Kind
    let title: String
    let summary: String
    /// 附加详情（如熔断说明、失败原因）。
    let detail: String
    /// 可滚动 disclosure 内容（草稿预览 / 请求文档）。
    let disclosureContent: String?
    /// 一键回退目标（自动发布卡）。
    let artifactName: String?
    let candidateHash: String?
    let reportHash: String?
    /// Slice B：同一 identity 链上的 originRunId（B1）。
    let originRunId: String?
    /// 该通知发布时看到的单槽 previous manifest。仅 hash 不足以防住
    /// A→B→A 的 ABA；回退必须同时绑定这一份 manifest。
    let rollbackManifest: IOSRecipePreviousManifest?
    let canRollback: Bool
    var hasBeenRolledBack: Bool
    let createdAt: Date

    init(
        id: UUID = UUID(),
        kind: Kind,
        title: String,
        summary: String,
        detail: String = "",
        disclosureContent: String? = nil,
        artifactName: String? = nil,
        candidateHash: String? = nil,
        reportHash: String? = nil,
        originRunId: String? = nil,
        rollbackManifest: IOSRecipePreviousManifest? = nil,
        canRollback: Bool = false,
        hasBeenRolledBack: Bool = false
    ) {
        self.id = id
        self.kind = kind
        self.title = title
        self.summary = summary
        self.detail = detail
        self.disclosureContent = disclosureContent
        self.artifactName = artifactName
        self.candidateHash = candidateHash
        self.reportHash = reportHash
        self.originRunId = originRunId
        self.rollbackManifest = rollbackManifest
        self.canRollback = canRollback
        self.hasBeenRolledBack = hasBeenRolledBack
        self.createdAt = Date()
    }
}

// MARK: - Workflow

@MainActor
@Observable
final class IOSEvolutionWorkflow {
    enum Stage: Equatable {
        case idle
        case projecting
        case diagnosing
        case building
        case evaluating
        case deciding
        case publishing
        case done
        case failed(String)
    }

    struct Dependencies {
        let dao: AgentRuntimeDao
        let ledger: any IOSAgentRunLedgering
        let recipeStoreBaseDirectory: URL
        let catalog: IOSRecipeCatalogLookup
        let mcpConnectionOracle: @MainActor @Sendable () -> [String]
        let model: @Sendable (String) async throws -> String
        let catalogSummary: String
        let policyConfiguration: IOSPromotionPolicyConfiguration
        let policyStateStore: IOSPromotionPolicyStateStore
        let autonomyLevelProvider: @MainActor @Sendable () -> IOSEvolutionAutonomyLevel
        let killSwitchProvider: @MainActor @Sendable () -> Bool
        /// Host-side evaluation suite provider, keyed on (hypothesis,
        /// evidence). Builds the suite from REAL run facts (ledger + message
        /// store) and reports the §18.2 data scope; nil → every candidate is
        /// draftOnly (§15 停止条件：无法构造 failure replay + protected +
        /// sealed holdout 时不自动发布). `.insufficientData` / `.failed` are
        /// typed honest downgrades, never a half-built suite.
        let suiteProvider: (@MainActor @Sendable (IOSGapHypothesis, [IOSEvolutionEvidence]) async -> IOSEvolutionSuiteBuildResult)?
        let registryRefresh: @MainActor () async -> IOSDynamicToolCatalogSnapshot?
        /// §19 观测 store（Phase 4 Wave 1）：每个终止路径一行埋点，只记
        /// 计数/时间戳/枚举键（§18.2 隐私）。
        let metrics: IOSEvolutionMetrics
    }

    /// Production wiring: real ledger/DAO/registry/settings/MCP manager +
    /// the one-shot aux-model path + the REAL host-side suite provider
    /// (`IOSEvolutionSuiteProvider` over the same ledger DAO and the
    /// production conversation store). Suites are now constructed from real
    /// run facts; when the facts are insufficient the provider returns a
    /// typed downgrade and the candidate is honestly routed to a manual draft
    /// (§15).
    static func production(sharedSettings: IOSSharedSettingsStore) -> IOSEvolutionWorkflow {
        let baseDirectory = (try? FileManager.default.url(
            for: .documentDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? FileManager.default.temporaryDirectory
        let dao = IosDatabaseFactory.shared.createDatabase().agentRuntimeDao()
        let suiteProvider = IOSEvolutionSuiteProvider(
            dao: dao,
            conversationStore: IOSConversationStore()
        )
        let mcpOracle: @MainActor @Sendable () -> [String] = {
            let manager = IOSMcpManager(sharedSettings: sharedSettings, configStore: .shared)
            manager.refreshServers()
            return manager.servers.filter(\.enabled).map(\.name)
        }
        return IOSEvolutionWorkflow(
            dao: dao,
            ledger: IOSAgentRunLedger(dao: dao),
            recipeStoreBaseDirectory: baseDirectory,
            catalog: IOSDynamicToolRegistry.primitiveCatalogEntry,
            mcpConnectionOracle: mcpOracle,
            model: IOSEvolutionModelGateway.productionModel(sharedSettings: sharedSettings),
            catalogSummary: IOSEvolutionCatalogSummary.assemble(
                catalog: IOSDynamicToolRegistry.primitiveCatalogEntry
            ),
            policyConfiguration: .standard,
            policyStateStore: .shared,
            autonomyLevelProvider: { IOSPromotionPolicyEngine.currentAutonomyLevel() },
            killSwitchProvider: { IOSPromotionPolicyEngine.killSwitchEnabled() },
            suiteProvider: { hypothesis, evidence in
                await suiteProvider.build(hypothesis: hypothesis, evidence: evidence)
            },
            registryRefresh: { await IOSDynamicToolRegistry.shared.refresh() },
            metrics: .shared
        )
    }

    private let dependencies: Dependencies
    private var diagnoser: IOSEvolutionDiagnoser?
    private var builder: IOSEvolutionCandidateBuilder?
    private var pendingPublishContext: PendingPublishContext?

    /// 自动发布后 7 天内的通知（§18.1 精神：不无限增长，只保留最近窗口）。
    private static let maximumNotifications = 8

    // MARK: Observable UI state

    private(set) var isRunning = false
    private(set) var stage: Stage = .idle
    private(set) var pendingApproval: IOSEvolutionApprovalCardModel?
    private(set) var notifications: [IOSEvolutionNotificationModel] = []
    /// 最近一次流程的用户可读结果（toast / 详情页复用）。
    private(set) var lastOutcome: String?

    init(
        dao: AgentRuntimeDao,
        ledger: any IOSAgentRunLedgering,
        recipeStoreBaseDirectory: URL,
        catalog: @escaping IOSRecipeCatalogLookup,
        mcpConnectionOracle: @escaping @MainActor @Sendable () -> [String],
        model: @escaping @Sendable (String) async throws -> String,
        catalogSummary: String,
        policyConfiguration: IOSPromotionPolicyConfiguration = .standard,
        policyStateStore: IOSPromotionPolicyStateStore,
        autonomyLevelProvider: @escaping @MainActor @Sendable () -> IOSEvolutionAutonomyLevel,
        killSwitchProvider: @escaping @MainActor @Sendable () -> Bool,
        suiteProvider: (@MainActor @Sendable (IOSGapHypothesis, [IOSEvolutionEvidence]) async -> IOSEvolutionSuiteBuildResult)?,
        registryRefresh: @escaping @MainActor () async -> IOSDynamicToolCatalogSnapshot?,
        metrics: IOSEvolutionMetrics = .shared
    ) {
        self.dependencies = Dependencies(
            dao: dao,
            ledger: ledger,
            recipeStoreBaseDirectory: recipeStoreBaseDirectory,
            catalog: catalog,
            mcpConnectionOracle: mcpConnectionOracle,
            model: model,
            catalogSummary: catalogSummary,
            policyConfiguration: policyConfiguration,
            policyStateStore: policyStateStore,
            autonomyLevelProvider: autonomyLevelProvider,
            killSwitchProvider: killSwitchProvider,
            suiteProvider: suiteProvider,
            registryRefresh: registryRefresh,
            metrics: metrics
        )
    }

    // MARK: - Entry points (§14.1)

    /// 「分析并改进」：对指定会话最近一次失败 run 投影证据并跑完整工作流。
    /// 显式指定会话后该会话无可归因证据时诚实 no-op（I-3），不回退到全局
    /// 窗口（B1：跨会话不穿透）；最近 7 天全局 evidence 只服务
    /// `conversationHex == nil` 的显式管理入口（RecipesView）。返回流程
    /// Task（测试可 await；已在运行时返回 nil）。
    @discardableResult
    func analyzeAndImprove(conversationHex: String?, userHint: String? = nil) -> Task<Void, Never>? {
        guard !isRunning else { return nil }
        isRunning = true
        stage = .projecting
        lastOutcome = nil
        return Task {
            await run(conversationHex: conversationHex, userHint: userHint)
        }
    }

    /// T2 / 「仅通知」人工卡批准。
    @discardableResult
    func approvePending() -> Task<Void, Never>? {
        guard let context = pendingPublishContext else { return nil }
        pendingPublishContext = nil
        pendingApproval = nil
        isRunning = true
        stage = .publishing
        return Task {
            await publish(context: context, approvedBy: "user")
            isRunning = false
            stage = .done
        }
    }

    /// 拒绝候选（§15 Phase 2 acceptance 5：不写 active、不偷偷重试发布）。
    func denyPending() {
        pendingPublishContext = nil
        pendingApproval = nil
        lastOutcome = "已拒绝该候选；没有写入任何制品，也不会自动重试发布。"
        stage = .done
        // 必须复位 isRunning：拒绝是终止路径，否则与通知文案
        // 「可随时再次发起分析」自相矛盾（guard !isRunning 会永久拒绝）。
        isRunning = false
        dependencies.metrics.record(.userReject)
        push(notification: IOSEvolutionNotificationModel(
            kind: .noOp,
            title: "候选已拒绝",
            summary: "未发布任何改动；可随时再次发起分析。"
        ))
    }

    /// 通知卡一键回退（不变量 17）。回退同时计入熔断状态：同一制品连续两次
    /// 回退 → 自动关闭该制品自治并通知（§13.4）。返回回退 Task（测试可 await）。
    ///
    /// Slice B（B3）：回退绑定「该通知晋升的版本」。previous 槽可能已被
    /// 后来一次 promotion 覆盖——点旧通知的回退必须零状态变更（push
    /// 「版本已变化」失败通知，不标 hasBeenRolledBack），绝不回退最新版本。
    @discardableResult
    func rollback(notificationId: UUID) -> Task<Void, Never>? {
        guard let index = notifications.firstIndex(where: { $0.id == notificationId }),
              let artifactName = notifications[index].artifactName else { return nil }
        let boundCandidateHash = notifications[index].candidateHash
        let boundRollbackManifest = notifications[index].rollbackManifest
        return Task {
            let store = IOSRecipeFileStore(baseDirectory: dependencies.recipeStoreBaseDirectory)
            // B3 绑定校验（进入 Task 后、rollbackAvailability 之前）：该通知
            // 晋升的版本必须仍是当前生效版本；previous 槽已被后来一次
            // promotion 覆盖时（live hash != 卡上候选 hash）不执行回退。
            guard let boundCandidateHash,
                  let boundRollbackManifest,
                  (try? store.readLiveRecipe(name: artifactName))?.hash == boundCandidateHash else {
                push(notification: IOSEvolutionNotificationModel(
                    kind: .failed,
                    title: "版本已变化",
                    summary: "该通知晋升的版本已不是当前生效版本，未执行回退；请按最新通知操作。"
                ))
                return
            }
            let result: String
            var didRollback = false
            do {
                _ = try store.rollbackRecipe(
                    name: artifactName,
                    expectedManifest: boundRollbackManifest
                )
                _ = await dependencies.registryRefresh()
                // §19 rollback 记录 + 熔断记账：与详情页回退共用同一通道
                // （`recordRollback(artifactId:)`，tripped 时内部 push 熔断通知）。
                _ = recordRollback(artifactId: artifactName)
                result = "已回退 \(artifactName)，下一模型轮生效。"
                didRollback = true
            } catch {
                result = "回退失败：\(error.localizedDescription)"
                let versionChanged: Bool = {
                    guard case .recipeRollbackUnavailable(let reason) = error as? IOSRecipeFileStoreError else {
                        return false
                    }
                    return reason.contains("版本已变化")
                }()
                push(notification: IOSEvolutionNotificationModel(
                    kind: .failed,
                    title: versionChanged ? "版本已变化" : "回退失败",
                    summary: versionChanged
                        ? "该通知绑定的可回退版本已变化，未执行回退；请按最新通知操作。"
                        : error.localizedDescription
                ))
            }
            // 进入 Task 前捕获的 index 在 await 期间会失效：push 一律 insert
            // at 0（熔断/失败通知会整体平移索引），用户也可能 dismiss 卡片。
            // 必须按 notificationId 重新查找再置位，否则 hasBeenRolledBack 会
            // 标到别的卡片（如熔断二次回退路径下原卡按钮保持可用）。
            if didRollback,
               let currentIndex = notifications.firstIndex(where: { $0.id == notificationId }) {
                notifications[currentIndex].hasBeenRolledBack = true
            }
            lastOutcome = result
        }
    }

    func dismiss(notificationId: UUID) {
        notifications.removeAll { $0.id == notificationId }
    }

    /// 回退记账的统一入口（§13.4 / 不变量 17）：熔断计数 + §19 rollback
    /// 指标 + tripped 熔断通知。通知卡一键回退（`rollback(notificationId:)`）
    /// 与详情页「回退上一次导入」（`RecipeDetailView.rollbackLastImport()`）
    /// 共用本方法，保证两条回退路径的熔断/指标行为一致。
    ///
    /// 2026-08 整体复核修法：详情页回退原来只调
    /// `IOSPromotionPolicyStateStore.recordRollback`，不记 §19 metrics、不
    /// 消费 tripped 发熔断通知——与通知卡路径不一致。现收敛到本方法：store
    /// 侧 rollback 已由调用方在 CAS 校验后完成，这里只做记账。返回 tripped
    /// 供测试断言；熔断通知已由本方法 push（详情页没有自己的通知 UI，复用
    /// workflow 的通知卡通道，不新造 UI）。
    @discardableResult
    func recordRollback(artifactId: String) -> Bool {
        // 熔断状态在 recordRollback 前采样——触发熔断的这一次回退本身是
        // 用户主动（§19 rollback 来源区分用户主动 / 熔断建议）。
        let breakerSuggested = dependencies.policyStateStore.isAutonomyDisabled(artifactId: artifactId)
        let tripped = dependencies.policyStateStore.recordRollback(
            artifactId: artifactId,
            configuration: dependencies.policyConfiguration
        )
        dependencies.metrics.recordRollback(
            artifactId: artifactId,
            source: breakerSuggested ? .circuitBreakerSuggested : .userInitiated
        )
        if tripped {
            dependencies.metrics.record(.circuitBreakerTripped)
            pushCircuitBreakerTripped(artifactId: artifactId)
        }
        return tripped
    }

    private func pushCircuitBreakerTripped(artifactId: String) {
        push(notification: IOSEvolutionNotificationModel(
            kind: .circuitBreakerTripped,
            title: "已关闭「\(artifactId)」的自治",
            summary: "该制品已连续 \(dependencies.policyConfiguration.maxConsecutiveRollbacksBeforeCircuitBreak) 次回退，自动发布已熔断；后续候选将始终人工批准。",
            detail: "可在设置「自进化」中查看；回退本身仍随时可用。"
        ))
    }

    // MARK: - Pipeline

    private func run(conversationHex: String?, userHint: String?) async {
        // 1. Evidence projection (I-1): durable owners only.
        let evidence = await projectEvidence(conversationHex: conversationHex)
        guard !evidence.isEmpty else {
            dependencies.metrics.record(.evidenceNoOp)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .noOp,
                title: "没有可归因的失败证据",
                summary: "最近没有可投影的运行失败（工具错误 / 中断 / 拒绝）。继续观察或换个会话再试（I-3）。"
            ))
            return
        }

        // 2. Diagnosis (§11.2). The MCP list is snapshotted at entry so the
        //    model closure stays @Sendable and the whole flow is deterministic
        //    within one run.
        stage = .diagnosing
        let knownMcp = dependencies.mcpConnectionOracle()
        let diagnoser = IOSEvolutionDiagnoser(
            catalogOracle: dependencies.catalog,
            mcpConnectionOracle: { knownMcp },
            model: dependencies.model
        )
        self.diagnoser = diagnoser
        let diagnosis = await diagnoser.diagnose(
            evidence: evidence,
            userHint: userHint,
            catalogSummary: dependencies.catalogSummary
        )
        switch diagnosis {
        case .noOp(let reason):
            dependencies.metrics.record(.diagnosisNoOp)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .noOp,
                title: "本次没有候选",
                summary: reason
            ))
            return
        case .failed(let error):
            dependencies.metrics.record(.workflowFailed)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .failed,
                title: "诊断失败",
                summary: Self.diagnosisErrorText(error)
            ))
            return
        case .hypothesis(let hypothesis):
            await buildAndRoute(hypothesis: hypothesis, evidence: evidence)
        }
    }

    private func buildAndRoute(hypothesis: IOSGapHypothesis, evidence: [IOSEvolutionEvidence]) async {
        // 3. Bounded build (§6.2 / §18.3). The suite (when available) is
        //    requested BEFORE drafting so the builder receives only its
        //    proposer view — sealed holdout content never reaches the drafting
        //    prompt (I-12). The provider's typed result distinguishes a real
        //    suite (with its §18.2 data scope) from honest downgrades
        //    (insufficient facts / fetch failure — never a half-built suite).
        stage = .building
        var suite: IOSEvaluationSuite?
        var dataScopeSummary: String?
        var suiteUnavailableReason: String?
        switch await dependencies.suiteProvider?(hypothesis, evidence) {
        case .built(let builtSuite, let scope):
            suite = builtSuite
            dataScopeSummary = scope
        case .insufficientData(let reason), .failed(let reason):
            suiteUnavailableReason = reason
        case nil:
            suiteUnavailableReason = "未配置评测套件构造器。"
        }
        let proposerView = suite?.proposerView
        let builder = IOSEvolutionCandidateBuilder(
            recipeStoreBaseDirectory: dependencies.recipeStoreBaseDirectory,
            catalog: dependencies.catalog,
            model: dependencies.model
        )
        self.builder = builder
        let buildOutcome = await builder.build(
            hypothesis: hypothesis,
            proposerSuite: proposerView,
            budget: .standard,
            catalogSummary: dependencies.catalogSummary
        )
        switch buildOutcome {
        case .noOp(let reason):
            dependencies.metrics.record(.buildNoOp)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .noOp,
                title: "本次没有候选",
                summary: reason
            ))
        case .failed(let error):
            dependencies.metrics.record(.workflowFailed)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .failed,
                title: "候选生成失败",
                summary: Self.buildErrorText(error)
            ))
        case .capabilityRequest(let request):
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .capabilityRequest,
                title: "缺少外部能力：\(request.serverName)",
                summary: request.purpose,
                disclosureContent: request.document()
            ))
        case .harnessProposal(let proposal):
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .harnessProposal,
                title: "Harness Lab 补丁提案",
                summary: proposal.problem,
                disclosureContent: proposal.document()
            ))
        case .candidate(let manifest, let content):
            await routeCandidate(
                manifest: manifest,
                content: content,
                hypothesis: hypothesis,
                evidence: evidence,
                suite: suite,
                dataScopeSummary: dataScopeSummary,
                suiteUnavailableReason: suiteUnavailableReason
            )
        }
    }

    private func routeCandidate(
        manifest: IOSEvolutionCandidateManifest,
        content: Data,
        hypothesis: IOSGapHypothesis,
        evidence: [IOSEvolutionEvidence],
        suite: IOSEvaluationSuite?,
        dataScopeSummary: String?,
        suiteUnavailableReason: String?
    ) async {
        // 4. §15 Phase 2 stop condition: skill/playbook deltas have no
        //    evaluator in v1 and recipe candidates without a complete suite
        //    are draftOnly — both may only be opened as a manual draft.
        if manifest.draftOnly || manifest.artifactKind != .recipe {
            dependencies.metrics.record(.draftDowngrade)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .draftOnly,
                title: "已生成人工草稿：\(manifest.artifactName)",
                summary: "评测套件不完整（需要 failure replay + protected success + sealed holdout），按 §15 停止条件不自动发布。",
                detail: Self.draftOnlyDetail(
                    reason: suiteUnavailableReason,
                    dataScopeSummary: dataScopeSummary
                ),
                disclosureContent: Self.draftPreview(manifest: manifest, content: content),
                artifactName: manifest.artifactName,
                candidateHash: manifest.candidateHash,
                canRollback: false
            ))
            return
        }
        guard let suite, !suite.proposerView.failureReplayCaseRefs.isEmpty else {
            dependencies.metrics.record(.draftDowngrade)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .draftOnly,
                title: "已生成人工草稿：\(manifest.artifactName)",
                summary: "评测套件不可用，候选仅作人工草稿。",
                detail: Self.draftOnlyDetail(
                    reason: suiteUnavailableReason,
                    dataScopeSummary: dataScopeSummary
                ),
                disclosureContent: Self.draftPreview(manifest: manifest, content: content),
                artifactName: manifest.artifactName,
                candidateHash: manifest.candidateHash
            ))
            return
        }

        // 5. Independent evaluation (§12): real runner + real ledger on the
        //    exact candidate bytes (I-5).
        stage = .evaluating
        let evaluator = IOSArtifactEvaluator(
            recipeStoreBaseDirectory: dependencies.recipeStoreBaseDirectory,
            catalog: dependencies.catalog,
            ledger: dependencies.ledger,
            dao: dependencies.dao
        )
        let outcome = await evaluator.evaluate(
            candidateBytes: content,
            expectedCandidateHash: manifest.candidateHash,
            suite: suite
        )
        switch outcome {
        case .staticRejected(let issues):
            dependencies.metrics.record(.candidateStaticFail)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .failed,
                title: "候选未通过静态校验",
                summary: issues.map(\.code.rawValue).joined(separator: "、")
            ))
        case .candidateHashMismatch(let expected, let actual):
            dependencies.metrics.record(.candidateEvalFail)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .failed,
                title: "候选哈希失配",
                summary: "预期 \(Self.shortHash(expected))，实际 \(Self.shortHash(actual))。候选已变化，评测不会继续（§13.1）。"
            ))
        case .budgetExhausted(let exhaustion):
            dependencies.metrics.record(.candidateEvalFail)
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .failed,
                title: "评测预算耗尽",
                summary: Self.budgetExhaustionText(exhaustion)
            ))
        case .report(let report):
            await decideAndRoute(
                report: report,
                manifest: manifest,
                content: content,
                hypothesis: hypothesis,
                evidence: evidence,
                dataScopeSummary: dataScopeSummary
            )
        }
    }

    private func decideAndRoute(
        report: IOSEvaluationReport,
        manifest: IOSEvolutionCandidateManifest,
        content: Data,
        hypothesis: IOSGapHypothesis,
        evidence: [IOSEvolutionEvidence],
        dataScopeSummary: String?
    ) async {
        // 6. Policy evaluation (§13.4, invariant 16).
        stage = .deciding
        let decision = IOSPromotionPolicyEngine.decide(
            input: IOSPromotionPolicyInput(
                artifactKind: manifest.artifactKind,
                artifactName: manifest.artifactName,
                permissionEnvelopeRaw: manifest.permissionEnvelope,
                candidateHash: manifest.candidateHash,
                report: report,
                draftOnly: manifest.draftOnly
            ),
            state: dependencies.policyStateStore,
            autonomyLevel: dependencies.autonomyLevelProvider(),
            killSwitchEnabled: dependencies.killSwitchProvider(),
            configuration: dependencies.policyConfiguration
        )
        // §19 观测：policy engine 门禁拒绝（带原因键）/ protected regression
        // 拦截 / 评测失败 / neverAuto 拒绝——一行埋点，不改决策逻辑。
        recordDecisionMetrics(decision)
        if decision.canAutoApprove {
            // 7. T0/T1 autonomous publish: apply (CAS) → registry refresh →
            //    notification card with one-tap rollback (不变量 17).
            stage = .publishing
            let context = PendingPublishContext(
                manifest: manifest,
                content: content,
                report: report,
                evidence: evidence,
                hypothesis: hypothesis,
                dataScopeSummary: dataScopeSummary,
                originRunId: report.originRunId
            )
            await publish(context: context, approvedBy: IOSPromotionPolicyEngine.approvedBy)
            return
        }
        // 不变量 13 / §12.2：protected regression 是硬拒绝——人工批准不能
        // 绕过（只有用户明确改变产品契约并更新评测套件后才可能放行）。
        // 因此任何档位（T0/T1/T2/neverAuto）都不进入人工批准分支，降级为
        // 「先更新套件」通知；人工批准卡只保留给非 protected 硬门禁失败
        // 的候选。T0/T1 的拦截计数已在 recordDecisionMetrics 记录，其他
        // 档位在此补记（§19 protected regression 拦截次数与档位无关）。
        if report.protectedRegressions > 0 {
            if decision.tier != .t0 && decision.tier != .t1 {
                dependencies.metrics.record(.protectedRegressionBlocked)
            }
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .noOp,
                title: "存在受保护回归，候选已拒绝",
                summary: "评测发现 \(report.protectedRegressions) 条受保护成功样例回归；按 §12.2 硬拒绝，不能人工批准绕过。请先更新评测套件（明确产品契约变更并更新受保护样例），再重新发起分析。",
                detail: Self.gateFailuresText(decision)
            ))
            return
        }
        if decision.tier == .neverAuto {
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .noOp,
                title: "候选不进入自动通道",
                summary: Self.gateFailuresText(decision)
            ))
            return
        }
        // T2（或「仅通知」档下 T0/T1）：人工批准卡带完整自动评估结论（§14.2）。
        let autonomyLevel = dependencies.autonomyLevelProvider()
        let assessment: String?
        if decision.tier == .t0 || decision.tier == .t1 {
            assessment = Self.policyAssessmentText(decision: decision, report: report)
        } else {
            assessment = nil
        }
        pendingPublishContext = PendingPublishContext(
            manifest: manifest,
            content: content,
            report: report,
            evidence: evidence,
            hypothesis: hypothesis,
            dataScopeSummary: dataScopeSummary,
            originRunId: report.originRunId
        )
        pendingApproval = Self.approvalCardModel(
            manifest: manifest,
            content: content,
            report: report,
            evidence: evidence,
            hypothesis: hypothesis,
            tier: decision.tier,
            policyAssessment: assessment,
            policyAssessmentEvidenceGatesPassed: Self.evidenceGateFailureKeys(decision).isEmpty,
            autonomyLevel: autonomyLevel,
            dataScopeSummary: dataScopeSummary
        )
        stage = .done
        lastOutcome = "候选已就绪，等待你的批准。"
    }

    // MARK: - Publish

    private struct PendingPublishContext {
        let manifest: IOSEvolutionCandidateManifest
        let content: Data
        let report: IOSEvaluationReport?
        let evidence: [IOSEvolutionEvidence]
        let hypothesis: IOSGapHypothesis
        /// §18.2 data scope of the evaluation suite the report was built on.
        let dataScopeSummary: String?
        /// Slice B（B1）：同一 identity 链上的 originRunId（来自 suite →
        /// report）；发布闸与通知卡用它绑定触发流程的失败 run。
        let originRunId: String?
    }

    private func publish(context: PendingPublishContext, approvedBy: String) async {
        let recipeStore = IOSRecipeFileStore(baseDirectory: dependencies.recipeStoreBaseDirectory)
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: dependencies.recipeStoreBaseDirectory)
        do {
            let publishReceipt = try await IOSPromotionPublisher.publishRecipe(
                content: context.content,
                manifest: context.manifest,
                report: context.report,
                approvedBy: approvedBy,
                expectedOriginRunId: context.originRunId,
                recipeStore: recipeStore,
                receiptStore: receiptStore,
                refreshRegistry: dependencies.registryRefresh
            )
            if approvedBy == IOSPromotionPolicyEngine.approvedBy {
                // 只把「自动」发布计入每日预算/冷却（§13.4）；人工批准不算。
                dependencies.policyStateStore.recordAutoPromotion(
                    artifactId: context.manifest.artifactName,
                    configuration: dependencies.policyConfiguration
                )
                dependencies.metrics.record(.autoPublish)
                dependencies.metrics.recordPromotion(
                    artifactId: context.manifest.artifactName,
                    source: .auto
                )
            } else {
                dependencies.metrics.record(.userApprove)
                dependencies.metrics.recordPromotion(
                    artifactId: context.manifest.artifactName,
                    source: .manual
                )
            }
            let report = context.report
            let rollbackManifest: IOSRecipePreviousManifest? = {
                guard publishReceipt.storeReceipt.outcome == .applied,
                      case .available(let manifest) = try? recipeStore.rollbackAvailability(
                        name: context.manifest.artifactName
                      ),
                      manifest.promotedHash == context.manifest.candidateHash else {
                    return nil
                }
                return manifest
            }()
            let notification = IOSEvolutionNotificationModel(
                kind: .autoPromoted,
                title: approvedBy == IOSPromotionPolicyEngine.approvedBy
                    ? "已自动发布 \(context.manifest.artifactName)"
                    : "已发布 \(context.manifest.artifactName)",
                summary: "\(context.manifest.artifactKind == .recipe ? "Recipe" : "制品") \(context.manifest.artifactName) 已生效，从下一模型轮起可用；\(rollbackManifest == nil ? "本次没有新的可回退版本。" : "一键回退可用。")",
                detail: Self.promotionDetail(publishReceipt: publishReceipt)
                    + (context.dataScopeSummary.map { "\n评测数据范围：\($0)" } ?? ""),
                artifactName: context.manifest.artifactName,
                candidateHash: context.manifest.candidateHash,
                reportHash: report?.reportHash,
                originRunId: context.originRunId,
                rollbackManifest: rollbackManifest,
                canRollback: rollbackManifest != nil
            )
            // 与其余终止分支一致走 finishWith（内部 push + lastOutcome +
            // 复位 isRunning/stage）。否则自动发布成功后状态机不收口，
            // `analyzeAndImprove` 的 `guard !isRunning` 会从此永久拒绝
            // （用户需重启 App 才能再次发起分析）。
            finishWith(notification: notification)
        } catch {
            dependencies.metrics.record(.publishFailed)
            if Self.isStaleCASEror(error) {
                dependencies.metrics.record(.staleCASSkipped)
            }
            finishWith(notification: IOSEvolutionNotificationModel(
                kind: .failed,
                title: "发布失败（零写入）",
                summary: "\(error.localizedDescription)\n候选/基线已变化时不会发布（§13.1 CAS）。"
            ))
        }
    }

    private func finishWith(notification: IOSEvolutionNotificationModel) {
        push(notification: notification)
        lastOutcome = notification.summary
        isRunning = false
        stage = .done
    }

    private func push(notification: IOSEvolutionNotificationModel) {
        notifications.insert(notification, at: 0)
        if notifications.count > Self.maximumNotifications {
            notifications.removeLast(notifications.count - Self.maximumNotifications)
        }
    }

    // MARK: - §19 观测埋点辅助（Phase 4 Wave 1）

    /// policy engine 决策的观测侧记（不改决策逻辑）：
    /// - 分类放行（T0/T1）但硬门禁拦截 → 逐条记录门禁拒绝原因键（§19）；
    /// - protected regression 拦截 → 专用计数；
    /// - 报告建议不 promote → 评测失败计数；
    /// - 分类「永不自动」→ 专用计数（T2 是设计上的人工批准，不算拒绝）。
    private func recordDecisionMetrics(_ decision: IOSPromotionPolicyDecision) {
        guard decision.tier == .t0 || decision.tier == .t1 else {
            if decision.tier == .neverAuto {
                dependencies.metrics.record(.candidateNeverAutoRefused)
            }
            return
        }
        for failure in decision.gateFailures {
            dependencies.metrics.recordGateDenial(reasonKey: Self.gateReasonKey(failure))
        }
        if decision.gateFailures.contains(where: { $0.hasPrefix("protected_regressions") }) {
            dependencies.metrics.record(.protectedRegressionBlocked)
        }
        if decision.gateFailures.contains(where: { $0.hasPrefix("recommendation_not_promote") }) {
            dependencies.metrics.record(.candidateEvalFail)
        }
    }

    /// 门禁失败文本的稳定原因键（":" 前缀；与 `IOSPromotionPolicyEngine`
    /// 硬门禁 append 的键一致，如 "kill_switch"、"cooldown"）。
    private static func gateReasonKey(_ failure: String) -> String {
        String(failure.prefix(while: { $0 != ":" }))
    }

    /// 发布失败是否属于 stale CAS（base/candidate 任一变化，§13.1 fail-closed）。
    private static func isStaleCASEror(_ error: Error) -> Bool {
        guard let storeError = error as? IOSRecipeFileStoreError else { return false }
        switch storeError {
        case .recipePackageBaseChanged, .recipePackageCandidateChanged:
            return true
        default:
            return false
        }
    }

    // MARK: - Evidence projection (I-1)

    /// 只取投影需要的最小字段并以 Sendable 值跨 continuation 返回
    /// （`AgentRunEntity` 非 Sendable，直接 resume 会触发 Swift 6
    /// "sending ... risks causing data races"）。
    private struct RunLookup: Sendable {
        let runId: String
        let conversationId: String?
        let status: String
        let startedAt: Int64
    }

    /// Slice B（B1）证据冻结：显式指定会话后，只解析该会话最近 terminal run
    /// 的投影证据并立即冻结——证据为空也原样返回（走既有 noOp 终止路径，
    /// 模型不被调用，I-3），【绝不】回退到最近 7 天全局窗口（跨会话泄漏）。
    /// 全局 recent evidence 只保留给没有指定会话的显式管理入口
    /// （RecipesView 传 nil，`conversationHex == nil`）。
    private func projectEvidence(conversationHex: String?) async -> [IOSEvolutionEvidence] {
        let dao = dependencies.dao
        if let conversationHex {
            let runs = await readRuns(dao: dao)
            let terminal = runs.filter {
                $0.conversationId == conversationHex && Self.isTerminalStatus($0.status)
            }
            guard let latest = terminal.max(by: { $0.startedAt < $1.startedAt }) else {
                return []
            }
            return await IOSEvolutionEvidenceProjector.project(runId: latest.runId, dao: dao)
        }
        let sinceEpochMs = Int64(Date().timeIntervalSince1970 * 1000) - 7 * 24 * 60 * 60 * 1000
        return await IOSEvolutionEvidenceProjector.projectRecent(sinceEpochMs: sinceEpochMs, dao: dao)
    }

    private func readRuns(dao: AgentRuntimeDao) async -> [RunLookup] {
        await withCheckedContinuation { (continuation: CheckedContinuation<[RunLookup], Never>) in
            dao.listAllRuns { result, error in
                guard error == nil, let result else {
                    continuation.resume(returning: [])
                    return
                }
                continuation.resume(returning: result.map {
                    RunLookup(
                        runId: $0.runId,
                        conversationId: $0.conversationId,
                        status: $0.status,
                        startedAt: $0.startedAt
                    )
                })
            }
        }
    }

    private static func isTerminalStatus(_ status: String) -> Bool {
        ["completed", "failed", "truncated", "interrupted"].contains(status)
    }

    // MARK: - Card assembly (§14.2)

    private static func approvalCardModel(
        manifest: IOSEvolutionCandidateManifest,
        content: Data,
        report: IOSEvaluationReport,
        evidence: [IOSEvolutionEvidence],
        hypothesis: IOSGapHypothesis,
        tier: IOSPromotionTier,
        policyAssessment: String?,
        policyAssessmentEvidenceGatesPassed: Bool,
        autonomyLevel: IOSEvolutionAutonomyLevel,
        dataScopeSummary: String?
    ) -> IOSEvolutionApprovalCardModel {
        let evidenceIds = hypothesis.evidenceIds
        let evidenceLines = evidence
            .filter { evidenceIds.contains($0.id) }
            .map { "- \($0.redactedSummary)（\($0.sourceRefs.map { "\($0.kind.rawValue):\($0.id)" }.joined(separator: ", "))）" }
            .joined(separator: "\n")
        let results = report.results.map { result -> String in
            let marker = result.passed ? "✓" : "✗"
            let failure = result.failureCode.map { "（\($0.rawValue)）" } ?? ""
            return "\(marker) \(result.kind.rawValue) \(result.caseId)\(failure)"
        }.joined(separator: "\n")
        let jsonPreview = String(data: content, encoding: .utf8) ?? "（无法预览）"
        let decodedManifest = try? IOSRecipeManifest.decode(content)
        let stepsSummary = decodedManifest?.steps.map { "\($0.id) → \($0.tool)" } ?? []
        return IOSEvolutionApprovalCardModel(
            observedSummary: evidenceLines.isEmpty
                ? "（无匹配 evidence 摘要）"
                : evidenceLines,
            evidenceRefs: evidenceIds,
            diagnosis: hypothesis.claim,
            alternatives: hypothesis.alternatives,
            falsifier: hypothesis.falsifier,
            artifactKind: manifest.artifactKind,
            artifactName: manifest.artifactName,
            artifactVersion: decodedManifest?.version ?? "",
            mutationKind: manifest.baseHash == nil ? .new : .update,
            changeSummary: decodedManifest?.description ?? "新 Recipe 候选（hash 绑定评测报告）",
            stepsSummary: stepsSummary,
            permissionSummary: IOSDynamicToolRegistry.permissionSummary(
                for: IOSToolEffectClass(rawValue: manifest.permissionEnvelope.first ?? "") ?? .sideEffect
            ),
            permissionEnvelopeRaw: manifest.permissionEnvelope.joined(separator: ", "),
            baseHash: manifest.baseHash,
            candidateHash: manifest.candidateHash,
            reportHash: report.reportHash,
            evaluationResultsText: results,
            unresolvedRisks: report.unresolvedRisks,
            skippedTiersText: report.skippedTiers.map(\.rawValue).joined(separator: ", "),
            candidateJSONPreview: jsonPreview,
            policyAssessment: policyAssessment,
            policyAssessmentEvidenceGatesPassed: policyAssessmentEvidenceGatesPassed,
            dataScopeSummary: dataScopeSummary,
            id: manifest.candidateId
        )
    }

    /// Draft-only 通知的 detail：附上套件不可用的 typed 原因与（若有）数据范围。
    private static func draftOnlyDetail(
        reason: String?,
        dataScopeSummary: String?
    ) -> String {
        var lines = ["v1 不把草稿自动写入 Workspace；如需落地请手工导入或等评测套件就绪。"]
        if let reason, !reason.isEmpty {
            lines.append("评测套件不可用：\(reason)")
        }
        if let dataScopeSummary, !dataScopeSummary.isEmpty {
            lines.append("评测数据范围：\(dataScopeSummary)")
        }
        return lines.joined(separator: "\n")
    }

    private static func draftPreview(manifest: IOSEvolutionCandidateManifest, content: Data) -> String {
        let text = String(data: content, encoding: .utf8) ?? "（无法预览）"
        return "候选 ID: \(manifest.candidateId)\n制品: \(manifest.artifactName)\n候选 hash: \(manifest.candidateHash)\n\n\(text)"
    }

    private static func promotionDetail(publishReceipt: IOSPromotionPublishReceipt) -> String {
        let receipt = publishReceipt.promotionReceipt
        return "approvedBy=\(receipt.approvedBy) report=\(Self.shortHash(receipt.evaluationReportHash ?? "")) revision=\(receipt.catalogRevision.map(String.init) ?? "-")"
    }

    private static func policyAssessmentText(
        decision: IOSPromotionPolicyDecision,
        report: IOSEvaluationReport
    ) -> String {
        let tierName: String
        switch decision.tier {
        case .t0: tierName = "T0"
        case .t1: tierName = "T1"
        case .t2: tierName = "T2"
        case .neverAuto: tierName = "永不自动"
        }
        let reasons = decision.classificationReasons.joined(separator: "；")
        // 证据类硬门禁（报告/哈希/建议/受保护回归/套件完整性）与政策类门禁
        // （自治级别/kill switch/冷却/预算/熔断）分开陈述：「仅通知」档需要
        // 明确「如果放开自治级别，自动通道是否本来就放行」。
        let evidenceGateFailures = Self.evidenceGateFailureKeys(decision)
        let evidenceGateText = evidenceGateFailures.isEmpty
            ? "证据硬门禁全部通过（报告匹配候选哈希、建议 promote、0 条受保护回归）"
            : "证据硬门禁未通过：\(evidenceGateFailures.joined(separator: "；"))"
        let policyGateFailures = decision.gateFailures.filter { failure in
            !evidenceGateFailures.contains(failure)
        }
        let policyGateText = policyGateFailures.isEmpty
            ? "无其他政策门禁拦截"
            : "未自动原因：\(policyGateFailures.joined(separator: "；"))"
        return "policy engine 自动评估结论：分级 \(tierName)（\(reasons)）；\(evidenceGateText)；\(policyGateText)。评测建议：\(report.recommendation.rawValue)。因当前自治级别，仍需要你人工批准。"
    }

    private static func gateFailuresText(_ decision: IOSPromotionPolicyDecision) -> String {
        decision.gateFailures.joined(separator: "\n")
    }

    /// 证据类硬门禁失败键（与 policyAssessmentText 的文案分类同一事实源，
    /// 卡片据此选择结论颜色）。
    private static func evidenceGateFailureKeys(_ decision: IOSPromotionPolicyDecision) -> [String] {
        decision.gateFailures.filter { failure in
            [
                "no_evaluation_report",
                "report_candidate_hash_mismatch",
                "recommendation_not_promote",
                "protected_regressions",
                "draft_only",
            ].contains { failure.hasPrefix($0) }
        }
    }

    private static func diagnosisErrorText(_ error: IOSDiagnosisError) -> String {
        switch error {
        case .modelCallFailed(let detail): "模型调用失败：\(detail)"
        case .malformedModelOutput(let detail): "模型输出非法：\(detail)"
        default: "\(error)"
        }
    }

    private static func buildErrorText(_ error: IOSCandidateBuildError) -> String {
        switch error {
        case .budgetExhausted(let attempts, _, let lastIssue):
            "候选生成在 \(attempts) 次尝试后仍未通过校验（预算耗尽）。最后问题：\(lastIssue)"
        case .artifactNotAllowedForKind(let kind, let artifact):
            "假设类型 \(kind.rawValue) 不允许制品 \(artifact?.rawValue ?? "nil")。"
        }
    }

    private static func budgetExhaustionText(_ exhaustion: IOSEvaluationBudgetExhaustion) -> String {
        switch exhaustion {
        case .caseCount(let maxCaseCount):
            "评测 case 数超过预算（\(maxCaseCount)）。"
        case .wallClock(let maxWallClockSeconds):
            "评测超过墙钟预算（\(Int(maxWallClockSeconds)) 秒）。"
        }
    }

    static func shortHash(_ hash: String) -> String {
        String(hash.prefix(10))
    }
}

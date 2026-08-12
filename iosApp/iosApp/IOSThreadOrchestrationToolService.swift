import Foundation
@preconcurrency import Shared

/// P1-c: 线程编排工具执行体（spawn_agent / list_agents / interrupt_agent）。
///
/// 设计要点（照 `IOSSkillMcpToolService` 模式做独立服务，经
/// `ChatToolRuntime.dispatchAdvancedToolCall` 分发）：
/// - 存储即真相：fork 会话走 `IOSConversationStore`（同写路径锁），spawn 边走
///   Room `thread_edge`，信封走 Room `mailbox_envelope`（NEW_TASK 直接渲染持久化
///   进子会话并标记 delivered，不等任何消费点；P1-d 后台引擎才有消费点）。
/// - 子 run 启动：durable 先行——构造 `IOSChatBackgroundHandoff` 落 payload 再经
///   后台协调器提交 BGTask（与既有「先落 handoff 再 submit」路径同构；子线程在
///   后台引擎里同样注册三工具，可 spawn 孙线程）。
/// - 终态回传：`notifyRunTerminal` 检查本会话是否有 Open 父 edge，有则向父线程
///   的 mailbox 投递 FINAL_ANSWER（前台 finishStreaming 与后台 job 终态两个挂钩点）。
@MainActor
final class IOSThreadOrchestrationToolService {

    /// P1-c 结构化错误码（常量集中；P1-e 会按并发限额细化文案/参数）。
    enum ErrorCode {
        static let agentLimitReached = "agent_limit_reached"
        static let agentDepthLimit = "agent_depth_limit"
        static let invalidTaskName = "invalid_task_name"
        static let invalidArguments = "invalid_arguments"
        static let unknownTarget = "unknown_target"
        static let notADescendant = "not_a_descendant"
        static let missingContext = "missing_context"
        static let startFailed = "start_failed"
        /// P1-d: send_message/followup_task 拒绝发给自己。
        static let cannotMessageSelf = "cannot_message_self"
    }

    /// 线程边状态（wire 字符串，与 KMP `ThreadEdgeStatus` 常量同值）。
    enum EdgeStatus {
        static let open = "Open"
        static let closed = "Closed"
    }

    /// 深度上限：/root(0) → 子(1) → 孙(2)。spawn 时沿 edge 链查父深度，
    /// 父深度 >= 2（将产生第 3 代）时拒绝。
    static let maxThreadDepth = 2

    /// 并发上限：活注册表占用（前台 run 0/1 + 后台 activeJobs + 在途 bootstrap）
    /// >= 该值时拒绝 spawn。保留默认常量，不做设置 UI（P1-e 计划：不加 UI）。
    static let defaultMaxConcurrentRuns = 4

    /// FINAL_ANSWER payload 截断长度。
    static let finalAnswerMaxChars = 2_000

    /// 后台 job 的调度面（生产 = `IOSChatBackgroundGenerationCoordinator.shared`，
    /// 测试注入 fake 捕获 handoff）。childThreadId 一律用 hex-dash 字符串传递，
    /// 避免在 Swift 侧从字符串重建 KotlinUuid。
    @MainActor
    protocol BackgroundScheduling: AnyObject {
        func start(
            handoff: IOSChatBackgroundHandoff,
            conversationStore: IOSConversationStore,
            toolRuntime: ChatToolRuntime,
            liveActivityController: AgentLiveActivityController,
            saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
        ) -> Bool

        func activeRunId(conversationHex: String) -> String?

        /// P1-e: 后台活跃 job 计数（并发限额的活注册表来源之一；生产 =
        /// activeJobs.count）。
        var activeJobCount: Int { get }

        @discardableResult
        func cancelJob(runId: String) -> Bool
    }

    /// Sendable 投影：Kotlin `ThreadEdgeEntity` 导出为非 Sendable，跨隔离边界只传
    /// 字符串字段（转换在 DAO 回调内完成，遵循 IOSMailboxStore 既有模式）。
    struct ThreadEdgeSnapshot: Sendable {
        let childThreadId: String
        let parentThreadId: String
        let agentPath: String
        let nickname: String?
        let roleAssistantId: String?
        let forkTurns: String
        let status: String

        init(_ entity: ThreadEdgeEntity) {
            self.childThreadId = entity.childThreadId
            self.parentThreadId = entity.parentThreadId
            self.agentPath = entity.agentPath
            self.nickname = entity.nickname
            self.roleAssistantId = entity.roleAssistantId
            self.forkTurns = entity.forkTurns
            self.status = entity.status
        }
    }

    /// P1-d 目标解析结果（hex = 目标会话 id，edge = 目标边记录，root 为 nil）。
    private struct ResolvedTarget: Sendable {
        let hex: String
        let edge: ThreadEdgeSnapshot?
    }

    /// P1-d 目标解析错误（结构化 code/reason，由调用方以本工具名组装 errorJSON）。
    private struct TargetError: Error, Sendable {
        let code: String
        let reason: String
    }

    /// P1-d wait_agent 竞速结果。
    private enum MailboxWaitOutcome: Sendable {
        case activity
        case timeout
        case cancelled
    }

    private let conversationStoreProvider: () -> IOSConversationStore?
    private let mailboxDaoProvider: () -> MailboxDao
    private let threadEdgeDaoProvider: () -> ThreadEdgeDao
    private let agentRuntimeDaoProvider: () -> AgentRuntimeDao
    private let backgroundCoordinator: any BackgroundScheduling
    private let makeBackgroundToolRuntime: () -> ChatToolRuntime
    private let currentConversationId: () -> KotlinUuid?
    private let foregroundActiveRunId: (String) -> String?
    private let cancelForegroundRun: (String) -> Bool
    /// P1-e: 全局前台活跃 run 判定（0/1）。生产 = `generationCoordinator.isRunning`
    /// （P1-c `activeForegroundRunId` 的同一来源，任意会话都算 1 个活跃槽）。
    private let foregroundRunActive: () -> Bool
    private let maxConcurrentRuns: Int
    /// P1-e: spawn bootstrap 在途计数（服务内跟踪）。限额检查与占槽之间无
    /// await（全部 MainActor 同步读），两个并发 spawn 不会同时通过检查。
    private var inFlightBootstrapCount = 0
    /// P1-d: 进程内 mailbox 活动广播（wait_agent 的事件源；默认共享实例）。
    private let activityCenter: IOSMailboxActivityCenter
    /// M4: role_assistant_id 存在性校验（生产 = 设置快照里的 assistants；
    /// 默认恒真 = 未注入校验器的调用方/测试不误伤，生产接线必须注入）。
    private let roleAssistantExists: (KotlinUuid) -> Bool
    private let soulMarkdown: () -> String
    /// P1-d: wait_agent 超时 clamp 区间与默认值（测试注入小下限避免真实等待）。
    private let waitTimeoutMinMs: Int64
    private let waitTimeoutMaxMs: Int64
    private let waitTimeoutDefaultMs: Int64

    init(
        conversationStoreProvider: @escaping () -> IOSConversationStore?,
        mailboxDaoProvider: @escaping () -> MailboxDao,
        threadEdgeDaoProvider: @escaping () -> ThreadEdgeDao,
        agentRuntimeDaoProvider: @escaping () -> AgentRuntimeDao,
        backgroundCoordinator: any BackgroundScheduling,
        makeBackgroundToolRuntime: @escaping () -> ChatToolRuntime,
        currentConversationId: @escaping () -> KotlinUuid?,
        foregroundActiveRunId: @escaping (String) -> String?,
        cancelForegroundRun: @escaping (String) -> Bool,
        foregroundRunActive: @escaping () -> Bool = { false },
        maxConcurrentRuns: Int = IOSThreadOrchestrationToolService.defaultMaxConcurrentRuns,
        activityCenter: IOSMailboxActivityCenter = .shared,
        waitTimeoutMinMs: Int64 = 5_000,
        waitTimeoutMaxMs: Int64 = 300_000,
        waitTimeoutDefaultMs: Int64 = 30_000,
        roleAssistantExists: @escaping (KotlinUuid) -> Bool = { _ in true },
        soulMarkdown: @escaping () -> String = { "" }
    ) {
        self.conversationStoreProvider = conversationStoreProvider
        self.mailboxDaoProvider = mailboxDaoProvider
        self.threadEdgeDaoProvider = threadEdgeDaoProvider
        self.agentRuntimeDaoProvider = agentRuntimeDaoProvider
        self.backgroundCoordinator = backgroundCoordinator
        self.makeBackgroundToolRuntime = makeBackgroundToolRuntime
        self.currentConversationId = currentConversationId
        self.foregroundActiveRunId = foregroundActiveRunId
        self.cancelForegroundRun = cancelForegroundRun
        self.foregroundRunActive = foregroundRunActive
        self.maxConcurrentRuns = maxConcurrentRuns
        self.activityCenter = activityCenter
        self.waitTimeoutMinMs = waitTimeoutMinMs
        self.waitTimeoutMaxMs = waitTimeoutMaxMs
        self.waitTimeoutDefaultMs = waitTimeoutDefaultMs
        self.roleAssistantExists = roleAssistantExists
        self.soulMarkdown = soulMarkdown
    }

    // MARK: - Dispatch

    func execute(
        toolName: String,
        arguments: String,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        conversationId: KotlinUuid? = nil,
        toolExposureBridge: IosToolExposureBridge? = nil
    ) async -> String {
        switch toolName {
        case "spawn_agent":
            return await spawnAgent(
                arguments: arguments,
                providerSetting: providerSetting,
                params: params,
                parentRunId: runId,
                conversationId: conversationId,
                toolExposureBridge: toolExposureBridge
            )
        case "list_agents":
            return await listAgents(arguments: arguments, conversationId: conversationId)
        case "interrupt_agent":
            return await interruptAgent(arguments: arguments, conversationId: conversationId)
        case "send_message":
            return await sendMessage(arguments: arguments, runId: runId, conversationId: conversationId)
        case "followup_task":
            return await followupTask(
                arguments: arguments,
                providerSetting: providerSetting,
                params: params,
                runId: runId,
                conversationId: conversationId,
                toolExposureBridge: toolExposureBridge
            )
        case "wait_agent":
            return await waitAgent(arguments: arguments, conversationId: conversationId)
        default:
            return Self.errorJSON(toolName: toolName, code: ErrorCode.invalidArguments, reason: "未知编排工具 \(toolName)")
        }
    }

    // MARK: - spawn_agent

    /// P1-e: 活注册表占用槽数 = 在途 bootstrap + 前台活跃 run(0/1) + 后台活跃
    /// job。替代全局 recoverable 账本计数：崩溃残留的 running 行不再占用
    /// 并发槽（恢复扫描前的窗口期不误伤 spawn）。
    private var occupiedRunSlotCount: Int {
        inFlightBootstrapCount
            + (foregroundRunActive() ? 1 : 0)
            + backgroundCoordinator.activeJobCount
    }

    private func spawnAgent(
        arguments: String,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        parentRunId: String,
        conversationId: KotlinUuid?,
        toolExposureBridge: IosToolExposureBridge?
    ) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(arguments),
              let taskNameRaw = args["task_name"] as? String,
              let message = args["message"] as? String else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.invalidArguments,
                reason: "spawn_agent 需要 task_name 与 message。"
            )
        }
        let taskName = taskNameRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard Self.isValidTaskName(taskName) else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.invalidTaskName,
                reason: "task_name 只能包含小写字母、数字与下划线（^[a-z0-9_]+$），实际: \(taskNameRaw)"
            )
        }
        let forkTurns = (args["fork_turns"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? "all"
        guard forkTurns == "none" || forkTurns == "all" || (Int(forkTurns) ?? -1) > 0 else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.invalidArguments,
                reason: "fork_turns 必须是 \"none\"、\"all\" 或正整数，实际: \(forkTurns)"
            )
        }
        let roleAssistantId = (args["role_assistant_id"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        // M4: role_assistant_id 应用到子会话 assistantId（fork 后改字段再保存）。
        // 先校验：格式非法或 assistant 不存在 → 诚实错误，不创建子线程。
        // 注意 KotlinUuid.parse 对非法串在 K/N 导出下不抛 Swift 错误而是终止
        // 进程（NSException）——必须先用 Foundation UUID(uuidString:) 正则级
        // 校验拦截（并归一化小写，Kotlin stdlib 的 parse 只认小写十六进制）。
        var resolvedRoleAssistantId: KotlinUuid? = nil
        if let roleAssistantId {
            let normalizedRoleId = roleAssistantId.lowercased()
            guard UUID(uuidString: normalizedRoleId) != nil else {
                return Self.errorJSON(
                    toolName: "spawn_agent",
                    code: ErrorCode.invalidArguments,
                    reason: "role_assistant_id 不是合法的 assistant id：\(roleAssistantId)"
                )
            }
            let roleUuid = KotlinUuid.companion.parse(uuidString: normalizedRoleId)
            guard roleAssistantExists(roleUuid) else {
                return Self.errorJSON(
                    toolName: "spawn_agent",
                    code: ErrorCode.invalidArguments,
                    reason: "role_assistant_id 不存在：\(roleAssistantId)。请用 list 或设置页确认 assistant id。"
                )
            }
            resolvedRoleAssistantId = roleUuid
        }

        guard let store = conversationStoreProvider() else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.missingContext,
                reason: "会话存储不可用，无法创建子线程。"
            )
        }
        // 父会话 = run 锚定的 conversationId（execute 透传；生成中切会话不串边）。
        // `currentConversationId()` 仅作兜底：调用方未透传时（旧调用点/直接驱动
        // 服务的测试）才回退读 VM 当前会话。
        guard let parentConversationId = conversationId ?? currentConversationId() else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.missingContext,
                reason: "当前会话不可用，无法确定父线程。"
            )
        }
        let parentHex = parentConversationId.toHexDashString()
        let threadEdgeDao = threadEdgeDaoProvider()

        // (b) 深度上限（父深度 >= 2 → 第 3 代拒绝）。
        let parentDepth = await depth(of: parentHex, threadEdgeDao: threadEdgeDao)
        guard parentDepth < Self.maxThreadDepth else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.agentDepthLimit,
                reason: "线程深度已达上限（/root 起最多 \(Self.maxThreadDepth) 层）。请用当前线程完成子任务或汇总结果。"
            )
        }
        // (c) 并发上限：活注册表占用（前台 run 0/1 + 后台 activeJobs + 本服务在途
        // bootstrap 槽，含 spawner 自身——与旧全局账本计数「含父自身」同语义）。
        // 检查与占槽之间无 await（三个计数源全部 MainActor 同步），并发 spawn
        // 不会双双通过；defer 在 spawn 收口（成功入后台注册表或失败回收）时释放。
        guard occupiedRunSlotCount < maxConcurrentRuns else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.agentLimitReached,
                reason: "并发运行数已达上限（\(maxConcurrentRuns) 个，含本线程）。请先等待或 interrupt 一个子线程再 spawn。"
            )
        }
        inFlightBootstrapCount += 1
        defer { inFlightBootstrapCount -= 1 }

        let parentAgentPath = await agentPath(of: parentHex, threadEdgeDao: threadEdgeDao)
        // (a) task_name 冲突自动 _2 后缀（同父下，按已有 agentPath 判定）。
        let resolvedTaskName = await Self.uniqueTaskName(
            taskName,
            parentAgentPath: parentAgentPath,
            threadEdgeDao: threadEdgeDao
        )
        let childAgentPath = "\(parentAgentPath)/\(resolvedTaskName)"
        let childConversationId = KotlinUuid.companion.random()
        let childHex = childConversationId.toHexDashString()
        let now = Int64(Date().timeIntervalSince1970 * 1000)

        // (d) fork 会话（三模式 + 变体折叠 + 截断安全），直接经 store 落盘。
        let sourceConversation: Conversation?
        if let current = store.currentConversation, current.id == parentConversationId {
            sourceConversation = current
        } else {
            sourceConversation = try? await store.loadConversationForOrchestration(parentConversationId)
        }
        guard let sourceConversation else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.missingContext,
                reason: "无法读取当前会话，无法 fork。"
            )
        }
        let forked = ConversationForkKt.forkConversation(
            source: sourceConversation,
            newId: childConversationId,
            newTitle: resolvedTaskName,
            forkTurns: forkTurns,
            assistantId: resolvedRoleAssistantId
        )
        guard await store.saveForkedConversation(forked) else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.startFailed,
                reason: "子会话持久化失败，请重试。"
            )
        }

        // (f) bootstrap：NEW_TASK 信封渲染为 user 消息直接持久化进子会话，
        // 信封 Room 记录标 delivered（不等任何消费点）。
        let renderedTask = MailboxEnvelopeKt.renderMailboxEnvelopeToUserText(
            authorThreadId: parentAgentPath,
            type: MailboxEnvelopeType.theNewTask.name,
            payload: message
        )
        let newTaskMessage = UIMessage.companion.user(prompt: renderedTask)
        let childMessages = forked.currentMessages + [newTaskMessage]
        guard await Self.persistTargetMessages(store: store, conversationId: childConversationId, messages: childMessages) else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.startFailed,
                reason: "子会话写入初始任务失败，请重试。"
            )
        }
        // 审计信封入队失败不阻塞 spawn（会话与边已持久化；IGNORE 幂等且
        // 不会像 ABORT 那样把 SQLite 冲突异常带出 @Throws 边界）。
        try? await Self.enqueueIfAbsent(
            mailboxDao: mailboxDaoProvider(),
            envelope: MailboxEnvelopeEntity(
                id: "new-task-\(childHex)",
                authorThreadId: parentAgentPath,
                recipientThreadId: childHex,
                type: MailboxEnvelopeType.theNewTask.name,
                payload: message,
                triggerTurn: true,
                parentTurnId: parentRunId,
                createdAt: now,
                deliveredAt: KotlinLong(value: now) // bootstrap 已直接渲染持久化，信封仅留审计记录
            )
        )

        // (e) 写 thread_edge（Open）。
        await Self.insertEdge(
            threadEdgeDao: threadEdgeDao,
            edge: ThreadEdgeEntity(
                childThreadId: childHex,
                parentThreadId: parentHex,
                agentPath: childAgentPath,
                nickname: nil,
                roleAssistantId: roleAssistantId,
                forkTurns: forkTurns,
                status: EdgeStatus.open,
                createdAt: now
            )
        )

        // (g) 启动子 run（P1-d 抽取助手：记账 + durable handoff → BGTask；
        // start 失败回收 running 行，见助手内注释）。
        let childRunId = UUID().uuidString
        guard let _ = await startDurableBackgroundRun(
            targetConversationId: childConversationId,
            targetHex: childHex,
            targetMessages: childMessages,
            renderedText: renderedTask,
            providerSetting: providerSetting,
            params: params,
            runId: childRunId,
            store: store,
            toolExposureBridge: toolExposureBridge
        ) else {
            return Self.errorJSON(
                toolName: "spawn_agent",
                code: ErrorCode.startFailed,
                reason: "子线程已创建但后台任务提交失败（子会话与边已持久化，线程保留可再次派活）。"
            )
        }

        return IOSWorkspaceStore.json([
            "ok": true,
            "tool": "spawn_agent",
            "task_name": resolvedTaskName,
            "agent_path": childAgentPath,
            "child_thread_id": childHex,
            "status": "started",
        ])
    }

    // MARK: - list_agents

    /// P1-e: 控制面纪律查询——该会话是否存在 thread_edge（有 edge 即编排子线程，
    /// 只读；含 Closed 边：interrupt 取消/已完成线程同样不可直接输入）。
    /// 调用方（ChatViewModel）在会话切换时刷新并缓存，不在每条 keystroke 上直查。
    func isOrchestratedChild(conversationId: KotlinUuid) async -> Bool {
        await Self.edgeFor(
            childThreadId: conversationId.toHexDashString(),
            threadEdgeDao: threadEdgeDaoProvider()
        ) != nil
    }

    /// 是否有子边（作为父线程）。供运行时上下文注入判定：只有参与线程树的
    /// 会话才注入 mailbox 语义说明，普通会话不付这笔 token。
    func hasOrchestrationChildren(conversationId: KotlinUuid) async -> Bool {
        await withCheckedContinuation { cont in
            threadEdgeDaoProvider().childrenOf(parentThreadId: conversationId.toHexDashString()) { result, _ in
                cont.resume(returning: !(result ?? []).isEmpty)
            }
        }
    }

    /// 子线程首个后台 run 的编排语境（管线闭环场景 C）：子线程 upload 不经
    /// ChatViewModel 注入管线，在 handoff 组装时直接注入子线程向文案。
    static func childUploadMessages(
        targetMessages: [UIMessage],
        soulMarkdown: String
    ) -> [UIMessage] {
        var messages = targetMessages
        if let soul = ChatRuntimeContextBuilder.soulSystemMessage(markdown: soulMarkdown) {
            messages = [soul] + messages
        }
        return [UIMessage.companion.system(prompt: childOrchestrationContextPrompt)] + messages
    }

    static let childOrchestrationContextPrompt = """
    You are a child agent thread in a thread-orchestration tree.
    - Your task arrives as a `[mailbox NEW_TASK from /root/...]` message; `[mailbox MESSAGE|FINAL_ANSWER from /root/...]` are inter-agent mail, not user input.
    - Your final answer is delivered to the parent thread automatically when this run ends — no need to contact the user.
    - Reach the parent with send_message; block for new mail mid-run with wait_agent.
    """

    /// 子线程 run 的输出 token 地板（真机回归：子线程曾继承聊天的几千 token
    /// 上限，长报告被截断为"达到输出上限"终态）。
    static let childRunMinOutputTokens: Int32 = 32_768

    private func listAgents(arguments: String, conversationId: KotlinUuid?) async -> String {
        // 根 = run 锚定的会话（execute 透传）；currentConversationId() 仅兜底。
        guard let parentConversationId = conversationId ?? currentConversationId() else {
            return Self.errorJSON(
                toolName: "list_agents",
                code: ErrorCode.missingContext,
                reason: "当前会话不可用，无法列出子线程。"
            )
        }
        let parentHex = parentConversationId.toHexDashString()
        let args = ChatToolCallParsing.jsonObject(arguments)
        let pathPrefix = (args?["path_prefix"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty

        let edges = await Self.descendants(of: parentHex, threadEdgeDao: threadEdgeDaoProvider())
        let runsByConversation = await Self.latestRunStatusByConversation(
            agentRuntimeDao: agentRuntimeDaoProvider()
        )
        var items: [[String: Any]] = []
        for edge in edges {
            if let pathPrefix, !edge.agentPath.hasPrefix(pathPrefix) { continue }
            items.append([
                "child_thread_id": edge.childThreadId,
                "agent_path": edge.agentPath,
                "nickname": edge.nickname ?? "",
                "role": edge.roleAssistantId ?? "",
                "status": edge.status,
                "fork_turns": edge.forkTurns,
                "run_status": runsByConversation[edge.childThreadId] ?? "none",
            ])
        }
        return IOSWorkspaceStore.json([
            "ok": true,
            "tool": "list_agents",
            "threads": items,
            "count": items.count,
        ])
    }

    // MARK: - interrupt_agent

    private func interruptAgent(arguments: String, conversationId: KotlinUuid?) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(arguments),
              let targetRaw = args["target"] as? String else {
            return Self.errorJSON(
                toolName: "interrupt_agent",
                code: ErrorCode.invalidArguments,
                reason: "interrupt_agent 需要 target（child_thread_id 或 agent path）。"
            )
        }
        let target = targetRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty, target != "/root" else {
            return Self.errorJSON(
                toolName: "interrupt_agent",
                code: ErrorCode.invalidArguments,
                reason: "不能中断根线程或空 target。"
            )
        }
        // 根 = run 锚定的会话（execute 透传）；currentConversationId() 仅兜底。
        guard let parentConversationId = conversationId ?? currentConversationId() else {
            return Self.errorJSON(
                toolName: "interrupt_agent",
                code: ErrorCode.missingContext,
                reason: "当前会话不可用，无法解析目标线程。"
            )
        }
        let parentHex = parentConversationId.toHexDashString()

        // 按 childThreadId 或 agentPath 解析；必须是 run 锚定会话的 descendant。
        let dao = threadEdgeDaoProvider()
        let targetEdge: ThreadEdgeSnapshot?
        if target.contains("/") {
            let all = await Self.allEdges(threadEdgeDao: dao)
            targetEdge = all.first { $0.agentPath == target }
        } else {
            targetEdge = await Self.edgeFor(childThreadId: target, threadEdgeDao: dao)
        }
        guard let targetEdge else {
            return Self.errorJSON(
                toolName: "interrupt_agent",
                code: ErrorCode.unknownTarget,
                reason: "找不到线程 \(target)。请用 list_agents 查看可寻址线程。"
            )
        }
        let descendants = await Self.descendants(of: parentHex, threadEdgeDao: dao)
        guard descendants.contains(where: { $0.childThreadId == targetEdge.childThreadId }) else {
            return Self.errorJSON(
                toolName: "interrupt_agent",
                code: ErrorCode.notADescendant,
                reason: "线程 \(target) 不是当前线程的后代，不能从中断它。"
            )
        }
        let childHex = targetEdge.childThreadId

        // 活跃 run：前台当前会话 run 优先，其次后台 job；都无 → idle（不算错误，
        // 线程保留 edge 仍 Open）。
        if let runId = foregroundActiveRunId(childHex) {
            _ = cancelForegroundRun(runId)
            return Self.interruptResultJSON(target: target, childHex: childHex, threadStatus: targetEdge.status, previousStatus: "running")
        }
        if let backgroundRunId = backgroundCoordinator.activeRunId(conversationHex: childHex) {
            _ = backgroundCoordinator.cancelJob(runId: backgroundRunId)
            return Self.interruptResultJSON(target: target, childHex: childHex, threadStatus: targetEdge.status, previousStatus: "running")
        }
        return Self.interruptResultJSON(target: target, childHex: childHex, threadStatus: targetEdge.status, previousStatus: "idle")
    }

    // MARK: - send_message / followup_task / wait_agent（P1-d）

    /// P1-d: 投递不唤醒。信封（type=MESSAGE, triggerTurn=false）入目标 mailbox，
    /// idle 目标的消息留在 mailbox 直到其下次 run；运行中目标在工具循环边界折入。
    private func sendMessage(arguments: String, runId: String, conversationId: KotlinUuid?) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(arguments),
              let targetRaw = args["target"] as? String,
              let message = args["message"] as? String else {
            return Self.errorJSON(
                toolName: "send_message",
                code: ErrorCode.invalidArguments,
                reason: "send_message 需要 target 与 message。"
            )
        }
        let trimmedMessage = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedMessage.isEmpty else {
            return Self.errorJSON(
                toolName: "send_message",
                code: ErrorCode.invalidArguments,
                reason: "message 不能为空。"
            )
        }
        guard let runConversationId = conversationId ?? currentConversationId() else {
            return Self.errorJSON(
                toolName: "send_message",
                code: ErrorCode.missingContext,
                reason: "当前会话不可用，无法确定发送方线程。"
            )
        }
        let resolved = await resolveTarget(targetRaw, runConversationId: runConversationId)
        guard case .success(let target) = resolved else {
            if case .failure(let failure) = resolved {
                return Self.errorJSON(toolName: "send_message", code: failure.code, reason: failure.reason)
            }
            return Self.errorJSON(toolName: "send_message", code: ErrorCode.unknownTarget, reason: "找不到线程 \(targetRaw)。")
        }
        let senderAgentPath = await agentPath(of: runConversationId.toHexDashString(), threadEdgeDao: threadEdgeDaoProvider())
        let envelope = MailboxEnvelopeEntity(
            id: "msg-\(UUID().uuidString)",
            authorThreadId: senderAgentPath,
            recipientThreadId: target.hex,
            type: MailboxEnvelopeType.message.name,
            payload: trimmedMessage,
            triggerTurn: false,
            parentTurnId: runId,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000),
            deliveredAt: nil
        )
        do {
            try await Self.enqueue(mailboxDao: mailboxDaoProvider(), envelope: envelope)
        } catch {
            return Self.errorJSON(
                toolName: "send_message",
                code: ErrorCode.startFailed,
                reason: "消息入队失败，请重试。"
            )
        }
        // 信号在入队成功之后：wait_agent「先订阅、后查 pending」的防丢窗口由此闭合。
        await activityCenter.signal(conversationIdHex: target.hex)
        return IOSWorkspaceStore.json([
            "ok": true,
            "tool": "send_message",
            "target": targetRaw,
            "recipient_thread_id": target.hex,
            "status": "queued",
        ])
    }

    /// P1-d: 投递 + 唤醒。目标无活跃 run（前台/后台两路径判定，与 interrupt 同）
    /// → spawn 同款 bootstrap（信封渲染直写目标会话 + 标 delivered + durable
    /// 后台 run 启动）；有活跃 run → 留 pending 等目标边界 drain。
    private func followupTask(
        arguments: String,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        conversationId: KotlinUuid?,
        toolExposureBridge: IosToolExposureBridge?
    ) async -> String {
        guard let args = ChatToolCallParsing.jsonObject(arguments),
              let targetRaw = args["target"] as? String,
              let message = args["message"] as? String else {
            return Self.errorJSON(
                toolName: "followup_task",
                code: ErrorCode.invalidArguments,
                reason: "followup_task 需要 target 与 message。"
            )
        }
        let trimmedMessage = message.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedMessage.isEmpty else {
            return Self.errorJSON(
                toolName: "followup_task",
                code: ErrorCode.invalidArguments,
                reason: "message 不能为空。"
            )
        }
        guard let runConversationId = conversationId ?? currentConversationId() else {
            return Self.errorJSON(
                toolName: "followup_task",
                code: ErrorCode.missingContext,
                reason: "当前会话不可用，无法确定发送方线程。"
            )
        }
        let resolved = await resolveTarget(targetRaw, runConversationId: runConversationId)
        guard case .success(let target) = resolved else {
            if case .failure(let failure) = resolved {
                return Self.errorJSON(toolName: "followup_task", code: failure.code, reason: failure.reason)
            }
            return Self.errorJSON(toolName: "followup_task", code: ErrorCode.unknownTarget, reason: "找不到线程 \(targetRaw)。")
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let senderAgentPath = await agentPath(of: runConversationId.toHexDashString(), threadEdgeDao: threadEdgeDaoProvider())

        // 活跃 run 判定（前台当前会话 run 优先，其次后台 job；两路径与 interrupt 同）。
        let activeRunId = foregroundActiveRunId(target.hex)
            ?? backgroundCoordinator.activeRunId(conversationHex: target.hex)
        if activeRunId != nil {
            // 运行中：仅入队（triggerTurn=true），目标在其工具循环边界 drain 折入。
            let envelope = MailboxEnvelopeEntity(
                id: "task-\(UUID().uuidString)",
                authorThreadId: senderAgentPath,
                recipientThreadId: target.hex,
                type: MailboxEnvelopeType.theNewTask.name,
                payload: trimmedMessage,
                triggerTurn: true,
                parentTurnId: runId,
                createdAt: now,
                deliveredAt: nil
            )
            do {
                try await Self.enqueue(mailboxDao: mailboxDaoProvider(), envelope: envelope)
            } catch {
                return Self.errorJSON(
                    toolName: "followup_task",
                    code: ErrorCode.startFailed,
                    reason: "任务入队失败，请重试。"
                )
            }
            await activityCenter.signal(conversationIdHex: target.hex)
            return IOSWorkspaceStore.json([
                "ok": true,
                "tool": "followup_task",
                "target": targetRaw,
                "recipient_thread_id": target.hex,
                "status": "queued",
            ])
        }

        // idle：bootstrap（信封渲染直写目标会话 + 标 delivered + durable 后台 run）。
        guard let store = conversationStoreProvider() else {
            return Self.errorJSON(
                toolName: "followup_task",
                code: ErrorCode.missingContext,
                reason: "会话存储不可用，无法派发任务。"
            )
        }
        let targetId: KotlinUuid
        do {
            targetId = try KotlinUuid.companion.parse(uuidString: target.hex)
        } catch {
            return Self.errorJSON(
                toolName: "followup_task",
                code: ErrorCode.unknownTarget,
                reason: "目标线程 id 无法解析：\(target.hex)"
            )
        }
        let currentMessages: [UIMessage]
        if store.currentConversation?.id == targetId {
            currentMessages = store.currentConversation?.currentMessages ?? []
        } else {
            guard let conversation = try? await store.loadConversationForOrchestration(targetId) else {
                return Self.errorJSON(
                    toolName: "followup_task",
                    code: ErrorCode.missingContext,
                    reason: "无法读取目标线程会话，无法派发任务。"
                )
            }
            currentMessages = conversation.currentMessages
        }
        let renderedTask = MailboxEnvelopeKt.renderMailboxEnvelopeToUserText(
            authorThreadId: senderAgentPath,
            type: MailboxEnvelopeType.theNewTask.name,
            payload: trimmedMessage
        )
        let updatedMessages = currentMessages + [UIMessage.companion.user(prompt: renderedTask)]
        // 信封渲染消息直写目标会话（与 spawn 的 bootstrap 同语义：先持久化，
        // 后 durable 启动；进程死亡也不丢任务消息）。
        guard await Self.persistTargetMessages(store: store, conversationId: targetId, messages: updatedMessages) else {
            return Self.errorJSON(
                toolName: "followup_task",
                code: ErrorCode.startFailed,
                reason: "目标会话写入任务失败，请重试。"
            )
        }
        let targetRunId = UUID().uuidString
        guard let _ = await startDurableBackgroundRun(
            targetConversationId: targetId,
            targetHex: target.hex,
            targetMessages: updatedMessages,
            renderedText: renderedTask,
            providerSetting: providerSetting,
            params: params,
            runId: targetRunId,
            store: store,
            toolExposureBridge: toolExposureBridge
        ) else {
            return Self.errorJSON(
                toolName: "followup_task",
                code: ErrorCode.startFailed,
                reason: "目标线程已持久化但后台任务提交失败（线程保留，可再次 followup_task）。"
            )
        }
        // 审计信封：bootstrap 已直写会话，信封仅留审计记录（标 delivered，不参与 drain）。
        try? await Self.enqueueIfAbsent(
            mailboxDao: mailboxDaoProvider(),
            envelope: MailboxEnvelopeEntity(
                id: "followup-\(UUID().uuidString)",
                authorThreadId: senderAgentPath,
                recipientThreadId: target.hex,
                type: MailboxEnvelopeType.theNewTask.name,
                payload: trimmedMessage,
                triggerTurn: true,
                parentTurnId: runId,
                createdAt: now,
                deliveredAt: KotlinLong(value: now)
            )
        )
        await activityCenter.signal(conversationIdHex: target.hex)
        return IOSWorkspaceStore.json([
            "ok": true,
            "tool": "followup_task",
            "target": targetRaw,
            "recipient_thread_id": target.hex,
            "status": "started",
        ])
    }

    /// P1-d: 等本线程 mailbox 任何活动。先订阅（防丢信号）再查 pending；
    /// pending 非空立即返回；否则事件（含 steer 打断）与超时竞速。
    private func waitAgent(arguments: String, conversationId: KotlinUuid?) async -> String {
        guard let runConversationId = conversationId ?? currentConversationId() else {
            return Self.errorJSON(
                toolName: "wait_agent",
                code: ErrorCode.missingContext,
                reason: "当前会话不可用，无法等待 mailbox。"
            )
        }
        let hex = runConversationId.toHexDashString()
        let args = ChatToolCallParsing.jsonObject(arguments)
        let requestedMs = (args?["timeout_ms"] as? NSNumber)?.int64Value

        // 订阅在前：订阅后的信号由缓冲流兜住，订阅前的信号必已落 Room 由
        // 下方 pending 检查兜住——两个窗口都不丢（选型理由见文件头注释）。
        let stream = await activityCenter.events(for: hex)
        let pendingCount = await Self.pendingCount(mailboxDao: mailboxDaoProvider(), recipientId: hex)
        if pendingCount > 0 {
            return IOSWorkspaceStore.json([
                "ok": true,
                "tool": "wait_agent",
                "message": "mailbox already has \(pendingCount) pending",
                "timed_out": false,
                "pending_count": pendingCount,
            ])
        }
        var timeoutMs = requestedMs ?? waitTimeoutDefaultMs
        var clamped = false
        if timeoutMs < waitTimeoutMinMs {
            timeoutMs = waitTimeoutMinMs
            clamped = true
        }
        if timeoutMs > waitTimeoutMaxMs {
            timeoutMs = waitTimeoutMaxMs
            clamped = true
        }

        let outcome = await Self.awaitMailboxEvent(stream: stream, timeoutMs: timeoutMs)
        switch outcome {
        case .activity:
            // 事件后复查 mailbox：有信封 → 活动；无 → steer 打断（steer 不产生信封）。
            let pendingAfterEvent = await Self.pendingCount(mailboxDao: mailboxDaoProvider(), recipientId: hex)
            if pendingAfterEvent > 0 {
                return IOSWorkspaceStore.json([
                    "ok": true,
                    "tool": "wait_agent",
                    "message": "Mailbox activity detected.",
                    "timed_out": false,
                    "pending_count": pendingAfterEvent,
                ])
            }
            return IOSWorkspaceStore.json([
                "ok": true,
                "tool": "wait_agent",
                "message": "Wait interrupted by new input.",
                "timed_out": false,
            ])
        case .timeout:
            var payload: [String: Any] = [
                "ok": true,
                "tool": "wait_agent",
                "message": "Timed out waiting for mailbox activity after \(timeoutMs) ms.",
                "timed_out": true,
                "timeout_ms": timeoutMs,
            ]
            if clamped {
                // clamp 时在返回里说明（下限/上限按注入常量，测试可改小）。
                payload["clamped"] = true
                payload["clamp_range_ms"] = [waitTimeoutMinMs, waitTimeoutMaxMs]
            }
            return IOSWorkspaceStore.json(payload)
        case .cancelled:
            // Task 取消不吞：走既有工具取消语义——立即返回、不等到超时；
            // 引擎在下一边界把 run 收口为 wasCancelled，输出不冒充成功。
            return IOSWorkspaceStore.json([
                "ok": true,
                "tool": "wait_agent",
                "status": "cancelled",
                "message": "Wait cancelled.",
                "timed_out": false,
            ])
        }
    }

    /// P1-d 目标解析共用：target 支持 childThreadId（hex）或 agentPath（含 "/"）。
    /// 同树校验：从当前 run 会话沿 edge 走到 root，target == root 或 root 的
    /// descendant 才合法；拒绝自身。未知/树外/自身分别给结构化错误。
    private func resolveTarget(
        _ targetRaw: String,
        runConversationId: KotlinUuid
    ) async -> Result<ResolvedTarget, TargetError> {
        let runHex = runConversationId.toHexDashString()
        let target = targetRaw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else {
            return .failure(TargetError(code: ErrorCode.invalidArguments, reason: "target 不能为空。"))
        }
        let dao = threadEdgeDaoProvider()
        let all = await Self.allEdges(threadEdgeDao: dao)
        let targetEdge: ThreadEdgeSnapshot?
        if target.contains("/") {
            targetEdge = all.first { $0.agentPath == target }
        } else {
            targetEdge = await Self.edgeFor(childThreadId: target, threadEdgeDao: dao)
        }
        let rootHex = await Self.rootHex(of: runHex, threadEdgeDao: dao)
        let targetHex: String
        if let targetEdge {
            targetHex = targetEdge.childThreadId
        } else if target == "/root" || target == rootHex {
            // 根线程本身（无 edge 记录）。
            targetHex = rootHex
        } else {
            return .failure(TargetError(
                code: ErrorCode.unknownTarget,
                reason: "找不到线程 \(target)。请用 list_agents 查看可寻址线程。"
            ))
        }
        let descendants = await Self.descendants(of: rootHex, threadEdgeDao: dao)
        guard targetHex == rootHex || descendants.contains(where: { $0.childThreadId == targetHex }) else {
            return .failure(TargetError(
                code: ErrorCode.notADescendant,
                reason: "线程 \(target) 不是当前线程所在树的后代，不能向其发送消息。"
            ))
        }
        guard targetHex != runHex else {
            return .failure(TargetError(
                code: ErrorCode.cannotMessageSelf,
                reason: "不能向自己发送消息。"
            ))
        }
        return .success(ResolvedTarget(hex: targetHex, edge: targetEdge))
    }

    /// P1-d 抽取（自 spawn (f) 段）：写基线 + 保存目标会话消息（spawn/followup 共用）。
    private static func persistTargetMessages(
        store: IOSConversationStore,
        conversationId: KotlinUuid,
        messages: [UIMessage]
    ) async -> Bool {
        let baseline = store.writeBaseline(for: conversationId)
        return await store.save(messages: messages, to: conversationId, ifUnchangedSince: baseline)
    }

    /// P1-d 抽取（自 spawn (g) 段）：把「记账（agent_run running）+ durable
    /// handoff → BGTask」收口为一个助手，spawn_agent 与 followup_task 共用。
    /// start 失败时回收 running 行（避免孤儿行计入并发限额，保留 failed 审计
    /// 事实），返回 nil；成功返回 handoff。
    private func startDurableBackgroundRun(
        targetConversationId: KotlinUuid,
        targetHex: String,
        targetMessages: [UIMessage],
        renderedText: String,
        providerSetting: ProviderSetting,
        params: TextGenerationParams,
        runId: String,
        store: IOSConversationStore,
        toolExposureBridge: IosToolExposureBridge?
    ) async -> IOSChatBackgroundHandoff? {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let inputDigest = chatInputDigest(for: renderedText)
        guard await Self.recordRunningRun(
            agentRuntimeDao: agentRuntimeDaoProvider(),
            runId: runId,
            conversationId: targetHex,
            startedAt: now,
            inputDigest: inputDigest
        ) else {
            return nil
        }
        // 管线闭环场景 C：子线程的后台 upload 不经 ChatViewModel 注入管线，
        // 直接在 handoff 里注入子线程向编排语境（仅 upload，不进展示/持久化）。
        let uploadMessages = Self.childUploadMessages(
            targetMessages: targetMessages,
            soulMarkdown: soulMarkdown()
        )
        // M3: fullToolNames 取 run 的桥全目录（对齐 ChatGenerationCoordinator
        // handoff 的做法）——params.tools 只是当轮可见子集，子线程目录会被
        // 永久截断（未暴露的 wm_* 等永远不可 search/命中）。桥不可用回退现行为。
        let fullToolNames = toolExposureBridge?.fullToolDeclarations().map(\.name)
            ?? params.tools.map { $0.name }
        // 子线程是干活线程，不能继承聊天取向的输出上限：聊天下每条回复的
        // maxTokens 通常只有几千 token（或为 nil 吃服务商默认的小上限），长报告
        // 必被截断成"达到输出上限"终态（真机观察到的子代理失败）。地板 32k，
        // 父显式设了更大值则保留。
        let childParams = params.withMaxTokenFloor(Self.childRunMinOutputTokens)
        let handoff = IOSChatBackgroundHandoff(
            runId: runId,
            startedAt: now,
            inputDigest: inputDigest,
            conversationId: targetConversationId,
            providerId: providerSetting.id.toHexDashString(),
            providerSetting: providerSetting,
            // 同工具面：继承当前 run 的 params（含已暴露的编排工具，可 spawn 孙线程），
            // 仅 maxTokens 按上注做了地板提升。
            params: childParams,
            uploadMessages: uploadMessages,
            displayMessages: targetMessages,
            mode: .continueModel,
            generativeUiRequirement: .none,
            generativeUiFallbackAttempted: false,
            fullToolNames: fullToolNames
        )
        let didStart = backgroundCoordinator.start(
            handoff: handoff,
            conversationStore: store,
            toolRuntime: makeBackgroundToolRuntime(),
            liveActivityController: .shared,
            saveMiniAppIfPresent: nil
        )
        guard didStart else {
            // P1-c 修复语义：回收 running 记账行——避免 start 失败留下 running
            // 孤儿行污染恢复账本，也不把失败 run 静默抹掉。
            await Self.markRunFailed(
                agentRuntimeDao: agentRuntimeDaoProvider(),
                runId: runId,
                now: Int64(Date().timeIntervalSince1970 * 1000)
            )
            return nil
        }
        return handoff
    }

    private static func pendingCount(mailboxDao: MailboxDao, recipientId: String) async -> Int {
        await withCheckedContinuation { cont in
            mailboxDao.pendingForRecipient(recipientId: recipientId) { result, _ in
                cont.resume(returning: result?.count ?? 0)
            }
        }
    }

    /// P1-d wait_agent 竞速：mailbox 活动事件 vs 超时 vs Task 取消。
    /// 取消传播：父任务取消会取消 group 子任务；三个子任务全部以
    /// CancellationError 收口 → 确定性返回 `.cancelled`（不吞、不等超时）。
    private static func awaitMailboxEvent(
        stream: AsyncStream<Void>,
        timeoutMs: Int64
    ) async -> MailboxWaitOutcome {
        do {
            return try await withThrowingTaskGroup(of: MailboxWaitOutcome.self) { group in
                group.addTask {
                    for await _ in stream {
                        return .activity
                    }
                    // 流结束（AsyncStream 在任务取消时结束迭代）→ 视为取消。
                    throw CancellationError()
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: UInt64(timeoutMs) * 1_000_000)
                    return .timeout
                }
                group.addTask {
                    // 取消观察：父取消传播到子任务后立即收口，不等超时。
                    while !Task.isCancelled {
                        try await Task.sleep(nanoseconds: 50_000_000)
                    }
                    throw CancellationError()
                }
                let first = try await group.next() ?? .timeout
                group.cancelAll()
                return first
            }
        } catch is CancellationError {
            return .cancelled
        } catch {
            return .timeout
        }
    }

    // MARK: - FINAL_ANSWER 终态回传（前台 finishStreaming / 后台 job 完成共用）

    /// run 终态钩子：本会话有 Open 父 edge 时，向父线程 mailbox 投递 FINAL_ANSWER
    /// （payload = 最后 assistant 文本截断 2000 字符；triggerTurn=false；
    /// parentTurnId = 本 runId）。父线程在其下一边界/下一 run 头经 P1-b 机制折入。
    func notifyRunTerminal(
        conversationId: KotlinUuid?,
        runId: String,
        finalMessages: [UIMessage]
    ) async {
        guard let conversationId else { return }
        let childHex = conversationId.toHexDashString()
        guard let edge = await Self.edgeFor(childThreadId: childHex, threadEdgeDao: threadEdgeDaoProvider()),
              edge.status == EdgeStatus.open else {
            return
        }
        let text = Self.lastAssistantText(finalMessages)
        // P1-c 修复：空转录（如整轮失败、无 assistant 输出）也回传终态——改投
        // 结构化信封携带终态语义，父线程不静默丢失子线程的终止信号；截断与
        // runId 幂等去重与文本路径一致。
        let payloadSource = text.isEmpty
            ? "[run ended without assistant output]"
            : text
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let envelope = MailboxEnvelopeEntity(
            id: "final-\(runId)",
            authorThreadId: edge.agentPath,
            recipientThreadId: edge.parentThreadId,
            type: MailboxEnvelopeType.finalAnswer.name,
            payload: String(payloadSource.prefix(Self.finalAnswerMaxChars)),
            triggerTurn: false,
            parentTurnId: runId,
            createdAt: now,
            deliveredAt: nil
        )
        // 幂等去重：同 runId 的终态信封已存在（cancel/finishStreaming 双触发）
        // 时 enqueueIfAbsent 静默跳过（IGNORE），不抛冲突异常、不双发。
        try? await Self.enqueueIfAbsent(mailboxDao: mailboxDaoProvider(), envelope: envelope)
        // P1-d: FINAL_ANSWER 落父 mailbox → 广播活动（父线程的 wait_agent 由此醒来）。
        await activityCenter.signal(conversationIdHex: edge.parentThreadId)
    }

    // MARK: - 纯辅助

    static func isValidTaskName(_ name: String) -> Bool {
        guard !name.isEmpty else { return false }
        return name.allSatisfy { character in
            (character >= "a" && character <= "z")
                || (character >= "0" && character <= "9")
                || character == "_"
        }
    }

    static func lastAssistantText(_ messages: [UIMessage]) -> String {
        guard let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) else {
            return ""
        }
        return lastAssistant.parts
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func errorJSON(toolName: String, code: String, reason: String) -> String {
        IOSWorkspaceStore.json([
            "ok": false,
            "tool": toolName,
            "status": "failed",
            "error": code,
            "reason": reason,
        ])
    }

    private static func interruptResultJSON(
        target: String,
        childHex: String,
        threadStatus: String,
        previousStatus: String
    ) -> String {
        IOSWorkspaceStore.json([
            "ok": true,
            "tool": "interrupt_agent",
            "target": target,
            "child_thread_id": childHex,
            "previous_status": previousStatus,
            "thread_status": threadStatus,
        ])
    }

    /// 同父下 task_name 冲突自动追加 `_2`、`_3`…（按父路径 + 已占用路径判定）。
    private static func uniqueTaskName(
        _ taskName: String,
        parentAgentPath: String,
        threadEdgeDao: ThreadEdgeDao
    ) async -> String {
        let children = await Self.children(of: parentAgentPath, threadEdgeDao: threadEdgeDao)
        let existingPaths = Set(children.map(\.agentPath))
        var candidate = taskName
        var suffix = 2
        while existingPaths.contains("\(parentAgentPath)/\(candidate)") {
            candidate = "\(taskName)_\(suffix)"
            suffix += 1
        }
        return candidate
    }

    /// 沿 edge 链向上数父深度：root 深度 0，其子深度 1，孙深度 2。
    private func depth(of threadHex: String, threadEdgeDao: ThreadEdgeDao) async -> Int {
        var current = threadHex
        var depth = 0
        while let edge = await Self.edgeFor(childThreadId: current, threadEdgeDao: threadEdgeDao) {
            depth += 1
            current = edge.parentThreadId
        }
        return depth
    }

    /// 沿 edge 链向上走到根：root 会话的 hex（无 edge 的顶层会话就是 root）。
    private static func rootHex(of threadHex: String, threadEdgeDao: ThreadEdgeDao) async -> String {
        var current = threadHex
        while let edge = await Self.edgeFor(childThreadId: current, threadEdgeDao: threadEdgeDao) {
            current = edge.parentThreadId
        }
        return current
    }

    private func agentPath(of threadHex: String, threadEdgeDao: ThreadEdgeDao) async -> String {
        if let edge = await Self.edgeFor(childThreadId: threadHex, threadEdgeDao: threadEdgeDao) {
            return edge.agentPath
        }
        return "/root"
    }

    // MARK: - DAO 桥（suspend → withCheckedContinuation，回调内归约成 Sendable 值）

    private static func enqueue(mailboxDao: MailboxDao, envelope: MailboxEnvelopeEntity) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            mailboxDao.enqueue(envelope: envelope) { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
    }

    /// 幂等入队（终态信封去重用）：IGNORE 冲突策略，同 id 重复入队静默跳过。
    private static func enqueueIfAbsent(mailboxDao: MailboxDao, envelope: MailboxEnvelopeEntity) async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            mailboxDao.enqueueIfAbsent(envelope: envelope) { error in
                if let error { cont.resume(throwing: error) }
                else { cont.resume() }
            }
        }
    }

    private static func insertEdge(threadEdgeDao: ThreadEdgeDao, edge: ThreadEdgeEntity) async {
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            threadEdgeDao.insertEdge(edge: edge) { _ in cont.resume() }
        }
    }

    private static func edgeFor(childThreadId: String, threadEdgeDao: ThreadEdgeDao) async -> ThreadEdgeSnapshot? {
        await withCheckedContinuation { cont in
            threadEdgeDao.edgeFor(childThreadId: childThreadId) { result, _ in
                cont.resume(returning: result.map(ThreadEdgeSnapshot.init))
            }
        }
    }

    /// 取某个 agentPath 下的直接子边（uniqueTaskName 冲突判定用；按 parent hex 存）。
    private static func children(of parentAgentPath: String, threadEdgeDao: ThreadEdgeDao) async -> [ThreadEdgeSnapshot] {
        let all = await allEdges(threadEdgeDao: threadEdgeDao)
        let parentPath = parentAgentPath
        return all.filter { edge in
            // 直接子级：路径 = parentPath/单段
            edge.agentPath.hasPrefix(parentPath + "/") &&
                edge.agentPath.dropFirst(parentPath.count + 1).split(separator: "/").count == 1
        }
    }

    private static func allEdges(threadEdgeDao: ThreadEdgeDao) async -> [ThreadEdgeSnapshot] {
        await withCheckedContinuation { cont in
            threadEdgeDao.allEdges() { result, _ in
                cont.resume(returning: (result ?? []).map(ThreadEdgeSnapshot.init))
            }
        }
    }

    private static func descendants(of rootThreadId: String, threadEdgeDao: ThreadEdgeDao) async -> [ThreadEdgeSnapshot] {
        await withCheckedContinuation { cont in
            threadEdgeDao.descendantsOf(rootThreadId: rootThreadId) { result, _ in
                cont.resume(returning: (result ?? []).map(ThreadEdgeSnapshot.init))
            }
        }
    }

    private static func countUnfinishedRuns(agentRuntimeDao: AgentRuntimeDao) async -> Int {
        let store = IOSDurableRunStore(dao: agentRuntimeDao)
        return (try? await store.recoverableRuns().count) ?? 0
    }

    private static func latestRunStatusByConversation(agentRuntimeDao: AgentRuntimeDao) async -> [String: String] {
        await withCheckedContinuation { cont in
            agentRuntimeDao.listAllRuns() { result, _ in
                let runs = result ?? []
                var latestByConversation: [String: AgentRunEntity] = [:]
                for run in runs {
                    guard IOSDurableRunStore.Descriptor.chatRecoveryAliases.contains(run.agentDescriptorId),
                          let conversationId = run.conversationId else { continue }
                    if let existing = latestByConversation[conversationId], existing.startedAt > run.startedAt {
                        continue
                    }
                    latestByConversation[conversationId] = run
                }
                cont.resume(returning: latestByConversation.mapValues { $0.status })
            }
        }
    }

    private static func recordRunningRun(
        agentRuntimeDao: AgentRuntimeDao,
        runId: String,
        conversationId: String,
        startedAt: Int64,
        inputDigest: String
    ) async -> Bool {
        let store = IOSDurableRunStore(dao: agentRuntimeDao)
        return (try? await store.startChatRun(
            runId: runId,
            startedAt: startedAt,
            inputDigest: inputDigest,
            conversationId: conversationId
        )) == true
    }

    /// 以 CAS 把活跃 run 收口为 failed（spawn start 失败回收）。
    private static func markRunFailed(
        agentRuntimeDao: AgentRuntimeDao,
        runId: String,
        now: Int64
    ) async {
        let store = IOSDurableRunStore(dao: agentRuntimeDao)
        _ = try? await store.transition(
            runId: runId,
            expected: .running,
            to: .failed,
            at: now
        )
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}


extension TextGenerationParams {
    /// 输出上限地板：低于 floor（或 nil 吃服务商默认小上限）时提升到 floor。
    func withMaxTokenFloor(_ floor: Int32) -> TextGenerationParams {
        let current = maxTokens?.int32Value ?? 0
        guard current < floor else { return self }
        return TextGenerationParams(
            model: model,
            temperature: temperature,
            topP: topP,
            maxTokens: KotlinInt(value: floor),
            tools: tools,
            reasoningLevel: reasoningLevel,
            customHeaders: customHeaders,
            customBody: customBody
        )
    }
}

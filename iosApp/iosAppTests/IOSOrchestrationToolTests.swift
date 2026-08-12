import XCTest
@preconcurrency import Shared
@testable import iosApp

/// P1-c: 线程编排工具契约（spawn_agent / list_agents / interrupt_agent +
/// FINAL_ANSWER 终态回传）。基建参考 IOSMailboxDeliveryTests / IOSAgentToolEngineTests：
/// 隔离临时目录的会话存储 + 隔离 Room 库（IosDatabaseFactory atFilePath）+ fake
/// 后台调度器（捕获 handoff，不提交真实 BGTask）。
@MainActor
final class IOSOrchestrationToolTests: XCTestCase {

    // MARK: - Fakes

    /// 捕获 handoff 的后台调度 fake（生产 = IOSChatBackgroundGenerationCoordinator.shared）。
    private final class FakeBackgroundScheduler: IOSThreadOrchestrationToolService.BackgroundScheduling {
        var startedHandoff: IOSChatBackgroundHandoff?
        var startedReturn = true
        var activeRunByHex: [String: String] = [:]
        var cancelledRunIds: [String] = []
        var cancelReturn = true
        /// P1-e: 后台活跃 job 计数（生产 = activeJobs.count；测试手动驱动）。
        var activeJobCount = 0

        func start(
            handoff: IOSChatBackgroundHandoff,
            conversationStore: IOSConversationStore,
            toolRuntime: ChatToolRuntime,
            liveActivityController: AgentLiveActivityController,
            saveMiniAppIfPresent: (@MainActor ([UIMessage], KotlinUuid?) -> ChatMiniAppOutputApplication?)?
        ) -> Bool {
            startedHandoff = handoff
            return startedReturn
        }

        func activeRunId(conversationHex: String) -> String? {
            activeRunByHex[conversationHex]
        }

        @discardableResult
        func cancelJob(runId: String) -> Bool {
            cancelledRunIds.append(runId)
            return cancelReturn
        }
    }

    // MARK: - Fixtures

    private func isolatedDefaults() -> UserDefaults {
        let suite = "IOSOrchestrationToolTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    private func makeTempDirectory(_ name: String) -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("\(name)-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func makeStore(directory: URL) -> IOSConversationStore {
        IOSConversationStore(baseDirectory: directory)
    }

    private func makeDatabase(directory: URL) -> AgentRuntimeDatabase {
        IosDatabaseFactory.shared.createDatabase(
            atFilePath: directory.appendingPathComponent("orchestration.db").path
        )
    }

    private func makeProviderSetting() -> ProviderSetting.OpenAI {
        ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "orch-test",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "sk-test",
            baseUrl: "https://example.test",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
    }

    private func makeParams(maxTokens: Int32? = nil) -> TextGenerationParams {
        let model = Model(
            modelId: "test-model",
            displayName: "test-model",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
        return TextGenerationParams(
            model: model,
            temperature: KotlinFloat(value: 0.7),
            topP: nil,
            maxTokens: maxTokens.map { KotlinInt(value: $0) },
            tools: [],
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
    }

    /// 全量 iOS 声明目录（>40 → lazy 模式；M3 测试需要含 deferred wm_* 的目录）。
    private func fullIosDeclarations() -> [Tool] {
        let names =
            IOSWorkspaceToolCatalog.supportedToolNames
            .union(IOSIshToolCatalog.supportedToolNames)
            .union(IOSEmbeddedIshToolCatalog.supportedToolNames)
            .union(IOSWebMountToolCatalog.supportedToolNames)
            .union(IOSSkillToolCatalog.toolNames)
            .union(IOSMcpManagementToolCatalog.toolNames)
            .union([
                "search_web", "scrape_web", "memory_tool", "generate_image",
                "mcp_call", "subagent_dispatch", "model_council_run", "ask_user",
                "spawn_agent", "list_agents", "interrupt_agent",
                "send_message", "followup_task", "wait_agent",
                "tools_list",
            ])
        return ToolKt.iosToolDeclarations(names: Array(names).sorted())
    }

    private func makeRuntime() -> ChatToolRuntime {
        let defaults = isolatedDefaults()
        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        return ChatToolRuntime(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            localToolExecutor: nil,
            searchTransport: IOSURLSessionSearchHTTPTransport(),
            mcpManager: IOSMcpManager(serverProvider: { [] })
        )
    }

    private func makeService(
        store: IOSConversationStore,
        db: AgentRuntimeDatabase,
        scheduler: FakeBackgroundScheduler,
        currentConversationId: @escaping () -> KotlinUuid?,
        foregroundActiveRunId: @escaping (String) -> String? = { _ in nil },
        cancelForegroundRun: @escaping (String) -> Bool = { _ in false },
        foregroundRunActive: @escaping () -> Bool = { false },
        maxConcurrentRuns: Int = IOSThreadOrchestrationToolService.defaultMaxConcurrentRuns,
        roleAssistantExists: @escaping (KotlinUuid) -> Bool = { _ in true }
    ) -> IOSThreadOrchestrationToolService {
        IOSThreadOrchestrationToolService(
            conversationStoreProvider: { store },
            mailboxDaoProvider: { db.mailboxDao() },
            threadEdgeDaoProvider: { db.threadEdgeDao() },
            agentRuntimeDaoProvider: { db.agentRuntimeDao() },
            backgroundCoordinator: scheduler,
            makeBackgroundToolRuntime: { [weak self] in self?.makeRuntime() ?? ChatToolRuntime(
                settingsStore: SettingsStore(),
                sharedSettings: IOSSharedSettingsStore(),
                localToolExecutor: nil,
                searchTransport: IOSURLSessionSearchHTTPTransport(),
                mcpManager: IOSMcpManager(serverProvider: { [] })
            ) },
            currentConversationId: currentConversationId,
            foregroundActiveRunId: foregroundActiveRunId,
            cancelForegroundRun: cancelForegroundRun,
            foregroundRunActive: foregroundRunActive,
            maxConcurrentRuns: maxConcurrentRuns,
            roleAssistantExists: roleAssistantExists
        )
    }

    private func spawnArguments(
        taskName: String,
        message: String = "初始任务",
        forkTurns: String? = "all",
        roleAssistantId: String? = nil
    ) -> String {
        var object: [String: Any] = ["task_name": taskName, "message": message]
        if let forkTurns { object["fork_turns"] = forkTurns }
        if let roleAssistantId { object["role_assistant_id"] = roleAssistantId }
        let data = try! JSONSerialization.data(withJSONObject: object)
        return String(data: data, encoding: .utf8)!
    }

    private func parseJSON(_ text: String) -> [String: Any] {
        let data = text.data(using: .utf8)!
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }

    // MARK: - spawn 全链路

    func testSpawnForkAllWritesEdgeBootstrapsTaskAndStartsBackgroundRun() async throws {
        let base = makeTempDirectory("SpawnAll")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let parentHex = parentId.toHexDashString()
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "q1"),
            UIMessage.companion.assistant(prompt: "a1"),
        ])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store,
            db: db,
            scheduler: scheduler,
            currentConversationId: { parentId }
        )

        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "research", message: "调研房价", forkTurns: "all"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run-1"
        ))

        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertEqual(result["status"] as? String, "started")
        XCTAssertEqual(result["task_name"] as? String, "research")
        XCTAssertEqual(result["agent_path"] as? String, "/root/research")
        let childHex = try XCTUnwrap(result["child_thread_id"] as? String)

        // edge 写入（Open，forkTurns=all）。
        let edge = try await db.threadEdgeDao().edgeFor(childThreadId: childHex)
        XCTAssertEqual(edge?.parentThreadId, parentHex)
        XCTAssertEqual(edge?.agentPath, "/root/research")
        XCTAssertEqual(edge?.status, "Open")
        XCTAssertEqual(edge?.forkTurns, "all")

        // 子会话含 NEW_TASK 渲染消息（bootstrap 直接持久化）。
        let childId = try! KotlinUuid.companion.parse(uuidString: childHex)
        let childMessages = await store.messages(for: childId) ?? []
        let childUserTexts = childMessages.filter { $0.role == MessageRole.user }.map { $0.toText() }
        XCTAssertEqual(
            childUserTexts,
            ["q1", "[mailbox NEW_TASK from /root]\n调研房价"],
            "fork=all 继承全部历史 + bootstrap 初始任务",
        )

        // 后台 start 被调且 payload 正确。
        let handoff = try XCTUnwrap(scheduler.startedHandoff)
        XCTAssertEqual(handoff.conversationId.toHexDashString(), childHex)
        // 场景 C 修复后：upload 首条为子线程向编排语境（system），其后与持久化一致。
        XCTAssertEqual(handoff.uploadMessages.count, childMessages.count + 1)
        XCTAssertEqual(handoff.uploadMessages.first?.role, MessageRole.system)
        XCTAssertEqual(
            handoff.uploadMessages.filter { $0.role == MessageRole.user }.last?.toText(),
            "[mailbox NEW_TASK from /root]\n调研房价"
        )

        // 子 run 已记账，且能从 Chat descriptor 的可恢复集合查到。
        let runs = try await db.agentRuntimeDao().listRecoverable(descriptorIds: ["chat"])
        XCTAssertTrue(runs.contains { $0.conversationId == childHex && $0.status == "running" })
    }

    // MARK: - M3: spawn/followup 的 handoff fullToolNames 取桥全目录

    /// 红测试对应缺陷：startDurableBackgroundRun 用 params.tools.map(\.name)（当轮
    /// 可见子集）作 fullToolNames → 子线程目录永久截断（未暴露的 wm_* 永远不可
    /// search/命中）。修复后从 run 桥全目录取名集合（对齐 ChatGenerationCoordinator）。
    func testSpawnHandoffFullToolNamesComeFromBridgeFullCatalog() async throws {
        let base = makeTempDirectory("SpawnFullNames")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )

        // 桥全目录（heavy → lazy）。params.tools = 首轮可见子集（不含 wm_*）。
        let bridge = IosToolExposureBridge(tools: fullIosDeclarations())
        XCTAssertTrue(bridge.lazyModeEnabled())
        let visibleNames = Set(bridge.visibleTools().map(\.name))
        XCTAssertFalse(visibleNames.contains("wm_type"), "fixture 前提：wm_type 首轮未暴露")

        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "fullnames", message: "调研"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run-m3",
            conversationId: parentId,
            toolExposureBridge: bridge
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)

        let handoff = try XCTUnwrap(scheduler.startedHandoff, "spawn 必须启动子 run")
        let fullNames = Set(handoff.fullToolNames)
        XCTAssertTrue(
            fullNames.contains("wm_type"),
            "fullToolNames 必须来自桥全目录（含未暴露的 wm_*），实际缺 wm_type"
        )
        XCTAssertTrue(fullNames.contains("spawn_agent"), "目录必须含线程编排工具")
        XCTAssertTrue(fullNames.isSuperset(of: visibleNames), "全目录必须覆盖当轮可见子集")
    }

    // MARK: - M4: role_assistant_id 应用到子会话 assistantId + 存在性校验

    func testSpawnAppliesRoleAssistantIdToChildConversation() async throws {
        let base = makeTempDirectory("SpawnRole")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let roleId = KotlinUuid.companion.random()
        let roleHex = roleId.toHexDashString()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId },
            roleAssistantExists: { candidate in candidate.toHexDashString() == roleHex }
        )

        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "rolechild", message: "按角色调研", roleAssistantId: roleHex),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run-role",
            conversationId: parentId
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        let childHex = try XCTUnwrap(result["child_thread_id"] as? String)

        // 子会话 assistantId = role（fork 后应用并保存）。
        let childId = try! KotlinUuid.companion.parse(uuidString: childHex)
        let loadedChild = try? await store.loadConversationForOrchestration(childId)
        let childConversation = try XCTUnwrap(loadedChild, "子会话必须已持久化")
        XCTAssertEqual(
            childConversation.assistantId.toHexDashString(), roleHex,
            "role_assistant_id 必须应用到子会话 assistantId"
        )
        // edge 同样记录 role（list_agents 展示）。
        let edge = try await db.threadEdgeDao().edgeFor(childThreadId: childHex)
        XCTAssertEqual(edge?.roleAssistantId, roleHex)
    }

    func testSpawnRejectsUnknownRoleAssistantIdWithoutStartingRun() async throws {
        let base = makeTempDirectory("SpawnBadRole")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId },
            roleAssistantExists: { _ in false }
        )

        // 存在但校验器拒绝 → 结构化错误。
        let knownButRejected = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(
                taskName: "badrole",
                message: "x",
                roleAssistantId: "11111111-1111-1111-1111-111111111111"
            ),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run-bad",
            conversationId: parentId
        ))
        XCTAssertEqual(knownButRejected["ok"] as? Bool, false)
        XCTAssertNotNil(knownButRejected["error"], "无效 role 必须返回结构化错误")
        XCTAssertNil(scheduler.startedHandoff, "无效 role 不得创建子线程/启动 run")

        // 非 UUID 字符串 → 结构化错误（解析失败）。
        let unparsable = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "badrole2", message: "x", roleAssistantId: "not-a-uuid"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run-bad2",
            conversationId: parentId
        ))
        XCTAssertEqual(unparsable["ok"] as? Bool, false)
        XCTAssertNil(scheduler.startedHandoff)
        // 没有残留 running 行（未创建子 run）。
        let runs = try await db.agentRuntimeDao().listRecoverable(descriptorIds: ["chat"])
        XCTAssertTrue(runs.isEmpty)
    }

    func testSpawnForkTurns3KeepsLastThreeUserTurnsInChildConversation() async throws {
        let base = makeTempDirectory("SpawnThree")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "q1"),
            UIMessage.companion.assistant(prompt: "a1"),
            UIMessage.companion.user(prompt: "q2"),
            UIMessage.companion.assistant(prompt: "a2"),
            UIMessage.companion.user(prompt: "q3"),
            UIMessage.companion.assistant(prompt: "a3"),
            UIMessage.companion.user(prompt: "q4"),
            UIMessage.companion.assistant(prompt: "a4"),
        ])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )

        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "digest", message: "汇总", forkTurns: "3"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run-2"
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        let childHex = try XCTUnwrap(result["child_thread_id"] as? String)
        let childId = try! KotlinUuid.companion.parse(uuidString: childHex)

        let childUserTexts = (await store.messages(for: childId) ?? [])
            .filter { $0.role == MessageRole.user }
            .map { $0.toText() }
        XCTAssertEqual(
            childUserTexts,
            ["q2", "q3", "q4", "[mailbox NEW_TASK from /root]\n汇总"],
            "fork_turns=3 只保留最近 3 个用户轮次 + 初始任务",
        )
    }

    func testSpawnValidatesTaskNameAndSuffixesConflicts() async throws {
        let base = makeTempDirectory("SpawnValidation")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )

        // 非法 task_name。
        let invalid = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "Bad Name"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(invalid["error"] as? String, "invalid_task_name")
        XCTAssertNil(scheduler.startedHandoff, "非法 task_name 不得启动子 run")

        // 缺 message。
        let missing = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: #"{"task_name":"ok_name"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(missing["error"] as? String, "invalid_arguments")

        // 同名冲突 → _2 后缀。
        let first = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "research"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r1"
        ))
        XCTAssertEqual(first["task_name"] as? String, "research")
        let second = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "research"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r2"
        ))
        XCTAssertEqual(second["task_name"] as? String, "research_2")
        XCTAssertEqual(second["agent_path"] as? String, "/root/research_2")
    }

    func testSpawnRejectsDepthThreeChain() async throws {
        let base = makeTempDirectory("SpawnDepth")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        let db = makeDatabase(directory: base)

        // 造 depth-2 链：root → child1（depth1）→ child2（depth2）。
        await store.newConversation()
        let rootId = try XCTUnwrap(store.currentConversation?.id)
        let child1 = KotlinUuid.companion.random()
        let child2 = KotlinUuid.companion.random()
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: child1, assistantId: AssistantKt.DEFAULT_ASSISTANT_ID, messages: [], newConversation: false
        ))
        await store.saveForkedConversation(Conversation.companion.ofId(
            id: child2, assistantId: AssistantKt.DEFAULT_ASSISTANT_ID, messages: [], newConversation: false
        ))
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: child1.toHexDashString(),
            parentThreadId: rootId.toHexDashString(),
            agentPath: "/root/grand",
            nickname: nil,
            roleAssistantId: nil,
            forkTurns: "all",
            status: "Open",
            createdAt: now
        ))
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: child2.toHexDashString(),
            parentThreadId: child1.toHexDashString(),
            agentPath: "/root/grand/great",
            nickname: nil,
            roleAssistantId: nil,
            forkTurns: "all",
            status: "Open",
            createdAt: now
        ))

        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { child2 }
        )
        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "deep"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(result["error"] as? String, "agent_depth_limit")
        XCTAssertNil(scheduler.startedHandoff)
    }

    /// P1-e 核心行为变化：崩溃残留的 running 行（无活跃注册）不再占用并发槽，
    /// spawn 放行。活注册表计数 = 前台 run(0/1) + 后台 activeJobs + 在途 bootstrap，
    /// 全部与 Room 的全局可恢复行无关。
    func testSpawnAllowsWhenOnlyResidualLedgerRowsExist() async throws {
        let base = makeTempDirectory("SpawnLimit")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        // 4 个残留 running 行（模拟崩溃/恢复扫描前的窗口期），但无前台 run、
        // 无后台 job、无在途 bootstrap → 旧全局账本计数会误伤，新计数放行。
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        for index in 0..<4 {
            _ = try await db.agentRuntimeDao().insertRunIfAbsent(run: AgentRunEntity(
                runId: "run-\(index)",
                parentRunId: nil,
                agentDescriptorId: "chat",
                agentVersion: "1",
                conversationId: index == 0 ? parentId.toHexDashString() : nil,
                messageNodeId: nil,
                producesMessageId: nil,
                assistantId: nil,
                status: "running",
                inputDigest: "d",
                inputSnapshotRef: nil,
                inputSchemaVersion: 1,
                startedAt: now,
                finishedAt: nil,
                interruptedReason: nil
            ))
        }
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )
        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "busy"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertNotNil(scheduler.startedHandoff)
    }

    /// P1-e：活注册表满额（1 前台 run + 3 后台 job = 4 槽满）→ spawn 拒绝；
    /// 降为 2 个后台 job（3 槽占用）→ 放行。不再读 Room 账本行。
    func testSpawnRejectsWhenActiveRegistrationsAtCapacity() async throws {
        let base = makeTempDirectory("SpawnLimit")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        scheduler.activeJobCount = 3
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId },
            foregroundRunActive: { true }
        )
        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "busy"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(result["error"] as? String, "agent_limit_reached")
        XCTAssertNil(scheduler.startedHandoff)

        // 边界：2 个后台 job（1 前台 + 2 后台 = 3 槽占用 < 4）→ 放行。
        scheduler.activeJobCount = 2
        let retry = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "busy"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(retry["ok"] as? Bool, true)
    }

    /// P1-e：并发 spawn 共享在途 bootstrap 槽——限额 1 时两个并发 spawn 恰好
    /// 一个通过（先到者占槽），另一个收到 agent_limit_reached；完成后槽位释放。
    func testConcurrentSpawnsShareInFlightBootstrapSlot() async throws {
        let base = makeTempDirectory("SpawnLimit")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId },
            maxConcurrentRuns: 1
        )

        // 两个并发 spawn 在 MainActor 的 await 点交错：先到者完成「检查+占槽」
        // 原子段（无 await），后到者的检查看到在途槽 → 拒绝。
        // 参数先提升为局部量：async let 初始化器不能调用 self 方法（Sendable 限制）。
        let provider = makeProviderSetting()
        let params = makeParams()
        let argsA = spawnArguments(taskName: "inflight_a")
        let argsB = spawnArguments(taskName: "inflight_b")
        async let first = service.execute(
            toolName: "spawn_agent",
            arguments: argsA,
            providerSetting: provider,
            params: params,
            runId: "r1"
        )
        async let second = service.execute(
            toolName: "spawn_agent",
            arguments: argsB,
            providerSetting: provider,
            params: params,
            runId: "r2"
        )
        let outcomes = [parseJSON(await first), parseJSON(await second)]
        let succeeded = outcomes.filter { $0["ok"] as? Bool == true }.count
        let rejected = outcomes.filter { $0["error"] as? String == "agent_limit_reached" }.count
        XCTAssertEqual(succeeded, 1, "限额 1 时并发 spawn 只能有一个通过")
        XCTAssertEqual(rejected, 1, "另一个并发 spawn 必须收到 agent_limit_reached")
        XCTAssertNotNil(scheduler.startedHandoff)

        // 两个 spawn 都结束后在途槽释放 → 新 spawn 恢复。
        let third = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "after"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r3"
        ))
        XCTAssertEqual(third["ok"] as? Bool, true)
    }

    // MARK: - list_agents

    func testListAgentsProjectsEdgesAndLatestRunStatus() async throws {
        let base = makeTempDirectory("ListAgents")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let childHex = "11111111-1111-1111-1111-111111111111"
        let grandHex = "22222222-2222-2222-2222-222222222222"
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: childHex,
            parentThreadId: parentId.toHexDashString(),
            agentPath: "/root/research",
            nickname: nil,
            roleAssistantId: "assistant-1",
            forkTurns: "3",
            status: "Open",
            createdAt: now
        ))
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: grandHex,
            parentThreadId: childHex,
            agentPath: "/root/research/digest",
            nickname: nil,
            roleAssistantId: nil,
            forkTurns: "all",
            status: "Open",
            createdAt: now
        ))
        // 两个 run：旧 completed + 新 running → 取最新。
        _ = try await db.agentRuntimeDao().insertRunIfAbsent(run: AgentRunEntity(
            runId: "old", parentRunId: nil, agentDescriptorId: "chat", agentVersion: "1",
            conversationId: childHex, messageNodeId: nil, producesMessageId: nil, assistantId: nil,
            status: "completed", inputDigest: "d", inputSnapshotRef: nil, inputSchemaVersion: 1,
            startedAt: now, finishedAt: KotlinLong(value: now + 1), interruptedReason: nil
        ))
        _ = try await db.agentRuntimeDao().insertRunIfAbsent(run: AgentRunEntity(
            runId: "new", parentRunId: nil, agentDescriptorId: "chat", agentVersion: "1",
            conversationId: childHex, messageNodeId: nil, producesMessageId: nil, assistantId: nil,
            status: "running", inputDigest: "d", inputSnapshotRef: nil, inputSchemaVersion: 1,
            startedAt: now + 2, finishedAt: nil, interruptedReason: nil
        ))

        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )
        let payload = parseJSON(await service.execute(
            toolName: "list_agents",
            arguments: "{}",
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(payload["ok"] as? Bool, true)
        XCTAssertEqual(payload["count"] as? Int, 2)
        let threads = payload["threads"] as! [[String: Any]]
        let byPath = Dictionary(uniqueKeysWithValues: threads.map { ($0["agent_path"] as! String, $0) })
        XCTAssertEqual(byPath["/root/research"]?["run_status"] as? String, "running", "取最新 run 状态")
        XCTAssertEqual(byPath["/root/research"]?["role"] as? String, "assistant-1")
        XCTAssertEqual(byPath["/root/research/digest"]?["run_status"] as? String, "none")

        // path_prefix 过滤。
        let filtered = parseJSON(await service.execute(
            toolName: "list_agents",
            arguments: #"{"path_prefix":"/root/research/digest"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(filtered["count"] as? Int, 1)
    }

    // MARK: - interrupt_agent

    func testInterruptBackgroundRunCancelsAndPreservesThread() async throws {
        let base = makeTempDirectory("InterruptBg")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let childHex = "33333333-3333-3333-3333-333333333333"
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: childHex, parentThreadId: parentId.toHexDashString(),
            agentPath: "/root/worker", nickname: nil, roleAssistantId: nil,
            forkTurns: "all", status: "Open", createdAt: now
        ))
        let scheduler = FakeBackgroundScheduler()
        scheduler.activeRunByHex[childHex] = "bg-run-9"
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )

        let result = parseJSON(await service.execute(
            toolName: "interrupt_agent",
            arguments: #"{"target":"/root/worker"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertEqual(result["previous_status"] as? String, "running")
        XCTAssertEqual(scheduler.cancelledRunIds, ["bg-run-9"])

        // 线程保留：edge 仍 Open。
        let edge = try await db.threadEdgeDao().edgeFor(childThreadId: childHex)
        XCTAssertEqual(edge?.status, "Open")
    }

    func testInterruptIdleThreadReturnsIdleWithoutError() async throws {
        let base = makeTempDirectory("InterruptIdle")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let childHex = "44444444-4444-4444-4444-444444444444"
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: childHex, parentThreadId: parentId.toHexDashString(),
            agentPath: "/root/idle", nickname: nil, roleAssistantId: nil,
            forkTurns: "all", status: "Open", createdAt: now
        ))
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )
        let result = parseJSON(await service.execute(
            toolName: "interrupt_agent",
            arguments: #"{"target":"\#(childHex)"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertEqual(result["previous_status"] as? String, "idle", "无活跃 run 的 idle 不算错误")
        XCTAssertTrue(scheduler.cancelledRunIds.isEmpty)
    }

    func testInterruptNonDescendantIsRejected() async throws {
        let base = makeTempDirectory("InterruptForeign")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let foreignHex = "55555555-5555-5555-5555-555555555555"
        let otherRoot = KotlinUuid.companion.random()
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        // 属于另一个 root 的线程。
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: foreignHex, parentThreadId: otherRoot.toHexDashString(),
            agentPath: "/root/foreign", nickname: nil, roleAssistantId: nil,
            forkTurns: "all", status: "Open", createdAt: now
        ))
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )
        let result = parseJSON(await service.execute(
            toolName: "interrupt_agent",
            arguments: #"{"target":"/root/foreign"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(result["error"] as? String, "not_a_descendant")
        XCTAssertEqual(result["ok"] as? Bool, false)
        XCTAssertTrue(scheduler.cancelledRunIds.isEmpty)
    }

    func testInterruptUnknownTargetIsRejected() async throws {
        let base = makeTempDirectory("InterruptUnknown")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )
        let result = parseJSON(await service.execute(
            toolName: "interrupt_agent",
            arguments: #"{"target":"nope"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r"
        ))
        XCTAssertEqual(result["error"] as? String, "unknown_target")
    }

    // MARK: - FINAL_ANSWER 终态回传（前台 finishStreaming → 父 mailbox → 父边界折入）

    func testFinalAnswerReachesParentMailboxAndFoldsAtParentBoundary() async throws {
        let base = makeTempDirectory("FinalAnswer")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()

        // 父会话 → 子会话（current）。
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.newConversation()
        let childId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "子线程任务"),
            UIMessage.companion.assistant(prompt: "子线程最终回答"),
        ])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: childId.toHexDashString(),
            parentThreadId: parentId.toHexDashString(),
            agentPath: "/root/sub",
            nickname: nil,
            roleAssistantId: nil,
            forkTurns: "all",
            status: "Open",
            createdAt: now
        ))

        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { childId }
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            mailboxStore: IOSMailboxStore(mailboxDao: db.mailboxDao()),
            orchestrationToolService: service
        )
        viewModel.conversationStore = store
        viewModel.reloadFromStore()

        // 子线程 run 终态（前台 finishStreaming 路径）→ FINAL_ANSWER 进父 mailbox。
        viewModel.generationCoordinatorForTesting.installRunSnapshotForTesting(
            runId: "child-run-1",
            snapshot: nil,
            conversationId: childId
        )
        XCTAssertTrue(viewModel.generationCoordinatorForTesting.finishStreamingForTesting(runId: "child-run-1"))
        let finalEnvelope = try await pollFinalAnswerEnvelope(
            mailboxDao: db.mailboxDao(),
            recipient: parentId,
            runId: "child-run-1"
        )
        XCTAssertEqual(finalEnvelope?.payload, "子线程最终回答")
        XCTAssertEqual(finalEnvelope?.author, "/root/sub")

        // 父线程在其下一边界折入（复用 P1-b 机制：渲染为带结构头的 user 消息）。
        await store.selectConversation(id: parentId)
        viewModel.reloadFromStore()
        let upload = await viewModel.nextRoundMessagesAfterMailboxAndSteerConsumptionForTesting(
            baseMessages: [UIMessage.companion.assistant(prompt: "父工具结果")]
        )
        let uploadUserTexts = upload.filter { $0.role == MessageRole.user }.map { $0.toText() }
        XCTAssertEqual(uploadUserTexts, [
            "[mailbox FINAL_ANSWER from /root/sub]\n子线程最终回答",
        ])
    }

    // MARK: - P1-c 复核修复回归（后台 runtime 注入 / run 锚定 / cancel 终态 /
    //           start 失败回收 / 空转录终态）

    func testViewModelBackgroundRuntimeInjectsOrchestrationService() async throws {
        let base = makeTempDirectory("VMBgRuntime")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )

        // 经 VM 的真实闭包路径取后台 executor 表（隔离依赖让服务可用）。
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            orchestrationToolService: service
        )
        viewModel.conversationStore = store
        let runtime = viewModel.makeBackgroundToolRuntimeForTesting()
        let baseParams = makeParams()
        let params = TextGenerationParams(
            model: baseParams.model,
            temperature: baseParams.temperature,
            topP: nil,
            maxTokens: nil,
            tools: ToolKt.iosToolDeclarations(names: ["spawn_agent", "list_agents", "interrupt_agent"]),
            reasoningLevel: .off,
            customHeaders: [],
            customBody: []
        )
        let executors = runtime.backgroundToolExecutors(
            providerSetting: makeProviderSetting(),
            params: params,
            runId: "parent-run-vm",
            conversationId: parentId
        )
        let executor = try XCTUnwrap(executors["spawn_agent"], "后台 runtime 必须注册 spawn_agent")
        let outcome = await IOSToolExecutorBox(executor).execute(
            name: "spawn_agent",
            arguments: spawnArguments(taskName: "subtask", message: "后台孙线程任务"),
            isUserInitiated: false
        )
        guard case .filled(let text) = outcome else {
            return XCTFail("spawn_agent 后台执行应成功，实际: \(outcome)")
        }
        XCTAssertFalse(text.contains("线程编排工具当前不可用"), "后台 runtime 必须注入编排服务，实际: \(text)")
        let result = parseJSON(text)
        XCTAssertEqual(result["ok"] as? Bool, true)
        XCTAssertEqual(result["status"] as? String, "started")
        XCTAssertEqual(scheduler.startedHandoff?.conversationId.toHexDashString(),
                       result["child_thread_id"] as? String)
    }

    func testSpawnListInterruptAnchorToRunConversationNotCurrent() async throws {
        let base = makeTempDirectory("RunAnchor")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let runA = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "q1"),
            UIMessage.companion.assistant(prompt: "a1"),
        ])
        // 生成中切到会话 B：current 是 B，run 锚定 A。
        await store.newConversation()
        let runB = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "B 内容"),
        ])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { runB }
        )

        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "anchored", message: "跑在 A 下"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-a",
            conversationId: runA
        ))
        XCTAssertEqual(result["ok"] as? Bool, true)
        let childHex = try XCTUnwrap(result["child_thread_id"] as? String)

        // edge 挂在 run 锚定的 A 下，不是当前会话 B。
        let edge = try await db.threadEdgeDao().edgeFor(childThreadId: childHex)
        XCTAssertEqual(edge?.parentThreadId, runA.toHexDashString())
        XCTAssertNotEqual(edge?.parentThreadId, runB.toHexDashString())

        // fork 源是 A（含 q1），不是 B（不含「B 内容」）。
        let childId = try! KotlinUuid.companion.parse(uuidString: childHex)
        let childUserTexts = (await store.messages(for: childId) ?? [])
            .filter { $0.role == MessageRole.user }
            .map { $0.toText() }
        XCTAssertTrue(childUserTexts.contains("q1"), "fork 源应为 run 锚定会话 A")
        XCTAssertFalse(childUserTexts.contains("B 内容"), "不得 fork 当前会话 B")

        // list 以 A 为根可见子线程；以 B 为根不可见。
        let listA = parseJSON(await service.execute(
            toolName: "list_agents",
            arguments: "{}",
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-a",
            conversationId: runA
        ))
        XCTAssertEqual(listA["count"] as? Int, 1)
        let listB = parseJSON(await service.execute(
            toolName: "list_agents",
            arguments: "{}",
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-a",
            conversationId: runB
        ))
        XCTAssertEqual(listB["count"] as? Int, 0, "list 不得以当前会话 B 为根")

        // interrupt 后代校验以 A 为根：A 下可中断，B 下视为非后代。
        let interruptA = parseJSON(await service.execute(
            toolName: "interrupt_agent",
            arguments: #"{"target":"\#(childHex)"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-a",
            conversationId: runA
        ))
        XCTAssertEqual(interruptA["ok"] as? Bool, true)
        let interruptB = parseJSON(await service.execute(
            toolName: "interrupt_agent",
            arguments: #"{"target":"\#(childHex)"}"#,
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "run-a",
            conversationId: runB
        ))
        XCTAssertEqual(interruptB["error"] as? String, "not_a_descendant")
    }

    func testCancelDeliversFinalAnswerToParentAndDoesNotDoubleSend() async throws {
        let base = makeTempDirectory("CancelFinal")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()

        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.newConversation()
        let childId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [
            UIMessage.companion.user(prompt: "子线程任务"),
            UIMessage.companion.assistant(prompt: "部分回答"),
        ])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: childId.toHexDashString(),
            parentThreadId: parentId.toHexDashString(),
            agentPath: "/root/sub",
            nickname: nil,
            roleAssistantId: nil,
            forkTurns: "all",
            status: "Open",
            createdAt: now
        ))

        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { childId }
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            mailboxStore: IOSMailboxStore(mailboxDao: db.mailboxDao()),
            orchestrationToolService: service
        )
        viewModel.conversationStore = store
        viewModel.reloadFromStore()

        // 前台 run 取消 → 终态回传父 mailbox（带 Open 父 edge）。
        viewModel.generationCoordinatorForTesting.installRunSnapshotForTesting(
            runId: "child-run-cancel",
            snapshot: nil,
            conversationId: childId
        )
        XCTAssertTrue(viewModel.generationCoordinatorForTesting.cancel(runId: "child-run-cancel"))
        let finalEnvelope = try await pollFinalAnswerEnvelope(
            mailboxDao: db.mailboxDao(),
            recipient: parentId,
            runId: "child-run-cancel"
        )
        XCTAssertEqual(finalEnvelope?.author, "/root/sub")
        XCTAssertEqual(finalEnvelope?.payload, "部分回答")

        // cancel 后再 finishStreaming 双触发：服务按 runId 幂等去重，不双发。
        viewModel.generationCoordinatorForTesting.installRunSnapshotForTesting(
            runId: "child-run-cancel",
            snapshot: nil,
            conversationId: childId
        )
        _ = viewModel.generationCoordinatorForTesting.finishStreamingForTesting(runId: "child-run-cancel")
        try await Task.sleep(nanoseconds: 300_000_000)
        let count = try await countFinalAnswerEnvelopes(
            mailboxDao: db.mailboxDao(),
            recipient: parentId,
            runId: "child-run-cancel"
        )
        XCTAssertEqual(count, 1, "cancel 与 finishStreaming 双触发不得双发 FINAL_ANSWER")
    }

    func testSpawnStartFailureReclaimsRunningRow() async throws {
        let base = makeTempDirectory("SpawnStartFail")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "q1")])
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        scheduler.startedReturn = false
        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { parentId }
        )

        let result = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "doomed"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "parent-run-fail"
        ))
        XCTAssertEqual(result["error"] as? String, "start_failed")

        // start 失败后：不留下 running 孤儿行（并发限额计数回落），
        // 该 run 行更新为 failed（保留审计事实）。
        let unfinished = try await db.agentRuntimeDao().listRecoverable(descriptorIds: ["chat"])
        XCTAssertTrue(unfinished.isEmpty, "start 失败后不得有 running 孤儿行，实际: \(unfinished.count)")
        let allRuns = try await db.agentRuntimeDao().listAllRuns()
        XCTAssertTrue(
            allRuns.contains { $0.status == "failed" },
            "start 失败的 run 行应更新为 failed"
        )
    }

    func testEmptyTranscriptTerminalDeliversStructuredEnvelope() async throws {
        let base = makeTempDirectory("EmptyTranscript")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()

        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        await store.newConversation()
        let childId = try XCTUnwrap(store.currentConversation?.id)
        // 空转录：只有 user 消息，没有任何 assistant 文本。
        await store.saveCurrent(messages: [UIMessage.companion.user(prompt: "子线程任务")])
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try await db.threadEdgeDao().insertEdge(edge: ThreadEdgeEntity(
            childThreadId: childId.toHexDashString(),
            parentThreadId: parentId.toHexDashString(),
            agentPath: "/root/sub",
            nickname: nil,
            roleAssistantId: nil,
            forkTurns: "all",
            status: "Open",
            createdAt: now
        ))

        let service = makeService(
            store: store, db: db, scheduler: scheduler, currentConversationId: { childId }
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            mailboxStore: IOSMailboxStore(mailboxDao: db.mailboxDao()),
            orchestrationToolService: service
        )
        viewModel.conversationStore = store
        viewModel.reloadFromStore()

        // 空转录 failed 子 run → 父 Room 仍收到结构化终态信封。
        viewModel.generationCoordinatorForTesting.installRunSnapshotForTesting(
            runId: "child-run-empty",
            snapshot: nil,
            conversationId: childId
        )
        XCTAssertTrue(viewModel.generationCoordinatorForTesting.finishStreamingForTesting(runId: "child-run-empty"))
        let finalEnvelope = try await pollFinalAnswerEnvelope(
            mailboxDao: db.mailboxDao(),
            recipient: parentId,
            runId: "child-run-empty"
        )
        XCTAssertEqual(finalEnvelope?.payload, "[run ended without assistant output]")
        XCTAssertEqual(finalEnvelope?.author, "/root/sub")
    }

    /// Sendable 归约：回调内只传匹配信封的 payload/author（Kotlin 实体非 Sendable）。
    private func pollFinalAnswerEnvelope(
        mailboxDao: MailboxDao,
        recipient: KotlinUuid,
        runId: String,
        timeout: TimeInterval = 5
    ) async throws -> (payload: String, author: String)? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let match: (payload: String, author: String)? = await withCheckedContinuation { cont in
                mailboxDao.pendingForRecipient(recipientId: recipient.toHexDashString()) { result, _ in
                    let pending = result ?? []
                    let hit = pending.first { $0.parentTurnId == runId && $0.type == "FINAL_ANSWER" }
                    cont.resume(returning: hit.map { (payload: $0.payload, author: $0.authorThreadId) })
                }
            }
            if let match { return match }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return nil
    }

    /// 计数指定 runId 的 FINAL_ANSWER 未投递信封（终态去重断言用；
    /// 与 pollFinalAnswerEnvelope 同款 Sendable 归约）。
    private func countFinalAnswerEnvelopes(
        mailboxDao: MailboxDao,
        recipient: KotlinUuid,
        runId: String,
        timeout: TimeInterval = 5
    ) async throws -> Int {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            let count: Int = await withCheckedContinuation { cont in
                mailboxDao.pendingForRecipient(recipientId: recipient.toHexDashString()) { result, _ in
                    let pending = result ?? []
                    cont.resume(returning: pending.filter {
                        $0.parentTurnId == runId && $0.type == "FINAL_ANSWER"
                    }.count)
                }
            }
            if count > 0 { return count }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        return 0
    }
    // MARK: - P1-e 控制面纪律（子线程会话只读）

    /// 子线程会话：composer 被 `.orchestratedThread` 拦截（含文案）；编辑/重生成/
    /// 删除/变体选择全部 no-op；普通会话不受影响。edge 缓存随会话切换刷新。
    func testChildConversationIsReadOnlyInViewModel() async throws {
        let base = makeTempDirectory("ChildReadOnly")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler,
            currentConversationId: { store.currentConversation?.id }
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            orchestrationToolService: service
        )
        viewModel.conversationStore = store

        // 普通（根）会话不受影响：composer 放行、徽标查询 false。
        viewModel.reloadFromStore()
        await viewModel.orchestratedStatusRefreshTask?.value
        XCTAssertNil(viewModel.composerSendBlockReason(for: "你好"))
        let rootIsChild = await viewModel.isOrchestratedChild(conversationId: parentId)
        XCTAssertFalse(rootIsChild)

        // spawn 一个子线程（写 thread_edge）。
        let spawnResult = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "child"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r1",
            conversationId: parentId
        ))
        XCTAssertEqual(spawnResult["ok"] as? Bool, true)
        let childHex = try XCTUnwrap(spawnResult["child_thread_id"] as? String)
        let childId = KotlinUuid.companion.parse(uuidString: childHex)

        // 切到子会话 → reloadFromStore 触发 edge 缓存刷新 → 立即生效。
        await store.selectConversation(id: childId)
        viewModel.reloadFromStore()
        await viewModel.orchestratedStatusRefreshTask?.value
        XCTAssertEqual(viewModel.composerSendBlockReason(for: "你好"), .orchestratedThread)
        XCTAssertEqual(
            viewModel.composerSendBlockReason(for: "你好")?.userVisibleMessage,
            "此会话由父线程编排，暂不支持直接输入"
        )
        let childIsOrchestrated = await viewModel.isOrchestratedChild(conversationId: childId)
        XCTAssertTrue(childIsOrchestrated)

        // 编辑/重生成/删除/变体守卫：全部 no-op，消息不变。
        let messageCountBefore = viewModel.messages.count
        viewModel.editMessage(atMessageIndex: 0, newText: "篡改")
        viewModel.regenerate(atMessageIndex: 0)
        viewModel.deleteMessage(atMessageIndex: 0)
        viewModel.selectVariant(messageIndex: 0, variantIndex: 0)
        XCTAssertEqual(viewModel.messages.count, messageCountBefore)

        // 切回根会话：缓存刷新后恢复可输入。
        await store.selectConversation(id: parentId)
        viewModel.reloadFromStore()
        await viewModel.orchestratedStatusRefreshTask?.value
        XCTAssertNil(viewModel.composerSendBlockReason(for: "你好"))
    }
    /// 管线闭环：mailbox 语义说明只注入参与线程树的会话（父或子），普通会话不注入。
    func testOrchestrationContextPromptInjectedOnlyForLinkedConversations() async throws {
        let base = makeTempDirectory("OrchestrationPrompt")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler,
            currentConversationId: { store.currentConversation?.id }
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            orchestrationToolService: service
        )
        viewModel.conversationStore = store

        func injectedSystemText(_ viewModel: ChatViewModel) -> String {
            viewModel.preparedUploadMessagesForTesting([UIMessage.companion.user(prompt: "hi")])
                .filter { $0.role == MessageRole.system }
                .map { $0.toText() }
                .joined(separator: "\n")
        }

        // 普通会话：不注入编排语境。
        viewModel.reloadFromStore()
        await viewModel.orchestratedStatusRefreshTask?.value
        XCTAssertFalse(injectedSystemText(viewModel).contains("[mailbox"))

        // spawn 后父会话（有子边）：注入。
        let spawnResult = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "child"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r1",
            conversationId: parentId
        ))
        XCTAssertEqual(spawnResult["ok"] as? Bool, true)
        viewModel.reloadFromStore()
        await viewModel.orchestratedStatusRefreshTask?.value
        let parentText = injectedSystemText(viewModel)
        XCTAssertTrue(parentText.contains("[mailbox"))
        XCTAssertTrue(parentText.contains("FINAL_ANSWER"))

        // 切到子会话（有父边）：同样注入。
        let childHex = try XCTUnwrap(spawnResult["child_thread_id"] as? String)
        let childId = KotlinUuid.companion.parse(uuidString: childHex)
        await store.selectConversation(id: childId)
        viewModel.reloadFromStore()
        await viewModel.orchestratedStatusRefreshTask?.value
        XCTAssertTrue(injectedSystemText(viewModel).contains("[mailbox"))
    }

    /// 管线闭环场景 A/B：spawn 后不切换会话，run 级刷新路径（bindings.refreshOrchestrationLinks
    /// 每轮组装前调用）必须让缓存跟上——否则信封折入时模型看不到编排语境。
    func testOrchestrationLinksRefreshWithoutConversationSwitch() async throws {
        let base = makeTempDirectory("OrchestrationRefresh")
        defer { try? FileManager.default.removeItem(at: base) }
        let store = makeStore(directory: base)
        await store.newConversation()
        let parentId = try XCTUnwrap(store.currentConversation?.id)
        let db = makeDatabase(directory: base)
        let scheduler = FakeBackgroundScheduler()
        let service = makeService(
            store: store, db: db, scheduler: scheduler,
            currentConversationId: { store.currentConversation?.id }
        )
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(userDefaults: isolatedDefaults()),
            autoGenerateResponses: false,
            orchestrationToolService: service
        )
        viewModel.conversationStore = store

        viewModel.reloadFromStore()
        await viewModel.orchestratedStatusRefreshTask?.value
        XCTAssertFalse(viewModel.currentConversationHasOrchestrationLinks)

        let spawnResult = parseJSON(await service.execute(
            toolName: "spawn_agent",
            arguments: spawnArguments(taskName: "child"),
            providerSetting: makeProviderSetting(),
            params: makeParams(),
            runId: "r1",
            conversationId: parentId
        ))
        XCTAssertEqual(spawnResult["ok"] as? Bool, true)

        // 不 reloadFromStore（不切会话）——直接走 run 级刷新路径。
        await viewModel.refreshCurrentConversationOrchestratedStatus()
        XCTAssertTrue(viewModel.currentConversationHasOrchestrationLinks)

        // 场景 C：子线程 handoff 的 upload 首条是子线程向编排语境，display 不带。
        let handoff = try XCTUnwrap(scheduler.startedHandoff)
        XCTAssertEqual(handoff.uploadMessages.first?.role, MessageRole.system)
        XCTAssertTrue(handoff.uploadMessages.first?.toText().contains("child agent thread") == true)
        XCTAssertFalse(handoff.displayMessages.first?.toText().contains("child agent thread") == true)
        // 真机回归：子线程 handoff 的 maxTokens 被地板提升（makeParams 为 nil）。
        XCTAssertEqual(handoff.params.maxTokens?.int32Value, 32_768)
    }

    /// 子线程 maxTokens 地板 helper：nil/小值 → 32k；显式大值保留；其余字段不动。
    func testChildRunMaxTokenFloorHelper() {
        XCTAssertEqual(makeParams().withMaxTokenFloor(32_768).maxTokens?.int32Value, 32_768)
        XCTAssertEqual(makeParams(maxTokens: 4_000).withMaxTokenFloor(32_768).maxTokens?.int32Value, 32_768)
        XCTAssertEqual(makeParams(maxTokens: 65_536).withMaxTokenFloor(32_768).maxTokens?.int32Value, 65_536)
        XCTAssertEqual(makeParams(maxTokens: 65_536).withMaxTokenFloor(32_768).reasoningLevel, .off)
    }

}

/// Sendable 桥：跨 await 调 non-Sendable 的 `any IOSToolExecutor`（照
/// IOSToolRuntimeTests.IOSToolRuntimeUncheckedExecutorBox 先例）。
private final class IOSToolExecutorBox: @unchecked Sendable {
    private let base: any IOSToolExecutor

    init(_ base: any IOSToolExecutor) {
        self.base = base
    }

    func execute(
        name: String,
        arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        await base.execute(
            name: name,
            arguments: arguments,
            isUserInitiated: isUserInitiated
        )
    }


}

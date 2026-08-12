import XCTest
@testable import iosApp

final class IOSSettingsWiringTests: XCTestCase {
    private func source(_ relativePath: String) throws -> String {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosAppRoot = testsDir.deletingLastPathComponent()
        let fileURL = iosAppRoot.appendingPathComponent(relativePath)
        return try String(contentsOf: fileURL, encoding: .utf8)
    }

    /// G7 接线闭环：设置页可见控件（Stepper）、UserDefaults key、运行时消费
    /// （coordinator 从 SettingsStore 读上限）三处齐备。
    func testChatToolResumeCapIsWiredThroughExecutionSettings() throws {
        let view = try source("iosApp/ExecutionSettingsView.swift")
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        let store = try source("iosApp/SettingsStore.swift")

        XCTAssertTrue(view.contains("@AppStorage(IOSExecutionPreferenceKeys.chatMaxToolResumeCount)"))
        XCTAssertTrue(view.contains("Stepper("))
        XCTAssertTrue(view.contains("chatMaxToolResumeCountRange"))
        XCTAssertTrue(store.contains("chatMaxToolResumeCount"))
        XCTAssertTrue(coordinator.contains("dependencies.settingsStore.chatMaxToolResumeCount"))
    }

    func testChatComposerSendAndStopReachTheCurrentConversationRun() throws {
        let chat = try source("iosApp/ChatView.swift")
        let viewModel = try source("iosApp/ChatViewModel.swift")
        let sendStart = try XCTUnwrap(chat.range(of: "private func sendComposerMessage()"))
        let sendEnd = try XCTUnwrap(chat.range(of: "private func openComposerModelSheet()", range: sendStart.upperBound..<chat.endIndex))
        let send = chat[sendStart.lowerBound..<sendEnd.lowerBound]
        let gate = try XCTUnwrap(send.range(of: "guard sendEnabled(for: committedText) else { return }"))
        let dispatch = try XCTUnwrap(send.range(of: "viewModel.sendMessage()"))
        let cancelStart = try XCTUnwrap(viewModel.range(of: "func cancelGeneration()"))
        let cancelEnd = try XCTUnwrap(viewModel.range(of: "func cancelGeneration(runId:", range: cancelStart.upperBound..<viewModel.endIndex))
        let cancel = viewModel[cancelStart.lowerBound..<cancelEnd.lowerBound]

        XCTAssertTrue(chat.contains("onSend: sendComposerMessage"))
        XCTAssertTrue(chat.contains("isLoading: isComposerStopMode"))
        XCTAssertTrue(chat.contains("viewModel.isGenerationActiveForCurrentConversation"))
        XCTAssertTrue(chat.contains("viewModel.cancelGeneration()"))
        XCTAssertTrue(send.contains("composerInputController.committedText()"))
        XCTAssertFalse(send.contains("dismissKeyboard()"))
        XCTAssertTrue(chat.contains("return viewModel.composerSendBlockReason(for: text) == nil"))
        XCTAssertLessThan(gate.lowerBound, dispatch.lowerBound)
        XCTAssertTrue(cancel.contains("guard let currentConversationId else { return }"))
        XCTAssertTrue(cancel.contains("conversationId: currentConversationId"))
    }

    func testChatMessageListRendersNativeTimelineDirectlyWithoutRoutePolicy() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let list = try source("iosApp/ChatCollectionMessageList.swift")
        let projection = try source("iosApp/ChatMessageProjection.swift")
        let settings = try source("iosApp/DisplayFontSettingsView.swift")
        let keys = try source("iosApp/PlaceholderViews.swift")

        // native timeline 已 hardcode 为唯一 Chat 列表路径：route 判定层（route enum /
        // policy / eligibility / 开关 flag）整层退役，ChatView 直接渲染 NativeChatTimelineView。
        XCTAssertTrue(chatView.contains("NativeChatTimelineView("))
        XCTAssertFalse(chatView.contains("ChatMessageListRoutePolicy.route"))
        XCTAssertFalse(chatView.contains("swiftUICleanListEnabled"))
        XCTAssertFalse(chatView.contains("@AppStorage(NativeChatTimelineStaticRenderFeatureFlags.key)"))
        XCTAssertFalse(chatView.contains("@AppStorage(NativeChatTimelineStreamingTailFeatureFlags.key)"))
        XCTAssertFalse(list.contains("enum ChatMessageListRoutePolicy"))
        XCTAssertFalse(list.contains("NativeChatTimelineRoutePolicy.shouldUseNativeTimeline"))
        XCTAssertFalse(list.contains("var nativeScrollDriverEnabled: Bool"))
        XCTAssertTrue(list.contains("@State private var scrollDriver = NativeTimelineScrollDriver()"))
        XCTAssertTrue(list.contains("scrollDriver.attach(scrollView)"))
        XCTAssertFalse(chatView.contains("NativeChatTimelineMirror"))
        XCTAssertFalse(chatView.contains("recordNativeTimelineMirrorIfEnabled"))
        XCTAssertFalse(projection.contains("NativeTimelineMirrorInput"))
        XCTAssertFalse(projection.contains("NativeChatTimelineMirror"))
        XCTAssertTrue(settings.contains("DisplayToggleRow(title: \"生成时跟随滚动\", isOn: followGeneration)"))
        XCTAssertTrue(keys.contains("static let followGeneration = \"app.amber.ios.display.followGeneration\""))
        XCTAssertTrue(chatView.contains("@AppStorage(IOSDisplayPreferenceKeys.followGeneration) private var followGeneration = true"))
        XCTAssertTrue(chatView.contains("followGeneration: followGeneration"))
    }

    func testChatTopBarUsesStableControlDimensions() {
        XCTAssertEqual(ChatTopBarLayout.controlsHeight, 54)
        XCTAssertEqual(ChatTopBarLayout.toolbarButtonDiameter, 38)
        XCTAssertEqual(ChatTopBarLayout.softEdgeExtension, 8)
    }

    func testStaticMarkdownUsesTheSharedExternalURLPolicy() throws {
        let markdown = try source("iosApp/MarkdownView.swift")

        XCTAssertTrue(markdown.contains("ChatMarkdownOpenURLPolicy.url(from: raw)"))
        XCTAssertFalse(markdown.contains("scheme == \"http\" || scheme == \"https\""))
        XCTAssertEqual(
            ChatMarkdownOpenURLPolicy.url(from: "mailto:hello@example.com")?.absoluteString,
            "mailto:hello@example.com"
        )
    }

    func testActivityIslandEdgeGlowIsOptionalAndDefaultsOff() throws {
        let keys = try source("iosApp/PlaceholderViews.swift")
        let settings = try source("iosApp/DisplayFontSettingsView.swift")
        let activityIsland = try source("iosApp/ChatActivityIslandView.swift")
        let storageDeclaration =
            "@AppStorage(IOSDisplayPreferenceKeys.activityIslandEdgeGlow)" +
            " private var activityIslandEdgeGlow = false"

        XCTAssertTrue(
            keys.contains(
                "static let activityIslandEdgeGlow = \"app.amber.ios.display.activityIslandEdgeGlow\""
            )
        )
        XCTAssertTrue(settings.contains(storageDeclaration))
        XCTAssertTrue(settings.contains("彩色边缘辉光"))
        XCTAssertTrue(settings.contains("activityIslandEdgeGlow.toggle()"))
        XCTAssertTrue(activityIsland.contains(storageDeclaration))
        XCTAssertTrue(activityIsland.contains("if activityIslandEdgeGlow,"))
    }

    /// 生成完成触觉：设置页开关（默认开）→ iOS 本地偏好 key → ChatViewModel
    /// 完成回调消费，三点齐备。刻意不复用 KMP enableMessageGenerationHapticEffect
    /// （Android-only 接线且默认关），避免改变 Android 默认行为。
    func testCompletionHapticToggleIsWiredWithDefaultOn() throws {
        let keys = try source("iosApp/PlaceholderViews.swift")
        let settings = try source("iosApp/DisplayFontSettingsView.swift")
        let viewModel = try source("iosApp/ChatViewModel.swift")
        let storageDeclaration =
            "@AppStorage(IOSDisplayPreferenceKeys.completionHaptic)" +
            " private var completionHaptic = true"

        XCTAssertTrue(
            keys.contains(
                "static let completionHaptic = \"app.amber.ios.display.completionHaptic\""
            )
        )
        XCTAssertTrue(keys.contains("case rigidImpact"))
        XCTAssertTrue(keys.contains("UIImpactFeedbackGenerator(style: .rigid).impactOccurred()"))
        XCTAssertTrue(settings.contains(storageDeclaration))
        XCTAssertTrue(settings.contains("生成完成振动"))
        XCTAssertTrue(settings.contains("completionHaptic.toggle()"))
        XCTAssertTrue(viewModel.contains("IOSDisplayPreferenceKeys.completionHaptic"))
        XCTAssertTrue(viewModel.contains("AmberHaptics.trigger(.rigidImpact)"))
    }

    func testBackgroundToolEnginePublishesLiveActivityStagesAtExecutionBoundaries() throws {
        let coordinator = try source("iosApp/IOSChatBackgroundGenerationCoordinator.swift")
        let engine = try source("iosApp/IOSAgentToolEngine.swift")
        let runStart = try XCTUnwrap(coordinator.range(of: "let initialResult = await engine.run("))
        let runSuffix = String(coordinator[runStart.lowerBound...])
        let runEnd = try XCTUnwrap(runSuffix.range(of: "case .singleToolOnly:"))
        let runBlock = String(runSuffix[..<runEnd.lowerBound])

        XCTAssertTrue(engine.contains("onToolExecutionStarted"))
        XCTAssertTrue(engine.contains("onAssistantStage"))
        XCTAssertTrue(engine.contains("onMessagesUpdated"))
        XCTAssertTrue(runBlock.contains("onToolExecutionStarted:"))
        XCTAssertTrue(runBlock.contains("onAssistantStage:"))
        XCTAssertTrue(runBlock.contains("onMessagesUpdated:"))
        XCTAssertTrue(runBlock.contains("job.messagesSnapshot.replace(with: messages)"))
        XCTAssertTrue(coordinator.contains("AsyncStream<AgentActivityPresentation>.makeStream()"))
        XCTAssertTrue(runBlock.contains("presentationEvents.continuation.yield("))
        XCTAssertFalse(runBlock.contains("await self.publishRunningPresentation("))
        XCTAssertTrue(coordinator.contains("guard runState.allowsRunningPresentation else { return }"))
        XCTAssertTrue(coordinator.contains("AgentActivityPresentation.runningTool(toolName: toolName)"))
        XCTAssertGreaterThanOrEqual(
            coordinator.components(
                separatedBy: "stage: AgentActivityResponseStagePolicy.initialStage"
            ).count - 1,
            2,
            "后台初次输出及工具后的下一轮都必须先回到准备态，再由真实 chunk 推进阶段"
        )

        let expirationStart = try XCTUnwrap(
            coordinator.range(of: "backgroundTask.expirationHandler = { [weak self] in")
        )
        let expirationSuffix = String(coordinator[expirationStart.lowerBound...])
        let expirationEnd = try XCTUnwrap(expirationSuffix.range(of: "let requestProvider: ProviderSetting"))
        let expirationBlock = String(expirationSuffix[..<expirationEnd.lowerBound])
        let mainActorHop = try XCTUnwrap(expirationBlock.range(of: "Task { @MainActor in"))
        let terminalClaim = try XCTUnwrap(
            expirationBlock.range(of: "let claim = runState.expireAndReserveTerminal()")
        )

        XCTAssertLessThan(
            terminalClaim.lowerBound,
            mainActorHop.lowerBound,
            "The expiration callback must reserve terminal ownership before hopping to MainActor."
        )
    }

    func testSystemProgressCardCancellationStopsTheOwnedChatRun() throws {
        let keepAlive = try source("iosApp/BackgroundGenerationKeepAlive.swift")
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")

        XCTAssertTrue(keepAlive.contains("var onSystemTaskExpiration: (() -> Void)?"))
        XCTAssertTrue(keepAlive.contains("onSystemTaskExpiration: (() -> Void)? = nil"))
        XCTAssertTrue(keepAlive.contains("lease.onExpire?()"))
        XCTAssertTrue(keepAlive.contains("(lease.onSystemTaskExpiration ?? lease.onExpire)?()"))
        XCTAssertEqual(
            coordinator.components(separatedBy: "onSystemTaskExpiration:").count - 1,
            3,
            "普通回复、生图与审批恢复都提交系统进度卡，取消任一张都必须停止其 owned run"
        )
        XCTAssertTrue(coordinator.contains("cancelRunAfterSystemKeepAliveExpiration(runId)"))
        XCTAssertTrue(coordinator.contains("private func cancelRunAfterSystemKeepAliveExpiration"))
    }

    func testBackgroundHandoffExpirationStopsWithoutAutomaticResubmission() throws {
        let coordinator = try source("iosApp/IOSChatBackgroundGenerationCoordinator.swift")
        let appShell = try source("iosApp/AppShell.swift")
        let start = try XCTUnwrap(
            coordinator.range(of: "backgroundTask.expirationHandler = { [weak self] in")
        )
        let end = try XCTUnwrap(
            coordinator.range(of: "let requestProvider: ProviderSetting", range: start.upperBound..<coordinator.endIndex)
        )
        let expiration = coordinator[start.lowerBound..<end.lowerBound]

        XCTAssertTrue(expiration.contains("await self.persistExpirationFailure("))
        XCTAssertTrue(expiration.contains("self.finish(runId: job.runId, requestId: backgroundTask.identifier)"))
        XCTAssertFalse(expiration.contains("suspendForResume("))
        XCTAssertFalse(appShell.contains("resumeSuspendedRunsIfNeeded()"))
        XCTAssertFalse(coordinator.contains("finalizeSuspendedRunsIfNeeded"))
        XCTAssertFalse(coordinator.contains("IOSChatBackgroundSuspensionStore"))
    }

    func testDirectImageKeepAliveExpiresWithoutStartingASecondImageRequest() throws {
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        let start = try XCTUnwrap(coordinator.range(of: "func runImageTool("))
        let end = try XCTUnwrap(
            coordinator.range(of: "private func failImageToolCallBeforeExecution(", range: start.upperBound..<coordinator.endIndex)
        )
        let imageRun = coordinator[start.lowerBound..<end.lowerBound]

        XCTAssertTrue(imageRun.contains("onExpire: { [weak self] in"))
        XCTAssertTrue(imageRun.contains("cancelRunAfterSystemKeepAliveExpiration(runId)"))
        XCTAssertFalse(imageRun.contains("mode: .singleToolOnly"))
    }

    func testReasoningExpansionAndIslandGlowHonorFrozenMotion() throws {
        let misc = try source("iosApp/ChatMiscViews.swift")
        let island = try source("iosApp/ChatActivityIslandView.swift")
        let glow = try source("iosApp/IslandEdgeGlowView.swift")

        // Reduce Motion 仍然钉死无动画；终态自动收起额外走无动画单帧收口
        // （suppressesShowsBodyAnimation），两条都要锁。
        XCTAssertTrue(misc.contains("reduceMotion || suppressesShowsBodyAnimation ? nil : .easeInOut(duration: 0.28)"))
        XCTAssertTrue(misc.contains("value: showsBody"))
        XCTAssertTrue(misc.contains("suppressesShowsBodyAnimation = true"))
        XCTAssertTrue(misc.contains("private func setExpanded(_ expanded: Bool, duration: Double)"))
        XCTAssertTrue(island.contains("isPaused: presentation.isFrozen"))
        XCTAssertTrue(glow.contains("paused: isPaused, reduceMotion: reduceMotion"))
        XCTAssertTrue(glow.contains("isAnimated && !isPaused && !isReduceMotion"))
    }

    func testGrokWebLoginIsWiredToProviderSettingsAndChatRuntime() throws {
        let detail = try source("iosApp/ProviderDetailView.swift")
        let configuration = try source("iosApp/ChatProviderConfiguration.swift")
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        let grokProvider = try source("iosApp/IOSGrokWebProvider.swift")

        XCTAssertTrue(detail.contains("GrokWebLoginView("))
        XCTAssertTrue(detail.contains("IOSGrokWebConstants.webBaseUrl"))
        XCTAssertTrue(detail.contains("updateProviderChatModels"))
        XCTAssertTrue(detail.contains("grokSection"))

        XCTAssertTrue(configuration.contains("IOSGrokWebProviderResolver.isGrokWebProvider(provider)"))
        XCTAssertTrue(configuration.contains(".grokNotSignedIn"))

        XCTAssertTrue(coordinator.contains("IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI)"))
        XCTAssertTrue(coordinator.contains("IOSGrokWebClient(providerId: providerId).streamText"))
        XCTAssertTrue(coordinator.contains("grokWebStreamTask?.cancel()"))
        XCTAssertTrue(coordinator.contains("!IOSGrokWebProviderResolver.isGrokWebProvider(handoff.providerSetting)"))
        XCTAssertTrue(detail.contains("providerBackup: grokProviderBackup"))
        XCTAssertTrue(detail.contains("baseUrl: backup.baseUrl"))
        XCTAssertTrue(detail.contains("if shouldSeedModels"))
        XCTAssertTrue(grokProvider.contains("IOSGrokWebBrowserTransport"))
        XCTAssertTrue(grokProvider.contains(#"credentials: "include""#))
        XCTAssertFalse(grokProvider.contains("session.bytes(for:"))
    }

    func testGrokWebAuthenticationRequiresSSOAndRestoresReadWriteCookies() {
        let analytics = makeCookie(name: "analytics", value: "present")
        let sso = makeCookie(name: "sso", value: "session-token")

        XCTAssertNil(IOSGrokWebCookieValidator.ssoCookieHeader(from: [analytics]))
        XCTAssertEqual(
            IOSGrokWebCookieValidator.ssoCookieHeader(from: [analytics, sso]),
            "sso=session-token"
        )
        XCTAssertFalse(IOSGrokWebCookieValidator.hasSSOCookie(in: "analytics=present"))
        XCTAssertTrue(IOSGrokWebCookieValidator.hasSSOCookie(in: "analytics=present; sso=session-token"))
        let cookies = IOSGrokWebCookieValidator.authenticationCookies(from: "sso=session-token")

        XCTAssertEqual(Set(cookies.map(\.name)), Set(["sso", "sso-rw"]))
        XCTAssertTrue(cookies.allSatisfy { $0.value == "session-token" })
        XCTAssertTrue(cookies.allSatisfy { $0.domain == ".grok.com" })
        XCTAssertTrue(cookies.allSatisfy(\.isSecure))
    }

    func testGrokWebModelResolverMapsSeededModelAndRejectsAPIOnlyModel() throws {
        XCTAssertEqual(
            try IOSGrokWebModelResolver.resolve("grok-4.20-fast"),
            IOSGrokWebWireModel(modelName: "grok-420", modelMode: "MODEL_MODE_FAST")
        )

        XCTAssertThrowsError(try IOSGrokWebModelResolver.resolve("grok-4.5")) { error in
            XCTAssertTrue(error.localizedDescription.contains("xAI API"))
            XCTAssertTrue(error.localizedDescription.contains("sub2api"))
        }
    }

    func testGrokWebTerminalFramesPreserveTokensSurfaceErrorsAndCloseTransport() throws {
        let provider = try source("iosApp/IOSGrokWebProvider.swift")
        let terminal = #"{"result":{"response":{"token":"final","isThinking":false,"finalMetadata":{"done":true}}}}"#
        let error = #"data: {"error":{"message":"session expired"}}"#

        XCTAssertEqual(
            IOSGrokWebStreamParser.parse(terminal),
            IOSGrokWebStreamFrame(token: "final", isFinished: true, errorMessage: nil)
        )
        XCTAssertEqual(
            IOSGrokWebStreamParser.parse(error),
            IOSGrokWebStreamFrame(token: nil, isFinished: true, errorMessage: "session expired")
        )
        XCTAssertTrue(provider.contains("return frame.isFinished"))
    }

    func testApprovedCouncilRunIsOwnedByTheCoordinatorCancellationTask() throws {
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        let start = try XCTUnwrap(coordinator.range(of: "private func finishPendingCouncilToolApproval"))
        let end = try XCTUnwrap(
            coordinator.range(of: "private func resumeAfterApproval", range: start.upperBound..<coordinator.endIndex)
        )
        let approvalPath = coordinator[start.lowerBound..<end.lowerBound]

        XCTAssertTrue(approvalPath.contains("foregroundToolExecutionTask = executionTask"))
        XCTAssertTrue(approvalPath.contains("completeApprovedToolExecution(result, matching: executionToken)"))
        XCTAssertTrue(coordinator.contains("approvedToolContinuation?.resume(returning: nil)"))
    }

    func testCouncilSettingsExposeAnExplicitCurrentModelConnectivityProbe() throws {
        let settings = try source("iosApp/CouncilSettingsView.swift")
        let runtime = try source("iosApp/CouncilChatRuntimeView.swift")

        XCTAssertTrue(settings.contains("测试当前议会模型"))
        XCTAssertTrue(settings.contains("IOSCouncilModelConnectivityTester"))
        XCTAssertTrue(settings.contains("实际回退"))
        // 生产入口跳过旧 CouncilView/CouncilSettingsView，活设置 sheet 必须自带连通性预检。
        XCTAssertTrue(runtime.contains("测试当前议会模型"))
        XCTAssertTrue(runtime.contains("IOSCouncilModelConnectivityTester"))
    }

    func testGrokWebPayloadDefaultsToChatAndSupportsIsolatedNovelRequests() {
        let wireModel = IOSGrokWebWireModel(
            modelName: "grok-420",
            modelMode: "MODEL_MODE_FAST"
        )

        let chatPayload = IOSGrokWebPayloadBuilder.makePayload(
            prompt: "chat prompt",
            wireModel: wireModel
        )
        XCTAssertEqual(chatPayload["disableSearch"] as? Bool, false)
        XCTAssertEqual(chatPayload["disableMemory"] as? Bool, false)

        let novelPayload = IOSGrokWebPayloadBuilder.makePayload(
            prompt: "novel prompt",
            wireModel: wireModel,
            options: .novel
        )
        XCTAssertEqual(novelPayload["disableSearch"] as? Bool, true)
        XCTAssertEqual(novelPayload["disableMemory"] as? Bool, true)
        XCTAssertEqual(novelPayload["message"] as? String, "novel prompt")
        XCTAssertEqual(novelPayload["modelName"] as? String, "grok-420")
    }

    func testProviderEndpointPolicyAllowsHTTPOnlyForIPAddressHosts() {
        XCTAssertTrue(IOSProviderEndpointPolicy.isValidBaseURL("https://sub2api.example/v1"))
        XCTAssertTrue(IOSProviderEndpointPolicy.isValidBaseURL("http://203.0.113.10:8080/v1"))
        XCTAssertTrue(IOSProviderEndpointPolicy.isValidBaseURL("http://[2001:db8::10]:8080/v1"))
        XCTAssertFalse(IOSProviderEndpointPolicy.isValidBaseURL("http://sub2api.example/v1"))
        XCTAssertFalse(IOSProviderEndpointPolicy.isValidBaseURL("203.0.113.10:8080/v1"))
    }

    func testInfoPlistAllowsInsecureHTTPOnlyForIPAddressRanges() throws {
        let info = try source("iosApp/Info.plist")

        XCTAssertTrue(info.contains("NSAppTransportSecurity"))
        XCTAssertTrue(info.contains("NSExceptionAllowsInsecureHTTPLoads"))
        XCTAssertTrue(info.contains("0.0.0.0/0"))
        XCTAssertTrue(info.contains("::/0"))
        XCTAssertFalse(info.contains("NSAllowsArbitraryLoads"))
    }

    /// §13.4 自进化设置三件套：可见控件（自治级别三档 + 全局 kill switch）、
    /// 持久化（`IOSEvolutionPreferenceKeys`）、运行时消费（policy engine 与
    /// workflow 读同一 keys）。缺任一项都不算接线完成（iosApp/AGENTS.md
    /// Settings 规则）。
    func testEvolutionAutonomyAndKillSwitchAreWiredThroughEvolutionSettings() throws {
        let settings = try source("iosApp/EvolutionSettingsView.swift")
        let engine = try source("iosApp/IOSPromotionPolicyEngine.swift")
        let workflow = try source("iosApp/IOSEvolutionWorkflow.swift")
        let keys = try source("iosApp/IOSPromotionPolicyEngine.swift")

        // 1. 可见控件：三档单选 + kill switch 开关，都绑定持久化 key。
        XCTAssertTrue(settings.contains("@AppStorage(IOSEvolutionPreferenceKeys.autonomyLevel)"))
        XCTAssertTrue(settings.contains("@AppStorage(IOSEvolutionPreferenceKeys.killSwitch)"))
        XCTAssertTrue(settings.contains("ForEach(IOSEvolutionAutonomyLevel.allCases, id: \\.rawValue)"))
        XCTAssertTrue(settings.contains("autonomyLevel = level.rawValue"))
        XCTAssertTrue(settings.contains("killSwitch.toggle()"))
        XCTAssertTrue(settings.contains("暂停自治发布"))

        // 2. 持久化：key 定义在 policy engine 文件里，默认 T0+T1 自动 / kill switch 关。
        XCTAssertTrue(
            keys.contains(
                "static let autonomyLevel = \"app.amber.ios.evolution.autonomyLevel\""
            )
        )
        XCTAssertTrue(
            keys.contains(
                "static let killSwitch = \"app.amber.ios.evolution.killSwitch\""
            )
        )
        XCTAssertTrue(engine.contains("case t0T1Auto"))

        // 3. 运行时消费：policy engine 的读者与 workflow 生产接线的 provider
        //    都读同一 keys（不是复制值）。
        XCTAssertTrue(engine.contains("IOSEvolutionPreferenceKeys.autonomyLevel"))
        XCTAssertTrue(engine.contains("IOSEvolutionPreferenceKeys.killSwitch"))
        XCTAssertTrue(engine.contains("func currentAutonomyLevel(defaults: UserDefaults"))
        XCTAssertTrue(engine.contains("func killSwitchEnabled(defaults: UserDefaults"))
        XCTAssertTrue(
            workflow.contains("autonomyLevelProvider: { IOSPromotionPolicyEngine.currentAutonomyLevel() }")
        )
        XCTAssertTrue(
            workflow.contains("killSwitchProvider: { IOSPromotionPolicyEngine.killSwitchEnabled() }")
        )
    }

    /// 持久化往返：写 UserDefaults（AppStorage 同 key）→ policy engine 读回；
    /// 未设置时回落默认值（T0+T1 自动 / kill switch 关）。
    func testEvolutionPreferenceRoundTripThroughUserDefaults() throws {
        let suiteName = "IOSSettingsWiringTests.evolution.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            return XCTFail("无法创建 UserDefaults suite")
        }
        defer { defaults.removePersistentDomain(forName: suiteName) }

        // 未设置：默认 T0+T1 自动、kill switch 关。
        XCTAssertEqual(
            IOSPromotionPolicyEngine.currentAutonomyLevel(defaults: defaults),
            .t0T1Auto
        )
        XCTAssertFalse(IOSPromotionPolicyEngine.killSwitchEnabled(defaults: defaults))

        // 往返：写入三档与 kill switch，逐一读回。
        defaults.set(IOSEvolutionAutonomyLevel.allManual.rawValue, forKey: IOSEvolutionPreferenceKeys.autonomyLevel)
        XCTAssertEqual(
            IOSPromotionPolicyEngine.currentAutonomyLevel(defaults: defaults),
            .allManual
        )
        defaults.set(IOSEvolutionAutonomyLevel.notifyOnly.rawValue, forKey: IOSEvolutionPreferenceKeys.autonomyLevel)
        XCTAssertEqual(
            IOSPromotionPolicyEngine.currentAutonomyLevel(defaults: defaults),
            .notifyOnly
        )
        defaults.set(IOSEvolutionAutonomyLevel.t0T1Auto.rawValue, forKey: IOSEvolutionPreferenceKeys.autonomyLevel)
        XCTAssertEqual(
            IOSPromotionPolicyEngine.currentAutonomyLevel(defaults: defaults),
            .t0T1Auto
        )
        defaults.set(true, forKey: IOSEvolutionPreferenceKeys.killSwitch)
        XCTAssertTrue(IOSPromotionPolicyEngine.killSwitchEnabled(defaults: defaults))
        defaults.set(false, forKey: IOSEvolutionPreferenceKeys.killSwitch)
        XCTAssertFalse(IOSPromotionPolicyEngine.killSwitchEnabled(defaults: defaults))
    }

    /// Phase 3 Wave 2 经验三件套（iosApp/AGENTS.md Settings 规则）：可见控件
    /// （自进化设置页「经验」区 + 批准/拒绝动作）、持久化（经验池 JSON store
    /// + dismissal 边车 + curator 建议投影）、运行时消费（ChatViewModel 每轮
    /// 组装经 ChatRuntimeContextBuilder 从 curator 检索注入，与技能目录共享
    /// 同一字节预算）。行为契约测试见 IOSEvolutionExperienceInjectionTests。
    func testExperienceInjectionAndManagementAreWiredThroughSelfEvolution() throws {
        let settings = try source("iosApp/EvolutionSettingsView.swift")
        let section = try source("iosApp/ExperiencesSettingsSection.swift")
        let model = try source("iosApp/IOSExperienceSettingsModel.swift")
        let context = try source("iosApp/ChatContextSupport.swift")
        let viewModel = try source("iosApp/ChatViewModel.swift")
        let curator = try source("iosApp/IOSEvolutionExperienceCurator.swift")

        // 1. 可见控件：设置页「经验」区沿自进化页结构渲染，建议可批准/拒绝。
        XCTAssertTrue(settings.contains("ExperiencesSettingsSection(model: experiencesModel)"))
        XCTAssertTrue(settings.contains("IOSExperienceSettingsModel()"))
        XCTAssertTrue(settings.contains("experiencesModel.reload()"))
        XCTAssertTrue(section.contains("AmberSectionLabel(text: \"经验\")"))
        XCTAssertTrue(section.contains("model.approve(suggestion)"))
        XCTAssertTrue(section.contains("model.reject(suggestion)"))
        XCTAssertTrue(section.contains("已归档"))
        XCTAssertTrue(section.contains("帮助 \\(experience.helpfulCount)"))

        // 2. 持久化：模型走真实 store + curator 投影 + dismissal 边车。
        XCTAssertTrue(model.contains("IOSExperienceSuggestionDismissalStore"))
        XCTAssertTrue(model.contains("curator.store.allExperiences()"))
        XCTAssertTrue(model.contains("curator.currentSuggestions()"))
        XCTAssertTrue(model.contains("case .delete"))
        XCTAssertTrue(model.contains("case .supersede"))
        XCTAssertTrue(curator.contains("func currentSuggestions(now: Int64"))
        XCTAssertTrue(curator.contains("harmfulDeleteThreshold"))

        // 3. 运行时消费：每轮组装从 curator 检索（不缓存跨轮结果），生产
        //    ChatViewModel 把 curator 注入 builder；预算与技能目录合并核算。
        XCTAssertTrue(context.contains("experienceCurator"))
        XCTAssertTrue(context.contains("experienceCurator.retrieve"))
        XCTAssertTrue(context.contains("sharedSkillExperienceByteBudget"))
        XCTAssertTrue(context.contains("topK: Self.experienceInjectionTopK"))
        XCTAssertTrue(context.contains("byteBudget: remainingBudget"))
        XCTAssertTrue(viewModel.contains("experienceCurator = IOSEvolutionExperienceCurator("))
        XCTAssertTrue(viewModel.contains("experienceCurator: experienceCurator"))
    }

    private func makeCookie(name: String, value: String) -> HTTPCookie {
        HTTPCookie(properties: [
            .domain: ".grok.com",
            .path: "/",
            .name: name,
            .value: value,
            .secure: "TRUE",
        ])!
    }
}

import XCTest
@preconcurrency import Shared
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

    func testChatTopBarKeepsIslandOpaqueAndWidthAdaptive() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let island = try source("iosApp/ChatActivityIslandView.swift")

        // Side chips + center island must not share GlassEffectContainer (punch-through).
        XCTAssertFalse(
            chatView.contains("AmberGlassGroup(spacing: 12)"),
            "Chat top bar must not group toolbar glass with the activity island."
        )
        // fixedSize must precede glass so the capsule paints hug width, not bar width.
        XCTAssertTrue(
            island.contains(
                """
                .frame(height: 40)
                            .fixedSize(horizontal: true, vertical: false)
                            .background { glowUnderlay }
                            .modifier(ChatActivityIslandGlass())
                """
            ),
            "Island must lock hug size before glassEffect."
        )
        XCTAssertTrue(island.contains("ChatTopBarLayout.islandTitleMaxWidth"))
        XCTAssertTrue(chatView.contains("ChatTopBarLayout.islandSideGutter"))
        XCTAssertTrue(
            chatView.contains(
                """
                ChatActivityIslandView(presentation: islandPresentation ?? .idle(topIslandState))
                                .fixedSize(horizontal: true, vertical: false)
                                .padding(.horizontal, ChatTopBarLayout.islandSideGutter)
                """
            )
        )
        // Must not stretch the island shell to the full bar width.
        XCTAssertFalse(
            chatView.contains("ChatActivityIslandView(presentation: islandPresentation ?? .idle(topIslandState))\n                .padding(.horizontal, ChatTopBarLayout.islandSideGutter)\n                .frame(maxWidth: .infinity)")
        )
        XCTAssertTrue(island.contains(".background(AmberTheme.glass.opacity(0.16), in: Capsule())"))
        XCTAssertTrue(island.contains(".glassEffect(.regular, in: Capsule())"))
    }

    func testCouncilTopBarUsesNativeSoftEdgeAndModeCapsule() throws {
        let runtime = try source("iosApp/CouncilChatRuntimeView.swift")

        XCTAssertTrue(runtime.contains(".safeAreaBar(edge: .top, spacing: 0)"))
        XCTAssertTrue(runtime.contains("ChatTopBarLayout.softEdgeExtension"))
        XCTAssertTrue(runtime.contains("ChatTopBarLayout.controlsHeight"))
        XCTAssertTrue(runtime.contains("ChatToolbarIconButton("))
        XCTAssertTrue(runtime.contains(".background(AmberTheme.glass.opacity(0.16), in: Capsule())"))
        XCTAssertTrue(runtime.contains(".glassEffect(.regular.interactive(), in: Capsule())"))
        // Mode capsule: fixedSize on label, then glass — not glass then fixedSize on Button.
        XCTAssertTrue(
            runtime.contains(
                """
                .frame(height: 40)
                            .fixedSize(horizontal: true, vertical: false)
                            .modifier(CouncilModeCapsuleGlass())
                """
            ),
            "Council mode capsule must lock hug size before glassEffect."
        )
        XCTAssertFalse(runtime.contains("AmberGlassGroup(spacing: 12)"))
        XCTAssertTrue(runtime.contains(".scrollEdgeEffectStyle(.soft, for: .top)"))
        XCTAssertTrue(runtime.contains("scrollView.topEdgeEffect.style = .soft"))
        XCTAssertTrue(runtime.contains("modeCapsuleButton"))
        XCTAssertFalse(
            runtime.contains("header\n                if let archiveErrorMessage = viewModel.archiveErrorMessage"),
            "Council chrome must not sit above the transcript as a clipping sibling."
        )
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
        let grokOAuth = try source("iosApp/IOSGrokOAuthClient.swift")

        XCTAssertTrue(detail.contains("GrokWebLoginView("))
        XCTAssertTrue(detail.contains("IOSGrokWebConstants.cliProxyBaseUrl"))
        XCTAssertTrue(detail.contains("adoptGrokOAuthChatCatalog"))
        XCTAssertTrue(detail.contains("legacyWebModelIds"))
        XCTAssertTrue(detail.contains("setCurrentChatModelId(grok46.id.description())"))
        XCTAssertTrue(detail.contains("IOSGrokOAuthAuthStore.loadBackup"))
        XCTAssertTrue(detail.contains("grokSection"))
        XCTAssertTrue(detail.contains("tokenPlanSection"))
        XCTAssertTrue(detail.contains("headerDisguiseSection"))
        XCTAssertTrue(detail.contains("OpenAICompatUserAgents.shared.OPENCODE"))
        XCTAssertTrue(detail.contains("notice = \"服务商配置已保存。\""))
        XCTAssertFalse(detail.contains("alert = .saved"))
        XCTAssertFalse(detail.contains("alert = .currentModelSet"))

        XCTAssertTrue(configuration.contains("IOSGrokWebProviderResolver.isGrokWebProvider(provider)"))
        XCTAssertTrue(configuration.contains(".grokNotSignedIn"))
        XCTAssertTrue(configuration.contains("hasUsableCredential"))
        XCTAssertTrue(configuration.contains("credentialStatusTitle"))

        XCTAssertTrue(coordinator.contains("IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI)"))
        XCTAssertTrue(coordinator.contains("IOSGrokWebProviderResolver.resolved("))
        XCTAssertTrue(coordinator.contains("IOSGrokWebClient(providerId: providerId).streamText"))
        XCTAssertTrue(coordinator.contains("grokWebStreamTask?.cancel()"))
        XCTAssertTrue(coordinator.contains("IOSGrokWebProviderResolver.isGrokWebConfiguration(grokOpenAI)"))
        XCTAssertTrue(coordinator.contains("backgroundProviderSetting: effectiveProvider"))
        XCTAssertTrue(detail.contains("providerBackup: grokProviderBackup"))
        XCTAssertTrue(detail.contains("baseUrl: backup.baseUrl"))
        XCTAssertTrue(grokProvider.contains("IOSGrokWebBrowserTransport"))
        XCTAssertTrue(grokProvider.contains(#"credentials: "include""#))
        XCTAssertTrue(grokProvider.contains("Authorization"))
        XCTAssertTrue(grokProvider.contains("IOSGrokOAuthClients.shared(providerId: providerId).resolveAccessToken()"))
        XCTAssertTrue(grokProvider.contains("@escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void"))
        XCTAssertTrue(grokProvider.contains("Grok Web 未能停留在 grok.com"))
        XCTAssertTrue(grokProvider.contains("if oauthToken != nil"))
        XCTAssertTrue(grokProvider.contains("cliProxyBaseUrl"))
        XCTAssertTrue(grokOAuth.contains("cli-chat-proxy.grok.com"))
        XCTAssertTrue(grokOAuth.contains("grok-4.6"))
        XCTAssertFalse(grokProvider.contains("session.bytes(for:"))
        XCTAssertTrue(detail.contains("用 Grok 账号登录"))
        XCTAssertFalse(detail.contains("已通过系统浏览器授权"))

        let grokLogin = try source("iosApp/GrokWebLoginView.swift")
        XCTAssertTrue(grokLogin.contains("ASWebAuthenticationSession("))
        XCTAssertTrue(grokLogin.contains("prefersEphemeralWebBrowserSession = false"))
        XCTAssertTrue(grokLogin.contains("开始登录"))
        XCTAssertTrue(grokLogin.contains("loopback?.stop()"))
        XCTAssertTrue(grokLogin.contains("attachProviderBackup"))
        XCTAssertTrue(grokLogin.contains(".onDisappear { model.cancelLogin() }"))
        XCTAssertFalse(grokLogin.contains("GrokWebLoginWebView"))
        XCTAssertFalse(grokLogin.contains("WKWebView"))
        XCTAssertTrue(grokLogin.contains("IOSGrokOAuthPKCE.buildAuthorizationURL"))
        XCTAssertTrue(grokLogin.contains("if !model.isSignedIn"))
        XCTAssertTrue(grokOAuth.contains("oauth.backup"))
    }

    func testGrokOAuthAuthorizationURLUsesLoopbackAndPKCE() {
        let verifier = IOSGrokOAuthPKCE.generateCodeVerifier()
        XCTAssertEqual(verifier.count, 64)
        let challenge = IOSGrokOAuthPKCE.s256Challenge(verifier: verifier)
        XCTAssertFalse(challenge.contains("+"))
        XCTAssertFalse(challenge.contains("/"))
        XCTAssertFalse(challenge.contains("="))

        let url = IOSGrokOAuthPKCE.buildAuthorizationURL(state: "st", nonce: "nn", codeChallenge: "ch")
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        let values = Dictionary(uniqueKeysWithValues: components.queryItems!.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(values["client_id"], IOSGrokOAuthConstants.clientId)
        XCTAssertEqual(values["redirect_uri"], IOSGrokOAuthConstants.redirectUri)
        XCTAssertEqual(values["state"], "st")
        XCTAssertEqual(values["nonce"], "nn")
        XCTAssertEqual(values["code_challenge"], "ch")
        XCTAssertEqual(values["code_challenge_method"], "S256")
        XCTAssertTrue(values["scope"]?.contains("offline_access") == true)
        XCTAssertTrue(values["scope"]?.contains("grok-cli:access") == true)
        XCTAssertTrue(values["scope"]?.contains("conversations:read") == true)
        XCTAssertTrue(values["scope"]?.contains("api:access") == true)

        let callback = "GET /callback?code=abc&state=st HTTP/1.1\r\nHost: 127.0.0.1:8787\r\n\r\n"
        let callbackURL = IOSLoopbackOAuthRequest.callbackURL(
            from: callback,
            port: Int(IOSGrokOAuthConstants.loopbackPort),
            pathPrefix: IOSGrokOAuthConstants.callbackPath
        )
        XCTAssertEqual(callbackURL?.host, "127.0.0.1")
        XCTAssertEqual(callbackURL?.path, "/callback")
        XCTAssertEqual(
            URLComponents(url: callbackURL!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "code" }?.value,
            "abc"
        )
        XCTAssertNil(
            IOSLoopbackOAuthRequest.callbackURL(
                from: "GET /oauth/callback?code=abc HTTP/1.1\r\n\r\n",
                port: 8787,
                pathPrefix: "/callback"
            )
        )
    }

    func testGrokOAuthTokenStoreRoundTripAndSignsInWithoutSSOCookie() {
        let id = KotlinUuid.companion.random()
        let providerId = id.description()
        defer {
            IOSGrokOAuthAuthStore.clear(providerId: providerId)
            IOSGrokWebAuthStore.clear(providerId: providerId)
        }
        XCTAssertNil(IOSGrokOAuthAuthStore.load(providerId: providerId))

        let backup = IOSGrokWebProviderBackup(
            baseUrl: "https://api.x.ai/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false
        )
        let tokens = IOSGrokOAuthTokens(
            accessToken: "at",
            refreshToken: "rt",
            expiresAtMillis: 1_800_000_000_000,
            idToken: nil,
            email: "user@example.com",
            providerBackup: backup
        )
        XCTAssertTrue(IOSGrokOAuthAuthStore.save(providerId: providerId, tokens: tokens))
        XCTAssertEqual(IOSGrokOAuthAuthStore.load(providerId: providerId), tokens)
        XCTAssertEqual(IOSGrokOAuthAuthStore.load(providerId: providerId)?.providerBackup?.baseUrl, "https://api.x.ai/v1")

        let provider = ProviderSetting.OpenAI(
            id: id,
            enabled: true,
            name: "xAI",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "",
            baseUrl: IOSGrokWebConstants.webBaseUrl,
            chatCompletionsPath: "/conversations/new",
            useResponseApi: true,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        XCTAssertTrue(IOSGrokWebProviderResolver.isSignedIn(provider))

        IOSGrokOAuthAuthStore.clear(providerId: providerId)
        XCTAssertNil(IOSGrokOAuthAuthStore.load(providerId: providerId))
        XCTAssertFalse(IOSGrokWebProviderResolver.isSignedIn(provider))
    }

    func testGrokOAuthResolvedRewritesToCliProxyAndAttachesIdentityHeaders() async throws {
        let id = KotlinUuid.companion.random()
        let providerId = id.description()
        defer { IOSGrokOAuthAuthStore.clear(providerId: providerId) }

        let tokens = IOSGrokOAuthTokens(
            accessToken: "grok-access",
            refreshToken: "grok-refresh",
            expiresAtMillis: 3_000_000_000_000,
            idToken: nil,
            email: "grok@example.com",
            providerBackup: nil
        )
        XCTAssertTrue(IOSGrokOAuthAuthStore.save(providerId: providerId, tokens: tokens))

        let original = ProviderSetting.OpenAI(
            id: id,
            enabled: true,
            name: "xAI",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "",
            baseUrl: IOSGrokWebConstants.webBaseUrl,
            chatCompletionsPath: "/conversations/new",
            useResponseApi: true,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        XCTAssertTrue(IOSGrokWebProviderResolver.isGrokWebConfiguration(original))
        XCTAssertFalse(IOSGrokWebProviderResolver.isGrokCliProxyConfiguration(original))

        let resolved = try await IOSGrokWebProviderResolver.resolved(original)
        let openAI = try XCTUnwrap(resolved as? ProviderSetting.OpenAI)
        XCTAssertEqual(openAI.apiKey, "grok-access")
        XCTAssertEqual(openAI.baseUrl, IOSGrokWebConstants.cliProxyBaseUrl)
        XCTAssertTrue(openAI.useResponseApi)
        XCTAssertTrue(IOSGrokWebProviderResolver.isGrokCliProxyConfiguration(openAI))
        XCTAssertFalse(IOSGrokWebProviderResolver.isGrokWebConfiguration(openAI))
        XCTAssertTrue(IOSGrokWebProviderResolver.isXAIProvider(openAI))

        let model = Model(
            modelId: "grok-4.6",
            displayName: "Grok 4.6",
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
        let params = IOSGrokWebProviderResolver.augmentParamsForGrok(
            TextGenerationParams(
                model: model,
                temperature: nil,
                topP: nil,
                maxTokens: nil,
                tools: [],
                reasoningLevel: ReasoningLevel.off,
                customHeaders: [],
                customBody: []
            ),
            provider: openAI
        )
        let headers = Dictionary(uniqueKeysWithValues: params.customHeaders.map { ($0.name, $0.value) })
        XCTAssertEqual(headers["x-grok-client-identifier"], "grok-shell")
        XCTAssertEqual(headers["x-grok-client-version"], IOSGrokCliProxyIdentity.clientVersion)
        XCTAssertEqual(headers["X-XAI-Token-Auth"], "xai-grok-cli")
        XCTAssertEqual(headers["x-authenticateresponse"], "authenticate-response")
        XCTAssertEqual(headers["x-grok-model-override"], "grok-4.6")
        XCTAssertEqual(IOSGrokWebConstants.fallbackModels.first?.modelId, "grok-4.6")
    }

    func testGrokCliProxyModelListParserReadsOpenAIShape() {
        let json = """
        {"data":[{"id":"grok-4.6","name":"Grok 4.6"},{"id":"grok-build"}]}
        """.data(using: .utf8)!
        let models = IOSGrokOAuthClient.parseModels(json)
        XCTAssertEqual(models.map(\.modelId), ["grok-4.6", "grok-build"])
        XCTAssertEqual(models.first?.displayName, "Grok 4.6")
        XCTAssertEqual(models.last?.displayName, "grok-build")
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

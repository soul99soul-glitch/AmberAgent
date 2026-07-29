import XCTest
import SwiftUI
@testable import iosApp

final class IOSSettingsWiringTests: XCTestCase {
    private func source(_ relativePath: String) throws -> String {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let iosAppRoot = testsDir.deletingLastPathComponent()
        let fileURL = iosAppRoot.appendingPathComponent(relativePath)
        return try String(contentsOf: fileURL, encoding: .utf8)
    }

    func testRenderingInteractionSectionOnlyExposesFollowGenerationToggle() throws {
        let settings = try source("iosApp/DisplayFontSettingsView.swift")

        // 渲染/滚动相关的实验开关已全部 hardcode 默认开启，设置页不再暴露；
        // 交互区只保留「生成时跟随滚动」一个用户开关。
        XCTAssertTrue(settings.contains("DisplayToggleRow(title: \"生成时跟随滚动\", isOn: followGeneration)"))
        XCTAssertFalse(settings.contains("@AppStorage(NativeChatTimelineStaticRenderFeatureFlags.key)"))
        XCTAssertFalse(settings.contains("@AppStorage(NativeChatTimelineStreamingTailFeatureFlags.key)"))
        XCTAssertFalse(settings.contains("@AppStorage(NativeTimelineScrollFeatureFlags.key)"))
        XCTAssertFalse(settings.contains("nativeTimelineScrollDriver.toggle()"))
        XCTAssertFalse(settings.contains("setNativeChatTimelineEnabled"))
    }

    func testNovelCreationAdvancedEntryOpensFeatureAndSharedSettingsPersistRoleDefaults() throws {
        let home = try source("iosApp/PlaceholderViews.swift")
        let shell = try source("iosApp/AppShell.swift")
        let settings = try source("iosApp/NovelCreation/NovelCreationSettingsView.swift")

        XCTAssertTrue(home.contains("title: \"小说创作\""))
        XCTAssertTrue(home.contains("color: AmberTheme.accentIndigo, route: .novelCreation)"))
        XCTAssertFalse(home.contains("route: .novelCreationSettings"))
        XCTAssertTrue(shell.contains("case novelCreationSettings"))
        XCTAssertFalse(shell.contains("NovelSettingsTransitionSource"))
        XCTAssertTrue(shell.contains("NovelCreationSettingsView("))
        XCTAssertTrue(settings.contains("创作模型"))
        XCTAssertTrue(settings.contains("剧情同步模型"))
        XCTAssertTrue(settings.contains("preferences.set(policy, for: purpose)"))
        XCTAssertTrue(settings.contains("NovelProjectManagementView("))
        XCTAssertFalse(settings.contains("projectID:"))
    }

    func testChatSendDismissesTheComposerKeyboardBeforeStartingGeneration() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let start = try XCTUnwrap(chatView.range(of: "private func sendComposerMessage()"))
        let end = try XCTUnwrap(
            chatView.range(of: "private func openComposerModelSheet()", range: start.upperBound..<chatView.endIndex)
        )
        let sendBody = chatView[start.lowerBound..<end.lowerBound]

        XCTAssertTrue(sendBody.contains("dismissKeyboard()"))
        XCTAssertLessThan(
            try XCTUnwrap(sendBody.range(of: "dismissKeyboard()")?.lowerBound),
            try XCTUnwrap(sendBody.range(of: "viewModel.sendMessage()")?.lowerBound)
        )
    }

    func testChatComposerUsesTheViewModelSendGateAndKeepsStopForTheCurrentRun() throws {
        let chat = try source("iosApp/ChatView.swift")

        XCTAssertTrue(chat.contains("viewModel.composerSendBlockReason(for: text) == nil"))
        XCTAssertTrue(chat.contains("isLoading: isCurrentConversationRunActive"))
        XCTAssertTrue(chat.contains("viewModel.isGenerationActiveForCurrentConversation"))
        XCTAssertTrue(chat.contains("viewModel.cancelGeneration()"))
    }

    func testPhotoPickerResultsStayOwnedByTheConversationThatStartedLoading() throws {
        let chat = try source("iosApp/ChatView.swift")

        XCTAssertTrue(chat.contains("let selectionConversationId = currentConversationIdString"))
        XCTAssertTrue(chat.contains("guard selectionConversationId == currentConversationIdString"))
        XCTAssertTrue(chat.contains("failedImageCount"))
        XCTAssertTrue(chat.contains("张图片处理失败"))
    }

    func testChatMessageListRendersNativeTimelineDirectlyWithoutRoutePolicy() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let list = try source("iosApp/ChatCollectionMessageList.swift")

        // native timeline 已 hardcode 为唯一 Chat 列表路径：route 判定层（route enum /
        // policy / eligibility / 开关 flag）整层退役，ChatView 直接渲染 NativeChatTimelineView。
        XCTAssertTrue(chatView.contains("NativeChatTimelineView("))
        XCTAssertFalse(chatView.contains("ChatMessageListRoutePolicy.route"))
        XCTAssertFalse(chatView.contains("swiftUICleanListEnabled"))
        XCTAssertFalse(chatView.contains("@AppStorage(NativeChatTimelineStaticRenderFeatureFlags.key)"))
        XCTAssertFalse(chatView.contains("@AppStorage(NativeChatTimelineStreamingTailFeatureFlags.key)"))
        XCTAssertFalse(list.contains("enum ChatMessageListRoutePolicy"))
        XCTAssertFalse(list.contains("NativeChatTimelineRoutePolicy.shouldUseNativeTimeline"))
    }

    func testNativeTimelineScrollDriverIsHardcodedOnAndConsumedByNativeTimelineView() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let list = try source("iosApp/ChatCollectionMessageList.swift")

        // 原生滚动 driver 已 hardcode 开启，不再由设置开关驱动。
        XCTAssertTrue(chatView.contains("nativeScrollDriverEnabled: true"))
        XCTAssertFalse(chatView.contains("@AppStorage(NativeTimelineScrollFeatureFlags.key)"))
        XCTAssertTrue(list.contains("@State private var scrollDriver = NativeTimelineScrollDriver()"))
        XCTAssertTrue(list.contains("var nativeScrollDriverEnabled: Bool"))
        XCTAssertTrue(list.contains("scrollDriver.attach(scrollView)"))
        XCTAssertTrue(list.contains("hasMeasuredNativeScrollGeometry && !viewportState.isAtBottom"))
        XCTAssertTrue(list.contains("driverPausedForUser: isNativeScrollDriverActive && scrollDriver.isPausedForUser"))
        XCTAssertTrue(list.contains("scrollDriver.submit(.streamContentGrew)"))
    }

    func testNativeTimelineScrollDriverIsSharedByEveryStreamingConversationSurface() throws {
        let driver = try source("iosApp/NativeTimelineScrollDriver.swift")
        let chat = try source("iosApp/ChatCollectionMessageList.swift")
        let council = try source("iosApp/CouncilChatRuntimeView.swift")
        let novel = try source("iosApp/NovelCreation/NovelSessionView.swift")

        XCTAssertTrue(driver.contains("struct NativeTimelineScrollViewResolver"))
        for surface in [chat, council, novel] {
            XCTAssertTrue(surface.contains("@State private var scrollDriver = NativeTimelineScrollDriver()"))
            XCTAssertTrue(surface.contains("NativeTimelineScrollViewResolver("))
            XCTAssertTrue(surface.contains("scrollDriver.submit(.streamContentGrew)"))
            XCTAssertTrue(surface.contains("scrollDriver.submit(.userDragBegan)"))
            XCTAssertTrue(surface.contains("scrollDriver.submit(.userDragEnded(isAtBottom:"))
            XCTAssertTrue(surface.contains("scrollDriver.setAutomaticFollowEnabled("))
            XCTAssertTrue(surface.contains("NativeTimelineScrollReturnPolicy.returnedToBottom("))
            XCTAssertTrue(surface.contains("isNativeScrollSurfaceVisible"))
        }
    }

    /// 需求「三界面共享流式基础设施」的机制门禁。
    ///
    /// 正文渲染只能有一个入口 `ChatAssistantMarkdownView`：Chat / 议会 / 小说会话
    /// 全部经由它，所以渲染层的改动（合并渲染、逐词淡入、缓存策略）落一次即三界面同享。
    /// 若有人新开一条绕过它的渲染路径，这条会红。
    func testAssistantProseRenderingIsSharedByEveryStreamingConversationSurface() throws {
        let bubble = try source("iosApp/MessageBubbleView.swift")
        let council = try source("iosApp/CouncilChatRuntimeView.swift")
        let novel = try source("iosApp/NovelCreation/NovelSessionBubble.swift")

        XCTAssertTrue(bubble.contains("struct ChatAssistantMarkdownView"))
        for surface in [council, novel] {
            XCTAssertTrue(surface.contains("ChatAssistantMarkdownView("))
            // 各界面必须带自己的缓存命名空间，避免 renderable 身份缓存跨界面串味。
            XCTAssertTrue(surface.contains("renderCacheNamespace:"))
        }
        // 渲染 config 只允许在这一处构造；合并渲染开关也只在这里接入。
        XCTAssertEqual(
            bubble.components(separatedBy: ".withCoalescesAdjacentTextBlocks(").count - 1,
            1,
            "合并渲染开关必须只在共享的 streamingMarkdownConfig 里接入"
        )
    }

    /// 呈现节奏（每拍推进多少字符）在 Chat 与小说之间必须是同一份策略，
    /// 而不是两份「注释声称同构」的副本——改一边不会让另一边变红是历史事故来源。
    func testChatAndNovelShareTheSameStreamPresentationPacingPolicy() {
        for backlog in [0, 1, 11, 12, 13, 200, 1_024, 4_000, 100_000] {
            XCTAssertEqual(
                ChatStreamPresentationPacer.textAdvance(backlogCount: backlog),
                NovelSessionPresentationPacer.textAdvance(backlogCount: backlog),
                "backlog=\(backlog) 时两界面推进量必须一致"
            )
            XCTAssertEqual(
                ChatStreamPresentationPacer.textAdvance(backlogCount: backlog),
                StreamPresentationPacingPolicy.textAdvance(backlogCount: backlog)
            )
        }
        XCTAssertEqual(StreamPresentationPacingPolicy.textAdvance(backlogCount: 0), 0)
        XCTAssertEqual(StreamPresentationPacingPolicy.textAdvance(backlogCount: 1), 12, "轻积压落到下限")
        XCTAssertEqual(StreamPresentationPacingPolicy.textAdvance(backlogCount: 100_000), 64, "大积压封顶")
    }

    func testChatTopBarUsesStableControlDimensions() {
        XCTAssertEqual(ChatTopBarLayout.controlsHeight, 54)
        XCTAssertEqual(ChatTopBarLayout.toolbarButtonDiameter, 38)
    }

    @MainActor
    func testChatTitleIslandUsesIntrinsicWidthUpToItsCollisionLimit() {
        let shortHost = UIHostingController(
            rootView: ChatActivityIslandView(state: .conversationTitle("Amber"))
        )
        let longHost = UIHostingController(
            rootView: ChatActivityIslandView(state: .conversationTitle("两千字小文请求"))
        )
        let proposal = CGSize(width: 393, height: 100)
        let shortWidth = shortHost.sizeThatFits(in: proposal).width
        let longWidth = longHost.sizeThatFits(in: proposal).width

        XCTAssertLessThan(shortWidth, 150)
        XCTAssertGreaterThan(longWidth, shortWidth + 40)
        XCTAssertLessThanOrEqual(longWidth, 230.5)
    }

    func testCustomTopBarsUseNativeSoftEdgesAndLiquidGlassControls() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let activityIsland = try source("iosApp/ChatActivityIslandView.swift")
        let composer = try source("iosApp/ChatComposerViews.swift")
        let feedback = try source("iosApp/PlaceholderViews.swift")
        let chat = try source("iosApp/ChatCollectionMessageList.swift")
        let appearance = try source("iosApp/AppearanceSettingsView.swift")
        let swiftUISoftEdgeCount = chat.components(
            separatedBy: ".scrollEdgeEffectStyle(.soft, for: .top)"
        ).count - 1

        XCTAssertGreaterThanOrEqual(swiftUISoftEdgeCount, 2)
        XCTAssertTrue(chat.contains("collectionView.topEdgeEffect.style = .soft"))
        XCTAssertTrue(appearance.contains(".scrollEdgeEffectStyle(.soft, for: .top)"))
        XCTAssertFalse(chatView.contains("ChatTopEdgeFadeMaterial"))
        XCTAssertFalse(chatView.contains("bottomExtension"))
        XCTAssertTrue(
            chatView.contains(".frame(height: ChatTopBarLayout.controlsHeight, alignment: .bottom)")
        )
        XCTAssertFalse(chatView.contains("edgeEffectTail"))
        XCTAssertFalse(chatView.contains("edgeEffectHeight"))
        XCTAssertTrue(chatView.contains("GlassEffectContainer(spacing: 12)"))
        XCTAssertTrue(composer.contains(".foregroundStyle(Color(uiColor: .label))"))
        XCTAssertTrue(composer.contains(".symbolRenderingMode(.monochrome)"))
        XCTAssertTrue(chatView.contains("topBarGlyphOverlay"))
        XCTAssertTrue(chatView.contains("showsGlyph: false"))
        XCTAssertGreaterThanOrEqual(
            chatView.components(separatedBy: "ZStack(alignment: .bottom)").count - 1,
            2
        )
        XCTAssertTrue(chatView.contains("isBackToolbarButtonPressed"))
        XCTAssertTrue(chatView.contains("isNewChatToolbarButtonPressed"))
        XCTAssertTrue(chatView.contains(".scaleEffect(isPressed ? 0.9 : 1)"))
        XCTAssertTrue(composer.contains("onPressChanged: onPressChanged"))
        XCTAssertTrue(feedback.contains("onPressChanged?(isEnabled && isPressed)"))
        XCTAssertTrue(activityIsland.contains(".glassEffect(.regular, in: Capsule())"))
        XCTAssertTrue(activityIsland.contains("Capsule().fill(.ultraThinMaterial)"))
    }

    func testStreamingMarkdownRendererSelectionPrefersBlockOverStable() {
        // 渲染器已简化为 block / stable 两态；liyanan / microsoft / fade 实验路径全部退役。
        XCTAssertEqual(
            ChatMarkdownRendererPolicy.selection(blockRendererEnabled: true),
            .block
        )
        XCTAssertEqual(
            ChatMarkdownRendererPolicy.selection(blockRendererEnabled: false),
            .stable
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

    func testChatAmbientAnimationsReadReduceMotionAtTheirOwningViews() throws {
        let composer = try source("iosApp/ChatComposerViews.swift")
        let messageSupport = try source("iosApp/ChatMessageListSupport.swift")
        let misc = try source("iosApp/ChatMiscViews.swift")

        XCTAssertTrue(composer.contains("struct ContextRingButton: View"))
        XCTAssertTrue(composer.contains("@Environment(\\.accessibilityReduceMotion) private var reduceMotion"))
        XCTAssertTrue(messageSupport.contains("struct TypingDots: View"))
        XCTAssertTrue(messageSupport.contains("reduceMotion ? 0.55"))
        XCTAssertTrue(misc.contains("struct VisionRecognitionIndicator: View"))
        XCTAssertTrue(misc.contains("guard !reduceMotion else"))
    }

    func testChatApprovalAndAttachmentControlsHaveRealFortyFourPointHitLayout() throws {
        let chat = try source("iosApp/ChatView.swift")
        let approvals = try source("iosApp/MemoryToolApprovalCard.swift")

        XCTAssertTrue(chat.contains(".frame(width: 44, height: 44)"))
        XCTAssertGreaterThanOrEqual(
            approvals.components(separatedBy: ".chatApprovalHitTarget()").count - 1,
            16
        )
        XCTAssertTrue(approvals.contains("minHeight: 44"))
        XCTAssertFalse(approvals.contains("Text(request.question)\n                .font(.footnote)\n                .foregroundStyle(AmberTheme.foreground2)\n                .lineLimit(6)"))
    }

    func testChatBodyTypographyCombinesDynamicTypeWithTheAppFontScale() throws {
        let support = try source("iosApp/ChatMessageListSupport.swift")
        let bubble = try source("iosApp/MessageBubbleView.swift")

        XCTAssertGreaterThanOrEqual(
            support.components(separatedBy: "@ScaledMetric(relativeTo: .body)").count - 1,
            2
        )
        XCTAssertTrue(support.contains("scaledBodyPointSize * boundedScale"))
        XCTAssertTrue(bubble.contains("@ScaledMetric(relativeTo: .body) private var scaledBodyPointSize: CGFloat = 17"))
        XCTAssertTrue(bubble.contains("bodyPointSize: scaledBodyPointSize"))
    }

    /// 长文合并渲染已 hardcode 开启，必须接到三界面共用的那一处 config 构造点
    /// （`streamingMarkdownConfig`）；config 记忆化的键必须与 config 同值，设置页不再暴露开关。
    func testCoalescedTextBlocksIsHardcodedIntoTheSharedMarkdownConfig() throws {
        let settings = try source("iosApp/DisplayFontSettingsView.swift")
        let bubble = try source("iosApp/MessageBubbleView.swift")

        XCTAssertFalse(settings.contains("@AppStorage(IOSDisplayPreferenceKeys.coalescedTextBlocks)"))
        XCTAssertFalse(settings.contains("长文正文合并渲染"))

        XCTAssertTrue(bubble.contains(".withCoalescesAdjacentTextBlocks(value: true)"))
        // config 记忆化的键必须与 config 本身同值（true），否则 cache 命中却行为不一致。
        XCTAssertTrue(bubble.contains("coalescedTextBlocks: true"))
    }

    func testTableBlockRendererRunsUnconditionallyAndConsumesTableDetection() throws {
        let settings = try source("iosApp/DisplayFontSettingsView.swift")
        let bubble = try source("iosApp/MessageBubbleView.swift")

        // 表格流式块渲染已 hardcode 无条件参与竞争，设置开关与 block guard 均已移除。
        XCTAssertFalse(settings.contains("@AppStorage(IOSDisplayPreferenceKeys.streamingBlockMarkdown)"))
        XCTAssertFalse(bubble.contains("guard streamingBlockMarkdown else { return false }"))

        XCTAssertTrue(bubble.contains("ChatStreamingMarkdownBlockParser.containsTable"))
        XCTAssertTrue(bubble.contains("text: table.markdown"))
        XCTAssertTrue(bubble.contains("cacheIdentity: renderCacheNamespace.map"))
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

    func testSystemDynamicIslandUsesBoundedLiveStatusAndSharedChatStageCopy() throws {
        let widget = try source("ActivityWidget/AmberAgentActivityWidget.swift")
        let chat = try source("iosApp/ChatView.swift")
        let coordinator = try source("iosApp/ChatGenerationCoordinator.swift")
        let compactStart = try XCTUnwrap(widget.range(of: "} compactTrailing: {"))
        let compactSuffix = String(widget[compactStart.lowerBound...])
        let compactEnd = try XCTUnwrap(compactSuffix.range(of: "} minimal: {"))
        let compactBlock = String(compactSuffix[..<compactEnd.lowerBound])

        XCTAssertTrue(
            widget.contains("Text(presentation.displayStage(isStale: isStale).compactTitle)")
        )
        XCTAssertFalse(compactBlock.contains("style: .timer"))
        XCTAssertTrue(widget.contains("if presentation.kind != .response"))
        XCTAssertTrue(chat.contains("title: AgentActivityStage.preparing.title"))
        XCTAssertTrue(chat.contains("title: AgentActivityStage.thinking.title"))
        XCTAssertTrue(chat.contains("title: AgentActivityStage.generating.title"))
        XCTAssertEqual(
            coordinator.components(
                separatedBy: "AgentActivityResponseStagePolicy.initialStage"
            ).count - 1,
            1,
            "工具或审批恢复后应继续「生成回复」，不应再冒充首次连接"
        )
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

    func testGrokWebLoginRequiresNonEmptySSOCookie() {
        let analytics = makeCookie(name: "analytics", value: "present")
        let sso = makeCookie(name: "sso", value: "session-token")

        XCTAssertNil(IOSGrokWebCookieValidator.ssoCookieHeader(from: [analytics]))
        XCTAssertEqual(
            IOSGrokWebCookieValidator.ssoCookieHeader(from: [analytics, sso]),
            "sso=session-token"
        )
        XCTAssertFalse(IOSGrokWebCookieValidator.hasSSOCookie(in: "analytics=present"))
        XCTAssertTrue(IOSGrokWebCookieValidator.hasSSOCookie(in: "analytics=present; sso=session-token"))
    }

    func testGrokWebRuntimeRestoresReadAndWriteCookiesFromSavedSSO() throws {
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

    func testGrokWebStreamParserKeepsTokenFromTerminalFrame() {
        let line = #"{"result":{"response":{"token":"final","isThinking":false,"finalMetadata":{"done":true}}}}"#

        XCTAssertEqual(
            IOSGrokWebStreamParser.parse(line),
            IOSGrokWebStreamFrame(token: "final", isFinished: true, errorMessage: nil)
        )
    }

    func testGrokWebClientClosesTransportWhenTerminalFrameArrives() throws {
        let provider = try source("iosApp/IOSGrokWebProvider.swift")

        XCTAssertTrue(provider.contains("return frame.isFinished"))
    }

    func testProviderConnectionTestWaitsForTheModelRequestResult() throws {
        let detail = try source("iosApp/ProviderDetailView.swift")

        XCTAssertFalse(
            detail.contains("连接测试已发起；模型获取结果会显示在模型页。"),
            "Starting an async request is not a successful connection result."
        )
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

        XCTAssertTrue(settings.contains("测试当前议会模型"))
        XCTAssertTrue(settings.contains("IOSCouncilModelConnectivityTester"))
        XCTAssertTrue(settings.contains("实际回退"))
    }

    func testGrokWebStreamParserSurfacesProviderError() {
        let line = #"data: {"error":{"message":"session expired"}}"#

        XCTAssertEqual(
            IOSGrokWebStreamParser.parse(line),
            IOSGrokWebStreamFrame(token: nil, isFinished: true, errorMessage: "session expired")
        )
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

    func testInfoPlistOptsIntoProMotionFrameRates() throws {
        let testsDir = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        let plistURL = testsDir.deletingLastPathComponent().appendingPathComponent("iosApp/Info.plist")
        let data = try Data(contentsOf: plistURL)
        let plist = try XCTUnwrap(
            PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any]
        )

        XCTAssertEqual(plist["CADisableMinimumFrameDurationOnPhone"] as? Bool, true)
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

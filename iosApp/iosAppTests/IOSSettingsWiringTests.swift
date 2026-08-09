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
        XCTAssertFalse(chatView.contains("NativeChatTimelineMirror"))
        XCTAssertFalse(chatView.contains("recordNativeTimelineMirrorIfEnabled"))
        XCTAssertFalse(projection.contains("NativeTimelineMirrorInput"))
        XCTAssertFalse(projection.contains("NativeChatTimelineMirror"))
    }

    func testNativeTimelineScrollDriverHasNoRetiredEnableParameterAndIsConsumedByNativeTimelineView() throws {
        let chatView = try source("iosApp/ChatView.swift")
        let list = try source("iosApp/ChatCollectionMessageList.swift")
        let driver = try source("iosApp/NativeTimelineScrollDriver.swift")

        // 原生滚动 driver 已是唯一默认 owner，不再保留恒为 true 的迁移参数。
        XCTAssertFalse(chatView.contains("nativeScrollDriverEnabled:"))
        XCTAssertFalse(chatView.contains("@AppStorage(NativeTimelineScrollFeatureFlags.key)"))
        XCTAssertTrue(list.contains("@State private var scrollDriver = NativeTimelineScrollDriver()"))
        XCTAssertFalse(list.contains("var nativeScrollDriverEnabled: Bool"))
        XCTAssertTrue(list.contains("scrollDriver.attach(scrollView)"))
        XCTAssertFalse(list.contains("hasMeasuredNativeScrollGeometry"))
        XCTAssertTrue(driver.contains("var isUIKitUserInteracting: Bool"))
        XCTAssertTrue(list.contains("Self.shouldBeginNativeUserDrag("))
        XCTAssertTrue(list.contains("driverPausedForUser: isNativeScrollDriverActive && scrollDriver.isPausedForUser"))
        XCTAssertTrue(list.contains("scrollDriver.submit(.streamContentGrew)"))
    }

    func testNativeTimelineLiveTailEqualityTracksVisualConfiguration() throws {
        let list = try source("iosApp/ChatCollectionMessageList.swift")
        let start = try XCTUnwrap(list.range(of: "static func == (lhs: NativeTimelineMessageBubble"))
        let end = try XCTUnwrap(
            list.range(of: "private var usesLiveTail", range: start.upperBound..<list.endIndex)
        )
        let equality = list[start.lowerBound..<end.lowerBound]

        XCTAssertTrue(equality.contains("lhs.displaySettingSignature == rhs.displaySettingSignature"))
        XCTAssertTrue(equality.contains("lhs.generativeUiSettingSignature == rhs.generativeUiSettingSignature"))
        XCTAssertTrue(equality.contains("lhs.reasoningLevelLabel == rhs.reasoningLevelLabel"))
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
        XCTAssertEqual(StreamPresentationPacingPolicy.textAdvance(backlogCount: 100_000), 36, "大积压封顶")
    }

    func testChatTopBarUsesStableControlDimensions() {
        XCTAssertEqual(ChatTopBarLayout.controlsHeight, 54)
        XCTAssertEqual(ChatTopBarLayout.toolbarButtonDiameter, 38)
        XCTAssertEqual(ChatTopBarLayout.softEdgeExtension, 36)
    }

    @MainActor
    func testChatToolbarButtonsExposeFortyFourPointHitTarget() {
        let host = UIHostingController(rootView: ChatToolbarIconButton(
            systemImage: "chevron.left",
            accessibilityLabel: "返回",
            size: ChatTopBarLayout.toolbarButtonDiameter,
            symbolSize: 16,
            action: {}
        ))
        let size = host.sizeThatFits(in: CGSize(width: 100, height: 100))

        XCTAssertGreaterThanOrEqual(size.width, 44)
        XCTAssertGreaterThanOrEqual(size.height, 44)
    }

    func testComposerReasoningLabelsAreChinesePresentationCopy() {
        XCTAssertEqual(
            ComposerReasoningOption.allCases.map(\.title),
            ["关闭", "自动", "低", "中", "高", "极高", "最高"]
        )
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

    @MainActor
    func testChatActivityIslandKeepsOneStableHeightAcrossStates() {
        let states: [ChatActivityIslandState] = [
            .conversationTitle("Amber"),
            .activity(
                kind: .thinking,
                title: "正在思考",
                detail: "高",
                systemImage: "brain.head.profile",
                tint: .amber
            ),
            .activity(
                kind: .tool,
                title: "正在搜索 巫师 3 来来来",
                detail: "关键词：巫师 3 来来来",
                systemImage: "magnifyingglass",
                tint: .green
            ),
            .activity(
                kind: .generating,
                title: "正在生成回复",
                systemImage: "text.bubble",
                tint: .accent
            )
        ]
        let proposal = CGSize(width: 393, height: 100)
        let heights = states.map {
            UIHostingController(rootView: ChatActivityIslandView(state: $0))
                .sizeThatFits(in: proposal)
                .height
        }

        guard let minimumHeight = heights.min(),
              let maximumHeight = heights.max(),
              let firstHeight = heights.first else {
            return XCTFail("活动岛状态样本不能为空")
        }

        XCTAssertEqual(maximumHeight, minimumHeight, accuracy: 0.5)
        XCTAssertEqual(firstHeight, 40, accuracy: 0.5)
    }

    func testChatActivityIslandUsesOneStableVisualContentIdentity() throws {
        let activityIsland = try source("iosApp/ChatActivityIslandView.swift")

        XCTAssertTrue(activityIsland.contains("private var islandContent: some View"))
        XCTAssertFalse(activityIsland.contains("private var activeContent: some View"))
        XCTAssertFalse(activityIsland.contains("private var titleContent: some View"))
        XCTAssertFalse(activityIsland.contains(".id(state.contentKey)"))
        XCTAssertFalse(activityIsland.contains(".opacity(presentation.isSettling ? 0 : 1)"))
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
        // 用透明延伸拉高 safeAreaBar，让原生 soft edge 盖住返回/标题；禁止再叠自定义 fade 材质。
        XCTAssertTrue(chatView.contains("ChatTopBarLayout.softEdgeExtension"))
        XCTAssertFalse(chatView.contains("edgeEffectTail"))
        XCTAssertFalse(chatView.contains("edgeEffectHeight"))
        XCTAssertFalse(chatView.contains("GlassEffectContainer(spacing: 12)"))
        XCTAssertTrue(composer.contains(".foregroundStyle(Color(uiColor: .label))"))
        XCTAssertTrue(composer.contains(".symbolRenderingMode(.monochrome)"))
        XCTAssertFalse(chatView.contains("topBarGlyphOverlay"))
        XCTAssertFalse(chatView.contains("showsGlyph: false"))
        XCTAssertFalse(chatView.contains("isBackToolbarButtonPressed"))
        XCTAssertFalse(chatView.contains("isNewChatToolbarButtonPressed"))
        XCTAssertTrue(chatView.contains("ChatToolbarIconButton("))
        XCTAssertTrue(composer.contains("Button(action: action)"))
        XCTAssertTrue(composer.contains("Image(systemName: systemImage)"))
        XCTAssertTrue(composer.contains("circleGlass"))
        XCTAssertFalse(composer.contains("var showsGlyph"))
        XCTAssertFalse(composer.contains("var onPressChanged"))
        XCTAssertFalse(feedback.contains("onPressChanged?(isEnabled && isPressed)"))
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

    func testSystemDynamicIslandUsesSingleAlignedSummaryAndIntentionalStaticFallback() throws {
        let widget = try source("ActivityWidget/AmberAgentActivityWidget.swift")
        let islandStart = try XCTUnwrap(widget.range(of: "DynamicIsland {"))
        let islandSuffix = String(widget[islandStart.lowerBound...])
        let islandEnd = try XCTUnwrap(islandSuffix.range(of: "private struct AgentActivityCompactStatus"))
        let islandBlock = String(islandSuffix[..<islandEnd.lowerBound])
        let compactLeadingStart = try XCTUnwrap(widget.range(of: "} compactLeading: {"))
        let compactLeadingSuffix = String(widget[compactLeadingStart.lowerBound...])
        let compactLeadingEnd = try XCTUnwrap(compactLeadingSuffix.range(of: "} compactTrailing: {"))
        let compactLeadingBlock = String(compactLeadingSuffix[..<compactLeadingEnd.lowerBound])
        let compactStart = try XCTUnwrap(widget.range(of: "} compactTrailing: {"))
        let compactSuffix = String(widget[compactStart.lowerBound...])
        let compactEnd = try XCTUnwrap(compactSuffix.range(of: "} minimal: {"))
        let compactBlock = String(compactSuffix[..<compactEnd.lowerBound])
        let minimalStart = try XCTUnwrap(widget.range(of: "} minimal: {"))
        let minimalSuffix = String(widget[minimalStart.lowerBound...])
        let minimalEnd = try XCTUnwrap(minimalSuffix.range(of: ".widgetURL("))
        let minimalBlock = String(minimalSuffix[..<minimalEnd.lowerBound])
        let orbStart = try XCTUnwrap(widget.range(of: "private struct AgentActivityIslandOrb"))
        let orbSuffix = String(widget[orbStart.lowerBound...])
        let orbEnd = try XCTUnwrap(orbSuffix.range(of: "private enum AgentActivityIslandOrbMapping"))
        let orbBlock = String(orbSuffix[..<orbEnd.lowerBound])

        XCTAssertEqual(
            islandBlock.components(separatedBy: "DynamicIslandExpandedRegion(.leading)").count - 1,
            0
        )
        XCTAssertEqual(
            islandBlock.components(separatedBy: "DynamicIslandExpandedRegion(.center)").count - 1,
            0
        )
        XCTAssertEqual(
            islandBlock.components(separatedBy: "DynamicIslandExpandedRegion(.trailing)").count - 1,
            0
        )
        XCTAssertFalse(islandBlock.contains("DynamicIslandExpandedRegion(.trailing, priority:"))
        XCTAssertEqual(
            islandBlock.components(separatedBy: "DynamicIslandExpandedRegion(.bottom)").count - 1,
            1
        )
        XCTAssertTrue(islandBlock.contains("HStack(alignment: .center, spacing: 12)"))
        XCTAssertTrue(islandBlock.contains("minHeight: 40"))
        XCTAssertTrue(islandBlock.contains("size: 40"))
        XCTAssertTrue(islandBlock.contains("animates: true"))
        XCTAssertTrue(islandBlock.contains("AgentActivityIslandHeadline("))
        XCTAssertTrue(islandBlock.contains("startedAt: context.attributes.startedAt"))
        XCTAssertTrue(islandBlock.contains("updatedAt: context.state.updatedAt"))
        XCTAssertFalse(islandBlock.contains("AgentActivityGlyph("))
        XCTAssertFalse(islandBlock.contains("AgentActivityExpandedFact("))
        XCTAssertFalse(islandBlock.contains("AgentActivityExpandedFooter("))
        XCTAssertFalse(islandBlock.contains(".amberAccent"))
        XCTAssertTrue(compactLeadingBlock.contains("animates: false"))
        XCTAssertTrue(minimalBlock.contains("animates: false"))
        XCTAssertTrue(orbBlock.contains("if displayPhase == .running, animates"))
        XCTAssertTrue(orbBlock.contains("presentation.displaySymbolName(isStale: isStale)"))
        XCTAssertTrue(orbBlock.contains(".white.opacity(displayPhase == .running ? 0.9 : 0.72)"))
        XCTAssertFalse(orbBlock.contains("displayPhase.widgetColor"))
        XCTAssertFalse(compactBlock.contains("style: .timer"))
        XCTAssertFalse(widget.contains(".activityBackgroundTint("))
        XCTAssertTrue(widget.contains(".keylineTint(.white.opacity(0.12))"))
        XCTAssertTrue(widget.contains("@Environment(\\.accessibilityReduceMotion)"))
        XCTAssertTrue(widget.contains("if isActive, !reduceMotion, !isLuminanceReduced"))
        XCTAssertTrue(widget.contains("agentActivityIslandAccessibilityLabel("))
        XCTAssertTrue(widget.contains("presentation.displayStage(isStale: isStale)"))
        XCTAssertTrue(widget.contains(
            "case .preparing, .waitingForConfirmation, .reconnecting, .stale:\n" +
            "            .listening"
        ))
        XCTAssertTrue(widget.contains("case .thinking:\n            .working"))
        XCTAssertTrue(widget.contains("case .generating:\n            .composing"))
        XCTAssertTrue(widget.contains("case .generatingImage:\n            .shaping"))
        XCTAssertTrue(widget.contains(".searching, .readingSources, .readingWeb"))
        XCTAssertTrue(widget.contains(".readingDocument, .updatingMemory, .runningTool, .organizing"))
        XCTAssertFalse(widget.contains("AgentActivityStaticOrb(stage: .searching"))
        XCTAssertTrue(widget.contains(".keyframeAnimator("))
        XCTAssertTrue(widget.contains("trigger: animationTrigger"))
        XCTAssertTrue(widget.contains("AgentActivityOrbAnimationTiming.duration("))
        XCTAssertTrue(widget.contains(
            "initialPhase + Double(AgentActivityOrbFrameCache.phases.count)"
        ))
        XCTAssertTrue(widget.contains("static let restingPhase = 0"))
        XCTAssertFalse(widget.contains("representativePhase"))
        XCTAssertFalse(widget.contains("AgentActivityOrbAnimationTiming.phaseAdvance("))
        XCTAssertFalse(widget.contains("static let animationDuration: TimeInterval = 0.8"))
        XCTAssertFalse(widget.contains("PhaseAnimator(AgentActivityOrbFrameCache.phases)"))
        XCTAssertFalse(widget.contains("private struct AgentActivityExpandedFact"))
        XCTAssertTrue(widget.contains("action.showsLockScreenLabel"))
        XCTAssertEqual(
            widget.components(separatedBy: ".widgetURL(").count - 1,
            2,
            "锁屏与灵动岛都必须保留整卡深链"
        )
        XCTAssertEqual(
            widget.components(separatedBy: "agentActivityHeadlineText(").count - 1,
            4,
            "展开岛、锁屏与无障碍摘要必须共用同一套主副标题语义"
        )
    }

    func testSystemActivityDefaultCopyIsChinese() throws {
        let copy = try source("iosApp/AgentActivity.strings")

        XCTAssertTrue(copy.contains(#""agent.activity.stage.thinking" = "正在思考";"#))
        XCTAssertTrue(copy.contains(#""agent.activity.stage.searching" = "正在搜索";"#))
        XCTAssertTrue(copy.contains(#""agent.activity.stage.generating" = "正在生成";"#))
        XCTAssertTrue(copy.contains(#""agent.activity.compact.thinking" = "正在思考";"#))
        XCTAssertTrue(copy.contains(#""agent.activity.compact.searching" = "正在搜索";"#))
        XCTAssertTrue(copy.contains(#""agent.activity.kind.workflow" = "智能任务";"#))
        XCTAssertFalse(copy.contains("Thinking"))
        XCTAssertFalse(copy.contains("Generating response"))
        XCTAssertFalse(copy.contains("Open conversation"))
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

        let finalizeStart = try XCTUnwrap(
            coordinator.range(of: "func finalizeSuspendedRunsIfNeeded()")
        )
        let finalizeEnd = try XCTUnwrap(
            coordinator.range(
                of: "private func persistExpirationFailure(",
                range: finalizeStart.upperBound..<coordinator.endIndex
            )
        )
        let finalize = coordinator[finalizeStart.lowerBound..<finalizeEnd.lowerBound]
        let legacyFinish = try XCTUnwrap(
            finalize.range(of: "finish(runId: job.runId, requestId: record.requestId)")
        )
        let persistenceTask = try XCTUnwrap(finalize.range(of: "Task { @MainActor in"))

        XCTAssertTrue(finalize.contains("BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: record.requestId)"))
        XCTAssertLessThan(legacyFinish.lowerBound, persistenceTask.lowerBound)
        XCTAssertFalse(finalize.contains("BGTaskScheduler.shared.submit"))
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

        XCTAssertTrue(misc.contains(".animation(reduceMotion ? nil : .easeInOut(duration: 0.28), value: showsBody)"))
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

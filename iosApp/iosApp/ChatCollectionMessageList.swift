import SwiftUI
import UIKit
import Shared
import ChatLayout
import Combine

enum ChatListAction {
    case regenerate(messageId: String)
    case requestEdit(messageId: String, currentText: String)
    case edit(messageId: String, newText: String)
    case delete(messageId: String)
    case selectVariant(messageId: String, variantIndex: Int)
    case generativeWidget(prompt: String)
    case modifyGeneratedImage(urlString: String, prompt: String, aspectRatio: String)
    case openMiniApp(appId: String)
    case openMiniApps
    case primaryConfiguration
    case modelDefaults
}

enum NativeChatTimelineSessionIdentity {
    static func viewID(conversationId: KotlinUuid?) -> String {
        guard let conversationId else { return "native-timeline:no-conversation" }
        return "native-timeline:\(String(describing: conversationId))"
    }
}

enum NativeStaticTimelineViewportPolicy {
    static func state(
        distanceToBottom: CGFloat,
        visibleHeight: CGFloat,
        contentHeight: CGFloat,
        hasMessages: Bool,
        userInteracting: Bool = false,
        driverPausedForUser: Bool = false
    ) -> ChatViewportState {
        let isScrollable = contentHeight > visibleHeight + ChatLayout.bottomStickThreshold
        let liveRenderingThreshold = max(
            ChatLayout.liveRenderingLODMinDistance,
            visibleHeight * ChatLayout.liveRenderingLODScreenFactor
        )
        var state = ChatViewportState()
        state.isAtBottom = distanceToBottom <= ChatLayout.bottomStickThreshold
        state.isContentScrollable = isScrollable
        state.liveRenderingFarFromBottom = distanceToBottom > liveRenderingThreshold
        state.showScrollToBottom = hasMessages && isScrollable && !state.isAtBottom
        state.userDragging = userInteracting
        state.followPaused = driverPausedForUser || (userInteracting && !state.isAtBottom)
        return state
    }
}

enum NativeStaticTimelineRendererMemory {
    static func nextStreamedMessageIDs(
        previous: Set<String>,
        event: ChatEvent,
        messages: [UIMessage]
    ) -> Set<String> {
        let currentIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
        switch event {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            return []
        default:
            var retained = previous.intersection(currentIDs)
            if event.remembersStreamingRenderer,
               let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) {
                retained.insert(ChatMessageProjector.messageId(for: lastAssistant))
            }
            return retained
        }
    }
}

@MainActor
private final class NativeTimelineProjectionCache {
    private var cachedProjection: NativeTimelineProjection?
    private var structuralKey = ""
    private var messageCount = 0
    private var lastMessageID: String?

    func projection(
        messages: [UIMessage],
        event: ChatEvent,
        configurationIssue: ChatConfigurationIssue?,
        isGenerationActive: Bool,
        isLoading: Bool,
        isRecognizingImages: Bool,
        contextCompactState: ChatContextCompactState,
        viewportState: ChatViewportState,
        displaySettingSignature: String,
        generativeUiSettingSignature: String,
        renderStateRevision: UInt64,
        reasoningLevelLabel: String?,
        streamedMessageIDs: Set<String>,
        renderStateStore: ChatRenderStateStore,
        variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?,
        contentHashProvider: (ChatMessageRowModel, Bool) -> Int
    ) -> NativeTimelineProjection {
        let lastID = messages.last.map(ChatMessageProjector.messageId(for:))
        let key = Self.structuralKey(
            configurationIssue: configurationIssue,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading,
            isRecognizingImages: isRecognizingImages,
            contextCompactState: contextCompactState,
            displaySettingSignature: displaySettingSignature,
            generativeUiSettingSignature: generativeUiSettingSignature,
            reasoningLevelLabel: reasoningLevelLabel
        )

        if key == structuralKey,
           messageCount == messages.count,
           lastMessageID == lastID,
           let cachedProjection,
           let increment = NativeTimelineProjector.replacingStreamingTail(
            in: cachedProjection,
            messages: messages,
            event: event,
            isGenerationActive: isGenerationActive,
            viewportState: viewportState,
            displaySettingSignature: displaySettingSignature,
            generativeUiSettingSignature: generativeUiSettingSignature,
            renderStateRevision: renderStateRevision,
            reasoningLevelLabel: reasoningLevelLabel,
            streamedMessageIDs: streamedMessageIDs,
            renderStateStore: renderStateStore,
            variantInfoProvider: variantInfoProvider,
            contentHashProvider: contentHashProvider
           ) {
            self.cachedProjection = increment
            return increment
        }

        let projection = NativeTimelineProjector.build(
            messages: messages,
            event: event,
            configurationIssue: configurationIssue,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading,
            isRecognizingImages: isRecognizingImages,
            contextCompactState: contextCompactState,
            viewportState: viewportState,
            displaySettingSignature: displaySettingSignature,
            generativeUiSettingSignature: generativeUiSettingSignature,
            renderStateRevision: renderStateRevision,
            reasoningLevelLabel: reasoningLevelLabel,
            streamedMessageIDs: streamedMessageIDs,
            renderStateStore: renderStateStore,
            variantInfoProvider: variantInfoProvider,
            contentHashProvider: contentHashProvider
        )
        cachedProjection = projection
        structuralKey = key
        messageCount = messages.count
        lastMessageID = lastID
        return projection
    }

    func reset() {
        cachedProjection = nil
        structuralKey = ""
        messageCount = 0
        lastMessageID = nil
    }

    private static func structuralKey(
        configurationIssue: ChatConfigurationIssue?,
        isGenerationActive: Bool,
        isLoading: Bool,
        isRecognizingImages: Bool,
        contextCompactState: ChatContextCompactState,
        displaySettingSignature: String,
        generativeUiSettingSignature: String,
        reasoningLevelLabel: String?
    ) -> String {
        [
            configurationIssue.map { "\($0.title)|\($0.message)" } ?? "no-issue",
            "generation=\(isGenerationActive)",
            "loading=\(isLoading)",
            "vision=\(isRecognizingImages)",
            "context=\(String(describing: contextCompactState.status)):\(contextCompactState.updatedAt.timeIntervalSince1970):\(contextCompactState.summary)",
            "display=\(displaySettingSignature)",
            "generative=\(generativeUiSettingSignature)",
            "reasoning=\(reasoningLevelLabel ?? "")"
        ].joined(separator: "||")
    }
}

/// Production Chat timeline. The native scroll driver owns normal bottom-follow;
/// SwiftUI takes over only after an explicit driver fallback.
struct NativeChatTimelineView: View {
    var signal: ChatMessageUpdateSignal
    var configurationIssue: ChatConfigurationIssue?
    var isGenerationActive: Bool
    var isLoading: Bool
    var isRecognizingImages: Bool
    var contextCompactState: ChatContextCompactState
    var followGeneration: Bool
    var displaySetting: DisplaySetting
    var generativeUiSetting: GenerativeUiSetting
    var reasoningLevelLabel: String?
    var workspaceStore: IOSWorkspaceStore
    var scrollToBottomTrigger: Int
    var scrollToBottomSource: NativeTimelineBottomIntentSource
    var messageAnchor: ChatMessageAnchor?
    var currentConversationID: String?
    var messagesProvider: () -> [UIMessage]
    var variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?
    var onAction: (ChatListAction) -> Void
    var onViewportStateChange: (ChatViewportState) -> Void
    var onDismissKeyboard: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var viewportState = ChatViewportState()
    @State private var scrollPosition = ScrollPosition()
    @State private var lastScrollToBottomTrigger = 0
    @State private var streamedMessageIDs: Set<String> = []
    @State private var renderStateStore = ChatRenderStateStore()
    @State private var renderStateRevision: UInt64 = 0
    @State private var contentHashCache = ChatRowContentHashCache()
    @State private var projectionCache = NativeTimelineProjectionCache()
    @State private var scrollDriver = NativeTimelineScrollDriver()
    @State private var nativeUserScrollActive = false
    @State private var nativeTouchStartedWhileFollowing = false
    @State private var nativeDragPhaseOccurredDuringTouch = false
    @State private var nativeScrollFallbackReason: NativeTimelineScrollFallbackReason?
    @State private var nativeScrollFallbackShouldReplayBottom = false
    @State private var nativeScrollFallbackReplayToken: UInt64 = 0
    @State private var isNativeScrollSurfaceVisible = false
    @State private var consumedMessageAnchor: ChatMessageAnchor?
    @State private var scheduledMessageAnchor: ChatMessageAnchor?
    @State private var imageAccessibilityFocusToolCallID: String?

    var body: some View {
        let messages = messagesProvider()
        let nativeScrollDriverDesired = isNativeScrollDriverDesired
        let displaySettingSignature = String(describing: displaySetting)
        let generativeUiSettingSignature = String(describing: generativeUiSetting)
        let effectiveStreamedMessageIDs = effectiveStreamedMessageIDsForRender(
            event: signal.event,
            messages: messages
        )
        let renderViewportState = {
            var state = viewportState
            // Keep the eager timeline's existing Markdown tree while history is visible;
            // pause model publications instead of replacing that tree.
            state.liveRenderingFarFromBottom = false
            return state
        }()
        let projection = projectionCache.projection(
            messages: messages,
            event: signal.event,
            configurationIssue: configurationIssue,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading,
            isRecognizingImages: isRecognizingImages,
            contextCompactState: contextCompactState,
            viewportState: renderViewportState,
            displaySettingSignature: displaySettingSignature,
            generativeUiSettingSignature: generativeUiSettingSignature,
            renderStateRevision: renderStateRevision,
            reasoningLevelLabel: reasoningLevelLabel,
            streamedMessageIDs: effectiveStreamedMessageIDs,
            renderStateStore: renderStateStore,
            variantInfoProvider: variantInfoProvider
        ) { [contentHashCache] row, isStreamingLayout in
            isStreamingLayout
                ? contentHashCache.streamingTailLayoutToken(for: row)
                : contentHashCache.contentHash(for: row)
        }
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // One eager height model prevents historical estimates and the live tail
                // from publishing conflicting content sizes into the same scroll view.
                ForEach(projection.entries) { entry in
                    entryView(
                        entry,
                        displaySettingSignature: displaySettingSignature,
                        generativeUiSettingSignature: generativeUiSettingSignature
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 28)
            .scrollTargetLayout()
            .background {
                if nativeScrollDriverDesired {
                    NativeTimelineScrollViewResolver(
                        onResolve: { scrollView in
                            handleNativeScrollViewResolved(scrollView)
                        },
                        onMetricsChanged: {
                            guard isNativeScrollDriverActive else { return }
                            scrollDriver.handleLayoutMetricsChanged()
                        }
                    )
                }
            }
        }
        .modifier(ChatSizeChangesPinModifier(
            enabled: followGeneration && !isNativeScrollDriverDesired
        ))
        .nativeTimelineScrollPosition($scrollPosition)
        .scrollEdgeEffectStyle(.soft, for: .top)
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .contentShape(Rectangle())
        .simultaneousGesture(
            TapGesture().onEnded {
                onDismissKeyboard()
            }
        )
        .onScrollPhaseChange { _, phase in
            guard isNativeScrollDriverActive else {
                handleNativeFallbackScrollPhase(phase)
                return
            }
            switch phase {
            case .tracking, .interacting:
                guard Self.shouldBeginNativeUserDrag(
                    phase: phase,
                    isUIKitUserInteracting: scrollDriver.isUIKitUserInteracting
                ) else { return }
                if !nativeUserScrollActive {
                    // 新触摸开始：重置「本次触摸内是否出现过拖拽」。只在此处
                    // 重置（interacting 会在一次拖拽中反复触发，不能在分支里
                    // 无条件清零），否则首次真实拖拽后轻点恢复永久失效。
                    nativeDragPhaseOccurredDuringTouch = false
                }
                if phase == .interacting {
                    // SwiftUI 把「移动已开始的主动拖拽」归入 interacting；
                    // tracking 只是手指落下。轻点（tracking→idle）与拖拽
                    // （tracking→interacting→…→idle）由此区分。
                    nativeDragPhaseOccurredDuringTouch = true
                }
                nativeUserScrollActive = true
                nativeTouchStartedWhileFollowing = scrollDriver.isFollowingBottomOrKeyboardFocus
                onDismissKeyboard()
                scrollDriver.submit(.userDragBegan)
            case .idle:
                let endedUserScroll = nativeUserScrollActive
                nativeUserScrollActive = false
                // 轻点（从未进入拖拽相位）不该终止跟随：按下期间快速增长可能把
                // 距底推过恢复阈值，但用户从未表达离开意图——恢复原跟随语义。
                let accidentalTapRestore = endedUserScroll && followGeneration && Self
                    .shouldRestoreFollowAfterAccidentalTap(
                        wasFollowingAtTouchDown: nativeTouchStartedWhileFollowing,
                        dragPhaseOccurred: nativeDragPhaseOccurredDuringTouch,
                        generationActive: isGenerationActive || isLoading
                    )
                if accidentalTapRestore {
                    scrollDriver.submit(.userDragEnded(isAtBottom: true))
                    resumeNativeBottomFollowAfterUserReturn()
                } else if endedUserScroll {
                    let returnedToBottom = NativeTimelineScrollReturnPolicy.returnedToBottom(
                        liveDistanceToBottom: scrollDriver.distanceToBottomNow(),
                        cachedNearBottom: viewportState.isAtBottom,
                        threshold: ChatLayout.nearBottomResumeThreshold
                    )
                    scrollDriver.submit(.userDragEnded(isAtBottom: returnedToBottom))
                    if returnedToBottom {
                        resumeNativeBottomFollowAfterUserReturn()
                    }
                }
            case .animating:
                break
            case .decelerating:
                // 甩向底部的惯性不等静止再判定：快速生成时底部在跑，静止时
                // 往往已移出恢复窗口（用户被迫甩第二次）。释放瞬间用 pan 速度
                // 预测落点，命中容差即磁吸接管。Reduce Motion 下不插入程序
                // 缓动：让自然减速走完，由 .idle 相位的 returnedToBottom 判定
                // 收口（该路径无动画）。
                if !reduceMotion {
                    scrollDriver.attemptMagneticBottomSnapAfterDragRelease()
                }
            @unknown default:
                break
            }
        }
        .onScrollGeometryChange(for: ChatSwiftUIScrollGeometry.self) { geo in
            ChatSwiftUIScrollGeometry(
                distanceToBottom: max(0, geo.contentSize.height - geo.visibleRect.maxY),
                visibleHeight: max(1, geo.visibleRect.height),
                contentHeight: geo.contentSize.height
            )
        } action: { previousGeometry, geometry in
            let wasAtBottom = viewportState.isAtBottom
            let messages = messagesProvider()
            let rawViewportState = NativeStaticTimelineViewportPolicy.state(
                distanceToBottom: geometry.distanceToBottom,
                visibleHeight: geometry.visibleHeight,
                contentHeight: geometry.contentHeight,
                hasMessages: !messages.isEmpty,
                userInteracting: nativeUserScrollActive,
                driverPausedForUser: isNativeScrollDriverActive && scrollDriver.isPausedForUser
            )
            let nextViewportState = rawViewportState
            publishViewportState(nextViewportState)
            unfreezeVisibleLiveTailIfNeeded(messages: messages, viewportState: nextViewportState)
            resumeNativeBottomFollowFromGeometryIfNeeded(
                geometry: geometry,
                viewportState: nextViewportState,
                messages: messages
            )
            reanchorAfterViewportShrinkIfNeeded(
                previous: previousGeometry,
                current: geometry,
                wasAtBottom: wasAtBottom
            )
        }
        .onAppear {
            isNativeScrollSurfaceVisible = true
            // 重新进入页面时清除上一次的 fallback 粘连，给原生滚动 driver 一次重试机会。
            nativeScrollFallbackReason = nil
            updateRendererMemory(event: signal.event, messages: messages)
            consumeExternalScrollToBottomTriggerIfNeeded()
            scrollToMessageAnchorIfAvailable()
        }
        .onDisappear {
            isNativeScrollSurfaceVisible = false
            nativeUserScrollActive = false
            scrollDriver.invalidate()
        }
        .onChange(of: followGeneration) { _, enabled in
            scrollDriver.setAutomaticFollowEnabled(enabled)
        }
        .onChange(of: signal) { _, newSignal in
            updateRendererMemory(event: newSignal.event, messages: messagesProvider())
            submitNativeScrollIntent(for: newSignal.event, lagAllowance: newSignal.lagAllowance)
            scrollToMessageAnchorIfAvailable()
        }
        .onChange(of: messageAnchor) { _, _ in
            scrollToMessageAnchorIfAvailable()
        }
        .onChange(of: scrollToBottomTrigger) { _, trigger in
            consumeExternalScrollToBottomTriggerIfNeeded(trigger)
        }
        .onChange(of: nativeScrollFallbackReason) { _, reason in
            guard reason != nil else { return }
            if scheduledMessageAnchor != nil {
                nativeScrollFallbackShouldReplayBottom = false
                return
            }
            if scrollToMessageAnchorIfAvailable() {
                nativeScrollFallbackShouldReplayBottom = false
                return
            }
            guard nativeScrollFallbackShouldReplayBottom else { return }
            let replayToken = nativeScrollFallbackReplayToken
            nativeScrollFallbackShouldReplayBottom = false
            Task { @MainActor in
                await Task.yield()
                guard replayToken == nativeScrollFallbackReplayToken,
                      !nativeUserScrollActive else { return }
                withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                    scrollPosition.scrollTo(id: ChatLayout.bottomAnchorID, anchor: .bottom)
                }
            }
        }
    }

    private var isNativeScrollDriverActive: Bool {
        isNativeScrollDriverDesired && scrollDriver.isAttached
    }

    static func shouldBeginNativeUserDrag(
        phase: ScrollPhase,
        isUIKitUserInteracting: Bool
    ) -> Bool {
        switch phase {
        case .tracking:
            return true
        case .interacting:
            return isUIKitUserInteracting
        case .idle, .decelerating, .animating:
            return false
        @unknown default:
            return false
        }
    }

    /// 轻点误伤恢复：触碰（.tracking）即暂停跟随防打架是有意的所有权设计，
    /// 但「按下→从未拖拽→抬起」是误触——按下期间的快速增长可能把距底推过
    /// 恢复阈值，静止判定会漏恢复。仅当按下时确在跟随、期间无拖拽相位、
    /// 生成仍在进行时恢复跟随。
    static func shouldRestoreFollowAfterAccidentalTap(
        wasFollowingAtTouchDown: Bool,
        dragPhaseOccurred: Bool,
        generationActive: Bool
    ) -> Bool {
        wasFollowingAtTouchDown && !dragPhaseOccurred && generationActive
    }

    private var isNativeScrollDriverDesired: Bool {
        nativeScrollFallbackReason == nil &&
            isNativeScrollSurfaceVisible
    }

    private func handleNativeScrollViewResolved(_ scrollView: UIScrollView) {
        guard isNativeScrollDriverDesired else { return }
        scrollDriver.setAutomaticFollowEnabled(followGeneration)
        scrollDriver.onFallback = { reason, shouldReplayBottom in
            handleNativeScrollFallback(reason, shouldReplayBottom: shouldReplayBottom)
        }
        let didAttach = scrollDriver.attach(scrollView)
        guard didAttach, scrollDriver.isAttached else { return }
        if scrollToMessageAnchorIfAvailable() {
            return
        }
        if nativeUserScrollActive || viewportState.followPaused {
            scrollDriver.submit(.userDragBegan)
        } else {
            scrollDriver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
            if followGeneration, shouldSettleNativeScrollAfterAttach {
                scrollDriver.submit(.generationTerminated)
            }
        }
    }

    @discardableResult
    private func scrollToMessageAnchorIfAvailable() -> Bool {
        let messages = messagesProvider()
        let availableMessageIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
        let availableImageToolCallIDs = Self.imageToolCallIDs(in: messages)
        guard let request = messageAnchor,
              scheduledMessageAnchor != request,
              let targetID = NativeTimelineMessageAnchorPolicy.targetEntryID(
                request: request,
                consumed: consumedMessageAnchor,
                currentConversationID: currentConversationID,
                availableMessageIDs: availableMessageIDs,
                availableImageToolCallIDs: availableImageToolCallIDs
              ),
              NativeTimelineMessageAnchorPolicy.canSchedule(
                nativeDriverActive: isNativeScrollDriverActive,
                fallbackActive: nativeScrollFallbackReason != nil
              ) else {
            return false
        }

        scheduledMessageAnchor = request
        if isNativeScrollDriverActive {
            scrollDriver.submit(.userDragBegan)
        } else {
            nativeScrollFallbackReplayToken &+= 1
            nativeScrollFallbackShouldReplayBottom = false
            var paused = viewportState
            paused.followPaused = true
            paused.showScrollToBottom = true
            publishViewportState(paused)
        }
        Task { @MainActor in
            await Task.yield()
            let currentMessages = messagesProvider()
            guard NativeTimelineMessageAnchorPolicy.targetEntryID(
                request: request,
                consumed: consumedMessageAnchor,
                currentConversationID: currentConversationID,
                availableMessageIDs: Set(currentMessages.map(ChatMessageProjector.messageId(for:))),
                availableImageToolCallIDs: Self.imageToolCallIDs(in: currentMessages)
            ) == targetID else {
                scheduledMessageAnchor = nil
                return
            }
            if reduceMotion {
                scrollPosition.scrollTo(id: targetID, anchor: .center)
                await Task.yield()
                completeMessageAnchorScroll(request: request, targetID: targetID)
            } else {
                withAnimation(.easeOut(duration: 0.24), completionCriteria: .logicallyComplete) {
                    scrollPosition.scrollTo(id: targetID, anchor: .center)
                } completion: {
                    completeMessageAnchorScroll(request: request, targetID: targetID)
                }
            }
        }
        return true
    }

    private func completeMessageAnchorScroll(
        request: ChatMessageAnchor,
        targetID: String
    ) {
        let currentMessages = messagesProvider()
        guard scheduledMessageAnchor == request,
              NativeTimelineMessageAnchorPolicy.targetEntryID(
                request: request,
                consumed: consumedMessageAnchor,
                currentConversationID: currentConversationID,
                availableMessageIDs: Set(currentMessages.map(ChatMessageProjector.messageId(for:))),
                availableImageToolCallIDs: Self.imageToolCallIDs(in: currentMessages)
              ) == targetID else {
            scheduledMessageAnchor = nil
            return
        }
        if let toolCallID = request.toolCallID {
            imageAccessibilityFocusToolCallID = toolCallID
            if UIAccessibility.isVoiceOverRunning,
               let announcement = Self.imageAnchorAnnouncement(
                toolCallID: toolCallID,
                messages: currentMessages
               ) {
                UIAccessibility.post(notification: .announcement, argument: announcement)
            }
        }
        ChatImageGenerationResumeConsumption.markViewedIfCompleted(
            anchor: request,
            messages: currentMessages,
            isGenerationActive: isGenerationActive
        )
        consumedMessageAnchor = request
        scheduledMessageAnchor = nil
    }

    private static func imageToolCallIDs(in messages: [UIMessage]) -> Set<String> {
        Set(messages.flatMap { message in
            message.parts.compactMap { part in
                guard let tool = part as? UIMessagePart.Tool,
                      tool.toolName == "generate_image" else { return nil }
                return tool.toolCallId
            }
        })
    }

    private static func imageAnchorAnnouncement(
        toolCallID: String,
        messages: [UIMessage]
    ) -> String? {
        for message in messages {
            for part in message.parts {
                guard let tool = part as? UIMessagePart.Tool,
                      tool.toolName == "generate_image",
                      tool.toolCallId == toolCallID else { continue }
                if tool.output.contains(where: { $0 is UIMessagePart.Image }) {
                    return "已定位到生成图片"
                }
                return tool.output.isEmpty
                    ? "已定位到正在生成的图片"
                    : "已定位到图片生成失败结果"
            }
        }
        return nil
    }

    private var shouldSettleNativeScrollAfterAttach: Bool {
        if !isGenerationActive && !isLoading {
            return true
        }
        switch signal.event {
        case .generationCompleted, .generationFailed, .generationCancelled,
             .generationHandedOffToBackground:
            return true
        case .userMessageAppended, .assistantStreamDelta, .assistantStreamClosed,
             .toolCallStarted, .toolResultAppended, .awaitingToolApproval,
             .conversationLoaded, .conversationSwitched, .branchChanged, .settingsRefreshed:
            return false
        }
    }

    @ViewBuilder
    private func entryView(
        _ entry: NativeTimelineEntry,
        displaySettingSignature: String,
        generativeUiSettingSignature: String
    ) -> some View {
        switch entry.kind {
        case .emptyState:
            ChatEmptyState()
        case .configurationIssue(let compact):
            if let configurationIssue {
                ChatConfigurationNoticeCard(
                    issue: configurationIssue,
                    compact: compact,
                    onPrimary: { onAction(.primaryConfiguration) },
                    onModelDefaults: { onAction(.modelDefaults) }
                )
            }
        case .message:
            if let message = entry.message,
               let index = entry.index,
               let messageId = entry.messageId {
                let model = nativeMessageRenderModel(
                    entry: entry,
                    message: message,
                    index: index,
                    messageId: messageId
                )
                NativeTimelineMessageBubble(
                    entryID: entry.id,
                    renderDigest: entry.renderDigest,
                    model: model,
                    displaySetting: displaySetting,
                    generativeUiSetting: generativeUiSetting,
                    displaySettingSignature: displaySettingSignature,
                    generativeUiSettingSignature: generativeUiSettingSignature,
                    reasoningLevelLabel: reasoningLevelLabel,
                    imageAccessibilityFocusToolCallID: imageAccessibilityFocusToolCallID,
                    onAction: onAction
                )
                .equatable()
                .environment(workspaceStore)
                .fixedSize(horizontal: false, vertical: true)
                .frame(
                    maxWidth: .infinity,
                    alignment: entry.role == MessageRole.user ? .topTrailing : .topLeading
                )
                .id(entry.id)
                .transition(userMessageInsertionTransition(for: entry))
                .zIndex(entry.canAnimateInsertion ? 1 : 0)
                .onAppear {
                    markRenderVisible(messageId)
                }
                .onDisappear {
                    guard entry.hasEverStreamed, !entry.isLastMessage else { return }
                    renderStateStore.freeze(
                        messageID: messageId,
                        latestText: message.singleNonEmptyTextPart
                    )
                }
            }
        case .pendingAssistant:
            ChatAssistantPendingResponseView()
                .frame(maxWidth: .infinity, alignment: .leading)
        case .visionRecognition:
            VisionRecognitionIndicator()
        case .contextMarker:
            ContextCompactTimelineMarker(state: contextCompactState)
        case .bottomAnchor:
            Color.clear
                .frame(height: ChatLayout.bottomRestGap)
                .id(ChatLayout.bottomAnchorID)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }

    private func nativeMessageRenderModel(
        entry: NativeTimelineEntry,
        message: UIMessage,
        index: Int,
        messageId: String
    ) -> ChatListMessageRenderModel {
        let row = ChatMessageRowModel(
            rowId: messageId,
            messageId: messageId,
            message: message,
            role: entry.role ?? message.role,
            parts: message.parts,
            index: index,
            isLast: entry.isLastMessage,
            isStreaming: entry.isStreaming,
            hasEverStreamed: entry.renderHasEverStreamed,
            canAnimateInsertion: entry.canAnimateInsertion
        )
        let renderState = entry.renderState ?? renderStateStore.stateForRow(
            row,
            isLiveRenderingFarFromBottom: viewportState.liveRenderingFarFromBottom
        )
        let liveTailModel = nativeLiveTailModelIfNeeded(row: row, renderState: renderState)
        return ChatListMessageRenderModel(
            row: row,
            variantInfo: variantInfoProvider(index),
            renderState: renderState,
            isGenerationActive: isGenerationActive,
            renderIdentity: ChatListSnapshotBuilder.renderIdentityForRow(row, renderState: renderState),
            liveTailModel: liveTailModel
        )
    }

    private func nativeLiveTailModelIfNeeded(
        row: ChatMessageRowModel,
        renderState: ChatRenderState
    ) -> ChatLiveTailModel? {
        guard renderState.liveRenderingEnabled else { return nil }
        return renderStateStore.liveTailModel(
            for: row,
            renderState: renderState,
            isGenerationActive: isGenerationActive
        )
    }

    private func userMessageInsertionTransition(for entry: NativeTimelineEntry) -> AnyTransition {
        guard !reduceMotion,
              entry.canAnimateInsertion,
              entry.role == MessageRole.user else { return .identity }
        return .chatUserMessageSend
    }

    private func updateRendererMemory(event: ChatEvent, messages: [UIMessage]) {
        if event == .conversationLoaded || event == .conversationSwitched || event == .branchChanged {
            renderStateStore.removeAll()
            contentHashCache.removeAll()
            projectionCache.reset()
        }
        if event == .assistantStreamDelta,
           let last = messages.last,
           last.role == MessageRole.assistant {
            streamedMessageIDs.insert(ChatMessageProjector.messageId(for: last))
        } else {
            let currentIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
            streamedMessageIDs = NativeStaticTimelineRendererMemory.nextStreamedMessageIDs(
                previous: streamedMessageIDs,
                event: event,
                messages: messages
            )
            renderStateStore.retain(ids: currentIDs)
            contentHashCache.retain(ids: currentIDs)
        }
        unfreezeVisibleLiveTailIfNeeded(messages: messages, viewportState: viewportState)
    }

    private func effectiveStreamedMessageIDsForRender(event: ChatEvent, messages: [UIMessage]) -> Set<String> {
        if event == .assistantStreamDelta,
           let last = messages.last,
           last.role == MessageRole.assistant {
            var next = streamedMessageIDs
            next.insert(ChatMessageProjector.messageId(for: last))
            return next
        }
        return NativeStaticTimelineRendererMemory.nextStreamedMessageIDs(
            previous: streamedMessageIDs,
            event: event,
            messages: messages
        )
    }

    private func isNativeMeasuredNearBottom(_ geometry: ChatSwiftUIScrollGeometry) -> Bool {
        geometry.distanceToBottom <= max(ChatLayout.bottomStickThreshold, NativeTimelineScrollCore.resumeEpsilon)
    }

    private func unfreezeVisibleLiveTailIfNeeded(messages: [UIMessage], viewportState: ChatViewportState) {
        guard !viewportState.liveRenderingFarFromBottom,
              let lastAssistantID = latestAssistantMessageID(messages: messages) else { return }
        markRenderVisible(lastAssistantID)
        updateNativeLiveTailModelIfNeeded(messages: messages, viewportState: viewportState)
    }

    private func markRenderVisible(_ messageID: String) {
        if renderStateStore.markVisible(messageID) {
            renderStateRevision &+= 1
        }
    }

    private func latestAssistantMessageID(messages: [UIMessage]) -> String? {
        guard let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) else {
            return nil
        }
        return ChatMessageProjector.messageId(for: lastAssistant)
    }

    private func updateNativeLiveTailModelIfNeeded(messages: [UIMessage], viewportState: ChatViewportState) {
        guard let indexedMessage = messages.enumerated().last(where: { $0.element.role == MessageRole.assistant })
        else { return }
        let message = indexedMessage.element
        let messageID = ChatMessageProjector.messageId(for: message)
        let isLast = indexedMessage.offset == messages.count - 1
        let isStreaming = signal.event == .assistantStreamDelta && isLast
        let row = ChatMessageRowModel(
            rowId: messageID,
            messageId: messageID,
            message: message,
            role: message.role,
            parts: message.parts,
            index: indexedMessage.offset,
            isLast: isLast,
            isStreaming: isStreaming,
            hasEverStreamed: isStreaming || streamedMessageIDs.contains(messageID),
            canAnimateInsertion: false
        )
        let renderState = renderStateStore.stateForRow(
            row,
            isLiveRenderingFarFromBottom: viewportState.liveRenderingFarFromBottom
        )
        guard renderState.liveRenderingEnabled else { return }
        let liveTailModel = renderStateStore.liveTailModel(
            for: row,
            renderState: renderState,
            isGenerationActive: isGenerationActive
        )
        liveTailModel?.update(
            message: row.message,
            isGenerationActive: isGenerationActive,
            renderState: renderState,
            sourceRevision: signal.revision
        )
    }

    private func resumeNativeBottomFollowFromGeometryIfNeeded(
        geometry: ChatSwiftUIScrollGeometry,
        viewportState: ChatViewportState,
        messages: [UIMessage]
    ) {
        guard followGeneration,
              isNativeScrollDriverActive,
              !nativeUserScrollActive,
              isGenerationActive || isLoading,
              isNativeMeasuredNearBottom(geometry),
              scrollDriver.isPausedForUser || viewportState.followPaused || viewportState.liveRenderingFarFromBottom
        else { return }
        scrollDriver.submit(.userDragEnded(isAtBottom: true))
        unfreezeVisibleLiveTailIfNeeded(messages: messages, viewportState: viewportState)
        scrollDriver.submit(.streamContentGrew())
    }

    private func resumeNativeBottomFollowAfterUserReturn() {
        guard followGeneration else { return }
        let messages = messagesProvider()
        var next = viewportState
        next.followPaused = false
        next.userDragging = false
        next.isAtBottom = true
        next.liveRenderingFarFromBottom = false
        next.showScrollToBottom = false
        publishViewportState(next)
        unfreezeVisibleLiveTailIfNeeded(messages: messages, viewportState: next)
        if isGenerationActive || isLoading {
            scrollDriver.submit(.streamContentGrew())
        }
    }

    private func reanchorAfterViewportShrinkIfNeeded(
        previous: ChatSwiftUIScrollGeometry,
        current: ChatSwiftUIScrollGeometry,
        wasAtBottom: Bool
    ) {
        guard wasAtBottom,
              previous.visibleHeight - current.visibleHeight > 2,
              current.contentHeight > current.visibleHeight + ChatLayout.bottomStickThreshold else { return }
        guard !isNativeScrollDriverActive else { return }
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) {
            scrollPosition.scrollTo(id: ChatLayout.bottomAnchorID, anchor: .bottom)
        }
    }

    private func consumeExternalScrollToBottomTriggerIfNeeded(_ trigger: Int? = nil) {
        let currentTrigger = trigger ?? scrollToBottomTrigger
        guard currentTrigger != lastScrollToBottomTrigger else { return }
        lastScrollToBottomTrigger = currentTrigger
        var next = viewportState
        next.followPaused = false
        next.userDragging = false
        next.isAtBottom = true
        next.liveRenderingFarFromBottom = false
        next.showScrollToBottom = false
        publishViewportState(next)
        unfreezeVisibleLiveTailIfNeeded(messages: messagesProvider(), viewportState: next)
        guard !isNativeScrollDriverActive else {
            scrollDriver.submit(
                .explicitBottom(
                    source: scrollToBottomSource,
                    animated: scrollToBottomSource != .composerFocus,
                    keyboardToken: UInt64(currentTrigger)
                )
            )
            return
        }
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
            scrollPosition.scrollTo(id: ChatLayout.bottomAnchorID, anchor: .bottom)
        }
    }

    private func submitNativeScrollIntent(for event: ChatEvent, lagAllowance: CGFloat = 1) {
        guard isNativeScrollDriverActive else {
            submitNativeSwiftUIFallbackScrollIntent(for: event)
            return
        }
        switch event {
        case .conversationLoaded, .conversationSwitched:
            scrollDriver.submit(.conversationReset)
            scrollDriver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        case .branchChanged:
            scrollDriver.submit(.conversationReset)
        case .userMessageAppended:
            scrollDriver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        case .assistantStreamDelta:
            guard followGeneration else { return }
            scrollDriver.submit(.streamContentGrew(lagAllowance: lagAllowance))
        case .assistantStreamClosed, .toolCallStarted, .toolResultAppended,
             .awaitingToolApproval:
            if followGeneration,
               viewportState.isAtBottom || scrollDriver.isFollowingBottomOrKeyboardFocus || scrollDriver.isAtBottomNow() {
                scrollDriver.submit(.streamContentGrew())
            }
        case .generationCompleted, .generationFailed, .generationCancelled,
             .generationHandedOffToBackground:
            guard followGeneration else { return }
            scrollDriver.submit(.generationTerminated)
        case .settingsRefreshed:
            break
        }
    }

    private func publishViewportState(_ next: ChatViewportState) {
        guard next != viewportState else { return }
        viewportState = next
        onViewportStateChange(next)
    }

    private func handleNativeScrollFallback(
        _ reason: NativeTimelineScrollFallbackReason,
        shouldReplayBottom: Bool
    ) {
        guard nativeScrollFallbackReason == nil else { return }
        nativeScrollFallbackReplayToken &+= 1
        nativeScrollFallbackShouldReplayBottom = shouldReplayBottom
        nativeScrollFallbackReason = reason
        NativeTimelineScrollDiagnostics.logFallbackActivated(reason: reason)
    }

    private func handleNativeFallbackScrollPhase(_ phase: ScrollPhase) {
        switch phase {
        case .tracking, .interacting:
            nativeScrollFallbackReplayToken &+= 1
            nativeUserScrollActive = true
            onDismissKeyboard()
        case .idle:
            nativeUserScrollActive = false
        case .animating, .decelerating:
            break
        @unknown default:
            break
        }
    }

    private func submitNativeSwiftUIFallbackScrollIntent(for event: ChatEvent) {
        guard !isNativeScrollDriverActive else { return }
        switch event {
        case .conversationLoaded, .conversationSwitched:
            resetNativeSwiftUIFallbackViewportForConversationEntry()
            requestNativeSwiftUIFallbackBottom(animated: false)
        case .userMessageAppended:
            requestNativeSwiftUIFallbackBottom(animated: false)
        case .assistantStreamDelta:
            guard canRunStreamingSwiftUIFallback else { return }
            guard canRunNativeSwiftUIFallbackBottomFollow else { return }
            requestNativeSwiftUIFallbackBottom(animated: false)
        case .assistantStreamClosed, .toolCallStarted, .toolResultAppended,
             .awaitingToolApproval, .generationCompleted, .generationFailed,
             .generationCancelled, .generationHandedOffToBackground:
            guard canRunStreamingSwiftUIFallback else { return }
            guard canRunNativeSwiftUIFallbackBottomFollow else { return }
            requestNativeSwiftUIFallbackBottom(animated: false)
        case .branchChanged, .settingsRefreshed:
            break
        }
    }

    private var canRunStreamingSwiftUIFallback: Bool {
        !isNativeScrollDriverDesired || nativeScrollFallbackReason != nil
    }

    private var canRunNativeSwiftUIFallbackBottomFollow: Bool {
        viewportState.isAtBottom &&
            !viewportState.followPaused &&
            !nativeUserScrollActive
    }

    private func requestNativeSwiftUIFallbackBottom(animated: Bool) {
        guard !nativeUserScrollActive else { return }
        if animated {
            withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                scrollPosition.scrollTo(id: ChatLayout.bottomAnchorID, anchor: .bottom)
            }
        } else {
            var transaction = Transaction()
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                scrollPosition.scrollTo(id: ChatLayout.bottomAnchorID, anchor: .bottom)
            }
        }
    }

    private func resetNativeSwiftUIFallbackViewportForConversationEntry() {
        nativeScrollFallbackReplayToken &+= 1
        nativeUserScrollActive = false
        var next = viewportState
        next.followPaused = false
        next.userDragging = false
        next.isAtBottom = true
        next.liveRenderingFarFromBottom = false
        next.showScrollToBottom = false
        publishViewportState(next)
    }
}

private struct ChatConfigurationNoticeCard: View {
    let issue: ChatConfigurationIssue
    let compact: Bool
    let onPrimary: () -> Void
    let onModelDefaults: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: compact ? 10 : 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(issue.title)
                    .font(compact ? .subheadline.weight(.semibold) : .headline)
                    .foregroundStyle(AmberTheme.foreground)
                Text(issue.message)
                    .font(.footnote)
                    .foregroundStyle(AmberTheme.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            HStack(spacing: 8) {
                Button(primaryTitle, action: onPrimary)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.small)
                    .tint(AmberTheme.accent)

                if issue != .missingModel {
                    Button("选择模型", action: onModelDefaults)
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            AmberTheme.surface.opacity(0.92),
            in: RoundedRectangle(cornerRadius: 16, style: .continuous)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(AmberTheme.borderSoft, lineWidth: 0.5)
        }
        .accessibilityElement(children: .contain)
    }

    private var primaryTitle: String {
        switch issue {
        case .missingAPIKey: "添加 API Key"
        case .invalidBaseURL: "修正服务商"
        case .missingModel: "选择模型"
        case .missingProvider: "配置服务商"
        case .providerDisabled: "启用服务商"
        case .unsupportedProvider: "切换服务商"
        case .codexNotSignedIn: "登录 Codex"
        case .grokNotSignedIn: "登录 Grok"
        case .geminiNotSignedIn: "登录 Antigravity"
        }
    }
}

/// 底部跟随三态机(对齐 Android TimelineFollowMode)。
/// 核心契约(C1):用户主动上滑进入 pausedForUser 后,在用户【主动滑回底部】之前,
/// 任何流式 delta / 几何帧 / 手指抬起都不能恢复跟随 —— 避免"滑动被拽回底部"。
enum FollowMode: Equatable {
    /// 无生成,或内容未超一屏。不主动跟随。
    case idle
    /// 生成中 + 贴底跟随。delta 到来时发跟随 scroll。
    case followingBottom
    /// 用户主动上滑离开底部。【唯一恢复路径】:用户滑回底部(distance ≤ resumeThreshold)。
    case pausedForUser
}

enum ChatSwiftUINearBottomResumePolicy {
    static func shouldResume(
        followPaused: Bool,
        userScrollActive: Bool,
        userScrollJustEnded: Bool,
        distanceToBottom: CGFloat
    ) -> Bool {
        guard followPaused,
              userScrollActive || userScrollJustEnded else { return false }
        return distanceToBottom <= ChatLayout.nearBottomResumeThreshold
    }
}

struct ChatSwiftUIExplicitBottomPlan: Equatable {
    let viewportState: ChatViewportState
    let animated: Bool
}

enum ChatSwiftUIExplicitBottomPolicy {
    static func plan(
        current: ChatViewportState,
        source: NativeTimelineBottomIntentSource,
        distanceToBottom: CGFloat
    ) -> ChatSwiftUIExplicitBottomPlan {
        var next = current
        next.followPaused = false
        next.userDragging = false
        next.showScrollToBottom = false
        return ChatSwiftUIExplicitBottomPlan(
            viewportState: next,
            animated: source == .button && distanceToBottom > ChatLayout.bottomStickThreshold
        )
    }
}

struct ChatSwiftUIStreamingTailVisibilityState: Equatable {
    var messageID: String?
    var isVisible: Bool?

    init(messageID: String? = nil, isVisible: Bool? = nil) {
        self.messageID = messageID
        self.isVisible = isVisible
    }
}

enum ChatSwiftUIStreamingTailRenderPolicy {
    static func shouldSuspend(
        isLastAssistant: Bool,
        hasEverStreamed: Bool,
        messageID: String,
        visibility: ChatSwiftUIStreamingTailVisibilityState
    ) -> Bool {
        isLastAssistant &&
            hasEverStreamed &&
            visibility.messageID == messageID &&
            visibility.isVisible == false
    }
}

enum ChatSwiftUIExplicitBottomLiveTailPolicy {
    static func shouldRelease(
        forceActive: Bool,
        messageID: String?,
        visibility: ChatSwiftUIStreamingTailVisibilityState,
        distanceToBottom: CGFloat
    ) -> Bool {
        forceActive &&
            messageID != nil &&
            visibility.messageID == messageID &&
            visibility.isVisible == true &&
            distanceToBottom <= ChatLayout.bottomStickThreshold
    }

    static func shouldCancelAtTerminal(forceActive: Bool, hasAssistantTail: Bool) -> Bool {
        forceActive && !hasAssistantTail
    }
}

enum ChatSwiftUIExplicitBottomLayoutPolicy {
    static func shouldReanchor(
        forceActive: Bool,
        explicitBottomAnimationActive: Bool,
        state: ChatViewportState,
        userScrollActive: Bool,
        previousContentHeight: CGFloat,
        currentContentHeight: CGFloat
    ) -> Bool {
        guard forceActive,
              !explicitBottomAnimationActive,
              !state.followPaused,
              !state.userDragging,
              !userScrollActive,
              state.isContentScrollable else { return false }
        return abs(currentContentHeight - previousContentHeight) > 0.5
    }
}

enum ChatSwiftUIConversationAnchorRetryDecision: Equatable {
    case abort
    case wait
    case attempt
}

enum ChatSwiftUIConversationAnchorRetryPolicy {
    static func decision(
        taskCancelled: Bool,
        tokenMatches: Bool,
        canRunNow: Bool,
        isAlreadyAnchored: Bool = false
    ) -> ChatSwiftUIConversationAnchorRetryDecision {
        guard !taskCancelled, tokenMatches else { return .abort }
        guard !isAlreadyAnchored else { return .abort }
        return canRunNow ? .attempt : .wait
    }
}

enum ChatSwiftUIMeasuredGrowthFollowPolicy {
    static func shouldTrackGrowth(
        generationActive: Bool,
        endSettleActive: Bool,
        explicitBottomCatchUpActive: Bool,
        followEnabled: Bool,
        followMode: FollowMode,
        state: ChatViewportState,
        userScrollActive: Bool,
        previousContentHeight: CGFloat,
        currentContentHeight: CGFloat
    ) -> Bool {
        guard generationActive || endSettleActive || explicitBottomCatchUpActive,
              followEnabled,
              followMode == .followingBottom,
              !state.followPaused,
              !state.userDragging,
              !userScrollActive else { return false }
        return currentContentHeight - previousContentHeight > 0.5
    }

    static func shouldFollow(
        generationActive: Bool,
        endSettleActive: Bool,
        explicitBottomCatchUpActive: Bool,
        explicitBottomAnimationActive: Bool,
        followEnabled: Bool,
        followMode: FollowMode,
        state: ChatViewportState,
        userScrollActive: Bool,
        previousContentHeight: CGFloat,
        currentContentHeight: CGFloat,
        distanceToBottom: CGFloat
    ) -> Bool {
        guard !explicitBottomAnimationActive,
              shouldTrackGrowth(
            generationActive: generationActive,
            endSettleActive: endSettleActive,
            explicitBottomCatchUpActive: explicitBottomCatchUpActive,
            followEnabled: followEnabled,
            followMode: followMode,
            state: state,
            userScrollActive: userScrollActive,
            previousContentHeight: previousContentHeight,
            currentContentHeight: currentContentHeight
        ), state.isContentScrollable else { return false }
        return distanceToBottom > 1
    }
}

enum ChatSwiftUIBottomWritePolicy {
    static func canIssueImmediateWrite(explicitBottomAnimationActive: Bool) -> Bool {
        !explicitBottomAnimationActive
    }
}

enum ChatSwiftUIGenerationEndSettlePolicy {
    static let frameDurationNanoseconds: UInt64 = 16_666_667
    // 最长 live Markdown 节流为 0.32s；0.4s 静默窗口覆盖解析和随后一轮布局。
    static let quietFrames = 24
    static let maxFrames = 60

    static var quietDuration: TimeInterval {
        Double(quietFrames) / 60
    }

    static func shouldFinish(
        elapsedFrames: Int,
        quietElapsed: TimeInterval,
        explicitBottomAnimationActive: Bool
    ) -> Bool {
        if elapsedFrames >= maxFrames {
            return true
        }
        return !explicitBottomAnimationActive && quietElapsed >= quietDuration
    }
}

enum ChatSwiftUICleanListRenderPolicy {
    static func liveTailState(for row: ChatMessageRowModel) -> ChatRenderState? {
        guard row.isLast,
              row.role == MessageRole.assistant,
              row.isStreaming || row.hasEverStreamed else { return nil }
        return ChatRenderState(
            rendererMode: .streamingMarkdown,
            hasEverStreamed: true,
            liveRenderingEnabled: true,
            frozenMarkdownSnapshot: nil
        )
    }

    static func nonLiveTailState(for row: ChatMessageRowModel) -> ChatRenderState? {
        guard !row.isLast || row.role != MessageRole.assistant else { return nil }
        if row.hasEverStreamed {
            return ChatRenderState(
                rendererMode: .streamingMarkdown,
                hasEverStreamed: true,
                liveRenderingEnabled: true,
                frozenMarkdownSnapshot: nil
            )
        }
        return ChatRenderState(
            rendererMode: .staticMarkdown,
            hasEverStreamed: false,
            liveRenderingEnabled: true,
            frozenMarkdownSnapshot: nil
        )
    }
}

private struct ChatSwiftUIScrollGeometry: Equatable {
    var distanceToBottom: CGFloat = 0
    var visibleHeight: CGFloat = 1
    var contentHeight: CGFloat = 0
}

enum NativeTimelineMessageAnchorPolicy {
    static func targetEntryID(
        request: ChatMessageAnchor?,
        consumed: ChatMessageAnchor?,
        currentConversationID: String?,
        availableMessageIDs: Set<String>,
        availableImageToolCallIDs: Set<String> = []
    ) -> String? {
        guard let request,
              request != consumed,
              request.conversationID == currentConversationID,
              availableMessageIDs.contains(request.messageID) else {
            return nil
        }
        if let toolCallID = request.toolCallID {
            guard availableImageToolCallIDs.contains(toolCallID) else { return nil }
            return ChatImageGenerationAnchorTarget.id(toolCallID: toolCallID)
        }
        return ChatTimelinePlanner.messageEntryIDPrefix + request.messageID
    }

    static func canSchedule(
        nativeDriverActive: Bool,
        fallbackActive: Bool
    ) -> Bool {
        nativeDriverActive || fallbackActive
    }
}

private extension View {
    func nativeTimelineScrollPosition(
        _ position: Binding<ScrollPosition>
    ) -> some View {
        self
            .scrollPosition(position)
            .defaultScrollAnchor(.bottom, for: .initialOffset)
            .defaultScrollAnchor(.top, for: .alignment)
    }
}

private struct ChatUserMessageInsertionModifier: ViewModifier {
    let active: Bool

    func body(content: Content) -> some View {
        content
            .opacity(active ? 0 : 1)
            .scaleEffect(active ? 0.88 : 1, anchor: .bottomTrailing)
            .offset(x: active ? 22 : 0, y: active ? 30 : 0)
    }
}

private struct ChatSwiftUIStreamingTailVisibilityModifier: ViewModifier {
    let active: Bool
    let onVisibilityChanged: (Bool) -> Void

    @ViewBuilder
    func body(content: Content) -> some View {
        if active {
            content
                .onGeometryChange(for: Bool?.self) { proxy in
                    guard let viewportBounds = proxy.bounds(of: .scrollView) else { return nil }
                    return proxy.frame(in: .local).intersects(viewportBounds)
                } action: { isVisible in
                    guard let isVisible else { return }
                    onVisibilityChanged(isVisible)
                }
                .onDisappear {
                    onVisibilityChanged(false)
                }
        } else {
            content
        }
    }
}

private extension AnyTransition {
    static var chatUserMessageSend: AnyTransition {
        .asymmetric(
            insertion: .modifier(
                active: ChatUserMessageInsertionModifier(active: true),
                identity: ChatUserMessageInsertionModifier(active: false)
            ),
            removal: .opacity
        )
    }
}

/// `ChatSwiftUIMessageList` 的滚动/跟随运行时状态盒。
/// 字段只在事件回调(scroll phase / geometry / signal / task)中读写,渲染 body
/// 不读取任何字段——所以对盒内字段的写入不触发 SwiftUI 失效,滚动帧与流式
/// delta 不会互相放大 body 重求值成本。新增字段前必须确认 body 不依赖它。
@MainActor
private final class ChatSwiftUIListScrollRuntime {
    var lastScrollToBottomTrigger = 0
    var followMode: FollowMode = .idle
    /// onScrollPhaseChange:区分「用户拖拽/惯性」与「程序滚动」。
    var userScrollActive = false
    var followDebugTick = 0
    /// onScrollGeometryChange 提供的真实距底距离。
    var scrollVisibleRectMaxY: CGFloat?
    var latestScrollGeometry = ChatSwiftUIScrollGeometry(
        distanceToBottom: 0,
        visibleHeight: 1,
        contentHeight: 0
    )
    var hasMeasuredScrollGeometry = false
    var streamFollowTask: Task<Void, Never>?
    var measuredGrowthFollowTask: Task<Void, Never>?
    var measuredGrowthRevision: UInt = 0
    var lastUserScrollActivityAt = Date.distantPast
    var conversationScrollTask: Task<Void, Never>?
    var conversationScrollToken = 0
    var explicitBottomAnimationActive = false
    var explicitBottomAnimationToken = 0
    var explicitBottomSettleTask: Task<Void, Never>?
    var explicitBottomSettleLastLayoutChangeAt = Date.distantPast
    var explicitBottomTrace: ChatPerfTrace.Interval?
    var generationEndSettleTask: Task<Void, Never>?
    var generationEndSettleTrace: ChatPerfTrace.Interval?
    var generationEndSettleToken = 0
    var generationEndSettleLastGrowthAt = Date.distantPast
}

/// 每次 body 求值计算一次的行级设置签名(所有行共享,避免逐行反射)。
struct ChatRowSettingSignatures {
    let display: String
    let generative: String
}

/// Android-style clean chat timeline:
/// - one stable SwiftUI row per message during streaming;
/// - natural SwiftUI measurement, no ChatLayout estimated height/cache;
/// - no liveTail overlay, frozen markdown snapshot, or UIKit invalidation bridge.
struct ChatSwiftUIMessageList: View {
    var signal: ChatMessageUpdateSignal
    var configurationIssue: ChatConfigurationIssue?
    var isGenerationActive: Bool
    var isLoading: Bool
    var isRecognizingImages: Bool
    var contextCompactState: ChatContextCompactState
    var followGeneration: Bool
    var displaySetting: DisplaySetting
    var generativeUiSetting: GenerativeUiSetting
    var reasoningLevelLabel: String?
    var workspaceStore: IOSWorkspaceStore
    var scrollToBottomTrigger: Int
    var scrollToBottomSource: NativeTimelineBottomIntentSource
    var messagesProvider: () -> [UIMessage]
    var variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?
    var onAction: (ChatListAction) -> Void
    var onViewportStateChange: (ChatViewportState) -> Void
    var onDismissKeyboard: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var viewportState = ChatViewportState()
    @State private var streamedMessageIDs: Set<String> = []
    @State private var swiftUIRenderStateStore = ChatRenderStateStore()
    @State private var swiftUIContentHashCache = ChatRowContentHashCache()
    @State private var streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState()
    @State private var explicitBottomForcesLiveTail = false
    /// iOS 18 ScrollPosition:替代 ScrollViewProxy,支持 scrollTo(edge:) + isPositionedByUser。
    @State private var scrollPosition = ScrollPosition()
    /// 滚动/跟随运行时状态。只被事件回调读写,渲染 body 不依赖其中任何字段,
    /// 因此放在引用盒子里而不是逐个 @State:滚动几何每帧更新、一次性 follow
    /// 任务和 settle token 递增都不应该把整个列表 body(timeline plan 重建
    /// + 可见行 digest)拖着一起重求值。这是流式期滑动历史掉帧的直接根因之一。
    @State private var runtime = ChatSwiftUIListScrollRuntime()

    var body: some View {
        let messages = messagesProvider()
        let timelinePlan = makeTimelinePlan(messages: messages)
        // 设置签名对所有行相同:每次 body 求值只反射一次,不随可见行数放大
        // (String(describing:) 走 Mirror,单次 ~25µs,逐行×2 会进热路径)。
        let rowSignatures = ChatRowSettingSignatures(
            display: String(describing: displaySetting),
            generative: String(describing: generativeUiSetting)
        )
        let renderedEntries = timelinePlan.entries.dropLast()
        let historicalEntries = renderedEntries.dropLast()
        let tailEntry = renderedEntries.last

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if messages.isEmpty {
                    emptyTimelineContent
                } else {
                    // Keep stable history lazy, but measure the only dynamically growing
                    // tail outside LazyVStack so its line-height changes stay exact.
                    if !historicalEntries.isEmpty {
                        LazyVStack(alignment: .leading, spacing: 14) {
                            ForEach(historicalEntries, id: \.id) { entry in
                                timelineEntryView(entry, signatures: rowSignatures)
                            }
                        }
                    }

                    if let tailEntry {
                        timelineEntryView(tailEntry, signatures: rowSignatures)
                    }

                    if isRecognizingImages {
                        VisionRecognitionIndicator()
                    }

                    if contextCompactState.isVisible {
                        ContextCompactTimelineMarker(state: contextCompactState)
                    }
                }

                bottomAnchor
            }
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .padding(.bottom, 28)
        }
        // iOS 18 scrollPosition + defaultScrollAnchor。动态消息行不登记为 scroll
        // targets；底部跟随只写物理 edge，避免动态行高度变化时重复解析目标。
        // initialOffset 负责长会话初始入场;alignment 负责内容不足一屏时自然顶排。
        // 流式增长由下方真实 geometry 回调唯一驱动。`.sizeChanges` 在长 UIKit
        // 文本段落的增量布局路径不会维持底锚，却会让回调误判已有别的写者。
        .scrollPosition($scrollPosition)
        .defaultScrollAnchor(.bottom, for: .initialOffset)
        .defaultScrollAnchor(.top, for: .alignment)
        .scrollEdgeEffectStyle(.soft, for: .top)
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.interactively)
        .contentShape(Rectangle())
        .simultaneousGesture(
            TapGesture().onEnded {
                onDismissKeyboard()
            }
        )
        .onScrollPhaseChange { _, phase in
            switch phase {
            case .tracking, .interacting:
                onDismissKeyboard()
                cancelExplicitBottomAnimation()
                cancelPendingStreamFollow()
                cancelGenerationEndSettle()
                runtime.userScrollActive = true
                runtime.lastUserScrollActivityAt = Date()
                cancelPendingConversationScroll()
            case .decelerating:
                // Deceleration can also be produced by programmatic anchoring or system
                // layout compensation. Only continue a session that began with a real
                // user gesture; never let deceleration itself initiate pause.
                if runtime.userScrollActive {
                    runtime.lastUserScrollActivityAt = Date()
                }
            case .idle:
                let endedUserScroll = runtime.userScrollActive
                let endedExplicitBottomAnimation = runtime.explicitBottomAnimationActive
                if endedUserScroll {
                    runtime.lastUserScrollActivityAt = Date()
                }
                runtime.userScrollActive = false
                if endedUserScroll {
                    resumeFollowAfterUserScrollEndedIfNeeded()
                }
                if endedExplicitBottomAnimation {
                    completeExplicitBottomAnimationIfNeeded()
                }
            case .animating:
                // Programmatic ScrollPosition writes can report `.animating`; do not let
                // that phase masquerade as a fresh user gesture. If it follows a real user
                // session, close that session once and let the bottom-distance check decide.
                let endedUserScroll = runtime.userScrollActive
                if endedUserScroll {
                    runtime.lastUserScrollActivityAt = Date()
                    runtime.userScrollActive = false
                    resumeFollowAfterUserScrollEndedIfNeeded()
                }
            @unknown default:
                break
            }
        }
        .onScrollGeometryChange(for: ChatSwiftUIScrollGeometry.self) { geo in
            ChatSwiftUIScrollGeometry(
                distanceToBottom: max(0, geo.contentSize.height - geo.visibleRect.maxY),
                visibleHeight: max(1, geo.visibleRect.height),
                contentHeight: geo.contentSize.height
            )
        } action: { previousGeometry, geometry in
            runtime.hasMeasuredScrollGeometry = true
            runtime.latestScrollGeometry = geometry
            let distanceToBottom = geometry.distanceToBottom
            runtime.scrollVisibleRectMaxY = distanceToBottom
            if runtime.userScrollActive {
                runtime.lastUserScrollActivityAt = Date()
            }
            let messages = messagesProvider()
            let visibleHeight = geometry.visibleHeight
            let liveRenderingThreshold = max(
                ChatLayout.liveRenderingLODMinDistance,
                visibleHeight * ChatLayout.liveRenderingLODScreenFactor
            )
            let previousFollowMode = runtime.followMode
            var next = viewportState
            next.userDragging = runtime.userScrollActive
            let commands = ChatViewportReducer.reduceGeometry(
                ChatViewportGeometrySnapshot(
                    atBottom: distanceToBottom <= ChatLayout.bottomStickThreshold,
                    isContentScrollable: geometry.contentHeight > visibleHeight + ChatLayout.bottomStickThreshold,
                    liveRenderingFarFromBottom: distanceToBottom > liveRenderingThreshold,
                    userScrollActive: runtime.userScrollActive
                ),
                hasMessages: !messages.isEmpty,
                state: &next,
                environment: ChatViewportEnvironment(
                    followEnabled: followGeneration,
                    generationActive: isGenerationActive || isLoading
                )
            )
            if ChatSwiftUINearBottomResumePolicy.shouldResume(
                followPaused: next.followPaused,
                userScrollActive: runtime.userScrollActive,
                userScrollJustEnded: false,
                distanceToBottom: distanceToBottom
            ) {
                // 96pt 只表达「真实用户已回到底部附近」的恢复意图；物理
                // true-bottom 仍由 40pt geometry 判定，不能在这里伪造 isAtBottom。
                next.followPaused = false
                next.showScrollToBottom = false
                next.liveRenderingFarFromBottom = false
            }
            syncFollowMode(from: next)
            let reachedExplicitBottom = runtime.explicitBottomAnimationActive && next.isAtBottom
            if previousFollowMode != runtime.followMode {
                debugFollow(runtime.followMode == .pausedForUser ? "pause" : "resume", messages: messages)
            }
            let shouldFollowMeasuredGrowth = shouldFollowMeasuredContentGrowth(
                previous: previousGeometry,
                current: geometry,
                state: next
            )
            let didObserveMeasuredGrowth = shouldTrackMeasuredContentGrowth(
                previous: previousGeometry,
                current: geometry,
                state: next
            )
            let shouldReanchorExplicitBottomLayout = ChatSwiftUIExplicitBottomLayoutPolicy.shouldReanchor(
                forceActive: explicitBottomForcesLiveTail,
                explicitBottomAnimationActive: runtime.explicitBottomAnimationActive,
                state: next,
                userScrollActive: runtime.userScrollActive,
                previousContentHeight: previousGeometry.contentHeight,
                currentContentHeight: geometry.contentHeight
            )
            if shouldReanchorExplicitBottomLayout,
               runtime.explicitBottomSettleTask != nil {
                runtime.explicitBottomSettleLastLayoutChangeAt = Date()
            }
            let shouldReanchorViewportShrink = shouldReanchorAfterViewportShrink(
                previous: previousGeometry,
                current: geometry,
                state: next
            )
            publish(next)
            releaseExplicitBottomLiveTailIfSettled()
            if reachedExplicitBottom {
                completeExplicitBottomAnimationIfNeeded()
            }
            let didIssueFollow = executeViewportCommands(
                commands,
                state: next,
                event: signal.event,
                // Measured growth is the sole live-height writer.
                animateMeasuredGrowth: shouldFollowMeasuredGrowth
                    && !shouldReanchorExplicitBottomLayout
            )
            if didObserveMeasuredGrowth, runtime.generationEndSettleTask != nil {
                runtime.generationEndSettleLastGrowthAt = Date()
            }
            if shouldReanchorExplicitBottomLayout {
                if !didIssueFollow {
                    scrollToBottomIfScrollable()
                }
            } else if shouldFollowMeasuredGrowth {
                if !didIssueFollow {
                    followMeasuredStreamGrowthToBottom()
                }
            } else if shouldReanchorViewportShrink,
                      !didIssueFollow,
                      ChatSwiftUIBottomWritePolicy.canIssueImmediateWrite(
                        explicitBottomAnimationActive: runtime.explicitBottomAnimationActive
                      ) {
                if reduceMotion {
                    scrollToBottomAnchor()
                } else {
                    withAnimation(.easeOut(duration: 0.22)) {
                        scrollToBottomAnchor(disableAnimations: false)
                    }
                }
            }
        }
        .onAppear {
            debugFollow("appear", messages: messages)
            handleSignal(signal, messages: messages)
            consumeExternalScrollToBottomTriggerIfNeeded()
        }
        .onDisappear {
            cancelExplicitBottomAnimation()
            cancelPendingStreamFollow()
            cancelPendingConversationScroll()
            cancelGenerationEndSettle()
        }
        .onChange(of: signal) { _, newSignal in
            handleSignal(newSignal, messages: messagesProvider())
        }
        .onChange(of: scrollToBottomTrigger) { _, trigger in
            consumeExternalScrollToBottomTriggerIfNeeded(trigger)
        }
        .transaction(value: signal) { transaction in
            if signal.event == .assistantStreamDelta {
                transaction.animation = nil
            }
        }
    }

    @ViewBuilder
    private var emptyTimelineContent: some View {
        ChatEmptyState()
    }

    private var bottomAnchor: some View {
        Color.clear
            .frame(height: ChatLayout.bottomRestGap)
            .id(ChatLayout.bottomAnchorID)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    private func makeTimelinePlan(messages: [UIMessage]) -> ChatTimelinePlan {
        ChatPerfTrace.measure("TimelineProjection", count: { messages.count }) {
            ChatTimelinePlanner.build(
                messages: messages,
                event: signal.event,
                streamedMessageIDs: streamedMessageIDs,
                includePendingAssistant: (isGenerationActive || isLoading) && messages.last?.role == MessageRole.user,
                // SwiftUI 列表路径不消费 renderToken,跳过逐行 token 计算。
                includeRenderTokens: false
            )
        }
    }

    @ViewBuilder
    private func timelineEntryView(
        _ entry: ChatTimelineEntry,
        signatures: ChatRowSettingSignatures
    ) -> some View {
        switch entry {
        case let .message(messageEntry):
            messageRow(messageEntry, signatures: signatures)
        case .pendingAssistant:
            ChatAssistantPendingResponseView()
                .frame(maxWidth: .infinity, alignment: .leading)
        case .bottomAnchor:
            EmptyView()
        }
    }

    private func messageRow(
        _ entry: ChatTimelineMessageEntry,
        signatures: ChatRowSettingSignatures
    ) -> some View {
        let row = entry.rowModel
        let renderState = swiftUIRenderState(for: entry)
        let variantInfo = variantInfoProvider(row.index)
        let updatesSuspended = !explicitBottomForcesLiveTail && ChatSwiftUIStreamingTailRenderPolicy.shouldSuspend(
            isLastAssistant: row.isLast && row.role == MessageRole.assistant,
            hasEverStreamed: row.hasEverStreamed,
            messageID: row.messageId,
            visibility: streamingTailVisibility
        )
        let contentHash: Int
        if updatesSuspended {
            contentHash = swiftUIContentHashCache.suspendedStreamingTailLayoutToken(for: row)
        } else if row.isStreaming {
            contentHash = swiftUIContentHashCache.streamingTailLayoutToken(for: row)
        } else {
            contentHash = swiftUIContentHashCache.contentHash(for: row)
        }
        let digest = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: contentHash,
            isGenerationActive: isGenerationActive,
            displaySettingSignature: signatures.display,
            generativeUiSettingSignature: signatures.generative,
            hasMultipleVariants: variantInfo?.hasMultipleVariants == true,
            reasoningLevelLabel: reasoningLevelLabel
        )
        let bubble = ChatSwiftUIMessageBubble(
            entryID: entry.id,
            renderDigest: digest,
            updatesSuspended: updatesSuspended,
            row: row,
            variantInfo: variantInfo,
            renderState: renderState,
            isGenerationActive: isGenerationActive,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
            onAction: onAction
        )
        .equatable()
        .environment(workspaceStore)
        .fixedSize(horizontal: false, vertical: true)
        .frame(
            maxWidth: .infinity,
            alignment: row.role == MessageRole.user ? .topTrailing : .topLeading
        )
        .padding(.vertical, row.role == MessageRole.user ? ChatLayout.userMessageRowVerticalPadding : 0)
        .id(entry.id)
        .transition(userMessageInsertionTransition(for: row))
        .zIndex(row.canAnimateInsertion ? 1 : 0)

        return bubble.modifier(
            ChatSwiftUIStreamingTailVisibilityModifier(
                active: row.isLast && row.role == MessageRole.assistant,
                onVisibilityChanged: { isVisible in
                    updateStreamingTailVisibility(messageID: row.messageId, isVisible: isVisible)
                }
            )
        )
    }

    private func userMessageInsertionTransition(for row: ChatMessageRowModel) -> AnyTransition {
        guard !reduceMotion,
              row.canAnimateInsertion,
              row.role == MessageRole.user else { return .identity }
        return .chatUserMessageSend
    }

    private func consumeExternalScrollToBottomTriggerIfNeeded(_ trigger: Int? = nil) {
        let currentTrigger = trigger ?? scrollToBottomTrigger
        guard currentTrigger != runtime.lastScrollToBottomTrigger else { return }
        runtime.lastScrollToBottomTrigger = currentTrigger
        cancelPendingConversationScroll()
        cancelPendingStreamFollow()
        cancelGenerationEndSettle()
        runtime.followMode = .followingBottom
        let plan = ChatSwiftUIExplicitBottomPolicy.plan(
            current: viewportState,
            source: scrollToBottomSource,
            distanceToBottom: currentBottomDistance
        )
        publish(plan.viewportState)
        syncFollowMode(from: plan.viewportState)
        if plan.animated {
            beginExplicitBottomTrace()
            explicitBottomForcesLiveTail = true
            beginExplicitBottomAnimation()
        } else {
            cancelExplicitBottomAnimation()
            beginExplicitBottomTrace()
            explicitBottomForcesLiveTail = true
            scrollToBottomAnchor()
        }
    }

    private func handleSignal(
        _ signal: ChatMessageUpdateSignal,
        messages: [UIMessage]
    ) {
        updateStreamedMessageIDs(event: signal.event, messages: messages)

        switch signal.event {
        case .conversationLoaded, .conversationSwitched:
            // 归位:重置跟随态 + 延迟滚到底(等消息布局实例化)。
            cancelPendingStreamFollow()
            cancelExplicitBottomAnimation()
            resetLatestGeometryForContentReplacement()
            cancelGenerationEndSettle()
            runtime.userScrollActive = false
            runtime.followMode = followGeneration ? .followingBottom : .idle
            publish(ChatViewportState())
            scheduleConversationBottomAnchor()
        case .branchChanged:
            // 编辑重发:先保持当前阅读锚,不要把截断后的用户消息强拉到底部;
            // 后续 assistant 内容真实增长时,由底部锚点跟随。
            cancelPendingConversationScroll()
            cancelGenerationEndSettle()
            runtime.followMode = followGeneration ? .followingBottom : .idle
            var next = viewportState
            next.followPaused = false
            next.userDragging = false
            next.isAtBottom = true
            next.isContentScrollable = false
            next.liveRenderingFarFromBottom = false
            next.showScrollToBottom = false
            resetLatestGeometryForContentReplacement()
            publish(next)
        case .userMessageAppended:
            // 用户刚发消息:意图明确是看回复,进入贴底跟随并锚到底部。
            // 第一屏内不滚动;超过一屏后由 stream follow 维持底部基线。
            cancelPendingConversationScroll()
            cancelGenerationEndSettle()
            runtime.followMode = followGeneration ? .followingBottom : .idle
            var next = viewportState
            next.followPaused = false
            next.userDragging = false
            next.showScrollToBottom = false
            publish(next)
            scrollToBottomIfScrollable()
        case .assistantStreamDelta:
            cancelGenerationEndSettle()
            runtime.followDebugTick &+= 1
            // delta 到来:若 idle 则转 following。pausedForUser 时【绝不】自动恢复(C1 契约)。
            if !followGeneration {
                runtime.followMode = .idle
            } else if runtime.followMode == .idle, isGenerationActive || isLoading {
                runtime.followMode = .followingBottom
            }
            if runtime.followDebugTick % 20 == 0 {
                debugFollow("delta", messages: messages)
            }
        case .generationCompleted, .generationFailed, .generationCancelled,
             .generationHandedOffToBackground:
            if ChatSwiftUIExplicitBottomLiveTailPolicy.shouldCancelAtTerminal(
                forceActive: explicitBottomForcesLiveTail,
                hasAssistantTail: messages.last?.role == MessageRole.assistant
            ) {
                cancelExplicitBottomAnimation()
            }
            // 最终 Markdown 解析/思考块收起会晚于 terminal signal 落地。保留一个
            // 有界收敛窗口，期间只响应真实 contentHeight 增长，结束后再回 idle。
            if followGeneration, runtime.followMode == .followingBottom {
                scheduleGenerationEndSettle()
            } else {
                cancelGenerationEndSettle()
                runtime.followMode = .idle
            }
        case .assistantStreamClosed, .toolCallStarted, .toolResultAppended,
             .awaitingToolApproval:
            // 流式中途事件(工具调用等):跟随当前态。
            if followGeneration, runtime.followMode == .followingBottom {
                scheduleStreamBottomFollow()
            }
        case .settingsRefreshed:
            break
        }
    }

    private func scheduleConversationBottomAnchor() {
        cancelPendingConversationScroll()
        runtime.conversationScrollToken &+= 1
        let token = runtime.conversationScrollToken
        runtime.conversationScrollTask = Task { @MainActor in
            let delays: [UInt64] = [
                50_000_000,
                100_000_000,
                200_000_000,
                350_000_000
            ]
            for delay in delays {
                do {
                    try await Task.sleep(nanoseconds: delay)
                } catch {
                    return
                }
                switch ChatSwiftUIConversationAnchorRetryPolicy.decision(
                    taskCancelled: Task.isCancelled,
                    tokenMatches: token == runtime.conversationScrollToken,
                    canRunNow: canRunConversationBottomAnchorRetry,
                    isAlreadyAnchored: runtime.hasMeasuredScrollGeometry && viewportState.isAtBottom
                ) {
                case .abort:
                    return
                case .wait:
                    continue
                case .attempt:
                    // Entry anchoring is also what bootstraps geometry. Do not require
                    // a prior scrollability sample before issuing the semantic edge write.
                    scrollToBottomAnchor()
                }
            }
            if token == runtime.conversationScrollToken {
                runtime.conversationScrollTask = nil
            }
        }
    }

    private func cancelPendingConversationScroll() {
        runtime.conversationScrollToken &+= 1
        runtime.conversationScrollTask?.cancel()
        runtime.conversationScrollTask = nil
    }

    private func beginExplicitBottomTrace() {
        if runtime.explicitBottomTrace != nil {
            ChatPerfTrace.event("BottomResumeCancelled")
            ChatPerfTrace.end(&runtime.explicitBottomTrace)
        }
        runtime.explicitBottomTrace = ChatPerfTrace.begin("BottomResume")
    }

    private func beginExplicitBottomAnimation() {
        cancelExplicitBottomSettle()
        runtime.explicitBottomAnimationToken &+= 1
        let token = runtime.explicitBottomAnimationToken
        runtime.explicitBottomAnimationActive = true
        guard !reduceMotion else {
            scrollToBottomAnchor()
            completeExplicitBottomAnimationIfNeeded()
            return
        }
        withAnimation(.easeOut(duration: 0.2), completionCriteria: .logicallyComplete) {
            scrollToBottomAnchor(disableAnimations: false)
        } completion: {
            guard token == runtime.explicitBottomAnimationToken else { return }
            completeExplicitBottomAnimationIfNeeded()
        }
    }

    private func completeExplicitBottomAnimationIfNeeded() {
        guard runtime.explicitBottomAnimationActive else { return }
        runtime.explicitBottomAnimationActive = false
        runtime.explicitBottomAnimationToken &+= 1
        if runtime.explicitBottomSettleTask != nil {
            runtime.explicitBottomSettleLastLayoutChangeAt = Date()
        }
        guard followGeneration else {
            runtime.followMode = .idle
            return
        }
        guard !runtime.userScrollActive, !viewportState.followPaused else { return }
        runtime.followMode = .followingBottom
        if isGenerationActive || isLoading {
            scheduleStreamBottomFollow()
        } else if explicitBottomForcesLiveTail {
            // The live tail may have published its real height while the explicit
            // animation owned scrolling. Re-issue the same semantic anchor once;
            // later height changes are handled by explicit-bottom settle ownership.
            scrollToBottomIfScrollable()
        } else {
            runtime.followMode = .idle
        }
    }

    private func cancelExplicitBottomAnimation() {
        cancelExplicitBottomSettle()
        explicitBottomForcesLiveTail = false
        if runtime.explicitBottomTrace != nil {
            ChatPerfTrace.event("BottomResumeCancelled")
            ChatPerfTrace.end(&runtime.explicitBottomTrace)
        }
        if runtime.explicitBottomAnimationActive {
            runtime.explicitBottomAnimationActive = false
            runtime.explicitBottomAnimationToken &+= 1
        }
    }

    private func updateStreamingTailVisibility(messageID: String, isVisible: Bool) {
        if isVisible {
            let next = ChatSwiftUIStreamingTailVisibilityState(messageID: messageID, isVisible: true)
            if next != streamingTailVisibility {
                streamingTailVisibility = next
            }
            releaseExplicitBottomLiveTailIfSettled()
        } else if streamingTailVisibility.messageID == messageID {
            guard streamingTailVisibility.isVisible != false else { return }
            streamingTailVisibility.isVisible = false
        }
    }

    private func releaseExplicitBottomLiveTailIfSettled() {
        guard explicitBottomForcesLiveTail else { return }
        let tailMessageID: String?
        if let last = messagesProvider().last, last.role == MessageRole.assistant {
            tailMessageID = ChatMessageProjector.messageId(for: last)
        } else {
            tailMessageID = nil
        }
        guard ChatSwiftUIExplicitBottomLiveTailPolicy.shouldRelease(
            forceActive: explicitBottomForcesLiveTail,
            messageID: tailMessageID,
            visibility: streamingTailVisibility,
            distanceToBottom: currentBottomDistance
        ) else { return }
        scheduleExplicitBottomSettle()
    }

    private func scheduleExplicitBottomSettle() {
        guard runtime.explicitBottomSettleTask == nil else { return }
        ChatPerfTrace.event("BottomTailVisible")
        runtime.explicitBottomSettleLastLayoutChangeAt = Date()
        runtime.explicitBottomSettleTask = Task { @MainActor in
            for elapsedFrames in 1...ChatSwiftUIGenerationEndSettlePolicy.maxFrames {
                do {
                    try await Task.sleep(
                        nanoseconds: ChatSwiftUIGenerationEndSettlePolicy.frameDurationNanoseconds
                    )
                } catch {
                    return
                }
                guard !Task.isCancelled, explicitBottomForcesLiveTail else { return }
                let quietElapsed = Date().timeIntervalSince(
                    runtime.explicitBottomSettleLastLayoutChangeAt
                )
                guard ChatSwiftUIGenerationEndSettlePolicy.shouldFinish(
                    elapsedFrames: elapsedFrames,
                    quietElapsed: quietElapsed,
                    explicitBottomAnimationActive: runtime.explicitBottomAnimationActive
                ) else { continue }
                scrollToBottomIfScrollable()
                explicitBottomForcesLiveTail = false
                runtime.explicitBottomSettleTask = nil
                ChatPerfTrace.event("BottomResumeSettled")
                ChatPerfTrace.end(&runtime.explicitBottomTrace)
                if !(isGenerationActive || isLoading), runtime.generationEndSettleTask == nil {
                    runtime.followMode = .idle
                }
                return
            }
        }
    }

    private func cancelExplicitBottomSettle() {
        runtime.explicitBottomSettleTask?.cancel()
        runtime.explicitBottomSettleTask = nil
    }

    private var canRunConversationBottomAnchorRetry: Bool {
        ChatViewportPolicy.canRunConversationBottomAnchorRetry(
            userScrollActive: runtime.userScrollActive,
            followPaused: viewportState.followPaused
        )
    }

    private func scheduleStreamBottomFollow() {
        guard runtime.streamFollowTask == nil else { return }
        runtime.streamFollowTask = Task { @MainActor in
            defer {
                runtime.streamFollowTask = nil
            }
            do {
                // Let the current layout transaction settle before the one-shot semantic
                // catch-up, while keeping the single proven bottom-anchor positioning shape.
                try await Task.sleep(nanoseconds: 24_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled,
                  followGeneration,
                  runtime.followMode == .followingBottom,
                  !runtime.userScrollActive,
                  !runtime.explicitBottomAnimationActive,
                  runtime.generationEndSettleTask == nil,
                  streamFollowContentCanScroll else { return }
            scrollToBottomAnchor()
        }
    }

    private func cancelPendingStreamFollow() {
        runtime.streamFollowTask?.cancel()
        runtime.streamFollowTask = nil
        runtime.measuredGrowthFollowTask?.cancel()
        runtime.measuredGrowthFollowTask = nil
    }

    private func scrollToBottomIfScrollable(disableAnimations: Bool = true) {
        guard ChatSwiftUIBottomWritePolicy.canIssueImmediateWrite(
            explicitBottomAnimationActive: runtime.explicitBottomAnimationActive
        ), currentContentScrollable else { return }
        scrollToBottomAnchor(disableAnimations: disableAnimations)
    }

    private func followMeasuredStreamGrowthToBottom() {
        guard followGeneration,
              runtime.followMode == .followingBottom,
              !viewportState.followPaused,
              !viewportState.userDragging,
              !runtime.userScrollActive,
              !runtime.explicitBottomAnimationActive,
              currentContentScrollable else { return }
        runtime.measuredGrowthRevision &+= 1
        guard runtime.measuredGrowthFollowTask == nil else { return }

        let leadingRevision = runtime.measuredGrowthRevision
        // Follow the first real geometry change immediately. TextKit can publish more
        // changes in the same layout pass; one task drains only those newer revisions.
        runtime.measuredGrowthFollowTask = Task { @MainActor in
            defer { runtime.measuredGrowthFollowTask = nil }
            var drainedRevision = leadingRevision
            while !Task.isCancelled {
                await Task.yield()
                guard !Task.isCancelled,
                      followGeneration,
                      runtime.followMode == .followingBottom,
                      !viewportState.followPaused,
                      !viewportState.userDragging,
                      !runtime.userScrollActive,
                      !runtime.explicitBottomAnimationActive,
                      currentContentScrollable else { return }
                guard runtime.measuredGrowthRevision != drainedRevision else { return }
                drainedRevision = runtime.measuredGrowthRevision
                scrollToBottomAnchor()
            }
        }
        scrollToBottomAnchor()
    }

    private func scrollToBottomAnchor(disableAnimations: Bool = true) {
        guard disableAnimations else {
            scrollPosition.scrollTo(edge: .bottom)
            return
        }
        var transaction = Transaction()
        transaction.disablesAnimations = true
        transaction.animation = nil
        withTransaction(transaction) {
            scrollPosition.scrollTo(edge: .bottom)
        }
    }

    private func liveMarkdownRenderingEnabled(for row: ChatMessageRowModel) -> Bool {
        guard row.isLast, row.role == MessageRole.assistant else { return true }
        return !viewportState.liveRenderingFarFromBottom
    }

    private func swiftUIRenderState(for entry: ChatTimelineMessageEntry) -> ChatRenderState {
        let row = entry.rowModel
        if let state = ChatSwiftUICleanListRenderPolicy.liveTailState(for: row) {
            return state
        }
        if let state = ChatSwiftUICleanListRenderPolicy.nonLiveTailState(for: row) {
            return state
        }
        return swiftUIRenderStateStore.stateForEntry(
            entry,
            isLiveRenderingFarFromBottom: viewportState.liveRenderingFarFromBottom
        )
    }

    private func updateStreamedMessageIDs(event: ChatEvent, messages: [UIMessage]) {
        // delta 热路径:chunk 不增删消息,只需登记尾行 id。全量 id 重建/求交是
        // O(消息数) 的 KMP 桥接,且无条件重写 @State 会让每个 chunk 都额外触发
        // 一轮 body 重求值(即使集合没变)。结构性事件才走完整清理分支。
        if event == .assistantStreamDelta {
            if event.remembersStreamingRenderer,
               let last = messages.last,
               last.role == MessageRole.assistant {
                let lastID = ChatMessageProjector.messageId(for: last)
                if !streamedMessageIDs.contains(lastID) {
                    streamedMessageIDs.insert(lastID)
                }
            }
            return
        }
        let currentIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
        switch event {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            streamedMessageIDs.removeAll()
            swiftUIRenderStateStore.removeAll()
            swiftUIContentHashCache.removeAll()
            streamingTailVisibility = ChatSwiftUIStreamingTailVisibilityState()
        default:
            if event.remembersStreamingRenderer,
               let last = messages.last,
               last.role == MessageRole.assistant {
                streamedMessageIDs.insert(ChatMessageProjector.messageId(for: last))
            }
        }
        let retained = streamedMessageIDs.intersection(currentIDs)
        if retained != streamedMessageIDs {
            streamedMessageIDs = retained
        }
        swiftUIRenderStateStore.retain(ids: currentIDs)
        swiftUIContentHashCache.retain(ids: currentIDs)
    }

    private var bottomFollowResumeThreshold: CGFloat {
        // 用户滑回底部附近(≤此阈值)才从 pausedForUser 恢复跟随。
        ChatLayout.nearBottomResumeThreshold
    }

    private var currentBottomDistance: CGFloat {
        runtime.scrollVisibleRectMaxY ?? .greatestFiniteMagnitude
    }

    private var streamFollowContentCanScroll: Bool {
        currentContentScrollable
    }

    private var currentContentScrollable: Bool {
        runtime.latestScrollGeometry.visibleHeight > 1 &&
            runtime.latestScrollGeometry.contentHeight > runtime.latestScrollGeometry.visibleHeight + ChatLayout.bottomStickThreshold
    }

    private func resetLatestGeometryForContentReplacement() {
        runtime.hasMeasuredScrollGeometry = false
        runtime.latestScrollGeometry = ChatSwiftUIScrollGeometry(
            distanceToBottom: 0,
            visibleHeight: max(1, runtime.latestScrollGeometry.visibleHeight),
            contentHeight: 0
        )
        runtime.scrollVisibleRectMaxY = 0
    }

    private func syncFollowMode(from state: ChatViewportState) {
        guard followGeneration else {
            runtime.followMode = .idle
            return
        }
        if state.followPaused {
            runtime.followMode = .pausedForUser
        } else if isGenerationActive || isLoading || runtime.followMode == .followingBottom {
            runtime.followMode = .followingBottom
        } else {
            runtime.followMode = .idle
        }
    }

    private func resumeFollowAfterUserScrollEndedIfNeeded() {
        let shouldResume = followGeneration && ChatSwiftUINearBottomResumePolicy.shouldResume(
            followPaused: viewportState.followPaused,
            userScrollActive: false,
            userScrollJustEnded: true,
            distanceToBottom: runtime.latestScrollGeometry.distanceToBottom
        )
        var next = viewportState
        next.userDragging = false
        if shouldResume {
            next.followPaused = false
            next.showScrollToBottom = false
            next.liveRenderingFarFromBottom = false
        }
        syncFollowMode(from: next)
        publish(next)
        guard followGeneration, !next.followPaused else { return }
        ChatPerfTrace.event("BottomResumeUser")
        if isGenerationActive || isLoading {
            scheduleStreamBottomFollow()
        }
    }

    private func shouldFollowMeasuredContentGrowth(
        previous: ChatSwiftUIScrollGeometry,
        current: ChatSwiftUIScrollGeometry,
        state: ChatViewportState
    ) -> Bool {
        ChatSwiftUIMeasuredGrowthFollowPolicy.shouldFollow(
            generationActive: isGenerationActive || isLoading,
            endSettleActive: runtime.generationEndSettleTask != nil,
            explicitBottomCatchUpActive: explicitBottomForcesLiveTail,
            explicitBottomAnimationActive: runtime.explicitBottomAnimationActive,
            followEnabled: followGeneration,
            followMode: runtime.followMode,
            state: state,
            userScrollActive: runtime.userScrollActive,
            previousContentHeight: previous.contentHeight,
            currentContentHeight: current.contentHeight,
            distanceToBottom: current.distanceToBottom
        )
    }

    private func shouldTrackMeasuredContentGrowth(
        previous: ChatSwiftUIScrollGeometry,
        current: ChatSwiftUIScrollGeometry,
        state: ChatViewportState
    ) -> Bool {
        ChatSwiftUIMeasuredGrowthFollowPolicy.shouldTrackGrowth(
            generationActive: isGenerationActive || isLoading,
            endSettleActive: runtime.generationEndSettleTask != nil,
            explicitBottomCatchUpActive: explicitBottomForcesLiveTail,
            followEnabled: followGeneration,
            followMode: runtime.followMode,
            state: state,
            userScrollActive: runtime.userScrollActive,
            previousContentHeight: previous.contentHeight,
            currentContentHeight: current.contentHeight
        )
    }

    private func scheduleGenerationEndSettle() {
        runtime.generationEndSettleTask?.cancel()
        ChatPerfTrace.end(&runtime.generationEndSettleTrace)
        runtime.generationEndSettleTrace = ChatPerfTrace.begin("TerminalSettle")
        runtime.generationEndSettleToken &+= 1
        let token = runtime.generationEndSettleToken
        runtime.generationEndSettleLastGrowthAt = Date()
        scrollToBottomIfScrollable()
        runtime.generationEndSettleTask = Task { @MainActor in
            for elapsedFrames in 1...ChatSwiftUIGenerationEndSettlePolicy.maxFrames {
                do {
                    try await Task.sleep(
                        nanoseconds: ChatSwiftUIGenerationEndSettlePolicy.frameDurationNanoseconds
                    )
                } catch {
                    return
                }
                guard !Task.isCancelled, token == runtime.generationEndSettleToken else { return }
                let quietElapsed = Date().timeIntervalSince(runtime.generationEndSettleLastGrowthAt)
                guard ChatSwiftUIGenerationEndSettlePolicy.shouldFinish(
                    elapsedFrames: elapsedFrames,
                    quietElapsed: quietElapsed,
                    explicitBottomAnimationActive: runtime.explicitBottomAnimationActive
                ) else { continue }
                scrollToBottomIfScrollable()
                runtime.followMode = .idle
                runtime.generationEndSettleTask = nil
                ChatPerfTrace.end(&runtime.generationEndSettleTrace)
                return
            }
        }
    }

    private func cancelGenerationEndSettle() {
        guard runtime.generationEndSettleTask != nil else { return }
        ChatPerfTrace.event("TerminalSettleCancelled")
        runtime.generationEndSettleToken &+= 1
        runtime.generationEndSettleTask?.cancel()
        runtime.generationEndSettleTask = nil
        ChatPerfTrace.end(&runtime.generationEndSettleTrace)
    }

    private func shouldReanchorAfterViewportShrink(
        previous: ChatSwiftUIScrollGeometry,
        current: ChatSwiftUIScrollGeometry,
        state: ChatViewportState
    ) -> Bool {
        guard !state.followPaused,
              !state.userDragging,
              !runtime.userScrollActive else { return false }
        guard previous.visibleHeight > 1,
              current.visibleHeight > 1,
              previous.distanceToBottom <= ChatLayout.bottomStickThreshold else { return false }
        guard previous.visibleHeight - current.visibleHeight > 24 else { return false }
        guard current.contentHeight > current.visibleHeight + ChatLayout.bottomStickThreshold else { return false }
        return current.distanceToBottom > 1
    }

    @discardableResult
    private func executeViewportCommands(
        _ commands: [ChatViewportScrollCommand],
        state: ChatViewportState,
        event: ChatEvent,
        animateMeasuredGrowth: Bool
    ) -> Bool {
        guard !commands.isEmpty else { return false }
        var didIssueFollow = false
        for command in commands {
            switch command {
            case .none, .showBottomButton(_):
                break
            case .initialAnchor, .resetForConversationSwitch:
                scheduleConversationBottomAnchor()
            case let .followBottom(animated, _, _):
                guard !state.followPaused, !state.userDragging else { continue }
                guard ChatSwiftUIBottomWritePolicy.canIssueImmediateWrite(
                    explicitBottomAnimationActive: runtime.explicitBottomAnimationActive
                ) else {
                    didIssueFollow = true
                    continue
                }
                if animateMeasuredGrowth {
                    followMeasuredStreamGrowthToBottom()
                } else if animated && event == .assistantStreamDelta {
                    scheduleStreamBottomFollow()
                } else {
                    scrollToBottomAnchor(disableAnimations: !animated)
                }
                didIssueFollow = true
            }
        }
        return didIssueFollow
    }

    private func publish(_ next: ChatViewportState) {
        guard next != viewportState else { return }
        viewportState = next
        onViewportStateChange(next)
    }

    private func debugFollow(_ event: String, messages: [UIMessage]) {
        #if DEBUG
        print(
            "[AA-FOLLOW] \(event) messages=\(messages.count) active=\(isGenerationActive ? 1 : 0) " +
            "loading=\(isLoading ? 1 : 0) follow=\(followGeneration ? 1 : 0) " +
            "mode=\(runtime.followMode == .followingBottom ? "following" : runtime.followMode == .pausedForUser ? "paused" : "idle") " +
            "runtime.userScrollActive=\(runtime.userScrollActive ? 1 : 0) " +
            String(format: "distance=%.1f resume=%.1f", currentBottomDistance, bottomFollowResumeThreshold)
        )
        #endif
    }

}

private struct ChatSwiftUIMessageBubble: View, @MainActor Equatable {
    let entryID: String
    let renderDigest: ChatRowDigest
    let updatesSuspended: Bool
    let row: ChatMessageRowModel
    let variantInfo: IOSConversationStore.VariantInfo?
    let renderState: ChatRenderState
    let isGenerationActive: Bool
    let displaySetting: DisplaySetting
    let generativeUiSetting: GenerativeUiSetting
    let reasoningLevelLabel: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        MessageBubbleView(
            message: row.message,
            messageIndex: row.index,
            variantInfo: variantInfo,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            onRegenerate: { onAction(.regenerate(messageId: row.messageId)) },
            onRequestEdit: { currentText in
                onAction(.requestEdit(messageId: row.messageId, currentText: currentText))
            },
            onEdit: { newText in onAction(.edit(messageId: row.messageId, newText: newText)) },
            onDelete: { onAction(.delete(messageId: row.messageId)) },
            onSelectVariant: { variantIndex in
                onAction(.selectVariant(messageId: row.messageId, variantIndex: variantIndex))
            },
            onGenerativeWidgetAction: { prompt in onAction(.generativeWidget(prompt: prompt)) },
            onModifyGeneratedImage: { imageURL, prompt, aspectRatio in
                onAction(.modifyGeneratedImage(urlString: imageURL, prompt: prompt, aspectRatio: aspectRatio))
            },
            onOpenMiniApp: { appId in onAction(.openMiniApp(appId: appId)) },
            onOpenMiniApps: { onAction(.openMiniApps) },
            isGenerating: row.isLast && isGenerationActive,
            isChatGenerationActive: isGenerationActive,
            isLastMessage: row.isLast,
            hasEverStreamed: renderState.hasEverStreamed,
            liveMarkdownRenderingEnabled: renderState.liveRenderingEnabled,
            frozenMarkdownSnapshot: renderState.frozenMarkdownSnapshot,
            reasoningLevelLabel: reasoningLevelLabel
        )
    }

    static func == (lhs: ChatSwiftUIMessageBubble, rhs: ChatSwiftUIMessageBubble) -> Bool {
        guard lhs.entryID == rhs.entryID,
              lhs.updatesSuspended == rhs.updatesSuspended else { return false }
        if lhs.updatesSuspended {
            return true
        }
        return lhs.row.messageId == rhs.row.messageId &&
            lhs.row.role == rhs.row.role &&
            lhs.row.index == rhs.row.index &&
            lhs.row.isLast == rhs.row.isLast &&
            lhs.row.hasEverStreamed == rhs.row.hasEverStreamed &&
            lhs.row.canAnimateInsertion == rhs.row.canAnimateInsertion &&
            lhs.variantInfo == rhs.variantInfo &&
            lhs.renderState == rhs.renderState &&
            lhs.renderDigest == rhs.renderDigest &&
            lhs.isGenerationActive == rhs.isGenerationActive
    }
}

private struct ChatListMessageRenderModel {
    let row: ChatMessageRowModel
    let variantInfo: IOSConversationStore.VariantInfo?
    let renderState: ChatRenderState
    let isGenerationActive: Bool
    let renderIdentity: String
    let liveTailModel: ChatLiveTailModel?
}

private struct NativeTimelineMessageBubble: View, @MainActor Equatable {
    let entryID: String
    let renderDigest: ChatRowDigest?
    let model: ChatListMessageRenderModel
    let displaySetting: DisplaySetting
    let generativeUiSetting: GenerativeUiSetting
    let displaySettingSignature: String
    let generativeUiSettingSignature: String
    let reasoningLevelLabel: String?
    let imageAccessibilityFocusToolCallID: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        ChatMessageHostedBubble(
            model: model,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
            imageAccessibilityFocusToolCallID: imageAccessibilityFocusToolCallID,
            onAction: onAction
        )
        .id(model.renderIdentity)
    }

    static func == (lhs: NativeTimelineMessageBubble, rhs: NativeTimelineMessageBubble) -> Bool {
        guard lhs.entryID == rhs.entryID,
              lhs.model.row.messageId == rhs.model.row.messageId,
              lhs.model.row.role == rhs.model.row.role,
              lhs.model.row.index == rhs.model.row.index,
              lhs.model.row.isLast == rhs.model.row.isLast,
              lhs.model.row.hasEverStreamed == rhs.model.row.hasEverStreamed,
              lhs.model.row.canAnimateInsertion == rhs.model.row.canAnimateInsertion,
              lhs.model.variantInfo == rhs.model.variantInfo,
              lhs.model.renderState == rhs.model.renderState,
              lhs.model.renderIdentity == rhs.model.renderIdentity,
              lhs.imageAccessibilityFocusToolCallID == rhs.imageAccessibilityFocusToolCallID
        else { return false }

        if lhs.usesLiveTail || rhs.usesLiveTail {
            return lhs.model.liveTailModel === rhs.model.liveTailModel &&
                lhs.displaySettingSignature == rhs.displaySettingSignature &&
                lhs.generativeUiSettingSignature == rhs.generativeUiSettingSignature &&
                lhs.reasoningLevelLabel == rhs.reasoningLevelLabel
        }

        return lhs.renderDigest == rhs.renderDigest &&
            lhs.model.isGenerationActive == rhs.model.isGenerationActive
    }

    private var usesLiveTail: Bool {
        model.liveTailModel != nil && model.renderState.liveRenderingEnabled
    }
}

private struct ChatMessageHostedBubble: View {
    let model: ChatListMessageRenderModel
    let displaySetting: DisplaySetting
    let generativeUiSetting: GenerativeUiSetting
    let reasoningLevelLabel: String?
    let imageAccessibilityFocusToolCallID: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        if let liveTailModel = model.liveTailModel {
            ChatLiveTailBubble(
                model: model,
                liveTailModel: liveTailModel,
                displaySetting: displaySetting,
                generativeUiSetting: generativeUiSetting,
                reasoningLevelLabel: reasoningLevelLabel,
                imageAccessibilityFocusToolCallID: imageAccessibilityFocusToolCallID,
                onAction: onAction
            )
        } else {
            messageBubble(
                message: model.row.message,
                isGenerationActive: model.isGenerationActive,
                renderState: model.renderState
            )
        }
    }

    @ViewBuilder
    fileprivate func messageBubble(
        message: UIMessage,
        isGenerationActive: Bool,
        renderState: ChatRenderState
    ) -> some View {
        MessageBubbleView(
            message: message,
            messageIndex: model.row.index,
            variantInfo: model.variantInfo,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            onRegenerate: { onAction(.regenerate(messageId: model.row.messageId)) },
            onRequestEdit: { currentText in
                onAction(.requestEdit(messageId: model.row.messageId, currentText: currentText))
            },
            onEdit: { newText in onAction(.edit(messageId: model.row.messageId, newText: newText)) },
            onDelete: { onAction(.delete(messageId: model.row.messageId)) },
            onSelectVariant: { variantIndex in
                onAction(.selectVariant(messageId: model.row.messageId, variantIndex: variantIndex))
            },
            onGenerativeWidgetAction: { prompt in onAction(.generativeWidget(prompt: prompt)) },
            onModifyGeneratedImage: { imageURL, prompt, aspectRatio in
                onAction(.modifyGeneratedImage(urlString: imageURL, prompt: prompt, aspectRatio: aspectRatio))
            },
            onOpenMiniApp: { appId in onAction(.openMiniApp(appId: appId)) },
            onOpenMiniApps: { onAction(.openMiniApps) },
            isGenerating: model.row.isLast && isGenerationActive,
            isChatGenerationActive: isGenerationActive,
            isLastMessage: model.row.isLast,
            hasEverStreamed: renderState.hasEverStreamed,
            liveMarkdownRenderingEnabled: renderState.liveRenderingEnabled,
            frozenMarkdownSnapshot: renderState.frozenMarkdownSnapshot,
            reasoningLevelLabel: reasoningLevelLabel,
            imageAccessibilityFocusToolCallID: imageAccessibilityFocusToolCallID
        )
    }
}

private struct ChatLiveTailBubble: View {
    let model: ChatListMessageRenderModel
    @ObservedObject var liveTailModel: ChatLiveTailModel
    let displaySetting: DisplaySetting
    let generativeUiSetting: GenerativeUiSetting
    let reasoningLevelLabel: String?
    let imageAccessibilityFocusToolCallID: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        ChatMessageHostedBubble(
            model: model,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
            imageAccessibilityFocusToolCallID: imageAccessibilityFocusToolCallID,
            onAction: onAction
        )
        .messageBubble(
            message: liveTailModel.message,
            isGenerationActive: liveTailModel.isGenerationActive,
            renderState: liveTailModel.renderState
        )
    }
}

final class ChatLiveTailModel: ObservableObject {
    let objectWillChange = ObservableObjectPublisher()
    private(set) var message: UIMessage
    private(set) var isGenerationActive: Bool
    private(set) var renderState: ChatRenderState
    private(set) var revision: Int = 0
    private var lastSourceRevision: Int?

    init(message: UIMessage, isGenerationActive: Bool, renderState: ChatRenderState) {
        self.message = message
        self.isGenerationActive = isGenerationActive
        self.renderState = renderState
    }

    func update(
        message: UIMessage,
        isGenerationActive: Bool,
        renderState: ChatRenderState,
        sourceRevision: Int? = nil
    ) {
        if let sourceRevision,
           sourceRevision == lastSourceRevision,
           self.isGenerationActive == isGenerationActive,
           self.renderState == renderState {
            return
        }
        objectWillChange.send()
        self.message = message
        self.isGenerationActive = isGenerationActive
        self.renderState = renderState
        lastSourceRevision = sourceRevision
        revision &+= 1
    }
}

private enum ChatListSnapshotBuilder {

    static func renderIdentityForRow(_ row: ChatMessageRowModel, renderState: ChatRenderState) -> String {
        row.messageId
    }
}

enum ChatRendererMode: String {
    case staticMarkdown
    case streamingMarkdown
    case frozen
}

struct ChatRenderState: Equatable {
    var rendererMode: ChatRendererMode
    var hasEverStreamed: Bool
    var liveRenderingEnabled: Bool
    var frozenMarkdownSnapshot: String?
}

final class ChatRenderStateStore {
    private var visibleMessageIDs: Set<String> = []
    private var frozenMessageIDs: Set<String> = []
    private var frozenMarkdownByMessageID: [String: String] = [:]
    private var liveTailModelsByMessageID: [String: ChatLiveTailModel] = [:]

    func stateForRow(_ row: ChatMessageRowModel, isLiveRenderingFarFromBottom: Bool) -> ChatRenderState {
        let renderer: ChatTimelineRendererKind = row.role == MessageRole.assistant && (row.hasEverStreamed || row.isStreaming)
            ? .streamingAssistantMarkdown
            : (row.role == MessageRole.user ? .user : .staticAssistantMarkdown)
        return stateForEntry(
            ChatTimelineMessageEntry(
                id: "message-\(row.messageId)",
                messageId: row.messageId,
                message: row.message,
                role: row.role,
                index: row.index,
                isLast: row.isLast,
                isStreaming: row.isStreaming,
                hasEverStreamed: row.hasEverStreamed,
                canAnimateInsertion: row.canAnimateInsertion,
                renderer: renderer,
                renderToken: row.messageId
            ),
            isLiveRenderingFarFromBottom: isLiveRenderingFarFromBottom
        )
    }

    func stateForEntry(_ entry: ChatTimelineMessageEntry, isLiveRenderingFarFromBottom: Bool) -> ChatRenderState {
        let row = entry.rowModel
        guard entry.renderer == .streamingAssistantMarkdown else {
            return ChatRenderState(
                rendererMode: .staticMarkdown,
                hasEverStreamed: false,
                liveRenderingEnabled: true,
                frozenMarkdownSnapshot: nil
            )
        }

        let frozen = frozenMessageIDs.contains(row.messageId)
        let live = row.isLast &&
            row.role == MessageRole.assistant &&
            !isLiveRenderingFarFromBottom &&
            !frozen
        return ChatRenderState(
            rendererMode: live ? .streamingMarkdown : .frozen,
            hasEverStreamed: true,
            liveRenderingEnabled: live,
            frozenMarkdownSnapshot: live ? nil : frozenMarkdownByMessageID[row.messageId]
        )
    }

    func liveTailModel(
        for row: ChatMessageRowModel,
        renderState: ChatRenderState,
        isGenerationActive: Bool
    ) -> ChatLiveTailModel? {
        guard row.isLast,
              row.role == MessageRole.assistant,
              renderState.hasEverStreamed else { return nil }
        if let model = liveTailModelsByMessageID[row.messageId] {
            return model
        }
        guard row.isStreaming || isGenerationActive else { return nil }
        let model = ChatLiveTailModel(
            message: row.message,
            isGenerationActive: isGenerationActive,
            renderState: renderState
        )
        liveTailModelsByMessageID[row.messageId] = model
        return model
    }

    /// 行进入视口即解冻并丢弃冻结快照:可见的行必须显示真实内容,
    /// 不能停在离屏时冻结的旧文本上。
    @discardableResult
    func markVisible(_ messageID: String) -> Bool {
        let changed = !visibleMessageIDs.contains(messageID) ||
            frozenMessageIDs.contains(messageID) ||
            frozenMarkdownByMessageID[messageID] != nil
        visibleMessageIDs.insert(messageID)
        frozenMessageIDs.remove(messageID)
        frozenMarkdownByMessageID.removeValue(forKey: messageID)
        return changed
    }

    func freeze(messageID: String, latestText: String?) {
        visibleMessageIDs.remove(messageID)
        frozenMessageIDs.insert(messageID)
        if let latestText {
            frozenMarkdownByMessageID[messageID] = latestText
        }
    }

    func retain(ids: Set<String>) {
        visibleMessageIDs = visibleMessageIDs.intersection(ids)
        frozenMessageIDs = frozenMessageIDs.intersection(ids)
        frozenMarkdownByMessageID = frozenMarkdownByMessageID.filter { ids.contains($0.key) }
        liveTailModelsByMessageID = liveTailModelsByMessageID.filter { ids.contains($0.key) }
    }

    func removeAll() {
        visibleMessageIDs.removeAll()
        frozenMessageIDs.removeAll()
        frozenMarkdownByMessageID.removeAll()
        liveTailModelsByMessageID.removeAll()
    }
}

/// 消息内容哈希缓存:全量 build 对每行做 toText() + 逐 part describing 的
/// O(全文) 哈希(跨 KMP 桥接)是长 session 卡顿主源,历史消息基本不可变,按行记忆化。
/// 已核实的原地变更路径只有 tool output 空→非空回填(后台补全/审批恢复),
/// 用 parts.count + tool output 计数指纹捕获;最后一条/流式行永不缓存。
/// internal(非 private)是有意的:契约由 `ChatRowContentHashCacheTests` 通过
/// `@testable import` 直接覆盖,`private`/`fileprivate` 会让测试文件完全看不到
/// 这个类型。
final class ChatRowContentHashCache {
    private struct Entry {
        let partsCount: Int
        let toolOutputFingerprint: Int
        let hash: Int
    }

    private var entries: [String: Entry] = [:]

    func contentHash(for row: ChatMessageRowModel) -> Int {
        let fingerprint = Self.toolOutputFingerprint(row.parts)
        if let entry = entries[row.messageId],
           entry.partsCount == row.parts.count,
           entry.toolOutputFingerprint == fingerprint {
            return entry.hash
        }
        var hasher = Hasher()
        hasher.combine(row.message.toText())
        hasher.combine(row.parts.count)
        for part in row.parts {
            hasher.combine(String(describing: type(of: part)))
            hasher.combine(String(describing: part))
        }
        let hash = hasher.finalize()
        if !row.isLast && !row.isStreaming {
            entries[row.messageId] = Entry(
                partsCount: row.parts.count,
                toolOutputFingerprint: fingerprint,
                hash: hash
            )
        }
        return hash
    }

    /// Streaming tail rows are re-laid out by the live-tail invalidation bridge on
    /// every delta. They do not need the expensive full `message.toText()` hash that
    /// historical rows need for precise cache invalidation; a cheap structural token
    /// is enough to mark tail growth without doing O(total text) string joins on the
    /// main actor for every provider chunk.
    func streamingTailLayoutToken(for row: ChatMessageRowModel) -> Int {
        var hasher = Hasher()
        hasher.combine(row.messageId)
        hasher.combine(row.parts.count)
        hasher.combine(row.message.annotations.count)
        for annotation in row.message.annotations {
            hasher.combine(String(describing: annotation))
        }
        for part in row.parts {
            switch part {
            case let text as UIMessagePart.Text:
                hasher.combine("text")
                hasher.combine(text.text.utf16.count)
                hasher.combine(String(text.text.suffix(24)))
            case let reasoning as UIMessagePart.Reasoning:
                hasher.combine("reasoning")
                hasher.combine(reasoning.reasoning.utf16.count)
                hasher.combine(String(reasoning.reasoning.suffix(24)))
                hasher.combine(reasoning.finishedAt != nil)
            case let tool as UIMessagePart.Tool:
                hasher.combine("tool")
                hasher.combine(tool.toolCallId)
                hasher.combine(tool.toolName)
                hasher.combine(tool.input.utf16.count)
                hasher.combine(String(tool.input.suffix(48)))
                hasher.combine(tool.output.count)
                hasher.combine(tool.isExecuted)
                hasher.combine(String(describing: tool.approvalState))
                for output in tool.output {
                    Self.combineCompactPart(output, into: &hasher)
                }
            case let image as UIMessagePart.Image:
                hasher.combine("image")
                hasher.combine(image.url)
            case let document as UIMessagePart.Document:
                hasher.combine("document")
                hasher.combine(document.fileName)
                hasher.combine(document.url)
            case let miniApp as UIMessagePart.MiniApp:
                hasher.combine("mini_app")
                hasher.combine(miniApp.appId)
                hasher.combine(miniApp.version)
                hasher.combine(miniApp.htmlHash ?? "")
                hasher.combine(miniApp.title)
            default:
                hasher.combine(String(describing: type(of: part)))
            }
        }
        return hasher.finalize()
    }

    /// 尾行几何已确认离开 ScrollView viewport 后，保持 token 只依赖行身份，
    /// 避免每个 delta 都让巨大的流式 bubble 参与 SwiftUI diff 和布局；重新
    /// 与 viewport 相交后会使用 streamingTailLayoutToken 接上累计全文。
    func suspendedStreamingTailLayoutToken(for row: ChatMessageRowModel) -> Int {
        var hasher = Hasher()
        hasher.combine("suspended-streaming-tail")
        hasher.combine(row.messageId)
        return hasher.finalize()
    }

    private static func combineCompactPart(_ part: UIMessagePart, into hasher: inout Hasher) {
        hasher.combine(String(describing: type(of: part)))
        switch part {
        case let text as UIMessagePart.Text:
            hasher.combine(text.text.utf16.count)
            hasher.combine(String(text.text.suffix(48)))
        case let image as UIMessagePart.Image:
            hasher.combine(image.url)
        case let document as UIMessagePart.Document:
            hasher.combine(document.fileName)
            hasher.combine(document.url)
        default:
            hasher.combine(String(describing: part))
        }
    }

    /// 每个 Tool part 记 (非空标志 + output 条数),空→非空回填必然改变指纹。
    private static func toolOutputFingerprint(_ parts: [UIMessagePart]) -> Int {
        var fingerprint = 0
        for part in parts {
            guard let tool = part as? UIMessagePart.Tool else { continue }
            fingerprint = fingerprint &* 31 &+ (tool.output.isEmpty ? 1 : 2 &+ tool.output.count)
        }
        return fingerprint
    }

    func retain(ids: Set<String>) {
        entries = entries.filter { ids.contains($0.key) }
    }

    func removeAll() {
        entries.removeAll()
    }
}

private final class ChatRowHeightCache {
    private var heights: [String: CGFloat] = [:]
    /// 每个 item 的最近一次实测高度(按宽度),签名失效后作为估算兜底:
    /// 旧实测顶多差一两行,110 默认估算对巨型消息会差几千 pt,
    /// 造成 contentSize 塌陷(完成后大片空白/上滑跳没的根因)。
    private var latestHeights: [String: (width: CGFloat, height: CGFloat)] = [:]

    func height(for itemID: String, signature: String?, width: CGFloat) -> CGFloat? {
        if let exact = heights[key(itemID: itemID, signature: signature, width: width)] {
            return exact
        }
        if let latest = latestHeights[itemID],
           abs(latest.width - width) <= 80 {
            return latest.height
        }
        return nil
    }

    func exactHeight(for itemID: String, signature: String?, width: CGFloat) -> CGFloat? {
        heights[key(itemID: itemID, signature: signature, width: width)]
    }

    @discardableResult
    func set(
        height: CGFloat,
        for itemID: String,
        signature: String?,
        width: CGFloat,
        monotonic: Bool = false
    ) -> CGFloat {
        guard height > 0 else { return height }
        let storedHeight: CGFloat
        if monotonic,
           let latest = latestHeights[itemID],
           abs(latest.width - width) <= 80 {
            storedHeight = max(height, latest.height)
        } else {
            storedHeight = height
        }
        heights[key(itemID: itemID, signature: signature, width: width)] = storedHeight
        latestHeights[itemID] = (width: width, height: storedHeight)
        return storedHeight
    }

    func invalidate(itemIDs: [String]) {
        guard !itemIDs.isEmpty else { return }
        let prefixes = itemIDs.map { "\($0)-" }
        heights = heights.filter { key, _ in
            !prefixes.contains { key.hasPrefix($0) }
        }
        // 有意不清 latestHeights:invalidate 的语义是"精确值不再可信",
        // 兜底估算仍然比 110 默认值准得多。
    }

    /// 会话/分支切换后收口:只保留仍存在的 message 行与非 message 行(bottomSpacer 等
    /// 固定 id),防止长期使用无界增长。
    func retain(messageItemIDs: Set<String>) {
        heights = heights.filter { key, _ in
            !key.hasPrefix("message-") || messageItemIDs.contains(where: { key.hasPrefix("\($0)-") })
        }
        latestHeights = latestHeights.filter { itemID, _ in
            !itemID.hasPrefix("message-") || messageItemIDs.contains(itemID)
        }
    }

    private func key(itemID: String, signature: String?, width: CGFloat) -> String {
        "\(itemID)-s\(signature ?? "none")-w\(Int(width.rounded()))"
    }
}

/// 按「跟随生成」偏好开关 `.sizeChanges` 底锚,与小说创作统一。
/// 行锚定位置(用户上滑浏览历史)不会被它拽走(探针已实证)。
private struct ChatSizeChangesPinModifier: ViewModifier {
    let enabled: Bool

    func body(content: Content) -> some View {
        if enabled {
            content.defaultScrollAnchor(.bottom, for: .sizeChanges)
        } else {
            content
        }
    }
}

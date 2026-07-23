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
    case primaryConfiguration
    case modelDefaults
}

struct ChatCollectionMessageList: UIViewControllerRepresentable {
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
    var messagesProvider: () -> [UIMessage]
    var variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?
    var onAction: (ChatListAction) -> Void
    var onViewportStateChange: (ChatViewportState) -> Void

    func makeUIViewController(context: Context) -> ChatCollectionViewController {
        let controller = ChatCollectionViewController()
        controller.messagesProvider = messagesProvider
        controller.variantInfoProvider = variantInfoProvider
        controller.onAction = onAction
        controller.onViewportStateChange = onViewportStateChange
        return controller
    }

    func updateUIViewController(_ controller: ChatCollectionViewController, context: Context) {
        controller.messagesProvider = messagesProvider
        controller.variantInfoProvider = variantInfoProvider
        controller.onAction = onAction
        controller.onViewportStateChange = onViewportStateChange
        controller.update(
            signal: signal,
            configurationIssue: configurationIssue,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading,
            isRecognizingImages: isRecognizingImages,
            contextCompactState: contextCompactState,
            followGeneration: followGeneration,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
            workspaceStore: workspaceStore,
            scrollToBottomTrigger: scrollToBottomTrigger
        )
    }

    static func dismantleUIViewController(_ controller: ChatCollectionViewController, coordinator: ()) {
        controller.prepareForDismantle()
    }
}

enum ChatMessageListRoute: Equatable {
    case nativeTimelineSwiftUI
    case swiftUICleanList
    case collection
}

enum ChatMessageListRoutePolicy {
    static func route(
        nativeTimelineStaticRenderEnabled: Bool,
        nativeTimelineStreamingTailEnabled: Bool,
        swiftUICleanListEnabled: Bool,
        messages: [UIMessage],
        event: ChatEvent,
        isGenerationActive: Bool,
        isLoading: Bool
    ) -> ChatMessageListRoute {
        if NativeChatTimelineRoutePolicy.shouldUseNativeTimeline(
            staticRenderEnabled: nativeTimelineStaticRenderEnabled,
            streamingTailEnabled: nativeTimelineStreamingTailEnabled,
            messages: messages,
            event: event,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading
        ) {
            return .nativeTimelineSwiftUI
        }
        if swiftUICleanListEnabled {
            return .swiftUICleanList
        }
        return .collection
    }
}

enum ChatSwiftUIMessageListFeatureFlags {
    static let key = "chat.swiftui.cleanList.enabled"

    static var isEnabled: Bool {
        // 默认 true: 走 SwiftUI clean-list 路径(ChatSwiftUIMessageList)。
        // 根因(2026-07-04): 该路径的 .frame(minHeight: viewportHeight) 触发
        // AttributeGraph 布局循环, 导致 GeometryReader 测不到尺寸、distance=∞。
        // 修复方向是打破该循环(见 frame 修饰处), 而非整体换路径。
        UserDefaults.standard.object(forKey: key).map { _ in UserDefaults.standard.bool(forKey: key) } ?? true
    }
}

enum NativeChatTimelineStaticRenderFeatureFlags {
    static let key = "chat.nativeTimeline.staticRender.enabled"

    static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: key)
    }
}

enum NativeChatTimelineStreamingTailFeatureFlags {
    static let key = "chat.nativeTimeline.streamingTail.enabled"

    static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: key)
    }
}

enum NativeChatTimelineStaticRenderEligibility {
    static func canRender(
        messages: [UIMessage],
        event: ChatEvent,
        isGenerationActive: Bool,
        isLoading: Bool
    ) -> Bool {
        if isGenerationActive || event == .assistantStreamDelta {
            return false
        }
        if isLoading {
            return messages.last?.role == MessageRole.user
        }
        return true
    }
}

enum NativeChatTimelineStreamingTailEligibility {
    static func canRender(
        messages: [UIMessage],
        event: ChatEvent,
        isGenerationActive: Bool,
        isLoading: Bool
    ) -> Bool {
        guard NativeChatTimelineStreamingTailFeatureFlags.isEnabled else {
            return false
        }
        if isLoading {
            return messages.last?.role == MessageRole.user
        }
        guard isGenerationActive || event == .assistantStreamDelta else {
            return false
        }
        return messages.last?.role == MessageRole.assistant
    }
}

enum NativeChatTimelineEligibility {
    static func canRender(
        messages: [UIMessage],
        event: ChatEvent,
        isGenerationActive: Bool,
        isLoading: Bool
    ) -> Bool {
        guard NativeChatTimelineStaticRenderFeatureFlags.isEnabled else {
            return false
        }
        if NativeChatTimelineStaticRenderEligibility.canRender(
            messages: messages,
            event: event,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading
        ) {
            return true
        }
        return NativeChatTimelineStreamingTailEligibility.canRender(
            messages: messages,
            event: event,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading
        )
    }
}

enum NativeChatTimelineRoutePolicy {
    static func shouldUseNativeTimeline(
        staticRenderEnabled: Bool,
        streamingTailEnabled: Bool,
        messages: [UIMessage],
        event: ChatEvent,
        isGenerationActive: Bool,
        isLoading: Bool
    ) -> Bool {
        guard staticRenderEnabled else { return false }
        if streamingTailEnabled {
            return true
        }
        return NativeChatTimelineStaticRenderEligibility.canRender(
            messages: messages,
            event: event,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading
        )
    }
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

/// Phase 3 static surface: render the shared Native projection behind a flag
/// without taking over streaming, keyboard, or scroll ownership yet.
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
    var nativeScrollDriverEnabled: Bool
    var scrollToBottomTrigger: Int
    var scrollToBottomSource: NativeTimelineBottomIntentSource
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
    @State private var latestNativeScrollGeometry = ChatSwiftUIScrollGeometry()
    @State private var nativeScrollFallbackReason: NativeTimelineScrollFallbackReason?
    @State private var nativeScrollFallbackShouldReplayBottom = false
    @State private var nativeScrollFallbackReplayToken: UInt64 = 0
    @State private var isNativeScrollSurfaceVisible = false
    @State private var hasMeasuredNativeScrollGeometry = false

    var body: some View {
        let messages = messagesProvider()
        let nativeScrollDriverDesired = isNativeScrollDriverDesired
        let effectiveStreamedMessageIDs = effectiveStreamedMessageIDsForRender(
            event: signal.event,
            messages: messages
        )
        let renderViewportState = {
            var state = viewportState
            // The native tail is kept outside LazyVStack. While history is visible,
            // pause model publications instead of replacing its whole Markdown tree.
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
            displaySettingSignature: String(describing: displaySetting),
            generativeUiSettingSignature: String(describing: generativeUiSetting),
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
        let tailStartIndex = projection.entries.lastIndex(where: { $0.isLastMessage }) ?? projection.entries.endIndex
        let historicalEntries = projection.entries.prefix(upTo: tailStartIndex)
        let tailEntries = projection.entries.suffix(from: tailStartIndex)

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if !historicalEntries.isEmpty {
                    LazyVStack(alignment: .leading, spacing: 14) {
                        ForEach(historicalEntries) { entry in
                            entryView(entry)
                        }
                    }
                }

                // Keep stable history lazy, but measure the dynamically growing tail exactly.
                ForEach(tailEntries) { entry in
                    entryView(entry)
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
                nativeUserScrollActive = true
                onDismissKeyboard()
                scrollDriver.submit(.userDragBegan)
            case .idle:
                let endedUserScroll = nativeUserScrollActive
                nativeUserScrollActive = false
                if endedUserScroll {
                    let returnedToBottom = NativeTimelineScrollReturnPolicy.returnedToBottom(
                        liveDistanceToBottom: scrollDriver.distanceToBottomNow(),
                        cachedNearBottom: isNativeMeasuredNearBottom(latestNativeScrollGeometry),
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
                break
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
            hasMeasuredNativeScrollGeometry = true
            latestNativeScrollGeometry = geometry
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
            updateRendererMemory(event: signal.event, messages: messages)
            consumeExternalScrollToBottomTriggerIfNeeded()
        }
        .onDisappear {
            isNativeScrollSurfaceVisible = false
            hasMeasuredNativeScrollGeometry = false
            scrollDriver.invalidate()
        }
        .onChange(of: followGeneration) { _, enabled in
            scrollDriver.setAutomaticFollowEnabled(enabled)
        }
        .onChange(of: nativeScrollDriverEnabled) { _, enabled in
            if !enabled {
                nativeScrollFallbackReason = nil
                hasMeasuredNativeScrollGeometry = false
                scrollDriver.invalidate()
            }
        }
        .onChange(of: signal) { _, newSignal in
            updateRendererMemory(event: newSignal.event, messages: messagesProvider())
            submitNativeScrollIntent(for: newSignal.event)
        }
        .onChange(of: scrollToBottomTrigger) { _, trigger in
            consumeExternalScrollToBottomTriggerIfNeeded(trigger)
        }
        .onChange(of: nativeScrollFallbackReason) { _, reason in
            guard reason != nil,
                  nativeScrollFallbackShouldReplayBottom else { return }
            let replayToken = nativeScrollFallbackReplayToken
            nativeScrollFallbackShouldReplayBottom = false
            Task { @MainActor in
                await Task.yield()
                guard replayToken == nativeScrollFallbackReplayToken,
                      !nativeUserScrollActive else { return }
                withAnimation(.easeOut(duration: 0.2)) {
                    scrollPosition.scrollTo(id: ChatLayout.bottomAnchorID, anchor: .bottom)
                }
            }
        }
    }

    private var isNativeScrollDriverActive: Bool {
        isNativeScrollDriverDesired && scrollDriver.isAttached
    }

    private var isNativeScrollDriverDesired: Bool {
        nativeScrollDriverEnabled &&
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
        if nativeUserScrollActive || viewportState.followPaused ||
            (hasMeasuredNativeScrollGeometry && !viewportState.isAtBottom) {
            scrollDriver.submit(.userDragBegan)
        } else {
            scrollDriver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        }
    }

    @ViewBuilder
    private func entryView(_ entry: NativeTimelineEntry) -> some View {
        switch entry.kind {
        case .emptyState:
            ChatEmptyState()
        case let .configurationIssue(compact):
            if let configurationIssue {
                ChatConfigurationNoticeCard(
                    issue: configurationIssue,
                    compact: compact,
                    onPrimary: { onAction(.primaryConfiguration) },
                    onModelDefaults: { onAction(.modelDefaults) }
                )
                .padding(.top, compact ? 0 : 72)
                .padding(.bottom, compact ? 0 : 150)
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
                    reasoningLevelLabel: reasoningLevelLabel,
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
        scrollDriver.submit(.streamContentGrew)
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
            scrollDriver.submit(.streamContentGrew)
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
        withAnimation(.easeOut(duration: 0.18)) {
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
        withAnimation(.easeOut(duration: 0.2)) {
            scrollPosition.scrollTo(id: ChatLayout.bottomAnchorID, anchor: .bottom)
        }
    }

    private func submitNativeScrollIntent(for event: ChatEvent) {
        guard isNativeScrollDriverActive else {
            submitNativeSwiftUIFallbackScrollIntent(for: event)
            return
        }
        switch event {
        case .conversationLoaded, .conversationSwitched:
            hasMeasuredNativeScrollGeometry = false
            scrollDriver.submit(.conversationReset)
            scrollDriver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        case .branchChanged:
            scrollDriver.submit(.conversationReset)
        case .userMessageAppended:
            scrollDriver.submit(.explicitBottom(source: .button, animated: false, keyboardToken: nil))
        case .assistantStreamDelta:
            guard followGeneration else { return }
            scrollDriver.submit(.streamContentGrew)
        case .assistantStreamClosed, .toolCallStarted, .toolResultAppended,
             .awaitingToolApproval, .generationCompleted, .generationFailed,
             .generationCancelled, .generationHandedOffToBackground:
            if followGeneration,
               viewportState.isAtBottom || scrollDriver.isFollowingBottomOrKeyboardFocus || scrollDriver.isAtBottomNow() {
                scrollDriver.submit(.streamContentGrew)
            }
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
            withAnimation(.easeOut(duration: 0.2)) {
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
        hasMeasuredNativeScrollGeometry = false
        var next = viewportState
        next.followPaused = false
        next.userDragging = false
        next.isAtBottom = true
        next.liveRenderingFarFromBottom = false
        next.showScrollToBottom = false
        publishViewportState(next)
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
                    if let configurationIssue {
                        ChatConfigurationNoticeCard(
                            issue: configurationIssue,
                            compact: true,
                            onPrimary: { onAction(.primaryConfiguration) },
                            onModelDefaults: { onAction(.modelDefaults) }
                        )
                    }

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
        // sizeChanges 底锚在同一布局事务内同步吸收流式高度增长(与小说创作统一);
        // 探针已实证行锚定位置不会被拽走,所以不需要按 followMode 动态开关。
        // 门控:关闭「跟随生成」时不得替用户自动跟随。
        .modifier(ChatSizeChangesPinModifier(enabled: followGeneration))
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
            // Clean-list sizeChanges pin (gated by followGeneration) is the SOLE
            // writer for stream height growth. A residual scrollTo snap fights the
            // pin mid-layout and produces continuous jump/flicker; never dual-write.
            let sizeChangesPinOwnsGrowth = followGeneration
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
                // Animated measured-growth chase only when the pin is off.
                animateMeasuredGrowth: shouldFollowMeasuredGrowth
                    && !shouldReanchorExplicitBottomLayout
                    && !sizeChangesPinOwnsGrowth,
                sizeChangesPinOwnsGrowth: sizeChangesPinOwnsGrowth
            )
            if didObserveMeasuredGrowth, runtime.generationEndSettleTask != nil {
                runtime.generationEndSettleLastGrowthAt = Date()
            }
            if shouldReanchorExplicitBottomLayout {
                if !didIssueFollow {
                    scrollToBottomIfScrollable()
                }
            } else if shouldFollowMeasuredGrowth, !sizeChangesPinOwnsGrowth {
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
        if let configurationIssue {
            ChatConfigurationNoticeCard(
                issue: configurationIssue,
                compact: false,
                onPrimary: { onAction(.primaryConfiguration) },
                onModelDefaults: { onAction(.modelDefaults) }
            )
            .padding(.top, 72)
            .padding(.bottom, 150)
        } else {
            ChatEmptyState()
        }
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
    }

    private func scrollToBottomIfScrollable(disableAnimations: Bool = true) {
        guard ChatSwiftUIBottomWritePolicy.canIssueImmediateWrite(
            explicitBottomAnimationActive: runtime.explicitBottomAnimationActive
        ), currentContentScrollable else { return }
        scrollToBottomAnchor(disableAnimations: disableAnimations)
    }

    private func followMeasuredStreamGrowthToBottom() {
        guard currentContentScrollable else { return }
        guard !reduceMotion, displaySetting.showBottomFollowAnimation else {
            scrollToBottomAnchor()
            return
        }
        Task { @MainActor in
            await Task.yield()
            guard followGeneration,
                  runtime.followMode == .followingBottom,
                  !viewportState.followPaused,
                  !viewportState.userDragging,
                  !runtime.userScrollActive,
                  !runtime.explicitBottomAnimationActive,
                  currentContentScrollable else { return }
            withAnimation(.linear(duration: 0.08)) {
                scrollToBottomAnchor(disableAnimations: false)
            }
        }
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

    private var canAutoFollow: Bool {
        followGeneration && runtime.followMode == .followingBottom
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
        animateMeasuredGrowth: Bool,
        sizeChangesPinOwnsGrowth: Bool = false
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
                // sizeChanges pin absorbs stream height growth; a second scrollTo on
                // every delta fights the pin and produces continuous jump/flicker.
                if sizeChangesPinOwnsGrowth, event == .assistantStreamDelta {
                    continue
                }
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

private struct ChatSwiftUIViewportHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct ChatSwiftUIContentHeightKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

private struct ChatSwiftUIBottomMaxYKey: PreferenceKey {
    static let defaultValue: CGFloat = 0

    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
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
            isGenerating: row.isLast && isGenerationActive,
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
            lhs.renderDigest == rhs.renderDigest
    }
}

final class ChatCollectionViewController: UIViewController {
    var messagesProvider: (() -> [UIMessage])?
    var variantInfoProvider: ((Int) -> IOSConversationStore.VariantInfo?)?
    var onAction: ((ChatListAction) -> Void)?
    var onViewportStateChange: ((ChatViewportState) -> Void)?

    private let store = ChatListControllerStore()
    private let collectionViewLayout = CollectionViewChatLayout()
    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: collectionViewLayout)
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>?
    private var latestConfiguration = ChatCollectionConfiguration()
    private var lastScrollToBottomTrigger = 0
    /// 计数器而非 Bool:tail-delta 与 viewport 刷新的 apply 可能重叠,
    /// Bool 会被先完成的 completion 提前清掉。
    private var applyingSnapshotCount = 0
    private var pendingLastAssistantRenderStateRefresh = false
    private var keyboardBottomInset: CGFloat = 0
    private var keyboardFollowGeneration = 0
    private var lastPublishedViewportState: ChatViewportState?
    private var lastAppliedUpdateKey: ChatCollectionUpdateKey?
    private let useScrollArbiter = ChatScrollArbiterFeatureFlags.isProgrammaticScrollArbiterEnabled
    private var scrollArbiter: ChatScrollArbiter?
    private var isDismantled = false
    private var verifiedDisplayedSizingKeys: Set<String> = []
    private var userBubbleHeightEnvironmentSignature =
        ChatCollectionViewController.currentUserBubbleHeightEnvironmentSignature()
    private var liveTailLayoutDisplayLink: CADisplayLink?
    private var liveTailLayoutDisplayLinkTarget: ChatLiveTailLayoutDisplayLinkTarget?
    private var pendingLiveTailLayoutItemIDs: Set<String> = []
    private var pendingLiveTailLayoutCompletion: ((ChatCollectionViewController) -> Void)?

    private var isScrollArbiterEnabled: Bool {
        useScrollArbiter
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        configureLayout()
        configureCollectionView()
        configureDataSource()
        observeKeyboard()
        observeDisplayPreferences()
    }

    deinit {
        guard Thread.isMainThread else {
            NotificationCenter.default.removeObserver(self)
            return
        }
        MainActor.assumeIsolated {
            invalidateScrollArbiter()
        }
        NotificationCenter.default.removeObserver(self)
    }

    func prepareForDismantle() {
        isDismantled = true
        keyboardFollowGeneration &+= 1
        stopLiveTailLayoutDisplayLink()
        invalidateScrollArbiter()
        NotificationCenter.default.removeObserver(self)
    }

    func update(
        signal: ChatMessageUpdateSignal,
        configurationIssue: ChatConfigurationIssue?,
        isGenerationActive: Bool,
        isLoading: Bool,
        isRecognizingImages: Bool,
        contextCompactState: ChatContextCompactState,
        followGeneration: Bool,
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting,
        reasoningLevelLabel: String?,
        workspaceStore: IOSWorkspaceStore,
        scrollToBottomTrigger: Int
    ) {
        let nextConfiguration = ChatCollectionConfiguration(
            signal: signal,
            configurationIssue: configurationIssue,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading,
            isRecognizingImages: isRecognizingImages,
            contextCompactState: contextCompactState,
            followGeneration: followGeneration,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
            workspaceStore: workspaceStore
        )
        latestConfiguration = nextConfiguration
        let updateKey = ChatCollectionUpdateKey(configuration: nextConfiguration)
        // 只有快照真正应用成功才记住 key:首次 update 可能早于 viewDidLoad
        // (dataSource 还没建),那次 no-op 若也记 key,同 key 的后续 update
        // 会被防重放拦住,列表就一直空白。
        if updateKey != lastAppliedUpdateKey, applyCurrentSnapshot() {
            lastAppliedUpdateKey = updateKey
        }

        if scrollToBottomTrigger != lastScrollToBottomTrigger {
            lastScrollToBottomTrigger = scrollToBottomTrigger
            store.viewportState.followPaused = false
            if isScrollArbiterEnabled {
                let arbiter = scrollArbiterInstance()
                arbiter.userReleased()
                arbiter.snapToBottom(animated: true)
            } else {
                legacyScrollToBottom(animated: true)
            }
            debugFollow("scrollToBottom")
            publishViewportGeometry()
        }
    }

    private func configureLayout() {
        collectionViewLayout.delegate = self
        syncBottomBatchCompensation()
        collectionViewLayout.keepContentAtBottomOfVisibleArea = false
        // TODO(缺陷 β)评估结论(2026-07-03):supportSelfSizingInvalidation 不能全局
        // 开启——失效重排会覆盖用户拖拽抢占的 offset(arbiter 三项抢占守护翻红)并
        // 放大流式期间 contentSize 回缩(jitter 守护翻红)。回收重建行的高度正确性
        // 改由 ChatMessageCollectionViewCell.preferredLayoutAttributesFitting 的
        // 真实宽度重测保证(首测即正确,不依赖事后自动失效)。
        collectionViewLayout.settings.interItemSpacing = 14
        collectionViewLayout.settings.additionalInsets = UIEdgeInsets(
            top: 12,
            left: ChatLayout.contentHorizontalInset,
            bottom: 0,
            right: ChatLayout.contentHorizontalInset
        )
    }

    private func configureCollectionView() {
        view.backgroundColor = .clear
        collectionView.backgroundColor = .clear
        collectionView.alwaysBounceVertical = true
        collectionView.topEdgeEffect.style = .soft
        collectionView.keyboardDismissMode = .onDrag
        collectionView.showsHorizontalScrollIndicator = false
        collectionView.delegate = self
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(collectionView)
        NSLayoutConstraint.activate([
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.topAnchor.constraint(equalTo: view.topAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboardFromListTap))
        tap.cancelsTouchesInView = false
        collectionView.addGestureRecognizer(tap)
    }

    private func configureDataSource() {
        let registration = UICollectionView.CellRegistration<ChatMessageCollectionViewCell, String> { [weak self] cell, _, itemID in
            Self.resetCellAnimationState(cell)
            guard let self,
                  let renderModel = self.store.renderModelsByID[itemID],
                  let displaySetting = self.latestConfiguration.displaySetting,
                  let generativeUiSetting = self.latestConfiguration.generativeUiSetting,
                  let workspaceStore = self.latestConfiguration.workspaceStore else {
                cell.accessibilityIdentifier = nil
                cell.minimumPreferredHeightProvider = nil
                cell.preferredHeightDidMeasure = nil
                cell.contentConfiguration = nil
                return
            }
            cell.accessibilityIdentifier = itemID
            cell.backgroundColor = .clear
            cell.contentView.backgroundColor = .clear
            cell.minimumPreferredHeightProvider = { [weak self] collectionWidth in
                self?.minimumPreferredHeight(itemID: itemID, collectionWidth: collectionWidth)
            }
            cell.preferredHeightDidMeasure = { [weak self] collectionWidth, measuredHeight in
                self?.recordPreferredHeight(
                    itemID: itemID,
                    collectionWidth: collectionWidth,
                    measuredHeight: measuredHeight
                )
            }
            cell.contentConfiguration = UIHostingConfiguration {
                ChatListHostedItemView(
                    renderModel: renderModel,
                    displaySetting: displaySetting,
                    generativeUiSetting: generativeUiSetting,
                    workspaceStore: workspaceStore,
                    reasoningLevelLabel: self.latestConfiguration.reasoningLevelLabel,
                    onAction: { [weak self] action in self?.onAction?(action) }
                )
            }
            .margins(.all, 0)
        }

        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { collectionView, indexPath, itemID in
            collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: itemID)
        }
    }

    @discardableResult
    private func applyCurrentSnapshot() -> Bool {
        guard let dataSource,
              let messages = messagesProvider?() else { return false }

        let signal = latestConfiguration.signal
        let event = signal.event
        let sourceUpdateKey = ChatCollectionUpdateKey(configuration: latestConfiguration)
        if event.invalidatesProgrammaticScrollIntent {
            keyboardFollowGeneration &+= 1
            if isScrollArbiterEnabled {
                scrollArbiterInstance().conversationReset()
            }
        }
        store.rememberStreamedRendererState(for: event, messages: messages)
        if event != .userMessageAppended {
            store.clearAnimatedInsertions()
        }
        guard let displaySetting = latestConfiguration.displaySetting,
              let generativeUiSetting = latestConfiguration.generativeUiSetting else { return false }

        if applyTailStreamDeltaIfPossible(
            messages: messages,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting
        ) {
            return true
        }

        let build = ChatListSnapshotBuilder.build(
            messages: messages,
            signal: latestConfiguration.signal,
            configurationIssue: latestConfiguration.configurationIssue,
            isGenerationActive: latestConfiguration.isGenerationActive,
            isLoading: latestConfiguration.isLoading,
            isRecognizingImages: latestConfiguration.isRecognizingImages,
            contextCompactState: latestConfiguration.contextCompactState,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: latestConfiguration.reasoningLevelLabel,
            viewportState: store.viewportState,
            streamedMessageIDs: store.streamedMessageIDs,
            renderStateStore: store.renderStateStore,
            previousDigests: store.digests,
            contentHashCache: store.contentHashCache,
            variantInfoProvider: variantInfoProvider ?? { _ in nil }
        )

        let previousItemIDs = dataSource.snapshot().itemIdentifiers
        let animatedInsertionIDs = ChatInsertionAnimationPolicy.animatedInsertionItemIDs(
            previousItemIDs: previousItemIDs,
            rows: build.rows
        )
        let commands = ChatViewportReducer.reduce(
            event: event,
            state: &store.viewportState,
            environment: ChatViewportEnvironment(
                followEnabled: latestConfiguration.followGeneration,
                generationActive: latestConfiguration.isStreamingFollowActive
            )
        )

        store.items = build.items
        store.renderModelsByID = build.renderModelsByID
        store.rowHeightCache.invalidate(itemIDs: build.heightChangedIDs)
        verifiedDisplayedSizingKeys.subtract(build.heightChangedIDs.map(displayedSizingVerificationKey))
        store.digests = build.digests
        store.queueAnimatedInsertions(animatedInsertionIDs)

        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(build.items.map { $0.id }, toSection: 0)

        let currentIDs = Set(previousItemIDs)
        let reconfigureIDs = build.changedIDs.filter { currentIDs.contains($0) }
        if !reconfigureIDs.isEmpty {
            snapshot.reconfigureItems(reconfigureIDs)
        }

        applyingSnapshotCount += 1
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
            guard let self else { return }
            self.applyingSnapshotCount -= 1
            self.invalidateLayoutForItemIDs(reconfigureIDs)
            self.verifiedDisplayedSizingKeys.subtract(reconfigureIDs.map(self.displayedSizingVerificationKey))
            self.collectionView.layoutIfNeeded()
            self.execute(
                commands: commands,
                event: event,
                sourceRevision: signal.revision,
                sourceUpdateKey: sourceUpdateKey
            )
            self.publishViewportGeometry()
            self.applyPendingLastAssistantRenderStateRefreshIfNeeded()
        }
        return true
    }

    private func applyTailStreamDeltaIfPossible(
        messages: [UIMessage],
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting
    ) -> Bool {
        let signal = latestConfiguration.signal
        let event = signal.event
        let sourceUpdateKey = ChatCollectionUpdateKey(configuration: latestConfiguration)
        guard event == .assistantStreamDelta,
              let lastMessage = messages.last,
              lastMessage.role == MessageRole.assistant else { return false }

        // .assistantStreamDelta 走 reduce 的 default 分支,只产出命令、不改 state,
        // 因此提前到 guard 之外计算是安全的(行不在快照里时 fallthrough 全量 build 会再算一次)。
        let commands = ChatViewportReducer.reduce(
            event: event,
            state: &store.viewportState,
            environment: ChatViewportEnvironment(
                followEnabled: latestConfiguration.followGeneration,
                generationActive: latestConfiguration.isStreamingFollowActive
            )
        )

        return reconfigureLastAssistantRow(
            lastMessage: lastMessage,
            messagesCount: messages.count,
            isStreaming: true,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            skipIfSignatureUnchanged: false
        ) { controller in
            controller.execute(
                commands: commands,
                event: event,
                sourceRevision: signal.revision,
                sourceUpdateKey: sourceUpdateKey
            )
            controller.publishViewportGeometry()
        }
    }

    /// tail-delta 与 viewport 刷新共用的「最后一条 assistant 行重建 + reconfigure」路径。
    /// row 的字段口径必须与 ChatMessageProjector.rows 对最后一条 assistant 的产出一致,
    /// 否则 signature 会在这里与全量 build 之间来回翻转。
    @discardableResult
    private func reconfigureLastAssistantRow(
        lastMessage: UIMessage,
        messagesCount: Int,
        isStreaming: Bool,
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting,
        skipIfSignatureUnchanged: Bool,
        onApplied: @escaping (ChatCollectionViewController) -> Void
    ) -> Bool {
        guard let dataSource else { return false }
        let messageID = ChatMessageProjector.messageId(for: lastMessage)
        let itemID = "message-\(messageID)"
        var snapshot = dataSource.snapshot()
        guard snapshot.indexOfItem(itemID) != nil,
              store.items.contains(where: { $0.id == itemID }) else {
            return false
        }

        let row = ChatMessageRowModel(
            rowId: messageID,
            messageId: messageID,
            message: lastMessage,
            role: lastMessage.role,
            parts: lastMessage.parts,
            index: max(0, messagesCount - 1),
            isLast: true,
            isStreaming: isStreaming,
            hasEverStreamed: isStreaming || store.streamedMessageIDs.contains(messageID),
            canAnimateInsertion: false
        )
        let renderState = store.renderStateStore.stateForRow(
            row,
            isLiveRenderingFarFromBottom: store.viewportState.liveRenderingFarFromBottom
        )
        let variantInfo = variantInfoProvider?(row.index)
        let digest = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: isStreaming
                ? store.contentHashCache.streamingTailLayoutToken(for: row)
                : store.contentHashCache.contentHash(for: row),
            isGenerationActive: latestConfiguration.isGenerationActive,
            displaySettingSignature: String(describing: displaySetting),
            generativeUiSettingSignature: String(describing: generativeUiSetting),
            hasMultipleVariants: variantInfo?.hasMultipleVariants == true,
            reasoningLevelLabel: latestConfiguration.reasoningLevelLabel
        )
        // 渲染状态没有实际变化时(比如行在底部附近反复进出视口)不要 reconfigure:
        // 高度缓存被无谓清掉后 sizeForItemAt 会退回估算值,造成 contentSize 抖动。
        let previousDigest = store.digests[itemID]
        if skipIfSignatureUnchanged, previousDigest == digest {
            return false
        }
        let liveTailModel = store.renderStateStore.liveTailModel(
            for: row,
            renderState: renderState,
            isGenerationActive: latestConfiguration.isGenerationActive
        )
        liveTailModel?.update(
            message: row.message,
            isGenerationActive: latestConfiguration.isGenerationActive,
            renderState: renderState
        )
        let messageRenderModel = ChatListMessageRenderModel(
            row: row,
            variantInfo: variantInfo,
            renderState: renderState,
            isGenerationActive: latestConfiguration.isGenerationActive,
            renderIdentity: ChatListSnapshotBuilder.renderIdentityForRow(row, renderState: renderState),
            liveTailModel: liveTailModel
        )
        store.renderModelsByID[itemID] = .message(messageRenderModel)
        store.digests[itemID] = digest
        let usesLiveTailUpdate = liveTailModel != nil && isStreaming
        // 高度只依赖 layout:presentation(index)变化不使高度失效。
        // live tail 流式更新期间不能逐 token 清掉精确高度:SwiftUI/Markdown
        // 中间态可能短暂回缩,清缓存会把 ChatLayout 带回估算高度并触发倒跳。
        if previousDigest?.layout != digest.layout, !usesLiveTailUpdate {
            store.rowHeightCache.invalidate(itemIDs: [itemID])
            verifiedDisplayedSizingKeys.remove(displayedSizingVerificationKey(itemID))
        }

        if usesLiveTailUpdate {
            scheduleLiveTailLayoutUpdate(itemID: itemID, onApplied: onApplied)
        } else {
            snapshot.reconfigureItems([itemID])
            applyingSnapshotCount += 1
            dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
                guard let self else { return }
                self.applyingSnapshotCount -= 1
                self.invalidateLayoutForItemIDs([itemID])
                self.verifiedDisplayedSizingKeys.remove(self.displayedSizingVerificationKey(itemID))
                self.collectionView.layoutIfNeeded()
                onApplied(self)
                self.applyPendingLastAssistantRenderStateRefreshIfNeeded()
            }
        }
        return true
    }

    private func execute(
        commands: [ChatViewportScrollCommand],
        event: ChatEvent,
        sourceRevision: Int? = nil,
        sourceUpdateKey: ChatCollectionUpdateKey? = nil
    ) {
        guard !isDismantled else { return }
        if let sourceRevision, sourceRevision != latestConfiguration.signal.revision {
            publishViewportStateIfNeeded()
            return
        }
        if let sourceUpdateKey,
           sourceUpdateKey != ChatCollectionUpdateKey(configuration: latestConfiguration) {
            publishViewportStateIfNeeded()
            return
        }

        for command in commands {
            switch command {
            case .none:
                break
            case .initialAnchor, .resetForConversationSwitch:
                keyboardFollowGeneration &+= 1
                syncBottomBatchCompensation(forceEnabled: true)
                if isScrollArbiterEnabled {
                    let arbiter = scrollArbiterInstance()
                    arbiter.conversationReset()
                    arbiter.anchorToBottom()
                } else {
                    legacyAnchorToBottomConverged()
                }
            case let .followBottom(animated, _, deferred):
                // 只跳过这一条 followBottom,不能 return:否则同批后续命令
                // 和末尾的 publishViewportStateIfNeeded() 会被一起吞掉。
                guard !store.viewportState.userDragging,
                      !store.viewportState.followPaused else { continue }
                let action = { [weak self] in
                    guard let self else { return }
                    self.syncBottomBatchCompensation()
                    if self.isScrollArbiterEnabled {
                        if event == .assistantStreamDelta {
                            self.scrollArbiterInstance().followTail()
                        } else {
                            self.scrollArbiterInstance().snapToBottom(animated: animated)
                        }
                    } else {
                        let useLinearFollow = animated && event == .assistantStreamDelta
                        self.legacyScrollToBottom(animated: animated, linear: useLinearFollow)
                    }
                }
                if deferred {
                    Task { @MainActor in action() }
                } else {
                    action()
                }
            case let .showBottomButton(visible):
                store.viewportState.showScrollToBottom = visible
            }
        }
        publishViewportStateIfNeeded()
    }

    /// 进会话锚定:列表此时几乎全是估算高度,一次 scrollToItem 会落在"假底部",
    /// 落点后尾屏 cell 实测修正又把真实底部推远。滚动→布局→校验,直到距底≤1pt
    /// 或达到上限(尾屏行实测化后第二轮通常即收敛)。
    private func legacyAnchorToBottomConverged() {
        legacyScrollToBottom(animated: false)
        guard collectionView.bounds.height > 0 else { return }
        for _ in 0..<2 {
            collectionView.layoutIfNeeded()
            let visibleHeight = collectionView.bounds.height - collectionView.adjustedContentInset.top - collectionView.adjustedContentInset.bottom
            let visibleMaxY = collectionView.contentOffset.y + collectionView.adjustedContentInset.top + visibleHeight
            let distanceToBottom = collectionView.contentSize.height - visibleMaxY
            if distanceToBottom <= 1 { break }
            legacyScrollToBottom(animated: false)
        }
    }

    private func legacyScrollToBottom(animated: Bool, linear: Bool = false) {
        guard let dataSource,
              let itemID = store.items.last(where: { $0.kind == .bottomSpacer })?.id,
              let indexPath = dataSource.indexPath(for: itemID) else { return }

        let scroll = {
            self.collectionView.scrollToItem(at: indexPath, at: .bottom, animated: false)
        }

        if animated {
            if linear {
                UIView.animate(withDuration: 0.08, delay: 0, options: [.curveLinear, .allowUserInteraction]) {
                    scroll()
                }
            } else {
                UIView.animate(withDuration: 0.18, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                    scroll()
                }
            }
        } else {
            UIView.performWithoutAnimation(scroll)
        }
    }

    private func scrollArbiterInstance() -> ChatScrollArbiter {
        if let scrollArbiter {
            return scrollArbiter
        }
        let arbiter = ChatScrollArbiter(collectionView: collectionView) { [weak self] in
            self?.bottomAnchorIndexPath()
        }
        scrollArbiter = arbiter
        return arbiter
    }

    private func invalidateScrollArbiter() {
        scrollArbiter?.invalidate()
        scrollArbiter = nil
    }

    private func bottomAnchorIndexPath() -> IndexPath? {
        guard let dataSource,
              let itemID = store.items.last(where: { $0.kind == .bottomSpacer })?.id else { return nil }
        return dataSource.indexPath(for: itemID)
    }

    private func publishViewportGeometry() {
        let visibleHeight = max(1, collectionView.bounds.height - collectionView.adjustedContentInset.top - collectionView.adjustedContentInset.bottom)
        let visibleMaxY = collectionView.contentOffset.y + collectionView.adjustedContentInset.top + visibleHeight
        let contentHeight = collectionView.contentSize.height
        let distanceToBottom = max(0, contentHeight - visibleMaxY)
        let liveRenderingThreshold = max(
            ChatLayout.liveRenderingLODMinDistance,
            visibleHeight * ChatLayout.liveRenderingLODScreenFactor
        )
        let previousLiveRenderingFarFromBottom = store.viewportState.liveRenderingFarFromBottom
        let geometry = ChatViewportGeometrySnapshot(
            atBottom: distanceToBottom <= ChatLayout.bottomStickThreshold,
            isContentScrollable: contentHeight > visibleHeight + ChatLayout.bottomStickThreshold,
            liveRenderingFarFromBottom: distanceToBottom > liveRenderingThreshold,
            userScrollActive: collectionView.isTracking || collectionView.isDragging || collectionView.isDecelerating
        )
        let commands = ChatViewportReducer.reduceGeometry(
            geometry,
            hasMessages: store.hasMessageItems,
            state: &store.viewportState,
            environment: ChatViewportEnvironment(
                followEnabled: latestConfiguration.followGeneration,
                generationActive: latestConfiguration.isStreamingFollowActive
            )
        )
        syncBottomBatchCompensation()
        if commands.isEmpty {
            publishViewportStateIfNeeded()
        } else {
            execute(commands: commands, event: latestConfiguration.signal.event)
        }
        if previousLiveRenderingFarFromBottom != store.viewportState.liveRenderingFarFromBottom {
            refreshLastAssistantRenderState()
        }
        followDebugTick &+= 1
        if followDebugTick % 20 == 0 {
            debugFollow("geometry")
        }
    }

    private func publishViewportStateIfNeeded() {
        guard lastPublishedViewportState != store.viewportState else { return }
        lastPublishedViewportState = store.viewportState
        onViewportStateChange?(store.viewportState)
    }

    /// `[AA-FOLLOW]` 诊断: 与 SwiftUI clean-list 路径字段对齐,全部读真实 UICollectionView 指标。
    /// 用于真机验证底部跟随状态机是否在 UICollectionView 路径上正确运转。
    private var followDebugTick = 0
    private func debugFollow(_ event: String) {
        #if DEBUG
        let visibleHeight = max(1, collectionView.bounds.height - collectionView.adjustedContentInset.top - collectionView.adjustedContentInset.bottom)
        let visibleMaxY = collectionView.contentOffset.y + collectionView.adjustedContentInset.top + visibleHeight
        let distanceToBottom = max(0, collectionView.contentSize.height - visibleMaxY)
        let messages = messagesProvider?().count ?? 0
        print(
            "[AA-FOLLOW] \(event) path=collection messages=\(messages) " +
            "active=\(latestConfiguration.isStreamingFollowActive ? 1 : 0) " +
            "loading=\(latestConfiguration.isLoading ? 1 : 0) " +
            "follow=\(latestConfiguration.followGeneration ? 1 : 0) " +
            "paused=\(store.viewportState.followPaused ? 1 : 0) " +
            "userDragging=\(store.viewportState.userDragging ? 1 : 0) " +
            "atBottom=\(store.viewportState.isAtBottom ? 1 : 0) " +
            "scrollable=\(store.viewportState.isContentScrollable ? 1 : 0) " +
            String(
                format: "distance=%.1f stick=%.1f ",
                distanceToBottom,
                ChatLayout.bottomStickThreshold
            ) +
            String(
                format: "offset=%.1f content=%.1f bounds=%.1f drag=%d track=%d decel=%d",
                collectionView.contentOffset.y,
                collectionView.contentSize.height,
                collectionView.bounds.height,
                collectionView.isDragging ? 1 : 0,
                collectionView.isTracking ? 1 : 0,
                collectionView.isDecelerating ? 1 : 0
            )
        )
        #endif
    }

    private func syncBottomBatchCompensation(forceEnabled: Bool = false) {
        let shouldKeepAtBottom = forceEnabled || (!store.viewportState.followPaused && !store.viewportState.userDragging)
        collectionViewLayout.keepContentOffsetAtBottomOnBatchUpdates = shouldKeepAtBottom
    }

    private func refreshLastAssistantRenderState() {
        // 上一个 diffable apply 的 completion 尚未回来时不能叠加 apply。完成态
        // cell 回到可见区后未必还有下一条 update，因此记住这一次解冻刷新，并在
        // 当前 snapshot completion 收口，不能直接丢弃。
        guard applyingSnapshotCount == 0 else {
            pendingLastAssistantRenderStateRefresh = true
            return
        }
        pendingLastAssistantRenderStateRefresh = false
        guard let messages = messagesProvider?(),
              let displaySetting = latestConfiguration.displaySetting,
              let generativeUiSetting = latestConfiguration.generativeUiSetting,
              let lastMessage = messages.last,
              lastMessage.role == MessageRole.assistant else { return }

        reconfigureLastAssistantRow(
            lastMessage: lastMessage,
            messagesCount: messages.count,
            isStreaming: latestConfiguration.signal.event == .assistantStreamDelta,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            skipIfSignatureUnchanged: true
        ) { controller in
            controller.publishViewportStateIfNeeded()
        }
    }

    private func applyPendingLastAssistantRenderStateRefreshIfNeeded() {
        guard applyingSnapshotCount == 0,
              pendingLastAssistantRenderStateRefresh else { return }
        pendingLastAssistantRenderStateRefresh = false
        refreshLastAssistantRenderState()
    }

    private func invalidateLayoutForItemIDs(_ itemIDs: [String]) {
        guard !itemIDs.isEmpty else { return }
        let indexPaths = itemIDs.compactMap { dataSource?.indexPath(for: $0) }
        guard !indexPaths.isEmpty else { return }
        let context = collectionViewLayout.invalidationContext(forBoundsChange: collectionView.bounds)
        context.invalidateItems(at: indexPaths)
        collectionViewLayout.invalidateLayout(with: context)
    }

    private func scheduleLiveTailLayoutUpdate(
        itemID: String,
        onApplied: @escaping (ChatCollectionViewController) -> Void
    ) {
        pendingLiveTailLayoutItemIDs.insert(itemID)
        // 多个 provider chunk 落在同一帧时只执行最后一次 viewport/follow 命令;
        // SwiftUI liveTailModel 已持有最新 message,这里合并的是布局与滚动写入。
        pendingLiveTailLayoutCompletion = onApplied
        startLiveTailLayoutDisplayLinkIfNeeded()
    }

    private func startLiveTailLayoutDisplayLinkIfNeeded() {
        guard liveTailLayoutDisplayLink == nil else { return }
        let target = ChatLiveTailLayoutDisplayLinkTarget { [weak self] in
            self?.flushLiveTailLayoutUpdate()
        }
        let link = CADisplayLink(target: target, selector: #selector(ChatLiveTailLayoutDisplayLinkTarget.tick))
        link.preferredFrameRateRange = CAFrameRateRange(minimum: 30, maximum: 120, preferred: 60)
        link.add(to: .main, forMode: .common)
        liveTailLayoutDisplayLinkTarget = target
        liveTailLayoutDisplayLink = link
    }

    private func stopLiveTailLayoutDisplayLink() {
        liveTailLayoutDisplayLink?.invalidate()
        liveTailLayoutDisplayLink = nil
        liveTailLayoutDisplayLinkTarget = nil
        pendingLiveTailLayoutItemIDs.removeAll()
        pendingLiveTailLayoutCompletion = nil
    }

    private func flushLiveTailLayoutUpdate() {
        guard !isDismantled else {
            stopLiveTailLayoutDisplayLink()
            return
        }
        guard !pendingLiveTailLayoutItemIDs.isEmpty else {
            stopLiveTailLayoutDisplayLink()
            return
        }

        let itemIDs = Array(pendingLiveTailLayoutItemIDs)
        let completion = pendingLiveTailLayoutCompletion
        pendingLiveTailLayoutItemIDs.removeAll()
        pendingLiveTailLayoutCompletion = nil

        invalidateLayoutForItemIDs(itemIDs)
        collectionView.setNeedsLayout()
        collectionView.layoutIfNeeded()
        completion?(self)

        if pendingLiveTailLayoutItemIDs.isEmpty {
            stopLiveTailLayoutDisplayLink()
        }
    }

    private func displayedSizingVerificationKey(_ itemID: String) -> String {
        let width = Int(collectionView.bounds.width.rounded())
        let signature = store.digests[itemID]?.layout ?? "none"
        return "\(itemID)-s\(signature)-w\(width)"
    }

    private func observeKeyboard() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardWillChangeFrame(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardDidChangeFrame(_:)),
            name: UIResponder.keyboardDidChangeFrameNotification,
            object: nil
        )
    }

    private func observeDisplayPreferences() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(userDefaultsDidChange(_:)),
            name: UserDefaults.didChangeNotification,
            object: nil
        )
    }

    @objc private func userDefaultsDidChange(_ notification: Notification) {
        let nextSignature = Self.currentUserBubbleHeightEnvironmentSignature()
        guard nextSignature != userBubbleHeightEnvironmentSignature else { return }
        userBubbleHeightEnvironmentSignature = nextSignature
        invalidateAllMessageLayoutsForHeightEnvironmentChange()
    }

    private func invalidateAllMessageLayoutsForHeightEnvironmentChange() {
        let messageItemIDs = store.items.compactMap { item -> String? in
            if case .message = item.kind {
                return item.id
            }
            return nil
        }
        store.rowHeightCache.invalidate(itemIDs: messageItemIDs)
        guard let dataSource, !messageItemIDs.isEmpty else {
            collectionViewLayout.invalidateLayout()
            collectionView.setNeedsLayout()
            return
        }

        var snapshot = dataSource.snapshot()
        let reconfigureIDs = messageItemIDs.filter { snapshot.indexOfItem($0) != nil }
        guard !reconfigureIDs.isEmpty else {
            collectionViewLayout.invalidateLayout()
            collectionView.setNeedsLayout()
            return
        }

        snapshot.reconfigureItems(reconfigureIDs)
        applyingSnapshotCount += 1
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
            guard let self else { return }
            self.applyingSnapshotCount -= 1
            self.invalidateLayoutForItemIDs(reconfigureIDs)
            self.collectionViewLayout.invalidateLayout()
            self.collectionView.visibleCells.forEach { cell in
                cell.setNeedsLayout()
                cell.contentView.setNeedsLayout()
            }
            self.collectionView.layoutIfNeeded()
            self.publishViewportGeometry()
            self.applyPendingLastAssistantRenderStateRefreshIfNeeded()
        }
    }

    @objc private func keyboardWillChangeFrame(_ notification: Notification) {
        updateKeyboardInset(from: notification, settleDelay: keyboardAnimationDuration(from: notification) + 0.16)
    }

    @objc private func keyboardDidChangeFrame(_ notification: Notification) {
        updateKeyboardInset(from: notification, settleDelay: 0.08)
    }

    private func updateKeyboardInset(from notification: Notification, settleDelay: TimeInterval) {
        guard let frameValue = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue else { return }
        let keyboardFrameInView = view.convert(frameValue.cgRectValue, from: nil)
        let overlap = max(0, view.bounds.maxY - keyboardFrameInView.minY)
        keyboardBottomInset = overlap
        collectionView.contentInset.bottom = overlap
        collectionView.verticalScrollIndicatorInsets.bottom = overlap
        guard store.viewportState.isAtBottom || !store.viewportState.followPaused else { return }
        keyboardFollowGeneration &+= 1
        let generation = keyboardFollowGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + settleDelay) { [weak self] in
            guard let self,
                  self.keyboardFollowGeneration == generation,
                  !self.isDismantled,
                  !self.store.viewportState.userDragging,
                  self.store.viewportState.isAtBottom || !self.store.viewportState.followPaused else { return }
            if self.isScrollArbiterEnabled {
                self.scrollArbiterInstance().snapToBottom(animated: true)
            } else {
                self.legacyScrollToBottom(animated: true)
            }
            self.publishViewportGeometry()
        }
    }

    private func keyboardAnimationDuration(from notification: Notification) -> TimeInterval {
        (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
    }

    @objc private func dismissKeyboardFromListTap() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    private static func resetCellAnimationState(_ cell: UICollectionViewCell) {
        cell.layer.removeAllAnimations()
        cell.alpha = 1
        cell.transform = .identity
    }
}

private extension ChatEvent {
    var invalidatesProgrammaticScrollIntent: Bool {
        switch self {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            true
        default:
            false
        }
    }
}

private final class ChatMessageCollectionViewCell: UICollectionViewCell {
    var minimumPreferredHeightProvider: ((CGFloat) -> CGFloat?)?
    var preferredHeightDidMeasure: ((CGFloat, CGFloat) -> CGFloat?)?

    override func prepareForReuse() {
        super.prepareForReuse()
        accessibilityIdentifier = nil
        minimumPreferredHeightProvider = nil
        preferredHeightDidMeasure = nil
    }

    override func preferredLayoutAttributesFitting(
        _ layoutAttributes: UICollectionViewLayoutAttributes
    ) -> UICollectionViewLayoutAttributes {
        // UIHostingConfiguration may report the Markdown table's unconstrained
        // intrinsic width here. Height must be measured at ChatLayout's proposed
        // row width, otherwise an offscreen table is cached as a short, 1000+pt-
        // wide row and collapses when it is recycled back onto screen.
        let proposedWidth = layoutAttributes.size.width > 1
            ? layoutAttributes.size.width
            : bounds.width
        let attributes = super.preferredLayoutAttributesFitting(layoutAttributes)
        guard proposedWidth > 1 else {
            return attributes
        }
        attributes.size.width = proposedWidth
        let measuredHeight = measuredFittingHeight(width: proposedWidth) ?? attributes.size.height
        if let preferredHeight = preferredHeightDidMeasure?(proposedWidth, measuredHeight) {
            attributes.size.height = preferredHeight
        } else if let minimumPreferredHeight = minimumPreferredHeightProvider?(proposedWidth) {
            attributes.size.height = max(measuredHeight, minimumPreferredHeight)
        } else {
            attributes.size.height = max(attributes.size.height, measuredHeight)
        }
        return attributes
    }

    func measuredFittingHeight(width: CGFloat) -> CGFloat? {
        guard width > 1 else { return nil }
        setNeedsLayout()
        contentView.setNeedsLayout()
        layoutIfNeeded()
        contentView.layoutIfNeeded()

        let targetSize = CGSize(
            width: width,
            height: UIView.layoutFittingCompressedSize.height
        )
        let contentSize = contentView.systemLayoutSizeFitting(
            targetSize,
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        )
        let cellSize = systemLayoutSizeFitting(
            targetSize,
            withHorizontalFittingPriority: .required,
            verticalFittingPriority: .fittingSizeLevel
        )
        let measuredHeight = max(contentSize.height, cellSize.height)
        return measuredHeight > 1 ? measuredHeight : nil
    }
}

extension ChatCollectionViewController: UICollectionViewDelegate {
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        if applyingSnapshotCount > 0 {
            collectionView.layer.removeAllAnimations()
        }
        collectionViewLayout.keepContentOffsetAtBottomOnBatchUpdates = false
        keyboardFollowGeneration &+= 1
        if isScrollArbiterEnabled {
            scrollArbiterInstance().userTakeover()
        }
        store.viewportState.userDragging = true
        store.viewportState.followPaused = true
        debugFollow("dragBegin")
        publishViewportStateIfNeeded()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        publishViewportGeometry()
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate {
            if isScrollArbiterEnabled {
                scrollArbiterInstance().userReleased()
            }
            store.viewportState.userDragging = false
            debugFollow("dragEnd")
            publishViewportGeometry()
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        if isScrollArbiterEnabled {
            scrollArbiterInstance().userReleased()
        }
        store.viewportState.userDragging = false
        debugFollow("decelEnd")
        publishViewportGeometry()
    }

    func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        guard let itemID = dataSource?.itemIdentifier(for: indexPath) else { return }
        let renderModel = store.renderModelsByID[itemID]
        let shouldKeepHeightMonotonic: Bool = {
            guard case let .message(messageModel) = renderModel else { return false }
            return messageModel.row.isLast &&
                messageModel.row.role == MessageRole.assistant &&
                messageModel.renderState.hasEverStreamed
        }()
        let verifiedHeight = verifyDisplayedCellPreferredHeightIfNeeded(
            cell: cell,
            itemID: itemID,
            indexPath: indexPath
        )
        let storedHeight = store.rowHeightCache.set(
            height: verifiedHeight ?? cell.bounds.height,
            for: itemID,
            signature: store.digests[itemID]?.layout,
            width: collectionView.bounds.width,
            monotonic: shouldKeepHeightMonotonic
        )
        if shouldKeepHeightMonotonic, storedHeight > cell.bounds.height + 1 {
            DispatchQueue.main.async { [weak self] in
                self?.invalidateLayoutForItemIDs([itemID])
                self?.collectionView.layoutIfNeeded()
            }
        }
        if let messageID = store.messageID(for: itemID) {
            store.renderStateStore.markVisible(messageID)
            if store.isLastAssistantItem(itemID) {
                DispatchQueue.main.async { [weak self] in
                    self?.refreshLastAssistantRenderState()
                }
            }
        }
        if store.consumeAnimatedInsertion(itemID) {
            animateInsertedUserMessageCell(cell)
        }
    }

    private func verifyDisplayedCellPreferredHeightIfNeeded(
        cell: UICollectionViewCell,
        itemID: String,
        indexPath: IndexPath
    ) -> CGFloat? {
        guard case .message = store.item(id: itemID)?.kind else { return nil }
        let verificationKey = displayedSizingVerificationKey(itemID)
        guard !verifiedDisplayedSizingKeys.contains(verificationKey) else { return nil }
        verifiedDisplayedSizingKeys.insert(verificationKey)

        let measuredWidth = cell.bounds.width > 1 ? cell.bounds.width : collectionView.bounds.width
        guard measuredWidth > 1 else { return nil }
        let attributes = (
            collectionView.layoutAttributesForItem(at: indexPath)?
                .copy() as? UICollectionViewLayoutAttributes
        ) ?? UICollectionViewLayoutAttributes(forCellWith: indexPath)
        let layoutHeight = attributes.size.height > 1 ? attributes.size.height : cell.bounds.height
        attributes.size.width = measuredWidth
        attributes.size.height = max(layoutHeight, 1)
        let preferredHeight = cell.preferredLayoutAttributesFitting(attributes).size.height
        guard preferredHeight > 1 else { return nil }

        if abs(preferredHeight - layoutHeight) > 1 {
            DispatchQueue.main.async { [weak self] in
                guard let self,
                      self.dataSource?.itemIdentifier(for: indexPath) == itemID else { return }
                let context = self.collectionViewLayout
                    .invalidationContext(forBoundsChange: self.collectionView.bounds)
                context.invalidateItems(at: [indexPath])
                self.collectionViewLayout.invalidateLayout(with: context)
                self.collectionView.setNeedsLayout()
            }
        }
        return preferredHeight
    }

    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        Self.resetCellAnimationState(cell)
        guard let itemID = dataSource?.itemIdentifier(for: indexPath),
              let messageID = store.messageID(for: itemID) else { return }
        store.renderStateStore.freeze(
            messageID: messageID,
            latestText: store.latestText(for: itemID)
        )
    }

    private func animateInsertedUserMessageCell(_ cell: UICollectionViewCell) {
        Self.resetCellAnimationState(cell)
        guard !UIAccessibility.isReduceMotionEnabled else { return }
        cell.alpha = 0
        cell.transform = CGAffineTransform(translationX: 0, y: 18)
            .scaledBy(x: 0.965, y: 0.965)
        UIView.animate(
            withDuration: 0.34,
            delay: 0,
            usingSpringWithDamping: 0.82,
            initialSpringVelocity: 0.18,
            options: [.allowUserInteraction, .beginFromCurrentState]
        ) {
            cell.alpha = 1
            cell.transform = .identity
        }
    }
}

extension ChatCollectionViewController: ChatLayoutDelegate {
    func sizeForItem(_ chatLayout: CollectionViewChatLayout, at indexPath: IndexPath) -> ItemSize {
        guard let itemID = dataSource?.itemIdentifier(for: indexPath),
              let item = store.item(id: itemID) else {
            return .estimated(CGSize(width: collectionView.bounds.width, height: 44))
        }
        let width = collectionView.bounds.width
        switch item.kind {
        case .bottomSpacer:
            return .exact(CGSize(width: width, height: ChatLayout.bottomRestGap))
        case .emptyState:
            return .estimated(CGSize(width: width, height: 260))
        case .configurationIssue:
            return .estimated(CGSize(width: width, height: 150))
        case .message:
            if isUserMessageItem(itemID),
               let estimatedHeight = userMessageEstimatedInitialHeight(itemID: itemID, collectionWidth: width) {
                if let measuredHeight = store.rowHeightCache.exactHeight(
                    for: itemID,
                    signature: store.digests[itemID]?.layout,
                    width: width
                ) {
                    return .exact(CGSize(width: width, height: measuredHeight))
                }
                return .estimated(CGSize(width: width, height: estimatedHeight))
            }
            if let measuredHeight = store.rowHeightCache.height(
                for: itemID,
                signature: store.digests[itemID]?.layout,
                width: width
            ) {
                return .exact(CGSize(width: width, height: measuredHeight))
            }
            return .estimated(CGSize(width: width, height: 110))
        case .pendingAssistant:
            return .estimated(CGSize(width: width, height: 72))
        case .visionRecognition:
            return .estimated(CGSize(width: width, height: 54))
        case .contextMarker:
            return .estimated(CGSize(width: width, height: 118))
        }
    }

    private func isUserMessageItem(_ itemID: String) -> Bool {
        guard let renderModel = store.renderModelsByID[itemID],
              case let .message(messageModel) = renderModel else { return false }
        return messageModel.row.role == MessageRole.user
    }

    private func userMessageEstimatedInitialHeight(itemID: String, collectionWidth: CGFloat) -> CGFloat? {
        guard let measuredMinimum = userMessageMinimumPreferredHeight(
            itemID: itemID,
            collectionWidth: collectionWidth
        ) else { return nil }
        // This value is only an estimate for ChatLayout's first pass. It must never be
        // lower than the eventual SwiftUI self-sized cell; otherwise the preceding user
        // bubble can paint into the streaming assistant row before UIKit gets a chance
        // to ask `preferredLayoutAttributesFitting`.
        return max(140, measuredMinimum + 56)
    }

    private func minimumPreferredHeight(itemID: String, collectionWidth: CGFloat) -> CGFloat? {
        if let userHeight = userMessageMinimumPreferredHeight(itemID: itemID, collectionWidth: collectionWidth) {
            return userHeight
        }
        guard let renderModel = store.renderModelsByID[itemID],
              case let .message(messageModel) = renderModel,
              messageModel.row.isLast,
              messageModel.row.role == MessageRole.assistant,
              messageModel.renderState.hasEverStreamed else { return nil }
        return store.rowHeightCache.height(
            for: itemID,
            signature: store.digests[itemID]?.layout,
            width: collectionWidth
        )
    }

    private func recordPreferredHeight(
        itemID: String,
        collectionWidth: CGFloat,
        measuredHeight: CGFloat
    ) -> CGFloat? {
        if let userHeight = userMessageMinimumPreferredHeight(itemID: itemID, collectionWidth: collectionWidth) {
            return userHeight
        }
        guard let renderModel = store.renderModelsByID[itemID],
              case let .message(messageModel) = renderModel else { return nil }
        let shouldKeepHeightMonotonic = messageModel.row.isLast &&
            messageModel.row.role == MessageRole.assistant &&
            messageModel.renderState.hasEverStreamed
        return store.rowHeightCache.set(
            height: measuredHeight,
            for: itemID,
            signature: store.digests[itemID]?.layout,
            width: collectionWidth,
            monotonic: shouldKeepHeightMonotonic
        )
    }

    private func userMessageMinimumPreferredHeight(itemID: String, collectionWidth: CGFloat) -> CGFloat? {
        userMessageHeightMeasurement(itemID: itemID, collectionWidth: collectionWidth)?.height
    }

    private func userMessageHeightMeasurement(
        itemID: String,
        collectionWidth: CGFloat
    ) -> (height: CGFloat, isTextOnly: Bool)? {
        guard let renderModel = store.renderModelsByID[itemID],
              case let .message(messageModel) = renderModel,
              messageModel.row.role == MessageRole.user else { return nil }
        let textParts = messageModel.row.parts.compactMap { part -> String? in
            guard let textPart = part as? UIMessagePart.Text,
                  !textPart.text.isEmpty else { return nil }
            return textPart.text
        }
        guard !textParts.isEmpty else { return nil }

        let rowWidth = max(0, collectionWidth - ChatLayout.contentHorizontalInset * 2)
        let bubbleWidth = min(ChatLayout.userMaxWidth, max(0, rowWidth - 48))
        let textWidth = max(1, bubbleWidth - 32)
        let scale = Self.currentChatFontScale()
        let lineSpacing = 3 * scale
        let font = Self.currentChatUIFont(size: 17 * scale)
        let textHeights = textParts.map { text in
            Self.userTextBubbleHeight(
                text: text,
                width: textWidth,
                font: font,
                lineSpacing: lineSpacing
            )
        }
        let variantHeight = visibleUserVariantSwitcherHeight(for: messageModel)
        let nonTextHeights = messageModel.row.parts.compactMap(Self.userNonTextPartMinimumHeight)
        var componentHeights = textHeights + nonTextHeights
        if let variantHeight {
            componentHeights.insert(variantHeight, at: 0)
        }
        guard !componentHeights.isEmpty else { return nil }
        let spacing = CGFloat(max(0, componentHeights.count - 1)) * 4
        let hasNonText = messageModel.row.parts.contains { !($0 is UIMessagePart.Text) }
        return (ceil(componentHeights.reduce(0, +) + spacing), !hasNonText)
    }

    private static func userTextBubbleHeight(
        text: String,
        width: CGFloat,
        font: UIFont,
        lineSpacing: CGFloat
    ) -> CGFloat {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineSpacing = lineSpacing
        let measuredTextHeight = (text as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [
                .font: font,
                .paragraphStyle: paragraph
            ],
            context: nil
        ).height
        let maxTextHeight = font.lineHeight * 50 + lineSpacing * 49
        let textHeight = min(ceil(measuredTextHeight), ceil(maxTextHeight))
        return ceil(textHeight + 28)
    }

    private func visibleUserVariantSwitcherHeight(for model: ChatListMessageRenderModel) -> CGFloat? {
        guard model.variantInfo?.hasMultipleVariants == true,
              !(model.row.isLast && model.isGenerationActive) else {
            return nil
        }
        return 18
    }

    private static func userNonTextPartMinimumHeight(_ part: UIMessagePart) -> CGFloat? {
        if part is UIMessagePart.Image {
            return 300
        }
        if part is UIMessagePart.Reasoning {
            return 56
        }
        if part is UIMessagePart.Tool {
            return 56
        }
        return nil
    }

    private static func currentChatFontScale() -> CGFloat {
        let raw = UserDefaults.standard.object(forKey: IOSDisplayPreferenceKeys.fontScale) as? Double ?? 1
        return CGFloat(min(max(raw, 0.88), 1.25))
    }

    private static func currentChatUIFont(size: CGFloat) -> UIFont {
        let rawFont = UserDefaults.standard.string(forKey: IOSDisplayPreferenceKeys.chatFont)
            ?? IOSChatFont.default.rawValue
        let chatFont = IOSChatFont(rawValue: rawFont) ?? .default
        let base = UIFont.systemFont(ofSize: size)
        let design: UIFontDescriptor.SystemDesign = switch chatFont {
        case .default:
            .default
        case .serif:
            .serif
        case .monospace:
            .monospaced
        }
        guard let descriptor = base.fontDescriptor.withDesign(design) else { return base }
        return UIFont(descriptor: descriptor, size: size)
    }

    private static func currentUserBubbleHeightEnvironmentSignature() -> String {
        let scale = currentChatFontScale()
        let rawFont = UserDefaults.standard.string(forKey: IOSDisplayPreferenceKeys.chatFont)
            ?? IOSChatFont.default.rawValue
        return "\(scale)-\(rawFont)"
    }
}

private struct ChatCollectionConfiguration {
    var signal: ChatMessageUpdateSignal
    var configurationIssue: ChatConfigurationIssue?
    var isGenerationActive: Bool
    var isLoading: Bool
    var isRecognizingImages: Bool
    var contextCompactState: ChatContextCompactState
    var followGeneration: Bool
    var displaySetting: DisplaySetting?
    var generativeUiSetting: GenerativeUiSetting?
    var reasoningLevelLabel: String?
    var workspaceStore: IOSWorkspaceStore?

    init(
        signal: ChatMessageUpdateSignal = ChatMessageUpdateSignal(reason: .initialLoad),
        configurationIssue: ChatConfigurationIssue? = nil,
        isGenerationActive: Bool = false,
        isLoading: Bool = false,
        isRecognizingImages: Bool = false,
        contextCompactState: ChatContextCompactState = .idle,
        followGeneration: Bool = true,
        displaySetting: DisplaySetting? = nil,
        generativeUiSetting: GenerativeUiSetting? = nil,
        reasoningLevelLabel: String? = nil,
        workspaceStore: IOSWorkspaceStore? = nil
    ) {
        self.signal = signal
        self.configurationIssue = configurationIssue
        self.isGenerationActive = isGenerationActive
        self.isLoading = isLoading
        self.isRecognizingImages = isRecognizingImages
        self.contextCompactState = contextCompactState
        self.followGeneration = followGeneration
        self.displaySetting = displaySetting
        self.generativeUiSetting = generativeUiSetting
        self.reasoningLevelLabel = reasoningLevelLabel
        self.workspaceStore = workspaceStore
    }

    var isStreamingFollowActive: Bool {
        isGenerationActive || isLoading
    }
}

private struct ChatCollectionUpdateKey: Equatable {
    let signal: ChatMessageUpdateSignal
    let configurationIssue: ChatConfigurationIssue?
    let isGenerationActive: Bool
    let isLoading: Bool
    let isRecognizingImages: Bool
    let contextCompactState: ChatContextCompactState
    let followGeneration: Bool
    let displaySettingSignature: String
    let generativeUiSettingSignature: String
    let reasoningLevelLabel: String?
    let workspaceStoreID: ObjectIdentifier?

    init(configuration: ChatCollectionConfiguration) {
        signal = configuration.signal
        configurationIssue = configuration.configurationIssue
        isGenerationActive = configuration.isGenerationActive
        isLoading = configuration.isLoading
        isRecognizingImages = configuration.isRecognizingImages
        contextCompactState = configuration.contextCompactState
        followGeneration = configuration.followGeneration
        displaySettingSignature = String(describing: configuration.displaySetting)
        generativeUiSettingSignature = String(describing: configuration.generativeUiSetting)
        reasoningLevelLabel = configuration.reasoningLevelLabel
        workspaceStoreID = configuration.workspaceStore.map(ObjectIdentifier.init)
    }
}

private struct ChatListHostedItemView: View {
    let renderModel: ChatListRenderModel
    let displaySetting: DisplaySetting
    let generativeUiSetting: GenerativeUiSetting
    let workspaceStore: IOSWorkspaceStore
    let reasoningLevelLabel: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        switch renderModel {
        case .emptyState:
            ChatEmptyState()
        case let .configurationIssue(issue, compact):
            ChatConfigurationNoticeCard(
                issue: issue,
                compact: compact,
                onPrimary: { onAction(.primaryConfiguration) },
                onModelDefaults: { onAction(.modelDefaults) }
            )
            .padding(.top, compact ? 0 : 72)
            .padding(.bottom, compact ? 0 : 150)
        case let .message(model):
            ChatMessageHostedBubble(
                model: model,
                displaySetting: displaySetting,
                generativeUiSetting: generativeUiSetting,
                reasoningLevelLabel: reasoningLevelLabel,
                onAction: onAction
            )
            .environment(workspaceStore)
            .id(model.renderIdentity)
        case .pendingAssistant:
            ChatAssistantPendingResponseView()
        case .visionRecognition:
            VisionRecognitionIndicator()
        case let .contextMarker(state):
            ContextCompactTimelineMarker(state: state)
        case .bottomSpacer:
            Color.clear
                .frame(height: ChatLayout.bottomRestGap)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

private enum ChatListRenderModel {
    case emptyState
    case configurationIssue(ChatConfigurationIssue, compact: Bool)
    case message(ChatListMessageRenderModel)
    case pendingAssistant
    case visionRecognition
    case contextMarker(ChatContextCompactState)
    case bottomSpacer
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
    let reasoningLevelLabel: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        ChatMessageHostedBubble(
            model: model,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
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
              lhs.model.renderIdentity == rhs.model.renderIdentity
        else { return false }

        if lhs.usesLiveTail || rhs.usesLiveTail {
            return lhs.model.liveTailModel === rhs.model.liveTailModel
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
    let onAction: (ChatListAction) -> Void

    var body: some View {
        if let liveTailModel = model.liveTailModel {
            ChatLiveTailBubble(
                model: model,
                liveTailModel: liveTailModel,
                displaySetting: displaySetting,
                generativeUiSetting: generativeUiSetting,
                reasoningLevelLabel: reasoningLevelLabel,
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
            isGenerating: model.row.isLast && isGenerationActive,
            isLastMessage: model.row.isLast,
            hasEverStreamed: renderState.hasEverStreamed,
            liveMarkdownRenderingEnabled: renderState.liveRenderingEnabled,
            frozenMarkdownSnapshot: renderState.frozenMarkdownSnapshot,
            reasoningLevelLabel: reasoningLevelLabel
        )
    }
}

private struct ChatLiveTailBubble: View {
    let model: ChatListMessageRenderModel
    @ObservedObject var liveTailModel: ChatLiveTailModel
    let displaySetting: DisplaySetting
    let generativeUiSetting: GenerativeUiSetting
    let reasoningLevelLabel: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        ChatMessageHostedBubble(
            model: model,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
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

private final class ChatLiveTailLayoutDisplayLinkTarget: NSObject {
    private let onTick: () -> Void

    init(onTick: @escaping () -> Void) {
        self.onTick = onTick
    }

    @objc func tick() {
        onTick()
    }
}

private struct ChatListBuildResult {
    let items: [ChatListItem]
    let renderModelsByID: [String: ChatListRenderModel]
    let digests: [String: ChatRowDigest]
    let changedIDs: [String]
    let heightChangedIDs: [String]
    let rows: [ChatMessageRowModel]
}

private enum ChatListSnapshotBuilder {
    static func build(
        messages: [UIMessage],
        signal: ChatMessageUpdateSignal,
        configurationIssue: ChatConfigurationIssue?,
        isGenerationActive: Bool,
        isLoading: Bool,
        isRecognizingImages: Bool,
        contextCompactState: ChatContextCompactState,
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting,
        reasoningLevelLabel: String?,
        viewportState: ChatViewportState,
        streamedMessageIDs: Set<String>,
        renderStateStore: ChatRenderStateStore,
        previousDigests: [String: ChatRowDigest],
        contentHashCache: ChatRowContentHashCache,
        variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?
    ) -> ChatListBuildResult {
        var items: [ChatListItem] = []
        var models: [String: ChatListRenderModel] = [:]
        var digests: [String: ChatRowDigest] = [:]
        var rows: [ChatMessageRowModel] = []
        let displaySettingSignature = String(describing: displaySetting)
        let generativeUiSettingSignature = String(describing: generativeUiSetting)

        func append(_ item: ChatListItem, model: ChatListRenderModel, digest: ChatRowDigest) {
            items.append(item)
            models[item.id] = model
            digests[item.id] = digest
        }

        if messages.isEmpty {
            if let configurationIssue {
                append(
                    ChatListItem(id: "configuration-issue-empty", kind: .configurationIssue),
                    model: .configurationIssue(configurationIssue, compact: false),
                    digest: ChatRowDigest(
                        layout: "configuration-empty-\(configurationIssue.title)-\(configurationIssue.message)",
                        presentation: ""
                    )
                )
            } else {
                append(
                    ChatListItem(id: "empty-state", kind: .emptyState),
                    model: .emptyState,
                    digest: ChatRowDigest(layout: "empty", presentation: "")
                )
            }
            append(
                ChatListItem(id: ChatLayout.bottomAnchorID, kind: .bottomSpacer),
                model: .bottomSpacer,
                digest: ChatRowDigest(layout: "bottom-\(ChatLayout.bottomRestGap)", presentation: "")
            )
        } else {
            if let configurationIssue {
                append(
                    ChatListItem(id: "configuration-issue-compact", kind: .configurationIssue),
                    model: .configurationIssue(configurationIssue, compact: true),
                    digest: ChatRowDigest(
                        layout: "configuration-compact-\(configurationIssue.title)-\(configurationIssue.message)",
                        presentation: ""
                    )
                )
            }

            let includePendingAssistant = (isGenerationActive || isLoading) &&
                messages.last?.role == MessageRole.user
            let timelinePlan = ChatTimelinePlanner.build(
                messages: messages,
                event: signal.event,
                streamedMessageIDs: streamedMessageIDs,
                includePendingAssistant: includePendingAssistant,
                // collection 路径只取 rowModel,不消费 renderToken。
                includeRenderTokens: false
            )
            rows = timelinePlan.entries.compactMap { entry in
                guard case let .message(messageEntry) = entry else { return nil }
                return messageEntry.rowModel
            }

            func appendMessageEntry(_ messageEntry: ChatTimelineMessageEntry) {
                let row = messageEntry.rowModel
                let itemID = "message-\(row.messageId)"
                let renderState = renderStateStore.stateForEntry(
                    messageEntry,
                    isLiveRenderingFarFromBottom: viewportState.liveRenderingFarFromBottom
                )
                let variantInfo = variantInfoProvider(row.index)
                let digest = ChatRowDigests.digest(
                    row: row,
                    renderState: renderState,
                    contentHash: contentHashCache.contentHash(for: row),
                    isGenerationActive: isGenerationActive,
                    displaySettingSignature: displaySettingSignature,
                    generativeUiSettingSignature: generativeUiSettingSignature,
                    hasMultipleVariants: variantInfo?.hasMultipleVariants == true,
                    reasoningLevelLabel: reasoningLevelLabel
                )
                let liveTailModel = renderStateStore.liveTailModel(
                    for: row,
                    renderState: renderState,
                    isGenerationActive: isGenerationActive
                )
                liveTailModel?.update(
                    message: row.message,
                    isGenerationActive: isGenerationActive,
                    renderState: renderState
                )
                append(
                    ChatListItem(id: itemID, kind: .message(messageId: row.messageId)),
                    model: .message(
                        ChatListMessageRenderModel(
                            row: row,
                            variantInfo: variantInfo,
                            renderState: renderState,
                            isGenerationActive: isGenerationActive,
                            renderIdentity: renderIdentityForRow(row, renderState: renderState),
                            liveTailModel: liveTailModel
                        )
                    ),
                    digest: digest
                )
            }

            func appendPendingAssistant(id: String) {
                append(
                    ChatListItem(id: id, kind: .pendingAssistant),
                    model: .pendingAssistant,
                    digest: ChatRowDigest(layout: "pending", presentation: "")
                )
            }

            func appendDeferredTailDecorationsAndBottom(id: String) {
                if isRecognizingImages {
                    append(
                        ChatListItem(id: "vision-recognition-indicator", kind: .visionRecognition),
                        model: .visionRecognition,
                        digest: ChatRowDigest(layout: "vision", presentation: "")
                    )
                }

                if contextCompactState.isVisible {
                    let id = "context-compact-\(String(describing: contextCompactState.status))-\(contextCompactState.updatedAt.timeIntervalSince1970)"
                    append(
                        ChatListItem(id: id, kind: .contextMarker),
                        model: .contextMarker(contextCompactState),
                        digest: ChatRowDigest(layout: "\(id)-\(contextCompactState.summary)", presentation: "")
                    )
                }

                append(
                    ChatListItem(id: id, kind: .bottomSpacer),
                    model: .bottomSpacer,
                    digest: ChatRowDigest(layout: "bottom-\(ChatLayout.bottomRestGap)", presentation: "")
                )
            }

            for entry in timelinePlan.entries {
                switch entry {
                case let .message(messageEntry):
                    appendMessageEntry(messageEntry)
                case let .pendingAssistant(id):
                    appendPendingAssistant(id: id)
                case let .bottomAnchor(id):
                    appendDeferredTailDecorationsAndBottom(id: id)
                }
            }
        }

        let changedIDs = digests.compactMap { id, digest -> String? in
            guard let previous = previousDigests[id] else { return nil }
            return previous == digest ? nil : id
        }
        let heightChangedIDs = digests.compactMap { id, digest -> String? in
            guard let previous = previousDigests[id] else { return nil }
            return previous.layout == digest.layout ? nil : id
        }
        return ChatListBuildResult(
            items: items,
            renderModelsByID: models,
            digests: digests,
            changedIDs: changedIDs,
            heightChangedIDs: heightChangedIDs,
            rows: rows
        )
    }

    static func renderIdentityForRow(_ row: ChatMessageRowModel, renderState: ChatRenderState) -> String {
        row.messageId
    }
}

private struct ChatListItem: Hashable {
    let id: String
    let kind: ChatListItemKind
}

private enum ChatListItemKind: Hashable {
    case emptyState
    case configurationIssue
    case message(messageId: String)
    case pendingAssistant
    case visionRecognition
    case contextMarker
    case bottomSpacer
}

private final class ChatListControllerStore {
    var items: [ChatListItem] = []
    var renderModelsByID: [String: ChatListRenderModel] = [:]
    var digests: [String: ChatRowDigest] = [:]
    var streamedMessageIDs: Set<String> = []
    var viewportState = ChatViewportState()
    let renderStateStore = ChatRenderStateStore()
    let rowHeightCache = ChatRowHeightCache()
    let contentHashCache = ChatRowContentHashCache()
    private var animatedInsertionItemIDs: Set<String> = []

    var hasMessageItems: Bool {
        items.contains { item in
            if case .message = item.kind { return true }
            return false
        }
    }

    func item(id: String) -> ChatListItem? {
        items.first { $0.id == id }
    }

    func messageID(for itemID: String) -> String? {
        guard let item = item(id: itemID),
              case let .message(messageID) = item.kind else { return nil }
        return messageID
    }

    /// 冻结快照只在「恰好一个非空 text part」时被消费(MessageBubbleView 的
    /// nonEmptyTextPartCount == 1 门槛),所以这里必须返回那个 part 自己的文本。
    /// 不能用 message.toText():它把 Reasoning/Tool 等 part 映射成空串再用 "\n" 拼接,
    /// 带思考的消息会多出前导空行,与 live 渲染不一致,冻结/解冻切换时产生跳变。
    func latestText(for itemID: String) -> String? {
        guard let renderModel = renderModelsByID[itemID],
              case let .message(messageModel) = renderModel else { return nil }
        return messageModel.row.message.singleNonEmptyTextPart
    }

    func isLastAssistantItem(_ itemID: String) -> Bool {
        guard let renderModel = renderModelsByID[itemID],
              case let .message(messageModel) = renderModel else { return false }
        return messageModel.row.isLast && messageModel.row.role == MessageRole.assistant
    }

    func queueAnimatedInsertions(_ itemIDs: [String]) {
        animatedInsertionItemIDs.formUnion(itemIDs)
    }

    func clearAnimatedInsertions() {
        animatedInsertionItemIDs.removeAll()
    }

    func consumeAnimatedInsertion(_ itemID: String) -> Bool {
        animatedInsertionItemIDs.remove(itemID) != nil
    }

    func rememberStreamedRendererState(for event: ChatEvent, messages: [UIMessage]) {
        let currentIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
        switch event {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            streamedMessageIDs.removeAll()
            renderStateStore.removeAll()
            contentHashCache.removeAll()
            animatedInsertionItemIDs.removeAll()
            // 高度缓存条目只会因"消息被移除"而变陈旧,而移除只发生在这三类事件;
            // retain 的前缀过滤是 O(条目×消息数),不能挂在每个流式 tick 上。
            rowHeightCache.retain(messageItemIDs: Set(currentIDs.map { "message-\($0)" }))
        default:
            break
        }

        streamedMessageIDs = streamedMessageIDs.intersection(currentIDs)
        if event.remembersStreamingRenderer,
           let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) {
            streamedMessageIDs.insert(ChatMessageProjector.messageId(for: lastAssistant))
        }
        renderStateStore.retain(ids: currentIDs)
        contentHashCache.retain(ids: currentIDs)
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

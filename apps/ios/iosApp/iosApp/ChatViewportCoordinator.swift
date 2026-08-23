import Foundation

enum ChatEventSource: Equatable {
    case viewModel
    case generationCoordinator
    case toolRuntime
    case conversationStore
    case settings
}

enum ChatEventOwner: Equatable {
    case messages
    case generation
    case tools
    case viewport
    case settings
}

enum ChatEventPayloadKind: Equatable {
    case none
    case message
    case stream
    case tool
    case generation
    case conversation
    case settings
}

struct ChatEventContract: Equatable {
    let source: ChatEventSource
    let owner: ChatEventOwner
    let payload: ChatEventPayloadKind
    let affectsViewport: Bool
    let allowsAnimation: Bool
    let requiresPersistence: Bool
}

enum ChatEvent: Equatable {
    case userMessageAppended
    case assistantStreamDelta
    case assistantStreamClosed
    case toolCallStarted
    case toolResultAppended
    case awaitingToolApproval
    case generationCompleted
    case generationFailed
    case generationCancelled
    case generationHandedOffToBackground
    case conversationLoaded
    case conversationSwitched
    case branchChanged
    case settingsRefreshed

    var contract: ChatEventContract {
        switch self {
        case .userMessageAppended:
            return ChatEventContract(
                source: .viewModel,
                owner: .messages,
                payload: .message,
                affectsViewport: true,
                allowsAnimation: true,
                requiresPersistence: true
            )
        case .assistantStreamDelta:
            return ChatEventContract(
                source: .generationCoordinator,
                owner: .generation,
                payload: .stream,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: false
            )
        case .assistantStreamClosed:
            return ChatEventContract(
                source: .generationCoordinator,
                owner: .generation,
                payload: .stream,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: false
            )
        case .toolCallStarted:
            return ChatEventContract(
                source: .generationCoordinator,
                owner: .tools,
                payload: .tool,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: false
            )
        case .toolResultAppended:
            return ChatEventContract(
                source: .toolRuntime,
                owner: .tools,
                payload: .tool,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: true
            )
        case .awaitingToolApproval:
            return ChatEventContract(
                source: .toolRuntime,
                owner: .tools,
                payload: .tool,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: false
            )
        case .generationCompleted:
            return ChatEventContract(
                source: .generationCoordinator,
                owner: .generation,
                payload: .generation,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: true
            )
        case .generationFailed:
            return ChatEventContract(
                source: .generationCoordinator,
                owner: .generation,
                payload: .generation,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: true
            )
        case .generationCancelled:
            return ChatEventContract(
                source: .generationCoordinator,
                owner: .generation,
                payload: .generation,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: true
            )
        case .generationHandedOffToBackground:
            return ChatEventContract(
                source: .generationCoordinator,
                owner: .generation,
                payload: .generation,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: true
            )
        case .conversationLoaded:
            return ChatEventContract(
                source: .conversationStore,
                owner: .viewport,
                payload: .conversation,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: false
            )
        case .conversationSwitched:
            return ChatEventContract(
                source: .conversationStore,
                owner: .viewport,
                payload: .conversation,
                affectsViewport: true,
                allowsAnimation: false,
                requiresPersistence: false
            )
        case .branchChanged:
            return ChatEventContract(
                source: .conversationStore,
                owner: .messages,
                payload: .conversation,
                affectsViewport: false,
                allowsAnimation: false,
                requiresPersistence: true
            )
        case .settingsRefreshed:
            return ChatEventContract(
                source: .settings,
                owner: .settings,
                payload: .settings,
                affectsViewport: false,
                allowsAnimation: false,
                requiresPersistence: false
            )
        }
    }

    var remembersStreamingRenderer: Bool {
        switch self {
        case .assistantStreamDelta, .assistantStreamClosed, .toolCallStarted, .toolResultAppended,
             .awaitingToolApproval, .generationCompleted, .generationFailed, .generationHandedOffToBackground:
            return true
        case .userMessageAppended, .generationCancelled, .conversationLoaded, .conversationSwitched,
             .branchChanged, .settingsRefreshed:
            return false
        }
    }
}

enum ChatMessageUpdateReason: Equatable {
    case initialLoad
    case conversationSwitch
    case userAppend
    case streamDelta
    case streamFinish
    case toolDelta
    case assistantStreamClosed
    case toolCallStarted
    case toolResultAppended
    case awaitingToolApproval
    case generationCompleted
    case generationFailed
    case generationCancelled
    case generationHandedOffToBackground
    case branchChange
    case settingsRefresh

    var event: ChatEvent {
        switch self {
        case .initialLoad:
            return .conversationLoaded
        case .conversationSwitch:
            return .conversationSwitched
        case .userAppend:
            return .userMessageAppended
        case .streamDelta:
            return .assistantStreamDelta
        case .streamFinish, .assistantStreamClosed:
            return .assistantStreamClosed
        case .toolDelta, .toolResultAppended:
            return .toolResultAppended
        case .toolCallStarted:
            return .toolCallStarted
        case .awaitingToolApproval:
            return .awaitingToolApproval
        case .generationCompleted:
            return .generationCompleted
        case .generationFailed:
            return .generationFailed
        case .generationCancelled:
            return .generationCancelled
        case .generationHandedOffToBackground:
            return .generationHandedOffToBackground
        case .branchChange:
            return .branchChanged
        case .settingsRefresh:
            return .settingsRefreshed
        }
    }
}

struct ChatMessageUpdateSignal: Equatable {
    var revision: Int = 0
    var reason: ChatMessageUpdateReason = .initialLoad
    /// 流式跟随的滞后允许度：1=流式期（跟随行为不变）；终态排空期间随剩余
    /// 积压连续衰减到 0，滚动核心据此连续收紧跟随时间常数，使完成瞬间的
    /// 钉底零跳变。非 streamDelta reason 不消费该字段。
    var lagAllowance: CGFloat = 1

    var event: ChatEvent {
        reason.event
    }
}

enum ChatViewportScrollCommand: Equatable {
    case none
    case initialAnchor
    case followBottom(animated: Bool, targetBottomAnchor: Bool, deferred: Bool)
    case showBottomButton(Bool)
    case resetForConversationSwitch
}

struct ChatViewportState: Equatable {
    var followPaused: Bool = false
    var userDragging: Bool = false
    var showScrollToBottom: Bool = false
    var isAtBottom: Bool = false
    var isContentScrollable: Bool = false
    var liveRenderingFarFromBottom: Bool = false
    var conversationLoadToken: Int = 0

    mutating func resetForConversationLoad() {
        followPaused = false
        userDragging = false
        showScrollToBottom = false
        isAtBottom = false
        isContentScrollable = false
        liveRenderingFarFromBottom = false
        conversationLoadToken &+= 1
    }
}

struct ChatViewportEnvironment: Equatable {
    var followEnabled: Bool
    var generationActive: Bool
}

struct ChatViewportGeometrySnapshot: Equatable {
    var atBottom: Bool
    var isContentScrollable: Bool
    var liveRenderingFarFromBottom: Bool
    var userScrollActive: Bool = false
}

enum ChatViewportReducer {
    static func reduce(
        event: ChatEvent,
        state: inout ChatViewportState,
        environment: ChatViewportEnvironment
    ) -> [ChatViewportScrollCommand] {
        switch event {
        case .conversationLoaded, .conversationSwitched:
            state.resetForConversationLoad()
            return [.resetForConversationSwitch]
        case .userMessageAppended:
            state.followPaused = false
            state.userDragging = false
            let command = ChatViewportPolicy.commandForMessageUpdate(
                event: event,
                canAutoFollow: environment.followEnabled,
                isContentScrollable: state.isContentScrollable
            )
            return command == .none ? [] : [command]
        default:
            let command = ChatViewportPolicy.commandForMessageUpdate(
                event: event,
                canAutoFollow: environment.followEnabled && !state.followPaused && !state.userDragging,
                isContentScrollable: state.isContentScrollable
            )
            return command == .none ? [] : [command]
        }
    }

    static func reduceGeometry(
        _ snapshot: ChatViewportGeometrySnapshot,
        hasMessages: Bool,
        state: inout ChatViewportState,
        environment: ChatViewportEnvironment
    ) -> [ChatViewportScrollCommand] {
        let wasContentScrollable = state.isContentScrollable
        state.isAtBottom = snapshot.atBottom
        state.isContentScrollable = snapshot.isContentScrollable
        state.followPaused = ChatViewportPolicy.followPausedAfterGeometryChange(
            wasPaused: state.followPaused,
            userDragging: state.userDragging,
            atBottom: snapshot.atBottom,
            userScrollActive: snapshot.userScrollActive
        )
        // LOD 降级只在用户真的离开底部(暂停跟随或拖拽中)时生效:
        // 自动跟随中的 contentSize 瞬时跳变不算"远离底部",否则 rendererMode
        // 翻转会经由 renderIdentity(.id)销毁重建流式气泡,逐词淡入丢失。
        state.liveRenderingFarFromBottom = snapshot.liveRenderingFarFromBottom &&
            (state.followPaused || state.userDragging)

        var commands: [ChatViewportScrollCommand] = []
        if !wasContentScrollable && snapshot.isContentScrollable {
            let command = ChatViewportPolicy.commandForContentBecameScrollable(
                canAutoFollow: environment.followEnabled && !state.followPaused && !state.userDragging,
                isStreamingFollowActive: environment.generationActive
            )
            if command != .none {
                commands.append(command)
            }
        }

        let autoFollowingGeneration = environment.generationActive && environment.followEnabled && !state.followPaused
        let shouldShow = !snapshot.atBottom && hasMessages && !autoFollowingGeneration
        if shouldShow != state.showScrollToBottom {
            state.showScrollToBottom = shouldShow
            commands.append(.showBottomButton(shouldShow))
        }
        return commands
    }
}

enum ChatViewportPolicy {
    static func commandForMessageUpdate(
        reason: ChatMessageUpdateReason,
        canAutoFollow: Bool,
        isContentScrollable: Bool
    ) -> ChatViewportScrollCommand {
        commandForMessageUpdate(
            event: reason.event,
            canAutoFollow: canAutoFollow,
            isContentScrollable: isContentScrollable
        )
    }

    static func commandForMessageUpdate(
        event: ChatEvent,
        canAutoFollow: Bool,
        isContentScrollable: Bool
    ) -> ChatViewportScrollCommand {
        guard event.contract.affectsViewport else { return .none }
        switch event {
        case .conversationLoaded, .conversationSwitched:
            return .none
        case .userMessageAppended:
            // 滚动本身必须无动画:行入场 transition(spring)已驱动上屏动效,
            // 若滚动也走 spring 会和入场动画抢,产生画面跳动。滚动只负责"贴到底"。
            guard canAutoFollow, isContentScrollable else { return .none }
            return .followBottom(animated: false, targetBottomAnchor: true, deferred: false)
        case .assistantStreamDelta:
            guard canAutoFollow, isContentScrollable else { return .none }
            return .followBottom(animated: true, targetBottomAnchor: true, deferred: false)
        case .assistantStreamClosed, .toolCallStarted, .toolResultAppended,
             .awaitingToolApproval, .generationCompleted, .generationFailed, .generationCancelled,
             .generationHandedOffToBackground:
            guard canAutoFollow, isContentScrollable else { return .none }
            return .followBottom(animated: false, targetBottomAnchor: true, deferred: false)
        case .branchChanged, .settingsRefreshed:
            return .none
        }
    }

    static func commandForContentBecameScrollable(
        canAutoFollow: Bool,
        isStreamingFollowActive: Bool
    ) -> ChatViewportScrollCommand {
        guard canAutoFollow, isStreamingFollowActive else { return .none }
        return .followBottom(animated: false, targetBottomAnchor: true, deferred: false)
    }

    static func commandForExplicitBottomRequest() -> ChatViewportScrollCommand {
        .followBottom(animated: true, targetBottomAnchor: true, deferred: false)
    }

    static func canRunConversationBottomAnchorRetry(
        userScrollActive: Bool,
        followPaused: Bool
    ) -> Bool {
        !userScrollActive && !followPaused
    }

    static func followPausedAfterGeometryChange(
        wasPaused: Bool,
        userDragging: Bool,
        atBottom: Bool,
        userScrollActive: Bool
    ) -> Bool {
        if atBottom {
            // 只有"用户自己滚到底"(拖拽中或惯性减速中)才恢复跟随;
            // 估算高度失真造成的瞬时假 atBottom 不能清掉用户的阅读暂停。
            return (userDragging || userScrollActive) ? false : wasPaused
        }
        if userDragging {
            return true
        }
        return wasPaused
    }
}

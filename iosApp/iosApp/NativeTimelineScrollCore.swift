import CoreGraphics
import Foundation

enum NativeTimelineScrollReturnPolicy {
    static func returnedToBottom(
        liveDistanceToBottom: CGFloat?,
        cachedNearBottom: Bool,
        threshold: CGFloat
    ) -> Bool {
        guard let liveDistanceToBottom, liveDistanceToBottom.isFinite else {
            return cachedNearBottom
        }
        return liveDistanceToBottom <= threshold
    }
}

enum NativeTimelineBottomIntentSource: String, Equatable {
    case button
    case composerFocus
    case streamGrowth
}

enum NativeTimelineScrollFallbackReason: String, Equatable {
    case nonFiniteOffset
    case horizontalOffsetDrift
}

struct NativeTimelineKeyboardFocusTransaction: Equatable {
    var token: UInt64
    var stableFrames: Int
}

enum NativeTimelineScrollState: Equatable {
    case idle
    case followingBottom(virtualOffset: CGFloat, target: CGFloat, lastFollowRequestAt: TimeInterval)
    case settlingAfterTerminal(virtualOffset: CGFloat, target: CGFloat, lastLayoutAt: TimeInterval)
    case pausedForUser
    case keyboardFocus(NativeTimelineKeyboardFocusTransaction)
}

struct NativeTimelineScrollGeometry: Equatable {
    var offsetY: CGFloat
    var contentHeight: CGFloat
    var viewportHeight: CGFloat
    var adjustedInsetTop: CGFloat
    var adjustedInsetBottom: CGFloat
    var distanceToBottom: CGFloat
    var userInteracting: Bool

    var bottomTarget: CGFloat {
        max(
            -adjustedInsetTop,
            contentHeight - viewportHeight + effectiveBottomInset
        )
    }

    var isAtBottom: Bool {
        distanceToBottom <= NativeTimelineScrollCore.bottomEpsilon
    }

    var isNearBottom: Bool {
        distanceToBottom <= NativeTimelineScrollCore.resumeEpsilon
    }

    var effectiveBottomInset: CGFloat {
        adjustedInsetBottom
    }
}

enum NativeTimelineScrollIntent: Equatable {
    case explicitBottom(source: NativeTimelineBottomIntentSource, animated: Bool, keyboardToken: UInt64?)
    case streamContentGrew
    case viewportChanged
    case layoutSettled(token: UInt64?)
    case generationTerminated
    case userDragBegan
    case userDragEnded(isAtBottom: Bool)
    case conversationReset
}

enum NativeTimelineScrollAction: Equatable {
    case requestBottomAnchor(animated: Bool, source: NativeTimelineBottomIntentSource)
    case writeOffsetY(CGFloat)
    case startFrameDriver
    case stopFrameDriver
    case markKeyboardFocusComplete
}

enum NativeTimelineScrollCore {
    static let bottomEpsilon: CGFloat = 2
    static let arrivalEpsilon: CGFloat = 0.5
    static let resumeEpsilon: CGFloat = 48
    static let idleStopInterval: TimeInterval = 0.3
    static let tau: TimeInterval = 0.06
    static let requiredStableKeyboardFrames = 1

    static func reduce(
        state: NativeTimelineScrollState,
        intent: NativeTimelineScrollIntent,
        geometry: NativeTimelineScrollGeometry,
        now: TimeInterval
    ) -> (state: NativeTimelineScrollState, actions: [NativeTimelineScrollAction]) {
        if geometry.userInteracting {
            switch intent {
            case .explicitBottom,
                 .userDragEnded,
                 .conversationReset:
                break
            default:
                return (.pausedForUser, stopFrameDriverIfNeeded(from: state))
            }
        }

        switch intent {
        case let .explicitBottom(source, animated, keyboardToken):
            let nextState: NativeTimelineScrollState
            if source == .composerFocus {
                nextState = .keyboardFocus(
                    NativeTimelineKeyboardFocusTransaction(
                        token: keyboardToken ?? 0,
                        stableFrames: 0
                    )
                )
            } else {
                nextState = .followingBottom(
                    virtualOffset: geometry.offsetY,
                    target: geometry.bottomTarget,
                    lastFollowRequestAt: now
                )
            }
            return (
                nextState,
                stopFrameDriverIfNeeded(from: state) + [
                    .requestBottomAnchor(animated: animated, source: source)
                ]
            )

        case .streamContentGrew:
            switch state {
            case .pausedForUser:
                return (state, [])
            case let .followingBottom(virtualOffset, target, _):
                return (
                    .followingBottom(
                        virtualOffset: virtualOffset,
                        target: clampedTarget(current: target, incoming: geometry.bottomTarget),
                        lastFollowRequestAt: now
                    ),
                    [.startFrameDriver]
                )
            case .keyboardFocus:
                return (
                    state,
                    [.requestBottomAnchor(animated: false, source: .streamGrowth)]
                )
            case let .settlingAfterTerminal(virtualOffset, _, _):
                return (
                    .settlingAfterTerminal(
                        virtualOffset: min(virtualOffset, geometry.bottomTarget),
                        target: geometry.bottomTarget,
                        lastLayoutAt: now
                    ),
                    [.startFrameDriver]
                )
            case .idle:
                guard geometry.isNearBottom else {
                    return (state, [])
                }
                return (
                    .followingBottom(
                        virtualOffset: geometry.offsetY,
                        target: geometry.bottomTarget,
                        lastFollowRequestAt: now
                    ),
                    [.startFrameDriver]
                )
            }

        case .viewportChanged:
            switch state {
            case .followingBottom, .keyboardFocus:
                return (
                    .followingBottom(
                        virtualOffset: min(geometry.offsetY, geometry.bottomTarget),
                        target: geometry.bottomTarget,
                        lastFollowRequestAt: now
                    ),
                    abs(geometry.offsetY - geometry.bottomTarget) < arrivalEpsilon
                        ? []
                        : [.startFrameDriver]
                )
            case .settlingAfterTerminal:
                return (
                    .settlingAfterTerminal(
                        virtualOffset: min(geometry.offsetY, geometry.bottomTarget),
                        target: geometry.bottomTarget,
                        lastLayoutAt: now
                    ),
                    [.startFrameDriver]
                )
            case .idle:
                // 终态 settle 交还所有权后，晚到的布局（终态重测二 pass、渲染态
                // 延迟刷新）仍可能移动底部；近底时重新收锚一次，收敛后照常交还
                // 所有权。远底（用户在阅读历史）不打扰。
                guard geometry.isNearBottom else { return (state, []) }
                return (
                    .settlingAfterTerminal(
                        virtualOffset: min(geometry.offsetY, geometry.bottomTarget),
                        target: geometry.bottomTarget,
                        lastLayoutAt: now
                    ),
                    [.startFrameDriver]
                )
            case .pausedForUser:
                return (state, [])
            }

        case let .layoutSettled(layoutToken):
            if layoutToken == nil,
               case let .settlingAfterTerminal(virtualOffset, target, lastLayoutAt) = state {
                let targetChanged = abs(target - geometry.bottomTarget) >= arrivalEpsilon
                guard targetChanged || !geometry.isAtBottom else {
                    return (state, [])
                }
                return (
                    .settlingAfterTerminal(
                        virtualOffset: min(virtualOffset, geometry.bottomTarget),
                        target: geometry.bottomTarget,
                        lastLayoutAt: targetChanged ? now : lastLayoutAt
                    ),
                    [.startFrameDriver]
                )
            }
            guard case let .keyboardFocus(transaction) = state else {
                guard layoutToken == nil,
                      case let .followingBottom(virtualOffset, target, _) = state
                else {
                    return (state, [])
                }
                let nextTarget = clampedTarget(current: target, incoming: geometry.bottomTarget)
                guard nextTarget > target + arrivalEpsilon || !geometry.isAtBottom else {
                    return (state, [])
                }
                return (
                    .followingBottom(
                        virtualOffset: max(virtualOffset, geometry.offsetY),
                        target: nextTarget,
                        lastFollowRequestAt: now
                    ),
                    [.startFrameDriver]
                )
            }
            guard layoutToken == transaction.token else {
                return (state, [])
            }
            guard geometry.isAtBottom else {
                return (
                    .keyboardFocus(
                        NativeTimelineKeyboardFocusTransaction(
                            token: transaction.token,
                            stableFrames: 0
                        )
                    ),
                    []
                )
            }
            let stableFrames = transaction.stableFrames + 1
            guard stableFrames >= requiredStableKeyboardFrames else {
                return (
                    .keyboardFocus(
                        NativeTimelineKeyboardFocusTransaction(
                            token: transaction.token,
                            stableFrames: stableFrames
                        )
                    ),
                    []
                )
            }
            return (
                .followingBottom(
                    virtualOffset: geometry.offsetY,
                    target: geometry.bottomTarget,
                    lastFollowRequestAt: now
                ),
                [.markKeyboardFocusComplete]
            )

        case .generationTerminated:
            switch state {
            case .pausedForUser:
                return (state, [])
            case .idle:
                guard geometry.isNearBottom else { return (state, []) }
            case .followingBottom, .settlingAfterTerminal, .keyboardFocus:
                break
            }
            return (
                .settlingAfterTerminal(
                    virtualOffset: min(geometry.offsetY, geometry.bottomTarget),
                    target: geometry.bottomTarget,
                    lastLayoutAt: now
                ),
                [.startFrameDriver]
            )

        case .userDragBegan:
            return (.pausedForUser, stopFrameDriverIfNeeded(from: state))

        case let .userDragEnded(isAtBottom):
            guard isAtBottom else {
                return (.pausedForUser, [])
            }
            return (
                .followingBottom(
                    virtualOffset: geometry.offsetY,
                    target: geometry.bottomTarget,
                    lastFollowRequestAt: now
                ),
                []
            )

        case .conversationReset:
            return (.idle, stopFrameDriverIfNeeded(from: state))
        }
    }

    static func tick(
        state: NativeTimelineScrollState,
        geometry: NativeTimelineScrollGeometry,
        now: TimeInterval,
        dt: TimeInterval
    ) -> (state: NativeTimelineScrollState, actions: [NativeTimelineScrollAction]) {
        switch state {
        case let .followingBottom(virtualOffset, target, lastFollowRequestAt):
            guard !geometry.userInteracting else {
                return (.pausedForUser, [.stopFrameDriver])
            }
            guard geometry.offsetY.isFinite, geometry.bottomTarget.isFinite else {
                return (state, [])
            }

            let newTarget = clampedTarget(current: target, incoming: geometry.bottomTarget)
            let externallyAdvanced = min(geometry.offsetY, newTarget)
            let baseVirtual = max(virtualOffset, externallyAdvanced)
            let remaining = newTarget - baseVirtual

            if abs(remaining) < arrivalEpsilon {
                if now - lastFollowRequestAt > idleStopInterval {
                    return (
                        .followingBottom(
                            virtualOffset: newTarget,
                            target: newTarget,
                            lastFollowRequestAt: now
                        ),
                        [.writeOffsetY(newTarget), .stopFrameDriver]
                    )
                }
                return (
                    .followingBottom(
                        virtualOffset: newTarget,
                        target: newTarget,
                        lastFollowRequestAt: lastFollowRequestAt
                    ),
                    abs(newTarget - geometry.offsetY) < arrivalEpsilon ? [] : [.writeOffsetY(newTarget)]
                )
            }

            let alpha = 1 - exp(-max(dt, 0) / tau)
            let newVirtual = baseVirtual + remaining * alpha
            return (
                .followingBottom(
                    virtualOffset: newVirtual,
                    target: newTarget,
                    lastFollowRequestAt: lastFollowRequestAt
                ),
                abs(newVirtual - geometry.offsetY) < arrivalEpsilon ? [] : [.writeOffsetY(newVirtual)]
            )

        case let .settlingAfterTerminal(_, target, lastLayoutAt):
            guard !geometry.userInteracting else {
                return (.pausedForUser, [.stopFrameDriver])
            }
            guard geometry.offsetY.isFinite, geometry.bottomTarget.isFinite else {
                return (state, [])
            }

            let targetChanged = abs(target - geometry.bottomTarget) >= arrivalEpsilon
            let newTarget = geometry.bottomTarget
            let quietSince = targetChanged ? now : lastLayoutAt
            let remaining = newTarget - geometry.offsetY

            if abs(remaining) < arrivalEpsilon {
                guard now - quietSince >= idleStopInterval else {
                    return (
                        .settlingAfterTerminal(
                            virtualOffset: newTarget,
                            target: newTarget,
                            lastLayoutAt: quietSince
                        ),
                        []
                    )
                }
                return (.idle, [.stopFrameDriver])
            }

            // 终态收锚不做指数缓动：完成瞬间的布局落地（推理卡收口、终态重测）
            // 会让 bottomTarget 在几帧内移动，缓动会拖着视口"再滑一段"，就是
            // 用户感知的完成后不流畅。逐帧瞬时钉住 bottomTarget，观感等同流式
            // 增长期的底部锚定——内容怎么变，视口都已经在底部。
            return (
                .settlingAfterTerminal(
                    virtualOffset: newTarget,
                    target: newTarget,
                    lastLayoutAt: quietSince
                ),
                [.writeOffsetY(newTarget)]
            )

        case .idle, .pausedForUser, .keyboardFocus:
            return (state, [])
        }
    }

    private static func clampedTarget(current: CGFloat, incoming: CGFloat) -> CGFloat {
        guard incoming.isFinite else { return current }
        return max(current, incoming)
    }

    private static func stopFrameDriverIfNeeded(from state: NativeTimelineScrollState) -> [NativeTimelineScrollAction] {
        switch state {
        case .followingBottom, .settlingAfterTerminal:
            return [.stopFrameDriver]
        case .idle, .pausedForUser, .keyboardFocus:
            return []
        }
    }
}

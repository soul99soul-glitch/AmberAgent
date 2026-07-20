import CoreGraphics
import Foundation

enum NativeTimelineScrollFeatureFlags {
    static let key = "chat.nativeTimeline.scrollDriver.enabled"

    static var isEnabled: Bool {
        UserDefaults.standard.bool(forKey: key)
    }
}

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
    /// 2026-07-18 起收敛耗尽不再触发 fallback（driver 保持所有权，仅记诊断日志），
    /// 生产路径已不构造此 case；保留是为了历史日志字符串的兼容。
    case bottomConvergenceExhausted
    case horizontalOffsetDrift
}

struct NativeTimelineKeyboardFocusTransaction: Equatable {
    var token: UInt64
    var stableFrames: Int
}

enum NativeTimelineScrollState: Equatable {
    case idle
    case followingBottom(virtualOffset: CGFloat, target: CGFloat, lastFollowRequestAt: TimeInterval)
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
            case .idle, .pausedForUser:
                return (state, [])
            }

        case let .layoutSettled(layoutToken):
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
        guard case let .followingBottom(virtualOffset, target, lastFollowRequestAt) = state else {
            return (state, [])
        }
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
    }

    private static func clampedTarget(current: CGFloat, incoming: CGFloat) -> CGFloat {
        guard incoming.isFinite else { return current }
        return max(current, incoming)
    }

    private static func stopFrameDriverIfNeeded(from state: NativeTimelineScrollState) -> [NativeTimelineScrollAction] {
        if case .followingBottom = state {
            return [.stopFrameDriver]
        }
        return []
    }
}

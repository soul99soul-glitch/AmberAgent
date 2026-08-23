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

/// 拖拽释放瞬间的磁吸回底判定：向上甩（负 y 速度）且预测落点进入底部容差。
/// 预测用 UIScrollView 正常减速的近似停止距离（速度 × 0.25s），连续于速度、
/// 无分档。快速生成时底部在跑，等减速静止再判定往往已移出恢复窗口——
/// 用户被迫甩第二次；磁吸让一次甩动直接被显式回底动画接管。
enum NativeTimelineMagneticBottomPolicy {
    static let decelerationHorizon: TimeInterval = 0.25

    static func shouldSnap(
        releaseVelocityY: CGFloat,
        distanceToBottom: CGFloat,
        resumeTolerance: CGFloat = NativeTimelineScrollCore.resumeEpsilon
    ) -> Bool {
        guard releaseVelocityY < 0 else { return false }
        let predictedTravel = -releaseVelocityY * CGFloat(decelerationHorizon)
        return distanceToBottom - predictedTravel <= resumeTolerance
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
    case followingBottom(
        virtualOffset: CGFloat,
        target: CGFloat,
        lastFollowRequestAt: TimeInterval,
        /// 滞后允许度：1=流式期（跟随器保留指数平滑的稳态滞后，行为不变）；
        /// 终态排空期间由节奏层随剩余积压连续衰减到 0，让视口在最后一拍前
        /// 贴回底部——完成瞬间的钉底因此不再需要一次性清掉跟随期积累的滞后。
        lagAllowance: CGFloat = 1
    )
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
    case streamContentGrew(lagAllowance: CGFloat = 1)
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
    /// τ_eff = tau × lagAllowance 的下限：排空最后一拍允许接近瞬时闭合，
    /// 但每帧闭合率保持严格小于 1，残余 ≤ arrivalEpsilon 交给终态钉底收口。
    static let minimumLagAllowance: CGFloat = 1.0 / 16.0
    /// 终态贴齐只允许消灭尾段残差（同阶于容差的几 pt）：dt≈0（display link
    /// 同帧双发或负间隔被钳零）时 step 恒为 0，若不限 remaining 量级会对
    /// 任意大的晚到增长单帧整段贴齐——完成跳变在退化路径上复发。
    static let snapFinishRemainingBudget: CGFloat = 8
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

        case let .streamContentGrew(lagAllowance):
            switch state {
            case .pausedForUser:
                return (state, [])
            case let .followingBottom(virtualOffset, target, _, _):
                return (
                    .followingBottom(
                        virtualOffset: virtualOffset,
                        target: clampedTarget(current: target, incoming: geometry.bottomTarget),
                        lastFollowRequestAt: now,
                        lagAllowance: lagAllowance
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
                        lastFollowRequestAt: now,
                        lagAllowance: lagAllowance
                    ),
                    [.startFrameDriver]
                )
            }

        case .viewportChanged:
            switch state {
            case let .followingBottom(_, _, _, lagAllowance):
                return (
                    .followingBottom(
                        virtualOffset: min(geometry.offsetY, geometry.bottomTarget),
                        target: geometry.bottomTarget,
                        lastFollowRequestAt: now,
                        lagAllowance: lagAllowance
                    ),
                    abs(geometry.offsetY - geometry.bottomTarget) < arrivalEpsilon
                        ? []
                        : [.startFrameDriver]
                )
            case .keyboardFocus:
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
                      case let .followingBottom(virtualOffset, target, _, lagAllowance) = state
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
                        lastFollowRequestAt: now,
                        lagAllowance: lagAllowance
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
        case let .followingBottom(virtualOffset, target, lastFollowRequestAt, lagAllowance):
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
                            lastFollowRequestAt: now,
                            lagAllowance: lagAllowance
                        ),
                        [.writeOffsetY(newTarget), .stopFrameDriver]
                    )
                }
                return (
                    .followingBottom(
                        virtualOffset: newTarget,
                        target: newTarget,
                        lastFollowRequestAt: lastFollowRequestAt,
                        lagAllowance: lagAllowance
                    ),
                    abs(newTarget - geometry.offsetY) < arrivalEpsilon ? [] : [.writeOffsetY(newTarget)]
                )
            }

            // 终态临近度连续收紧时间常数：流式期 allowance=1，τ_eff=tau，
            // 保留把每拍高度台阶抹成连续运动的稳态滞后；排空期间 allowance
            // 随剩余积压连续衰减，闭合速度连续上升、滞后同步收敛到 0——
            // 最后一拍落定时视口已在底部，完成钉底从「一次清掉 10–35pt 滞后」
            // 变成空操作。指数闭合本身速度连续，无单帧瞬移。
            let tauEff = tau * min(max(lagAllowance, minimumLagAllowance), 1)
            let alpha = 1 - exp(-max(dt, 0) / tauEff)
            let newVirtual = baseVirtual + remaining * alpha
            return (
                .followingBottom(
                    virtualOffset: newVirtual,
                    target: newTarget,
                    lastFollowRequestAt: lastFollowRequestAt,
                    lagAllowance: lagAllowance
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

            if remaining > 0 {
                // 晚到的「增长」（终态重测、最后一拍文本的布局延迟落地）以基础
                // τ 缓动追入——新内容落地是书写的延续，瞬时钉底会把它变成完成
                // 瞬间的单帧跳变。「收缩」（推理卡收起）保持下方瞬时钉底：
                // 收起本身已是 0.2s ramp 的连续高度源，再缓动会复发"再滑一段"。
                let alpha = 1 - exp(-max(dt, 0) / tau)
                let step = remaining * alpha
                if step < arrivalEpsilon, remaining <= snapFinishRemainingBudget {
                    // 指数尾段每拍步长已小于到达容差时直接贴齐：否则视口在
                    // arrivalEpsilon 边界极限环上徘徊，永远不满足严格小于判定，
                    // 静默交还被无限推迟。贴齐量与容差同阶（~2–4pt），不可感知。
                    return (
                        .settlingAfterTerminal(
                            virtualOffset: newTarget,
                            target: newTarget,
                            lastLayoutAt: quietSince
                        ),
                        [.writeOffsetY(newTarget)]
                    )
                }
                let newVirtual = geometry.offsetY + remaining * alpha
                return (
                    .settlingAfterTerminal(
                        virtualOffset: newVirtual,
                        target: newTarget,
                        lastLayoutAt: quietSince
                    ),
                    [.writeOffsetY(newVirtual)]
                )
            }

            // 收缩方向：终态收锚不做指数缓动，逐帧瞬时钉住 bottomTarget——
            // 完成瞬间的布局落地（推理卡收口、终态重测）会让 bottomTarget 在
            // 几帧内移动，缓动会拖着视口"再滑一段"，就是完成后不流畅的载体。
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

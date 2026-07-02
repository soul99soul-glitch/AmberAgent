import Foundation
import CoreGraphics

/// L3 滚动仲裁者的纯核心:无 UIKit 依赖,(状态, 输入) → (新状态, 动作)。
///
/// 背景:offset 目前有多个程序性写者且无仲裁,曾致振荡事故。仲裁者是唯一 offset
/// 写者的"马达";政策(该不该跟随)仍归 ChatViewportReducer,这里只管"怎么动"。
/// 外壳(P3.2)负责 display link、真实 offset 写入与几何采样;本文件不接线、不改
/// 任何现有产品代码。
///
/// 两个关键机制,不可简化:
/// 1. **virtual offset**:following 状态自持一份虚拟偏移,插值永远从上次写入值
///    出发,绝不从实时 offset 出发。实时值会被 ChatLayout 批量补偿瞬移,若插值
///    从它出发就没有东西可平滑——这正是上次振荡事故的根因。
/// 2. **单调目标钳制**:following 期间 target 只增不减(纯追加流式中
///    contentSize 真值只增,估算导致的下探是伪信号)。状态退出/会话重置时清除。
enum ChatScrollArbiterState: Equatable {
    case idle
    /// 用户手势接管:一切程序性意图被忽略,直到 userReleased。
    case userControlled
    /// 进会话收敛锚定(外壳执行多轮 scroll+layout 校验)。锚定完成后不由核心
    /// 感知一个专门的"完成"意图——core 把 anchoring 当作一个不锁定后续行为的
    /// 标记态:下一个到达的意图按 idle 语义处理(见 reduce 顶部注释)。
    case anchoring
    /// 流式连续跟随:自持虚拟偏移与单调目标。
    case following(virtualOffset: CGFloat, target: CGFloat, lastFollowRequestAt: TimeInterval)
    /// 一次性跳转收敛(外壳执行 performSnap)。语义同 anchoring:不锁定后续行为。
    case snapping
}

enum ChatScrollIntent: Equatable {
    case userTakeover
    case userReleased
    case anchorToBottom
    case followTail
    case snapToBottom(animated: Bool)
    case conversationReset
}

struct ChatScrollGeometrySample: Equatable {
    var currentOffset: CGFloat
    var bottomTarget: CGFloat
    var userInteracting: Bool   // isTracking || isDragging || isDecelerating
}

enum ChatScrollArbiterAction: Equatable {
    case none
    case performAnchor
    case performSnap(animated: Bool)
    case writeOffset(CGFloat)
    case startDisplayLink
    case stopDisplayLink
}

enum ChatScrollArbiterCore {
    /// 趋近时间常数(秒):约 80ms 走完剩余距离的 63%。
    static let tau: TimeInterval = 0.08
    /// 到达目标且这么久没有新 follow 请求就停表回 idle。
    static let idleStopInterval: TimeInterval = 0.3
    /// 视为到达目标的距离阈值。
    static let arrivalEpsilon: CGFloat = 0.5

    /// 单调目标钳制的唯一实现:防振荡安全不变量,target 只增不减。reduce 的
    /// followTail-while-following 分支与 tick 的每帧钳制必须共享这同一公式,
    /// 不允许分叉出第二份实现(上次振荡事故的教训)。
    ///
    /// NaN/Infinity 防护内建于此:`incoming` 非有限时直接返回 `current`,不参与
    /// 比较。这是必须的——一旦 NaN 混入 target,`max` 的任何后续比较都恒为
    /// false,状态会被永久污染且无法自愈,必须在这里挡住,不能指望调用方每处
    /// 都记得校验。
    private static func clampedTarget(current: CGFloat, incoming: CGFloat) -> CGFloat {
        guard incoming.isFinite else { return current }
        return max(current, incoming)
    }

    /// (状态, 意图) → (新状态, 动作序列)。
    ///
    /// 优先级:userTakeover > anchor > snap > follow。
    /// `.anchoring` / `.snapping` 是不锁定行为的"标记态":它们只是外壳当前正在
    /// 执行一次性收敛动作的记录,核心自身不知道该动作何时完成。因此当 reduce
    /// 在这两个状态下收到*任何*后续意图时,一律按该意图在 `.idle` 下的语义处理
    /// (等价于先隐式转回 idle 再处理),不需要额外引入"完成"意图。
    static func reduce(
        state: ChatScrollArbiterState,
        intent: ChatScrollIntent,
        geometry: ChatScrollGeometrySample,
        now: TimeInterval
    ) -> (state: ChatScrollArbiterState, actions: [ChatScrollArbiterAction]) {
        // userControlled 是唯一会拦截意图的状态:除 userReleased / conversationReset
        // 外,一切程序性意图必须被忽略。
        if case .userControlled = state {
            switch intent {
            case .userReleased:
                return (.idle, [])
            case .conversationReset:
                return (.idle, [])
            default:
                return (state, [])
            }
        }

        // anchoring / snapping 视同 idle 处理后续意图(见上方文档注释)。
        let effectiveState: ChatScrollArbiterState
        switch state {
        case .anchoring, .snapping:
            effectiveState = .idle
        default:
            effectiveState = state
        }

        switch intent {
        case .userTakeover:
            let stopActions: [ChatScrollArbiterAction]
            if case .following = effectiveState {
                stopActions = [.stopDisplayLink]
            } else {
                stopActions = []
            }
            return (.userControlled, stopActions)

        case .userReleased:
            // 仅在 userControlled 下有效,已在上方处理;此处一律忽略。
            return (state, [])

        case .conversationReset:
            if case .following = effectiveState {
                return (.idle, [.stopDisplayLink])
            }
            return (.idle, [])

        case .anchorToBottom:
            var actions: [ChatScrollArbiterAction] = []
            if case .following = effectiveState {
                actions.append(.stopDisplayLink)
            }
            actions.append(.performAnchor)
            return (.anchoring, actions)

        case .snapToBottom(let animated):
            var actions: [ChatScrollArbiterAction] = []
            if case .following = effectiveState {
                actions.append(.stopDisplayLink)
            }
            actions.append(.performSnap(animated: animated))
            return (.snapping, actions)

        case .followTail:
            switch effectiveState {
            case .following(let virtualOffset, let target, _):
                // NaN/Infinity 防护:非有限的 bottomTarget 样本被 clampedTarget 忽略,
                // target 保持不变,不参与 max 比较——避免污染永久卡死状态。
                let newTarget = clampedTarget(current: target, incoming: geometry.bottomTarget)
                return (.following(virtualOffset: virtualOffset, target: newTarget, lastFollowRequestAt: now), [])
            default:
                // 起步基线:唯一一次读取实时 offset 作为 virtual 的初始值。
                // NaN/Infinity 防护:起步值本身若非有限,直接拒绝进入 following 并
                // 原样返回传入状态——一旦 NaN 成为 virtualOffset 的初始值,后续每帧
                // 插值都会永久污染,没有任何补救手段,必须挡在门外。
                guard geometry.currentOffset.isFinite else {
                    return (state, [])
                }
                let initialTarget = clampedTarget(current: geometry.currentOffset, incoming: geometry.bottomTarget)
                return (
                    .following(virtualOffset: geometry.currentOffset, target: initialTarget, lastFollowRequestAt: now),
                    [.startDisplayLink]
                )
            }
        }
    }

    /// following 状态的每帧推进;其它状态原样返回、动作为 `[.none]`。
    static func tick(
        state: ChatScrollArbiterState,
        geometry: ChatScrollGeometrySample,
        now: TimeInterval,
        dt: TimeInterval
    ) -> (state: ChatScrollArbiterState, actions: [ChatScrollArbiterAction]) {
        guard case .following(let virtualOffset, let target, let lastFollowRequestAt) = state else {
            return (state, [.none])
        }

        if geometry.userInteracting {
            return (.userControlled, [.stopDisplayLink])
        }

        // NaN/Infinity 防护:非有限的几何采样本次直接忽略——不更新 target、不写
        // offset,原状态原样返回。一旦 NaN 进入 target,后续的 max 比较会永久
        // 卡死(NaN 与任何数比较恒为 false),这是不可恢复的污染,必须挡在门外。
        // currentOffset 目前不参与本函数下方的算术(virtual 链只从上次写入值
        // 出发),但仍一并校验,为将来任何引用它的改动提供同等保护。
        guard geometry.bottomTarget.isFinite, geometry.currentOffset.isFinite else {
            return (state, [.none])
        }

        // dt 防护:负 dt(时钟回拨、display link 回调乱序等)会让 alpha 变成
        // 负指数之外的异常值,导致 virtual 向 target 反方向过冲。钳到 [0, +∞)
        // 后最坏情况是这一帧原地不动,绝不会倒退。
        let safeDt = max(dt, 0)

        // 单调目标钳制:估算导致的下探是伪信号,target 只增不减。与 reduce 的
        // followTail-while-following 分支共享同一个 clampedTarget 实现。
        let newTarget = clampedTarget(current: target, incoming: geometry.bottomTarget)
        let remaining = newTarget - virtualOffset

        if abs(remaining) < arrivalEpsilon {
            let settledVirtual = newTarget
            if now - lastFollowRequestAt > idleStopInterval {
                // 停表前补一笔精确写入,消除 arrivalEpsilon 允许的残差,再停表。
                return (.idle, [.writeOffset(settledVirtual), .stopDisplayLink])
            }
            let settledState = ChatScrollArbiterState.following(
                virtualOffset: settledVirtual,
                target: newTarget,
                lastFollowRequestAt: lastFollowRequestAt
            )
            return (settledState, [.writeOffset(settledVirtual)])
        }

        // virtual 链上的指数趋近,writeOffset 的值永远来自 virtual,绝不来自
        // geometry.currentOffset(followTail 起步基线除外)——这是上次振荡事故的正解。
        let alpha = 1 - exp(-safeDt / tau)
        let newVirtual = virtualOffset + remaining * alpha
        let newState = ChatScrollArbiterState.following(
            virtualOffset: newVirtual,
            target: newTarget,
            lastFollowRequestAt: lastFollowRequestAt
        )
        return (newState, [.writeOffset(newVirtual)])
    }
}

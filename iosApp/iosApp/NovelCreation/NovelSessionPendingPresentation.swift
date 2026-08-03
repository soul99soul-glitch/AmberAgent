import SwiftUI

/// Copy for the empty-content streaming placeholder in `NovelSessionBubble`.
///
/// quickStart intentionally never shows its raw structured-JSON delta (see
/// `NovelSessionViewModel.publishQuickStartStreamingPhaseIfNeeded`), so the bubble stays
/// content-empty for the whole run. Without this, `.waitingForFirstToken` (nothing has
/// arrived yet) and `.streaming` (the model is actively producing output that is just not
/// rendered) were visually identical, making a 30+s reasoning-heavy run look stuck. This is a
/// pure function so the "different phases render different copy" contract is unit-testable
/// without going through SwiftUI.
enum NovelSessionPendingPresentation {
    static func label(for phase: NovelSessionTransientTailPhase?, elapsed: Int) -> String {
        switch phase {
        case .waitingForFirstToken:
            elapsed >= 2
                ? "模型思考中 \(elapsed) 秒"
                : "正在连接模型"
        case .streaming:
            // 保留跳动的秒数:quickStart 全程内容为空,若文案恒定不变,用户仍会觉得
            // 界面卡死。秒数是这里唯一诚实且零成本的「还活着」信号(不做假进度条)。
            "模型已开始生成 \(elapsed) 秒 · 正在整理输出"
        case .terminalAwaitingRefresh, .interrupted, .failed, .persistenceBlocked, nil:
            // These phases don't render through the empty-content branch in practice
            // (content is non-empty, or the bubble takes a different branch entirely), but
            // keep the original Chat copy as a safe, tested fallback.
            ChatAssistantPendingResponseView.defaultLabel(elapsed: elapsed)
        }
    }
}

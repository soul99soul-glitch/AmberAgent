import Foundation
import UIKit

/// 把「这一轮后台生成能不能活下来」的全部信号压成一行。
///
/// 后台没法下断点，事后只能靠日志定罪：所以每次生命周期变化、每次后台任务
/// 起止/到期都打一条同构的快照，包含 App 状态、剩余后台时间、以及协调器
/// 自己的在飞任务数。同时保留最近若干条到内存环形缓冲，崩溃/异常时可直接读。
@MainActor
enum IOSBackgroundLifecycleLog {
    struct Entry {
        let at: Date
        let line: String
    }

    private static let ringCapacity = 32
    private static var ring: [Entry] = []

    /// 最近一次记录的快照行，nil 表示本进程还没有过生命周期事件。
    private(set) static var lastLine: String?

    /// 最近若干条快照，最新的在最后。诊断入口读取用。
    static var recentEntries: [Entry] { ring }

    static func record(_ transition: String, detail: String = "") {
        let remaining = UIApplication.shared.backgroundTimeRemaining
        // backgroundTimeRemaining 在前台是一个极大的哨兵值，原样打出来只会是噪音。
        let remainingText = remaining > 99_999
            ? "unlimited"
            : String(format: "%.0fs", remaining)
        var line = "[BGLifecycle] → \(transition)"
            + " | app=\(applicationStateText)"
            + " bgRemaining=\(remainingText)"
        if !detail.isEmpty {
            line += " | \(detail)"
        }
        lastLine = line
        ring.append(Entry(at: Date(), line: line))
        if ring.count > ringCapacity {
            ring.removeFirst(ring.count - ringCapacity)
        }
        NSLog("%@", line)
    }

    private static var applicationStateText: String {
        switch UIApplication.shared.applicationState {
        case .active: return "active"
        case .inactive: return "inactive"
        case .background: return "background"
        @unknown default: return "unknown"
        }
    }
}

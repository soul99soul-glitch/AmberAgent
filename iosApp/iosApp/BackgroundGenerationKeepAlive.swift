import BackgroundTasks
import Foundation
import UIKit

/// 流式生成期间的后台执行权，全 App 唯一所有者。
///
/// 与「后台重跑」是两回事：这里不搬运任何生成状态、不重新调模型，只负责在
/// 生成期间保住进程的后台执行权，让正在跑的那条流自己跑完。调用方（聊天 /
/// 生图 / 小说 / 议会）只需在生成开始时 `begin`、终态时 `end`，生成逻辑一行
/// 都不用改。
///
/// 默认使用两条腿：
/// - `beginBackgroundTask`：调用即生效，覆盖「提交」到「系统真正调度」之间的
///   空窗。没有它，App 可能在 BG 任务启动前就被挂起。上限约 30 秒。
/// - `BGContinuedProcessingTask`：系统调度后接管，提供长执行窗口。它的 handler
///   里不干活，只是挂起等 `end`——执行权借给正在跑的那条流。
///
/// 两条腿互为兜底：BG 任务提交失败或系统迟迟不调度，第一条腿仍然撑住 30 秒；
/// BG 任务一旦接管，第一条腿立刻释放，不白占系统配额。
/// 不适合出现在系统 continued-processing UI 的调用方可显式关闭第二条腿；短腿与
/// 到期回调仍保持同一租约语义。
@MainActor
final class BackgroundGenerationKeepAlive {
    typealias BeginBackgroundTask = (String, @escaping () -> Void) -> UIBackgroundTaskIdentifier
    typealias EndBackgroundTask = (UIBackgroundTaskIdentifier) -> Void
    typealias SubmitTaskRequest = (BGContinuedProcessingTaskRequest) throws -> Void
    typealias CancelTaskRequest = (String) -> Void
    typealias RegisterLaunchHandler = (String, @escaping (BGTask) -> Void) -> Bool

    static let shared = BackgroundGenerationKeepAlive()

    /// 一次生成占用的执行权。两条腿的句柄都挂在这里，终态时一起释放。
    private struct Lease {
        var uiTaskId: UIBackgroundTaskIdentifier
        var systemTask: BGContinuedProcessingTask?
        /// BG handler 挂起在这里等 `end`；nil 表示系统还没调度到这一轮。
        var waiter: CheckedContinuation<Void, Never>?
        /// UIKit 短窗口到期时通知上层；系统任务没有专用回调时也沿用它。
        var onExpire: (() -> Void)?
        /// 系统 continued-processing 活动被用户取消或被系统终止时通知上层。
        var onSystemTaskExpiration: (() -> Void)?
    }

    private var leases: [String: Lease] = [:]
    /// 系统只认 task identifier，回调里要靠它反查是哪一轮租约。
    private var leaseIdsByIdentifier: [String: String] = [:]
    /// Continued Processing 每轮使用具体 identifier 注册；同一 run 在本进程内
    /// 重投时复用既有 handler，避免系统判定为重复注册并终止 App。
    private var registeredIdentifiers: Set<String> = []

    private let beginBackgroundTask: BeginBackgroundTask
    private let endBackgroundTask: EndBackgroundTask
    private let submitTaskRequest: SubmitTaskRequest
    private let cancelTaskRequest: CancelTaskRequest
    private let registerLaunchHandler: RegisterLaunchHandler

    init(
        beginBackgroundTask: @escaping BeginBackgroundTask = { name, expiration in
            UIApplication.shared.beginBackgroundTask(withName: name, expirationHandler: expiration)
        },
        endBackgroundTask: @escaping EndBackgroundTask = { identifier in
            UIApplication.shared.endBackgroundTask(identifier)
        },
        submitTaskRequest: @escaping SubmitTaskRequest = { request in
            try BGTaskScheduler.shared.submit(request)
        },
        cancelTaskRequest: @escaping CancelTaskRequest = { identifier in
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
        },
        registerLaunchHandler: @escaping RegisterLaunchHandler = { identifier, handler in
            BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil, launchHandler: handler)
        }
    ) {
        self.beginBackgroundTask = beginBackgroundTask
        self.endBackgroundTask = endBackgroundTask
        self.submitTaskRequest = submitTaskRequest
        self.cancelTaskRequest = cancelTaskRequest
        self.registerLaunchHandler = registerLaunchHandler
    }

    private var bundleIdentifier: String { Bundle.main.bundleIdentifier ?? "app.amber.ios" }

    private var identifierPrefix: String { "\(bundleIdentifier).keepalive." }

    /// 必须落在 Info.plist 的 BGTaskSchedulerPermittedIdentifiers 通配范围内。
    /// 只放行 ASCII 字母数字和连字符——非 ASCII 是否被 BGTaskScheduler 接受没有
    /// 明确契约，一路放过去等于把系统 key 的合法性赌在未文档化的行为上。
    func identifier(for leaseId: String) -> String {
        identifierPrefix + leaseId.map { character in
            character.isASCII && (character.isLetter || character.isNumber)
                ? character
                : "-"
        }.reduce(into: "") { $0.append($1) }
    }

    var activeLeaseIds: Set<String> { Set(leases.keys) }

    /// 这一轮生成是否还握着后台执行权。握着就说明流能自己跑完，
    /// 上层不该去砍流做交接。
    func holdsLease(_ leaseId: String) -> Bool {
        leases[leaseId] != nil
    }

    /// 供生命周期日志读取：当前有几轮生成占着执行权、其中几轮已被系统接管。
    var snapshotDetail: String {
        let adopted = leases.values.filter { $0.systemTask != nil }.count
        return "keepAlive=\(leases.count) adopted=\(adopted)"
    }

    // MARK: - 调用方接口

    /// 生成开始时调用。重复 begin 同一个 id 是幂等的。
    ///
    /// - Parameter onExpire: UIKit 短窗口在系统任务接管前到期时回调。上层在这里
    ///   做后台交接；正常跑完不会触发。
    /// - Parameter onSystemTaskExpiration: 系统进度活动被取消或终止时回调。
    ///   未提供时沿用 `onExpire`，保持既有非 Chat 调用方语义。
    /// - Parameter submitSystemTask: 是否提交系统 continued-processing task。
    ///   关闭时仍持有 UIKit 短任务，并在其到期时执行 `onExpire`。
    func begin(
        _ leaseId: String,
        title: String,
        subtitle: String,
        onExpire: (() -> Void)? = nil,
        onSystemTaskExpiration: (() -> Void)? = nil,
        submitSystemTask: Bool = true
    ) {
        guard leases[leaseId] == nil else { return }

        // 先拿第一条腿。它在 submit 之前就位，才能覆盖住提交到调度的空窗。
        let uiTaskId = beginBackgroundTask("AmberGeneration-\(leaseId)") { [weak self] in
            // 30 秒到了系统还没接管：这条腿必须还回去，否则会被强杀。
            self?.handleUITaskExpiration(leaseId)
        }
        leases[leaseId] = Lease(
            uiTaskId: uiTaskId,
            systemTask: nil,
            waiter: nil,
            onExpire: onExpire,
            onSystemTaskExpiration: onSystemTaskExpiration
        )

        if submitSystemTask {
            submitContinuedTask(leaseId, title: title, subtitle: subtitle)
        }
        IOSBackgroundLifecycleLog.record("keepAliveBegin(\(leaseId))", detail: snapshotDetail)
    }

    /// 生成走到任意终态时调用（完成 / 失败 / 取消都要）。幂等。
    func end(_ leaseId: String) {
        guard let lease = removeLease(leaseId) else { return }

        if lease.uiTaskId != .invalid {
            endBackgroundTask(lease.uiTaskId)
        }
        // 放行挂起的 handler。resume 只是把它排进队列，不会在这里同步跑起来，
        // 所以和下面报完成的先后其实不影响正确性——保持这个顺序只是读起来顺。
        lease.waiter?.resume()
        lease.systemTask?.setTaskCompleted(success: true)
        IOSBackgroundLifecycleLog.record("keepAliveEnd(\(leaseId))", detail: snapshotDetail)
    }

    // MARK: - 内部

    private func submitContinuedTask(_ leaseId: String, title: String, subtitle: String) {
        let taskIdentifier = identifier(for: leaseId)
        leaseIdsByIdentifier[taskIdentifier] = leaseId

        if !registeredIdentifiers.contains(taskIdentifier) {
            let registered = registerLaunchHandler(taskIdentifier) { [weak self] task in
                guard let task = task as? BGContinuedProcessingTask else {
                    task.setTaskCompleted(success: false)
                    return
                }
                Task { @MainActor in
                    guard let self,
                          let leaseId = self.leaseIdsByIdentifier[task.identifier] else {
                        // 没人认领：这一轮早结束了，直接收口，别留孤儿任务。
                        task.setTaskCompleted(success: true)
                        return
                    }
                    await self.adopt(task, leaseId: leaseId)
                }
            }
            guard registered else {
                // 未注册的 request 在真机会触发 Objective-C assertion；不能交给
                // Swift do/catch。短窗口已经在 begin 中拿到，保留它即可。
                NSLog("[AmberKeepAlive] registration refused for \(taskIdentifier)")
                return
            }
            registeredIdentifiers.insert(taskIdentifier)
        }

        let request = BGContinuedProcessingTaskRequest(
            identifier: taskIdentifier,
            title: title,
            subtitle: subtitle
        )
        // .queue 而不是 .fail：排队等系统有资源，好过当场判死。第一条腿在
        // 排队期间撑着，真等不到也只是退化成 30 秒，不会更差。
        request.strategy = .queue

        do {
            try submitTaskRequest(request)
            IOSBackgroundLifecycleLog.record("keepAliveSubmitted(\(leaseId))", detail: snapshotDetail)
        } catch {
            NSLog("[AmberKeepAlive] submit failed for \(taskIdentifier): \(error)")
            IOSBackgroundLifecycleLog.record("keepAliveSubmitFailed(\(leaseId))", detail: snapshotDetail)
        }
    }

    /// 系统调度到这一轮：接管执行权，然后挂起等 `end`。
    /// 这里不重跑、不碰生成状态——正在跑的那条流本来就没停。
    func adopt(_ task: BGContinuedProcessingTask, leaseId: String) async {
        guard leases[leaseId] != nil else {
            // 生成已经结束了，系统才调度过来。直接收口，别留个孤儿任务。
            task.setTaskCompleted(success: true)
            return
        }

        // 系统要求在到期回调里当场报完成，跳一次 actor 再报就晚了，会被判超时。
        // 所以先同步收口，清理再回主 actor 做。
        task.expirationHandler = { [weak self] in
            task.setTaskCompleted(success: false)
            Task { @MainActor in self?.handleSystemExpiration(leaseId) }
        }
        leases[leaseId]?.systemTask = task
        // 系统接管了，第一条腿就该还回去，不白占 30 秒配额。
        releaseUITask(leaseId)
        IOSBackgroundLifecycleLog.record("keepAliveAdopted(\(leaseId))", detail: snapshotDetail)

        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            guard leases[leaseId] != nil else {
                // 真正挡住「end 抢在挂起之前」的是函数开头那道 guard——从那里到
                // 这里全程同步，租约不可能中途消失。这道只是廉价的自证，别当成
                // 关掉了某个竞态。
                continuation.resume()
                return
            }
            leases[leaseId]?.waiter = continuation
        }
    }

    /// 30 秒的短腿到期了。系统任务已接管的话这不算失去执行权，只还短腿；
    /// 没接管才是真的失去执行权，得通知上层收口。
    private func handleUITaskExpiration(_ leaseId: String) {
        guard let lease = leases[leaseId] else { return }
        guard lease.systemTask == nil else {
            releaseUITask(leaseId)
            return
        }
        removeLease(leaseId)
        if lease.uiTaskId != .invalid {
            endBackgroundTask(lease.uiTaskId)
        }
        lease.waiter?.resume()
        IOSBackgroundLifecycleLog.record("keepAliveLapsedBeforeAdoption(\(leaseId))", detail: snapshotDetail)
        // 先摘 lease 再回调：上层此刻查 holdsLease 必须是 false，
        // 否则它的交接逻辑会被自己短路掉，两边都不干活。
        lease.onExpire?()
    }

    /// 系统把长窗口也收走了。执行权到此为止，但生成状态不归这一层管——
    /// 上层各自的中断/恢复逻辑负责收口。
    ///
    /// 只由 `expirationHandler` 调用，`setTaskCompleted` 已经在那里同步报过了。
    private func handleSystemExpiration(_ leaseId: String) {
        guard let lease = removeLease(leaseId) else { return }
        if lease.uiTaskId != .invalid {
            endBackgroundTask(lease.uiTaskId)
        }
        lease.waiter?.resume()
        IOSBackgroundLifecycleLog.record("keepAliveExpired(\(leaseId))", detail: snapshotDetail)
        (lease.onSystemTaskExpiration ?? lease.onExpire)?()
    }

    /// 摘租约必须连 identifier 反查表和已提交的请求一起摘。
    ///
    /// 撤请求是必须的：`.queue` 下提交出去的请求不会自己过期，不撤的话系统会在
    /// 这一轮早就结束之后才调度到它，把 App 唤起来、给用户弹一张名不副实的
    /// 「正在生成」进度卡，白烧一次唤醒。已经被调度走的那些 cancel 是 no-op。
    @discardableResult
    private func removeLease(_ leaseId: String) -> Lease? {
        let taskIdentifier = identifier(for: leaseId)
        leaseIdsByIdentifier.removeValue(forKey: taskIdentifier)
        cancelTaskRequest(taskIdentifier)
        return leases.removeValue(forKey: leaseId)
    }

    /// 只还第一条腿，租约本身留着（系统任务可能还撑着）。
    private func releaseUITask(_ leaseId: String) {
        guard let lease = leases[leaseId], lease.uiTaskId != .invalid else { return }
        endBackgroundTask(lease.uiTaskId)
        leases[leaseId]?.uiTaskId = .invalid
    }
}

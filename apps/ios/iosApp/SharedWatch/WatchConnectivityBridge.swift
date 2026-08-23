import Foundation
import WatchConnectivity

protocol WatchConnectivityTransporting: AnyObject {
    var isSupported: Bool { get }
    var isPaired: Bool { get }
    var isWatchAppInstalled: Bool { get }
    var isReachable: Bool { get }
    func activate()
    func updateApplicationContext(_ context: [String: Any]) throws
    func transferUserInfo(_ userInfo: [String: Any]) -> String
    func sendMessage(
        _ message: [String: Any],
        replyHandler: (([String: Any]) -> Void)?,
        errorHandler: ((Error) -> Void)?
    )
}

#if os(iOS) || os(watchOS)
final class SystemWatchConnectivityTransport: NSObject, WatchConnectivityTransporting {
    private let session: WCSession

    init(session: WCSession = .default) {
        self.session = session
        super.init()
    }

    var isSupported: Bool { WCSession.isSupported() }
    var isPaired: Bool {
        #if os(iOS)
        session.isPaired
        #else
        true
        #endif
    }
    var isWatchAppInstalled: Bool {
        #if os(iOS)
        session.isWatchAppInstalled
        #else
        true
        #endif
    }
    var isReachable: Bool { session.isReachable }

    func activate() {
        guard isSupported else { return }
        session.activate()
    }

    func updateApplicationContext(_ context: [String: Any]) throws {
        try session.updateApplicationContext(context)
    }

    func transferUserInfo(_ userInfo: [String: Any]) -> String {
        session.transferUserInfo(userInfo).description
    }

    func sendMessage(
        _ message: [String: Any],
        replyHandler: (([String: Any]) -> Void)?,
        errorHandler: ((Error) -> Void)?
    ) {
        session.sendMessage(message, replyHandler: replyHandler, errorHandler: errorHandler)
    }

    var underlyingSession: WCSession { session }
}
#endif

@MainActor
protocol WatchTaskActionHandling: AnyObject {
    func handleWatchAction(_ request: WatchTaskActionRequest) async -> WatchTaskActionResult
}

/// Decoded on the WCSession queue so MainActor only receives Sendable values.
private enum WatchInboundEnvelope: Sendable {
    case snapshot(WatchTaskSnapshot)
    case action(WatchTaskActionRequest)
    case actionResult(WatchTaskActionResult)
    case requestSnapshot
    case ignored
}

@MainActor
final class WatchConnectivityBridge: NSObject {
    static let shared = WatchConnectivityBridge()

    private(set) var latestSnapshot: WatchTaskSnapshot = .idle
    private var transport: WatchConnectivityTransporting?
    private weak var actionHandler: WatchTaskActionHandling?
    private var lastPushedSnapshot: WatchTaskSnapshot?
    private var isActivated = false
    private let actionTimeoutNanoseconds: UInt64
    private var actionTimeoutTasks: [String: Task<Void, Never>] = [:]

    var onSnapshotUpdated: ((WatchTaskSnapshot) -> Void)?
    var onActionResult: ((WatchTaskActionResult) -> Void)?

    override init() {
        actionTimeoutNanoseconds = 12_000_000_000
        super.init()
    }

    init(actionTimeoutNanoseconds: UInt64) {
        self.actionTimeoutNanoseconds = actionTimeoutNanoseconds
        super.init()
    }

    func configure(
        transport: WatchConnectivityTransporting? = nil,
        actionHandler: WatchTaskActionHandling? = nil
    ) {
        #if os(iOS) || os(watchOS)
        if let transport {
            self.transport = transport
        } else if self.transport == nil {
            let system = SystemWatchConnectivityTransport()
            self.transport = system
            system.underlyingSession.delegate = self
        }
        #endif
        if let actionHandler {
            self.actionHandler = actionHandler
        }
        activateIfNeeded()
    }

    func activateIfNeeded() {
        guard let transport, transport.isSupported, !isActivated else { return }
        transport.activate()
        isActivated = true
    }

    func publish(_ snapshot: WatchTaskSnapshot) {
        latestSnapshot = snapshot
        guard let transport, transport.isSupported else { return }
        activateIfNeeded()
        guard lastPushedSnapshot != snapshot else { return }
        lastPushedSnapshot = snapshot

        do {
            let message = try WatchTaskCodec.snapshotMessage(for: snapshot)
            try transport.updateApplicationContext(message)
            if transport.isReachable {
                transport.sendMessage(message, replyHandler: nil, errorHandler: nil)
            } else {
                _ = transport.transferUserInfo(message)
            }
        } catch {
            // Best-effort companion sync; iPhone remains authoritative.
        }
    }

    func clear() {
        var idle = WatchTaskSnapshot.idle
        idle.updatedAt = Date()
        publish(idle)
    }

    func requestSnapshotFromPhone() {
        guard let transport, transport.isSupported else { return }
        activateIfNeeded()
        // Only request when reachable; otherwise keep local/applicationContext snapshot.
        guard transport.isReachable else { return }
        let message = WatchTaskCodec.requestSnapshotMessage()
        transport.sendMessage(message, replyHandler: { [weak self] reply in
            let envelope = Self.decodeEnvelope(reply)
            Task { @MainActor in
                self?.apply(envelope)
            }
        }, errorHandler: nil)
    }

    func sendAction(_ request: WatchTaskActionRequest) {
        guard let transport, transport.isSupported else {
            reportActionFailure(request, message: "无法连接 iPhone，请稍后重试")
            return
        }
        activateIfNeeded()
        scheduleActionTimeout(for: request)
        do {
            let message = try WatchTaskCodec.actionMessage(for: request)
            if transport.isReachable {
                transport.sendMessage(message, replyHandler: { [weak self] reply in
                    let envelope = Self.decodeEnvelope(reply)
                    Task { @MainActor in
                        self?.apply(envelope)
                    }
                }, errorHandler: { [weak self] _ in
                    _ = transport.transferUserInfo(message)
                    Task { @MainActor in
                        self?.reportActionFailure(request, message: "发送到 iPhone 失败，请稍后重试")
                    }
                })
            } else {
                _ = transport.transferUserInfo(message)
            }
        } catch {
            reportActionFailure(request, message: "发送到 iPhone 失败，请稍后重试")
        }
    }

    private func scheduleActionTimeout(for request: WatchTaskActionRequest) {
        actionTimeoutTasks[request.requestId]?.cancel()
        actionTimeoutTasks[request.requestId] = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: self?.actionTimeoutNanoseconds ?? 0)
            } catch {
                return
            }
            guard let self else { return }
            self.reportActionFailure(request, message: "iPhone 响应超时，请重试")
        }
    }

    private func reportActionFailure(_ request: WatchTaskActionRequest, message: String) {
        actionTimeoutTasks.removeValue(forKey: request.requestId)?.cancel()
        onActionResult?(WatchTaskActionResult(
            requestId: request.requestId,
            runId: request.runId,
            accepted: false,
            message: message,
            snapshot: latestSnapshot
        ))
    }

    fileprivate func apply(_ envelope: WatchInboundEnvelope) {
        switch envelope {
        case .snapshot(let snapshot):
            latestSnapshot = snapshot
            onSnapshotUpdated?(snapshot)
        case .action(let request):
            #if os(iOS)
            Task { @MainActor in
                let result = await self.actionHandler?.handleWatchAction(request)
                    ?? WatchTaskActionResult(
                        requestId: request.requestId,
                        runId: request.runId,
                        accepted: false,
                        message: "iPhone 当前无法处理手表操作",
                        snapshot: self.latestSnapshot
                    )
                self.reply(with: result)
            }
            #endif
        case .actionResult(let result):
            actionTimeoutTasks.removeValue(forKey: result.requestId)?.cancel()
            if let snapshot = result.snapshot {
                latestSnapshot = snapshot
                onSnapshotUpdated?(snapshot)
            }
            onActionResult?(result)
        case .requestSnapshot:
            #if os(iOS)
            publish(latestSnapshot)
            #endif
        case .ignored:
            break
        }
    }

    #if os(iOS)
    private func reply(with result: WatchTaskActionResult) {
        if let snapshot = result.snapshot {
            publish(snapshot)
        }
        guard let transport, transport.isSupported else { return }
        do {
            let message = try WatchTaskCodec.resultMessage(for: result)
            if transport.isReachable {
                transport.sendMessage(message, replyHandler: nil, errorHandler: nil)
            } else {
                _ = transport.transferUserInfo(message)
            }
        } catch {
            // Ignore reply transport failures.
        }
    }
    #endif

    nonisolated fileprivate static func decodeEnvelope(_ message: [String: Any]) -> WatchInboundEnvelope {
        if let version = message[WatchConnectivityPayloadKey.protocolVersion] as? Int,
           version != WatchConnectivityPayloadKey.currentProtocolVersion {
            return .ignored
        }
        guard let type = message[WatchConnectivityPayloadKey.type] as? String else {
            return .ignored
        }
        switch type {
        case WatchConnectivityPayloadKey.typeSnapshot:
            guard let data = message[WatchConnectivityPayloadKey.snapshot] as? Data,
                  let snapshot = try? WatchTaskCodec.decodeSnapshot(data) else {
                return .ignored
            }
            return .snapshot(snapshot)
        case WatchConnectivityPayloadKey.typeAction:
            guard let data = message[WatchConnectivityPayloadKey.action] as? Data,
                  let request = try? WatchTaskCodec.decodeAction(data) else {
                return .ignored
            }
            return .action(request)
        case WatchConnectivityPayloadKey.typeActionResult:
            guard let data = message[WatchConnectivityPayloadKey.result] as? Data,
                  let result = try? WatchTaskCodec.decodeResult(data) else {
                return .ignored
            }
            return .actionResult(result)
        case WatchConnectivityPayloadKey.typeRequestSnapshot:
            return .requestSnapshot
        default:
            return .ignored
        }
    }
}

#if os(iOS) || os(watchOS)
extension WatchConnectivityBridge: WCSessionDelegate {
    nonisolated func session(
        _ session: WCSession,
        activationDidCompleteWith activationState: WCSessionActivationState,
        error: Error?
    ) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            #if os(watchOS)
            self.requestSnapshotFromPhone()
            #else
            self.publish(self.latestSnapshot)
            #endif
        }
    }

    #if os(iOS)
    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated func sessionDidDeactivate(_ session: WCSession) {
        session.activate()
    }
    #endif

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let reachable = session.isReachable
        Task { @MainActor [weak self] in
            guard let self, reachable else { return }
            #if os(watchOS)
            self.requestSnapshotFromPhone()
            #else
            self.publish(self.latestSnapshot)
            #endif
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveMessage message: [String: Any]
    ) {
        let envelope = Self.decodeEnvelope(message)
        Task { @MainActor [weak self] in
            self?.apply(envelope)
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveMessage message: [String: Any],
        replyHandler: @escaping ([String: Any]) -> Void
    ) {
        let envelope = Self.decodeEnvelope(message)
        let reply = UnsafeReplyHandler(replyHandler)
        Task { @MainActor [weak self] in
            guard let self else {
                reply.respond([:])
                return
            }
            self.apply(envelope)
            #if os(iOS)
            if case .requestSnapshot = envelope,
               let response = try? WatchTaskCodec.snapshotMessage(for: self.latestSnapshot) {
                reply.respond(response)
                return
            }
            #endif
            reply.respond([:])
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveApplicationContext applicationContext: [String: Any]
    ) {
        let envelope = Self.decodeEnvelope(applicationContext)
        Task { @MainActor [weak self] in
            self?.apply(envelope)
        }
    }

    nonisolated func session(
        _ session: WCSession,
        didReceiveUserInfo userInfo: [String: Any] = [:]
    ) {
        let envelope = Self.decodeEnvelope(userInfo)
        Task { @MainActor [weak self] in
            self?.apply(envelope)
        }
    }
}

/// Tiny wrapper so escaping WCSession reply handlers can be invoked after a MainActor hop.
private final class UnsafeReplyHandler: @unchecked Sendable {
    private let handler: ([String: Any]) -> Void

    init(_ handler: @escaping ([String: Any]) -> Void) {
        self.handler = handler
    }

    func respond(_ payload: [String: Any]) {
        handler(payload)
    }
}
#endif

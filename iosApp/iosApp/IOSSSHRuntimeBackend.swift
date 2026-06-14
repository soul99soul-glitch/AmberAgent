import Foundation

#if canImport(CryptoKit)
import CryptoKit
#endif

#if canImport(NIOCore) && canImport(NIOPosix) && canImport(NIOSSH)
import NIOCore
import NIOPosix
import NIOSSH
#endif

final class IOSSSHRuntimeBackend: IOSSSHRuntimeBackendProtocol, @unchecked Sendable {
    func testConnection(profile: IOSSSHProfile, password: String) async throws -> IOSSSHConnectionProbeResult {
        let validated = try profile.validated()
        guard !password.isEmpty else { throw IOSSSHError.missingPassword }

        #if canImport(NIOCore) && canImport(NIOPosix) && canImport(NIOSSH)
        let session = IOSSSHActiveSessionRegistry()
        return try await withTaskCancellationHandler {
            try await Task.detached {
                try IOSSSHExecClient.probe(profile: validated, password: password, registry: session)
            }.value
        } onCancel: {
            session.cancelAll()
        }
        #else
        throw IOSSSHError.backendUnavailable
        #endif
    }

    func execute(
        command: String,
        profile: IOSSSHProfile,
        password: String,
        timeout: TimeInterval,
        output: @escaping @Sendable (String) -> Void
    ) async throws -> IOSSSHCommandResult {
        let validated = try profile.validated()
        guard !password.isEmpty else { throw IOSSSHError.missingPassword }
        guard let fingerprint = validated.knownHostSHA256, !fingerprint.isEmpty else {
            throw IOSSSHError.hostKeyNotTrusted("Run Test Connection and Trust Host first.")
        }

        #if canImport(NIOCore) && canImport(NIOPosix) && canImport(NIOSSH)
        let session = IOSSSHActiveSessionRegistry()
        return try await withTaskCancellationHandler {
            try await Task.detached {
                try IOSSSHExecClient.execute(
                    command: command,
                    profile: validated,
                    password: password,
                    timeout: timeout,
                    output: output,
                    registry: session
                )
            }.value
        } onCancel: {
            session.cancelAll()
        }
        #else
        throw IOSSSHError.backendUnavailable
        #endif
    }
}

#if canImport(NIOCore) && canImport(NIOPosix) && canImport(NIOSSH)
private final class IOSSSHActiveSessionRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var channels: [ObjectIdentifier: Channel] = [:]
    private var cancelled = false

    func register(_ channel: Channel) {
        lock.lock()
        channels[ObjectIdentifier(channel)] = channel
        lock.unlock()
    }

    func unregister(_ channel: Channel) {
        lock.lock()
        channels.removeValue(forKey: ObjectIdentifier(channel))
        lock.unlock()
    }

    func cancelAll() {
        lock.lock()
        cancelled = true
        let snapshot = Array(channels.values)
        lock.unlock()

        for channel in snapshot {
            channel.close(promise: nil)
        }
    }

    var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }
}

private enum IOSSSHClientError: Error {
    case invalidChannelType
    case invalidHostKey
    case passwordAuthenticationNotSupported
    case probeComplete
}

private enum IOSSSHExecClient {
    static func probe(
        profile: IOSSSHProfile,
        password: String,
        registry: IOSSSHActiveSessionRegistry
    ) throws -> IOSSSHConnectionProbeResult {
        let result = try connect(
            profile: profile,
            password: password,
            command: nil,
            output: nil,
            enforceHostKey: false,
            abortAfterHostKey: IOSSSHProbePolicy.abortsAfterHostKey,
            timeout: 15,
            registry: registry
        )
        return IOSSSHConnectionProbeResult(fingerprint: result.fingerprint, trustState: result.trustState)
    }

    static func execute(
        command: String,
        profile: IOSSSHProfile,
        password: String,
        timeout: TimeInterval,
        output: @escaping @Sendable (String) -> Void,
        registry: IOSSSHActiveSessionRegistry
    ) throws -> IOSSSHCommandResult {
        let result = try connect(
            profile: profile,
            password: password,
            command: command,
            output: output,
            enforceHostKey: true,
            abortAfterHostKey: false,
            timeout: timeout,
            registry: registry
        )
        if case .trusted = result.trustState {
            return result.commandResult ?? IOSSSHCommandResult(output: "", exitCode: nil)
        } else {
            switch result.trustState {
            case .trusted:
                return result.commandResult ?? IOSSSHCommandResult(output: "", exitCode: nil)
            case .needsTrust(let fingerprint):
                throw IOSSSHError.hostKeyNotTrusted(fingerprint)
            case .mismatch(let expected, let actual):
                throw IOSSSHError.hostKeyMismatch(expected: expected, actual: actual)
            }
        }
    }

    private static func connect(
        profile: IOSSSHProfile,
        password: String,
        command: String?,
        output: (@Sendable (String) -> Void)?,
        enforceHostKey: Bool,
        abortAfterHostKey: Bool,
        timeout: TimeInterval,
        registry: IOSSSHActiveSessionRegistry
    ) throws -> (fingerprint: String, trustState: IOSSSHTrustState, commandResult: IOSSSHCommandResult?) {
        let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)
        defer { try? group.syncShutdownGracefully() }

        let authDelegate = IOSSSHPasswordAuthDelegate(
            username: profile.username,
            password: command == nil ? IOSSSHProbePolicy.passwordOffer(realPassword: password) : password
        )
        let hostDelegate = IOSSSHHostKeyDelegate(
            expectedFingerprint: profile.knownHostSHA256,
            enforceHostKey: enforceHostKey,
            abortAfterHostKey: abortAfterHostKey
        )

        let bootstrap = ClientBootstrap(group: group)
            .channelInitializer { channel in
                channel.eventLoop.makeCompletedFuture {
                    let ssh = NIOSSHHandler(
                        role: .client(
                            .init(
                                userAuthDelegate: authDelegate,
                                serverAuthDelegate: hostDelegate
                            )
                        ),
                        allocator: channel.allocator,
                        inboundChildChannelInitializer: nil
                    )
                    let sync = channel.pipeline.syncOperations
                    try sync.addHandler(ssh)
                    try sync.addHandler(IOSSSHErrorHandler())
                }
            }
            .channelOption(ChannelOptions.socket(SocketOptionLevel(SOL_SOCKET), SO_REUSEADDR), value: 1)
            .channelOption(ChannelOptions.socket(SocketOptionLevel(IPPROTO_TCP), TCP_NODELAY), value: 1)
            .channelOption(ChannelOptions.connectTimeout, value: .milliseconds(Int64(max(timeout, 0.1) * 1000)))

        let channel: Channel
        do {
            channel = try bootstrap.connect(host: profile.host, port: profile.port).wait()
        } catch {
            if let probeResult = waitForProbeResult(from: hostDelegate, timeout: 2) {
                return (probeResult.fingerprint, probeResult.trustState, nil)
            }
            throw error
        }
        registry.register(channel)
        defer { registry.unregister(channel) }

        let timeoutTask = command == nil
            ? channel.eventLoop.scheduleTask(
                in: .milliseconds(Int64(max(timeout, 0.1) * 1000))
            ) {
                channel.close(promise: nil)
            }
            : nil
        defer { timeoutTask?.cancel() }

        guard let command else {
            var probeError: Error?
            do {
                try triggerProbeHandshake(channel: channel)
            } catch {
                probeError = error
            }
            guard let probeResult = waitForProbeResult(from: hostDelegate, timeout: 2) else {
                throw probeError ?? IOSSSHError.commandTimedOut
            }
            try? channel.close().wait()
            return (probeResult.fingerprint, probeResult.trustState, nil)
        }

        guard let hostResult = waitForProbeResult(from: hostDelegate, timeout: 2) else {
            throw IOSSSHError.commandTimedOut
        }
        let fingerprint = hostResult.fingerprint
        let trustState = hostResult.trustState

        guard case .trusted = trustState else {
            try channel.close().wait()
            return (fingerprint, trustState, nil)
        }

        let resultPromise = channel.eventLoop.makePromise(of: IOSSSHCommandResult.self)
        let childChannel: Channel
        do {
            childChannel = try channel.pipeline.handler(type: NIOSSHHandler.self).flatMap { sshHandler in
                let promise = channel.eventLoop.makePromise(of: Channel.self)
                sshHandler.createChannel(promise) { childChannel, channelType in
                    guard channelType == .session else {
                        return childChannel.eventLoop.makeFailedFuture(IOSSSHClientError.invalidChannelType)
                    }
                    return childChannel.eventLoop.makeCompletedFuture {
                        let sync = childChannel.pipeline.syncOperations
                        try sync.addHandler(
                            IOSSSHExecHandler(
                                command: command,
                                output: output,
                                completePromise: resultPromise,
                                timeout: timeout
                            )
                        )
                        try sync.addHandler(IOSSSHErrorHandler())
                    }
                }
                return promise.futureResult
            }.wait()
        } catch {
            resultPromise.fail(error)
            throw error
        }
        registry.register(childChannel)
        defer { registry.unregister(childChannel) }

        let commandResult: IOSSSHCommandResult
        do {
            commandResult = try resultPromise.futureResult.wait()
        } catch {
            if registry.isCancelled {
                throw IOSSSHError.commandCancelled
            }
            throw error
        }
        if registry.isCancelled {
            throw IOSSSHError.commandCancelled
        }
        try? childChannel.closeFuture.wait()
        try? channel.close().wait()
        return (fingerprint, trustState, commandResult)
    }

    private static func waitForProbeResult(
        from hostDelegate: IOSSSHHostKeyDelegate,
        timeout: TimeInterval
    ) -> IOSSSHConnectionProbeResult? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let result = hostDelegate.probeResult {
                return result
            }
            Thread.sleep(forTimeInterval: 0.01)
        }
        return hostDelegate.probeResult
    }

    private static func triggerProbeHandshake(channel: Channel) throws {
        do {
            _ = try channel.pipeline.handler(type: NIOSSHHandler.self).flatMap { sshHandler in
                let promise = channel.eventLoop.makePromise(of: Channel.self)
                sshHandler.createChannel(promise) { childChannel, channelType in
                    guard channelType == .session else {
                        return childChannel.eventLoop.makeFailedFuture(IOSSSHClientError.invalidChannelType)
                    }
                    return childChannel.eventLoop.makeFailedFuture(IOSSSHClientError.probeComplete)
                }
                return promise.futureResult
            }.wait()
        } catch IOSSSHClientError.probeComplete {
            return
        } catch IOSSSHError.hostKeyNotTrusted {
            return
        } catch IOSSSHError.hostKeyMismatch {
            return
        } catch {
            if channel.isActive {
                throw error
            }
        }
    }
}

private final class IOSSSHPasswordAuthDelegate: NIOSSHClientUserAuthenticationDelegate, Sendable {
    private let username: String
    private let password: String

    init(username: String, password: String) {
        self.username = username
        self.password = password
    }

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        guard availableMethods.contains(.password) else {
            nextChallengePromise.fail(IOSSSHClientError.passwordAuthenticationNotSupported)
            return
        }
        nextChallengePromise.succeed(
            NIOSSHUserAuthenticationOffer(
                username: username,
                serviceName: "",
                offer: .password(.init(password: password))
            )
        )
    }
}

private final class IOSSSHHostKeyDelegate: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var capturedProbeResult: IOSSSHConnectionProbeResult?

    private let expectedFingerprint: String?
    private let enforceHostKey: Bool
    private let abortAfterHostKey: Bool

    init(
        expectedFingerprint: String?,
        enforceHostKey: Bool,
        abortAfterHostKey: Bool
    ) {
        self.expectedFingerprint = expectedFingerprint
        self.enforceHostKey = enforceHostKey
        self.abortAfterHostKey = abortAfterHostKey
    }

    var probeResult: IOSSSHConnectionProbeResult? {
        lock.lock()
        defer { lock.unlock() }
        return capturedProbeResult
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        do {
            let fingerprint = try IOSSSHHostKeyFingerprint.sha256(hostKey: hostKey)
            if let expectedFingerprint, !expectedFingerprint.isEmpty {
                if expectedFingerprint == fingerprint {
                    recordProbeResult(fingerprint: fingerprint, trustState: .trusted)
                    if abortAfterHostKey {
                        validationCompletePromise.fail(IOSSSHClientError.probeComplete)
                    } else {
                        validationCompletePromise.succeed(())
                    }
                } else {
                    let error = IOSSSHError.hostKeyMismatch(expected: expectedFingerprint, actual: fingerprint)
                    let trustState = IOSSSHTrustState.mismatch(expected: expectedFingerprint, actual: fingerprint)
                    recordProbeResult(fingerprint: fingerprint, trustState: trustState)
                    if abortAfterHostKey || enforceHostKey {
                        validationCompletePromise.fail(error)
                    } else {
                        validationCompletePromise.succeed(())
                    }
                }
            } else {
                let trustState = IOSSSHTrustState.needsTrust(fingerprint: fingerprint)
                recordProbeResult(fingerprint: fingerprint, trustState: trustState)
                if abortAfterHostKey || enforceHostKey {
                    validationCompletePromise.fail(IOSSSHError.hostKeyNotTrusted(fingerprint))
                } else {
                    validationCompletePromise.succeed(())
                }
            }
        } catch {
            validationCompletePromise.fail(error)
        }
    }

    private func recordProbeResult(fingerprint: String, trustState: IOSSSHTrustState) {
        lock.lock()
        capturedProbeResult = IOSSSHConnectionProbeResult(fingerprint: fingerprint, trustState: trustState)
        lock.unlock()
    }
}

private enum IOSSSHHostKeyFingerprint {
    static func sha256(hostKey: NIOSSHPublicKey) throws -> String {
        #if canImport(CryptoKit)
        let openSSH = String(openSSHPublicKey: hostKey)
        let parts = openSSH.split(separator: " ")
        guard parts.count >= 2,
              let keyData = Data(base64Encoded: String(parts[1])) else {
            throw IOSSSHClientError.invalidHostKey
        }
        let digest = SHA256.hash(data: keyData)
        let encoded = Data(digest).base64EncodedString().replacingOccurrences(of: "=", with: "")
        return "SHA256:\(encoded)"
        #else
        throw IOSSSHClientError.invalidHostKey
        #endif
    }
}

private final class IOSSSHChannelCloser: @unchecked Sendable {
    private let channel: Channel

    init(channel: Channel) {
        self.channel = channel
    }

    func close() {
        channel.close(promise: nil)
    }
}

private final class IOSSSHExecHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData

    private static let outputTailLimit = 128 * 1024

    private let command: String
    private let output: (@Sendable (String) -> Void)?
    private var completePromise: EventLoopPromise<IOSSSHCommandResult>?
    private var outputBuffer = ""
    private var exitCode: Int?

    init(
        command: String,
        output: (@Sendable (String) -> Void)?,
        completePromise: EventLoopPromise<IOSSSHCommandResult>,
        timeout: TimeInterval
    ) {
        self.command = command
        self.output = output
        self.completePromise = completePromise
        self.timeout = timeout
    }

    private let timeout: TimeInterval
    private var timeoutTask: Scheduled<Void>?

    func channelActive(context: ChannelHandlerContext) {
        let execRequest = SSHChannelRequestEvent.ExecRequest(command: command, wantReply: false)
        let closer = IOSSSHChannelCloser(channel: context.channel)
        timeoutTask = context.eventLoop.scheduleTask(
            in: .milliseconds(Int64(max(timeout, 0.1) * 1000))
        ) { [weak self, closer] in
            self?.failIfNeeded(IOSSSHError.commandTimedOut)
            closer.close()
        }
        context.triggerUserOutboundEvent(execRequest, promise: nil)
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let data = unwrapInboundIn(data)
        guard case .byteBuffer(var bytes) = data.data else { return }
        let chunk = bytes.readString(length: bytes.readableBytes) ?? ""
        guard !chunk.isEmpty else { return }
        outputBuffer = limitedTail(outputBuffer + chunk)
        output?(chunk)
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        switch event {
        case let event as SSHChannelRequestEvent.ExitStatus:
            exitCode = event.exitStatus
            context.close(promise: nil)
        default:
            context.fireUserInboundEventTriggered(event)
        }
    }

    func channelInactive(context: ChannelHandlerContext) {
        completeIfNeeded()
    }

    private func completeIfNeeded() {
        guard let completePromise else { return }
        self.completePromise = nil
        timeoutTask?.cancel()
        completePromise.succeed(IOSSSHCommandResult(output: outputBuffer, exitCode: exitCode))
    }

    private func failIfNeeded(_ error: Error) {
        guard let completePromise else { return }
        self.completePromise = nil
        timeoutTask?.cancel()
        completePromise.fail(error)
    }

    private func limitedTail(_ value: String) -> String {
        let utf8 = Array(value.utf8)
        guard utf8.count > Self.outputTailLimit else { return value }
        return String(decoding: utf8.suffix(Self.outputTailLimit), as: UTF8.self)
    }
}

private final class IOSSSHErrorHandler: ChannelInboundHandler {
    typealias InboundIn = Any

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        context.close(promise: nil)
    }
}
#endif

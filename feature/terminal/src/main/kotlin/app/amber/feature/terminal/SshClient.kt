package app.amber.feature.terminal

import app.amber.core.settings.ssh.SshCredentials
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private const val PROBE_TIMEOUT_MS = 25_000L
private const val READER_JOIN_TIMEOUT_MS = 2_000L
internal const val UNSUPPORTED_CRYPTO_ERROR =
    "Only Ed25519, ECDSA, and RSA/SHA-2 SSH keys are supported in this build. Use a supported server host key and private key."
private const val COMMAND_INTERRUPTED_ERROR =
    "SSH command interrupted; remote status is unknown."
private const val COMMAND_FAILED_UNKNOWN_ERROR =
    "SSH command failed after request; remote status is unknown."
private const val SUPPORTED_KEX =
    "curve25519-sha256,curve25519-sha256@libssh.org," +
        "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
        "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512," +
        "diffie-hellman-group18-sha512,diffie-hellman-group14-sha256"
private const val SUPPORTED_HOST_KEY_ALGORITHMS =
    "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
        "ssh-ed25519,rsa-sha2-512,rsa-sha2-256"
private const val SUPPORTED_PUBLIC_KEY_ALGORITHMS =
    "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
        "rsa-sha2-512,rsa-sha2-256"

/** A safe, user-facing failure from the managed SSH backend. */
open class SshClientException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The result of one remote exec request. A null exit code means the result is unknown. */
data class SshCommandOutcome(
    val exitCode: Int?,
    val error: String? = null,
)

/**
 * Small JSch adapter used by [TerminalRuntime].
 *
 * JSch connects and authenticates synchronously. Operations therefore run on a dedicated daemon
 * worker, and cancellation closes the owned socket as well as any intermediate ProxyJump channels
 * and JSch sessions. Every hop gets its own JSch instance so identities and host-key state never
 * cross profile boundaries.
 */
class SshClient {
    /** Observe the server host key without sending a password, private key, or keyboard response. */
    suspend fun probe(profile: SshProfile): SshHostKeyProbe =
        probeRoute(listOf(profile)) { SshCredentials() }

    /**
     * Sequentially probes the first untrusted hop (or the final hop if all hops are trusted)
     * in [route] over `direct-tcpip`.
     */
    suspend fun probeRoute(
        route: List<SshProfile>,
        credentialsForHop: (SshProfile) -> SshCredentials,
    ): SshHostKeyProbe {
        return try {
            withTimeout(PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val cancelled = AtomicBoolean(false)
                    val sessions = CopyOnWriteArrayList<Session>()
                    val proxies = CopyOnWriteArrayList<DirectTcpipProxy>()
                    val socketFactoryRef = AtomicReference<OwnedSocketFactory?>(null)
                    val worker = thread(
                        start = false,
                        isDaemon = true,
                        name = "amberagent-ssh-host-key-probe",
                    ) {
                        try {
                            continuation.resumeWith(
                                Result.success(
                                    SshHopChain.probeRouteBlocking(
                                        route = route,
                                        credentialsForHop = credentialsForHop,
                                        socketFactoryRef = socketFactoryRef,
                                        sessionsSink = sessions,
                                        proxiesSink = proxies,
                                        cancelled = cancelled,
                                        totalTimeoutMs = PROBE_TIMEOUT_MS,
                                    ),
                                ),
                            )
                        } catch (error: Throwable) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }
                    continuation.invokeOnCancellation {
                        cancelled.set(true)
                        socketFactoryRef.getAndSet(null)?.close()
                        proxies.reversed().forEach { runCatching { it.close() } }
                        sessions.reversed().forEach { runCatching { it.disconnect() } }
                        worker.interrupt()
                    }
                    if (continuation.isActive) worker.start()
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw SshClientException("SSH host-key probe timed out.")
        }
    }

    /** Create the one-shot connection handle used by one terminal job. */
    fun command(): SshCommandConnection = SshCommandConnection()
}

/** One-shot managed SSH exec handle supporting both direct and multi-hop ProxyJump routes. */
class SshCommandConnection : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val sessions = CopyOnWriteArrayList<Session>()
    private val proxies = CopyOnWriteArrayList<DirectTcpipProxy>()
    private val channelRef = AtomicReference<ChannelExec?>(null)
    private val socketFactoryRef = AtomicReference<OwnedSocketFactory?>(null)
    private val commandRequested = AtomicBoolean(false)

    suspend fun execute(
        profile: SshProfile,
        credentials: () -> SshCredentials,
        command: String,
        timeoutMillis: Long,
        onOutput: (String) -> Unit,
    ): SshCommandOutcome = executeRoute(
        route = listOf(profile),
        credentialsForHop = { credentials() },
        command = command,
        timeoutMillis = timeoutMillis,
        onOutput = onOutput,
    )

    suspend fun executeRoute(
        route: List<SshProfile>,
        credentialsForHop: (SshProfile) -> SshCredentials,
        command: String,
        timeoutMillis: Long,
        onOutput: (String) -> Unit,
    ): SshCommandOutcome {
        check(started.compareAndSet(false, true)) { "SSH command connection can only execute once." }
        if (closed.get()) throw SshClientException("SSH command connection is closed.")

        val timeout = timeoutMillis.coerceAtLeast(1L)
        return try {
            withTimeout(timeout) {
                suspendCancellableCoroutine { continuation ->
                    lateinit var worker: Thread
                    worker = thread(
                        start = false,
                        isDaemon = true,
                        name = "amberagent-ssh-command",
                    ) {
                        try {
                            continuation.resumeWith(
                                Result.success(
                                    executeRouteBlocking(route, credentialsForHop, command, onOutput, timeout),
                                ),
                            )
                        } catch (error: Throwable) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }
                    continuation.invokeOnCancellation {
                        close()
                        worker.interrupt()
                    }
                    if (continuation.isActive) worker.start()
                }
            }
        } catch (error: TimeoutCancellationException) {
            close()
            SshCommandOutcome(
                exitCode = null,
                error = "SSH command timed out after ${timeout}ms; remote status is unknown.",
            )
        } catch (error: CancellationException) {
            close()
            throw error
        }
    }

    /** Close the local SSH transport and any intermediate jump hops in reverse order. */
    override fun close() {
        closed.set(true)
        channelRef.getAndSet(null)?.let { runCatching { it.disconnect() } }
        for (i in sessions.indices.reversed()) {
            runCatching { sessions[i].disconnect() }
            if (i - 1 in proxies.indices) {
                runCatching { proxies[i - 1].close() }
            }
        }
        proxies.forEach { runCatching { it.close() } }
        sessions.clear()
        proxies.clear()
        socketFactoryRef.getAndSet(null)?.close()
    }

    private fun executeRouteBlocking(
        route: List<SshProfile>,
        credentialsForHop: (SshProfile) -> SshCredentials,
        command: String,
        onOutput: (String) -> Unit,
        timeoutMillis: Long,
    ): SshCommandOutcome {
        val deadline = System.nanoTime() +
            timeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L

        try {
            val connected = SshHopChain.connectRouteBlocking(
                route = route,
                credentialsForHop = credentialsForHop,
                deadlineNanos = deadline,
                closed = closed,
                socketFactoryRef = socketFactoryRef,
                sessionsSink = sessions,
                proxiesSink = proxies,
            )
            if (remainingMillis(deadline) <= 0L || closed.get()) return interruptedOutcome()

            val session = connected.targetSession
            val secrets = connected.allSecrets
            val channel = try {
                session.openChannel("exec") as ChannelExec
            } catch (error: Exception) {
                if (closed.get()) return interruptedOutcome()
                throw safeConnectionFailure(error)
            }
            if (!installChannel(channel)) {
                channel.disconnect()
                return interruptedOutcome()
            }

            val stdout = channel.getInputStream()
            val stderr = channel.getExtInputStream()
            channel.setCommand(command.toByteArray(StandardCharsets.UTF_8))
            val channelTimeout = remainingMillis(deadline)
            if (channelTimeout <= 0L || closed.get()) return interruptedOutcome()
            commandRequested.set(true)
            try {
                channel.connect(jschTimeout(channelTimeout))
            } catch (error: Exception) {
                if (closed.get()) return interruptedOutcome()
                return commandFailedUnknownOutcome()
            } catch (error: LinkageError) {
                if (closed.get()) return interruptedOutcome()
                return commandFailedUnknownOutcome()
            }

            val readers = listOf(
                startReader(stdout, secrets, onOutput, "stdout"),
                startReader(stderr, secrets, onOutput, "stderr"),
            )
            try {
                while (!channel.isClosed) {
                    if (closed.get()) return interruptedOutcome()
                    if (remainingMillis(deadline) <= 0L) {
                        return SshCommandOutcome(
                            exitCode = null,
                            error = "SSH command timed out; remote status is unknown.",
                        )
                    }
                    try {
                        Thread.sleep(20L)
                    } catch (_: InterruptedException) {
                        if (closed.get()) return interruptedOutcome()
                        Thread.currentThread().interrupt()
                        return commandFailedUnknownOutcome()
                    }
                }
            } finally {
                readers.forEach { it.join(READER_JOIN_TIMEOUT_MS) }
            }

            if (closed.get()) return interruptedOutcome()
            val exitCode = channel.exitStatus.takeIf { it >= 0 }
            return SshCommandOutcome(
                exitCode = exitCode,
                error = if (exitCode == null) {
                    "Remote command ended without an exit status; remote result is unknown."
                } else {
                    null
                },
            )
        } catch (error: SshClientException) {
            throw error
        } catch (error: CancellationException) {
            if (closed.get()) return interruptedOutcome()
            throw error
        } catch (error: Exception) {
            if (closed.get()) return interruptedOutcome()
            if (commandRequested.get()) return commandFailedUnknownOutcome()
            throw safeConnectionFailure(error)
        } catch (error: LinkageError) {
            if (closed.get()) return interruptedOutcome()
            if (commandRequested.get()) return commandFailedUnknownOutcome()
            throw SshClientException("SSH private key is invalid or unsupported.", error)
        } finally {
            close()
        }
    }

    private fun installChannel(channel: ChannelExec): Boolean {
        if (closed.get()) return false
        channelRef.set(channel)
        if (closed.get()) {
            if (channelRef.compareAndSet(channel, null)) channel.disconnect()
            return false
        }
        return true
    }

    private fun startReader(
        stream: InputStream,
        secrets: List<String>,
        onOutput: (String) -> Unit,
        streamName: String,
    ): Thread = thread(
        start = true,
        isDaemon = true,
        name = "amberagent-ssh-$streamName-output",
    ) {
        val redactor = StreamingSecretRedactor(secrets)
        try {
            InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(8 * 1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    redactor.append(String(buffer, 0, count))
                        .takeIf { it.isNotEmpty() }
                        ?.let { emitOutput(onOutput, it) }
                }
            }
        } catch (_: Exception) {
            // Closing a stream while cancellation races with a reader is expected.
        } finally {
            redactor.flush().takeIf { it.isNotEmpty() }?.let { emitOutput(onOutput, it) }
        }
    }

    private fun emitOutput(onOutput: (String) -> Unit, output: String) {
        runCatching { onOutput(output) }
    }

    private fun interruptedOutcome(): SshCommandOutcome = SshCommandOutcome(
        exitCode = null,
        error = COMMAND_INTERRUPTED_ERROR,
    )

    private fun commandFailedUnknownOutcome(): SshCommandOutcome = SshCommandOutcome(
        exitCode = null,
        error = COMMAND_FAILED_UNKNOWN_ERROR,
    )
}

internal fun configuredSession(
    jsch: JSch,
    profile: SshProfile,
    preferredAuthentications: String,
): Session {
    SshKeyPairs.configureBouncyCastleEdDsa()
    return jsch.getSession(profile.username, profile.host, profile.port).apply {
        setConfig("StrictHostKeyChecking", "yes")
        setConfig("PreferredAuthentications", preferredAuthentications)
        setConfig("kex", SUPPORTED_KEX)
        setConfig("server_host_key", SUPPORTED_HOST_KEY_ALGORITHMS)
        setConfig("PubkeyAcceptedAlgorithms", SUPPORTED_PUBLIC_KEY_ALGORITHMS)
        setConfig("CheckSignatures", "ssh-ed25519,ssh-ed448")
        setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        setConfig("keypairgen_fromprivate.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        setConfig("curve25519-sha256", "com.jcraft.jsch.DH25519")
        setConfig("curve25519-sha256@libssh.org", "com.jcraft.jsch.DH25519")
    }
}

internal fun jschTimeout(timeoutMillis: Long): Int =
    timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

internal fun remainingMillis(deadlineNanos: Long): Long {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) return 0L
    return (remainingNanos / 1_000_000L).coerceAtLeast(1L)
}

internal fun safeConnectionFailure(error: Throwable?): SshClientException {
    if (error is SshClientException) return error
    if (error != null && looksLikeUnsupportedCrypto(error)) {
        return SshClientException(UNSUPPORTED_CRYPTO_ERROR, error)
    }
    return SshClientException("SSH connection failed.", error)
}

internal fun looksLikeUnsupportedCrypto(error: Throwable): Boolean {
    val text = error.message.orEmpty()
    return error is LinkageError ||
        text.contains("server_host_key", ignoreCase = true) ||
        text.contains("algorithm negotiation", ignoreCase = true) ||
        text.contains("ed448", ignoreCase = true) ||
        text.contains("bouncy castle", ignoreCase = true)
}

/**
 * Socket ownership is kept outside JSch so close() can interrupt a blocking TCP connect too.
 * JSch's SocketFactory contract requires createSocket() to return a connected socket.
 */
internal class OwnedSocketFactory(
    private val closed: AtomicBoolean,
    private val connectTimeoutMillis: Long,
) : SocketFactory {
    private val socketRef = AtomicReference<Socket?>(null)

    override fun createSocket(host: String, port: Int): Socket {
        if (closed.get()) throw IOException("SSH connection is closed")
        val socket = Socket()
        socketRef.set(socket)
        if (closed.get()) {
            if (socketRef.compareAndSet(socket, null)) runCatching { socket.close() }
            throw IOException("SSH connection is closed")
        }
        try {
            socket.connect(InetSocketAddress(host, port), jschTimeout(connectTimeoutMillis))
            if (closed.get()) throw IOException("SSH connection is closed")
            return socket
        } catch (error: IOException) {
            socketRef.compareAndSet(socket, null)
            runCatching { socket.close() }
            throw error
        }
    }

    override fun getInputStream(socket: Socket): InputStream {
        if (closed.get()) throw IOException("SSH connection is closed")
        return socket.getInputStream()
    }

    override fun getOutputStream(socket: Socket): OutputStream {
        if (closed.get()) throw IOException("SSH connection is closed")
        return socket.getOutputStream()
    }

    fun close() {
        socketRef.getAndSet(null)?.let { runCatching { it.close() } }
    }
}

internal data class ObservedHostKey(
    val bytes: ByteArray,
    val algorithm: String,
)

internal class ProbeHostKeyRepository : HostKeyRepository {
    @Volatile
    var key: ObservedHostKey? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val hostKey = runCatching { HostKey(host, key.copyOf()) }.getOrNull()
            ?: return HostKeyRepository.NOT_INCLUDED
        this.key = ObservedHostKey(
            bytes = key.copyOf(),
            algorithm = hostKey.getType(),
        )
        return HostKeyRepository.NOT_INCLUDED
    }

    override fun add(hostkey: HostKey, ui: UserInfo) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String? = null

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

/** A per-command repository that compares the raw SSH host-key blob to one pinned SHA-256. */
internal class PinnedHostKeyRepository(
    private val acceptedFingerprint: String,
) : HostKeyRepository {
    @Volatile
    var result: Int? = null
        private set

    @Volatile
    var observedFingerprint: String? = null
        private set

    override fun check(host: String, key: ByteArray): Int {
        val observed = sshSha256Fingerprint(key)
        observedFingerprint = observed
        val matches = constantTimeEquals(acceptedFingerprint, observed)
        result = if (matches) HostKeyRepository.OK else HostKeyRepository.CHANGED
        return result!!
    }

    override fun add(hostkey: HostKey, ui: UserInfo) = Unit

    override fun remove(host: String?, type: String?) = Unit

    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit

    override fun getKnownHostsRepositoryID(): String? = null

    override fun getHostKey(): Array<HostKey> = emptyArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

internal fun sshSha256Fingerprint(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            append(((byte.toInt() ushr 4) and 0x0f).toString(16))
            append((byte.toInt() and 0x0f).toString(16))
        }
    }
}

private fun constantTimeEquals(left: String, right: String): Boolean {
    if (left.length != right.length) return false
    var difference = 0
    for (index in left.indices) difference = difference or (left[index].code xor right[index].code)
    return difference == 0
}

/** Bounded streaming replacement that catches secrets split across adjacent reader chunks. */
internal class StreamingSecretRedactor(secrets: List<String>) {
    private val secrets = secrets
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
    private val maxSecretLength = secrets.maxOfOrNull { it.length } ?: 0
    private var pending = ""

    fun append(chunk: String): String {
        if (chunk.isEmpty()) return ""
        if (maxSecretLength == 0) return chunk

        val source = pending + chunk
        val output = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val match = secrets.firstOrNull { source.startsWith(it, index) }
            if (match != null) {
                output.append(REDACTED)
                index += match.length
            } else {
                val remaining = source.length - index
                if (secrets.any { it.length > remaining && it.regionMatches(0, source, index, remaining) }) {
                    break
                }
                output.append(source[index])
                index++
            }
        }
        pending = source.substring(index)
        return output.toString()
    }

    fun flush(): String {
        // append emits every complete value; only a partial secret prefix can remain.
        val output = if (pending.isEmpty()) "" else REDACTED
        pending = ""
        return output
    }

    private companion object {
        const val REDACTED = "[REDACTED]"
    }
}

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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

private const val PROBE_CONNECT_TIMEOUT_MS = 15_000L
private const val PROBE_TIMEOUT_MS = 20_000L
private const val READER_JOIN_TIMEOUT_MS = 2_000L
private const val UNSUPPORTED_CRYPTO_ERROR =
    "Only RSA/SHA-2 and ECDSA SSH keys are supported in this build. Use a supported server host key and private key."
private const val COMMAND_INTERRUPTED_ERROR =
    "SSH command interrupted; remote status is unknown."
private const val COMMAND_FAILED_UNKNOWN_ERROR =
    "SSH command failed after request; remote status is unknown."
private const val SUPPORTED_KEX =
    "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
        "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512," +
        "diffie-hellman-group18-sha512,diffie-hellman-group14-sha256"
private const val SUPPORTED_HOST_KEY_ALGORITHMS =
    "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
        "rsa-sha2-512,rsa-sha2-256"
private const val SUPPORTED_PUBLIC_KEY_ALGORITHMS =
    "ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521," +
        "rsa-sha2-512,rsa-sha2-256"

/** A safe, user-facing failure from the managed SSH backend. */
class SshClientException(message: String) : Exception(message)

/** The result of one remote exec request. A null exit code means the result is unknown. */
data class SshCommandOutcome(
    val exitCode: Int?,
    val error: String? = null,
)

/**
 * Small JSch adapter used by [TerminalRuntime].
 *
 * JSch connects and authenticates synchronously. Operations therefore run on a dedicated daemon
 * worker, and cancellation closes the owned socket as well as the JSch session. Every command gets
 * a new JSch instance so identities and host-key state never cross profile boundaries.
 */
class SshClient {
    /** Observe the server host key without sending a password, private key, or keyboard response. */
    suspend fun probe(profile: SshProfile): SshHostKeyProbe {
        return try {
            withTimeout(PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val cancelled = AtomicBoolean(false)
                    val sessionRef = AtomicReference<Session?>(null)
                    val socketFactoryRef = AtomicReference<OwnedSocketFactory?>(null)
                    val worker = thread(
                        start = false,
                        isDaemon = true,
                        name = "amberagent-ssh-host-key-probe",
                    ) {
                        try {
                            continuation.resumeWith(
                                Result.success(
                                    probeBlocking(profile, sessionRef, socketFactoryRef, cancelled)
                                )
                            )
                        } catch (error: Throwable) {
                            continuation.resumeWith(Result.failure(error))
                        }
                    }
                    continuation.invokeOnCancellation {
                        cancelled.set(true)
                        socketFactoryRef.getAndSet(null)?.close()
                        sessionRef.getAndSet(null)?.disconnect()
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

    private fun probeBlocking(
        profile: SshProfile,
        sessionRef: AtomicReference<Session?>,
        socketFactoryRef: AtomicReference<OwnedSocketFactory?>,
        cancelled: AtomicBoolean,
    ): SshHostKeyProbe {
        val jsch = JSch()
        val observed = ProbeHostKeyRepository()
        val session = configuredSession(jsch, profile, preferredAuthentications = "")
        session.setHostKeyRepository(observed)
        val socketFactory = OwnedSocketFactory(cancelled, PROBE_CONNECT_TIMEOUT_MS)
        socketFactoryRef.set(socketFactory)
        session.setSocketFactory(socketFactory)

        try {
            if (!installSession(sessionRef, session, cancelled)) {
                throw CancellationException("SSH host-key probe cancelled")
            }

            var failure: Throwable? = null
            try {
                // An empty auth list still performs KEX and host-key verification, but never sends
                // a password, private key, or keyboard-interactive response.
                session.connect(jschTimeout(PROBE_CONNECT_TIMEOUT_MS))
            } catch (error: Exception) {
                failure = error
            } catch (error: LinkageError) {
                failure = error
            }

            if (cancelled.get()) throw CancellationException("SSH host-key probe cancelled")
            val key = observed.key ?: throw safeConnectionFailure(failure)
            return SshHostKeyProbe(
                profile = profile,
                fingerprint = sshSha256Fingerprint(key.bytes),
                algorithm = key.algorithm,
            )
        } finally {
            socketFactoryRef.compareAndSet(socketFactory, null)
            socketFactory.close()
            sessionRef.compareAndSet(session, null)
            session.disconnect()
        }
    }
}

/** One-shot managed SSH exec handle. */
class SshCommandConnection : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val sessionRef = AtomicReference<Session?>(null)
    private val channelRef = AtomicReference<ChannelExec?>(null)
    private val socketFactoryRef = AtomicReference<OwnedSocketFactory?>(null)
    private val commandRequested = AtomicBoolean(false)

    suspend fun execute(
        profile: SshProfile,
        credentials: () -> SshCredentials,
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
                                    executeBlocking(profile, credentials, command, onOutput, timeout)
                                )
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

    /** Close the local SSH transport; this cannot prove that a remote process stopped. */
    override fun close() {
        closed.set(true)
        socketFactoryRef.getAndSet(null)?.close()
        channelRef.getAndSet(null)?.disconnect()
        sessionRef.getAndSet(null)?.disconnect()
    }

    private fun executeBlocking(
        profile: SshProfile,
        credentialsProvider: () -> SshCredentials,
        command: String,
        onOutput: (String) -> Unit,
        timeoutMillis: Long,
    ): SshCommandOutcome {
        val acceptedFingerprint = profile.acceptedHostKeyFingerprint
            ?.let { runCatching { SshTrustPolicy.normalizeFingerprint(it) }.getOrNull() }
            ?: throw SshClientException(
                "SSH host key is not trusted. Probe and confirm this server before running a command.",
            )
        val deadline = System.nanoTime() +
            timeoutMillis.coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L

        try {
            val jsch = JSch()
            val session = configuredSession(
                jsch = jsch,
                profile = profile,
                preferredAuthentications = when (profile.authMethod) {
                    SshAuthMethod.PASSWORD -> "password"
                    SshAuthMethod.PRIVATE_KEY -> "publickey"
                },
            )
            val repository = PinnedHostKeyRepository(acceptedFingerprint)
            session.setHostKeyRepository(repository)
            val socketFactory = OwnedSocketFactory(closed, remainingMillis(deadline))
            socketFactoryRef.set(socketFactory)
            session.setSocketFactory(socketFactory)
            if (!installSession(sessionRef, session, closed)) return interruptedOutcome()

            if (remainingMillis(deadline) <= 0L || closed.get()) return interruptedOutcome()
            val credentialSnapshot = try {
                // JSch needs credentials before Session.connect(), but this happens on a session
                // whose host-key repository is already pinned. No credential is sent before JSch's
                // pre-auth host-key check.
                credentialsProvider()
            } catch (_: Exception) {
                throw SshClientException("SSH credentials are unavailable.")
            }
            val secrets = listOfNotNull(
                credentialSnapshot.password,
                credentialSnapshot.privateKey,
                credentialSnapshot.passphrase,
            ).filter { it.isNotEmpty() }

            when (profile.authMethod) {
                SshAuthMethod.PASSWORD -> {
                    val password = credentialSnapshot.password?.takeIf { it.isNotEmpty() }
                        ?: throw SshClientException("SSH password is not configured for this profile.")
                    val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
                    try {
                        // JSch 2.28.7 copies the byte array into Session and clears its own copy
                        // after connect; clear this caller-owned copy as soon as it is set.
                        session.setPassword(passwordBytes)
                    } finally {
                        passwordBytes.fill(0)
                    }
                }

                SshAuthMethod.PRIVATE_KEY -> {
                    val privateKey = credentialSnapshot.privateKey?.takeIf { it.isNotEmpty() }
                        ?: throw SshClientException("SSH private key is not configured for this profile.")
                    val privateKeyBytes = privateKey.toByteArray(StandardCharsets.UTF_8)
                    val passphraseBytes = credentialSnapshot.passphrase
                        ?.toByteArray(StandardCharsets.UTF_8)
                    try {
                        // Use the byte-array overload; the String passphrase overload is deprecated.
                        jsch.addIdentity(profile.id, privateKeyBytes, null, passphraseBytes)
                    } catch (error: Exception) {
                        throw safeCredentialFailure(error)
                    } catch (error: LinkageError) {
                        throw safeCredentialFailure(error)
                    } finally {
                        privateKeyBytes.fill(0)
                        passphraseBytes?.fill(0)
                    }
                }
            }

            val connectTimeout = remainingMillis(deadline)
            if (connectTimeout <= 0L || closed.get()) return interruptedOutcome()
            try {
                session.connect(jschTimeout(connectTimeout))
            } catch (error: Exception) {
                if (closed.get()) return interruptedOutcome()
                if (repository.result == HostKeyRepository.CHANGED) {
                    throw SshClientException("SSH host key changed; authentication was blocked.")
                }
                throw safeAuthenticationFailure(error)
            } catch (error: LinkageError) {
                if (closed.get()) return interruptedOutcome()
                throw safeCredentialFailure(error)
            }
            if (closed.get()) return interruptedOutcome()
            if (repository.result != HostKeyRepository.OK) {
                throw SshClientException("SSH host key changed; authentication was blocked.")
            }

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
                // Set this flag before the call: the command request may have reached the server
                // even if the channel acknowledgement is lost locally.
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
            throw error
        } catch (error: Exception) {
            if (closed.get()) return interruptedOutcome()
            if (commandRequested.get()) return commandFailedUnknownOutcome()
            throw safeConnectionFailure(error)
        } catch (error: LinkageError) {
            if (closed.get()) return interruptedOutcome()
            if (commandRequested.get()) return commandFailedUnknownOutcome()
            throw safeCredentialFailure(error)
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

    private fun safeCredentialFailure(error: Throwable): SshClientException =
        if (looksLikeUnsupportedCrypto(error)) {
            SshClientException(UNSUPPORTED_CRYPTO_ERROR)
        } else {
            SshClientException("SSH private key is invalid or unsupported.")
        }

    private fun safeAuthenticationFailure(error: Throwable): SshClientException =
        if (looksLikeUnsupportedCrypto(error)) {
            SshClientException(UNSUPPORTED_CRYPTO_ERROR)
        } else {
            SshClientException("SSH authentication failed.")
        }
}

private fun configuredSession(
    jsch: JSch,
    profile: SshProfile,
    preferredAuthentications: String,
): Session = jsch.getSession(profile.username, profile.host, profile.port).apply {
    setConfig("StrictHostKeyChecking", "yes")
    setConfig("PreferredAuthentications", preferredAuthentications)
    // The Android build does not bundle Bouncy Castle. Stay on the JSch Java-8-compatible
    // algorithms and surface a safe error for Ed25519/Ed448-only servers or private keys.
    setConfig("kex", SUPPORTED_KEX)
    setConfig("server_host_key", SUPPORTED_HOST_KEY_ALGORITHMS)
    setConfig("PubkeyAcceptedAlgorithms", SUPPORTED_PUBLIC_KEY_ALGORITHMS)
    setConfig("CheckSignatures", "ssh-ed25519,ssh-ed448")
}

private fun jschTimeout(timeoutMillis: Long): Int =
    timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

private fun remainingMillis(deadlineNanos: Long): Long {
    val remainingNanos = deadlineNanos - System.nanoTime()
    if (remainingNanos <= 0L) return 0L
    return (remainingNanos / 1_000_000L).coerceAtLeast(1L)
}

private fun installSession(
    target: AtomicReference<Session?>,
    session: Session,
    closedState: AtomicBoolean,
): Boolean {
    if (closedState.get()) return false
    target.set(session)
    if (closedState.get()) {
        if (target.compareAndSet(session, null)) session.disconnect()
        return false
    }
    return true
}

private fun safeConnectionFailure(error: Throwable?): SshClientException {
    if (error != null && looksLikeUnsupportedCrypto(error)) {
        return SshClientException(UNSUPPORTED_CRYPTO_ERROR)
    }
    return SshClientException("SSH connection failed.")
}

private fun looksLikeUnsupportedCrypto(error: Throwable): Boolean {
    val text = error.message.orEmpty()
    return error is LinkageError ||
        text.contains("server_host_key", ignoreCase = true) ||
        text.contains("algorithm negotiation", ignoreCase = true) ||
        text.contains("ed25519", ignoreCase = true) ||
        text.contains("ed448", ignoreCase = true) ||
        text.contains("bouncy castle", ignoreCase = true)
}

/**
 * Socket ownership is kept outside JSch so close() can interrupt a blocking TCP connect too.
 * JSch's SocketFactory contract requires createSocket() to return a connected socket.
 */
private class OwnedSocketFactory(
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

private data class ObservedHostKey(
    val bytes: ByteArray,
    val algorithm: String,
)

private class ProbeHostKeyRepository : HostKeyRepository {
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

    override fun check(host: String, key: ByteArray): Int {
        val observed = sshSha256Fingerprint(key)
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

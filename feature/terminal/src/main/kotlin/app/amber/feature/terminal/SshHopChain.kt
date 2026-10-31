package app.amber.feature.terminal

import app.amber.core.settings.ssh.SshCredentials
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

/**
 * Structured multi-hop SSH failure that carries the 1-based [hopIndex], total [hopCount],
 * offending [profile], and optional expected/presented fingerprints while remaining a
 * subtype of [SshClientException] for existing callers.
 */
class SshHopException(
    val status: String,
    val hopIndex: Int,
    val hopCount: Int,
    val profile: SshProfile,
    message: String,
    val expectedFingerprint: String? = null,
    val presentedFingerprint: String? = null,
    cause: Throwable? = null,
) : SshClientException(message, cause) {
    val code: String
        get() = status
    val hopProfileId: String
        get() = profile.id
}

internal data class ConnectedSshRoute(
    val targetSession: Session,
    val sessions: List<Session>,
    val proxies: List<DirectTcpipProxy>,
    val allSecrets: List<String>,
) {
    override fun toString(): String = "ConnectedSshRoute(sessions=${sessions.size}, secrets=<redacted>)"

    fun closeAll() {
        for (i in sessions.indices.reversed()) {
            runCatching { sessions[i].disconnect() }
            if (i - 1 in proxies.indices) {
                runCatching { proxies[i - 1].close() }
            }
        }
        proxies.forEach { runCatching { it.close() } }
    }
}

internal object SshHopChain {
    private const val HOP_CONNECT_TIMEOUT_MS = 10_000L

    /**
     * Probes the first untrusted hop (or the target hop if all prior hops are trusted)
     * in [route]. Any trusted prefix hops `0 until targetIdx` are connected and
     * authenticated over `direct-tcpip`, while the probed hop is contacted with
     * `PreferredAuthentications=""` so its credentials are never transmitted.
     */
    fun probeRouteBlocking(
        route: List<SshProfile>,
        credentialsForHop: (SshProfile) -> SshCredentials,
        socketFactoryRef: AtomicReference<OwnedSocketFactory?>,
        sessionsSink: CopyOnWriteArrayList<Session>,
        proxiesSink: CopyOnWriteArrayList<DirectTcpipProxy>,
        cancelled: AtomicBoolean,
        totalTimeoutMs: Long,
    ): SshHostKeyProbe {
        require(route.isNotEmpty()) { "SSH route must not be empty" }
        val hopCount = route.size
        val firstUntrustedIndex = route.indexOfFirst { hop ->
            SshTrustPolicy.evaluate(hop, hop.acceptedHostKeyFingerprint) !is SshHostTrust.Trusted
        }
        val probeIndex = if (firstUntrustedIndex >= 0) firstUntrustedIndex else route.lastIndex
        val deadline = System.nanoTime() + totalTimeoutMs * 1_000_000L

        try {
            var parentSession: Session? = null
            for (idx in 0 until probeIndex) {
                if (cancelled.get()) throw CancellationException("SSH host-key probe cancelled")
                val hop = route[idx]
                val hopNumber = idx + 1
                val hopTimeout = remainingMillis(deadline).coerceAtMost(HOP_CONNECT_TIMEOUT_MS)
                if (hopTimeout <= 0L) throw SshClientException("SSH host-key probe timed out.")

                val session = connectAuthenticatedHop(
                    hop = hop,
                    hopNumber = hopNumber,
                    hopCount = hopCount,
                    parentSession = parentSession,
                    parentProfile = if (idx > 0) route[idx - 1] else null,
                    credentialsProvider = { credentialsForHop(hop) },
                    connectTimeoutMs = hopTimeout,
                    closed = cancelled,
                    socketFactoryRef = socketFactoryRef,
                    sessionsSink = sessionsSink,
                    proxiesSink = proxiesSink,
                ).first
                parentSession = session
            }

            if (cancelled.get()) throw CancellationException("SSH host-key probe cancelled")
            val targetHop = route[probeIndex]
            val targetHopNumber = probeIndex + 1
            val probeTimeout = remainingMillis(deadline).coerceAtMost(HOP_CONNECT_TIMEOUT_MS)
            if (probeTimeout <= 0L) throw SshClientException("SSH host-key probe timed out.")

            val jsch = JSch()
            val observed = ProbeHostKeyRepository()
            val probeSession = configuredSession(jsch, targetHop, preferredAuthentications = "")
            probeSession.setHostKeyRepository(observed)

            if (parentSession == null) {
                val socketFactory = OwnedSocketFactory(cancelled, probeTimeout)
                socketFactoryRef.set(socketFactory)
                probeSession.setSocketFactory(socketFactory)
            } else {
                val proxy = DirectTcpipProxy(
                    parentSession = parentSession,
                    parentProfile = route[probeIndex - 1],
                    hopIndex = targetHopNumber,
                    hopCount = hopCount,
                )
                proxiesSink.add(proxy)
                probeSession.setProxy(proxy)
            }
            sessionsSink.add(probeSession)
            if (cancelled.get()) throw CancellationException("SSH host-key probe cancelled")

            var failure: Throwable? = null
            try {
                probeSession.connect(jschTimeout(probeTimeout))
            } catch (error: SshHopException) {
                throw error
            } catch (error: Exception) {
                failure = error
            } catch (error: LinkageError) {
                failure = error
            }

            if (cancelled.get()) throw CancellationException("SSH host-key probe cancelled")
            if (failure?.cause is SshHopException) {
                throw failure.cause as SshHopException
            }
            val key = observed.key ?: throw safeConnectionFailure(failure)
            return SshHostKeyProbe(
                profile = targetHop,
                fingerprint = sshSha256Fingerprint(key.bytes),
                algorithm = key.algorithm,
                hopIndex = targetHopNumber,
                hopCount = hopCount,
                route = route,
            )
        } finally {
            for (i in sessionsSink.indices.reversed()) {
                runCatching { sessionsSink[i].disconnect() }
            }
            for (i in proxiesSink.indices.reversed()) {
                runCatching { proxiesSink[i].close() }
            }
            socketFactoryRef.getAndSet(null)?.close()
        }
    }

    /**
     * Connects and authenticates every hop in [route] sequentially, enforcing
     * per-hop pinned host key verification before authentication on each hop.
     */
    fun connectRouteBlocking(
        route: List<SshProfile>,
        credentialsForHop: (SshProfile) -> SshCredentials,
        deadlineNanos: Long,
        closed: AtomicBoolean,
        socketFactoryRef: AtomicReference<OwnedSocketFactory?>,
        sessionsSink: CopyOnWriteArrayList<Session>,
        proxiesSink: CopyOnWriteArrayList<DirectTcpipProxy>,
    ): ConnectedSshRoute {
        require(route.isNotEmpty()) { "SSH route must not be empty" }
        val hopCount = route.size

        // Pre-flight check: every hop in the route must have a valid accepted fingerprint
        // bound to its current (host, port, jumpProfileId) before any network packet is sent.
        for ((idx, hop) in route.withIndex()) {
            val hopNumber = idx + 1
            val trust = SshTrustPolicy.evaluate(hop, hop.acceptedHostKeyFingerprint)
            if (trust !is SshHostTrust.Trusted) {
                throw SshHopException(
                    status = "host_key_untrusted",
                    hopIndex = hopNumber,
                    hopCount = hopCount,
                    profile = hop,
                    message = if (hopCount == 1) {
                        "SSH host key is not trusted. Probe and confirm this server before running a command."
                    } else {
                        "SSH host key is not trusted for hop $hopNumber/$hopCount ('${hop.name}'). Probe and confirm this server before running a command."
                    },
                )
            }
        }

        val allSecrets = mutableListOf<String>()
        var parentSession: Session? = null
        for ((idx, hop) in route.withIndex()) {
            val hopNumber = idx + 1
            val remaining = remainingMillis(deadlineNanos)
            if (remaining <= 0L || closed.get()) {
                throw CancellationException("SSH connection interrupted before hop $hopNumber")
            }
            val hopTimeout = if (hopCount > 1) {
                remaining.coerceAtMost(HOP_CONNECT_TIMEOUT_MS)
            } else {
                remaining
            }
            val (session, hopSecrets) = connectAuthenticatedHop(
                hop = hop,
                hopNumber = hopNumber,
                hopCount = hopCount,
                parentSession = parentSession,
                parentProfile = if (idx > 0) route[idx - 1] else null,
                credentialsProvider = { credentialsForHop(hop) },
                connectTimeoutMs = hopTimeout,
                closed = closed,
                socketFactoryRef = socketFactoryRef,
                sessionsSink = sessionsSink,
                proxiesSink = proxiesSink,
            )
            allSecrets.addAll(hopSecrets)
            parentSession = session
        }

        return ConnectedSshRoute(
            targetSession = parentSession!!,
            sessions = sessionsSink.toList(),
            proxies = proxiesSink.toList(),
            allSecrets = allSecrets,
        )
    }

    private fun connectAuthenticatedHop(
        hop: SshProfile,
        hopNumber: Int,
        hopCount: Int,
        parentSession: Session?,
        parentProfile: SshProfile?,
        credentialsProvider: () -> SshCredentials,
        connectTimeoutMs: Long,
        closed: AtomicBoolean,
        socketFactoryRef: AtomicReference<OwnedSocketFactory?>,
        sessionsSink: CopyOnWriteArrayList<Session>,
        proxiesSink: CopyOnWriteArrayList<DirectTcpipProxy>,
    ): Pair<Session, List<String>> {
        val acceptedFingerprint = hop.acceptedHostKeyFingerprint
            ?.let { runCatching { SshTrustPolicy.normalizeFingerprint(it) }.getOrNull() }
            ?: throw SshHopException(
                status = "host_key_untrusted",
                hopIndex = hopNumber,
                hopCount = hopCount,
                profile = hop,
                message = "SSH host key is not trusted. Probe and confirm this server before running a command.",
            )

        val jsch = JSch()
        val session = configuredSession(
            jsch = jsch,
            profile = hop,
            preferredAuthentications = when (hop.authMethod) {
                SshAuthMethod.PASSWORD -> "password"
                SshAuthMethod.PRIVATE_KEY -> "publickey"
            },
        )
        val repository = PinnedHostKeyRepository(acceptedFingerprint)
        session.setHostKeyRepository(repository)

        if (parentSession == null) {
            val socketFactory = OwnedSocketFactory(closed, connectTimeoutMs)
            socketFactoryRef.set(socketFactory)
            session.setSocketFactory(socketFactory)
        } else {
            val proxy = DirectTcpipProxy(
                parentSession = parentSession,
                parentProfile = parentProfile ?: hop,
                hopIndex = hopNumber,
                hopCount = hopCount,
            )
            proxiesSink.add(proxy)
            session.setProxy(proxy)
        }
        sessionsSink.add(session)
        if (closed.get()) throw CancellationException("SSH connection closed")

        val credentialSnapshot = try {
            credentialsProvider()
        } catch (_: Exception) {
            throw SshHopException(
                status = "hop_auth_failed",
                hopIndex = hopNumber,
                hopCount = hopCount,
                profile = hop,
                message = "SSH credentials are unavailable.",
            )
        }
        val secrets = listOfNotNull(
            credentialSnapshot.password,
            credentialSnapshot.privateKey,
            credentialSnapshot.passphrase,
        ).filter { it.isNotEmpty() }

        when (hop.authMethod) {
            SshAuthMethod.PASSWORD -> {
                val password = credentialSnapshot.password?.takeIf { it.isNotEmpty() }
                    ?: throw SshHopException(
                        status = "hop_auth_failed",
                        hopIndex = hopNumber,
                        hopCount = hopCount,
                        profile = hop,
                        message = "SSH password is not configured for this profile.",
                    )
                val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
                try {
                    session.setPassword(passwordBytes)
                } finally {
                    passwordBytes.fill(0)
                }
            }

            SshAuthMethod.PRIVATE_KEY -> {
                val privateKey = credentialSnapshot.privateKey?.takeIf { it.isNotEmpty() }
                    ?: throw SshHopException(
                        status = "hop_auth_failed",
                        hopIndex = hopNumber,
                        hopCount = hopCount,
                        profile = hop,
                        message = "SSH private key is not configured for this profile.",
                    )
                val privateKeyBytes = privateKey.toByteArray(StandardCharsets.UTF_8)
                val passphraseBytes = credentialSnapshot.passphrase?.toByteArray(StandardCharsets.UTF_8)
                try {
                    jsch.addIdentity(hop.id, privateKeyBytes, null, passphraseBytes)
                } catch (error: Exception) {
                    throw safeHopCredentialFailure(hop, hopNumber, hopCount, error)
                } catch (error: LinkageError) {
                    throw safeHopCredentialFailure(hop, hopNumber, hopCount, error)
                } finally {
                    privateKeyBytes.fill(0)
                    passphraseBytes?.fill(0)
                }
            }
        }

        try {
            session.connect(jschTimeout(connectTimeoutMs))
        } catch (error: SshHopException) {
            throw error
        } catch (error: Exception) {
            if (closed.get()) throw CancellationException("SSH connection closed")
            if (error.cause is SshHopException) {
                throw error.cause as SshHopException
            }
            if (repository.result == HostKeyRepository.CHANGED) {
                throw hostKeyChangedException(hop, hopNumber, hopCount, acceptedFingerprint, repository.observedFingerprint)
            }
            throw safeHopAuthFailure(hop, hopNumber, hopCount, error)
        } catch (error: LinkageError) {
            if (closed.get()) throw CancellationException("SSH connection closed")
            throw safeHopCredentialFailure(hop, hopNumber, hopCount, error)
        }

        if (closed.get()) throw CancellationException("SSH connection closed")
        if (repository.result != HostKeyRepository.OK) {
            throw hostKeyChangedException(hop, hopNumber, hopCount, acceptedFingerprint, repository.observedFingerprint)
        }

        return session to secrets
    }

    private fun hostKeyChangedException(
        hop: SshProfile,
        hopNumber: Int,
        hopCount: Int,
        acceptedHex: String,
        observedHex: String?,
    ): SshHopException = SshHopException(
        status = "host_key_changed",
        hopIndex = hopNumber,
        hopCount = hopCount,
        profile = hop,
        expectedFingerprint = runCatching { SshTrustPolicy.displayFingerprint(acceptedHex) }.getOrDefault(acceptedHex),
        presentedFingerprint = observedHex?.let {
            runCatching { SshTrustPolicy.displayFingerprint(it) }.getOrDefault(it)
        },
        message = if (hopCount == 1) {
            "SSH host key changed; authentication was blocked."
        } else {
            "SSH host key changed on hop $hopNumber/$hopCount ('${hop.name}'); authentication was blocked."
        },
    )

    private fun safeHopCredentialFailure(
        hop: SshProfile,
        hopNumber: Int,
        hopCount: Int,
        error: Throwable,
    ): SshHopException {
        val msg = if (looksLikeUnsupportedCrypto(error)) {
            UNSUPPORTED_CRYPTO_ERROR
        } else {
            "SSH private key is invalid or unsupported."
        }
        return SshHopException(
            status = "hop_auth_failed",
            hopIndex = hopNumber,
            hopCount = hopCount,
            profile = hop,
            message = msg,
            cause = error,
        )
    }

    private fun safeHopAuthFailure(
        hop: SshProfile,
        hopNumber: Int,
        hopCount: Int,
        error: Throwable,
    ): SshHopException {
        val msg = if (looksLikeUnsupportedCrypto(error)) {
            UNSUPPORTED_CRYPTO_ERROR
        } else if (hopCount == 1) {
            "SSH authentication failed."
        } else {
            "SSH authentication failed on hop $hopNumber/$hopCount ('${hop.name}')."
        }
        return SshHopException(
            status = "hop_auth_failed",
            hopIndex = hopNumber,
            hopCount = hopCount,
            profile = hop,
            message = msg,
            cause = error,
        )
    }
}

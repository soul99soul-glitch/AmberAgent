package app.amber.feature.terminal

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/**
 * JSch [Proxy] implementation that tunnels the next hop's SSH transport over an
 * already-authenticated parent [Session] using an SSH `direct-tcpip` channel
 * (`SSH_MSG_CHANNEL_OPEN "direct-tcpip"`), equivalent to `OpenSSH ProxyJump`.
 *
 * Never spawns a shell or runs `nc`/`ssh -W` on the jump host, so jump hosts
 * configured with `ForceCommand /bin/false` or `nologin` work transparently
 * as long as `AllowTcpForwarding` permits the target `host:port`.
 */
internal class DirectTcpipProxy(
    private val parentSession: Session,
    private val parentProfile: SshProfile,
    private val hopIndex: Int,
    private val hopCount: Int,
) : Proxy {
    @Volatile
    private var channel: ChannelDirectTCPIP? = null

    @Volatile
    private var inputStream: InputStream? = null

    @Volatile
    private var outputStream: OutputStream? = null

    override fun connect(
        socketFactory: SocketFactory?,
        host: String,
        port: Int,
        timeout: Int,
    ) {
        if (!parentSession.isConnected) {
            throw SshHopException(
                status = "jump_target_unreachable",
                hopIndex = hopIndex - 1,
                hopCount = hopCount,
                profile = parentProfile,
                message = "Jump host '${parentProfile.name}' session disconnected before opening direct-tcpip to $host:$port.",
            )
        }
        val opened = (parentSession.openChannel("direct-tcpip") as? ChannelDirectTCPIP)
            ?: throw JSchException("Unable to allocate direct-tcpip channel on jump host '${parentProfile.name}'")
        channel = opened
        opened.setHost(host)
        opened.setPort(port)
        opened.setOrgIPAddress("127.0.0.1")
        opened.setOrgPort(0)

        // JSch requires binding the channel streams BEFORE calling connect(),
        // otherwise ChannelDirectTCPIP.connect() creates an internal PassiveInputStream
        // that races or drops the initial SSH banner from the downstream server.
        val boundIn = opened.inputStream
        val boundOut = opened.outputStream
        inputStream = boundIn
        outputStream = boundOut

        try {
            val effectiveTimeout = if (timeout > 0) timeout else DEFAULT_CHANNEL_TIMEOUT_MS
            opened.connect(effectiveTimeout)
        } catch (t: Throwable) {
            val exitStatus = opened.exitStatus
            runCatching { opened.disconnect() }
            if (exitStatus == SSH_OPEN_ADMINISTRATIVELY_PROHIBITED ||
                t.message?.contains("administratively prohibited", ignoreCase = true) == true ||
                t.message?.contains("open failed", ignoreCase = true) == true && exitStatus == 1
            ) {
                throw SshHopException(
                    status = "jump_forward_denied",
                    hopIndex = hopIndex - 1,
                    hopCount = hopCount,
                    profile = parentProfile,
                    message = "Jump host '${parentProfile.name}' denied TCP forwarding to $host:$port (AllowTcpForwarding / PermitOpen).",
                    cause = t,
                )
            }
            throw SshHopException(
                status = "jump_target_unreachable",
                hopIndex = hopIndex - 1,
                hopCount = hopCount,
                profile = parentProfile,
                message = "Jump host '${parentProfile.name}' could not reach target $host:$port via direct-tcpip.",
                cause = t,
            )
        }
    }

    override fun getInputStream(): InputStream? = inputStream

    override fun getOutputStream(): OutputStream? = outputStream

    override fun getSocket(): Socket? = null

    override fun close() {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { channel?.disconnect() }
        inputStream = null
        outputStream = null
        channel = null
    }

    companion object {
        private const val DEFAULT_CHANNEL_TIMEOUT_MS = 10_000
        private const val SSH_OPEN_ADMINISTRATIVELY_PROHIBITED = 1
    }
}

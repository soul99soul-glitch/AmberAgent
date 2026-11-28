package app.amber.core.automation.display.adb

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

/** The only ADB stream this client can open is the installed app's own display helper. */
internal class LocalAdbTlsClient(context: Context) {
    private val app = context.applicationContext
    private val keys = LocalAdbKeyStore(app)

    suspend fun pair(port: Int?, code: String) = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 34) { "adb_requires_android_14" }
        require(code.matches(Regex("[0-9]{6}"))) { "adb_invalid_pairing_code" }
        val endpoint = endpoint(port, "_adb-tls-pairing._tcp")
        openSocket(endpoint).use { raw ->
            startTls(raw).use { tls ->
                val guid = AdbSpake2Pairing.pair(tls, code, keys.adbPublicKey())
                keys.recordPaired(guid)
            }
        }
        Unit
    }

    suspend fun startHelper(token: String, port: Int? = null): Closeable {
        check(Build.VERSION.SDK_INT >= 34) { "adb_requires_android_14" }
        require(token.matches(Regex("[0-9a-f]{64}")))
        // Keep ownership across the dispatcher switch, including cancellation during return.
        var connection: Socket? = null
        try {
            return withContext(Dispatchers.IO) {
                val guid = checkNotNull(keys.pairedGuid) { "adb_not_paired" }
                val endpoint = endpoint(port, "_adb-tls-connect._tcp", guid)
                val raw = openSocket(endpoint)
                connection = raw
                send(raw, CNXN, VERSION, MAX_PAYLOAD, "host::features=shell_v2".toByteArray())
                val upgrade = receive(raw)
                check(upgrade.command == STLS && upgrade.arg0 == TLS_VERSION) { "adb_tls_upgrade_required" }
                send(raw, STLS, TLS_VERSION, 0)
                val tls = startTls(raw)
                connection = tls
                val banner = receive(tls)
                check(banner.command == CNXN && banner.arg0 >= VERSION) { "adb_tls_connection_rejected" }
                val features = banner.data.toString(Charsets.UTF_8).trimEnd('\u0000')
                    .substringAfter("::").split(';').firstOrNull { it.startsWith("features=") }
                    ?.removePrefix("features=")?.split(',').orEmpty()
                check("shell_v2" in features) { "adb_shell_v2_unavailable" }

                // PackageManager supplies the installed APK path. No caller-provided shell arguments.
                val apk = app.applicationInfo.sourceDir.replace("'", "'\\''")
                val service = "shell,v2,raw:CLASSPATH='$apk' exec app_process /system/bin " +
                    "app.amber.core.automation.display.AmberDisplayHelperMain"
                send(tls, OPEN, STREAM, 0, (service + '\u0000').toByteArray())
                val open = receive(tls)
                check(open.command == OKAY && open.arg1 == STREAM && open.arg0 != 0) { "adb_helper_open_rejected" }
                val input = (token + "\n").toByteArray(Charsets.US_ASCII)
                val stdin = littleEndian(5 + input.size).put(0).putInt(input.size).put(input).array()
                send(tls, WRTE, STREAM, open.arg0, stdin)
                // Shell stays attached, with stdin open until the owning bridge releases the socket.
                tls.soTimeout = 0
                Thread({ drainHelper(tls, open.arg0) }, "AmberDisplayAdb").apply { isDaemon = true }.start()
                tls
            }
        } catch (error: Throwable) {
            runCatching { connection?.close() }
            throw error
        }
    }

    private fun startTls(raw: Socket): SSLSocket {
        val socket = keys.sslContext().socketFactory
            .createSocket(raw, raw.inetAddress.hostAddress, raw.port, true) as SSLSocket
        try {
            socket.enabledProtocols = arrayOf("TLSv1.3")
            socket.soTimeout = IO_TIMEOUT_MS
            socket.startHandshake()
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private suspend fun endpoint(port: Int?, type: String, guid: String? = null): InetSocketAddress {
        if (port != null) {
            require(port in 1..65535) { "adb_invalid_port" }
            return InetSocketAddress(InetAddress.getByName("127.0.0.1"), port)
        }
        return discover(type, guid)
    }

    private fun openSocket(endpoint: InetSocketAddress): Socket {
        check(isLocal(endpoint.address)) { "adb_non_local_endpoint" }
        val socket = Socket()
        try {
            socket.soTimeout = IO_TIMEOUT_MS
            socket.tcpNoDelay = true
            socket.connect(endpoint, IO_TIMEOUT_MS)
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    /** All callback registration, resolution and teardown run on the same executor. */
    private suspend fun discover(type: String, guid: String?): InetSocketAddress = withContext(Dispatchers.Main.immediate) {
        val nsd = checkNotNull(app.getSystemService(NsdManager::class.java)) { "adb_nsd_unavailable" }
        val executor = app.mainExecutor
        val result = CompletableDeferred<InetSocketAddress>()
        val resolutions = mutableListOf<NsdManager.ServiceInfoCallback>()
        var stopped = false
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                result.completeExceptionally(IOException("adb_discovery_failed:$errorCode"))
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (stopped || result.isCompleted || (guid != null && serviceInfo.serviceName != guid)) return
                val resolver = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit
                    override fun onServiceLost() = Unit
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        val local = info.hostAddresses.firstOrNull(::isLocal) ?: return
                        if (info.port in 1..65535) result.complete(InetSocketAddress(local, info.port))
                    }
                }
                try {
                    nsd.registerServiceInfoCallback(serviceInfo, executor, resolver)
                    resolutions += resolver
                } catch (e: RuntimeException) {
                    result.completeExceptionally(e)
                }
            }
        }
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, null as Network?, executor, discovery)
        try {
            withTimeoutOrNull(8_000L) { result.await() } ?: throw IOException("adb_local_port_not_found:$type")
        } finally {
            stopped = true
            runCatching { nsd.stopServiceDiscovery(discovery) }
            resolutions.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        }
    }

    private fun isLocal(address: InetAddress): Boolean =
        address.isLoopbackAddress || NetworkInterface.getByInetAddress(address) != null

    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun receive(socket: Socket): Packet {
        val input = DataInputStream(socket.getInputStream())
        val header = littleEndian(24)
        input.readFully(header.array())
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val size = header.int
        header.int // Peers negotiating version 0x01000001 omit the legacy checksum.
        check(header.int == command.inv() && size in 0..MAX_PAYLOAD) { "adb_invalid_header" }
        return Packet(command, arg0, arg1, ByteArray(size).also(input::readFully))
    }

    private fun send(socket: Socket, command: Int, arg0: Int, arg1: Int, data: ByteArray = byteArrayOf()) {
        val header = littleEndian(24).putInt(command).putInt(arg0).putInt(arg1).putInt(data.size)
            .putInt(data.sumOf { it.toInt() and 255 }).putInt(command.inv())
        socket.getOutputStream().apply {
            write(header.array())
            write(data)
            flush()
        }
    }

    private fun drainHelper(socket: SSLSocket, remoteId: Int) {
        try {
            while (!socket.isClosed) {
                val packet = receive(socket)
                if (packet.arg1 != STREAM || packet.arg0 != remoteId) break
                when (packet.command) {
                    WRTE -> send(socket, OKAY, STREAM, remoteId)
                    OKAY -> Unit
                    else -> break
                }
            }
        } catch (_: IOException) {
            // EOF and local shutdown terminate the fixed helper stream.
        } catch (_: IllegalStateException) {
            // Malformed packets are treated just like transport loss.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun littleEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private companion object {
        const val IO_TIMEOUT_MS = 8_000
        const val VERSION = 0x01000001
        const val TLS_VERSION = 0x01000000
        const val MAX_PAYLOAD = 65536
        const val STREAM = 1
        const val CNXN = 0x4e584e43
        const val STLS = 0x534c5453
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
    }
}

package app.amber.core.automation.display.adb

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
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

    fun pairingEndpoints(): Flow<InetSocketAddress?> = localEndpoints(PAIRING_SERVICE, null)

    suspend fun pair(port: Int?, code: String) = pairUsing(code) {
        openSocket(endpoint(port, PAIRING_SERVICE))
    }

    suspend fun pairDiscovered(endpoint: InetSocketAddress?, code: String) = pairUsing(code) {
        val cached = endpoint?.takeIf { it.port > 0 && it.address?.let(::isLocal) == true }
        val connected = try {
            cached?.let(::openSocket)
        } catch (_: IOException) {
            // A system pairing dialog can reopen on a new port while its notification is visible.
            null
        }
        connected ?: openSocket(discover(PAIRING_SERVICE, null))
    }

    private suspend fun pairUsing(code: String, connect: suspend () -> Socket) = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 34) { "adb_requires_android_14" }
        require(code.matches(Regex("[0-9]{6}"))) { "adb_invalid_pairing_code" }
        connect().use { raw ->
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

    private suspend fun discover(type: String, guid: String?): InetSocketAddress =
        withTimeoutOrNull(8_000L) { localEndpoints(type, guid).filterNotNull().first() }
            ?: throw IOException("adb_local_port_not_found:$type")

    /** Callback registration, updates and cleanup are serialized on the main executor. */
    private fun localEndpoints(type: String, guid: String?): Flow<InetSocketAddress?> = callbackFlow {
        check(Build.VERSION.SDK_INT >= 34) { "adb_requires_android_14" }
        val nsd = checkNotNull(app.getSystemService(NsdManager::class.java)) { "adb_nsd_unavailable" }
        val executor = app.mainExecutor
        val resolutions = mutableMapOf<Pair<String, Network?>, NsdManager.ServiceInfoCallback>()
        val endpoints = linkedMapOf<Pair<String, Network?>, InetSocketAddress>()
        var stopped = false
        fun publish() { trySend(endpoints.values.lastOrNull()) }
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("adb_discovery_failed:$errorCode"))
            }
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) {
                if (!stopped) close(IOException("adb_discovery_stopped"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val key = serviceInfo.serviceName to serviceInfo.network
                val resolver = resolutions.remove(key) ?: return
                endpoints.remove(key)
                publish()
                runCatching { nsd.unregisterServiceInfoCallback(resolver) }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val key = serviceInfo.serviceName to serviceInfo.network
                if (stopped || key in resolutions || (guid != null && serviceInfo.serviceName != guid)) return
                val resolver = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        if (resolutions[key] === this) {
                            resolutions.remove(key)
                            endpoints.remove(key)
                            publish()
                        }
                    }
                    override fun onServiceInfoCallbackUnregistered() = Unit
                    override fun onServiceLost() {
                        if (resolutions[key] === this) {
                            endpoints.remove(key)
                            publish()
                        }
                    }
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        if (resolutions[key] !== this) return
                        val local = info.hostAddresses.firstOrNull(::isLocal)
                        if (local != null && info.port in 1..65535) {
                            val endpoint = InetSocketAddress(local, info.port)
                            if (endpoints[key] != endpoint) {
                                endpoints.remove(key)
                                endpoints[key] = endpoint
                            }
                        } else {
                            endpoints.remove(key)
                        }
                        publish()
                    }
                }
                resolutions[key] = resolver
                try {
                    nsd.registerServiceInfoCallback(serviceInfo, executor, resolver)
                } catch (e: RuntimeException) {
                    resolutions.remove(key)
                    close(e)
                }
            }
        }
        publish()
        nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, null as Network?, executor, discovery)
        try {
            awaitClose()
        } finally {
            stopped = true
            runCatching { nsd.stopServiceDiscovery(discovery) }
            resolutions.values.toList().forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            resolutions.clear()
        }
    }.flowOn(Dispatchers.Main.immediate).conflate().distinctUntilChanged()

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
        const val PAIRING_SERVICE = "_adb-tls-pairing._tcp"
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

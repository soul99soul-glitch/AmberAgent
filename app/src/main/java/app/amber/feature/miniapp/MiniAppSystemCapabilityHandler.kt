package app.amber.feature.miniapp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * P4 W10: per-runner native system capability owner, mirroring the iOS
 * `IOSMiniAppDeviceCapabilities` dispatch surface. The protocol layer
 * (MiniAppBridge) owns permission/gate/confirmation/audit; implementations
 * only execute the actual system call and must return honest results.
 */
interface MiniAppSystemCapabilityHandler {
    val supportedMethods: Set<String>
    suspend fun dispatch(method: String, params: JsonObject): JsonElement
}

/**
 * Fixed method/permission registry shared by bridge dispatch and capability
 * discovery. `qrcode.generate` has no per-app permission (iOS parity) but is
 * still gated by the global system-capabilities switch.
 */
object MiniAppSystemCapabilityRegistry {
    const val BRIDGE_VERSION_SYSTEM_CAPABILITIES = "0.3-system-capabilities"
    const val BRIDGE_VERSION_BASE = "0.2"

    val methodPermissions: Map<String, MiniAppPermission> = mapOf(
        "haptics.impact" to MiniAppPermission.Haptics,
        "haptics.notification" to MiniAppPermission.Haptics,
        "haptics.selection" to MiniAppPermission.Haptics,
        "device.getInfo" to MiniAppPermission.Device,
        "device.getBattery" to MiniAppPermission.Device,
        "screen.getBrightness" to MiniAppPermission.Screen,
        "screen.setBrightness" to MiniAppPermission.Screen,
        "screen.setKeepAwake" to MiniAppPermission.Screen,
        "speech.getVoices" to MiniAppPermission.Speech,
        "speech.speak" to MiniAppPermission.Speech,
        "speech.stop" to MiniAppPermission.Speech,
        "speech.pause" to MiniAppPermission.Speech,
        "speech.resume" to MiniAppPermission.Speech,
        "share" to MiniAppPermission.Share,
        "openURL" to MiniAppPermission.OpenURL,
    )

    val allSystemMethods: Set<String> = methodPermissions.keys + "qrcode.generate"

    /** Discovery reports only methods the runner's native owner really supports. */
    fun availableMethods(handler: MiniAppSystemCapabilityHandler?): Set<String> =
        handler?.supportedMethods?.intersect(allSystemMethods) ?: emptySet()

    fun bridgeVersion(handler: MiniAppSystemCapabilityHandler?): String =
        if (availableMethods(handler).containsAll(allSystemMethods)) {
            BRIDGE_VERSION_SYSTEM_CAPABILITIES
        } else {
            BRIDGE_VERSION_BASE
        }
}

/**
 * P4 W11: openURL/share URL validation, shared by the bridge (pre-confirmation
 * preview) and the native owner (pre-ACTION_VIEW). Same rule set as iOS
 * `IOSMiniAppDeviceCapabilities.validatedOpenURL`: only public HTTPS, mailto
 * and tel; reject credentials, control characters, whitespace, local/private
 * hosts and non-public IP literals.
 */
object MiniAppOpenUrlValidator {
    private const val MAX_URL_BYTES = 4_096
    private val TEL_ALLOWED = Regex("""[+*#0-9\-().]+""")

    data class ValidatedUrl(val url: String)

    fun validate(raw: String): ValidatedUrl {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw invalid("url is empty")
        if (trimmed.encodeToByteArray().size > MAX_URL_BYTES) {
            throw invalid("url is too long")
        }
        if (trimmed.contains("\\") || trimmed.any { it.isWhitespace() } ||
            raw.any { it.code < 0x20 || it.code == 0x7f }
        ) {
            throw invalid("url is malformed")
        }
        val schemeSeparator = trimmed.indexOf("://")
        val scheme: String
        val rest: String
        if (schemeSeparator > 0) {
            scheme = trimmed.substring(0, schemeSeparator).lowercase()
            rest = trimmed.substring(schemeSeparator + 3)
        } else {
            val colon = trimmed.indexOf(':')
            if (colon <= 0) throw invalid("url is malformed")
            scheme = trimmed.substring(0, colon).lowercase()
            rest = trimmed.substring(colon + 1)
        }
        if (scheme.isEmpty() || !scheme.all { it.isLetterOrDigit() || it in "+-." }) {
            throw invalid("url is malformed")
        }
        return when (scheme) {
            "https" -> ValidatedUrl("https://" + validateHttps(rest))
            "mailto" -> ValidatedUrl("mailto:" + validateMailto(rest))
            "tel" -> ValidatedUrl("tel:" + validateTel(rest))
            else -> throw MiniAppBridgeException(
                "invalid_url",
                "Only public HTTPS, mailto, and tel URLs are allowed",
            )
        }
    }

    private fun invalid(message: String): MiniAppBridgeException =
        MiniAppBridgeException("invalid_url", message)

    /** Returns canonical HTTPS authority/path; rejects userinfo, local hosts and bad ports. */
    private fun validateHttps(rest: String): String {
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (authorityEnd >= 0) rest.substring(0, authorityEnd) else rest
        if (authority.contains('@')) {
            throw invalid("HTTPS URL may not contain credentials")
        }
        // A colon means an explicit port unless it belongs to an IPv6 literal.
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) throw invalid("url is malformed")
            val suffix = authority.substring(close + 1)
            // After a bracketed IPv6 literal only an optional numeric port is valid.
            if (suffix.isNotEmpty()) {
                val port = suffix.removePrefix(":")
                if (!suffix.startsWith(":") || !validPort(port)) {
                    throw invalid("url is malformed")
                }
            }
        } else if (authority.contains(':')) {
            val hostPort = authority.substringBeforeLast(':')
            val port = authority.substring(hostPort.length + 1)
            if (!validPort(port)) throw invalid("url is malformed")
        }
        // Canonicalize escaped/IDN hosts before applying the same policy as the launcher.
        val parsed = ("https://" + rest).toHttpUrlOrNull() ?: throw invalid("url is malformed")
        val normalized = parsed.host.lowercase().trimEnd('.')
        if (normalized.isEmpty()) {
            throw invalid("HTTPS URL needs a public host")
        }
        if (!publicHostAllowed(normalized)) {
            throw invalid("HTTPS URL must target a public host")
        }
        return parsed.toString().removePrefix("https://")
    }

    private fun validPort(port: String): Boolean =
        port.isNotEmpty() && port.length <= 5 && port.toIntOrNull() in 1..65535

    private fun validateMailto(rest: String): String {
        if (rest.isEmpty() || rest.startsWith("/")) {
            throw invalid("mailto URL is malformed")
        }
        val address = rest.substringBefore('?')
        if (address.contains('@') && address.substringBefore('@').isEmpty()) {
            throw invalid("mailto URL is malformed")
        }
        return rest
    }

    private fun validateTel(rest: String): String {
        if (rest.isEmpty() || rest.startsWith("/") || rest.any { it == '?' || it == '#' }) {
            throw invalid("tel URL is malformed")
        }
        if (!rest.any { it.isDigit() } || !TEL_ALLOWED.matches(rest)) {
            throw invalid("tel URL is malformed")
        }
        return rest
    }

    /** Local, private, and non-routable hosts are rejected (iOS `publicHostAllowed` parity). */
    fun publicHostAllowed(host: String): Boolean {
        val lower = host.lowercase().trim('.', '[', ']')
        if (lower == "localhost" || lower == "0.0.0.0" || lower.endsWith(".localhost") ||
            lower.endsWith(".local") || lower.endsWith(".internal")
        ) {
            return false
        }
        // Browsers also interpret shortened, octal and hexadecimal IPv4 hosts.
        // Accept only canonical dotted decimal here so those spellings cannot
        // bypass the private-address check below.
        val lastLabel = lower.substringAfterLast('.')
        if (lastLabel.all { it in '0'..'9' } || Regex("0x[0-9a-f]+").matches(lastLabel)) {
            val parts = lower.split('.')
            if (parts.size != 4 || parts.any { part ->
                    part.isEmpty() || part.any { it !in '0'..'9' } ||
                        (part.length > 1 && part.startsWith('0')) || part.toIntOrNull() !in 0..255
                }) return false
            val octets = parts.map { it.toInt() }
            return publicIPv4Allowed(octets[0], octets[1], octets[2])
        }
        val bare = lower.removePrefix("[").removeSuffix("]")
        if (bare.contains(':')) {
            return publicIPv6Allowed(bare)
        }
        return true
    }

    private fun publicIPv4Allowed(first: Int, second: Int, third: Int): Boolean {
        if (first == 0 || first == 10 || first == 127) return false
        if (first == 100 && second in 64..127) return false
        if (first == 169 && second == 254) return false
        if (first == 172 && second in 16..31) return false
        if (first == 192 && second == 168) return false
        if (first == 192 && second == 0 && (third == 0 || third == 2)) return false
        if (first == 198 && (second == 18 || second == 19)) return false
        if (first == 198 && second == 51 && third == 100) return false
        if (first == 203 && second == 0 && third == 113) return false
        return first < 224
    }

    private fun publicIPv6Allowed(raw: String): Boolean {
        val groups = raw.split("::")
        if (groups.size > 2) return false
        val head = groups[0].split(':').filter { it.isNotEmpty() }
        val tail = if (groups.size == 2) groups[1].split(':').filter { it.isNotEmpty() } else emptyList()
        val bytes = IntArray(16)
        var parsed = 0
        for (part in head) {
            val value = part.toIntOrNull(16) ?: return false
            if (parsed + 1 >= 16) return false
            bytes[parsed++] = (value shr 8) and 0xff
            bytes[parsed++] = value and 0xff
        }
        if (groups.size == 2) {
            var index = 15
            for (part in tail.asReversed()) {
                val value = part.toIntOrNull(16) ?: return false
                if (value > 0xffff || index - 1 < parsed) return false
                bytes[index--] = value and 0xff
                bytes[index--] = (value shr 8) and 0xff
            }
        } else if (parsed != 16) {
            return false
        }
        // IPv4-mapped ::ffff:a.b.c.d
        if (bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff) {
            return publicIPv4Allowed(bytes[12], bytes[13], bytes[14])
        }
        // Only globally routable unicast (2000::/3) is allowed; rejects
        // loopback, link-local, unique-local, multicast and documentation space.
        return (bytes[0] and 0xe0) == 0x20 &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8)
    }
}

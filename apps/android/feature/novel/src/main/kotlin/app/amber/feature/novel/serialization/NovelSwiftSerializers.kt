package app.amber.feature.novel.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

/** Typed ID wire form: `{"rawValue":"UUID-UPPERCASE"}`. */
abstract class NovelTypedIdSerializer<T>(
    serialName: String,
    private val wrap: (String) -> T,
    private val unwrap: (T) -> String,
) : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(serialName) {
        element<String>("rawValue")
    }

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeStructure(descriptor) {
            encodeStringElement(descriptor, 0, normalizeUuidString(unwrap(value)))
        }
    }

    override fun deserialize(decoder: Decoder): T {
        var raw: String? = null
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> raw = decodeStringElement(descriptor, 0)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw IllegalArgumentException("Unexpected index $index for typed id")
                }
            }
        }
        return wrap(normalizeUuidString(requireNotNull(raw) { "typed id missing rawValue" }))
    }
}

/** Bare UUID string (uppercase), e.g. factCompatibilityID. */
object NovelBareUuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NovelBareUuid", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) {
        encoder.encodeString(value.toString().uppercase(Locale.US))
    }

    override fun deserialize(decoder: Decoder): UUID =
        UUID.fromString(decoder.decodeString())
}

/**
 * Swift `Date` as seconds since 2001-01-01 UTC.
 * Domain value is [Instant] (Unix timeline).
 */
object NovelSwiftDateSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NovelSwiftDate", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Instant) {
        // Wire uses Double seconds. Normalize to whole milliseconds so encode/decode
        // is stable (Instant.now() nanos would otherwise fail repository equality checks).
        val millis = value.toEpochMilli()
        val unixSeconds = millis / 1000.0
        encoder.encodeDouble(NovelSwiftWireContract.unixToSwiftDateSeconds(unixSeconds))
    }

    override fun deserialize(decoder: Decoder): Instant {
        val unixSeconds = NovelSwiftWireContract.swiftDateSecondsToUnix(decoder.decodeDouble())
        val millis = kotlin.math.round(unixSeconds * 1000.0).toLong()
        return Instant.ofEpochMilli(millis)
    }
}

fun normalizeUuidString(raw: String): String =
    UUID.fromString(raw.trim()).toString().uppercase(Locale.US)

fun uuidStringLower(raw: String): String =
    UUID.fromString(raw.trim()).toString().lowercase(Locale.US)

/**
 * Recursive key-sorted canonical JSON (compact, no spaces).
 * Used for payload SHA-256; must not depend on data-class declaration order.
 */
fun canonicalJsonElement(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonPrimitive -> element.toString()
    is JsonArray -> element.joinToString(separator = ",", prefix = "[", postfix = "]") {
        canonicalJsonElement(it)
    }
    is JsonObject -> {
        val keys = element.keys.sorted()
        keys.joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
            val encodedKey = JsonPrimitive(key).toString()
            "$encodedKey:${canonicalJsonElement(element.getValue(key))}"
        }
    }
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sha256HexOfUtf8(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

/**
 * Swift-style associated enum encode helper: single keyed object.
 * - empty associated: `{"caseName":{}}`
 * - labeled fields: `{"caseName":{...fields...}}`
 * - single unlabeled: `{"caseName":{"_0":value}}`
 */
fun swiftAssociatedObject(caseName: String, associated: JsonObject = buildJsonObject { }): JsonObject =
    buildJsonObject {
        put(caseName, associated)
    }

fun decodeSwiftAssociatedCase(element: JsonElement): Pair<String, JsonObject> {
    val obj = element as? JsonObject
        ?: throw IllegalArgumentException("Expected associated enum object, got $element")
    require(obj.size == 1) { "Associated enum must have exactly one case key, got keys=${obj.keys}" }
    val (caseName, payload) = obj.entries.first()
    val associated = when (payload) {
        is JsonObject -> payload
        is JsonNull -> buildJsonObject { }
        else -> throw IllegalArgumentException("Associated enum payload must be object for case $caseName")
    }
    return caseName to associated
}

package app.amber.feature.novel.serialization

import app.amber.feature.novel.model.NovelPackageEnvelopeV1
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * Swift Codable-compatible project/package codecs for Novel V1.
 */
object NovelSwiftCompatibleJson {
    /**
     * Production codec:
     * - explicitNulls = false (omit Swift optional nil)
     * - encodeDefaults = true (emit non-optional V1 defaults)
     * - ignoreUnknownKeys = true on package/project decode
     * - custom serializers for typed IDs, Date, and associated enums
     */
    val json: Json = Json {
        explicitNulls = false
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
        isLenient = false
    }

    fun canonicalJson(element: JsonElement): String = canonicalJsonElement(element)

    fun sha256Hex(bytes: ByteArray): String =
        app.amber.feature.novel.serialization.sha256Hex(bytes)

    fun decodeProjectDocument(rawJson: ByteArray): NovelProjectDocumentV1 {
        val text = rawJson.toString(Charsets.UTF_8)
        return json.decodeFromString(NovelProjectDocumentWireSerializer, text)
    }

    fun encodeProjectDocument(document: NovelProjectDocumentV1): ByteArray {
        val element = json.encodeToJsonElement(NovelProjectDocumentWireSerializer, document)
        val canonical = canonicalJson(element)
        return canonical.toByteArray(Charsets.UTF_8)
    }

    fun decodePackageEnvelope(rawJson: ByteArray): NovelPackageEnvelope {
        val envelope = json.decodeFromString(NovelPackageEnvelopeV1.serializer(), rawJson.toString(Charsets.UTF_8))
        return NovelPackageEnvelope(
            format = envelope.format,
            envelopeVersion = envelope.envelopeVersion,
            projectSchemaVersion = envelope.projectSchemaVersion,
            projectIdRawValue = envelope.projectID.rawValue,
            projectByteCount = envelope.projectByteCount,
            projectSha256 = envelope.projectSHA256,
            projectJsonBase64 = envelope.projectJSONBase64,
        )
    }

    fun encodePackageEnvelope(envelope: NovelPackageEnvelope): ByteArray {
        val model = NovelPackageEnvelopeV1(
            format = envelope.format,
            envelopeVersion = envelope.envelopeVersion,
            projectSchemaVersion = envelope.projectSchemaVersion,
            projectID = NovelProjectId.parse(envelope.projectIdRawValue),
            projectByteCount = envelope.projectByteCount,
            projectSHA256 = envelope.projectSha256,
            projectJSONBase64 = envelope.projectJsonBase64,
        )
        val element = json.encodeToJsonElement(NovelPackageEnvelopeV1.serializer(), model)
        return canonicalJson(element).toByteArray(Charsets.UTF_8)
    }

    fun decodeProjectDocumentFromPackage(rawEnvelope: ByteArray): NovelProjectDocumentV1 {
        val envelope = decodePackageEnvelope(rawEnvelope)
        validateEnvelopeMetadata(envelope)
        val projectBytes = decodeStrictBase64(envelope.projectJsonBase64)
        require(projectBytes.size == envelope.projectByteCount) {
            "Project payload byte count does not match"
        }
        require(sha256Hex(projectBytes) == envelope.projectSha256.lowercase()) {
            "Project payload SHA-256 does not match"
        }
        val document = decodeProjectDocument(projectBytes)
        require(document.schemaVersion == envelope.projectSchemaVersion) {
            "Envelope and project schema versions differ"
        }
        require(document.project.id.rawValue == envelope.projectIdRawValue) {
            "Envelope project ID does not match payload"
        }
        return document
    }

    fun encodePackageFromDocument(document: NovelProjectDocumentV1): NovelPackageEnvelope {
        require(document.schemaVersion == NovelSwiftWireContract.PROJECT_SCHEMA_VERSION) {
            "Unsupported project schema ${document.schemaVersion}"
        }
        val projectBytes = encodeProjectDocument(document)
        require(projectBytes.size <= NovelSwiftWireContract.MAX_PROJECT_BYTES) {
            "Project exceeds maximum size"
        }
        val sha = sha256Hex(projectBytes)
        val b64 = Base64.getEncoder().encodeToString(projectBytes)
        return NovelPackageEnvelope(
            format = NovelSwiftWireContract.PACKAGE_FORMAT,
            envelopeVersion = NovelSwiftWireContract.ENVELOPE_VERSION,
            projectSchemaVersion = document.schemaVersion,
            projectIdRawValue = document.project.id.rawValue,
            projectByteCount = projectBytes.size,
            projectSha256 = sha,
            projectJsonBase64 = b64,
        )
    }

    private fun validateEnvelopeMetadata(envelope: NovelPackageEnvelope) {
        require(envelope.format == NovelSwiftWireContract.PACKAGE_FORMAT) {
            "Unrecognized package format: ${envelope.format}"
        }
        require(envelope.envelopeVersion == NovelSwiftWireContract.ENVELOPE_VERSION) {
            "Unsupported envelope version: ${envelope.envelopeVersion}"
        }
        require(envelope.projectSchemaVersion == NovelSwiftWireContract.PROJECT_SCHEMA_VERSION) {
            "Unsupported project schema: ${envelope.projectSchemaVersion}"
        }
        require(envelope.projectByteCount in 0..NovelSwiftWireContract.MAX_PROJECT_BYTES) {
            "Invalid project byte count"
        }
    }

    private fun decodeStrictBase64(b64: String): ByteArray {
        require('\n' !in b64 && '\r' !in b64) { "Base64 must not contain newlines" }
        val decoded = Base64.getDecoder().decode(b64)
        val reencoded = Base64.getEncoder().encodeToString(decoded)
        require(reencoded == b64) { "Base64 is not canonical" }
        return decoded
    }
}

data class NovelPackageEnvelope(
    val format: String,
    val envelopeVersion: Int,
    val projectSchemaVersion: Int,
    val projectIdRawValue: String,
    val projectByteCount: Int,
    val projectSha256: String,
    val projectJsonBase64: String,
)

/** Convenience: parse project fixture JSON object for tests that still want JsonObject. */
fun NovelSwiftCompatibleJson.decodeProjectDocumentAsJsonObject(rawJson: ByteArray): JsonObject =
    json.parseToJsonElement(rawJson.toString(Charsets.UTF_8)).jsonObject

/**
 * Wire serializer for [NovelProjectDocumentV1] implementing the P5-01 unknown-field
 * retention strategy (model-layer extension map + controlled merge):
 *
 * - decode: known keys are decoded by the generated serializer; every other top-level
 *   key is captured verbatim into [NovelProjectDocumentV1.preservedUnknownFields].
 * - encode: the generated serializer emits only known fields; extension entries are
 *   merged back under their original key names, so unknown iOS fields survive
 *   Android import → edit → export → reimport (and repository persistence, which
 *   round-trips through this serializer).
 *
 * Scope note: retention is at the project-document top level. Unknown keys nested
 * inside known records are not retained; iOS fields that participate in the
 * cross-platform contract (including message Ask User payloads and state-snapshot
 * identity clarifications) are promoted to typed known fields instead.
 */
object NovelProjectDocumentWireSerializer : KSerializer<NovelProjectDocumentV1> {
    private val delegate = NovelProjectDocumentV1.serializer()

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: NovelProjectDocumentV1) {
        val json = encoder as JsonEncoder
        val known = json.json.encodeToJsonElement(delegate, value) as JsonObject
        if (value.preservedUnknownFields.isEmpty()) {
            json.encodeJsonElement(known)
            return
        }
        val merged = buildJsonObject {
            known.forEach { (key, element) -> put(key, element) }
            value.preservedUnknownFields.forEach { (key, element) -> put(key, element) }
        }
        json.encodeJsonElement(merged)
    }

    override fun deserialize(decoder: Decoder): NovelProjectDocumentV1 {
        val json = decoder as JsonDecoder
        val root = json.decodeJsonElement().jsonObject
        val knownNames = delegate.descriptor.elementNames.toSet()
        val extension = buildJsonObject {
            root.forEach { (key, element) ->
                if (key !in knownNames) put(key, element)
            }
        }
        val document = json.json.decodeFromJsonElement(delegate, root)
        return if (extension.isEmpty()) document else document.copy(preservedUnknownFields = extension)
    }
}

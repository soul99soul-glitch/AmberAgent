package app.amber.feature.novel.serialization

import app.amber.feature.novel.model.NovelAppliedOperationRecord
import app.amber.feature.novel.model.NovelBranchCheckpointRecord
import app.amber.feature.novel.model.NovelChapterVersionRecord
import app.amber.feature.novel.model.NovelFactAttemptRecord
import app.amber.feature.novel.model.NovelGenerationReceiptRecord
import app.amber.feature.novel.model.NovelInjectionReceiptRecord
import app.amber.feature.novel.model.NovelInjectionSectionKind
import app.amber.feature.novel.model.NovelMaterialRevisionRecord
import app.amber.feature.novel.model.NovelPendingOperationRecord
import app.amber.feature.novel.model.NovelPolishAssessmentRecord
import app.amber.feature.novel.model.NovelPolishAttemptRecord
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelStateSnapshotRecord
import app.amber.feature.novel.model.NovelStoryEventRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.time.Instant

/**
 * In-memory encode cache for heavy, append-mostly document sections.
 *
 * `NovelFileProjectRepository` re-encodes the whole monofile on every commit; for a
 * ~20MB project (injection receipts dominate) that is the dominant commit cost. This
 * cache stores the canonical JSON of unchanged heavy sections, keyed by a cheap
 * fingerprint, so a commit only re-encodes the sections that actually changed.
 *
 * Correctness model:
 * - A cache entry is only reused when [fingerprint] matches; fingerprints cover every
 *   field the domain reducers can mutate on these sections (see fingerprint functions).
 *   Sections are append-only in the reducers, except `stateSnapshots` which the
 *   ManualSync reducer rewrites in place — its fingerprint therefore covers the full
 *   content of every snapshot.
 * - Output bytes are identical to the cold path: the document is composed from the
 *   same per-field canonical encodings and key-sorted exactly like
 *   [canonicalJsonElement] does, so cache-hit output == cold output for the same
 *   document (covered by equivalence tests).
 * - If the document model gains a field the composition does not know, the whole
 *   encode falls back to the cold path rather than silently dropping the field.
 *
 * No disk persistence and no GC: entries live for the repository instance lifetime
 * (per project id) and are replaced wholesale on fingerprint change.
 */
class NovelSectionEncodeCache {
    internal data class Entry(val fingerprint: String, val canonicalJson: String)

    private val entries = mutableMapOf<String, Entry>()

    internal fun get(section: String): Entry? = entries[section]

    internal fun put(section: String, entry: Entry) {
        entries[section] = entry
    }
}

/** Result of a cached project encode; counts are per cached section (fresh vs hit). */
class NovelCachedEncodeResult(
    val bytes: ByteArray,
    val freshSectionCount: Int,
    val cachedSectionCount: Int,
)

/**
 * Sections that participate in the encode cache. Chosen from the measured encode
 * hotspots (injection receipts dominate; generation receipts / applied operations /
 * checkpoints / events / chapter versions / material revisions / state snapshots are
 * secondary) and restricted to sections whose mutations are all captured by their
 * fingerprint. Small or frequently-mutated sections (branches, sessions, activeRuns,
 * pendingOperations, ...) always re-encode, mirroring the iOS sharded storage policy.
 */
private val CACHED_SECTIONS: Set<String> = setOf(
    "injectionReceipts",
    "generationReceipts",
    "events",
    "stateSnapshots",
    "checkpoints",
    "chapterVersions",
    "materialRevisions",
    "appliedOperations",
)

/**
 * Encode a project document, reusing [cache] entries for unchanged heavy sections.
 * Mutates [cache] in place (fresh entries replace stale ones). When [cache] is null
 * or the model shape is not covered by the composition, behaves exactly like
 * [NovelSwiftCompatibleJson.encodeProjectDocument].
 */
fun encodeProjectDocumentCached(
    document: NovelProjectDocumentV1,
    cache: NovelSectionEncodeCache?,
): NovelCachedEncodeResult {
    if (cache == null) {
        return NovelCachedEncodeResult(NovelSwiftCompatibleJson.encodeProjectDocument(document), 0, 0)
    }
    // Drift guard: composition must cover every serialized top-level field; otherwise
    // fall back to the cold full-document encode so a model change can never drop a
    // field silently. Note this guard only compares top-level field names — nested
    // model evolution is covered by FINGERPRINT_SCHEMA_VERSION (bump it on any section
    // record model or wire serializer change) which is mixed into every fingerprint.
    val serializedNames = NovelProjectDocumentV1.serializer().descriptor.elementNames.toSet()
    if (serializedNames != COMPOSED_FIELD_ENCODERS.keys) {
        return NovelCachedEncodeResult(NovelSwiftCompatibleJson.encodeProjectDocument(document), 0, 0)
    }

    var freshSections = 0
    var cachedSections = 0
    val parts = ArrayList<Pair<String, String>>(serializedNames.size + document.preservedUnknownFields.size)
    var estimatedLength = 2
    for (name in COMPOSED_FIELD_ORDER) {
        val value = if (name in CACHED_SECTIONS) {
            val fingerprint = cachedSectionFingerprint(name, document)
            val hit = cache.get(name)
            if (hit != null && hit.fingerprint == fingerprint) {
                cachedSections++
                hit.canonicalJson
            } else {
                freshSections++
                val canonical = cachedSectionCanonicalJson(name, document)
                cache.put(name, NovelSectionEncodeCache.Entry(fingerprint, canonical))
                canonical
            }
        } else {
            COMPOSED_FIELD_ENCODERS.getValue(name)(document)
        }
        if (value != null) {
            parts.add(name to value)
            estimatedLength += value.length + name.length + 4
        }
    }
    document.preservedUnknownFields.forEach { (key, element) ->
        val canonical = canonicalJsonElement(element)
        parts.add(key to canonical)
        estimatedLength += canonical.length + key.length + 4
    }
    parts.sortBy { it.first }
    val sb = StringBuilder(estimatedLength)
    sb.append('{')
    parts.forEachIndexed { index, (key, value) ->
        if (index > 0) sb.append(',')
        sb.append(JsonPrimitive(key).toString()).append(':').append(value)
    }
    sb.append('}')
    return NovelCachedEncodeResult(
        bytes = sb.toString().toByteArray(Charsets.UTF_8),
        freshSectionCount = freshSections,
        cachedSectionCount = cachedSections,
    )
}

/**
 * Canonical JSON encoders for every serialized top-level field (null value = omit,
 * matching `explicitNulls = false`). Values are produced exactly like the generated
 * wire serializer encodes each field, so composition output is byte-identical to the
 * cold `encodeProjectDocument` path (verified by equivalence tests). Sections in
 * [CACHED_SECTIONS] are handled by [cachedSectionCanonicalJson] instead.
 */
private val COMPOSED_FIELD_ENCODERS: Map<String, (NovelProjectDocumentV1) -> String?> = mapOf(
    "schemaVersion" to { document -> JsonPrimitive(document.schemaVersion).toString() },
    "producerVersion" to { document -> document.producerVersion?.let { JsonPrimitive(it).toString() } },
    "project" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                app.amber.feature.novel.model.NovelProjectRecord.serializer(),
                document.project,
            ),
        )
    },
    "materials" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelMaterialRecord.serializer()),
                document.materials,
            ),
        )
    },
    "materialRevisions" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelMaterialRevisionRecord.serializer()),
                document.materialRevisions,
            ),
        )
    },
    "branches" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelBranchRecord.serializer()),
                document.branches,
            ),
        )
    },
    "sessions" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelSessionRecord.serializer()),
                document.sessions,
            ),
        )
    },
    "chapters" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelChapterRecord.serializer()),
                document.chapters,
            ),
        )
    },
    "chapterVersions" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelChapterVersionRecord.serializer()),
                document.chapterVersions,
            ),
        )
    },
    "events" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelStoryEventRecord.serializer()),
                document.events,
            ),
        )
    },
    "stateSnapshots" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelStateSnapshotRecord.serializer()),
                document.stateSnapshots,
            ),
        )
    },
    "checkpoints" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelBranchCheckpointRecord.serializer()),
                document.checkpoints,
            ),
        )
    },
    "candidates" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelCandidateRecord.serializer()),
                document.candidates,
            ),
        )
    },
    "injectionReceipts" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelInjectionReceiptRecord.serializer()),
                document.injectionReceipts,
            ),
        )
    },
    "generationReceipts" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelGenerationReceiptRecord.serializer()),
                document.generationReceipts,
            ),
        )
    },
    "factAttempts" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelFactAttemptRecord.serializer()),
                document.factAttempts,
            ),
        )
    },
    "polishTransactions" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(
                    app.amber.feature.novel.model.NovelPendingPolishTransactionRecord.serializer(),
                ),
                document.polishTransactions,
            ),
        )
    },
    "polishAttempts" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelPolishAttemptRecord.serializer()),
                document.polishAttempts,
            ),
        )
    },
    "polishAssessments" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelPolishAssessmentRecord.serializer()),
                document.polishAssessments,
            ),
        )
    },
    "pendingOperations" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelPendingOperationRecord.serializer()),
                document.pendingOperations,
            ),
        )
    },
    "activeRuns" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelActiveRunRecord.serializer()),
                document.activeRuns,
            ),
        )
    },
    "settingProposals" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelSettingProposalRecord.serializer()),
                document.settingProposals,
            ),
        )
    },
    "chapterPlans" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelChapterPlanRecord.serializer()),
                document.chapterPlans,
            ),
        )
    },
    "upcomingArcs" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(app.amber.feature.novel.model.NovelUpcomingArcRecord.serializer()),
                document.upcomingArcs,
            ),
        )
    },
    "appliedOperations" to { document ->
        canonicalJsonElement(
            NovelSwiftCompatibleJson.json.encodeToJsonElement(
                ListSerializer(NovelAppliedOperationRecord.serializer()),
                document.appliedOperations,
            ),
        )
    },
)

/** Field iteration order of the composition (order is irrelevant — keys are sorted). */
private val COMPOSED_FIELD_ORDER: List<String> = COMPOSED_FIELD_ENCODERS.keys.toList()

private fun cachedSectionCanonicalJson(name: String, document: NovelProjectDocumentV1): String = when (name) {
    "injectionReceipts" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelInjectionReceiptRecord.serializer()),
            document.injectionReceipts,
        ),
    )
    "generationReceipts" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelGenerationReceiptRecord.serializer()),
            document.generationReceipts,
        ),
    )
    "events" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelStoryEventRecord.serializer()),
            document.events,
        ),
    )
    "stateSnapshots" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelStateSnapshotRecord.serializer()),
            document.stateSnapshots,
        ),
    )
    "checkpoints" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelBranchCheckpointRecord.serializer()),
            document.checkpoints,
        ),
    )
    "chapterVersions" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelChapterVersionRecord.serializer()),
            document.chapterVersions,
        ),
    )
    "materialRevisions" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelMaterialRevisionRecord.serializer()),
            document.materialRevisions,
        ),
    )
    "appliedOperations" -> canonicalJsonElement(
        NovelSwiftCompatibleJson.json.encodeToJsonElement(
            ListSerializer(NovelAppliedOperationRecord.serializer()),
            document.appliedOperations,
        ),
    )
    else -> error("Unexpected cached section: $name")
}

// ---------------------------------------------------------------------------
// Fingerprints
//
// Cheap structural marks per element (IDs, enum names, dates at millisecond
// precision, token/SHA fields, and for in-place mutable sections the full small
// content). Any mutation the domain reducers can perform changes at least one mark,
// so a matching fingerprint means the section content is unchanged and the cached
// canonical JSON is still valid.
// ---------------------------------------------------------------------------

/**
 * Manually maintained fingerprint schema version. The drift guard only compares
 * top-level field names, so it cannot detect nested model evolution (a new field
 * inside a section record, a changed wire serializer). Bump this constant whenever
 * any section record model or its wire serializer changes; it is mixed into every
 * fingerprint, invalidating all cached sections.
 */
private const val FINGERPRINT_SCHEMA_VERSION = 2

private const val FNV1_64_OFFSET = 0xcbf29ce484222325UL
private const val FNV1_64_PRIME = 0x100000001b3UL

private class FingerprintSink {
    private var hash = FNV1_64_OFFSET

    fun add(text: String) {
        for (i in 0 until text.length) {
            hash = (hash xor text[i].code.toULong()) * FNV1_64_PRIME
        }
        hash = (hash xor 0xffUL) * FNV1_64_PRIME
    }

    fun add(value: Long) = add(value.toString())
    fun add(value: Int) = add(value.toLong())
    fun add(value: Boolean) = add(if (value) "1" else "0")
    fun add(value: Instant) = add(value.toEpochMilli())

    fun finish(): String = hash.toString(16)
}

private fun fingerprintInjectionReceipts(list: List<NovelInjectionReceiptRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (receipt in list) {
        sink.add(receipt.id.rawValue)
        sink.add(receipt.runID.rawValue)
        sink.add(receipt.projectID.rawValue)
        sink.add(receipt.branchID.rawValue)
        sink.add(receipt.promptVersion)
        sink.add(receipt.providerID)
        sink.add(receipt.ownerProviderID)
        sink.add(receipt.modelID)
        sink.add(receipt.wireModelID)
        sink.add(receipt.requestedInputBudgetTokens)
        sink.add(receipt.maxEstimatedInputTokens)
        sink.add(receipt.estimatedInputTokens)
        sink.add(receipt.canonicalInputSHA256)
        sink.add(receipt.createdAt)
        // Maps must hash in sorted key order: canonical JSON sorts keys, so a decoded
        // round-trip iterates entries differently than the in-memory map — a fingerprint
        // that depended on iteration order would never match after a wire round-trip.
        for ((key, value) in receipt.parameters.entries.sortedBy { it.key }) {
            sink.add(key)
            sink.add(value)
        }
        sink.add(receipt.sections.size.toLong())
        for (section in receipt.sections) {
            // Hash the full canonical wire serialization of the kind, not just the class
            // name: the payload-bearing variants (ChapterPlan(planID), CurrentState
            // (snapshotID), Material(revisionID), ...) would otherwise evade the
            // fingerprint, and hashing the wire string also covers nested payload
            // evolution automatically.
            sink.add(
                canonicalJsonElement(
                    NovelSwiftCompatibleJson.json.encodeToJsonElement(
                        NovelInjectionSectionKind.Serializer,
                        section.kind,
                    ),
                ),
            )
            sink.add(section.label)
            sink.add(section.reason.name)
            sink.add(section.estimatedTokens)
            sink.add(section.contentSHA256)
        }
        sink.add(receipt.materialDecisions.size.toLong())
        for (decision in receipt.materialDecisions) {
            sink.add(decision.materialID.rawValue)
            sink.add(decision.revisionID.rawValue)
            sink.add(decision.included)
            sink.add(decision.reason.name)
            sink.add(decision.relevanceScore)
            sink.add(decision.estimatedTokens)
            sink.add(decision.contentSHA256)
            for (match in decision.matchReasons) sink.add(match)
        }
        // Force lists are set-like: hash the contents in sorted order so a different
        // content with the same size still changes the fingerprint, and list order
        // differences (which the set semantics do not preserve) do not cause spurious
        // misses after a wire round-trip.
        for (materialId in receipt.forceIncludeMaterialIDs.map { it.rawValue }.sorted()) {
            sink.add(materialId)
        }
        for (materialId in receipt.forceExcludeMaterialIDs.map { it.rawValue }.sorted()) {
            sink.add(materialId)
        }
        receipt.factTransaction?.let { fact ->
            sink.add(fact.pendingID.rawValue)
            sink.add(fact.ownerOperationID.rawValue)
            sink.add(fact.attemptOperationID.rawValue)
            sink.add(fact.attemptPayloadSHA256)
            sink.add(fact.kind.name)
            sink.add(fact.chunkIndex?.toLong() ?: -1L)
        }
    }
    return sink.finish()
}

private fun fingerprintGenerationReceipts(list: List<NovelGenerationReceiptRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (receipt in list) {
        sink.add(receipt.id.rawValue)
        sink.add(receipt.runID.rawValue)
        sink.add(receipt.providerID)
        sink.add(receipt.ownerProviderID)
        sink.add(receipt.modelID)
        sink.add(receipt.wireModelID)
        sink.add(receipt.promptVersion)
        sink.add(receipt.injectionReceiptID.rawValue)
        for ((key, value) in receipt.parameters.entries.sortedBy { it.key }) {
            sink.add(key)
            sink.add(value)
        }
        sink.add(receipt.requestSHA256)
        sink.add(receipt.createdAt)
        receipt.factTransaction?.let { fact ->
            sink.add(fact.pendingID.rawValue)
            sink.add(fact.ownerOperationID.rawValue)
            sink.add(fact.attemptOperationID.rawValue)
            sink.add(fact.attemptPayloadSHA256)
            sink.add(fact.kind.name)
            sink.add(fact.chunkIndex?.toLong() ?: -1L)
        }
    }
    return sink.finish()
}

private fun fingerprintEvents(list: List<NovelStoryEventRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (event in list) {
        sink.add(event.id.rawValue)
        sink.add(event.sequence)
        sink.add(event.kind)
        sink.add(event.summary)
        for (reference in event.entityReferences) sink.add(reference)
        sink.add(event.createdAt)
    }
    return sink.finish()
}

/** State snapshots are rewritten in place by the ManualSync reducer — cover full content. */
private fun fingerprintStateSnapshots(list: List<NovelStateSnapshotRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (snapshot in list) {
        sink.add(snapshot.id.rawValue)
        sink.add(snapshot.createdAt)
        for (eventId in snapshot.eventIDs) sink.add(eventId.rawValue)
        sink.add(snapshot.summary)
        sink.add(snapshot.branchOutline)
        for (name in snapshot.unresolvedEntityNames) sink.add(name)
        for (clarification in snapshot.characterIdentityClarifications) {
            sink.add(clarification.mention)
            sink.add(clarification.clarification)
            sink.add(clarification.operationID.rawValue)
            sink.add(clarification.createdAt)
        }
        for (proposalId in snapshot.settingProposalIDs) sink.add(proposalId.rawValue)
        for (highlight in snapshot.recentWrittenHighlights) sink.add(highlight)
    }
    return sink.finish()
}

private fun fingerprintCheckpoints(list: List<NovelBranchCheckpointRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (checkpoint in list) {
        sink.add(checkpoint.id.rawValue)
        sink.add(checkpoint.kind.name)
        sink.add(checkpoint.createdOnBranchID.rawValue)
        sink.add(checkpoint.parentCheckpointID?.rawValue ?: "-")
        for (selection in checkpoint.chapterSelections) {
            sink.add(selection.chapterID.rawValue)
            sink.add(selection.versionID.rawValue)
        }
        sink.add(checkpoint.stateSnapshotID.rawValue)
        sink.add(checkpoint.sessionCursor::class.simpleName ?: "")
        (checkpoint.sessionCursor as? app.amber.feature.novel.model.NovelSessionCursor.Through)
            ?.let { sink.add(it.sequence) }
        for (revisionId in checkpoint.branchOverrideRevisionIDs) sink.add(revisionId.rawValue)
        sink.add(checkpoint.sourceCandidateID?.rawValue ?: "-")
        sink.add(checkpoint.baseHeadRevision)
        sink.add(checkpoint.operationID.rawValue)
        sink.add(checkpoint.createdAt)
    }
    return sink.finish()
}

/** Chapter versions are append-only in the reducers; full title/content included for safety. */
private fun fingerprintChapterVersions(list: List<NovelChapterVersionRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (version in list) {
        sink.add(version.id.rawValue)
        sink.add(version.chapterID.rawValue)
        sink.add(version.kind.name)
        sink.add(version.title)
        sink.add(version.content)
        sink.add(version.factCompatibilityID.toString())
        sink.add(version.sourceChapterVersionID?.rawValue ?: "-")
        sink.add(version.sourceCandidateID?.rawValue ?: "-")
        sink.add(version.createdAt)
        sink.add(version.operationID.rawValue)
    }
    return sink.finish()
}

private fun fingerprintMaterialRevisions(list: List<NovelMaterialRevisionRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (revision in list) {
        sink.add(revision.id.rawValue)
        sink.add(revision.materialID.rawValue)
        sink.add(revision.revision)
        sink.add(revision.title)
        sink.add(revision.content)
        for (tag in revision.tags) sink.add(tag)
        for (alias in revision.aliases) sink.add(alias)
        sink.add(revision.injectionMode.name)
        sink.add(revision.createdAt)
        sink.add(revision.operationID.rawValue)
    }
    return sink.finish()
}

private fun fingerprintAppliedOperations(list: List<NovelAppliedOperationRecord>): String {
    val sink = FingerprintSink()
    sink.add(list.size.toLong())
    for (operation in list) {
        sink.add(operation.operationID.rawValue)
        sink.add(operation.kind.name)
        sink.add(operation.payloadSHA256)
        sink.add(operation.outcome::class.simpleName ?: "")
        sink.add(operation.appliedProjectRevision)
        sink.add(operation.appliedAt)
    }
    return sink.finish()
}

private fun cachedSectionFingerprint(name: String, document: NovelProjectDocumentV1): String =
    // Mix the schema version into every fingerprint: any section record model or wire
    // serializer change bumps FINGERPRINT_SCHEMA_VERSION and invalidates all entries.
    "$FINGERPRINT_SCHEMA_VERSION/" + when (name) {
    "injectionReceipts" -> fingerprintInjectionReceipts(document.injectionReceipts)
    "generationReceipts" -> fingerprintGenerationReceipts(document.generationReceipts)
    "events" -> fingerprintEvents(document.events)
    "stateSnapshots" -> fingerprintStateSnapshots(document.stateSnapshots)
    "checkpoints" -> fingerprintCheckpoints(document.checkpoints)
    "chapterVersions" -> fingerprintChapterVersions(document.chapterVersions)
    "materialRevisions" -> fingerprintMaterialRevisions(document.materialRevisions)
    "appliedOperations" -> fingerprintAppliedOperations(document.appliedOperations)
    else -> error("Unexpected cached section: $name")
}

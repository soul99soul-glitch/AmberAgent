package app.amber.feature.novel.persistence

import app.amber.feature.novel.domain.NovelDocumentValidator
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelRecoverySidecarV1
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import java.io.File
import java.time.Instant

class NovelRecoveryStore(private val rootDirectory: File) {
    private val recoveryDir get() = File(rootDirectory, "recovery").also { it.mkdirs() }

    fun write(
        projectId: NovelProjectId,
        runId: NovelRunId,
        branchId: NovelBranchId,
        sessionId: NovelSessionId,
        messageId: NovelMessageId,
        baseProjectRevision: Long,
        sequence: Long,
        partialContent: String,
        now: Instant = Instant.ofEpochMilli(System.currentTimeMillis()),
    ) {
        val sidecar = NovelRecoverySidecarV1(
            projectID = projectId,
            runID = runId,
            branchID = branchId,
            sessionID = sessionId,
            messageID = messageId,
            baseProjectRevision = baseProjectRevision,
            sequence = sequence,
            partialContent = partialContent,
            partialSHA256 = sha256HexOfUtf8(partialContent),
            updatedAt = now,
        )
        NovelDocumentValidator.validateRecovery(sidecar)
        val bytes = NovelSwiftCompatibleJson.json.encodeToString(
            NovelRecoverySidecarV1.serializer(),
            sidecar,
        ).toByteArray(Charsets.UTF_8)
        val file = fileFor(projectId, runId)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(file)
    }

    fun delete(projectId: NovelProjectId, runId: NovelRunId) {
        fileFor(projectId, runId).delete()
    }

    fun listForProject(projectId: NovelProjectId): List<NovelRecoverySidecarV1> {
        val prefix = projectId.rawValue.lowercase()
        return recoveryDir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }
            ?.mapNotNull { file ->
                runCatching {
                    NovelSwiftCompatibleJson.json.decodeFromString(
                        NovelRecoverySidecarV1.serializer(),
                        file.readText(),
                    )
                }.getOrNull()
            }
            .orEmpty()
    }

    private fun fileFor(projectId: NovelProjectId, runId: NovelRunId): File =
        File(recoveryDir, "${projectId.rawValue.lowercase()}-${runId.rawValue.lowercase()}.json")
}

package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchRecord
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelSessionCursor
import app.amber.feature.novel.model.NovelSessionMessageInteraction
import java.security.MessageDigest

/**
 * Repairs the narrow shape written by the pre-remapping Android fork reducer.
 *
 * The legacy reducer copied session message IDs and left message.candidateID pointing
 * at the source branch, while cloned candidates carried clonedFromCandidateID. This
 * migration only touches that exact fork-origin shape; unrelated missing or foreign
 * references remain for the strict document validator to reject.
 */
object NovelLegacyForkMigration {
    fun migrate(document: NovelProjectDocumentV1): NovelProjectDocumentV1 {
        var migrated = document
        for (branch in document.branches) {
            if (branch.forkOrigin == null) continue
            migrated = migrateBranch(migrated, branch)
        }
        return migrated
    }

    private fun migrateBranch(
        document: NovelProjectDocumentV1,
        branch: NovelBranchRecord,
    ): NovelProjectDocumentV1 {
        val origin = branch.forkOrigin ?: return document
        val sourceBranch = document.branches.singleOrNull { it.id == origin.parentBranchID }
            ?: return document
        val sourceSession = document.sessions.singleOrNull {
            it.id == sourceBranch.sessionID && it.branchID == sourceBranch.id
        } ?: return document
        val currentSessionIndex = document.sessions.indexOfFirst {
            it.id == branch.sessionID && it.branchID == branch.id
        }
        if (currentSessionIndex < 0) return document
        val checkpoint = document.checkpoints.singleOrNull { it.id == origin.checkpointID }
            ?: return document
        if (checkpoint.createdOnBranchID != sourceBranch.id) return document

        val sourcePrefix = when (val cursor = checkpoint.sessionCursor) {
            NovelSessionCursor.Empty -> emptyList()
            is NovelSessionCursor.Through -> sourceSession.messages.filter {
                it.sequence <= cursor.sequence
            }
        }
        val currentSession = document.sessions[currentSessionIndex]
        val legacyMessagesBySourceID = sourcePrefix.mapNotNull { sourceMessage ->
            currentSession.messages.singleOrNull {
                it.id == sourceMessage.id && it.sequence == sourceMessage.sequence
            }?.let { sourceMessage.id to it }
        }.toMap()
        val currentMessageIDBySourceID = legacyMessagesBySourceID.keys.associateWith { sourceID ->
            deterministicLegacyMessageID(branch, sourceID)
        }
        val replacedLegacyIDs = legacyMessagesBySourceID.values.mapTo(mutableSetOf()) { it.id }
        val occupiedIDs = buildSet {
            document.sessions.forEach { session ->
                session.messages.forEach { message ->
                    if (session.id != currentSession.id || message.id !in replacedLegacyIDs) {
                        add(message.id)
                    }
                }
                session.discussionArchives.forEach { archive -> add(archive.id) }
            }
        }
        if (currentMessageIDBySourceID.values.toSet().size != currentMessageIDBySourceID.size ||
            currentMessageIDBySourceID.values.any { it in occupiedIDs }
        ) {
            return document
        }

        val sourceCandidates = sourceBranchCandidates(document, sourceBranch)
        val clonesBySourceID = document.candidates
            .filter { it.branchID == branch.id && it.sessionID == branch.sessionID }
            .filter { it.clonedFromCandidateID in sourceCandidates.keys }
            .groupBy { it.clonedFromCandidateID!! }

        val migratedMessages = currentSession.messages.map { message ->
            val remappedMessageID = currentMessageIDBySourceID[message.id] ?: return@map message
            val sourceCandidateID = message.candidateID
            val remappedCandidateID = if (sourceCandidateID == null) {
                null
            } else {
                val sourceCandidate = sourceCandidates[sourceCandidateID]
                    ?: return@map message.copy(id = remappedMessageID, runID = null)
                val clone = clonesBySourceID[sourceCandidateID].orEmpty().singleOrNull()
                val currentSourceMessageID = currentMessageIDBySourceID[sourceCandidate.sourceMessageID]
                if (clone == null || currentSourceMessageID == null) null else clone.id
            }
            val remappedInteraction = when (val interaction = message.interaction) {
                is NovelSessionMessageInteraction.AskUserAnswer -> interaction.copy(
                    response = interaction.response.copy(
                        promptMessageID = currentMessageIDBySourceID[
                            interaction.response.promptMessageID
                        ] ?: interaction.response.promptMessageID,
                    ),
                )
                else -> interaction
            }
            message.copy(
                id = remappedMessageID,
                runID = null,
                candidateID = remappedCandidateID,
                interaction = remappedInteraction,
            )
        }

        val migratedCandidates = document.candidates.map { candidate ->
            val sourceCandidateID = candidate.clonedFromCandidateID
            if (candidate.branchID != branch.id ||
                candidate.sessionID != branch.sessionID ||
                sourceCandidateID == null ||
                sourceCandidateID !in sourceCandidates
            ) {
                candidate
            } else {
                val sourceCandidate = sourceCandidates.getValue(sourceCandidateID)
                val currentSourceMessageID = currentMessageIDBySourceID[sourceCandidate.sourceMessageID]
                if (clonesBySourceID[sourceCandidateID].orEmpty().singleOrNull()?.id == candidate.id &&
                    currentSourceMessageID != null
                ) {
                    candidate.copy(sourceMessageID = currentSourceMessageID)
                } else {
                    candidate
                }
            }
        }
        if (migratedMessages == currentSession.messages && migratedCandidates == document.candidates) {
            return document
        }
        val sessions = document.sessions.toMutableList()
        sessions[currentSessionIndex] = currentSession.copy(messages = migratedMessages)
        return document.copy(sessions = sessions, candidates = migratedCandidates)
    }

    private fun sourceBranchCandidates(
        document: NovelProjectDocumentV1,
        sourceBranch: NovelBranchRecord,
    ): Map<app.amber.feature.novel.model.NovelCandidateId, NovelCandidateRecord> =
        document.candidates
            .filter { it.branchID == sourceBranch.id && it.sessionID == sourceBranch.sessionID }
            .groupBy { it.id }
            .mapNotNull { (id, candidates) ->
                candidates.singleOrNull()?.let { id to it }
            }
            .toMap()

    private fun deterministicLegacyMessageID(
        branch: NovelBranchRecord,
        sourceID: NovelMessageId,
    ): NovelMessageId {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(
                "${branch.id.rawValue}:legacy-fork-message:${sourceID.rawValue}"
                    .toByteArray(Charsets.UTF_8),
            )
            .copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val hex = bytes.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        return NovelMessageId.parse(
            listOf(
                hex.substring(0, 8),
                hex.substring(8, 12),
                hex.substring(12, 16),
                hex.substring(16, 20),
                hex.substring(20, 32),
            ).joinToString("-"),
        )
    }
}

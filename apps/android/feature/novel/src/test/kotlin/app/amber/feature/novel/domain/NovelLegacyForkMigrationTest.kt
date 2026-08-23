package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelAskUserPrompt
import app.amber.feature.novel.model.NovelAskUserResponse
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelSessionMessageInteraction
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelLegacyForkMigrationTest {
    @Test
    fun migrateRemapsLegacySourceCandidateToUniqueInheritedClone() {
        val base = fixture()
        val sourceBranch = base.branches.first { it.id == base.project.mainBranchID }
        val forkBranch = base.branches.first { it.id != sourceBranch.id }
        val sourceSession = base.sessions.first { it.id == sourceBranch.sessionID }
        val sourcePrompt = sourceSession.messages.first().copy(
            interaction = NovelSessionMessageInteraction.AskUser(
                NovelAskUserPrompt("Which direction?", listOf("Dawn", "Dusk")),
            ),
        )
        val sourceAnswer = sourceSession.messages[1].copy(
            interaction = NovelSessionMessageInteraction.AskUserAnswer(
                NovelAskUserResponse(promptMessageID = sourcePrompt.id, answer = "Dawn"),
            ),
        )
        val sourceSessionWithInteraction = sourceSession.copy(
            messages = listOf(sourcePrompt, sourceAnswer),
        )
        val sourceCandidate = base.candidates.single().copy(status = NovelCandidateStatus.Available)
        val inheritedCandidate = sourceCandidate.copy(
            id = app.amber.feature.novel.model.NovelCandidateId.generate(),
            branchID = forkBranch.id,
            sessionID = forkBranch.sessionID,
            status = NovelCandidateStatus.InheritedReadOnly,
            clonedFromCandidateID = sourceCandidate.id,
        )
        val legacy = base.copy(
            candidates = listOf(sourceCandidate, inheritedCandidate),
            sessions = base.sessions.map { session ->
                when (session.id) {
                    sourceSession.id -> sourceSessionWithInteraction
                    forkBranch.sessionID -> session.copy(messages = sourceSessionWithInteraction.messages)
                    else -> session
                }
            },
        )

        val migrated = NovelLegacyForkMigration.migrate(legacy)
        val migratedSession = migrated.sessions.first { it.id == forkBranch.sessionID }
        val migratedMessage = migratedSession.messages.first { it.candidateID != null }
        val sourceMessage = sourceSession.messages.first { it.candidateID != null }
        assertNotEquals(sourceMessage.id, migratedMessage.id)
        assertNull(migratedMessage.runID)
        assertEquals(inheritedCandidate.id, migratedMessage.candidateID)
        assertEquals(migratedMessage.id, migrated.candidates.first {
            it.id == inheritedCandidate.id
        }.sourceMessageID)
        val migratedPrompt = migratedSession.messages.first()
        val migratedAnswer = migratedMessage.interaction
            as NovelSessionMessageInteraction.AskUserAnswer
        assertNotEquals(sourcePrompt.id, migratedPrompt.id)
        assertEquals(migratedPrompt.id, migratedAnswer.response.promptMessageID)
        NovelDocumentValidator.validate(migrated)
    }

    @Test
    fun migrateTreatsSourceSessionIDsAsOccupiedWhenGeneratedIDCollides() {
        val base = fixture()
        val sourceBranch = base.branches.first { it.id == base.project.mainBranchID }
        val forkBranch = base.branches.first { it.id != sourceBranch.id }
        val sourceSession = base.sessions.first { it.id == sourceBranch.sessionID }
        val collisionID = deterministicLegacyMessageID(forkBranch.id, sourceSession.messages.first().id)
        val sourceMessages = sourceSession.messages.mapIndexed { index, message ->
            if (index == 1) message.copy(id = collisionID) else message
        }
        val sourceCandidate = base.candidates.single().copy(sourceMessageID = collisionID)
        val legacy = base.copy(
            candidates = listOf(sourceCandidate),
            sessions = base.sessions.map { session ->
                when (session.id) {
                    sourceSession.id -> sourceSession.copy(messages = sourceMessages)
                    forkBranch.sessionID -> session.copy(messages = sourceMessages)
                    else -> session
                }
            },
        )

        assertEquals(legacy, NovelLegacyForkMigration.migrate(legacy))
    }

    @Test
    fun migrateKeepsUniqueInheritedCloneWhenSourceCandidateWasCollectedLater() {
        val base = fixture()
        val sourceBranch = base.branches.first { it.id == base.project.mainBranchID }
        val forkBranch = base.branches.first { it.id != sourceBranch.id }
        val sourceSession = base.sessions.first { it.id == sourceBranch.sessionID }
        val sourceCandidate = base.candidates.single()
        val inheritedCandidate = sourceCandidate.copy(
            id = app.amber.feature.novel.model.NovelCandidateId.generate(),
            branchID = forkBranch.id,
            sessionID = forkBranch.sessionID,
            status = NovelCandidateStatus.InheritedReadOnly,
            clonedFromCandidateID = sourceCandidate.id,
        )
        val legacy = base.copy(
            candidates = base.candidates + inheritedCandidate,
            sessions = base.sessions.map { session ->
                if (session.id == forkBranch.sessionID) session.copy(messages = sourceSession.messages) else session
            },
        )

        val migrated = NovelLegacyForkMigration.migrate(legacy)
        val migratedSession = migrated.sessions.first { it.id == forkBranch.sessionID }
        assertEquals(
            inheritedCandidate.id,
            migratedSession.messages.first { it.content == "Once upon a time." }.candidateID,
        )
        NovelDocumentValidator.validate(migrated)
    }

    @Test
    fun migrateClearsLegacyReferenceWhenNoInheritedCloneExists() {
        val base = fixture()
        val sourceBranch = base.branches.first { it.id == base.project.mainBranchID }
        val forkBranch = base.branches.first { it.id != sourceBranch.id }
        val sourceSession = base.sessions.first { it.id == sourceBranch.sessionID }
        val legacy = base.copy(
            sessions = base.sessions.map { session ->
                if (session.id == forkBranch.sessionID) session.copy(messages = sourceSession.messages) else session
            },
        )

        val migrated = NovelLegacyForkMigration.migrate(legacy)
        val migratedSession = migrated.sessions.first { it.id == forkBranch.sessionID }
        assertTrue(migratedSession.messages.none { it.candidateID != null })
        assertTrue(
            migratedSession.messages.map { it.id }.toSet()
                .intersect(sourceSession.messages.map { it.id }.toSet())
                .isEmpty(),
        )
        NovelDocumentValidator.validate(migrated)
    }

    private fun fixture() = NovelSwiftCompatibleJson.decodeProjectDocument(
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(
            "novel-v1/projects/full-two-branch.project.json",
        )).readBytes(),
    )

    private fun deterministicLegacyMessageID(
        branchID: NovelBranchId,
        sourceID: NovelMessageId,
    ): NovelMessageId {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(
                "${branchID.rawValue}:legacy-fork-message:${sourceID.rawValue}"
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

package app.amber.feature.novel.domain

import app.amber.feature.novel.model.NovelActiveRunRecord
import app.amber.feature.novel.model.NovelBranchId
import app.amber.feature.novel.model.NovelBranchSyncStatus
import app.amber.feature.novel.model.NovelCandidateId
import app.amber.feature.novel.model.NovelCandidateKind
import app.amber.feature.novel.model.NovelCandidateRecord
import app.amber.feature.novel.model.NovelCandidateStatus
import app.amber.feature.novel.model.NovelChapterId
import app.amber.feature.novel.model.NovelChapterPlanId
import app.amber.feature.novel.model.NovelChapterPlanRecord
import app.amber.feature.novel.model.NovelChapterPlanStatus
import app.amber.feature.novel.model.NovelCheckpointId
import app.amber.feature.novel.model.NovelCollectionSource
import app.amber.feature.novel.model.NovelCollectionTarget
import app.amber.feature.novel.model.NovelCollaborationMode
import app.amber.feature.novel.model.NovelGenerationGranularity
import app.amber.feature.novel.model.NovelGenerationReceiptRecord
import app.amber.feature.novel.model.NovelInjectionMode
import app.amber.feature.novel.model.NovelInjectionReceiptRecord
import app.amber.feature.novel.model.NovelMaterialId
import app.amber.feature.novel.model.NovelMaterialKind
import app.amber.feature.novel.model.NovelMaterialRecord
import app.amber.feature.novel.model.NovelMaterialRevisionId
import app.amber.feature.novel.model.NovelMaterialRevisionRecord
import app.amber.feature.novel.model.NovelMessageId
import app.amber.feature.novel.model.NovelOperationId
import app.amber.feature.novel.model.NovelOutcome
import app.amber.feature.novel.model.NovelProjectCreationMode
import app.amber.feature.novel.model.NovelProjectDocumentV1
import app.amber.feature.novel.model.NovelProjectId
import app.amber.feature.novel.model.NovelProjectModelPolicy
import app.amber.feature.novel.model.NovelReceiptId
import app.amber.feature.novel.model.NovelRunId
import app.amber.feature.novel.model.NovelRunKind
import app.amber.feature.novel.model.NovelRunStatus
import app.amber.feature.novel.model.NovelSessionId
import app.amber.feature.novel.model.NovelSessionMode
import app.amber.feature.novel.model.NovelStateSnapshotId
import app.amber.feature.novel.serialization.NovelSwiftCompatibleJson
import app.amber.feature.novel.serialization.sha256HexOfUtf8
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NovelGhostwriteContractTest {
    @Test
    fun legacyPayloadsDecodeGhostwriteDefaults() {
        val created = createDocument()
        val encoded = NovelSwiftCompatibleJson.json
            .encodeToJsonElement(NovelProjectDocumentV1.serializer(), created)
            .jsonObject
        val legacyProject = encoded.getValue("project").jsonObject.toMutableMap().apply {
            remove("collaborationMode")
            remove("pauseGhostwriteOnBlockingContinuity")
            remove("reviewModelPolicy")
        }
        val legacyStates = encoded.getValue("stateSnapshots").jsonArray.map { state ->
            JsonObject(state.jsonObject.toMutableMap().apply { remove("recentWrittenHighlights") })
        }
        val legacyDocument = JsonObject(encoded.toMutableMap().apply {
            put("project", JsonObject(legacyProject))
            put("stateSnapshots", JsonArray(legacyStates))
            remove("chapterPlans")
            remove("upcomingArcs")
        })

        val decoded = NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelProjectDocumentV1.serializer(),
            legacyDocument,
        )
        assertEquals(NovelCollaborationMode.Cocreation, decoded.project.collaborationMode)
        assertTrue(decoded.project.pauseGhostwriteOnBlockingContinuity)
        assertNull(decoded.project.reviewModelPolicy)
        assertTrue(decoded.chapterPlans.isEmpty())
        assertTrue(decoded.upcomingArcs.isEmpty())
        assertTrue(decoded.stateSnapshots.single().recentWrittenHighlights.isEmpty())

        val candidate = candidate(created, digest = "a".repeat(64), planID = NovelChapterPlanId.generate())
        val legacyCandidate = NovelSwiftCompatibleJson.json
            .encodeToJsonElement(NovelCandidateRecord.serializer(), candidate)
            .jsonObject
            .toMutableMap()
            .apply {
                remove("chapterPlanDigest")
                remove("ghostwritePlanID")
            }
        val decodedCandidate = NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelCandidateRecord.serializer(),
            JsonObject(legacyCandidate),
        )
        assertNull(decodedCandidate.chapterPlanDigest)
        assertNull(decodedCandidate.ghostwritePlanID)

        val run = activeRun(created, candidate.id, "b".repeat(64), NovelChapterPlanId.generate())
        val legacyRun = NovelSwiftCompatibleJson.json
            .encodeToJsonElement(NovelActiveRunRecord.serializer(), run)
            .jsonObject
            .toMutableMap()
            .apply {
                remove("chapterPlanDigest")
                remove("ghostwritePlanID")
            }
        val decodedRun = NovelSwiftCompatibleJson.json.decodeFromJsonElement(
            NovelActiveRunRecord.serializer(),
            JsonObject(legacyRun),
        )
        assertNull(decodedRun.chapterPlanDigest)
        assertNull(decodedRun.ghostwritePlanID)
    }

    @Test
    fun swiftCompatibleRoundTripPreservesGhostwriteProtocolFields() {
        val planned = confirmedPlanDocument()
        val withArc = NovelReducer.upsertUpcomingArc(
            NovelUpsertUpcomingArcCommand(
                context = configContext(planned),
                projectID = planned.project.id,
                branchID = planned.project.mainBranchID,
                beats = listOf("  找到信物  ", "找到信物", "盟约破裂"),
            ),
            planned,
        ).document
        val plan = withArc.confirmedChapterPlan(withArc.project.mainBranchID)!!
        val candidate = candidate(withArc, plan.contentDigest, plan.id)
        val run = activeRun(withArc, candidate.id, plan.contentDigest, plan.id)
        val state = withArc.stateSnapshots.single().copy(
            recentWrittenHighlights = listOf("祭坛下找到信物", "使者带来盟约"),
        )
        val document = withArc.copy(
            project = withArc.project.copy(
                collaborationMode = NovelCollaborationMode.Ghostwrite,
                pauseGhostwriteOnBlockingContinuity = false,
                reviewModelPolicy = NovelProjectModelPolicy.Fixed("review-provider", "review-model"),
            ),
            stateSnapshots = listOf(state),
            candidates = listOf(candidate),
            activeRuns = listOf(run),
        )

        val decoded = NovelSwiftCompatibleJson.decodeProjectDocument(
            NovelSwiftCompatibleJson.encodeProjectDocument(document),
        )
        assertEquals(document.project.collaborationMode, decoded.project.collaborationMode)
        assertEquals(document.project.reviewModelPolicy, decoded.project.reviewModelPolicy)
        assertEquals(document.chapterPlans, decoded.chapterPlans)
        assertEquals(document.upcomingArcs, decoded.upcomingArcs)
        assertEquals(state.recentWrittenHighlights, decoded.stateSnapshots.single().recentWrittenHighlights)
        assertEquals(plan.contentDigest, decoded.candidates.single().chapterPlanDigest)
        assertEquals(plan.id, decoded.candidates.single().ghostwritePlanID)
        assertEquals(plan.contentDigest, decoded.activeRuns.single().chapterPlanDigest)
        assertEquals(plan.id, decoded.activeRuns.single().ghostwritePlanID)
    }

    @Test
    fun configurationReducersAndOutcomesRoundTripThroughSwiftWireFormat() {
        var document = withGhostwriteMaterials(createDocument())
        document = NovelReducer.setCollaborationMode(
            NovelSetCollaborationModeCommand(
                context = configContext(document),
                projectID = document.project.id,
                branchID = document.project.mainBranchID,
                mode = NovelCollaborationMode.Ghostwrite,
            ),
            document,
            NOW,
        ).document
        document = NovelReducer.setPauseGhostwriteOnBlockingContinuity(
            NovelSetPauseGhostwriteOnBlockingContinuityCommand(
                context = configContext(document),
                projectID = document.project.id,
                enabled = false,
            ),
            document,
            NOW.plusSeconds(1),
        ).document
        document = NovelReducer.upsertChapterPlan(
            NovelUpsertChapterPlanCommand(
                context = configContext(document),
                projectID = document.project.id,
                branchID = document.project.mainBranchID,
                planID = NovelChapterPlanId.generate(),
                status = NovelChapterPlanStatus.Confirmed,
                outlinePlacement = "第 1 章",
                goalAndConflict = "夺回信物",
                mustHappen = listOf("夺回信物"),
                mustNotHappen = emptyList(),
                endingHook = "信物碎裂",
                visibleFacts = emptyList(),
            ),
            document,
            NOW.plusSeconds(2),
        ).document
        document = NovelReducer.upsertUpcomingArc(
            NovelUpsertUpcomingArcCommand(
                context = configContext(document),
                projectID = document.project.id,
                branchID = document.project.mainBranchID,
                beats = listOf("寻找铸造者"),
            ),
            document,
            NOW.plusSeconds(3),
        ).document
        document = NovelReducer.clearChapterPlan(
            NovelClearChapterPlanCommand(
                context = configContext(document),
                projectID = document.project.id,
                branchID = document.project.mainBranchID,
            ),
            document,
            NOW.plusSeconds(4),
        ).document
        document = NovelReducer.clearUpcomingArc(
            NovelClearUpcomingArcCommand(
                context = configContext(document),
                projectID = document.project.id,
                branchID = document.project.mainBranchID,
            ),
            document,
            NOW.plusSeconds(5),
        ).document

        val decoded = NovelSwiftCompatibleJson.decodeProjectDocument(
            NovelSwiftCompatibleJson.encodeProjectDocument(document),
        )
        assertEquals(document.appliedOperations, decoded.appliedOperations)
        assertTrue(decoded.appliedOperations.any { it.outcome is NovelOutcome.CollaborationModeChanged })
        assertTrue(
            decoded.appliedOperations.any {
                it.outcome is NovelOutcome.PauseGhostwriteOnBlockingContinuityChanged
            },
        )
        assertTrue(decoded.appliedOperations.any { it.outcome is NovelOutcome.ChapterPlanUpserted })
        assertTrue(decoded.appliedOperations.any { it.outcome is NovelOutcome.ChapterPlanCleared })
        assertTrue(decoded.appliedOperations.any { it.outcome is NovelOutcome.UpcomingArcUpserted })
        assertTrue(decoded.appliedOperations.any { it.outcome is NovelOutcome.UpcomingArcCleared })
        assertEquals(NovelCollaborationMode.Ghostwrite, decoded.project.collaborationMode)
        assertFalse(decoded.project.pauseGhostwriteOnBlockingContinuity)
        assertTrue(decoded.chapterPlans.isEmpty())
        assertTrue(decoded.upcomingArcs.isEmpty())
    }

    @Test
    fun chapterPlanUpcomingArcAndReadinessUseDurableNormalizedContracts() {
        val base = withGhostwriteMaterials(createDocument())
        assertEquals(
            listOf(NovelGhostwriteReadinessIssue.MissingChapterPlan),
            NovelGhostwriteReadiness.issues(base, base.project.mainBranchID, requireChapterPlan = true),
        )

        val planID = NovelChapterPlanId.generate()
        val planned = NovelReducer.upsertChapterPlan(
            NovelUpsertChapterPlanCommand(
                context = configContext(base),
                projectID = base.project.id,
                branchID = base.project.mainBranchID,
                planID = planID,
                status = NovelChapterPlanStatus.Confirmed,
                outlinePlacement = "  第 1 章  ",
                goalAndConflict = "  夺回信物  ",
                mustHappen = listOf("  找到线索  ", "", "逼近祭坛"),
                mustNotHappen = listOf("提前揭晓真凶"),
                endingHook = "  信物碎裂  ",
                visibleFacts = listOf("主角只知道地图残缺"),
            ),
            base,
            NOW,
        ).document
        val plan = planned.confirmedChapterPlan(base.project.mainBranchID)!!
        assertEquals(planID, plan.id)
        assertEquals("第 1 章", plan.outlinePlacement)
        assertEquals(listOf("找到线索", "逼近祭坛"), plan.mustHappen)
        assertEquals(NovelChapterPlanRecord.digest(plan.canonicalDigestPayload()), plan.contentDigest)
        assertTrue(NovelDocumentValidator.isSHA256(plan.contentDigest))
        assertTrue(
            runCatching {
                NovelDocumentValidator.validate(
                    planned.copy(
                        chapterPlans = listOf(plan.copy(contentDigest = "0".repeat(64))),
                    ),
                )
            }.exceptionOrNull() is NovelError.InvalidDocument,
        )
        assertTrue(
            NovelGhostwriteReadiness.issues(
                planned,
                planned.project.mainBranchID,
                requireChapterPlan = true,
            ).isEmpty(),
        )

        val longBeat = "线".repeat(200)
        val withArc = NovelReducer.upsertUpcomingArc(
            NovelUpsertUpcomingArcCommand(
                context = configContext(planned),
                projectID = planned.project.id,
                branchID = planned.project.mainBranchID,
                beats = listOf("  调查祭坛  ", "调查祭坛", longBeat),
            ),
            planned,
            NOW.plusSeconds(1),
        ).document
        assertEquals(2, withArc.upcomingArc(planned.project.mainBranchID)!!.beats.size)
        assertEquals(160, withArc.upcomingArc(planned.project.mainBranchID)!!.beats.last().length)

        val review = NovelReducer.setModelPolicy(
            NovelSetModelPolicyCommand(
                context = configContext(withArc),
                projectID = withArc.project.id,
                policy = NovelProjectModelPolicy.Fixed("review", "model"),
                purpose = NovelModelPolicyPurpose.Review,
            ),
            withArc,
        ).document
        assertEquals(
            NovelProjectModelPolicy.Fixed("review", "model"),
            review.project.reviewModelPolicy,
        )
    }

    @Test
    fun systemAutoCollectFailsClosedAndAcceptsOnlyWholeOwnedCandidate() {
        val planned = confirmedPlanDocument()
        val plan = planned.confirmedChapterPlan(planned.project.mainBranchID)!!
        val validCandidate = candidate(planned, plan.contentDigest, plan.id)
        val document = planned.copy(candidates = listOf(validCandidate))

        assertAutoCollectFails(
            document.copy(
                candidates = listOf(validCandidate.copy(status = NovelCandidateStatus.Interrupted)),
            ),
        )
        assertAutoCollectFails(
            document,
            target = NovelCollectionTarget.AppendToChapter(NovelChapterId.generate()),
        )
        assertAutoCollectFails(document, selectedText = "半章")
        assertAutoCollectFails(
            document.copy(candidates = listOf(validCandidate.copy(ghostwritePlanID = null))),
        )
        assertAutoCollectFails(
            document.copy(
                candidates = listOf(
                    validCandidate.copy(ghostwritePlanID = NovelChapterPlanId.generate()),
                ),
            ),
        )
        assertAutoCollectFails(
            document.copy(candidates = listOf(validCandidate.copy(chapterPlanDigest = "0".repeat(64)))),
        )

        val userCollected = NovelCollectionReducer.collect(
            collectCommand(document, validCandidate.content).copy(
                source = NovelCollectionSource.User,
            ),
            document,
            NOW.plusSeconds(1),
        ).document
        assertEquals(
            sha256HexOfUtf8("${validCandidate.id.rawValue}|${validCandidate.content}|user"),
            userCollected.appliedOperations.last().payloadSHA256,
        )

        val autoCommand = collectCommand(document, validCandidate.content)
        val collectedResult = NovelCollectionReducer.collect(
            autoCommand,
            document,
            NOW.plusSeconds(2),
        )
        val collected = collectedResult.document
        assertEquals(1, collected.chapters.size)
        assertEquals(NovelCandidateStatus.Collected, collected.candidates.single().status)
        assertEquals(NovelBranchSyncStatus.NeedsSync, collected.branches.single().syncStatus)
        assertEquals(
            sha256HexOfUtf8(
                "${validCandidate.id.rawValue}|${validCandidate.content}|systemAutoCollect",
            ),
            collected.appliedOperations.last().payloadSHA256,
        )

        val replay = NovelCollectionReducer.collect(
            autoCommand,
            collected,
            NOW.plusSeconds(3),
        )
        assertEquals(collected, replay.document)
        assertEquals(collectedResult.outcome, replay.outcome)
        assertEquals(1, replay.document.chapters.size)
        assertEquals(1, replay.document.checkpoints.count { it.sourceCandidateID == validCandidate.id })
    }

    @Test
    fun automaticCollectionRejectsPinnedMaterialConfigDrift() {
        val planned = withGhostwriteMaterials(confirmedPlanDocument())
        val plan = planned.confirmedChapterPlan(planned.project.mainBranchID)!!
        val candidate = candidate(planned, plan.contentDigest, plan.id)
        val beforeDrift = planned.copy(candidates = listOf(candidate))
        val material = beforeDrift.materials.first()
        val drifted = NovelReducer.reviseMaterial(
            NovelReviseMaterialCommand(
                context = configContext(beforeDrift),
                projectID = beforeDrift.project.id,
                materialID = material.id,
                revisionID = NovelMaterialRevisionId.generate(),
                kind = material.kind,
                title = "资料",
                content = "后台任务开始后被作者修改",
            ),
            beforeDrift,
            NOW.plusSeconds(2),
        ).document
        val staleCommand = collectCommand(beforeDrift, candidate.content).copy(
            expectedProjectRevision = drifted.project.revision,
            expectedConfigRevision = beforeDrift.project.configRevision,
        )

        val failure = runCatching {
            NovelCollectionReducer.collect(staleCommand, drifted, NOW.plusSeconds(3))
        }.exceptionOrNull()

        assertTrue(failure is NovelError.StaleConfigRevision)
        assertEquals(0, drifted.chapters.size)
        assertEquals(NovelCandidateStatus.Available, drifted.candidates.single().status)
    }

    @Test
    fun clearChapterPlanPinsIdentityAndReplaysBeforeRevisionChecks() {
        val planned = confirmedPlanDocument()
        val oldPlan = planned.confirmedChapterPlan(planned.project.mainBranchID)!!
        val branch = planned.branches.single { it.id == planned.project.mainBranchID }
        val clearOperationID = NovelOperationId.generate()
        val clearCommand = NovelClearChapterPlanCommand(
            context = configContext(planned).copy(
                operationID = clearOperationID,
                expectedBranchHeadRevision = branch.headRevision,
            ),
            projectID = planned.project.id,
            branchID = planned.project.mainBranchID,
            expectedPlanID = oldPlan.id,
            expectedPlanDigest = oldPlan.contentDigest,
        )
        val clearedResult = NovelReducer.clearChapterPlan(clearCommand, planned, NOW.plusSeconds(1))
        val cleared = clearedResult.document

        val replay = NovelReducer.clearChapterPlan(clearCommand, cleared, NOW.plusSeconds(2))
        assertEquals(cleared, replay.document)
        assertEquals(clearedResult.outcome, replay.outcome)

        val conflictingReplay = runCatching {
            NovelReducer.clearChapterPlan(
                clearCommand.copy(expectedPlanDigest = "0".repeat(64)),
                cleared,
                NOW.plusSeconds(3),
            )
        }.exceptionOrNull()
        assertTrue(conflictingReplay is NovelError.IdempotencyConflict)

        val draftWithSameDigest = NovelReducer.upsertChapterPlan(
            NovelUpsertChapterPlanCommand(
                context = configContext(planned),
                projectID = planned.project.id,
                branchID = branch.id,
                planID = oldPlan.id,
                status = NovelChapterPlanStatus.Draft,
                outlinePlacement = oldPlan.outlinePlacement,
                goalAndConflict = oldPlan.goalAndConflict,
                mustHappen = oldPlan.mustHappen,
                mustNotHappen = oldPlan.mustNotHappen,
                endingHook = oldPlan.endingHook,
                visibleFacts = oldPlan.visibleFacts,
            ),
            planned,
            NOW.plusSeconds(3),
        ).document
        assertEquals(oldPlan.contentDigest, draftWithSameDigest.chapterPlan(branch.id)?.contentDigest)
        val configDriftFailure = runCatching {
            NovelReducer.clearChapterPlan(
                clearCommand.copy(
                    context = NovelMutationContext(
                        operationID = NovelOperationId.generate(),
                        expectedProjectRevision = draftWithSameDigest.project.revision,
                        expectedConfigRevision = planned.project.configRevision,
                        expectedBranchHeadRevision = branch.headRevision,
                    ),
                ),
                draftWithSameDigest,
                NOW.plusSeconds(4),
            )
        }.exceptionOrNull()
        assertTrue(configDriftFailure is NovelError.StaleConfigRevision)
        assertEquals(oldPlan.id, draftWithSameDigest.chapterPlan(branch.id)?.id)

        val headDrifted = planned.copy(
            branches = planned.branches.map {
                if (it.id == branch.id) it.copy(headRevision = it.headRevision + 1) else it
            },
        )
        val headDriftFailure = runCatching {
            NovelReducer.clearChapterPlan(
                clearCommand.copy(
                    context = clearCommand.context.copy(operationID = NovelOperationId.generate()),
                ),
                headDrifted,
                NOW.plusSeconds(5),
            )
        }.exceptionOrNull()
        assertTrue(headDriftFailure is NovelError.StaleBranchHeadRevision)
        assertEquals(oldPlan.id, headDrifted.chapterPlan(branch.id)?.id)

        val replacementPlanID = NovelChapterPlanId.generate()
        val replaced = NovelReducer.upsertChapterPlan(
            NovelUpsertChapterPlanCommand(
                context = configContext(cleared),
                projectID = cleared.project.id,
                branchID = cleared.project.mainBranchID,
                planID = replacementPlanID,
                status = NovelChapterPlanStatus.Confirmed,
                outlinePlacement = "第 2 章",
                goalAndConflict = "保护新线索",
                mustHappen = listOf("保护新线索"),
                mustNotHappen = emptyList(),
                endingHook = "线索失踪",
                visibleFacts = emptyList(),
            ),
            cleared,
            NOW.plusSeconds(6),
        ).document
        val staleClear = clearCommand.copy(
            context = configContext(replaced),
        )

        val replacementFailure = runCatching {
            NovelReducer.clearChapterPlan(staleClear, replaced, NOW.plusSeconds(7))
        }.exceptionOrNull()
        assertTrue(replacementFailure is NovelError.InvalidInput)
        assertEquals(replacementPlanID, replaced.confirmedChapterPlan(replaced.project.mainBranchID)?.id)
    }

    @Test
    fun upsertChapterPlanRejectsPinnedBranchHeadDrift() {
        val document = createDocument()
        val branch = document.branches.single()
        val command = NovelUpsertChapterPlanCommand(
            context = configContext(document).copy(expectedBranchHeadRevision = branch.headRevision + 1),
            projectID = document.project.id,
            branchID = branch.id,
            planID = NovelChapterPlanId.generate(),
            status = NovelChapterPlanStatus.Confirmed,
            outlinePlacement = "第 1 章",
            goalAndConflict = "寻找信物",
            mustHappen = listOf("找到线索"),
            mustNotHappen = emptyList(),
            endingHook = "线索断裂",
            visibleFacts = emptyList(),
        )

        val failure = runCatching {
            NovelReducer.upsertChapterPlan(command, document, NOW.plusSeconds(1))
        }.exceptionOrNull()

        assertTrue(failure is NovelError.StaleBranchHeadRevision)
        assertTrue(document.chapterPlans.isEmpty())
    }

    @Test
    fun recentWrittenHighlightsAreDeduplicatedClippedAndBounded() {
        val merged = app.amber.feature.novel.model.NovelStateSnapshotRecord.mergedHighlights(
            prior = listOf("祭坛下找到信物", "使者带来盟约"),
            newEventSummaries = listOf("祭坛下找到信物", "主角夺回信物", ""),
        )
        assertEquals(
            listOf("祭坛下找到信物", "使者带来盟约", "主角夺回信物"),
            merged,
        )

        val overflow = (0 until 29).map { "beat-$it" } + "线".repeat(200)
        val normalized = app.amber.feature.novel.model.NovelStateSnapshotRecord.normalizedHighlights(overflow)
        assertEquals(24, normalized.size)
        assertEquals("beat-6", normalized.first())
        assertEquals(160, normalized.last().length)
    }

    @Test
    fun strictSyncCumulativeStateRejectsUnsupportedMutationAndUnresolvedDeletion() {
        val original = createDocument()
        val branch = original.branches.single()
        val stateIndex = original.stateSnapshots.indexOfFirst { it.id == branch.currentStateSnapshotID }
        val states = original.stateSnapshots.toMutableList()
        states[stateIndex] = states[stateIndex].copy(
            summary = "S0",
            branchOutline = "O0",
            unresolvedEntityNames = listOf("A"),
        )
        val document = original.copy(stateSnapshots = states)
        val unchanged = NovelStateDeltaV1(
            schemaVersion = 1,
            stateSummary = "S0",
            unresolvedEntityNames = listOf("A"),
        )
        NovelManualSyncReducer.validateCumulativeStateDelta(
            document,
            branch.id,
            unchanged,
        )

        assertTrue(
            runCatching {
                NovelManualSyncReducer.validateCumulativeStateDelta(
                    document,
                    branch.id,
                    unchanged.copy(stateSummary = "unsupported rewrite"),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                NovelManualSyncReducer.validateCumulativeStateDelta(
                    document,
                    branch.id,
                    unchanged.copy(
                        events = listOf(
                            NovelStateEventV1(
                                id = "event-b",
                                kind = "fact",
                                summary = "B changed",
                                entityReferences = listOf("B"),
                                evidence = "B changed",
                            ),
                        ),
                        unresolvedEntityNames = emptyList(),
                    ),
                )
            }.isFailure,
        )
    }

    @Test
    fun generationBindsPlanIdentityAndRejectsInvalidGhostwriteOwnership() {
        val planned = confirmedPlanDocument()
        val plan = planned.confirmedChapterPlan(planned.project.mainBranchID)!!
        val request = internalRunRequest(planned, plan.id)
        val started = NovelGenerationReducer.begin(
            request,
            generationArtifacts(request),
            planned,
            NOW.plusSeconds(3),
        ).document
        assertEquals(plan.contentDigest, started.activeRuns.last().chapterPlanDigest)
        assertEquals(plan.id, started.activeRuns.last().ghostwritePlanID)

        val completed = NovelGenerationReducer.complete(
            request.id,
            "完整正文",
            started,
            NOW.plusSeconds(4),
        ).first
        val candidate = completed.candidates.single { it.id == request.candidateID }
        assertEquals(plan.contentDigest, candidate.chapterPlanDigest)
        assertEquals(plan.id, candidate.ghostwritePlanID)

        val continuation = internalRunRequest(planned, plan.id).copy(
            id = NovelRunId.generate(),
            operationID = NovelOperationId.generate(),
            userMessageID = NovelMessageId.generate(),
            assistantMessageID = NovelMessageId.generate(),
            candidateID = NovelCandidateId.generate(),
            granularity = NovelGenerationGranularity.Continuation,
        )
        assertTrue(
            runCatching {
                NovelGenerationReducer.begin(
                    continuation,
                    generationArtifacts(continuation),
                    planned,
                )
            }.exceptionOrNull() is NovelError.InvalidInput,
        )
        val wrongPlan = internalRunRequest(planned, NovelChapterPlanId.generate()).copy(
            id = NovelRunId.generate(),
            operationID = NovelOperationId.generate(),
            userMessageID = NovelMessageId.generate(),
            assistantMessageID = NovelMessageId.generate(),
            candidateID = NovelCandidateId.generate(),
        )
        assertTrue(
            runCatching {
                NovelGenerationReducer.begin(
                    wrongPlan,
                    generationArtifacts(wrongPlan),
                    planned,
                )
            }.exceptionOrNull() is NovelError.InvalidInput,
        )
    }

    private fun assertAutoCollectFails(
        document: NovelProjectDocumentV1,
        selectedText: String = document.candidates.single().content,
        target: NovelCollectionTarget = NovelCollectionTarget.CreateNextChapter(
            NovelChapterId.generate(),
            "第一章",
        ),
    ) {
        val result = runCatching {
            NovelCollectionReducer.collect(
                collectCommand(document, selectedText, target),
                document,
            )
        }
        assertTrue(result.exceptionOrNull() is NovelError.InvalidInput)
        assertFalse(result.isSuccess)
    }

    private fun collectCommand(
        document: NovelProjectDocumentV1,
        selectedText: String,
        target: NovelCollectionTarget = NovelCollectionTarget.CreateNextChapter(
            NovelChapterId.generate(),
            "第一章",
        ),
    ) = NovelCollectCommand(
        projectId = document.project.id,
        branchId = document.project.mainBranchID,
        candidateId = document.candidates.single().id,
        selectedText = selectedText,
        target = target,
        expectedProjectRevision = document.project.revision,
        expectedConfigRevision = document.project.configRevision,
        expectedBranchHeadRevision = document.branches.single().headRevision,
        source = NovelCollectionSource.SystemAutoCollect,
    )

    private fun confirmedPlanDocument(): NovelProjectDocumentV1 {
        val document = createDocument()
        return NovelReducer.upsertChapterPlan(
            NovelUpsertChapterPlanCommand(
                context = configContext(document),
                projectID = document.project.id,
                branchID = document.project.mainBranchID,
                planID = NovelChapterPlanId.generate(),
                status = NovelChapterPlanStatus.Confirmed,
                outlinePlacement = "第 1 章",
                goalAndConflict = "夺回信物",
                mustHappen = listOf("夺回信物"),
                mustNotHappen = emptyList(),
                endingHook = "信物碎裂",
                visibleFacts = emptyList(),
            ),
            document,
            NOW,
        ).document
    }

    private fun withGhostwriteMaterials(document: NovelProjectDocumentV1): NovelProjectDocumentV1 {
        val kinds = listOf(
            NovelMaterialKind.MasterOutline,
            NovelMaterialKind.Character,
            NovelMaterialKind.WritingRequirements,
        )
        val records = kinds.map { kind ->
            val materialID = NovelMaterialId.generate()
            val revisionID = NovelMaterialRevisionId.generate()
            NovelMaterialRecord(materialID, kind, revisionID, listOf(revisionID)) to
                NovelMaterialRevisionRecord(
                    id = revisionID,
                    materialID = materialID,
                    revision = 1,
                    title = "资料",
                    content = "非空内容",
                    tags = emptyList(),
                    injectionMode = NovelInjectionMode.Always,
                    createdAt = NOW,
                    operationID = NovelOperationId.generate(),
                )
        }
        return document.copy(
            materials = records.map { it.first },
            materialRevisions = records.map { it.second },
        )
    }

    private fun candidate(
        document: NovelProjectDocumentV1,
        digest: String,
        planID: NovelChapterPlanId,
    ): NovelCandidateRecord {
        val branch = document.branches.single()
        return NovelCandidateRecord(
            id = NovelCandidateId.generate(),
            kind = NovelCandidateKind.Prose,
            branchID = branch.id,
            sessionID = branch.sessionID,
            sourceMessageID = NovelMessageId.generate(),
            baseCheckpointID = branch.headCheckpointID,
            baseHeadRevision = branch.headRevision,
            status = NovelCandidateStatus.Available,
            content = "完整正文",
            chapterPlanDigest = digest,
            ghostwritePlanID = planID,
            createdAt = NOW,
        )
    }

    private fun activeRun(
        document: NovelProjectDocumentV1,
        candidateID: NovelCandidateId,
        digest: String,
        planID: NovelChapterPlanId,
    ): NovelActiveRunRecord {
        val branch = document.branches.single()
        return NovelActiveRunRecord(
            id = NovelRunId.generate(),
            operationID = NovelOperationId.generate(),
            requestPayloadSHA256 = "c".repeat(64),
            branchID = branch.id,
            sessionID = branch.sessionID,
            kind = NovelRunKind.Prose,
            mode = NovelSessionMode.WriteProse,
            granularity = NovelGenerationGranularity.WholeChapter,
            userMessageID = NovelMessageId.generate(),
            messageID = NovelMessageId.generate(),
            candidateID = candidateID,
            baseCheckpointID = branch.headCheckpointID,
            baseHeadRevision = branch.headRevision,
            status = NovelRunStatus.Completed,
            receiptID = NovelReceiptId.generate(),
            startedAt = NOW,
            terminalAt = NOW,
            chapterPlanDigest = digest,
            ghostwritePlanID = planID,
        )
    }

    private fun internalRunRequest(
        document: NovelProjectDocumentV1,
        planID: NovelChapterPlanId,
    ): NovelInternalRunRequest {
        val branch = document.branches.single()
        return NovelInternalRunRequest(
            id = NovelRunId.generate(),
            operationID = NovelOperationId.generate(),
            projectID = document.project.id,
            branchID = branch.id,
            kind = NovelRunKind.Prose,
            mode = NovelSessionMode.WriteProse,
            granularity = NovelGenerationGranularity.WholeChapter,
            userText = "写第一章",
            userMessageID = NovelMessageId.generate(),
            assistantMessageID = NovelMessageId.generate(),
            candidateID = NovelCandidateId.generate(),
            generationReceiptID = NovelReceiptId.generate(),
            injectionReceiptID = NovelReceiptId.generate(),
            sourceChapterVersionID = null,
            ghostwritePlanID = planID,
            expectedProjectRevision = document.project.revision,
            expectedConfigRevision = document.project.configRevision,
            expectedBranchHeadRevision = branch.headRevision,
            requestPayloadSHA256 = sha256HexOfUtf8("run-${planID.rawValue}"),
        )
    }

    private fun generationArtifacts(request: NovelInternalRunRequest): NovelGenerationStartArtifacts {
        val injection = NovelInjectionReceiptRecord(
            id = request.injectionReceiptID,
            runID = request.id,
            projectID = request.projectID,
            branchID = request.branchID,
            promptVersion = "test",
            providerID = "provider",
            ownerProviderID = "provider",
            modelID = "model",
            wireModelID = "model",
            requestedInputBudgetTokens = 1,
            maxEstimatedInputTokens = 1,
            estimatedInputTokens = 1,
            canonicalInputSHA256 = "d".repeat(64),
            createdAt = NOW,
        )
        val generation = NovelGenerationReceiptRecord(
            id = request.generationReceiptID,
            runID = request.id,
            providerID = "provider",
            ownerProviderID = "provider",
            modelID = "model",
            wireModelID = "model",
            promptVersion = "test",
            injectionReceiptID = injection.id,
            requestSHA256 = "e".repeat(64),
            createdAt = NOW,
        )
        return NovelGenerationStartArtifacts(injection, generation)
    }

    private fun configContext(document: NovelProjectDocumentV1) = NovelMutationContext(
        operationID = NovelOperationId.generate(),
        expectedProjectRevision = document.project.revision,
        expectedConfigRevision = document.project.configRevision,
    )

    private fun createDocument(): NovelProjectDocumentV1 = NovelReducer.createProject(
        NovelCreateProjectCommand(
            context = NovelMutationContext(NovelOperationId.generate()),
            projectID = NovelProjectId.generate(),
            branchID = NovelBranchId.generate(),
            sessionID = NovelSessionId.generate(),
            initialStateSnapshotID = NovelStateSnapshotId.generate(),
            initialCheckpointID = NovelCheckpointId.generate(),
            name = "Novel",
            creationMode = NovelProjectCreationMode.Blank,
        ),
        NOW,
    ).document

    companion object {
        private val NOW: Instant = Instant.parse("2026-08-09T00:00:00Z")
    }
}

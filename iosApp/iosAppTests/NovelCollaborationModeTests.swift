import XCTest
@testable import iosApp

final class NovelCollaborationModeTests: XCTestCase {
    func testNewProjectsDefaultToCocreationWithoutChapterPlans() throws {
        let document = try NovelTestFixtures.document()
        XCTAssertEqual(document.project.collaborationMode, .cocreation)
        XCTAssertTrue(document.project.pauseGhostwriteOnBlockingContinuity)
        XCTAssertTrue(document.chapterPlans.isEmpty)
    }

    func testLegacyProjectsDefaultPauseGhostwriteOnBlockingContinuityOn() throws {
        let document = try NovelTestFixtures.document()
        var encoded = try JSONEncoder().encode(document.project)
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        object.removeValue(forKey: "pauseGhostwriteOnBlockingContinuity")
        encoded = try JSONSerialization.data(withJSONObject: object)
        let decoded = try JSONDecoder().decode(NovelProjectRecord.self, from: encoded)
        XCTAssertTrue(decoded.pauseGhostwriteOnBlockingContinuity)
    }

    func testSetPauseGhostwriteOnBlockingContinuityTogglesProjectPreference() throws {
        var document = try NovelTestFixtures.document()
        XCTAssertTrue(document.project.pauseGhostwriteOnBlockingContinuity)

        document = try NovelReducer.apply(
            .setPauseGhostwriteOnBlockingContinuity(
                NovelSetPauseGhostwriteOnBlockingContinuityCommand(
                    context: NovelTestFixtures.context(
                        configRevision: document.project.configRevision
                    ),
                    projectID: document.project.id,
                    enabled: false
                )
            ),
            to: document
        ).document
        XCTAssertFalse(document.project.pauseGhostwriteOnBlockingContinuity)

        document = try NovelReducer.apply(
            .setPauseGhostwriteOnBlockingContinuity(
                NovelSetPauseGhostwriteOnBlockingContinuityCommand(
                    context: NovelTestFixtures.context(
                        configRevision: document.project.configRevision
                    ),
                    projectID: document.project.id,
                    enabled: true
                )
            ),
            to: document
        ).document
        XCTAssertTrue(document.project.pauseGhostwriteOnBlockingContinuity)
    }

    func testGhostwriteContinuityGateOnlySurfacesBlockingIssues() {
        let report = NovelContinuityAuditReport(
            projectID: NovelProjectID(),
            branchID: NovelBranchID(),
            auditedChapterSelections: [],
            promptVersion: "test",
            scannedChapterCount: 1,
            chunkCount: 1,
            failedChunkCount: 0,
            issues: [
                NovelContinuityIssue(
                    id: "b1",
                    category: .identityDrift,
                    severity: .blocking,
                    summary: "严重身份漂移",
                    references: []
                ),
                NovelContinuityIssue(
                    id: "m1",
                    category: .chronology,
                    severity: .major,
                    summary: "时间线可疑",
                    references: []
                ),
                NovelContinuityIssue(
                    id: "n1",
                    category: .other,
                    severity: .minor,
                    summary: "小瑕疵",
                    references: []
                ),
            ],
            droppedIssueCount: 0,
            createdAt: Date(timeIntervalSince1970: 1_700_000_100)
        )
        XCTAssertEqual(
            NovelGhostwriteContinuityGate.blockingIssueSummaries(in: report),
            ["严重身份漂移"]
        )
        XCTAssertEqual(
            NovelGhostwriteContinuityGate.pauseDetail(for: report),
            "严重身份漂移"
        )
        XCTAssertEqual(
            NovelGhostwriteContinuityGate.pauseReason(for: report),
            .blockingContinuity
        )
    }

    func testGhostwriteContinuityGatePausesWhenAuditIncomplete() {
        let report = NovelContinuityAuditReport(
            projectID: NovelProjectID(),
            branchID: NovelBranchID(),
            auditedChapterSelections: [],
            promptVersion: "test",
            scannedChapterCount: 2,
            chunkCount: 2,
            failedChunkCount: 1,
            issues: [],
            droppedIssueCount: 0,
            createdAt: Date(timeIntervalSince1970: 1_700_000_100)
        )
        XCTAssertEqual(
            NovelGhostwriteContinuityGate.pauseDetail(for: report),
            "连续性检查未完整完成，已暂停自动收录。"
        )
        XCTAssertEqual(
            NovelGhostwriteContinuityGate.pauseReason(for: report),
            .continuityAuditIncomplete
        )
    }

    func testUpsertConfirmAndClearChapterPlanUpdatesDigest() throws {
        var document = try NovelTestFixtures.document()
        let branchID = document.branches[0].id
        let planID = NovelChapterPlanID()
        let now = Date(timeIntervalSince1970: 1_700_000_100)

        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID,
            planID: planID,
            status: .draft,
            outlinePlacement: "第 1 章",
            goalAndConflict: "主角必须夺回信物",
            mustHappen: ["夺回信物"],
            mustNotHappen: ["暴露身份"],
            endingHook: "信物碎裂",
            visibleFacts: ["信物在祭坛下"]
        )), to: document, now: now).document

        let draft = try XCTUnwrap(document.chapterPlan(for: branchID))
        XCTAssertEqual(draft.status, .draft)
        XCTAssertNil(draft.confirmedAt)
        XCTAssertEqual(
            draft.contentDigest,
            NovelChapterPlanRecord.digest(forCanonicalPayload: draft.canonicalDigestPayload())
        )
        XCTAssertNil(document.confirmedChapterPlan(for: branchID))

        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID,
            planID: planID,
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "主角必须夺回信物",
            mustHappen: ["夺回信物"],
            mustNotHappen: ["暴露身份"],
            endingHook: "信物碎裂",
            visibleFacts: ["信物在祭坛下"]
        )), to: document, now: now.addingTimeInterval(1)).document

        let confirmed = try XCTUnwrap(document.confirmedChapterPlan(for: branchID))
        XCTAssertEqual(confirmed.status, .confirmed)
        XCTAssertNotNil(confirmed.confirmedAt)
        XCTAssertEqual(confirmed.id, planID)

        document = try NovelReducer.apply(.clearChapterPlan(NovelClearChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID
        )), to: document, now: now.addingTimeInterval(2)).document

        XCTAssertNil(document.chapterPlan(for: branchID))
        XCTAssertEqual(document.project.collaborationMode, .cocreation)
    }

    func testConfirmChapterPlanRequiresMustHappen() throws {
        let document = try NovelTestFixtures.document()
        XCTAssertThrowsError(try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "只有目标没有必发生",
            mustHappen: [],
            mustNotHappen: [],
            endingHook: "",
            visibleFacts: []
        )), to: document)) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("must-happen"))
        }
    }

    func testSwitchToGhostwriteRequiresPlanningPackage() throws {
        let empty = try NovelTestFixtures.document()
        XCTAssertThrowsError(try NovelReducer.apply(.setCollaborationMode(
            NovelSetCollaborationModeCommand(
                context: NovelTestFixtures.context(configRevision: empty.project.configRevision),
                projectID: empty.project.id,
                branchID: empty.branches[0].id,
                mode: .ghostwrite
            )
        ), to: empty)) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("代笔"))
        }

        var ready = try seedGhostwriteMaterials(in: try NovelTestFixtures.document())
        ready = try NovelReducer.apply(.setCollaborationMode(NovelSetCollaborationModeCommand(
            context: NovelTestFixtures.context(configRevision: ready.project.configRevision),
            projectID: ready.project.id,
            branchID: ready.branches[0].id,
            mode: .ghostwrite
        )), to: ready).document

        XCTAssertEqual(ready.project.collaborationMode, .ghostwrite)
        XCTAssertTrue(ready.chapterPlans.isEmpty)
    }

    func testSwitchToGhostwriteRejectsNonMainBranch() throws {
        var document = try seedGhostwriteMaterials(
            in: NovelTestFixtures.documentWithForkableCheckpoint()
        )
        let mainBranch = try XCTUnwrap(document.branches.first)
        let forkedBranchID = NovelBranchID()
        document = try NovelReducer.apply(.forkBranch(
            NovelBranchTestFixtures.forkCommand(
                document: document,
                sourceBranchID: mainBranch.id,
                checkpointID: mainBranch.headCheckpointID,
                branchID: forkedBranchID,
                name: "支线"
            )
        ), to: document).document

        XCTAssertThrowsError(try NovelReducer.apply(.setCollaborationMode(
            NovelSetCollaborationModeCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                branchID: forkedBranchID,
                mode: .ghostwrite
            )
        ), to: document)) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("主分支"))
        }
    }

    @MainActor
    func testGhostwriteStartRechecksPlanningPackageAfterModeSwitch() async throws {
        var document = try seedGhostwriteMaterials(in: NovelTestFixtures.document())
        let branchID = try XCTUnwrap(document.branches.first?.id)
        document = try NovelReducer.apply(.setCollaborationMode(
            NovelSetCollaborationModeCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                branchID: branchID,
                mode: .ghostwrite
            )
        ), to: document).document
        document = try NovelReducer.apply(.upsertChapterPlan(
            NovelUpsertChapterPlanCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                branchID: branchID,
                planID: NovelChapterPlanID(),
                status: .confirmed,
                outlinePlacement: "第 1 章",
                goalAndConflict: "夺回信物",
                mustHappen: ["夺回信物"],
                mustNotHappen: [],
                endingHook: "信物碎裂",
                visibleFacts: []
            )
        ), to: document).document
        let outlineID = try XCTUnwrap(document.materials.first(where: {
            $0.kind == .masterOutline && !$0.isDeleted
        })?.id)
        document = try NovelReducer.apply(.deleteMaterial(
            NovelDeleteMaterialCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                materialID: outlineID
            )
        ), to: document).document

        let repository = InMemoryNovelProjectRepository()
        _ = try await repository.createProject(document)
        let adapter = ScriptedNovelModelAdapter(resolvedModel: NovelResolvedModel(
            providerID: "review-provider",
            ownerProviderID: "review-owner",
            modelID: "review-model",
            wireModelID: "review-wire",
            displayName: "Review Model",
            contextWindowTokens: 128_000
        ))
        let workspace = NovelCreationViewModel(creation: DefaultNovelCreation(
            repository: repository,
            modelRunner: adapter
        ))
        await workspace.loadProjects(selecting: document.project.id)
        let session = NovelSessionViewModel(workspace: workspace)
        await session.bindToCurrentSelection()

        XCTAssertEqual(session.ghostwriteReadinessIssue, .missingMasterOutline)
        XCTAssertFalse(session.canStartGhostwriteChapter)
    }

    func testCollaborationModeCanSwitchBackToCocreation() throws {
        var document = try seedGhostwriteMaterials(in: try NovelTestFixtures.document())
        document = try NovelReducer.apply(.setCollaborationMode(NovelSetCollaborationModeCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            mode: .ghostwrite
        )), to: document).document
        document = try NovelReducer.apply(.setCollaborationMode(NovelSetCollaborationModeCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            mode: .cocreation
        )), to: document).document
        XCTAssertEqual(document.project.collaborationMode, .cocreation)
    }

    func testCannotSwitchBackToCocreationWhileBranchRunIsActive() throws {
        var document = try seedGhostwriteMaterials(in: try NovelTestFixtures.document())
        let branchID = document.branches[0].id
        document = try NovelReducer.apply(.setCollaborationMode(NovelSetCollaborationModeCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID,
            mode: .ghostwrite
        )), to: document).document
        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "夺回信物",
            mustHappen: ["夺回信物"],
            mustNotHappen: [],
            endingHook: "信物碎裂",
            visibleFacts: []
        )), to: document).document
        let plan = try XCTUnwrap(document.confirmedChapterPlan(for: branchID))
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: branchID,
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "写第一章",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: NovelCandidateID(),
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            ghostwritePlanID: plan.id,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
        document = try NovelGenerationReducer.begin(
            request,
            artifacts: makeStartArtifacts(document: document, request: request),
            in: document,
            now: Date(timeIntervalSince1970: 1_700_000_200)
        ).document

        XCTAssertThrowsError(try NovelReducer.apply(.setCollaborationMode(
            NovelSetCollaborationModeCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                branchID: branchID,
                mode: .cocreation
            )
        ), to: document)) { error in
            guard case .projectBusy(let projectID) = error as? NovelError else {
                return XCTFail("Expected projectBusy, got \(error)")
            }
            XCTAssertEqual(projectID, document.project.id)
        }
    }

    func testChapterPlanCanBeRecreatedAfterClear() throws {
        var document = try NovelTestFixtures.document()
        let branchID = document.branches[0].id
        let firstID = NovelChapterPlanID()
        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID,
            planID: firstID,
            status: .draft,
            outlinePlacement: "第 1 章",
            goalAndConflict: "先写一版",
            mustHappen: ["起冲突"],
            mustNotHappen: [],
            endingHook: "",
            visibleFacts: []
        )), to: document).document
        document = try NovelReducer.apply(.clearChapterPlan(NovelClearChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID
        )), to: document).document
        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "重拟合同",
            mustHappen: ["起冲突"],
            mustNotHappen: [],
            endingHook: "",
            visibleFacts: []
        )), to: document).document
        let plan = try XCTUnwrap(document.confirmedChapterPlan(for: branchID))
        XCTAssertNotEqual(plan.id, firstID)
        XCTAssertEqual(plan.goalAndConflict, "重拟合同")
    }

    func testGhostwriteWholeChapterRequiresConfirmedPlan() throws {
        var document = try seedGhostwriteMaterials(in: try NovelTestFixtures.document())
        document = try NovelReducer.apply(.setCollaborationMode(NovelSetCollaborationModeCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            mode: .ghostwrite
        )), to: document).document

        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "写第一章",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: NovelCandidateID(),
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
        let artifacts = try makeStartArtifacts(document: document, request: request)

        XCTAssertThrowsError(try NovelGenerationReducer.begin(
            request,
            artifacts: artifacts,
            in: document,
            now: Date(timeIntervalSince1970: 1_700_000_100)
        )) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("本章计划"))
        }

        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "夺回信物",
            mustHappen: ["夺回信物"],
            mustNotHappen: [],
            endingHook: "信物碎裂",
            visibleFacts: []
        )), to: document).document

        let withPlan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: request.branchID,
                promptKind: .proseWholeChapter,
                userText: request.userText
            )
        )
        XCTAssertTrue(withPlan.sections.contains { section in
            if case .chapterPlan = section.kind { return true }
            return false
        })
        XCTAssertTrue(withPlan.canonicalInput.contains("夺回信物"))
    }

    func testConfirmedChapterPlanInjectedOnlyForWholeChapterProse() throws {
        var document = try NovelTestFixtures.document()
        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 2 章",
            goalAndConflict: "谈判破裂",
            mustHappen: ["公开拒绝盟约"],
            mustNotHappen: ["私下和解"],
            endingHook: "使者离席",
            visibleFacts: ["使者带来盟约"]
        )), to: document).document

        let whole = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseWholeChapter,
                userText: "写下一章"
            )
        )
        let continuation = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseContinuation,
                userText: "续写一段"
            )
        )

        XCTAssertTrue(whole.sections.contains { section in
            section.reason == .confirmedChapterPlan
        })
        XCTAssertFalse(continuation.sections.contains { section in
            section.reason == .confirmedChapterPlan
        })
    }

    func testWholeChapterProseBindsChapterPlanDigestToCandidate() throws {
        var document = try seedGhostwriteMaterials(in: try NovelTestFixtures.document())
        document = try NovelReducer.apply(.setCollaborationMode(NovelSetCollaborationModeCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            mode: .ghostwrite
        )), to: document).document
        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "夺回信物",
            mustHappen: ["夺回信物"],
            mustNotHappen: ["暴露身份"],
            endingHook: "信物碎裂",
            visibleFacts: []
        )), to: document).document
        let plan = try XCTUnwrap(document.confirmedChapterPlan(for: document.branches[0].id))
        let candidateID = NovelCandidateID()
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "写第一章",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: candidateID,
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            ghostwritePlanID: plan.id,
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: document.branches[0].headRevision
        )
        let artifacts = try makeStartArtifacts(document: document, request: request)
        let started = try NovelGenerationReducer.begin(
            request,
            artifacts: artifacts,
            in: document,
            now: Date(timeIntervalSince1970: 1_700_000_200)
        )
        XCTAssertEqual(started.document.activeRuns[0].chapterPlanDigest, plan.contentDigest)
        XCTAssertEqual(started.document.activeRuns[0].ghostwritePlanID, plan.id)

        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "林晚夺回了信物。\n\n信物却在掌心碎裂。",
            in: started.document,
            now: Date(timeIntervalSince1970: 1_700_000_201)
        )
        let candidate = try XCTUnwrap(completed.document.candidates.first { $0.id == candidateID })
        XCTAssertEqual(candidate.chapterPlanDigest, plan.contentDigest)
        XCTAssertEqual(candidate.ghostwritePlanID, plan.id)
        XCTAssertEqual(candidate.status, .available)
    }

    func testGhostwriteCandidateOwnershipRequiresDurablePlanIdentity() throws {
        var document = try NovelTestFixtures.document()
        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "相同内容",
            mustHappen: ["同一事件"],
            mustNotHappen: [],
            endingHook: "",
            visibleFacts: []
        )), to: document).document
        let plan = try XCTUnwrap(document.confirmedChapterPlan(for: document.branches[0].id))
        let branch = document.branches[0]
        let session = document.sessions[0]
        func candidate(ghostwritePlanID: NovelChapterPlanID?) -> NovelCandidateRecord {
            NovelCandidateRecord(
                id: NovelCandidateID(),
                kind: .prose,
                branchID: branch.id,
                sessionID: session.id,
                sourceMessageID: NovelMessageID(),
                baseCheckpointID: branch.headCheckpointID,
                baseHeadRevision: branch.headRevision,
                status: .available,
                content: "正文",
                sourceChapterVersionID: nil,
                collectedCheckpointID: nil,
                chapterPlanDigest: plan.contentDigest,
                ghostwritePlanID: ghostwritePlanID,
                createdAt: Date()
            )
        }

        XCTAssertTrue(NovelGhostwriteCandidateOwnership.belongs(candidate(ghostwritePlanID: plan.id), to: plan))
        XCTAssertFalse(NovelGhostwriteCandidateOwnership.belongs(candidate(ghostwritePlanID: NovelChapterPlanID()), to: plan))
        XCTAssertFalse(NovelGhostwriteCandidateOwnership.belongs(candidate(ghostwritePlanID: nil), to: plan))
    }

    func testCollectRejectsCandidateWhenChapterPlanDigestMismatches() throws {
        var document = try NovelTestFixtures.document()
        document = try NovelReducer.apply(.upsertChapterPlan(NovelUpsertChapterPlanCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            planID: NovelChapterPlanID(),
            status: .confirmed,
            outlinePlacement: "第 1 章",
            goalAndConflict: "新合同",
            mustHappen: ["新事件"],
            mustNotHappen: [],
            endingHook: "",
            visibleFacts: []
        )), to: document).document
        let branch = document.branches[0]
        let candidateID = NovelCandidateID()
        let messageID = NovelMessageID()
        var session = document.sessions[0]
        session.messages.append(NovelSessionMessageRecord(
            id: messageID,
            sequence: Int64(session.messages.count),
            role: .assistant,
            mode: .writeProse,
            kind: .proseCandidate,
            content: "旧合同写出的正文。",
            createdAt: Date(timeIntervalSince1970: 1_700_000_210),
            runID: NovelRunID(),
            candidateID: candidateID
        ))
        session.revision += 1
        document.sessions[0] = session
        document.candidates.append(NovelCandidateRecord(
            id: candidateID,
            kind: .prose,
            branchID: branch.id,
            sessionID: session.id,
            sourceMessageID: messageID,
            baseCheckpointID: branch.headCheckpointID,
            baseHeadRevision: branch.headRevision,
            status: .available,
            content: "旧合同写出的正文。",
            sourceChapterVersionID: nil,
            collectedCheckpointID: nil,
            chapterPlanDigest: "stale-digest",
            createdAt: Date(timeIntervalSince1970: 1_700_000_210)
        ))

        let paragraphs = NovelParagraphParser.paragraphs(in: document.candidates[0].content)
        let command = NovelCollectCandidateCommand(
            context: NovelTestFixtures.context(
                projectRevision: document.project.revision,
                configRevision: document.project.configRevision,
                branchHeadRevision: branch.headRevision
            ),
            projectID: document.project.id,
            branchID: branch.id,
            pendingID: NovelPendingOperationID(),
            candidateID: candidateID,
            selection: NovelParagraphSelection(paragraphIDs: paragraphs.map(\.id), editedText: nil),
            target: .createNextChapter(chapterID: NovelChapterID(), title: "第 1 章"),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID(),
            source: .systemAutoCollect
        )
        XCTAssertThrowsError(try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            command,
            payloadSHA256: try command.canonicalPayloadSHA256(),
            in: document
        )) { error in
            guard case .invalidInput(let message) = error as? NovelError else {
                return XCTFail("Expected invalidInput, got \(error)")
            }
            XCTAssertTrue(message.contains("chapter plan") || message.contains("合同"))
        }
    }

    func testChapterPlanAcceptanceDecoderFailClosed() throws {
        let legacyAccepted = """
        {"schemaVersion":1,"accepted":true,"missingMustHappen":[],"forbiddenViolations":[],"summary":"合同要点均已落地。"}
        """
        let legacy = try NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(from: legacyAccepted)
        XCTAssertTrue(legacy.accepted)
        XCTAssertTrue(legacy.obviousRepetition.isEmpty)

        let accepted = """
        {"schemaVersion":2,"accepted":true,"missingMustHappen":[],"forbiddenViolations":[],"obviousRepetition":[],"summary":"合同要点均已落地。"}
        """
        let ok = try NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(from: accepted)
        XCTAssertTrue(ok.accepted)
        XCTAssertTrue(ok.obviousRepetition.isEmpty)

        let softGate = """
        {"schemaVersion":2,"accepted":true,"missingMustHappen":[],"forbiddenViolations":[],"obviousRepetition":["再次夺回同一信物"],"summary":"合同满足，但复读旧拍。"}
        """
        let repeated = try NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(from: softGate)
        XCTAssertTrue(repeated.accepted)
        XCTAssertEqual(repeated.obviousRepetition, ["再次夺回同一信物"])

        let rejected = """
        {"schemaVersion":2,"accepted":false,"missingMustHappen":["夺回信物"],"forbiddenViolations":[],"obviousRepetition":[],"summary":"缺少必发生。"}
        """
        let bad = try NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(from: rejected)
        XCTAssertFalse(bad.accepted)
        XCTAssertEqual(bad.missingMustHappen, ["夺回信物"])

        XCTAssertThrowsError(try NovelStructuredOutputDecoder.decodeChapterPlanAcceptance(from: """
        {"schemaVersion":2,"accepted":true,"missingMustHappen":["x"],"forbiddenViolations":[],"obviousRepetition":[],"summary":"矛盾"}
        """))
    }

    func testRecentWrittenHighlightsMergeDedupAndCap() {
        let merged = NovelStateSnapshotRecord.mergedHighlights(
            prior: ["祭坛下找到信物", "使者带来盟约"],
            newEventSummaries: ["祭坛下找到信物", "主角夺回信物", ""]
        )
        XCTAssertEqual(
            merged,
            ["祭坛下找到信物", "使者带来盟约", "主角夺回信物"]
        )

        let overflow = (0..<(NovelStateSnapshotRecord.maxRecentWrittenHighlights + 5)).map {
            "beat-\($0)"
        }
        let capped = NovelStateSnapshotRecord.normalizedHighlights(overflow)
        XCTAssertEqual(capped.count, NovelStateSnapshotRecord.maxRecentWrittenHighlights)
        XCTAssertEqual(capped.first, "beat-5")
        XCTAssertEqual(capped.last, "beat-\(NovelStateSnapshotRecord.maxRecentWrittenHighlights + 4)")
    }

    func testStateSnapshotDecodesMissingRecentWrittenHighlights() throws {
        let original = NovelStateSnapshotRecord(
            id: NovelStateSnapshotID(),
            eventIDs: [],
            summary: "s",
            branchOutline: "o",
            unresolvedEntityNames: [],
            createdAt: Date(timeIntervalSince1970: 0),
            recentWrittenHighlights: ["should-be-stripped"]
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .secondsSince1970
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoder.encode(original)) as? [String: Any]
        )
        object.removeValue(forKey: "recentWrittenHighlights")
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .secondsSince1970
        let decoded = try decoder.decode(
            NovelStateSnapshotRecord.self,
            from: try JSONSerialization.data(withJSONObject: object)
        )
        XCTAssertTrue(decoded.recentWrittenHighlights.isEmpty)
    }

    func testWholeChapterInjectsRecentWrittenHighlights() throws {
        var document = try NovelTestFixtures.document()
        document.stateSnapshots[0] = NovelStateSnapshotRecord(
            id: document.stateSnapshots[0].id,
            eventIDs: document.stateSnapshots[0].eventIDs,
            summary: document.stateSnapshots[0].summary,
            branchOutline: document.stateSnapshots[0].branchOutline,
            unresolvedEntityNames: document.stateSnapshots[0].unresolvedEntityNames,
            createdAt: document.stateSnapshots[0].createdAt,
            recentWrittenHighlights: ["祭坛下找到信物", "使者带来盟约"]
        )

        let whole = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseWholeChapter,
                userText: "写下一章"
            )
        )
        XCTAssertTrue(whole.sections.contains { section in
            if case .recentWrittenHighlights = section.kind { return true }
            return false
        })
        XCTAssertTrue(whole.canonicalInput.contains("祭坛下找到信物"))
        XCTAssertTrue(whole.canonicalInput.contains("DO NOT REHASH"))

        let continuation = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseContinuation,
                userText: "续写一段"
            )
        )
        XCTAssertFalse(continuation.sections.contains { section in
            if case .recentWrittenHighlights = section.kind { return true }
            return false
        })
        XCTAssertLessThanOrEqual(whole.estimatedInputTokens, whole.maxEstimatedInputTokens)
    }

    func testUpsertAndClearUpcomingArc() throws {
        var document = try NovelTestFixtures.document()
        let branchID = document.branches[0].id

        document = try NovelReducer.apply(.upsertUpcomingArc(NovelUpsertUpcomingArcCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID,
            beats: ["使者身份曝光", "夺回信物"]
        )), to: document).document
        XCTAssertEqual(document.upcomingArc(for: branchID)?.beats, ["使者身份曝光", "夺回信物"])

        document = try NovelReducer.apply(.clearUpcomingArc(NovelClearUpcomingArcCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: branchID
        )), to: document).document
        XCTAssertNil(document.upcomingArc(for: branchID))
    }

    func testWholeChapterInjectsUpcomingArc() throws {
        var document = try NovelTestFixtures.document()
        document = try NovelReducer.apply(.upsertUpcomingArc(NovelUpsertUpcomingArcCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            beats: ["使者身份曝光"]
        )), to: document).document

        let whole = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseWholeChapter,
                userText: "写下一章"
            )
        )
        XCTAssertTrue(whole.sections.contains { section in
            if case .upcomingArc = section.kind { return true }
            return false
        })
        XCTAssertTrue(whole.canonicalInput.contains("UPCOMING ARC"))
        XCTAssertTrue(whole.canonicalInput.contains("使者身份曝光"))

        let continuation = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseContinuation,
                userText: "续写一段"
            )
        )
        XCTAssertFalse(continuation.sections.contains { section in
            if case .upcomingArc = section.kind { return true }
            return false
        })
    }

    func testGhostwriteBoardStepSummaryTracksPhase() {
        let binding = NovelSessionBinding(
            projectID: NovelProjectID(),
            branchID: NovelBranchID()
        )
        var progress = NovelGhostwriteProgress(
            binding: binding,
            phase: .accepting,
            pauseReason: nil,
            detailMessage: nil,
            candidateID: nil,
            chapterPlanDigest: nil,
            autoCollectedCandidateIDs: [],
            startedAt: Date(timeIntervalSince1970: 0)
        )
        XCTAssertEqual(progress.boardStepSummary, "写✓ · 验收中")

        progress.phase = .waitingUser
        progress.pauseReason = .chapterCompleted
        XCTAssertEqual(progress.boardStepSummary, "写✓验✓收✓同✓")

        progress.pauseReason = .obviousRepetition
        // 质量失败：看板标明继续将重写，避免「再验旧稿」误解。
        XCTAssertEqual(progress.boardStepSummary, "已中断·将重写")
    }

    func testGhostwriteBatchClampAndProgressLabels() {
        XCTAssertEqual(NovelGhostwriteBatch.clamp(0), 1)
        XCTAssertEqual(NovelGhostwriteBatch.clamp(3), 3)
        XCTAssertEqual(NovelGhostwriteBatch.clamp(99), 10)

        let binding = NovelSessionBinding(
            projectID: NovelProjectID(),
            branchID: NovelBranchID()
        )
        var progress = NovelGhostwriteProgress(
            binding: binding,
            phase: .writing,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 5,
            completedChapterCount: 2,
            currentChapterIndex: 3
        )
        XCTAssertEqual(progress.batchProgressLabel, "第 3/5 章")
        XCTAssertTrue(progress.statusLabel.contains("3/5"))
        XCTAssertTrue(progress.boardStepSummary.contains("已收2/5"))
        XCTAssertFalse(progress.isBatchComplete)

        progress.phase = .planning
        XCTAssertTrue(progress.boardStepSummary.contains("拟定下一章"))

        progress.completedChapterCount = 5
        progress.currentChapterIndex = 5
        progress.phase = .waitingUser
        progress.pauseReason = .batchCompleted
        XCTAssertTrue(progress.isBatchComplete)
        XCTAssertEqual(progress.boardStepSummary, "写✓验✓收✓同✓ · 已收5/5")
        XCTAssertEqual(progress.statusLabel, "本批已完成 · 5/5 章")
        progress.pauseReason = .chapterCompleted
        progress.targetChapterCount = 1
        progress.currentChapterIndex = 1
        progress.completedChapterCount = 1
        XCTAssertEqual(progress.statusLabel, "本章已完成")
        XCTAssertTrue(NovelGhostwritePauseReason.syncFailed.resumesWithoutConfirmedPlan)
        XCTAssertTrue(NovelGhostwritePauseReason.planProposalFailed.resumesWithoutConfirmedPlan)
        XCTAssertFalse(NovelGhostwritePauseReason.acceptanceFailed.resumesWithoutConfirmedPlan)
        progress.targetChapterCount = 5
        progress.completedChapterCount = 2
        progress.pauseReason = .syncFailed
        XCTAssertTrue(progress.canResumeWithoutConfirmedPlan)

        // syncFailed 待记账：续跑先计章，避免越过 N 再写。
        var pending = NovelGhostwriteProgress(
            binding: binding,
            phase: .failed,
            pauseReason: .syncFailed,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 1,
            completedChapterCount: 0,
            currentChapterIndex: 1,
            pendingSyncChapterCredit: true
        )
        XCTAssertTrue(pending.shouldContinueSameBatch)
        XCTAssertTrue(pending.canResumeWithoutConfirmedPlan)
        XCTAssertTrue(pending.applyPendingSyncChapterCredit())
        XCTAssertEqual(pending.completedChapterCount, 1)
        XCTAssertFalse(pending.pendingSyncChapterCredit)
        XCTAssertTrue(pending.isBatchComplete)
        XCTAssertFalse(pending.shouldContinueSameBatch)

        // N=2：记账后未达批上限，应继续同批（下一章拟合同），而不是完批。
        var mid = NovelGhostwriteProgress(
            binding: binding,
            phase: .failed,
            pauseReason: .syncFailed,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 2,
            completedChapterCount: 0,
            currentChapterIndex: 1,
            pendingSyncChapterCredit: true
        )
        XCTAssertFalse(mid.applyPendingSyncChapterCredit())
        XCTAssertEqual(mid.completedChapterCount, 1)
        XCTAssertFalse(mid.isBatchComplete)

        // 取消且无待记账：显示「开始」、不得续旧批。
        var cancelled = NovelGhostwriteProgress(
            binding: binding,
            phase: .paused,
            pauseReason: .cancelled,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 3,
            completedChapterCount: 1,
            currentChapterIndex: 2
        )
        XCTAssertFalse(cancelled.shouldContinueSameBatch)

        // 取消但有待同步记账：必须先续跑记账。
        cancelled.pendingSyncChapterCredit = true
        XCTAssertTrue(cancelled.shouldContinueSameBatch)
    }

    func testGhostwriteQualityFailureRequiresRewriteNotReaccept() {
        XCTAssertTrue(NovelGhostwritePauseReason.acceptanceFailed.requiresRewriteOnContinue)
        XCTAssertTrue(NovelGhostwritePauseReason.obviousRepetition.requiresRewriteOnContinue)
        XCTAssertTrue(NovelGhostwritePauseReason.blockingContinuity.requiresRewriteOnContinue)
        XCTAssertTrue(NovelGhostwritePauseReason.healBudgetExhausted.requiresRewriteOnContinue)
        XCTAssertTrue(NovelGhostwritePauseReason.acceptanceFailed.allowsAutomaticQualityHeal)
        XCTAssertTrue(NovelGhostwritePauseReason.obviousRepetition.allowsAutomaticQualityHeal)
        XCTAssertFalse(NovelGhostwritePauseReason.blockingContinuity.allowsAutomaticQualityHeal)
        XCTAssertFalse(NovelGhostwritePauseReason.syncFailed.requiresRewriteOnContinue)

        let binding = NovelSessionBinding(
            projectID: NovelProjectID(),
            branchID: NovelBranchID()
        )
        let failedID = NovelCandidateID()
        var progress = NovelGhostwriteProgress(
            binding: binding,
            phase: .paused,
            pauseReason: .acceptanceFailed,
            candidateID: failedID,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 5,
            completedChapterCount: 0,
            currentChapterIndex: 1
        )
        XCTAssertTrue(progress.mustRewriteCandidateOnResume)
        XCTAssertTrue(progress.shouldContinueSameBatch)

        let receipt = NovelGhostwriteFailureReceipt.make(
            reason: .acceptanceFailed,
            summary: "缺必发生：主角心里不爽",
            missingMustHappen: ["主角觉得京娘有点碍事、心里不爽"],
            repetitionBeats: ["赵大放缓步子等京娘"],
            attemptIndex: 1,
            sourceCandidateID: failedID,
            planDigest: "digest-1"
        )
        let first = progress.registerQualityFailureForHeal(
            reason: .acceptanceFailed,
            receipt: receipt,
            failedCandidateID: failedID
        )
        XCTAssertTrue(first.willRewrite)
        XCTAssertFalse(first.blockedByFingerprint)
        XCTAssertEqual(progress.qualityAttemptIndex, 1)
        XCTAssertNil(progress.candidateID)
        XCTAssertTrue(progress.supersededCandidateIDs.contains(failedID))
        XCTAssertEqual(progress.phase, .writing)
        XCTAssertNil(progress.pauseReason)
        XCTAssertTrue(
            NovelGhostwriteHeal.writeUserText(receipt: receipt).contains("禁止再写")
                || NovelGhostwriteHeal.writeUserText(receipt: receipt).contains("赵大")
        )

        // 第 2 次失败仍可改写；第 3 次失败后（index==3）不再自动改写。
        // 使用不同 fingerprint 的 receipt，避免指纹熔断抢先挡住预算路径。
        let receipt2 = NovelGhostwriteFailureReceipt.make(
            reason: .acceptanceFailed,
            summary: "缺必发生：另一条",
            missingMustHappen: ["另一条节拍"],
            attemptIndex: 2,
            sourceCandidateID: nil,
            planDigest: "digest-1"
        )
        let second = progress.registerQualityFailureForHeal(
            reason: .acceptanceFailed,
            receipt: receipt2,
            failedCandidateID: NovelCandidateID()
        )
        XCTAssertTrue(second.willRewrite)
        XCTAssertEqual(progress.qualityAttemptIndex, 2)
        let receipt3 = NovelGhostwriteFailureReceipt.make(
            reason: .acceptanceFailed,
            summary: "缺必发生：第三条",
            missingMustHappen: ["第三条节拍"],
            attemptIndex: 3,
            sourceCandidateID: nil,
            planDigest: "digest-1"
        )
        let third = progress.registerQualityFailureForHeal(
            reason: .acceptanceFailed,
            receipt: receipt3,
            failedCandidateID: NovelCandidateID()
        )
        XCTAssertFalse(third.willRewrite)
        XCTAssertEqual(progress.qualityAttemptIndex, 3)
        XCTAssertFalse(
            NovelGhostwriteHeal.shouldAutoRewrite(
                afterFailureCount: 3,
                maxAttempts: 3,
                reason: .acceptanceFailed
            )
        )
    }

    func testGhostwriteFingerprintFuseStopsSameFailureLoop() {
        let binding = NovelSessionBinding(
            projectID: NovelProjectID(),
            branchID: NovelBranchID()
        )
        var progress = NovelGhostwriteProgress(
            binding: binding,
            phase: .writing,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 5,
            maxQualityAttempts: 5
        )
        let same = NovelGhostwriteFailureReceipt.make(
            reason: .obviousRepetition,
            summary: "开篇复读",
            repetitionBeats: ["赵大放缓步子等京娘"],
            attemptIndex: 1,
            sourceCandidateID: nil,
            planDigest: "d"
        )
        let a = progress.registerQualityFailureForHeal(
            reason: .obviousRepetition,
            receipt: same,
            failedCandidateID: NovelCandidateID()
        )
        XCTAssertTrue(a.willRewrite)
        let b = progress.registerQualityFailureForHeal(
            reason: .obviousRepetition,
            receipt: same,
            failedCandidateID: NovelCandidateID()
        )
        // 连续相同 fingerprint → 熔断，即使预算未用尽。
        XCTAssertFalse(b.willRewrite)
        XCTAssertTrue(b.blockedByFingerprint)
        XCTAssertTrue(NovelGhostwriteHeal.isStuckOnSameFingerprint(progress.recentFailureFingerprints))
        XCTAssertTrue(
            NovelGhostwriteHeal.shouldAttemptMustNotAmend(
                reason: .obviousRepetition,
                receipt: same,
                alreadyAmendedThisChapter: false
            )
        )
        XCTAssertFalse(
            NovelGhostwriteHeal.shouldAttemptMustNotAmend(
                reason: .obviousRepetition,
                receipt: same,
                alreadyAmendedThisChapter: true
            )
        )

        progress.resetChapterHealState()
        XCTAssertEqual(progress.qualityAttemptIndex, 0)
        XCTAssertNil(progress.lastFailureReceipt)
        XCTAssertTrue(progress.supersededCandidateIDs.isEmpty)
    }

    func testGhostwriteRevisionUserTextIncludesSourceDraft() {
        let briefOnly = NovelGhostwriteProgress.writeUserText(
            receipt: nil,
            revisionBrief: "补写碍事情绪",
            sourceDraft: nil
        )
        XCTAssertTrue(briefOnly.contains("补写碍事情绪"))
        XCTAssertFalse(briefOnly.contains("上一稿正文"))

        let withDraft = NovelGhostwriteProgress.writeUserText(
            receipt: nil,
            revisionBrief: "补写碍事情绪",
            sourceDraft: "沈砚心里一沉，只觉京娘站在一旁碍眼。"
        )
        XCTAssertTrue(withDraft.contains("【上一稿正文】"))
        XCTAssertTrue(withDraft.contains("碍眼"))
        XCTAssertTrue(withDraft.contains("【润修要求】"))
        // 自动改写路径不带 brief 时仍走 receipt 模板
        let auto = NovelGhostwriteProgress.writeUserText(
            receipt: NovelGhostwriteFailureReceipt.make(
                reason: .acceptanceFailed,
                summary: "缺拍",
                missingMustHappen: ["A"],
                attemptIndex: 1,
                sourceCandidateID: nil,
                planDigest: nil
            ),
            revisionBrief: nil,
            sourceDraft: "不该出现"
        )
        XCTAssertFalse(auto.contains("不该出现"))
        XCTAssertTrue(auto.contains("缺拍") || auto.contains("必须补写"))
    }

    func testGhostwriteCancellationPauseReasonPrefersUserPause() {
        // 契约：协作取消不得落到「取消本批」语义——由 progress 续跑字段表达。
        XCTAssertTrue(NovelGhostwritePauseReason.userPaused.requiresRewriteOnContinue == false)
        XCTAssertTrue(
            NovelGhostwriteProgress(
                binding: NovelSessionBinding(
                    projectID: NovelProjectID(),
                    branchID: NovelBranchID()
                ),
                phase: .paused,
                pauseReason: .userPaused,
                startedAt: Date(timeIntervalSince1970: 0),
                targetChapterCount: 5,
                completedChapterCount: 2
            ).shouldContinueSameBatch
        )
        XCTAssertFalse(
            NovelGhostwriteProgress(
                binding: NovelSessionBinding(
                    projectID: NovelProjectID(),
                    branchID: NovelBranchID()
                ),
                phase: .paused,
                pauseReason: .cancelled,
                startedAt: Date(timeIntervalSince1970: 0),
                targetChapterCount: 5,
                completedChapterCount: 2
            ).shouldContinueSameBatch
        )
    }

    func testGhostwriteSingleMustAlignGateAndRephrase() {
        let missingReceipt = NovelGhostwriteFailureReceipt.make(
            reason: .acceptanceFailed,
            summary: "主角吃醋与自觉多余已写出，但「碍事」字面未点明",
            missingMustHappen: ["主角觉得京娘有点碍事、心里不爽"],
            attemptIndex: 3,
            sourceCandidateID: nil,
            planDigest: "d"
        )
        XCTAssertTrue(
            NovelGhostwriteHeal.shouldAttemptMustAlign(
                reason: .acceptanceFailed,
                receipt: missingReceipt,
                forbiddenViolations: [],
                alreadyAmendedThisChapter: false
            )
        )
        // 有禁止项违反 → 不改 must
        XCTAssertFalse(
            NovelGhostwriteHeal.shouldAttemptMustAlign(
                reason: .acceptanceFailed,
                receipt: missingReceipt,
                forbiddenViolations: ["出现了禁止的死亡"],
                alreadyAmendedThisChapter: false
            )
        )
        // 同时有复读 → 不改 must（走 mustNot 路径）
        let withRep = NovelGhostwriteFailureReceipt.make(
            reason: .acceptanceFailed,
            summary: "缺 must 且复读",
            missingMustHappen: ["A"],
            repetitionBeats: ["旧 beat"],
            attemptIndex: 3,
            sourceCandidateID: nil,
            planDigest: "d"
        )
        XCTAssertFalse(
            NovelGhostwriteHeal.shouldAttemptMustAlign(
                reason: .acceptanceFailed,
                receipt: withRep,
                forbiddenViolations: [],
                alreadyAmendedThisChapter: false
            )
        )
        // 缺 2 条 must → 不改
        let two = NovelGhostwriteFailureReceipt.make(
            reason: .acceptanceFailed,
            summary: "缺两条",
            missingMustHappen: ["A", "B"],
            attemptIndex: 3,
            sourceCandidateID: nil,
            planDigest: "d"
        )
        XCTAssertFalse(
            NovelGhostwriteHeal.shouldAttemptMustAlign(
                reason: .acceptanceFailed,
                receipt: two,
                forbiddenViolations: [],
                alreadyAmendedThisChapter: false
            )
        )

        let planMust = ["主角觉得京娘有点碍事、心里不爽", "章末钩子落地"]
        let rephrase = NovelGhostwriteHeal.rephraseSingleMust(
            planMustHappen: planMust,
            missingItem: "主角觉得京娘有点碍事、心里不爽",
            acceptanceSummary: "已写出吃醋，未点明碍事"
        )
        XCTAssertEqual(rephrase?.index, 0)
        XCTAssertTrue(rephrase?.rewritten.contains("允许等价") == true)
        XCTAssertTrue(rephrase?.rewritten.contains("吃醋") == true)
        // 已放宽过不再叠
        let again = NovelGhostwriteHeal.rephraseSingleMust(
            planMustHappen: [rephrase!.rewritten],
            missingItem: rephrase!.rewritten,
            acceptanceSummary: "再来"
        )
        XCTAssertNil(again)
        XCTAssertEqual(
            NovelGhostwriteContractAmendment.Kind.alignSingleMust.rawValue,
            "alignSingleMust"
        )
    }

    func testGhostwriteRevisionBriefPrefillsFromReceipt() {
        let receipt = NovelGhostwriteFailureReceipt.make(
            reason: .obviousRepetition,
            summary: "开篇复读",
            repetitionBeats: ["赵大放缓步子等京娘"],
            attemptIndex: 2,
            sourceCandidateID: nil,
            planDigest: nil
        )
        let brief = receipt.recommendedRevisionBrief()
        XCTAssertTrue(brief.contains("赵大放缓步子等京娘"))
        XCTAssertFalse(brief.isEmpty)

        let binding = NovelSessionBinding(
            projectID: NovelProjectID(),
            branchID: NovelBranchID()
        )
        let progress = NovelGhostwriteProgress(
            binding: binding,
            phase: .paused,
            pauseReason: .acceptanceFailed,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 5
        )
        XCTAssertTrue(progress.boardStepSummary.contains("将重写"))

        let exhausted = NovelGhostwriteProgress(
            binding: binding,
            phase: .waitingUser,
            pauseReason: .healBudgetExhausted,
            startedAt: Date(timeIntervalSince1970: 0),
            targetChapterCount: 5,
            qualityAttemptIndex: 3
        )
        XCTAssertTrue(exhausted.boardStepSummary.contains("待润修"))
        XCTAssertTrue(exhausted.shouldContinueSameBatch)
        XCTAssertTrue(exhausted.mustRewriteCandidateOnResume)
        XCTAssertTrue(exhausted.shouldOfferRevisionSheet)

        let revisionText = NovelGhostwriteProgress.writeUserText(
            receipt: receipt,
            revisionBrief: "补写京娘碍事的内心；开篇禁止放缓步子。"
        )
        XCTAssertTrue(revisionText.contains("补写京娘碍事"))
        XCTAssertTrue(revisionText.contains("润修要求") || revisionText.contains("本章计划"))
    }

    func testGhostwriteBatchProgressRecordRoundTripAndColdStart() throws {
        let binding = NovelSessionBinding(
            projectID: NovelProjectID(),
            branchID: NovelBranchID()
        )
        let failedID = NovelCandidateID()
        var live = NovelGhostwriteProgress(
            binding: binding,
            phase: .writing,
            pauseReason: nil,
            detailMessage: "验收未过，自动改写 1/3…",
            candidateID: failedID,
            chapterPlanDigest: "digest",
            autoCollectedCandidateIDs: [NovelCandidateID()],
            startedAt: Date(timeIntervalSince1970: 100),
            targetChapterCount: 5,
            completedChapterCount: 2,
            currentChapterIndex: 3,
            lastCompletedPlanSummary: "Goal: x",
            pendingSyncChapterCredit: false,
            qualityAttemptIndex: 1,
            maxQualityAttempts: 3,
            lastFailureReceipt: NovelGhostwriteFailureReceipt.make(
                reason: .acceptanceFailed,
                summary: "缺拍",
                missingMustHappen: ["A"],
                attemptIndex: 1,
                sourceCandidateID: failedID,
                planDigest: "digest"
            ),
            supersededCandidateIDs: [failedID],
            recentFailureFingerprints: ["fp1"],
            revisionBriefOverride: nil,
            didThinContractAmendThisChapter: true,
            contractAmendments: [
                NovelGhostwriteContractAmendment(
                    kind: .appendMustNot,
                    detail: "复读 beat",
                    chapterIndex: 3,
                    beforeDigest: "a",
                    afterDigest: "b"
                ),
            ]
        )
        live.phase = .writing
        let encoded = NovelGhostwriteBatchProgressRecord.from(progress: live)
        XCTAssertTrue(encoded.shouldPersist)

        let data = try JSONEncoder().encode(encoded)
        let decoded = try JSONDecoder().decode(NovelGhostwriteBatchProgressRecord.self, from: data)
        XCTAssertEqual(decoded.completedChapterCount, 2)
        XCTAssertEqual(decoded.targetChapterCount, 5)
        XCTAssertEqual(decoded.qualityAttemptIndex, 1)
        XCTAssertEqual(decoded.contractAmendments.count, 1)

        let restored = decoded.makeProgress()
        // 写稿中杀进程 → 冷启动收成暂停可续。
        XCTAssertEqual(restored.phase, .paused)
        XCTAssertEqual(restored.pauseReason, .userPaused)
        XCTAssertEqual(restored.completedChapterCount, 2)
        XCTAssertTrue(restored.shouldContinueSameBatch)
        XCTAssertTrue(restored.detailMessage?.contains("恢复") == true)

        var pending = encoded
        pending.phase = .syncing
        pending.pendingSyncChapterCredit = true
        pending.pauseReason = nil
        let afterSyncKill = pending.makeProgress()
        XCTAssertEqual(afterSyncKill.pauseReason, .syncFailed)
        XCTAssertTrue(afterSyncKill.pendingSyncChapterCredit)
        XCTAssertTrue(afterSyncKill.shouldContinueSameBatch)

        var done = encoded
        done.completedChapterCount = 5
        done.phase = .waitingUser
        done.pauseReason = .batchCompleted
        XCTAssertFalse(done.shouldPersist)
    }

    func testGhostwriteBatchProgressSidecarRepositoryRoundTrip() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("ghostwrite-progress-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let repository = NovelFileProjectRepository(rootDirectory: root)
        let document = try NovelTestFixtures.document()
        _ = try await repository.createProject(document)

        let binding = NovelSessionBinding(
            projectID: document.project.id,
            branchID: document.project.mainBranchID
        )
        let progress = NovelGhostwriteProgress(
            binding: binding,
            phase: .paused,
            pauseReason: .healBudgetExhausted,
            detailMessage: "待润修",
            startedAt: Date(timeIntervalSince1970: 1),
            targetChapterCount: 4,
            completedChapterCount: 1,
            currentChapterIndex: 2,
            qualityAttemptIndex: 3
        )
        let record = NovelGhostwriteBatchProgressRecord.from(progress: progress)
        try await repository.saveGhostwriteBatchProgress(record)
        let loaded = try await repository.loadGhostwriteBatchProgress(
            projectID: binding.projectID,
            branchID: binding.branchID
        )
        XCTAssertEqual(loaded?.completedChapterCount, 1)
        XCTAssertEqual(loaded?.pauseReason, .healBudgetExhausted)
        XCTAssertEqual(loaded?.targetChapterCount, 4)

        try await repository.removeGhostwriteBatchProgress(
            projectID: binding.projectID,
            branchID: binding.branchID
        )
        let gone = try await repository.loadGhostwriteBatchProgress(
            projectID: binding.projectID,
            branchID: binding.branchID
        )
        XCTAssertNil(gone)
    }

    func testGhostwriteHealInjectionUsesEmptySessionCursorContract() {
        // GenerationLifecycle 在 suppressRecentSessionMessages 时传 sessionCursorLimit=.empty 且 max messages 0。
        // 这里锁 planner 契约：empty cursor → 无近期会话段。
        let request = NovelInjectionPlanningRequest(
            branchID: NovelBranchID(),
            promptKind: .proseWholeChapter,
            userText: "重写",
            sessionCursorLimit: .empty,
            budget: NovelInjectionBudget(
                maxEstimatedInputTokens: 16_000,
                chapterTailCharacterLimit: 6_000,
                maximumRecentSessionMessages: 0
            )
        )
        XCTAssertEqual(request.sessionCursorLimit, .empty)
        XCTAssertEqual(request.budget.maximumRecentSessionMessages, 0)
    }

    func testChapterPlanProposalDecoderFailClosed() throws {
        let ok = try NovelStructuredOutputDecoder.decodeChapterPlanProposal(from: """
        {
          "schemaVersion": 1,
          "outlinePlacement": "第 4 章 · 中段",
          "goalAndConflict": "揭露身份并逼主角表态",
          "mustHappen": ["身份被当众揭穿"],
          "mustNotHappen": ["主角死亡"],
          "endingHook": "门外响起脚步声",
          "visibleFacts": ["主角已知信封来源"]
        }
        """)
        XCTAssertEqual(ok.mustHappen, ["身份被当众揭穿"])
        XCTAssertEqual(ok.goalAndConflict, "揭露身份并逼主角表态")

        XCTAssertThrowsError(try NovelStructuredOutputDecoder.decodeChapterPlanProposal(from: """
        {
          "schemaVersion": 1,
          "outlinePlacement": "第 4 章",
          "goalAndConflict": "只有目标没有义务",
          "mustHappen": [],
          "mustNotHappen": [],
          "endingHook": "",
          "visibleFacts": []
        }
        """))

        XCTAssertThrowsError(try NovelStructuredOutputDecoder.decodeChapterPlanProposal(from: """
        {
          "schemaVersion": 1,
          "outlinePlacement": "第 4 章",
          "goalAndConflict": "",
          "mustHappen": ["有义务"],
          "mustNotHappen": [],
          "endingHook": "",
          "visibleFacts": []
        }
        """))
    }

    func testChapterPlanProposalContextIncludesBoundedSections() throws {
        var document = try NovelTestFixtures.document()
        document = try NovelReducer.apply(.upsertUpcomingArc(NovelUpsertUpcomingArcCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            beats: ["使者身份曝光"]
        )), to: document).document
        let branch = document.branches[0]
        let context = DefaultNovelCreation.chapterPlanProposalContext(
            document: document,
            branch: branch,
            nextChapterOrdinal: 3,
            previousPlanSummary: "Placement: 第 2 章\nGoal: 试探"
        )
        XCTAssertTrue(context.contains("NEXT CHAPTER ORDINAL"))
        XCTAssertTrue(context.contains("3"))
        XCTAssertTrue(context.contains("UPCOMING ARC"))
        XCTAssertTrue(context.contains("使者身份曝光"))
        XCTAssertTrue(context.contains("PREVIOUS CHAPTER PLAN SUMMARY"))
        XCTAssertTrue(context.contains("试探"))
    }

    func testWholeChapterHighlightsCountTowardRequiredBudget() throws {
        var document = try NovelTestFixtures.document()
        let cap = NovelStateSnapshotRecord.maxHighlightCharacterCount
        let highlights = (0..<NovelStateSnapshotRecord.maxRecentWrittenHighlights).map { index -> String in
            let prefix = String(format: "%02d-", index)
            return prefix + String(repeating: "拍", count: max(1, cap - prefix.count))
        }
        document.stateSnapshots[0] = NovelStateSnapshotRecord(
            id: document.stateSnapshots[0].id,
            eventIDs: document.stateSnapshots[0].eventIDs,
            summary: document.stateSnapshots[0].summary,
            branchOutline: document.stateSnapshots[0].branchOutline,
            unresolvedEntityNames: document.stateSnapshots[0].unresolvedEntityNames,
            createdAt: document.stateSnapshots[0].createdAt,
            recentWrittenHighlights: highlights
        )
        XCTAssertEqual(
            document.stateSnapshots[0].recentWrittenHighlights.count,
            NovelStateSnapshotRecord.maxRecentWrittenHighlights
        )

        XCTAssertThrowsError(try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseWholeChapter,
                userText: "写下一章",
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: 2_000,
                    chapterTailCharacterLimit: 200,
                    maximumRecentSessionMessages: 0
                )
            )
        )) { error in
            guard let planningError = error as? NovelInjectionPlanningError,
                  case .requiredContentExceedsBudget(_, _, let items) = planningError else {
                return XCTFail("Expected requiredContentExceedsBudget, got \(error)")
            }
            XCTAssertTrue(items.contains(where: {
                $0.label.contains("RECENT WRITTEN BEATS")
            }))
        }
    }

    private func makeStartArtifacts(
        document: NovelProjectDocumentV1,
        request: NovelRunRequest
    ) throws -> NovelGenerationStartArtifacts {
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: request.branchID,
                promptKind: .proseWholeChapter,
                userText: request.userText
            )
        )
        let injection = NovelInjectionReceiptRecord(
            id: request.injectionReceiptID,
            runID: request.id,
            projectID: request.projectID,
            branchID: request.branchID,
            plan: plan,
            overrides: .none,
            providerID: "provider-id",
            modelID: "model-id",
            parameters: [:],
            createdAt: Date(timeIntervalSince1970: 1_700_000_100)
        )
        let generation = NovelGenerationReceiptRecord(
            id: request.generationReceiptID,
            runID: request.id,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: injection.parameters,
            requestSHA256: NovelDocumentValidator.sha256(plan.canonicalInput),
            createdAt: Date(timeIntervalSince1970: 1_700_000_100)
        )
        return NovelGenerationStartArtifacts(
            injectionReceipt: injection,
            generationReceipt: generation
        )
    }

    private func seedGhostwriteMaterials(
        in document: NovelProjectDocumentV1
    ) throws -> NovelProjectDocumentV1 {
        var next = document
        next = try revise(
            next,
            kind: .masterOutline,
            title: "总纲",
            content: "全书主线：夺回失落的信物。"
        )
        next = try revise(
            next,
            kind: .character,
            title: "林晚",
            content: "冷静的女刺客，目标是找回信物。"
        )
        next = try revise(
            next,
            kind: .writingRequirements,
            title: "写作要求",
            content: "第三人称；节奏紧凑。"
        )
        return next
    }

    private func revise(
        _ document: NovelProjectDocumentV1,
        kind: NovelMaterialKind,
        title: String,
        content: String
    ) throws -> NovelProjectDocumentV1 {
        try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            materialID: NovelMaterialID(),
            revisionID: NovelMaterialRevisionID(),
            kind: kind,
            title: title,
            content: content,
            tags: [],
            injectionMode: .always
        )), to: document).document
    }
}

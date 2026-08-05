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
            XCTAssertTrue(message.contains("本章合同"))
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

        let completed = try NovelGenerationReducer.complete(
            runID: request.id,
            content: "林晚夺回了信物。\n\n信物却在掌心碎裂。",
            in: started.document,
            now: Date(timeIntervalSince1970: 1_700_000_201)
        )
        let candidate = try XCTUnwrap(completed.document.candidates.first { $0.id == candidateID })
        XCTAssertEqual(candidate.chapterPlanDigest, plan.contentDigest)
        XCTAssertEqual(candidate.status, .available)
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
        XCTAssertEqual(progress.boardStepSummary, "已中断")
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

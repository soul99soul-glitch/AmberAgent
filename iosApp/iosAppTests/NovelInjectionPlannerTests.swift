import XCTest
@testable import iosApp

final class NovelInjectionPlannerTests: XCTestCase {
    func testMaterialModesOverridesAndOrderingAreDeterministic() throws {
        var document = try NovelTestFixtures.document()
        let excludedAlways = addMaterial(
            to: &document,
            kind: .world,
            title: "Zeta Rule",
            content: "Always present unless explicitly excluded.",
            tags: ["rule"],
            mode: .always
        )
        let smart = addMaterial(
            to: &document,
            kind: .character,
            title: "Dragon Keeper",
            content: "The keeper protects the mountain dragon.",
            tags: ["dragon"],
            mode: .smart
        )
        let forcedOff = addMaterial(
            to: &document,
            kind: .custom("artifact"),
            title: "Silent Bell",
            content: "The bell rings only at midnight.",
            tags: ["bell"],
            mode: .off
        )
        _ = addMaterial(
            to: &document,
            kind: .writingRequirements,
            title: "Unused Style",
            content: "Use terse sentences.",
            tags: ["terse"],
            mode: .off
        )

        let request = NovelInjectionPlanningRequest(
            branchID: document.branches[0].id,
            promptKind: .discussion,
            userText: "How should the dragon react?",
            overrides: NovelInjectionOverrides(
                forceIncludeMaterialIDs: [forcedOff.materialID],
                forceExcludeMaterialIDs: [excludedAlways.materialID]
            )
        )
        let first = try NovelInjectionPlanner.plan(document: document, request: request)

        document.materials.reverse()
        document.materialRevisions.reverse()
        let reordered = try NovelInjectionPlanner.plan(document: document, request: request)

        XCTAssertEqual(first, reordered)
        XCTAssertEqual(
            first.materialDecisions.map(\.reason),
            [.forceExcluded, .smartMatch, .disabled, .forceIncluded]
        )
        XCTAssertEqual(
            first.materialDecisions.filter(\.included).map(\.revisionID),
            [smart.revisionID, forcedOff.revisionID]
        )
        XCTAssertLessThanOrEqual(first.estimatedInputTokens, first.maxEstimatedInputTokens)
        XCTAssertFalse(first.canonicalInputSHA256.isEmpty)
    }

    func testProtectedMaterialOverflowFailsInsteadOfSilentlyTrimming() throws {
        var document = try NovelTestFixtures.document()
        _ = addMaterial(
            to: &document,
            kind: .world,
            title: "Required World",
            content: String(repeating: "required ", count: 2_000),
            tags: [],
            mode: .always
        )
        let request = NovelInjectionPlanningRequest(
            branchID: document.branches[0].id,
            promptKind: .discussion,
            userText: "Plan the next turn.",
            budget: NovelInjectionBudget(
                maxEstimatedInputTokens: 1_000,
                chapterTailCharacterLimit: 100,
                maximumRecentSessionMessages: 0
            )
        )

        XCTAssertThrowsError(try NovelInjectionPlanner.plan(document: document, request: request)) {
            guard let planningError = $0 as? NovelInjectionPlanningError,
                  case .requiredContentExceedsBudget(let limit, let estimated, let items) =
                    planningError else {
                return XCTFail("Expected required-content budget failure, got \($0)")
            }
            XCTAssertEqual(limit, 1_000)
            XCTAssertGreaterThan(estimated, limit)
            XCTAssertTrue(items.contains(where: { $0.label == "PROJECT MATERIAL" }))
        }
    }

    func testBranchOverrideIsRequiredAndCannotBeForceExcluded() throws {
        var document = try NovelTestFixtures.document()
        let materialID = NovelMaterialID()
        let base = addMaterial(
            to: &document,
            materialID: materialID,
            kind: .world,
            title: "Sky Color",
            content: "The sky is blue.",
            tags: ["sky"],
            mode: .off
        )
        let overrideRevision = NovelMaterialRevisionRecord(
            id: NovelMaterialRevisionID(),
            materialID: materialID,
            revision: 2,
            title: "Sky Color",
            content: "On this branch, the sky is permanently red.",
            tags: ["sky"],
            injectionMode: .off,
            createdAt: document.project.updatedAt,
            operationID: NovelOperationID()
        )
        document.materialRevisions.append(overrideRevision)
        document.materials[document.materials.firstIndex(where: { $0.id == materialID })!]
            .revisionIDs.append(overrideRevision.id)
        document.branches[0].overrideRevisionIDs = [overrideRevision.id]

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .discussion,
                userText: "What does the sky look like?",
                overrides: NovelInjectionOverrides(
                    forceIncludeMaterialIDs: [],
                    forceExcludeMaterialIDs: [base.materialID]
                )
            )
        )

        XCTAssertEqual(plan.materialDecisions.count, 1)
        XCTAssertEqual(plan.materialDecisions[0].revisionID, overrideRevision.id)
        XCTAssertEqual(plan.materialDecisions[0].reason, .branchOverride)
        XCTAssertTrue(plan.materialDecisions[0].included)
        XCTAssertTrue(plan.contextText.contains("permanently red"))
        XCTAssertFalse(plan.contextText.contains("sky is blue"))
    }

    func testContinuationWholeChapterAndPolishUseDistinctChapterContext() throws {
        var document = try NovelTestFixtures.document()
        let fullText = "OPENING-" + String(repeating: "middle-", count: 40) + "FINAL-TAIL"
        let version = addChapter(to: &document, title: "Chapter One", content: fullText)
        let budget = NovelInjectionBudget(
            maxEstimatedInputTokens: 4_000,
            chapterTailCharacterLimit: 32,
            maximumRecentSessionMessages: 0
        )

        let continuation = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseContinuation,
                userText: "Continue the confrontation.",
                budget: budget
            )
        )
        let wholeChapter = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .proseWholeChapter,
                userText: "Write the next chapter.",
                budget: budget
            )
        )
        let polish = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .wholeChapterPolish,
                userText: "Improve the prose only.",
                sourceChapterVersionID: version.id,
                budget: budget
            )
        )

        let continuationContext = try XCTUnwrap(chapterSection(in: continuation))
        let wholeContext = try XCTUnwrap(chapterSection(in: wholeChapter))
        let polishContext = try XCTUnwrap(chapterSection(in: polish))
        XCTAssertEqual(continuationContext.label, "CURRENT CHAPTER TAIL")
        XCTAssertEqual(continuationContext.reason, .currentChapterTail)
        XCTAssertFalse(continuationContext.content.contains("OPENING-"))
        XCTAssertEqual(wholeContext.label, "PREVIOUS CHAPTER TAIL")
        XCTAssertEqual(wholeContext.reason, .previousChapterTail)
        XCTAssertFalse(wholeContext.content.contains("OPENING-"))
        XCTAssertEqual(polishContext.reason, .fullSourceChapter)
        XCTAssertTrue(polishContext.content.contains(fullText))
        XCTAssertNotEqual(continuation.prompt.version, wholeChapter.prompt.version)
        XCTAssertNotEqual(wholeChapter.prompt.version, polish.prompt.version)
    }

    func testNeedsSyncAllowsDiscussionButBlocksFormalProse() throws {
        var document = try NovelTestFixtures.document()
        document.branches[0].syncStatus = .needsSync
        let branchID = document.branches[0].id

        let discussion = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branchID,
                promptKind: .discussion,
                userText: "Discuss the edit."
            )
        )
        XCTAssertTrue(discussion.contextText.contains("potentially stale"))

        XCTAssertThrowsError(try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branchID,
                promptKind: .proseWholeChapter,
                userText: "Write more."
            )
        )) {
            XCTAssertEqual(
                $0 as? NovelInjectionPlanningError,
                .branchRequiresSync(branchID)
            )
        }
    }

    func testReceiptMatchesPlanAndDropsSensitiveModelMetadata() throws {
        let document = try NovelTestFixtures.document()
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .discussion,
                userText: "Compare two options."
            )
        )
        let receipt = NovelInjectionReceiptRecord(
            id: NovelReceiptID(),
            runID: NovelRunID(),
            projectID: document.project.id,
            branchID: document.branches[0].id,
            plan: plan,
            overrides: .none,
            providerID: "provider",
            modelID: "model",
            parameters: [
                "temperature": "0.7",
                "maxOutputTokens": "4096",
                "apiKey": "must-not-persist",
                "oauth_token": "must-not-persist",
                "baseURL": "must-not-persist"
            ],
            createdAt: document.project.updatedAt
        )

        XCTAssertEqual(receipt.promptVersion, plan.prompt.version)
        XCTAssertEqual(receipt.canonicalInputSHA256, plan.canonicalInputSHA256)
        XCTAssertEqual(receipt.estimatedInputTokens, plan.estimatedInputTokens)
        XCTAssertEqual(receipt.parameters, [
            "maxOutputTokens": "4096",
            "temperature": "0.7"
        ])
        XCTAssertEqual(receipt.sections.map(\.contentSHA256), plan.sections.map(\.contentSHA256))
    }

    func testRelevantOldStoryEventOutranksRecentHistoryFromUserStateAndChapterQueries() throws {
        let cases: [(user: String, state: String, chapter: String?, entity: String, prompt: NovelPromptKind)] = [
            ("What did UserNeedle sacrifice?", "The branch is quiet.", nil, "UserNeedle", .discussion),
            ("Recall the old sacrifice.", "StateNeedle may return.", nil, "StateNeedle", .discussion),
            (
                "Continue from the current scene.",
                "The branch is quiet.",
                "ChapterNeedle waits beyond the gate.",
                "ChapterNeedle",
                .proseContinuation
            )
        ]

        for testCase in cases {
            var document = try NovelTestFixtures.document()
            replaceCurrentState(in: &document, summary: testCase.state, eventIDs: [])
            if let chapter = testCase.chapter {
                _ = addChapter(to: &document, title: "Current Chapter", content: chapter)
            }
            let oldEvent = addStoryEvent(
                to: &document,
                sequence: 1,
                kind: "character-experience",
                summary: "\(testCase.entity) surrendered the archive key years ago.",
                entities: [testCase.entity]
            )
            let generousRequest = NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: testCase.prompt,
                userText: testCase.user,
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: 16_000,
                    chapterTailCharacterLimit: 6_000,
                    maximumRecentSessionMessages: 0
                )
            )
            let oneEventPlan = try NovelInjectionPlanner.plan(
                document: document,
                request: generousRequest
            )
            XCTAssertEqual(storyEventSections(in: oneEventPlan).map(\.kind), [.storyEvent(oldEvent.id)])

            for sequence in 100...103 {
                _ = addStoryEvent(
                    to: &document,
                    sequence: Int64(sequence),
                    kind: "unrelated",
                    summary: "An unrelated recent event numbered \(sequence).",
                    entities: ["Unrelated\(sequence)"]
                )
            }
            let constrainedRequest = NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: testCase.prompt,
                userText: testCase.user,
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: oneEventPlan.estimatedInputTokens,
                    chapterTailCharacterLimit: 6_000,
                    maximumRecentSessionMessages: 0
                )
            )
            let selected = try NovelInjectionPlanner.plan(
                document: document,
                request: constrainedRequest
            )
            let eventSections = storyEventSections(in: selected)
            XCTAssertEqual(eventSections.map(\.kind), [.storyEvent(oldEvent.id)])
            XCTAssertEqual(eventSections.first?.reason, .branchEventHistory)
            XCTAssertTrue(selected.contextText.contains(oldEvent.summary))
            XCTAssertTrue(selected.canonicalInput.contains(oldEvent.summary))

            var reordered = document
            reordered.events.reverse()
            let stateIndex = try XCTUnwrap(reordered.stateSnapshots.firstIndex {
                $0.id == reordered.branches[0].currentStateSnapshotID
            })
            replaceCurrentState(
                in: &reordered,
                summary: reordered.stateSnapshots[stateIndex].summary,
                eventIDs: reordered.stateSnapshots[stateIndex].eventIDs.reversed()
            )
            XCTAssertEqual(
                try NovelInjectionPlanner.plan(document: reordered, request: constrainedRequest),
                selected
            )

            let receipt = NovelInjectionReceiptRecord(
                id: NovelReceiptID(),
                runID: NovelRunID(),
                projectID: document.project.id,
                branchID: document.branches[0].id,
                plan: selected,
                overrides: .none,
                providerID: "provider",
                modelID: "model",
                parameters: [:],
                createdAt: document.project.updatedAt
            )
            XCTAssertTrue(receipt.sections.contains {
                $0.kind == .storyEvent(oldEvent.id) && $0.reason == .branchEventHistory
            })
        }
    }

    func testRequiredCurrentStateAlwaysIncludesCompleteUnresolvedEntities() throws {
        var document = try NovelTestFixtures.document()
        let stateIndex = try XCTUnwrap(document.stateSnapshots.firstIndex {
            $0.id == document.branches[0].currentStateSnapshotID
        })
        let current = document.stateSnapshots[stateIndex]
        document.stateSnapshots[stateIndex] = NovelStateSnapshotRecord(
            id: current.id,
            eventIDs: current.eventIDs,
            summary: current.summary,
            branchOutline: current.branchOutline,
            unresolvedEntityNames: ["Masked Archivist", "North-Gate Witness"],
            createdAt: current.createdAt
        )

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .stateDeltaV1,
                userText: "A short collected passage.",
                budget: NovelInjectionBudget(
                    maxEstimatedInputTokens: 1_000,
                    chapterTailCharacterLimit: 0,
                    maximumRecentSessionMessages: 0
                )
            )
        )

        let state = try XCTUnwrap(plan.sections.first {
            if case .currentState = $0.kind { return true }
            return false
        })
        XCTAssertEqual(state.reason, .requiredCurrentState)
        XCTAssertTrue(state.content.contains("- Masked Archivist"))
        XCTAssertTrue(state.content.contains("- North-Gate Witness"))
        XCTAssertTrue(plan.contextText.contains("- Masked Archivist"))
    }

    func testSessionCursorLimitExcludesDiscussionAfterFactTransactionStarted() throws {
        var document = try NovelTestFixtures.document()
        let firstID = NovelMessageID()
        let laterID = NovelMessageID()
        document.sessions[0].messages = [
            NovelSessionMessageRecord(
                id: firstID,
                sequence: 0,
                role: .assistant,
                mode: .writeProse,
                kind: .proseCandidate,
                content: "The candidate source message.",
                createdAt: document.project.updatedAt,
                runID: nil,
                candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: laterID,
                sequence: 1,
                role: .user,
                mode: .discussPlan,
                kind: .userInput,
                content: "A later idea that must not affect extraction.",
                createdAt: document.project.updatedAt,
                runID: nil,
                candidateID: nil
            )
        ]
        document.sessions[0].revision = 2

        let limited = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .stateDeltaV1,
                userText: "Collected manuscript.",
                sessionCursorLimit: .through(sequence: 0)
            )
        )
        let includedMessageIDs = limited.sections.compactMap { section -> NovelMessageID? in
            if case .sessionMessage(let id) = section.kind { return id }
            return nil
        }
        XCTAssertEqual(includedMessageIDs, [firstID])
        XCTAssertFalse(limited.contextText.contains("later idea"))

        let empty = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .manualSyncV1,
                userText: "Rebuilt manuscript.",
                sessionCursorLimit: .empty
            )
        )
        XCTAssertFalse(empty.sections.contains {
            if case .sessionMessage = $0.kind { return true }
            return false
        })
    }

    private func chapterSection(in plan: NovelInjectionPlan) -> NovelInjectionSection? {
        plan.sections.first {
            if case .chapterContext = $0.kind { return true }
            return false
        }
    }

    private func storyEventSections(in plan: NovelInjectionPlan) -> [NovelInjectionSection] {
        plan.sections.filter {
            if case .storyEvent = $0.kind { return true }
            return false
        }
    }

    private func replaceCurrentState(
        in document: inout NovelProjectDocumentV1,
        summary: String,
        eventIDs: some Sequence<NovelEventID>
    ) {
        guard let index = document.stateSnapshots.firstIndex(where: {
            $0.id == document.branches[0].currentStateSnapshotID
        }) else {
            return
        }
        let current = document.stateSnapshots[index]
        document.stateSnapshots[index] = NovelStateSnapshotRecord(
            id: current.id,
            eventIDs: Array(eventIDs),
            summary: summary,
            branchOutline: current.branchOutline,
            unresolvedEntityNames: current.unresolvedEntityNames,
            createdAt: current.createdAt
        )
    }

    @discardableResult
    private func addStoryEvent(
        to document: inout NovelProjectDocumentV1,
        sequence: Int64,
        kind: String,
        summary: String,
        entities: [String]
    ) -> NovelStoryEventRecord {
        let event = NovelStoryEventRecord(
            id: NovelEventID(),
            sequence: sequence,
            kind: kind,
            summary: summary,
            entityReferences: entities,
            createdAt: document.project.updatedAt
        )
        document.events.append(event)
        guard let index = document.stateSnapshots.firstIndex(where: {
            $0.id == document.branches[0].currentStateSnapshotID
        }) else {
            return event
        }
        let current = document.stateSnapshots[index]
        replaceCurrentState(
            in: &document,
            summary: current.summary,
            eventIDs: current.eventIDs + [event.id]
        )
        return event
    }

    @discardableResult
    private func addMaterial(
        to document: inout NovelProjectDocumentV1,
        materialID: NovelMaterialID = NovelMaterialID(),
        kind: NovelMaterialKind,
        title: String,
        content: String,
        tags: [String],
        mode: NovelInjectionMode
    ) -> (materialID: NovelMaterialID, revisionID: NovelMaterialRevisionID) {
        let revisionID = NovelMaterialRevisionID()
        document.materials.append(NovelMaterialRecord(
            id: materialID,
            kind: kind,
            currentRevisionID: revisionID,
            revisionIDs: [revisionID]
        ))
        document.materialRevisions.append(NovelMaterialRevisionRecord(
            id: revisionID,
            materialID: materialID,
            revision: 1,
            title: title,
            content: content,
            tags: tags,
            injectionMode: mode,
            createdAt: document.project.updatedAt,
            operationID: NovelOperationID()
        ))
        return (materialID, revisionID)
    }

    @discardableResult
    private func addChapter(
        to document: inout NovelProjectDocumentV1,
        title: String,
        content: String
    ) -> NovelChapterVersionRecord {
        let chapterID = NovelChapterID()
        let version = NovelChapterVersionRecord(
            id: NovelChapterVersionID(),
            chapterID: chapterID,
            kind: .collected,
            title: title,
            content: content,
            factCompatibilityID: UUID(),
            sourceCandidateID: nil,
            createdAt: document.project.updatedAt,
            operationID: NovelOperationID()
        )
        document.chapters.append(NovelChapterRecord(
            id: chapterID,
            createdAt: document.project.updatedAt
        ))
        document.chapterVersions.append(version)
        document.branches[0].workingChapterSelections = [NovelChapterSelection(
            chapterID: chapterID,
            versionID: version.id
        )]
        return version
    }
}

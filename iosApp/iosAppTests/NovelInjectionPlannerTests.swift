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
        let discussion = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .discussion,
                userText: "Should the confrontation happen now?",
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
        let discussionContext = try XCTUnwrap(chapterSection(in: discussion))
        let wholeContext = try XCTUnwrap(chapterSection(in: wholeChapter))
        let polishContext = try XCTUnwrap(chapterSection(in: polish))
        XCTAssertEqual(continuationContext.label, "CURRENT CHAPTER TAIL")
        XCTAssertEqual(continuationContext.reason, .currentChapterTail)
        XCTAssertFalse(continuationContext.content.contains("OPENING-"))
        XCTAssertEqual(discussionContext.label, "CURRENT MANUSCRIPT TAIL")
        XCTAssertEqual(discussionContext.reason, .currentChapterTail)
        XCTAssertTrue(discussionContext.content.contains("FINAL-TAIL"))
        XCTAssertFalse(discussionContext.content.contains("OPENING-"))
        XCTAssertEqual(wholeContext.label, "PREVIOUS CHAPTER TAIL")
        XCTAssertEqual(wholeContext.reason, .previousChapterTail)
        XCTAssertFalse(wholeContext.content.contains("OPENING-"))
        XCTAssertEqual(polishContext.reason, .fullSourceChapter)
        XCTAssertTrue(polishContext.content.contains(fullText))
        XCTAssertNotEqual(continuation.prompt.version, wholeChapter.prompt.version)
        XCTAssertNotEqual(wholeChapter.prompt.version, polish.prompt.version)
    }

    func testNeedsSyncAllowsDiscussionAndProseButStillBlocksPolish() throws {
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

        let prose = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branchID,
                promptKind: .proseWholeChapter,
                userText: "Write more."
            )
        )
        XCTAssertTrue(prose.contextText.contains("potentially stale"))

        XCTAssertThrowsError(try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branchID,
                promptKind: .wholeChapterPolish,
                userText: "Polish the chapter."
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

    func testInjectionPanelProjectionHandlesMissingReceipt() {
        XCTAssertEqual(NovelInjectionPanelPresentation.project(nil), .empty)
    }

    func testInjectionPanelProjectionShowsIncludedMaterialsStateAndRecentRounds() throws {
        var document = try NovelTestFixtures.document()
        let included = addMaterial(
            to: &document,
            kind: .world,
            title: "北境法则",
            content: "北境长夜持续半年。",
            tags: [],
            mode: .always
        )
        let runIDs = [NovelRunID(), NovelRunID()]
        document.sessions[0].messages = (0..<4).map { index in
            NovelSessionMessageRecord(
                id: NovelMessageID(),
                sequence: Int64(index),
                role: index.isMultiple(of: 2) ? .user : .assistant,
                mode: .discussPlan,
                kind: index.isMultiple(of: 2) ? .userInput : .discussion,
                content: "消息 \(index)",
                createdAt: document.project.updatedAt,
                runID: runIDs[index / 2],
                candidateID: nil
            )
        }
        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .discussion,
                userText: "继续讨论"
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
            parameters: [:],
            createdAt: document.project.updatedAt
        )

        let model = NovelInjectionPanelPresentation.project(receipt)
        XCTAssertTrue(model.hasReceipt)
        XCTAssertEqual(model.materials, [
            NovelInjectionPanelMaterialModel(
                id: included.materialID,
                title: "北境法则",
                kindTitle: "世界观"
            )
        ])
        XCTAssertTrue(model.includesPlotState)
        XCTAssertEqual(model.recentMessageRoundCount, 2)
        XCTAssertEqual(model.budgetExcludedItemCount, 0)
    }

    func testInjectionPanelProjectionReportsBudgetExclusions() {
        let prompt = NovelPromptCatalog.template(for: .discussion)
        let canonical = "context"
        let plan = NovelInjectionPlan(
            prompt: prompt,
            sections: [],
            materialDecisions: [],
            maxEstimatedInputTokens: 100,
            estimatedInputTokens: 2,
            contextText: canonical,
            canonicalInput: canonical,
            canonicalInputSHA256: NovelDocumentValidator.sha256(canonical),
            budgetExcludedItemCount: 3
        )
        let receipt = NovelInjectionReceiptRecord(
            id: NovelReceiptID(),
            runID: NovelRunID(),
            projectID: NovelProjectID(),
            branchID: NovelBranchID(),
            plan: plan,
            overrides: .none,
            providerID: "provider",
            modelID: "model",
            parameters: [:],
            createdAt: Date(timeIntervalSince1970: 1_700_000_000)
        )

        XCTAssertEqual(
            NovelInjectionPanelPresentation.project(receipt).budgetExcludedItemCount,
            3
        )
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

    func testCharacterAliasesAreAlwaysInjectedAndNoLongerRemainUnresolved() throws {
        var document = try NovelTestFixtures.document()
        _ = addMaterial(
            to: &document,
            kind: .character,
            title: "朱元璋",
            content: "开篇仍使用乳名。",
            tags: [],
            mode: .off,
            aliases: ["朱重八", "朱重九"]
        )
        let stateIndex = try XCTUnwrap(document.stateSnapshots.firstIndex {
            $0.id == document.branches[0].currentStateSnapshotID
        })
        let current = document.stateSnapshots[stateIndex]
        document.stateSnapshots[stateIndex] = NovelStateSnapshotRecord(
            id: current.id,
            eventIDs: current.eventIDs,
            summary: current.summary,
            branchOutline: current.branchOutline,
            unresolvedEntityNames: ["朱重八"],
            createdAt: current.createdAt
        )

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .stateDeltaV1,
                userText: "朱重八走进濠州城。"
            )
        )
        let state = try XCTUnwrap(plan.sections.first {
            if case .currentState = $0.kind { return true }
            return false
        })

        XCTAssertTrue(state.content.contains("- 朱元璋 | aliases: 朱重八, 朱重九"))
        XCTAssertTrue(state.content.contains("Unresolved entities:\n(none)"))
    }

    func testCharacterRenameUsesNewCanonicalNameAndTreatsHistoricalNameAsAlias() throws {
        var document = try NovelTestFixtures.document()
        let materialID = NovelMaterialID()
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: .character,
            title: "赵旧名",
            content: "旧的人物设定。",
            tags: [],
            injectionMode: .always,
            aliases: []
        )), to: document).document
        let stateIndex = try XCTUnwrap(document.stateSnapshots.firstIndex {
            $0.id == document.branches[0].currentStateSnapshotID
        })
        let current = document.stateSnapshots[stateIndex]
        document.stateSnapshots[stateIndex] = NovelStateSnapshotRecord(
            id: current.id,
            eventIDs: current.eventIDs,
            summary: "赵旧名仍出现在既有剧情摘要里。",
            branchOutline: current.branchOutline,
            unresolvedEntityNames: ["赵旧名"],
            createdAt: current.createdAt
        )
        let newRevisionID = NovelMaterialRevisionID()
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: newRevisionID,
            kind: .character,
            title: "赵大来",
            content: "用户保存后的新人物设定。",
            tags: [],
            injectionMode: .always,
            aliases: []
        )), to: document).document

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .discussion,
                userText: "继续讨论这个人物。"
            )
        )
        let state = try XCTUnwrap(plan.sections.first {
            if case .currentState = $0.kind { return true }
            return false
        })
        let materialSection = try XCTUnwrap(plan.sections.first {
            $0.kind == .material(newRevisionID)
        })

        XCTAssertTrue(state.content.contains("- 赵大来 | aliases: 赵旧名"))
        XCTAssertTrue(state.content.contains("Unresolved entities:\n(none)"))
        XCTAssertTrue(state.content.contains("use canonical names in new output"))
        XCTAssertTrue(materialSection.content.contains("Title: 赵大来"))
        XCTAssertTrue(materialSection.content.contains("用户保存后的新人物设定。"))
    }

    func testSmartCharacterMaterialMatchesItsHistoricalName() throws {
        var document = try NovelTestFixtures.document()
        let materialID = NovelMaterialID()
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: .character,
            title: "赵旧名",
            content: "旧的人物设定。",
            tags: [],
            injectionMode: .smart,
            aliases: []
        )), to: document).document
        let currentRevisionID = NovelMaterialRevisionID()
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: currentRevisionID,
            kind: .character,
            title: "赵大来",
            content: "用户保存后的新人物设定。",
            tags: [],
            injectionMode: .smart,
            aliases: []
        )), to: document).document

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .discussion,
                userText: "继续讨论赵旧名。"
            )
        )

        let decision = try XCTUnwrap(plan.materialDecisions.first {
            $0.materialID == materialID
        })
        XCTAssertTrue(decision.included)
        XCTAssertEqual(decision.reason, .smartMatch)
        XCTAssertTrue(plan.sections.contains { $0.kind == .material(currentRevisionID) })
    }

    func testCharacterHistoricalAliasesExcludeRetiredBranchOverrideTitles() throws {
        var document = try NovelTestFixtures.document()
        let materialID = NovelMaterialID()
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: NovelMaterialRevisionID(),
            kind: .character,
            title: "赵旧名",
            content: "旧的人物设定。",
            tags: [],
            injectionMode: .always
        )), to: document).document

        let branchID = document.branches[0].id
        let branchRevisionID = NovelMaterialRevisionID()
        document = try NovelReducer.apply(.setBranchMaterialOverride(
            NovelSetBranchMaterialOverrideCommand(
                context: NovelTestFixtures.context(
                    projectRevision: document.project.revision,
                    configRevision: document.project.configRevision,
                    branchHeadRevision: document.branches[0].headRevision
                ),
                projectID: document.project.id,
                branchID: branchID,
                materialID: materialID,
                change: .createRevision(
                    revisionID: branchRevisionID,
                    title: "仅分支称呼",
                    content: "只属于这一条分支的设定。",
                    tags: [],
                    injectionMode: .always
                )
            )
        ), to: document).document
        document = try NovelReducer.apply(.setBranchMaterialOverride(
            NovelSetBranchMaterialOverrideCommand(
                context: NovelTestFixtures.context(
                    projectRevision: document.project.revision,
                    configRevision: document.project.configRevision,
                    branchHeadRevision: document.branches[0].headRevision
                ),
                projectID: document.project.id,
                branchID: branchID,
                materialID: materialID,
                change: .inherit
            )
        ), to: document).document

        let currentRevisionID = NovelMaterialRevisionID()
        document = try NovelReducer.apply(.reviseMaterial(NovelReviseMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            materialID: materialID,
            revisionID: currentRevisionID,
            kind: .character,
            title: "赵大来",
            content: "当前人物设定。",
            tags: [],
            injectionMode: .always
        )), to: document).document

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: branchID,
                promptKind: .discussion,
                userText: "继续讨论这个人物。"
            )
        )
        let state = try XCTUnwrap(plan.sections.first {
            if case .currentState = $0.kind { return true }
            return false
        })
        let materialSection = try XCTUnwrap(plan.sections.first {
            $0.kind == .material(currentRevisionID)
        })

        XCTAssertTrue(state.content.contains("- 赵大来 | aliases: 赵旧名"))
        XCTAssertFalse(state.content.contains("仅分支称呼"))
        XCTAssertFalse(materialSection.content.contains("仅分支称呼"))
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

    func testArchivedDiscussionReplacesOnlyRawDiscussionWithSummaryAndConfirmedDecisions() throws {
        var document = try NovelTestFixtures.documentWithForkableCheckpoint()
        let branchID = document.branches[0].id
        let now = Date(timeIntervalSince1970: 1_700_000_200)
        let proseCandidateID = NovelMessageID()
        document.sessions[0].messages = [
            NovelSessionMessageRecord(
                id: NovelMessageID(),
                sequence: 0,
                role: .user,
                mode: .discussPlan,
                kind: .userInput,
                content: "RAW_ARCHIVED_USER_MESSAGE",
                createdAt: now,
                runID: nil,
                candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: proseCandidateID,
                sequence: 1,
                role: .assistant,
                mode: .writeProse,
                kind: .proseCandidate,
                content: "INTERLEAVED_PROSE_CANDIDATE",
                createdAt: now,
                runID: nil,
                candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: NovelMessageID(),
                sequence: 2,
                role: .assistant,
                mode: .discussPlan,
                kind: .discussion,
                content: "RAW_ARCHIVED_ASSISTANT_MESSAGE",
                createdAt: now,
                runID: nil,
                candidateID: nil
            ),
        ]

        let archivedResult = try NovelReducer.apply(.archiveDiscussion(
            NovelArchiveDiscussionCommand(
                context: NovelTestFixtures.context(
                    projectRevision: document.project.revision,
                    branchHeadRevision: document.branches[0].headRevision
                ),
                projectID: document.project.id,
                branchID: branchID,
                archiveID: NovelMessageID(),
                checkpointID: NovelCheckpointID(),
                throughSequence: 2,
                chapterID: nil,
                summary: "ARCHIVED_DISCUSSION_SUMMARY",
                decisions: [NovelConfirmedDiscussionDecision(
                    materialID: NovelMaterialID(),
                    revisionID: NovelMaterialRevisionID(),
                    topic: "DECISION_TOPIC",
                    decision: "CONFIRMED_DECISION_CONTENT",
                    relatedMaterialID: nil
                )]
            )
        ), to: document, now: now)
        var archived = archivedResult.document
        let postArchiveMessageID = NovelMessageID()
        archived.sessions[0].messages.append(NovelSessionMessageRecord(
            id: postArchiveMessageID,
            sequence: 3,
            role: .user,
            mode: .discussPlan,
            kind: .userInput,
            content: "POST_ARCHIVE_MESSAGE",
            createdAt: now.addingTimeInterval(1),
            runID: nil,
            candidateID: nil
        ))
        archived.sessions[0].revision += 1

        let plan = try NovelInjectionPlanner.plan(
            document: archived,
            request: NovelInjectionPlanningRequest(
                branchID: branchID,
                promptKind: .discussion,
                userText: "继续讨论"
            )
        )

        XCTAssertFalse(plan.contextText.contains("RAW_ARCHIVED_USER_MESSAGE"))
        XCTAssertFalse(plan.contextText.contains("RAW_ARCHIVED_ASSISTANT_MESSAGE"))
        XCTAssertTrue(plan.contextText.contains("INTERLEAVED_PROSE_CANDIDATE"))
        XCTAssertTrue(plan.contextText.contains("ARCHIVED_DISCUSSION_SUMMARY"))
        XCTAssertTrue(plan.contextText.contains("DECISION_TOPIC"))
        XCTAssertTrue(plan.contextText.contains("CONFIRMED_DECISION_CONTENT"))
        XCTAssertTrue(plan.contextText.contains("POST_ARCHIVE_MESSAGE"))
        XCTAssertEqual(plan.sections.compactMap { section -> NovelMessageID? in
            if case .sessionMessage(let messageID) = section.kind { return messageID }
            return nil
        }, [proseCandidateID, postArchiveMessageID])
    }

    func testSessionWithoutArchiveCursorPreservesRawInjectionBehavior() throws {
        var document = try NovelTestFixtures.document()
        document.sessions[0].messages = [NovelSessionMessageRecord(
            id: NovelMessageID(),
            sequence: 0,
            role: .user,
            mode: .discussPlan,
            kind: .userInput,
            content: "UNARCHIVED_RAW_MESSAGE",
            createdAt: document.project.updatedAt,
            runID: nil,
            candidateID: nil
        )]

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .discussion,
                userText: "继续讨论"
            )
        )

        XCTAssertTrue(plan.contextText.contains("UNARCHIVED_RAW_MESSAGE"))
    }

    func testQuickStartRetryExcludesFailedStructuredDraftFromModelContext() throws {
        var document = try NovelTestFixtures.document()
        let runID = NovelRunID()
        let userMessageID = NovelMessageID()
        let assistantMessageID = NovelMessageID()
        document.sessions[0].messages = [
            NovelSessionMessageRecord(
                id: userMessageID,
                sequence: 0,
                role: .user,
                mode: .discussPlan,
                kind: .userInput,
                content: "现代社畜青年",
                createdAt: document.project.updatedAt,
                runID: runID,
                candidateID: nil
            ),
            NovelSessionMessageRecord(
                id: assistantMessageID,
                sequence: 1,
                role: .assistant,
                mode: .discussPlan,
                kind: .interruptedDraft,
                content: "INVALID_QUICK_START_DRAFT_THAT_POISONS_RETRY",
                createdAt: document.project.updatedAt,
                runID: runID,
                candidateID: nil
            )
        ]
        document.sessions[0].revision = 2
        document.activeRuns = [NovelActiveRunRecord(
            id: runID,
            operationID: NovelOperationID(),
            requestPayloadSHA256: NovelTestFixtures.hashA,
            branchID: document.branches[0].id,
            sessionID: document.sessions[0].id,
            kind: .quickStart,
            mode: .discussPlan,
            granularity: nil,
            userMessageID: userMessageID,
            messageID: assistantMessageID,
            candidateID: nil,
            sourceChapterVersionID: nil,
            contextualCharacterMention: nil,
            baseCheckpointID: document.branches[0].headCheckpointID,
            baseHeadRevision: document.branches[0].headRevision,
            status: .failed,
            partialContent: "INVALID_QUICK_START_DRAFT_THAT_POISONS_RETRY",
            receiptID: NovelReceiptID(),
            startedAt: document.project.updatedAt,
            terminalAt: document.project.updatedAt,
            interruptionReason: nil,
            terminalFailure: NovelFailure(
                code: "invalid_quick_start_output",
                message: "invalid",
                isRetryable: true
            )
        )]

        let plan = try NovelInjectionPlanner.plan(
            document: document,
            request: NovelInjectionPlanningRequest(
                branchID: document.branches[0].id,
                promptKind: .quickStart,
                userText: "现代社畜青年"
            )
        )

        XCTAssertFalse(plan.contextText.contains("INVALID_QUICK_START_DRAFT_THAT_POISONS_RETRY"))
        XCTAssertFalse(plan.sections.contains { section in
            if case .sessionMessage = section.kind { return true }
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
        mode: NovelInjectionMode,
        aliases: [String] = []
    ) -> (materialID: NovelMaterialID, revisionID: NovelMaterialRevisionID) {
        let revisionID = NovelMaterialRevisionID()
        document.materials.append(NovelMaterialRecord(
            id: materialID,
            kind: kind,
            currentRevisionID: revisionID,
            revisionIDs: [revisionID],
            aliases: aliases
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

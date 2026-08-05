import XCTest
@testable import iosApp

final class NovelProjectConfigurationTests: XCTestCase {
    func testModelPolicyUsesStableProviderAndModelIDsAndReplays() throws {
        let document = try NovelTestFixtures.document()
        let operationID = NovelOperationID()
        let action = NovelAction.setModelPolicy(NovelSetModelPolicyCommand(
            context: NovelTestFixtures.context(
                operationID: operationID,
                configRevision: document.project.configRevision
            ),
            projectID: document.project.id,
            purpose: .creation,
            policy: .fixed(providerID: " provider-uuid ", modelID: " model-uuid ")
        ))

        let first = try NovelReducer.apply(action, to: document)
        let replay = try NovelReducer.apply(action, to: first.document)

        XCTAssertEqual(
            first.document.project.modelPolicy,
            .fixed(providerID: "provider-uuid", modelID: "model-uuid")
        )
        XCTAssertEqual(first.document.project.configRevision, document.project.configRevision + 1)
        XCTAssertEqual(replay.document, first.document)
        XCTAssertEqual(replay.outcome, first.outcome)
        XCTAssertNoThrow(try NovelDocumentValidator.validateTransition(
            from: document,
            to: first.document
        ))
    }

    func testFixedModelPolicyRejectsEmptyStableID() throws {
        let document = try NovelTestFixtures.document()
        let action = NovelAction.setModelPolicy(NovelSetModelPolicyCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            purpose: .creation,
            policy: .fixed(providerID: " ", modelID: "model")
        ))

        XCTAssertThrowsError(try NovelReducer.apply(action, to: document)) { error in
            guard case .invalidInput = error as? NovelError else {
                return XCTFail("Expected invalid fixed policy, got \(error)")
            }
        }
    }

    func testStateSyncModelPolicyPersistsIndependentlyFromCreationModel() throws {
        let document = try NovelTestFixtures.document()
        let action = NovelAction.setModelPolicy(NovelSetModelPolicyCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            purpose: .stateSync,
            policy: .fixed(providerID: "sync-provider", modelID: "sync-model")
        ))

        let result = try NovelReducer.apply(action, to: document).document

        XCTAssertEqual(result.project.modelPolicy, document.project.modelPolicy)
        XCTAssertEqual(
            result.project.stateSyncModelPolicy,
            .fixed(providerID: "sync-provider", modelID: "sync-model")
        )
        XCTAssertEqual(result.project.configRevision, document.project.configRevision + 1)
    }

    func testReviewModelPolicyPersistsIndependentlyAndDefaultsMissingToGlobal() throws {
        let document = try NovelTestFixtures.document()
        let action = NovelAction.setModelPolicy(NovelSetModelPolicyCommand(
            context: NovelTestFixtures.context(configRevision: document.project.configRevision),
            projectID: document.project.id,
            purpose: .review,
            policy: .fixed(providerID: "review-provider", modelID: "review-model")
        ))

        let result = try NovelReducer.apply(action, to: document).document
        XCTAssertEqual(result.project.modelPolicy, document.project.modelPolicy)
        XCTAssertEqual(result.project.stateSyncModelPolicy, document.project.stateSyncModelPolicy)
        XCTAssertEqual(
            result.project.reviewModelPolicy,
            .fixed(providerID: "review-provider", modelID: "review-model")
        )
        XCTAssertEqual(
            result.project.configuredModelPolicy(for: .review),
            .fixed(providerID: "review-provider", modelID: "review-model")
        )

        let data = try JSONEncoder().encode(result)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var project = try XCTUnwrap(object["project"] as? [String: Any])
        project.removeValue(forKey: "reviewModelPolicy")
        object["project"] = project
        let legacyData = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let decoded = try JSONDecoder().decode(NovelProjectDocumentV1.self, from: legacyData)
        XCTAssertNil(decoded.project.reviewModelPolicy)
        XCTAssertEqual(decoded.project.configuredModelPolicy(for: .review), .global)
    }

    func testOlderProjectPayloadDefaultsMissingStateSyncModelToGlobal() throws {
        let document = try NovelTestFixtures.document()
        let data = try JSONEncoder().encode(document)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var project = try XCTUnwrap(object["project"] as? [String: Any])
        project.removeValue(forKey: "stateSyncModelPolicy")
        object["project"] = project

        let legacyData = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let decoded = try JSONDecoder().decode(NovelProjectDocumentV1.self, from: legacyData)

        XCTAssertNil(decoded.project.stateSyncModelPolicy)
        XCTAssertEqual(decoded.project.configuredModelPolicy(for: .stateSync), .global)
    }

    func testNovelModelPreferencesPersistRoleDefaultsIncludingReview() {
        let suite = "NovelModelPreferencesTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = NovelCreationModelPreferences(userDefaults: defaults)

        preferences.set(
            .fixed(providerID: "creative-provider", modelID: "creative-model"),
            for: .creation
        )
        preferences.set(
            .fixed(providerID: "sync-provider", modelID: "sync-model"),
            for: .stateSync
        )
        preferences.set(
            .fixed(providerID: "review-provider", modelID: "review-model"),
            for: .review
        )

        let reloaded = NovelCreationModelPreferences(userDefaults: defaults)
        XCTAssertEqual(
            reloaded.policy(for: .creation),
            .fixed(providerID: "creative-provider", modelID: "creative-model")
        )
        XCTAssertEqual(
            reloaded.policy(for: .stateSync),
            .fixed(providerID: "sync-provider", modelID: "sync-model")
        )
        XCTAssertEqual(
            reloaded.policy(for: .review),
            .fixed(providerID: "review-provider", modelID: "review-model")
        )
    }

    func testDeleteMaterialKeepsImmutableHistoryAndExcludesFutureInjection() throws {
        let initial = try NovelTestFixtures.document()
        let withMaterial = try NovelReducer.apply(
            NovelTestFixtures.materialAction(document: initial),
            to: initial
        ).document
        let material = try XCTUnwrap(withMaterial.materials.first)
        let revisions = withMaterial.materialRevisions
        let deleted = try NovelReducer.apply(.deleteMaterial(NovelDeleteMaterialCommand(
            context: NovelTestFixtures.context(
                configRevision: withMaterial.project.configRevision
            ),
            projectID: withMaterial.project.id,
            materialID: material.id
        )), to: withMaterial).document

        XCTAssertTrue(try XCTUnwrap(deleted.materials.first).isDeleted)
        XCTAssertEqual(deleted.materialRevisions, revisions)
        let plan = try NovelInjectionPlanner.plan(
            document: deleted,
            request: NovelInjectionPlanningRequest(
                branchID: deleted.branches[0].id,
                promptKind: .discussion,
                userText: "Discuss magic."
            )
        )
        XCTAssertTrue(plan.materialDecisions.isEmpty)
        XCTAssertNoThrow(try NovelDocumentValidator.validateTransition(
            from: withMaterial,
            to: deleted
        ))
    }

    func testMaterialTombstoneDecodesOlderV1PayloadAsActive() throws {
        let initial = try NovelTestFixtures.document()
        let document = try NovelReducer.apply(
            NovelTestFixtures.materialAction(document: initial),
            to: initial
        ).document
        let encoded = try JSONEncoder().encode(document)
        var object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: encoded) as? [String: Any]
        )
        var materials = try XCTUnwrap(object["materials"] as? [[String: Any]])
        materials[0].removeValue(forKey: "isDeleted")
        object["materials"] = materials
        let legacy = try JSONSerialization.data(withJSONObject: object)

        let decoded = try JSONDecoder().decode(NovelProjectDocumentV1.self, from: legacy)

        XCTAssertFalse(try XCTUnwrap(decoded.materials.first).isDeleted)
        XCTAssertNoThrow(try NovelDocumentValidator.validate(decoded))
    }

    func testProposalAcceptanceAtomicallyCreatesMaterialAndResolvesProposal() throws {
        let source = try documentWithActiveProposal()
        let proposal = source.settingProposals[0]
        let materialID = NovelMaterialID()
        let revisionID = NovelMaterialRevisionID()
        let editedTitle = "用户确认后的名称"
        let editedContent = "用户在写入前修订后的完整设定。"
        let action = NovelAction.resolveSettingProposal(NovelResolveSettingProposalCommand(
            context: NovelTestFixtures.context(
                projectRevision: source.project.revision,
                configRevision: source.project.configRevision,
                branchHeadRevision: source.branches[0].headRevision
            ),
            projectID: source.project.id,
            proposalID: proposal.id,
            resolution: .accept(
                materialID: materialID,
                revisionID: revisionID,
                kind: .world,
                title: editedTitle,
                content: editedContent,
                tags: ["rule"],
                injectionMode: .always,
                aliases: []
            )
        ))

        let result = try NovelReducer.apply(action, to: source)

        XCTAssertTrue(result.document.settingProposals[0].isResolved)
        XCTAssertEqual(result.document.materials[0].id, materialID)
        XCTAssertEqual(result.document.materialRevisions[0].title, editedTitle)
        XCTAssertEqual(result.document.materialRevisions[0].content, editedContent)
        XCTAssertEqual(result.document.project.configRevision, source.project.configRevision + 1)
        XCTAssertNoThrow(try NovelDocumentValidator.validateTransition(
            from: source,
            to: result.document
        ))
    }

    func testEditedCharacterProposalKeepsTheSuggestedNameAsAnEffectiveAlias() throws {
        let source = try documentWithActiveProposal(
            title: "赵旧名",
            content: "模型最初建议的人物设定。"
        )
        let proposal = source.settingProposals[0]
        let materialID = NovelMaterialID()
        let revisionID = NovelMaterialRevisionID()
        let result = try NovelReducer.apply(.resolveSettingProposal(
            NovelResolveSettingProposalCommand(
                context: NovelTestFixtures.context(
                    projectRevision: source.project.revision,
                    configRevision: source.project.configRevision,
                    branchHeadRevision: source.branches[0].headRevision
                ),
                projectID: source.project.id,
                proposalID: proposal.id,
                resolution: .accept(
                    materialID: materialID,
                    revisionID: revisionID,
                    kind: .character,
                    title: "赵大来",
                    content: "用户写入前修改后的人物设定。",
                    tags: [],
                    injectionMode: .smart,
                    aliases: []
                )
            )
        ), to: source).document

        let effective = try NovelMaterialResolver.effectiveRevisions(
            document: result,
            branch: result.branches[0]
        )
        let character = try XCTUnwrap(effective.first { $0.material.id == materialID })

        XCTAssertEqual(character.revision.title, "赵大来")
        XCTAssertEqual(character.material.aliases, ["赵旧名"])
    }

    func testProposalAcceptanceRejectsEmptyEditedContent() throws {
        let source = try documentWithActiveProposal()
        let proposal = source.settingProposals[0]
        let action = NovelAction.resolveSettingProposal(NovelResolveSettingProposalCommand(
            context: NovelTestFixtures.context(
                projectRevision: source.project.revision,
                configRevision: source.project.configRevision,
                branchHeadRevision: source.branches[0].headRevision
            ),
            projectID: source.project.id,
            proposalID: proposal.id,
            resolution: .accept(
                materialID: NovelMaterialID(),
                revisionID: NovelMaterialRevisionID(),
                kind: .world,
                title: "月亮法则",
                content: " \n ",
                tags: [],
                injectionMode: .smart,
                aliases: []
            )
        ))

        XCTAssertThrowsError(try NovelReducer.apply(action, to: source)) { error in
            guard case .invalidInput = error as? NovelError else {
                return XCTFail("Expected empty proposal content to be rejected, got \(error)")
            }
        }
    }

    func testProposalRejectionDoesNotAdvanceConfigRevision() throws {
        let source = try documentWithActiveProposal()
        let result = try NovelReducer.apply(.resolveSettingProposal(
            NovelResolveSettingProposalCommand(
                context: NovelTestFixtures.context(
                    projectRevision: source.project.revision,
                    configRevision: source.project.configRevision,
                    branchHeadRevision: source.branches[0].headRevision
                ),
                projectID: source.project.id,
                proposalID: source.settingProposals[0].id,
                resolution: .reject
            )
        ), to: source)

        XCTAssertTrue(result.document.settingProposals[0].isResolved)
        XCTAssertEqual(result.document.materials, [])
        XCTAssertEqual(result.document.project.revision, source.project.revision + 1)
        XCTAssertEqual(result.document.project.configRevision, source.project.configRevision)
    }

    func testStaleProposalOutsideCurrentStateCannotBeResolved() throws {
        var source = try documentWithActiveProposal()
        source.stateSnapshots[0] = stateSnapshot(
            source.stateSnapshots[0],
            settingProposalIDs: []
        )
        let action = NovelAction.resolveSettingProposal(NovelResolveSettingProposalCommand(
            context: NovelTestFixtures.context(
                projectRevision: source.project.revision,
                configRevision: source.project.configRevision,
                branchHeadRevision: source.branches[0].headRevision
            ),
            projectID: source.project.id,
            proposalID: source.settingProposals[0].id,
            resolution: .reject
        ))

        XCTAssertThrowsError(try NovelReducer.apply(action, to: source)) { error in
            guard case .invalidInput = error as? NovelError else {
                return XCTFail("Expected stale proposal rejection, got \(error)")
            }
        }
    }

    func testBranchOverrideCreatesIndependentRevisionThenReturnsToInheritance() throws {
        let initial = try NovelTestFixtures.document()
        let withMaterial = try NovelReducer.apply(
            NovelTestFixtures.materialAction(document: initial),
            to: initial
        ).document
        let material = withMaterial.materials[0]
        let sharedRevisionID = material.currentRevisionID
        let overrideRevisionID = NovelMaterialRevisionID()
        let override = NovelAction.setBranchMaterialOverride(NovelSetBranchMaterialOverrideCommand(
            context: NovelTestFixtures.context(
                projectRevision: withMaterial.project.revision,
                configRevision: withMaterial.project.configRevision,
                branchHeadRevision: withMaterial.branches[0].headRevision
            ),
            projectID: withMaterial.project.id,
            branchID: withMaterial.branches[0].id,
            materialID: material.id,
            change: .createRevision(
                revisionID: overrideRevisionID,
                title: "Branch Rule",
                content: "Magic is free on this branch.",
                tags: ["magic"],
                injectionMode: .off
            )
        ))
        let overridden = try NovelReducer.apply(override, to: withMaterial).document

        XCTAssertEqual(overridden.materials[0].currentRevisionID, sharedRevisionID)
        XCTAssertEqual(overridden.branches[0].overrideRevisionIDs, [overrideRevisionID])
        let plan = try NovelInjectionPlanner.plan(
            document: overridden,
            request: NovelInjectionPlanningRequest(
                branchID: overridden.branches[0].id,
                promptKind: .discussion,
                userText: "Discuss magic."
            )
        )
        XCTAssertEqual(plan.materialDecisions.first?.revisionID, overrideRevisionID)
        XCTAssertEqual(plan.materialDecisions.first?.reason, .branchOverride)

        let inherited = try NovelReducer.apply(.setBranchMaterialOverride(
            NovelSetBranchMaterialOverrideCommand(
                context: NovelTestFixtures.context(
                    projectRevision: overridden.project.revision,
                    configRevision: overridden.project.configRevision,
                    branchHeadRevision: overridden.branches[0].headRevision
                ),
                projectID: overridden.project.id,
                branchID: overridden.branches[0].id,
                materialID: material.id,
                change: .inherit
            )
        ), to: overridden).document
        XCTAssertTrue(inherited.branches[0].overrideRevisionIDs.isEmpty)
        XCTAssertEqual(inherited.materials[0].currentRevisionID, sharedRevisionID)
        XCTAssertNoThrow(try NovelDocumentValidator.validateTransition(
            from: overridden,
            to: inherited
        ))
    }

    func testInjectionPreviewUsesGenerationBudgetWithoutStartingProviderOrPersistingReceipt() async throws {
        let repository = InMemoryNovelProjectRepository()
        let document = try NovelTestFixtures.document()
        _ = try await repository.createProject(document)
        let adapter = ScriptedNovelModelAdapter(
            resolvedModel: NovelResolvedModel(
                providerID: "transport-provider",
                ownerProviderID: "owner-provider",
                modelID: "stable-model",
                wireModelID: "wire-model",
                displayName: "Preview Model",
                contextWindowTokens: 20_000
            )
        )
        let creation = DefaultNovelCreation(repository: repository, modelRunner: adapter)

        let snapshot = try await creation.snapshot(.injectionPreview(
            NovelInjectionPreviewRequest(
                projectID: document.project.id,
                branchID: document.branches[0].id,
                kind: .prose,
                mode: .writeProse,
                granularity: .wholeChapter,
                userText: "Write the next chapter.",
                sourceChapterVersionID: nil,
                injectionOverrides: .none,
                inputBudgetTokens: 16_000
            )
        ))
        guard case .injectionPreview(let preview) = snapshot else {
            return XCTFail("Expected injection preview snapshot.")
        }

        XCTAssertEqual(preview.projectRevision, document.project.revision)
        XCTAssertEqual(preview.branchHeadRevision, document.branches[0].headRevision)
        XCTAssertEqual(preview.resolvedModel.modelID, "stable-model")
        XCTAssertEqual(preview.effectiveInputBudgetTokens, 10_784)
        let modelRequests = await adapter.requests
        XCTAssertEqual(modelRequests.count, 0)
        let reloaded = try await repository.loadProject(id: document.project.id)
        XCTAssertTrue(reloaded.document.injectionReceipts.isEmpty)
        XCTAssertTrue(reloaded.document.generationReceipts.isEmpty)
    }

    func testProjectImportPreviewReportsConflictWithoutMutatingPackage() async throws {
        let repository = InMemoryNovelProjectRepository()
        let document = try NovelTestFixtures.document()
        _ = try await repository.createProject(document)
        let artifact = try NovelProjectPackageCodec.encode(document)
        let creation = DefaultNovelCreation(repository: repository)

        let snapshot = try await creation.snapshot(.projectImportPreview(artifact.data))
        guard case .projectImportPreview(let preview) = snapshot else {
            return XCTFail("Expected import preview snapshot.")
        }

        XCTAssertEqual(preview.sourceProjectID, document.project.id)
        XCTAssertEqual(preview.projectSHA256, artifact.projectSHA256)
        XCTAssertEqual(preview.existingProject?.revision, document.project.revision)
        XCTAssertEqual(preview.runningRunCount, 0)
        let projects = try await repository.listProjects()
        XCTAssertEqual(projects.count, 1)
    }

    private func documentWithActiveProposal(
        title: String = "Moon Rule",
        content: String = "The moon remembers every oath."
    ) throws -> NovelProjectDocumentV1 {
        var document = try NovelTestFixtures.document()
        let proposal = NovelSettingProposalRecord(
            id: NovelProposalID(),
            branchID: document.branches[0].id,
            title: title,
            content: content,
            createdAt: document.project.updatedAt,
            isResolved: false
        )
        document.settingProposals.append(proposal)
        document.stateSnapshots[0] = stateSnapshot(
            document.stateSnapshots[0],
            settingProposalIDs: [proposal.id]
        )
        try NovelDocumentValidator.validate(document)
        return document
    }

    private func stateSnapshot(
        _ source: NovelStateSnapshotRecord,
        settingProposalIDs: [NovelProposalID]
    ) -> NovelStateSnapshotRecord {
        NovelStateSnapshotRecord(
            id: source.id,
            eventIDs: source.eventIDs,
            summary: source.summary,
            branchOutline: source.branchOutline,
            unresolvedEntityNames: source.unresolvedEntityNames,
            createdAt: source.createdAt,
            settingProposalIDs: settingProposalIDs
        )
    }
}

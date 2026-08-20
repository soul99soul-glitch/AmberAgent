import Foundation

enum NovelWorkspaceImporter {
    static func makeDocument(
        from files: [NovelWorkspaceBackup.File],
        now: Date = Date()
    ) throws -> NovelProjectDocumentV1 {
        let parsed = try ParsedWorkspace(files: files)
        guard parsed.format == NovelWorkspaceBackup.format else {
            throw NovelError.invalidPackage("Unrecognized workspace format.")
        }
        guard parsed.formatVersion == NovelWorkspaceBackup.formatVersion else {
            throw NovelError.unsupportedSchema(parsed.formatVersion)
        }

        let projectID = NovelProjectID()
        let branchID = NovelBranchID()
        let created = try NovelReducer.createProject(
            NovelCreateProjectCommand(
                context: NovelMutationContext(
                    operationID: NovelOperationID(),
                    expectedProjectRevision: nil,
                    expectedConfigRevision: nil,
                    expectedBranchHeadRevision: nil
                ),
                projectID: projectID,
                branchID: branchID,
                sessionID: NovelSessionID(),
                initialStateSnapshotID: NovelStateSnapshotID(),
                initialCheckpointID: NovelCheckpointID(),
                name: parsed.projectTitle,
                branchName: parsed.branchTitle ?? parsed.mainBranchName,
                creationMode: .blank,
                quickStartSeed: nil
            ),
            now: now
        ).document
        var document = created

        if !parsed.polishPreference.isEmpty {
            document = try NovelReducer.apply(
                .setPolishPreference(NovelSetPolishPreferenceCommand(
                    context: context(document, branchID: nil),
                    projectID: projectID,
                    preference: parsed.polishPreference
                )),
                to: document,
                now: now
            ).document
        }

        for material in parsed.materials {
            document = try NovelReducer.apply(
                .reviseMaterial(NovelReviseMaterialCommand(
                    context: context(document, branchID: nil),
                    projectID: projectID,
                    materialID: material.id,
                    revisionID: NovelMaterialRevisionID(),
                    kind: material.kind,
                    title: material.title,
                    content: material.content,
                    tags: [],
                    injectionMode: material.injection,
                    aliases: material.aliases
                )),
                to: document,
                now: now
            ).document
        }

        for chapter in parsed.workingChapters {
            document = try appendChapter(
                title: chapter.title,
                content: chapter.content,
                to: document,
                now: now
            )
        }

        for discarded in parsed.discardedChapters {
            document = try appendChapter(
                title: discarded.title,
                content: discarded.content,
                to: document,
                now: now
            )
            let chapterID = document.branches[0].workingChapterSelections.last!.chapterID
            document = try NovelReducer.apply(
                .discardChapter(NovelDiscardChapterCommand(
                    context: context(document, branchID: branchID),
                    projectID: projectID,
                    branchID: branchID,
                    chapterID: chapterID
                )),
                to: document,
                now: now
            ).document
            if let index = document.branches.firstIndex(where: { $0.id == branchID }) {
                document.branches[index].syncStatus = .synchronized
            }
        }

        if let plan = parsed.plan {
            document = try NovelReducer.apply(
                .upsertChapterPlan(NovelUpsertChapterPlanCommand(
                    context: context(document, branchID: branchID),
                    projectID: projectID,
                    branchID: branchID,
                    planID: plan.id,
                    status: plan.status,
                    outlinePlacement: plan.outlinePlacement,
                    goalAndConflict: plan.goalAndConflict,
                    mustHappen: plan.mustHappen,
                    mustNotHappen: plan.mustNotHappen,
                    endingHook: plan.endingHook,
                    visibleFacts: plan.visibleFacts
                )),
                to: document,
                now: now
            ).document
        }

        if !parsed.upcomingBeats.isEmpty {
            document = try NovelReducer.apply(
                .upsertUpcomingArc(NovelUpsertUpcomingArcCommand(
                    context: context(document, branchID: branchID),
                    projectID: projectID,
                    branchID: branchID,
                    beats: parsed.upcomingBeats
                )),
                to: document,
                now: now
            ).document
        }

        if parsed.collaborationMode != document.project.collaborationMode {
            document = try NovelReducer.apply(
                .setCollaborationMode(NovelSetCollaborationModeCommand(
                    context: context(document, branchID: branchID),
                    projectID: projectID,
                    branchID: branchID,
                    mode: parsed.collaborationMode
                )),
                to: document,
                now: now
            ).document
        }

        applyPlot(parsed, to: &document)
        applyInboxProposals(parsed, to: &document, now: now)
        applyWorkspacePassthrough(parsed, to: &document)
        if !parsed.hasPlotFiles,
           let branchIndex = document.branches.firstIndex(where: {
               $0.id == document.project.mainBranchID
           }) {
            // Contract conventions §7: a workspace without plot/ imports as
            // needsSync (previously every import claimed synchronized).
            document.branches[branchIndex].syncStatus = .needsSync
        }
        try NovelDocumentValidator.validate(document)
        return document
    }

    static func packageData(
        from files: [NovelWorkspaceBackup.File],
        now: Date = Date()
    ) throws -> Data {
        let document = try makeDocument(from: files, now: now)
        return try NovelProjectPackageCodec.encode(document).data
    }

    static func packageData(fromDirectory url: URL) throws -> Data {
        try packageData(from: NovelWorkspaceFolderDocument.files(fromDirectory: url))
    }
}

private extension NovelWorkspaceImporter {
    static func context(
        _ document: NovelProjectDocumentV1,
        branchID: NovelBranchID?
    ) -> NovelMutationContext {
        let branch = branchID.flatMap { id in document.branches.first { $0.id == id } }
            ?? document.branches.first
        return NovelMutationContext(
            operationID: NovelOperationID(),
            expectedProjectRevision: document.project.revision,
            expectedConfigRevision: document.project.configRevision,
            expectedBranchHeadRevision: branch?.headRevision
        )
    }

    static func appendChapter(
        title: String,
        content: String,
        to document: NovelProjectDocumentV1,
        now: Date
    ) throws -> NovelProjectDocumentV1 {
        var next = document
        if let index = next.branches.firstIndex(where: { $0.id == document.project.mainBranchID }) {
            next.branches[index].syncStatus = .synchronized
        }
        let branch = next.branches.first { $0.id == next.project.mainBranchID }!
        let request = NovelRunRequest(
            id: NovelRunID(),
            operationID: NovelOperationID(),
            projectID: next.project.id,
            branchID: branch.id,
            kind: .prose,
            mode: .writeProse,
            granularity: .wholeChapter,
            userText: "Import \(title)",
            userMessageID: NovelMessageID(),
            assistantMessageID: NovelMessageID(),
            candidateID: NovelCandidateID(),
            generationReceiptID: NovelReceiptID(),
            injectionReceiptID: NovelReceiptID(),
            sourceChapterVersionID: nil,
            expectedProjectRevision: next.project.revision,
            expectedConfigRevision: next.project.configRevision,
            expectedBranchHeadRevision: branch.headRevision
        )
        let plan = try NovelInjectionPlanner.plan(
            document: next,
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
            overrides: request.injectionOverrides,
            providerID: "workspace-import",
            modelID: "workspace-import",
            parameters: [:],
            createdAt: now
        )
        let generation = NovelGenerationReceiptRecord(
            id: request.generationReceiptID,
            runID: request.id,
            providerID: injection.providerID,
            modelID: injection.modelID,
            promptVersion: injection.promptVersion,
            injectionReceiptID: injection.id,
            parameters: injection.parameters,
            requestSHA256: NovelDocumentValidator.sha256(plan.canonicalInput + "\nMODEL REQUEST"),
            createdAt: now
        )
        next = try NovelGenerationReducer.begin(
            request,
            artifacts: NovelGenerationStartArtifacts(
                injectionReceipt: injection,
                generationReceipt: generation
            ),
            in: next,
            now: now
        ).document
        next = try NovelGenerationReducer.complete(
            runID: request.id,
            content: content,
            in: next,
            now: now
        ).document
        let command = NovelCollectCandidateCommand(
            context: context(next, branchID: branch.id),
            projectID: next.project.id,
            branchID: branch.id,
            pendingID: NovelPendingOperationID(),
            candidateID: request.candidateID!,
            selection: NovelParagraphSelection(
                paragraphIDs: NovelParagraphParser.paragraphs(in: content).map(\.id)
            ),
            target: .createNextChapter(chapterID: NovelChapterID(), title: title),
            proposedChapterVersionID: NovelChapterVersionID(),
            checkpointID: NovelCheckpointID(),
            stateSnapshotID: NovelStateSnapshotID(),
            factCompatibilityID: UUID()
        )
        next = try NovelFactTransactionReducer.commitCollectionWithoutStateSync(
            command,
            payloadSHA256: command.canonicalPayloadSHA256(),
            in: next,
            now: now
        ).document
        if let index = next.branches.firstIndex(where: { $0.id == branch.id }) {
            next.branches[index].syncStatus = .synchronized
        }
        return next
    }

    static func applyPlot(_ parsed: ParsedWorkspace, to document: inout NovelProjectDocumentV1) {
        guard let branchIndex = document.branches.firstIndex(where: {
            $0.id == document.project.mainBranchID
        }) else { return }
        let snapshotID = document.branches[branchIndex].currentStateSnapshotID
        guard let snapshotIndex = document.stateSnapshots.firstIndex(where: { $0.id == snapshotID }) else {
            return
        }
        let old = document.stateSnapshots[snapshotIndex]
        var eventIDs = old.eventIDs
        if let lines = parsed.plotEvents, !lines.isEmpty {
            var nextSequence = (document.events.map(\.sequence).max() ?? -1) + 1
            for line in lines {
                let event = NovelStoryEventRecord(
                    id: NovelEventID(),
                    sequence: nextSequence,
                    kind: "import",
                    summary: line,
                    entityReferences: [],
                    createdAt: old.createdAt
                )
                nextSequence += 1
                document.events.append(event)
                eventIDs.append(event.id)
            }
        }
        let branch = document.branches[branchIndex]
        let working = NovelWorkspaceLedger.liveWorkingSelections(branch: branch, in: document)
        let modules = NovelWorkspaceLedger.alignedModules(
            existing: old.chapterPlots,
            working: working,
            seeds: NovelWorkspaceLedger.seedTexts(working: working, in: document)
        )
        let highlights = parsed.highlights
            ?? NovelWorkspaceLedger.foldedHighlightTexts(modules)
        document.stateSnapshots[snapshotIndex] = NovelStateSnapshotRecord(
            id: old.id,
            eventIDs: eventIDs,
            summary: parsed.plotSummary ?? old.summary,
            branchOutline: parsed.plotOutline ?? old.branchOutline,
            unresolvedEntityNames: old.unresolvedEntityNames,
            createdAt: old.createdAt,
            settingProposalIDs: old.settingProposalIDs,
            characterIdentityClarifications: old.characterIdentityClarifications,
            recentWrittenHighlights: highlights,
            chapterPlots: modules
        )
    }

    /// Inbox files become pending setting proposals so imported books keep
    /// their unconfirmed material visible (they were silently dropped before
    /// contract v1.1). Frontmatter ids are preserved for export stability.
    static func applyInboxProposals(
        _ parsed: ParsedWorkspace,
        to document: inout NovelProjectDocumentV1,
        now: Date
    ) {
        guard !parsed.inboxProposals.isEmpty,
              let branchIndex = document.branches.firstIndex(where: {
                  $0.id == document.project.mainBranchID
              }) else { return }
        let branchID = document.branches[branchIndex].id
        var proposalIDs: [NovelProposalID] = []
        for proposal in parsed.inboxProposals {
            document.settingProposals.append(NovelSettingProposalRecord(
                id: proposal.id,
                branchID: branchID,
                title: proposal.title,
                content: proposal.content,
                createdAt: now,
                isResolved: false
            ))
            proposalIDs.append(proposal.id)
        }
        let snapshotID = document.branches[branchIndex].currentStateSnapshotID
        guard let snapshotIndex = document.stateSnapshots.firstIndex(where: {
            $0.id == snapshotID
        }) else { return }
        let old = document.stateSnapshots[snapshotIndex]
        document.stateSnapshots[snapshotIndex] = NovelStateSnapshotRecord(
            id: old.id,
            eventIDs: old.eventIDs,
            summary: old.summary,
            branchOutline: old.branchOutline,
            unresolvedEntityNames: old.unresolvedEntityNames,
            createdAt: old.createdAt,
            settingProposalIDs: old.settingProposalIDs + proposalIDs,
            characterIdentityClarifications: old.characterIdentityClarifications,
            recentWrittenHighlights: old.recentWrittenHighlights,
            chapterPlots: old.chapterPlots
        )
    }

    /// Collects opaque content into the document passthrough section so the
    /// exporter can write it back unchanged (core contract v1.1 §3.6).
    static func applyWorkspacePassthrough(
        _ parsed: ParsedWorkspace,
        to document: inout NovelProjectDocumentV1
    ) {
        var passthrough = NovelWorkspacePassthroughRecord.empty
        for material in parsed.materials where !material.extensionLines.isEmpty {
            passthrough.frontmatterExtensions[material.id.description] =
                material.extensionLines
        }
        for proposal in parsed.inboxProposals where !proposal.extensionLines.isEmpty {
            passthrough.frontmatterExtensions[proposal.id.description] =
                proposal.extensionLines
        }
        if let branch = document.branches.first(where: {
            $0.id == document.project.mainBranchID
        }) {
            let selections = branch.workingChapterSelections
            for (index, chapter) in parsed.workingChapters.enumerated()
            where !chapter.extensionLines.isEmpty {
                guard index < selections.count else { break }
                passthrough.frontmatterExtensions[
                    selections[index].chapterID.description
                ] = chapter.extensionLines
            }
            for (offset, chapter) in parsed.discardedChapters.enumerated()
            where !chapter.extensionLines.isEmpty {
                let index = parsed.workingChapters.count + offset
                guard index < selections.count else { break }
                passthrough.frontmatterExtensions[
                    selections[index].chapterID.description
                ] = chapter.extensionLines
            }
            if !parsed.branchExtensionLines.isEmpty {
                passthrough.frontmatterExtensions["branch:\(branch.id)"] =
                    parsed.branchExtensionLines
            }
            if !parsed.upcomingExtensionLines.isEmpty {
                passthrough.frontmatterExtensions["upcoming:\(branch.id)"] =
                    parsed.upcomingExtensionLines
            }
            // Anchored to the branch (not the snapshot id): snapshots rotate
            // on every write, which would orphan the extensions.
            if !parsed.plotExtensionLines.isEmpty {
                passthrough.frontmatterExtensions["plot-current:\(branch.id)"] =
                    parsed.plotExtensionLines
            }
            if !parsed.outlineExtensionLines.isEmpty {
                passthrough.frontmatterExtensions["plot-outline:\(branch.id)"] =
                    parsed.outlineExtensionLines
            }
            if !parsed.eventsExtensionLines.isEmpty {
                passthrough.frontmatterExtensions["plot-events:\(branch.id)"] =
                    parsed.eventsExtensionLines
            }
        }
        if let plan = parsed.plan, !plan.extensionLines.isEmpty {
            passthrough.frontmatterExtensions[plan.id.description] =
                plan.extensionLines
        }
        if !parsed.projectExtensionLines.isEmpty {
            passthrough.frontmatterExtensions["project:\(document.project.id)"] =
                parsed.projectExtensionLines
        }
        for (path, contents) in parsed.opaqueFiles {
            passthrough.opaqueFiles[path] = contents
        }
        document.workspacePassthrough = passthrough
    }
}

private struct ParsedWorkspace {
    let format: String
    let formatVersion: Int
    let projectID: NovelProjectID?
    let projectTitle: String
    let polishPreference: String
    let collaborationMode: NovelCollaborationMode
    let mainBranchName: String
    /// Human-readable title from branch.md; the manifest only carries the
    /// slug, so preferring it keeps branch names stable across round trips.
    let branchTitle: String?
    let mainBranchID: NovelBranchID?
    let materials: [ParsedMaterial]
    let workingChapters: [ParsedChapter]
    let discardedChapters: [ParsedChapter]
    let plotSummary: String?
    let plotOutline: String?
    let plotEvents: [String]?
    let highlights: [String]?
    let plan: ParsedPlan?
    let upcomingBeats: [String]
    let inboxProposals: [ParsedProposal]
    /// Files iOS has no semantic mapping for; preserved verbatim so an
    /// export after import never drops them (core contract v1.1 §3.6).
    let opaqueFiles: [String: String]
    let hasPlotFiles: Bool
    let projectExtensionLines: [String]
    let branchExtensionLines: [String]
    let plotExtensionLines: [String]
    let outlineExtensionLines: [String]
    let eventsExtensionLines: [String]
    let upcomingExtensionLines: [String]

    struct ParsedMaterial {
        let id: NovelMaterialID
        let kind: NovelMaterialKind
        let title: String
        let content: String
        let injection: NovelInjectionMode
        let aliases: [String]
        let extensionLines: [String]
    }

    struct ParsedChapter {
        let title: String
        let content: String
        let ordinal: Int
        let extensionLines: [String]
    }

    struct ParsedProposal {
        let id: NovelProposalID
        let title: String
        let content: String
        let extensionLines: [String]
    }

    struct ParsedPlan {
        let id: NovelChapterPlanID
        let status: NovelChapterPlanStatus
        let outlinePlacement: String
        let goalAndConflict: String
        let mustHappen: [String]
        let mustNotHappen: [String]
        let endingHook: String
        let visibleFacts: [String]
        let extensionLines: [String]
    }

    init(files: [NovelWorkspaceBackup.File]) throws {
        let byPath = Dictionary(uniqueKeysWithValues: files.map { ($0.path, $0.contents) })
        guard let manifestText = byPath["manifest.yaml"] else {
            throw NovelError.invalidPackage("Workspace is missing manifest.yaml.")
        }
        let manifest = NovelWorkspaceMarkdown.parseMapping(manifestText)
        format = manifest["format"] ?? ""
        formatVersion = Int(manifest["formatVersion"] ?? "") ?? 0
        projectID = NovelWorkspaceMarkdown.identifier(manifest["source.projectID"] ?? manifest["projectID"])
        mainBranchName = manifest["mainBranch"].flatMap { $0.isEmpty ? nil : $0 } ?? "Main"

        let projectFile = NovelWorkspaceMarkdown.parseFile(byPath["project.md"] ?? "")
        projectTitle = projectFile.fields["title"].flatMap { $0.isEmpty ? nil : $0 } ?? "Untitled"
        polishPreference = projectFile.fields["polishPreference"] ?? ""
        collaborationMode = NovelCollaborationMode(
            rawValue: projectFile.fields["collaborationMode"] ?? ""
        ) ?? .cocreation

        var materials: [ParsedMaterial] = []
        var working: [ParsedChapter] = []
        var discarded: [ParsedChapter] = []
        var plotSummary: String?
        var plotOutline: String?
        var plotEvents: [String]?
        var highlights: [String]?
        var plan: ParsedPlan?
        var upcoming: [String] = []
        var branchID: NovelBranchID?
        var branchTitle: String?
        var inboxProposals: [ParsedProposal] = []
        var opaqueFiles: [String: String] = [:]
        var hasPlotFiles = false
        var projectExtensionLines = NovelWorkspaceMarkdown.extensionLines(
            in: byPath["project.md"] ?? "",
            knownKeys: Self.projectKnownFrontmatterKeys
        )
        var branchExtensionLines: [String] = []
        var plotExtensionLines: [String] = []
        var outlineExtensionLines: [String] = []
        var eventsExtensionLines: [String] = []
        var upcomingExtensionLines: [String] = []

        let mainPrefix = "branches/\(mainBranchName)/"
        for file in files {
            let parsed = NovelWorkspaceMarkdown.parseFile(file.contents)
            let onMain = file.path.hasPrefix(mainPrefix)
            if file.path == "manifest.yaml" || file.path == "project.md" {
                continue
            } else if file.path.hasPrefix("setting/") && !file.path.hasPrefix("branches/") {
                guard let kind = Self.materialKind(path: file.path, fields: parsed.fields) else {
                    // Unknown materialKind values stay opaque so a future or
                    // cross-platform kind never silently becomes `world`.
                    opaqueFiles[file.path] = file.contents
                    continue
                }
                materials.append(ParsedMaterial(
                    id: NovelWorkspaceMarkdown.identifier(parsed.fields["id"]) ?? NovelMaterialID(),
                    kind: kind,
                    title: parsed.fields["title"] ?? Self.fileNameTitle(file.path),
                    content: parsed.body,
                    injection: NovelInjectionMode(rawValue: parsed.fields["injection"] ?? "") ?? .smart,
                    aliases: parsed.lists["aliases"] ?? [],
                    extensionLines: NovelWorkspaceMarkdown.extensionLines(
                        in: file.contents,
                        knownKeys: Self.materialKnownFrontmatterKeys
                    )
                ))
            } else if file.path.hasPrefix("inbox/"), file.path.hasSuffix(".md") {
                inboxProposals.append(ParsedProposal(
                    id: NovelWorkspaceMarkdown.identifier(parsed.fields["id"]) ?? NovelProposalID(),
                    title: parsed.fields["title"] ?? Self.fileNameTitle(file.path),
                    content: parsed.body,
                    extensionLines: NovelWorkspaceMarkdown.extensionLines(
                        in: file.contents,
                        knownKeys: Self.proposalKnownFrontmatterKeys
                    )
                ))
            } else if onMain, file.path.contains("/chapters/"), file.path.hasSuffix(".md") {
                let ordinal = Self.chapterOrdinal(from: file.path) ?? (working.count + 1)
                working.append(ParsedChapter(
                    title: parsed.fields["title"] ?? Self.fileNameTitle(file.path),
                    content: parsed.body,
                    ordinal: ordinal,
                    extensionLines: NovelWorkspaceMarkdown.extensionLines(
                        in: file.contents,
                        knownKeys: Self.chapterKnownFrontmatterKeys
                    )
                ))
            } else if onMain, file.path.contains("/discarded/"), file.path.hasSuffix(".md") {
                discarded.append(ParsedChapter(
                    title: parsed.fields["title"] ?? Self.fileNameTitle(file.path),
                    content: parsed.body,
                    ordinal: discarded.count + 1,
                    extensionLines: NovelWorkspaceMarkdown.extensionLines(
                        in: file.contents,
                        knownKeys: Self.chapterKnownFrontmatterKeys
                    )
                ))
            } else if onMain, file.path.hasSuffix("/plot/current.md") {
                hasPlotFiles = true
                let split = NovelWorkspaceMarkdown.splitHighlights(parsed.body)
                plotSummary = split.body
                highlights = split.highlights
                plotExtensionLines = NovelWorkspaceMarkdown.extensionLines(
                    in: file.contents,
                    knownKeys: Self.plotKnownFrontmatterKeys
                )
            } else if onMain, file.path.hasSuffix("/plot/outline.md") {
                hasPlotFiles = true
                plotOutline = parsed.body
                outlineExtensionLines = NovelWorkspaceMarkdown.extensionLines(
                    in: file.contents,
                    knownKeys: Self.plotKnownFrontmatterKeys
                )
            } else if onMain, file.path.hasSuffix("/plot/events.md") {
                hasPlotFiles = true
                plotEvents = parsed.body
                    .split(whereSeparator: \.isNewline)
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .map { $0.hasPrefix("- ") ? String($0.dropFirst(2)) : $0 }
                    .filter { !$0.isEmpty }
                eventsExtensionLines = NovelWorkspaceMarkdown.extensionLines(
                    in: file.contents,
                    knownKeys: Self.plotKnownFrontmatterKeys
                )
            } else if onMain, file.path.hasSuffix("/plan/this-chapter.md") {
                plan = Self.parsePlan(
                    parsed,
                    extensionLines: NovelWorkspaceMarkdown.extensionLines(
                        in: file.contents,
                        knownKeys: Self.plotKnownFrontmatterKeys.union(["status"])
                    )
                )
            } else if onMain, file.path.hasSuffix("/plan/upcoming.md") {
                let beats = parsed.body
                    .split(whereSeparator: \.isNewline)
                    .map { $0.trimmingCharacters(in: .whitespaces) }
                    .map { $0.hasPrefix("- ") ? String($0.dropFirst(2)) : $0 }
                    .filter { !$0.isEmpty }
                if beats.isEmpty {
                    // No semantic arc content: keep the file verbatim so a
                    // round trip never drops it (contract §3.6 no-drop).
                    opaqueFiles[file.path] = file.contents
                } else {
                    upcoming = beats
                    upcomingExtensionLines = NovelWorkspaceMarkdown.extensionLines(
                        in: file.contents,
                        knownKeys: Self.plotKnownFrontmatterKeys
                    )
                }
            } else if onMain, file.path.hasSuffix("/branch.md") {
                branchID = NovelWorkspaceMarkdown.identifier(parsed.fields["id"])
                branchTitle = parsed.fields["title"].flatMap { $0.isEmpty ? nil : $0 }
                branchExtensionLines = NovelWorkspaceMarkdown.extensionLines(
                    in: file.contents,
                    knownKeys: Self.branchKnownFrontmatterKeys
                )
            } else if file.path.hasPrefix("drafts/") {
                // Candidate records carry generation-time references that a
                // markdown file cannot express; keep drafts opaque instead of
                // dropping them (they were silently lost before contract v1.1).
                opaqueFiles[file.path] = file.contents
            } else {
                // Foreshadowing nodes, unknown setting subfolders on branches,
                // unknown top-level entries: preserved verbatim (D-F).
                opaqueFiles[file.path] = file.contents
            }
        }

        self.materials = materials
        self.workingChapters = working.sorted { $0.ordinal < $1.ordinal }
        self.discardedChapters = discarded
        self.plotSummary = plotSummary
        self.plotOutline = plotOutline
        self.plotEvents = plotEvents
        self.highlights = highlights
        self.plan = plan
        self.upcomingBeats = upcoming
        self.mainBranchID = branchID
        self.branchTitle = branchTitle
        self.inboxProposals = inboxProposals
        self.opaqueFiles = opaqueFiles
        self.hasPlotFiles = hasPlotFiles
        self.projectExtensionLines = projectExtensionLines
        self.branchExtensionLines = branchExtensionLines
        self.plotExtensionLines = plotExtensionLines
        self.outlineExtensionLines = outlineExtensionLines
        self.eventsExtensionLines = eventsExtensionLines
        self.upcomingExtensionLines = upcomingExtensionLines
    }

    static let materialKnownFrontmatterKeys: Set<String> = [
        "id", "kind", "title", "materialKind", "injection",
        "sourceVersionID", "customName", "override", "aliases",
    ]
    static let chapterKnownFrontmatterKeys: Set<String> = [
        "id", "kind", "title", "ordinal", "sourceVersionID",
    ]
    static let proposalKnownFrontmatterKeys: Set<String> = [
        "id", "kind", "title", "materialKind",
    ]
    static let plotKnownFrontmatterKeys: Set<String> = [
        "id", "kind", "title",
    ]
    static let branchKnownFrontmatterKeys: Set<String> = [
        "id", "kind", "title", "syncStatus",
    ]
    static let projectKnownFrontmatterKeys: Set<String> = [
        "id", "kind", "title", "collaborationMode", "polishPreference",
    ]

    private static let knownMaterialKindValues: Set<String> = [
        "world", "character", "relationship", "masterOutline",
        "writingRequirements", "decisionLog", "custom",
    ]

    /// Nil when an explicit `materialKind` value is unknown to this host:
    /// such files stay opaque instead of being reinterpreted (contract §3.6).
    private static func materialKind(path: String, fields: [String: String]) -> NovelMaterialKind? {
        if let raw = fields["materialKind"] {
            guard knownMaterialKindValues.contains(raw) else { return nil }
            switch raw {
            case "world": return .world
            case "character": return .character
            case "relationship": return .relationship
            case "masterOutline": return .masterOutline
            case "writingRequirements": return .writingRequirements
            case "decisionLog": return .decisionLog
            case "custom":
                return .custom(fields["customName"] ?? "自定义")
            default:
                return nil
            }
        }
        if path.contains("/characters/") { return .character }
        if path.contains("/relationships/") { return .relationship }
        if path.contains("/outline/") || path.contains("master-outline") { return .masterOutline }
        if path.contains("/writing/") || path.contains("writing-requirements") {
            return .writingRequirements
        }
        if path.contains("/log/") || path.contains("decision-log") { return .decisionLog }
        if path.contains("/custom/") { return .custom(fields["customName"] ?? "自定义") }
        return .world
    }

    private static func chapterOrdinal(from path: String) -> Int? {
        let name = URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
        let prefix = name.prefix(while: { $0.isNumber })
        return prefix.isEmpty ? nil : Int(prefix)
    }

    private static func fileNameTitle(_ path: String) -> String {
        let name = URL(fileURLWithPath: path).deletingPathExtension().lastPathComponent
        if let dash = name.firstIndex(of: "-"),
           name[..<dash].allSatisfy(\.isNumber) {
            return String(name[name.index(after: dash)...])
        }
        return name
    }

    private static func parsePlan(
        _ parsed: NovelWorkspaceMarkdown.ParsedFile,
        extensionLines: [String]
    ) -> ParsedPlan {
        let sections = NovelWorkspaceMarkdown.sections(parsed.body)
        return ParsedPlan(
            id: NovelWorkspaceMarkdown.identifier(parsed.fields["id"]) ?? NovelChapterPlanID(),
            status: NovelChapterPlanStatus(rawValue: parsed.fields["status"] ?? "") ?? .draft,
            outlinePlacement: sections["位置"] ?? "",
            goalAndConflict: sections["目标与冲突"] ?? "Imported plan",
            mustHappen: sections["必须发生"].map(NovelWorkspaceMarkdown.bullets) ?? [],
            mustNotHappen: sections["不可发生"].map(NovelWorkspaceMarkdown.bullets) ?? [],
            endingHook: sections["收束"] ?? "",
            visibleFacts: sections["可见事实"].map(NovelWorkspaceMarkdown.bullets) ?? [],
            extensionLines: extensionLines
        )
    }
}

enum NovelWorkspaceMarkdown {
    struct ParsedFile {
        var fields: [String: String] = [:]
        var lists: [String: [String]] = [:]
        var body: String = ""
    }

    static func parseFile(_ text: String) -> ParsedFile {
        var parsed = ParsedFile()
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("---") else {
            parsed.body = trimmed
            return parsed
        }
        let rest = trimmed.dropFirst(3).drop(while: { $0 == "\n" || $0 == "\r" })
        guard let end = rest.range(of: "\n---") else {
            parsed.body = trimmed
            return parsed
        }
        let front = String(rest[..<end.lowerBound])
        parsed.body = String(rest[end.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        var currentList: String?
        for rawLine in front.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(rawLine)
            if line.hasPrefix("  - ") || line.hasPrefix("- ") {
                let value = line.trimmingCharacters(in: .whitespaces)
                    .dropFirst(2)
                    .trimmingCharacters(in: .whitespaces)
                if let key = currentList {
                    parsed.lists[key, default: []].append(unquote(String(value)))
                }
                continue
            }
            currentList = nil
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
            if value.isEmpty {
                currentList = key
                parsed.lists[key] = []
            } else {
                parsed.fields[key] = unquote(value)
            }
        }
        return parsed
    }

    /// Frontmatter lines whose keys are not in `knownKeys`, preserved verbatim
    /// and in original relative order (core contract v1.1 §3.6: unknown fields
    /// such as node `status`/`relations` pass through opaque, never dropped).
    /// List items follow their owning key; lines without a colon are kept.
    static func extensionLines(in text: String, knownKeys: Set<String>) -> [String] {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("---") else { return [] }
        let rest = trimmed.dropFirst(3).drop(while: { $0 == "\n" || $0 == "\r" })
        guard let end = rest.range(of: "\n---") else { return [] }
        let front = String(rest[..<end.lowerBound])
        var result: [String] = []
        var currentList: String?
        for rawLine in front.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(rawLine)
            if line.hasPrefix("  - ") || line.hasPrefix("- ") {
                if currentList == nil || !knownKeys.contains(currentList!) {
                    result.append(line)
                }
                continue
            }
            guard let colon = line.firstIndex(of: ":") else {
                currentList = nil
                result.append(line)
                continue
            }
            let key = String(line[..<colon]).trimmingCharacters(in: .whitespaces)
            let value = String(line[line.index(after: colon)...])
                .trimmingCharacters(in: .whitespaces)
            currentList = value.isEmpty ? key : nil
            if !knownKeys.contains(key) {
                result.append(line)
            }
        }
        return result
    }

    static func parseMapping(_ text: String) -> [String: String] {
        var result: [String: String] = [:]
        var prefix = ""
        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(rawLine)
            if line.hasPrefix("  ") {
                let trimmed = line.trimmingCharacters(in: .whitespaces)
                guard let colon = trimmed.firstIndex(of: ":") else { continue }
                let key = String(trimmed[..<colon])
                let value = String(trimmed[trimmed.index(after: colon)...])
                    .trimmingCharacters(in: .whitespaces)
                result["\(prefix)\(key)"] = unquote(value)
            } else if line.hasSuffix(":") {
                prefix = String(line.dropLast()) + "."
            } else if let colon = line.firstIndex(of: ":") {
                prefix = ""
                let key = String(line[..<colon])
                let value = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                result[key] = unquote(value)
            }
        }
        return result
    }

    static func sections(_ body: String) -> [String: String] {
        var result: [String: String] = [:]
        var current = ""
        var lines: [String] = []
        func flush() {
            guard !current.isEmpty else { return }
            result[current] = lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        }
        for raw in body.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(raw)
            if line.hasPrefix("## ") {
                flush()
                current = String(line.dropFirst(3)).trimmingCharacters(in: .whitespaces)
                lines = []
            } else {
                lines.append(line)
            }
        }
        flush()
        return result
    }

    static func bullets(_ text: String) -> [String] {
        text.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .map { $0.hasPrefix("- ") ? String($0.dropFirst(2)) : $0 }
            .filter { !$0.isEmpty }
    }

    static func identifier<Tag>(_ raw: String?) -> NovelIdentifier<Tag>? {
        guard let raw, let uuid = UUID(uuidString: raw) else { return nil }
        return NovelIdentifier(rawValue: uuid)
    }

    static func splitHighlights(_ body: String) -> (body: String, highlights: [String]?) {
        guard let range = body.range(of: "\n## 近期已写") ?? body.range(of: "## 近期已写") else {
            return (body, nil)
        }
        let summary = String(body[..<range.lowerBound]).trimmingCharacters(in: .whitespacesAndNewlines)
        let rest = String(body[range.upperBound...])
        let highlights = rest
            .split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .map { $0.hasPrefix("- ") ? String($0.dropFirst(2)) : $0 }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") }
        return (summary, highlights.isEmpty ? nil : highlights)
    }

    static func unquote(_ value: String) -> String {
        guard value.count >= 2, value.hasPrefix("\""), value.hasSuffix("\"") else {
            return value
        }
        return String(value.dropFirst().dropLast())
            .replacingOccurrences(of: "\\\"", with: "\"")
            .replacingOccurrences(of: "\\\\", with: "\\")
    }
}

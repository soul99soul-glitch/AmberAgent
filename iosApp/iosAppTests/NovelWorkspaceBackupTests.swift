import XCTest
@testable import iosApp

final class NovelWorkspaceBackupTests: XCTestCase {
    func testExportsWorkspaceTreeWithoutWrappingChapterHeadings() throws {
        let exportedAt = Date(timeIntervalSince1970: 1_787_011_200)
        let document = try makeNovelWorkspaceBackupFixture()
        let files = Dictionary(
            uniqueKeysWithValues: try NovelWorkspaceBackup.export(
                document,
                exportedAt: exportedAt
            ).map { ($0.path, $0.contents) }
        )

        XCTAssertEqual(files["manifest.yaml"]?.contains("format: amber.novel.workspace"), true)
        XCTAssertEqual(files["manifest.yaml"]?.contains("formatVersion: 1"), true)
        let exportedStamp = ISO8601DateFormatter()
        exportedStamp.formatOptions = [.withInternetDateTime]
        exportedStamp.timeZone = TimeZone(secondsFromGMT: 0)
        XCTAssertEqual(
            files["manifest.yaml"]?.contains("exportedAt: \(exportedStamp.string(from: exportedAt))"),
            true
        )
        XCTAssertEqual(files["manifest.yaml"]?.contains(document.project.id.description), true)
        XCTAssertEqual(files["project.md"]?.contains("title: Test Novel"), true)
        XCTAssertEqual(files["project.md"]?.contains("collaborationMode: cocreation"), true)

        let world = try XCTUnwrap(files["setting/world/world-rule.md"])
        XCTAssertTrue(world.contains("kind: material"))
        XCTAssertTrue(world.contains("materialKind: world"))
        XCTAssertTrue(world.hasSuffix("Magic has a cost.\n") || world.contains("\n\nMagic has a cost.\n"))
        XCTAssertFalse(world.contains("\n# World Rule\n"))

        let character = try XCTUnwrap(files["setting/characters/赵大.md"])
        XCTAssertTrue(character.contains("materialKind: character"))
        XCTAssertTrue(character.contains("aliases:"))
        XCTAssertTrue(character.contains("赵大"))
        XCTAssertTrue(character.contains("赵匡胤坐在马上。"))

        let chapter = try XCTUnwrap(files["branches/main/chapters/001-山呼.md"])
        XCTAssertTrue(chapter.contains("kind: chapter"))
        XCTAssertTrue(chapter.contains("title: 山呼"))
        XCTAssertTrue(chapter.contains("ordinal: 1"))
        XCTAssertTrue(chapter.contains("陈桥驿的风先到。"))
        XCTAssertFalse(chapter.contains("\n# 山呼\n"))

        let discarded = try XCTUnwrap(files["branches/main/discarded/水稿.md"])
        XCTAssertTrue(discarded.contains("title: 水稿"))
        XCTAssertTrue(discarded.contains("这章作废。"))
        XCTAssertNil(files["branches/main/chapters/002-水稿.md"])

        let current = try XCTUnwrap(files["branches/main/plot/current.md"])
        XCTAssertTrue(current.contains("赵大已在陈桥。"))
        XCTAssertTrue(current.contains("## 近期已写"))
        XCTAssertTrue(current.contains("风先到"))
        XCTAssertTrue(files["branches/main/plot/outline.md"]?.contains("从陈桥往汴京") == true)
        XCTAssertTrue(files["branches/main/plot/events.md"]?.contains("陈桥驿的风先到。") == true)

        let plan = try XCTUnwrap(files["branches/main/plan/this-chapter.md"])
        XCTAssertTrue(plan.contains("status: confirmed"))
        XCTAssertTrue(plan.contains("拿下军心"))
        XCTAssertTrue(files["branches/main/plan/upcoming.md"]?.contains("入汴") == true)

        XCTAssertNil(files["sessions.md"])
        XCTAssertFalse(files.keys.contains(where: { $0.contains("candidate") }))
    }

    func testWorkspaceRoundTripPreservesChapterAndSetting() throws {
        let original = try makeNovelWorkspaceBackupFixture()
        let files = try NovelWorkspaceBackup.export(original)
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)

        XCTAssertNotEqual(imported.project.id, original.project.id)
        XCTAssertEqual(imported.project.name, original.project.name)
        XCTAssertEqual(imported.project.collaborationMode, original.project.collaborationMode)
        let liveOriginal = original.materials.filter { !$0.isDeleted }
        XCTAssertEqual(imported.materials.filter { !$0.isDeleted }.count, liveOriginal.count)
        let importedWorking = imported.branches[0].workingChapterSelections.filter { selection in
            imported.chapters.first { $0.id == selection.chapterID }?.discardedAt == nil
        }
        XCTAssertEqual(importedWorking.count, 1)
        let version = imported.chapterVersions.first {
            $0.id == importedWorking[0].versionID
        }
        XCTAssertEqual(version?.title, "山呼")
        XCTAssertEqual(version?.content, "陈桥驿的风先到。")
        XCTAssertEqual(imported.stateSnapshots.first { $0.id == imported.branches[0].currentStateSnapshotID }?.summary, "赵大已在陈桥。")
        XCTAssertEqual(imported.chapterPlans.first?.goalAndConflict, "拿下军心")
        XCTAssertEqual(imported.upcomingArcs.first?.beats, ["入汴"])
        XCTAssertTrue(imported.chapters.contains { $0.discardedAt != nil })
    }

    func testExportCopiedDevicePackageWhenAsked() throws {
        let env = ProcessInfo.processInfo.environment
        let defaultRoot = "/Users/arquiel/Downloads/amber-novel-backups/2026-08-17-raw/NovelCreation"
        let rootPath = env["AMBER_NOVEL_BACKUP_ROOT"]?.isEmpty == false
            ? env["AMBER_NOVEL_BACKUP_ROOT"]!
            : defaultRoot
        let root = URL(fileURLWithPath: rootPath)
        guard FileManager.default.fileExists(atPath: root.appendingPathComponent("index.json").path) else {
            throw XCTSkip("No copied novel package at \(rootPath).")
        }
        let index = try JSONDecoder().decode(
            BackupIndex.self,
            from: Data(contentsOf: root.appendingPathComponent("index.json"))
        )
        XCTAssertFalse(index.projects.isEmpty)
        let outRoot = URL(
            fileURLWithPath: env["AMBER_NOVEL_WORKSPACE_OUT"]
                ?? root.deletingLastPathComponent().appendingPathComponent("workspace").path
        )
        try FileManager.default.createDirectory(at: outRoot, withIntermediateDirectories: true)
        for project in index.projects {
            let package = root
                .appendingPathComponent("projects", isDirectory: true)
                .appendingPathComponent(project.id.description, isDirectory: true)
            let destination = outRoot.appendingPathComponent(project.name, isDirectory: true)
            try NovelWorkspaceBackup.exportPackage(
                at: package,
                projectID: project.id,
                to: destination
            )
            XCTAssertTrue(
                FileManager.default.fileExists(
                    atPath: destination.appendingPathComponent("manifest.yaml").path
                )
            )
        }
    }

    func testWriteCreatesReadableChapterFiles() throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        let document = try makeNovelWorkspaceBackupFixture()
        let destination = root.appendingPathComponent("workspace", isDirectory: true)
        try NovelWorkspaceBackup.write(document, to: destination, exportedAt: Date(timeIntervalSince1970: 1_787_011_200))

        let chapterURL = destination
            .appendingPathComponent("branches/main/chapters/001-山呼.md")
        let text = try String(contentsOf: chapterURL, encoding: .utf8)
        XCTAssertTrue(text.contains("陈桥驿的风先到。"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: destination.appendingPathComponent("manifest.yaml").path))
    }

}

func makeNovelWorkspaceBackupFixture() throws -> NovelProjectDocumentV1 {
        var document = try NovelBranchTestFixtures.documentWithCollectedCandidate(
            content: "陈桥驿的风先到。"
        )
        document.project.polishPreference = "少议论"
        let chapterID = document.branches[0].workingChapterSelections[0].chapterID
        if let versionIndex = document.chapterVersions.firstIndex(where: {
            $0.id == document.branches[0].workingChapterSelections[0].versionID
        }) {
            let old = document.chapterVersions[versionIndex]
            document.chapterVersions[versionIndex] = NovelChapterVersionRecord(
                id: old.id,
                chapterID: old.chapterID,
                kind: old.kind,
                title: "山呼",
                content: old.content,
                factCompatibilityID: old.factCompatibilityID,
                sourceChapterVersionID: old.sourceChapterVersionID,
                sourceCandidateID: old.sourceCandidateID,
                createdAt: old.createdAt,
                operationID: old.operationID
            )
        }

        let world = try NovelReducer.apply(
            NovelTestFixtures.materialAction(document: document),
            to: document
        ).document
        document = world

        let characterID = NovelMaterialID()
        let characterRevision = NovelMaterialRevisionID()
        document = try NovelReducer.apply(
            .reviseMaterial(NovelReviseMaterialCommand(
                context: NovelTestFixtures.context(configRevision: document.project.configRevision),
                projectID: document.project.id,
                materialID: characterID,
                revisionID: characterRevision,
                kind: .character,
                title: "赵大",
                content: "赵匡胤坐在马上。",
                tags: [],
                injectionMode: .always,
                aliases: ["赵大", "点检"]
            )),
            to: document
        ).document

        let discardedGenerated = try NovelBranchTestFixtures.appendCompletedRun(
            to: document,
            branchID: document.branches[0].id,
            kind: .prose,
            content: "这章作废。"
        )
        document = try NovelBranchTestFixtures.collectCandidate(
            discardedGenerated.candidateID!,
            in: discardedGenerated.document,
            title: "水稿"
        )
        let discardedChapterID = document.branches[0].workingChapterSelections.last!.chapterID
        document = try NovelReducer.apply(
            .discardChapter(NovelDiscardChapterCommand(
                context: NovelTestFixtures.context(projectRevision: document.project.revision),
                projectID: document.project.id,
                branchID: document.branches[0].id,
                chapterID: discardedChapterID
            )),
            to: document
        ).document

        if let snapshotIndex = document.stateSnapshots.firstIndex(where: {
            $0.id == document.branches[0].currentStateSnapshotID
        }) {
            let old = document.stateSnapshots[snapshotIndex]
            document.stateSnapshots[snapshotIndex] = NovelStateSnapshotRecord(
                id: old.id,
                eventIDs: old.eventIDs,
                summary: "赵大已在陈桥。",
                branchOutline: "从陈桥往汴京",
                unresolvedEntityNames: old.unresolvedEntityNames,
                createdAt: old.createdAt,
                settingProposalIDs: old.settingProposalIDs,
                characterIdentityClarifications: old.characterIdentityClarifications,
                recentWrittenHighlights: ["风先到"]
            )
        }

        var plan = NovelChapterPlanRecord(
            id: NovelChapterPlanID(),
            branchID: document.branches[0].id,
            status: .confirmed,
            outlinePlacement: "第 2 章",
            goalAndConflict: "拿下军心",
            mustHappen: ["黄袍加身"],
            mustNotHappen: ["提前入汴"],
            endingHook: "军士呼喊",
            visibleFacts: [],
            contentDigest: "",
            updatedAt: document.project.updatedAt,
            confirmedAt: document.project.updatedAt
        )
        plan.contentDigest = NovelChapterPlanRecord.digest(
            forCanonicalPayload: plan.canonicalDigestPayload()
        )
        document.chapterPlans.append(plan)
        document.upcomingArcs.append(
            NovelUpcomingArcRecord(
                branchID: document.branches[0].id,
                beats: ["入汴"],
                updatedAt: document.project.updatedAt
            )
        )

        _ = chapterID
        try NovelDocumentValidator.validate(document)
        return document
}

private struct BackupIndex: Decodable {
    struct Project: Decodable {
        let id: NovelProjectID
        let name: String
    }

    let projects: [Project]
}

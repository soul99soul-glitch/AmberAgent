import XCTest
@testable import iosApp

/// Novel workspace core contract v1.1 (§3.6 + §5) conformance tests:
/// unknown frontmatter fields and unknown files pass through import/export
/// opaque and are never dropped, so an Android→iOS→Android round trip cannot
/// lose node extensions (status/relations), foreshadowing nodes, drafts, or
/// unknown directories. Fixture tree mirrors the canonical golden pack
/// layout (`Fixtures/NovelWorkspaceContract/v1_1/` semantics).
final class NovelWorkspaceContractTests: XCTestCase {

    // MARK: - §3.6 unknown frontmatter fields

    func testImportPreservesUnknownMaterialFrontmatterFields() throws {
        let files = try Self.fixtureWorkspace()
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)

        let material = try XCTUnwrap(imported.materials.first(where: {
            $0.aliases.contains("官家") || $0.id.description == Self.zhaoKuangyinID
        }))
        let lines = try XCTUnwrap(
            imported.workspacePassthrough.frontmatterExtensions[material.id.description],
            "material node extensions must be preserved opaque"
        )
        XCTAssertTrue(lines.contains("status: 殿前司都点检，暗中结交军将"))
        XCTAssertTrue(lines.contains("relations:"))
        XCTAssertTrue(lines.contains("  - {with: 赵大, type: 结拜兄弟}"))
        XCTAssertTrue(lines.contains("era: 北宋"))

        let exported = try NovelWorkspaceBackup.export(imported, exportedAt: Self.exportedAt)
        let rendered = try XCTUnwrap(
            exported.first(where: { $0.path.contains("setting/characters/") }),
            "character card must be exported"
        )
        // Known fields re-render by the host; unknown fields come back
        // verbatim after them (exempting only the regenerated provenance id).
        let renderedLines = rendered.contents
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map(String.init)
            .filter { !$0.hasPrefix("sourceVersionID:") }
        let fixtureLines = Self.zhaoKuangyinFile
            .split(separator: "\n", omittingEmptySubsequences: false)
            .map(String.init)
            .filter { !$0.hasPrefix("sourceVersionID:") }
        XCTAssertEqual(renderedLines, fixtureLines)
    }

    func testUnknownMaterialKindStaysOpaqueInsteadOfBecomingWorld() throws {
        let files = try Self.fixtureWorkspace()
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)

        XCTAssertFalse(
            imported.materials.contains(where: { $0.id.description == Self.timelineNodeID }),
            "a card with an unknown materialKind must not be reinterpreted"
        )
        let opaque = try XCTUnwrap(
            imported.workspacePassthrough.opaqueFiles["setting/timeline/陈桥兵变.md"]
        )
        XCTAssertTrue(opaque.contains("materialKind: timeline"))

        let exported = try NovelWorkspaceBackup.export(imported, exportedAt: Self.exportedAt)
        XCTAssertEqual(
            exported.first(where: { $0.path == "setting/timeline/陈桥兵变.md" })?.contents,
            opaque
        )
    }

    // MARK: - §3.6 unknown files

    func testImportPreservesForeshadowingNodesVerbatim() throws {
        let files = try Self.fixtureWorkspace()
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)
        let passthrough = imported.workspacePassthrough

        let open = try XCTUnwrap(
            passthrough.opaqueFiles["branches/Main/plot/foreshadowing/黄袍.md"]
        )
        XCTAssertTrue(open.contains("status: open"))
        let resolved = try XCTUnwrap(
            passthrough.opaqueFiles["branches/Main/plot/foreshadowing/点检之名.md"]
        )
        XCTAssertTrue(resolved.contains("status: resolved"))

        let exported = try NovelWorkspaceBackup.export(imported, exportedAt: Self.exportedAt)
        XCTAssertEqual(
            exported.first(where: { $0.path == "branches/Main/plot/foreshadowing/黄袍.md" })?.contents,
            open
        )
        XCTAssertEqual(
            exported.first(where: { $0.path == "branches/Main/plot/foreshadowing/点检之名.md" })?.contents,
            resolved
        )
    }

    func testImportPreservesDraftsAndUnknownTopLevelFiles() throws {
        let files = try Self.fixtureWorkspace()
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)
        let passthrough = imported.workspacePassthrough

        let draft = try XCTUnwrap(passthrough.opaqueFiles["drafts/ab12cd34.md"])
        XCTAssertTrue(draft.contains("未收录草稿"))
        let notes = try XCTUnwrap(passthrough.opaqueFiles["notes.md"])
        XCTAssertTrue(notes.contains("跨端便签"))

        let exported = try NovelWorkspaceBackup.export(imported, exportedAt: Self.exportedAt)
        XCTAssertEqual(exported.first(where: { $0.path == "drafts/ab12cd34.md" })?.contents, draft)
        XCTAssertEqual(exported.first(where: { $0.path == "notes.md" })?.contents, notes)
    }

    // MARK: - inbox becomes visible proposals without losing extensions

    func testImportRestoresInboxProposalsAndTheirUnknownFields() throws {
        let files = try Self.fixtureWorkspace()
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)

        let proposal = try XCTUnwrap(imported.settingProposals.first(where: {
            $0.id.description == Self.inboxProposalID
        }))
        XCTAssertEqual(proposal.title, "粮仓")
        XCTAssertFalse(proposal.isResolved)
        XCTAssertEqual(
            imported.workspacePassthrough.frontmatterExtensions[proposal.id.description],
            ["source: 讨论"]
        )

        let exported = try NovelWorkspaceBackup.export(imported, exportedAt: Self.exportedAt)
        let rendered = try XCTUnwrap(exported.first(where: { $0.path == "inbox/粮仓.md" }))
        XCTAssertTrue(rendered.contents.contains("id: \(Self.inboxProposalID)"))
        XCTAssertTrue(rendered.contents.contains("source: 讨论"))
        XCTAssertTrue(rendered.contents.contains("城北粮仓连夜进了三车粮。"))
    }

    // MARK: - conventions §7 missing plot imports as needsSync

    func testImportWithoutPlotMarksBranchNeedsSync() throws {
        let withPlot = try NovelWorkspaceImporter.makeDocument(from: Self.fixtureWorkspace())
        XCTAssertEqual(withPlot.branches.first?.syncStatus, .synchronized)

        let withoutPlot = try Self.fixtureWorkspace().filter { !$0.path.contains("/plot/") }
        let imported = try NovelWorkspaceImporter.makeDocument(from: withoutPlot)
        XCTAssertEqual(
            imported.branches.first?.syncStatus,
            .needsSync,
            "a workspace without plot/ must import as needsSync (conventions §7)"
        )
    }

    // MARK: - §5.1 round trip never loses opaque content

    func testRoundTripKeepsOpaqueFilesAndExtensionLinesByteIdentical() throws {
        let firstImport = try NovelWorkspaceImporter.makeDocument(from: Self.fixtureWorkspace())
        let firstExport = try NovelWorkspaceBackup.export(firstImport, exportedAt: Self.exportedAt)
        let secondImport = try NovelWorkspaceImporter.makeDocument(from: firstExport)
        let secondExport = try NovelWorkspaceBackup.export(secondImport, exportedAt: Self.exportedAt)

        // Every opaque file survives both passes with identical bytes.
        XCTAssertFalse(firstImport.workspacePassthrough.opaqueFiles.isEmpty)
        XCTAssertEqual(
            secondImport.workspacePassthrough.opaqueFiles,
            firstImport.workspacePassthrough.opaqueFiles
        )

        // Extension lines survive both passes (anchors may be regenerated ids,
        // so compare the preserved line sets, not the anchor keys).
        let firstLines = firstImport.workspacePassthrough.frontmatterExtensions.values
            .flatMap { $0 }.sorted()
        let secondLines = secondImport.workspacePassthrough.frontmatterExtensions.values
            .flatMap { $0 }.sorted()
        XCTAssertEqual(secondLines, firstLines)

        // Rendered book files are stable across passes, exempting only the
        // ids iOS regenerates on import (documented in the contract report).
        let exemptedKeys = ["id:", "sourceVersionID:"]
        func normalized(_ file: NovelWorkspaceBackup.File) -> [String] {
            file.contents
                .split(separator: "\n", omittingEmptySubsequences: false)
                .map(String.init)
                .filter { line in !exemptedKeys.contains(where: { line.hasPrefix($0) }) }
        }
        let firstByName = Dictionary(
            firstExport.map { ($0.path, normalized($0)) },
            uniquingKeysWith: { a, _ in a }
        )
        for file in secondExport {
            guard file.path != "manifest.yaml" else { continue } // exportedAt/source revision move
            XCTAssertEqual(
                normalized(file),
                firstByName[file.path],
                "re-export drifted for \(file.path)"
            )
        }
        XCTAssertEqual(Set(secondExport.map(\.path)), Set(firstExport.map(\.path)))
    }

    // MARK: - anchor families with no semantic ids of their own

    func testDiscardedChapterPlotAndBranchExtensionsSurvive() throws {
        var files = try Self.fixtureWorkspace()
        files.append(.init(path: "branches/Main/discarded/弃章.md", contents: """
            ---
            id: 3f2a0c1a-0000-4000-8000-00000000000c
            kind: chapter
            title: 弃章
            sourceVersionID: 3f2a0c1a-0000-4000-8000-00000000000d
            discardReason: 节奏重复
            ---

            被放弃的一章。
            """))
        // Unknown fields on plot + branch files ride branch-id anchors.
        files[files.firstIndex(where: { $0.path == "branches/Main/branch.md" })!] =
            .init(path: "branches/Main/branch.md", contents: """
                ---
                id: \(Self.branchID)
                kind: branch
                title: Main
                syncStatus: synchronized
                pace: 慢热
                ---
                """)
        files[files.firstIndex(where: { $0.path == "branches/Main/plot/current.md" })!] =
            .init(path: "branches/Main/plot/current.md", contents: """
                ---
                id: 3f2a0c1a-0000-4000-8000-00000000000a
                kind: plot
                title: 当前状态
                confidence: high
                ---

                赵大已在陈桥。
                """)

        let imported = try NovelWorkspaceImporter.makeDocument(from: files)
        let exported = try NovelWorkspaceBackup.export(imported, exportedAt: Self.exportedAt)
        func contents(suffix: String) throws -> String {
            try XCTUnwrap(
                exported.first(where: { $0.path.hasSuffix(suffix) })?.contents,
                "missing exported file ending in \(suffix)"
            )
        }
        XCTAssertTrue(try contents(suffix: "discarded/弃章.md").contains("discardReason: 节奏重复"))
        XCTAssertTrue(try contents(suffix: "/branch.md").contains("pace: 慢热"))
        XCTAssertTrue(try contents(suffix: "/plot/current.md").contains("confidence: high"))

        // A second pass must keep them too (anchors re-key to fresh ids).
        let reimported = try NovelWorkspaceImporter.makeDocument(from: exported)
        let reexported = try NovelWorkspaceBackup.export(reimported, exportedAt: Self.exportedAt)
        XCTAssertTrue(
            reexported.first(where: { $0.path.hasSuffix("discarded/弃章.md") })?
                .contents.contains("discardReason: 节奏重复") == true
        )
        XCTAssertTrue(
            reexported.first(where: { $0.path.hasSuffix("/branch.md") })?
                .contents.contains("pace: 慢热") == true
        )
        XCTAssertTrue(
            reexported.first(where: { $0.path.hasSuffix("/plot/current.md") })?
                .contents.contains("confidence: high") == true
        )
    }

    func testUpcomingWithoutBeatsIsPreservedOpaque() throws {
        var files = try Self.fixtureWorkspace()
        files.removeAll { $0.path == "branches/Main/plan/upcoming.md" }
        files.append(.init(path: "branches/Main/plan/upcoming.md", contents: """
            ---
            id: \(Self.branchID)
            kind: plan
            title: 往后几章
            ---
            """))
        let imported = try NovelWorkspaceImporter.makeDocument(from: files)
        XCTAssertTrue(imported.upcomingArcs.isEmpty)
        let opaque = try XCTUnwrap(
            imported.workspacePassthrough.opaqueFiles["branches/Main/plan/upcoming.md"]
        )
        XCTAssertTrue(opaque.contains("title: 往后几章"))
        let exported = try NovelWorkspaceBackup.export(imported, exportedAt: Self.exportedAt)
        XCTAssertEqual(
            exported.first(where: { $0.path == "branches/Main/plan/upcoming.md" })?.contents,
            opaque
        )
    }

    func testSnapshotCarriesPassthroughIntoAgentDocumentView() throws {
        let imported = try NovelWorkspaceImporter.makeDocument(from: Self.fixtureWorkspace())
        XCTAssertFalse(imported.workspacePassthrough.isEmpty)
        let snapshot = NovelProjectSnapshot(
            loaded: NovelLoadedProject(document: imported, access: .readWrite)
        )
        XCTAssertEqual(snapshot.workspacePassthrough, imported.workspacePassthrough)
        // The agent's five primitives export from this bridged document; it
        // must include opaque files and extension lines.
        let exported = try NovelWorkspaceBackup.export(snapshot.document, exportedAt: Self.exportedAt)
        XCTAssertTrue(exported.contains(where: {
            $0.path == "branches/Main/plot/foreshadowing/黄袍.md"
        }))
        XCTAssertTrue(exported.contains(where: {
            $0.contents.contains("status: 殿前司都点检，暗中结交军将")
        }))
    }

    // MARK: - storage: passthrough persists through the package formats

    func testLegacyDocumentDecodesEmptyPassthrough() throws {
        let fixture = try makeNovelWorkspaceBackupFixture()
        let data = try JSONEncoder().encode(fixture)
        var object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        object.removeValue(forKey: "workspacePassthrough")
        let legacyData = try JSONSerialization.data(withJSONObject: object)
        let decoded = try JSONDecoder().decode(
            NovelProjectDocumentV1.self,
            from: legacyData
        )
        XCTAssertTrue(decoded.workspacePassthrough.isEmpty)
    }

    func testShardedStorageRoundTripsPassthroughAndToleratesLegacyLayout() throws {
        let root = try NovelTestFixtures.temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: root) }
        var document = try makeNovelWorkspaceBackupFixture()
        document.workspacePassthrough = NovelWorkspacePassthroughRecord(
            frontmatterExtensions: ["anchor": ["status: 测试"]],
            opaqueFiles: ["notes.md": "---\nkind: note\n---\n便签\n"]
        )
        let package = NovelProjectShardedStorage.packageDirectory(
            projectDirectory: root,
            projectID: document.project.id
        )
        _ = try NovelProjectShardedStorage.writePackage(
            document: document,
            packageDirectory: package,
            encoder: JSONEncoder(),
            fileManager: .default,
            cache: nil
        )
        let loaded = try NovelProjectShardedStorage.loadDocument(
            packageDirectory: package,
            projectID: document.project.id,
            decoder: JSONDecoder(),
            fileManager: .default
        )
        XCTAssertEqual(loaded.document.workspacePassthrough, document.workspacePassthrough)

        // Simulate a package written before contract v1.1: strip the section
        // reference from the layout; loading must synthesize an empty record.
        let layoutURL = NovelProjectShardedStorage.layoutURL(in: package)
        let layout = try JSONDecoder().decode(
            NovelProjectShardedStorage.LayoutV2.self,
            from: Data(contentsOf: layoutURL)
        )
        var strippedSections = layout.sections
        strippedSections.removeValue(forKey: "workspacePassthrough")
        let strippedLayout = NovelProjectShardedStorage.LayoutV2(
            schemaVersion: layout.schemaVersion,
            documentSchemaVersion: layout.documentSchemaVersion,
            projectID: layout.projectID,
            revision: layout.revision,
            updatedAt: layout.updatedAt,
            sections: strippedSections
        )
        try JSONEncoder().encode(strippedLayout).write(to: layoutURL)
        let legacyLoaded = try NovelProjectShardedStorage.loadDocument(
            packageDirectory: package,
            projectID: document.project.id,
            decoder: JSONDecoder(),
            fileManager: .default
        )
        XCTAssertTrue(legacyLoaded.document.workspacePassthrough.isEmpty)
    }

    // MARK: - fixture

    static let exportedAt = Date(timeIntervalSince1970: 1_787_100_000)
    static let zhaoKuangyinID = "3f2a0c1a-0000-4000-8000-000000000001"
    static let timelineNodeID = "3f2a0c1a-0000-4000-8000-000000000002"
    static let inboxProposalID = "3f2a0c1a-0000-4000-8000-000000000003"
    static let chapterID = "3f2a0c1a-0000-4000-8000-000000000004"
    static let planID = "3f2a0c1a-0000-4000-8000-000000000005"
    static let projectID = "3f2a0c1a-0000-4000-8000-000000000006"
    static let branchID = "3f2a0c1a-0000-4000-8000-000000000007"

    static let zhaoKuangyinBody = """
        ---
        id: \(zhaoKuangyinID)
        kind: material
        title: 赵匡胤
        materialKind: character
        injection: smart
        aliases:
          - 官家
        status: 殿前司都点检，暗中结交军将
        relations:
          - {with: 赵大, type: 结拜兄弟}
          - {with: 汴京, type: 驻地}
        era: 北宋
        ---

        陈桥驿的夜风里，他披衣起身。
        """

    /// File form ends with the newline the exporter always appends.
    static var zhaoKuangyinFile: String { zhaoKuangyinBody + "\n" }

    static func fixtureWorkspace() throws -> [NovelWorkspaceBackup.File] {
        [
            .init(path: "manifest.yaml", contents: """
                format: amber.novel.workspace
                formatVersion: 1
                exportedAt: 2026-08-19T00:00:00Z
                source:
                  projectID: "\(projectID)"
                  projectRevision: 12
                  schemaVersion: 1
                mainBranch: Main
                """),
            .init(path: "project.md", contents: """
                ---
                id: \(projectID)
                kind: project
                title: 赵大来了
                collaborationMode: cocreation
                polishPreference: 少议论
                ---
                """),
            .init(path: "setting/characters/赵匡胤.md", contents: zhaoKuangyinFile),
            .init(path: "setting/world/汴京.md", contents: """
                ---
                id: 3f2a0c1a-0000-4000-8000-000000000008
                kind: material
                title: 汴京
                materialKind: world
                injection: smart
                ---

                汴河穿城。
                """),
            .init(path: "setting/timeline/陈桥兵变.md", contents: """
                ---
                id: \(timelineNodeID)
                kind: material
                title: 陈桥兵变
                materialKind: timeline
                ---

                显德七年正月。
                """),
            .init(path: "branches/Main/branch.md", contents: """
                ---
                id: \(branchID)
                kind: branch
                title: Main
                syncStatus: synchronized
                ---
                """),
            .init(path: "branches/Main/chapters/001-山呼.md", contents: """
                ---
                id: \(chapterID)
                kind: chapter
                title: 山呼
                ordinal: 1
                sourceVersionID: 3f2a0c1a-0000-4000-8000-000000000009
                tone: 沉郁
                ---

                陈桥驿的风先到。
                """),
            .init(path: "branches/Main/plot/current.md", contents: """
                ---
                id: 3f2a0c1a-0000-4000-8000-00000000000A
                kind: plot
                title: 当前状态
                ---

                赵大已在陈桥。

                ## 近期已写

                - 山呼：军中拥立。
                """),
            .init(path: "branches/Main/plot/foreshadowing/黄袍.md", contents: """
                ---
                kind: foreshadowing
                title: 黄袍
                status: open
                ---

                那件黄袍还在行囊里。
                """),
            .init(path: "branches/Main/plot/foreshadowing/点检之名.md", contents: """
                ---
                kind: foreshadowing
                title: 点检之名
                status: resolved
                ---

                木牌上的名字已经应验。
                """),
            .init(path: "branches/Main/plan/this-chapter.md", contents: """
                ---
                id: \(planID)
                kind: plan
                title: 本章计划
                status: confirmed
                ---

                ## 目标与冲突

                拿下军心。

                ## 必须发生

                - 将士拥立
                """),
            .init(path: "inbox/粮仓.md", contents: """
                ---
                id: \(inboxProposalID)
                kind: material
                title: 粮仓
                materialKind: custom
                source: 讨论
                ---

                城北粮仓连夜进了三车粮。
                """),
            .init(path: "drafts/ab12cd34.md", contents: """
                ---
                id: 3f2a0c1a-0000-4000-8000-00000000000B
                kind: chapter
                title: 未收录草稿
                ---

                一段没进正史的开头。
                """),
            .init(path: "notes.md", contents: "跨端便签：不属于任何已知目录。\n"),
        ]
    }
}

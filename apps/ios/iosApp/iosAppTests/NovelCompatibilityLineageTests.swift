import XCTest
@testable import iosApp

final class NovelCompatibilityLineageTests: XCTestCase {
    func testLegacyChapterVersionWithoutSourceDecodesAsRoot() throws {
        let root = version(kind: .collected, compatibilityID: UUID())
        let data = try JSONEncoder().encode(root)
        let object = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: data) as? [String: Any]
        )

        XCTAssertNil(object["sourceChapterVersionID"])
        XCTAssertNil(try JSONDecoder().decode(
            NovelChapterVersionRecord.self,
            from: data
        ).sourceChapterVersionID)
    }

    func testValidCompatibilitySublineagesPassFullDocumentValidation() throws {
        let chapterID = NovelChapterID()
        let root = version(
            chapterID: chapterID,
            kind: .collected,
            compatibilityID: UUID()
        )
        let collected = version(
            chapterID: chapterID,
            kind: .collected,
            compatibilityID: UUID(),
            sourceID: root.id
        )
        let polished = version(
            chapterID: chapterID,
            kind: .polish,
            compatibilityID: collected.factCompatibilityID,
            sourceID: collected.id
        )
        let restored = version(
            chapterID: chapterID,
            kind: .restore,
            compatibilityID: collected.factCompatibilityID,
            sourceID: polished.id
        )
        let manual = version(
            chapterID: chapterID,
            kind: .manualEdit,
            compatibilityID: UUID(),
            sourceID: restored.id
        )
        let document = try formalDocument(
            chapterID: chapterID,
            versions: [root, collected, polished, restored, manual]
        )

        XCTAssertNoThrow(try NovelDocumentValidator.validate(document))
    }

    func testVersionKindsEnforceCompatibilitySemantics() throws {
        let chapterID = NovelChapterID()
        let root = version(
            chapterID: chapterID,
            kind: .collected,
            compatibilityID: UUID()
        )
        let collected = version(
            chapterID: chapterID,
            kind: .collected,
            compatibilityID: root.factCompatibilityID,
            sourceID: root.id
        )
        let manual = version(
            chapterID: chapterID,
            kind: .manualEdit,
            compatibilityID: root.factCompatibilityID,
            sourceID: collected.id
        )
        let polish = version(
            chapterID: chapterID,
            kind: .polish,
            compatibilityID: UUID(),
            sourceID: manual.id
        )
        let restore = version(
            chapterID: chapterID,
            kind: .restore,
            compatibilityID: UUID(),
            sourceID: polish.id
        )
        let issues = lineageIssues(
            versions: [root, collected, manual, polish, restore]
        )

        XCTAssertEqual(issues.filter { $0.contains("start a new fact compatibility") }.count, 2)
        XCTAssertEqual(issues.filter { $0.contains("preserve source compatibility") }.count, 2)
    }

    func testSourceMustBeEarlierInTheSameChapterAndAcyclic() throws {
        let firstChapterID = NovelChapterID()
        let secondChapterID = NovelChapterID()
        let firstID = NovelChapterVersionID()
        let secondID = NovelChapterVersionID()
        let compatibilityID = UUID()
        let first = version(
            id: firstID,
            chapterID: firstChapterID,
            kind: .collected,
            compatibilityID: compatibilityID,
            sourceID: secondID
        )
        let second = version(
            id: secondID,
            chapterID: secondChapterID,
            kind: .polish,
            compatibilityID: compatibilityID,
            sourceID: firstID
        )
        let issues = lineageIssues(versions: [first, second])

        XCTAssertTrue(issues.contains { $0.contains("earlier source version") })
        XCTAssertTrue(issues.contains { $0.contains("another chapter") })
        XCTAssertTrue(issues.contains { $0.contains("contains a cycle") })
    }

    func testEachChapterHasOneCollectedRoot() throws {
        let chapterID = NovelChapterID()
        let manualRoot = version(
            chapterID: chapterID,
            kind: .manualEdit,
            compatibilityID: UUID()
        )
        let collectedRoot = version(
            chapterID: chapterID,
            kind: .collected,
            compatibilityID: UUID()
        )
        let issues = lineageIssues(versions: [manualRoot, collectedRoot])

        XCTAssertTrue(issues.contains { $0.contains("must have a source version") })
        XCTAssertTrue(issues.contains { $0.contains("exactly one version root") })
    }

    func testCompatibilityIDsAreNonzeroProjectGlobalAndConnected() throws {
        let firstChapterID = NovelChapterID()
        let secondChapterID = NovelChapterID()
        let sharedID = UUID()
        let firstRoot = version(
            chapterID: firstChapterID,
            kind: .collected,
            compatibilityID: sharedID
        )
        let otherRoot = version(
            chapterID: secondChapterID,
            kind: .collected,
            compatibilityID: sharedID
        )
        let bridge = version(
            chapterID: firstChapterID,
            kind: .collected,
            compatibilityID: UUID(),
            sourceID: firstRoot.id
        )
        let disconnected = version(
            chapterID: firstChapterID,
            kind: .manualEdit,
            compatibilityID: sharedID,
            sourceID: bridge.id
        )
        let zero = version(
            chapterID: firstChapterID,
            kind: .manualEdit,
            compatibilityID: UUID(uuidString: "00000000-0000-0000-0000-000000000000")!,
            sourceID: disconnected.id
        )
        let issues = lineageIssues(
            versions: [firstRoot, otherRoot, bridge, disconnected, zero]
        )

        XCTAssertTrue(issues.contains { $0.contains("zero fact compatibility ID") })
        XCTAssertTrue(issues.contains { $0.contains("shared across chapters") })
        XCTAssertTrue(issues.contains { $0.contains("disconnected lineage") })
    }
}

private extension NovelCompatibilityLineageTests {
    func version(
        id: NovelChapterVersionID = NovelChapterVersionID(),
        chapterID: NovelChapterID = NovelChapterID(),
        kind: NovelChapterVersionKind,
        compatibilityID: UUID,
        sourceID: NovelChapterVersionID? = nil,
        operationID: NovelOperationID = NovelOperationID()
    ) -> NovelChapterVersionRecord {
        NovelChapterVersionRecord(
            id: id,
            chapterID: chapterID,
            kind: kind,
            title: "Chapter",
            content: "Content",
            factCompatibilityID: compatibilityID,
            sourceChapterVersionID: sourceID,
            sourceCandidateID: nil,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000),
            operationID: operationID
        )
    }

    func lineageIssues(versions: [NovelChapterVersionRecord]) -> [String] {
        var document = try! NovelTestFixtures.document()
        document.chapters = Array(Set(versions.map(\.chapterID))).map {
            NovelChapterRecord(id: $0, createdAt: document.project.createdAt)
        }
        document.chapterVersions = versions
        var issues: [String] = []
        NovelCompatibilityLineageValidator.validate(document, issues: &issues)
        return issues
    }

    func formalDocument(
        chapterID: NovelChapterID,
        versions: [NovelChapterVersionRecord]
    ) throws -> NovelProjectDocumentV1 {
        var document = try NovelTestFixtures.document()
        let operationID = document.appliedOperations[0].operationID
        let committed = versions.map { version in
            NovelChapterVersionRecord(
                id: version.id,
                chapterID: version.chapterID,
                kind: version.kind,
                title: version.title,
                content: version.content,
                factCompatibilityID: version.factCompatibilityID,
                sourceChapterVersionID: version.sourceChapterVersionID,
                sourceCandidateID: version.sourceCandidateID,
                createdAt: version.createdAt,
                operationID: operationID
            )
        }
        document.chapters.append(NovelChapterRecord(
            id: chapterID,
            createdAt: document.project.createdAt
        ))
        document.chapterVersions.append(contentsOf: committed)
        return document
    }
}

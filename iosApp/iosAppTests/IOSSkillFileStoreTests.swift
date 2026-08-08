import XCTest
@preconcurrency import Shared
@testable import iosApp

final class IOSSkillFileStoreTests: XCTestCase {
    private var tempDirs: [URL] = []

    override func tearDown() async throws {
        for dir in tempDirs {
            try? FileManager.default.removeItem(at: dir)
        }
        tempDirs.removeAll()
    }

    func testCreateSkillWritesSkillMdAndKmpScannerReadsIt() throws {
        let root = tempRoot()
        let store = IOSSkillFileStore(baseDirectory: root)

        let name = try store.createSkill(
            name: "Research Helper",
            description: "Use when the user asks for a concise research brief.",
            allowedTools: ["read", "rg", "web_search"]
        )

        XCTAssertEqual(name, "research-helper")
        let content = try store.readSkillMarkdown(dirName: name)
        XCTAssertTrue(content.contains(#"name: "research-helper""#))
        XCTAssertTrue(content.contains("allowed-tools: read rg web_search"))

        let scanned = IosSkillFactory.shared.listSkills(documentsDir: root.path)
        XCTAssertEqual(scanned.count, 1)
        XCTAssertEqual(scanned.first?.name, "research-helper")
        XCTAssertEqual(scanned.first?.description_, "Use when the user asks for a concise research brief.")
    }

    func testCreateSkillRejectsPathTraversalName() {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())

        XCTAssertThrowsError(
            try store.createSkill(name: "../bad", description: "Use when testing.", allowedTools: [])
        )
    }

    func testSaveMarkdownRejectsNameChange() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        let name = try store.createSkill(name: "stable-skill", description: "Use when testing.", allowedTools: [])

        let changed = """
        ---
        name: "renamed-skill"
        description: "Use when testing."
        ---

        # renamed-skill
        """

        XCTAssertThrowsError(
            try store.saveSkillMarkdown(dirName: name, expectedName: name, content: changed)
        )
    }

    func testDeleteSkillRemovesLocalDirectory() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        let name = try store.createSkill(name: "delete-me", description: "Use when testing deletion.", allowedTools: [])

        try store.deleteSkill(dirName: name)

        XCTAssertThrowsError(try store.readSkillMarkdown(dirName: name))
    }

    func testRequiredBuiltinCannotBeEditedOrDeletedAfterSeeding() throws {
        let store = IOSSkillFileStore(baseDirectory: tempRoot())
        let name = try XCTUnwrap(IOSBuiltinSkills.requiredNames.first)
        let markdown = try XCTUnwrap(IOSBuiltinSkills.markdown(for: name))
        try store.saveSkillFiles(files: ["SKILL.md": markdown], allowBuiltinSkill: true)

        XCTAssertThrowsError(
            try store.saveSkillMarkdown(dirName: name, expectedName: name, content: markdown)
        ) { error in
            XCTAssertEqual(error as? IOSSkillFileStoreError, .builtinSkillProtected(name))
        }
        XCTAssertThrowsError(try store.deleteSkill(dirName: name)) { error in
            XCTAssertEqual(error as? IOSSkillFileStoreError, .builtinSkillProtected(name))
        }
        XCTAssertEqual(try store.readSkillMarkdown(dirName: name), markdown)
    }

    private func tempRoot() -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("ios-skill-file-store-\(UUID().uuidString)", isDirectory: true)
        tempDirs.append(url)
        return url
    }
}

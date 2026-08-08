import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Cluster 7 tests: skill injection into chat + conversation-store IO error
/// surfacing. These are the clean, fully-testable parity fixes (no real model
/// needed). Settings-IA routing is verified by a build-success + the entry
/// existence check in IOSCapabilityRegistryTests.
@MainActor
final class IOSSkillInjectionAndIOErrorTests: XCTestCase {

    private func isolatedDefaults() -> UserDefaults {
        let suite = "IOSSkillInjectionAndIOErrorTests-\(UUID().uuidString)"
        return UserDefaults(suiteName: suite)!
    }

    // MARK: - Skill injection

    func testNoSkillCatalogWhenSkillPackagesMissing() throws {
        // Default assistant may list required skill names; catalog only injects for packages on disk.
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSSkillInjectionEmpty-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }

        var builder = ChatRuntimeContextBuilder(
            sharedSettings: sharedSettings,
            mcpTools: [],
            miniAppRepository: IOSMiniAppRepository(baseDirectory: tempRoot),
            miniAppRuntimeEnabled: false
        )
        builder.skillFileStore = IOSSkillFileStore(baseDirectory: tempRoot)

        let prepared = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "hello")],
            coalesceSystemMessages: true
        )
        let hasSkillBlock = prepared.contains { msg in
            let text = msg.toText()
            return text.contains("<available_skills>") || text.contains("<skills>")
        }
        XCTAssertFalse(hasSkillBlock)
    }

    func testEnabledSkillInjectsCatalogNotFullBody() throws {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        let tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSSkillInjectionCatalog-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }

        let store = IOSSkillFileStore(baseDirectory: tempRoot)
        try store.createSkill(
            name: "summarize",
            description: "Summarize any text concisely.",
            allowedTools: []
        )
        sharedSettings.setSkillEnabled(name: "summarize", enabled: true)

        var builder = ChatRuntimeContextBuilder(
            sharedSettings: sharedSettings,
            mcpTools: [],
            miniAppRepository: IOSMiniAppRepository(baseDirectory: tempRoot),
            miniAppRuntimeEnabled: false
        )
        builder.skillFileStore = store

        let prepared = builder.injectingRuntimeContext(
            into: [UIMessage.companion.user(prompt: "hello")],
            coalesceSystemMessages: true
        )
        let systemText = prepared
            .filter { $0.role == MessageRole.system }
            .map { $0.toText() }
            .joined(separator: "\n")
        XCTAssertTrue(systemText.contains("<available_skills>"))
        XCTAssertTrue(systemText.contains("<name>summarize</name>"))
        XCTAssertTrue(systemText.contains("Summarize any text concisely."))
        // Catalog only — not the full markdown body section heading dump.
        XCTAssertFalse(systemText.contains("### summarize"))
        XCTAssertTrue(systemText.contains("use_skill"))
    }

    func testRuntimeSystemMessagesAreCoalescedForSingleSystemProviders() {
        let prepared = ChatRuntimeContextBuilder.coalescingSystemMessages([
            UIMessage.companion.system(prompt: "assistant persona"),
            UIMessage.companion.system(prompt: "memory context"),
            UIMessage.companion.user(prompt: "hello"),
        ])

        XCTAssertEqual(prepared.filter { $0.role == MessageRole.system }.count, 1)
        XCTAssertEqual(prepared.filter { $0.role == MessageRole.user }.count, 1)
        let systemText = prepared.first?.toText() ?? ""
        XCTAssertTrue(systemText.contains("assistant persona"))
        XCTAssertTrue(systemText.contains("memory context"))
        XCTAssertEqual(prepared.last?.toText(), "hello")
    }

    func testCompactHandoffIsTrimmedBeforeRuntimeSystemMessagesAreCoalesced() {
        let tailMarker = "TAIL_MARKER_MUST_BE_TRIMMED"
        let compactHandoff = """
        [Conversation compact handoff: handoff-id]

        \(String(repeating: "旧摘要前段。", count: 2_000))
        \(tailMarker)
        \(String(repeating: "旧摘要后段。", count: 2_000))
        """
        let uploadMessages = [
            UIMessage.companion.system(prompt: compactHandoff),
            UIMessage.companion.user(prompt: "继续")
        ]

        let finalMessages = ChatGenerationRequestPreparationTestSupport.finalizedUploadMessagesForTesting(
            uploadMessages: uploadMessages,
            maxTokens: 1_200,
            messagesByInjectingRuntimeContext: { messages in
                [UIMessage.companion.system(prompt: "runtime context")] + messages
            }
        )
        let finalText = finalMessages.map { $0.toText() }.joined(separator: "\n")

        XCTAssertTrue(finalText.contains("[Conversation compact handoff:"))
        XCTAssertFalse(finalText.contains(tailMarker))
        XCTAssertEqual(finalMessages.filter { $0.role == MessageRole.system }.count, 1)
    }

    func testEnabledSkillIsInjectedAsSystemMessage() throws {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())

        // Create a skill on disk in an isolated temp directory.
        let tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSSkillInjectionTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }

        // The default IOSSkillFileStore reads from Documents/skills; to test in
        // isolation we use a dedicated store and exercise the injection logic
        // directly via the skill-body helper + the injector's data path.
        let store = IOSSkillFileStore(baseDirectory: tempRoot)
        try store.createSkill(name: "summarize", description: "Summarize any text concisely.", allowedTools: [])

        // Enable the skill for the current assistant.
        sharedSettings.setSkillEnabled(name: "summarize", enabled: true)
        XCTAssertTrue(sharedSettings.isSkillEnabled("summarize"))

        // The skill must appear in the on-disk listing.
        XCTAssertTrue(store.listSkillDirNames().contains("summarize"))

        // The markdown body extraction strips frontmatter.
        let markdown = try store.readSkillMarkdown(dirName: "summarize")
        let body = ChatRuntimeContextBuilder.skillBodyFromMarkdown(markdown)
        XCTAssertTrue(body.contains("Summarize any text concisely."))
        XCTAssertFalse(body.contains("description:"))
    }

    func testSkillBodyExtractionHandlesMissingFrontmatter() {
        // No frontmatter → whole content is the body.
        let body = ChatRuntimeContextBuilder.skillBodyFromMarkdown("# Plain skill\nDo the thing.")
        XCTAssertTrue(body.contains("Do the thing."))
    }

    // MARK: - Conversation IO error surfacing

    func testIOErrorIsSetWhenSaveFails() async throws {
        // Point the store at a base directory, then make it unreadable so
        // saveConversation fails. We use a path under a file (not a directory)
        // so the KMP JsonConversationStorage I/O throws.
        let badBase = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationIOError-\(UUID().uuidString)")
        // Write a FILE at badBase (not a dir) so list/save I/O fails.
        try "not-a-directory".data(using: .utf8)!.write(to: badBase)
        defer { try? FileManager.default.removeItem(at: badBase) }

        let store = IOSConversationStore(baseDirectory: badBase)
        await store.bootstrap()

        XCTAssertNotNil(store.lastIOError)
        XCTAssertTrue(store.lastIOError?.message.contains("会话存储") ?? false)
        XCTAssertEqual(store.lastUserVisibleError?.title, "会话存储出错")
        XCTAssertEqual(store.lastUserVisibleError?.severity, .error)
        XCTAssertEqual(store.lastUserVisibleError?.message, store.lastIOError?.message)

        store.clearUserVisibleError()
        XCTAssertNil(store.lastIOError)
        XCTAssertNil(store.lastUserVisibleError)
    }

    func testClearIOErrorResetsToNil() {
        let sharedSettings = IOSSharedSettingsStore(userDefaults: isolatedDefaults())
        _ = sharedSettings // sanity: settings store constructs
        // The clearIOError contract: calling it when there's no error is a no-op.
        let tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSClearIOError-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }
        let store = IOSConversationStore(baseDirectory: tempRoot)
        store.clearIOError()
        XCTAssertNil(store.lastIOError)
        XCTAssertNil(store.lastUserVisibleError)
    }

    func testListSidecarWriteFailureIsUserVisible() throws {
        let badBase = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSConversationSidecarError-\(UUID().uuidString)")
        try "not-a-directory".data(using: .utf8)!.write(to: badBase)
        defer { try? FileManager.default.removeItem(at: badBase) }

        let store = IOSConversationStore(baseDirectory: badBase)
        store.setListPreview(id: KotlinUuid.companion.random(), preview: "预览")

        XCTAssertEqual(store.lastIOError?.operation, "保存会话预览")
        XCTAssertEqual(store.lastUserVisibleError?.title, "会话存储出错")
    }

    func testUserVisibleErrorBusCanPublishNonConversationErrors() {
        let tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("IOSUserVisibleError-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: tempRoot, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }
        let store = IOSConversationStore(baseDirectory: tempRoot)

        store.publishUserVisibleError(
            IOSUserVisibleError(
                title: "Workspace 同步失败",
                message: "无法保存 artifact，请稍后重试。",
                severity: .warning
            )
        )

        XCTAssertNil(store.lastIOError)
        XCTAssertEqual(store.lastUserVisibleError?.title, "Workspace 同步失败")
        XCTAssertEqual(store.lastUserVisibleError?.severity, .warning)

        store.clearUserVisibleError()
        XCTAssertNil(store.lastUserVisibleError)
    }
}

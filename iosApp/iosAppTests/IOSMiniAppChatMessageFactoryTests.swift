import XCTest
import Shared
@testable import iosApp

@MainActor
final class IOSMiniAppChatMessageFactoryTests: XCTestCase {
    func testMightContainMiniAppRequiresCoreFieldsOrHTMLDocument() {
        XCTAssertTrue(IOSMiniAppChatMessageFactory.mightContainMiniApp(
            #"{"title":"计时","description":"番茄钟","html":"<!DOCTYPE html><html></html>"}"#
        ))
        XCTAssertTrue(IOSMiniAppChatMessageFactory.mightContainMiniApp(
            "<!DOCTYPE html><html><body>hello</body></html>"
        ))
        XCTAssertFalse(IOSMiniAppChatMessageFactory.mightContainMiniApp(
            #"{"title":"计时","html":"not really"}"#
        ))
    }

    func testAssistantCandidateSkipsTrailingNoticeAndStaysInCurrentTurn() {
        let priorPayload = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(
                text: #"{"title":"旧应用","description":"不应命中","html":"<!DOCTYPE html><html></html>"}"#,
                metadata: nil
            )],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let user = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Text(text: "做一个番茄钟小应用", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let payload = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(
                text: #"{"title":"计时","description":"番茄钟","html":"<!DOCTYPE html><html></html>"}"#,
                metadata: nil
            )],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let notice = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: "模型连续重复调用工具，已停止本轮。", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let messages = [priorPayload, user, payload, notice]
        XCTAssertEqual(
            IOSMiniAppChatMessageFactory.assistantCandidateIndex(in: messages, afterUserIndex: 1),
            2
        )
    }

    func testGeneratedOutputDecodesMissingCategoryAndPermissions() throws {
        let json = """
        {"title":"计时器","description":"一个番茄钟","html":"<!DOCTYPE html><html><body>ok</body></html>"}
        """
        let output = try JSONDecoder().decode(IOSMiniAppGeneratedOutput.self, from: Data(json.utf8))
        XCTAssertEqual(output.category, "tool")
        XCTAssertEqual(output.permissions, [])
        XCTAssertEqual(output.title, "计时器")
    }

    func testUpdatedAssistantReplacesJSONWithStatusAndMiniAppPart() throws {
        let json = #"{"title":"计时器","description":"一个番茄钟","category":"tool","permissions":[],"html":"<!DOCTYPE html><html><body>ok</body></html>"}"#
        let assistant = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Text(text: "先说明一下\n\(json)", metadata: nil),
            ],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let record = IOSMiniAppRecord(
            id: "app-1",
            title: "计时器",
            description: "一个番茄钟",
            htmlContent: "<!DOCTYPE html><html><body>ok</body></html>",
            sourceConversationId: nil,
            sourceMessageId: nil,
            iconEmoji: "⏱",
            category: "tool",
            permissions: [],
            pinned: false,
            runCount: 0,
            boardSummary: nil,
            version: 1,
            htmlHash: "abc",
            createdAt: 1,
            updatedAt: 1,
            lastRunAt: nil
        )

        let updated = IOSMiniAppChatMessageFactory.updatedAssistant(
            assistant,
            textPartIndex: 0,
            statusText: "已生成小应用：计时器",
            record: record
        )

        XCTAssertEqual(updated.parts.count, 2)
        let text = try XCTUnwrap(updated.parts[0] as? UIMessagePart.Text)
        XCTAssertEqual(text.text, "已生成小应用：计时器")
        let miniApp = try XCTUnwrap(updated.parts[1] as? UIMessagePart.MiniApp)
        XCTAssertEqual(miniApp.appId, "app-1")
        XCTAssertEqual(miniApp.title, "计时器")
        XCTAssertEqual(miniApp.description_, "一个番茄钟")
        XCTAssertEqual(miniApp.iconEmoji, "⏱")
        XCTAssertEqual(miniApp.version, 1)
        XCTAssertEqual(miniApp.htmlHash, "abc")
    }

    func testRevisionPromptIncludesAppIdAndUserRequest() {
        let prompt = IOSMiniAppChatMessageFactory.revisionPrompt(
            appId: "app-9",
            title: "计时器",
            version: 2,
            request: "按钮改小一点"
        )
        XCTAssertTrue(prompt.contains("修改小应用"))
        XCTAssertTrue(prompt.contains("appId: app-9"))
        XCTAssertTrue(prompt.contains("currentVersion: 2"))
        XCTAssertTrue(prompt.contains("按钮改小一点"))
    }

    func testRevisionChangeNoteStopsBeforeBaseInstruction() {
        let note = IOSMiniAppChatMessageFactory.revisionChangeNote(from: """
        修改小应用
        appId: app-1
        currentVersion: 1
        title: 计时器

        用户修改意见：
        增加今日统计

        请基于这个已保存小应用生成新版，并保留适合的能力声明。
        """)
        XCTAssertEqual(note, "增加今日统计")
    }

    func testAppliedMiniAppRollbackRemovesRepositoryAndWorkspaceWrites() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("miniapp-chat-rollback-\(UUID().uuidString)", isDirectory: true)
        let defaultsName = "miniapp-chat-rollback-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer {
            try? FileManager.default.removeItem(at: root)
            defaults.removePersistentDomain(forName: defaultsName)
        }

        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(enabled: true) }
        let repository = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let workspace = IOSWorkspaceStore(baseDirectory: root)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            miniAppRepository: repository,
            workspaceStore: workspace,
            autoGenerateResponses: false
        )
        let output = IOSMiniAppGeneratedOutput(
            title: "计时器",
            description: "一个番茄钟",
            html: "<!DOCTYPE html><html><body>ok</body></html>"
        )
        let payload = try XCTUnwrap(String(data: JSONEncoder().encode(output), encoding: .utf8))
        let application = try XCTUnwrap(viewModel.applyMiniAppOutputIfPresentPublic(
            to: [
                UIMessage.companion.user(prompt: "请创建一个小应用"),
                UIMessage.companion.assistant(prompt: payload),
            ],
            conversationId: nil
        ))

        XCTAssertEqual(repository.apps.count, 1)
        XCTAssertTrue(workspace.artifacts.isEmpty, "Workspace sync must wait until conversation persistence succeeds.")
        XCTAssertTrue(application.rollback())
        XCTAssertTrue(repository.apps.isEmpty)
        XCTAssertTrue(workspace.artifacts.isEmpty)
        XCTAssertTrue(application.rollbackMessages.last?.toText().contains("未保留") == true)
    }

    func testAppliedMiniAppSyncsWorkspaceOnlyAfterConversationCommitHook() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("miniapp-chat-workspace-\(UUID().uuidString)", isDirectory: true)
        let defaultsName = "miniapp-chat-workspace-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: defaultsName))
        defer {
            try? FileManager.default.removeItem(at: root)
            defaults.removePersistentDomain(forName: defaultsName)
        }

        let sharedSettings = IOSSharedSettingsStore(userDefaults: defaults)
        sharedSettings.updateMiniAppRuntime { _ in MiniAppSettingPatch(enabled: true) }
        let repository = IOSMiniAppRepository(baseDirectory: root, seedOnMissingStore: false)
        let workspace = IOSWorkspaceStore(baseDirectory: root)
        let viewModel = ChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: sharedSettings,
            miniAppRepository: repository,
            workspaceStore: workspace,
            autoGenerateResponses: false
        )
        let output = IOSMiniAppGeneratedOutput(
            title: "计时器",
            description: "一个番茄钟",
            html: "<!DOCTYPE html><html><body>ok</body></html>"
        )
        let payload = try XCTUnwrap(String(data: JSONEncoder().encode(output), encoding: .utf8))
        let application = try XCTUnwrap(viewModel.applyMiniAppOutputIfPresentPublic(
            to: [
                UIMessage.companion.user(prompt: "请创建一个小应用"),
                UIMessage.companion.assistant(prompt: payload),
            ],
            conversationId: nil
        ))

        XCTAssertTrue(workspace.artifacts.isEmpty)
        XCTAssertNil(application.syncWorkspaceAfterConversationPersistence())
        XCTAssertEqual(workspace.artifacts.count, 1)
        XCTAssertEqual(workspace.artifacts.first?.sourceId, repository.apps.first?.id)
    }
}

import XCTest
import Shared
@testable import iosApp

/// `ChatCollectionViewController.update(...)` 防重放闸门的专门单测。
///
/// `update(...)` 内部按 `ChatCollectionUpdateKey`(把 `signal`/各配置项打包)去重:
/// 同一个 key 的连续调用只应用一次 `applyCurrentSnapshot()`,避免 SwiftUI
/// `updateUIViewController` 在同一次状态下被多次调用时重复重建整个快照。
///
/// 这里验证两件事:
/// 1. 闸门确实拦住了"同 key 但底层数据已经偷偷变了"的重放调用 —— 不应该
///    重新 build,item 数量必须原地不动。
/// 2. 闸门不会误伤真实更新 —— revision 推进后同一批调用必须正常生效。
@MainActor
final class ChatCollectionUpdateGateTests: XCTestCase {

    private static let screenSize = CGSize(width: 393, height: 852)

    func testDuplicateUpdateKeyDoesNotRebuildSnapshot() {
        let sharedSettings = IOSSharedSettingsStore(
            userDefaults: UserDefaults(suiteName: "ChatCollectionUpdateGateTests-\(UUID().uuidString)")!
        )
        let displaySetting = sharedSettings.displaySetting
        let generativeUiSetting = sharedSettings.agentRuntime.generativeUi

        let workspaceStore = IOSWorkspaceStore(
            baseDirectory: FileManager.default.temporaryDirectory
                .appendingPathComponent("ChatCollectionUpdateGateTests-\(UUID().uuidString)", isDirectory: true)
        )

        var messages: [UIMessage] = []
        let controller = ChatCollectionViewController()
        controller.messagesProvider = { messages }
        controller.variantInfoProvider = { _ in nil }
        controller.onAction = { _ in }
        controller.onViewportStateChange = { _ in }

        let window: UIWindow
        if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
            window = UIWindow(windowScene: scene)
        } else {
            window = UIWindow(frame: .zero)
        }
        window.frame = CGRect(origin: .zero, size: Self.screenSize)
        window.rootViewController = controller
        window.makeKeyAndVisible()
        window.layoutIfNeeded()

        guard let collectionView = Self.findCollectionView(in: controller.view) else {
            XCTFail("未能在视图树中找到 UICollectionView,测试无法继续")
            return
        }

        func pump(_ seconds: TimeInterval) {
            let deadline = Date().addingTimeInterval(seconds)
            repeat {
                RunLoop.current.run(mode: .default, before: Date().addingTimeInterval(0.005))
            } while Date() < deadline
        }

        func applyUpdate(revision: Int) {
            controller.update(
                signal: ChatMessageUpdateSignal(revision: revision, reason: .userAppend),
                configurationIssue: nil,
                isGenerationActive: false,
                isLoading: false,
                isRecognizingImages: false,
                contextCompactState: .idle,
                followGeneration: true,
                displaySetting: displaySetting,
                generativeUiSetting: generativeUiSetting,
                reasoningLevelLabel: nil,
                workspaceStore: workspaceStore,
                scrollToBottomTrigger: 0
            )
        }

        let userMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Text(text: "第一条消息", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )

        // 第一次 update:真实建立快照。
        messages = [userMessage]
        applyUpdate(revision: 1)
        pump(0.05)
        collectionView.layoutIfNeeded()

        let itemCountAfterFirstUpdate = collectionView.numberOfItems(inSection: 0)
        XCTAssertGreaterThan(itemCountAfterFirstUpdate, 0, "首次 update 后应至少渲染出一行 + bottomSpacer")

        // 偷偷在 messagesProvider 背后追加一条新消息,但用同一组参数(同 revision)
        // 再调一次 update —— ChatCollectionUpdateKey 应该判定为重复 key,直接
        // no-op,不应该重新 build,collectionView 的 item 数量必须原地不动。
        let secondMessage = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: "偷偷追加的回复", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        messages = [userMessage, secondMessage]
        applyUpdate(revision: 1)
        pump(0.05)
        collectionView.layoutIfNeeded()

        let itemCountAfterDuplicateUpdate = collectionView.numberOfItems(inSection: 0)
        XCTAssertEqual(
            itemCountAfterDuplicateUpdate,
            itemCountAfterFirstUpdate,
            "同一 revision 的重复 update 不应该触发重新 build(防重放失效,stale 数据被渲染出来了)"
        )

        // revision 推进 —— 闸门只拦重复 key,不能拦住真实更新:这次必须生效,
        // 新追加的消息要真正出现在 collectionView 里。
        applyUpdate(revision: 2)
        pump(0.05)
        collectionView.layoutIfNeeded()

        let itemCountAfterRealUpdate = collectionView.numberOfItems(inSection: 0)
        XCTAssertGreaterThan(
            itemCountAfterRealUpdate,
            itemCountAfterFirstUpdate,
            "revision 推进后的 update 必须真实生效,item 数量应该增加"
        )

        window.isHidden = true
        window.rootViewController = nil
    }

    private static func findCollectionView(in view: UIView) -> UICollectionView? {
        if let collectionView = view as? UICollectionView { return collectionView }
        for subview in view.subviews {
            if let found = findCollectionView(in: subview) { return found }
        }
        return nil
    }
}

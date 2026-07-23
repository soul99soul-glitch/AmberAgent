import XCTest
import SwiftUI
@preconcurrency import Shared
@testable import iosApp

/// 模型议会「持久化 + Room 重开」的真实验证:不是只测编译过,而是 save→load→restore
/// 往返无损(消息流、席位名册、颜色 hex 都还原),以及 ViewModel 重开进入只读重放态。
@MainActor
final class IOSCouncilRoomArchiveStoreTests: XCTestCase {

    private func tempStore() -> CouncilRoomArchiveStore {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("council-archive-test-\(UUID().uuidString)", isDirectory: true)
        return CouncilRoomArchiveStore(baseDirectory: dir)
    }

    private func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "council-archive-test-\(UUID().uuidString)")!
    }

    private func sampleRoom(taskId: String) -> CouncilPersistedRoom {
        let host = CouncilParticipant(
            id: "host", handle: "host", displayName: "Host · gpt-4o",
            roleDescription: "主持、串联、综合", shortLens: "主持与综合",
            systemImage: "crown", tint: Color(red: 0.78, green: 0.25, blue: 0.18),
            isHost: true, modelHint: "gpt-4o", modelId: "gpt-4o"
        )
        let seat = CouncilParticipant(
            id: "risk", handle: "Risk", displayName: "风险官",
            roleDescription: "找漏洞与风险", shortLens: "风险",
            systemImage: "exclamationmark.triangle", tint: Color(red: 0.10, green: 0.45, blue: 0.85),
            isHost: false, modelHint: "claude", modelId: "claude-sonnet"
        )
        let m1 = CouncilChatMessage(
            kind: .host, author: "主持人", body: "本场议题:是否上线该功能。",
            systemImage: "crown", tint: host.tint, subtitle: "主持", status: .completed
        )
        let m2 = CouncilChatMessage(
            kind: .guest, author: "风险官", body: "我反对:回滚成本过高。",
            systemImage: "exclamationmark.triangle", tint: seat.tint, subtitle: "claude-sonnet", status: .completed
        )
        return CouncilPersistedRoom(
            taskId: taskId,
            objective: "是否上线该功能",
            modeRaw: "debate",
            statusRaw: "就绪",
            failedSpeakerIds: ["timeout-seat"],
            participants: [CouncilPersistedParticipant(host), CouncilPersistedParticipant(seat)],
            messages: [CouncilPersistedMessage(m1), CouncilPersistedMessage(m2)],
            updatedAtMs: 1_700_000_000_000
        )
    }

    // MARK: - Store round-trip

    func testArchiveRoundTripIsLossless() throws {
        let store = tempStore()
        let room = sampleRoom(taskId: "run-1")
        store.save(room)
        let loaded = store.load(taskId: "run-1")
        XCTAssertEqual(loaded, room, "save→load 必须无损还原整场议会快照")
    }

    func testCurrentTranscriptRoundTripKeepsTaskAndTerminalState() throws {
        let defaults = isolatedDefaults()
        let room = sampleRoom(taskId: "run-current")

        CouncilTranscriptStore.save(room, defaults: defaults)

        let loaded = try XCTUnwrap(CouncilTranscriptStore.load(defaults: defaults))
        XCTAssertEqual(loaded.taskId, "run-current")
        XCTAssertEqual(loaded.objective, room.objective)
        XCTAssertEqual(loaded.statusRaw, room.statusRaw)
        XCTAssertEqual(loaded.messages, room.messages)
    }

    func testInterruptedArchiveConvertsStreamingMessagesToFailed() throws {
        let store = tempStore()
        let room = sampleRoom(taskId: "run-interrupted")
        let speaking = CouncilPersistedMessage(
            id: room.messages[0].id,
            kind: room.messages[0].kind,
            author: room.messages[0].author,
            body: room.messages[0].body,
            systemImage: room.messages[0].systemImage,
            tintHex: room.messages[0].tintHex,
            subtitle: room.messages[0].subtitle,
            status: "speaking"
        )
        store.save(CouncilPersistedRoom(
            taskId: room.taskId,
            objective: room.objective,
            modeRaw: room.modeRaw,
            statusRaw: "运行中",
            failedSpeakerIds: room.failedSpeakerIds,
            participants: room.participants,
            messages: [speaking] + Array(room.messages.dropFirst()),
            updatedAtMs: room.updatedAtMs
        ))

        store.markInterrupted(taskIds: [room.taskId])

        let recovered = try XCTUnwrap(store.load(taskId: room.taskId))
        XCTAssertEqual(recovered.statusRaw, IOSAdvancedTaskStatus.interrupted.title)
        XCTAssertEqual(recovered.messages.first?.status, "failed")
    }

    func testExistsAndDelete() {
        let store = tempStore()
        XCTAssertFalse(store.exists(taskId: "run-x"))
        store.save(sampleRoom(taskId: "run-x"))
        XCTAssertTrue(store.exists(taskId: "run-x"))
        store.delete(taskId: "run-x")
        XCTAssertFalse(store.exists(taskId: "run-x"))
        XCTAssertNil(store.load(taskId: "run-x"))
    }

    func testEachTaskArchivedToItsOwnFile() {
        // 多场历史互不覆盖(单房间 transcript 做不到的关键点)。
        let store = tempStore()
        store.save(sampleRoom(taskId: "a"))
        store.save(sampleRoom(taskId: "b"))
        XCTAssertNotNil(store.load(taskId: "a"))
        XCTAssertNotNil(store.load(taskId: "b"))
        XCTAssertEqual(store.load(taskId: "a")?.taskId, "a")
        XCTAssertEqual(store.load(taskId: "b")?.taskId, "b")
    }

    // MARK: - Deferred (off-main) write pump

    func testDeferredWritesCoalesceToLatestSnapshot() async throws {
        // 同一 MainActor 回合内连续两次 saveDeferred:写入泵尚未启动,第二份直接覆盖
        // 第一份(latest-wins),最终只落盘一次且是最新快照。
        let store = tempStore()
        let v1 = sampleRoom(taskId: "run-coalesce")
        let v2 = CouncilPersistedRoom(
            taskId: "run-coalesce",
            objective: "更新后的议题",
            modeRaw: v1.modeRaw,
            statusRaw: v1.statusRaw,
            failedSpeakerIds: v1.failedSpeakerIds,
            participants: v1.participants,
            messages: v1.messages,
            updatedAtMs: v1.updatedAtMs + 1000
        )
        store.saveDeferred(v1)
        store.saveDeferred(v2)

        await store.flushDeferred()

        XCTAssertEqual(store.completedWriteCount, 1, "两次 saveDeferred 应合并成一次离主线程写入")
        XCTAssertEqual(store.load(taskId: "run-coalesce")?.objective, "更新后的议题", "应落盘最新快照")
    }

    func testSyncSaveInvalidatesStaleDeferredWrite() async throws {
        // 延迟快照入队后、落盘前若发生同步 save(终态检查点),代数闸必须让这份过时
        // 延迟写跳过,避免旧快照覆盖终态最新事实。
        let store = tempStore()
        let stale = sampleRoom(taskId: "run-gate")
        let fresh = CouncilPersistedRoom(
            taskId: "run-gate",
            objective: "终态最新",
            modeRaw: stale.modeRaw,
            statusRaw: "就绪",
            failedSpeakerIds: stale.failedSpeakerIds,
            participants: stale.participants,
            messages: stale.messages,
            updatedAtMs: stale.updatedAtMs + 5000
        )
        store.saveDeferred(stale)
        store.save(fresh)

        await store.flushDeferred()

        XCTAssertEqual(store.load(taskId: "run-gate")?.objective, "终态最新", "同步终态写必须是最终事实")
        XCTAssertEqual(store.completedWriteCount, 1, "过时的延迟写应被代数闸跳过,只有同步写落盘")
    }

    func testFlushDeferredMakesDeferredWriteVisibleToLoad() async throws {
        // 「写后立即 load」的检查点(openArchive 的 reload)依赖 flushDeferred 排空在途
        // 写入,保证 load 看到延迟写的最新快照。
        let store = tempStore()
        let room = sampleRoom(taskId: "run-flush")
        store.saveDeferred(room)

        await store.flushDeferred()

        XCTAssertEqual(store.load(taskId: "run-flush")?.taskId, "run-flush", "flush 之后延迟写必须对 load 可见")
        XCTAssertEqual(store.completedWriteCount, 1)
    }

    // MARK: - DTO restore fidelity (the "Color encoding" concern)

    func testMessageDTOPreservesColorHexKindAndStatus() {
        let tint = Color(red: 0.80, green: 0.20, blue: 0.10)
        let message = CouncilChatMessage(
            kind: .guest, author: "议员", body: "正文", systemImage: "x",
            tint: tint, subtitle: "副标题", status: .failed
        )
        let dto = CouncilPersistedMessage(message)
        XCTAssertEqual(dto.kind, "guest")
        XCTAssertEqual(dto.status, "failed")
        XCTAssertTrue(dto.tintHex.hasPrefix("#") && dto.tintHex.count == 7, "颜色应存成 #RRGGBB")

        // 还原后再次编码,hex 必须稳定 —— 证明颜色穿过持久化没漂移。
        let restored = dto.restored()
        let reDto = CouncilPersistedMessage(restored)
        XCTAssertEqual(reDto.tintHex, dto.tintHex, "颜色 hex 往返必须稳定")
        XCTAssertEqual(restored.author, "议员")
        XCTAssertEqual(restored.body, "正文")
        XCTAssertEqual(reDto.kind, "guest")
        XCTAssertEqual(reDto.status, "failed")
    }

    func testRestoredSpeakingStatusIsCleanedToFailed() {
        // A message still `.speaking` when the process is killed mid-generation
        // must not resurrect as "speaking" forever after a cold start; only a
        // fresh discussion (resetRoom) should ever set `.speaking` again.
        let speakingMessage = CouncilChatMessage(
            kind: .host, author: "主持人", body: "正在生成", systemImage: "crown",
            tint: .red, subtitle: nil, status: .speaking
        )
        let restoredSpeaking = CouncilPersistedMessage(speakingMessage).restored()
        XCTAssertEqual(restoredSpeaking.status, .failed, "冷启动恢复的 speaking 行必须清洗为 failed,不能永远显示发言中")

        let completedMessage = CouncilChatMessage(
            kind: .host, author: "主持人", body: "已完成", systemImage: "crown",
            tint: .red, subtitle: nil, status: .completed
        )
        XCTAssertEqual(CouncilPersistedMessage(completedMessage).restored().status, .completed)

        let failedMessage = CouncilChatMessage(
            kind: .host, author: "主持人", body: "失败了", systemImage: "crown",
            tint: .red, subtitle: nil, status: .failed
        )
        XCTAssertEqual(CouncilPersistedMessage(failedMessage).restored().status, .failed)
    }

    func testParticipantDTOPreservesIdentityAndModel() {
        let p = CouncilParticipant(
            id: "design", handle: "Design", displayName: "设计师",
            roleDescription: "可用性视角", shortLens: "设计",
            systemImage: "paintbrush", tint: Color(red: 0.3, green: 0.7, blue: 0.4),
            isHost: false, modelHint: "glm", modelId: "glm-4"
        )
        let restored = CouncilPersistedParticipant(p).restored()
        XCTAssertEqual(restored.id, "design")
        XCTAssertEqual(restored.displayName, "设计师")
        XCTAssertEqual(restored.roleDescription, "可用性视角")
        XCTAssertEqual(restored.modelId, "glm-4")
        XCTAssertFalse(restored.isHost)
    }

    // MARK: - ViewModel reopen path (link actually connected)

    func testOpenArchiveEntersReadOnlyReplayAndRestoresConversation() {
        let store = tempStore()
        store.save(sampleRoom(taskId: "hist-1"))

        let vm = CouncilChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(),
            providerRegistry: nil,
            permissionStore: IOSPermissionStore(),
            transcriptDefaults: isolatedDefaults(),
            archiveStore: store
        )

        vm.openArchive(taskId: "hist-1")

        XCTAssertTrue(vm.isReplay, "重开历史议会应进入只读重放态")
        XCTAssertEqual(vm.activeReplayTaskId, "hist-1")
        XCTAssertEqual(vm.messages.count, 2, "两条消息应被还原")
        XCTAssertEqual(vm.messages.first?.author, "主持人")
        XCTAssertEqual(vm.messages.last?.body, "我反对:回滚成本过高。")
        XCTAssertTrue(vm.participants.contains { $0.id == "host" }, "席位名册应还原")
        XCTAssertTrue(vm.participants.contains { $0.id == "risk" })
        XCTAssertTrue(vm.failedSpeakerIds.contains("timeout-seat"), "失败席位应还原")
    }

    func testOpenArchiveMissingTaskIsNoOp() {
        let store = tempStore()
        let vm = CouncilChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(),
            providerRegistry: nil,
            permissionStore: IOSPermissionStore(),
            transcriptDefaults: isolatedDefaults(),
            archiveStore: store
        )
        vm.openArchive(taskId: "does-not-exist")
        XCTAssertFalse(vm.isReplay, "找不到归档时不应进入重放态(诚实失败,不伪造)")
    }

    func testStartFreshRoomExitsReplay() {
        let store = tempStore()
        store.save(sampleRoom(taskId: "hist-2"))
        let vm = CouncilChatViewModel(
            settingsStore: SettingsStore(),
            sharedSettings: IOSSharedSettingsStore(),
            providerRegistry: nil,
            permissionStore: IOSPermissionStore(),
            transcriptDefaults: isolatedDefaults(),
            archiveStore: store
        )
        vm.openArchive(taskId: "hist-2")
        XCTAssertTrue(vm.isReplay)

        vm.startFreshRoom()
        XCTAssertFalse(vm.isReplay, "开新议会应退出只读重放")
        XCTAssertNil(vm.activeReplayTaskId)
    }
}

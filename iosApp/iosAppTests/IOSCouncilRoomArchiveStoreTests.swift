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

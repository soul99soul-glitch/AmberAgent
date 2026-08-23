import XCTest
@preconcurrency import Shared
@testable import iosApp

/// [Slice 4] Verifies the council-seat write-back bridge:
///  1. addCouncilSeat merges into `snapshot.agentRuntime.modelCouncil.defaultSeats`
///     via IosSettingsMutations (KMP), not just a side-channel UserDefaults row.
///  2. The merged seat survives a fresh store init (simulates app restart),
///     proving the snapshot is persisted via restoreSnapshot.
///  3. removeCouncilSeat removes from the snapshot and the seat does not reappear
///     after restart (no resurrection).
///
/// These tests gate the Slice 4 acceptance "新增/删除席位→重启→保留/真没了"
/// without needing UI taps (simctl has no tap). They use an isolated
/// UserDefaults suite so they don't pollute the app's real settings.
@MainActor
final class IOSSharedSettingsStoreCouncilSeatTests: XCTestCase {

    /// A valid Uuid string the KMP side will accept (kotlin.uuid.Uuid.parse).
    /// Reused across seats; the seatId distinguishes them.
    private let validModelId = "00000000-0000-0000-0000-000000000001"

    func testAddCouncilSeatMergesIntoSnapshotDefaultSeats() {
        let store = makeIsolatedStore()
        let baseline = store.snapshot.agentRuntime.modelCouncil.defaultSeats.count

        store.addCouncilSeat(
            name: "测试工程席",
            role: "工程",
            modelId: validModelId,
            runnerType: "provider"
        )

        let after = store.snapshot.agentRuntime.modelCouncil.defaultSeats
        XCTAssertEqual(after.count, baseline + 1, "addCouncilSeat must add a real seat to the snapshot")
        let added = after.last
        XCTAssertEqual(added?.name, "测试工程席")
        XCTAssertEqual(added?.role, "工程")
        XCTAssertFalse(added?.seatId.isEmpty ?? true, "seat must have a generated seatId")
    }

    func testAddedCouncilSeatSurvivesRestart() {
        // First store instance: add a seat (persists snapshot JSON to UserDefaults).
        let suiteName = "Slice4-Restart-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addCouncilSeat(
            name: "重启后应在",
            role: "工程",
            modelId: validModelId,
            runnerType: "provider"
        )
        let countBeforeRestart = store1.snapshot.agentRuntime.modelCouncil.defaultSeats.count

        // Second store instance from the SAME suite: simulates app restart
        // (init reads the persisted sharedSettingsJson).
        let store2 = makeIsolatedStore(suiteName: suiteName)
        let seatsAfterRestart = store2.snapshot.agentRuntime.modelCouncil.defaultSeats

        XCTAssertEqual(
            seatsAfterRestart.count,
            countBeforeRestart,
            "Added seat must survive a fresh store init (app restart)"
        )
        XCTAssertTrue(
            seatsAfterRestart.contains { $0.name == "重启后应在" },
            "The specific added seat must still be present after restart"
        )
    }

    func testRemoveCouncilSeatDoesNotResurrectAfterRestart() {
        let suiteName = "Slice4-Remove-\(UUID().uuidString)"
        let store1 = makeIsolatedStore(suiteName: suiteName)
        store1.addCouncilSeat(
            name: "待删除",
            role: "工程",
            modelId: validModelId,
            runnerType: "provider"
        )
        let mirrorCount = store1.savedCouncilSeats.count
        XCTAssertGreaterThan(mirrorCount, 0, "seat should be in the legacy mirror too")

        // Remove by index 0 (the seat we just added is the only custom one).
        store1.removeCouncilSeat(at: 0)
        XCTAssertEqual(
            store1.snapshot.agentRuntime.modelCouncil.defaultSeats
                .filter { $0.name == "待删除" }.count,
            0,
            "seat must be removed from the snapshot immediately"
        )

        // Restart: the deleted seat must NOT come back.
        let store2 = makeIsolatedStore(suiteName: suiteName)
        XCTAssertEqual(
            store2.snapshot.agentRuntime.modelCouncil.defaultSeats
                .filter { $0.name == "待删除" }.count,
            0,
            "Deleted seat must not resurrect after restart"
        )
    }

    func testInvalidModelIdFallsBackToMirrorOnly() {
        // Per the 铁律 (don't fake): an invalid modelId cannot build a real
        // ModelCouncilSeat, so it must NOT pollute the snapshot. It lands in
        // the legacy mirror only (honest fallback).
        let store = makeIsolatedStore()
        let baseline = store.snapshot.agentRuntime.modelCouncil.defaultSeats.count
        store.addCouncilSeat(
            name: "坏 modelId",
            role: "工程",
            modelId: "not-a-uuid",
            runnerType: "provider"
        )
        XCTAssertEqual(
            store.snapshot.agentRuntime.modelCouncil.defaultSeats.count,
            baseline,
            "Invalid modelId must not add a fake seat to the snapshot"
        )
    }

    // MARK: - helpers

    /// Build an IOSSharedSettingsStore that writes its snapshot to an isolated
    /// UserDefaults suite, so tests don't touch the app's real settings and
    /// don't bleed into each other.
    private func makeIsolatedStore(suiteName: String = "Slice4-Isolated-\(UUID().uuidString)") -> IOSSharedSettingsStore {
        IOSSharedSettingsStore(userDefaults: UserDefaults(suiteName: suiteName)!)
    }
}

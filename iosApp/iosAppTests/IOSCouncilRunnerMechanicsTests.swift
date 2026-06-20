import XCTest
@preconcurrency import Shared
@testable import iosApp

/// Council runner mechanics tests. The iOS Room runner is the formal execution
/// path; fake streamers/researchers keep these tests offline and deterministic.
@MainActor
final class IOSCouncilRunnerMechanicsTests: XCTestCase {

    func testDefaultSeatDescriptorsIncludeHostRiskOpponent() {
        // The Android parity core-seats (host/opponent/judge-or-risk) should be
        // represented in the default council roster.
        let seats = CouncilRunner.defaultSeatDescriptorsForTesting()
        let seatIds = Set(seats.map(\.id))
        XCTAssertTrue(seatIds.contains("host"), "host seat must be present")
        XCTAssertTrue(seats.count >= 3, "council should default to at least 3 seats")
        // Each seat must carry a non-empty role description (Android seat.role parity).
        XCTAssertTrue(seats.allSatisfy { !$0.role.isEmpty && !$0.name.isEmpty })
    }

    func testCouncilSeatDescriptorIsEquatable() {
        let a = IOSCouncilSeatDescriptor(id: "x", name: "X", role: "r", modelLabel: "m")
        let b = IOSCouncilSeatDescriptor(id: "x", name: "X", role: "r", modelLabel: "m")
        let c = IOSCouncilSeatDescriptor(id: "y", name: "Y", role: "r", modelLabel: "m")
        XCTAssertEqual(a, b)
        XCTAssertNotEqual(a, c)
    }

    func testRoomSettingsPersistHostLimitsAndSeatPayload() throws {
        let defaults = isolatedDefaults()
        let store = IOSCouncilRoomSettingsStore(
            userDefaults: defaults,
            storageKey: "settings",
            currentModelId: "gpt-main"
        )

        store.updateHost(modelId: "gpt-host", reasoning: .high, prompt: "主持 prompt")
        store.updateLimits(maxSeats: 6, defaultRounds: 3, seatTimeoutSeconds: 45, outputBudgetCharacters: 16_000)
        store.addOrUpdateSeat(
            IOSCouncilRoomSeatConfig(
                id: "design",
                name: "设计",
                rolePrompt: "看体验",
                modelId: "gpt-seat",
                reasoning: .low,
                prompt: "只说关键判断",
                isDefault: false
            ),
            currentModelId: "gpt-main"
        )

        let reloaded = IOSCouncilRoomSettingsStore(
            userDefaults: defaults,
            storageKey: "settings",
            currentModelId: "gpt-main"
        )
        XCTAssertEqual(reloaded.settings.host.modelId, "gpt-host")
        XCTAssertEqual(reloaded.settings.host.reasoning, .high)
        XCTAssertEqual(reloaded.settings.host.prompt, "主持 prompt")
        XCTAssertEqual(reloaded.settings.limits.maxSeats, 6)
        XCTAssertEqual(reloaded.settings.limits.defaultRounds, 3)
        XCTAssertEqual(reloaded.settings.limits.seatTimeoutSeconds, 45)
        XCTAssertEqual(reloaded.settings.limits.outputBudgetCharacters, 16_000)

        let seat = try XCTUnwrap(reloaded.settings.seats.first { $0.id == "design" })
        XCTAssertEqual(seat.name, "设计")
        XCTAssertEqual(seat.modelId, "gpt-seat")
        XCTAssertEqual(seat.reasoning, .low)
        XCTAssertEqual(seat.prompt, "只说关键判断")
        XCTAssertFalse(seat.isDefault)
    }

    func testPlannedSeatsParseDynamicSeatLinesAndFallbackWhenInvalid() {
        let fallback = [
            roomSpeaker(id: "engineering", name: "工程", modelId: "gpt-engineer"),
            roomSpeaker(id: "risk", name: "风险", modelId: "gpt-risk"),
            roomSpeaker(id: "product", name: "产品", modelId: "gpt-product")
        ]

        let planned = IOSCouncilRoomRunner.plannedSeats(
            from: """
            最终议题：完善 iOS 模型议会
            席位: 工程 | 看实现复杂度
            Seat: 风险 | 看安全和失败模式
            """,
            fallback: fallback,
            maxSeats: 2,
            currentModelId: "gpt-main"
        )

        XCTAssertEqual(planned.map(\.name), ["工程", "风险"])
        XCTAssertEqual(planned.map(\.id), ["engineering", "risk"])
        XCTAssertEqual(planned.map(\.modelId), ["gpt-engineer", "gpt-risk"])

        let fallbacked = IOSCouncilRoomRunner.plannedSeats(
            from: "席位: 只有一个 | 数量非法",
            fallback: fallback,
            maxSeats: 2,
            currentModelId: "gpt-main"
        )
        XCTAssertEqual(fallbacked.map(\.id), ["engineering", "risk"])
    }

    func testRoomRunnerFreeChatPersistsTaskApprovalAndOrderedMessages() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let permissionStore = IOSPermissionStore(
            userDefaults: defaults,
            storageKey: "policies",
            approvalStorageKey: "approvals",
            taskStore: taskStore
        )
        let policiesBefore = permissionStore.policies
        let streamer = ScriptedCouncilStreamer([
            .success("""
            最终议题：把模型议会做成正式 Room。
            席位: 工程 | 看实现路径
            席位: 风险 | 看失败模式
            """),
            .success("工程建议：拆 runner，UI 只消费事件。"),
            .success("风险建议：不要伪装跨 provider。"),
            .success("主持总结：先做当前服务商内多模型 Room。")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore,
            permissionStore: permissionStore
        )
        var events: [IOSCouncilRoomEvent] = []

        let outcome = await runner.run(
            request: roomRequest(
                mode: .freeChat,
                settings: compactRoomSettings(),
                researchConsent: .allowed
            ),
            onEvent: { events.append($0) }
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.failedSeats, [])
        XCTAssertTrue(outcome.transcript.contains("主持总结"))
        XCTAssertEqual(streamer.callCount, 4)
        XCTAssertEqual(permissionStore.policies, policiesBefore, "Room consent must not mutate global permission policy.")

        let messageAuthors = events.compactMap { event -> String? in
            guard case let .append(message) = event,
                  message.kind == .host || message.kind == .seat else { return nil }
            return message.author
        }
        XCTAssertEqual(messageAuthors, ["主持人", "工程", "风险", "主持人"])

        XCTAssertEqual(Set(permissionStore.approvalRecords.map(\.toolName)), ["search_web", "scrape_web"])
        XCTAssertTrue(permissionStore.approvalRecords.allSatisfy { $0.action == .allowed })

        let task = try XCTUnwrap(taskStore.recent(kind: .modelCouncil, limit: 1).first)
        XCTAssertEqual(task.status, .completed)
        XCTAssertEqual(task.toolScope, ["search_web", "scrape_web"])
        XCTAssertEqual(task.metadata["room_mode"], IOSCouncilRoomRunMode.freeChat.rawValue)
    }

    func testRoomRunnerSeatFailureCompletesWithFailedSeat() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = ScriptedCouncilStreamer([
            .success("""
            最终议题：压测席位失败降级。
            席位: 工程 | 先给实现判断
            席位: 风险 | 再给风险判断
            """),
            .success("工程发言完成。"),
            .failure(CouncilTestError.scriptedFailure),
            .success("主持总结：风险席位缺席，结论保持不确定。")
        ])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )
        var events: [IOSCouncilRoomEvent] = []

        let outcome = await runner.run(
            request: roomRequest(
                mode: .debate,
                settings: compactRoomSettings(defaultRounds: 1),
                researchConsent: .denied
            ),
            onEvent: { events.append($0) }
        )

        XCTAssertEqual(outcome.status, .completed)
        XCTAssertEqual(outcome.failedSeats, ["风险"])
        XCTAssertEqual(streamer.callCount, 4)
        XCTAssertTrue(outcome.transcript.contains("风险席位缺席"))

        let failedUpdate = events.contains { event in
            guard case let .updateMessage(_, body, status) = event else { return false }
            return status == .failed && body.contains("席位失败")
        }
        XCTAssertTrue(failedUpdate)

        let task = try XCTUnwrap(taskStore.recent(kind: .modelCouncil, limit: 1).first)
        XCTAssertEqual(task.status, .completed)
        XCTAssertTrue(task.retryable)
        XCTAssertTrue(task.error.contains("风险"))
    }

    func testRoomRunnerMissingAPIKeyFailsBeforeStreaming() async throws {
        let defaults = isolatedDefaults()
        let taskStore = IOSAdvancedTaskStore(userDefaults: defaults, storageKey: "tasks")
        let streamer = ScriptedCouncilStreamer([])
        let runner = IOSCouncilRoomRunner(
            streamer: streamer,
            researcher: StaticCouncilResearcher(),
            taskStore: taskStore
        )

        let outcome = await runner.run(
            request: roomRequest(
                settings: compactRoomSettings(),
                apiKey: "",
                researchConsent: .unavailable
            )
        )

        XCTAssertEqual(outcome.status, .failed)
        XCTAssertEqual(streamer.callCount, 0)
        let task = try XCTUnwrap(taskStore.recent(kind: .modelCouncil, limit: 1).first)
        XCTAssertEqual(task.status, .failed)
        XCTAssertTrue(task.error.contains("API Key"))
    }

    private func compactRoomSettings(
        currentModelId: String = "gpt-main",
        defaultRounds: Int = 2
    ) -> IOSCouncilRoomSettings {
        IOSCouncilRoomSettings(
            host: IOSCouncilHostConfig(
                modelId: "gpt-host",
                reasoning: .off,
                prompt: "主持人只做议题组织和总结。"
            ),
            seats: [
                IOSCouncilRoomSeatConfig(
                    id: "engineering",
                    name: "工程",
                    rolePrompt: "工程实现",
                    modelId: "gpt-engineer",
                    reasoning: .medium,
                    prompt: "短句",
                    isDefault: true
                ),
                IOSCouncilRoomSeatConfig(
                    id: "risk",
                    name: "风险",
                    rolePrompt: "风险审计",
                    modelId: "gpt-risk",
                    reasoning: .high,
                    prompt: "指出缺口",
                    isDefault: true
                )
            ],
            limits: IOSCouncilRoomLimits(
                maxSeats: 2,
                defaultRounds: defaultRounds,
                seatTimeoutSeconds: 30,
                outputBudgetCharacters: 6_000
            ),
            legacySeatsImported: true
        ).normalized(currentModelId: currentModelId)
    }

    private func roomRequest(
        mode: IOSCouncilRoomRunMode = .freeChat,
        settings: IOSCouncilRoomSettings,
        apiKey: String = "test-key",
        researchConsent: IOSCouncilResearchConsent = .unavailable
    ) -> IOSCouncilRoomRunRequest {
        IOSCouncilRoomRunRequest(
            objective: "完善 iOS 模型议会",
            mode: mode,
            settings: settings,
            currentModelId: "gpt-main",
            providerSetting: IOSCouncilRoomRunner.makeProviderSetting(
                baseUrl: "https://example.com/v1",
                apiKey: apiKey
            ),
            searchSettings: nil,
            researchConsent: researchConsent
        )
    }

    private func roomSpeaker(id: String, name: String, modelId: String) -> IOSCouncilRoomSpeaker {
        IOSCouncilRoomSpeaker(
            id: id,
            name: name,
            rolePrompt: "\(name)职责",
            modelId: modelId,
            reasoning: .medium,
            prompt: "",
            isHost: false
        )
    }

    private func isolatedDefaults() -> UserDefaults {
        let suiteName = "app.amber.ios.council.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        return defaults
    }
}

@MainActor
private final class ScriptedCouncilStreamer: IOSCouncilTextStreaming {
    private var outputs: [Result<String, Error>]
    private(set) var callCount = 0
    private(set) var cancelCount = 0

    init(_ outputs: [Result<String, Error>]) {
        self.outputs = outputs
    }

    func streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: [UIMessage],
        params: TextGenerationParams,
        onUpdate: @escaping @MainActor (String) -> Void
    ) async throws -> String {
        callCount += 1
        guard !outputs.isEmpty else { throw CouncilTestError.unexpectedCall }
        let next = outputs.removeFirst()
        switch next {
        case .success(let text):
            onUpdate(text)
            return text
        case .failure(let error):
            throw error
        }
    }

    func cancel() {
        cancelCount += 1
    }
}

@MainActor
private final class StaticCouncilResearcher: IOSCouncilResearching {
    func research(
        objective: String,
        settings: Settings?,
        maxSearches: Int,
        maxScrapes: Int
    ) async -> IOSCouncilResearchBundle {
        IOSCouncilResearchBundle(
            searches: [],
            scrapedPages: [
                IOSCouncilScrapedPage(
                    url: "https://example.com/current",
                    content: "Fresh context for \(objective)."
                )
            ],
            failures: []
        )
    }
}

private enum CouncilTestError: LocalizedError {
    case scriptedFailure
    case unexpectedCall

    var errorDescription: String? {
        switch self {
        case .scriptedFailure: "scripted seat failure"
        case .unexpectedCall: "unexpected streamer call"
        }
    }
}

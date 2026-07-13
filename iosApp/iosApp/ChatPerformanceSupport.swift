import Foundation
import os
import SwiftUI
@preconcurrency import Shared

/// Sparse, opt-in signposts for the chat pipeline. The trace never records message
/// contents or identifiers, and it remains available in Release/Profile builds.
enum ChatPerfTrace {
    static let enabledDefaultsKey = "chat.perf.trace.enabled"
    static let subsystem = "app.amber.ios"
    static let category = "ChatPerformance"

    struct Interval {
        fileprivate let name: StaticString
        fileprivate let state: OSSignpostIntervalState
    }

    private static let signposter = OSSignposter(subsystem: subsystem, category: category)
    private static let configuredEnabled: Bool = {
#if CHAT_PERF_REPLAY
        true
#else
        UserDefaults.standard.bool(forKey: enabledDefaultsKey) ||
            ProcessInfo.processInfo.arguments.contains("-ChatPerfTrace") ||
            ProcessInfo.processInfo.environment["AA_CHAT_PERF_TRACE"] == "1"
#endif
    }()

    static var isEnabled: Bool {
        configuredEnabled && signposter.isEnabled
    }

    static func begin(_ name: StaticString, count: Int = 0) -> Interval? {
        guard isEnabled else { return nil }
        let id = signposter.makeSignpostID()
        let state = signposter.beginInterval(name, id: id, "count=\(count)")
        return Interval(name: name, state: state)
    }

    static func end(_ interval: inout Interval?) {
        guard let current = interval else { return }
        signposter.endInterval(current.name, current.state)
        interval = nil
    }

    static func event(_ name: StaticString, value: Int = 0) {
        guard isEnabled else { return }
        signposter.emitEvent(name, id: signposter.makeSignpostID(), "value=\(value)")
    }

    @discardableResult
    static func measure<T>(
        _ name: StaticString,
        count: () -> Int = { 0 },
        _ operation: () throws -> T
    ) rethrows -> T {
        guard isEnabled else { return try operation() }
        var interval = begin(name, count: count())
        defer { end(&interval) }
        return try operation()
    }

    @discardableResult
    static func measure<T>(
        _ name: StaticString,
        count: () -> Int = { 0 },
        _ operation: () async throws -> T
    ) async rethrows -> T {
        guard isEnabled else { return try await operation() }
        var interval = begin(name, count: count())
        defer { end(&interval) }
        return try await operation()
    }
}

struct ChatStreamReplayFrame: Equatable, Sendable {
    let elapsedMilliseconds: Int64
    let cumulativeText: String
}

enum ChatPerfReplayFixtureName: String, CaseIterable, Sendable {
    case longProse = "chat-perf-long-prose"
    case growingTable = "chat-perf-growing-table"
}

enum ChatStreamReplayFixtureError: Error, Equatable, LocalizedError {
    case missingResource(String)
    case invalidRecord(line: Int)
    case nonMonotonicTimestamp(line: Int)
    case emptyFixture

    var errorDescription: String? {
        switch self {
        case .missingResource(let name):
            "Missing replay fixture: \(name).jsonl"
        case .invalidRecord(let line):
            "Invalid replay record at line \(line)."
        case .nonMonotonicTimestamp(let line):
            "Replay timestamps are not monotonic at line \(line)."
        case .emptyFixture:
            "Replay fixture contains no frames."
        }
    }
}

enum ChatStreamReplayFixture {
    private struct Record: Decodable {
        struct Metadata: Decodable {
            let startedAt: Int64?
            let runId: String?
        }

        let meta: Metadata?
        let timestamp: Int64?
        let delta: String?
        let reset: String?

        enum CodingKeys: String, CodingKey {
            case meta
            case timestamp = "t"
            case delta = "d"
            case reset
        }
    }

    static func decodeJSONL(_ data: Data) throws -> [ChatStreamReplayFrame] {
        let decoder = JSONDecoder()
        var cumulativeText = ""
        var frames: [ChatStreamReplayFrame] = []
        var previousTimestamp: Int64 = 0

        for (zeroBasedIndex, bytes) in data.split(
            separator: 0x0A,
            omittingEmptySubsequences: false
        ).enumerated() {
            let lineNumber = zeroBasedIndex + 1
            guard !bytes.allSatisfy({ $0 == 0x0D || $0 == 0x20 || $0 == 0x09 }) else {
                continue
            }
            let record: Record
            do {
                record = try decoder.decode(Record.self, from: Data(bytes))
            } catch {
                throw ChatStreamReplayFixtureError.invalidRecord(line: lineNumber)
            }

            if record.meta != nil {
                guard record.timestamp == nil, record.delta == nil, record.reset == nil else {
                    throw ChatStreamReplayFixtureError.invalidRecord(line: lineNumber)
                }
                continue
            }

            guard let timestamp = record.timestamp,
                  timestamp >= 0,
                  (record.delta == nil) != (record.reset == nil) else {
                throw ChatStreamReplayFixtureError.invalidRecord(line: lineNumber)
            }
            guard frames.isEmpty || timestamp >= previousTimestamp else {
                throw ChatStreamReplayFixtureError.nonMonotonicTimestamp(line: lineNumber)
            }

            if let reset = record.reset {
                cumulativeText = reset
            } else if let delta = record.delta {
                cumulativeText.append(delta)
            }
            frames.append(ChatStreamReplayFrame(
                elapsedMilliseconds: timestamp,
                cumulativeText: cumulativeText
            ))
            previousTimestamp = timestamp
        }

        guard !frames.isEmpty else {
            throw ChatStreamReplayFixtureError.emptyFixture
        }
        return frames
    }

    static func loadBundled(
        _ fixture: ChatPerfReplayFixtureName,
        bundle: Bundle = .main
    ) throws -> [ChatStreamReplayFrame] {
        guard let url = bundle.url(forResource: fixture.rawValue, withExtension: "jsonl") else {
            throw ChatStreamReplayFixtureError.missingResource(fixture.rawValue)
        }
        return try decodeJSONL(Data(contentsOf: url))
    }
}

#if CHAT_PERF_REPLAY || DEBUG
@MainActor
private final class ChatPerfReplayModel: ObservableObject {
    @Published var signal = ChatMessageUpdateSignal(revision: 0, reason: .initialLoad)
    @Published var isGenerationActive = false
    @Published var scrollToBottomTrigger = 0
    @Published var viewportState = ChatViewportState()
    @Published var errorDescription: String?

    var messages: [UIMessage] = []

    private let fixtureName: ChatPerfReplayFixtureName
    private var didStart = false

    init(fixtureName: ChatPerfReplayFixtureName) {
        self.fixtureName = fixtureName
    }

    func start() async {
        guard !didStart else { return }
        didStart = true

        do {
            let frames = try ChatStreamReplayFixture.loadBundled(fixtureName)

            messages = makeHistory(turns: 20)
            send(.initialLoad)

            let clock = ContinuousClock()
            try await clock.sleep(for: .seconds(2))
            messages.append(makeUserMessage("请继续生成固定性能回放内容。"))
            isGenerationActive = true
            send(.userAppend)
            try await clock.sleep(for: .seconds(2))

            var replayTrace = ChatPerfTrace.begin("ReplayStreamInput", count: frames.count)
            defer { ChatPerfTrace.end(&replayTrace) }
            let assistantID = KotlinUuid.companion.random()
            let replayStart = clock.now
            for frame in frames {
                try Task.checkCancellation()
                try await clock.sleep(
                    until: replayStart.advanced(by: .milliseconds(frame.elapsedMilliseconds))
                )
                let message = makeAssistantMessage(
                    id: assistantID,
                    text: frame.cumulativeText,
                    finished: false
                )
                if messages.last?.role == MessageRole.assistant {
                    messages[messages.count - 1] = message
                } else {
                    messages.append(message)
                }
                send(.streamDelta)
            }

            if let finalText = frames.last?.cumulativeText {
                messages[messages.count - 1] = makeAssistantMessage(
                    id: assistantID,
                    text: finalText,
                    finished: true
                )
            }
            isGenerationActive = false
            send(.generationCompleted)
        } catch is CancellationError {
            return
        } catch {
            errorDescription = error.localizedDescription
            ChatPerfTrace.event("ReplayLoadFailed")
        }
    }

    func requestBottom() {
        scrollToBottomTrigger &+= 1
    }

    private func send(_ reason: ChatMessageUpdateReason) {
        signal = ChatMessageUpdateSignal(revision: signal.revision + 1, reason: reason)
    }

    private func makeHistory(turns: Int) -> [UIMessage] {
        var result: [UIMessage] = []
        result.reserveCapacity(turns * 2)
        for turn in 0..<turns {
            result.append(makeUserMessage("历史问题 \(turn)：请说明流式渲染的职责边界。"))
            result.append(makeAssistantMessage(
                id: KotlinUuid.companion.random(),
                text: "历史回答 \(turn)：缓冲、解析、布局、滚动与动画各自保持单一所有者。",
                finished: true
            ))
        }
        return result
    }

    private func makeUserMessage(_ text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    private func makeAssistantMessage(
        id: KotlinUuid,
        text: String,
        finished: Bool
    ) -> UIMessage {
        UIMessage(
            id: id,
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: finished ? chatNowLocalDateTime() : nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }
}

struct ChatPerfReplayView: View {
    @StateObject private var model: ChatPerfReplayModel
    private let fixedSettings = IosSettingsDefaults.shared.defaultSeededSettings()
    private let replayDefaults: UserDefaults = {
        let defaults = UserDefaults(suiteName: "app.amber.ios.chat-perf-replay.v1")!
        defaults.set(false, forKey: IOSDisplayPreferenceKeys.microsoftStreamingMarkdown)
        defaults.set(false, forKey: IOSDisplayPreferenceKeys.liyananStreamingMarkdown)
        defaults.set(true, forKey: IOSDisplayPreferenceKeys.streamingBlockMarkdown)
        defaults.set(1.0, forKey: IOSDisplayPreferenceKeys.fontScale)
        defaults.set(IOSChatFont.default.rawValue, forKey: IOSDisplayPreferenceKeys.chatFont)
        return defaults
    }()
    private let workspaceStore = IOSWorkspaceStore(
        baseDirectory: FileManager.default.temporaryDirectory
            .appendingPathComponent("ChatPerfReplay", isDirectory: true)
    )

    init() {
        _model = StateObject(wrappedValue: ChatPerfReplayModel(
            fixtureName: Self.selectedFixtureName()
        ))
    }

    var body: some View {
        ZStack {
            AmberTheme.background.ignoresSafeArea()
            ChatSwiftUIMessageList(
                signal: model.signal,
                configurationIssue: nil,
                isGenerationActive: model.isGenerationActive,
                isLoading: false,
                isRecognizingImages: false,
                contextCompactState: .idle,
                followGeneration: true,
                displaySetting: fixedSettings.displaySetting,
                generativeUiSetting: fixedSettings.agentRuntime.generativeUi,
                reasoningLevelLabel: nil,
                workspaceStore: workspaceStore,
                scrollToBottomTrigger: model.scrollToBottomTrigger,
                scrollToBottomSource: .button,
                messagesProvider: { model.messages },
                variantInfoProvider: { _ in nil },
                onAction: { _ in },
                onViewportStateChange: { model.viewportState = $0 },
                onDismissKeyboard: {}
            )

            if model.viewportState.showScrollToBottom, !model.messages.isEmpty {
                VStack {
                    Spacer()
                    ChatScrollToBottomButton {
                        model.requestBottom()
                    }
                    .padding(.bottom, 18)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .transition(.scale(scale: 0.6).combined(with: .opacity))
                .zIndex(10)
            }

            if let errorDescription = model.errorDescription {
                ContentUnavailableView(
                    "Replay unavailable",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorDescription)
                )
            }
        }
        .defaultAppStorage(replayDefaults)
        .task {
            await model.start()
        }
    }

    private static func selectedFixtureName() -> ChatPerfReplayFixtureName {
        let environmentValue = ProcessInfo.processInfo.environment["AA_CHAT_PERF_FIXTURE"]
        let arguments = ProcessInfo.processInfo.arguments
        let argumentValue: String?
        if let index = arguments.firstIndex(of: "-ChatPerfFixture"),
           arguments.indices.contains(index + 1) {
            argumentValue = arguments[index + 1]
        } else {
            argumentValue = nil
        }
        switch environmentValue ?? argumentValue {
        case "prose", ChatPerfReplayFixtureName.longProse.rawValue:
            return .longProse
        default:
            return .growingTable
        }
    }
}
#endif

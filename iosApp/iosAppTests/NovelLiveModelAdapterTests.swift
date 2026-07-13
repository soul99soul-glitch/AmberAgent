import Foundation
import XCTest
@preconcurrency import Shared
@testable import iosApp

final class NovelLiveModelAdapterTests: XCTestCase {
    func testGlobalAndFixedPoliciesResolveStableProviderAndModelUUIDs() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let adapter = makeAdapter(fixture: fixture)

        let global = try await adapter.resolveModel(for: .global)
        XCTAssertEqual(global.providerID, fixture.provider.id.description())
        XCTAssertEqual(global.ownerProviderID, fixture.provider.id.description())
        XCTAssertEqual(global.modelID, fixture.model.id.description())
        XCTAssertEqual(global.wireModelID, "novel-live")
        XCTAssertEqual(global.contextWindowTokens, 128_000)

        let fixed = try await adapter.resolveModel(for: .fixed(
            providerID: fixture.provider.id.description().uppercased(),
            modelID: fixture.model.id.description().uppercased()
        ))
        XCTAssertEqual(fixed, global)
    }

    func testMissingFixedModelDoesNotFallBackToGlobalModel() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let adapter = makeAdapter(fixture: fixture)

        do {
            _ = try await adapter.resolveModel(for: .fixed(
                providerID: fixture.provider.id.description(),
                modelID: KotlinUuid.companion.random().description()
            ))
            XCTFail("Expected strict fixed-model resolution to fail.")
        } catch let failure as NovelModelFailure {
            XCTAssertEqual(failure.code, "fixed_model_missing")
        }
    }

    func testGlobalAndFixedPoliciesKeepOwnerIdentityWhileDispatchingProviderOverwrite() async throws {
        let overwrite = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Override",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "override-key",
            baseUrl: "https://override.example/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let model = Model(
            modelId: "overwritten-model",
            displayName: "Overwritten",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: KotlinInt(value: 64_000),
            providerOverwrite: overwrite
        )
        let owner = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Owner",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "owner-key",
            baseUrl: "https://owner.example/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let dispatchedProviderIDs = LockedBox<[String]>([])
        let adapter = NovelLiveModelAdapter(
            catalogProvider: {
                NovelLiveModelCatalog(currentModel: model, providers: [owner])
            },
            kmpTransport: { request, callbacks in
                dispatchedProviderIDs.mutate {
                    $0.append(request.providerSetting.id.description())
                }
                callbacks.onComplete()
                return nil
            }
        )

        let global = try await adapter.resolveModel(for: .global)
        XCTAssertEqual(global.providerID, overwrite.id.description())
        XCTAssertEqual(global.ownerProviderID, owner.id.description())
        _ = await Self.collect(try await adapter.start(makeRequest(model: global)))

        let fixed = try await adapter.resolveModel(for: .fixed(
            providerID: owner.id.description(),
            modelID: model.id.description()
        ))
        XCTAssertEqual(fixed, global)
        _ = await Self.collect(try await adapter.start(makeRequest(model: fixed)))

        XCTAssertEqual(dispatchedProviderIDs.value, [
            overwrite.id.description(),
            overwrite.id.description(),
        ])
    }

    func testReservedCustomBodyKeysCannotReplaceNovelContextOrReenableTools() async throws {
        let json = Kotlinx_serialization_jsonJson.companion
        let customBodies = [
            CustomBody(key: "tools", value: json.parseToJsonElement(string: "[]")),
            CustomBody(key: "tool_choice", value: json.parseToJsonElement(string: "\"auto\"")),
            CustomBody(key: "messages", value: json.parseToJsonElement(string: "[]")),
            CustomBody(key: "input", value: json.parseToJsonElement(string: "\"forged\"")),
            CustomBody(key: "system", value: json.parseToJsonElement(string: "\"forged\"")),
            CustomBody(key: "web_search_options", value: json.parseToJsonElement(string: "{}")),
            CustomBody(key: "enable_search", value: json.parseToJsonElement(string: "true")),
            CustomBody(key: "memory_enabled", value: json.parseToJsonElement(string: "true")),
            CustomBody(key: "tool_config", value: json.parseToJsonElement(string: "{}")),
            CustomBody(key: "assistant_context", value: json.parseToJsonElement(string: "\"forged\"")),
            CustomBody(key: "plugins", value: json.parseToJsonElement(string: "[{\"id\":\"web\"}]")),
            CustomBody(key: "web_plugins", value: json.parseToJsonElement(string: "[]")),
            CustomBody(key: "reasoning_effort", value: json.parseToJsonElement(string: "\"high\"")),
        ]
        let model = makeModel(customBodies: customBodies)
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Responses",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "test-key",
            baseUrl: "https://example.test/v1",
            chatCompletionsPath: "/responses",
            useResponseApi: true,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: {
                NovelLiveModelCatalog(currentModel: model, providers: [provider])
            },
            kmpTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            }
        )

        let resolved = try await adapter.resolveModel(for: .global)
        _ = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        let request = try XCTUnwrap(captured.value)
        XCTAssertEqual(request.parameters.customBody.map(\.key), ["reasoning_effort"])
        XCTAssertEqual(request.parameters.model.customBodies.map(\.key), ["reasoning_effort"])
        XCTAssertTrue(request.parameters.tools.isEmpty)
        XCTAssertTrue(request.parameters.model.tools.isEmpty)
        XCTAssertEqual(request.messages.map { $0.toText() }, ["仅使用小说上下文。", "继续写。"])
    }

    func testConfigurationIssueFailsBeforeTransportStarts() async throws {
        let fixture = makeFixture(apiKey: "")
        let calls = LockedBox(0)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in
                calls.mutate { $0 += 1 }
                return nil
            }
        )

        do {
            _ = try await adapter.resolveModel(for: .global)
            XCTFail("Expected configuration validation to fail.")
        } catch let failure as NovelModelFailure {
            XCTAssertEqual(failure.code, "configuration_missing_api_key")
        }
        XCTAssertEqual(calls.value, 0)
    }

    func testKMPDispatchClearsBothToolLayersAndMapsOrderedEvents() async throws {
        let fixture = makeFixture(apiKey: "test-key", reasoning: true)
        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { request, callbacks in
                captured.set(request)
                callbacks.onChunk(Self.deltaChunk("第一段"))
                callbacks.onChunk(Self.usageChunk())
                callbacks.onChunk(Self.replacementChunk("完整正文"))
                callbacks.onComplete()
                callbacks.onComplete()
                callbacks.onChunk(Self.deltaChunk("迟到内容"))
                return NovelLiveCancellationHandle {}
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)
        let stream = try await adapter.start(makeRequest(model: resolved, reasoning: .high))

        let events = await Self.collect(stream)
        XCTAssertEqual(events, [
            .textDelta("第一段"),
            .usage(NovelModelUsage(
                promptTokens: 21,
                completionTokens: 8,
                cachedTokens: 5,
                totalTokens: 29
            )),
            .textReplacement("完整正文"),
            .completed,
        ])

        let request = try XCTUnwrap(captured.value)
        XCTAssertTrue(request.parameters.tools.isEmpty)
        XCTAssertTrue(request.parameters.model.tools.isEmpty)
        XCTAssertNil(request.grokIsolation)
        XCTAssertEqual(request.parameters.reasoningLevel.name.lowercased(), "high")
        XCTAssertEqual(request.messages.map(\.role), [.system, .user])
        XCTAssertEqual(request.messages.map { $0.toText() }, ["仅使用小说上下文。", "继续写。"])
    }

    func testCodexPreparationOrderIsResolveThenAugmentThenDiagnostic() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let order = LockedBox<[String]>([])
        let hooks = NovelLiveCodexHooks(
            isCodex: { _ in true },
            resolve: { provider in
                order.mutate { $0.append("resolve") }
                return provider
            },
            augment: { params, _ in
                order.mutate { $0.append("augment") }
                return params
            },
            diagnose: { _, _, _ in
                order.mutate { $0.append("diagnose") }
            }
        )
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, callbacks in
                order.mutate { $0.append("transport") }
                callbacks.onComplete()
                return nil
            },
            codex: hooks
        )
        let resolved = try await adapter.resolveModel(for: .global)
        let events = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        XCTAssertEqual(events, [.completed])
        XCTAssertEqual(order.value, ["resolve", "augment", "diagnose", "transport"])
    }

    func testClaudeDispatchUsesTheSharedKMPTransport() async throws {
        let model = makeModel(modelID: "claude-novel")
        let provider = ProviderSetting.Claude(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Claude",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "test-key",
            baseUrl: "https://api.anthropic.com/v1",
            promptCaching: false
        )
        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: {
                NovelLiveModelCatalog(currentModel: model, providers: [provider])
            },
            kmpTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            }
        )

        let resolved = try await adapter.resolveModel(for: .global)
        let events = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        XCTAssertEqual(events, [.completed])
        XCTAssertTrue(captured.value?.providerSetting is ProviderSetting.Claude)
        XCTAssertNil(captured.value?.grokIsolation)
    }

    func testGrokDispatchSelectsTheIsolatedNovelTransport() async throws {
        let model = makeModel(modelID: "grok-4.20-fast")
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Grok Web",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "",
            baseUrl: IOSGrokWebConstants.webBaseUrl,
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        let providerID = IOSGrokWebProviderResolver.providerKey(provider)
        guard IOSGrokWebAuthStore.save(
            providerId: providerID,
            cookieHeader: "sso=novel-test-session"
        ) else {
            throw XCTSkip("Simulator keychain is unavailable.")
        }
        defer { IOSGrokWebAuthStore.clear(providerId: providerID) }

        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let kmpCalls = LockedBox(0)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: {
                NovelLiveModelCatalog(currentModel: model, providers: [provider])
            },
            kmpTransport: { _, _ in
                kmpCalls.mutate { $0 += 1 }
                return nil
            },
            grokTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            }
        )

        let resolved = try await adapter.resolveModel(for: .global)
        let events = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        XCTAssertEqual(events, [.completed])
        XCTAssertEqual(kmpCalls.value, 0)
        XCTAssertEqual(captured.value?.grokIsolation, .novel)
        XCTAssertTrue(captured.value?.providerSetting is ProviderSetting.OpenAI)
    }

    func testSynchronousTerminalBeforeHandleInstallationDoesNotTurnStartIntoFailure() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, callbacks in
                callbacks.onComplete()
                return NovelLiveCancellationHandle {}
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let stream = try await adapter.start(makeRequest(model: resolved))

        let events = await Self.collect(stream)
        XCTAssertEqual(events, [.completed])
    }

    func testCancelBeforeStartAndAfterHandleInstallNeverAcceptLateCallbacks() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let transportCalls = LockedBox(0)
        let cancelCalls = LockedBox(0)
        let callbacks = LockedBox<NovelLiveTransportCallbacks?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, value in
                transportCalls.mutate { $0 += 1 }
                callbacks.set(value)
                return NovelLiveCancellationHandle {
                    cancelCalls.mutate { $0 += 1 }
                }
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let preCancelled = makeRequest(model: resolved)
        await adapter.cancel(runID: preCancelled.runID)
        do {
            _ = try await adapter.start(preCancelled)
            XCTFail("Expected pre-start cancellation.")
        } catch let failure as NovelModelFailure {
            XCTAssertEqual(failure.code, "cancelled")
        }
        XCTAssertEqual(transportCalls.value, 0)

        let request = makeRequest(model: resolved)
        let stream = try await adapter.start(request)
        var iterator = stream.makeAsyncIterator()
        await adapter.cancel(runID: request.runID)
        callbacks.value?.onChunk(Self.deltaChunk("late"))
        callbacks.value?.onComplete()

        let event = await iterator.next()
        XCTAssertNil(event)
        XCTAssertEqual(transportCalls.value, 1)
        XCTAssertEqual(cancelCalls.value, 1)
    }

    private struct Fixture: @unchecked Sendable {
        let model: Model
        let provider: ProviderSetting.OpenAI
        let catalog: NovelLiveModelCatalog
    }

    private func makeFixture(apiKey: String, reasoning: Bool = false) -> Fixture {
        let model = makeModel(reasoning: reasoning)
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Novel Test",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: apiKey,
            baseUrl: "https://example.test/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: false,
            authMode: OpenAIAuthMode.apiKey,
            brand: OpenAIBrand.generic
        )
        return Fixture(
            model: model,
            provider: provider,
            catalog: NovelLiveModelCatalog(currentModel: model, providers: [provider])
        )
    }

    private func makeModel(
        modelID: String = "novel-live",
        reasoning: Bool = false,
        customBodies: [CustomBody] = []
    ) -> Model {
        Model(
            modelId: modelID,
            displayName: "Novel Live",
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [CustomHeader(name: "X-Novel", value: "1")],
            customBodies: customBodies,
            inputModalities: [],
            outputModalities: [],
            abilities: reasoning ? [.reasoning] : [],
            tools: Set<BuiltInTools>(),
            contextWindowTokens: KotlinInt(value: 128_000),
            providerOverwrite: nil
        )
    }

    private func makeAdapter(fixture: Fixture) -> NovelLiveModelAdapter {
        NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, callbacks in
                callbacks.onComplete()
                return nil
            }
        )
    }

    private func makeRequest(
        model: NovelResolvedModel,
        reasoning: NovelModelReasoningLevel = .off
    ) -> NovelModelRequest {
        NovelModelRequest(
            runID: NovelRunID(),
            model: model,
            purpose: .prose,
            messages: [
                NovelModelMessage(role: .system, content: "仅使用小说上下文。"),
                NovelModelMessage(role: .user, content: "继续写。"),
            ],
            parameters: NovelModelParameters(
                temperature: 0.8,
                topP: 0.95,
                maxOutputTokens: 4_096,
                reasoningLevel: reasoning
            )
        )
    }

    private static func deltaChunk(_ text: String) -> MessageChunk {
        MessageChunk(
            id: UUID().uuidString,
            model: "novel-live",
            choices: [UIMessageChoice(
                index: 0,
                delta: UIMessage.companion.assistant(prompt: text),
                message: nil,
                finishReason: nil
            )],
            usage: nil
        )
    }

    private static func replacementChunk(_ text: String) -> MessageChunk {
        MessageChunk(
            id: UUID().uuidString,
            model: "novel-live",
            choices: [UIMessageChoice(
                index: 0,
                delta: nil,
                message: UIMessage.companion.assistant(prompt: text),
                finishReason: "stop"
            )],
            usage: nil
        )
    }

    private static func usageChunk() -> MessageChunk {
        MessageChunk(
            id: UUID().uuidString,
            model: "novel-live",
            choices: [],
            usage: TokenUsage(
                promptTokens: 21,
                completionTokens: 8,
                cachedTokens: 5,
                totalTokens: 29
            )
        )
    }

    private static func collect(_ stream: AsyncStream<NovelModelEvent>) async -> [NovelModelEvent] {
        var events: [NovelModelEvent] = []
        for await event in stream {
            events.append(event)
        }
        return events
    }
}

private final class LockedBox<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: Value

    init(_ value: Value) {
        storage = value
    }

    var value: Value {
        lock.lock()
        defer { lock.unlock() }
        return storage
    }

    func set(_ value: Value) {
        lock.lock()
        storage = value
        lock.unlock()
    }

    func mutate(_ body: (inout Value) -> Void) {
        lock.lock()
        body(&storage)
        lock.unlock()
    }
}

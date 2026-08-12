import Foundation
import XCTest
@preconcurrency import Shared
@testable import iosApp

/// 小说讨论写工具在组装点恒声明（与 IOSNovelProjectToolExecutor.supportedToolNames 同源）。
private let novelDiscussionProjectToolNames: [String] = [
    "novel_rename_project",
    "novel_set_polish_preference",
    "novel_upsert_upcoming_arc",
    "novel_clear_upcoming_arc",
    "novel_revise_material",
    "novel_propose_chapter_plan",
    "novel_set_chapter_title",
]

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

    func testReasoningOnlyChunkPublishesReasoningDeltaWithoutManuscriptText() async throws {
        let chunk = Self.reasoningChunk("正在梳理人物关系")
        let foregroundFixture = makeFixture(apiKey: "test-key", reasoning: true)
        let foregroundAdapter = NovelLiveModelAdapter(
            catalogProvider: { foregroundFixture.catalog },
            kmpTransport: { _, callbacks in
                callbacks.onChunk(chunk)
                callbacks.onComplete()
                return nil
            }
        )
        let foregroundModel = try await foregroundAdapter.resolveModel(for: .global)
        let foregroundEvents = await Self.collect(
            try await foregroundAdapter.start(makeRequest(model: foregroundModel))
        )
        XCTAssertEqual(foregroundEvents, [.reasoningDelta("正在梳理人物关系"), .completed])
        XCTAssertFalse(
            foregroundEvents.contains { if case .textDelta = $0 { return true }; return false },
            "Reasoning must not be folded into manuscript text deltas."
        )

        let durableFixture = makeResponsesFixture()
        let cursor = NovelResponsesResumeCursor(responseID: "resp_reasoning", sequenceNumber: 1)
        let durableAdapter = NovelLiveModelAdapter(
            catalogProvider: { durableFixture.catalog },
            kmpTransport: { _, _ in XCTFail("Unexpected foreground transport"); return nil },
            durableStartTransport: { _, callbacks in
                callbacks.onChunk(chunk)
                callbacks.onCheckpoint(cursor)
                callbacks.onComplete()
                return nil
            }
        )
        let durableModel = try await durableAdapter.resolveModel(for: .global)
        let durableEvents = await Self.collect(
            try await durableAdapter.start(makeRequest(model: durableModel))
        )
        XCTAssertEqual(durableEvents, [
            .responseFrame(NovelModelResponseFrame(
                cursor: cursor,
                events: [.reasoningDelta("正在梳理人物关系")]
            )),
            .completed,
        ])
    }

    func testReasoningAndTextInSameChunkEmitBothChannels() async throws {
        let createdAt = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0)
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [
                UIMessagePart.Reasoning(
                    reasoning: "先想人物动机",
                    createdAt: createdAt,
                    finishedAt: nil,
                    metadata: nil
                ),
                UIMessagePart.Text(text: "正文第一句", metadata: nil),
            ],
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026,
                month: 8,
                day: 4,
                hour: 0,
                minute: 0,
                second: 0,
                nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let chunk = MessageChunk(
            id: "mixed",
            model: "novel-live",
            choices: [UIMessageChoice(
                index: 0,
                delta: message,
                message: nil,
                finishReason: nil
            )],
            usage: nil
        )
        let fixture = makeFixture(apiKey: "test-key", reasoning: true)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, callbacks in
                callbacks.onChunk(chunk)
                callbacks.onComplete()
                return nil
            }
        )
        let model = try await adapter.resolveModel(for: .global)
        let events = await Self.collect(try await adapter.start(makeRequest(model: model)))
        XCTAssertEqual(events, [
            .reasoningDelta("先想人物动机"),
            .textDelta("正文第一句"),
            .completed,
        ])
    }

    func testKMPOutputLimitDoesNotBecomeSuccessfulCompletion() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, callbacks in
                callbacks.onChunk(Self.deltaChunk("未写完的回复"))
                callbacks.onChunk(MessageChunk(
                    id: "limited",
                    model: "novel-live",
                    choices: [UIMessageChoice(
                        index: 0,
                        delta: nil,
                        message: nil,
                        finishReason: "length"
                    )],
                    usage: nil
                ))
                callbacks.onComplete()
                return nil
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let events = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        XCTAssertEqual(events, [
            .textDelta("未写完的回复"),
            .failed(NovelModelFailure(
                code: "output_limit_reached",
                message: "模型回复达到输出上限，请重试。",
                isRetryable: true
            )),
        ])
    }

    func testDiscussionRequestAdvertisesAskUserAndEnabledSearchTools() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in
                XCTFail("Discussion search must not use the one-shot KMP transport.")
                return nil
            },
            discussionTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        _ = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .discussion,
            withProjectContext: true
        )))

        let request = try XCTUnwrap(captured.value)
        XCTAssertEqual(
            Set(request.parameters.tools.map(\.name)),
            Set(["ask_user", "search_web", "scrape_web"] + novelDiscussionProjectToolNames)
        )
        XCTAssertEqual(request.parameters.model.tools.count, 1)
    }

    func testDiscussionTransportExecutesSearchAndResumesTheModel() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let provider = ScriptedNovelDiscussionProvider()
        let executor = RecordingNovelSearchExecutor()
        let transport = NovelLiveModelAdapter.discussionSearchTransport(
            using: provider,
            executors: { _ in ["search_web": executor] }
        )
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in
                XCTFail("Discussion search must use the tool transport.")
                return nil
            },
            discussionTransport: transport
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let events = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .discussion
        )))

        XCTAssertEqual(events, [
            .textReplacement("根据检索结果，建议先核对史料再调整人物动机。"),
            .completed,
        ])
        XCTAssertEqual(provider.callCount, 2)
        XCTAssertTrue(provider.secondTurnReceivedToolOutput)
        XCTAssertEqual(executor.calls, ["search_web"])
    }

    /// Multi-step tool loop: the model speaks visible text before invoking a
    /// tool (step 1), then streams its final discussion answer (step 2). The
    /// transport must not let step 2's per-token `replacementChunk`s (a
    /// full-replace signal) erase step 1's text — every emitted replacement
    /// during/after step 2 must still carry step 1's text as a prefix, and the
    /// terminal replacement must join both steps' text with "\n\n".
    func testDiscussionTransportPreservesPreToolTextAcrossStreamingSteps() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let provider = ScriptedStreamingNovelDiscussionProvider()
        let executor = RecordingNovelSearchExecutor()
        let transport = NovelLiveModelAdapter.discussionSearchTransport(
            using: provider,
            executors: { _ in ["search_web": executor] }
        )
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in
                XCTFail("Discussion search must use the tool transport.")
                return nil
            },
            discussionTransport: transport
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let events = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .discussion
        )))

        let replacements: [String] = events.compactMap {
            if case let .textReplacement(text) = $0 { return text }
            return nil
        }
        XCTAssertFalse(replacements.isEmpty)
        for replacement in replacements {
            XCTAssertTrue(
                replacement.hasPrefix(ScriptedStreamingNovelDiscussionProvider.step1Text),
                "step 2 streaming must not drop step 1's pre-tool text, got: \(replacement)"
            )
        }

        let expectedFinal = [
            ScriptedStreamingNovelDiscussionProvider.step1Text,
            ScriptedStreamingNovelDiscussionProvider.step2Text,
        ].joined(separator: "\n\n")
        XCTAssertEqual(replacements.last, expectedFinal)
        XCTAssertEqual(events.last, .completed)
        XCTAssertEqual(executor.calls, ["search_web"])
    }

    func testDiscussionWithoutEnabledSearchStillAdvertisesAskUser() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in
                XCTFail("Discussion Ask User must use the tool transport.")
                return nil
            },
            discussionTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            },
            discussionSearchEnabled: { false }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        _ = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .discussion,
            withProjectContext: true
        )))

        XCTAssertEqual(
            captured.value?.parameters.tools.map(\.name),
            ["ask_user"] + novelDiscussionProjectToolNames
        )
        XCTAssertTrue(captured.value?.parameters.model.tools.isEmpty == true)
    }

    func testQuickStartAdvertisesAskUserWithoutSearchTools() async throws {
        let fixture = makeFixture(apiKey: "test-key")
        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in
                XCTFail("Quick Start Ask User must use the tool transport.")
                return nil
            },
            discussionTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            },
            discussionSearchEnabled: { true }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        _ = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .quickStart
        )))

        XCTAssertEqual(captured.value?.parameters.tools.map(\.name), ["ask_user"])
        XCTAssertTrue(captured.value?.parameters.model.tools.isEmpty == true)
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
            kmpTransport: { _, _ in
                XCTFail("Codex discussion must use the tool transport.")
                return nil
            },
            discussionTransport: { request, callbacks in
                XCTAssertEqual(
                    Set(request.parameters.tools.map(\.name)),
                    Set(["ask_user", "search_web", "scrape_web"] + novelDiscussionProjectToolNames)
                )
                order.mutate { $0.append("transport") }
                callbacks.onComplete()
                return nil
            },
            codex: hooks
        )
        let resolved = try await adapter.resolveModel(for: .global)
        let events = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .discussion,
            withProjectContext: true
        )))

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
            kmpTransport: { _, _ in
                XCTFail("Claude discussion must use the tool transport.")
                return nil
            },
            discussionTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            }
        )

        let resolved = try await adapter.resolveModel(for: .global)
        let events = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .discussion,
            withProjectContext: true
        )))

        XCTAssertEqual(events, [.completed])
        XCTAssertTrue(captured.value?.providerSetting is ProviderSetting.Claude)
        XCTAssertEqual(
            Set(captured.value?.parameters.tools.map(\.name) ?? []),
            Set(["ask_user", "search_web", "scrape_web"] + novelDiscussionProjectToolNames)
        )
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

    func testGrokDiscussionEnablesChatSearchWhileKeepingMemoryIsolated() async throws {
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
            cookieHeader: "sso=novel-discussion-test-session"
        ) else {
            throw XCTSkip("Simulator keychain is unavailable.")
        }
        defer { IOSGrokWebAuthStore.clear(providerId: providerID) }

        let captured = LockedBox<NovelLiveTransportRequest?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: {
                NovelLiveModelCatalog(currentModel: model, providers: [provider])
            },
            kmpTransport: { _, _ in nil },
            grokTransport: { request, callbacks in
                captured.set(request)
                callbacks.onComplete()
                return nil
            }
        )

        let resolved = try await adapter.resolveModel(for: .global)
        _ = await Self.collect(try await adapter.start(makeRequest(
            model: resolved,
            purpose: .discussion
        )))

        XCTAssertEqual(captured.value?.grokIsolation, .discussion)
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

    func testBackgroundResponsesPublishAtomicFramesOnlyAfterCheckpoint() async throws {
        let fixture = makeResponsesFixture()
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in
                XCTFail("Official Responses prose must use the durable transport.")
                return nil
            },
            durableStartTransport: { _, callbacks in
                callbacks.onChunk(Self.deltaChunk("第一段"))
                callbacks.onCheckpoint(NovelResponsesResumeCursor(
                    responseID: "resp_1",
                    sequenceNumber: 1
                ))
                callbacks.onChunk(Self.deltaChunk("第二段"))
                callbacks.onCheckpoint(NovelResponsesResumeCursor(
                    responseID: "resp_1",
                    sequenceNumber: 2
                ))
                callbacks.onComplete()
                return nil
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let events = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        XCTAssertEqual(events, [
            .responseFrame(NovelModelResponseFrame(
                cursor: NovelResponsesResumeCursor(responseID: "resp_1", sequenceNumber: 1),
                events: [.textDelta("第一段")]
            )),
            .responseFrame(NovelModelResponseFrame(
                cursor: NovelResponsesResumeCursor(responseID: "resp_1", sequenceNumber: 2),
                events: [.textDelta("第二段")]
            )),
            .completed,
        ])
    }

    func testBackgroundDisconnectAfterCursorIsResumableNotTerminalFailure() async throws {
        let fixture = makeResponsesFixture()
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in XCTFail("Unexpected foreground transport"); return nil },
            durableStartTransport: { _, callbacks in
                callbacks.onCheckpoint(NovelResponsesResumeCursor(
                    responseID: "resp_1",
                    sequenceNumber: 4
                ))
                callbacks.onDisconnected(NovelModelFailure(
                    code: "provider_background_disconnected",
                    message: "network dropped",
                    isRetryable: true
                ))
                return nil
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let events = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        XCTAssertEqual(events, [
            .responseFrame(NovelModelResponseFrame(
                cursor: NovelResponsesResumeCursor(responseID: "resp_1", sequenceNumber: 4),
                events: []
            )),
            .responseDisconnected(NovelModelFailure(
                code: "provider_background_disconnected",
                message: "network dropped",
                isRetryable: true
            )),
        ])
    }

    func testBackgroundTerminalFailureDoesNotEnterReconnectPath() async throws {
        let fixture = makeResponsesFixture()
        let failure = NovelModelFailure(
            code: "provider_background_failed",
            message: "response.failed",
            isRetryable: false
        )
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in XCTFail("Unexpected foreground transport"); return nil },
            durableStartTransport: { _, callbacks in
                callbacks.onFailure(failure)
                return nil
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let events = await Self.collect(try await adapter.start(makeRequest(model: resolved)))

        XCTAssertEqual(events, [.failed(failure)])
    }

    func testResumeUsesFixedReceiptRouteAndStartsAfterPersistedCursor() async throws {
        let fixture = makeResponsesFixture()
        let captured = LockedBox<(NovelModelResumeRequest, String, [CustomHeader])?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in XCTFail("Resume must not use foreground transport"); return nil },
            durableResumeTransport: { request, provider, headers, callbacks in
                captured.set((request, provider.id.description(), headers))
                callbacks.onChunk(Self.deltaChunk("恢复内容"))
                callbacks.onCheckpoint(NovelResponsesResumeCursor(
                    responseID: request.cursor.responseID,
                    sequenceNumber: request.cursor.sequenceNumber + 1
                ))
                callbacks.onComplete()
                return nil
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)
        let request = NovelModelResumeRequest(
            runID: NovelRunID(),
            model: resolved,
            purpose: .polish,
            cursor: NovelResponsesResumeCursor(responseID: "resp_99", sequenceNumber: 18)
        )

        let events = await Self.collect(try await adapter.resume(request))

        XCTAssertEqual(events, [
            .responseFrame(NovelModelResponseFrame(
                cursor: NovelResponsesResumeCursor(responseID: "resp_99", sequenceNumber: 19),
                events: [.textDelta("恢复内容")]
            )),
            .completed,
        ])
        XCTAssertEqual(captured.value?.0, request)
        XCTAssertEqual(captured.value?.1, fixture.provider.id.description())
        XCTAssertEqual(captured.value?.2.map(\.name), ["X-Novel"])
    }

    func testLateCallbacksFromDetachedAttemptCannotTerminateResumedStream() async throws {
        let fixture = makeResponsesFixture()
        let initialCallbacks = LockedBox<NovelLiveTransportCallbacks?>(nil)
        let resumedCallbacks = LockedBox<NovelLiveTransportCallbacks?>(nil)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in XCTFail("Unexpected foreground transport"); return nil },
            durableStartTransport: { _, callbacks in
                initialCallbacks.set(callbacks)
                return NovelLiveCancellationHandle {}
            },
            durableResumeTransport: { _, _, _, callbacks in
                resumedCallbacks.set(callbacks)
                return NovelLiveCancellationHandle {}
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)
        let request = makeRequest(model: resolved)
        let initialStream = try await adapter.start(request)
        var initialIterator = initialStream.makeAsyncIterator()
        let cursor = NovelResponsesResumeCursor(responseID: "resp_attempt", sequenceNumber: 1)
        initialCallbacks.value?.onCheckpoint(cursor)
        initialCallbacks.value?.onDisconnected(NovelModelFailure(
            code: "provider_background_disconnected",
            message: "network dropped",
            isRetryable: true
        ))
        guard case .responseFrame? = await initialIterator.next(),
              case .responseDisconnected? = await initialIterator.next() else {
            return XCTFail("Expected the first attempt to detach with a cursor")
        }

        let resumedStream = try await adapter.resume(NovelModelResumeRequest(
            runID: request.runID,
            model: resolved,
            purpose: .prose,
            cursor: cursor
        ))
        initialCallbacks.value?.onChunk(Self.deltaChunk("旧流迟到内容"))
        initialCallbacks.value?.onCheckpoint(NovelResponsesResumeCursor(
            responseID: cursor.responseID,
            sequenceNumber: 2
        ))
        initialCallbacks.value?.onComplete()
        await Task.yield()
        await Task.yield()

        resumedCallbacks.value?.onChunk(Self.deltaChunk("恢复内容"))
        resumedCallbacks.value?.onCheckpoint(NovelResponsesResumeCursor(
            responseID: cursor.responseID,
            sequenceNumber: 2
        ))
        resumedCallbacks.value?.onComplete()

        let events = await Self.collect(resumedStream)
        XCTAssertEqual(events, [
            .responseFrame(NovelModelResponseFrame(
                cursor: NovelResponsesResumeCursor(
                    responseID: cursor.responseID,
                    sequenceNumber: 2
                ),
                events: [.textDelta("恢复内容")]
            )),
            .completed,
        ])
    }

    func testDurableResumeFailureCancelsKnownRemoteResponse() async throws {
        let fixture = makeResponsesFixture()
        let callbackBox = LockedBox<NovelLiveTransportCallbacks?>(nil)
        let remoteCancels = LockedBox(0)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in XCTFail("Unexpected foreground transport"); return nil },
            durableResumeTransport: { _, _, _, callbacks in
                callbackBox.set(callbacks)
                return NovelLiveCancellationHandle(
                    {},
                    remoteAction: { remoteCancels.mutate { $0 += 1 } }
                )
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)
        let cursor = NovelResponsesResumeCursor(responseID: "resp_http_failure", sequenceNumber: 7)
        let stream = try await adapter.resume(NovelModelResumeRequest(
            runID: NovelRunID(),
            model: resolved,
            purpose: .prose,
            cursor: cursor
        ))
        let failure = NovelModelFailure(
            code: "provider_background_failed",
            message: "HTTP 429",
            isRetryable: false
        )

        callbackBox.value?.onFailure(failure)

        let events = await Self.collect(stream)
        XCTAssertEqual(events, [.failed(failure)])
        XCTAssertEqual(remoteCancels.value, 1)
    }

    func testCancelWithCursorCallsRemoteCancelButDetachOnlyClosesLocalStream() async throws {
        let fixture = makeResponsesFixture()
        let callbackBox = LockedBox<NovelLiveTransportCallbacks?>(nil)
        let localCancels = LockedBox(0)
        let remoteCancels = LockedBox(0)
        let adapter = NovelLiveModelAdapter(
            catalogProvider: { fixture.catalog },
            kmpTransport: { _, _ in XCTFail("Unexpected foreground transport"); return nil },
            durableStartTransport: { _, callbacks in
                callbackBox.set(callbacks)
                return NovelLiveCancellationHandle(
                    { localCancels.mutate { $0 += 1 } },
                    remoteAction: { remoteCancels.mutate { $0 += 1 } }
                )
            }
        )
        let resolved = try await adapter.resolveModel(for: .global)

        let cancelRequest = makeRequest(model: resolved)
        _ = try await adapter.start(cancelRequest)
        callbackBox.value?.onCheckpoint(NovelResponsesResumeCursor(
            responseID: "resp_cancel",
            sequenceNumber: 3
        ))
        await Task.yield()
        await adapter.cancel(runID: cancelRequest.runID)

        XCTAssertEqual(localCancels.value, 1)
        XCTAssertEqual(remoteCancels.value, 1)

        let detachRequest = makeRequest(model: resolved)
        _ = try await adapter.start(detachRequest)
        await adapter.detach(runID: detachRequest.runID)

        XCTAssertEqual(localCancels.value, 2)
        XCTAssertEqual(remoteCancels.value, 1)

        await adapter.cancel(runID: detachRequest.runID)

        XCTAssertEqual(localCancels.value, 2)
        XCTAssertEqual(
            remoteCancels.value,
            2,
            "An explicit cancel after detaching must still stop the stored server response."
        )
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

    private func makeResponsesFixture() -> Fixture {
        let model = makeModel()
        let provider = ProviderSetting.OpenAI(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "OpenAI Responses",
            models: [model],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: "test-key",
            baseUrl: "https://api.openai.com/v1",
            chatCompletionsPath: "/chat/completions",
            useResponseApi: true,
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
        purpose: NovelModelPurpose = .prose,
        reasoning: NovelModelReasoningLevel = .off,
        withProjectContext: Bool = false
    ) -> NovelModelRequest {
        NovelModelRequest(
            runID: NovelRunID(),
            model: model,
            purpose: purpose,
            messages: [
                NovelModelMessage(role: .system, content: "仅使用小说上下文。"),
                NovelModelMessage(role: .user, content: "继续写。"),
            ],
            parameters: NovelModelParameters(
                temperature: 0.8,
                topP: 0.95,
                maxOutputTokens: 4_096,
                reasoningLevel: reasoning
            ),
            projectID: withProjectContext ? NovelProjectID() : nil,
            branchID: withProjectContext ? NovelBranchID() : nil
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

    private static func reasoningChunk(_ text: String) -> MessageChunk {
        let createdAt = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0)
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Reasoning(
                reasoning: text,
                createdAt: createdAt,
                finishedAt: nil,
                metadata: nil
            )],
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026,
                month: 8,
                day: 4,
                hour: 0,
                minute: 0,
                second: 0,
                nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: UUID().uuidString,
            model: "novel-live",
            choices: [UIMessageChoice(
                index: 0,
                delta: message,
                message: nil,
                finishReason: nil
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

private final class RecordingNovelSearchExecutor: IOSToolExecutor, @unchecked Sendable {
    private let recordedCalls = LockedBox<[String]>([])

    var calls: [String] {
        recordedCalls.value
    }

    func execute(
        name: String,
        arguments: String,
        isUserInitiated: Bool
    ) async -> IOSAgentToolOutcome {
        recordedCalls.mutate { $0.append(name) }
        return .filled(#"{"results":[{"title":"史料","url":"https://example.test"}]}"#)
    }
}

private final class ScriptedNovelDiscussionProvider: IOSAgentTextProvider, @unchecked Sendable {
    private let state = LockedBox((calls: 0, receivedToolOutput: false))

    var callCount: Int {
        state.value.calls
    }

    var secondTurnReceivedToolOutput: Bool {
        state.value.receivedToolOutput
    }

    func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        var currentCall = 0
        state.mutate { state in
            state.calls += 1
            currentCall = state.calls
            if currentCall == 2 {
                state.receivedToolOutput = messages
                .flatMap(\.parts)
                .compactMap { $0 as? UIMessagePart.Tool }
                .contains { !$0.output.isEmpty }
            }
        }

        if currentCall == 1 {
            return chunk(parts: [UIMessagePart.Tool(
                toolCallId: "novel-search-1",
                toolName: "search_web",
                input: #"{"query":"唐代凌烟阁功臣名单"}"#,
                output: [],
                approvalState: ToolApprovalState.Auto.shared,
                streamIndex: nil,
                metadata: nil
            )])
        }
        return chunk(parts: [UIMessagePart.Text(
            text: "根据检索结果，建议先核对史料再调整人物动机。",
            metadata: nil
        )])
    }

    private func chunk(parts: [UIMessagePart]) -> MessageChunk {
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026,
                month: 7,
                day: 14,
                hour: 0,
                minute: 0,
                second: 0,
                nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: UUID().uuidString,
            model: "novel-live",
            choices: [UIMessageChoice(
                index: 0,
                delta: nil,
                message: message,
                finishReason: "stop"
            )],
            usage: nil
        )
    }
}

/// Streaming variant of `ScriptedNovelDiscussionProvider`: step 1 streams
/// visible text before its tool call, step 2 streams its final answer token
/// by token. Used to prove the engine's per-step `onAssistantText` callbacks
/// don't lose earlier steps' text across the tool-loop boundary.
private final class ScriptedStreamingNovelDiscussionProvider: IOSAgentTextProvider, IOSAgentStreamingProvider, @unchecked Sendable {
    static let step1Text = "我先搜索一下相关设定。"
    static let step2Text = "根据检索结果，建议先核对史料再调整人物动机。"

    private let state = LockedBox(0)

    func generateText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        XCTFail("Streaming test double must not fall back to non-streaming generateText.")
        return MessageChunk(id: "unused", model: "novel-live", choices: [], usage: nil)
    }

    func streamText(
        providerSetting: ProviderSetting,
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping @Sendable (MessageChunk) -> Void,
        onComplete: @escaping @Sendable () -> Void,
        onError: @escaping @Sendable (KotlinThrowable) -> Void
    ) -> Kotlinx_coroutines_coreJob? {
        var currentCall = 0
        state.mutate { calls in
            calls += 1
            currentCall = calls
        }

        if currentCall == 1 {
            onChunk(Self.finalChunk(parts: [
                UIMessagePart.Text(text: Self.step1Text, metadata: nil),
                UIMessagePart.Tool(
                    toolCallId: "novel-search-1",
                    toolName: "search_web",
                    input: #"{"query":"唐代凌烟阁功臣名单"}"#,
                    output: [],
                    approvalState: ToolApprovalState.Auto.shared,
                    streamIndex: nil,
                    metadata: nil
                ),
            ]))
        } else {
            onChunk(Self.deltaChunk("根据"))
            onChunk(Self.deltaChunk("检索结果，"))
            onChunk(Self.finalChunk(parts: [
                UIMessagePart.Text(text: Self.step2Text, metadata: nil),
            ]))
        }
        onComplete()
        return nil
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

    private static func finalChunk(parts: [UIMessagePart]) -> MessageChunk {
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: parts,
            annotations: [],
            createdAt: Kotlinx_datetimeLocalDateTime(
                year: 2026,
                month: 7,
                day: 14,
                hour: 0,
                minute: 0,
                second: 0,
                nanosecond: 0
            ),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: UUID().uuidString,
            model: "novel-live",
            choices: [UIMessageChoice(
                index: 0,
                delta: nil,
                message: message,
                finishReason: "stop"
            )],
            usage: nil
        )
    }
}

import XCTest
@preconcurrency import Shared
@testable import iosApp

@MainActor
final class IOSGeminiProviderTests: XCTestCase {

    // MARK: - helpers

    private func makeGoogleProvider(
        authMode: GoogleAuthMode = .apiKey,
        apiKey: String = "AIza-test",
        baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
        vertexAI: Bool = false
    ) -> ProviderSetting.Google {
        ProviderSetting.Google(
            id: KotlinUuid.companion.random(),
            enabled: true,
            name: "Gemini",
            models: [],
            balanceOption: BalanceOption(enabled: false, apiPath: "", resultPath: ""),
            builtIn: false,
            descriptionText: nil,
            shortDescriptionText: nil,
            apiKey: apiKey,
            baseUrl: baseUrl,
            vertexAI: vertexAI,
            useServiceAccount: false,
            privateKey: "",
            serviceAccountEmail: "",
            location: "us-central1",
            projectId: "",
            authMode: authMode
        )
    }

    private func makeModel(
        _ modelId: String = "gemini-3-pro-preview",
        abilities: [ModelAbility] = [ModelAbility.tool]
    ) -> Model {
        Model(
            modelId: modelId,
            displayName: modelId,
            id: KotlinUuid.companion.random(),
            type: ModelType.chat,
            customHeaders: [],
            customBodies: [],
            inputModalities: [],
            outputModalities: [],
            abilities: abilities,
            tools: Set<BuiltInTools>(),
            contextWindowTokens: nil,
            providerOverwrite: nil
        )
    }

    private func makeParams(model: Model, tools: [Tool] = []) -> TextGenerationParams {
        TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: tools,
            reasoningLevel: ReasoningLevel.off,
            customHeaders: [],
            customBody: []
        )
    }

    private func makeTool() -> Tool {
        // KMP factory (direct Swift construction of the suspend-carrying Tool
        // type is not bridged).
        ToolKt.createSearchWebToolDeclaration()
    }

    private func userMessage(_ text: String) -> UIMessage {
        UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.user,
            parts: [UIMessagePart.Text(text: text, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
    }

    // MARK: - parser

    func testParserEmitsIncrementalTextAndFinish() {
        let textFrames = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}"#
        )
        XCTAssertEqual(textFrames, [IOSGeminiStreamFrame(kind: .text("Hello "))])

        // Public streamGenerateContent (and Android) send incremental tokens.
        // The final chunk is the last increment plus finishReason, not the full string.
        let wrapped = IOSGeminiStreamParser.parse(
            #"data: {"response":{"candidates":[{"content":{"parts":[{"text":"world"}]},"finishReason":"STOP"}]}}"#
        )
        XCTAssertEqual(wrapped, [
            IOSGeminiStreamFrame(kind: .text("world")),
            IOSGeminiStreamFrame(kind: .finish("STOP")),
        ])
    }

    func testParserMapsThoughtPartsToReasoningAndKeepsVisibleText() {
        let frames = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"text":"scratch","thought":true},{"text":"answer"}]}}]}"#
        )
        XCTAssertEqual(frames, [
            IOSGeminiStreamFrame(kind: .reasoning("scratch")),
            IOSGeminiStreamFrame(kind: .text("answer")),
        ])
    }

    func testParserTreatsNumericThoughtFlagAsReasoning() {
        let frames = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"text":"scratch","thought":1},{"text":"answer"}]}}]}"#
        )
        XCTAssertEqual(frames, [
            IOSGeminiStreamFrame(kind: .reasoning("scratch")),
            IOSGeminiStreamFrame(kind: .text("answer")),
        ])
    }

    func testParserReadsThoughtStringAsReasoningWhenTextIsMissing() {
        let frames = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"thought":"内部推演"},{"text":"正文"}]}}]}"#
        )
        XCTAssertEqual(frames, [
            IOSGeminiStreamFrame(kind: .reasoning("内部推演")),
            IOSGeminiStreamFrame(kind: .text("正文")),
        ])
    }

    func testParserEmitsMaxTokensFinishWithoutParts() {
        let frames = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"finishReason":"MAX_TOKENS"}]}"#
        )
        XCTAssertEqual(frames, [IOSGeminiStreamFrame(kind: .finish("MAX_TOKENS"))])
    }

    func testThinkingModelWithoutMaxTokensGetsAUsableGeminiOutputFloor() {
        let model = makeModel("gemini-3.7-flash", abilities: [ModelAbility.reasoning])
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.auto_,
            customHeaders: [],
            customBody: []
        )
        let generation = IOSGeminiPayloadBuilder.generationConfig(params: params)
        XCTAssertEqual(generation["maxOutputTokens"] as? Int, 65_536)
        XCTAssertEqual(
            (generation["thinkingConfig"] as? [String: Any])?["includeThoughts"] as? Bool,
            true
        )
    }

    func testExplicitMaxTokensIsNotReplacedByGeminiOutputFloor() {
        let model = makeModel("gemini-3.7-flash", abilities: [ModelAbility.reasoning])
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: KotlinInt(value: 4_096),
            tools: [],
            reasoningLevel: ReasoningLevel.auto_,
            customHeaders: [],
            customBody: []
        )
        let generation = IOSGeminiPayloadBuilder.generationConfig(params: params)
        XCTAssertEqual(generation["maxOutputTokens"] as? Int, 4_096)
    }

    func testTerminalFinishChunkSurfacesGeminiMaxTokens() throws {
        let chunk = try XCTUnwrap(IOSGeminiClient.terminalFinishChunk(
            reason: "MAX_TOKENS",
            model: makeModel("gemini-3.7-flash")
        ))
        XCTAssertEqual(chunk.choices.first?.finishReason, "MAX_TOKENS")
        XCTAssertNil(IOSGeminiClient.terminalFinishChunk(reason: "STOP", model: makeModel()))
    }

    func testParserCapturesThoughtSignatureOnFunctionCallPart() {
        let frames = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_web","args":{"query":"x"}},"thoughtSignature":"sig-1"}]}}]}"#
        )
        XCTAssertEqual(frames, [
            IOSGeminiStreamFrame(kind: .functionCallName(index: 0, name: "search_web")),
            IOSGeminiStreamFrame(kind: .functionCallArgs(index: 0, delta: #"{"query":"x"}"#)),
            IOSGeminiStreamFrame(kind: .functionCallSignature(index: 0, signature: "sig-1")),
        ])
    }

    func testParserHandlesFunctionCallStreamingFragments() {
        let nameFrame = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_web"}}]}}]}"#
        )
        XCTAssertEqual(nameFrame, [IOSGeminiStreamFrame(kind: .functionCallName(index: 0, name: "search_web"))])

        let argsFragment = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"functionCall":{"args":"{\"query\":"}}]}}]}"#
        )
        XCTAssertEqual(argsFragment, [IOSGeminiStreamFrame(kind: .functionCallArgs(index: 0, delta: #"{"query":"#))])
    }

    func testParserHandlesMergedFunctionCallObjectAndFinish() {
        let frames = IOSGeminiStreamParser.parse(
            #"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_web","args":{"query":"x"}}}]},"finishReason":"STOP"}]}"#
        )
        XCTAssertEqual(frames, [
            IOSGeminiStreamFrame(kind: .functionCallName(index: 0, name: "search_web")),
            IOSGeminiStreamFrame(kind: .functionCallArgs(index: 0, delta: #"{"query":"x"}"#)),
            IOSGeminiStreamFrame(kind: .finish("STOP")),
        ])
    }

    func testParserSurfacesStreamErrors() {
        let frames = IOSGeminiStreamParser.parse(
            #"data: {"error":{"message":"quota exceeded"}}"#
        )
        XCTAssertEqual(frames, [IOSGeminiStreamFrame(kind: .error("quota exceeded"))])
    }

    // MARK: - payload builder

    func testPayloadBuilderMapsSystemUserAndToolTurns() {
        let system = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.system,
            parts: [UIMessagePart.Text(text: "Be concise", metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let assistantTool = UIMessagePart.Tool(
            toolCallId: "c1",
            toolName: "search_web",
            input: #"{"query":"amber"}"#,
            output: [UIMessagePart.Text(text: "result-1", metadata: nil)],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let assistant = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [assistantTool],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )

        let instruction = IOSGeminiPayloadBuilder.systemInstruction(from: [system, userMessage("hi")])
        XCTAssertNotNil(instruction)
        XCTAssertEqual((instruction?["parts"] as? [[String: Any]])?.first?["text"] as? String, "Be concise")

        // Pending tool in the assistant turn is dropped from upload history
        // (Android parity: toGooglePart returns null for unexecuted tools).
        let pendingTool = UIMessagePart.Tool(
            toolCallId: "c1",
            toolName: "search_web",
            input: #"{"query":"amber"}"#,
            output: [],
            approvalState: ToolApprovalState.Auto.shared,
            streamIndex: nil,
            metadata: nil
        )
        let assistantCall = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [pendingTool],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let contents = IOSGeminiPayloadBuilder.makeContents([userMessage("hi"), assistantCall])
        XCTAssertEqual(contents.count, 1)

        // Executed tool in the assistant turn → model turn with functionCall
        // followed by a user turn with functionResponse.
        let contents2 = IOSGeminiPayloadBuilder.makeContents([userMessage("hi"), assistant])
        XCTAssertEqual(contents2.count, 3)
        let modelTurn = contents2[1]
        XCTAssertEqual(modelTurn["role"] as? String, "model")
        let modelParts = modelTurn["parts"] as? [[String: Any]]
        let call = modelParts?.first?["functionCall"] as? [String: Any]
        XCTAssertEqual(call?["name"] as? String, "search_web")
        XCTAssertEqual(call?["args"] as? NSDictionary, ["query": "amber"] as NSDictionary)

        let userTurn = contents2[2]
        XCTAssertEqual(userTurn["role"] as? String, "user")
        let userParts = userTurn["parts"] as? [[String: Any]]
        let response = userParts?.first?["functionResponse"] as? [String: Any]
        XCTAssertEqual(response?["name"] as? String, "search_web")
        let inner = response?["response"] as? [String: Any]
        XCTAssertEqual(inner?["result"] as? String, "result-1")

        let signedTool = MessageKt.geminiToolPart(
            toolCallId: "c1",
            toolName: "search_web",
            input: #"{"query":"amber"}"#,
            output: [UIMessagePart.Text(text: "result-1", metadata: nil)],
            streamIndex: nil,
            thoughtSignature: "sig-1"
        )
        let signedAssistant = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [signedTool],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        let signedContents = IOSGeminiPayloadBuilder.makeContents([userMessage("hi"), signedAssistant])
        let signedModelParts = signedContents[1]["parts"] as? [[String: Any]]
        XCTAssertEqual(signedModelParts?.first?["thoughtSignature"] as? String, "sig-1")
    }

    func testToolDeltaWithThoughtSignatureAppendsWithoutCrashingAccumulator() {
        // Device crash 2026-08-21 08:40: SIGSEGV in JsonObject.get →
        // responsesItemId → MessageStreamAccumulator.appendTool when Gemini 3.7
        // Flash streamed a functionCall with thoughtSignature. Swift dictionaries
        // must not be passed as JsonObject metadata.
        let user = userMessage("hi")
        let acc = MessageStreamAccumulator(initialMessages: [user], model: nil)
        let tool = MessageKt.geminiToolPart(
            toolCallId: "gemini-1",
            toolName: "search_web",
            input: #"{"query":"x"}"#,
            output: [],
            streamIndex: KotlinInt(value: 0),
            thoughtSignature: "sig-1"
        )
        let delta = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [tool],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        acc.append(chunk: MessageChunk(
            id: UUID().uuidString,
            model: "gemini-3.7-flash",
            choices: [UIMessageChoice(index: 0, delta: delta, message: nil, finishReason: nil)],
            usage: nil
        ))
        let tools = acc.snapshot().last?.parts.compactMap { $0 as? UIMessagePart.Tool } ?? []
        XCTAssertEqual(tools.count, 1)
        XCTAssertEqual(tools.first?.toolName, "search_web")
        XCTAssertEqual(tools.first?.thoughtSignature(), "sig-1")
        XCTAssertNil(tools.first?.responsesItemId())
    }

    func testPayloadBuilderIncludesFunctionDeclarationsForToolCapableModel() {
        let params = makeParams(model: makeModel(), tools: [makeTool()])
        let body = IOSGeminiPayloadBuilder.makeBody(messages: [userMessage("hi")], params: params)
        let tools = body["tools"] as? [[String: Any]]
        let declarations = tools?.first?["functionDeclarations"] as? [[String: Any]]
        XCTAssertEqual(declarations?.first?["name"] as? String, "search_web")
        XCTAssertTrue((declarations?.first?["description"] as? String)?.contains("Search the web") == true)
        // search_web carries an InputSchema — the KMP mapper must render it as an
        // object schema with properties.
        let parameters = declarations?.first?["parameters"] as? [String: Any]
        XCTAssertEqual(parameters?["type"] as? String, "object")
        XCTAssertNotNil(parameters?["properties"] as? [String: Any])
    }

    func testApiKeyPayloadSendsGemini37ThinkingLevel() {
        let model = makeModel("gemini-3.7-flash", abilities: [ModelAbility.reasoning])
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.low,
            customHeaders: [],
            customBody: []
        )
        let body = IOSGeminiPayloadBuilder.makeBody(messages: [userMessage("hi")], params: params)
        let generation = body["generationConfig"] as? [String: Any]
        let thinking = generation?["thinkingConfig"] as? [String: Any]
        XCTAssertEqual(thinking?["thinkingLevel"] as? String, "low")
        XCTAssertEqual(thinking?["includeThoughts"] as? Bool, true)
    }

    func testAntigravityBodyStillRequestsThoughtSummariesForGemini37() {
        let model = makeModel("gemini-3.7-flash", abilities: [ModelAbility.reasoning])
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.high,
            customHeaders: [],
            customBody: []
        )
        let body = IOSGeminiPayloadBuilder.makeBody(
            messages: [userMessage("hi")],
            params: params,
            includeThinkingConfig: false
        )
        let generation = body["generationConfig"] as? [String: Any] ?? [:]
        let thinking = generation["thinkingConfig"] as? [String: Any]
        // Suffix still encodes the thinking level; includeThoughts is required
        // or 3.7 keeps thoughts internal and the novel bubble never sees them.
        XCTAssertEqual(thinking?["includeThoughts"] as? Bool, true)
        XCTAssertNil(thinking?["thinkingLevel"])
    }

    func testGemini37RequestsThoughtSummariesWithoutCatalogReasoningAbility() {
        let model = makeModel("gemini-3.7-flash", abilities: [])
        let params = TextGenerationParams(
            model: model,
            temperature: nil,
            topP: nil,
            maxTokens: nil,
            tools: [],
            reasoningLevel: ReasoningLevel.auto_,
            customHeaders: [],
            customBody: []
        )
        let thinking = IOSGeminiPayloadBuilder.generationConfig(params: params)["thinkingConfig"] as? [String: Any]
        XCTAssertEqual(thinking?["includeThoughts"] as? Bool, true)
    }

    func testApiKeyCatalogMarksGemini37AsReasoningAndToolCapable() {
        let abilities = IOSGeminiPayloadBuilder.catalogAbilities(for: "gemini-3.7-flash")
        XCTAssertTrue(abilities.contains(ModelAbility.reasoning))
        XCTAssertTrue(abilities.contains(ModelAbility.tool))
    }

    // MARK: - resolver

    func testResolverSupportsApiKeyAndAntigravityOnly() {
        let apiKeyProvider = makeGoogleProvider()
        XCTAssertTrue(IOSGeminiProviderResolver.supportsChat(apiKeyProvider))
        XCTAssertFalse(IOSGeminiProviderResolver.isAntigravityOAuth(apiKeyProvider))

        let oauthProvider = makeGoogleProvider(authMode: .antigravityOauth, apiKey: "", baseUrl: "https://cloudcode-pa.googleapis.com")
        XCTAssertTrue(IOSGeminiProviderResolver.supportsChat(oauthProvider))
        XCTAssertTrue(IOSGeminiProviderResolver.isAntigravityOAuth(oauthProvider))

        let vertex = makeGoogleProvider(vertexAI: true)
        XCTAssertFalse(IOSGeminiProviderResolver.supportsChat(vertex))

        let codeAssist = makeGoogleProvider(authMode: .geminiCodeAssistOauth, apiKey: "")
        XCTAssertFalse(IOSGeminiProviderResolver.supportsChat(codeAssist))
    }

    func testProviderConfigurationGatesGeminiCredentials() {
        let keyed = makeGoogleProvider()
        let model = makeModel()
        XCTAssertTrue(ChatProviderConfiguration.supportsChatStreaming(keyed))
        XCTAssertTrue(ChatProviderConfiguration.hasUsableCredential(keyed))
        XCTAssertEqual(ChatProviderConfiguration.credentialStatusTitle(keyed), "已配置")
        XCTAssertNil(ChatProviderConfiguration.issue(for: model, provider: keyed))

        let oauth = makeGoogleProvider(authMode: .antigravityOauth, apiKey: "", baseUrl: "https://cloudcode-pa.googleapis.com")
        XCTAssertTrue(ChatProviderConfiguration.supportsChatStreaming(oauth))
        XCTAssertFalse(ChatProviderConfiguration.hasUsableCredential(oauth))
        XCTAssertEqual(ChatProviderConfiguration.credentialStatusTitle(oauth), "未填写")
        XCTAssertEqual(ChatProviderConfiguration.issue(for: model, provider: oauth), .geminiNotSignedIn)
    }

    // MARK: - pkce

    func testPKCEVerifierAndChallenge() {
        let verifier = IOSAntigravityPKCE.generateCodeVerifier()
        XCTAssertEqual(verifier.count, 64)
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")
        XCTAssertTrue(verifier.unicodeScalars.allSatisfy { allowed.contains($0) })

        let challenge = IOSAntigravityPKCE.s256Challenge(verifier: verifier)
        XCTAssertFalse(challenge.contains("+"))
        XCTAssertFalse(challenge.contains("/"))
        XCTAssertFalse(challenge.contains("="))
        XCTAssertEqual(challenge.count, 43)
    }

    func testAuthorizationURLCarriesPKCEAndOfflineAccess() {
        let url = IOSAntigravityPKCE.buildAuthorizationURL(state: "st", codeChallenge: "ch")
        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        let values = Dictionary(uniqueKeysWithValues: components.queryItems!.map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(
            values["client_id"],
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        )
        XCTAssertEqual(values["client_id"], IOSAntigravityOAuthConstants.clientId)
        XCTAssertEqual(values["redirect_uri"], IOSAntigravityOAuthConstants.redirectUri)
        XCTAssertEqual(values["state"], "st")
        XCTAssertEqual(values["code_challenge"], "ch")
        XCTAssertEqual(values["code_challenge_method"], "S256")
        XCTAssertEqual(values["access_type"], "offline")
        XCTAssertEqual(values["prompt"], "consent")
    }

    func testLoopbackRequestParsesCallbackAndIgnoresOtherPaths() {
        let request = "GET /oauth/callback?code=abc&state=st HTTP/1.1\r\nHost: localhost:8085\r\n\r\n"
        let url = IOSLoopbackOAuthRequest.callbackURL(from: request)
        XCTAssertEqual(url?.host, "127.0.0.1")
        XCTAssertEqual(url?.path, "/oauth/callback")
        XCTAssertEqual(URLComponents(url: url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == "code" }?.value, "abc")
        XCTAssertNil(IOSLoopbackOAuthRequest.callbackURL(from: "GET /favicon.ico HTTP/1.1\r\n\r\n"))
        XCTAssertNil(IOSLoopbackOAuthRequest.callbackURL(from: "GET /callback?code=abc HTTP/1.1\r\n\r\n"))
        let grokCallback = IOSLoopbackOAuthRequest.callbackURL(
            from: "GET /callback?code=abc HTTP/1.1\r\n\r\n",
            port: 8787,
            pathPrefix: "/callback"
        )
        XCTAssertEqual(grokCallback?.path, "/callback")
    }

    // MARK: - token store round trip

    func testAntigravityTokenStoreRoundTrip() {
        let providerId = "test-\(UUID().uuidString)"
        defer { IOSAntigravityAuthStore.clear(providerId: providerId) }
        XCTAssertNil(IOSAntigravityAuthStore.load(providerId: providerId))

        let tokens = IOSAntigravityAuthTokens(
            accessToken: "at",
            refreshToken: "rt",
            expiresAtMillis: 1_800_000_000_000,
            idToken: nil,
            email: "user@example.com",
            projectId: "ghost-project",
            onboardedTier: "FREE"
        )
        XCTAssertTrue(IOSAntigravityAuthStore.save(providerId: providerId, tokens: tokens))
        XCTAssertEqual(IOSAntigravityAuthStore.load(providerId: providerId), tokens)

        IOSAntigravityAuthStore.clear(providerId: providerId)
        XCTAssertNil(IOSAntigravityAuthStore.load(providerId: providerId))
    }

    // MARK: - streaming client

    func testStreamTextEmitsDeltasAndFlushesToolCallAtEOF() async throws {
        GeminiStreamStubURLProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [GeminiStreamStubURLProtocol.self]
        let session = URLSession(configuration: configuration)

        let provider = makeGoogleProvider()
        let model = makeModel()
        let client = IOSGeminiClient(provider: provider, session: session)
        var texts: [String] = []
        var toolInputs: [String] = []

        try await client.streamText(
            messages: [userMessage("hi")],
            params: makeParams(model: model)
        ) { chunk in
            for choice in chunk.choices {
                if let delta = choice.delta {
                    for part in delta.parts {
                        if let text = part as? UIMessagePart.Text {
                            texts.append(text.text)
                        }
                        if let tool = part as? UIMessagePart.Tool {
                            toolInputs.append(tool.input)
                        }
                    }
                }
            }
        }

        XCTAssertEqual(texts, ["Hello ", "world"])
        XCTAssertEqual(toolInputs, [#"{"query":"amber"}"#])
        XCTAssertTrue(GeminiStreamStubURLProtocol.lastRequestHeaders?["x-goog-api-key"] == "AIza-test")
    }

    func testStreamTextKeepsArgsWhenFunctionCallNameIsRestated() async throws {
        GeminiStreamStubURLProtocol.reset()
        GeminiStreamStubURLProtocol.lines = [
            #"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_web","args":"{\"query\":"}}]}}]}"# + "\n",
            #"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_web","args":"\"amber\"}"}}]}}]}"# + "\n",
        ]
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [GeminiStreamStubURLProtocol.self]
        let session = URLSession(configuration: configuration)
        let client = IOSGeminiClient(provider: makeGoogleProvider(), session: session)
        var toolInputs: [String] = []

        try await client.streamText(
            messages: [userMessage("hi")],
            params: makeParams(model: makeModel())
        ) { chunk in
            for choice in chunk.choices {
                for part in choice.delta?.parts ?? [] {
                    if let tool = part as? UIMessagePart.Tool {
                        toolInputs.append(tool.input)
                    }
                }
            }
        }

        XCTAssertEqual(toolInputs, [#"{"query":"amber"}"#])
    }

    func testCloudCodeAssistWrapperLooksLikeAntigravityAgent() {
        let wrapper = IOSGeminiPayloadBuilder.makeCloudCodeAssistWrapper(
            modelId: "gemini-3.7-flash",
            projectId: "proj-1",
            inner: ["contents": [["role": "user", "parts": [["text": "hi"]]]]]
        )
        XCTAssertEqual(wrapper["model"] as? String, "gemini-3.7-flash")
        XCTAssertEqual(wrapper["project"] as? String, "proj-1")
        XCTAssertEqual(wrapper["userAgent"] as? String, "antigravity")
        XCTAssertEqual(wrapper["requestType"] as? String, "agent")
        XCTAssertTrue((wrapper["requestId"] as? String)?.hasPrefix("agent-") == true)
        let request = wrapper["request"] as? [String: Any]
        XCTAssertNotNil(request?["sessionId"] as? String)
        XCTAssertTrue((request?["sessionId"] as? String)?.hasPrefix("-") == true)
        XCTAssertNotNil(request?["contents"])
        XCTAssertNil(wrapper["user_prompt_id"])
    }

    func testHTTPErrorDetailReadsGoogleStatusAndMessage() {
        let body = Data(#"{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}"#.utf8)
        XCTAssertEqual(
            IOSGeminiClient.httpErrorDetail(body),
            "RESOURCE_EXHAUSTED: Resource has been exhausted (e.g. check quota)."
        )
        XCTAssertEqual(IOSGeminiClient.httpErrorDetail(Data()), "")
    }

    func testParseAvailableModelsKeepsCurrentFlashAndDropsInternalIds() {
        let payload = Data("""
        {"models":{
          "gemini-3.7-flash":{"displayName":"Gemini 3.7 Flash"},
          "gemini-3.1-pro-preview":{"displayName":"Gemini 3.1 Pro"},
          "chat_20706":{"displayName":"internal"},
          "gemini-3.1-flash-image-preview":{"displayName":"image"},
          "gemini-2.5-pro":{"displayName":"retired"}
        }}
        """.utf8)
        let models = IOSGeminiClient.parseAvailableModels(payload)
        XCTAssertEqual(models.map(\.modelId), ["gemini-3.7-flash", "gemini-3.1-pro"])
        XCTAssertEqual(models.first?.displayName, "Gemini 3.7 Flash")
        XCTAssertEqual(models.last?.displayName, "Gemini 3.1 Pro")
    }

    func testParseAvailableModelsCollapsesEffortAndTieredIntoOneModel() {
        let payload = Data("""
        {"models":{
          "gemini-3.7-flash-tiered":{"displayName":"Gemini 3.7 Flash"},
          "gemini-3.6-flash-high":{"displayName":"Gemini 3.6 Flash (High)"},
          "gemini-3.6-flash-tiered":{"displayName":"gemini-3.6-flash-tiered"},
          "gemini-3.5-flash":{"displayName":"Gemini 3.5 Flash (Medium)"},
          "gemini-3.5-flash-lite":{"displayName":"Gemini 3.5 Flash Lite"},
          "gemini-3-flash-agent":{"displayName":"agent"},
          "gemini-flash-latest":{"displayName":"latest"}
        }}
        """.utf8)
        let models = IOSGeminiClient.parseAvailableModels(payload)
        XCTAssertEqual(
            models.map(\.modelId),
            ["gemini-3.7-flash", "gemini-3.6-flash", "gemini-3.5-flash", "gemini-3.5-flash-lite"]
        )
        XCTAssertEqual(models.map(\.displayName), [
            "Gemini 3.7 Flash",
            "Gemini 3.6 Flash",
            "Gemini 3.5 Flash",
            "Gemini 3.5 Flash Lite",
        ])
        XCTAssertTrue(models.first?.abilities.contains(ModelAbility.reasoning) == true)
        XCTAssertEqual(
            IOSAntigravityModelId.wireModelId(
                "gemini-3.7-flash",
                reasoning: ReasoningLevel.auto_,
                variants: IOSAntigravityModelVariants.effortsByBase
            ),
            "gemini-3.7-flash-tiered"
        )
        XCTAssertEqual(
            IOSAntigravityModelId.wireModelId(
                "gemini-3.6-flash",
                reasoning: ReasoningLevel.high,
                variants: IOSAntigravityModelVariants.effortsByBase
            ),
            "gemini-3.6-flash-high"
        )
        XCTAssertEqual(
            IOSAntigravityModelId.wireModelId(
                "gemini-3.7-flash-tiered",
                reasoning: ReasoningLevel.high,
                variants: IOSAntigravityModelVariants.effortsByBase
            ),
            "gemini-3.7-flash-tiered"
        )
    }

    func testWireModelIdDoesNotInventSuffixWhenCatalogUnknown() {
        XCTAssertEqual(
            IOSAntigravityModelId.wireModelId(
                "gemini-3.7-flash",
                reasoning: ReasoningLevel.medium,
                variants: [:]
            ),
            "gemini-3.7-flash"
        )
        XCTAssertEqual(
            IOSAntigravityModelId.wireModelId(
                "gemini-3.7-flash",
                reasoning: ReasoningLevel.auto_,
                variants: [:]
            ),
            "gemini-3.7-flash"
        )
    }

    func testAntigravityVariantsPersistAcrossReplace() {
        IOSAntigravityModelVariants.replace(["gemini-3.7-flash": ["tiered", "high"]])
        XCTAssertEqual(
            IOSAntigravityModelVariants.load()["gemini-3.7-flash"],
            ["tiered", "high"]
        )
        IOSAntigravityModelVariants.replace([:])
    }

    func testFallbackCatalogIncludesGemini37Flash() {
        XCTAssertEqual(IOSGeminiConstants.fallbackModels.first?.modelId, "gemini-3.7-flash")
        XCTAssertTrue(IOSGeminiConstants.fallbackModels.contains { $0.modelId == "gemini-3.7-flash" })
        XCTAssertTrue(IOSAntigravityOAuthConstants.userAgent.hasPrefix("antigravity/1.1.13"))
        XCTAssertFalse(IOSAntigravityOAuthConstants.userAgent.contains("/hub/"))
        XCTAssertEqual(IOSAntigravityOAuthConstants.ideMetadata["ideType"], "ANTIGRAVITY")
        XCTAssertEqual(IOSAntigravityOAuthConstants.ideMetadata["pluginType"], "CLOUD_CODE")
        XCTAssertTrue(IOSAntigravityOAuthConstants.cloudcodePaBaseUrl.contains("daily-cloudcode-pa"))
    }

    func testStreamTextThrowsOnHTTPError() async {
        GeminiStreamStubURLProtocol.reset(statusCode: 403)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [GeminiStreamStubURLProtocol.self]
        let session = URLSession(configuration: configuration)

        let client = IOSGeminiClient(provider: makeGoogleProvider(), session: session)
        do {
            try await client.streamText(
                messages: [userMessage("hi")],
                params: makeParams(model: makeModel()),
                onChunk: { _ in }
            )
            XCTFail("expected httpStatus error")
        } catch let error as IOSGeminiError {
            XCTAssertEqual(error, .httpStatus(403, "wire model: gemini-3-pro-preview"))
        } catch {
            XCTFail("unexpected error \(error)")
        }
    }
}

// MARK: - URLProtocol stub

private final class GeminiStreamStubURLProtocol: URLProtocol {
    nonisolated(unsafe) private static var stubStatusCode = 200
    nonisolated(unsafe) static var lastRequestHeaders: [String: String]?
    nonisolated(unsafe) static var lines: [String]?

    static func reset(statusCode: Int = 200) {
        stubStatusCode = statusCode
        lastRequestHeaders = nil
        lines = nil
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lastRequestHeaders = request.allHTTPHeaderFields
        let response = HTTPURLResponse(
            url: request.url!,
            statusCode: Self.stubStatusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "text/event-stream"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        if Self.stubStatusCode == 200 {
            let lines = (Self.lines ?? [
                #"data: {"candidates":[{"content":{"parts":[{"text":"Hello "}]}}]}"# + "\n",
                #"data: {"candidates":[{"content":{"parts":[{"text":"world"}]}}]}"# + "\n",
                #"data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"search_web","args":{"query":"amber"}}}]}}]}"# + "\n",
            ]).joined()
            client?.urlProtocol(self, didLoad: Data(lines.utf8))
        }
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

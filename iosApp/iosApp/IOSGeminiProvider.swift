import Foundation
@preconcurrency import Shared

/// Native Swift Gemini provider for iOS.
///
/// Two auth routes, both speaking Google's GenerateContent SSE protocol:
///  - **API Key**: `x-goog-api-key` against `{baseUrl}/models/{model}:streamGenerateContent?alt=sse`
///    (Generative Language API).
///  - **Antigravity OAuth**: Bearer against
///    `cloudcode-pa.googleapis.com/v1internal:streamGenerateContent?alt=sse`, with the
///    Antigravity agent wrapper (`userAgent` / `requestType` / `requestId` / `request`).
///    Token resolution + onboarding lives in `IOSAntigravityOAuthClient`.
///
/// Vertex AI + service-account mode is NOT implemented on iOS (JWT signing out of
/// scope); such providers are kept out of `supportsChat` and fail honestly.
enum IOSGeminiConstants {
    static let apiKeyDefaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta"
    static let cloudcodePaBaseUrl = IOSAntigravityOAuthConstants.cloudcodePaBaseUrl

    /// Offline seed when `fetchAvailableModels` is unreachable. Live refresh
    /// uses `/v1internal:fetchAvailableModels` so newer IDs (3.7 flash, …)
    /// show up without another app release.
    static let fallbackModels: [(modelId: String, displayName: String)] = [
        (modelId: "gemini-3.7-flash", displayName: "Gemini 3.7 Flash"),
        (modelId: "gemini-3.5-flash", displayName: "Gemini 3.5 Flash"),
        (modelId: "gemini-3.1-pro", displayName: "Gemini 3.1 Pro"),
        (modelId: "gemini-3-pro", displayName: "Gemini 3 Pro"),
        (modelId: "gemini-3-flash", displayName: "Gemini 3 Flash"),
    ]
}

enum IOSGeminiError: LocalizedError, Equatable {
    case notSignedIn
    case missingAPIKey
    case unsupportedAuthMode(String)
    case unsupportedModel(String)
    case httpStatus(Int, String = "")
    case stream(String)

    var errorDescription: String? {
        switch self {
        case .notSignedIn:
            return "尚未登录 Antigravity，请先在 Gemini 服务商设置里用 Google 账号登录。"
        case .missingAPIKey:
            return "Gemini 服务商还没有 API Key，请先填写。"
        case .unsupportedAuthMode(let mode):
            return "当前 Gemini 认证方式（\(mode)）在 iOS 上尚未支持，请切换为 API Key 或 Antigravity 登录。"
        case .unsupportedModel(let modelId):
            return "\(modelId) 不是可用的 Gemini 模型，请检查模型 ID。"
        case .httpStatus(let status, let detail):
            if status == 401 || status == 403 {
                let suffix = detail.isEmpty ? "" : " \(detail)"
                return "Gemini 凭据未被服务器接受。API Key 模式请检查 Key；Antigravity 模式请重新登录。\(suffix)"
            }
            return detail.isEmpty
                ? "Gemini 请求失败：HTTP \(status)"
                : "Gemini 请求失败：HTTP \(status) \(detail)"
        case .stream(let message):
            return message
        }
    }
}

/// Antigravity's catalog encodes thinking / channel as model-id suffixes
/// (`-high`, `-tiered`, `-preview`). The picker shows one clean base name;
/// the send path puts a real catalog id back.
enum IOSAntigravityModelId {
    /// Longer tokens first so `extra-low` wins over `low`.
    static let variantSuffixes = [
        "extra-low", "low", "medium", "high",
        "tiered", "preview", "latest",
    ]

    static func split(_ modelId: String) -> (base: String, variant: String?) {
        let id = modelId.trimmingCharacters(in: .whitespacesAndNewlines)
        let lower = id.lowercased()
        for suffix in variantSuffixes {
            let token = "-" + suffix
            if lower.hasSuffix(token) {
                return (String(id.dropLast(token.count)), suffix)
            }
        }
        return (id, nil)
    }

    /// `gemini-3.7-flash` → `Gemini 3.7 Flash`. Never keep High / Tiered in the title.
    static func displayName(forBase base: String) -> String {
        let parts = base.split(separator: "-").map(String.init)
        let rest = parts.dropFirst().filter { $0.lowercased() != "gemini" }
        let titled = rest.map { token -> String in
            if token.contains(where: \.isNumber) { return token }
            return token.localizedCapitalized
        }
        let name = titled.joined(separator: " ")
        return name.isEmpty ? "Gemini" : "Gemini \(name)"
    }

    static func wantedVariant(for level: ReasoningLevel) -> String? {
        switch level {
        case .off:
            return nil
        case .low:
            return "low"
        case .medium:
            return "medium"
        case .auto_:
            return "tiered"
        case .high, .xhigh, .max:
            return "high"
        default:
            return "high"
        }
    }

    static func preference(for wanted: String?) -> [String] {
        switch wanted {
        case nil:
            return ["", "low", "extra-low", "tiered", "medium", "high", "preview"]
        case "tiered":
            return ["tiered", "", "high", "medium", "low", "extra-low", "preview"]
        case "high":
            return ["high", "tiered", "medium", "low", "extra-low", "", "preview"]
        case "medium":
            return ["medium", "high", "tiered", "low", "extra-low", "", "preview"]
        case "low":
            return ["low", "extra-low", "medium", "tiered", "high", "", "preview"]
        default:
            return [wanted ?? "", "tiered", "", "high", "medium", "low", "preview"]
        }
    }

    static func wireModelId(
        _ modelId: String,
        reasoning: ReasoningLevel,
        variants: [String: Set<String>]
    ) -> String {
        let (base, _) = split(modelId)
        let available = variants[base] ?? []
        return pick(base: base, wanted: wantedVariant(for: reasoning), available: available)
    }

    static func pick(base: String, wanted: String?, available: Set<String>) -> String {
        func resolve(_ variant: String?) -> String {
            guard let variant, !variant.isEmpty else { return base }
            return "\(base)-\(variant)"
        }
        if available.isEmpty {
            // Never invent `-medium` / `-tiered` without a live catalog.
            // cloudcode-pa 404s GA names *and* guessed suffixes.
            return base
        }
        if let match = preference(for: wanted).first(where: { available.contains($0) }) {
            return resolve(match.isEmpty ? nil : match)
        }
        return resolve(wanted)
    }
}

enum IOSAntigravityModelVariants {
    private static let defaultsKey = "amber.antigravity.modelVariants"

    nonisolated(unsafe) static var effortsByBase: [String: Set<String>] = load()

    static func replace(_ grouped: [String: Set<String>]) {
        effortsByBase = grouped
        let encoded = Dictionary(uniqueKeysWithValues: grouped.map { key, value in
            (key, Array(value))
        })
        UserDefaults.standard.set(encoded, forKey: defaultsKey)
    }

    static func load() -> [String: Set<String>] {
        guard let raw = UserDefaults.standard.dictionary(forKey: defaultsKey) as? [String: [String]] else {
            return [:]
        }
        return Dictionary(uniqueKeysWithValues: raw.map { key, value in
            (key, Set(value))
        })
    }
}

enum IOSGeminiProviderResolver {
    static func providerKey(_ google: ProviderSetting.Google) -> String {
        google.id.description()
    }

    static func isGeminiProvider(_ provider: ProviderSetting) -> Bool {
        provider is ProviderSetting.Google
    }

    static func isAntigravityOAuth(_ google: ProviderSetting.Google) -> Bool {
        google.authMode == GoogleAuthMode.antigravityOauth
    }

    static func isSignedIn(_ provider: ProviderSetting) -> Bool {
        guard let google = provider as? ProviderSetting.Google, isAntigravityOAuth(google) else { return false }
        return IOSAntigravityAuthStore.load(providerId: providerKey(google)) != nil
    }

    /// Whether the iOS chat chain can run this Gemini provider.
    /// API_KEY (non-Vertex) and ANTIGRAVITY_OAUTH qualify; Vertex/service-account
    /// and the Android-only Code Assist OAuth mode do not.
    static func supportsChat(_ provider: ProviderSetting) -> Bool {
        guard let google = provider as? ProviderSetting.Google else { return false }
        if isAntigravityOAuth(google) { return true }
        return google.authMode == GoogleAuthMode.apiKey && !google.vertexAI
    }
}

// MARK: - Payload building

enum IOSGeminiPayloadBuilder {
    /// Maps AmberAgent messages to Gemini `contents` (flat turn list).
    /// Mirrors Android `GoogleProvider.buildContents`:
    ///  - SYSTEM messages → systemInstruction (separate field).
    ///  - USER / TOOL messages → role "user" turns.
    ///  - ASSISTANT messages → role "model" turns; executed tool parts follow
    ///    their function-call group as an extra "user" turn with functionResponse
    ///    parts (Gemini requires the response in a user turn).
    static func systemInstruction(from messages: [UIMessage]) -> [String: Any]? {
        let text = messages
            .filter { $0.role == MessageRole.system }
            .flatMap { $0.parts }
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        return ["parts": [["text": text]]]
    }

    static func makeContents(_ messages: [UIMessage]) -> [[String: Any]] {
        var contents: [[String: Any]] = []
        for message in messages where message.role != MessageRole.system {
            if message.role == MessageRole.assistant {
                appendModelMessage(message, to: &contents)
            } else {
                // USER and TOOL roles both map to Gemini "user" turns.
                let parts = message.parts.compactMap(partToJSON)
                guard !parts.isEmpty else { continue }
                contents.append(["role": "user", "parts": parts])
            }
        }
        return contents
    }

    private static func appendModelMessage(_ message: UIMessage, to contents: inout [[String: Any]]) {
        var buffer: [[String: Any]] = []
        for part in message.parts {
            if let tool = part as? UIMessagePart.Tool, tool.isExecuted {
                // Executed tool in the assistant turn: flush text buffer as a
                // model turn, then emit the call + response as model+user turns
                // (Gemini requires the response in a user turn). Mirrors Android
                // groupPartsByToolBoundary + addModelMessage.
                if !buffer.isEmpty {
                    contents.append(["role": "model", "parts": buffer])
                    buffer = []
                }
                contents.append(["role": "model", "parts": [functionCallPart(tool)]])
                contents.append(["role": "user", "parts": [functionResponsePart(tool)]])
                continue
            }
            if let tool = part as? UIMessagePart.Tool, !tool.isExecuted {
                // Pending (unexecuted) tool call in history: dropped, same as
                // Android's toGooglePart `else -> null` — the pipeline executes
                // pending tools before the next upload.
                continue
            }
            if let json = partToJSON(part) {
                buffer.append(json)
            }
        }
        if !buffer.isEmpty {
            contents.append(["role": "model", "parts": buffer])
        }
    }

    private static func functionCallPart(_ tool: UIMessagePart.Tool) -> [String: Any] {
        let args = parseJSON(tool.input)
        var part: [String: Any] = ["functionCall": ["name": tool.toolName, "args": args]]
        if let signature = tool.thoughtSignature() {
            part["thoughtSignature"] = signature
        }
        return part
    }

    private static func functionResponsePart(_ tool: UIMessagePart.Tool) -> [String: Any] {
        let result = tool.output
            .compactMap { ($0 as? UIMessagePart.Text)?.text }
            .joined(separator: "\n")
        return ["functionResponse": ["name": tool.toolName, "response": ["result": result]]]
    }

    private static func partToJSON(_ part: UIMessagePart) -> [String: Any]? {
        if let text = part as? UIMessagePart.Text {
            return ["text": text.text]
        }
        // Images/audio/video/other modalities are not supported by the iOS Gemini
        // transport yet — dropped silently so text chats keep working.
        return nil
    }

    static func functionDeclarations(tools: [Tool]) -> [[String: Any]] {
        let raw = GeminiWireToolMapper.shared.functionDeclarationsJson(tools: tools)
        guard let data = raw.data(using: .utf8),
              let array = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]] else {
            return []
        }
        return array
    }

    static func generationConfig(
        params: TextGenerationParams,
        includeThinkingConfig: Bool = true
    ) -> [String: Any] {
        var config: [String: Any] = [:]
        if let temperature = params.temperature { config["temperature"] = temperature }
        if let topP = params.topP { config["topP"] = topP }
        if let maxTokens = params.maxTokens { config["maxOutputTokens"] = maxTokens }
        if includeThinkingConfig, params.model.abilities.contains(ModelAbility.reasoning) {
            let thinking = ReasoningWireKt.geminiThinkingConfig(
                modelId: params.model.modelId,
                level: params.reasoningLevel
            )
            var thinkingConfig: [String: Any] = ["includeThoughts": thinking.includeThoughts]
            if let level = thinking.thinkingLevel {
                thinkingConfig["thinkingLevel"] = level
            }
            if let budget = thinking.thinkingBudget {
                thinkingConfig["thinkingBudget"] = budget.intValue
            }
            config["thinkingConfig"] = thinkingConfig
        }
        return config
    }

    static func makeBody(
        messages: [UIMessage],
        params: TextGenerationParams,
        includeThinkingConfig: Bool = true
    ) -> [String: Any] {
        var body: [String: Any] = [:]
        if let system = systemInstruction(from: messages) {
            body["systemInstruction"] = system
        }
        let generation = generationConfig(params: params, includeThinkingConfig: includeThinkingConfig)
        if !generation.isEmpty {
            body["generationConfig"] = generation
        }
        body["contents"] = makeContents(messages)
        if !params.tools.isEmpty, params.model.abilities.contains(ModelAbility.tool) {
            let declarations = functionDeclarations(tools: params.tools)
            if !declarations.isEmpty {
                body["tools"] = [["functionDeclarations": declarations]]
            }
        }
        return body
    }

    /// Antigravity cloudcode-pa wrapper. The Gemini CLI `{user_prompt_id, request}`
    /// shape is accepted by OAuth/onboard but chat is 429 RESOURCE_EXHAUSTED unless
    /// the body looks like official Antigravity agent traffic
    /// (`userAgent` + `requestType` + `requestId` + `request.sessionId`).
    static func makeCloudCodeAssistWrapper(
        modelId: String,
        projectId: String,
        inner: [String: Any]
    ) -> [String: Any] {
        var request = inner
        if request["sessionId"] == nil {
            request["sessionId"] = antigravitySessionID()
        }
        return [
            "model": modelId,
            "project": projectId,
            "userAgent": "antigravity",
            "requestType": "agent",
            "requestId": "agent-\(UUID().uuidString)",
            "request": request,
        ]
    }

    static func antigravitySessionID() -> String {
        "-\(UInt64.random(in: 1_000_000_000_000_000...8_999_999_999_999_999_999))"
    }

    private static func parseJSON(_ raw: String) -> Any {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let data = trimmed.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) else {
            return [String: Any]()
        }
        return object
    }
}

// MARK: - SSE parsing

struct IOSGeminiStreamFrame: Equatable {
    enum Kind: Equatable {
        case text(String)
        case reasoning(String)
        case functionCallName(index: Int, name: String)
        case functionCallArgs(index: Int, delta: String)
        case functionCallSignature(index: Int, signature: String)
        case finish(String?)
        case error(String)
    }

    let kind: Kind
}

enum IOSGeminiStreamParser {
    /// Parses one SSE `data:` line into frames. cloudcode-pa wraps every chunk
    /// as `{"response": {...standard GenerateContentResponse...}}`; the public
    /// generative-language API emits the standard payload at the top level.
    static func parse(_ rawLine: String) -> [IOSGeminiStreamFrame] {
        let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !line.isEmpty else { return [] }
        let dataLine = line.hasPrefix("data:")
            ? String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
            : line
        if dataLine == "[DONE]" {
            return [IOSGeminiStreamFrame(kind: .finish(nil))]
        }
        guard let data = dataLine.data(using: .utf8),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return []
        }
        let object = (root["response"] as? [String: Any]) ?? root

        if let error = object["error"] as? [String: Any] {
            let message = (error["message"] as? String) ?? "Gemini stream error"
            return [IOSGeminiStreamFrame(kind: .error(message))]
        }
        if let blockReason = (object["promptFeedback"] as? [String: Any])?["blockReason"] as? String,
           !blockReason.isEmpty {
            return [IOSGeminiStreamFrame(kind: .error("请求被拒：\(blockReason)"))]
        }
        guard let candidates = object["candidates"] as? [[String: Any]],
              let first = candidates.first else {
            return []
        }
        let finishReason = (first["finishReason"] as? String).nonBlank
        guard let content = first["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else {
            return finishReason.map { [IOSGeminiStreamFrame(kind: .finish($0))] } ?? []
        }
        var frames: [IOSGeminiStreamFrame] = []
        for (index, part) in parts.enumerated() {
            if let text = part["text"] as? String {
                if part["thought"] as? Bool == true {
                    frames.append(IOSGeminiStreamFrame(kind: .reasoning(text)))
                } else {
                    frames.append(IOSGeminiStreamFrame(kind: .text(text)))
                }
                continue
            }
            if let call = part["functionCall"] as? [String: Any] {
                let name = (call["name"] as? String).nonBlank
                // The API streams `args` as JSON text fragments; the final chunk
                // may carry the completed object. Handle both.
                let argsJSON: String
                if let dict = call["args"] as? [String: Any] {
                    argsJSON = dict.isEmpty
                        ? ""
                        : (try? JSONSerialization.data(withJSONObject: dict)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
                } else if let fragment = call["args"] as? String {
                    argsJSON = fragment
                } else {
                    argsJSON = ""
                }
                if let name {
                    frames.append(IOSGeminiStreamFrame(kind: .functionCallName(index: index, name: name)))
                }
                if !argsJSON.isEmpty {
                    frames.append(IOSGeminiStreamFrame(kind: .functionCallArgs(index: index, delta: argsJSON)))
                }
                if let signature = (part["thoughtSignature"] as? String).nonBlank {
                    frames.append(IOSGeminiStreamFrame(kind: .functionCallSignature(index: index, signature: signature)))
                }
            }
        }
        // The final chunk usually carries the last text together with
        // finishReason — emit the content first so no delta is lost.
        if let finishReason {
            frames.append(IOSGeminiStreamFrame(kind: .finish(finishReason)))
        }
        return frames
    }
}

// MARK: - Native client

/// Executes Gemini chat calls. Pure URLSession (no WebView), so it also runs on
/// the background executor. MainActor-isolated like `IOSGrokWebClient` so the
/// chat coordinator can hand it non-Sendable message arrays.
@MainActor
final class IOSGeminiClient {
    let provider: ProviderSetting.Google
    private let session: URLSession

    init(provider: ProviderSetting.Google, session: URLSession = .shared) {
        self.provider = provider
        self.session = session
    }

    // MARK: streaming

    func streamText(
        messages: [UIMessage],
        params: TextGenerationParams,
        onChunk: @escaping (MessageChunk) -> Void
    ) async throws {
        let (request, wireModelId) = try await makeStreamRequest(messages: messages, params: params)
        let (bytes, response) = try await session.bytes(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            var detail = await Self.readHTTPErrorDetail(from: bytes)
            if !wireModelId.isEmpty {
                let suffix = "wire model: \(wireModelId)"
                detail = detail.isEmpty ? suffix : "\(detail) (\(suffix))"
            }
            throw IOSGeminiError.httpStatus(status, detail)
        }

        var pendingCalls: [Int: (name: String, args: String, thoughtSignature: String?)] = [:]

        for try await line in bytes.lines {
            if Task.isCancelled { throw CancellationError() }
            for frame in IOSGeminiStreamParser.parse(line) {
                switch frame.kind {
                case .text(let text):
                    if !text.isEmpty {
                        onChunk(Self.textDeltaChunk(token: text, model: params.model))
                    }
                case .reasoning(let text):
                    if !text.isEmpty {
                        onChunk(Self.reasoningDeltaChunk(text: text, model: params.model))
                    }
                case .functionCallName(let index, let name):
                    let existing = pendingCalls[index]
                    pendingCalls[index] = (
                        name: name,
                        args: existing?.args ?? "",
                        thoughtSignature: existing?.thoughtSignature
                    )
                case .functionCallArgs(let index, let delta):
                    // Fragments arrive as partial JSON text; the final chunk may
                    // carry the completed object. Replace when the delta itself
                    // parses as a full JSON object, append otherwise.
                    let existing = pendingCalls[index]
                    let args = Self.isCompleteJSONObject(delta) ? delta : (existing?.args ?? "") + delta
                    pendingCalls[index] = (
                        name: existing?.name ?? "",
                        args: args,
                        thoughtSignature: existing?.thoughtSignature
                    )
                case .functionCallSignature(let index, let signature):
                    let existing = pendingCalls[index]
                    pendingCalls[index] = (
                        name: existing?.name ?? "",
                        args: existing?.args ?? "",
                        thoughtSignature: signature
                    )
                case .finish:
                    for (index, call) in pendingCalls.sorted(by: { $0.key < $1.key }) {
                        onChunk(Self.toolDeltaChunk(
                            callId: index,
                            name: call.name,
                            args: call.args,
                            thoughtSignature: call.thoughtSignature
                        ))
                    }
                    pendingCalls = [:]
                case .error(let message):
                    throw IOSGeminiError.stream(message)
                }
            }
        }
        // EOF without an explicit finishReason (e.g. server closed after the last
        // chunk): still flush any accumulated functionCall so the pipeline can
        // execute it instead of dropping the turn.
        if !pendingCalls.isEmpty {
            for (index, call) in pendingCalls.sorted(by: { $0.key < $1.key }) {
                onChunk(Self.toolDeltaChunk(
                    callId: index,
                    name: call.name,
                    args: call.args,
                    thoughtSignature: call.thoughtSignature
                ))
            }
        }
    }

    // MARK: non-streaming (engine paths)

    func generateText(
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> MessageChunk {
        var lastText = ""
        var finish: String?
        try await streamText(messages: messages, params: params) { chunk in
            for choice in chunk.choices {
                if let delta = choice.delta {
                    lastText += delta.parts.compactMap { ($0 as? UIMessagePart.Text)?.text }.joined()
                }
            }
            finish = chunk.choices.compactMap(\.finishReason).last
        }
        let message = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: lastText, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: chatNowLocalDateTime(),
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: UUID().uuidString,
            model: params.model.modelId,
            choices: [UIMessageChoice(index: 0, delta: nil, message: message, finishReason: finish)],
            usage: nil
        )
    }

    // MARK: models

    /// API-Key route lists from `GET {baseUrl}/models`. Antigravity lists from
    /// `POST /v1internal:fetchAvailableModels` (the generative-language ListModels
    /// path 403s on this token). If the live catalog is empty, fall back to the
    /// bundled seed so "refresh models" still has something to write.
    func listModelsOrThrow() async throws -> [Model] {
        if IOSGeminiProviderResolver.isAntigravityOAuth(provider) {
            let live = try await fetchAntigravityModels()
            if !live.isEmpty { return live }
            return IOSGeminiConstants.fallbackModels.map { item in
                Self.model(
                    item.modelId,
                    displayName: item.displayName,
                    abilities: [ModelAbility.reasoning, ModelAbility.tool]
                )
            }
        }
        let accessKey = provider.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !accessKey.isEmpty else { throw IOSGeminiError.missingAPIKey }
        let base = provider.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: base + "/models?pageSize=100") else {
            throw IOSGeminiError.stream("Gemini 模型地址无效。")
        }
        var request = URLRequest(url: url)
        request.setValue(accessKey, forHTTPHeaderField: "x-goog-api-key")
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw IOSGeminiError.httpStatus(status, Self.httpErrorDetail(data))
        }
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let models = root["models"] as? [[String: Any]] else {
            throw IOSGeminiError.stream("Gemini 模型响应为空或格式无效。")
        }
        return models.compactMap { entry -> Model? in
            guard let name = (entry["name"] as? String).nonBlank else { return nil }
            let supported = (entry["supportedGenerationMethods"] as? [String]) ?? []
            guard supported.contains("generateContent") else { return nil }
            let modelId = name.hasPrefix("models/") ? String(name.dropFirst("models/".count)) : name
            let displayName = (entry["displayName"] as? String).nonBlank ?? modelId
            return Self.model(modelId, displayName: displayName)
        }
    }

    private func fetchAntigravityModels() async throws -> [Model] {
        let token = try await resolveOAuthSessionToken()
        guard let url = URL(string: IOSGeminiConstants.cloudcodePaBaseUrl + "/v1internal:fetchAvailableModels") else {
            throw IOSGeminiError.stream("Gemini 模型地址无效。")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue(IOSAntigravityOAuthConstants.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue(IOSAntigravityOAuthConstants.clientMetadata, forHTTPHeaderField: "Client-Metadata")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["project": token.projectId])
        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw IOSGeminiError.httpStatus(status, Self.httpErrorDetail(data))
        }
        return Self.parseAvailableModels(data)
    }

    /// Parses `fetchAvailableModels` (`models` is a map keyed by id). Drops
    /// internal / image / agent / alias ids. Thinking and channel suffixes
    /// (`-high`, `-tiered`, `-preview`) collapse to one pretty base name.
    static func parseAvailableModels(_ data: Data) -> [Model] {
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let catalog = root["models"] as? [String: Any] else {
            return []
        }
        var grouped: [String: Set<String>] = [:]
        for rawId in catalog.keys {
            let modelId = rawId.trimmingCharacters(in: .whitespacesAndNewlines)
            guard isChatCatalogModel(modelId) else { continue }
            let (base, variant) = IOSAntigravityModelId.split(modelId)
            guard isChatCatalogModel(base), !isAliasBase(base) else { continue }
            grouped[base, default: []].insert(variant ?? "")
        }
        IOSAntigravityModelVariants.replace(grouped)
        var models: [Model] = grouped.keys.map { base in
            Self.model(
                base,
                displayName: IOSAntigravityModelId.displayName(forBase: base),
                abilities: [ModelAbility.reasoning, ModelAbility.tool]
            )
        }
        models.sort { lhs, rhs in
            let left = catalogSortKey(lhs.modelId)
            let right = catalogSortKey(rhs.modelId)
            if left.major != right.major { return left.major > right.major }
            if left.minor != right.minor { return left.minor > right.minor }
            if left.kind != right.kind { return left.kind < right.kind }
            return lhs.modelId < rhs.modelId
        }
        return models
    }

    static func isChatCatalogModel(_ modelId: String) -> Bool {
        let id = modelId.lowercased()
        if id.hasPrefix("chat_") || id.hasPrefix("tab_") || id.hasPrefix("rev19") { return false }
        if id.contains("image") || id.contains("thinking") || id.contains("-agent") { return false }
        if id.hasPrefix("gemini-2.") { return false }
        return id.hasPrefix("gemini-")
    }

    private static func isAliasBase(_ base: String) -> Bool {
        switch base.lowercased() {
        case "gemini-flash", "gemini-pro", "gemini-flash-lite":
            return true
        default:
            return false
        }
    }

    private static func catalogSortKey(_ modelId: String) -> (major: Int, minor: Int, kind: Int) {
        let id = modelId.lowercased()
        var major = 0
        var minor = 0
        if let match = id.range(of: #"gemini-(\d+)(?:\.(\d+))?"#, options: .regularExpression) {
            let digits = id[match].split(whereSeparator: { !$0.isNumber })
            if let first = digits.first { major = Int(first) ?? 0 }
            if digits.count > 1 { minor = Int(digits[1]) ?? 0 }
        }
        let kind: Int
        if id.contains("flash-lite") {
            kind = 1
        } else if id.contains("flash") {
            kind = 0
        } else if id.contains("pro") {
            kind = 2
        } else {
            kind = 3
        }
        return (major, minor, kind)
    }

    // MARK: request construction

    private func makeStreamRequest(
        messages: [UIMessage],
        params: TextGenerationParams
    ) async throws -> (URLRequest, String) {
        let inner = IOSGeminiPayloadBuilder.makeBody(
            messages: messages,
            params: params,
            includeThinkingConfig: !IOSGeminiProviderResolver.isAntigravityOAuth(provider)
        )
        let url: URL
        var request: URLRequest
        var wireModelId = params.model.modelId

        if IOSGeminiProviderResolver.isAntigravityOAuth(provider) {
            let token = try await resolveOAuthSessionToken()
            guard let target = URL(string: IOSGeminiConstants.cloudcodePaBaseUrl + "/v1internal:streamGenerateContent?alt=sse") else {
                throw IOSGeminiError.stream("Gemini 请求地址无效。")
            }
            let (base, _) = IOSAntigravityModelId.split(params.model.modelId)
            if IOSAntigravityModelVariants.effortsByBase[base] == nil {
                _ = try? await fetchAntigravityModels()
            }
            wireModelId = IOSAntigravityModelId.wireModelId(
                params.model.modelId,
                reasoning: params.reasoningLevel,
                variants: IOSAntigravityModelVariants.effortsByBase
            )
            let wrapper = IOSGeminiPayloadBuilder.makeCloudCodeAssistWrapper(
                modelId: wireModelId,
                projectId: token.projectId,
                inner: inner
            )
            url = target
            request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.setValue(IOSAntigravityOAuthConstants.userAgent, forHTTPHeaderField: "User-Agent")
            request.setValue(IOSAntigravityOAuthConstants.clientMetadata, forHTTPHeaderField: "Client-Metadata")
            request.httpBody = try JSONSerialization.data(withJSONObject: wrapper)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            Self.applyCustomHeaders(params.customHeaders, to: &request)
            request.setValue("Bearer \(token.accessToken)", forHTTPHeaderField: "Authorization")
        } else {
            guard provider.authMode == GoogleAuthMode.apiKey else {
                throw IOSGeminiError.unsupportedAuthMode(String(describing: provider.authMode))
            }
            let accessKey = provider.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !accessKey.isEmpty else { throw IOSGeminiError.missingAPIKey }
            let base = provider.baseUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard let target = URL(string: base + "/models/\(params.model.modelId):streamGenerateContent?alt=sse") else {
                throw IOSGeminiError.stream("Gemini 请求地址无效。")
            }
            url = target
            request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.httpBody = try JSONSerialization.data(withJSONObject: inner)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            Self.applyCustomHeaders(params.customHeaders, to: &request)
            request.setValue(accessKey, forHTTPHeaderField: "x-goog-api-key")
        }
        return (request, wireModelId)
    }

    private static func applyCustomHeaders(_ headers: [CustomHeader], to request: inout URLRequest) {
        for header in headers where !header.name.trimmingCharacters(in: .whitespaces).isEmpty {
            request.setValue(header.value, forHTTPHeaderField: header.name)
        }
    }

    private func resolveOAuthSessionToken() async throws -> (accessToken: String, projectId: String) {
        guard IOSAntigravityAuthStore.load(providerId: IOSGeminiProviderResolver.providerKey(provider)) != nil else {
            throw IOSGeminiError.notSignedIn
        }
        let client = IOSAntigravityOAuthClients.shared(providerId: IOSGeminiProviderResolver.providerKey(provider))
        // Fresh token first; ensureOnboarded is idempotent and returns the cached
        // token record (which may be stale), so use the refreshed value here.
        let accessToken = try await client.getValidAccessToken()
        let onboarded = try await client.ensureOnboarded()
        guard let projectId = onboarded.projectId, !projectId.isEmpty else {
            throw IOSGeminiError.stream("Antigravity 账号尚未完成 Gemini 接入，请稍后重试。")
        }
        return (accessToken, projectId)
    }

    // MARK: chunk helpers

    static func httpErrorDetail(_ body: Data) -> String {
        guard !body.isEmpty else { return "" }
        if let obj = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any] {
            let error = obj["error"] as? [String: Any]
            let statusName = (error?["status"] as? String).nonBlank
            let message = (error?["message"] as? String).nonBlank
                ?? (obj["message"] as? String).nonBlank
            switch (statusName, message) {
            case let (statusName?, message?):
                return "\(statusName): \(message)"
            case let (statusName?, nil):
                return statusName
            case let (nil, message?):
                return message
            default:
                break
            }
        }
        let text = String(data: body, encoding: .utf8) ?? ""
        return String(text.prefix(240))
    }

    private static func readHTTPErrorDetail(from bytes: URLSession.AsyncBytes) async -> String {
        var collected = Data()
        do {
            for try await byte in bytes {
                collected.append(byte)
                if collected.count >= 2048 { break }
            }
        } catch {
            // Status already failed; a truncated body is still enough to surface.
        }
        return httpErrorDetail(collected)
    }

    private static func isCompleteJSONObject(_ raw: String) -> Bool {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{"), trimmed.hasSuffix("}"),
              let data = trimmed.data(using: .utf8),
              (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] != nil else {
            return false
        }
        return true
    }

    private static func model(
        _ modelId: String,
        displayName: String,
        abilities: [ModelAbility] = []
    ) -> Model {
        Model(
            modelId: modelId,
            displayName: displayName,
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

    private static func textDeltaChunk(token: String, model: Model) -> MessageChunk {
        let delta = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Text(text: token, metadata: nil)],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: UUID().uuidString,
            model: model.modelId,
            choices: [UIMessageChoice(index: 0, delta: delta, message: nil, finishReason: nil)],
            usage: nil
        )
    }

    private static func reasoningDeltaChunk(text: String, model: Model) -> MessageChunk {
        let createdAt = KotlinInstant.companion.fromEpochMilliseconds(
            epochMilliseconds: Int64(Date().timeIntervalSince1970 * 1000)
        )
        let delta = UIMessage(
            id: KotlinUuid.companion.random(),
            role: MessageRole.assistant,
            parts: [UIMessagePart.Reasoning(
                reasoning: text,
                createdAt: createdAt,
                finishedAt: nil,
                metadata: nil
            )],
            annotations: [],
            createdAt: chatNowLocalDateTime(),
            finishedAt: nil,
            modelId: nil,
            usage: nil,
            translation: nil
        )
        return MessageChunk(
            id: UUID().uuidString,
            model: model.modelId,
            choices: [UIMessageChoice(index: 0, delta: delta, message: nil, finishReason: nil)],
            usage: nil
        )
    }

    private static func toolDeltaChunk(
        callId: Int,
        name: String,
        args: String,
        thoughtSignature: String?
    ) -> MessageChunk {
        let tool = MessageKt.geminiToolPart(
            toolCallId: "gemini-\(UUID().uuidString)",
            toolName: name,
            input: args,
            output: [],
            streamIndex: KotlinInt(value: Int32(callId)),
            thoughtSignature: thoughtSignature
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
        return MessageChunk(
            id: UUID().uuidString,
            model: "",
            choices: [UIMessageChoice(index: 0, delta: delta, message: nil, finishReason: nil)],
            usage: nil
        )
    }
}

private extension Optional where Wrapped == String {
    var nonBlank: String? {
        guard let self, !self.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
        return self
    }
}

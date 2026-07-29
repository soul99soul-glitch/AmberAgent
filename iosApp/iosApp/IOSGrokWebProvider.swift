import Foundation
import Shared
import WebKit

enum IOSGrokWebConstants {
    static let origin = "https://grok.com"
    static let webBaseUrl = "https://grok.com/rest/app-chat"
    static let conversationUrl = "https://grok.com/rest/app-chat/conversations"

    static let fallbackModels: [(modelId: String, displayName: String)] = [
        (modelId: "grok-4.20-fast", displayName: "Grok 4.20 Fast"),
        (modelId: "grok-4.20-auto", displayName: "Grok 4.20 Auto"),
        (modelId: "grok-4.20-expert", displayName: "Grok 4.20 Expert"),
        (modelId: "grok-4.20-heavy", displayName: "Grok 4.20 Heavy"),
    ]
}

struct IOSGrokWebSession: Codable, Equatable {
    let cookieHeader: String
    let savedAtMillis: Int64
    let providerBackup: IOSGrokWebProviderBackup?
    let isInvalidated: Bool?
}

struct IOSGrokWebProviderBackup: Codable, Equatable {
    let baseUrl: String
    let chatCompletionsPath: String
    let useResponseApi: Bool
}

enum IOSGrokWebCookieValidator {
    static func ssoCookieHeader(from cookies: [HTTPCookie]) -> String? {
        guard let cookie = cookies.first(where: { cookie in
            cookie.name.lowercased() == "sso"
                && isGrokDomain(cookie.domain)
                && !cookie.value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        }) else {
            return nil
        }
        return "sso=\(cookie.value)"
    }

    static func hasSSOCookie(in cookieHeader: String) -> Bool {
        ssoValue(in: cookieHeader) != nil
    }

    static func authenticationCookies(from cookieHeader: String) -> [HTTPCookie] {
        guard let value = ssoValue(in: cookieHeader) else { return [] }
        return ["sso", "sso-rw"].compactMap { name in
            HTTPCookie(properties: [
                .domain: ".grok.com",
                .path: "/",
                .name: name,
                .value: value,
                .secure: "TRUE",
            ])
        }
    }

    private static func ssoValue(in cookieHeader: String) -> String? {
        cookieHeader
            .split(separator: ";")
            .compactMap { component -> String? in
                let pair = component.split(separator: "=", maxSplits: 1)
                guard pair.count == 2,
                      pair[0].trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "sso" else {
                    return nil
                }
                let value = pair[1].trimmingCharacters(in: .whitespacesAndNewlines)
                return value.isEmpty ? nil : value
            }
            .first
    }

    static func isGrokDomain(_ domain: String) -> Bool {
        let normalized = domain.lowercased()
        return normalized == "grok.com" || normalized.hasSuffix(".grok.com")
    }
}

enum IOSGrokWebAuthStore {
    static func credentialKey(providerId: String) -> String { "grokweb.\(providerId).session" }

    static func load(providerId: String) -> IOSGrokWebSession? {
        guard let raw = IOSCredentialSideTable.load(key: credentialKey(providerId: providerId)),
              let data = raw.data(using: .utf8),
              let session = try? JSONDecoder().decode(IOSGrokWebSession.self, from: data) else {
            return nil
        }
        return session
    }

    @discardableResult
    static func save(
        providerId: String,
        cookieHeader: String,
        providerBackup: IOSGrokWebProviderBackup? = nil
    ) -> Bool {
        guard IOSGrokWebCookieValidator.hasSSOCookie(in: cookieHeader) else { return false }
        let session = IOSGrokWebSession(
            cookieHeader: cookieHeader,
            savedAtMillis: Int64(Date().timeIntervalSince1970 * 1000),
            providerBackup: load(providerId: providerId)?.providerBackup ?? providerBackup,
            isInvalidated: false
        )
        return persist(session, providerId: providerId)
    }

    static func invalidate(providerId: String) {
        guard let session = load(providerId: providerId) else { return }
        _ = persist(
            IOSGrokWebSession(
                cookieHeader: session.cookieHeader,
                savedAtMillis: session.savedAtMillis,
                providerBackup: session.providerBackup,
                isInvalidated: true
            ),
            providerId: providerId
        )
    }

    static func clear(providerId: String) {
        IOSCredentialSideTable.delete(key: credentialKey(providerId: providerId))
    }

    private static func persist(_ session: IOSGrokWebSession, providerId: String) -> Bool {
        guard let data = try? JSONEncoder().encode(session),
              let raw = String(data: data, encoding: .utf8) else { return false }
        return IOSCredentialSideTable.store(key: credentialKey(providerId: providerId), value: raw)
    }
}

enum IOSGrokWebProviderResolver {
    static func providerKey(_ openAI: ProviderSetting.OpenAI) -> String {
        openAI.id.description()
    }

    static func isGrokWebProvider(_ provider: ProviderSetting) -> Bool {
        guard let openAI = provider as? ProviderSetting.OpenAI else { return false }
        return isGrokWebConfiguration(openAI)
    }

    static func isSignedIn(_ provider: ProviderSetting) -> Bool {
        guard let openAI = provider as? ProviderSetting.OpenAI else { return false }
        guard let session = IOSGrokWebAuthStore.load(providerId: providerKey(openAI)) else { return false }
        return session.isInvalidated != true
            && IOSGrokWebCookieValidator.hasSSOCookie(in: session.cookieHeader)
    }

    static func isGrokWebConfiguration(_ openAI: ProviderSetting.OpenAI) -> Bool {
        let value = openAI.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: value),
              components.scheme?.lowercased() == "https",
              components.host?.lowercased() == "grok.com" else {
            return false
        }
        let path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased()
        return path == "rest/app-chat" || path == "rest/app-chat/conversations"
    }

    static func isXAIProvider(_ provider: ProviderSetting?) -> Bool {
        guard let openAI = provider as? ProviderSetting.OpenAI else { return false }
        let name = openAI.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if name == "xai" || name == "x.ai" || name.contains("grok") { return true }
        guard let components = URLComponents(string: openAI.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme?.lowercased() == "https",
              let host = components.host?.lowercased() else {
            return false
        }
        return host == "api.x.ai" || host == "grok.com"
    }
}

struct IOSGrokWebStreamFrame: Equatable {
    let token: String?
    let isFinished: Bool
    let errorMessage: String?
}

struct IOSGrokWebWireModel: Equatable {
    let modelName: String
    let modelMode: String
}

struct IOSGrokWebRequestOptions: Equatable, Sendable {
    let disableSearch: Bool
    let disableMemory: Bool

    static let chat = IOSGrokWebRequestOptions(
        disableSearch: false,
        disableMemory: false
    )

    static let novel = IOSGrokWebRequestOptions(
        disableSearch: true,
        disableMemory: true
    )
}

enum IOSGrokWebPayloadBuilder {
    static func makePayload(
        prompt: String,
        wireModel: IOSGrokWebWireModel,
        options: IOSGrokWebRequestOptions = .chat
    ) -> [String: Any] {
        [
            "temporary": true,
            "modelName": wireModel.modelName,
            "message": prompt,
            "fileAttachments": [],
            "imageAttachments": [],
            "disableSearch": options.disableSearch,
            "enableImageGeneration": false,
            "returnImageBytes": false,
            "returnRawGrokInXaiRequest": false,
            "enableImageStreaming": false,
            "imageGenerationCount": 1,
            "forceConcise": false,
            "toolOverrides": [:],
            "enableSideBySide": true,
            "sendFinalMetadata": true,
            "disableTextFollowUps": true,
            "responseMetadata": ["requestModelDetails": ["modelId": wireModel.modelName]],
            "disableMemory": options.disableMemory,
            "forceSideBySide": false,
            "modelMode": wireModel.modelMode,
            "isAsyncChat": false,
            "disableSelfHarmShortCircuit": false,
            "collectionIds": [],
            "disabledConnectorIds": [],
            "deviceEnvInfo": [
                "darkModeEnabled": true,
                "devicePixelRatio": 3,
                "screenWidth": 390,
                "screenHeight": 844,
                "viewportWidth": 390,
                "viewportHeight": 844,
            ],
        ]
    }
}

enum IOSGrokWebModelResolver {
    static func resolve(_ modelId: String) throws -> IOSGrokWebWireModel {
        switch modelId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "grok-4.20", "grok-4.20-auto", "grok-4.20-beta", "grok-4.2":
            IOSGrokWebWireModel(modelName: "grok-420", modelMode: "MODEL_MODE_GROK_420")
        case "grok-4.20-fast":
            IOSGrokWebWireModel(modelName: "grok-420", modelMode: "MODEL_MODE_FAST")
        case "grok-4.20-expert":
            IOSGrokWebWireModel(modelName: "grok-420", modelMode: "MODEL_MODE_EXPERT")
        case "grok-4.20-heavy":
            IOSGrokWebWireModel(modelName: "grok-420", modelMode: "MODEL_MODE_HEAVY")
        case "grok-4.1", "grok-4.1-fast":
            IOSGrokWebWireModel(modelName: "grok-4-1-thinking-1129", modelMode: "MODEL_MODE_FAST")
        case "grok-4.1-expert":
            IOSGrokWebWireModel(modelName: "grok-4-1-thinking-1129", modelMode: "MODEL_MODE_EXPERT")
        case "grok-4.1-thinking":
            IOSGrokWebWireModel(modelName: "grok-4-1-thinking-1129", modelMode: "MODEL_MODE_GROK_4_1_THINKING")
        case "grok-4":
            IOSGrokWebWireModel(modelName: "grok-4", modelMode: "MODEL_MODE_GROK_4")
        case "grok-4-heavy":
            IOSGrokWebWireModel(modelName: "grok-4", modelMode: "MODEL_MODE_HEAVY")
        case "grok-3":
            IOSGrokWebWireModel(modelName: "grok-3", modelMode: "MODEL_MODE_GROK_3")
        default:
            throw IOSGrokWebError.unsupportedModel(modelId)
        }
    }
}

enum IOSGrokWebStreamParser {
    static func parse(_ rawLine: String) -> IOSGrokWebStreamFrame? {
        let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !line.isEmpty else { return nil }
        let dataLine = line.hasPrefix("data:")
            ? String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
            : line
        if dataLine == "[DONE]" {
            return IOSGrokWebStreamFrame(token: nil, isFinished: true, errorMessage: nil)
        }
        guard let data = dataLine.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        if let error = object["error"] as? [String: Any] {
            let message = (error["message"] as? String)
                ?? (error["error"] as? String)
                ?? "Grok Web stream error"
            return IOSGrokWebStreamFrame(token: nil, isFinished: true, errorMessage: message)
        }
        guard let result = object["result"] as? [String: Any],
              let response = result["response"] as? [String: Any] else {
            return nil
        }
        let token = response["isThinking"] as? Bool == true ? nil : response["token"] as? String
        let isFinished = response["finalMetadata"] != nil || response["isSoftStop"] as? Bool == true
        return IOSGrokWebStreamFrame(
            token: token?.isEmpty == false ? token : nil,
            isFinished: isFinished,
            errorMessage: nil
        )
    }
}

enum IOSGrokWebError: LocalizedError {
    case notSignedIn
    case unsupportedModel(String)
    case httpStatus(Int)
    case browser(String)

    var errorDescription: String? {
        switch self {
        case .notSignedIn:
            "尚未登录 Grok，请先在 xAI 服务商设置里登录 grok.com。"
        case .unsupportedModel(let modelId):
            "\(modelId) 是 xAI API 模型或尚未适配的网页模型，不能通过 Grok Web 登录调用。请改用已列出的 Grok Web 模型，或单独配置 xAI API / sub2api。"
        case .httpStatus(let status):
            if status == 401 || status == 403 {
                "Grok Web 登录凭据未被服务器接受，或当前账号没有该网页模式权限。请重新登录后改用已列出的 Grok Web 模型。"
            } else {
                "Grok Web 请求失败：HTTP \(status)"
            }
        case .browser(let message):
            message
        }
    }
}

@MainActor
private final class IOSGrokWebBrowserTransport: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    private static let handlerName = "amberGrokStream"

    private let webView: WKWebView
    private var loadContinuation: CheckedContinuation<Void, Error>?
    private var streamContinuation: CheckedContinuation<Void, Error>?
    private var activeRequestId: String?
    private var onLine: ((String) throws -> Bool)?

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .default()
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        self.webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        configuration.userContentController.add(self, name: Self.handlerName)
        webView.navigationDelegate = self
    }

    func stream(
        payload: [String: Any],
        headers: [String: String],
        cookieHeader: String,
        onLine: @escaping (String) throws -> Bool
    ) async throws {
        do {
            await restoreAuthenticationCookies(from: cookieHeader)
            try await loadOrigin()
        } catch {
            dispose()
            throw error
        }
        defer { dispose() }
        let payloadData = try JSONSerialization.data(withJSONObject: payload)
        let headersData = try JSONSerialization.data(withJSONObject: headers)
        guard let payloadJSON = String(data: payloadData, encoding: .utf8),
              let headersJSON = String(data: headersData, encoding: .utf8) else {
            throw IOSGrokWebError.browser("无法编码 Grok Web 请求。")
        }
        let requestId = UUID().uuidString
        let script = Self.streamScript(
            requestId: requestId,
            payloadJSON: payloadJSON,
            headersJSON: headersJSON
        )

        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                activeRequestId = requestId
                streamContinuation = continuation
                self.onLine = onLine
                webView.evaluateJavaScript(script) { [weak self] _, error in
                    guard let error else { return }
                    self?.finishStream(throwing: IOSGrokWebError.browser(error.localizedDescription))
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in
                self?.cancel()
            }
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let continuation = loadContinuation else { return }
        loadContinuation = nil
        continuation.resume()
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        finishLoading(throwing: error)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        finishLoading(throwing: error)
    }

    // 系统因内存压力等原因终止 WKWebView 内容进程时，进行中的 fetch 不会再 settle，
    // 也不会触发 didFail。若不在这里收口，stream/load 的 continuation 会永久挂起。
    // 用浏览器错误而非 CancellationError：这是真实的传输中断，应作为失败上报，不被当作取消吞掉。
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        let error = IOSGrokWebError.browser("Grok Web 浏览器进程被系统终止。")
        finishLoading(throwing: error)
        finishStream(throwing: error)
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              body["requestId"] as? String == activeRequestId,
              let kind = body["kind"] as? String else {
            return
        }
        switch kind {
        case "response":
            let status = (body["status"] as? NSNumber)?.intValue ?? 0
            if !(200..<300).contains(status) {
                finishStream(throwing: IOSGrokWebError.httpStatus(status))
            }
        case "line":
            guard let line = body["value"] as? String else { return }
            do {
                if try onLine?(line) == true {
                    webView.evaluateJavaScript("window.__amberGrokAbort && window.__amberGrokAbort.abort();")
                    finishStream(throwing: nil)
                }
            } catch {
                finishStream(throwing: error)
            }
        case "complete":
            finishStream(throwing: nil)
        case "error":
            let value = body["value"] as? String ?? "Grok Web 浏览器请求失败。"
            finishStream(throwing: IOSGrokWebError.browser(value))
        default:
            break
        }
    }

    private func loadOrigin() async throws {
        if webView.url?.host?.lowercased() == "grok.com", !webView.isLoading {
            return
        }
        try await withCheckedThrowingContinuation { continuation in
            loadContinuation = continuation
            webView.load(URLRequest(url: URL(string: IOSGrokWebConstants.origin)!))
        }
    }

    private func restoreAuthenticationCookies(from cookieHeader: String) async {
        let store = webView.configuration.websiteDataStore.httpCookieStore
        for cookie in IOSGrokWebCookieValidator.authenticationCookies(from: cookieHeader) {
            await withCheckedContinuation { continuation in
                store.setCookie(cookie) {
                    continuation.resume()
                }
            }
        }
    }

    private func finishLoading(throwing error: Error) {
        guard let continuation = loadContinuation else { return }
        loadContinuation = nil
        continuation.resume(throwing: error)
    }

    private func finishStream(throwing error: Error?) {
        guard let continuation = streamContinuation else { return }
        streamContinuation = nil
        activeRequestId = nil
        onLine = nil
        if let error {
            continuation.resume(throwing: error)
        } else {
            continuation.resume()
        }
    }

    private func cancel() {
        activeRequestId = nil
        webView.evaluateJavaScript("window.__amberGrokAbort && window.__amberGrokAbort.abort();")
        finishStream(throwing: CancellationError())
    }

    private func dispose() {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: Self.handlerName)
        webView.navigationDelegate = nil
        webView.stopLoading()
    }

    private static func streamScript(requestId: String, payloadJSON: String, headersJSON: String) -> String {
        """
        (() => {
          const requestId = "\(requestId)";
          const post = (kind, value) => window.webkit.messageHandlers.\(handlerName).postMessage({
            requestId,
            kind,
            ...value
          });
          const controller = new AbortController();
          if (window.__amberGrokAbort) window.__amberGrokAbort.abort();
          window.__amberGrokAbort = controller;
          fetch("\(IOSGrokWebConstants.conversationUrl)/new", {
            method: "POST",
            credentials: "include",
            headers: \(headersJSON),
            body: JSON.stringify(\(payloadJSON)),
            signal: controller.signal
          }).then(async response => {
            post("response", { status: response.status });
            // 非 2xx 由 native 侧的 "response" 分支按 status 收口；这里只关心"成功但无可读流"。
            // 200 + 空 body 会让 fetch 流立即 done、读不到任何行；若此时静默 return，
            // native 的 stream continuation 永远等不到 complete/error 而永久挂起。故显式 complete。
            if (!response.ok) return;
            if (!response.body) { post("complete", {}); return; }
            const reader = response.body.getReader();
            const decoder = new TextDecoder();
            let pending = "";
            while (true) {
              const result = await reader.read();
              if (result.done) break;
              pending += decoder.decode(result.value, { stream: true });
              const lines = pending.split(/\\r?\\n/);
              pending = lines.pop() || "";
              for (const line of lines) post("line", { value: line });
            }
            pending += decoder.decode();
            if (pending.trim()) post("line", { value: pending });
            post("complete", {});
          }).catch(error => {
            if (error && error.name === "AbortError") return;
            post("error", { value: error?.message || String(error) });
          });
        })();
        """
    }
}

@MainActor
struct IOSGrokWebClient {
    let providerId: String

    @MainActor
    func streamText(
        messages: [UIMessage],
        params: TextGenerationParams,
        options: IOSGrokWebRequestOptions = .chat,
        onChunk: @escaping (MessageChunk) -> Void
    ) async throws {
        try await streamTokens(
            prompt: prompt(from: messages),
            modelId: params.model.modelId,
            options: options
        ) { token in
            onChunk(Self.textDeltaChunk(token: token, model: params.model))
        }
    }

    func generateText(
        prompt: String,
        modelId: String,
        options: IOSGrokWebRequestOptions = .chat
    ) async throws -> String {
        var output = ""
        try await streamTokens(prompt: prompt, modelId: modelId, options: options) { token in
            output += token
        }
        return output
    }

    private func streamTokens(
        prompt: String,
        modelId: String,
        options: IOSGrokWebRequestOptions,
        onToken: @escaping (String) -> Void
    ) async throws {
        guard let auth = IOSGrokWebAuthStore.load(providerId: providerId),
              auth.isInvalidated != true,
              IOSGrokWebCookieValidator.hasSSOCookie(in: auth.cookieHeader) else {
            throw IOSGrokWebError.notSignedIn
        }
        let transport = IOSGrokWebBrowserTransport()
        let wireModel = try IOSGrokWebModelResolver.resolve(modelId)
        do {
            try await transport.stream(
                payload: IOSGrokWebPayloadBuilder.makePayload(
                    prompt: prompt,
                    wireModel: wireModel,
                    options: options
                ),
                headers: headers(),
                cookieHeader: auth.cookieHeader
            ) { rawLine in
                guard let frame = IOSGrokWebStreamParser.parse(rawLine) else { return false }
                if let message = frame.errorMessage {
                    throw IOSGrokWebError.browser(message)
                }
                if let token = frame.token {
                    onToken(token)
                }
                return frame.isFinished
            }
        } catch let error as IOSGrokWebError {
            if case .httpStatus(let status) = error, status == 401 || status == 403 {
                IOSGrokWebAuthStore.invalidate(providerId: providerId)
            }
            throw error
        }
    }

    private func headers() -> [String: String] {
        [
            "Accept": "*/*",
            "Content-Type": "application/json",
            "x-statsig-id": Self.dynamicStatsigId(),
            "x-xai-request-id": UUID().uuidString,
        ]
    }

    private func prompt(from messages: [UIMessage]) -> String {
        messages.compactMap { message in
            let text = message.parts.compactMap { part -> String? in
                if let text = part as? UIMessagePart.Text { return text.text }
                return nil
            }
            .joined(separator: "\n")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            guard !text.isEmpty else { return nil }
            return "[\(String(describing: message.role).lowercased())]\n\(text)"
        }
        .joined(separator: "\n\n")
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

    private static func dynamicStatsigId() -> String {
        let symbols = "abcdefghijklmnopqrstuvwxyz0123456789"
        let random = String((0..<8).map { _ in symbols.randomElement() ?? "a" })
        let message = "e:TypeError: Cannot read properties of undefined (reading '\(random)')"
        return Data(message.utf8).base64EncodedString()
    }
}

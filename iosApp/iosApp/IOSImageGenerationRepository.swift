import Foundation
import Observation

@MainActor
protocol IOSImageGenerationHTTPTransport {
    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data)
}

struct IOSURLSessionImageGenerationHTTPTransport: IOSImageGenerationHTTPTransport {
    var session: URLSession = .shared

    func send(_ request: URLRequest) async throws -> (HTTPURLResponse, Data) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw IOSImageGenerationError.invalidResponse
        }
        return (http, data)
    }
}

@MainActor
@Observable
final class IOSImageGenerationHistoryStore {
    static let shared = IOSImageGenerationHistoryStore()

    private(set) var records: [IOSImageGenerationRecord]

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let key: String
    @ObservationIgnored private let limit = 80

    init(
        userDefaults: UserDefaults = .standard,
        key: String = "app.amber.ios.imageGeneration.history.v1"
    ) {
        self.defaults = userDefaults
        self.key = key
        if let data = userDefaults.data(forKey: key),
           let decoded = try? JSONDecoder().decode([IOSImageGenerationRecord].self, from: data) {
            self.records = decoded
        } else {
            self.records = []
        }
    }

    func insert(_ record: IOSImageGenerationRecord) {
        records.removeAll { $0.id == record.id }
        records.insert(record, at: 0)
        if records.count > limit {
            records = Array(records.prefix(limit))
        }
        persist()
    }

    func delete(id: String, removeFiles: Bool = true) {
        guard let record = records.first(where: { $0.id == id }) else { return }
        records.removeAll { $0.id == id }
        if removeFiles {
            for file in record.files {
                try? FileManager.default.removeItem(atPath: file.path)
            }
        }
        persist()
    }

    func clear(removeFiles: Bool = true) {
        if removeFiles {
            for record in records {
                for file in record.files {
                    try? FileManager.default.removeItem(atPath: file.path)
                }
            }
        }
        records = []
        persist()
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: key)
        }
    }
}

@MainActor
final class IOSImageGenerationRepository {
    static let shared = IOSImageGenerationRepository()
    nonisolated private static let chatImageURLScheme = "amber-image-generation"

    private let historyStore: IOSImageGenerationHistoryStore
    private let fileManager: FileManager

    init(historyStore: IOSImageGenerationHistoryStore = .shared, fileManager: FileManager = .default) {
        self.historyStore = historyStore
        self.fileManager = fileManager
    }

    func generate(
        request: IOSImageGenerationRequest,
        apiKey: String,
        baseURL: String,
        transport: any IOSImageGenerationHTTPTransport = IOSURLSessionImageGenerationHTTPTransport()
    ) async throws -> IOSImageGenerationRecord {
        let trimmedPrompt = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedPrompt.isEmpty else { throw IOSImageGenerationError.missingPrompt }
        let model = request.model.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !model.isEmpty else { throw IOSImageGenerationError.missingModel }
        let resolvedApiKey = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !resolvedApiKey.isEmpty else { throw IOSImageGenerationError.missingAPIKey }
        let endpoint = try imageEndpoint(from: baseURL)

        var urlRequest = URLRequest(url: endpoint)
        urlRequest.httpMethod = "POST"
        urlRequest.timeoutInterval = 120
        urlRequest.setValue("Bearer \(resolvedApiKey)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Accept")
        urlRequest.httpBody = try JSONSerialization.data(
            withJSONObject: [
                "model": model,
                "prompt": request.effectivePrompt,
                "n": max(1, min(request.count, 4)),
                "size": request.aspectRatio.apiSize
            ],
            options: [.sortedKeys]
        )

        let (response, data) = try await transport.send(urlRequest)
        guard (200...299).contains(response.statusCode) else {
            throw IOSImageGenerationError.httpStatus(response.statusCode, responseBodyPreview(data))
        }

        let payload = try imageItems(from: data)
        guard !payload.isEmpty else { throw IOSImageGenerationError.noImages(nil) }

        let recordId = UUID().uuidString
        let directory = try imageDirectory()
        var files: [IOSGeneratedImageFile] = []
        for (index, item) in payload.enumerated() {
            let data = try await imageData(from: item, transport: transport)
            let ext = fileExtension(for: item.mimeType)
            let fileURL = directory.appendingPathComponent("\(recordId)_\(index).\(ext)", isDirectory: false)
            try data.write(to: fileURL, options: [.atomic])
            files.append(IOSGeneratedImageFile(id: UUID().uuidString, path: fileURL.path, mimeType: item.mimeType))
        }

        let record = IOSImageGenerationRecord(
            id: recordId,
            prompt: trimmedPrompt,
            model: model,
            aspectRatio: request.aspectRatio,
            count: files.count,
            style: request.style,
            source: request.source,
            files: files,
            createdAt: Self.nowMillis()
        )
        historyStore.insert(record)
        return record
    }

    func toolRequest(from input: String, modelId: String) throws -> IOSImageGenerationRequest {
        let object = jsonObject(input)
        let prompt = (object["prompt"] as? String) ?? input
        let aspect = IOSImageAspectRatio(toolValue: object["aspect_ratio"] as? String)
        let count = max(1, min((object["count"] as? Int) ?? intValue(object["count"]) ?? 1, 4))
        let style = (object["style"] as? String) ?? ""
        let sourceImageURL = try normalizedSourceImageURL(
            (object["source_image_url"] as? String) ?? (object["image_url"] as? String)
        )
        return IOSImageGenerationRequest(
            prompt: prompt,
            model: modelId,
            aspectRatio: aspect,
            count: count,
            style: style,
            source: "chat",
            sourceImageURL: sourceImageURL
        )
    }

    func toolResultJSON(record: IOSImageGenerationRecord) -> String {
        let payload: [String: Any] = [
            "status": "ok",
            "source": "generate_image",
            "model": record.model,
            "prompt": record.prompt,
            "aspect_ratio": record.aspectRatio.title,
            "count": record.files.count,
            "files": record.files.map { ["url": Self.chatImageURLString(filePath: $0.path), "mime_type": $0.mimeType] }
        ]
        return jsonString(payload)
    }

    nonisolated static func chatImageURLString(filePath: String) -> String {
        let fileName = URL(fileURLWithPath: filePath).lastPathComponent
        guard !fileName.isEmpty else {
            return URL(fileURLWithPath: filePath).absoluteString
        }
        var components = URLComponents()
        components.scheme = chatImageURLScheme
        components.host = "image-generation"
        components.path = "/" + fileName
        return components.url?.absoluteString ?? URL(fileURLWithPath: filePath).absoluteString
    }

    nonisolated static func resolvedImageURL(from raw: String) -> URL? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !trimmed.hasPrefix("data:") else { return nil }

        if let url = URL(string: trimmed),
           let scheme = url.scheme?.lowercased() {
            switch scheme {
            case chatImageURLScheme:
                return generatedImageDirectoryURL().appendingPathComponent(url.lastPathComponent)
            case "file":
                if FileManager.default.fileExists(atPath: url.path) {
                    return url
                }
                if let repaired = repairedGeneratedImageURL(from: url),
                   FileManager.default.fileExists(atPath: repaired.path) {
                    return repaired
                }
                return url
            case "http", "https":
                return url
            default:
                return url
            }
        }

        let fileURL = URL(fileURLWithPath: trimmed)
        if FileManager.default.fileExists(atPath: fileURL.path) {
            return fileURL
        }
        if let repaired = repairedGeneratedImageURL(from: fileURL),
           FileManager.default.fileExists(atPath: repaired.path) {
            return repaired
        }
        return fileURL
    }

    nonisolated static func imageData(from raw: String) throws -> Data {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("data:") {
            guard let comma = trimmed.firstIndex(of: ","),
                  let data = Data(base64Encoded: String(trimmed[trimmed.index(after: comma)...])) else {
                throw IOSImageGenerationError.invalidImageData
            }
            return data
        }
        guard let url = resolvedImageURL(from: trimmed) else {
            throw IOSImageGenerationError.invalidImageData
        }
        return try Data(contentsOf: url)
    }

    /// Codex (ChatGPT OAuth) image generation. The codex backend has no
    /// `/images/generations` endpoint — generation runs through the Responses API
    /// `image_generation` tool (mirrors Android). Resolves the OAuth bearer, POSTs
    /// `/responses` with the tool attached, and decodes the `image_generation_call`
    /// base64 result.
    func generateViaCodex(
        request: IOSImageGenerationRequest,
        providerId: String,
        transport: any IOSImageGenerationHTTPTransport = IOSURLSessionImageGenerationHTTPTransport()
    ) async throws -> IOSImageGenerationRecord {
        let trimmedPrompt = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedPrompt.isEmpty else { throw IOSImageGenerationError.missingPrompt }

        let client = IOSCodexOAuthClient(providerId: providerId)
        let targetCount = max(1, min(request.count, 4))
        var base64Images: [String] = []
        var noImageReasons: [String] = []
        for _ in 0..<targetCount {
            do {
                let image = try await generateOneCodexImage(
                    request: request,
                    providerId: providerId,
                    client: client,
                    transport: transport
                )
                base64Images.append(image)
            } catch IOSImageGenerationError.noImages(let reason) {
                if let reason, !reason.isEmpty {
                    noImageReasons.append(reason)
                }
            }
        }
        guard !base64Images.isEmpty else {
            throw IOSImageGenerationError.noImages(noImageReasons.first)
        }

        let recordId = UUID().uuidString
        let directory = try imageDirectory()
        var files: [IOSGeneratedImageFile] = []
        for (index, base64) in base64Images.enumerated() {
            guard let imageData = Data(base64Encoded: Self.normalizedBase64(base64)) else { continue }
            let fileURL = directory.appendingPathComponent("\(recordId)_\(index).png", isDirectory: false)
            try imageData.write(to: fileURL, options: [.atomic])
            files.append(IOSGeneratedImageFile(id: UUID().uuidString, path: fileURL.path, mimeType: "image/png"))
        }
        guard !files.isEmpty else { throw IOSImageGenerationError.invalidImageData }

        let record = IOSImageGenerationRecord(
            id: recordId,
            prompt: trimmedPrompt,
            model: IOSCodexOAuthConstants.imageModelId,
            aspectRatio: request.aspectRatio,
            count: files.count,
            style: request.style,
            source: request.source,
            files: files,
            createdAt: Self.nowMillis()
        )
        historyStore.insert(record)
        return record
    }

    private func generateOneCodexImage(
        request: IOSImageGenerationRequest,
        providerId: String,
        client: IOSCodexOAuthClient,
        transport: any IOSImageGenerationHTTPTransport
    ) async throws -> String {
        do {
            let token = try await client.getValidAccessToken()
            let accountId = IOSCodexAuthStore.load(providerId: providerId)?.accountId
            return try await sendCodexImageRequest(
                request: request,
                token: token,
                accountId: accountId,
                transport: transport
            )
        } catch IOSImageGenerationError.httpStatus(let status, _) where status == 401 {
            let token = try await client.getValidAccessToken(forceRefresh: true)
            let accountId = IOSCodexAuthStore.load(providerId: providerId)?.accountId
            return try await sendCodexImageRequest(
                request: request,
                token: token,
                accountId: accountId,
                transport: transport
            )
        }
    }

    private func sendCodexImageRequest(
        request: IOSImageGenerationRequest,
        token: String,
        accountId: String?,
        transport: any IOSImageGenerationHTTPTransport
    ) async throws -> String {
        guard let url = URL(string: IOSCodexOAuthConstants.codexBackendBaseUrl + "/responses") else {
            throw IOSImageGenerationError.invalidBaseURL
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.timeoutInterval = 180
        urlRequest.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        urlRequest.setValue("responses=experimental", forHTTPHeaderField: "OpenAI-Beta")
        urlRequest.setValue(IOSCodexOAuthConstants.originator, forHTTPHeaderField: "originator")
        if let accountId, !accountId.isEmpty {
            urlRequest.setValue(accountId, forHTTPHeaderField: "ChatGPT-Account-Id")
        }
        urlRequest.httpBody = try JSONSerialization.data(
            withJSONObject: Self.codexResponsesRequestBody(for: request),
            options: [.sortedKeys]
        )

        let (response, data) = try await transport.send(urlRequest)
        guard (200...299).contains(response.statusCode) else {
            throw IOSImageGenerationError.httpStatus(response.statusCode, responseBodyPreview(data))
        }
        let extraction = Self.codexImageExtraction(from: data)
        if let image = extraction.images.first {
            return image
        }
        throw IOSImageGenerationError.noImages(extraction.failureReason ?? responseBodyPreview(data))
    }

    static func codexResponsesRequestBody(for request: IOSImageGenerationRequest) -> [String: Any] {
        [
            "model": IOSCodexOAuthConstants.imageRoutingModel,
            "instructions": "",
            "store": false,
            "stream": true,
            "tool_choice": "required",
            "tools": [[
                "type": "image_generation",
                "size": request.aspectRatio.apiSize,
                "quality": "high",
                "moderation": "low",
            ]],
            "input": [[
                "role": "user",
                "content": codexInputContent(for: request),
            ]],
        ]
    }

    private static func codexInputContent(for request: IOSImageGenerationRequest) -> [[String: Any]] {
        var content: [[String: Any]] = [[
            "type": "input_text",
            "text": request.effectivePrompt,
        ]]
        if let sourceImageURL = request.sourceImageURL?.trimmingCharacters(in: .whitespacesAndNewlines),
           !sourceImageURL.isEmpty {
            content.append([
                "type": "input_image",
                "image_url": sourceImageURL,
            ])
        }
        return content
    }

    struct CodexImageExtraction: Equatable {
        var images: [String]
        var failureReason: String?
    }

    static func codexImageExtraction(from data: Data) -> CodexImageExtraction {
        var results: [String] = []
        var reasons: [String] = []
        var eventTypes: [String] = []

        if let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            results.append(contentsOf: codexImageResults(fromJSONObject: root))
            reasons.append(contentsOf: codexImageFailureReasons(fromJSONObject: root))
            if let type = root["type"] as? String, !type.isEmpty {
                eventTypes.append(type)
            }
        }

        if let text = String(data: data, encoding: .utf8) {
            for object in sseJSONObjects(from: text) {
                results.append(contentsOf: codexImageResults(fromJSONObject: object))
                reasons.append(contentsOf: codexImageFailureReasons(fromJSONObject: object))
                if let type = object["type"] as? String, !type.isEmpty {
                    eventTypes.append(type)
                }
            }
        }

        var seen = Set<String>()
        let images = results.filter { result in
            guard !seen.contains(result) else { return false }
            seen.insert(result)
            return true
        }
        let reason = images.isEmpty ? (reasons.first ?? fallbackCodexImageReason(eventTypes: eventTypes, data: data)) : nil
        return CodexImageExtraction(images: images, failureReason: reason)
    }

    private static func codexImageResults(fromJSONObject root: [String: Any]) -> [String] {
        var results: [String] = []

        if let type = root["type"] as? String,
           type.contains("image_generation"),
           let result = root["result"] as? String,
           !result.isEmpty {
            results.append(result)
        }

        if let item = root["item"] as? [String: Any],
           (item["type"] as? String) == "image_generation_call",
           let result = item["result"] as? String,
           !result.isEmpty {
            results.append(result)
        }

        if let response = root["response"] as? [String: Any] {
            results.append(contentsOf: codexImageResults(fromJSONObject: response))
        }

        if let output = root["output"] as? [[String: Any]] {
            results.append(contentsOf: output.compactMap { item -> String? in
                guard (item["type"] as? String) == "image_generation_call",
                      let result = item["result"] as? String,
                      !result.isEmpty else {
                    return nil
                }
                return result
            })
        }

        return results
    }

    private static func codexImageFailureReasons(fromJSONObject root: [String: Any]) -> [String] {
        var reasons: [String] = []

        if let error = root["error"] {
            reasons.append(contentsOf: textValues(from: error))
        }
        if let details = root["incomplete_details"] {
            reasons.append(contentsOf: textValues(from: details))
        }

        if let item = root["item"] as? [String: Any] {
            reasons.append(contentsOf: codexImageFailureReasons(fromJSONObject: item))
            if (item["type"] as? String) == "image_generation_call",
               item["result"] == nil,
               let status = item["status"] as? String,
               !status.isEmpty,
               status != "completed" {
                reasons.append("image_generation_call status=\(status)")
            }
        }

        if let response = root["response"] as? [String: Any] {
            reasons.append(contentsOf: codexImageFailureReasons(fromJSONObject: response))
            if let status = response["status"] as? String,
               status != "completed",
               !status.isEmpty {
                reasons.append("response status=\(status)")
            }
        }

        if let output = root["output"] as? [[String: Any]] {
            for item in output {
                reasons.append(contentsOf: codexImageFailureReasons(fromJSONObject: item))
            }
        }

        if let content = root["content"] as? [[String: Any]] {
            for item in content {
                if let refusal = item["refusal"] as? String, !refusal.isEmpty {
                    reasons.append(refusal)
                }
                if let text = item["text"] as? String,
                   (item["type"] as? String) == "output_text",
                   !text.isEmpty {
                    reasons.append(text)
                }
            }
        }

        return dedupedNonEmpty(reasons)
    }

    private static func textValues(from value: Any) -> [String] {
        if let text = value as? String {
            return [text]
        }
        if let object = value as? [String: Any] {
            let keys = ["message", "reason", "detail", "code", "type"]
            return keys.compactMap { key in
                (object[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        }
        return []
    }

    private static func fallbackCodexImageReason(eventTypes: [String], data: Data) -> String? {
        let uniqueTypes = dedupedNonEmpty(eventTypes)
        if !uniqueTypes.isEmpty {
            return "Codex SSE 未包含 image_generation_call.result；events=\(uniqueTypes.prefix(8).joined(separator: ","))"
        }
        let text = String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return text.isEmpty ? nil : String(text.prefix(500))
    }

    private static func dedupedNonEmpty(_ values: [String]) -> [String] {
        var seen = Set<String>()
        return values
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .filter { value in
                guard !seen.contains(value) else { return false }
                seen.insert(value)
                return true
            }
    }

    private static func sseJSONObjects(from text: String) -> [[String: Any]] {
        var objects: [[String: Any]] = []
        var dataLines: [String] = []

        func flush() {
            guard !dataLines.isEmpty else { return }
            let payload = dataLines.joined(separator: "\n")
            dataLines.removeAll()
            guard payload != "[DONE]",
                  let data = payload.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return
            }
            objects.append(object)
        }

        for rawLine in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = rawLine.trimmingCharacters(in: CharacterSet(charactersIn: "\r"))
            if line.isEmpty {
                flush()
                continue
            }
            if line.hasPrefix(":") { continue }
            guard line.hasPrefix("data:") else { continue }
            var value = String(line.dropFirst("data:".count))
            if value.hasPrefix(" ") {
                value.removeFirst()
            }
            dataLines.append(value)
        }
        flush()
        return objects
    }

    private static func normalizedBase64(_ value: String) -> String {
        if let comma = value.lastIndex(of: ",") {
            return String(value[value.index(after: comma)...])
        }
        return value
    }

    private struct RawImageItem {
        var base64: String?
        var url: String?
        var mimeType: String
    }

    private func imageEndpoint(from baseURL: String) throws -> URL {
        guard var components = URLComponents(string: baseURL.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              components.host != nil else {
            throw IOSImageGenerationError.invalidBaseURL
        }
        var path = components.path
        while path.hasSuffix("/") {
            path.removeLast()
        }
        if !path.hasSuffix("/images/generations") {
            path += "/images/generations"
        }
        components.path = path
        guard let url = components.url else { throw IOSImageGenerationError.invalidBaseURL }
        return url
    }

    private func imageItems(from data: Data) throws -> [RawImageItem] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawItems = object["data"] as? [[String: Any]] else {
            throw IOSImageGenerationError.invalidResponse
        }
        return rawItems.compactMap { item in
            let base64 = (item["b64_json"] as? String) ?? (item["data"] as? String)
            let url = item["url"] as? String
            let mime = (item["mime_type"] as? String) ?? (item["mimeType"] as? String) ?? "image/png"
            guard base64 != nil || url != nil else { return nil }
            return RawImageItem(base64: base64, url: url, mimeType: mime)
        }
    }

    private func imageData(
        from item: RawImageItem,
        transport: any IOSImageGenerationHTTPTransport
    ) async throws -> Data {
        if let base64 = item.base64 {
            let payload: String
            if let comma = base64.lastIndex(of: ",") {
                payload = String(base64[base64.index(after: comma)...])
            } else {
                payload = base64
            }
            guard let data = Data(base64Encoded: payload) else {
                throw IOSImageGenerationError.invalidImageData
            }
            return data
        }
        if let rawURL = item.url, let url = URL(string: rawURL) {
            var request = URLRequest(url: url)
            request.httpMethod = "GET"
            request.timeoutInterval = 60
            let (response, data) = try await transport.send(request)
            guard (200...299).contains(response.statusCode) else {
                throw IOSImageGenerationError.httpStatus(response.statusCode, responseBodyPreview(data))
            }
            return data
        }
        throw IOSImageGenerationError.invalidImageData
    }

    private func imageDirectory() throws -> URL {
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let directory = documents.appendingPathComponent("image-generation", isDirectory: true)
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    nonisolated private static func generatedImageDirectoryURL() -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("image-generation", isDirectory: true)
    }

    nonisolated private static func repairedGeneratedImageURL(from staleURL: URL) -> URL? {
        guard staleURL.path.contains("/image-generation/") else { return nil }
        let fileName = staleURL.lastPathComponent
        guard !fileName.isEmpty else { return nil }
        return generatedImageDirectoryURL().appendingPathComponent(fileName, isDirectory: false)
    }

    private func responseBodyPreview(_ data: Data) -> String {
        let text = String(data: data, encoding: .utf8) ?? ""
        return String(text.prefix(500))
    }

    private func normalizedSourceImageURL(_ raw: String?) throws -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else {
            return nil
        }
        if trimmed.hasPrefix("data:") {
            return trimmed
        }
        if let url = URL(string: trimmed),
           let scheme = url.scheme?.lowercased() {
            if scheme == "http" || scheme == "https" {
                return trimmed
            }
            if scheme == Self.chatImageURLScheme,
               let resolved = Self.resolvedImageURL(from: trimmed) {
                return try imageDataURL(from: resolved)
            }
            if scheme == "file" {
                return try imageDataURL(from: Self.resolvedImageURL(from: trimmed) ?? url)
            }
        }
        return try imageDataURL(from: Self.resolvedImageURL(from: trimmed) ?? URL(fileURLWithPath: trimmed))
    }

    private func imageDataURL(from url: URL) throws -> String {
        let data = try Data(contentsOf: url)
        return "data:\(mimeType(for: url));base64,\(data.base64EncodedString())"
    }

    private func mimeType(for url: URL) -> String {
        switch url.pathExtension.lowercased() {
        case "jpg", "jpeg":
            return "image/jpeg"
        case "webp":
            return "image/webp"
        case "gif":
            return "image/gif"
        default:
            return "image/png"
        }
    }

    private func fileExtension(for mimeType: String) -> String {
        let lower = mimeType.lowercased()
        if lower.contains("jpeg") || lower.contains("jpg") { return "jpg" }
        if lower.contains("webp") { return "webp" }
        return "png"
    }

    private func jsonObject(_ string: String) -> [String: Any] {
        guard let data = string.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return object
    }

    private func jsonString(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .withoutEscapingSlashes]),
              let string = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return string
    }

    private func intValue(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        if let value = value as? NSNumber { return value.intValue }
        if let value = value as? String { return Int(value) }
        return nil
    }

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1_000)
    }
}

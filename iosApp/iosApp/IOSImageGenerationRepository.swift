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
        guard !payload.isEmpty else { throw IOSImageGenerationError.noImages }

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
        return IOSImageGenerationRequest(
            prompt: prompt,
            model: modelId,
            aspectRatio: aspect,
            count: count,
            style: style,
            source: "chat"
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
            "files": record.files.map { ["url": URL(fileURLWithPath: $0.path).absoluteString, "mime_type": $0.mimeType] }
        ]
        return jsonString(payload)
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

    private func responseBodyPreview(_ data: Data) -> String {
        let text = String(data: data, encoding: .utf8) ?? ""
        return String(text.prefix(500))
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

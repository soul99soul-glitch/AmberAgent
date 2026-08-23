import Foundation
@preconcurrency import Shared

/// Host-side provider/model configuration tools for the Chat agent.
/// Keys never appear in tool results. Writes go through `IOSSharedSettingsStore`.
@MainActor
final class IOSProviderConfigToolService {
    private let sharedSettings: IOSSharedSettingsStore

    init(sharedSettings: IOSSharedSettingsStore) {
        self.sharedSettings = sharedSettings
    }

    // MARK: - Public entry

    func execute(toolName: String, argumentsJSON: String) async -> String {
        switch toolName {
        case "provider_config_status":
            return status(argumentsJSON: argumentsJSON)
        case "provider_config_apply":
            return apply(argumentsJSON: argumentsJSON)
        case "provider_config_create":
            return create(argumentsJSON: argumentsJSON)
        case "provider_refresh_models":
            return await refreshModels(argumentsJSON: argumentsJSON)
        case "settings_set_model_slot":
            return setModelSlot(argumentsJSON: argumentsJSON)
        default:
            return fail(toolName, "未知的配置工具。")
        }
    }

    // MARK: - status

    private func status(argumentsJSON: String) -> String {
        let args = Self.jsonObject(argumentsJSON) ?? [:]
        let providerIdFilter = (args["provider_id"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let nameContains = (args["provider_name_contains"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        let includeModels = (args["include_models"] as? Bool) ?? false

        let snapshot = sharedSettings.snapshot
        var providersOut: [[String: Any]] = []
        var issues: [String] = []

        for provider in snapshot.providers {
            let id = provider.id.description() as String
            let name = provider.name
            if let providerIdFilter, !providerIdFilter.isEmpty,
               id.caseInsensitiveCompare(providerIdFilter) != .orderedSame {
                continue
            }
            if let nameContains, !nameContains.isEmpty,
               !name.lowercased().contains(nameContains) {
                continue
            }
            let chatModels = provider.models.filter { $0.type == ModelType.chat }
            let imageModels = provider.models.filter { $0.type == ModelType.image }
            let hasKey = ChatProviderConfiguration.hasUsableCredential(provider)
            let host = Self.host(of: provider)
            let chatCapable = ChatProviderConfiguration.supportsChatStreaming(provider)
            var entry: [String: Any] = [
                "id": id,
                "name": name,
                "type": Self.typeName(of: provider),
                "enabled": provider.enabled,
                "base_url_host": host,
                "has_api_key": hasKey,
                "auth_mode": Self.authMode(of: provider),
                "chat_model_count": chatModels.count,
                "image_model_count": imageModels.count,
                // false for placeholder brands (e.g. MiMo) — still listed in settings.
                "chat_streaming_supported": chatCapable,
            ]
            if let openAI = provider as? ProviderSetting.OpenAI {
                entry["brand"] = openAI.brand.name
            }
            if includeModels {
                entry["chat_models"] = chatModels.prefix(20).map { model -> [String: Any] in
                    let display = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                    return [
                        "id": model.id.toHexDashString(),
                        "model_id": model.modelId,
                        "display_name": display.isEmpty ? model.modelId : display,
                    ]
                }
            }
            providersOut.append(entry)
            if provider.enabled && !hasKey {
                issues.append("provider \(name) has no API key")
            }
            if provider.enabled && hasKey && chatModels.isEmpty {
                issues.append("provider \(name) has zero chat models")
            }
        }

        let slots = Self.slotMap(snapshot: snapshot)
        for (slot, info) in slots {
            if let resolved = info["resolved"] as? Bool, !resolved,
               let mid = info["model_id"] as? String, !mid.isEmpty {
                issues.append("\(slot) model id does not resolve to a configured model")
            }
        }

        return Self.ok([
            "tool": "provider_config_status",
            "providers": providersOut,
            "slots": slots,
            "available_slots": Self.availableSlots,
            "issues": issues,
        ])
    }

    // MARK: - create

    /// Create a user-owned OpenAI-compatible provider shell (brand=generic).
    /// Does not create/enable bundled brands (MiMo/OpenAI/…) — apply those by id.
    private func create(argumentsJSON: String) -> String {
        let args = Self.jsonObject(argumentsJSON) ?? [:]
        let name = (args["name"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !name.isEmpty else {
            return fail("provider_config_create", "需要 name。")
        }
        guard let baseUrl = (args["base_url"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !baseUrl.isEmpty else {
            return fail("provider_config_create", "需要 base_url。")
        }
        guard let url = URL(string: baseUrl), let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else {
            return fail("provider_config_create", "base_url 必须是合法的 http(s) URL。")
        }
        let host = (url.host ?? "").lowercased()
        let isLocal = host == "localhost" || host == "127.0.0.1" || host.hasPrefix("192.168.")
        if scheme != "https", !isLocal {
            return fail("provider_config_create", "非本机 base_url 必须使用 https。")
        }
        let path = (args["chat_completions_path"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let apiKeyRaw = args["api_key"]
        let apiKey: String?
        if apiKeyRaw == nil {
            apiKey = nil
        } else if let s = apiKeyRaw as? String {
            let trimmed = s.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                if Self.looksLikePlaceholderKey(trimmed) {
                    return fail("provider_config_create", "api_key 看起来是占位符，已拒绝写入。")
                }
                if trimmed.count < 8 {
                    return fail("provider_config_create", "api_key 过短，已拒绝写入。")
                }
            }
            apiKey = trimmed
        } else {
            return fail("provider_config_create", "api_key 必须是字符串。")
        }

        // Shell first (empty key); then optional key via the same Keychain path as apply.
        let shell = IosSettingsMutations.shared.buildBlankOpenAIProvider(
            name: name,
            apiKey: "",
            baseUrl: baseUrl
        )
        _ = sharedSettings.addProvider(shell)
        let providerId = shell.id.description() as String
        var changed: [String] = ["created"]
        if let path, !path.isEmpty {
            _ = sharedSettings.updateProviderEndpoint(
                providerId: providerId,
                baseUrl: baseUrl,
                chatCompletionsPath: path,
                useResponseApi: false,
                promptCaching: false
            )
            changed.append("chat_completions_path")
        }
        if let apiKey, !apiKey.isEmpty {
            _ = sharedSettings.updateProviderApiKey(providerId: providerId, apiKey: apiKey)
            changed.append("api_key")
        }
        let updated = sharedSettings.snapshot.providers.first {
            ($0.id.description() as String) == providerId
        }
        return Self.ok([
            "tool": "provider_config_create",
            "provider_id": providerId,
            "name": updated?.name ?? name,
            "type": "openai",
            "brand": "generic",
            "enabled": updated?.enabled ?? true,
            "has_api_key": !(apiKey ?? "").isEmpty,
            "chat_streaming_supported": true,
            "changed": changed,
            "next": "provider_refresh_models then settings_set_model_slot(slot=chat, …)",
        ])
    }

    // MARK: - apply

    private func apply(argumentsJSON: String) -> String {
        let args = Self.jsonObject(argumentsJSON) ?? [:]
        guard let provider = resolveProvider(args: args) else {
            return fail("provider_config_apply", "找不到唯一匹配的 provider。请用 provider_config_status 取 id。")
        }
        let providerId = provider.id.description() as String

        // Validate fully before any write (single-provider atomic apply).
        let enabled = args["enabled"] as? Bool
        let newName = (args["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let baseUrl = (args["base_url"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let path = (args["chat_completions_path"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let useResponse = args["use_response_api"] as? Bool

        var willChangeBasics = false
        if enabled != nil { willChangeBasics = true }
        if let newName, !newName.isEmpty { willChangeBasics = true }

        var willChangeEndpoint = false
        if let baseUrl, !baseUrl.isEmpty {
            guard let url = URL(string: baseUrl), let scheme = url.scheme?.lowercased(),
                  scheme == "https" || scheme == "http" else {
                return fail("provider_config_apply", "base_url 必须是合法的 http(s) URL。")
            }
            if scheme == "http", url.host != "localhost", url.host != "127.0.0.1" {
                return fail("provider_config_apply", "非本机 base_url 必须使用 https。")
            }
            willChangeEndpoint = true
        }
        if let path, !path.isEmpty { willChangeEndpoint = true }
        if useResponse != nil { willChangeEndpoint = true }

        var keyToWrite: String?
        if args.keys.contains("api_key") {
            guard let raw = args["api_key"] as? String else {
                return fail("provider_config_apply", "api_key 必须是字符串；省略表示不改，空字符串表示清除。")
            }
            let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            let lowered = trimmed.lowercased()
            if !trimmed.isEmpty {
                let placeholders = ["sk-xxx", "your_key", "your-api-key", "xxx", "todo", "placeholder"]
                if placeholders.contains(where: { lowered == $0 || lowered.contains($0) }) {
                    return fail("provider_config_apply", "api_key 看起来是占位符，已拒绝写入。")
                }
                if trimmed.count < 8 {
                    return fail("provider_config_apply", "api_key 过短，已拒绝写入。")
                }
            }
            keyToWrite = trimmed
        }

        guard willChangeBasics || willChangeEndpoint || keyToWrite != nil else {
            return fail("provider_config_apply", "未提供任何可应用的字段。")
        }

        var changed: [String] = []
        if willChangeBasics {
            let nameToSet = (newName?.isEmpty == false) ? newName! : provider.name
            let enabledToSet = enabled ?? provider.enabled
            _ = sharedSettings.updateProviderBasics(
                providerId: providerId,
                name: nameToSet,
                enabled: enabledToSet
            )
            if enabled != nil { changed.append("enabled") }
            if newName?.isEmpty == false { changed.append("name") }
        }

        if willChangeEndpoint {
            let openAI = provider as? ProviderSetting.OpenAI
            let claude = provider as? ProviderSetting.Claude
            let currentBase = openAI?.baseUrl ?? Self.baseURL(of: provider)
            let currentPath = openAI?.chatCompletionsPath ?? "/chat/completions"
            let currentResponse = openAI?.useResponseApi ?? false
            let currentPromptCaching = claude?.promptCaching ?? false
            _ = sharedSettings.updateProviderEndpoint(
                providerId: providerId,
                baseUrl: (baseUrl?.isEmpty == false) ? baseUrl! : currentBase,
                chatCompletionsPath: (path?.isEmpty == false) ? path! : currentPath,
                useResponseApi: useResponse ?? currentResponse,
                promptCaching: currentPromptCaching
            )
            if baseUrl?.isEmpty == false { changed.append("base_url") }
            if path?.isEmpty == false { changed.append("chat_completions_path") }
            if useResponse != nil { changed.append("use_response_api") }
        }

        var keyStatus = "unchanged"
        if let keyToWrite {
            _ = sharedSettings.updateProviderApiKey(providerId: providerId, apiKey: keyToWrite)
            keyStatus = keyToWrite.isEmpty ? "cleared" : "updated"
            changed.append("api_key")
        }

        let updated = sharedSettings.snapshot.providers.first {
            ($0.id.description() as String) == providerId
        }
        return Self.ok([
            "tool": "provider_config_apply",
            "provider_id": providerId,
            "provider_name": updated?.name ?? provider.name,
            "changed_fields": changed,
            // Status only — never the secret material.
            "api_key_status": keyStatus,
            "has_api_key": !(Self.apiKey(of: updated ?? provider).isEmpty),
            "enabled": updated?.enabled ?? provider.enabled,
            "base_url_host": Self.host(of: updated ?? provider),
        ])
    }

    // MARK: - refresh models

    private func refreshModels(argumentsJSON: String) async -> String {
        let args = Self.jsonObject(argumentsJSON) ?? [:]
        guard let provider = resolveProvider(args: args) else {
            return fail("provider_refresh_models", "找不到唯一匹配的 provider。")
        }
        let providerId = provider.id.description() as String
        guard hasRefreshCredential(provider) else {
            return fail("provider_refresh_models", "该 provider 没有可用凭据。请先填写 API Key，或完成 Codex / Grok / Antigravity 登录。")
        }
        let mode = ((args["mode"] as? String) ?? "merge")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()

        let models: [Model]
        do {
            if IOSCodexProviderResolver.isCodexProvider(provider),
               let openAI = provider as? ProviderSetting.OpenAI {
                let discovered = try await IOSCodexOAuthClient(
                    providerId: IOSCodexProviderResolver.providerKey(openAI)
                ).fetchCodexModelsOrThrow()
                models = discovered.map { item in
                    Model(
                        modelId: item.modelId,
                        displayName: item.displayName,
                        id: KotlinUuid.companion.random(),
                        type: ModelType.chat,
                        customHeaders: [],
                        customBodies: [],
                        inputModalities: [],
                        outputModalities: [],
                        abilities: [],
                        tools: Set<BuiltInTools>(),
                        contextWindowTokens: nil,
                        providerOverwrite: nil
                    )
                }
            } else if IOSGrokWebProviderResolver.isGrokWebProvider(provider),
                      let openAI = provider as? ProviderSetting.OpenAI {
                let providerKey = IOSGrokWebProviderResolver.providerKey(openAI)
                if IOSGrokOAuthAuthStore.load(providerId: providerKey) != nil {
                    let discovered = try await IOSGrokOAuthClients.shared(providerId: providerKey)
                        .fetchGrokModelsOrThrow()
                    models = discovered.map { item in
                        Model(
                            modelId: item.modelId,
                            displayName: item.displayName,
                            id: KotlinUuid.companion.random(),
                            type: ModelType.chat,
                            customHeaders: [],
                            customBodies: [],
                            inputModalities: [],
                            outputModalities: [],
                            abilities: [],
                            tools: Set<BuiltInTools>(),
                            contextWindowTokens: nil,
                            providerOverwrite: nil
                        )
                    }
                } else {
                    models = provider.models.filter { $0.type == ModelType.chat }
                }
            } else if let google = provider as? ProviderSetting.Google {
                models = try await IOSGeminiClient(provider: google).listModelsOrThrow()
            } else if let openAI = provider as? ProviderSetting.OpenAI {
                models = try await OpenAIKmpProvider().listModelsWithHeadersOrThrow(
                    providerSetting: openAI,
                    extraHeaders: IOSProviderRequestHeaderStore.headers(for: providerId)
                )
            } else if let claude = provider as? ProviderSetting.Claude {
                models = try await ClaudeKmpProvider().listModelsOrThrow(providerSetting: claude)
            } else {
                return fail("provider_refresh_models", "此 provider 类型暂不支持在 iOS 上拉取模型列表。")
            }
        } catch {
            return fail(
                "provider_refresh_models",
                ChatViewModel.userFacingGenerationError(error.localizedDescription, modelId: nil)
            )
        }

        let chatPairs: [(modelId: String, displayName: String)] = models
            .filter { $0.type == ModelType.chat }
            .map { model in
                let display = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                return (model.modelId, display.isEmpty ? model.modelId : display)
            }
        let beforeCount = provider.models.filter { $0.type == ModelType.chat }.count
        if mode == "replace_chat" {
            _ = sharedSettings.updateProviderChatModels(providerId: providerId, models: chatPairs)
        } else {
            _ = sharedSettings.mergeProviderChatModels(providerId: providerId, models: chatPairs)
        }
        let after = sharedSettings.snapshot.providers.first {
            ($0.id.description() as String) == providerId
        }
        let afterCount = after?.models.filter { $0.type == ModelType.chat }.count ?? 0
        let sample = (after?.models.filter { $0.type == ModelType.chat } ?? [])
            .prefix(20)
            .map { m -> String in
                let d = m.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                return d.isEmpty ? m.modelId : d
            }

        return Self.ok([
            "tool": "provider_refresh_models",
            "provider_id": providerId,
            "provider_name": after?.name ?? provider.name,
            "mode": mode == "replace_chat" ? "replace_chat" : "merge",
            "fetched_chat_models": chatPairs.count,
            "chat_model_count_before": beforeCount,
            "chat_model_count_after": afterCount,
            "sample_labels": Array(sample),
        ])
    }

    // MARK: - set model slot

    private func setModelSlot(argumentsJSON: String) -> String {
        let args = Self.jsonObject(argumentsJSON) ?? [:]
        guard let slotRaw = (args["slot"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased(),
            !slotRaw.isEmpty else {
            return fail("settings_set_model_slot", "需要 slot。")
        }
        let modelIdArg = (args["model_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let modelRef = (args["model_ref"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)

        let wantImage = slotRaw == "image_generation"
        let candidates = sharedSettings.snapshot.providers
            .filter(\.enabled)
            .flatMap { provider -> [(Model, String)] in
            provider.models
                .filter { wantImage ? $0.type == ModelType.image : $0.type == ModelType.chat }
                .map { ($0, provider.name) }
        }

        let resolved: (Model, String)?
        if let modelIdArg, !modelIdArg.isEmpty {
            resolved = candidates.first {
                $0.0.id.toHexDashString().caseInsensitiveCompare(modelIdArg) == .orderedSame
            }
            if resolved == nil {
                return fail("settings_set_model_slot", "model_id 未匹配到已配置模型。")
            }
        } else if let modelRef, !modelRef.isEmpty {
            let q = modelRef.lowercased()
            let hits = candidates.filter { model, _ in
                model.modelId.lowercased().contains(q)
                    || model.displayName.lowercased().contains(q)
                    || model.id.toHexDashString().lowercased() == q
            }
            if hits.isEmpty {
                return fail("settings_set_model_slot", "model_ref 未匹配到已配置模型。")
            }
            if hits.count > 1 {
                let labels = hits.prefix(8).map { m, p -> [String: Any] in
                    let d = m.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                    return [
                        "id": m.id.toHexDashString(),
                        "model_id": m.modelId,
                        "display_name": d.isEmpty ? m.modelId : d,
                        "provider_name": p,
                    ]
                }
                return Self.ok([
                    "tool": "settings_set_model_slot",
                    "status": "ambiguous",
                    "reason": "model_ref 匹配到多个模型，请改用 model_id。",
                    "candidates": Array(labels),
                ])
            }
            resolved = hits[0]
        } else {
            return fail("settings_set_model_slot", "需要 model_id 或 model_ref。")
        }

        guard let (model, providerName) = resolved else {
            return fail("settings_set_model_slot", "未能解析模型。")
        }
        let hex = model.id.toHexDashString()
        switch slotRaw {
        case "chat":
            sharedSettings.setCurrentChatModelId(hex)
        case "assistant_chat":
            sharedSettings.setCurrentAssistantChatModelId(hex)
        case "title":
            sharedSettings.setTitleModelId(hex)
        case "ocr":
            sharedSettings.setOcrModelId(hex)
        case "compress":
            sharedSettings.setCompressModelId(hex)
        case "suggestion":
            sharedSettings.setSuggestionModelId(hex)
        case "image_generation":
            sharedSettings.setImageGenerationModelId(hex)
        default:
            return fail(
                "settings_set_model_slot",
                "不支持的 slot。可用：chat, assistant_chat, title, ocr, compress, suggestion, image_generation。"
            )
        }

        let display = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        return Self.ok([
            "tool": "settings_set_model_slot",
            "slot": slotRaw,
            "model_id": hex,
            "model_wire_id": model.modelId,
            "display_name": display.isEmpty ? model.modelId : display,
            "provider_name": providerName,
        ])
    }

    // MARK: - Resolve provider

    private func resolveProvider(args: [String: Any]) -> ProviderSetting? {
        let providers = sharedSettings.snapshot.providers
        if let id = (args["provider_id"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !id.isEmpty {
            return providers.first {
                ($0.id.description() as String).caseInsensitiveCompare(id) == .orderedSame
            }
        }
        if let name = (args["provider_name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
           !name.isEmpty {
            let q = name.lowercased()
            let exact = providers.filter { $0.name.lowercased() == q }
            if exact.count == 1 { return exact[0] }
            let partial = providers.filter { $0.name.lowercased().contains(q) }
            if partial.count == 1 { return partial[0] }
            return nil
        }
        return nil
    }

    // MARK: - Helpers

    private static func slotMap(snapshot: Settings) -> [String: [String: Any]] {
        func resolve(_ raw: String?, wantImage: Bool) -> [String: Any] {
            let id = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !id.isEmpty else {
                return ["model_id": "", "resolved": false, "label": NSNull()]
            }
            for provider in snapshot.providers {
                for model in provider.models {
                    let typeOK = wantImage ? model.type == ModelType.image : model.type == ModelType.chat
                    guard typeOK else { continue }
                    if model.id.toHexDashString().caseInsensitiveCompare(id) == .orderedSame {
                        let d = model.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                        return [
                            "model_id": id,
                            "resolved": true,
                            "label": d.isEmpty ? model.modelId : d,
                            "provider_name": provider.name,
                        ]
                    }
                }
            }
            return ["model_id": id, "resolved": false, "label": NSNull()]
        }
        func hex(_ id: KotlinUuid) -> String { id.toHexDashString() }
        let assistantChatModelId = snapshot.getCurrentAssistant().chatModelId ?? snapshot.chatModelId
        return [
            "chat": resolve(hex(snapshot.chatModelId), wantImage: false),
            "assistant_chat": resolve(hex(assistantChatModelId), wantImage: false),
            "title": resolve(hex(snapshot.titleModelId), wantImage: false),
            "ocr": resolve(hex(snapshot.ocrModelId), wantImage: false),
            "compress": resolve(hex(snapshot.compressModelId), wantImage: false),
            "suggestion": resolve(hex(snapshot.suggestionModelId), wantImage: false),
            "image_generation": resolve(hex(snapshot.imageGenerationModelId), wantImage: true),
        ]
    }

    private func hasRefreshCredential(_ provider: ProviderSetting) -> Bool {
        ChatProviderConfiguration.hasUsableCredential(provider)
    }

    /// Fixed global model roles — not free-form provider names (e.g. "mimo" is a brand, not a slot).
    static let availableSlots: [String] = [
        "chat", "assistant_chat", "title", "ocr", "compress", "suggestion", "image_generation",
    ]

    private static func looksLikePlaceholderKey(_ value: String) -> Bool {
        let lower = value.lowercased()
        if lower.contains("your") && lower.contains("key") { return true }
        if lower.contains("placeholder") || lower.contains("example") { return true }
        if lower == "sk-xxx" || lower == "sk-..." || lower.hasPrefix("sk-xxxx") { return true }
        return false
    }

    private static func apiKey(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI {
            return openAI.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let claude = provider as? ProviderSetting.Claude {
            return claude.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        if let google = provider as? ProviderSetting.Google {
            return google.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return ""
    }

    private static func baseURL(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI { return openAI.baseUrl }
        if let claude = provider as? ProviderSetting.Claude { return claude.baseUrl }
        if let google = provider as? ProviderSetting.Google { return google.baseUrl }
        return ""
    }

    private static func host(of provider: ProviderSetting) -> String {
        let raw = baseURL(of: provider)
        return URL(string: raw)?.host ?? raw
    }

    private static func typeName(of provider: ProviderSetting) -> String {
        if provider is ProviderSetting.OpenAI { return "openai" }
        if provider is ProviderSetting.Claude { return "claude" }
        if provider is ProviderSetting.Google { return "google" }
        return "unknown"
    }

    private static func authMode(of provider: ProviderSetting) -> String {
        if let openAI = provider as? ProviderSetting.OpenAI {
            return openAI.authMode.name
        }
        return "api_key"
    }

    private static func jsonObject(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        return obj
    }

    private static func ok(_ payload: [String: Any]) -> String {
        var body = payload
        body["ok"] = true
        body["status"] = body["status"] ?? "ok"
        return IOSWorkspaceStore.json(body)
    }

    private func fail(_ tool: String, _ reason: String) -> String {
        ChatToolOutputFormatter.toolFailureJSON(toolName: tool, reason: reason, status: "failed")
    }
}

enum IOSProviderConfigToolCatalog {
    static let toolNames: Set<String> = [
        "provider_config_status",
        "provider_config_apply",
        "provider_config_create",
        "provider_refresh_models",
        "settings_set_model_slot",
    ]
    static let mutatingToolNames: Set<String> = [
        "provider_config_apply",
        "provider_config_create",
        "provider_refresh_models",
        "settings_set_model_slot",
    ]
    /// Writes that touch credentials or create providers need a foreground approval card.
    static let highRiskToolNames: Set<String> = [
        "provider_config_apply",
        "provider_config_create",
    ]
    static let backgroundAllowedToolNames: Set<String> = [
        "provider_config_status",
    ]

    /// Redact secrets in tool arguments while keeping valid JSON (for persistence
    /// and re-upload). Approval cards may further truncate via truncatedMcpArguments.
    static func redactedArgumentsJSON(_ argumentsJSON: String) -> String {
        var args = ChatToolCallParsing.jsonObject(argumentsJSON) ?? [:]
        if args.keys.contains("api_key") {
            if let raw = args["api_key"] as? String {
                let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.isEmpty {
                    args["api_key"] = "(clear)"
                } else if trimmed.count <= 4 {
                    args["api_key"] = "****"
                } else {
                    args["api_key"] = "****\(String(trimmed.suffix(4)))"
                }
            } else {
                args["api_key"] = "(redacted)"
            }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: args, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return "{}"
        }
        return text
    }

    /// Approval snapshots are durable before the tool runs. Keep the live
    /// in-memory call intact for the approved write, but never persist or
    /// re-upload a plaintext provider key while waiting for that approval.
    static func redactedApprovalMessages(_ messages: [UIMessage]) -> [UIMessage] {
        messages.map { message in
            var changed = false
            let parts = message.parts.map { part -> UIMessagePart in
                guard let tool = part as? UIMessagePart.Tool,
                      tool.toolName == "provider_config_apply" else {
                    return part
                }
                changed = true
                return UIMessagePart.Tool(
                    toolCallId: tool.toolCallId,
                    toolName: tool.toolName,
                    input: redactedArgumentsJSON(tool.input),
                    output: tool.output,
                    approvalState: tool.approvalState,
                    streamIndex: tool.streamIndex,
                    metadata: nil
                )
            }
            guard changed else { return message }
            return UIMessage(
                id: message.id,
                role: message.role,
                parts: parts,
                annotations: message.annotations,
                createdAt: message.createdAt,
                finishedAt: message.finishedAt,
                modelId: message.modelId,
                usage: message.usage,
                translation: message.translation
            )
        }
    }

    /// Approval-card preview that never echoes raw API keys.
    static func redactedApprovalPreview(argumentsJSON: String) -> String {
        ChatToolCallParsing.truncatedMcpArguments(
            ChatToolCallParsing.jsonObject(redactedArgumentsJSON(argumentsJSON)) ?? [:]
        )
    }

    static func approvalReason(argumentsJSON: String) -> String {
        let args = ChatToolCallParsing.jsonObject(argumentsJSON) ?? [:]
        var parts: [String] = ["将写入本机 LLM 提供商配置，需要你确认。"]
        if let name = args["provider_name"] as? String, !name.isEmpty {
            parts.append("目标：\(name)")
        } else if let id = args["provider_id"] as? String, !id.isEmpty {
            parts.append("目标 id：\(String(id.prefix(8)))…")
        }
        if args.keys.contains("api_key") {
            let raw = (args["api_key"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            parts.append(raw.isEmpty ? "将清除 API Key。" : "将更新 API Key（仅显示尾号）。")
        }
        if let base = args["base_url"] as? String, !base.isEmpty {
            let host = URL(string: base)?.host ?? base
            parts.append("将改 endpoint host：\(host)")
        }
        return parts.joined(separator: " ")
    }
}

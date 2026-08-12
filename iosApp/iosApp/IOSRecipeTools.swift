import Foundation
@preconcurrency import Shared

// MARK: - Recipe approval request types (Wave B2; §14.2)
//
// One approval prompt type for the whole recipe surface, discriminated by
// payload:
// - `.step`:  a mutation STEP of an in-flight `recipe__<name>` call needs the
//   existing per-step approval (invariant 11 — a recipe never gets blanket
//   approval; §10.3.5). The card shows which step, which primitive and the
//   step's arguments.
// - `.import`: a `recipe_import` promotion of a Workspace `recipe.json`
//   candidate needs explicit approval (§13.1 / §14.2). The card shows the
//   manifest summary, permission envelope, base/candidate short hashes, the
//   step list (scrollable when long) and the "批准后从下一模型轮生效" copy.
//
// These are deliberately NOT slotted into `McpToolApprovalRequest`: the skill
// card's shape (file changes / server chips) does not fit a recipe's
// envelope/steps/hashes, and the finisher semantics differ (recipe step
// approval continues the recipe; recipe import approval applies a package).

struct RecipeStepApprovalPayload: Equatable {
    let stepId: String
    let tool: String
    let argumentsPreview: String
    let effectClass: IOSToolEffectClass
}

struct RecipeImportApprovalPayload: Equatable {
    let mutationKind: IOSRecipeMutationKind
    let baseHash: String?
    let candidateHash: String
    let description: String
    let permissionSummary: String
    let effectClassRawValue: String
    let inputsSummary: String
    /// One row per step: `"<stepId> → <tool>"` (card renders them in a
    /// scrollable list when long, §14.2).
    let stepsSummary: [String]
    let outputsSummary: String
}

struct RecipeToolApprovalRequest: Identifiable, Equatable {
    enum Payload: Equatable {
        case step(RecipeStepApprovalPayload)
        case recipeImport(RecipeImportApprovalPayload)
    }

    let id: String
    let recipeName: String
    let recipeVersion: String
    let payload: Payload
    let reason: String

    var title: String {
        switch payload {
        case .step: "执行 Recipe 步骤"
        case .recipeImport: "导入 Recipe"
        }
    }

    var activityKind: AgentActivityKind {
        switch payload {
        case .step: .workflow
        case .recipeImport: .workflow
        }
    }
}

// MARK: - Recipe import preview / prepared context (mirrors skill_import)

/// Read-only preview of a Workspace `recipe.json` candidate. Applying must
/// reproduce this exact candidate hash (§13.1: base/candidate CAS).
struct IOSRecipeImportPreview: Equatable {
    let name: String
    let version: String
    let kind: IOSRecipeMutationKind
    let baseHash: String?
    let candidateHash: String
    let description: String
    let effectClassRawValue: String
    let permissionSummary: String
    let inputsSummary: String
    let stepsSummary: [String]
    let outputsSummary: String

    var approvalSummary: String {
        let action = kind == .new ? "新增" : "更新"
        return "\(action) Recipe \(name) v\(version)"
    }
}

/// Small, in-memory approval context (same lifecycle as
/// `IOSPreparedSkillImport`). The candidate bytes stay in Workspace and are
/// read again when approval is granted, so stale candidates cannot be applied.
struct IOSPreparedRecipeImport: Equatable {
    let workspacePath: String
    let preview: IOSRecipeImportPreview
}

enum IOSRecipeToolCatalog {
    static let toolNames: Set<String> = ["recipe_import"]
    static let mutatingToolNames: Set<String> = ["recipe_import"]
}

// MARK: - Execution checkpoint (durable pending-approval state, §13.2 / W1)

/// One paused `recipe__<name>` execution, mirrored to disk before the pause
/// becomes durable (`markRunAwaitingPermission`), so the in-flight recipe
/// state (inputs, completed step outputs, next step index) is never lost to a
/// process death between "card shown" and "card answered". The Room
/// awaiting-permission marker + persisted baseMessages are the SAME durable
/// domain the existing approvals use; the checkpoint file lives next to the
/// recipe store (`<base>/recipes/.checkpoints/`) because it pins that store's
/// catalog revision. Cold-start recovery terminates the approval fail-closed
/// (never resumes), so the file's only reader is the in-process finisher; a
/// sweep at the next recipe execution start removes any orphan (at most one
/// recipe pause can be live per process).
struct IOSRecipeExecutionCheckpoint: Codable, Equatable, Sendable {
    let schemaVersion: Int
    let toolCallId: String
    let recipeName: String
    let recipeVersion: String
    let catalogRevision: Int64?
    let inputs: [String: IOSRecipeJSONValue]
    let stepOutputs: [String: String]
    let completedSteps: [String]
    let nextStepIndex: Int
    let executionId: String
}

struct IOSRecipeExecutionCheckpointStore {
    static let schemaVersion = 1

    private let directory: URL
    private let fileManager: FileManager

    init(baseDirectory: URL, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.directory = baseDirectory
            .appendingPathComponent("recipes", isDirectory: true)
            .appendingPathComponent(".checkpoints", isDirectory: true)
    }

    /// Atomic write; the parent directory is created on demand. Best-effort
    /// mirror (a failed checkpoint write must not block the pause, but it is
    /// logged loudly — the checkpoint is the resume contract).
    func save(_ checkpoint: IOSRecipeExecutionCheckpoint) {
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(checkpoint).write(to: fileURL(toolCallId: checkpoint.toolCallId), options: .atomic)
        } catch {
            NSLog("[IOSRecipeTools] checkpoint write failed toolCallId=\(checkpoint.toolCallId): \(error.localizedDescription)")
        }
    }

    func remove(toolCallId: String) {
        try? fileManager.removeItem(at: fileURL(toolCallId: toolCallId))
    }

    /// Removes every checkpoint file. Safe: at most one recipe pause can be
    /// live in the process (a paused run holds `currentRunId`; a new run
    /// cancels it and clears the checkpoint first), so any file on disk at
    /// the start of a new recipe execution is an orphan from a crash.
    func sweepOrphans() {
        guard let urls = try? fileManager.contentsOfDirectory(
            at: directory,
            includingPropertiesForKeys: nil,
            options: []
        ) else { return }
        for url in urls {
            try? fileManager.removeItem(at: url)
        }
    }

    private func fileURL(toolCallId: String) -> URL {
        // toolCallId is model-supplied text; the filename is its digest so an
        // arbitrary id cannot escape the checkpoints directory.
        directory.appendingPathComponent("\(chatInputDigest(for: toolCallId)).json")
    }
}

// MARK: - recipe_import service (mirrors IOSSkillMcpToolService.skill_import)

/// `recipe_import` service: read-only preview of the Workspace candidate →
/// explicit approval → re-read + base/candidate CAS + semantic validation
/// (the registry's own catalog oracle) → `applyRecipe` → registry refresh so
/// the next model round sees the promoted recipe (§13.1 / §16.1).
@MainActor
struct IOSRecipeToolService {
    private static let maximumRecipeReadBytes = 256 * 1024

    let workspaceStore: IOSWorkspaceStore
    let recipeStore: IOSRecipeFileStore
    /// The same catalog oracle the registry uses (`IOSDynamicToolRegistry
    /// .primitiveCatalogEntry`), so promotion validation and execution-time
    /// availability can never disagree (§16.1).
    let catalog: IOSRecipeCatalogLookup
    /// Round-boundary publish: after a successful apply the registry is
    /// refreshed so the next model round acquires the new revision. Returns
    /// the published snapshot (its revision goes into the receipt).
    let refreshRegistry: @MainActor () async -> IOSDynamicToolCatalogSnapshot?
    /// §19 观测：stale CAS fail-closed 计数（Phase 4 Wave 1）。
    let metrics: IOSEvolutionMetrics

    init(
        workspaceStore: IOSWorkspaceStore,
        recipeStore: IOSRecipeFileStore,
        catalog: @escaping IOSRecipeCatalogLookup = IOSDynamicToolRegistry.primitiveCatalogEntry,
        refreshRegistry: @escaping @MainActor () async -> IOSDynamicToolCatalogSnapshot? = {
            await IOSDynamicToolRegistry.shared.refresh()
        },
        metrics: IOSEvolutionMetrics = .shared
    ) {
        self.workspaceStore = workspaceStore
        self.recipeStore = recipeStore
        self.catalog = catalog
        self.refreshRegistry = refreshRegistry
        self.metrics = metrics
    }

    /// Read-only preview — zero writes (never creates directories).
    func prepareRecipeImport(arguments: String) throws -> IOSPreparedRecipeImport {
        guard let args = ChatToolCallParsing.jsonObject(arguments) else {
            throw IOSRecipeToolError.invalidArguments
        }
        let workspacePath = (args["workspace_path"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !workspacePath.isEmpty else {
            throw IOSRecipeToolError.missingArgument("workspace_path")
        }
        let resolved = try resolveWorkspacePath(workspacePath)
        let data = try readWorkspaceRecipeData(workspacePath: resolved)
        let preview = try makeImportPreview(recipeJSON: data)
        return IOSPreparedRecipeImport(workspacePath: resolved, preview: preview)
    }

    /// Applies a previously previewed candidate after re-checking both sides
    /// of the CAS contract AND re-validating the manifest against the catalog
    /// oracle. Any stale change fails closed with a structured error — zero
    /// writes (§13.1: "任一变化，批准必须 fail closed，重新 preview").
    func applyPreparedRecipeImport(_ prepared: IOSPreparedRecipeImport) async throws -> String {
        let data: Data
        do {
            data = try readWorkspaceRecipeData(workspacePath: prepared.workspacePath)
        } catch {
            metrics.record(.staleCASSkipped)
            return Self.importErrorJSON(
                code: "stale_candidate",
                message: "Workspace 候选包已无法读取，请重新生成并预览。"
            )
        }
        let reread: IOSRecipeImportPreview
        do {
            reread = try makeImportPreview(recipeJSON: data)
        } catch {
            metrics.record(.staleCASSkipped)
            return Self.importErrorJSON(
                code: "stale_candidate",
                message: (error as? LocalizedError)?.errorDescription
                    ?? "Workspace 候选包已无法验证，请重新生成并预览。"
            )
        }
        guard reread.candidateHash == prepared.preview.candidateHash else {
            metrics.record(.staleCASSkipped)
            return Self.importErrorJSON(
                code: "stale_candidate",
                message: "Workspace 候选包在批准前发生变化，请重新预览。"
            )
        }
        guard reread.baseHash == prepared.preview.baseHash else {
            metrics.record(.staleCASSkipped)
            return Self.importErrorJSON(
                code: "stale_base",
                message: "已安装 Recipe 在批准前发生变化，请重新预览。"
            )
        }

        let receipt: IOSRecipeApplyReceipt
        do {
            receipt = try recipeStore.applyRecipe(
                name: prepared.preview.name,
                recipeJSON: data,
                expectedBaseHash: prepared.preview.baseHash,
                expectedCandidateHash: prepared.preview.candidateHash
            )
        } catch let error as IOSRecipeFileStoreError {
            switch error {
            case .recipePackageBaseChanged:
                metrics.record(.staleCASSkipped)
                return Self.importErrorJSON(
                    code: "stale_base",
                    message: error.errorDescription ?? "已安装 Recipe 在批准前发生变化，请重新预览。"
                )
            case .recipePackageCandidateChanged:
                metrics.record(.staleCASSkipped)
                return Self.importErrorJSON(
                    code: "stale_candidate",
                    message: error.errorDescription ?? "Workspace 候选包在批准前发生变化，请重新预览。"
                )
            default:
                throw error
            }
        }

        // §13.2.3 / §16.1: publish the new revision so the NEXT model round
        // acquires the promoted recipe (the round-boundary seam refreshes on
        // its own; this makes the receipt carry the actual revision).
        let snapshot = await refreshRegistry()
        return Self.json([
            "success": true,
            "status": receipt.outcome == .applied ? "applied" : "unchanged",
            "name": receipt.name,
            "hash": receipt.promotedHash,
            "version": reread.version,
            "description": reread.description,
            "permission_envelope": reread.effectClassRawValue,
            "permission_summary": reread.permissionSummary,
            "catalog_revision": snapshot?.revision as Any? ?? NSNull(),
        ])
    }

    /// Model-facing preview payload for `recipe_import` (mirrors the skill
    /// import preview shape: `requires_approval` + hashes + summaries).
    func recipeImportPreviewJSON(_ prepared: IOSPreparedRecipeImport) -> String {
        let preview = prepared.preview
        return Self.json([
            "ok": true,
            "status": "preview",
            "requires_approval": true,
            "name": preview.name,
            "version": preview.version,
            "kind": preview.kind.rawValue,
            "base_hash": preview.baseHash as Any? ?? NSNull(),
            "candidate_hash": preview.candidateHash,
            "description": preview.description,
            "permission_envelope": preview.effectClassRawValue,
            "permission_summary": preview.permissionSummary,
            "inputs": preview.inputsSummary,
            "steps": preview.stepsSummary,
            "outputs": preview.outputsSummary,
        ])
    }

    // MARK: Private

    private func makeImportPreview(recipeJSON: Data) throws -> IOSRecipeImportPreview {
        let preparation = try recipeStore.prepareRecipe(recipeJSON: recipeJSON)
        let manifest = try IOSRecipeManifest.decode(recipeJSON)
        let validation = IOSRecipeValidator.validate(manifest: manifest, catalog: catalog)
        guard validation.isValid, let envelope = validation.permissionEnvelope else {
            throw IOSRecipeToolError.invalidRecipePackage(
                validation.issues.map(\.message)
            )
        }
        let permissionSummary = IOSDynamicToolRegistry.permissionSummary(for: envelope)
        let inputsSummary = manifest.inputs.sorted(by: { $0.key < $1.key })
            .map { "\($0.key):\($0.value.rawValue)" }
            .joined(separator: ", ")
        let stepsSummary = manifest.steps.map { "\($0.id) → \($0.tool)" }
        let outputsSummary = manifest.outputs.sorted(by: { $0.key < $1.key })
            .map { name, value in
                if case .binding(let binding) = value { return "\(name)=\(binding.text)" }
                return name
            }
            .joined(separator: ", ")
        return IOSRecipeImportPreview(
            name: preparation.candidate.name,
            version: preparation.candidate.version,
            kind: preparation.kind,
            baseHash: preparation.base?.hash,
            candidateHash: preparation.candidate.hash,
            description: manifest.description,
            effectClassRawValue: envelope.rawValue,
            permissionSummary: permissionSummary,
            inputsSummary: inputsSummary,
            stepsSummary: stepsSummary,
            outputsSummary: outputsSummary
        )
    }

    /// Normalizes `workspace_path` (accepts `/workspace/...`, bare paths and
    /// record ids) and returns the canonical workspace-relative path.
    private func resolveWorkspacePath(_ raw: String) throws -> String {
        var path = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !path.isEmpty, !path.contains("\\") else {
            throw IOSRecipeToolError.invalidWorkspacePath(raw)
        }
        if path == "/workspace" || path == "/workspace/" {
            throw IOSRecipeToolError.missingArgument("workspace_path")
        }
        if path.hasPrefix("/workspace/") {
            path.removeFirst("/workspace/".count)
        } else if path.hasPrefix("/") {
            throw IOSRecipeToolError.invalidWorkspacePath(raw)
        }
        while path.hasSuffix("/") { path.removeLast() }
        let components = path.split(separator: "/", omittingEmptySubsequences: false)
        guard !components.isEmpty,
              components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else {
            throw IOSRecipeToolError.invalidWorkspacePath(raw)
        }
        return components.map(String.init).joined(separator: "/")
    }

    private func readWorkspaceRecipeData(workspacePath: String) throws -> Data {
        let record = workspaceStore.fileRecord(idOrPath: workspacePath)
            ?? workspaceStore.fileRecord(idOrPath: "/workspace/\(workspacePath)")
        guard let record else {
            throw IOSRecipeToolError.workspacePathMissing(workspacePath)
        }
        let url = workspaceStore.fileURL(for: record)
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true else {
            throw IOSRecipeToolError.invalidWorkspacePath(workspacePath)
        }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let data = try handle.read(upToCount: Self.maximumRecipeReadBytes + 1) ?? Data()
        guard data.count <= Self.maximumRecipeReadBytes else {
            throw IOSRecipeToolError.recipeFileTooLarge(workspacePath, Self.maximumRecipeReadBytes)
        }
        return data
    }

    static func json(_ object: [String: Any]) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]),
              let text = String(data: data, encoding: .utf8) else {
            return #"{"ok":false,"error":"JSON encoding failed"}"#
        }
        return text
    }

    static func importErrorJSON(code: String, message: String) -> String {
        json([
            "ok": false,
            "success": false,
            "status": "stale",
            "code": code,
            "error": message,
        ])
    }
}

// MARK: - Approval request builders

enum RecipeToolApprovalRequestBuilder {
    /// Card for one mutation step of an in-flight recipe execution.
    /// Slice B（B2）：id 必须是「外层 toolCallId + executionId + stepId」的
    /// 复合 id——同一 recipe 执行里的不同 step 卡 id 互不相同，旧 step 卡的
    /// approve/deny 不能消费当前暂停的另一个 step。
    static func stepRequest(
        for toolCall: UIMessagePart.Tool,
        recipeName: String,
        recipeVersion: String,
        payload: RecipeStepApprovalPayload,
        reason: String,
        executionId: String
    ) -> RecipeToolApprovalRequest {
        RecipeToolApprovalRequest(
            id: "\(ChatToolCallParsing.requestId(for: toolCall)):\(executionId):\(payload.stepId)",
            recipeName: recipeName,
            recipeVersion: recipeVersion,
            payload: .step(payload),
            reason: reason
        )
    }

    /// Card for a `recipe_import` promotion (§14.2: manifest summary +
    /// permission envelope + short hashes + step list + next-round copy).
    static func importRequest(
        for toolCall: UIMessagePart.Tool,
        prepared: IOSPreparedRecipeImport
    ) -> RecipeToolApprovalRequest {
        let preview = prepared.preview
        return RecipeToolApprovalRequest(
            id: ChatToolCallParsing.requestId(for: toolCall),
            recipeName: preview.name,
            recipeVersion: preview.version,
            payload: .recipeImport(RecipeImportApprovalPayload(
                mutationKind: preview.kind,
                baseHash: preview.baseHash,
                candidateHash: preview.candidateHash,
                description: preview.description,
                permissionSummary: preview.permissionSummary,
                effectClassRawValue: preview.effectClassRawValue,
                inputsSummary: preview.inputsSummary,
                stepsSummary: preview.stepsSummary,
                outputsSummary: preview.outputsSummary
            )),
            reason: "请核对候选 Recipe 的 manifest 摘要与权限包络；批准后会复核 base/candidate 哈希并原子替换 Recipe 包。"
        )
    }
}

private enum IOSRecipeToolError: LocalizedError {
    case invalidArguments
    case missingArgument(String)
    case workspacePathMissing(String)
    case invalidWorkspacePath(String)
    case recipeFileTooLarge(String, Int)
    case invalidRecipePackage([String])

    var errorDescription: String? {
        switch self {
        case .invalidArguments:
            "Tool arguments must be a JSON object."
        case .missingArgument(let name):
            "\(name) is required"
        case .workspacePathMissing(let path):
            "Workspace path not found: \(path)"
        case .invalidWorkspacePath(let path):
            "Recipe 候选路径非法：\(path)"
        case .recipeFileTooLarge(let path, let limit):
            "Recipe 候选文件 \(path) 超过上限 \(limit) 字节。"
        case .invalidRecipePackage(let issues):
            issues.joined(separator: "；")
        }
    }
}

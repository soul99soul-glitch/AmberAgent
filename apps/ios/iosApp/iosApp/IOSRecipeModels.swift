import Foundation

// MARK: - amber.recipe.v1 manifest model, canonical encoding and validator
//
// Declarative Recipe plugin model (§10 of
// the iOS Recipe runtime contract).
//
// Wire format decision: the manifest is JSON (Codable), not YAML. §10.2 of the
// plan shows a YAML example, but that is only a semantic illustration — a
// recipe package is a single `recipe.json` file, and its canonical bytes are
// the JSON produced by `canonicalJSONData()` (deterministically re-encoded,
// keys sorted at every level), so "executed = stored = hashed" (invariant 5)
// holds across preview, apply, rollback and execution.
//
// Grammar / budget decisions (documented here, enforced below):
// - recipe `name`: `^[a-z][a-z0-9_]{1,31}$` (2..32 chars). It will later be
//   exposed to the model as the tool `recipe__<name>` (§13.2), so the charset
//   excludes characters that cannot appear in a ToolId.
// - input names / step ids / output names: `^[a-z][a-z0-9_]{1,32}$` — the
//   same conservative charset keeps binding syntax unambiguous.
// - step-output JSON fields referenced by bindings: `^[A-Za-z_][A-Za-z0-9_]*$`
//   (a JSON key may be anything, but only identifier-like keys can be bound).
// - binding grammar: `"${input.<name>}"` or `"${step.<id>.output.<field>}"`.
//   A binding must be the ENTIRE argument string; there is no string
//   interpolation. A string that starts with `${` but does not match the
//   grammar is a syntax error (rejected), so a typo cannot silently become a
//   literal.
// - steps ≤ 8 (§18.3 "artifact 文件数/总字节/step 数上限" budget spirit; the
//   exact cap is a budget constant, not a schema rule).
// - per-step timeout: default 60s, cap 600s (`IOSRecipeLimits`).
// - numbers in argument literals decode to Double; integers beyond 2^53 may
//   lose exactness on re-encode (documented limitation, deterministic hash).
//
// Invariants this file participates in:
// - I-5 (executed = stored = hashed): the store hashes the canonical bytes
//   this file produces; the runner executes the same decoded manifest.
// - I-10 (permission does not silently widen): `permissionEnvelope` is the
//   conservative union (upper bound) of every step's effect class.
// - No loops / recursion / dynamic code / recipe-calling-recipe (§10.1): the
//   validator rejects any step whose tool starts with `recipe__`.

// MARK: - Limits and naming rules

enum IOSRecipeLimits {
    /// §18.3 budget spirit: at most 8 sequential steps per recipe.
    static let maxSteps = 8
    /// Every step has a timeout; this applies when the manifest omits one.
    static let defaultStepTimeoutSeconds = 60
    /// Upper cap for an explicitly declared step timeout.
    static let maxStepTimeoutSeconds = 600
    /// Recipe package hash domain separator (mirrors the skill store's
    /// domain-separated hashing so different artifact kinds cannot collide).
    static let packageHashDomain = Data("amber.recipe.package.v1\0".utf8)
}

enum IOSRecipeNames {
    /// `^[a-z][a-z0-9_]{1,31}$` — the recipe id, later exposed as `recipe__<name>`.
    static func isValidRecipeName(_ raw: String) -> Bool {
        guard let first = raw.first, isAsciiLowercase(first) else { return false }
        let rest = raw.dropFirst()
        guard (1...31).contains(rest.count) else { return false }
        return rest.allSatisfy { isAsciiLowercase($0) || isAsciiDigit($0) || $0 == "_" }
    }

    /// `^[a-z][a-z0-9_]{1,31}$` — input names / step ids / output names.
    static func isValidMemberName(_ raw: String) -> Bool {
        guard let first = raw.first, isAsciiLowercase(first) else { return false }
        let rest = raw.dropFirst()
        guard (0...31).contains(rest.count) else { return false }
        return rest.allSatisfy { isAsciiLowercase($0) || isAsciiDigit($0) || $0 == "_" }
    }

    /// `^[A-Za-z_][A-Za-z0-9_]*$` — a step-output JSON field that a binding
    /// may reference (top-level keys only).
    static func isValidOutputField(_ raw: String) -> Bool {
        guard let first = raw.first, isAsciiLowercase(first) || isAsciiUppercase(first) || first == "_" else {
            return false
        }
        return raw.dropFirst().allSatisfy { isAsciiLowercase($0) || isAsciiUppercase($0) || isAsciiDigit($0) || $0 == "_" }
    }

    private static func isAsciiLowercase(_ c: Character) -> Bool { ("a"..."z").contains(c) }
    private static func isAsciiUppercase(_ c: Character) -> Bool { ("A"..."Z").contains(c) }
    private static func isAsciiDigit(_ c: Character) -> Bool { ("0"..."9").contains(c) }
}

// MARK: - JSON value (argument literals, run inputs, run outputs)

/// A JSON value with a deterministic canonical encoding. Used for step
/// argument literals, call-time inputs and recipe outputs.
enum IOSRecipeJSONValue: Equatable, Sendable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case null
    case array([IOSRecipeJSONValue])
    case object([String: IOSRecipeJSONValue])

    /// Walks the value and returns every string that starts with "${" but does
    /// not parse as a binding — i.e. intended-but-malformed binding syntax.
    func malformedBindingStrings() -> [String] {
        var found: [String] = []
        collectMalformedBindings(into: &found)
        return found
    }

    private func collectMalformedBindings(into found: inout [String]) {
        switch self {
        case .string(let s):
            if s.hasPrefix("${"), IOSRecipeBinding.parse(s) == nil {
                found.append(s)
            }
        case .array(let items):
            for item in items { item.collectMalformedBindings(into: &found) }
        case .object(let dict):
            for (_, value) in dict.sorted(by: { $0.key < $1.key }) {
                value.collectMalformedBindings(into: &found)
            }
        case .number, .bool, .null:
            break
        }
    }
}

extension IOSRecipeJSONValue: Codable {
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            // Verified: JSONDecoder throws for `1` when asked for Bool and for
            // `true` when asked for Double, so this order is unambiguous.
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([IOSRecipeJSONValue].self) {
            self = .array(value)
        } else {
            let object = try container.decode([String: IOSRecipeJSONValue].self)
            self = .object(object)
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .null: try container.encodeNil()
        case .array(let items): try container.encode(items)
        case .object(let dict): try container.encode(dict)
        }
    }
}

// MARK: - Bindings

/// One `"${input.x}"` / `"${step.<id>.output.<field>}"` reference.
struct IOSRecipeBinding: Equatable, Sendable {
    enum Source: Equatable, Sendable {
        case input(String)
        case stepOutput(stepId: String, field: String)
    }

    let source: Source

    /// Parses the strict grammar. Returns nil for anything that is not a
    /// complete, well-formed binding.
    static func parse(_ raw: String) -> IOSRecipeBinding? {
        guard raw.hasPrefix("${"), raw.hasSuffix("}") else { return nil }
        let inner = String(raw.dropFirst(2).dropLast())
        if inner.hasPrefix("input.") {
            let name = String(inner.dropFirst("input.".count))
            guard IOSRecipeNames.isValidMemberName(name) else { return nil }
            return IOSRecipeBinding(source: .input(name))
        }
        if inner.hasPrefix("step.") {
            let rest = String(inner.dropFirst("step.".count))
            guard let outputRange = rest.range(of: ".output.") else { return nil }
            let stepId = String(rest[..<outputRange.lowerBound])
            let field = String(rest[outputRange.upperBound...])
            guard IOSRecipeNames.isValidMemberName(stepId),
                  IOSRecipeNames.isValidOutputField(field) else { return nil }
            return IOSRecipeBinding(source: .stepOutput(stepId: stepId, field: field))
        }
        return nil
    }

    /// Canonical `${...}` text for this binding.
    var text: String {
        switch source {
        case .input(let name): return "${input.\(name)}"
        case .stepOutput(let stepId, let field): return "${step.\(stepId).output.\(field)}"
        }
    }
}

/// An argument value: either a literal JSON value or a binding. A string that
/// matches the full binding grammar is always a binding; any other string is a
/// literal (a `${`-prefixed non-matching string is flagged by the validator).
enum IOSRecipeValue: Equatable, Sendable {
    case binding(IOSRecipeBinding)
    case literal(IOSRecipeJSONValue)
}

extension IOSRecipeValue: Codable {
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let raw = try? container.decode(String.self) {
            if let binding = IOSRecipeBinding.parse(raw) {
                self = .binding(binding)
            } else {
                self = .literal(.string(raw))
            }
        } else {
            self = .literal(try container.decode(IOSRecipeJSONValue.self))
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .binding(let binding): try container.encode(binding.text)
        case .literal(let value): try container.encode(value)
        }
    }
}

// MARK: - Manifest

enum IOSRecipeInputType: String, Codable, Equatable, Sendable {
    case string
    case number
    case boolean
}

struct IOSRecipeStep: Codable, Equatable, Sendable {
    let id: String
    /// Production ToolId of an App-shipped primitive (§5.3: recipes may only
    /// reference already-published ToolIds).
    let tool: String
    let arguments: [String: IOSRecipeValue]
    /// Optional; `IOSRecipeLimits.defaultStepTimeoutSeconds` applies when nil.
    let timeoutSeconds: Int?
}

struct IOSRecipeManifest: Codable, Equatable, Sendable {
    static let schemaVersion = "amber.recipe.v1"

    let schema: String
    let name: String
    let version: String
    let description: String
    let inputs: [String: IOSRecipeInputType]
    let steps: [IOSRecipeStep]
    let outputs: [String: IOSRecipeValue]

    init(
        schema: String = IOSRecipeManifest.schemaVersion,
        name: String,
        version: String,
        description: String,
        inputs: [String: IOSRecipeInputType],
        steps: [IOSRecipeStep],
        outputs: [String: IOSRecipeValue]
    ) {
        self.schema = schema
        self.name = name
        self.version = version
        self.description = description
        self.inputs = inputs
        self.steps = steps
        self.outputs = outputs
    }

    /// Decodes a recipe manifest from raw JSON bytes. Throws on malformed
    /// JSON, unknown input types or a malformed binding — i.e. grammar/shape
    /// errors. Semantic errors (unknown tools, dangling references, budget
    /// violations) are reported by `IOSRecipeValidator`, which needs a catalog.
    static func decode(_ data: Data) throws -> IOSRecipeManifest {
        try JSONDecoder().decode(IOSRecipeManifest.self, from: data)
    }

    /// Canonical bytes: every dictionary key sorted at every level
    /// (`JSONEncoder.sortedKeys`), deterministic for identical content. The
    /// store hashes exactly these bytes (invariant 5).
    func canonicalJSONData() throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        encoder.keyEncodingStrategy = .useDefaultKeys
        return try encoder.encode(self)
    }
}

// MARK: - Catalog lookup (injected; no chat singleton dependency)

/// What the runner/validator needs to know about one primitive ToolId.
struct IOSRecipeCatalogEntry: Equatable, Sendable {
    let exists: Bool
    /// The tool's version as known to the catalog (informational today; used
    /// as the plan's `toolVersion` for ledger/lease attribution, and reserved
    /// for future per-step minimum-version checks — §10.1 "ToolId 和最低版本").
    let minVersion: String?
    let effectClass: IOSToolEffectClass
}

/// `(ToolId) -> entry?`; nil means the catalog does not know the tool at all
/// (same fail-closed treatment as `exists == false`).
typealias IOSRecipeCatalogLookup = @Sendable (String) -> IOSRecipeCatalogEntry?

// MARK: - Validation

enum IOSRecipeValidationCode: String, Equatable, Sendable {
    case invalidManifestJSON
    case schemaMismatch
    case invalidName
    case invalidVersion
    case emptyDescription
    case invalidInputName
    case invalidInputType
    case noSteps
    case stepLimitExceeded
    case invalidStepId
    case duplicateStepId
    case invalidToolName
    case recipeToolReference
    case unknownTool
    case invalidBindingSyntax
    case unresolvedInputBinding
    case unresolvedStepBinding
    case invalidStepReference
    case invalidTimeout
    case invalidOutputName
    case outputMustBeBinding
    case unresolvedOutputStep
}

struct IOSRecipeValidationIssue: Equatable, Sendable {
    let code: IOSRecipeValidationCode
    /// Human-readable dotted path to the offending field, e.g. `steps[1].tool`.
    let path: String?
    let message: String
}

struct IOSRecipeValidationResult: Equatable, Sendable {
    let issues: [IOSRecipeValidationIssue]
    /// Conservative union of all steps' effect classes (I-10, §10.3.7); nil
    /// when the manifest has any issue (there is no valid envelope to report).
    let permissionEnvelope: IOSToolEffectClass?

    var isValid: Bool { issues.isEmpty }
}

enum IOSRecipeValidator {
    /// Pure function: manifest + catalog lookup closure → issues + envelope.
    /// No chat singleton, no I/O — unit-testable in isolation.
    static func validate(
        manifest: IOSRecipeManifest,
        catalog: @escaping IOSRecipeCatalogLookup
    ) -> IOSRecipeValidationResult {
        var issues: [IOSRecipeValidationIssue] = []

        if manifest.schema != IOSRecipeManifest.schemaVersion {
            issues.append(issue(.schemaMismatch, path: "schema",
                                 "不支持的 schema「\(manifest.schema)」，需要 \(IOSRecipeManifest.schemaVersion)。"))
        }
        if !IOSRecipeNames.isValidRecipeName(manifest.name) {
            issues.append(issue(.invalidName, path: "name",
                                 "name 必须匹配 ^[a-z][a-z0-9_]{1,31}$（将来以 recipe__<name> 暴露）。"))
        }
        if manifest.version.isEmpty || manifest.version.unicodeScalars.contains(where: {
            CharacterSet.whitespacesAndNewlines.contains($0)
        }) {
            issues.append(issue(.invalidVersion, path: "version", "version 不能为空且不能包含空白字符。"))
        }
        if manifest.description.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            issues.append(issue(.emptyDescription, path: "description", "description 不能为空。"))
        }

        for (name, _) in manifest.inputs.sorted(by: { $0.key < $1.key }) {
            if !IOSRecipeNames.isValidMemberName(name) {
                issues.append(issue(.invalidInputName, path: "inputs.\(name)",
                                     "输入名必须匹配 ^[a-z][a-z0-9_]{1,31}$。"))
            }
        }

        if manifest.steps.isEmpty {
            issues.append(issue(.noSteps, path: "steps", "Recipe 至少需要一个 step。"))
        } else if manifest.steps.count > IOSRecipeLimits.maxSteps {
            issues.append(issue(.stepLimitExceeded, path: "steps",
                                 "step 数 \(manifest.steps.count) 超过上限 \(IOSRecipeLimits.maxSteps)（§18.3 预算）。"))
        }

        var seenStepIds: Set<String> = []
        for (index, step) in manifest.steps.enumerated() {
            let stepPath = "steps[\(index)]"
            if !IOSRecipeNames.isValidMemberName(step.id) {
                issues.append(issue(.invalidStepId, path: "\(stepPath).id",
                                     "step id 必须匹配 ^[a-z][a-z0-9_]{1,31}$。"))
            } else if seenStepIds.contains(step.id) {
                issues.append(issue(.duplicateStepId, path: "\(stepPath).id",
                                     "step id「\(step.id)」重复。"))
            } else {
                seenStepIds.insert(step.id)
            }

            let trimmedTool = step.tool.trimmingCharacters(in: .whitespacesAndNewlines)
            if trimmedTool.isEmpty || trimmedTool != step.tool || trimmedTool.contains(where: { $0.isWhitespace }) {
                issues.append(issue(.invalidToolName, path: "\(stepPath).tool",
                                     "tool 必须是 App 已发布的 ToolId 字符串（无空白）。"))
            } else if trimmedTool.hasPrefix("recipe__") {
                // §10.1: no recipe-calling-recipe; recipes only compose
                // primitives, defense in depth even before catalog lookup.
                issues.append(issue(.recipeToolReference, path: "\(stepPath).tool",
                                     "Recipe 不能引用另一个 Recipe（tool「\(trimmedTool)」）。"))
            } else if let entry = catalog(trimmedTool) {
                if !entry.exists {
                    issues.append(issue(.unknownTool, path: "\(stepPath).tool",
                                         "工具「\(trimmedTool)」在目录中不存在。"))
                }
            } else {
                issues.append(issue(.unknownTool, path: "\(stepPath).tool",
                                     "工具「\(trimmedTool)」不在当前 primitive 目录中。"))
            }

            if let timeout = step.timeoutSeconds {
                if timeout <= 0 || timeout > IOSRecipeLimits.maxStepTimeoutSeconds {
                    issues.append(issue(.invalidTimeout, path: "\(stepPath).timeoutSeconds",
                                         "timeout 必须在 1…\(IOSRecipeLimits.maxStepTimeoutSeconds) 秒之间。"))
                }
            }

            for (key, value) in step.arguments.sorted(by: { $0.key < $1.key }) {
                let argPath = "\(stepPath).arguments.\(key)"
                switch value {
                case .binding(let binding):
                    validate(binding, at: argPath, stepId: step.id, index: index,
                             manifest: manifest, issues: &issues)
                case .literal(let literal):
                    for malformed in literal.malformedBindingStrings() {
                        issues.append(issue(.invalidBindingSyntax, path: argPath,
                                             "「\(malformed)」不是合法的绑定语法（绑定必须是完整字符串，如 ${input.x}）。"))
                    }
                }
            }
        }

        for (name, value) in manifest.outputs.sorted(by: { $0.key < $1.key }) {
            if !IOSRecipeNames.isValidMemberName(name) {
                issues.append(issue(.invalidOutputName, path: "outputs.\(name)",
                                     "输出名必须匹配 ^[a-z][a-z0-9_]{1,31}$。"))
            }
            switch value {
            case .binding(let binding):
                // Outputs are evaluated after every step has run, so any
                // existing step may be referenced (but still not a missing one).
                switch binding.source {
                case .stepOutput(let stepId, _):
                    if !seenStepIds.contains(stepId) {
                        issues.append(issue(.unresolvedOutputStep, path: "outputs.\(name)",
                                             "输出引用了不存在的 step「\(stepId)」。"))
                    }
                case .input:
                    issues.append(issue(.outputMustBeBinding, path: "outputs.\(name)",
                                         "输出必须绑定 step 输出，不能绑定输入。"))
                }
            case .literal:
                issues.append(issue(.outputMustBeBinding, path: "outputs.\(name)",
                                     "输出必须是 ${step.<id>.output.<field>} 绑定。"))
            }
        }

        let envelope: IOSToolEffectClass?
        if issues.isEmpty {
            let classes = manifest.steps.compactMap { step -> IOSToolEffectClass? in
                let trimmedTool = step.tool.trimmingCharacters(in: .whitespacesAndNewlines)
                guard let entry = catalog(trimmedTool), entry.exists else { return nil }
                return entry.effectClass
            }
            envelope = IOSToolEffectClass.conservativeUpperBound(of: classes)
        } else {
            envelope = nil
        }
        return IOSRecipeValidationResult(issues: issues, permissionEnvelope: envelope)
    }

    /// Convenience for raw bytes: a decode failure surfaces as a single
    /// `invalidManifestJSON` issue instead of throwing.
    static func validate(
        data: Data,
        catalog: @escaping IOSRecipeCatalogLookup
    ) -> IOSRecipeValidationResult {
        guard let manifest = try? IOSRecipeManifest.decode(data) else {
            return IOSRecipeValidationResult(
                issues: [issue(.invalidManifestJSON, path: nil, "recipe.json 不是合法的 amber.recipe.v1 JSON。")],
                permissionEnvelope: nil
            )
        }
        return validate(manifest: manifest, catalog: catalog)
    }

    // MARK: Argument binding resolution rules (used by validate)

    private static func validate(
        _ binding: IOSRecipeBinding,
        at path: String,
        stepId: String,
        index: Int,
        manifest: IOSRecipeManifest,
        issues: inout [IOSRecipeValidationIssue]
    ) {
        switch binding.source {
        case .input(let name):
            if manifest.inputs[name] == nil {
                issues.append(issue(.unresolvedInputBinding, path: path,
                                     "绑定了未声明的输入「\(name)」。"))
            }
        case .stepOutput(let referencedStepId, _):
            guard let referencedIndex = manifest.steps.firstIndex(where: { $0.id == referencedStepId }) else {
                issues.append(issue(.unresolvedStepBinding, path: path,
                                     "绑定了不存在的 step「\(referencedStepId)」。"))
                return
            }
            if referencedIndex >= index {
                issues.append(issue(.invalidStepReference, path: path,
                                     "step「\(stepId)」只能绑定前序 step（不能绑定自身或后续 step）。"))
            }
        }
    }

    private static func issue(_ code: IOSRecipeValidationCode, path: String?, _ message: String) -> IOSRecipeValidationIssue {
        IOSRecipeValidationIssue(code: code, path: path, message: message)
    }
}

// MARK: - Effect class ordering (I-10 conservative union)

extension IOSToolEffectClass {
    /// Ordering used for the conservative union:
    /// pure < networkRead < idempotent < sideEffect.
    var conservativenessRank: Int {
        switch self {
        case .pure: return 0
        case .networkRead: return 1
        case .idempotent: return 2
        case .sideEffect: return 3
        }
    }

    /// Upper bound of a set of effect classes; nil only for an empty set.
    /// A recipe's envelope is the most conservative class among all steps
    /// (§10.3.7, invariant 10: permission does not silently widen).
    static func conservativeUpperBound(of classes: [IOSToolEffectClass]) -> IOSToolEffectClass? {
        classes.max { $0.conservativenessRank < $1.conservativenessRank }
    }
}

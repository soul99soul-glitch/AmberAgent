import CryptoKit
import Foundation

// MARK: - IOSRecipeFileStore
//
// Independent, deliberately small store for declarative Recipe packages
// (§10.4: do NOT generalize `IOSSkillFileStore` into a generic artifact store
// yet — a second artifact kind that needs the same machinery has not
// appeared). A recipe package is a single `recipe.json` (the canonical bytes
// produced by `IOSRecipeManifest.canonicalJSONData()`).
//
// Mirrors the skill store's verified safety principles:
// - canonical package bytes + stable domain-separated hash (invariant 5:
//   executed = stored = hashed);
// - read-only preview (`prepareRecipe` never touches disk state);
// - base/candidate hash CAS on apply — a changed live base or changed
//   candidate fails closed with zero writes (§13.1);
// - same-volume staging + atomic `replaceItemAt` publish (a crash between the
//   slot and live renames can still leave a backup/stale slot; rollback
//   availability fails closed there);
// - active + single-slot previous (§18.1);
// - rollback re-validates the exact manifest the caller saw AND the live hash
//   against the slot's promoted hash, so a newer import cannot replace the
//   confirmed rollback target;
// - successful apply/rollback writes an `IOSPromotionReceipt` whose `toHash`
//   is the same hash the store just published (no re-hashing).

enum IOSRecipeMutationKind: String, Codable, Equatable {
    case new
    case update
}

/// A recipe package as the store hashes it: canonical bytes + stable hash.
struct IOSRecipePackage: Equatable {
    let name: String
    let version: String
    let hash: String
    let canonicalJSON: Data
}

/// What a preview shows; applying must reproduce this exact candidate hash.
struct IOSRecipePackagePreparation: Equatable {
    let kind: IOSRecipeMutationKind
    let base: IOSRecipePackage?
    let candidate: IOSRecipePackage
}

/// Durable rollback slot manifest (`.previous/<name>/manifest.json`).
struct IOSRecipePreviousManifest: Codable, Equatable {
    let schemaVersion: Int
    let hashFormatVersion: Int
    let name: String
    let kind: IOSRecipeMutationKind
    let baseHash: String?
    let promotedHash: String
}

enum IOSRecipeApplyOutcome: Equatable {
    case applied
    case unchanged
}

struct IOSRecipeApplyReceipt: Equatable {
    let name: String
    let promotedHash: String
    let outcome: IOSRecipeApplyOutcome
}

enum IOSRecipeRollbackAvailability: Equatable {
    case available(IOSRecipePreviousManifest)
    case unavailable(String)
    case stale(String)

    var canRollback: Bool {
        if case .available = self { return true }
        return false
    }

    var reason: String {
        switch self {
        case .available(let manifest):
            manifest.kind == .new
                ? "回退后会移除这个新导入的 Recipe。"
                : "可恢复上一次导入前的 Recipe 包。"
        case .unavailable(let reason), .stale(let reason):
            reason
        }
    }
}

struct IOSRecipeRollbackReceipt: Equatable {
    let manifest: IOSRecipePreviousManifest
}

struct IOSRecipeFileStore {
    // All in-process writers share the CAS critical section, even when callers
    // construct separate value-type store instances for the same directory.
    private static let mutationLock = NSLock()

    private let baseDirectory: URL
    private let fileManager: FileManager

    init(baseDirectory: URL, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.baseDirectory = baseDirectory
    }

    var recipesDirectory: URL {
        baseDirectory.appendingPathComponent("recipes", isDirectory: true)
    }

    private var previousDirectory: URL {
        recipesDirectory.appendingPathComponent(".previous", isDirectory: true)
    }

    // MARK: Preview — zero writes

    /// Builds the exact package a later approved apply would promote. Only
    /// reads; never creates directories, staging or previous slots.
    func prepareRecipe(recipeJSON: Data) throws -> IOSRecipePackagePreparation {
        let candidate = try makePackage(recipeJSON: recipeJSON)
        let base = try installedPackage(name: candidate.name)
        return IOSRecipePackagePreparation(
            kind: base == nil ? .new : .update,
            base: base,
            candidate: candidate
        )
    }

    // MARK: Apply — base/candidate CAS + atomic publish + receipt

    /// Promotes a previously previewed candidate after re-checking both sides
    /// of the CAS contract. The candidate is fully staged before the previous
    /// slot changes; synchronous publication failures restore the prior slot.
    /// A process crash mid-publish can still leave a backup or stale slot;
    /// `rollbackAvailability` fails closed there.
    ///
    /// `approvedBy` / `evaluationReportHash` are the receipt authorizer
    /// (invariant 7/16: policy engine + policy version for autonomous
    /// promotions, "user" for manual approvals) and the bound evaluation
    /// report — both default to the historical manual path so existing
    /// callers compile unchanged.
    @discardableResult
    func applyRecipe(
        name: String,
        recipeJSON: Data,
        expectedBaseHash: String?,
        expectedCandidateHash: String,
        approvedBy: String = "user",
        evaluationReportHash: String? = nil
    ) throws -> IOSRecipeApplyReceipt {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }

        let candidate = try makePackage(recipeJSON: recipeJSON)
        guard candidate.name == name else {
            throw IOSRecipeFileStoreError.recipeNameMismatch(expected: name, actual: candidate.name)
        }
        guard candidate.hash == expectedCandidateHash else {
            throw IOSRecipeFileStoreError.recipePackageCandidateChanged(
                expected: expectedCandidateHash,
                actual: candidate.hash
            )
        }

        let base = try installedPackage(name: name)
        guard base?.hash == expectedBaseHash else {
            throw IOSRecipeFileStoreError.recipePackageBaseChanged(
                expected: expectedBaseHash,
                actual: base?.hash
            )
        }
        if base?.hash == candidate.hash {
            return IOSRecipeApplyReceipt(name: name, promotedHash: candidate.hash, outcome: .unchanged)
        }

        let kind: IOSRecipeMutationKind = base == nil ? .new : .update
        let manifest = IOSRecipePreviousManifest(
            schemaVersion: Self.previousManifestSchemaVersion,
            hashFormatVersion: Self.packageHashFormatVersion,
            name: name,
            kind: kind,
            baseHash: base?.hash,
            promotedHash: candidate.hash
        )
        let liveStagingDirectory = try stageLivePackage(candidate)
        var shouldRemoveLiveStaging = true
        defer {
            if shouldRemoveLiveStaging {
                try? fileManager.removeItem(at: liveStagingDirectory)
            }
        }

        let previousBackup = try backupPreviousSlotIfPresent(name: name)
        do {
            try publishPreviousSlot(manifest: manifest, base: base)
            try promoteStagedLivePackage(liveStagingDirectory, name: name)
            shouldRemoveLiveStaging = false
        } catch {
            let publicationError = error
            try restorePreviousSlot(name: name, from: previousBackup)
            throw publicationError
        }
        if let previousBackup {
            try? fileManager.removeItem(at: previousBackup)
        }
        // §15 Phase 0 / §9.6: a successful promotion writes a receipt so later
        // run outcomes can be attributed to this exact candidate hash.
        // `candidate.hash` is the same value `stageLivePackage` wrote, so no
        // re-hashing happens here.
        IOSPromotionReceiptStore(baseDirectory: baseDirectory).record(
            IOSPromotionReceipt(
                artifactId: name,
                fromHash: base?.hash,
                toHash: candidate.hash,
                evaluationReportHash: evaluationReportHash,
                catalogRevision: nil,
                approvedBy: approvedBy,
                promotedAtEpochMs: Self.nowMillis()
            )
        )
        return IOSRecipeApplyReceipt(name: name, promotedHash: candidate.hash, outcome: .applied)
    }

    // MARK: Rollback — re-validated against the seen manifest

    func rollbackAvailability(name: String) throws -> IOSRecipeRollbackAvailability {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        return try inspectRollback(name: name).availability
    }

    /// Restores the package captured by the last apply, or removes a newly
    /// imported recipe. Both the displayed manifest and the live hash are
    /// re-validated here so a newer import cannot replace the confirmed
    /// rollback target.
    func rollbackRecipe(
        name: String,
        expectedManifest: IOSRecipePreviousManifest
    ) throws -> IOSRecipeRollbackReceipt {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        let inspection = try inspectRollback(name: name)
        guard case .available(let manifest) = inspection.availability else {
            throw IOSRecipeFileStoreError.recipeRollbackUnavailable(inspection.availability.reason)
        }
        guard manifest == expectedManifest else {
            throw IOSRecipeFileStoreError.recipeRollbackUnavailable(
                "可回退版本已变化，请刷新后重试。"
            )
        }

        switch manifest.kind {
        case .update:
            guard let previousPackage = inspection.previousPackage else {
                throw IOSRecipeFileStoreError.recipeRollbackUnavailable("上一次 Recipe 包不可用。")
            }
            try publishLivePackage(previousPackage)
        case .new:
            let liveDirectory = try resolveRecipeDirectory(name: name)
            let discardedDirectory = recipesDirectory.appendingPathComponent(
                ".\(name)-rollback-\(UUID().uuidString)",
                isDirectory: true
            )
            try fileManager.moveItem(at: liveDirectory, to: discardedDirectory)
            try? fileManager.removeItem(at: discardedDirectory)
        }

        // Live state is already complete at this point; best-effort slot
        // cleanup so a failed cleanup cannot report a failed rollback.
        try? fileManager.removeItem(at: previousSlotDirectory(name: name))
        // A rollback is itself a versioned promotion (§9.6): record a receipt
        // whose toHash is the restored live package hash. For an update, that
        // is manifest.baseHash (inspectRollback verified the slot package hash
        // equals baseHash before publishing it). A "new" rollback removes the
        // artifact, so its receipts are cleared (no active version, §18.1).
        let receiptStore = IOSPromotionReceiptStore(baseDirectory: baseDirectory)
        switch manifest.kind {
        case .update:
            if let restoredHash = manifest.baseHash {
                receiptStore.record(IOSPromotionReceipt(
                    artifactId: name,
                    fromHash: manifest.promotedHash,
                    toHash: restoredHash,
                    evaluationReportHash: nil,
                    catalogRevision: nil,
                    approvedBy: "user",
                    promotedAtEpochMs: Self.nowMillis()
                ))
            }
        case .new:
            receiptStore.clear(artifactId: name)
        }
        return IOSRecipeRollbackReceipt(manifest: manifest)
    }

    /// Reads the active recipe package (canonical bytes + hash) — used by
    /// tests and by later waves to load the live manifest for execution.
    func readLiveRecipe(name: String) throws -> IOSRecipePackage {
        let directory = try resolveRecipeDirectory(name: name)
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory),
              isDirectory.boolValue else {
            throw IOSRecipeFileStoreError.recipeMissing(name)
        }
        let jsonURL = directory.appendingPathComponent("recipe.json")
        guard fileManager.fileExists(atPath: jsonURL.path) else {
            throw IOSRecipeFileStoreError.recipeMissing(name)
        }
        return try package(at: directory, expectedName: name)
    }

    // MARK: Private

    private static func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private static let previousManifestSchemaVersion = 1
    private static let packageHashFormatVersion = 1

    private struct RollbackInspection {
        let availability: IOSRecipeRollbackAvailability
        let previousPackage: IOSRecipePackage?
    }

    private func makePackage(recipeJSON: Data) throws -> IOSRecipePackage {
        guard let manifest = try? IOSRecipeManifest.decode(recipeJSON) else {
            throw IOSRecipeFileStoreError.invalidRecipeJSON
        }
        let name = Self.normalizedRecipeName(manifest.name)
        guard IOSRecipeNames.isValidRecipeName(name) else {
            throw IOSRecipeFileStoreError.invalidRecipeName
        }
        // Canonical bytes are the stored + hashed bytes (invariant 5).
        let canonicalJSON = try manifest.canonicalJSONData()
        return IOSRecipePackage(
            name: name,
            version: manifest.version,
            hash: Self.canonicalPackageHash(canonicalJSON),
            canonicalJSON: canonicalJSON
        )
    }

    private func installedPackage(name: String) throws -> IOSRecipePackage? {
        let directory = try resolveRecipeDirectory(name: name)
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory) else {
            return nil
        }
        guard isDirectory.boolValue else {
            throw IOSRecipeFileStoreError.invalidRecipeName
        }
        return try package(at: directory, expectedName: name)
    }

    private func package(at directory: URL, expectedName: String) throws -> IOSRecipePackage {
        let rootValues = try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard rootValues.isDirectory == true, rootValues.isSymbolicLink != true else {
            throw IOSRecipeFileStoreError.invalidRecipeName
        }
        let jsonURL = directory.appendingPathComponent("recipe.json")
        let data = try Data(contentsOf: jsonURL)
        let package = try makePackage(recipeJSON: data)
        guard package.name == expectedName else {
            throw IOSRecipeFileStoreError.recipeNameMismatch(expected: expectedName, actual: package.name)
        }
        return package
    }

    private func publishLivePackage(_ package: IOSRecipePackage) throws {
        let stagingDirectory = try stageLivePackage(package)
        var shouldRemoveStaging = true
        defer {
            if shouldRemoveStaging {
                try? fileManager.removeItem(at: stagingDirectory)
            }
        }
        try promoteStagedLivePackage(stagingDirectory, name: package.name)
        shouldRemoveStaging = false
    }

    private func stageLivePackage(_ package: IOSRecipePackage) throws -> URL {
        try fileManager.createDirectory(at: recipesDirectory, withIntermediateDirectories: true)
        let stagingDirectory = recipesDirectory.appendingPathComponent(
            ".\(package.name)-staging-\(UUID().uuidString)",
            isDirectory: true
        )
        try fileManager.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
        try package.canonicalJSON.write(
            to: stagingDirectory.appendingPathComponent("recipe.json"),
            options: .atomic
        )
        return stagingDirectory
    }

    private func promoteStagedLivePackage(_ stagingDirectory: URL, name: String) throws {
        let liveDirectory = try resolveRecipeDirectory(name: name)
        if fileManager.fileExists(atPath: liveDirectory.path) {
            // 原子替换：失败或进程强杀时原目录始终在位（replaceItemAt 先把
            // 新目录就位再移除旧的），没有「旧目录已移成 backup 后被杀」的窗口。
            _ = try fileManager.replaceItemAt(
                liveDirectory,
                withItemAt: stagingDirectory,
                backupItemName: nil,
                options: []
            )
        } else {
            try fileManager.moveItem(at: stagingDirectory, to: liveDirectory)
        }
    }

    private func backupPreviousSlotIfPresent(name: String) throws -> URL? {
        let slotDirectory = previousSlotDirectory(name: name)
        guard fileManager.fileExists(atPath: slotDirectory.path) else { return nil }
        try fileManager.createDirectory(at: previousDirectory, withIntermediateDirectories: true)
        let backupDirectory = previousDirectory.appendingPathComponent(
            ".\(name)-backup-\(UUID().uuidString)",
            isDirectory: true
        )
        try fileManager.moveItem(at: slotDirectory, to: backupDirectory)
        return backupDirectory
    }

    private func restorePreviousSlot(name: String, from backupDirectory: URL?) throws {
        let slotDirectory = previousSlotDirectory(name: name)
        if let backupDirectory {
            if fileManager.fileExists(atPath: slotDirectory.path) {
                _ = try fileManager.replaceItemAt(
                    slotDirectory,
                    withItemAt: backupDirectory,
                    backupItemName: nil,
                    options: []
                )
            } else {
                try fileManager.moveItem(at: backupDirectory, to: slotDirectory)
            }
        } else if fileManager.fileExists(atPath: slotDirectory.path) {
            try fileManager.removeItem(at: slotDirectory)
        }
    }

    private func publishPreviousSlot(
        manifest: IOSRecipePreviousManifest,
        base: IOSRecipePackage?
    ) throws {
        try fileManager.createDirectory(at: previousDirectory, withIntermediateDirectories: true)
        let slotDirectory = previousSlotDirectory(name: manifest.name)
        let stagingDirectory = previousDirectory.appendingPathComponent(
            ".\(manifest.name)-staging-\(UUID().uuidString)",
            isDirectory: true
        )
        try fileManager.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
        var shouldRemoveStaging = true
        defer {
            if shouldRemoveStaging {
                try? fileManager.removeItem(at: stagingDirectory)
            }
        }

        if let base {
            let packageDirectory = stagingDirectory.appendingPathComponent("package", isDirectory: true)
            try fileManager.createDirectory(at: packageDirectory, withIntermediateDirectories: true)
            try base.canonicalJSON.write(
                to: packageDirectory.appendingPathComponent("recipe.json"),
                options: .atomic
            )
        }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        try encoder.encode(manifest).write(
            to: stagingDirectory.appendingPathComponent("manifest.json"),
            options: .atomic
        )

        if fileManager.fileExists(atPath: slotDirectory.path) {
            _ = try fileManager.replaceItemAt(
                slotDirectory,
                withItemAt: stagingDirectory,
                backupItemName: nil,
                options: []
            )
        } else {
            try fileManager.moveItem(at: stagingDirectory, to: slotDirectory)
        }
        shouldRemoveStaging = false
    }

    private func previousSlotDirectory(name: String) -> URL {
        previousDirectory.appendingPathComponent(name, isDirectory: true)
    }

    private func inspectRollback(name: String) throws -> RollbackInspection {
        _ = try resolveRecipeDirectory(name: name)
        let slotDirectory = previousSlotDirectory(name: name)
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: slotDirectory.path, isDirectory: &isDirectory) else {
            return RollbackInspection(
                availability: .unavailable("没有可回退的上一次导入。"),
                previousPackage: nil
            )
        }
        guard isDirectory.boolValue else {
            return RollbackInspection(
                availability: .stale("上一次导入记录已损坏，不能回退。"),
                previousPackage: nil
            )
        }

        let manifest: IOSRecipePreviousManifest
        do {
            let data = try Data(contentsOf: slotDirectory.appendingPathComponent("manifest.json"))
            manifest = try JSONDecoder().decode(IOSRecipePreviousManifest.self, from: data)
        } catch {
            return RollbackInspection(
                availability: .stale("上一次导入记录已损坏，不能回退。"),
                previousPackage: nil
            )
        }
        guard manifest.schemaVersion == Self.previousManifestSchemaVersion,
              manifest.hashFormatVersion == Self.packageHashFormatVersion,
              manifest.name == name else {
            return RollbackInspection(
                availability: .stale("上一次导入记录版本或 Recipe 名称不匹配。"),
                previousPackage: nil
            )
        }

        let livePackage: IOSRecipePackage
        do {
            guard let installed = try installedPackage(name: name) else {
                return RollbackInspection(
                    availability: .stale("当前 Recipe 已经不存在，不能回退。"),
                    previousPackage: nil
                )
            }
            livePackage = installed
        } catch {
            return RollbackInspection(
                availability: .stale("当前 Recipe 包无法读取，不能回退。"),
                previousPackage: nil
            )
        }
        guard livePackage.hash == manifest.promotedHash else {
            return RollbackInspection(
                availability: .stale("当前 Recipe 已在导入后发生变化，不能回退。"),
                previousPackage: nil
            )
        }

        switch manifest.kind {
        case .new:
            guard manifest.baseHash == nil,
                  !fileManager.fileExists(
                    atPath: slotDirectory.appendingPathComponent("package", isDirectory: true).path
                  ) else {
                return RollbackInspection(
                    availability: .stale("新 Recipe 的回退记录不一致，不能回退。"),
                    previousPackage: nil
                )
            }
            return RollbackInspection(availability: .available(manifest), previousPackage: nil)
        case .update:
            guard let baseHash = manifest.baseHash else {
                return RollbackInspection(
                    availability: .stale("旧 Recipe 包哈希缺失，不能回退。"),
                    previousPackage: nil
                )
            }
            do {
                let previousPackage = try package(
                    at: slotDirectory.appendingPathComponent("package", isDirectory: true),
                    expectedName: name
                )
                guard previousPackage.hash == baseHash else {
                    return RollbackInspection(
                        availability: .stale("旧 Recipe 包已损坏，不能回退。"),
                        previousPackage: nil
                    )
                }
                return RollbackInspection(
                    availability: .available(manifest),
                    previousPackage: previousPackage
                )
            } catch {
                return RollbackInspection(
                    availability: .stale("旧 Recipe 包无法读取，不能回退。"),
                    previousPackage: nil
                )
            }
        }
    }

    private func resolveRecipeDirectory(name: String) throws -> URL {
        guard IOSRecipeNames.isValidRecipeName(name) else {
            throw IOSRecipeFileStoreError.invalidRecipeName
        }
        let root = recipesDirectory.standardizedFileURL
        let directory = root.appendingPathComponent(name, isDirectory: true).standardizedFileURL
        guard directory.deletingLastPathComponent().path == root.path else {
            throw IOSRecipeFileStoreError.invalidRecipeName
        }
        return directory
    }

    private static func normalizedRecipeName(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// Stable, domain-separated hash of the canonical recipe bytes (same
    /// length-prefix style as the skill store's package hash).
    private static func canonicalPackageHash(_ canonicalJSON: Data) -> String {
        var hasher = SHA256()
        hasher.update(data: IOSRecipeLimits.packageHashDomain)
        hasher.update(data: encodedLength(canonicalJSON.count))
        hasher.update(data: canonicalJSON)
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func encodedLength(_ length: Int) -> Data {
        var value = UInt64(length).bigEndian
        return Data(bytes: &value, count: MemoryLayout<UInt64>.size)
    }
}

enum IOSRecipeFileStoreError: LocalizedError, Equatable {
    case invalidRecipeJSON
    case invalidRecipeName
    case recipeNameMismatch(expected: String, actual: String)
    case recipeMissing(String)
    case recipePackageBaseChanged(expected: String?, actual: String?)
    case recipePackageCandidateChanged(expected: String, actual: String)
    case recipeRollbackUnavailable(String)

    var errorDescription: String? {
        switch self {
        case .invalidRecipeJSON:
            "recipe.json 不是合法的 amber.recipe.v1 JSON。"
        case .invalidRecipeName:
            "Recipe 名称必须匹配 ^[a-z][a-z0-9_]{1,31}$。"
        case .recipeNameMismatch(let expected, let actual):
            "Recipe 包名称不匹配（预期 \(expected)，实际 \(actual)）。"
        case .recipeMissing(let name):
            "Recipe \(name) 不存在。"
        case .recipePackageBaseChanged(let expected, let actual):
            "Recipe 已在预览后发生变化（预期 \(expected ?? "不存在")，实际 \(actual ?? "不存在")）。"
        case .recipePackageCandidateChanged(let expected, let actual):
            "候选 Recipe 已在预览后发生变化（预期 \(expected)，实际 \(actual)）。"
        case .recipeRollbackUnavailable(let reason):
            reason
        }
    }
}

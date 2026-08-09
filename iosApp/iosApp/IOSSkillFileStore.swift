import CryptoKit
import Foundation

enum IOSSkillMutationKind: String, Codable, Equatable {
    case new
    case update
}

struct IOSSkillPackage: Equatable {
    let name: String
    let files: [String: Data]
    let hash: String
}

struct IOSSkillPackagePreparation: Equatable {
    let kind: IOSSkillMutationKind
    let base: IOSSkillPackage?
    let candidate: IOSSkillPackage
}

struct IOSSkillPreviousManifest: Codable, Equatable {
    let schemaVersion: Int
    let hashFormatVersion: Int
    let name: String
    let kind: IOSSkillMutationKind
    let baseHash: String?
    let promotedHash: String
    let enabledBefore: Bool
    let optionalSeedWasRemoved: Bool
}

enum IOSSkillPackageApplyOutcome: Equatable {
    case applied
    case unchanged
}

struct IOSSkillPackageApplyReceipt: Equatable {
    let name: String
    let promotedHash: String
    let outcome: IOSSkillPackageApplyOutcome
}

enum IOSSkillRollbackAvailability: Equatable {
    case available(IOSSkillPreviousManifest)
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
                ? "回退后会移除这个新导入的技能。"
                : "可恢复上一次导入前的完整技能包和启用状态。"
        case .unavailable(let reason), .stale(let reason):
            reason
        }
    }
}

struct IOSSkillRollbackReceipt: Equatable {
    let manifest: IOSSkillPreviousManifest
}

struct IOSSkillFileStore {
    // All in-process writers share the CAS critical section, even when callers
    // construct separate value-type store instances for the same directory.
    private static let mutationLock = NSLock()

    private let baseDirectory: URL
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.baseDirectory = baseDirectory
            ?? (try? fileManager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
    }

    var skillsDirectory: URL {
        baseDirectory.appendingPathComponent("skills", isDirectory: true)
    }

    private var previousDirectory: URL {
        skillsDirectory.appendingPathComponent(".previous", isDirectory: true)
    }

    @discardableResult
    func createSkill(name rawName: String, description: String, allowedTools: [String]) throws -> String {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        let name = Self.normalizedSkillName(rawName)
        guard !IOSBuiltinSkills.requiredNames.contains(name) else {
            throw IOSSkillFileStoreError.builtinSkillProtected(name)
        }
        let skillDirectory = try resolveSkillDirectory(name: name)
        guard !fileManager.fileExists(atPath: skillDirectory.path) else {
            throw IOSSkillFileStoreError.skillAlreadyExists(name)
        }

        let trimmedDescription = description.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedDescription.isEmpty else {
            throw IOSSkillFileStoreError.emptyDescription
        }

        try fileManager.createDirectory(at: skillDirectory, withIntermediateDirectories: true)
        let markdown = Self.makeSkillMarkdown(
            name: name,
            description: trimmedDescription,
            allowedTools: allowedTools
        )
        try markdown.write(to: skillDirectory.appendingPathComponent("SKILL.md"), atomically: true, encoding: .utf8)
        return name
    }

    func readSkillMarkdown(dirName: String) throws -> String {
        let directory = try resolveSkillDirectory(name: dirName)
        return try String(contentsOf: directory.appendingPathComponent("SKILL.md"), encoding: .utf8)
    }

    func saveSkillMarkdown(dirName: String, expectedName: String, content: String) throws {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        // Required builtins (e.g. skill-creator) are editable for self-iteration;
        // only create/delete stay protected.
        let directory = try resolveSkillDirectory(name: dirName)
        guard fileManager.fileExists(atPath: directory.path) else {
            throw IOSSkillFileStoreError.skillMissing(dirName)
        }
        let parsedName = Self.frontmatterName(in: content)
        guard parsedName == expectedName else {
            throw IOSSkillFileStoreError.skillNameChanged(expected: expectedName)
        }
        try content.write(to: directory.appendingPathComponent("SKILL.md"), atomically: true, encoding: .utf8)
    }

    func deleteSkill(dirName: String) throws {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        let normalizedName = Self.normalizedSkillName(dirName)
        guard !IOSBuiltinSkills.requiredNames.contains(normalizedName) else {
            throw IOSSkillFileStoreError.builtinSkillProtected(normalizedName)
        }
        let directory = try resolveSkillDirectory(name: dirName)
        guard fileManager.fileExists(atPath: directory.path) else {
            throw IOSSkillFileStoreError.skillMissing(dirName)
        }
        try fileManager.removeItem(at: directory)
    }

    /// Writes a skill package (at least `SKILL.md`) into `skills/<name>/`.
    /// Frontmatter `name` is the authoritative package id. Overwrites an existing
    /// package, including editable required builtins such as `skill-creator`.
    /// `allowBuiltinSkill` is retained for call-site clarity (seed/restore) and is unused for gating.
    /// When `mergeExisting` is true and the package already exists, files not present in
    /// `files` are kept from the previous package (for single-file SKILL.md updates).
    @discardableResult
    func saveSkillFiles(
        files: [String: String],
        allowBuiltinSkill: Bool = false,
        mergeExisting: Bool = false
    ) throws -> String {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        _ = allowBuiltinSkill
        guard let skillMd = files["SKILL.md"] ?? files["skill.md"] else {
            throw IOSSkillFileStoreError.missingSkillMarkdown
        }
        let frontmatter = Self.parseFrontmatter(skillMd)
        guard let declaredName = frontmatter["name"], !declaredName.isEmpty else {
            throw IOSSkillFileStoreError.missingFrontmatterField("name")
        }
        guard let description = frontmatter["description"], !description.isEmpty else {
            throw IOSSkillFileStoreError.missingFrontmatterField("description")
        }
        let packageName = Self.normalizedSkillName(declaredName)
        let skillDirectory = try resolveSkillDirectory(name: packageName)
        try fileManager.createDirectory(at: skillsDirectory, withIntermediateDirectories: true)
        let stagingDirectory = skillsDirectory.appendingPathComponent(
            ".\(packageName)-staging-\(UUID().uuidString)",
            isDirectory: true
        )
        try fileManager.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
        var shouldRemoveStaging = true
        defer {
            if shouldRemoveStaging {
                try? fileManager.removeItem(at: stagingDirectory)
            }
        }
        for (relativePath, content) in files {
            guard !relativePath.contains("\0") else {
                throw IOSSkillFileStoreError.invalidSkillPackagePath(relativePath)
            }
            let clean = relativePath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            guard !clean.isEmpty, !clean.contains("..") else { continue }
            let destination: URL
            if clean.lowercased() == "skill.md" || clean.lowercased().hasSuffix("/skill.md") {
                let parent = clean.contains("/")
                    ? stagingDirectory.appendingPathComponent((clean as NSString).deletingLastPathComponent)
                    : stagingDirectory
                try fileManager.createDirectory(at: parent, withIntermediateDirectories: true)
                destination = parent.appendingPathComponent("SKILL.md")
            } else {
                destination = stagingDirectory.appendingPathComponent(clean)
                try fileManager.createDirectory(
                    at: destination.deletingLastPathComponent(),
                    withIntermediateDirectories: true
                )
            }
            try content.write(to: destination, atomically: true, encoding: .utf8)
        }
        if !fileManager.fileExists(atPath: stagingDirectory.appendingPathComponent("SKILL.md").path) {
            try skillMd.write(
                to: stagingDirectory.appendingPathComponent("SKILL.md"),
                atomically: true,
                encoding: .utf8
            )
        }

        if fileManager.fileExists(atPath: skillDirectory.path) {
            if mergeExisting {
                try copyMissingSkillFiles(from: skillDirectory, into: stagingDirectory)
            }
            // 原子替换：失败或进程强杀时原目录始终在位（replaceItemAt 先把
            // 新目录就位再移除旧的），没有「旧目录已移成 backup 后被杀」的窗口。
            _ = try fileManager.replaceItemAt(
                skillDirectory,
                withItemAt: stagingDirectory,
                backupItemName: nil,
                options: []
            )
            shouldRemoveStaging = false
        } else {
            try fileManager.moveItem(at: stagingDirectory, to: skillDirectory)
            shouldRemoveStaging = false
        }
        _ = description
        return packageName
    }

    /// Builds the exact package that a later approved import would promote.
    /// This method only reads the installed package; it does not create staging
    /// directories, previous slots, or otherwise mutate disk state.
    func prepareSkillPackage(
        importedFiles: [String: Data],
        mergeExisting: Bool
    ) throws -> IOSSkillPackagePreparation {
        let importedPackage = try makePackage(files: importedFiles, expectedName: nil)
        let base = try installedPackage(name: importedPackage.name)
        var candidateFiles = mergeExisting ? (base?.files ?? [:]) : [:]
        for (path, data) in importedPackage.files {
            candidateFiles[path] = data
        }
        let candidate = try makePackage(files: candidateFiles, expectedName: importedPackage.name)
        return IOSSkillPackagePreparation(
            kind: base == nil ? .new : .update,
            base: base,
            candidate: candidate
        )
    }

    /// Promotes a previously previewed package after re-checking both sides of
    /// the compare-and-swap contract. The candidate is fully staged before the
    /// previous slot changes, and synchronous publication failures restore the
    /// prior slot. A process crash between the slot and live renames can still
    /// leave a backup or stale slot; `rollbackAvailability` fails closed there.
    func applySkillPackage(
        candidateFiles: [String: Data],
        name rawName: String,
        expectedBaseHash: String?,
        expectedCandidateHash: String,
        enabledBefore: Bool,
        optionalSeedWasRemoved: Bool
    ) throws -> IOSSkillPackageApplyReceipt {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        let name = Self.normalizedSkillName(rawName)
        let candidate = try makePackage(files: candidateFiles, expectedName: name)
        guard candidate.hash == expectedCandidateHash else {
            throw IOSSkillFileStoreError.skillPackageCandidateChanged(
                expected: expectedCandidateHash,
                actual: candidate.hash
            )
        }

        let base = try installedPackage(name: name)
        guard base?.hash == expectedBaseHash else {
            throw IOSSkillFileStoreError.skillPackageBaseChanged(
                expected: expectedBaseHash,
                actual: base?.hash
            )
        }
        if base?.hash == candidate.hash {
            return IOSSkillPackageApplyReceipt(
                name: name,
                promotedHash: candidate.hash,
                outcome: .unchanged
            )
        }

        let kind: IOSSkillMutationKind = base == nil ? .new : .update
        let manifest = IOSSkillPreviousManifest(
            schemaVersion: Self.previousManifestSchemaVersion,
            hashFormatVersion: Self.packageHashFormatVersion,
            name: name,
            kind: kind,
            baseHash: base?.hash,
            promotedHash: candidate.hash,
            enabledBefore: enabledBefore,
            optionalSeedWasRemoved: optionalSeedWasRemoved
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
        return IOSSkillPackageApplyReceipt(
            name: name,
            promotedHash: candidate.hash,
            outcome: .applied
        )
    }

    func rollbackAvailability(name rawName: String) throws -> IOSSkillRollbackAvailability {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        return try inspectRollback(name: Self.normalizedSkillName(rawName)).availability
    }

    /// Restores the complete package captured by the last update, or removes a
    /// newly imported package. Both the displayed manifest and the live hash are
    /// re-validated here so a newer import cannot replace the confirmed target.
    func rollbackSkillPackage(
        name rawName: String,
        expectedManifest: IOSSkillPreviousManifest
    ) throws -> IOSSkillRollbackReceipt {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        let name = Self.normalizedSkillName(rawName)
        let inspection = try inspectRollback(name: name)
        guard case .available(let manifest) = inspection.availability else {
            throw IOSSkillFileStoreError.skillRollbackUnavailable(inspection.availability.reason)
        }
        guard manifest == expectedManifest else {
            throw IOSSkillFileStoreError.skillRollbackUnavailable(
                "可回退版本已变化，请刷新后重试。"
            )
        }

        switch manifest.kind {
        case .update:
            guard let previousPackage = inspection.previousPackage else {
                throw IOSSkillFileStoreError.skillRollbackUnavailable("上一次技能包不可用。")
            }
            try publishLivePackage(previousPackage)
        case .new:
            let liveDirectory = try resolveSkillDirectory(name: name)
            let discardedDirectory = skillsDirectory.appendingPathComponent(
                ".\(name)-rollback-\(UUID().uuidString)",
                isDirectory: true
            )
            try fileManager.moveItem(at: liveDirectory, to: discardedDirectory)
            try? fileManager.removeItem(at: discardedDirectory)
        }

        // Live state is already complete at this point. Best-effort consumption
        // avoids reporting a failed rollback after the package has changed; if
        // cleanup itself fails, the retained slot is safely classified stale.
        try? fileManager.removeItem(at: previousSlotDirectory(name: name))
        return IOSSkillRollbackReceipt(manifest: manifest)
    }

    private static let previousManifestSchemaVersion = 1
    private static let packageHashFormatVersion = 1
    private static let packageHashDomain = Data("amber.skill.package.v1\0".utf8)

    private struct RollbackInspection {
        let availability: IOSSkillRollbackAvailability
        let previousPackage: IOSSkillPackage?
    }

    private func makePackage(
        files: [String: Data],
        expectedName: String?
    ) throws -> IOSSkillPackage {
        let normalizedFiles = try Self.normalizedPackageFiles(files)
        guard let skillData = normalizedFiles["SKILL.md"] else {
            throw IOSSkillFileStoreError.missingSkillMarkdown
        }
        guard let skillMarkdown = String(data: skillData, encoding: .utf8) else {
            throw IOSSkillFileStoreError.invalidSkillMarkdownEncoding
        }
        let frontmatter = Self.parseFrontmatter(skillMarkdown)
        guard let declaredName = frontmatter["name"], !declaredName.isEmpty else {
            throw IOSSkillFileStoreError.missingFrontmatterField("name")
        }
        guard let description = frontmatter["description"], !description.isEmpty else {
            throw IOSSkillFileStoreError.missingFrontmatterField("description")
        }
        let name = Self.normalizedSkillName(declaredName)
        _ = try resolveSkillDirectory(name: name)
        if let expectedName, name != expectedName {
            throw IOSSkillFileStoreError.skillPackageNameMismatch(
                expected: expectedName,
                actual: name
            )
        }
        return IOSSkillPackage(
            name: name,
            files: normalizedFiles,
            hash: Self.canonicalPackageHash(normalizedFiles)
        )
    }

    private func installedPackage(name: String) throws -> IOSSkillPackage? {
        let directory = try resolveSkillDirectory(name: name)
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(atPath: directory.path, isDirectory: &isDirectory) else {
            return nil
        }
        guard isDirectory.boolValue else {
            throw IOSSkillFileStoreError.invalidSkillPackagePath(name)
        }
        return try package(at: directory, expectedName: name)
    }

    private func package(at directory: URL, expectedName: String) throws -> IOSSkillPackage {
        let rootValues = try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard rootValues.isDirectory == true, rootValues.isSymbolicLink != true else {
            throw IOSSkillFileStoreError.invalidSkillPackagePath(directory.lastPathComponent)
        }

        var enumerationError: Error?
        guard let enumerator = fileManager.enumerator(
            at: directory,
            includingPropertiesForKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey],
            options: [],
            errorHandler: { _, error in
                enumerationError = error
                return false
            }
        ) else {
            throw IOSSkillFileStoreError.invalidSkillPackagePath(directory.lastPathComponent)
        }
        let rootPath = directory.standardizedFileURL.path
        var files: [String: Data] = [:]
        while let item = enumerator.nextObject() as? URL {
            let values = try item.resourceValues(
                forKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey]
            )
            if values.isSymbolicLink == true {
                throw IOSSkillFileStoreError.invalidSkillPackagePath(item.lastPathComponent)
            }
            guard values.isRegularFile == true else { continue }
            let itemPath = item.standardizedFileURL.path
            guard itemPath.hasPrefix(rootPath + "/") else {
                throw IOSSkillFileStoreError.invalidSkillPackagePath(item.lastPathComponent)
            }
            let relativePath = String(itemPath.dropFirst(rootPath.count + 1))
            files[relativePath] = try Data(contentsOf: item)
        }
        if let enumerationError {
            throw enumerationError
        }
        return try makePackage(files: files, expectedName: expectedName)
    }

    private func publishLivePackage(_ package: IOSSkillPackage) throws {
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

    private func stageLivePackage(_ package: IOSSkillPackage) throws -> URL {
        try fileManager.createDirectory(at: skillsDirectory, withIntermediateDirectories: true)
        let stagingDirectory = skillsDirectory.appendingPathComponent(
            ".\(package.name)-staging-\(UUID().uuidString)",
            isDirectory: true
        )
        try fileManager.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
        var shouldRemoveStaging = true
        defer {
            if shouldRemoveStaging {
                try? fileManager.removeItem(at: stagingDirectory)
            }
        }
        try writePackageFiles(package.files, to: stagingDirectory)
        shouldRemoveStaging = false
        return stagingDirectory
    }

    private func promoteStagedLivePackage(_ stagingDirectory: URL, name: String) throws {
        let liveDirectory = try resolveSkillDirectory(name: name)
        if fileManager.fileExists(atPath: liveDirectory.path) {
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
        manifest: IOSSkillPreviousManifest,
        base: IOSSkillPackage?
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
            try writePackageFiles(base.files, to: packageDirectory)
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

    private func writePackageFiles(_ files: [String: Data], to directory: URL) throws {
        let normalizedFiles = try Self.normalizedPackageFiles(files)
        for (relativePath, data) in normalizedFiles {
            let destination = directory.appendingPathComponent(relativePath)
            try fileManager.createDirectory(
                at: destination.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: destination, options: .atomic)
        }
    }

    private func previousSlotDirectory(name: String) -> URL {
        previousDirectory.appendingPathComponent(name, isDirectory: true)
    }

    private func inspectRollback(name: String) throws -> RollbackInspection {
        _ = try resolveSkillDirectory(name: name)
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

        let manifest: IOSSkillPreviousManifest
        do {
            let data = try Data(contentsOf: slotDirectory.appendingPathComponent("manifest.json"))
            manifest = try JSONDecoder().decode(IOSSkillPreviousManifest.self, from: data)
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
                availability: .stale("上一次导入记录版本或技能名称不匹配。"),
                previousPackage: nil
            )
        }

        let livePackage: IOSSkillPackage
        do {
            guard let installed = try installedPackage(name: name) else {
                return RollbackInspection(
                    availability: .stale("当前技能已经不存在，不能回退。"),
                    previousPackage: nil
                )
            }
            livePackage = installed
        } catch {
            return RollbackInspection(
                availability: .stale("当前技能包无法读取，不能回退。"),
                previousPackage: nil
            )
        }
        guard livePackage.hash == manifest.promotedHash else {
            return RollbackInspection(
                availability: .stale("当前技能已在导入后发生变化，不能回退。"),
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
                    availability: .stale("新技能的回退记录不一致，不能回退。"),
                    previousPackage: nil
                )
            }
            if IOSBuiltinSkills.requiredNames.contains(name) {
                return RollbackInspection(
                    availability: .unavailable(
                        "必需技能不能回退到缺失状态；如需重置，请使用恢复出厂。"
                    ),
                    previousPackage: nil
                )
            }
            return RollbackInspection(availability: .available(manifest), previousPackage: nil)
        case .update:
            guard let baseHash = manifest.baseHash else {
                return RollbackInspection(
                    availability: .stale("旧技能包哈希缺失，不能回退。"),
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
                        availability: .stale("旧技能包已损坏，不能回退。"),
                        previousPackage: nil
                    )
                }
                return RollbackInspection(
                    availability: .available(manifest),
                    previousPackage: previousPackage
                )
            } catch {
                return RollbackInspection(
                    availability: .stale("旧技能包无法读取，不能回退。"),
                    previousPackage: nil
                )
            }
        }
    }

    private static func normalizedPackageFiles(_ files: [String: Data]) throws -> [String: Data] {
        var normalizedFiles: [String: Data] = [:]
        var normalizedPaths: [String: String] = [:]
        for (rawPath, data) in files {
            let path = rawPath.precomposedStringWithCanonicalMapping
            guard !path.isEmpty,
                  !path.hasPrefix("/"),
                  !path.contains("\0"),
                  !path.contains("\\") else {
                throw IOSSkillFileStoreError.invalidSkillPackagePath(rawPath)
            }
            let components = path.split(separator: "/", omittingEmptySubsequences: false)
            guard !components.isEmpty,
                  components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else {
                throw IOSSkillFileStoreError.invalidSkillPackagePath(rawPath)
            }
            let joinedPath = components.map(String.init).joined(separator: "/")
            let canonicalPath = joinedPath.lowercased() == "skill.md" ? "SKILL.md" : joinedPath
            let collisionKey = canonicalPath.lowercased()
            if let existingPath = normalizedPaths[collisionKey] {
                throw IOSSkillFileStoreError.duplicateSkillPackagePath(
                    existingPath == canonicalPath ? canonicalPath : "\(existingPath) / \(canonicalPath)"
                )
            }
            for (existingKey, existingPath) in normalizedPaths {
                if collisionKey.hasPrefix(existingKey + "/")
                    || existingKey.hasPrefix(collisionKey + "/") {
                    throw IOSSkillFileStoreError.duplicateSkillPackagePath(
                        "\(existingPath) / \(canonicalPath)"
                    )
                }
            }
            normalizedPaths[collisionKey] = canonicalPath
            normalizedFiles[canonicalPath] = data
        }
        return normalizedFiles
    }

    private static func canonicalPackageHash(_ files: [String: Data]) -> String {
        var hasher = SHA256()
        hasher.update(data: packageHashDomain)
        hasher.update(data: encodedLength(files.count))
        let paths = files.keys.sorted { lhs, rhs in
            lhs.utf8.lexicographicallyPrecedes(rhs.utf8)
        }
        for path in paths {
            let pathData = Data(path.utf8)
            let fileData = files[path] ?? Data()
            hasher.update(data: encodedLength(pathData.count))
            hasher.update(data: pathData)
            hasher.update(data: encodedLength(fileData.count))
            hasher.update(data: fileData)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private static func encodedLength(_ length: Int) -> Data {
        var value = UInt64(length).bigEndian
        return Data(bytes: &value, count: MemoryLayout<UInt64>.size)
    }

    /// Copies files from an existing package that are absent in `destination`.
    private func copyMissingSkillFiles(from source: URL, into destination: URL) throws {
        guard let enumerator = fileManager.enumerator(
            at: source,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles]
        ) else { return }
        let sourceRoot = source.standardizedFileURL.path
        while let item = enumerator.nextObject() as? URL {
            let values = try item.resourceValues(forKeys: [.isRegularFileKey])
            guard values.isRegularFile == true else { continue }
            let itemPath = item.standardizedFileURL.path
            guard itemPath.hasPrefix(sourceRoot + "/") else { continue }
            let relative = String(itemPath.dropFirst(sourceRoot.count + 1))
            guard !relative.isEmpty, !relative.contains("..") else { continue }
            let target = destination.appendingPathComponent(relative)
            guard !fileManager.fileExists(atPath: target.path) else { continue }
            try fileManager.createDirectory(
                at: target.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try fileManager.copyItem(at: item, to: target)
        }
    }

    func skillDirectoryURL(name: String) throws -> URL {
        try resolveSkillDirectory(name: Self.normalizedSkillName(name))
    }

    func containsMcpConfig(name: String) -> Bool {
        guard let directory = try? skillDirectoryURL(name: name) else { return false }
        return fileManager.fileExists(atPath: directory.appendingPathComponent("mcp.json").path)
    }

    func resolveSkillFile(name: String, relativePath: String) throws -> URL {
        let directory = try skillDirectoryURL(name: name)
        let clean = relativePath.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard !clean.isEmpty, !clean.contains("\0"), !clean.contains("..") else {
            throw IOSSkillFileStoreError.invalidSkillName
        }
        let target = directory.appendingPathComponent(clean).standardizedFileURL
        guard target.path.hasPrefix(directory.standardizedFileURL.path + "/")
            || target.path == directory.standardizedFileURL.path else {
            throw IOSSkillFileStoreError.invalidSkillName
        }
        return target
    }

    static func parseFrontmatter(_ content: String) -> [String: String] {
        guard content.hasPrefix("---"),
              let endRange = content.range(
                of: "\n---",
                range: content.index(content.startIndex, offsetBy: 3)..<content.endIndex
              ) else {
            return [:]
        }
        let yaml = String(content[content.index(content.startIndex, offsetBy: 3)..<endRange.lowerBound])
        var frontmatter: [String: String] = [:]
        for line in yaml.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard let separator = trimmed.firstIndex(of: ":") else { continue }
            let key = String(trimmed[..<separator]).trimmingCharacters(in: .whitespaces)
            let value = String(trimmed[trimmed.index(after: separator)...])
                .trimmingCharacters(in: .whitespaces)
                .trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
            if !key.isEmpty {
                frontmatter[key] = value
            }
        }
        return frontmatter
    }

    static func extractBody(from content: String) -> String {
        guard content.hasPrefix("---"),
              let endRange = content.range(
                of: "\n---",
                range: content.index(content.startIndex, offsetBy: 3)..<content.endIndex
              ) else {
            return content
        }
        let bodyStart = endRange.upperBound
        return String(content[bodyStart...]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    static func normalizedSkillName(_ raw: String) -> String {
        raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: " ", with: "-")
    }

    static func allowedToolTokens(from raw: String) -> [String] {
        raw
            .components(separatedBy: CharacterSet(charactersIn: ", \n\t"))
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
    }

    static func makeSkillMarkdown(name: String, description: String, allowedTools: [String]) -> String {
        let toolsLine = allowedTools.isEmpty ? "" : "\nallowed-tools: \(allowedTools.joined(separator: " "))"
        return """
        ---
        name: "\(escapeYaml(name))"
        description: "\(escapeYaml(description))"\(toolsLine)
        ---

        # \(name)

        \(description)
        """
    }

    private func resolveSkillDirectory(name: String) throws -> URL {
        guard !name.isEmpty,
              name != ".",
              name != "..",
              !name.hasPrefix("."),
              !name.contains("/"),
              !name.contains("\0"),
              !name.contains("\\") else {
            throw IOSSkillFileStoreError.invalidSkillName
        }

        let root = skillsDirectory.standardizedFileURL
        let directory = root.appendingPathComponent(name, isDirectory: true).standardizedFileURL
        guard directory.deletingLastPathComponent().path == root.path else {
            throw IOSSkillFileStoreError.invalidSkillName
        }
        return directory
    }

    /// Lists the directory names of every skill that has a SKILL.md on disk.
    /// Used by chat skill-context injection to map enabled skill names → their
    /// markdown bodies. Best-effort: skips unreadable / malformed entries.
    func listSkillDirNames() -> [String] {
        guard let entries = try? fileManager.contentsOfDirectory(at: skillsDirectory, includingPropertiesForKeys: nil) else {
            return []
        }
        var result: [String] = []
        for entry in entries {
            guard !entry.lastPathComponent.hasPrefix(".") else { continue }
            var isDir: ObjCBool = false
            guard fileManager.fileExists(atPath: entry.path, isDirectory: &isDir), isDir.boolValue else { continue }
            let skillMd = entry.appendingPathComponent("SKILL.md")
            if fileManager.fileExists(atPath: skillMd.path) {
                result.append(entry.lastPathComponent)
            }
        }
        return result
    }

    private static func frontmatterName(in content: String) -> String? {
        guard content.hasPrefix("---"),
              let endRange = content.range(of: "\n---", range: content.index(content.startIndex, offsetBy: 3)..<content.endIndex) else {
            return nil
        }
        let yaml = String(content[content.index(content.startIndex, offsetBy: 3)..<endRange.lowerBound])
        for line in yaml.components(separatedBy: .newlines) {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            guard trimmed.hasPrefix("name:") else { continue }
            return trimmed
                .dropFirst("name:".count)
                .trimmingCharacters(in: .whitespaces)
                .trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
        }
        return nil
    }

    private static func escapeYaml(_ value: String) -> String {
        value
            .replacingOccurrences(of: "\\", with: "\\\\")
            .replacingOccurrences(of: "\"", with: "\\\"")
            .replacingOccurrences(of: "\n", with: " ")
    }
}

enum IOSSkillFileStoreError: LocalizedError, Equatable {
    case invalidSkillName
    case emptyDescription
    case skillAlreadyExists(String)
    case builtinSkillProtected(String)
    case skillMissing(String)
    case skillNameChanged(expected: String)
    case missingSkillMarkdown
    case missingFrontmatterField(String)
    case invalidSkillMarkdownEncoding
    case invalidSkillPackagePath(String)
    case duplicateSkillPackagePath(String)
    case skillPackageNameMismatch(expected: String, actual: String)
    case skillPackageBaseChanged(expected: String?, actual: String?)
    case skillPackageCandidateChanged(expected: String, actual: String)
    case skillRollbackUnavailable(String)

    var errorDescription: String? {
        switch self {
        case .invalidSkillName:
            "技能名称不能为空，且不能包含路径分隔符。"
        case .emptyDescription:
            "触发说明不能为空。"
        case .skillAlreadyExists(let name):
            "技能 \(name) 已存在。"
        case .builtinSkillProtected(let name):
            "内置技能 \(name) 不允许删除或通过「新建」重复创建；可以编辑，也可恢复出厂备份。"
        case .skillMissing(let name):
            "技能 \(name) 不存在。"
        case .skillNameChanged(let expected):
            "不允许修改技能名称（name 字段必须为 \(expected)）。"
        case .missingSkillMarkdown:
            "Skill 包缺少 SKILL.md。"
        case .missingFrontmatterField(let field):
            "SKILL.md 缺少 \(field)。"
        case .invalidSkillMarkdownEncoding:
            "SKILL.md 必须是 UTF-8 文本。"
        case .invalidSkillPackagePath(let path):
            "Skill 包含无效文件路径：\(path)。"
        case .duplicateSkillPackagePath(let path):
            "Skill 包含冲突文件路径：\(path)。"
        case .skillPackageNameMismatch(let expected, let actual):
            "Skill 包名称不匹配（预期 \(expected)，实际 \(actual)）。"
        case .skillPackageBaseChanged(let expected, let actual):
            "Skill 已在预览后发生变化（预期 \(expected ?? "不存在")，"
                + "实际 \(actual ?? "不存在")）。"
        case .skillPackageCandidateChanged(let expected, let actual):
            "候选 Skill 已在预览后发生变化（预期 \(expected)，实际 \(actual)）。"
        case .skillRollbackUnavailable(let reason):
            reason
        }
    }
}

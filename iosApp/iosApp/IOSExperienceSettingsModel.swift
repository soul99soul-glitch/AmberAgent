import Foundation
import Observation

// MARK: - Phase 3 Wave 2: 经验管理设置模型（§11.3 / §15 Phase 3 验收 3）
//
// 自进化设置页「经验」区的可观察模型：active 条目列表、建议（supersede/
// delete）批准/拒绝、已归档（superseded/rejected）投影。建议的落地走
// curator 的真实状态机：
// - 批准 delete → `curator.delete`（物理移除 + tombstone 防复读）；
// - 批准 supersede → `curator.update(status: .rejected)`。Wave 1 的建议只
//   携带目标 experience id、没有具体替代条目，因此「降级」落地为 rejected
//   状态（同样永不被注入、保留审计痕迹）；存在具体替代条目时，
//   `curator.supersede(experienceId:newExperienceId:)` 仍是权威路径。
// - 拒绝 → 建议消失（持久化 dismissal），条目保持 active 继续参与注入。
//
// 建议本身不持久化（Wave 1 由 recordHarmful 在跨越阈值时产生），本模型用
// curator.currentSuggestions() 从当前 active 池确定性投影，因此拒绝必须
// 持久化 dismissal 标记，否则重进页面建议会复现。

/// 持久化的建议拒绝标记（小 JSON 边车，`evolution/dismissed-suggestions.json`）。
/// 原子写 + 进程内锁，与 `IOSEvolutionExperienceStore` 同一目录族。
struct IOSExperienceSuggestionDismissalStore {
    private static let mutationLock = NSLock()
    private static let fileName = "dismissed-suggestions.json"

    private let baseDirectory: URL
    private let fileManager: FileManager

    init(baseDirectory: URL? = nil, fileManager: FileManager = .default) {
        self.fileManager = fileManager
        self.baseDirectory = baseDirectory
            ?? (try? fileManager.url(for: .documentDirectory, in: .userDomainMask, appropriateFor: nil, create: true))
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
    }

    private var fileURL: URL {
        baseDirectory.appendingPathComponent("evolution", isDirectory: true)
            .appendingPathComponent(Self.fileName)
    }

    /// 已拒绝的建议键（"\(experienceId)|\(kind.rawValue)"）。缺失/损坏文件
    /// 按空集合处理——拒绝标记丢了最多让建议重新出现，不影响状态机。
    func dismissedKeys() -> Set<String> {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        return Self.loadKeys(from: fileURL)
    }

    func dismiss(_ key: String) {
        Self.mutationLock.lock()
        defer { Self.mutationLock.unlock() }
        // 注意：NSLock 不可重入——这里不能调用 dismissedKeys()（它会上同一把
        // 锁），必须内联无锁读取。
        var keys = Self.loadKeys(from: fileURL)
        guard keys.insert(key).inserted else { return }
        do {
            try fileManager.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.sortedKeys]
            try encoder.encode(keys.sorted()).write(to: fileURL, options: .atomic)
        } catch {
            // Best-effort：持久化失败只影响拒绝记忆，不破坏状态机。
            print("[AmberChat] experience suggestion dismissal write failed key=\(key): \(error)")
        }
    }

    private static func loadKeys(from url: URL) -> Set<String> {
        guard let data = try? Data(contentsOf: url),
              let keys = try? JSONDecoder().decode([String].self, from: data) else {
            return []
        }
        return Set(keys)
    }
}

@MainActor
@Observable
final class IOSExperienceSettingsModel {
    /// 当前 active 条目（按 updatedAt 倒序，稳定展示）。
    private(set) var activeExperiences: [IOSEvolutionExperience] = []
    /// 当前待批准的 supersede/delete 建议（已过滤掉被拒绝的）。
    private(set) var suggestions: [IOSExperienceActionSuggestion] = []
    /// 已归档条目（superseded/rejected，按 updatedAt 倒序）——UI 默认折叠。
    private(set) var archivedExperiences: [IOSEvolutionExperience] = []
    /// 最近一次操作的失败原因（UI 就地展示；nil 表示无）。
    private(set) var lastError: String?

    private let curator: IOSEvolutionExperienceCurator
    private let dismissalStore: IOSExperienceSuggestionDismissalStore

    init(
        curator: IOSEvolutionExperienceCurator,
        dismissalStore: IOSExperienceSuggestionDismissalStore = IOSExperienceSuggestionDismissalStore()
    ) {
        self.curator = curator
        self.dismissalStore = dismissalStore
    }

    /// 生产默认：Documents 目录下的经验池 + 同一目录的 dismissal 边车。
    convenience init() {
        self.init(curator: IOSEvolutionExperienceCurator(store: IOSEvolutionExperienceStore()))
    }

    func reload() {
        lastError = nil
        let all = (try? curator.store.allExperiences()) ?? []
        let dismissed = dismissalStore.dismissedKeys()
        activeExperiences = all
            .filter { $0.status == .active }
            .sorted { $0.updatedAtEpochMs != $1.updatedAtEpochMs ? $0.updatedAtEpochMs > $1.updatedAtEpochMs : $0.id < $1.id }
        archivedExperiences = all
            .filter { $0.status != .active }
            .sorted { $0.updatedAtEpochMs != $1.updatedAtEpochMs ? $0.updatedAtEpochMs > $1.updatedAtEpochMs : $0.id < $1.id }
        suggestions = curator.currentSuggestions().filter {
            !dismissed.contains(Self.dismissalKey(for: $0))
        }
    }

    /// 批准建议 → 真实落库（delete 物理移除 + tombstone；supersede 降级为
    /// rejected），随后重投影。
    func approve(_ suggestion: IOSExperienceActionSuggestion) {
        switch suggestion.kind {
        case .delete:
            switch curator.delete(
                experienceId: suggestion.experienceId,
                reason: "用户批准删除建议（harmful \(suggestion.harmfulCount)）"
            ) {
            case .deleted:
                break
            case .rejected(let error):
                lastError = String(describing: error)
            }
        case .supersede:
            switch curator.update(experienceId: suggestion.experienceId, status: .rejected) {
            case .updated:
                break
            case .rejected(let error):
                lastError = String(describing: error)
            }
        }
        reload()
    }

    /// 拒绝建议 → 持久化 dismissal 标记；条目保持 active。
    func reject(_ suggestion: IOSExperienceActionSuggestion) {
        dismissalStore.dismiss(Self.dismissalKey(for: suggestion))
        reload()
    }

    /// 驳回按经验粒度记忆：用户在 harmful=3 时驳回 supersede 后，升到 5 触发的
    /// delete 建议不再复现——尊重已表达的「别动它」意图（checker 复核结论）。
    static func dismissalKey(for suggestion: IOSExperienceActionSuggestion) -> String {
        suggestion.experienceId
    }
}

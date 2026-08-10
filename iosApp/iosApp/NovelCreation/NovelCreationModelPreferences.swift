import Foundation

final class NovelCreationModelPreferences: @unchecked Sendable {
    static let shared = NovelCreationModelPreferences()

    private enum Keys {
        static let creation = "novel.creation.default-model-policy"
        static let stateSync = "novel.creation.state-sync-model-policy"
        static let review = "novel.creation.review-model-policy"
        /// 剧情同步（stateDelta / stateRebuild）是否允许模型推理。默认 false。
        static let stateSyncReasoningEnabled = "novel.creation.state-sync-reasoning-enabled"
    }

    private let lock = NSLock()
    private let userDefaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
    }

    func policy(for purpose: NovelModelRole) -> NovelProjectModelPolicy {
        lock.withLock {
            guard let data = userDefaults.data(forKey: key(for: purpose)),
                  let policy = try? decoder.decode(NovelProjectModelPolicy.self, from: data) else {
                return .global
            }
            return policy
        }
    }

    func set(_ policy: NovelProjectModelPolicy, for purpose: NovelModelRole) {
        lock.withLock {
            if case .global = policy {
                userDefaults.removeObject(forKey: key(for: purpose))
            } else if let data = try? encoder.encode(policy) {
                userDefaults.set(data, forKey: key(for: purpose))
            }
        }
    }

    /// 剧情同步是否开启推理。默认关闭：DeepSeek Flash 等带 REASONING 能力的模型
    /// 在 automatic 下会先 thinking 再出 JSON，同步明显变慢。
    var stateSyncReasoningEnabled: Bool {
        get {
            lock.withLock {
                // 缺省 false；只有显式写成 true 才开启。
                userDefaults.object(forKey: Keys.stateSyncReasoningEnabled) as? Bool ?? false
            }
        }
        set {
            lock.withLock {
                userDefaults.set(newValue, forKey: Keys.stateSyncReasoningEnabled)
            }
        }
    }

    private func key(for purpose: NovelModelRole) -> String {
        switch purpose {
        case .creation: Keys.creation
        case .stateSync: Keys.stateSync
        case .review: Keys.review
        }
    }
}

import Foundation

final class NovelCreationModelPreferences: @unchecked Sendable {
    static let shared = NovelCreationModelPreferences()

    private enum Keys {
        static let creation = "novel.creation.default-model-policy"
        static let stateSync = "novel.creation.state-sync-model-policy"
        static let review = "novel.creation.review-model-policy"
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

    private func key(for purpose: NovelModelRole) -> String {
        switch purpose {
        case .creation: Keys.creation
        case .stateSync: Keys.stateSync
        case .review: Keys.review
        }
    }
}

import Foundation

@MainActor
enum NovelCreationComposition {
    private static var productionCreation: (any NovelCreation)?

    static func makeViewModel(
        sharedSettings: IOSSharedSettingsStore,
        rootDirectory: URL? = nil
    ) throws -> NovelCreationViewModel {
        if let rootDirectory {
            return NovelCreationViewModel(
                creation: makeCreation(
                    sharedSettings: sharedSettings,
                    rootDirectory: rootDirectory
                )
            )
        }
        if let productionCreation {
            return NovelCreationViewModel(creation: productionCreation)
        }
        let directory = try NovelFileProjectRepository.defaultRootDirectory()
        let creation = makeCreation(
            sharedSettings: sharedSettings,
            rootDirectory: directory
        )
        productionCreation = creation
        return NovelCreationViewModel(creation: creation)
    }

    private static func makeCreation(
        sharedSettings: IOSSharedSettingsStore,
        rootDirectory: URL
    ) -> any NovelCreation {
        let repository = NovelFileProjectRepository(rootDirectory: rootDirectory)
        let modelAdapter = NovelLiveModelAdapter(sharedSettings: sharedSettings)
        return DefaultNovelCreation(
            repository: repository,
            modelRunner: modelAdapter
        )
    }
}

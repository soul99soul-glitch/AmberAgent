import Foundation
import Observation
@preconcurrency import Shared

/// Read-only access to a real, seeded KMP `Settings` snapshot on iOS.
///
/// This is the "real settings read" entry point for iOS. On init it calls the KMP
/// `IosSettingsDefaults.shared.defaultSeededSettings()` — which runs the same
/// `applyBackfillAndSeedShared` + `applyCrossDomainConsistencyShared` passes the
/// Android `SettingsAggregator` applies — to produce a structurally complete,
/// honestly-seeded `Settings` (carrying `DEFAULT_PROVIDERS`, `DEFAULT_TTS_PROVIDERS`,
/// `DEFAULT_ASSISTANTS`, a default `SearchServiceOptions`, a default `DisplaySetting`,
/// etc.) instead of the handful of hardcoded placeholder fields the legacy Swift
/// `SettingsStore` held.
///
/// HONESTY BOUNDARIES (this slice):
/// - This is a READ-ONLY seed snapshot. It is NOT wired to the Android DataStore
///   (iOS and Android are separate apps; on-device settings are not shared), and
///   it is NOT a writable persistence layer — there is no write-back to disk.
/// - Every property below is derived from the live KMP snapshot; nothing here is
///   a Swift hardcoded value.
/// - The snapshot is computed once at init and cached. A future slice can add
///   refresh / write-back; until then, treat all values as seeded defaults.
@Observable
final class IOSSharedSettingsStore {

    /// The underlying KMP `Settings` snapshot (real, seeded, read-only).
    @ObservationIgnored private(set) var snapshot: Settings

    init() {
        self.snapshot = IosSettingsDefaults.shared.defaultSeededSettings()
    }

    // MARK: - Real seeded collections (for UI display)

    /// Real KMP default TTS providers (DEFAULT_TTS_PROVIDERS), seeded + de-duplicated.
    /// E.g. the SystemTTS default plus cloud-provider templates. Read-only.
    var ttsProviders: [TTSProviderSetting] {
        snapshot.ttsProviders
    }

    /// The id of the seeded default TTS provider (DEFAULT_SYSTEM_TTS_ID).
    var selectedTTSProviderId: KotlinUuid {
        snapshot.selectedTTSProviderId
    }

    /// Real KMP default providers (DEFAULT_PROVIDERS), seeded + de-duplicated.
    /// Read-only preset templates (the Provider registry already consumes these).
    var providers: [ProviderSetting] {
        snapshot.providers
    }

    /// Real KMP default assistants (DEFAULT_ASSISTANTS), with branding applied.
    var assistants: [Assistant] {
        snapshot.assistants
    }

    /// Real KMP default search services (a default SearchServiceOptions entry).
    var searchServices: [SearchServiceOptions] {
        snapshot.searchServices
    }

    /// Real default display setting.
    var displaySetting: DisplaySetting {
        snapshot.displaySetting
    }

    /// Real default agent-runtime setting (council/subagent/memory/miniApp/etc.
    /// default config). Read-only display; iOS cannot execute any of these yet.
    var agentRuntime: AgentRuntimeSetting {
        snapshot.agentRuntime
    }

    // MARK: - Scalar model-id pointers (Uuids into providers' models)

    /// Seeded image-generation model pointer (Uuid). NOTE: in the seed snapshot
    /// these modelIds are freshly random Uuids that may not resolve to a real
    /// model in any provider — display as a pointer only, do not assume a label.
    var imageGenerationModelId: KotlinUuid { snapshot.imageGenerationModelId }
    var titleModelId: KotlinUuid { snapshot.titleModelId }
    var suggestionModelId: KotlinUuid { snapshot.suggestionModelId }
    var ocrModelId: KotlinUuid { snapshot.ocrModelId }
    var compressModelId: KotlinUuid { snapshot.compressModelId }
}

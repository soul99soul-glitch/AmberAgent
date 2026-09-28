# E8 frozen contract: task provider identity and truthful setup guidance

Source gate starts only after E7 source/checkpoint. Design: results/E8-task-provider-design.md. Preserve E6 per-run chat fallback and E7 fields/behavior. Task pair uses ProviderSetting.id + ProviderModel.id (configuration UUID), never ProviderModel.modelId (API name). Novel fixed already follows this identity and remains fail-closed; global Chat selection and DeepRead registry keep their existing formats.

## Pure domain and small platform selection support

- Named interfaces TaskModelPair, TaskModelAutoReference(kind='auto'), TaskModelLegacyReference(kind='legacy',modelId), TaskModelFixedReference(kind='fixed',pair); named TaskModelReference union. No inline object union in .ets.
- Decode existing scalar empty/AUTO according to slot semantics, nonempty legacy UUID without writing or guessing provider. User selection writes JSON pair to the same existing scalar key; clear preserves the slot's original AUTO/empty behavior. Malformed new pair reports invalid configuration, does not invent a provider. Keep original UUID helpers for unrelated legacy callers.
- New task-model reference candidate helpers preserve worker/daydream/compress/chat ordering while carrying provider identity. Existing string helpers remain source compatible. Worker snapshots add providerId:string|null and daydreamProviderId:string|null (default null); same existing agent_runtime RMW persists each UUID+provider+follow flag atomically, preserving E7 fields and unknown keys.
- New TaskModelSelectionSupport.ets accepts existing KeyValueStore for read/write and exposes raw configured provider/model lookup from a typed reference. It does not import shared DI or create a second provider factory. Root applies existing effectiveProvider/makeProviderChoice/auth logic.
- Fixed lookup only matches the stated provider container and model UUID; it never searches another provider with the same UUID. Auxiliary task fallback order remains the existing order. OCR/image retain their actual capability gates and absence behavior.

## Ownership after E7 gate

A: new task_model_reference.ts, necessary task_model.ts reference helpers and TaskModelSelectionSupport.ets. Root owns index exports. One necessary legacy/new-pair/duplicate-UUID/clear+candidate focused group only.
B: OcrSupport.ets and ImageGenTool.ets actual pair consumers/health checks; real target is identical during setup and execution, no unrelated provider change.
C: ChatTaskModelsPage.ets and ChatTaskModelPickerPage.ets rows/selection/save/clear/display use provider+UUID, no global UUID substitution; configured unavailable choice remains visible with truthful fallback/absence.
D: ChatProviderSettingsPage.ets and SettingDeepReadPage.ets guidance and current-Chat-to-draft helper; only supported effective OpenAI API-key Chat Completions configuration is copied, never OAuth/SA/Google/Claude credentials or unsupported headers/bodies. Copy fills draft; existing Save persists. Novel shares Chat, DeepRead and council model registry keep their real independent APIs. No first-launch modal or new read-state store.
Root: AppContainer wrapper/resolvers/current-chat references, E6 Dependencies/Auxiliary/remaining ChatPage task consumers, E7 MemoryDreamOps/TopicOps/AgentRuntimePrefs, shared index/route integration. Use named reference DTO, freeze actual exports to owners before consumers.

## Gates

Seven slots title/suggestion/compress/image/ocr/worker/daydream preserve legacy and consume the explicit pair end-to-end. Effective provider override remains applied once by the existing factory. E6 detached rounds retain their actual parent/current chat fallback. Settings and runtime agree on unavailable fixed choices. Core settings backup scope is unchanged; do not claim these keys are already in providers/assistants backup.

Only the one domain/selection group and one affected consumer port group if necessary; no full old suite or repeated device matrix. Root affected lint/types/compiler/sign and independent A logic/B UI-call-chain review. Device C remains explicitly pending while offline. Continue E9 after source gates, preserve all unverified external gates.

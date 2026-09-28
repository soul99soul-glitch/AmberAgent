# E8 OCR/image typed task consumers

Owned source: `OcrSupport.ets`, `ImageGenTool.ets`; one necessary controlled consumer group `chat/src/test/task_model_consumers.test.cjs`. No shared DI/page/domain edits, full suite, app build/sign, or device runs by B.

## Actual consumer chain

- `loadOcrSeed(kv)` reads the original `ocr_model` key through `loadTaskModelReference`; resolves exactly that reference and type `chat` through the existing provider factory. `OcrSeed` keeps `providers/ocrModelId/ocrPrompt` and adds `ocrReference:TaskModelReference`, `ocrChoice:ChatProviderChoice|null`.
- `createEntryOcrTransformer` and `probeEntryOcrHealth(seed):Promise<VisionModelHealth>` consume that same choice directly. Both preserve the IMAGE capability gate. Absent/auto and corrupted legacy UUID preserve unconfigured behavior; malformed new pair is a configuration error. Compatibility `providers` contains only the chosen effective provider and model. Actual current Chat IMAGE capability remains independently provided by `runtime.model` in ConversationRunDependencies and by the actual Chat choice in ChatPage.
- `generateVisionText` now reuses `buildProviderApi` with the already effective choice and the original model-only OCR generation parameters. It keeps the historical OCR behavior concerning model custom headers/body, rather than adding unrelated configuration behavior. Existing Theme Appearance consumer/export remains compatible.
- `resolveImageGenChoice():Promise<ChatProviderChoice|null>` is public. Both `imageGenToolAvailable` and each `generate_image.execute` call read the typed image slot anew, resolve type `image`, and retain the OpenAI/usable-auth gate. Existing create/edit HTTP, image persistence, and file repository paths remain intact.
- Root `ChatProviderChoice.providerContainerId` identifies the original provider container; its effective `provider.id` can be different after model override. UI selection uses the raw reference/container identity, rather than guessing from the effective ID.

## Concrete cache failure and smallest fix

The domain's existing VisionCache key was image URL + configuration UUID + prompt hash. Switching to another provider containing the same UUID returned the first provider's OCR result. In the controlled group, both effective provider IDs were deliberately equal, while the original container IDs and endpoint URLs differed.

`/tmp/e8-consumers-red.log`: actual Entry transformer returned `https://a.example.test/v1` while the selected/healthy target was B. Exact assertion failed at the B transformation, 0/1.

`TargetVisionCache` now prefixes accesses with serialized typed reference + original container ID. It delegates get/put/remove/size to the original singleton VisionCache. The existing file, capacity, expiration, and old cached data are retained; no second store, clear, fallback, or cache schema migration was added. The constructor was subsequently changed only to explicit private fields/assignments for ArkTS compatibility.

## Necessary evidence

Command, cwd `harmony/chat`: `node --import tsx --test src/test/task_model_consumers.test.cjs`.

`/tmp/e8-consumers-green.log`: 1/1 PASS, 377.368 ms process duration. This single group executes actual TaskModelSelectionSupport, OCR seed/transformer/health and ImageGenTool source. SDK I/O delegates to Node temporary files; provider/DI endpoints are controlled, not external service acceptance. It checks same URL/UUID target isolation plus same-target cache reuse, one health and one OCR request per target; image registration/execution after changing the pair; real persisted decoded image bytes/file repository insertion; removed fixed target rejects without using another target. A transient harness error was the SDK `closeSync` host mock accepting only a File object rather than both numeric fd and File; only that mock was corrected before the green result.

`/tmp/e8-consumers-lint.log`: the two owned Entry files, final explicit-field constructor source, exit 0 / `No defects found in your code`. No new SDK API was introduced. App compiler/sign/device evidence remains root-owned and was not claimed from transpilation/lint.

Source hashes at owned source freeze:

- OcrSupport.ets: `7d4d14b22aff572fa90f2d9b97b64a2e005b97f7d8437ca06a6788da31832287`
- ImageGenTool.ets: `97400634fe62a2e62d195127f053154776fb469f7bbdee6f0e64c7f34f9c477d`
- task_model_consumers.test.cjs: `124cb7d965579672df707715d8232d9bd276306aedd2c5f18a143f0151cb2dfd`

Root/C have connected `probeEntryOcrHealth(seed)` to the actual health consumers. Integration compiler and external C device gate remain separate.

## Independent review: legacy slot semantics

The independent review found that the generic typed resolver's legacy search differed from these two historical consumers. B added only the two concrete assertions to the same necessary group, without a new suite:

- Old image UUID search first skips non-OpenAI or unauthenticated raw provider containers, then matches UUID/type. `resolveImageGenChoice` now preserves that order only for `legacy`, converts the selected container into an in-memory fixed reference, and uses the existing provider factory. Fixed pair resolution remains strict.
- OCR's old UUID search takes the first raw UUID match without jumping to another same-UUID container when it lacks auth. Root's auxiliary Chat legacy resolver deliberately skips unauthenticated containers. `loadOcrSeed` now pins the first raw OCR UUID match to its container before calling that factory, avoiding an unintended OCR target change. The persisted scalar and `OcrSeed.ocrReference` remain legacy; no preference migration write is performed.

`/tmp/e8-consumers-legacy-red.log`: before the narrow branches, actual result was `{imageContainer:null,ocrContainer:'legacy-b'}`, expected `{imageContainer:'legacy-b',ocrContainer:null}` for unauthenticated first container A and authenticated B sharing the legacy UUID. The final seed uses one UUID/model per provider at each image/Chat stage, isolating the cross-provider behavior.

`/tmp/e8-consumers-legacy-green.log`: same single group 1/1 PASS, 508.933 ms process duration. `/tmp/e8-consumers-legacy-lint.log`: exactly the two changed Entry files after these branches, exit 0 / `No defects found in your code`. The final hashes above cover this reviewed behavior. This necessary rerun addresses the specific newly found legacy regression; it is not a full old-suite repeat.

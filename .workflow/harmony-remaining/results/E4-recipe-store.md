# E4 RecipeStore 实现与必要验证

2026-09-28，owner：e2_platform_secure。依据冻结 `packets/E4-contract.md`，只改新增 `recipes/ports.ts`、`entry/platform_impl/RecipeStore.ets`、单组实文件夹具及此报告。保留其他 worker 修改；不改 AtomicJsonFile / Skills / DI，不操作设备/build，不改变 E3 C 待验结论。

## 接线与存储

```ts
export const createEntryRecipeStore = async (
  context: common.UIAbilityContext, workspace: PosixWorkspaceManager,
): Promise<RecipeStore>
```

root 可用实际 ability context 与当前 Workspace manager 构造单例。factory 只保存私有 `context.filesDir/recipes` 和 workspace 引用，无初始读写。Agent 与 UI 同用冻结 RecipeStore 接口；ports 同时精确定义 RecipeImportPreview、RecipeExecutionPort、RecipeLoopAdapter，checkpoint 由 model owner 定义。

- 每包 `<name>/recipe.json` 保存 canonical manifest，`enabled.json` 独立保存 JSON boolean。路径 name 严格冻结正则；新包默认 enabled=true，更新不写已有 enabled 状态。
- 候选仅通过 normalizeWorkspacePath + 当前 PosixWorkspaceManager.readBytesCapped(256KiB) 读取，用已有 ArkTS UTF-8 fatal decoder；不会读取任意绝对路径。prepare 解析/validate/hash、读取基线、产生 envelope，全程不创建目录或写文件。
- hash 精确为 SHA256(UTF8(`amber.recipe.package.v1\0`) + 8byte uint64BE canonical UTF-8 字节数 + canonical bytes)。复用仓库已有 cryptoFramework.createMd/updateSync/digestSync 与 TextEncoder；没有新增 native 依赖。
- apply 先复制 name/path/candidateHash/baseHash，在同名 sharedKvMutex 锁内重读候选、使用当前 primitive oracle 重新 validate，再检查 candidateHash/baseHash。变化分别 stale_candidate / stale_base，写前拒绝。实际写入与返回 descriptor 使用同一个重读 canonical 内容。
- 所有 manifest/state 写入复用 writeTextFileAtomic；同一锁保护 list 单包读取、启停、删除。setEnabled/remove 要求 expected_hash；失败 stale_base，不操作文件。新安装写入失败只清理其新包目录并保留原错误；更新失败由已有原子 helper 保留旧 manifest。
- IO/parse 错误直接抛出；现有目录缺 manifest/state、状态不是 boolean、manifest.name 与目录不一致均失败，绝不伪装空库或 quarantine 后回填。Store 本地错误有 code：invalid_recipe / stale_candidate / stale_base / recipe_store_corrupt / invalid_recipe_name。

## 已完成的窄检查

- `ports.ts` 使用继承真实 chat tsconfig 的窄 include 跑 strict tsc --noEmit，exit 0。临时 config `/tmp/e4-recipe-ports-tsconfig.json` 只 include 自有 ports，使用真实 models/tool/message/permission 类型；首次 `/tmp` 的 node typeRoots 解析失败，仅修正临时 config 的 typeRoots 后通过。
- 精确 lint：`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/RecipeStore.ets harmony/chat/src/main/ets/chat/recipes/ports.ts`，exit 0、0 error / 0 warn / 0 suggestion，`/tmp/e4-recipe-store-lint.txt`。随后 name 收敛为全串匹配，防止 JS `$` 接受末尾换行；只重查该 Store，最终仍 exit 0、0 error / 0 warn，`/tmp/e4-recipe-store-final-lint.txt`。首次默认 sandbox 拒绝 Huawei 自身 codelinter.log 写入，原命令必要权限重试后通过，不改变源码或设备。
- 新增 fixture `node --check` exit 0。产品源 Store 139 行、ports 37 行；实文件验证一组，不新增 domain suite。
- 本地 official docs search/read 已查 `@ohos.file.fs`：accessSync 返回 false 仅不存在，抛 IO 错误不可回退空库；rmdirSync 删除目录及所有子项，API 首批9，符合API12。出处缓存 `/tmp/e4-file-fs-official.txt:271,1933`，原文由 `harmony/scripts/devecocli.sh docs read API参考/Core_File_Kit_文件基础服务/ArkTS_API/ohos_file_fs_文件管理_/js-apis-file-fs` 获得。

## 单组实文件验证（通过）

命令：`node --test .workflow/harmony-remaining/fixtures/E4-recipe-store.cjs`。

fixture 直接 transpile 实际 RecipeStore.ets、AtomicJsonFile.ets、domain models/validation、PosixWorkspaceManager，临时私有目录/workspace是真实 Node 文件；仅 Harmony SDK 调用采用 Node file/crypto 等价 shims。primitive 是批准 metadata 夹具，execute 触发会明确抛错，Store 本身不执行 primitive。

一组连续关键检查：Unicode canonical 与未知 schema 字段舍弃、prepare 零写入、初始安装实写、候选变更零写入拒绝、两个同基线 update 在 mutex 下仅一个成功/另一个 stale_base、更新保持 disabled、旧 expected_hash 启停/删除拒绝、原子 rename 失败保留旧 manifest/state并清理tmp、读取失败与坏 JSON不覆盖、正确启用/删除立即变化。

跨 Swift/JS 对一个明确 fixture：中文、emoji、换行、1.5 literal、array/object、字面量路径 `结果/output.json`、未知 schema 字段。从现有 iOS IOSRecipeModels.swift 抽取真实 IOSRecipeManifest decoder/canonicalJSONData，Swift CryptoKit 使用同一 domain + uint64BE 算法，比较 canonical 全字节及 hash。

首个无 slash Unicode fixture 的完整文件/CAS链通过；model owner 指出真实 iOS JSONEncoder 的 slash 逃逸差异后，在同一 fixture 添加上述路径，具体红复现 JS `/` 与 Swift `\/` 字节不等（`/tmp/e4-recipe-store-slash-red.log`）。model owner 仅修 domain canonicalString 的 key/value slash 编码，Store 继续使用唯一 canonical 函数。随后重用此组绿验证，无新增 suite 或排列组合：

```text
node --test .workflow/harmony-remaining/fixtures/E4-recipe-store.cjs
1/1 pass，0 fail / cancelled / skipped，约2.02s，exit 0
canonical UTF8 bytes = 357
Swift/JS SHA256 = fbc71d9558f002fcaba9b9f74de1b5743de7a6d0f13a38f057d59cf78fd55790
```

证据 `/tmp/e4-recipe-store-fixture.log`。上述所有实文件/CAS/生命周期/失败保留断言均走到完成。本 fixture 的跨平台 canonical/hash 全等，不声称任意浮点或 Unicode key排序的通用兼容。

这组 host 实文件验证不等同于 Harmony API12 编译、签名或设备 IO；root 统一构建/设备/产品接线验收分别记证。

## root 统一构建的精准回喂

root 的 `/tmp/e4-release-build.log` 报 Store constructor parameter property 两条 `arkts-no-ctor-prop-decls`。只将 root/workspace 改为显式 private readonly 类字段与 constructor 赋值，行为相同，不改 shared helper。两份自有 entry 文件精确 lint 后 exit0、0error/1warn（warn仅 RecipePageLaunch 原 checkpoint JSON桥接，Store无缺陷），证据 `/tmp/e4-recipe-entry-compile-fix-lint.txt`；diff-check通过。按 root 指令未重跑已通过夹具、未执行build/device；修后的统一编译结论仍由 root 报告。

# E5-B Plugin Store / Archive

状态：平台实现与一组必要宿主实文件验证完成。独立审查、统一 Harmony 构建和原生 API 验收由 root 收口；不声明设备或原生 CryptoFramework 验签已通过。

## 接线与实现

- `createEntryPluginStore(context: common.UIAbilityContext, primitives: () => Promise<AgentTool[]>): Promise<PluginStore>`。factory 不读取 catalogue；`listInstalled(primitives?)` 优先使用当前轮真实 oracle，中心/冷启动才调用异步默认助手 provider。工作区复用 `WorkspaceStore.workspaceRootAbs()`，私有根为 `filesDir/plugins`。
- `createEntryPluginArchive(): PluginArchivePort`，由 Store 内部使用。归档为真实 `amber.plugin.archive.v1` JSON/Base64，导出返回真实 `Uint8Array`；签名信息随验证后的字节保留。
- 工作区读取逐级 `lstat`，拒绝链接和非普通文件；逐目录 `listNum` 有界枚举。固定 32 文件、256 KiB/文件、1 MiB/包；walk 额外限制 256 条目、32 层，避免空目录和深链无界遍历。读取检查 stat 大小并最多读上限 + 1 字节，读后再次检查。
- Domain 负责 manifest/Recipe canonical 与 domain + uint64BE 包哈希。prepare 无写；apply 在同 id mutex 下重新读取来源，校验固定 candidate hash/trust/permission diff 和 base hash。
- 完整 package 写入 UUID staging，关闭所有 fd 后 rename 成内容 hash 目录；一个原子 `current.json` 同时发布 hash、enabled、trust、一个 previous slot。默认导入关闭，`enable=true` 固定在 preview；权限差异列出五个 capability 集合和 primitive union，publisher 身份降低/更换也列为变化。Store 不伪造用户审批。
- enable/remove/rollback/restore/export 均使用 expected hash。rollback 重新校验上一版、权限和签名，消费唯一槽；坏 live 包恢复时关闭恢复包。严格有效 pointer 的 hash 可用于删除损坏包，坏 pointer 不产生假 hash。无效包返回明确 invalid card，实际 FS I/O 拒绝，不回退空库覆盖。
- current 与 previous 以外 hash 目录在发布后尽力清理；指针写失败保留旧 live，可能留下不可达 hash 目录，后续成功发布清理。cleanup 不改发布结果。
- health 按 id/hash 隔离，三次连续真实 fault 隔离；成功重置连续次数；最多 50 diagnostic、detail 500 Unicode 字符。健康写失败保留当前进程内的隔离状态并抛真实错误。旧 pinned hash 在更新/删除后完成时返回旧 cache/healthy，不写新 hash、不复活目录。candidate test 和哪些错误计 fault 由 runner 决定。
- raw 32-byte Ed25519 公钥，signature 覆盖 `amber.plugin.signature.v1\0` + package hash hex；keyId 为公钥 SHA256 前 16 hex，可信管理使用完整 SHA256 fingerprint。签名有效与 publisherTrusted 分开，不自动授权。所有 key、signature 的 Base64 解码前检查预算/编码，归档拒重复路径并限 2 MiB。

## 证据

`PluginStore.ets` 433 行，`PluginArchive.ets` 155 行；未修改 DI、index、其他 owner 的 domain 文件、AtomicJsonFile 或设备入口。

唯一 fixture：`.workflow/harmony-remaining/fixtures/E5-plugin-store.cjs`。真实 Store/Archive/AtomicJsonFile/domain source 在 Node 转译加载，实际临时文件；SDK file/crypto 使用 Node 等价实现。stage writeSync 固定注入 7-byte short write，所有后续包读取、hash、签名 export 检查写全。

- 1/1 PASS，`/tmp/e5-store-fixture.log`。
- 使用实际 iOS `IOSRecipeManifest`、`IOSPluginManifest`、`IOSPluginJSONSchema` 与 CryptoKit 重建相同多文件包。含 Unicode/slash、默认字段、Recipe、原始资产、`汉`/`😀`/`U+E000` 路径及 enum 对象 keys。canonical plugin JSON **774 bytes**，Swift/Store hash 均 **`7fb48ff8186653bafd7179e5b5dd66ed1a40825619f3082e8bb20c97c8afeccb`**。
- 同一组验证：prepare 无写；审批后改源不写；两个同 base 并发提交仅一个成功；旧 hash lifecycle 拒绝；current pointer 发布失败保留旧包；一槽 rollback；真实 archive export/signature 保留；raw key fingerprint add/remove；签名 tamper、重复路径、2 MiB 超额、链接和 33+ 文件拒绝；实际缺 oracle invalid card/FS I/O 抛出；隔离、恢复、旧 pinned health 不污染新 hash；损坏包保留 currentHash 并可 CAS 删除。
- 唯一 cross-Swift fixture 先发现 UTF16/scalar 路径顺序差异（`/tmp/e5-store-unicode-sort.log`），再发现 JSON enum key 顺序差异；domain owner 精准修 scalar comparator 与 private Recipe canonical。沿用同一 fixture 修后通过；Store 不另造 canonical。
- 两 entry 文件精确 CodeLinter：**0 error / 0 warning**，`/tmp/e5-store-lint.txt`。
- 仅两个 entry source 的窄 strict TS 检查：**0**，使用本机实际 SDK kit/API `.d.ts`、真实 domain types；共享 helper 只声明其现有签名。`/tmp/e5-store-types/tsconfig.json`、`/tmp/e5-store-types.log`。这不替代 ArkTS 构建。
- `git diff --check` 指定本 owner 文件通过。未运行全 suite、build、签名、安装、设备或模型调用。

独立 reviewer 精准回喂：有效 current pointer 的包 hash 目录缺失曾使整个列表以 13900002 拒绝。`packageFor` 仅把已指向的 `packages` / hash 目录首个 lstat 的 **13900002** 包装为 **missing_package**；权限、其他 I/O 和成员读取错误仍抛原始错误。现有唯一 fixture 增加 `E5_STORE_CHECK=missing-package` 受影响分支，真实安装后删 hash 目录，验证 invalid card 保留 currentHash / configuredEnabled、enabled=false，并能按原 hash CAS 删除。

该点修前 `/tmp/e5-store-missing-package-red.log`，修后 `/tmp/e5-store-missing-package.log` **1/1 PASS**；未重跑原完整已绿组。修后仅 Store 单文件 lint **0 error / 0 warning**，`/tmp/e5-store-missing-package-lint.txt`。

统一 ArkTS 编译回喂 `/tmp/e5-release-build.log` 的三处 structural typing：重复的本地 `PluginArchiveSignature` 与冻结的 domain `PluginSignature` 不具名义兼容性。Archive 的 envelope、verify、encode 直接复用冻结 `PluginSignature`，使 Store 调用也传同一接口；删除重复接口，不增加任意 cast，行为不变。修后仅 Archive 单文件 lint **0 error / 0 warning**，`/tmp/e5-store-compile-fix-lint.txt`；未重跑 FS fixture、types、build 或设备。`pluginPathCompare` 的 index export 由 root 修复，统一构建结论由 root 后续确认。

## 官方 API 核对

使用 `harmony/scripts/devecocli.sh docs search/read`：`js-apis-file-fs`（lstat、listFile/ListFileOptions、read/write/open/close/mkdir/rename），`errorcode-filemanagement`（仅 13900002 为 missing），`js-apis-cryptoframework`（ED25519PubKeySpec API 11、positive big-endian bigint、generatePubKey/Verify init/verify），以及 Ed25519 官方 FAQ。文档缓存：`/tmp/e5-fs-official.txt`、`/tmp/e5-fs-errors-official.txt`、`/tmp/e5-crypto-official.txt`。接口均在 API 12 基线内。

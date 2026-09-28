# E5 包、Schema、Store 独立审查

2026-09-28。结论：**PASS；本审查范围内无开放 P1/P2**。两个查实问题均已由对应 owner 精准修复，最终源码与受影响证据已复核。

本次只读审查 `chat/plugins/{models,ports,json_schema,validation}.ts`、`platform_impl/{PluginStore,PluginArchive}.ets`，对照 E5-contract、E5-plugin-design 与实际 iOS model/schema/file-store 语义。源实现分别属于 E5-A / E5-B owners；reviewer 仅写本报告，未改源、执行测试、构建或操作设备。已遵守此前加载的 harmony-arkts skill；未扩大到 backend 执行或 UI 验收。

## 源码闭环

- **包身份与预算**：包路径规范化/NFC，拒绝隐藏成员、绝对路径、反斜杠、控制字符、穿越与孤立代理字符；平台逐级 lstat 拒绝链接。32 文件、256 KiB/文件、1 MiB/包，归档 2 MiB；目录 walk 额外有界枚举与层数限制，读取检查大小并以预算 + 1 有界读，canonical 后再次检查预算。manifest 与私有 Recipe 重建 canonical，脚本/资产保持原字节；包哈希覆盖 domain、Unicode scalar 排序的 UTF-8 路径、uint64BE 长度及**所有文件全部字节**，不只覆盖 manifest。
- **四种 handler 与真实 primitive**：每个工具严格只有 Recipe / JavaScript / command / remote 一种。私有 Recipe 校验并派生 primitive 和包络；JavaScript 宿主名称使用同一真实白名单、拒重复；command 必须明确 embedded_python 能力并绑定已有 python_execute；MCP 按真实 mcpTarget 的 serverId/toolName 匹配，OpenAPI 固定 HTTPS URL/域/method 且需要当前 http_request。缺失实际 primitive 返回校验错误/invalid card，不能借插件绕过助手目录。模型/ports 与冻结 DTO 一致，没有新增平台句柄或另造 checkpoint。
- **Schema 与调用输入**：最小支持词汇逐层验证，未知关键词/坏 schema 拒绝；required 检查 own-key，嵌套对象/数组、整数、null、有限数值、enum 深比较统一。旧三类型输入全部必需且拒额外字段；manifest 及 input_schema.properties 均拒绝宿主保留名 display_title。输出先检查声明的 coarse 类型，再检查 output_schema，失败不能被转成成功值。实际缺全局 URL 的 Harmony 兼容修复消费 broker 唯一纯字符串 hostname parser，未引入新的 SDK 依赖。
- **发布与生命周期**：prepare 无写；apply 在同 id mutex 内重读来源，校验 candidate hash、base hash、enable 意图、trust 与 permission diff；完整 staging 写全并关闭 fd 后 rename，单原子 current.json 同时发布 hash/enabled/trust/唯一 previous slot。审批后来源变化或旧 base 不写；enable/remove/rollback/restore/export 使用 expected hash。rollback 重新验证上一版、签名与权限，消费槽；权限扩张或 live 包坏时恢复包关闭。pointer 失败保留旧 live；不可达 hash 目录可在后续成功发布后清理，不把 cleanup 失败误报成已发布失败。
- **签名与健康状态**：归档 Base64 先检查编码/预算再解码，并验证 round-trip；raw 32-byte Ed25519 公钥、64-byte signature，签名消息为 `amber.plugin.signature.v1\0` + package hash hex。keyId 是公钥 SHA256 前 16 hex，可信管理使用完整 fingerprint；signed 与 publisherTrusted 分开，可信发布者不自动授权。health 按 id/hash 隔离，三个连续真实 fault 隔离；成功重置计数，恢复需 current hash；旧 pinned 执行完成不写新 hash、不复活删除目录。diagnostic 数量与 Unicode detail 长度有界，健康写失败保留当前进程内隔离并抛真实错误。
- **坏包与 I/O**：有效 pointer 可定位的坏包返回 currentHash 保留、actual enabled=false 的 invalid card，并可按该 hash 删除/恢复。实际目录权限、成员读取和索引 I/O 继续拒绝，不吞成空列表；missing package 的处理仅限已指向包首目录缺失。

## 查实问题与闭环

1. **P2：两个不同孤立 surrogate 路径产生相同 UTF-8 路径字节。** 原路径谓词允许 `assets/\ud800` 与 `assets/\ud801`，既有编码器均产生 U+FFFD，导致路径身份别名。E5-A 在包路径及 Workspace prefix 校验复用三行 scalar 检查，拒绝孤立代理值并保留成对 emoji。reviewer 已核最终源码。owner 的 `/private/tmp/E5-surrogate-regression.mts` 精确重建原谓词并同场调用真实新谓词，日志 `/private/tmp/E5-surrogate-regression.log` 证明 before 两路径 admitted/字节相同，after 两路径 rejected/emoji allowed；没有将该证据描述成旧完整测试先失败。
2. **P2：有效 current pointer 引用的包目录缺失导致整个 catalogue 失败。** 原 `packageFor` 首目录 lstat 的 13900002 直接越过 listInstalled 的 domain-error 分支，阻断可定位坏包的删除。E5-B 仅在已指向 `packages` / hash 目录首检查把 13900002 转为 `PluginStoreError('missing_package')`，其余错误不扩大 catch。reviewer 已核最终源码及现有同一 fixture 的受影响分支：真实安装后删除 current hash 目录，返回 package=null/currentHash 原值/configuredEnabled 保留/actual disabled，并同 hash CAS 删除成功。`/tmp/e5-store-missing-package-red.log` 是实际旧源 ENOENT/13900002 失败，`/tmp/e5-store-missing-package.log` 为 **1/1 PASS**；只重跑该分支，未重跑原完整绿组。修后 Store 单文件 lint **0 error / 0 warning**，`/tmp/e5-store-missing-package-lint.txt`。

## 既有必要证据与边界

已阅读 owner 自检与 fixture 源码/日志，未重复执行：

- E5-A `/private/tmp/E5-model-schema-check.mts` 六个必要组由模型 owner 执行通过；覆盖 canonical/default/content identity、path/UTF-8/budget、deep schema/null/enum、handlers/host capabilities、固定 Python/MCP/HTTP 与私有 Recipe。最终 owned strict types 通过；全局 URL 删除仅由 broker owner 跑 affected fixture，模型最终 validation lint **0 error / 537 warning**。跨 TS import 的 CodeLinter unsafe 告警不等于构建完成，本报告不将其报成 0 warning。
- E5-B 唯一 `.workflow/harmony-remaining/fixtures/E5-plugin-store.cjs` 真实转译加载 Store/Archive/AtomicJsonFile/domain 源，实际临时文件，SDK file/crypto 为 Node 等价实现。原完整组 `/tmp/e5-store-fixture.log` **1/1 PASS**：包含 7-byte short write、审批后改源、并发双 CAS、pointer 发布失败、一槽 rollback、真实 archive/signature 与信任管理、链接/预算/tamper 拒绝、缺 oracle、真实 I/O、health 隔离及坏包 hash 保留。
- 同一组使用实际 iOS `IOSRecipeManifest`、`IOSPluginManifest`、`IOSPluginJSONSchema` 与 CryptoKit 生成对应包；Unicode/slash、emoji/私用区路径、enum Unicode keys、私有 Recipe 和原始资产的 canonical plugin JSON 为 **774 bytes**，Swift/Store 包 hash 均为 **`7fb48ff8186653bafd7179e5b5dd66ed1a40825619f3082e8bb20c97c8afeccb`**。此证据仅证明该真实 fixture 的跨端一致，不外推全部 Double 编码。
- 两平台源原精确 lint `/tmp/e5-store-lint.txt` **0 error / 0 warning**；窄 strict TS 使用实际 SDK `.d.ts` 与实际 domain types，owner 报告 exit 0，`/tmp/e5-store-types/tsconfig.json` / `/tmp/e5-store-types.log`。独立审查核对了原生 CryptoFramework Ed25519 key spec 的 positive big-endian bigint 与 API 11 基线说明，接口位于 API 12 内。

宿主 Node 等价 Crypto fixture、类型检查和源码审查不等于设备原生 CryptoFramework 验签验收；本报告不声明 Harmony build、签名安装、设备 UI、JSVM 原生执行、真实四 backend 调用、完整审批/工具循环产品验收通过。这些由其余 owners / root 的 E5 整合证据单独收口。

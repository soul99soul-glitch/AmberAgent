# Phase2 备份独立复审

最终结论：通过。初次复审发现的两个真实 DAV 缺口已纳入本 phase 修复并独立复核；最新56个聚焦测试全部通过。没有发现剩余阻塞问题。审查者未改 production 源码。

## 已实现修复核查

- Uint8Array body 在域客户端中保持二进制；entry RCP adapter 对 view 使用 `slice().buffer`，仅上传 byteOffset/byteLength 覆盖的内容。下载直接 ArrayBuffer→Uint8Array，不做文本解码。所有0–255字节及带前后额外容量的 subarray 测试覆盖两个边界。
- list 正式使用 PROPFIND Depth1，仅接受207；MKCOL 使用真实方法，405保留已有目录，409明确停止，不递归建目录、无重试。response与propstat按成功属性分组，404缺失属性不删除同响应已有成功属性。
- 新 port 只解决 DAV custom method/binary 契约，没有扩大 LLM HttpClient；index 追加请求/响应/transport类型导出。与并行 provider 修复的index改动分属独立职责。
- RCP官方离线文档 `/tmp/amber-review-rcp-docs.md` 明确 Request constructor、fetch、HttpMethod含非空自定义字符串、RequestContent RawDataContent和Response ArrayBuffer 均适用于基线API12。本 adapter 未使用高版本 sendable/queue特性，finally关闭session，没有TLS验证放宽。
- packBackupBytes 在 list/getById 读取结束后检查 chatStorageDegraded，覆盖首次lazy init故障；只有导出conversations时启用，settings-only不误拦截。
- 两个小helper覆盖本地export、远端download落盘、本地restore preview与restore四条FD路径：open成功后stat/read/write/分配都处于try-finally，实际byte count不匹配明确失败且不进入inspect/restore。短写失败不宣称成功，没有为局部文件IO添加循环重试抽象。

## 首次独立测试

按phase2-backup.md两个命令实跑：DAV14 + snapshot13 + entryIO18 + RCP adapter4 =49/49 PASS，0 fail/skip/todo。

## 初次发现的两条窄反例

### DAV03 数字XML字符引用未解码

当前 `sync_webdav.ts` 的 xmlText只替换5个预定义实体。合法响应href `/backups/a&#38;b.abin` 通过list产生name=`a&#38;b.abin`，随后页面按该name下载，GET成为`/backups/a%26%2338%3Bb.abin`，与真实`a&b.abin`资源不同。

`/tmp/amber-phase2-backup-review-repro.ts` 实跑当前client确认上述错误路径；Python标准XML解析器对完全相同XML返回`/backups/a&b.abin`。数字字符引用为XML1.0标准语法，见[W3C XML1.0 §4.1](https://www.w3.org/TR/xml/#sec-references)。修复可在现有xmlText中一次替换命名+十进制+十六进制引用，不需要XML框架或额外server fallback。

### DAV04 Basic凭据的非BMP字符UTF8编码错误

现有 `btoaUtf8` 对UTF16 surrogate分开编码。配置用户名u、密码🔑时真实client发送`Basic dTrtoL3ttJE=`，正确UTF8的Base64为`Basic dTrwn5SR`。用户可直接在当前设置页输入该密码；要求UTF8凭据的DAV服务器将其视为错误密码。ASCII/中文BMP已有编码不暴露此缺陷。

该函数既有缺陷被此次全面复审识别，不是新adapter引入。复现使用真实client记录Authorization并与标准UTF8 Buffer对照；RFC7617说明charset=UTF-8的Basic凭据编码，见[RFC7617 §2.1](https://www.rfc-editor.org/rfc/rfc7617.html#section-2.1)。最小修复复用现有utf8Encode，不改变认证方式或添加fallback。

## 验证界限

协议/byte/session/FD测试使用受控transport与SDK宿主，没有连接实际DAV服务。不能从mock测试证明DNS、TLS、认证成功或某个云端服务器兼容性；本次也不要求新增多服务兼容框架。设备ArkTS构建/lint由主agent集成阶段验证。


## DAV03/04 补齐后的最终独立复核

- xmlText单个replace同时解码named、decimal和hex引用，替换结果不再次进入扫描。`a&#38;b.abin`/`a&#x26;b.abin`还原`a&b.abin`；`a&amp;#38;b.abin`保留真实字面`a&#38;b.abin`，不会过度解码。
- 仓库现有utf8Encode仅是sync_snapshot/workspace_artifacts私有函数，zip_archive无encoder。实施采用更窄的既有btoaUtf8循环补paired surrogate→4字节、unpaired→U+FFFD；没有为复用而新增公共编码API。ASCII/BMP路径保持，新增emoji、中文混合和两个unpaired测试用标准TextEncoder bytes oracle验证。
- 重新执行原两个聚焦命令：DAV21 + snapshot13 + entryIO18 + adapter4 =56/56 PASS，0 fail/skip/todo。最初49个测试与断言保留，新增7个回归没有跳过。
- 独立复跑原 `/tmp/amber-phase2-backup-review-repro.ts`：filename现为`a&b.abin`，GET现为`https://dav.test/backups/a%26b.abin`，Authorization现为`Basic dTrwn5SR`，与标准UTF8相同。

上述补齐不影响MKCOL/PROPFIND status处理、byte view复制、degraded门或FD helper；无XML框架、认证fallback、自动重试或额外云端行为。

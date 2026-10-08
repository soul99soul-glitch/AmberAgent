# Phase 2：WebDAV 与备份 IO

范围：`chat/sync_webdav.ts`、`SettingBackupPage.ets`、新 `WebDavTransport.ets`、相关聚焦测试与必要域类型导出。保留工作区既有改动，未操作外部 DAV 服务。

## 已二次确认的真实问题

| 问题 | 实际影响 | 红复现 |
|---|---|---|
| DAV 使用 GET / PUT 替代 PROPFIND / MKCOL | 合规服务器不能列远程快照；创建目录发送错误方法且忽略父目录不存在 | 实际 client 方法检查；PROPFIND 401 返回空成功；MKCOL 被观察为 PUT |
| 二进制备份经过文本 HttpClient | 0x80–0xFF 在上传 UTF-8 编码、下载文本解码时损坏，ASCII-only 旧测试掩盖损坏 | 全 0–255 bytes、真实 Uint8Array subarray 测试拒绝 binary string |
| DAV 文件名缺 URI / XML 处理 | namespace 非 d 前缀不能识别；编码名没有解码显示；空格/#/%/中文名下载错误资源 | 任意前缀、百分号、实体、collection 用例 |
| 备份文件短写被报成功 | 128 bytes 实际写 3 bytes 仍“已导出” | 执行真实 doExport，writeSync 返回 3 |
| 备份 IO 异常不释放 fd | 文件读写或 stat 失败泄漏文件描述符 | 执行真实 doExport / doPickRestore / doRestore，异常后 close=0 |
| degraded 数据库被成功导出为空会话 | 不完整的内存回退会被当作全量备份 | AppStorage chatStorageDegraded=true，真实 packBackupBytes 仍执行 createSyncBackup |

初始红结果：DAV 11 项中 6 fail；entry IO 8 项中 7 fail。短读测试也直接证明 page 忽略 readSync 实际返回字节数，仍继续 inspect/restore。

## 复核排除和设计边界

- MKCOL 405 表示资源已存在，保留这个正常幂等结果；409 表示父路径不存在，不能忽略。
- 专用 DAV binary/custom-method port 服务明确现有入口，不扩展 LLM HttpClient，不添加 fallback / 自动重试 / 递归建目录。
- 旧 RCP 实现曾记载部分设备默认 DNS 127.0.x.x 解析失败。这一轮可验证 API/byte/session 契约，不能据本地 mock 声称真实服务器 DNS/TLS/认证验收通过。
- 备份不包含 managed attachments 是现有归档边界，本次没有扩大归档格式。

## 实施与验证

已实现：

- `WebDavTransport` 是 28 行 API 12 RCP adapter，以 `ArrayBuffer` 请求/响应保持 bytes；`finally` 关闭 session，保留默认 TLS 验证。
- client 发送真实 `PROPFIND` / `MKCOL`，列目录只接受 DAV `207`，错误状态显式失败；按 response / 成功 propstat 提取文件信息，接受不同 DAV 前缀和 XML 实体，percent-decoded filename 重新编码请求。
- 上传先创建一次配置目标目录，已存在的 `405` 继续，父路径缺失 `409` 停止；没有递归、重试或 fallback。
- 两个 page 文件 helper 覆盖四条 IO 路径，检查一次实际 byte count，短读写明确失败，异常均 `finally close`。
- conversation backup 读取全部会话后检查既有 degraded flag，覆盖 lazy init 故障；settings-only backup 保持可用。

验证命令：

```sh
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json --test harmony/chat/src/test/sync_webdav.test.ts harmony/chat/src/test/sync_snapshot.test.ts
node --test harmony/entry/src/test/backup_io.test.cjs harmony/entry/src/test/webdav_transport.test.cjs
```

绿结果：领域 DAV 14/14 + snapshot 13/13；真实 page 方法 IO/上传/下载 18/18 + 真实 RCP adapter 4/4，共 **49/49**。覆盖 0–255 全 bytes、subarray 边界、DAV 401/409/405/HTML-200、混合成功/缺失属性 propstat、所有四条 IO 的异常关闭和短字节数、lazy degrade、settings-only、创建目标目录顺序与无重试。

`git diff --check` 通过。统一 ArkTS lint、构建、设备验证与独立 subagent 复审由主 agent 继续执行。测试未连接实际 DAV 服务器；真实认证、TLS、DNS、服务器兼容性不能算已通过。

## 独立复审回喂：DAV03 / DAV04

reviewer 真实 client 探针确认合法 numeric XML entity `a&#38;b.abin` 会被当成字面文件名，以及 Basic 密码 `🔑` 被按两段 surrogate 编码，实际 `dTrtoL3ttJE=` 而标准 UTF-8 为 `dTrwn5SR`。

窄补仅 `sync_webdav.ts` 与既有测试：XML named / decimal / hexadecimal entities 在一次 replace 中解码，`&amp;#38;` 保持字面 `&#38;` 避免二次解释；既有 UTF-8 编码循环补完整 surrogate pair 的 4-byte 编码与标准 U+FFFD 非配对语义。没有新增通用 XML parser 或公共 encoder API（`zip_archive` 仅已有 decoder）。

新增 7 项测试，修改前 **6 fail / 1 pass**，修后 **7/7 pass**。全聚焦范围现为 DAV21 + snapshot13 + page18 + adapter4 = **56/56**，`git diff --check` 通过。认证测试 oracle 使用真实 `TextEncoder` 字节。等待 reviewer 再复跑。

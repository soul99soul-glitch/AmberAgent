# P9 小说成果文件与恢复链独立核验

2026-10-03；文件链 reviewer；生产文件只读。已读取根 AGENTS.md、`.agents/skills/harmony-arkts/SKILL.md` 与 P9 计划。未构建、签名、安装或操作设备，未改动生产代码。已独立核验真实系统导出字节、设备实际恢复目录与原项目逐字保留、冷启动后 native 6 章计数及真实目录重读；确认的一项 P3 保存回执错误文件名已由 root 修复，并经新包真实改名保存截图和取回字节验证关闭。

## 实际入口与数据链

| 环节 | 实际入口／代码 | 观察到的行为 |
| --- | --- | --- |
| 导出菜单 | `NovelWorkspacePage.ets:2930` 项目控制→导出作品；`:3626` 顶栏 accessibilityText=导出 | `showExportMenu()` 列出公开工作区 ZIP、完整备份 ZIP、正文 TXT／Markdown／EPUB |
| 导出类型 | `NovelWorkspacePage.ets:1317` → `NovelWorkspaceService.ets:114` | 索引 0 调 `exportWorkspaceZip`，1 调 `exportNativeBackupZip`，2 调 `exportBook` 的 TXT |
| 系统保存 | `NovelWorkspacePage.ets:1295` → `:1260` | DocumentViewPicker.save 的第一条 URI 用 READ_WRITE\|CREATE\|TRUNC 打开；写入 Uint8Array 的准确 byteOffset/byteLength 切片；finally 关闭。取消选择不写文件 |
| 项目导入 | `NovelProjectsPage.ets:269`；`:655`／`:659`／`:712` 可达按钮 | DocumentViewPicker.select 筛 `.zip`，最多一个；读真实 URI，16 MiB 入口上限；`inspectImport` 完成后才显示预览 |
| 格式识别／预览 | `NovelWorkspaceService.ets:65` | ZIP 包含 `native-backup.json` 走原生解析与完整状态树校验，否则走公开解析与分支计划；collision 由 project ID 的 live/trash/deleted 目录决定，不由显示名称决定 |
| 确认／保留两份 | `NovelProjectsPage.ets:320`／`:489`；`NovelWorkspaceService.ets:95` | collision 时显示保留两份与明确替换；无 collision 时确认。keepBoth 创建 novelId 新身份再安装 |
| 原生持久安装 | `repository.ts:2121` | 重检预览 sourceToken 与目标状态→写 staging→loadAt(stage)→swap stage/live→loadAt(live) 返回；旧树替换时进 `.retained` |
| 公开持久安装 | `repository.ts:2195`／`:2250` | 从分支计划重建分支／commit／ledger／checkout，验证完整树，写 staging 并交换，最后 loadAt(live) 返回 |
| 页面重读 | `NovelProjectsPage.ets:70`／`:131`；`NovelWorkspacePage.ets:814` | 列表从 repository inventory 重读；工作区 readWorkspaceSnapshot 从 loadProjectInternal／磁盘 checkout 与分支快照取得，不只沿用导入返回对象 |

## 真实文件核验契约

### 公开工作区 ZIP

由 `repository.publicExportPlan()` 枚举所有 lifecycle=active 分支；`buildNovelWorkspacePublicFiles()` 按实际分支 pathName 生成；`exportNovelWorkspaceArchive()` 排除宿主 `.amber/`。公开格式有意不迁移私有创作消息、版本历史、执行任务、ordinaryRun／provider response cursor，不能把这些缺失报为备份数据丢失。

- `manifest.yaml`：`format=amber.novel.workspace`，`formatVersion=1`，`mainBranch`／`activeBranch` 为实际分支路径，`title`、`created_at`、`updated_at`、`source.projectID`。不要误把公开包要求成 Harmony 私有 manifest 的 `version/project_id/active_branch` 方言。
- `project.md`：frontmatter `id/kind=project/title` 与 manifest 一致；`polishPreference`、`stateSyncReasoningEnabled` 是公开映射字段。
- `branches/<pathName>/branch.md`：分支 `id/title/syncStatus/harmonyInboxComplete`；每个 active 分支都应出现，active/main 必须命中。
- `chapters/*.md`：非 discarded 正文的 `id/kind=chapter/title/ordinal` 与正文逐字匹配；discarded 章节在 `discarded/<id>.md`，不进入 TXT。
- 本篇作品实际存在的基础及分支资料、inbox 提案、plan/this-chapter.md、plan/upcoming.md、setting/preferences.md、setting/catalog.json、state/evidence.json、plot/current.md 与 plot/chapters 应按对象与分支核对。未创建的对象不能以“包中没有”认定丢失。
- 未知扩展路径按原样保留；zip CRC、重复路径、非法路径、private `.amber/` 泄漏、大小限制分别检查。域默认限制 1024 entries、总压缩 16 MiB／展开 64 MiB、单项压缩 8 MiB／展开 16 MiB。

依据：`workspace_contract.ts:106`、`workspace_interop.ts:403`、`workspace_exchange.ts:37`／`:210`／`:227`、`repository.ts:2147`。

### 完整原生备份 ZIP

- 根 `native-backup.json` 必须有 `format=amber.novel.native-backup`、`version=1`、`checksumAlgorithm=fnv1a32`、`projectId/title/schemaVersion/activeBranch/state/createdAt/entries`。
- 每个 payload 文件恰有一条 entries 声明，声明 `byteLength` 与解压字节一致；对真实字节逐项重新计算 8 位小写 FNV-1a32。manifest 自己不在 entries；ZIP CRC 另行验证。
- 保留完整项目树（含 `.amber/`）：checkout、project-state、ledger、所有 commit／branch／存在的 undo 快照、消息与 UIMessage 内容、已存在的 proposals／jobs／unknown bytes；项目正文文件与章节索引互相匹配。
- manifest 的 projectId/title/schemaVersion 与完整重读项目一致；activeBranch 与 checkout.branchId、state.branchId/head/treeDigest 一致。台账每个 commit 的 snapshot、每个 active branch 的 head/body/state、proposal/job CAS 引用必须成立。
- 导出／恢复只接受闲置任务状态：paused/failed/waiting_system/completed/cancelled；运行任务要求暂停，属于既有行为。

依据：`native_backup.ts:9`／`:179`／`:211`、`repository.ts:1821`／`:1827`／`:1954`／`:2096`。FNV 是传输完整性校验，不是签名或真实性证明。

### 正文 TXT

只导出当前分支的非 discarded 章节。标题 trim、CRLF/CR 正规化为 LF，按 ordinal 升序，每章标签为三位序号+空格+标题；书名、章标签、正文间空行，最后一个 LF。严格 UTF-8 解码后，与同次导出 ZIP 的 active 分支正文比较，核对中文／标点／换行与章节总数，不混入其他分支、discarded 或资料。

依据：`repository.ts:3462`、`workspace_book_export.ts:55`／`:70`／`:212`、`NovelWorkspacePage.ets:1272`。

## native keepBoth 游标与新身份的既定行为

`copyNovelNativeBackup()` 在 `.amber/project-state.json`、ledger 所指 commit snapshots、branch snapshots、undo snapshots 中重写 project.id，并删除完整 `ordinaryRun` 对象。因此旧 provider responseId／sequence／执行游标不迁移到新身份；创作消息、章节、分支及 commit/CAS/任务记录不因这个删除而丢失。`.amber/jobs.json` 的 job.projectId 改为新身份，任务自身 frozen plan／progress、commit/head/treeDigest 仍保留。`manifest.yaml` 与 native metadata projectId 同步更新，新清单重新计算 checksum。

同身份 native 恢复可保留 ordinaryRun；keepBoth 是新身份复制。公开导入也没有 ordinaryRun。现有明确测试 `novel_p5_exchange.test.ts:94` 覆盖“同身份保留 response cursor、新身份清除 ordinaryRun 并保留 messages、每分支清除、public 不带 ordinaryRun”；本次只阅读其契约，未重复运行旧测试。

验收时应核对原项目 ID 与字节未变、新副本 ID 不同、messages/branches/chapters 与原生内容一致；副本没有旧 execution cursor 是预期。需要同时证明入口真实导入成功及进程重启后重读，不能用纯内存恢复代替设备持久性证据。

## 实际产物核验结果

三文件均为 root 通过已安装应用系统 DocumentViewPicker 保存到 Download 后取回的产物；建议名经系统改为 p9 名称。本文 reviewer 独立读取并验证这些真实字节，不另行构造替代文件。

| 真实文件 | 字节数 | SHA-256 |
| --- | ---: | --- |
| `artifacts/p9-novel-public.zip` | 11630 | `56d475db0fa9c1e7ba875302928f83e51b83151e3a9090db949a7aedc9f9e550` |
| `artifacts/p9-novel-native.zip` | 523746 | `291f10b3bf759407222ae1d851c449daf66c52282562b05e8ef0614f1c964caa` |
| `artifacts/p9-novel-manuscript.txt` | 1154 | `7df6771ee971b1bfd22c42fadb45d75e974f8c4a9f73f702894e236b145d25ba` |

Python zipfile 独立解压检查：公开 38 entries、原生 139 entries，CRC 均无失败、无重复路径。原生清单恰为 138 payload，逐项 byteLength／FNV-1a32 全通过；manifest schemaVersion=4、项目 `nv_mur96bsr_1_23pat28f`、active branch `nv_murb86h3_5_mbj0dk5c`、head `c101-f97804e2a056166e`、treeDigest `f97804e2a056166e` 与 checkout 完全相符。

root 从真实 `/data/app/el2/100/base/app.amber.novel/haps/entry/files/amberagent/novel-workspace/nv_mur96bsr_1_23pat28f` 取回 `artifacts/source-project-tree`。独立枚举得 138 文件／3748721 字节；与 native payload **路径集合完全相同且每文件逐字相等**。该树含 102 条 ledger commit、3 branch snapshots、2 undo、19 条创作消息、9 chapterVersions、1 discussionArchive、6 jobs、3 proposals／3 decisions。因此真实原生导出不存在漏文件或字节损坏证据。源树聚合 SHA-256 为 `82fcd46870f7d008912ca5a444ba0a61e66444ed5150922e57fe300cd093cca6`，计算顺序为 sorted path，每项 `path UTF-8 + NUL + SHA256(fileBytes).digest()` 的连接后 SHA256。

此外对真实 ZIP 用现有 `readZipEntries/readZipEntryBytes` + Node inflateRaw 实现只读 codec，调用生产域 `importNovelNativeBackup`／`importNovelWorkspaceArchive`，在独立 MemoryFileStore 安装实际文件并重读；这是实际产物的附加域验证，**不代替设备恢复**。native 的 `inspectNativeRestore/installNativeBackup` 完整树／commit／branch/CAS 校验通过。public/native 所有 active branch 的章节 id/title/content/discarded、资料 id/kind/title/content/enabled/aliases/tags/customKind/injectionMode 与分支写作偏好一致：

| 分支 | 章节 | 资料 |
| --- | ---: | ---: |
| main／主线 | 1 | 1 |
| nv_murb5qr5_2_oezqcegt／P2-History-Branch-LonP2-Renamed-Branchg-Name-Review | 1 | 1 |
| nv_murb86h3_5_mbj0dk5c／P2-Reader-Version-Fork（活动分支） | 6 | 1 |

公开映射材料时间戳由 manifest created_at/updated_at 重建，与 native 私有创建/更新时间不同；此处核对实际内容和上下文字段，不要求两个不同格式的全部对象逐字段相等。TXT 严格 UTF-8 解码成功，**与真实 native 活动分支通过 buildNovelBookExport 得到的 TXT 逐字相等**，包含第 4 章中文及两个 Local draft chapter，6 章齐全。

对实际 native 输入调用 `copyNativeBackup` 后安装副本，新身份 ordinaryRun 缺失、19 条 messages 完全保留；copy 操作前后内存存储的源项目树逐字不变，新 repository 重开相同存储可重读 6 章。源当前 project-state 不含 ordinaryRun；源 33 个历史 commit 含 ordinaryRun，cursor 均为 null。此次可实证历史 ordinaryRun 清除，但没有非空 response cursor 的清除实证；非空游标行为依据前述生产代码与既有明确测试。

设备 `p9-public-import-selection.jpeg` 独立目测为公开工作区 3 分支的冲突预览，显示格式边界与保留两份／替换按钮；`p9-public-imported-chapters.jpeg` 独立目测活动分支 6 章齐全；`p9-projects-after-public-copy.jpeg` 独立目测原项目与“（副本）”各 6 章并存。`p9-native-import-preview.jpeg` 独立目测为完整备份冲突预览，显示正文／资料／所有分支／创作记录／版本／任务及保留两份入口。`p9-native-imported-chapters.jpeg` 名称虽含 chapters，实际截图仍在创作 tab，可确认创作记录可见，不能据文件名宣称看到了 6 章目录。冷重启和设备侧目录完整性仍由后续证据核对。

## P3：系统改名后 toast 仍误报建议文件名（已修复并验收关闭）

独立目测 `device/p9-txt-save-picker.jpeg`：系统 Download 保存名为 `p9-novel-manuscript.txt`；`device/p9-txt-saved.jpeg`：保存成功 toast 为“已导出 P4-Renamed-Verification.txt”。实际取回文件名和字节均为 p9-novel-manuscript.txt。初始 `NovelWorkspacePage.ets:1304` 直接使用传入的 suggested fileName，未反映系统用户修改，故为真实回执误报，非数据丢失。

root 将该成功回执一行改为“文件已导出”，避免声称并不知道的系统最终文件名；本 reviewer 读取当前源码确认此行已变为通用回执。改动局部且不接入新 API，不改变保存字节或格式。

修复后独立目测 `device/p9-final-toast-final-picker.jpeg`：真实系统 Download 文件名改为 `p9-novel-toast-final.txt`；`device/p9-final-toast-frame-03.jpeg`：回到小说工作区后 toast 清楚显示“文件已导出”，不再误报建议名。root 记录此帧为保存点击后 1.85 秒。两次从真实 Download 取回的 `artifacts/p9-novel-toast-verified.txt` 与 `artifacts/p9-novel-toast-final.txt` 均为 1154 字节；reviewer 逐字比较均完全等于原 `p9-novel-manuscript.txt`，SHA-256 均为 `7df6771ee971b1bfd22c42fadb45d75e974f8c4a9f73f702894e236b145d25ba`。P3 成功回执与字节保存已完成闭环，此发现关闭。

## 设备实际 keepBoth 恢复目录与冷启动核验

root 在系统 importer 完成真实公开／原生 keepBoth 后，从设备取回以下目录；reviewer 未调用 HDC，对取回文件独立核对：

| 设备取回目录 | 实际新／原身份 | 文件数／字节数 | 独立结果 |
| --- | --- | --- | --- |
| `artifacts/recovered-public-tree` | `nv_murlu1zt_2_2ngf29fk`，显示名“P4-Renamed-Verification（副本）” | 73／42999 | 实际 73 文件路径和每个字节完全等于真实 public ZIP 经生产 `copyNovelWorkspaceImportPlan(newID)` 与 `installWorkspacePlan()` 的预期结果 |
| `artifacts/recovered-native-tree` | `nv_murlvpaj_4_fowrjvcp`，显示名保留“P4-Renamed-Verification” | 138／2253154 | 实际 138 文件路径和每个字节完全等于真实 native ZIP 经生产 `copyNovelNativeBackup(newID)` 的预期结果 |
| `artifacts/original-project-after-import` | 原 `nv_mur96bsr_1_23pat28f` | 138／3748721 | 路径集合及每个文件与 `source-project-tree` 逐字完全相等，原项目无改写 |

native 新副本比源少 1495567 字节，**不是丢失文件**。33 个历史 commit 的 ordinaryRun 对象按照 keepBoth 契约清除，其他改动只有指定 project.id／job.projectId／manifest 项目标识；实际目录与复制函数预期字节全等证明没有额外收缩。102 条 ledger、commit 身份／CAS、checkout、所有分支、undo、19 条 messages、9 chapterVersions、1 discussionArchive、6 jobs、3 proposals／3 decisions 均保留。当前源与副本没有非空 ordinary cursor，此次不声称非空 response cursor 的设备实证。

公开新副本重建宿主 commit 与 ledger、保留 `.amber/public-originals/`。旧私有 102 commit／jobs／proposals／undo／messages／chapterVersions 不在公开 ZIP 契约内，设备公开恢复实际 messages=0、chapterVersions=0 是预期。实际新项目 id 与“（副本）”名称重写准确，branch IDs／章 ID 和正文保留。

将这两个真实设备目录分别放入独立 MemoryFileStore，创建全新 production repository 后 `loadProject/nativeBackupSnapshot` 校验完整树成功；逐分支重读均为 1／1／6 章，每支一份角色资料。native 活动分支重读 19 messages／9 chapterVersions，主线原有 1 chapterVersion 也保留。这是对真实设备字节的独立重读，未生成替代产物。

root 冷启动后 `p9-final-novel-cold-projects-ready.jpeg` 独立目测三个项目均列出且各 6 章；`p9-final-public-six-ready.jpeg` 独立目测公开副本正文 01–06 完整；`p9-final-native-cold-chapters.jpeg` 独立目测 native 正文“共 6 章”、完成润色任务卡与当前可见 01–04 章；05／06 因任务卡占屏暂未在此截图可见，已由实际目录重读证明存在。新增 `p9-final-native-six-chapters`／`native-lower-chapters` 实为项目列表，未依据名字增加正文目测声称。冷启动动作由 root 执行，本文 reviewer 的证据是实际目录重读和截图目测。此次没有实际分支历史切换／暂停任务续跑设备操作，不把保留状态字节等同于全部任务续跑成功。

公开／原生／TXT 的真实文件与 importer/keepBoth/持久重读验收通过，无确证正文／分支／源树数据丢失；P3 已修复并完成新包系统改名保存验收，本 reviewer 范围内无未关闭发现。

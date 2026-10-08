# Phase 2 W15：Novel workspace 交换审计

本记录以 iOS 仓库 `HEAD=1023e8a08f5957157226725b43dbf3e2e7078a17` 的真实实现为准。iOS 仓库只读；Android 端的实现位于 `feature/novel-workspace`。交换对象是 Novel workspace 文件树，不是两端的内部数据库快照，也不是实时同步协议。

## 2026-10-01：Android 直接导入 `.ambernovel` 项目包

小说列表的导入入口现在调用 `NovelWorkspaceProjectImport.importProject`，按文件内容识别工作区 ZIP 与 `.ambernovel` JSON envelope，不依赖文件扩展名或文件提供者的 MIME。

- ZIP 沿用 `NovelWorkspaceExchange.readZipFiles` 和现有安装逻辑。
- `.ambernovel` 使用 `NovelPackageCodec.decodeForWorkspaceImport`，校验格式、版本、长度、Base64 和 SHA-256，流式读取工作区与讨论所需字段，检查书稿引用，再经 `NovelLegacyWorkspaceMigrator` 转成工作区文件。支持范围为现有 v1 项目包契约；超出已支持 schema 的包拒绝导入。
- 每次导入生成新的本地项目 ID 和初始 ledger；源项目 ID 保留为来源元数据。现有小说不会被覆盖。
- 转换保留现有迁移器支持的活动分支、已选章节、弃稿、设定、分支设定覆盖、剧情、计划、未解决设定提案和可用候选草稿。项目包的讨论消息另存为新项目的本地 sessions；ZIP 仍只交换公开工作区树。
- 不恢复源平台运行任务、CAS/checkpoints 或完整版本历史；未知扩展字段在这条 JSON → 工作区转换路径上没有完整往返保留承诺。此功能不改变 Android 的工作区 ZIP 导出格式。

新增 `NovelWorkspaceProjectImportTest` 验证包导入、内容与讨论记录、重复导入、ZIP、额外 iOS 字段、损坏校验和恢复 epoch；`NovelProjectsImportTest` 验证正式 ViewModel 入口、成功跳转、已有书保留和失败提示。输入使用本仓 `test-fixtures/novel-v1`，其中项目包按 Swift Codable 规则构造，不能据此宣称当前 iOS App 真机导出已验收。

首次定点验证：项目包导入 6、项目 codec 10、已有迁移器 4、工作区 ZIP 7、应用迁移服务 5、应用导入入口 3，共 35 项 JVM/Robolectric 测试通过，0 失败、0 跳过；`:app:assembleDebug --offline` 通过。该阶段没有用用户实际 iOS 导出文件验收。

### 实际大项目包的内存崩溃修复与真机验收

真机 crash buffer 显示导入在 `NovelProjectDocumentWireSerializer.deserialize` 的整份 JSON 树构建阶段发生 OOM，堆上限为 512MB。实际项目包为 70,017,556 bytes，内层 JSON 为 52,430,646 bytes；原导入路径在相同 512MB JVM 限制下复现 OOM。

工作区导入改用独立的 `NovelWorkspaceImportDocument` 流式解码，只读取现有转换器消费的书稿与讨论字段。源运行态的操作历史、注入回执、checkpoints 等不再构造为 Android 领域对象，因此 iOS 历史里的 `workspacePlot` 不阻挡书稿迁移。包的校验和、schema、项目 ID 校验继续保留；主分支、已选章节版本、设定版本、分支设定覆盖及当前剧情引用须存在。原 `NovelPackageCodec.decode` 的原生文档解码、未知字段保留和完整运行态验证行为保持。回读测试另定位到全局设定转换路径缺少 `.md` 后缀，一并修复。

- 回归：59 项 JVM/Robolectric 测试通过，0 失败、0 跳过，包含实际 70MB 包在 512MB 限制下完整安装、iOS 运行历史兼容、原生 codec 严格性、设定文件可见性和缺失正文引用拒绝。
- 真实包重放测试 `NovelWorkspaceLargePackageImportTest` 通过 `AMBER_NOVEL_IMPORT_REPRO_PATH` 指向本地私有样本；普通测试未配置该变量时跳过，书稿不会加入仓库。
- `:app:assembleGraphite --offline` 通过；主包 `app.amber.agent` 2.6.8 / 396 使用与已安装应用一致的证书签署，16KB zipalign 通过，保留数据覆盖安装到 M610BB / `506e0b25`。
- 真机新增项目进入工作区，回读实际文件与源包逐项比对：53 章正文、5 份弃稿、19 项设定的标题/内容一致，596 条讨论正文一致，当前分支大纲一致。首次安装时间及数据目录保持；真机 APK SHA256 与交付包一致。crash buffer 仅保留修复前的 23:38:14 崩溃，没有本次新增崩溃。
- 交付包及证据：`/tmp/amber-ios-import-oom-20261001/Amber-2.6.8-ios-import-memory-fix.apk`、`verified-device-import.json`、`device-imported-book.tar`、`test-red.log`、`validation.log`。私有书稿与设备回读仅留在本地临时目录。该结果验证此实际项目包，不外推任意大小包的内存表现。

## 已确认的生产链路

```text
iOS NovelWorkspaceBackup.export(document)
    -> UTF-8 .md/.yaml tree
    -> Android NovelWorkspaceExchange.importZip/readZipFiles
    -> NovelWorkspaceInstaller.install
    -> Android edits through NovelWorkspaceStore
    -> NovelWorkspaceExchange.exportZip
    -> UTF-8 .md/.yaml tree
    -> iOS NovelWorkspaceImporter.makeDocument(from:)
```

`scripts/run_novel_workspace_exchange.sh` 将当前 iOS checkout 复制到 `/tmp`，在副本中注入 `scripts/novel-workspace/W15CrossPlatformRunnerTests.swift`，再调用真实的 iOS exporter/importer。Android 侧由 `NovelWorkspaceExchangeRunnerTest` 调用生产导入、存储和导出 API。脚本不修改 iOS checkout；完整 runner 由主任务统一运行 Gradle。

### 传输外壳边界

Novel 的 iOS 生产入口 `NovelProjectListView.fileImporter` 声明的是
`.amberNovelProject` 和 `folder`。目录入口最终调用
`NovelWorkspaceFolderDocument` / `NovelWorkspaceImporter.packageData(fromDirectory:)`；当前没有另一条把 Novel workspace ZIP 直接交给 iOS importer 的生产 consumer。Android 的生产 exchange API 产出 ZIP，因此 W15 runner 先用 Android 的 `exportZipBytes`，再用同一生产读取器解包成 workspace folder，最后把这个 folder 交给 iOS importer。产品流程需要“Android ZIP → iOS”时，沿用系统文件分享/文件 App 的解压步骤；不把 Novel workspace 的 folder importer 与会话 stored ZIP 混为一谈。

## 真实 iOS 导出样本

临时 harness 调用真实 `makeNovelWorkspaceBackupFixture()` 和 `NovelWorkspaceBackup.export` 后，得到 12 个文件。fixture 的 project/branch UUID 每次由 iOS helper 新建，因此 `sourceProjectID` 与 `branch.id` 是动态值；本次主线 Android runner 消费的真实树记录为 `sourceProjectID=95f9971c-a6b6-44a8-9be8-cc68172bd099`、`mainBranchID=ed47cee2-5d96-4cff-87b1-f7c5b0779c61`、`sourceRevision=12`、`schemaVersion=1`、`mainBranch=main`、`exportedAt=2026-08-18T00:00:00Z`。

```text
manifest.yaml
project.md
setting/characters/赵大.md
setting/world/world-rule.md
branches/main/branch.md
branches/main/chapters/001-山呼.md
branches/main/discarded/水稿.md
branches/main/plot/current.md
branches/main/plot/outline.md
branches/main/plot/events.md
branches/main/plan/this-chapter.md
branches/main/plan/upcoming.md
```

样本实际包含 project、branch、working/discarded chapter、world/character material、plot current/outline/events、this-chapter/upcoming plan、chapter ordinal/title/body、material aliases/injection/sourceVersionID 等字段；不是手写的跨端 fixture。

## 字段和路径映射

| iOS exporter | Android 读取/保存 | 结果 |
| --- | --- | --- |
| `manifest.yaml`：`format`, `formatVersion`, `exportedAt`, `source.*`, `mainBranch` | `NovelWorkspaceManifest.parse` + `NovelWorkspaceParsed` | v1 严格识别；`source.*` 用于来源元数据，`exportedAt` 不进入 ledger |
| `project.md`：`id`, `kind`, `title`, `collaborationMode`, `polishPreference` | `NovelWorkspaceParsed.projectTitle/collaborationMode/polishPreference`，原文件写回 | 已覆盖；未知 front matter 随原树保留 |
| `branches/<slug>/branch.md`：`id`, `syncStatus`, `title` | `mainBranchID` + branch 原文件 | branch id 用于 Android 初始 ledger head 映射 |
| `chapters/%03d-<slug>.md`：`id`, `ordinal`, `sourceVersionID`, `title`, body | `workingChapters` 按 ordinal 解析，原文件保留 | ordinal 是排序身份；未知字段不丢失 |
| `discarded/<slug>.md` | `discardedChapters`，原文件保留 | 作为弃稿树保存；不提升为 working chapter |
| `setting/<kind>/<slug>.md`：material fields、aliases、body | `materials` 解析 kind/title/injection/aliases，原文件保留 | 已支持已知 kind；未知 material kind 按路径推断并保留原字段 |
| `plot/current.md`、`outline.md`、`events.md` | `plotSummary/highlights`, `plotOutline`, `plotEvents` | current 的 `## 近期已写` 由现有分割逻辑解析 |
| `plan/this-chapter.md`、`plan/upcoming.md` | upcoming bullet 解析，计划文件原样保留 | plan 的结构化消费以现有字段为限 |
| `inbox/<slug>.md`、`drafts/<id>.md`、未知 `.md/.yaml` | 不误删，随 workspace 原树保存并再次导出 | 见下方支持边界 |

## 明确的支持边界

- **运行态不迁移。** iOS exporter 不输出 `.amber` ledger、sessions、activeRuns；Android exporter 通过隐藏目录规则也不输出 `.amber`。因此 W14 只恢复本机 `.amber/jobs` 和 Controller/Worker/VM 状态，不需要先把 job 迁移进交换树，二者不存在契约冲突。
- **身份不是同一领域对象。** iOS importer 会创建新的 project/branch/session/record IDs；`source.projectID` 和源 front matter id 是来源信息。Android installer 同样创建新的初始 commit，使用导入 branch 文件的 id 作为本地 head 映射；不能把跨端导入当作保留同一数据库主键。
- **扩展字段和未知文件采用原树保留。** Android 读取 zip 时只转换 UTF-8 `.md/.yaml`，Installer 逐文件写回；因此 iOS passthrough front matter、未知目录、`inbox` 和 `drafts` 在不修改内容时能回传。语义转换只承诺上表字段。
- **候选不自动入库。** iOS `drafts/` 是可交换的候选文件树；Android 当前只保证保留/导出，不把它冒充成已确认 candidate，也不承诺其 runtime ledger、accept/reject 历史跨端迁移。`inbox/` 同理，保留原文件而不伪造已处理状态。
- **附件不在 v1 子集。** 两端当前 workspace exporter/store 都筛选 `.md/.yaml`；图片、音频、PDF 或其他二进制不会随这条 workspace 交换链路迁移。完整备份恢复另走平台数据集保护，不在 W15 扩大范围。
- **日期和 UUID 不作为跨端同一身份。** 日期按 manifest 的 ISO UTC 文本交换；UUID 只作为 front matter/source 的可读来源字段，导入端可重生成领域身份。枚举未知值保留在原文件，结构化消费者使用各自已知值集合。

## 当前实现审查结论

Android 现有 v1 exchange 已具备真实 iOS 样本所需的路径、字段和 UTF-8 读写能力；没有发现需要重写迁移器的契约缺口。W15 的最小实现是：保留生产 zip/tree API，增加真实两端 runner、生产导入后修改再导出的回归和上述边界记录。任何需要完整候选运行时、二进制附件、session/ledger 合并或实时同步的需求都超出当前 v1 子集，应另开版本与预算。

## 验证状态

- 真实 iOS exporter：在 `/tmp/amber-w15-ios-project` 调用当前 `NovelWorkspaceBackup.export`，生成 12 文件树；`testRealIOSExport` 通过。
- Android importer/exporter：主线定点 Gradle runner 通过（1 test、0 failures、0 skipped），消费 12 个 iOS 文件，写入本地初始 ledger，修改 project title，再由生产 `exportZipBytes` 生成 12 文件 `android-export`；metadata 记录 `mainBranchID=ed47cee2-5d96-4cff-87b1-f7c5b0779c61`。
- 真实 iOS importer：`testRealIOSImport` 已消费上述 `android-export` folder，通过 `NovelDocumentValidator`，验证 Android 修改后的标题、working/discarded chapter 数量和 character material，再次调用当前 `NovelWorkspaceBackup.export`，生成 13 文件 `ios-reimport`（含 importer 重建的 per-chapter plot module）。
- iOS 临时构建产生的模拟器/Xcode 警告不属于产品改动；没有修改 iOS 工作树。

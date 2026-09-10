# Phase 2 W15：Novel workspace 交换审计

本记录以 iOS 仓库 `HEAD=1023e8a08f5957157226725b43dbf3e2e7078a17` 的真实实现为准。iOS 仓库只读；Android 端的实现位于 `feature/novel-workspace`。交换对象是 Novel workspace 文件树，不是两端的内部数据库快照，也不是实时同步协议。

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

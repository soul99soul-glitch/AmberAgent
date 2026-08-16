# 小说：文件工作区 + 薄版本控制

日期：2026-08-16  
状态：Proposed（先备份与导入，不改运行时存储）

## 问题

讨论 agent 面对的是不透明的 JSON 包，所以每个作者意图都长成专用 `novel_*` 动词：读章、改正文、回退、拒设定建议……小说本质是能读能写的文稿，历史用指针管。现在的事实事务、段号补丁、检查点种类，是存储不透明之后的补偿层。

一次性把磁盘改成 markdown 工作树会破坏现有项目：章节版本、检查点、剧情快照、会话和回执缠在同一份 `NovelProjectDocumentV1` 里。所以运行时仓库先不动。

## 锁定决策

1. **北星**：书是文件；agent 用通用读写；host 只守正史闸门和版本指针。版本控制借 git 的模型（工作树、commit、branch、checkout），不内嵌真 git，不对正文做三路文本 merge。
2. **磁盘目的地是 markdown 工作树，不是 JSON 包。** `NovelProjectDocumentV1` 分片包是过渡权威，不是终点。迁库是换实现，不换作者看见的卡片和三入口。
3. **现在不迁库。** 现有项目一行都不改格式。先备份/导入证明往返，再虚拟读写，最后才把打开中的书从包换成目录。禁止在用户设备上原地改格式。
4. **先做可往返的 markdown 工作区备份**。给人看、可归档，也作为后续导入和重构的契约样本。它不是今天的「当前分支正文拼成一个 `.md`」。
5. **导入只建新项目**，不原地替换正在用的包。历史会话、候选、回执、润色事务、旧检查点不进备份；丢历史、保内容。
6. **专用动词冻住为默认增量**。缺能力先问：能否变成「投影路径上的 read/write」或「备份/导入」。不先加新的 `novel_*` RPC。
7. **作者看见的界面不换皮。** 创作 / 正文 / 设定三入口、审批卡、身份卡、设定建议、本章计划、收录气泡都留。文件工作区是 agent 和备份的世界，不是把 App 改成文件浏览器。

## 和现有导出的差别

| | 现有 `exportBranchMarkdown` | 本设计的工作区备份 |
|---|---|---|
| 形态 | 单个 `.md` | 目录树（分享时打成 zip） |
| 内容 | 当前分支未废弃章的标题+正文 | 项目元数据、共享设定、每条活动分支的正文、剧情、本章计划、往后几章；废弃章另放 |
| 往返 | 不能导入 | 可导入为新项目 |
| 用途 | 给人读成稿 | 备份、搬家、给后续重构当格式契约 |

完整 Amber 项目包（JSON envelope）仍是「连历史一起搬走」的通道。两套并存，职责不混。

## 备份目录

```text
<project-stem>/
  manifest.yaml
  project.md
  setting/
    world.md
    master-outline.md
    writing-requirements.md
    characters/<slug>.md
    relationships/<slug>.md
    custom/<slug>.md
  branches/
    <branch-slug>/
      branch.md
      chapters/
        001-<slug>.md
        002-<slug>.md
      discarded/
        <slug>.md
      plot/
        current.md
        outline.md
        events.md
      plan/
        this-chapter.md
        upcoming.md
```

- 共享设定放根上 `setting/`，对应项目级 materials（当前修订）。分支覆盖若存在，写在该分支 `setting/` 下同名文件，并在 front matter 标 `override: true`。
- 每条 **active** 分支各有一份当前工作稿（head 上未废弃章）和当前剧情快照。
- 文件名 slug 来自标题，非法字符换成 `-`，空则用 id 前 8 位。序号三位，按工作稿顺序。
- 正文文件 **不要** 再包一层 `# 标题`。标题只在 front matter / 文件名里，避免导入时和正文开头的标题重复。

### `manifest.yaml`

```yaml
format: amber.novel.workspace
formatVersion: 1
exportedAt: 2026-08-16T12:00:00Z
source:
  projectID: "..."
  projectRevision: 193
  schemaVersion: 1
mainBranch: main
```

`format` / `formatVersion` 是导入的唯一门闩。不认识就拒，不猜。

### 文稿 front matter

每篇内容文件以 YAML 开头，最少包含稳定身份，方便日后往返：

```yaml
---
id: 3f2a0c1a-...
kind: chapter          # chapter | material | plot | plan | project | branch
title: 山呼
ordinal: 3             # 仅 chapter
materialKind: character
aliases: [赵大]
injection: always      # always | smart | off
sourceVersionID: "..." # 导出时的章节版本 / 资料修订，只读溯源
---
```

正文区是作者看到的 markdown，原样进出。不把 UUID 写进正文。

`plot/current.md` 的正文就是当前快照 `summary`。`plot/outline.md` 是 `branchOutline`。`plot/events.md` 按 sequence 列出当时快照引用的事件摘要（一项一段，前面可有 `- `）。近期已写要点若有，写在 `current.md` 文末的 `## 近期已写` 下；导入时有则读，无则空。

`plan/this-chapter.md` 用小标题还原合同字段（目标冲突、必须发生、不可发生、可见事实、收束），front matter 带 `status: draft|confirmed`。`upcoming.md` 每行一条 beat，最多 8 条。

`project.md` / `branch.md` 只放短元数据：名称、共创或代笔、润色偏好、同步状态。不把模型 key、run、receipt 写进去。

### 明确不进备份

会话消息、讨论归档、未收录候选、注入/生成回执、事实尝试、润色事务、pending/active run、设定建议 inbox、检查点链、旧章节版本、已删资料。

这些仍只活在 JSON 包里。备份不是完整时间机器。

## 导入

入口：选一个工作区 zip 或目录。预览显示书名、分支数、章数；确认后 **新建** 一个项目（等同今天的 keepBoth），不覆盖同 id 的现有书。

重建范围：

- 项目记录 + 共享资料（各一篇当前修订）
- 每条备份分支：工作稿章（每章一个版本）、一个当前剧情快照、可选本章计划与往后几章
- 合成一条初始检查点 + 一条 head 检查点，足以让现有 reducer 认为分支合法
- 新 session 空开

身份：front matter 有 `id` 且与库内不撞则沿用；否则新生成。`sourceVersionID` 只作注释，不当外键。

缺 `plot/`：分支标 `needsSync`，允许讨论，挡正式正文/润色/代笔整章——与今天手改后未同步相同。

缺章、缺 manifest、format 不认识、正文不是 UTF-8：预览失败，不建项目。

导入不是 git clone，不恢复历史指针。书能读能写能继续生成，就算成功。

## 什么在变，什么不动

这不是「把后端数据库换一种形式、前端重做」。更不是先改磁盘。

| 层 | 现在 | 以后 |
|---|---|---|
| 作者 UI | 审批卡、身份卡、设定页、阅读器 | 原样留着，继续当产品 |
| Agent 世界 | 14 个 `novel_*` + 预装摘要 | 看见与备份同构的文件树，通用读写 |
| 正史闸门 | 各写各的审批工具 | 仍是审批卡；只是都走同一道 commit 闸 |
| 身份卡 / 设定卡 | 资料记录的视图 | 仍是视图；底层多一个 `setting/characters/*.md` 投影 |
| 运行时存储 | 分片 JSON 包 | **先不动**；目的地是同构的 markdown 目录，另开迁库规格，必须先有备份往返证据 |

身份卡不是该删的复杂度。该削的是模型必须绕过卡片、再学一套 RPC 才能改同一份人设。

## 北星（后做，不在备份阶段开工）

agent 看见的世界与备份树同构。第一刀应是 **虚拟工作树**：`read/write` 映射到现有 document，`commit` 映射现有检查点，`revert` 映射 `undoBranchHead`。磁盘格式仍是 JSON 包。作者仍点卡片，不进文件树。

正史闸门只有一道：改已收录章或会让后文失效的 `write`，先出 diff，作者确认才 commit。草稿 `write` 可直接落工作树。候选气泡、讨论改正文、代笔收录共用这道闸。

冲突借 git 的依赖失效，不借文本 merge：

| 改动 | 像 git | 结果 |
|---|---|---|
| 只续写 / 只改末章 | fast-forward | 一笔 commit：章 + `plot/` |
| 从某章另开线 | checkout + branch | 旧线不动 |
| 主线上改已有后续的旧章 | rebase 中间 commit | 允许；第 K 章之后的章和 `plot/` 标 unresolved |

解开之前只许讨论和读。解开方式：只修剧情、后章一起改（逐章过闸）、Fork、或显式接受后章不动。不要把 `<<<<<<<` 打进正文。

真 git、iCloud 当 remote、自动合并两条剧情线：不做。

## 重构怎么接

备份格式先当契约样本，用真书导出再导入，核对章序、正文、设定、剧情摘要。过了再做虚拟工作树。虚拟读写用稳了，再把运行时权威从 JSON 包换成这棵目录树。

迁库：先导出工作区 → 离线核对 → 再写新仓库。禁止在用户设备上原地把 JSON 包改成目录树。迁库当天完整项目包仍是回滚通道。会话等未进备份的东西，要么继续旁路存放，要么承认迁库后丢掉——那条在迁库规格里单开，不混进备份。

## 刻意不做（本设计落地时）

- 改 `NovelProjectShardedStorage` 或 document schema
- 替换现有单文件成稿导出
- 讨论侧虚拟 `read/write`（下一阶段）
- 把现有 14 个 `novel_*` 删掉
- 备份进会话、候选、检查点链
- 内嵌 git / 正文三路 merge
- 用备份覆盖正在打开的项目
- 把设定页、身份卡、审批卡改成通用文件浏览器
- 为了迁库而重做作者可见信息架构

## 验收（实施备份/导入时）

- 真项目导出的树，人能直接打开 `chapters/*.md` 读完全文
- 导出再导入得到新项目：活动分支的章序、标题、正文、未删设定、当前剧情摘要一致
- 原项目 revision、检查点、会话不受影响
- 缺 plot 的树导入后是 `needsSync`，不是坏包
- 现有单文件成稿导出行为不变
- 本机无真书时，用最小 document fixture 做往返测试

## 实施顺序（未开工）

1. 导出工作区树 + zip（只读，不碰仓库）
2. 导入为新项目
3. 以后：讨论虚拟工作树（另开规格）
4. 最后：把运行时权威从 JSON 包换成 markdown 目录（另开规格；必须先有 1+2 往返证据，且不改作者 UI）

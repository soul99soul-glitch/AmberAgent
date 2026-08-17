# 小说：文件工作区 + 薄版本控制

日期：2026-08-16  
修订：2026-08-17（锁定方案 A：会话留在账本）  
状态：Active（五阶段已落地最小闭环；代笔 JSON 抽取未拆除；磁盘权威仍是 JSON 包 + checkout 旁路）

## 问题

讨论 agent 面对不透明的 JSON 包，每个作者意图都长成专用 `novel_*`。小说本质是能读能写的文稿，历史用指针管。事实事务、段号补丁、检查点种类，是存储不透明之后的补偿层。

《赵大来了》实证：工作区 markdown 约 684KB，原包约 62MB。书很小，会话和回执占了绝大部分。两者不该继续缠在一份 `NovelProjectDocumentV1` 里。

## 锁定决策

1. **北星**：书是文件；agent 用通用读写；host 只守正史闸门和版本指针。版本控制借 git 的模型（工作树、commit、branch、checkout），不内嵌真 git，不对正文做三路文本 merge。
2. **两层存储。** 书是工作树；账本是会话、commit 指针、回执、run。agent 只看见书。
3. **方案 A：会话留在账本。** 不把讨论气泡做成 `session/*.md`。聊天继续走现有 session 呈现。以后若要「把讨论当创作笔记带走」，另开规格。
4. **磁盘目的地是 markdown 工作树，不是 JSON 包。** 分片包是过渡权威。迁库换实现，不换作者看见的卡片和三入口。
5. **现在不迁库。** 现有项目一行都不改格式。禁止在用户设备上原地改格式。
6. **导入只建新项目。** 备份保书、丢账本历史（会话、回执、旧检查点）。新项目空开 session。
7. **专用动词冻住为默认增量。** 缺能力先问能否变成路径上的 `read`/`write`。
8. **作者界面不换皮。** 创作 / 正文 / 设定、审批卡、身份卡、本章计划、收录气泡都留。

## 书和账本

```text
书（工作树，agent 能读能写）
  chapters/031-入汴.md
  setting/characters/赵匡胤.md
  setting/world/*.md
  plot/current.md
  plan/this-chapter.md
  drafts/              未收录候选
  inbox/               待确认设定

账本（host 自己用，不当工具）
  commits / 分支指针 / 撤销
  会话气泡、讨论归档
  回执、run、润色事务
```

界面是视图：身份卡渲染 `setting/characters/*.md`；审批卡是往正史路径 `write` 的闸。

## Agent 原语

| 工具 | 作用 |
|---|---|
| `list` / `read` | 看目录和文件 |
| `write` / `edit` | 改工作树 |
| `grep` | 按需翻旧章，不再预装 6000 字尾巴 |
| `status` | 脏文件、冲突范围、HEAD |

`commit` / `revert` / `fork` 是 host 动作。模型可以提议，作者点卡才执行。

正史闸只有一道：写已收录章，或写会让后文失效的 `plot/`，出审批卡再 commit。`drafts/`、`inbox/` 可直接写。候选气泡、讨论改正文、代笔收录共用这道闸。

## 剧情

收录或改正文之后，`plot/` 标脏。模型读章、自己改 `plot/`，与正文同一笔 commit。不再抽 JSON 事实，不再 delta/rebuild 自愈，不再把场景家具提成设定卡。

设定建议：模型往 `inbox/` 写一篇 markdown；作者留则移进 `setting/`，拒则删。身份卡继续渲染 `setting/characters/`。

现有 `plot/current.md` 里的「近期已写」是旧抽取回执。迁到文件权威后不再单独维护这类列表；需要防复读时，agent 自己 `grep` 近章。

## 冲突

借依赖失效，不借文本 merge。不要把 `<<<<<<<` 打进正文。

| 改动 | 像 git | 结果 |
|---|---|---|
| 只续写 / 只改末章 | fast-forward | 一笔 commit：章 + `plot/` |
| 从某章另开线 | checkout + branch | 旧线不动 |
| 主线上改已有后续的旧章 | rebase 中间 commit | 允许；第 K 章之后的章和 `plot/` 标 unresolved |

解开之前只许讨论和读。解开：只修剧情、后章过闸重写、Fork、或显式接受后章不动。撤销 / 回退最近 N 章 / Fork = 挪账本指针。

## 工作树布局

真书不是一张 `world.md`。一张卡一个文件。章名可重复，身份是序号。

```text
<project-stem>/
  manifest.yaml
  project.md
  setting/
    world/<slug>.md
    outline/<slug>.md
    writing/<slug>.md
    characters/<slug>.md
    relationships/<slug>.md
    custom/<slug>.md
  inbox/
    <slug>.md
  drafts/
    <id>.md
  branches/
    <branch-slug>/
      branch.md
      chapters/
        001-<slug>.md
        024-山呼.md
        030-山呼.md
      discarded/
        <slug>.md
      plot/
        current.md
        outline.md
        events.md
      plan/
        this-chapter.md
        upcoming.md
      setting/                 # 分支覆盖，front matter override: true
```

- 正文不要再包一层 `# 标题`。标题在 front matter / 文件名。
- `001-山呼.md` 与 `030-山呼.md` 合法。导入以 `ordinal` 为准，不以 slug。
- 当前导出器仍把多张世界卡写成 `setting/world-<slug>.md`。下一刀导出改成 `setting/world/`，旧树仍可读。

### `manifest.yaml`

```yaml
format: amber.novel.workspace
formatVersion: 1
exportedAt: 2026-08-16T12:00:00Z
source:
  projectID: "..."
  projectRevision: 1112
  schemaVersion: 1
mainBranch: 主线
```

`format` / `formatVersion` 是导入的唯一门闩。不认识就拒。

### front matter

```yaml
---
id: 3f2a0c1a-...
kind: chapter          # chapter | material | plot | plan | project | branch
title: 山呼
ordinal: 24
materialKind: character
aliases: [赵大]
injection: always
sourceVersionID: "..." # 只读溯源，不当外键
---
```

正文原样进出。不把 UUID 写进正文。

## 备份与导入

备份是书的快照，不是时间机器。不进备份：会话、讨论归档、回执、run、润色事务、检查点链、旧章节版本、已删资料。未收录候选和设定 inbox 进 `drafts/` / `inbox/`（导出下一刀补；现在的导出还没有）。

完整 Amber 项目包仍是「连账本一起搬走」的通道。两套并存。

导入：选 zip 或目录，预览书名/分支/章数，**新建**项目。重建书 + 一条初始检查点 + 一条 head 检查点。session 空开。缺 `plot/` 则 `needsSync`。缺章、缺 manifest、非 UTF-8：预览失败，不建项目。

## 什么在变，什么不动

| 层 | 现在 | 以后 |
|---|---|---|
| 作者 UI | 审批卡、身份卡、设定页、阅读器 | 原样留着 |
| Agent 世界 | 14 个 `novel_*` + 预装摘要 | 文件树 + 上表原语 |
| 正史闸门 | 各写各的审批工具 | 仍是审批卡，同一道 commit |
| 剧情 | JSON 事实抽取 / delta / rebuild | 模型改 `plot/` |
| 会话 | document.sessions | **仍在账本**，不进工作树 |
| 运行时存储 | 分片 JSON 包 | 先不动；目的地是工作树 + 旁路账本 |

## 重构顺序

1. 导出工作区 — 已落地（`NovelWorkspaceBackup`；尚未 zip / App 按钮；目录仍是扁平 `world-*.md`）
2. 导入为新项目 — 未做。真书导出再导回，核章序、正文、设定、剧情摘要。
3. 讨论虚拟工作树：`list/read/write/grep/status` 映射现有 document。专用动词停增。磁盘仍是 JSON。
4. 用「写 `plot/`」替换事实抽取。不过这一刀，不声称已经简单。
5. 运行时权威换成目录；账本留下会话和指针。先导出 → 离线核对 → 再写新仓库。迁库当天完整项目包是回滚通道。

1 没过不去 2。2 没过不去 3。

## 刻意不做

- 现在改 `NovelProjectShardedStorage` 或原地迁打开中的书
- 把会话做成 markdown（否决方案 B）
- 内嵌真 git / 正文三路 merge / iCloud remote
- 自动合并两条剧情线
- 把设定页改成文件浏览器
- 替换现有单文件成稿导出
- 为了迁库重做信息架构
- 在 4 完成前删掉全部 `novel_*`（可先停增）

## 验收

备份/导入：

- 人能直接打开 `chapters/*.md` 读完全文
- 导出再导入：活动分支章序、标题、正文、未删设定、当前剧情摘要一致
- 原项目 revision、检查点、会话不受影响
- 缺 plot 导入后是 `needsSync`
- 现有单文件成稿导出不变

虚拟工作树（阶段 3）：

- 讨论能 `read` 任意工作章和设定卡，不必再加 `novel_read_*`
- 改正文仍出审批卡，走现有 `saveManualEdit`
- 普通 Chat 工具集不出现这棵树

## 证据

2026-08-17：从 iPhone Air 拷出《赵大来了》原包并转工作区。31 章、8 个人物、多张世界/总纲/笔法卡。原包约 62MB，工作区约 684KB。路径见 `docs/PROJECT_STATE.md`。

# 小说工作区跨端约定基线（Android 实施依据）

日期：2026-08-19
来源：iOS `feat/ios-provider-parity-claude` @ 9d301388b 实现 + `docs/superpowers/specs/2026-08-16-novel-markdown-workspace-design.md`
用途：Android 重构（workspace 为权威）必须与下列约定逐条一致；改动需两端同步。

## 1. manifest.yaml（导入唯一门闩）

```yaml
format: amber.novel.workspace     # 必须精确匹配
formatVersion: 1                  # 整数；不认识即拒
exportedAt: 2026-08-16T12:00:00Z  # ISO8601 UTC 秒级
source:
  projectID: "..."
  projectRevision: 1112
  schemaVersion: 1
mainBranch: <主分支 slug>
```

键排序输出；嵌套 source 块；`formatVersion/projectRevision/schemaVersion` 不带引号；其余值按 yamlScalar 规则。

## 2. 树布局

```
<root>/
  manifest.yaml
  project.md                          # id/kind:project/title/collaborationMode/polishPreference
  setting/{world,outline,writing,characters,relationships,log,custom}/<slug>.md
  inbox/<slug>.md                     # 待确认设定（materialKind: custom）
  drafts/<id 前 8 位>.md               # 未收录候选（kind: chapter, title: 未收录草稿）
  branches/<branch-slug>/
    branch.md                         # id/kind:branch/title/syncStatus
    chapters/%03d-<slug>.md           # 序号=身份；章名可重复
    discarded/<slug>.md               # 废弃章（无 ordinal 字段）
    plot/{current,outline,events}.md  # current 含 "## 近期已写" 高亮段
    plan/{this-chapter,upcoming}.md
    setting/<folder>/<slug>.md        # 分支覆盖，front matter override: true
  .amber/commits.json                 # 账本（host 专用，agent 不可见）
```

- 资料目录映射：world→world、masterOutline→outline、writingRequirements→writing、decisionLog→**log**、character→characters、relationship→relationships、custom→custom。
- 正文不包 `# 标题`；标题只在 front matter/文件名；UUID 不进正文。
- 导入只认 `.md`/`.yaml`，跳过 `.` 开头目录；根目录无 manifest 时允许恰一层嵌套（唯一含 manifest 的子目录）。

## 3. front matter

字段（按需出现）：`id kind title ordinal materialKind aliases injection sourceVersionID override customName syncStatus status collaborationMode polishPreference`。
kind 取值：`project | branch | chapter | material | plot | plan`。

渲染：`---` 围栏；字段按宿主定义的插入序输出（与 iOS 导出器一致，**不排序**；manifest 例外，见下）；`aliases` 渲染为块列表（`  - item`）置于字段之后；正文 trim 后空一行接上；文件以换行结尾。
yamlScalar：空串→`""`；值含 `:#{}[],&*?|>!%@\`'"` 或首尾空格或换行→双引号包裹并转义 `\` 与 `"`；否则原样。

解析（parseFile）：首行须为 `---`，找 `\n---` 结束围栏；`key: value`；`key:`（值为空）后跟 `  - ` 或 `- ` 行视为列表；其余行忽略。unquote：成对双引号剥掉，`\"`→`"`、`\\`→`\`。

## 4. slug 与路径去重

slug：禁字符 `/ \ : ? % * | " < >`、换行、控制字符→`-`；空格→`-`；连续 `-` 折叠为一个；去首尾 `-`；若结果全为 ASCII 字母/`-` 则转小写；空→""。
reservedPath：同集合内重名追加 `-2`、`-3`…；空 slug 用 id 前 8 位兜底；再空用 `untitled`。

## 5. 账本（.amber/commits.json）

```json
{
  "head": "<commitId?>",
  "heads": { "<branchId>": "<commitId>" },   // 空时可省略
  "commits": [ { "id", "parentID"?, "createdAt", "message", "treeSHA256",
                 "files": { "<path>": "<sha256>" } } ]
}
```

- 日期 ISO8601 UTC 秒级（与 iOS 一致）；写入原子（temp+rename），commits 追加式。
- `files` 不含 manifest.yaml；treeSHA256 = sha256(按 path 排序的 `path\thash` 行以 `\n` 连接)。
- commit message 约定：初始/收录/剧情指针/讨论归档/人物说明/润色/还原/提交。
- head 镜像主分支指针；撤销/回退/fork = 指针操作。
- 冲突策略：依赖失效，不做文本 merge；中间章被改→其后章节与 plot 标 unresolved。

## 6. Agent 工具（5 个，禁新增 novel_* 动词）

| 名称 | 参数 | 说明 |
|---|---|---|
| novel_workspace_list | prefix? | 列目录，只读 |
| novel_workspace_read | path | 读单文件，只读 |
| novel_workspace_grep | query, prefix? | 子串搜索，只读 |
| novel_workspace_status | — | 项目名/分支/同步态/章数/plot 是否 stale/unresolved |
| novel_workspace_write | path, content, reason? | 三类处置（白名单制）：① branches/*/chapters 与 branches/*/plot → 登记审批提案；② setting/、inbox/、drafts/、branches/*/setting/、branches/*/plan/ → 直写；③ 其余（manifest.yaml、project.md、branch.md、discarded/ 等宿主文件）→ 拒绝写入 |

路径一律 tree-relative；身份由路径保持：模型提交的 front matter 一律丢弃、只取正文，宿主保留/合成 id/ordinal/title。账本（commits.json）损坏时先改名隔离（commits.json.corrupt-<ts>），绝不静默以空账本覆盖。章节文件名 `%03d` 必须 Locale.ROOT（ar/fa 设备不得本地化数字）。grep 大小写不敏感。zip 重复条目直接拒收。

## 7. 导入语义

- 永远新建 projectID；sessions 空开。
- 章身份=文件名数字前缀（ordinal），不是 slug；章节按 ordinal 排序。
- 只收 `branches/<mainBranch>/` 子树（manifest.mainBranch 指定；缺省 "Main"）。
- materialKind 优先 front matter，回退按路径目录判断，默认 world。
- 缺 plot → needsSync；缺章/缺 manifest/非 UTF-8 → 拒绝。

## 8. 一致性引擎（节点 schema + 约束简报）

核心命题：写新内容时把已抽取的核心节点作为约束注入上下文，防前后文矛盾（上下文工程）。节点=markdown 文件，边=front matter 类型化 relations 字段；不建图数据库、无自动抽取、不走 embedding RAG。

节点类型（驱动一致性的核心节点）：角色、关系、世界观、剧情状态（plot/current.md）、决定（setting/log/）、伏笔（branches/<slug>/plot/foreshadowing/）、时间线、写作要求。

### 节点文件 schema（在资料卡 front matter 上扩展）

```yaml
---
kind: material
materialKind: character     # 或 world/relationship/decisionLog/custom…；伏笔文件用 kind: foreshadowing
title: 赵匡胤
status: 殿前司都点检，暗中结交军将   # 一行当前状态，简报用
aliases:
  - 官家
relations:                  # 类型化关系边，inline map 列表项
  - {with: 赵大, type: 结拜兄弟}
  - {with: 汴京, type: 驻地}
---
```

- `relations` 项格式 `{with: <节点 title/别名>, type: <关系>}`；`with` 必填，`type` 可空。
- 伏笔节点：`branches/<slug>/plot/foreshadowing/<slug>.md`，front matter `kind: foreshadowing` + `status: open|resolved`（缺省视为 open）；正文写埋设/回收内容。
- 兼容注记：iOS 旧 parser 会把 inline map 项当普通列表字符串保存（优雅降级、不崩）；Android parser 解析为结构化 maps。

### 约束简报（每轮 host 注入）

组装顺序与预算（默认 6000 字符，超预算的后续段落跳过、首段必留）：
1. `## 当前剧情状态` ← branches/<slug>/plot/current.md 正文
2. `## 未回收伏笔` ← status=open 的伏笔节点（title + 首行摘要）
3. `## 本章相关节点` ← 从 plan/this-chapter.md 正文按 title/别名（≥2 字）匹配实体 → 沿 relations 展开一跳邻域 → 每节点输出 title/别名/状态/关系/首行摘要
4. `## 已确认决定` ← setting/log/ 资料卡

简报拼接在 systemPrompt 之后，标题「工作区状态简报（host 注入，以下为当前正史约束，不得与之矛盾）」。空树不注入。

写作纪律（提示词固定）：动笔前对照简报、拿不准就 grep 节点；写正文引起角色状态/关系/剧情变化时必须同步更新对应节点与 plot/current.md，并与正文同一轮提交；埋伏笔建 foreshadowing 节点（open），回收改 resolved。

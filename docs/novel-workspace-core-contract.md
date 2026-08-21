# 小说工作区核心契约（iOS / Android 唯一事实源）

状态：核心语义决策已定案（D-B~D-F，2026-08-19 产品负责人拍板）；待 iOS 端确认落地路径并跑通 §5 验证
规范路径（canonical）：`/Users/arquiel/Downloads/AI/amberagent-Android/docs/novel-workspace-core-contract.md`
约定：**两端会话启动时必读本文**；任何影响契约的改动先改本文、经双方确认，再各自实现。本文之外，两端各有自己的实现计划（Android：`2026-08-19-novel-workspace-migration-plan.md`；iOS：`PROJECT_STATE.md` + 交接档）。

> 上下文对齐机制：两个会话的上下文窗口彼此独立、无法直接同步。唯一可靠的做法是**都读这份唯一的、版本化的文档**。不允许任一会话凭自己的记忆/理解去解释一个契约点——以本文为准；本文没写的，就是"未决"，必须显式决策，禁止默默各自实现。

---

## 1. 北星（不可再辩）

1. 书是文件；agent 用通用读写（list/read/grep/status/write 五原语，禁新增 novel_* 动词）。
2. host 只守正史闸门 + 版本指针。
3. 版本控制借 git 模型（工作树/commit/branch/checkout），不内嵌真 git，不做正文三路 merge。
4. 会话留账本，不做 session/*.md。
5. **一致性 = 上下文工程**：写新内容时把已建立的核心节点作为约束注入上下文，防前后矛盾。这是本架构的第一目标，优先于功能对齐与 VCS 完整度。

## 2. 分区表：必须对齐 vs 可以分叉

| 层 | 归属 | 说明 |
|---|---|---|
| 目录 schema、manifest、frontmatter 编解码、章节命名、导入/导出语义 | **必须对齐（互换契约）** | 详见 §3，冻结 |
| 节点 schema（status/relations/foreshadowing） | **必须对齐（契约扩展）** | 见 §3.6，两端必须"保留"；实现分工见 D-F |
| 账本深度、是否存内容、undo/fork/版本史范围 | 可分叉（平台内务） | 已验证账本不跨端（导出跳过 .amber、导入重建），不阻塞互操作 |
| 一致性机制 | **方向已定**：写方维护节点 + 强制新鲜度检查（见 D-C） | iOS 现 host 生成，需向该语义收敛 |
| 正文+plot 提交方式、中间章 unresolved 闸门 | **已定为跨端标准**（见 D-B / D-D） | 两端须实现 |
| UI、代笔实现、提示词细节 | 可分叉 | 各平台自定 |

## 3. 互换契约（冻结）

以 Android 约定文档为细节基准：`docs/2026-08-19-novel-workspace-conventions.md`（§1–§8）。要点：
- 目录：`manifest.yaml / project.md / setting/{world,outline,writing,characters,relationships,log,custom}/ / inbox/ / drafts/ / branches/<slug>/{branch.md, chapters/NNN-slug.md, discarded/, plot/{current,outline,events}.md, plan/, setting/}`。
- manifest：`format: amber.novel.workspace` + `formatVersion`，是导入唯一门闩。
- frontmatter：字段按宿主插入序渲染；yamlScalar 引号规则；`aliases` 块列表。
- 章节身份 = 文件名数字前缀 ordinal，不是 slug；`%03d` 必须 Locale 无关。
- 导入永远新建 projectID；备份保书、丢账本历史。

### 3.6 节点 schema（契约扩展，两端必须保留）
```yaml
status: <一行当前状态>
aliases: [...]
relations:
  - {with: <节点 title/别名>, type: <关系>}
```
伏笔节点：`branches/<slug>/plot/foreshadowing/<slug>.md`，`kind: foreshadowing` + `status: open|resolved`（缺省 open）。
**硬性规则：两端导入/导出/渲染必须把未知 frontmatter 字段与未知文件当 opaque 透传，禁止 drop。**（这是防"Android→iOS 往返丢节点数据"的底线。）

## 4. 语义决策日志（消除空档，防分叉的关键）

规则：任何影响契约的语义点，状态只能是 `已决`（写明决策+理由）或 `未决`（待联合拍板）。**禁止"事实上已按某理解实现但没记录"。**

| 编号 | 语义点 | 状态 | 决策 / 选项 |
|---|---|---|---|
| D-A | 账本是否存内容（blob），undo/restore 深度 | 平台内务（Android 自决） | Android：降级为轻量安全网（①对象库/②快照/③仅撤销最近一笔，待定）；iOS：保留旧版本史。均不阻塞互操作。 |
| D-B | plot commit 与正文 commit 合一笔还是两笔 | **已决** | **统一一笔**：正文与被其更新的 plot/节点同一笔 commit 原子提交，消除断点窗口。iOS 现两笔（正文一笔+剧情指针一笔），需收敛为一笔；Android 按一笔实现。 |
| D-C | plot/ 与节点谁维护、未更新是否拦截 | **已决** | **写方（agent）维护 + 强制新鲜度检查**：plot/current.md 与相关节点由写作方维护；写了正文而未同步更新 plot/节点 → host 拦截/警告（不能放任过期）。iOS 现 host 生成按章模块，需向此语义收敛；硬性要求=plot 与正文同笔更新 + 有检查。_Android：已实现（`isPlotStale` 由 commit 父 diff 推导 + 简报强制警告 + UI 横幅），待真机验证。_ |
| D-D | 中间章编辑的 unresolved 闸门 | **已决（跨端标准）** | 改中间章 → 该章之后的章 + plot 标 unresolved → 解开前禁止继续写后章/收录/代笔（解开方式=确认无碍、fork、或重写后章）。两端都需实现；iOS 现仅 stale 标记、无强闸。_Android：检测+记录+呈现+「确认无碍」解开已实现（`.amber/unresolved.json`），fork/重写在 P4；待真机验证。_ |
| D-E | iOS 最终是否也切 markdown 权威 | **已决** | **iOS 最终也切 markdown 权威**（与 Android 终态一致，两端同一存储）。允许渐进过渡，方向不变。 |
| D-F | 节点 schema 是升为共同层还是 Android 扩展 | **已决** | **先按选项②**：Android 实现一致性引擎（节点 schema）；iOS 保证对节点文件/字段 opaque 保留（不丢，§3.6 底线）。跑稳后再评估是否升为两端共同层。 |

## 5. 验证协议（机器校验，替代人读代码对）

1. **Golden fixture pack**：一组 canonical 工作区树（含节点扩展、伏笔、relations、边界用例），两端各自 import 断言结构、export 对比 golden、再 import 对比无丢失。
2. **跨端往返**：Android 导出 → iOS 导入 → iOS 再导出 → 与 Android 导出对 book 文件做 diff，应一致（除时间戳）。反向同理。
3. **语义清单对拍**：每个写作场景（写章/改末章/改中间章/删章/回退/fork/收录）两端各写一句行为描述，放一起 diff，抓语义分叉。
4. 契约测试随契约版本走；fixture 与契约同目录管理。

## 6. 协作协议

- 发现契约空档 → 记入 §4 为"未决"，提出选项，**升级给用户或另一端拍板**，禁止单方面静默实现。
- 改契约 → 先改本文 + 升契约版本 → 两端确认 → 各自实现 → 跑 §5 验证。
- 每完成一个实现里程碑 → 跑 §5 契约测试 → 把结果同步给对方会话。
- 契约版本：`v1.1`。§3 互换契约冻结；§4 决策点 D-B~D-F 已定案（2026-08-19），D-A 为 Android 平台内务自决项。后续任何语义变更走本节流程并升版本。

# 小说工作区完整迁移 Plan

日期：2026-08-19
状态：已批准（2026-08-21，用户拍板《赵大来了》全量迁移：书+账+笔；D1–D4 随批准落定，iOS 执行计划见本仓 `docs/PROJECT_STATE.md`）
目标终态：markdown 工作区成为唯一存储，旧 JSON 引擎与旧 UI 退役；一致性=上下文工程是贯穿主线。

---

## 0. 现状盘点（已完成，勿重做）

| 能力 | 状态 | 载体 |
|---|---|---|
| 工作区存储（树+账本+checkout） | ✅ | `feature/novel-workspace` |
| 编解码/slug/frontmatter/manifest（对齐 iOS） | ✅ | 同上 |
| 旧 JSON → 工作区迁移器（保书+会话） | ✅ | `NovelLegacyWorkspaceMigrator` |
| Agent 运行时（Generator 接线 + 5 工具 + 正史闸门） | ✅ | `NovelWorkspaceRuntime` |
| 一致性引擎（节点图 + 邻域检索 + 约束简报） | ✅ | `NovelWorkspaceContextAssembler` |
| 聊天切片 + 只读正文 tab（实验 UI） | ✅ | `NovelMarkdownWorkspacePage` |
| 真机验证 | ❌ 零 | — |

已知硬缺口：**无"新建空白书"入口**、**无草稿收录动作**、**无代笔/润色/编辑**、**内容级回滚缺失（账本只存哈希不存内容）**。

---

## 决策点（2026-08-21 已落定）

- **D1 内容安全网方案**：**① content-addressed 对象库 `.amber/objects/<sha256>`**（真 git 式、去重、可 diff）。账本 Commit 已存每文件哈希，补内容侧即得真历史；单章版本恢复/撤一步都靠它。
- **D2 UI 策略**：Android 按原推荐「重建精简编辑面」；**iOS 变体 = 不重建 UI、不重写引擎，换持久层边界**——load 改工作区投影、write 改 commit 管线，reducer 与 UI 保留（iOS 域引擎+UI 约 7 万行是流式呈现/代笔/审批资产，非存储补偿物）。
- **D3 代笔范围**：**完整对齐**（单章+批量连写不降级）；产出落 `drafts/`，收录 = 一笔 commit（正文+剧情）。
- **D4 切换后是否保留旧引擎只读兜底**：**灰度保留后删**——JSON 包迁后改名 `legacy-package/` 封存（回滚副本），旧引擎对已迁项目不可达，最后阶段再删代码。

---

## Phase A · 引擎可信化（先让地基站得住）

目标：真实模型下链路可用 + 一致性闭环补全。**不做完这阶段，后面都是沙上城堡。**

1. **真机验证垂直切片**：装机 → 转换 → 打开 → 让 agent 写，验证简报注入/工具调用/审批卡/commit 全链。修真实模型暴露的问题（模型不守纪律、工具误用、审批交互）。
2. **新建空白工作区书入口**：补 `install()` 的创建路径 + 项目列表"新建（工作区）"，空书有初始 manifest/project.md/主分支。
3. **草稿收录通路**：drafts → chapters 的显式动作（选中草稿 → 选目标章/新开章 → 审批 → commit），替代"靠 agent 重写提案"。
4. **新鲜度硬检查**（跨端标准 D-C）：写正文的 commit 若没同时更新 plot/ 或相关节点 → host 提示/拦截（从提示词软约束升级为机制）。
5. **中间章 unresolved 闸门**（跨端标准 D-D）：改中间章 → 后章+plot 标 unresolved → 解开前禁写后章/收录/代笔；账本/账本外记录 unresolved 范围，提供"确认无碍/fork/重写后章"三种解开动作。
6. **矛盾检测（第三层）**：新章成型后跑审稿模型，读新章+节点图报矛盾；代笔默认开、共创可关。
7. **内容安全网**（按 D1 结论落地）：最小先做"撤销最近一笔"。

**出口标准**：真机能从空书/迁移书写一章、收录、看到简报生效、写坏能撤一步。

---

## Phase B · 生产能力（从"陪写"到"产线"）

目标：无人值守与改稿能力。

1. **单章代笔**：前台管道，计划→生成→核对节点→收录→同步节点，全走工作区。
2. **批量代笔**（按 D3）：WorkManager 持久任务，**commit 即进度游标**（进程重启按 commit 续跑，替代旧 9 阶段 CAS）。依赖 D1 的内容安全网做回滚兜底。
3. **润色/重写本章**：以当前章为源产出候选，审批后替换。
4. **手稿编辑**：正文可手改，保存=commit（带 front matter 身份守护）。
5. **轻量分支**（可选，若有余力）：fork/切换，复用已有 heads 指针。

**出口标准**：能挂机连写 N 章且崩了能续、能手改一章并留痕。

---

## Phase C · UI 建成（新 UI 成为可用编辑面）

目标：按 D2 重建精简编辑面，覆盖日常工作流。**不移植旧 UI 的补偿机器**（候选卡/版本历史/同步横幅大多在新模型下消失）。

1. 创作 tab 完善：流式/工具活动/审批卡/简报可见（可选展示"本轮注入了哪些节点"）。
2. 正文 tab 完善：目录、阅读、编辑、废弃/删除、章间导航。
3. 资料/图谱 tab：节点列表 + 关系可视化（轻量）+ 伏笔看板（open/resolved）。
4. 代笔控制台：启动/暂停/进度（挂 Phase B）。
5. 设置：模型策略（写作/审稿/状态同步三角色）、润色偏好迁移过来。

**出口标准**：日常"开书→写→收→改→挂机"全在新 UI 完成，无需回旧 UI。

---

## Phase D · 迁移与切换（全员切到新存储）

1. **首启自动迁移**：启动时把全部旧项目转工作区（原文件保留为回滚副本），进度可见、可跳过/重试。
2. **项目列表切新存储**：列表读 `NovelWorkspaceProjectRepository`；旧项目标记"已迁移"。
3. **边界处理**：降级只读项目、迁移失败项目、在途代笔（切换前先停）。
4. **灰度**：新旧入口并存一段时间（按 D4），观察后收口。

**出口标准**：冷启动后所有书都是工作区格式，旧路径不再被默认触达。

---

## Phase E · 旧引擎退役

1. 删 `feature/novel` 旧引擎（reducer/CAS/事实抽取/注入规划/recovery/旧序列化）。
2. 删旧 UI（`NovelWorkspacePage`/旧 VM/相关组件）与旧 DI。
3. 删迁移器本身（迁移完成、回滚期结束后）。
4. 全量回归 + 真机冒烟 + 更新文档。

**出口标准**：`feature/novel` 只剩 workspace 相关，代码量从 ~23k 大幅下降；无任何旧格式读写。

---

## 依赖与顺序

```
A（引擎可信）→ B（生产能力）→ C（UI 建成）→ D（迁移切换）→ E（退役）
D1 决策阻塞 A.6 与 B；D2 决策阻塞 C；D3 影响 B 体量；D4 影响 D/E。
```
A 是硬前置；C 的部分（资料/图谱 tab）可与 B 并行。

## 刻意不做（判为过度设计，永久放弃）

- 任意版本恢复/跨版本 diff/fork 多分支的完整 VCS（只做 D1 选定的安全网）
- CAS 修订号、操作重放账本、确定性 reducer
- 自动事实抽取 / delta / rebuild 状态机（被"节点+简报+新鲜度闭环"取代）
- 旧格式双向同步（只保留一次性迁移 + 工作区 zip 导出导入）

## 风险登记

| 风险 | 缓解 |
|---|---|
| 真实模型不守纪律/矛盾照出 | Phase A.1 真机调教 + 简报+矛盾检测双保险 |
| 自由路径写坏节点 | D1 内容安全网 + 节点写也可考虑加审批 |
| 代笔续跑错乱 | commit 游标 + 进程重启测试 |
| 迁移丢数据 | 原文件保留为回滚副本 + 灰度期 |
| 大书 token 成本 | 简报预算封顶 + 按需 grep，监测成本 |

---

## 跨端对齐（贯穿所有阶段，因 iOS/Android 路线分叉而新增）

背景（sess_4849dcc7 iOS 分析会话结论）：两端是**架构路线分叉**，非文件冲突——iOS 渐进（JSON 仍权威、保留旧 undo/fork/版本史、host 生成按章 plot 模块）；Android 激进（切权威、删旧引擎、降级 undo/fork、agent 维护节点）。若不收敛，未来工作区互导会语义不一致甚至丢数据。

### 分区原则：先分清"必须对齐"与"可以分叉"

**必须冻结对齐 = 互换契约（书本身）**：
- 目录 schema（manifest/project.md/setting//branches/<slug>/chapters|plot|plan|discarded/inbox//drafts/）
- manifest 格式 + formatVersion 门闩
- frontmatter 编解码（字段、yamlScalar、字段序=宿主插入序）
- 章节命名 NNN-slug.md（ordinal 为身份）
- 导入/导出语义

**可以各自分叉 = 平台内务（不出门）**：
- 账本：已验证 Android 导出跳过 `.amber`、导入重建账本 → **账本不跨端**，故"账本存不存内容/undo/fork 深度"（D1）是平台内政，**不阻塞互操作**
- 一致性机制（host 生成模块 vs agent 维护节点）、UI、代笔实现

### 真正的丢数据风险（须优先解）

Android 的节点扩展（frontmatter `status`/`relations`、`plot/foreshadowing/` 节点）iOS 目前不认识：iOS importer 只读已知路径、render 可能丢弃未知 frontmatter → **Android→iOS 往返会丢节点数据**。对策：
1. 把节点 schema（status/relations/foreshadowing）写成共享规格附录，定为"未知但须保留"的扩展。
2. 两端都必须**保留未知 frontmatter 与未知文件**（opaque 透传，不 drop）。
3. 加 Android 自身 export→import 往返测试，钉住 status/relations/foreshadowing 不丢。

### 跨端语义决策 —— 已定案（2026-08-19，唯一事实源：docs/novel-workspace-core-contract.md v1.1）

五条均按推荐拍板：

- **D-B 一笔提交**：正文 + 被更新的 plot/节点同一笔 commit。Android 本就按一笔设计，无需改；iOS 从两笔收敛。
- **D-C 写方维护节点 + 强制新鲜度检查**：写正文未更 plot/节点 → host 拦截/警告。**本 plan Phase A 的「新鲜度硬检查」由此确认为跨端标准，必做。**
- **D-D 中间章 unresolved 闸门 = 跨端标准**：改中间章 → 后章+plot 标 unresolved → 解开前禁写后章/收录/代笔。**Android 需新增此实现**（与新鲜度硬检查同批做）。
- **D-E iOS 最终也切 markdown 权威**：终态两端同一存储。
- **D-F 节点 schema 先 Android 实现、iOS opaque 保留**：跑稳后再评估升共同层。

**对 Android 计划的净影响**：Phase A 增加「unresolved 闸门」（与新鲜度硬检查同批）；其余不变。

### 交给 iOS 侧的跨团队项（本会话不改 iOS 代码，仅登记）

- importer 缺 plot 时应置 needsSync（现一律 synchronized）
- importer/render 保留未知 frontmatter 与 foreshadowing 等扩展文件
- 账本只写不读（status/log/diff/restore 读取路径缺失）

**Android 侧动作**：在 Phase A 增加"契约冻结 + 往返不丢测试"一项；节点 schema 附录随约定文档（conventions §8 已有雏形）输出给 iOS。

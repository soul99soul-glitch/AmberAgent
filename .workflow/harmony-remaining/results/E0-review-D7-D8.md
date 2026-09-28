# E0 · D7/D8 独立补审

日期：2026-09-28。初审基线：D7 `b633085d0668f47b996d380890a83f34ea004377`、D8 `6840fa551`；开始时业务工作树干净。原始发现部分行号指修复前基线，后续复审部分指当时修复源码。本报告保留初审与逐轮回喂，最终判定如下。

已读取 `.agents/skills/harmony-arkts/SKILL.md`、`docs/plans/2026-09-28-harmony-full-parity-plan.md` 与后续 E0–E14 计划。仅写本报告；没有修改业务代码、构建、签名、安装或操作设备。使用历史记忆仅定位 C8 既有小说链路，结论由当前源码和当前测试确认。

## 最终复审结论（E0 所审范围）

| 清单 | 最终结果 | 验证范围 |
|---|---|---|
| A 逻辑闭环 | PASS | 本章计划受控 write/delete/approve/reload/deny；复制格式；图片推理消费者；实测 nativepx 均分页、CSS 尾视口量化修复、主题 revision 与资源释放 |
| B 调用链 | PASS | 普通消息长按→独立动作 sheet→导出 overlay→系统保存→Gallery；Mermaid onPageEnd/bridge/偏移/实际图；本章计划两层白名单→原子批准写回与 receipt |
| C 已验 UI / 实际结果 | PASS | 新签名 Release：Chat 均分10张首尾与9→10交界；Mermaid 四片 Node00–25、暗色新PNG末片；亮/暗页脚、连线/箭头、无 Web 原生bar；精确未覆盖状态见末尾 |
| D8 范围裁定 | PASS | 初审基线的独立批次登记与实际缺失子系统一致；此判定不是 Recipes/插件已实现 |

E0 所分配阻断已关闭。F9 小说专用 diff/裁决卡仍准确登记 E13；原通用持久化闭环与专用 UI 完成度分开。当前导出是连续物理像素多图分片，允许字形/节点跨片，不能称为文本行重排分页或无上限单张长图。没有据此宣称全部 phase 或产品已经完成。

## 初审结论（修复前）

| 清单 | 结果 | 原因 |
|---|---|---|
| A 逻辑闭环 | FAIL | 长图实现无入口；图片推理开关未消费；纯文本复制仍为 Markdown 原文；本章计划提案路径被拒绝 |
| B 调用链 | FAIL | Mermaid 在 host 脚本加载前调用；未传 streamKey 的组件导出 ID 冲突；本章计划 proposal 未贯通到分支设定 |
| C UI 对齐与结果完整性 | FAIL / 视觉未验 | Mermaid 固定 300vp 截图会缺长图底部；单次长截图缺分段；小说审批仅工具胶囊，没有文档所述 diff；没有本轮设备截图 |
| D8 裁定一致性 | PASS | Recipes 与动态插件确实尚未实现，计划明确登记独立批次；不当作 D7 回归 |

## 具体发现

### F1 · P1 · 长图导出没有用户入口（A/B）

- 证据：`harmony/entry/src/main/ets/components/ChatExportSheet.ets:19` 仅定义组件；全 `harmony/` 搜索 `ChatExportSheet` 只有本文件两处，没有 import 或构造消费者。导出执行位于 `:67`。
- 实际入口：`harmony/entry/src/main/ets/pages/ChatListPage.ets:693-707` 的会话菜单只有复制 Markdown、导出 Markdown 文件；`ChatPage.ets:3372-3390` 的消息分享走纯文本 ShareKit。
- 触发：用户从会话列表或聊天消息动作寻找图片导出，无法调用 `startImageExport()`。
- 最小建议：沿既有消息动作或会话导出入口挂载该组件，并传入当前分支可见消息；保留既有 Markdown 导出。
- 已交主 agent 修复。

### F2 · P1 · Mermaid 首次加载时序断链（A/B）

- 证据：`harmony/entry/src/main/ets/components/MermaidBlock.ets:51-54` 在 `onControllerAttached` 中执行 `renderNow()`；`:37-39` 使用 `window.__amberRenderMermaid && ...` 并吞掉失败。host 函数在 `harmony/entry/src/main/resources/rawfile/mermaid_host.html:15-20` 的本地库与普通 script 执行后才注册。
- 触发：打开已完成、源码不再变化的历史 Mermaid 消息；controller 先绑定，host 尚未加载，首次调用不会绘图，也没有 `onPageEnd` 补调用；流式源码变化可能掩盖此问题。
- 官方依据：离线官方 FAQ `faqs-arkweb-21` 明确 `runJavaScript` 需在 `loadUrl` 完成后调用，例如 `onPageEnd`。仓库先例 `MiniAppRunnerPage.ets:594-596` 同样按加载完成使用 JS。
- 最小建议：在 `onPageEnd` 标记页面就绪并执行最新 source；源码 watch 仅在页面就绪后渲染。
- 已交主 agent 修复。

### F3 · P1 · 本章计划 proposal 并未闭环（A/B）

- 文档宣称：`docs/plans/2026-09-28-harmony-full-parity-plan.md:44` 写明 `novel_workspace_write patch plan/this-chapter.md` 走提案卡验收写回。
- 路径归一化：`harmony/deepread/src/main/ets/novel/creation.ts:1153-1156` 会将相对路径归一化到活动分支。
- 断点一：`harmony/deepread/src/main/ets/novel/workspace_history.ts:268-275` 只允许章节 Markdown 或 `plan/plot.md`。
- 断点二：`harmony/deepread/src/main/ets/novel/repository.ts:1104-1117` 只允许已有章节或 plot；`:1851-1884` 的批准应用没有 `branchSettings.thisChapterPlan` 分支。
- 当前真实文件映射：`repository.ts:633-640` 明确 `thisChapterPlan` 对应 `branches/<id>/plan/this-chapter.md`；`:677` 持久化，`:2126` 代笔冻结校验，`:2153` 成功提交后轮换计划。
- 窄复现：直接执行 `validateWorkspaceProposalPatch({ operation: 'write', path: 'branches/main/plan/this-chapter.md', content: '本章目标' }, 'main')`，得到 `NovelError: proposal patch 不是受控正文或剧情路径: branches/main/plan/this-chapter.md`。
- 最小建议：只扩展本章计划的 allowlist、target 检查与批准写回；delete 清空字符串；复用既有分支 CAS、receipt、原子快照。添加批准/驳回/删除/重载及重复批准测试。专用卡 UI 仍按 E13 执行。
- 已交 `fix_novel_plan` 修复。

### F4 · P2 · 纯文本复制仍保留 Markdown 标记（A）

- 证据：`harmony/entry/src/main/ets/components/ChatMessageCopySheet.ets:26-31` 的纯文本分支直接 `toText()`；`harmony/deepread/src/main/ets/agent/message.ts:188-189` 只拼接原始 text，不去除 Markdown。
- 触发：正文为 `**粗体**`、`[链接](https://example.com)` 或 fenced code；选择“纯文本”并全部复制，仍复制原始标记。普通仅 text 消息的两种格式实际相同。
- 最小建议：复用既有 Markdown AST 将正文转换为纯文本；Markdown 模式保留原文；逐段复制也消费同一选项。
- 已交 `fix_copy_mermaid_id` 修复。

### F5 · P2 · 无 streamKey 的 Mermaid 导出 ID 冲突（B）

- 证据：`harmony/entry/src/main/ets/components/MarkdownText.ets:770-772` 只用 `streamKey + block index` 生成 ID；`streamKey` 默认空。`ChatMessageReasoning.ets:155-159` 和 `ChatMessageToolPreviewSheet.ets:233-237` 没有传该 key。
- 触发：同一组件树中展开两条包含 Mermaid 的推理内容，两者首图均为 `mmd__0`；第二张“导出”仍按相同 ID 查树，可能取到第一张。
- 官方依据：离线官方组件截图指南明确 ID 全应用唯一，查找以首个结果为准。
- 最小建议：每个 MarkdownText 实例持有稳定唯一前缀，并保留图块索引；无需引入全局渲染状态。
- 已交 `fix_copy_mermaid_id` 修复。

### F6 · P2 · 纵向大 Mermaid 导出只保留 300vp 视口（C）

- 证据：`harmony/entry/src/main/ets/components/MermaidBlock.ets:46` 固定 `.height(300)`；`MarkdownText.ets:775-779` 对该 Web 组件直接 snapshot。host `mermaid_host.html:8-9` 允许文档内容滚动，SVG 按宽度缩放但不强制完整图落入 300vp。
- 触发：纵向节点链较多的 `flowchart TB` 超过 300vp；界面可滚动看到下半部，导出当前组件只保存当前视口，下半部不在 PNG 中。
- 官方依据：离线 FAQ `faqs-arkui-1423` 明确组件截图只截组件大小区域；超出内容需要滚动长截图。
- 最小建议：依据实际 SVG 高度形成完整导出，或对 Web 内容滚动分段截图；首次验收至少包含超过 300vp 的纵向图。
- 来源是当前源码与官方限制；本轮未做设备视觉复现。已告知主 agent。

### F7 · P1 · 长图缺分段，消息条数限制不能约束截图高度（A/C）

- 证据：`harmony/entry/src/main/ets/components/ChatExportSheet.ets:74` 一次截整棵树；`:87-89` 仅截最近 40 条；`:114-116` 的正文行数无限制，`:98-133` 无分段或拼接。
- 触发：40 条各约 1000 字、或单条数万字正文，渲染总高度可超设备 GPU 纹理高度；一次截图失败。D7-4 原计划要求“分段拼接”。
- 官方依据：离线 FAQ `faqs-arkui-481` 给出常见 8192×8192px 纹理限制与 `hidumper -s 10 -a 'vktextureLimit'` 查询方式，要求长内容分段截图拼接。
- 最小建议：按实际像素高度分段或采用滚动页截图拼接；条数上限可保留为文案，但不能替代分段。
- 本轮未操作设备查询实际纹理值；具体设备阈值尚未测得。已告知主 agent。

### F8 · P2 · 图片导出的“展开推理”状态没有消费者（A）

- 证据：`harmony/entry/src/main/ets/components/ChatExportSheet.ets:185-191` 将开关放在图片卡中；`:92-94` 的图片正文仅 `toText()`；仅 Markdown 导出 `:44-45` 消费 `expandReasoning`。
- 触发：含 reasoning 的会话开启“展开推理过程”后导出图片，结果没有推理内容。
- 最小建议：图片正文构造消费该开关，不复制额外状态。
- 主 agent 已纳入修复。

### F9 · P2 · 小说审批 UI 没有文档所述拟写入 diff（C / 裁定矛盾）

- 证据：`harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets:63` 设置 `openToolPreview: false`；`:1484-1500` 仅接批准、拒绝、回答。`AgentMessageRow.ets:378-392` 将该能力传下；`ChatMessageTools.ets:96-107` 只交给工具胶囊工具名、状态和 ✓/✕。没有传旧/新内容。
- 即便通用工具详情可用，`ChatMessageToolPreviewSheet.ets:248-266` 也仅有 input JsonTree，没有旧/新 diff。
- 触发：Agent 发出小说 workspace 写提案，用户直接看到工具胶囊审批按钮，不能从该入口核对拟改内容与旧内容；与 D7 范围裁定的“提案卡 diff→验收”描述不符。
- 最小建议：当前文档准确登记该 UI 缺口；E13 专用提案卡显示对应旧/新计划和批准/驳回，继续复用持久化和 continuation 链路。
- 已告知主 agent；不要求在 E0 重建大型小说 UI。

## 小说既有链路核实

- 候选裁决语义成立：`ghostwrite_runner.ts:281-305` 模型审核→校验→review checkpoint→commit；`repository.ts:2109-2122` 校验阶段、候选绑定与审核结果，`:2147-2189` 章节、下一计划与提交进度同一快照。阻塞审核不提交、两次定向重写及 replay 已由现有测试覆盖。
- 正文/plot 的工具审批闭环成立：`novel_workspace_tools.ts:150-157` 强制审批且不允许自动审批；`NovelWorkspacePage.ets:988-1001` 续跑同一 toolCallId；`creation.ts:1038-1080` 准备、checkpoint 与拒绝结案；`AppContainer.ets:1507-1515` write→workspaceWriteResult；`repository.ts:1805-1893` 结案、CAS 与快照持久化。该证据不能推导本章计划路径已支持。

## D8 裁定核实

- 全 Harmony 产品源搜索没有 `recipe__`、recipe runner/manifest、`plugin_import`、`plugin_test` 或插件中心页面；仅 `WebMountTools.ets:11` 注释登记 recipe_candidates 依赖。
- iOS 当前四个 `IOSRecipe*.swift` 共 4984 行，`RecipesView.swift` 1488 行，合计正好 6472 行/5 文件；计划规模依据与当前源一致。计划里的 `IOSRecipe*.swift` 基准应理解为四核心文件加 `RecipesView.swift`。
- iOS `DYNAMIC_PLUGIN_IMPLEMENTATION_PLAN.md:54,63-64` 明确插件中心、试运行 expected_result 与 expected_candidate_hash；Harmony 尚无该子系统，D8 标记为独立批次与当前事实一致。
- D8 没有新增产品入口，因此不产生本次 D7 UI 回归。后续 E4/E5 实施验收另计。

## 本轮验证与未测层

- Chat：`tsx --test src/test/novel_workspace_tools.test.ts src/test/novel_interactive_adapter.test.ts`，15/15 PASS。
- DeepRead：`tsx --test src/test/novel_ghostwrite_runner.test.ts src/test/novel_ghostwrite_repository.test.ts src/test/novel_workspace_history_repository.test.ts src/test/novel_c5_tool_continuation.test.ts`，34/34 PASS。
- 本章计划路径执行级窄复现得到上述 NovelError；未新增永久测试。
- 没有构建、签名、安装、亮暗色截图或相册输出验证；这些层不能用源审查或 49 条领域测试代替。主 agent 修复后需重新审查与设备验证。

## E0 修复窄复审回喂

本轮仅审查已经交付的本章计划、复制格式、实例 ID、导出入口与推理消费者。图像分页 worker 仍在修改 ChatExportSheet/MermaidBlock，本轮不提前验收其图像结果，也不重复主 agent 的 lint、build 或设备操作。

| 原发现 | 窄复审结果 | 当前证据 |
|---|---|---|
| F1 长图无入口 | 源码闭环 PASS，设备待验 | `ChatMessageActionsSheet.ets:66-69` 新增导出动作，`ChatPage.ets:4982` 开 overlay，`:5857-5863` 传 conversation 与 `currentMessages()`；`:994` 返回关闭 |
| F2 Mermaid 首次时序 | 源码闭环 PASS，历史图设备待验 | `MermaidBlock.ets:52-55` 改在 onPageEnd 调最新 source，离开清理 attached/timer；后续分页 worker 应保留这个时序 |
| F3 本章计划断链 | PASS | history 路径白名单只增 this-chapter；repository target/apply 同步贯通；保留 CAS/receipt/分支边界，审批 write 写入，delete 清空 |
| F4 复制格式 | PASS | `message_copy.ts` 复用 Markdown AST；`ChatMessageCopySheet.ets:26,87` 的全部/逐段都消费当前选项；代码字面字符保留 |
| F5 Mermaid ID 冲突 | 源码闭环 PASS，实际多图导出待验 | `MarkdownText.ets:152` 每实例一次 newId；`:770-772` ID 含实例前缀/key/图块索引，`:818-820` 下传 MermaidBlock |
| F6 Mermaid 300vp 截断 | 待图像修复/设备 | 旧外层 snapshot 导出已移除，正在迁移到 MermaidBlock；导出按钮与完整 SVG 高度结果尚不能验收 |
| F7 长图纹理上限 | 待图像修复/设备 | image_export_pages helper 正在集成，最终分页导出和真实长文本结果尚未审查 |
| F8 推理开关 | PASS | `ChatExportSheet.ets:87-88` 消费 messageImageExportText(msg, expandReasoning)，helper 与新回归分别覆盖开/关 |
| F9 小说专用 diff | 仍待 E13 | 范围表已准确登记：通用批准写回不代表专用卡与 diff 已完成；当前不能算完整小说 UI 验收 |

### 本章计划修复审查

- 只允许本分支 `plan/this-chapter.md`，没有扩大远期计划、偏好、跨分支或未知章节写权限。
- approve 的变更在既有原子快照路径落库，并更新 canonical `branchSettings.thisChapterPlan`；delete 语义与手工/代笔清空一致。
- 新增 `assertChapterPlanEditable` 抽取并复用原手工编辑的同一冻结检查；批准改变冻结计划失败时 proposal 保持 pending，拒绝仍可用，没有备用写通道。
- plan-only 改动保留现有剧情过期与 unresolved 状态，没有把一次计划批准当剧情同步；章节/plot 原有回归仍通过。
- 独立重跑 `novel_workspace_history.test.ts`、`novel_workspace_history_repository.test.ts`、`novel_c5_tool_continuation.test.ts`、`novel_ghostwrite_repository.test.ts`：32/32 PASS。没有新阻断项。

### 复制与导出接线修复审查

- `message_copy.ts` 对标题、格式、链接、列表、引用、表格转为可读文字，fenced/inline code 保留字面内容；Markdown 模式保持既有 reasoning/tool 策略。未新增另一套 Markdown 解析器。
- 导出由既有消息动作进入 overlay，传入当前选中消息分支；返回/关闭只清理导出状态，不改会话。
- `messageImageExportText` 只在开关打开时包含 reasoning，并已导出到 chat-domain，UI 有真实消费者。
- 独立重跑 `conversation_export.test.ts`、`message_copy.test.ts`、`markdown_blocks.test.ts`：58/58 PASS。

本轮独立回归合计 90/90 PASS；没有生产改动。总体 E0 的 C/UI 和图像交付仍未完成，不能用上述窄复审声明 D7 或全部产品已完成。已提醒主 agent 同步修正旧计划收尾中仍残留的“D7-1/D7-2 经通用管线闭环”历史措辞。

## F6/F7 新分页实现：源审查中间回喂

图像 worker 已将 ChatExportSheet/MermaidBlock 的整棵大图快照改为按真实测量高度分页、逐片打包、一次相册授权。此处只审查源码，不操作设备；中央构建和截图结果未到，F6/F7 仍保持设备验收待完成。

- `ChatExportSheet.ets:188-189` 从实际内容 Column 的 onAreaChange 度量 vp 高度，`:113` 转 nativepx 调用 `imageExportPages`；`:87-88` 从 display 的 nativepx 设 vp 宽高。没有以字数估算代替布局度量。
- `image_export_pages.ts:7-18` 连续覆盖 `ceil(contentHeightPx)`，每片不超过屏幕 nativepx，末片缩至剩余高度；独立重跑 `image_export_pages.test.ts` 5/5 PASS，覆盖 92783 行每行一次、精确倍数、分数与无效高度。
- Chat `:119-126`、Mermaid `:112-119` 先缩末页 viewport、等待布局，再滚动指定 top 并校验实际 offset；避免保持全屏末页导致 scroll clamp 重复尾段。实际 offset 的设备精度仍待中央验证。
- Mermaid host 有 `width=device-width, initial-scale=1`；离线官方 `faqs-arkweb-154` 与 `bpta-web-adaptation` 明确此时 H5 CSSpx 视觉上对应 vp。`MermaidBlock.ets:85-87` 读 host CSS 高度作为 vp、`:107` 转 nativepx、`:116` 将分页 nativepx top 转回 vp/CSSpx，单位链一致。
- `mermaid_host.html:23-29,35-52` 用 seq 过滤过时 render/测量，回传 revision；`MermaidBlock.ets:61-69,82-87` 在源码变化后立即失效旧 ready，并只接当前 revision；onPageEnd 再发最新源码。导出途中 revision 改变会退出并清理本批临时文件。
- 两处逐片 snapshot→pack→finally release PixelMap；`ImageSaveSupport.ets:14-48` 按页顺序一次 dialog 申请授权、逐文件复制并嵌套 finally 关闭源/目标句柄；取消空 URI 列表返回 false；打包失败移除临时文件并释放 packer；退出/失败/成功后各 capture finally 清理缓存。
- Chat 在启动时冻结 exportMessages/标题/推理选项；退出解除等待中的 frame，后续 alive/show 检查终止。Mermaid 同样解除 frame 并清理 debounce；只在仍活着时复位原滚动位置和 UI 状态。
- 当前只读审查没有新增确定的实现阻断。分页的产品结果已准确显示“内容较长时分为多张图片”，不将其表述为无上限单张拼接长图。

中央设备验收仍需提供：离屏 Scroll 的实际 PNG 非空且有正文；大于一屏并带短末页的导出无漏行/重复；长 TB 图包含最底节点；多图导出对应各自 ID；系统相册确认/取消与退出后清理。组件截图对实际父约束、离屏送绘和 Web 自渲染的结果不能由 helper 或源码检查代替。

### 图像二次补审：F10 · P1 · Mermaid 逻辑像素提前取整扩大末页偏移误差

后续单位细查发现：`MermaidBlock.ets:86` 将 host 实测 `info.height` 先按 CSSpx/vp `Math.ceil`，`:107` 再 vp2px，分页 helper 再按 nativepx ceil；末页则 `:117` 只容许 1 nativepx 滚动误差。两次不同单位的取整不等价。

触发例：SVG 按宽度缩放后的实际总高为 2000.1vp，density=3，屏高2856px。提前 ceil 得2001vp，即6003px；末页 top5712px、高291px（97vp）。真实 DOM 末页最大滚动约 `2000.1 - 97 = 1903.1vp`，期望为1904vp，差2.7nativepx，超过校验容差，报“图表滚动尚未就绪”。SVG 的 getBoundingClientRect 分数高度属于当前缩放布局正常输出。

最小修复：保留 host 浮点高度，只有 imageExportPages 对最终 nativepx 取整；关联回归检真实分页 helper 的末页最大 offset 与物理像素误差，避免源码文本断言。已发 `fix_image_export` 与主 agent，F6 继续待修/设备验收。

### F10 修复窄复审

- `MermaidBlock.ets:86` 已直接保存 `info.height`，CSSpx/vp 不再提前取整，分页 helper 仅对最终 nativepx ceil。源码缺陷关闭。
- 新回归实际调用 `imageExportPages`，覆盖 density 1/1.25/2/2.75/3/3.5 和多种分数高度，检真实几何最大 offset 与期望页 top 的差小于 1 nativepx；2000.1vp×3 的末页改为 top5712px、高289px。
- 独立重跑 `image_export_pages.test.ts` 与 `conversation_export.test.ts`，13/13 PASS；未放宽运行时 offset 校验。
- Web 的 scrollHeight/clientHeight 量化、真实 scrollY、离屏 Scroll 与 Web PNG、实际相册保存依然等待中央设备证据。F10 的源审查关闭不代替 F6/F7 的设备验收，也不代表全部 E0/产品完成。

### F1 入口可达性追加阻断：重复 bindSheet 宿主

独立 `e0_sheet_probe` 的只读框架审查发现：`ChatPage.ets:5908-5958` 将 9 个 bindSheet 连到同一根 Stack；`:5950` 的 actions(true) 后面还有 `:5955` 的 toolPreview(false)。API12 时期 ArkUI 框架按宿主 targetId 构造 SheetKey，false 会关闭同一 key，因此消息操作 sheet 仍可能无法显示，新增导出回调尚不能视为设备入口闭环。

该发现和固定官方框架 commit/行号证据已写 `.workflow/harmony-remaining/results/E0-sheet-probe.md`，由主 agent 安排 `e0_sheet_fix` 给各 Sheet 独立锚点。本报告引用该独立证据，不重复探索框架；F1 仍保持“接线源审查 PASS，入口/UI 待修复后的新鲜 HAP 设备验收”。

### F11 · P2 · 相册保存的 PhotoAccessHelper 未释放（A）

- 证据：`ImageSaveSupport.ets:30` 每次保存获得 PhotoAccessHelper 实例；`:31-48` 的保存成功、取消空 URI 和异常退出均无 release。该对象没有存为长期服务，函数结束后不再使用。
- 官方本地 SDK 依据：`/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/api/@ohos.file.photoAccessHelper.d.ts:3677-3688` 明确在实例 API 不再使用时调用 `release(): Promise<void>`，since10，API12 可用。
- 触发：反复保存或取消图像，每次取得的 helper 都缺少规范要求的确定释放；无需推测失败重试或加额外状态。
- 最小修复：保留现有保存逻辑，以 try/finally 覆盖 dialog→复制→toast，并在 finally await helper.release()。已告知主 agent 与图像 owner 等统一文件/构建安排。

F11 后续窄复审 PASS：保存 helper 已在创建后立即进入 try/finally；空 URI 返回、正常复制和异常路径都 await release，源/目标文件仍由嵌套 finally 关闭。释放失败不覆盖业务保存结果或原始错误。未重复中央 lint/build。

中央设备中间回喂：25 节点 Mermaid 的导出仍出现 offset mismatch。当前仅加入临时尺寸/偏移数字诊断，等待真实 scrollY/clientHeight/innerHeight 后再定位；F6 继续阻断，不能以 F10 几何 helper 的 13 条测试声明 Web 分页已成功。

只读视觉补审 `/tmp/e0-mermaid.jpeg`：纵向图在聊天中实际渲染，旧页脚的灰色文字落在深色代码块背景上，分页说明/导出按钮低对比。图像 owner 已窄修 Mermaid 整体 Column 为 SURFACE2 与圆角，设计 token 配对成立；最新亮/暗截图待验。该截图没有实际导出 PNG 或图底节点，仍不能关闭 F6/F7。图中自动命名错误条仅有视觉现象，未核 fixture/provider 响应，未据此登记产品回归。

### F6 真实 Web 几何回喂后的窄复审

中央数字实测：第 4 页目标 2448vp，总图高 2686.0224609375vp；native viewport 指定 238.285714vp，而 Web inner/clientHeight 量化为239CSSpx，实际最大 scrollY 为2446.857177734375。这个实测差超过 1 nativepx 校验，证明当前阻断来自 Web 尾视口量化，不能由纯浮点分页几何覆盖。

窄修源复审 PASS：`mermaid_host.html:31-40` 导出滚动时只临时增加 body.minHeight 为 top+innerHeight+1CSSpx，令目标偏移可达；原 #app 实测高度与 nativepx pages 不变，不把临时空白算入图内容。`MermaidBlock.ets:136-140` 在既有 finally 调 EndExport 清空 minHeight 与复位滚动；运行时 1nativepx 校验保留，临时 console/metrics 已移除。

新增 `mermaid_export_host.test.ts` 执行真实 host 脚本中的函数，DOM fixture 明确只重现已实测 CSS/native 几何，先复现旧 clamp，再检4页物理像素连续覆盖与还原；没有以源码文本匹配代替行为。独立同跑 image_export_pages/conversation_export/mermaid_export_host，15/15 PASS。该测试不是浏览器送绘或保存 PNG 验收；F6/F7 仍等更新 HAP 实际输出与首尾节点截图。

### F7 相册实图回喂后的均分页窄复审

只读查看中央旧相册截图 `/tmp/e0-chat-page9-photo.jpeg`、`/tmp/e0-chat-tail-photo.jpeg`：Chat 导出确实保存了10张；第9张有长文本末段与 END 标识，末张只有 bubble 底圆角/padding。这能确认离屏 Scroll 的 PNG 非空与系统相册实际有正文，不能视为末张 UX 合格。

主 agent 授权的窄修只修改共用 `imageExportPages`：仍 ceil 实测 nativepx 总高，仍以屏高计算最少页数，再用整数商余数均分各页；页高差≤1nativepx，每页≤floor屏高，top 连续累计，普通单页与总页数保持。没有加正文/空白识别、裁字、额外重试或估算高度。2000.1vp×3 的新均分末页为 top4001px、高2000px，取代上文历史修复时的 top5712px/289px；原 CSS/vp 不提前取整原则不变。

真实 host fixture 随新均分 pages 继续执行，2686.0224609375vp×3.5 总9402px分为2351/2351/2350/2350px，滚动余量/EndExport 还原仍通过。独立同组三套件17/17 PASS，包含9屏+92px的尾装饰复现、逐物理行恰好一次和多尺寸约束。此均分页源复审 PASS；更新 HAP 的首尾/交界实图与 Mermaid 最底节点仍待中央验证。

### F7 新签名 HAP 的尾页/交界独立视觉验收

主 agent 确认新签名 HAP 于12:47实际授权保存 Chat 均分10张。独立目测 `/tmp/e0-chat-balanced-page9.jpeg` 与 `/tmp/e0-chat-balanced-tail.jpeg`：第9张079–089完整，090第一行位于页尾；第10张接090第二行“page boundary.”，继续091–100、E0 IMAGE END，最后保留 bubble 底圆角和 padding。该交界未见重复、漏段或切字；文本字体、左对齐、边距正常，末张不再是独立留白装饰图。

F7 尾页修复与这处交界的设备运行视觉门 PASS，实际图片已保存到 Gallery。首图标题/起始标识仍等中央补图，Mermaid 保存用例继续待验；未由这两个截图推断其它功能或全部产品已完成。图像窄 lint 0error/7warn 与中央33文件0error/1910warn是主 agent 回喂记录，本审查未自行重跑 lint/build。

首图补审 `/tmp/e0-chat-balanced-first.jpeg`：标题、日期、用户 render-fixture、助手原文起始均齐；左/右对齐、边距与泡泡布局正常。Gallery 黑色上下边是查看器 letterbox，不是导出 PNG 内容。F7 实际导出/授权保存/首尾完整与这处9→10交界门通过。

边界表述须准确：首图底端将 N17→N18 源码行字形跨两片切开。当前算法提供连续物理像素分片，保证不丢失，不是按文本行重排的分页；只有9→10实测交界恰在行间，不能概括为“所有页都不切字”。已告知主 agent，按当前不加裁字/空白识别的窄改范围记录这个显示特征，没有引入另一套分页算法。Mermaid 实际图片与最底节点仍待验。

### F6 四张实际 Mermaid 输出独立视觉验收

中央新签名 HAP 实际保存4张（相册12:50）。独立查看 `/tmp/e0-mermaid-first.jpeg`、`/tmp/e0-mermaid-page2.jpeg`、`/tmp/e0-mermaid-page3.jpeg`、`/tmp/e0-mermaid-tail.jpeg`：Node00–25全部存在。第1→2片续接Node06标签/框，第2→3片续接12→13连线，第3→4片续接Node19标签/框，末节点25及底部留白完整；未见漏节点或重复片。Web snapshot 实际包含图内容，F6 的完整内容/偏移/保存设备运行门 PASS。该视觉结论限这组26节点纵向图；节点/字形跨片与当前像素分片机制一致，不宣称语义分页。

### F12 · P2 · Mermaid 导出 PNG 包含 Web 滚动条（C）

四张实际图片右侧均出现灰色 Web 滚动条（首片顶部、末片底部），这是阅读控件落入导出内容的明确视觉问题。`MermaidBlock.ets:147-163` 未隐藏 bar，host 导出滚动也未设置对应样式。

最小建议：仅导出开始/结束启用/恢复 CSS bar 隐藏，并用同组真实图验证是否控制此 ArkWeb bar。官方本地 FAQ `faqs-arkweb-77` 与 SDK WebAttribute 也提供 horizontal/verticalScrollBarAccess（since9），但 SDK 明确 @State 动态切换须 controller.refresh，会重载图；因此不要在当前导出中额外 refresh。若 CSS 对该 native bar 无效，可由主 agent 判断静态隐藏两个 bar 属性；无需泛化另一套导出系统。已发主 agent 与图像 owner，实际图内容门通过但此 C/UI 细节尚待修复复验。

F12 CSS 窄改源复审 PASS：host 只在 SetExportOffset 开启 html.amber-export，EndExport 移除；两条 scrollbar 伪元素样式仅在 class 生效时隐藏，原 body.minHeight/连续 pages/运行时偏移校验不变。真实 host 测试增加 class 开启/恢复断言，独立同组17/17 PASS。此处不宣称 ArkWeb 实际滚动条已消失，待中央新图。

### F1 普通产品入口设备运行门关闭

主 agent 回喂两轮普通 UI 路径：`hdc uitest longClick 1225,460` 用户 bubble→动作菜单→导出对话→导出 overlay；12:47 的均分10张也从同一路径进入图片导出、系统保存窗、允许。入口没有用 Want/DEBUG 开 sheet 绕过产品 UI，fixture 只提供消息内容。

独立查看 `/tmp/e0-actions-fixed.jpeg`，稳定动作菜单有“导出对话”，图标/文字/分隔线对齐正常；解析 `/tmp/e0-export-sheet.json`，导出格式、Markdown、图片、展开推理过程和关闭均 visible/enabled。加上已目测的 Gallery 首尾10张实图，F1 源码与实际入口门 PASS，重复 bindSheet 宿主阻断关闭。这个结论不扩为其余8种 sheet 全部功能已验收。

### F13 · P1 · 暗色 Mermaid 连线/箭头低对比（C）

独立查看中央 `/tmp/e0-mermaid-footer-dark.jpeg`：页脚分页说明与导出按钮已可读，确认整体 Column 背景修正有效；但浅色节点之间的近黑连线/箭头落在深灰 Web 背景上，几乎不可见。触发为 app darkMode 开启，而 host 仍在 `mermaid.initialize(... theme: 'default')` 下渲染，背景 token 已切暗但 SVG 配色没有切换。

主 agent 已限定图像 owner 做局部 darkMode StorageProp/Watch→既有 revision 失效→host dark/default主题初始化；继续复用 ready/分页/取消链，不修改全局主题，不加浏览器 prefers-color-scheme 作为另一来源。当前该明确暗色视觉门仍阻断，等待源复审与新暗色实图。

### F12/F13 后续窄改源复审

F12 的 export CSS 在13:04真实新 batch仍有 native bar（主 agent 数字/图像回喂），不能把源码样式存在当隐藏成功。现已移除无效 class/style，改 `MermaidBlock.ets:157-158` Web 静态 vertical/horizontalScrollBarAccess(false)，SDK since9、只控制paint；没有 @State 动态切换或 refresh，正常触摸滚动保留，图内可滚动文案仍存在。源审查 PASS，实际无 bar 图待验。

F13 源审查 PASS：`:22` 用既有 AppStorage darkMode 的 V1 StorageProp/Watch；`:62-70` 复用 sourceRevision 递增、旧ready失效与有限debounce；`:79` 传明确dark/default给host，`mermaid_host.html:47-49` 以strict配置初始化对应主题再render。没有全局主题改造、备用主题来源或另一套图渲染管线。测量/nativepx均分/EndExport与资源释放保持。

新增测试执行真实host生产函数，以明确库/DOM stub只核参数、strict配置和revision/height/ready协议；没有模拟Mermaid图像视觉通过。独立image_export_pages/conversation_export/mermaid_export_host同组18/18 PASS。F12/F13 的最终C门仍等待中央新版亮暗截图与实际PNG。

### F12/F13 新 Release 的暗色实际图像验收

中央最终Release签名HAP：独立查看 `/tmp/e0-mermaid-theme-dark-fixed.jpeg`，暗色图的节点边框、标签、连接线、箭头均已清楚可读；页脚说明与导出按钮可读。系统保存4页后，独立查看13:11相册新图 `/tmp/e0-mermaid-final-png-tail.jpeg`：Node19余段→20–25和底圆角完整，右侧原生滚动条消失。底部横条是系统/Gallery查看器覆盖，不记为导出PNG内容缺陷。

F12实际bar门关闭，F13暗色视觉门PASS；default恢复后的新版亮色footer仍等中央补图。没有再发现确定阻断项，最终A/B/C判定将在该补图后统一记录。

### 最终亮色补图与 C 门关闭

独立查看最终 Release `/tmp/e0-mermaid-theme-light-fixed.jpeg`：恢复 default 后紫色节点框、黑色连线/箭头与标签清楚；“图内可滚动 · 导出分为4张”和导出图片按钮在浅底上可读，布局、对齐、边距正常。截图右外侧灰条位于 Chat 列表边缘，不是导出 Web 的 bar；新暗色相册PNG已确认 Web bar 消失。主 agent 另实测正常手势滚动保持。本轮 A/B 与已验 C 主链均 PASS，没有剩余确定 E0 阻断。

### 精确验证边界

- 本轮只读审查与独立窄测试：本章计划32/32、复制/Markdown/导出58/58；图像最终同组18/18。后者含重复 conversation_export 用例，不相加伪称唯一覆盖数。lint、签名构建、安装和设备操作由主 agent 执行；我仅复核其文件/截图与明确步骤。
- 系统确认保存已实际验过，取消、捕获时离开/修改 source 的处理及临时文件清理是源码路径审查；本轮没有独立设备监测取消后缓存或 helper/PixelMap 存活数量。
- 推理开关两态实际图片、纯文本/Markdown剪贴板设备对比、reasoning/tool-preview 的跨 MarkdownText 多实例图组合未提供逐态设备截图；这些由已审源码和领域回归支撑，不列为已逐态视觉验过。
- 当前设备证据是一组1320×2856屏幕上的代表主链。横屏/分屏、其它设备密度、全部Mermaid图型没有逐项设备验收；多密度几何回归不替代这些硬件视觉状态。
- 小说通用本章计划审批持久化已闭环，但专用提案diff/裁决UI属于E13；D8仅做调研/裁定一致性核实，不代表其后独立子系统或全部产品完成。

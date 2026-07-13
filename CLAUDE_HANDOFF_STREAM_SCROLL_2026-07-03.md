# 流式滚动/渲染战役 Handoff(2026-07-03 晚)

上一份相关文档:CHAT_LIST_HANDOFF.md、CODEX_HANDOFF.md。本文档记录 2026-07-03 全天会话的战果、未竟事项与纪律,供下一个会话无损接手。

## 0. 一句话现状

聊天流式体验的**总根因已钉死并修复主体**(双渲染器排版分裂 538pt/70%),真机已装排版对齐版(legacy 滚动路径);**用户尚未给出五症状的最终弹着点反馈**——下一步的分叉点在等这个输入。

## 1. 工作区状态(全部未提交,严禁 commit/push/stash/reset/checkout,除非用户明确要求)

| 文件 | 内容 |
|---|---|
| `iosApp/iosApp/ChatScrollArbiterCore.swift` | M:tick 前向收编 + 无效写入抑制(修锯齿根因) |
| `iosApp/iosApp/ChatScrollArbiter.swift` | ??:仲裁者 UIKit 外壳(P3.2);**feature flag 默认已改为 false**(legacy 路径),注释说明了原因 |
| `iosApp/iosApp/ChatCollectionMessageList.swift` | M:P3.2 接线(会话前已有)+ 补偿常开注释 + snapshot 重入 guard + 死代码标记 + 缺陷 β TODO |
| `iosApp/iosApp/MessageBubbleView.swift` | M:streamingMarkdownConfig 对齐紧凑基准(17×scale/4×scale/8pt/表格 h12 v8/列宽300/bullet15/collapsesSoftBreaks) |
| `iosApp/iosApp/MarkdownView.swift` | M:AmberMarkdownView 加 `.frame(maxWidth:.infinity)+.fixedSize(h:false,v:true)` 修单行化低估 bug |
| `iosApp/iosApp/ChatComposerViews.swift` + `ChatView.swift` | M:provider/model sheet 原生 Liquid Glass(已完成验收的独立工作,勿回滚) |
| `iosApp/vendor/SwiftStreamingMarkdown/*`(7 文件) | M:排版参数化(paragraphLineSpacing/headingLineSpacing/listItemSpacing/tableCellPadding 拆分/bulletWidth/maxColumnWidth/collapsesSoftBreaks)。**默认值=旧硬编码,vendor 行为零变化**,仅 ChatAssistantMarkdownView 显式覆盖;vendor `make test` 含 snapshot 全绿 |
| `iosApp/iosAppTests/ChatStreamReplayTests.swift` | M:+~2000 行守护/诊断测试(见 §4) |
| `iosApp/iosAppTests/ChatScrollArbiterCoreTests.swift` | M:前向收编 3 新测试 + 4 旧测试按新语义更新 |

真机(iPhone Air, 94918570-...)已装以上全部;仲裁者关=legacy 滚动。

## 2. 分层框架与五症状归层(用户钦定方法论:正向分层,禁止补丁叠加)

- **L0 内容事实层**(高度/contentSize 必须真)→ **L1 锚定层**(批量更新瞬间不跳;ChatLayout 补偿=锚"最后可见行",天然阅读锚定,健康)→ **L2 意图层**(ChatViewportReducer,健康)→ **L3 马达层**(仲裁者)。
- 用户五症状:①起步抖动(L1/L3 起步并发,未动刀)②流式中滑动卡顿(渲染成本层,未动刀)③回底跳变(排版分裂为主,已修大头)④跟随错位内容画面外 ⑤结束后卡住划不上来(④⑤=排版分裂+缺陷 β;分裂已修,β 未修)。

## 3. 已钉死根因与已落地修复(全部有运行时证据,不是读码推断)

1. **仲裁者-补偿锯齿**(最初"一抖一抖"):CADisplayLink 回调先于 layout 阶段,补偿瞬移后仲裁者回写落后虚拟值,实测每 chunk 回跳 10-43pt。修复:Core.tick **前向收编**(基线=max(虚拟链, min(实时, target)),绝不回写低于实时的值)+ 写入抑制。守护:`testStreamingFollowOffsetStabilityDiagnostics` regressions=0。
2. **双渲染器排版分裂**(③④⑤总根源):流式 SwiftStreamingMarkdown 疏朗 vs 完成/历史 AmberMarkdownView 紧凑,同内容差 538pt/70%,cell 回收重建即"变身"。修复:用户拍板紧凑基准,流式侧参数全部对齐设计原值(**零负值零魔法数**;当年需要 -5.4 负行距即坐标系错误信号);两个语义因素:vendor softBreak 渲染成硬换行(+25pt/段,加 `collapsesSoftBreaks`)、表格列宽 cap 200→300。体裁级收敛 ≤4pt(无序列表除外,见 §5)。
3. **AmberMarkdownView 单行化低估**(存在已久的真 bug):UIHostingConfiguration 无约束提案下裸 Text 按理想单行宽报高,多行段落按 1 行计高,历史行长期低估 ~200pt。已修(fixedSize)。**用户会看到历史消息变高——是修复不是回归**。
4. **snapshot 重入崩溃**:`refreshLastAssistantRenderState` 无 `applyingSnapshotCount` guard → APPLYING_SNAPSHOTS_REENTRANTLY。已修。
5. **测量口径陷阱**(测试方法论):UIHostingController.sizeThatFits 对纯 SwiftUI 内容比真实 cell(UIHostingConfiguration)系统性高估 ~20-30%。**所有高度类测试必须用生产同构口径**(真实 controller+cell 渲染单行量 self-sizing 高度)。

## 4. 测试基建(iPhone 17 模拟器)

```bash
cd <repo>
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild test \
  -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17' \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentSimTestDerivedData \
  -only-testing:iosAppTests/ChatStreamReplayTests \
  -only-testing:iosAppTests/ChatScrollArbiterCoreTests 2>&1 | tail -60
# vendor: cd iosApp/vendor/SwiftStreamingMarkdown && make test
```

关键测试:`testStreamingFollowOffsetStabilityDiagnostics`(offset 单调守护)、`testStreamedRowSurvivesCellRecycleRoundtrip`(**XCTExpectFailure 记账中,被缺陷 β 阻塞**,β 修复后 unexpected-pass 自动翻红提醒掀开)、`testRendererHeightComparisonByGenre`(生产同构分体裁对照,[RENDERER-DIFF])、`runFreezeDiagnosisReplay`(滚离滚回场景引擎)、TallUserMessage 系列、仲裁者场景 5 件套。最近全量:49 绿 1 skip。

## 5. 未竟事项(按优先级)

1. **等用户五症状弹着点反馈**(已装排版对齐版但用户初判"感觉没解决";已提醒冷启动)。分叉:仍"卡住划不上来"→ 打 β;仍"跟随抖/卡"→ 属滚动手感层(legacy 未动),考虑重测仲裁者(见 3)。
2. **缺陷 β 专项**:ChatLayout 对回收重建行不重新询问 preferred 高度。已实验并回退两条死路:全局 `supportSelfSizingInvalidation=true` 会让仲裁者拖拽抢占+jitter 守护全红(失效重排覆盖用户 offset);cell 层强制重测破坏流式增长。建议方向:**仅对回收重建行的一次性 preferred 重询**。证据注释在 configureLayout 附近。
3. **仲裁者重开评估**:五症状灾难发生在排版分裂未修时,观察被污染;L0 干净后值得真机重测 flag-on(`chat.scroll.arbiter.enabled`,UserDefaults)。重开门槛(既定):结构性回放 fixture(卡片插入/思考块收起)+ 真机录制回归(M0 ChatStreamRecorder,`Library/Caches/stream-recordings/*.jsonl`)红转绿。
4. **症状 1/2**:起步抖动(起步阶段锚定+种子+补偿并发,曾发现种子 `max(currentOffset,bottomTarget)` 橡皮筋出界隐患未修)、滑动性能(每 16ms reconfigure+markdown 重解析成本)。
5. **记账**:无序列表 27pt(UITextView vs SwiftUI Text 对贴边 CJK 断行翻转,物理性,产品取舍:换引擎或接受);有序列表 4pt(不可感知,接受);死代码删除待授权(假 `ChatLayoutDelegate` extension + `ChatRowHeightCache`,标记在 ~:897)。

## 6. 纪律(用户铁律,违者返工)

- **全程中文**;**正向分层设计,症状归层自底向上修,禁止补丁叠加**;**根因必须运行时证据**(插桩/红→绿),读码只产生嫌疑——本仓库有害死人的"貌似承重"死代码,先验证代码真的在跑;**红→绿**:守护测试先复现(红)再修复(绿),禁止静默改断言。
- **模型分工**:主模型只做规划/决策/审查;实施→Sonnet 子代理;大规模读码/探索→Haiku(Explore, effort low)。
- **git**:不 commit/push/stash/reset/checkout。**装机前报备视觉/行为变量**;碰滚动/布局文件必须先过 ChatStreamReplayTests。
- 真机 build/install:
```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=00008150-000A594E0AF8401C' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -skipMacroValidation -skipPackagePluginValidation \
  -derivedDataPath /tmp/AmberAgentDeviceLiquidGlassDerivedData build
xcrun devicectl device install app --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  /tmp/AmberAgentDeviceLiquidGlassDerivedData/Build/Products/Debug-iphoneos/iosApp.app
```

## 7. 本日已完结的独立工作(勿回滚)

provider/model 选择 sheet:系统原生 Liquid Glass(删 presentationBackground/CornerRadius 覆盖)+ 单块半透明卡 + 紧凑 headline 头部 + 原生 `.buttonStyle(.glass)` 关闭钮 + 对勾选中态。用户已验收玻璃效果。

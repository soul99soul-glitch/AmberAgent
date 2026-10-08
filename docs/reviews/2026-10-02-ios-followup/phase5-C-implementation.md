# Phase 5 C 实施与验证

新增四个 Jev 用途默认关闭，并接通真实完成校验提示；既有近期会话标题 `context_selection` 保留原入口。

- `jev_models` 增加 toolContextSelection / contextRetention / autoApproval / completionCheck，独立 `allowToolOutput` 默认 false。旧设置加载、全局 mode / API / key / model / base 修改及单用途修改保留全部六个用途字段。
- `resolveJevPurposeMode` 实现全局 off / shadow 上限；窄 `jevPurposeConsentUnchanged` 比较目标用途许可与服务配置。`jevEvaluatePurpose` 复用原 noul / choice 协议，无新增题型。
- 固定 1200ms race 包括首次设置读取、短串行预算 reservation、后续配置读取和 HTTP。日 200 次在请求前预留，HTTP 不持有锁；parent abort 经现有 EntryAbortController 传递，finally 清 timer / listener。取消或 deadline 不写错误冷却，没有重试。
- 四用途按实际 core 数据准入：工具筛选/保留要求 output，自动批准复核要求 metadata，完成校验要求 TaskText（最终回复）。其他许可独立省略数据，不把模式做成必须全选才生效。完成 metadata 关闭时不发路径或本地检查事实，任何配置下不发工具输出正文。
- 评估入口第 5 个 optional `expectedSettings` 接收构建外发 payload 时的 snapshot；首次设置读取即比较，修复“可选许可在构建后关闭，但旧 state 仍外发”的真实 dispatch race。回程再次比较当前配置。A/B/C 分别传原 snapshot；旧四参调用兼容。
- `jev_completion` 根据最后 user 后的真实 file_write / file_edit / file_move 结果判定非文档变更；编辑要求 replace_count > 0 且文本确实不同，directory move 不当成文件变更。只认 terminal_execute completed、running=false、exit_code=0 的可确认检查，后台启动不当成通过。
- 检查命令只采最后可证明成功的 simple / && 链。quoted echo、`true || npm test`、`npm test || true`、`npm test; true` 不清除提示；`echo ...; npx vitest run` 最后真实检查和本仓 node build 脚本可确认。没有通用 shell parser。
- 最终回复非空，claims_done 或 claims_verified 概率 >= 0.8 才提示；只在正常成功、无待执行工具、非 manual/package 终态异步评估。SHADOW 不提示。
- `ConversationRunService` 仅保留 volatile notice，绑定 run / final ID / final content / 当前 selected history fingerprint。新 run、selected branch、tail、cancel、delete、session release 清旧提醒或取消待评估；迟到结果不保存捕获的旧 conversation。
- 小 `JevCompletionNoticeView` 承载视觉，ChatPage 只消费 snapshot 与回调。验证/关闭控制均最少 44×44；空 composer 且无附件/编辑时沿原 send 发送固定验证文本；草稿、附件、编辑存在时只追加文字，保持原内容与身份，等待用户手动发送。

验证与 review：

- 先 RED 再 GREEN：模型/用途、runtime budget / cancellation / deadline、completion 事实与 composer、真实 service volatile notice；独立 review 的 terminal 假检查、KV 延迟超时、同尾 user branch 旧提醒都已分别复现并修复。
- 最终 focused 组合 **42/42**，其中 actual Entry runtime suite **10/10**，覆盖 expected snapshot dispatch race。
- chat tsc 通过。
- 9 文件 ArkTS lint **0 errors / 310 warnings**；绝大多数为纯 TS type-only imports 的 CodeLinter 推断 warning，另含既有 ChatPage 性能 warning 和小组件建议。最后 optional snapshot 参数涉及的 3 个 Entry 文件 scoped 复查 **0 defects**。
- 独立 reviewer 实际 service 补测 tail / cancel / delete / release × pending / visible，8 scenarios 通过，旧提醒不添加 history saves；详见 `phase5-C-review.md`。

本记录仅确认 source / focused host tests / lint。没有调用付费 Jev 或 Provider，没有执行 build / sign / install。原生设备入口、配置行间距与明暗提示实际触区由 root 串行验收，不用 host tests 冒充完成。

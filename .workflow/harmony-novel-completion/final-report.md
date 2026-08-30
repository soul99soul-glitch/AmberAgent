# Final Report: Harmony Novel Completion

## Outcome

C0-C8 实现阶段完成。Harmony Novel 已从独立字符串生成页升级为 Chat-backed 作者工作台：canonical
富消息/思考/工具/ask-user/流式 Markdown、三职责模型、分支工作区、持久代笔、持久润色、后台与通知
都走单一生产链；C8 清退旧 adapter/fence/batch 入口并补齐 Harmony 历史迁移和交互 run 离页重连。

当前签名包已在指定真机安装并完成无模型配置状态下的布局验收。设备没有可用 Chat Provider/模型，
因此云端真实回合的本轮真机复验仍是外部配置门，不作为已验证事实。

## Accepted Results

- Chat 与 Novel 共用 `AgentMessageRow`、`InteractiveMessageTimeline`、`ChatInput` 和 provider/tool loop。
- 网络增量只由 Chat runtime 判定 `live/buffered/unavailable`；Novel 只消费 immutable snapshot。
- `ask_user` 与 workspace write 均是可 checkpoint、可审批、沿同 toolCallId 续跑的原生工具。
- writing/review/stateSync 三职责按项目/分支持久化；ghostwrite/polish candidate 与 review 强绑定。
- 代笔与润色均有 durable job、claim/lease/CAS/receipt、pause/resume/retry/cancel/cold recovery。
- Quick Start、普通讨论、续写、整章、章节重生成/润色、分支/版本/资料、导入导出均接生产服务。
- 旧 v2/v3 active project 与 C3 前 legacy deleted JSON 都有迁移 reader 和回归。

## Rejected Results

- 没有保留非流式整包后伪造 `delta/replace` 的 adapter。
- 没有继续用 fenced JSON 模拟 ask-user 或设定写入。
- 没有让 writer/reviewer 直接覆盖正史；只有 repository 原子 commit 能推进章节与 job cursor。
- 没有把无 Provider 的真机页面、安装成功或受控 fake 当作云端真模型成功。

## Conflicts Resolved

- 旧内存 batch polish 与 durable polish 双真相：删除旧 domain 入口，保留唯一 durable job 链。
- checkpoint transcript 与重连 active tail 重复：以 run 启动 historyCount 固定切尾。
- 页面离开中断 provider：改为解除订阅并在重进时接回同一 run/最新 snapshot。
- 代笔卡 + 空态挤掉输入器：空态归入共享 Timeline 并压缩为同屏横向布局。
- 用户脏零 caller sheet 与清理目标冲突：明确排除该 WIP，不覆盖用户改动，生产链已无 caller。

## Verification Evidence

- C8：DeepRead 767/767，Chat 2033/2033，双域 TypeScript clean。
- C8 logic gate：127 个聚焦回归 PASS；UI/detail gate 修复实测 clip/主题问题后 PASS。
- ArkTS lint 0 defects；AGC 签名构建成功；指定真机覆盖安装成功。
- 最终截图：`/tmp/c8-workspace-accepted.jpeg`；布局树：`/tmp/c8-workspace-accepted.json`。
- C0-C7 的逐阶段结果见本目录 `results/C0-integration.md` 至 `results/C7-integration.md`。

## Remaining Risks

- 指定真机没有可用 Chat Provider/模型；需配置后再执行云端 SSE、ask-user、5/10 章代笔、批量润色、
  后台通知点击的人工 acceptance，不能从当前设备状态推断成功。
- 已从历史文档删除明文 provider credential；该值应在对应服务端轮换。
- 用户 WIP `NovelBatchPolishSheet.ets` 已无生产 caller但仍为脏文件，未纳入提交。

## Reusable Follow-up

配置一个可用 Chat Provider 后，直接按 C8 真机矩阵运行，不需要再新增 Novel provider、Markdown、
tool loop 或 batch runner；若出现问题，应在共享 Chat runtime、durable repository 或当前 UI host seam 修复。

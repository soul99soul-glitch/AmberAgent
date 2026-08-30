# C8 Integration

## Accepted

- `NovelModelEvent` / `NovelRunEvent` 收敛为 snapshot-only；ghostwrite、polish、suggestion 与 creation
  统一从 canonical `UIMessage[]` 取最新助手正文。
- `NovelCreation.activeRun` 保留 run、启动 history 边界、user text 与时间；emitter 只回放最近 snapshot，
  页面离开不 cancel，重进后接回同一 run，终态统一清理并 reload。
- Quick Start 使用既有 `quick_start → generate → NovelChatRunAdapter → Chat provider/tool loop`；Novel
  继续共用 `AgentMessageRow`、`InteractiveMessageTimeline`、`ChatInput` 与实时 Markdown。
- Discussion prompt 攧为原生 `ask_user` / `novel_workspace_write`；旧 fenced parser、模拟卡和新写 helper
  删除，历史 persisted setting proposal 的读、展示与 resolve 继续保留。
- `restorePrevious` 同时扫描新 workspace 回收站与旧 `novel_deleted/*.novel.json`，按 updatedAt 择新；
  legacy 在项目独占锁内复读、拒绝覆盖，安装成功后才删源，损坏文件保留。
- 删除零 caller 的 `NovelModelAdapter.ets`、`NovelDiscussionAskCard.ets`、旧 batch domain 与私有 renderer；
  durable ghostwrite/polish 及 migration reader 保留。
- Novel 的 ChatInput 隐藏无实现附件和 core slash，三模式 placeholder 生效；滚动延迟回调有 token；
  触及交互补齐 44vp，浅色专用背景改用主题令牌。

## Review Fixes

- 真机首轮发现空项目提示位于 Timeline 外，和代笔卡共同把输入器挤出屏幕；空态移入共享 Timeline，
  空项目不再自动落底，并收为横向紧凑布局。最终输入器、模式 chip、Quick Start 同屏且无裁切。
- 离页重连最初只有领域 handle；补页面重新订阅、最新 snapshot 回放、固定 history slice 与 terminal race
  退订守卫，避免 checkpoint 尾重复和离页误中断。
- Chat 的 snapshot-only characterization 测试改以 runtime string kinds 继续断言没有旧事件，同时保持新 union
  的静态封闭性。
- 清理过时项目状态文档，并删除其中遗留的明文 provider credential；该凭据需按已泄露处理并轮换。

## Evidence

- DeepRead 767/767；Chat 2033/2033；双域 `tsc --noEmit` clean。
- C8 最终逻辑 gate 聚焦：DeepRead 116/116 + Chat adapter 11/11，PASS。
- 改动 ArkTS lint：`No defects found`；工具仍显示其通用 incomplete-report 横幅。
- `node scripts/harmony-inventory/build-harmony-app.mjs`：AGC `BUILD SUCCESSFUL`，HAP 签名成功。
- `5MT0225B12013017` 对 `entry-default-signed.hap` 覆盖安装成功。
- Logic gate PASS；UI/detail gate 首轮拦截代笔按钮 clip/主题硬编码，修复后最终 PASS。
- `/tmp/c8-workspace-accepted.jpeg` 与 `/tmp/c8-workspace-accepted.json`：Quick Start、三模式、输入器同屏；
  “开始代笔”、Quick Start 与三枚模式 chip 实测高度 149 px（44vp），bounds 等于 origBounds，无裁切。
- 设备仍未配置可用 Chat Provider/模型，因此本次没有把云端流式、ask-user、代笔/润色真模型回合伪报为
  真机成功；这些调用链由两域受控 provider 与 durable repository/runner 回归覆盖。

## Preserved User Work

- `.agents/skills/harmony-arkts/SKILL.md` 与 `NovelBatchPolishSheet.ets` 保持用户原有脏状态，未暂存。
- `NovelBatchPolishSheet.ets` 已确认零 caller；因含用户 WIP，本提交不删除，且不影响生产 bundle 调用链。

## Commit

本文件与 C8 实现同一提交；SHA 以该提交 Git 记录为准。

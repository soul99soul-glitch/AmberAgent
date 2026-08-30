# C6 Integration

## Accepted

- Durable job 覆盖 planning/writing/reviewing/rewriting_1/rewriting_2/committing 与所有终态。
- 1–10/default 5、plan freeze、strict candidate/review binding、最多两次 rewrite、final plan clear。
- `.amber/jobs.json` 纳入既有 staged install；最终提交一次写入 chapter、plot、plan、ledger、undo、receipt 和 job。
- writing/review/stateSync 模型职责与 Chat read-only tool profile；无 Novel 私有 provider/stream/tool loop。
- start/pause/resume/retry/cancel/cold recovery、owner lease/epoch、CAS fail closed、ancestry progress。
- 页面 durable card、真实模型门禁、轮询恢复与工作区 mutation 锁。

## Review Fixes

- 真机发现 global target 被静态推断为可用；改为调用 `NovelCreation.validateGhostwriteModels` 的同一真实校验。
- UI gate 发现 job 期间仍可进入资料库 mutation；job 出现时归位工作区设定并禁用资料库 chip。
- 逻辑 gate 发现 pause/cancel 未 abort in-flight Chat request；runner 增加 job→runId 瞬时句柄，终态先持久化再 cancel。
- 共享 ChatInput 的 disabled 覆盖整行、附件、slash 与发送；运行中 stop 仍映射安全暂停。

## Evidence

- DeepRead 732 passed；Chat 2033 passed；C6 focused 29 passed。
- ArkTS lint 0 defects；AGC `BUILD SUCCESSFUL`；`5MT0225B12013017` 安装成功。
- Logic review PASS；UI review PASS。
- `/tmp/c6-final.jpeg`：计划已就绪、模型未配置原因清晰、开始按钮禁用、无裁切或错位。
- 设备未配置可用 Chat Provider；因此没有声称真机云端章节生成完成，1/5/10 连续链由受控 provider 测试覆盖。

## Commit

本文件与 C6 实现同一提交；SHA 以该提交 Git 记录为准。

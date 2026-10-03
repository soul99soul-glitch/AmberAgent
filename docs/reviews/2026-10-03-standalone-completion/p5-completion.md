# P5 完成证据

小说运行与交换阶段全部实现；独立领域与 UI review 已分别复验精确发现。最后设备 gate 核对实际新包自动刷新，未跳过已确认缺陷。

- 润色：每项目偏好冻结、任意选章、逐章失败继续、同 durable job 定向重试；独立 58/58，实际第1章失败/第3章成功→仅重试第1章，原成功章只请求收录一次。
- 普通：原 runtime/input/context/tool snapshot 持久化，不保存密钥；可保留冷部分稿并显式原请求重试；支持官方 Responses 时同 ID GET 与 sequence 原子保存。工具 AskUser 真链失败/冷续接、两次失败部分稿、答案仅执行一次，独立 30领域+69adapter HTTP+7ETS 全通过；实际 HTTP Creation 4/4。
- 交换：生产仓库 public/native 安装往返、备份新身份10处 runtime cursor清除/同身份保留；clone lineage 与现 CAS，原收录记录保留。独立 22/22 与实际反例通过。
- UI：原请求恢复卡、选章/预览/重试、clone收录表单、归档原文折叠、正文保存与分析反馈均可达。95/95组合回归；已修复实际按值 Builder 冻结、进度计数/阶段左对齐/系统菜单颜色；最终设备 hot notice→writing→pause→continue→自动completed 同卡通过。
- 系统：三个格式实际 DocumentPicker 保存成功；TXT ShareKit文件面板798B（未发送）。通知热/冷点击定位 own project/branch/job，保活实际申请/授予/停止，UIAbility owner不会被Extension报告覆盖。
- 回归：Novel501/501、Chat2645/2645、Entry190/190、prepare6/6；两域tsc与scoped ArkTS lint0errors。两产品最新build/sign通过，novel独立包覆盖安装/冷启动。35源文件与两产品生成源码hash一致，HAP完整指纹见device/p5/final-artifact-fingerprints.json。

来源：p5-polish-review.md、p5-ordinary-review.md、p5-exchange-review.md、p5-ui-review.md、p5-workspace-ui-independent-review.md；具体真实设备交互见device/p5/device-cases.md及request-receipts.json。

本地合成provider用于请求/状态/设备UI；没有实测真实云模型质量或官方OpenAI后台服务。系统DATATRANSFER存在时限，不宣称无限后台存活。零宿主密钥复制、零调试权限旁路、未发布。

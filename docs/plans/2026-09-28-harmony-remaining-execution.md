# 鸿蒙剩余功能执行计划（E0–E14）

日期：2026-09-28。基线：`6840fa551`，工作树干净。
接续：[D0–D8 计划](2026-09-28-harmony-full-parity-plan.md)。

## 范围与完成定义

本次用户明确将原登记缓做项纳入实施：5 个大批次、11 个中小登记项。
既有 D0–D8 的“裁定收口”不等于这些功能已经实现；不重做已闭环部分。
明确不做的 Apple 服务、已下线今日看板、多语言、外部 CLI 席位、群聊、
Synara/ZCode 分享、个人上下文注入、设备绑定加密、WebMount 外部桌面服务、
MCP OAuth 和无主体的幻灯片字体维持原裁定。

完成必须有：真实入口 → 数据持久化 → 运行消费者 → 结果/错误反馈，
以及覆盖成功、失败、取消或切换边界的验证证据。占位入口、抛错登记和调研结论
不计为实现完成；外部账号或服务未验证时分别记录本地链路和未测层。

## 恢复证据

用户提供的两个路径相同：`~/.zcode/cli/log/zcode-2026-09-28.jsonl`。
日志按 sessionId 分出两个主任务：

- `sess_043c24cf-dc7b-45ec-ac66-4e6ed94d8537`：C 系列执行；Agent 曾遇验证码超时。
- `sess_1ef61546-bb82-4373-a86f-6482e6ebbc1d`：D 系列；D3 后审查遇 `exceed quota limit`。

接续时 Git 历史已有 C0–C6、D0–D8 提交。恢复基线时模拟器 `127.0.0.1:5555` 与真机
`6HK0226627000384` 在线；当前连接以 state.json / 各 gate 实际证据为准，不沿用历史在线状态。

## 阶段顺序和验收

| 阶段 | 实施内容 | 验收重点 |
|---|---|---|
| E0 | 补审 D3–D8，修复真实缺陷；建立测试/构建基线 | 余额、导入、工具声明、分类备份、Jev、子代理、议会、记忆、WebMount、渲染调用闭环；UI 截图 |
| E1 | Rust OHOS 交叉编译 + C++ NAPI + tokenizer 首条管线 | arm64 真机及模拟器可加载；同一文本夹具计数一致；无 JNI/Android 依赖 |
| E2 | SSH 终端（libssh2 + 静态 OpenSSL libcrypto） | 无凭据指纹探测→明确信任→认证；Asset 密钥加密私有凭据；PTY/作业/工具/UI 闭环；前台运行、后台断开不重发，远端 tmux 托管独立验证 |
| E3 | 内嵌 Python 和 Mosh | Python 本地执行/取消/文件边界；Mosh 通过 SSH 启动及 UDP 会话；真实能力独立验证 |
| E4 | Recipes | manifest 校验、存储、runner、recipe__name 工具注册、列表/运行结果 UI；沿用批准与工具权限 |
| E5 | 动态插件 | manifest、试运行、expected_result、内容 hash 注册、启停卸载、插件中心；注册内容与运行内容一致 |
| E6 | 多会话并行生成 | 按会话管理运行/取消；切页不 abort；回到会话重连状态；完成落库不串写；并发交错测试 |
| E7 | 记忆主题聚合派生文档 | 聚类输入、派生文档生成更新、来源追溯和查看 UI；源记忆保留、污染检测贯通 |
| E8 | 任务槽 Provider Override + 首次配置引导 | (providerId, modelId) 保存/迁移/全链路解析；解释 Chat 与小说/深读配置及复用入口 |
| E9 | Vertex SA + Antigravity OAuth | RS256 JWT 与 token 更新；Code Assist 请求封装及 onboarding；不虚构账号可用性 |
| E10 | Google 内置工具 | ChatModel.tools → Google 请求映射；url_context 与工具组合约束；服务商 UI 与请求一致 |
| E11 | Jev 审批分诊/Web 自动化 + 议会联网/FULL | 审批协议与 OFF/SHADOW/ACTIVE；席位级工具循环及主持人编排；取消、批准和结果回流 |
| E12 | WebMount 余量 | 站点 OAuth 配置、site_adapter、完整 HAR replay、run_goal 多步循环、tap；真实网络操作证据 |
| E13 | 小说专用裁决/章节计划卡 + DATA_TRANSFER 深化 | 专用 UI 消费既有通用管线；进度更新、超过十分钟续约、停止清理和生命周期验证 |
| E14 | 全产品集成与最终审查 | 关键调用链检查/类型检查/lint/build/sign；新增入口必要亮暗色截图；没有未修阻断项及未声明未测层 |

阶段按表推进。一个阶段内部可将互不冲突的代码或研究交给 subagent；不因某个外部
账号、站点服务或工具链问题把其他已授权、独立工作全部停住。遇到硬阻断保留具体证据，
不把“登记”改成“完成”。

E7 后续阶段预查校正：小说当前 global/fixed 模型已复用 Chat providers，不再要求重填
深读配置；E8 引导解释的是 Chat 与深读两个真实入口，保留模型配置 UUID 与 API modelId
的区别。E9 将 Gemini Code Assist 与 Antigravity 独立身份和请求封装分开核证，不靠名称互换。
E10 的 Google 内置工具需同时处理工具组合和真实响应 parts，不能只补请求字段。
E13 的 DATA_TRANSFER 进度更新和 manifest backgroundModes 配置按官方能力核实，
不把 updateBackgroundRunning 的类型更新当作续约。精确合同见各阶段设计与 packet。

E2 的平台边界已通过离线官方 `faqs-network-95` 查证：普通三方 SSH/Mosh 不能用
DATA_TRANSFER 承诺长期后台保活，TASK_KEEPING 需要系统 ACL。异步 job 不等同于
系统后台常驻；断线状态必须真实，回前台不得自动重发未确认命令。Asset SECRET
上限 1024 bytes，因此完整私钥使用 Asset 内的随机 AES 密钥加密应用私有文件，
不把普通 Preferences 的 getSecret/setSecret 当成安全仓。

## 每阶段闸门

1. 先核实代码及双端参考，明确最小可工作的行为；新 Harmony API 查离线官方文档。
2. 只为关键新逻辑和已确认缺陷保留必要用例；修 bug 先复现。优先局部模块，避免扩大 ChatPage 等大文件。
3. 仅运行受影响的必要检查、类型检查和 ArkTS lint（0 error），再构建并修编译诊断。已通过检查只有新变化、失败或未闭环证据才重复。
4. 独立 subagent 查 A 逻辑闭环、B 调用链、C UI 对齐，报告具体 file:line 和触发条件。
5. 主 agent 核实并修复具体问题；不增加无证据的重试、兜底和抽象。
6. UI/主题/生命周期改动签名、安装，取得必要亮暗色及状态截图即收口；不反复巡检模拟器。记录构建、安装、启动、外部能力验证各层。
7. 通过后按明确文件清单提交；不 `git add -A`，不覆盖他人改动，不强推。

## 调度与证据

执行状态：[state.json](../../.workflow/harmony-remaining/state.json)。
审查和验证落在 `.workflow/harmony-remaining/results/`，体积大的原始日志与截图留 `/tmp`。
主 agent 负责整合、构建和设备操作，避免多个 agent 同时操作同一设备。
subagent 各自有文件或审查范围，不回退其他人的改动，不擅自扩展产品范围。

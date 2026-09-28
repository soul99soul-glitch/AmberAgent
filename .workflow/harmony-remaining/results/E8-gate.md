# E8 gate: purpose provider identity and configuration guidance

2026-09-28。七个用途槽的源实现和独立 A/B 审查已收口。设备 C 仍 pending；本阶段没有追加设备恢复、模拟器矩阵或外网模型请求。最终实际编译/签名以本报告末尾回执为准，不把源码检查当视觉验收。

## 已落实调用闭环

- 新用途选择在原键保存 Provider 容器 ID + 模型配置 UUID；API 模型名只用于请求。旧 scalar UUID 兼容读取不写回、不猜 Provider；新 malformed pair 明确配置错误。fixed 只查原容器，不偷用其它 Provider 的重复 UUID。
- 有效 providerOverwrite 仍由原 factory/auth 应用一次；ChatProviderChoice 追加原 providerContainerId，生成回退引用不以有效 override 的 ID 猜原容器。worker/daydream 原 RMW 同次保存 UUID/provider/follow，保留未知字段与 E7 开关；候选与父会话回退顺序保留。
- 标题、建议、压缩、记忆提取、daydream/主题都实际消费 typed reference；每个调用只读取自己的用途键。E6 detached seed 仍保真实父轮选择。压缩与记忆请求保所选模型 customHeaders/customBodies；reasoning 保原用途值。
- OCR transformer 与健康探针消费同一 frozen choice/IMAGE gate；无 Chat fallback。原 VisionCache 只加 typed reference+原容器前缀，容量、过期、持久文件仍用原仓。生图装配和实际 execute 每次同一路径重读其槽，仍需 image/OpenAI/auth。
- 两设置页保存/清除/返回刷新/失效展示与实际用途一致；legacy 显示/高亮使用对应 consumer 真实选择，fixed 保精确原容器身份。44vp 操作、主题 tokens、safearea、长名布局与 ForEach 版本已源码审查。
- 配置说明真实区分 Chat/小说共用 Provider 与 DeepRead 独立客户端。复用入口只填三个 draft，原保存路径持久；有效 OpenAI API-key Chat Completions、真实 endpoint、单 Key 及可保留 headers/body 才可复用，不转换 OAuth/SA/其它协议凭据。

## 独立审查与精确修复

A `E8-logic-review.md`：legacy 聊天认证顺序、生图原预筛顺序、legacy 重复 UUID 名称/高亮三项 P2 已关闭。OCR 的原 first-UUID 身份独立保留，避免借聊天兼容修复偷换 OCR 服务商。
B `E8-ui-call-review.md`：无关坏槽阻断正常 Chat 的 P1、legacy UI 同项 P2、多个 API Key 草稿合并 Bearer 的 P2 已关闭。未扩大 fallback 或新增 framework。

## 只有必要验证

- 唯一选择组 `harmony/tests/task-model-selection.test.cjs` 初 PASS；confirmed legacy 认证回归在原组用实际 DI factory/resolver AST 与真实 domain auth/copy 提取 red→green。最终 `/tmp/e8-task-model-selection-green.log` 1 PASS，fixed A 不借 B、override 原容器引用保留。没有全 Chat suite。
- 唯一真实 Entry consumer 组 `harmony/chat/src/test/task_model_consumers.test.cjs`：同 URL+重复 UUID OCR 串缓存 red→最小前缀→green；同组追加 confirmed legacy image/OCR 两反例 red→green。最终 `/tmp/e8-consumers-legacy-green.log` 1 PASS；端口受控，临时文件和原 cache/seed/health/execute 真执行，不证明外网/设备成功。
- Domain/平台窄类型 0；root 实际 SDK/DI 五模块 types 0（`/tmp/e8-root-typecheck.log`）。最初 harness 未加载真实 AppStorage 全局声明，补 SDK common_ts_ets_api 后清零，不修改产品来迎合 harness。
- 窄 lint 0 error：初 domain/reference 26 跨文件解析 warnings；root 初 7 文件和受影响 review 修后 5 文件各仅4已有 performance warnings。consumer/两 UI/onboarding 均 affected final No defects；具体日志见各 owner 报告。
- 实际集成首次仅四个 UI Record literal 类型诊断；局部明确七字段 TaskTexts 与 switch accessor机械修，不改文案或选择行为。之后必要最终编译与签名如下。

## 最终 artifact / C gate

最终实际 Release `/tmp/e8-release-final-build.log` 9.256s PASS，generated BuildProfile release/DEBUG=false；`/tmp/e8-release-final-sign.log` exit0、Digest verify=true、verify-app success。构建保留已有 app-symbol symlink/zip warnings，未为无关符号打包做清理。 本地 Test-CA 包只验证签名，未装到原 AGC 签名设备。C pending：原签名设备安装启动、七槽保存返回/失效/OCR/生图真实服务和关键配置页亮暗视觉。当前 HDC 已记录离线，不再反复 polling。源 checkpoint 后继续 E9，保留全部实际未测层。

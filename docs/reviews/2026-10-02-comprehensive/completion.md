# 全面 review 与分阶段修复交付记录

本轮范围是当前 HarmonyOS 产品源码和开始时已有 WIP；Android 作为参考。HEAD `5ea51f7a548198ee4676fc63ef0b3bbfa34ba290`，branch `harmony-preview`；未提交、推送或创建 PR。

## 问题与阶段

最终二次核实清单见 findings.md，41 组（13 P1 / 28 P2）。同根因的多调用点合并；测试 fixture 的 9 个基线失败不算产品缺陷；不可达 legacy Index/UI06 已撤回。

| 阶段 | 问题组 | 独立复审 |
| --- | --- | --- |
| 0 审查 | 聊天、工具、小说、深读、存储/原生、UI；Standards/Spec 两轴 | 对照当前入口、规格、实际方法反例，假阳性与 by design 分开记录 |
| 1 | 8：小说分支/CAS/取消/恢复/undo/编号，数据库搜索和初始化 | novel-review、storage-review、harness-review 通过 |
| 2 | 21：OAuth 辅助请求、HTTP/retry/上下文取消、工具/子任务、议会/schema/私网分类、WebDAV/备份 IO | chat/auth/tools/deepread/backup 五项独立复审通过 |
| 3 | 9：UI 发送与草稿、搜索/通知/历史、Provider/OAuth 生命周期、讨论归档 | Chat UI、Provider lifecycle、Archive 独立复审通过 |
| 4 | 3：Codex 新增入口、实际跨页面 draft、Builder 字段刷新；收口 UI09 提示归属遗漏 | Provider entry/fields 及集成交叉复审通过；UI09 提示补修独立复审与设备检查通过 |

## 保护已有工作与修复范围

开始时快照 `/tmp/amber-harmony-review-20261002-baseline`，见 baseline.json。repair-manifest.json 与 repairs.patch 相对于这一开始时工作区生成，包含本轮增量，不把已有 WIP 混称本轮改动。最新增量 70 文件、其中生产 46；原快照文件缺失数 0。final-source-manifest.json 记录源文件及本轮回归的 SHA256，用于确认 build/install 期间源码漂移。

修复继续使用既有 CAS/lease、运行上下文、预算、认证归属与 AppStorage；未新增通用 fallback/重试、持久队列、并行状态机或整页 remount。主题 epoch 机制、深读保留旧稿/显式恢复、API12/13 PDF 能力门保留；大文件维护性风险只标记，未混入拆分。

## 最终验证

最终结果见 verification.json：

| 验证 | 结果 |
| --- | --- |
| Chat TS 全量 | 2486/2486 通过 |
| DeepRead TS 全量 | 1000/1000 通过 |
| Chat/DeepRead/Entry/Integration CJS 全量 | 515/515 通过 |
| 总计 | **4001/4001，0 failed / skipped / cancelled** |
| 两领域 tsc --noEmit | 均 exit 0，无诊断；之后领域源码未变 |
| 本轮生产改动 scoped ArkTS lint | 各批 exit 0 / 0 errors，warnings 保留；最终 Detail 0 defect |
| 应用构建 | BUILD SUCCESSFUL，15.287s |
| sign-hap.sh 离线签名/verify | exit 0 / Verify success |
| 最终安装包 verify-app | 通过，profile development cert 与已安装 bundle 指纹一致 |
| 同签名 HAP 覆盖安装 | 明确 install bundle successfully，未卸载 |
| 最终包启动 | force stop / start ability successfully |
| build/install 前后源及回归 hash | 906 项，drift 0；HEAD 未变 |

最终安装 HAP：`harmony/entry/build/default/outputs/default/entry-default-signed.hap`。
SHA256：`13ee6572a8eb04975a197f8f04b3ff8d37e2aeb0af604f82f4683a31748da270`。

日志：`/tmp/amber-review-final-chat-tests.log`、`...deepread-tests.log`、`...cjs-tests.log`、`...chat-tsc.log`、`...deepread-tsc.log`、`...build.log`、`...sign.log`、`...installed-signature.log`、`...install.log`、`...launch.log`；省略号均为同一 `/tmp/amber-review-final-` 前缀。

设备截图/AX 在 device/：聊天键盘、小说、深读历史、Provider、新增配置和浅/暗色主题。UI12 有真实设备初值、编辑回写、切模型再回配置保留、API mode 可编辑 base 的证据。最新包重录 final-provider-name-light / typing / retained / apikey-mode，实际确认模式切换后旧 Codex 错误提示消失；独立 reviewer 亲看最终截图。暗色新增表单检查及原浅色恢复见 final-provider-name-dark / final-theme-restored-light，此主题检查在最后四行认证提示补修前完成，未冒充额外暗色认证登录验收。测试草稿未保存；没有触发真实 OAuth、付费模型或 WebDAV 远端读写。原外观显式浅色已恢复。

`sign-hap.sh` 离线签名及校验通过，但它的自签证书与已安装应用不同，不能覆盖已有安装；一次安装输出明确拒绝后没有卸载。最终使用项目原 Hvigor/AGC 签名 HAP，development-certificate 指纹匹配既有安装，并检查 HDC 输出 `install bundle successfully` 后才 force-stop/start。使用默认 outputs 的 HAP，而非 signed/ 下的自签 HAP。

构建保留既有 debug-symbol symlink EEXIST/app-symbol.zip ENOENT 警告，应用构建成功；未删除构建缓存来掩盖警告。ArkTS lint error 门通过，已有 warnings 未宣称清零。

## 验收边界

真实 OAuth 云端、Provider 计费请求、WebDAV 服务端、系统后台长任务、物理设备及全 iOS parity 未验收。普通 API 模板在现有设备已全部配置并被过滤，未为测试删除用户 Provider；覆盖限于实际 callback、跨独立模块上下文/AppStorage 和表单设备分支，不宣称该按钮设备点击通过。

# Phase 0：生产基线与行为保护

基线 `cf0292929`；本阶段不改生产行为。**范围更正：用户随后明确只处理鸿蒙，本文 Android/Web 内容仅为既有历史检查记录，不再继续这些检查或改动。**完整原始日志在 `/tmp/amber-modularization-cf0292929/`。

## 已确认

- `baseline.json` 记录 50 个超 1000 行自维护生产目录文件、17 个超 1500 行文件。
- 独立 reviewer 核对全部 17 项阶段归属，无遗漏；补入 council manager、section writer 的职责复核，PageTemplate 明确保留。
- 新增真实 Entry Cron factory 的 5 项行为测试，通过且独立审查通过。测试运行实际工具循环、协议适配、审批 gate 与 repository，验证多轮工具结果续传、参数/signal、审批保存后失败、取消及通知/release 顺序。
- Chat 与 DeepRead `tsc --noEmit` 均 exit 0。
- Node 26 完整门有终态：4947 tests，4945 pass、2 fail、0 cancelled。旧中断日志中的 426 cancelled 未复现，不判为产品回归。
- 两失败文件在 Node 24 focused 检查 20/20 通过。源码核对识别出测试同步问题：collectMessage 的 80ms 墙钟阈值在全门负载下超时；author brief fixture 以 request 入队代替 writer held 信号。已用明确同步信号修复并保留/加强业务断言；修后 20/20、两文件 lint 与 DeepRead tsc 通过，独立 reviewer 复核通过。
- 本仓原生依赖第一次构建因旧临时工作树路径残留在 CMake 缓存失败；CMake 重生成后原生与 ArkTS 编译、HAP 打包通过。没有修改原生业务代码或清空缓存。
- `assembleApp` 最终签名失败：本机 AGC keystore 密码校验失败。仓库已有离线模拟器签名与 verify exit 0，未修改 AGC 凭据；签名产物仍需单独安装验收。
- Web 依赖从现有 frozen lockfile 安装，锁文件 SHA 未变化；typecheck、只读 oxlint、react-router build exit 0；fmt:check 报原有 22 文件格式问题。本轮未批量格式化，未触发 copy.ts。

## 产品与设备边界

- Harmony 默认 host bundle 是 `app.amber.deepread`，`ProductIdentity` 将 `app.amber.deepread.reader` 才识别为独立 DeepRead；不能仅凭 app 名称判断产品。
- Android 本仓 base ID 是 `app.amber.agent`，debug 有 `.graphite` 后缀。现有模拟器缺该产品，已有独立 DeepRead 截图不能作本仓聊天页验收。
- 初次 Harmony hdc target 在线但 shell/start/snapshot 间歇超时，没有可用基线截图，不据源码臆断 UI 正确或错位。正确产物保留数据安装后补检查。
- 系统 scheduler、真实通知投递、真实 provider、跨 Ability 后台和物理设备仍需各自证据；Node 通过不代替端侧验收。

## 独立 review

`p0_review_ui` 核对计划与 Cron 测试：测试抽取生产 factory 原声明，没有复制实现、删除或弱化旧断言；不阻挡装配迁移。随后对两项同步 fixture 修复独立复核，通过：明确 gate 比墙钟更直接证明非阻塞，writer held 信号消除测试假竞态且保留完整业务断言。

## 收尾待完成

- Android `:ai:testDebugUnitTest` 142/142 通过；离线 `:app:testDebugUnitTest` 在 resource 前置依赖缺缓存处失败，未到 Kotlin/测试。当前原命令联网补齐固定版本依赖，不改配置或版本。
- Pura90 发生 FreezeCrash 后退出，已保留数据 coldboot 恢复连接。当前离线签名 HAP 的保留数据安装失败（9568332 sign info inconsistent），不卸载/清数据，正在核对可复用签名材料。旧安装截图只能作 installed baseline；本次 HEAD UI 门保持未验证。
- 上述端侧与环境缺口不改变生产行为基线或模块化范围；Phase 1 继续代码整理，阶段性/最终交付必须单列这些门，不能以编译/Node 结果宣称 UI 或产品验收完成。

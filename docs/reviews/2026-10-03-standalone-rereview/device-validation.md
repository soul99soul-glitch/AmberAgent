# 本轮新包设备验证

2026-10-03，沿用已运行的 `127.0.0.1:5555` 模拟器，1320×2856px、3.5px/vp。root 独占构建与设备；没有新开模拟器。以下证据均来自本轮修复后的新包，早期布局草稿检查另外明确记录。

## 构建、签名、安装与产物对应

- `node scripts/harmony-standalone/build.mjs --product all --sign` 实际 exit 0；两个产品均 BUILD SUCCESSFUL，离线 debug HAP 签名、digest/codesign verification 成功。完整日志 `/tmp/amber-r2-final-build.log`。这是模拟器 debug 签名，不是零售分发签名。
- 两个已签名 HAP 均以 `hdc install -r` 覆盖安装，分别返回 `install bundle successfully`，随后实际启动。
- [final-artifact-fingerprints.json](final-artifact-fingerprints.json) 对比全部804份生产 TS/ETS：当前源码与两个生成产品对应副本全部一致。本轮相对审查前基线仅10个生产文件变化。测试前提修正没有生产变化，不需要重复构建。
- 实际解开两个 HAP 的 `module.json`，包名分别为 `app.amber.novel`、`app.amber.deepread.reader`。[installed-identities.json](installed-identities.json) 是当前 `bm dump` 数据：Novel UID20020074、DeepRead UID20020075、原host UID20020073。只覆盖安装两个独立产品，保留各自原沙箱与原host。
- 新包 Novel SHA256 `acc79f3fbf89da6055ad528415b262227adbce004b1a0d29799e47f833af7667`；DeepRead `493852710e38653e5a086adad553a4c41df5902c08736915d2fd11d90ba7afa3`。

## R2-UI-01：保存热区与真实边缘点击

新包实际打开原6章项目、当前共创分支的项目控制，滚至偏好／操作分节。[保存新包帧](device/novel-control-save-after.jpeg) 与同名AX确认 clickable Text 的 `bounds=origBounds=[1103,1444][1257,1598]`，154×154px即44×44vp。修前新证据为 `[1158,982][1257,1136]`，99×154px即28.3×44vp。

实际点击 `(1110,1520)`，处于旧热区左边界之外、新热区之内。[边缘点击结果](device/novel-control-save-edge-result.jpeg) 与AX实际出现“已保存到当前分支”，回执与保存按钮不重叠。UI subagent 重新查看两张实际图片及AX，独立确认关闭finding。

布局检查时临时输入的 `R2 draft` 没有保存：重开项目设置后的 [原项目偏好](device/novel-project-preference-unchanged.jpeg) 只有 `P2 project polish preference`；重开控制后的 `novel-control-bottom-reload.json` 偏好为空且没有R2draft。边缘点击保存的是原后续计划和原空偏好。新包根页仍显示原项目6章，未清空数据。

## INT-01：实际 DATA_TRANSFER 通知点击

使用原独立DeepRead里的自建文章 `Local Reader Source Verification` 及已配置的本地受控模型，重新生成完整旧稿。实际确认框、新包运行帧显示“重新生成中，原文章已保留”；旧标题、正文、版式仍可见。本地fixture仅延迟本轮规划流以留出通知操作时间，未改应用生产代码或使用云provider凭据。

按Home让UIAbility进入后台，下拉通知中心。[实际保活通知](device/deepread-live-notification.jpeg) 顶部独立DATA_TRANSFER卡显示 `Local Reader Source Verification · 深读中`、模型输出；它与下方“正在深度阅读”普通通知是两个不同卡。

实际点击顶部保活卡，得到 [通知打开文章](device/deepread-live-notification-opened.jpeg) 与AX：文章标题正确，原正文和版式显示，运行条仍为“重新生成中，原文章已保留”，取消生成可达，没有“文章参数缺失”。该证据是系统保活通知的实际点击，不是 `aa start` 传参模拟，也没有把下方普通通知点击当成保活通知验收。

本轮规划及四段共5次实际本地HTTP请求完成后，[最终完成帧](device/deepread-notification-final-result.jpeg) 恢复“完成”、导出、保存到工作区与重新生成；标题及版式保持。再次后台并展开通知中心，[终态通知帧](device/deepread-notification-terminal-cleanup.jpeg) 显示本文章“深度阅读完成”，独立DATA_TRANSFER保活卡已经移除，普通“正在深度阅读”通知已替换为完成通知。

验收结束已停止本轮自行启动的fixture服务，并只移除自行添加的18771 reverse。原18765 reverse保持。没有遗留本轮模型任务或后台保活卡。

## 证据范围

新设备验证涵盖新包保存热区／边缘点击、保活卡实际点击及原数据保留。IME布局新帧与600字符标题／late owner替换的独立源码、真实函数链测试分别见 [ui.md](ui.md) 和 [integration.md](integration.md)。同前60字符的新旧任务替换去重由真实bridge+KeepAlive+lease探针证明，没有将普通标题设备点击冒充该竞态设备复现。

这些结论不代表所有主题×系统字号×方向组合、零售API12设备、真实Google在线页面或云模型的全面验收，也不代表无限后台运行。没有为未覆盖组合添加新的兜底设计。

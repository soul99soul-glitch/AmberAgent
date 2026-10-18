# 合并后浏览器揭示诊断

日期：2026-10-18。仅只读诊断；未修改仓库产品源或测试源，未扩大修复范围，未提交或推送。

## 判断

没有确认合并引入的浏览器产品缺陷。首跑在 sandbox 的 started(page) 处 5 秒超时，未再次复现相同失败。随后完整诊断运行 **8/8 场景通过并以 exit 0 退出**。再原样执行生产测试，全部 8 场景断言和 8 张截图完成，停在最终 `browser.close()`；按主 agent 要求停止等待并向所属测试会话发 Ctrl-C，以 exit 130 退出。后者应记录为浏览器驱动收尾未正常结束，不能记作整条测试命令通过。

测试载入的 7 个浏览器相关文件已逐字节与远端 `104f07f8c` 核对，全部相同：`svg-host.html`、`host.js`、`ui-generation.js`、`ui-generation.css`、`amber-ui-generation.js`、`UIGenerationMotion.ets`、`ui_generation_browser.test.py`。因此没有这些文件的合并不兼容差异。最初超时只能标为一次未复现的运行/时序失败，尚无精确根因证据。

## 实际证据

- 首次失败日志：`merged-browser-tests.log`。
- 加只读事件、pageerror、requestfailed 及失败时 status/DOM/iframe 几何采样的诊断脚本：`browser-diagnostic.py`。该脚本仅存在 delivery 文件夹，未替换仓库测试。
- 诊断成功输出：`merged-browser-diagnostic.log`，来自工具会话 9202 的原始连续输出，exit 0，PASS 8 real-browser scenarios。无 pageerror。没有发生超时，故失败分支中的额外 status/DOM 采样未触发；原始测试本身的 started、遮罩存在、坐标、主题、高度、恢复及 sandbox 断言全部执行通过。
- 唯一网络错误确定是 `http://127.0.0.1/slow.png net::ERR_FAILED`，来自测试主动 abort 未完成图片请求的超时用例，不是揭示资源请求失败。
- 原样复跑命令：`/tmp/amber-round2-browser-20261018/bin/python harmony/entry/src/test/ui_generation_browser.test.py`，日志 `merged-browser-tests-recheck.log`。Ctrl-C 栈明确停在第 150 行 `browser.close()`；之前第 149 行的最后一张 completed 截图已保存。全部 4 个非沙箱场景及 4 个沙箱组合中的断言均已越过。
- 原样复跑截图保存在 `browser-recheck-screenshots/`，320/390 × 浅/深 × wireframe/complete 共 8 张。

## 资源清理与限制

诊断成功运行自行执行 browser.close 并 exit 0。原样复跑收尾期间，其进程树已无 Chrome 子进程；Ctrl-C 后再次确认诊断 Python PID 95484、原样 Python PID 99240、其 Playwright driver PID 99244 及其子进程均不存在。未关闭、操作或清理用户预先存在的 Chrome 窗口。

没有再次重跑，也没有为交付新增重试、延长超时或产品兜底。该浏览器验证不能替代目标 ArkWeb/设备验收；主 agent 的原生 lint、构建和模拟器验证另行记录。

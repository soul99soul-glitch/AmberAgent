# P9 独立最终出口审查

状态：**PASS，P9 完成条件闭环。** 最终新包真实改名保存回执及系统取回文件已独立核对；没有尚需修复的确证生产或 UI 问题。

2026-10-03；本 reviewer 独占本报告，只读生产与其他报告，不构建、签名、安装或操作 HDC，不改动他人工作。

## 已独立核验

- 已读 P9 计划、PDF／成果文件两份独立 review 与 R2 回归／总结。直接重新计算 804 个生产 TS/ETS 源文件及两产品副本 SHA256，全部匹配当前指纹；对两份签名 HAP 重新计算大小和 SHA256，均匹配。
- 与 R2 源指纹逐项比较仅 NovelWorkspacePage.ets 改变。将当前成功 toast 一行在内存还原为旧 `已导出 ${fileName}` 后，整个文件 SHA256 恰好等于 R2 指纹：可独立证明本 phase 的生产变更确为一行“文件已导出”，没有改变保存字节、格式、导航或任务逻辑。
- 直接读取实际 TXT／公开 ZIP／native ZIP，大小与 SHA 均与文件 review 一致；公开 38 entries、native 139 entries，ZIP CRC 与唯一路径检查通过。native 的 138 payload 路径与每文件字节完全等于源设备项目树；原项目导入后 138 文件仍与导入前逐字相等。
- 直接核对真实恢复目录：原／公开／native 三个 project.id 不同；native 当前 19 条创作消息、6 章对象及资料与源逐字段相等，公开 messages=0 符合公开契约。公开章节保留 id/title/content，时间戳、缺省 ordinal 和私有 suggestionWarning 映射差异属于公开格式边界，不当作数据丢失。两位领域 reviewer 的实际 production repository 重新打开设备字节验证可支持完整分支／历史引用；本 reviewer 不将它替代实际 UI 冷启动。
- 已独立对两份真实 PDF 再用 pypdf strict=True／PyMuPDF 解析：普通2页、180%3页，实际全文去空白均379字符且相等，所有span均在页界内；标题22.5→40.5pt、正文11.625→20.925pt精确1.8倍。已再目测普通 2 页与180% 3 页全部 PDF 渲染。层级、内容顺序、左右边距与字形清楚，无横向溢出、重叠、方块字或半字截断；大倍率核心要点卡跨页与稀疏尾页为真实分页观察，不构成已定义的缺陷，不额外设计打印版式。
- 已目测实际完整备份冲突预览 p9-native-import-preview：底部弹层显示完整备份范围、保留两份／替换／取消，间距、换行和按钮对齐正常。p9-final-novel-cold-projects-ready 显示三个6章项目；p9-final-public-ready 显示公开副本新创作空态；p9-final-public-six-ready 显示全部 01–06 章，行距／左右对齐一致。
- p9-final-native-cold-chapters 实际显示完成润色任务卡及目录总数6、01–03与04上缘；它不能单独证明整屏看到了全部6章，但真实设备文件已独立重读6章。部分 native-six/native-lower/public-cold 文件名对应的帧实际为项目列表，本 review 不用文件名宣称通过。
- 日志 /tmp/amber-p9-entry-tests.log 汇总241/241、0失败/取消/跳过；单文件 lint 显示 No defects found；双产品构建／签名日志显示 BUILD SUCCESSFUL 与 digest/codesign verify success。R2 4554/4554 是既有领域回归，P9未改领域源码，不声称本轮再次运行全部4554或将241重复累加。

- 已读 root 的 device-validation.md，区分新包构建／签名／安装／冷启动与 R2 导出字节证据，外部 gate 没有被扩写为通过。独立再看 p9-final-deepread-cold-ready 与 p9-final-deepread-cold-article-100：独立首页、完成文章、捕获杂志·立场、保存／导出入口及100%摘要版式可见；操作栏对齐、内容左右边距与行距正常，滚动底边裁切属于正常视口行为。取回新包 p9-novel-toast-verified.txt 为1154bytes，与初次 TXT 逐字相同。

## 最终关闭证据

- 最终 root summary.md 与更新 device-validation.md、exported-file-fingerprints.json 已读。再次核对新包系统保存图 `p9-final-toast-final-picker.jpeg`，实际 Download 文件名为 p9-novel-toast-final.txt；`p9-final-toast-frame-03.jpeg` 实际清晰显示“文件已导出”，不是旧建议名，也不是系统等待层。toast 尺寸与文字居中正常，不改变既有布局。
- 最终系统取回 TXT 1154bytes，SHA256 `7df6771ee971b1bfd22c42fadb45d75e974f8c4a9f73f702894e236b145d25ba`，与旧产物每字节相同。成功回执位于 picker 返回非空URI、实际写完并关闭之后；取消与失败路径未变。P3已关闭。
- 本阶段真实系统文件、格式边界与 keepBoth 新身份、设备持久树重读及新包冷启动、PDF普通／180%逐页视觉、精准一行修复后的测试／lint／双包签名安装、当前804源指纹和独立 review 均有对应证据，符合P9计划退出条件。

独立复审没有新增P1/P2或未关闭P3发现。早期自动化采集误帧已被明确剔除；PDF尾页留白、滚动视口裁切、公开格式的私有字段边界以及native新身份清除旧执行游标均没有被夸大为缺陷。真实手机／零售签名、云端非空response游标、Google在线和任意自定义CSS／远程图像保持未验证，不外推完全iOS等价。本review未改生产或他人报告。

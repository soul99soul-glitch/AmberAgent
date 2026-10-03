# P9 真实设备与成果文件验收

## 环境和产物

设备为既有模拟器 `127.0.0.1:5555`，1320×2856 px，3.5 px/vp。仅操作自建验收作品，不卸载、不删除、不改宿主密钥或签名权限。P9新包由当前工作树构建：`node scripts/harmony-standalone/build.mjs --product all --sign`，日志 `/tmp/amber-p9-final-build.log`，exit 0；两产品 codesign/digest/verify-app 成功。两个 signed HAP 顺序 `hdc install -r` 均返回 install bundle successfully，Novel 与 DeepRead 随后 force-stop/start 成功。离线签名只用于模拟器。

[当前包与804份生产源码的SHA证据](final-artifact-fingerprints.json)：两产品源码副本逐文件全匹配，相对R2只有NovelWorkspacePage成功回执一行变化。Novel SHA256 `b3e1d44b6b9176ed797ab4bf3797d3e1d87a60a5b64fec7f9c6a013ff6fc221b`，DeepRead `b9c0978451d3ceabb6347def196bdd53dc1d3de1ee465f6043521823cafb2ab8`。

## 实际系统保存与取回

全部成果由应用的DocumentViewPicker保存到用户所选Download，再通过HDC读取同一真实已保存文件：`/storage/media/100/local/files/Docs/Download/<filename>`。未启用root模式、未修改文件权限、未从应用缓存伪造产物。保存框改名的文件名和目录截图，与取回文件字节及SHA一起留存。ZIP/正文由小说原项目 `nv_mur96bsr_1_23pat28f` 导出；PDF由深读已完成文章 Local Reader Source Verification 导出。

[文件字节及SHA清单](exported-file-fingerprints.json)、[小说独立内容/调用链review](exchange-review.md)、[PDF独立逐页review](pdf-review.md)。TXT1154 bytes；public ZIP11630 bytes/38 entries；native ZIP523746 bytes/139 entries。PDF100%467094 bytes/2页、180%467493 bytes/3页。175%首样本保留并准确标记，不冒称180%。PDF实际字节全部是ArkWeb createPdf结果，非另行本地作者生成。

## keepBoth真实恢复和冷启动

公开包和完整包分别由项目列表“导入项目”→系统文件选择→真实检查预览→“保留两份”恢复。public新ID `nv_murlu1zt_2_2ngf29fk`、显示（副本）；native新ID `nv_murlvpaj_4_fowrjvcp`、显示原名（native契约不追加副本名）。原ID保留。新包冷启动后的 `device/p9-final-novel-cold-projects-ready.jpeg` 可见原项目及两副本，各6章。

有效公开副本冷读图：`p9-final-public-ready.jpeg`显示新的创作对话，`p9-final-public-six-ready.jpeg`显示01–06全部章节及字数47/477/112/46/51/51。公开交流不迁移私有对话/任务历史。

有效native冷读图：`p9-final-native-cold-workspace.jpeg`显示P5_LONG原对话和已保存的长篇正文，`p9-final-native-cold-chapters.jpeg`显示“共6章”、已完成整章润色记录与01–04目录。所有章节及私有字段由真实恢复目录完整树重读进一步验证；不宣称此截图展示全部6行。

HDC取回的public目录73 files/42999 bytes、native目录138 files/2253154 bytes，与生产keepBoth算法用同一真实ZIP和新ID生成的预期树逐文件相等。native保留102 commits、19 messages、9章版本、3分支快照、任务及提案；缩小1495567 bytes源于33个历史commit的ordinaryRun按契约清除，未丢文件。历史cursor为null，不冒称非空云端cursor的实际恢复验收。原项目after-import138 files/3748721 bytes，与导出前原树逐文件完全相等。

## PDF与冷重读

保存框有效证据：`p9-pdf-normal-picker-directory.jpeg`、`p9-pdf-normal-saved.jpeg`、`p9-pdf-180-save-picker.jpeg`、`p9-pdf-180-saved.jpeg`。全部PDF页用PyMuPDF渲染、pypdf strict读取；100%/175%/180%全文去空白379字符相等，180%实际标题和正文精确1.8倍率。无裁字、横向溢出、重叠、方块字或整页空白。尾页较稀疏是实际分页结果，未作无需求排版修改。

最新DeepRead新包冷启动 `p9-final-deepread-cold-ready.jpeg` 正常进入独立首页；`p9-final-deepread-cold-article-100.jpeg`显示同篇已完成文章、捕获杂志·立场版式、保存/导出入口，摘要bounds `[129,1224][1253,1954]`与原100%一致，证明阅读字号恢复和内容持久重读。

## 精准修复与验证

唯一生产修复：系统保存框可改名，旧成功提示仍报建议文件名；NovelWorkspacePage `saveExportFile` 改为“文件已导出”。不猜文件URI的最终名字，不改变导出格式、写入或错误路径。该文件 scoped ArkTS lint exit0/no defects；Entry现有测试241/241通过，日志 `/tmp/amber-p9-toast-lint.log`、`/tmp/amber-p9-entry-tests.log`。Chat/DeepRead未变，沿用R2实际4554/4554全量结果，不冒称本阶段重新跑全量。

最新新包保存框 `p9-final-renamed-picker.jpeg`显示改名p9-novel-toast-verified.txt及Download；取回的新文件1154bytes，与原导出TXT逐字相等。第二次改名保存 `p9-final-toast-final-picker.jpeg` 与快速连续帧 `p9-final-toast-frame-03.jpeg`（保存点击后1.85s）实际可见“文件已导出”，不再显示旧建议名字。取回 `p9-novel-toast-final.txt` 仍1154bytes且与原TXT逐字相等，P3已关闭；未改变toast时长以迎合采集。

## 证据精度与边界

部分早期采集遇到dumpLayout超时或页面动画尚未完成，文件名含native-six-chapters/native-lower-chapters/public-cold-chapters/ toast-save-picker等的帧实际为项目列表或launcher，不计验收通过证据。`p9-final-renamed-toast.jpeg`首帧实际仍是系统保存等待层，亦不算toast证据。自动化操作竞态归属验收操作，不认定产品缺陷。

本阶段只覆盖当前自建作品、模拟器、内置捕获模板、系统导出和keepBoth；零售签名/真实手机、云端生成/Responses游标、Google在线成功和任意CSS/远程图像不在本阶段已通过声明内。P1–P8及R2仍保留各自历史证据。

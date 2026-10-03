# P8 最终包设备记录

目标为已有模拟器127.0.0.1:5555，1320×2856；无新建模拟器，无宿主覆盖。最终 /tmp/amber-p8-final-build.log 两build/sign/verify成功后分别 install -r 得到 install bundle successfully，再 `aa force-stop <bundleName>` 明确终止并 `aa start -b <bundleName> -a EntryAbility` 冷启动。不是仅返回已有后台页面。

| 当前最终包入口 | 设备证据 | 实际结果 |
| --- | --- | --- |
| 小说 app.amber.novel | device/p8/p8-novel-cold-retained-six、p8-novel-cold-chapters | 独立小说项目首页→同测试项目→目录6章；原46/112字正文和完成润色记录保留，5/6两章各51字；没有活动失败代笔 |
| 深读 app.amber.deepread.reader | device/p8/p8-deepread-cold-board、p8-deepread-cold-library | 独立发现首页→阅读库；本地原生稿完成、先前自定义模板稿完成，随机项按主动取消显示未完成；没有活动卡冒充运行 |
| 历史模板冷读 | device/p8/p8-cold-captured-template-A、p8-cold-font-restored | 旧稿仍P6 Captured Template A，与全局杂志立场不同；180倍率跨冷启动仍在，验收后16次A-还原100，正文可读 |
| 两应用独立配置 | device/p8/p8-deepread-provider-independent、p8-novel-provider-independent | 深读P6 Local Verification与小说Local-verification不同名字保持各自；页面帮助和模型入口按产品显示，不互串；只合成测试key，无宿主密钥迁移 |
| 宿主保留/签名身份 | device/p8/installed-identities.json | bm dump分别三个bundle均在；两独立包为debug provision，没有把开发离线签名当AGC商店发行 |

P7最后精确44vp触点在这个最终包实际复验：Compose154px输入、重新生成两动作154px、裁决查看/收起154×154px；独立reviews均关闭。两个产品实屏浅/暗均已验，最后恢复暗色；深读阅读字体最后还原100%。保留自己的本地HTTP验证配置；验收后已停止自有fixture并移除仅18771反向端口（原18765保留），真实使用先配置可用提供商。合成fixture不是云提供商验收，不复制任何个人云token。独立沙盒由manifest/productidentity与实际分离项目/阅读库/不同配置联合核实，不将仅不同页标题作为沙盒证明。

正式设备/正式分发签名、官方Responses云侧恢复、Google外网成功、公网上每个源长期可靠、全主题字号组合及PDF逐页印刷是本轮未验证边界。API12有界后台为既有DATATRANSFER系统能力，非无限后台承诺。损坏快照恢复有真实领域反例，未增加调试路由构造设备损坏样本。

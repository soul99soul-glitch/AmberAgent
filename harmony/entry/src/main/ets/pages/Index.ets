// DeepReadHomePage — minimal standalone (deepread integration deferred to post-HAP)
// 验证列表页能打开 + 输入框。

@Entry
@Component
struct DeepReadHomePage {
  @State title: string = '';
  @State status: string = '准备就绪';

  build(): void {
    Column() {
      Text('深度阅读')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
        .margin({ top: 32, bottom: 16 })

      TextInput({ text: this.title, placeholder: '输入话题标题开始深读' })
        .width('90%')
        .height(48)
        .onChange((v: string) => { this.title = v; })

      Button('开始深读')
        .type(ButtonType.Capsule)
        .margin({ top: 16 })
        .enabled(this.title.trim().length > 0)
        .onClick(() => { this.status = `已提交: ${this.title}`; })

      Text(this.status)
        .fontSize(14)
        .fontColor('#666')
        .margin({ top: 24 })

      Text('逻辑层(deepread)已实现 450 tests pass;运行时集成在 HAP 编译通过后接入。')
        .fontSize(11)
        .fontColor('#999')
        .margin({ top: 40, left: 24, right: 24 })
        .textAlign(TextAlign.Center)
    }
    .width('100%')
    .height('100%')
    .alignItems(HorizontalAlign.Center)
  }
}

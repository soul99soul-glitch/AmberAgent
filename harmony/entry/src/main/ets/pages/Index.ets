// DeepReadHomePage — 对照设计 §7.1
// 输入框(话题标题) + "从分享/链接开始"按钮 + 最近历史列表
// 手动输入 → deriveTopicId({title}) → 跳 DeepReadArticlePage

import { router } from '@kit.ArkUI';
import { deriveTopicId } from '@amber/deepread-domain';
import { getRepository } from '../di/AppContainer.ets';

@Entry
@Component
struct DeepReadHomePage {
  @State title: string = '';
  @State seedUrl: string = '';
  @State showUrlInput: boolean = false;
  @State recent: Array<{ topicId: string; title: string; updatedAt: number }> = [];
  @StorageLink('pendingTopicId') pendingTopicId: string = '';

  aboutToAppear(): void {
    this.refreshRecent();
    // 若 EntryAbility 写入了 pendingTopicId(分享/deep-link 入口),自动跳文章页
    if (this.pendingTopicId.length > 0) {
      const id = this.pendingTopicId;
      this.pendingTopicId = '';
      this.gotoArticle(id, this.title, this.seedUrl.length > 0 ? this.seedUrl : null);
    }
  }

  refreshRecent(): void {
    // TODO(phase6): Repository 补充 getRecentDeepReads(limit) 后,在此接通并填 this.recent
    // 当前 deepread 的 Repository 接口只有 get/save/clear/observe;recent 列表查询待加。
    this.recent = [];
  }

  gotoArticle(topicId: string, title: string, seedUrl: string | null): void {
    router.pushUrl({
      url: 'pages/DeepReadArticlePage',
      params: { topicId, title, seedUrl } as Record<string, string>,
    });
  }

  startFromTitle(): void {
    const t = this.title.trim();
    if (t.length === 0) return;
    const topicId = deriveTopicId({ title: t });
    this.gotoArticle(topicId, t, null);
  }

  startFromUrl(): void {
    const u = this.seedUrl.trim();
    if (u.length === 0) return;
    const topicId = deriveTopicId({ url: u });
    this.gotoArticle(topicId, u, u);
  }

  build(): void {
    Column() {
      Text('深度阅读')
        .fontSize(28)
        .fontWeight(FontWeight.Bold)
        .margin({ top: 24, bottom: 16 })

      TextInput({ text: this.title, placeholder: '输入话题标题开始深读' })
        .width('90%')
        .height(48)
        .onChange((v: string) => { this.title = v; })
        .onSubmit(() => this.startFromTitle())

      if (this.showUrlInput) {
        TextInput({ text: this.seedUrl, placeholder: '粘贴来源 URL' })
          .width('90%')
          .height(48)
          .margin({ top: 8 })
          .onChange((v: string) => { this.seedUrl = v; })
          .onSubmit(() => this.startFromUrl())
      }

      Row() {
        Button('开始深读')
          .type(ButtonType.Capsule)
          .enabled(this.title.trim().length > 0)
          .onClick(() => this.startFromTitle())
        Button(this.showUrlInput ? '收起链接' : '从链接开始')
          .type(ButtonType.Capsule)
          .margin({ left: 12 })
          .onClick(() => { this.showUrlInput = !this.showUrlInput; })
      }
      .margin({ top: 16 })

      // 最近历史
      Text('最近').fontSize(16).fontWeight(FontWeight.Medium).margin({ top: 24, left: 16 }).width('90%')
      if (this.recent.length === 0) {
        Text('暂无历史').fontSize(14).fontColor('#999').margin({ top: 12 })
      } else {
        List({ space: 8 }) {
          ForEach(this.recent, (item: { topicId: string; title: string; updatedAt: number }) => {
            ListItem() {
              Row() {
                Text(item.title).fontSize(15).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(new Date(item.updatedAt).toLocaleDateString()).fontSize(12).fontColor('#999')
              }
              .width('90%')
              .padding(12)
              .borderRadius(8)
              .backgroundColor('#F5F5F5')
              .onClick(() => this.gotoArticle(item.topicId, item.title, null))
            }
          })
        }
        .width('95%')
        .layoutWeight(1)
        .margin({ top: 8 })
      }

      Button('查看全部历史 (7天)')
        .type(ButtonType.Text)
        .margin({ bottom: 16 })
        .onClick(() => router.pushUrl({ url: 'pages/HistoryPage' }))
    }
    .width('100%')
    .height('100%')
    .alignItems(HorizontalAlign.Center)
  }
}

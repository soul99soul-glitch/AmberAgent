// HistoryPage — 对照设计 §7.1
// 7d 历史列表,点击进入 DeepReadArticlePage(只读)

import { router } from '@kit.ArkUI';

interface HistoryItem {
  topicId: string;
  title: string;
  updatedAt: number;
}

@Entry
@Component
struct HistoryPage {
  @State items: HistoryItem[] = [];

  aboutToAppear(): void {
    // TODO(phase6): Repository 补充 listRecentDeepReads(sinceMs) 后接通
    this.items = [];
  }

  build(): void {
    Column() {
      Row() {
        Button('←').type(ButtonType.Text).onClick(() => router.back())
        Text('历史 (7天)').fontSize(18).fontWeight(FontWeight.Medium).margin({ left: 8 })
      }
      .width('100%').height(56).padding({ left: 12 })

      if (this.items.length === 0) {
        Column() {
          Text('暂无历史记录').fontSize(14).fontColor('#999').margin({ top: 60 })
        }.layoutWeight(1)
      } else {
        List({ space: 8 }) {
          ForEach(this.items, (item: HistoryItem) => {
            ListItem() {
              Row() {
                Text(item.title).fontSize(15).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(new Date(item.updatedAt).toLocaleString()).fontSize(12).fontColor('#999')
              }
              .width('90%')
              .padding(12)
              .borderRadius(8)
              .backgroundColor('#F5F5F5')
              .onClick(() => router.pushUrl({
                url: 'pages/DeepReadArticlePage',
                params: { topicId: item.topicId, title: item.title } as Record<string, string>,
              }))
            }
          })
        }
        .width('95%')
        .layoutWeight(1)
        .margin({ top: 8 })
      }
    }
    .width('100%').height('100%')
  }
}

// HistoryPage — 7d 历史列表(对照设计 §7.1)
// ArkTS 兼容。

import { router } from '@kit.ArkUI';

interface HistoryItem {
  topicId: string;
  title: string;
}

@Entry
@Component
struct HistoryPage {
  @State items: HistoryItem[] = [];

  aboutToAppear(): void {
    // TODO(repository): 从 Repository.listHistory 填充
    this.items = [];
  }

  build(): void {
    Column() {
      Row() {
        Button('返回').fontSize(14).onClick(() => router.back())
        Text('历史 (7天)').fontSize(18).fontWeight(FontWeight.Medium).margin({ left: 8 })
      }
      .width('100%').height(56).padding({ left: 12 })

      if (this.items.length === 0) {
        Column() {
          Text('暂无历史记录').fontSize(14).fontColor('#999').margin({ top: 60 })
        }.layoutWeight(1)
      }
    }
    .width('100%').height('100%')
  }
}

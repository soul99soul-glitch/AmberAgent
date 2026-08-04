// DeepReadHistoryPage — 深度阅读历史
// 列出已生成的 DeepRead 文章(标题 + 日期 + 状态),点击重新打开;空状态
// Android 基准: feature/ui/pages/deepread/DeepReadHistoryPage.kt
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, ERROR } from '../design/tokens.ets';

const DANGER: string = 'ERROR';

interface HistoryEntry {
  id: string;
  title: string;
  date: string;
  status: string;
}

function makeEntry(id: string, title: string, date: string, status: string): HistoryEntry {
  return { id: id, title: title, date: date, status: status };
}

function placeholderLoadHistory(): Promise<HistoryEntry[]> {
  const seed: HistoryEntry[] = [
    makeEntry('h-1', '国产大模型集体降价,API 价格战再升级', '2026-08-02', '完成'),
    makeEntry('h-2', '鸿蒙 NEXT 5.0 发布,纯血原生应用破万', '2026-08-01', '完成'),
    makeEntry('h-3', 'AI 编程助手效率提升实测对比', '2026-07-30', '完成'),
    makeEntry('h-4', '端侧小模型部署最佳实践总结', '2026-07-28', '失败'),
    makeEntry('h-5', '开源向量数据库年度横评', '2026-07-25', '完成'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct DeepReadHistoryPage {
  @State items: HistoryEntry[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadHistory().then((list: HistoryEntry[]): void => {
      this.items = list;
      this.loaded = true;
    });
  }

  private reopen(item: HistoryEntry): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.id;
    params['title'] = item.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('深度阅读历史').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.items.length === 0) {
        Column() {
          Text('◎').fontSize(34).fontColor(INK4)
          Text('还没有阅读历史').fontSize(BODY).fontColor(INK2).margin({ top: 12 })
          Text('去热榜选一篇文章深度阅读吧').fontSize(META).fontColor(INK3).margin({ top: 6 })
        }.margin({ top: 64 })
      } else {
        List() {
          ForEach(this.items, (item: HistoryEntry): void => {
            ListItem() {
              Column() {
                Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Row() {
                  Text(item.date).fontSize(LABEL).fontColor(INK3)
                  Text(item.status).fontSize(LABEL)
                    .fontColor(item.status === '完成' ? ACCENT : DANGER)
                    .margin({ left: 12 })
                  Blank()
                  Text('重读 ›').fontSize(LABEL).fontColor(INK4)
                }
                .width('100%').margin({ top: 6 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 14, bottom: 14 })
              .backgroundColor(SURFACE)
              .onClick((): void => { this.reopen(item); })
            }
          }, (item: HistoryEntry): string => item.id)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

// DeepReadHistoryPage — 深度阅读历史
// 列出已生成的 DeepRead 文章(标题 + 日期 + 状态),点击重新打开;空状态
// Android 基准: feature/ui/pages/deepread/DeepReadHistoryPage.kt
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import type { DeepReadCacheEntry } from '@amber/deepread-domain';
import { isCacheEntryExpired } from '@amber/deepread-domain';
import { getRepository } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, LINE, BG, ACCENT, ACCENT_LIGHT, SIGNAL, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

const DANGER: string = 'ERROR';

interface HistoryEntry {
  id: string;
  title: string;
  date: string;
  status: string;
  expired: boolean;
}

function historyStatus(entry: DeepReadCacheEntry): string {
  if (entry.lastError !== null && entry.lastError.trim().length > 0) return '失败';
  return entry.phase === 'COMPLETE' ? '完成' : '未完成';
}

function historyDate(updatedAt: number): string {
  const d: Date = new Date(updatedAt);
  const pad = (n: number): string => n < 10 ? `0${n}` : `${n}`;
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function loadHistory(): Promise<HistoryEntry[]> {
  return getRepository().listHistory(100).then(
    (rows: DeepReadCacheEntry[]): HistoryEntry[] => rows.map(
      (entry: DeepReadCacheEntry): HistoryEntry => ({
        id: entry.topicId,
        title: entry.title,
        date: historyDate(entry.updatedAt),
        status: historyStatus(entry),
        expired: isCacheEntryExpired(entry),
      }),
    ),
  );
}

@Entry
@Component
struct DeepReadHistoryPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State items: HistoryEntry[] = [];
  @State loaded: boolean = false;
  @State loadError: string = '';

  aboutToAppear(): void {
    loadHistory().then((list: HistoryEntry[]): void => {
      this.items = list;
      this.loaded = true;
    }).catch((e: Error): void => {
      this.items = [];
      this.loadError = e.message;
      this.loaded = true;
    });
  }

  private reopen(item: HistoryEntry): void {
    const params: Record<string, string> = {};
    params['topicId'] = item.id;
    params['title'] = item.title;
    params['fromHistory'] = 'true';
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Icon({ name: 'back', iconSize: 20, color: INK3 }).onClick((): void => { router.back(); })
        Text('深度阅读历史').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.items.length === 0) {
        Column() {
          // 空态图标座替换裸 glyph
          Row() {
            Icon({ name: 'book', iconSize: 30, color: ACCENT })
          }
          .width(76).height(76).borderRadius(38)
          .backgroundColor(ACCENT_LIGHT)
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)
          Text(this.loadError.length > 0 ? '历史记录加载失败' : '还没有阅读历史')
            .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 18 })
          Text(this.loadError.length > 0
            ? this.loadError
            : '还没有生成过深度阅读')
            .fontSize(META).fontColor(INK3).margin({ top: 6 })
        }.margin({ top: 64 }).padding({ left: 24, right: 24 })
      } else {
        List() {
          ForEach(this.items, (item: HistoryEntry): void => {
            ListItem() {
              Column() {
                Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Row() {
                  Text(item.date).fontSize(LABEL).fontColor(INK3)
                  Text(item.expired ? '已失效' : '有效').fontSize(LABEL)
                    .fontColor(item.expired ? '#D19A66' : SIGNAL)
                    .margin({ left: 12 })
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

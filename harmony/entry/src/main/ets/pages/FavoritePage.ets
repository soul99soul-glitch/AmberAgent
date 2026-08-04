// FavoritePage — 收藏页
// 收藏的消息/会话列表(标题 + 预览 + 日期),点击打开,滑动取消收藏;空状态
// Android 基准: feature/ui/pages/favorite/FavoritePage.kt
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, ERROR } from '../design/tokens.ets';

const DANGER: string = 'ERROR';

interface FavoriteItem {
  id: string;
  title: string;
  preview: string;
  date: string;
  kind: string;
}

function makeFav(id: string, title: string, preview: string, date: string, kind: string): FavoriteItem {
  return { id: id, title: title, preview: preview, date: date, kind: kind };
}

function placeholderLoadFavorites(): Promise<FavoriteItem[]> {
  const seed: FavoriteItem[] = [
    makeFav('f-1', '关于架构设计的几点思考', '分层架构的关键在于依赖方向的控制…', '2026-08-02', '消息'),
    makeFav('f-2', 'Rust vs Go 性能对比', '在并发场景下两者的取舍与权衡…', '2026-08-01', '消息'),
    makeFav('f-3', '产品需求文档模板', '一份完整的 PRD 应包含的要素…', '2026-07-30', '会话'),
    makeFav('f-4', '如何写好技术文档', '受众、结构与示例的重要性…', '2026-07-28', '消息'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct FavoritePage {
  @State items: FavoriteItem[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    placeholderLoadFavorites().then((list: FavoriteItem[]): void => {
      this.items = list;
      this.loaded = true;
    });
  }

  private openItem(item: FavoriteItem): void {
    if (item.kind === '会话') {
      const params: Record<string, string> = {};
      params['conversationId'] = item.id;
      router.pushUrl({ url: 'pages/ChatPage', params: params });
    } else {
      router.pushUrl({ url: 'pages/ChatListPage' });
    }
  }

  private unfavorite(item: FavoriteItem): void {
    this.items = this.items.filter((f: FavoriteItem): boolean => f.id !== item.id);
  }

  @Builder swipeActions(item: FavoriteItem): void {
    Row() {
      Text('取消收藏').fontSize(13).fontColor(Color.White)
        .textAlign(TextAlign.Center)
        .width(96).height('100%')
        .backgroundColor(DANGER)
        .onClick((): void => { this.unfavorite(item); })
    }
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('收藏').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else if (this.items.length === 0) {
        Column() {
          Text('★').fontSize(34).fontColor(INK4)
          Text('还没有收藏').fontSize(BODY).fontColor(INK2).margin({ top: 12 })
          Text('在消息中点击收藏即可保存到这里').fontSize(META).fontColor(INK3).margin({ top: 6 })
        }.margin({ top: 64 })
      } else {
        List() {
          ForEach(this.items, (item: FavoriteItem): void => {
            ListItem() {
              Column() {
                Row() {
                  Text(item.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                    .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text(item.kind).fontSize(LABEL).fontColor(INK4)
                }
                .width('100%')
                Text(item.preview).fontSize(META).fontColor(INK3).margin({ top: 4 })
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(item.date).fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .onClick((): void => { this.openItem(item); })
            }
            .swipeAction({ end: this.swipeActions(item) })
          }, (item: FavoriteItem): string => item.id)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

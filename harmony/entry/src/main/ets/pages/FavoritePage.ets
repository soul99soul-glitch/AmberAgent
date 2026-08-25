// FavoritePage — 收藏页(接 FavoriteStore 真实存储)
//
// Android 基准: favorite/FavoritePage.kt — 列表/取消收藏。
//   鸿蒙接 entry FavoriteStore.ets(KV JSON 落盘);收藏入口 = 消息动作 star 键。

import type { FavoriteEntry } from '../platform_impl/FavoriteStore.ets';
import { promptAction } from '@kit.ArkUI';
import { listFavorites, removeFavorite } from '../platform_impl/FavoriteStore.ets';
import {
  BG, SURFACE, INK, INK3, INK4, LINE, ERROR,
  BODY, BODY2, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct FavoritePage {
  @State items: FavoriteEntry[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.reload();
  }

  private reload(): void {
    listFavorites().then((list: FavoriteEntry[]): void => {
      this.items = list;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private fmtDate(ms: number): string {
    const d: Date = new Date(ms);
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '收藏' })

      if (this.loaded && this.items.length === 0) {
        Column() {
          Text('// 暂无收藏').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
          Text('在消息动作行点 star 收藏一段回答')
            .fontSize(BODY2).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.items, (f: FavoriteEntry): void => {
            ListItem() {
              Column() {
                Row() {
                  Text(f.title.length > 0 ? f.title : '（无标题）')
                    .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                    .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Icon({ name: 'trash', iconSize: 15, color: ERROR })
                    .onClick((): void => {
                      AlertDialog.show({
                        title: '取消收藏', message: '删除这条收藏？',
                        primaryButton: { value: '取消', action: (): void => {} },
                        secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
                          removeFavorite(f.id).then((): void => { this.reload(); })
                            .catch((e: Error): void => {
                              promptAction.showToast({ message: `删除失败:${e.message}`, duration: 1800 });
                            });
                        } },
                      });
                    })
                }
                .width('100%')

                Text(f.content).fontSize(BODY2).fontColor(INK3).margin({ top: 4 })
                  .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })

                Text(this.fmtDate(f.createdAt))
                  .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono')
                  .margin({ top: 6 })
              }
              .padding(14)
              .backgroundColor(SURFACE)
              .border({ width: 1, color: LINE }).borderRadius(14)
              .margin({ left: 18, right: 18, bottom: 10 })
              .alignItems(HorizontalAlign.Start)
            }
          }, (f: FavoriteEntry): string => f.id)
        }
        .width('100%').layoutWeight(1).padding({ top: 10 })
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

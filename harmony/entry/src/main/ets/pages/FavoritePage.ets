// FavoritePage — 收藏页(接 FavoriteStore 真实存储)
//
// Android 基准: favorite/FavoritePage.kt — 列表/取消收藏。
//   鸿蒙接 entry FavoriteStore.ets(KV JSON 落盘);收藏入口 = 消息动作 star 键。

import type { FavoriteEntry } from '../platform_impl/FavoriteStore.ets';
import { promptAction } from '@kit.ArkUI';
import { listFavorites, removeFavorite } from '../platform_impl/FavoriteStore.ets';
import {
  BG, SURFACE2, INK, INK3, INK4, ERROR,
  BODY2, META, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, PageSpacer, PaperSeparator } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Component
struct FavoritePaperRow {
  @Prop title: string = '';
  @Prop content: string = '';
  @Prop dateText: string = '';
  @Prop isLast: boolean = false;
  onDelete: () => void = (): void => {};
  @State private pressed: boolean = false;

  build(): void {
    Column() {
      Row() {
        Column() {
          Text(this.title)
            .fontSize(15)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
          Text(this.content)
            .fontSize(BODY2)
            .fontWeight(400)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .maxLines(3)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 4 })
            .width('100%')
          Text(this.dateText)
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(500)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK4)
            .margin({ top: 6 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Row() {
          Icon({ name: 'trash', iconSize: 15, color: ERROR })
        }
        .width(44)
        .height(44)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .onClick((): void => { this.onDelete(); })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressed ? SURFACE2 : Color.Transparent)
      .onTouch((e: TouchEvent): void => {
        if (e.type === TouchType.Down) this.pressed = true;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressed = false;
      })
      if (!this.isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }
}

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
          Text('// 暂无收藏')
            .fontSize(META).fontWeight(500).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(INK3)
          Text('在消息动作行点 star 收藏一段回答')
            .fontSize(BODY2).fontWeight(400).lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.items, (f: FavoriteEntry, index: number): void => {
            ListItem() {
              FavoritePaperRow({
                title: f.title.length > 0 ? f.title : '（无标题）',
                content: f.content,
                dateText: this.fmtDate(f.createdAt),
                isLast: index === this.items.length - 1,
                onDelete: (): void => {
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
                },
              })
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

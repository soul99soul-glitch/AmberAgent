// FavoritePage — 收藏页(接 FavoriteStore 真实存储)
//
// Android 基准: favorite/FavoritePage.kt — 列表/取消收藏。
//   鸿蒙接 entry FavoriteStore.ets(KV JSON 落盘);收藏入口 = 消息动作 star 键。
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
//   CenteredTitleBar / PlainSection / CardGroup r18+发丝描边 +
//   收藏行(标题/摘要/日期 + 44 热区删除钮)/ sans 两行居中空态;
//   逻辑(列表/取消收藏确认/删除失败 toast)零改动。
// 2026-09-30:节点收藏可点回原消息；旧文本收藏保留并打开来源会话。

import type { FavoriteEntry } from '../platform_impl/FavoriteStore.ets';
import { promptAction, router } from '@kit.ArkUI';
import { listFavorites, removeFavorite, resolveFavoriteSource } from '../platform_impl/FavoriteStore.ets';
import { BG, INK, INK3, INK4, ERROR, LINE, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD, FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { CenteredTitleBar, PlainSection, CardGroup, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

interface FavoriteChatParams {
  conversationId: string;
  nodeId: string;
}

@Entry
@Component
struct FavoritePage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State items: FavoriteEntry[] = [];
  @State loaded: boolean = false;
  private pageVisible: boolean = false;

  aboutToAppear(): void {
    this.pageVisible = true;
    this.reload();
  }

  onPageShow(): void {
    this.pageVisible = true;
    this.reload();
  }
  onPageHide(): void { this.pageVisible = false; }
  aboutToDisappear(): void { this.pageVisible = false; }

  private reload(): void {
    listFavorites().then((list: FavoriteEntry[]): void => {
      if (!this.pageVisible) return;
      this.items = list;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private openSource(favorite: FavoriteEntry): void {
    resolveFavoriteSource(favorite).then((source): void => {
      if (!this.pageVisible) return;
      const params: FavoriteChatParams = { conversationId: source.conversationId, nodeId: source.nodeId };
      router.pushUrl({ url: 'pages/ChatPage', params });
    }).catch((error: Error): void => {
      if (this.pageVisible) promptAction.showToast({ message: error.message, duration: 1800 });
    });
  }

  private fmtDate(ms: number): string {
    const d: Date = new Date(ms);
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  }

  private confirmDelete(f: FavoriteEntry): void {
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
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  @Builder
  favoriteCard(): void {
    Column() {
      ForEach(this.items, (f: FavoriteEntry, index: number): void => {
        this.favoriteRow(f, index === this.items.length - 1);
      }, (f: FavoriteEntry): string => f.id)
    }
    .width('100%')
  }

  // 收藏行:标题/摘要(3 行)/mono 日期 + 44 热区删除钮
  @Builder
  favoriteRow(f: FavoriteEntry, isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text(f.title.length > 0 ? f.title : '（无标题）')
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            .width('100%')
          Text(f.content)
            .fontSize(12).lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
            .width('100%')
          Row() {
            Text(this.fmtDate(f.createdAt))
              .fontSize(12).lineHeight(lineHeightForSize(12))
              .fontFamily(FONT_MONO_MEDIUM).fontColor(INK4)
            Blank()
            Text((f.nodeId ?? '').length > 0 ? '查看原消息 ›' : '打开来源会话 ›')
              .fontSize(12).lineHeight(lineHeightForSize(12))
              .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
          }
          .width('100%').margin({ top: 5 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .accessibilityText(`${f.title}，查看收藏来源`)
        .onClick((): void => { this.openSource(f); })
        Row() {
          Icon({ name: 'trash', iconSize: 15, color: ERROR })
        }
        .width(44)
        .height(44)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .margin({ right: -8 })
        .accessibilityText('删除收藏')
        .onClick((): void => { this.confirmDelete(f); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      if (!isLast) {
        // 无图标座:分隔线内缩对齐文字起点(行 padding 14)
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%')
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '收藏' })

      if (this.loaded && this.items.length === 0) {
        Column() {
          Text('暂无收藏')
            .fontSize(15).fontWeight(600).lineHeight(lineHeightForSize(15))
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK3)
          Text('在消息菜单或操作行收藏消息')
            .fontSize(13).lineHeight(lineHeightForSize(13))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .margin({ top: 8 })
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            PlainSection({ label: '收藏' })
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.favoriteCard(); } })
            }
            .width('100%').padding({ left: 16, right: 16 })
            Column().height(24)
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
      }
      PageSpacer()
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

// MiniAppStreamingCard — 小应用 JSON/HTML 流式输出期间的单一卡片(iOS ChatMiniAppStreamingCard 对齐)
//
// 默认是生图式点阵占位,点按整卡切到代码窗口,再点收起。代码窗口只显示 html 字段
// 解码后的最后 MINI_APP_STREAM_PREVIEW_LIMIT 字;占位态完全不解码,流式增量只改 @Prop。

import { MiniAppStreamingCodePreview } from '@amber/chat-domain';
import { ACCENT, INK2, INK3, LINE, MONO_SMALL, MOTION_OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { FONT_MONO } from '../design/fonts.ets';
import { Icon } from './ui/Icon.ets';
import { DotGround } from './ui/DotMatrix.ets';
import { DotLoading } from './ui/DotLoading.ets';

@Component
export struct MiniAppStreamingCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @Prop @Watch('onTextChange') text: string = '';
  @State showCode: boolean = false;
  @State code: string = '';
  private preview: MiniAppStreamingCodePreview = new MiniAppStreamingCodePreview();
  private codeScroller: Scroller = new Scroller();

  private onTextChange(): void {
    if (this.showCode) this.refreshCode();
  }

  private refreshCode(): void {
    this.preview.update(this.text);
    this.code = this.preview.displayText();
  }

  private toggle(): void {
    if (!this.showCode) this.refreshCode();
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseInOut }, (): void => {
      this.showCode = !this.showCode;
    });
  }

  private title(): string {
    return this.showCode ? '正在生成前端代码' : '正在生成小应用';
  }

  @Builder
  private PreviewBody(): void {
    Stack() {
      DotGround()
      DotLoading({ color: this.accentColorStore, dotSize: 6 })
    }
    .width('100%')
    .aspectRatio(16 / 10)
    .backgroundColor(SURFACE2)
    .borderRadius(12)
    .clip(true)
  }

  @Builder
  private CodeBody(): void {
    Scroll(this.codeScroller) {
      Text(this.code.length > 0 ? this.code : ' ')
        .fontSize(MONO_SMALL).fontColor(INK2).fontFamily(FONT_MONO)
        .width('100%')
        .onSizeChange((): void => { this.codeScroller.scrollEdge(Edge.Bottom); })
    }
    .width('100%')
    .height(200)
    .padding(10)
    .align(Alignment.TopStart)
    .scrollBar(BarState.Off)
    .backgroundColor(SURFACE2)
    .borderRadius(12)
  }

  build(): void {
    Column() {
      Row() {
        Icon({ name: this.showCode ? 'doc' : 'spark', iconSize: 13, color: this.accentColorStore })
        Text(this.title())
          .fontSize(13).fontColor(INK2).fontWeight(FontWeight.Medium)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ left: 8 })
          .layoutWeight(1)
        Icon({ name: 'chevron_down', iconSize: 12, color: INK3 })
          .rotate({ angle: this.showCode ? 180 : 0 })
      }
      .width('100%')
      .constraintSize({ minHeight: 44 })
      .padding({ left: 12, right: 12 })

      Column() {
        if (this.showCode) {
          this.CodeBody()
        } else {
          this.PreviewBody()
        }
      }
      .width('100%')
      .padding({ left: 10, right: 10, bottom: 10 })
    }
    .enabled(this.themeEpoch >= 0)
    .width('100%')
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE, radius: 16 })
    .clip(true)
    .margin({ bottom: 4 })
    .onClick((): void => { this.toggle(); })
    .accessibilityText(this.title())
    .accessibilityDescription('点按切换预览与代码')
  }
}

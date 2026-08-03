// Favicon — 网站图标(ui/Favicon.kt:95 行)
// Android 基准: AsyncImage 加载 favicone.com 远程图标 + fallback Link01 icon
// ArkUI: Image(url) + fallback 文字图标;FaviconRow 叠加布局
import { INK3, SURFACE, LINE } from '../../design/tokens.ets';

export const faviconUrl = (url: string): string => {
  try {
    const u: string = url.startsWith('http') ? url : `https://${url}`;
    const host: string = new URL(u).hostname;
    return `https://favicone.com/${host}?s=20`;
  } catch (_e) {
    return '';
  }
};

@Component
export struct Favicon {
  @Prop url: string = '';
  @Prop iconSize: number = 20;
  build(): void {
    if (faviconUrl(this.url).length > 0) {
      Image(faviconUrl(this.url))
        .width(this.iconSize).height(this.iconSize)
        .borderRadius(this.iconSize / 4)
        .backgroundColor(SURFACE)
        .border({ width: 0.5, color: LINE })
        .objectFit(ImageFit.Cover)
        .alt('🔗')
    } else {
      Text('🔗')
        .fontSize(this.iconSize * 0.6).fontColor(INK3)
        .width(this.iconSize).height(this.iconSize)
        .textAlign(TextAlign.Center)
        .backgroundColor(SURFACE)
        .borderRadius(this.iconSize / 4)
    }
  }
}

@Component
export struct FaviconRow {
  @Prop urls: string[] = [];
  @Prop iconSize: number = 20;
  build(): void {
    Row() {
      ForEach(this.urls.slice(0, 3), (url: string, i: number): void => {
        Favicon({ url: url, iconSize: this.iconSize })
          .margin({ left: i > 0 ? -4 : 0 })
      }, (url: string, i: number): string => `${url}-${i}`)
    }
    .width('100%')
  }
}

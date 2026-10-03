// Article presentation only. Scheduling, recovery, and display preferences belong to the page.
import type { CorePoint, DeepReadImageAsset, DeepReadOutput, Perspective, Quote, ReadingLink,
  TimelineEvent } from '@amber/deepread-domain';
import { displayHeroCaption, displayHeroImageUrl, makeEmptyDeepReadOutput, IMAGE_CONFIDENCE } from '@amber/deepread-domain';
import { MarkdownText } from './MarkdownText.ets';
import { DeepReadDiagram } from './DeepReadDiagram.ets';
import { FONT_SERIF, FONT_SANS, FONT_SANS_MEDIUM, FONT_MONO_SEMIBOLD } from '../design/fonts.ets';
import { INK, INK2, INK3, INK4, LINE, LINE2, ACCENT, ACCENT_LIGHT, META } from '../design/tokens.ets';

@Component
export struct DeepReadNativeArticleBody {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop title: string = '';
  @Prop output: DeepReadOutput = makeEmptyDeepReadOutput();
  @Prop fontScale: number = 1;
  @Prop fontSerif: boolean = false;
  onOpenLink: (url: string) => void = (_url: string): void => {};

  private readingFont(): string { return this.fontSerif ? FONT_SERIF : FONT_SANS; }
  private heroUrl(): string | null { return displayHeroImageUrl(this.output); }
  private heroCaption(): string { return displayHeroCaption(this.output) ?? ''; }
  private twoDigits(n: number): string { return n < 10 ? '0' + n.toString() : n.toString(); }
  private sourceLabel(): string {
    const pool: ReadingLink[] = this.output.references.length > 0
      ? this.output.references : this.output.extendedReading;
    const seen: string[] = [];
    for (const link of pool) {
      const key: string = link.source?.trim() || link.url;
      if (key.length > 0 && !seen.includes(key)) seen.push(key);
    }
    return seen.length > 0 ? `${seen.length} SOURCES` : 'DEEP READ';
  }
  private coreTitle(): string {
    if (this.output.topicType === 'product') return '功能亮点';
    if (this.output.topicType === 'person') return '人物背景';
    if (this.output.topicType === 'opinion') return '核心论点';
    return '关键脉络';
  }
  private imageUrl(url: string | null): string {
    return url !== null && /^https?:\/\//i.test(url) ? url : '';
  }
  private galleryAssets(): DeepReadImageAsset[] {
    const used: string[] = [];
    const hero: string | null = this.heroUrl();
    if (hero !== null) used.push(hero);
    for (const event of this.output.timeline ?? []) {
      if (event.imageUrl !== null) used.push(event.imageUrl);
    }
    for (const point of this.output.corePoints ?? []) {
      if (point.imageUrl !== null) used.push(point.imageUrl);
    }
    return this.output.imageAssets.filter((asset: DeepReadImageAsset): boolean => {
      if (asset.confidence === IMAGE_CONFIDENCE.REJECT || this.imageUrl(asset.url).length === 0 || used.includes(asset.url)) return false;
      used.push(asset.url);
      return true;
    });
  }

  @Builder
  ImageWithCaption(url: string, caption: string | null, hero: boolean = false): void {
    Column() {
      Image(url).width('100%').height(hero ? 220 : 180)
        .objectFit(ImageFit.Cover).borderRadius(14).clip(true)
      if (caption !== null && caption.trim().length > 0) {
        Text(caption).fontSize(META * this.fontScale).fontFamily(this.readingFont())
          .fontColor(INK3).lineHeight(20 * this.fontScale).width('100%').margin({ top: 7 })
      }
    }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 12 })
  }

  @Builder
  SectionKicker(label: string): void {
    Text(label).fontSize(11 * this.fontScale).letterSpacing(3.5)
      .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).width('100%')
  }

  @Builder
  TimelineSection(): void {
    if (this.output.timeline !== null && this.output.timeline.length > 0) {
      Column() {
        this.SectionKicker('时间轴')
        ForEach(this.output.timeline, (event: TimelineEvent, index: number): void => {
          Row() {
            Stack() {
              Column().width(1).height(54).backgroundColor(LINE2)
              Circle({ width: event.isHighlight ? 10 : 6, height: event.isHighlight ? 10 : 6 })
                .fill(event.isHighlight ? ACCENT : LINE2).margin({ top: 7 })
            }.width(18).height(54).alignContent(Alignment.Top)
            Column() {
              Text(event.date).fontSize(META * this.fontScale).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
              MarkdownText({ content: event.event, fontSize: 16 * this.fontScale, fontColor: INK,
                readingMode: true, serifForReading: this.fontSerif, lineHeightScale: 1.65 })
                .margin({ top: 5 })
              if (this.imageUrl(event.imageUrl).length > 0) {
                this.ImageWithCaption(this.imageUrl(event.imageUrl), event.imageCaption)
              }
            }.layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 12 })
          }.width('100%').alignItems(VerticalAlign.Top).margin({ top: 20 })
        }, (_event: TimelineEvent, index: number): string => `timeline-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 36 })
    }
  }

  @Builder
  CorePointsSection(): void {
    if (this.output.corePoints !== null && this.output.corePoints.length > 0) {
      Column() {
        this.SectionKicker(this.coreTitle())
        ForEach(this.output.corePoints, (point: CorePoint, index: number): void => {
          Row() {
            Text(this.twoDigits(index + 1)).fontSize(META * this.fontScale).letterSpacing(2)
              .fontFamily(FONT_MONO_SEMIBOLD).fontColor(ACCENT)
            Column() {
              MarkdownText({ content: point.point, fontSize: 17 * this.fontScale, fontColor: INK,
                readingMode: true, serifForReading: this.fontSerif, lineHeightScale: 1.6 })
              if (point.supporting !== null && point.supporting.trim().length > 0) {
                MarkdownText({ content: point.supporting, fontSize: 14 * this.fontScale, fontColor: INK3,
                  readingMode: true, serifForReading: this.fontSerif, lineHeightScale: 1.75 })
                  .margin({ top: 6 })
              }
              if (this.imageUrl(point.imageUrl).length > 0) {
                this.ImageWithCaption(this.imageUrl(point.imageUrl), point.imageCaption)
              }
            }.layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 12 })
          }.width('100%').alignItems(VerticalAlign.Top).margin({ top: 18 })
        }, (_point: CorePoint, index: number): string => `point-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 36 })
    }
  }

  @Builder
  AnalysisSection(): void {
    if ((this.output.analysis.coreDispute?.trim().length ?? 0) > 0
      || this.output.analysis.perspectives.length > 0 || this.output.analysis.quotes.length > 0
      || (this.output.analysis.implications?.trim().length ?? 0) > 0) {
      Column() {
        this.SectionKicker('深度分析')
        if ((this.output.analysis.coreDispute?.trim().length ?? 0) > 0) {
          MarkdownText({ content: this.output.analysis.coreDispute as string,
            fontSize: 20 * this.fontScale, fontColor: INK, readingMode: true,
            serifForReading: this.fontSerif, lineHeightScale: 1.55 }).margin({ top: 16 })
        }
        ForEach(this.output.analysis.quotes, (quote: Quote, index: number): void => {
          Row() {
            Text('\u201C').fontSize(36 * this.fontScale).fontColor(ACCENT)
              .fontFamily(FONT_SERIF).lineHeight(42 * this.fontScale).width(28 * this.fontScale)
            Column() {
              MarkdownText({ content: quote.text, fontSize: 16 * this.fontScale, fontColor: INK2,
                readingMode: true, serifForReading: this.fontSerif, lineHeightScale: 1.75 })
              if (quote.attribution !== null && quote.attribution.trim().length > 0) {
                Text('\u2014 ' + quote.attribution).fontSize(META * this.fontScale)
                  .fontFamily(this.readingFont()).fontColor(INK4).width('100%').margin({ top: 8 })
              }
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          }.width('100%').alignItems(VerticalAlign.Top).margin({ top: 20 })
        }, (_quote: Quote, index: number): string => `quote-${index}`)
        ForEach(this.output.analysis.perspectives, (perspective: Perspective, index: number): void => {
          Column() {
            if (perspective.holder !== null && perspective.holder.trim().length > 0) {
              Text(perspective.holder).fontSize(META * this.fontScale)
                .fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            }
            MarkdownText({ content: perspective.viewpoint, fontSize: 16 * this.fontScale, fontColor: INK,
              readingMode: true, serifForReading: this.fontSerif, lineHeightScale: 1.7 }).margin({ top: 4 })
          }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 16 })
        }, (_perspective: Perspective, index: number): string => `perspective-${index}`)
        if ((this.output.analysis.implications?.trim().length ?? 0) > 0) {
          MarkdownText({ content: this.output.analysis.implications as string,
            fontSize: 16 * this.fontScale, fontColor: INK, readingMode: true,
            serifForReading: this.fontSerif, lineHeightScale: 1.7 }).margin({ top: 20 })
        }
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 36 })
    }
  }

  @Builder
  LinkSection(label: string, links: ReadingLink[]): void {
    if (links.length > 0) {
      Column() {
        this.SectionKicker(label)
        ForEach(links, (link: ReadingLink, index: number): void => {
          Row() {
            Text(this.twoDigits(index + 1)).fontSize(11 * this.fontScale).letterSpacing(1.5)
              .fontFamily(FONT_MONO_SEMIBOLD).fontColor(ACCENT)
            Column() {
              Text(link.title.trim().length > 0 ? link.title : link.url)
                .fontSize(14 * this.fontScale).fontColor(INK).fontFamily(this.readingFont())
                .lineHeight(22 * this.fontScale).width('100%')
              if ((link.source?.trim().length ?? 0) > 0) {
                Text(link.source as string).fontSize(11 * this.fontScale).fontColor(INK4)
                  .fontFamily(this.readingFont()).width('100%').margin({ top: 4 })
              }
              if ((link.publishedAt?.trim().length ?? 0) > 0) {
                Text(link.publishedAt as string).fontSize(11 * this.fontScale)
                  .fontColor(INK4).fontFamily(this.readingFont()).width('100%').margin({ top: 3 })
              }
            }.layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
          }.width('100%').alignItems(VerticalAlign.Top).constraintSize({ minHeight: 44 })
            .padding({ top: 10, bottom: 10 }).onClick((): void => { this.onOpenLink(link.url); })
        }, (_link: ReadingLink, index: number): string => `${label}-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 36 })
    }
  }

  build(): void {
    Column() {
      if (this.heroUrl() !== null) {
        this.ImageWithCaption(this.heroUrl() as string, this.heroCaption(), true)
      }
      Row() {
        Text('DEEP READ').fontSize(11 * this.fontScale).letterSpacing(3.2)
          .fontFamily(FONT_MONO_SEMIBOLD).fontColor(ACCENT)
        Blank()
        Text(this.sourceLabel()).fontSize(11 * this.fontScale).letterSpacing(2.6)
          .fontFamily(FONT_MONO_SEMIBOLD).fontColor(INK4)
      }.width('100%').margin({ top: this.heroUrl() !== null ? 20 : 12 })
      Column().width(126).height(1).backgroundColor(LINE).margin({ top: 16 })
      Text(this.output.topicType.toUpperCase()).fontSize(11 * this.fontScale).letterSpacing(3)
        .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).margin({ top: 20 })
      Text(this.title).fontSize(30 * this.fontScale).fontColor(INK).fontFamily(this.readingFont())
        .lineHeight(42 * this.fontScale).width('100%').margin({ top: 14 })
      if (this.output.summary.trim().length > 0) {
        MarkdownText({ content: this.output.summary, fontSize: 17 * this.fontScale, fontColor: INK,
          readingMode: true, serifForReading: this.fontSerif, lineHeightScale: 1.8 }).margin({ top: 22 })
      }
      if (this.output.keyEntities.length > 0) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.output.keyEntities, (entity: string, index: number): void => {
            Text(entity).fontSize(12 * this.fontScale).fontFamily(this.readingFont()).fontColor(ACCENT)
              .padding({ left: 10, right: 10, top: 5, bottom: 5 }).backgroundColor(ACCENT_LIGHT)
              .borderRadius(999).margin({ right: 8, bottom: 8 })
          }, (_entity: string, index: number): string => `entity-${index}`)
        }.margin({ top: 18 })
      }
      this.TimelineSection()
      this.CorePointsSection()
      this.AnalysisSection()
      if (this.output.diagram !== null) {
        DeepReadDiagram({ diagram: this.output.diagram, fontScale: this.fontScale, fontSerif: this.fontSerif })
          .margin({ top: 36 })
      }
      this.LinkSection('扩展阅读', this.output.extendedReading)
      this.LinkSection('参考来源', this.output.references)
      ForEach(this.galleryAssets(), (asset: DeepReadImageAsset): void => {
        this.ImageWithCaption(asset.url, asset.caption)
      }, (asset: DeepReadImageAsset): string => `gallery-${asset.url}`)
    }.opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').alignItems(HorizontalAlign.Start)
      .padding({ left: 24, right: 24, top: 12, bottom: 40 })
  }
}

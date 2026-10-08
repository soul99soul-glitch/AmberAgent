import type { CorePoint, DeepReadOutput, Perspective, Quote, ReadingLink, TimelineEvent, DeepReadImpact, DeepReadUncertainty } from '@amber/deepread-domain';
import { makeEmptyDeepReadOutput, normalizedDeepReadUncertainties } from '@amber/deepread-domain';
import { nativeArticleSources, nativeCitationNumbers, nativeArticleLinkUrl,
  nativeArticleHasHierarchy, nativeArticleSourceCited, nativeSupplementarySources } from './DeepReadArticleHierarchy.ets';
import { MarkdownText } from '../MarkdownText.ets';
import { DeepReadDiagram } from '../DeepReadDiagram.ets';
import { readerCanvas, readerSectionOrder, DeepReadReaderCanvas } from '../../platform_impl/DeepReadAppearance.ets';
import { DeepReadDesign as D } from '../../design/DeepReadDesign.ets';

// iOS built-in magazine: 22 pt gutters, 28 pt section spacing, 15/1.68 body and 32/1.13 headline.
// The illustrated edition and user HTML keep their existing Web renderer, including their images.
@Component
export struct DeepReadArticleMagazine {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('deepread.appearance.readerLayout') readerLayout: string = 'classic';
  @StorageProp('deepread.appearance.readerStyle') readerStyle: string = 'classic';
  @Prop title: string = '';
  @Prop output: DeepReadOutput = makeEmptyDeepReadOutput();
  @Prop fontScale: number = 1;
  @Prop fontSerif: boolean = true;
  onOpenLink: (url: string) => void = (_url: string): void => {};

  private canvas(): DeepReadReaderCanvas { return readerCanvas(this.readerStyle, this.darkMode); }
  private usesSerif(): boolean { return this.readerStyle !== 'minimal' && this.fontSerif; }
  private family(): string { return this.usesSerif() ? D.readingSerif : D.sans; }
  private headlineInk(): string { return this.readerStyle === 'colorPage' ? this.canvas().background : this.canvas().foreground; }
  private titleSize(): number {
    if (this.readerStyle === 'academic') return 26;
    if (this.readerStyle === 'broadsheet') return 34;
    if (this.readerStyle === 'minimal' || this.readerStyle === 'journal') return 28;
    if (this.readerStyle === 'scroll' || this.readerStyle === 'briefing') return 30;
    return 32;
  }
  private titleLeading(): number {
    if (this.readerStyle === 'scroll') return 1.3;
    if (this.readerStyle === 'briefing') return 1.15;
    if (this.readerStyle === 'journal') return 1.45;
    if (this.readerStyle === 'broadsheet') return 1.08;
    if (this.readerStyle === 'academic') return 1.35;
    return this.readerStyle === 'minimal' ? 1.2 : 1.13;
  }
  private sectionLabel(label: string): string {
    if (this.readerStyle !== 'academic') return label;
    const labels: string[] = ['关键判断', '时间轴', '各方立场', '影响与走向', '待核实', '关系图解'];
    const keys: string[] = ['core', 'timeline', 'analysis', 'outlook', 'uncertainties', 'diagram'];
    const key: string = keys[labels.indexOf(label)] ?? '';
    const index: number = readerSectionOrder(this.output.topicType, this.readerLayout).indexOf(key);
    return index < 0 ? label : (index + 1).toString() + '. ' + label;
  }
  private genre(): string {
    if (this.output.topicType === 'product') return '产品';
    if (this.output.topicType === 'person') return '人物';
    if (this.output.topicType === 'opinion') return '观点';
    return '深度阅读';
  }

  private standaloneExtendedReading(): ReadingLink[] {
    return (this.output.sources?.length ?? 0) > 0 ? [] : this.output.extendedReading;
  }

  @Builder
  BodyText(content: string, muted: boolean = false, headline: boolean = false): void {
    MarkdownText({ content: content, fontSize: 15 * this.fontScale, fontColor: headline ? this.headlineInk() : muted ? this.canvas().muted : this.canvas().foreground,
      readingMode: true, serifForReading: this.usesSerif(), lineHeightScale: 1.68 })
  }

  @Builder
  Kicker(label: string): void {
    Text(this.sectionLabel(label)).fontFamily(this.readerStyle === 'academic' ? this.family() : D.sans).fontSize(10 * this.fontScale).letterSpacing(1.8 * this.fontScale)
      .fontColor(this.readerStyle === 'colorPage' ? this.canvas().background : this.canvas().muted)
      .backgroundColor(this.readerStyle === 'colorPage' ? this.canvas().foreground : this.readerStyle === 'journal' ? this.canvas().surface : Color.Transparent)
      .padding(this.readerStyle === 'journal' || this.readerStyle === 'colorPage' ? 6 : 0)
      .width('100%').margin({ bottom: 12 * this.fontScale })
  }

  @Builder
  Headline(): void {
    Column() {
      if (this.readerStyle === 'broadsheet') {
        Column({ space: 2 }) {
          Row().width('100%').height(2).backgroundColor(this.canvas().foreground)
          Row().width('100%').height(1).backgroundColor(this.canvas().foreground)
        }.width('100%').margin({ bottom: 10 })
      }
      Text(this.genre()).fontFamily(D.sans).fontSize(10 * this.fontScale).letterSpacing(2.4 * this.fontScale)
        .fontColor(this.readerStyle === 'colorPage' ? this.canvas().background : D.accent).width('100%')
      Text(this.title).fontFamily(this.readerStyle === 'briefing' ? D.sans : this.family()).fontSize(this.titleSize() * this.fontScale).fontWeight(this.readerStyle === 'broadsheet' || this.readerStyle === 'briefing' ? FontWeight.Bold : FontWeight.Medium)
        .fontColor(this.headlineInk()).lineHeight(this.titleSize() * this.titleLeading() * this.fontScale).width('100%')
        .textAlign(this.readerStyle === 'scroll' || this.readerStyle === 'broadsheet' || this.readerStyle === 'academic' ? TextAlign.Center : TextAlign.Start)
        .decoration({ type: this.readerStyle === 'journal' ? TextDecorationType.Underline : TextDecorationType.None, color: D.accent })
        .margin({ top: 12 * this.fontScale, bottom: 16 * this.fontScale })
      if (this.readerStyle === 'scroll') { Text('❦').fontSize(18).fontColor(D.accent).width('100%').textAlign(TextAlign.Center).margin({ bottom: 12 }) }
      if ((this.output.bottomLine?.trim().length ?? 0) > 0) {
        MarkdownText({ content: this.output.bottomLine as string, fontSize: 19 * this.fontScale, fontColor: this.headlineInk(),
          readingMode: true, serifForReading: this.usesSerif(), lineHeightScale: 1.5 })
          .margin({ bottom: 12 * this.fontScale })
      }
      if (this.output.summary.trim().length > 0) {
        Column() {
          if (this.readerStyle === 'academic') { Text('摘要').fontSize(12).fontColor(this.canvas().foreground).width('100%').textAlign(TextAlign.Center).margin({ bottom: 8 }) }
          this.BodyText(this.output.summary, false, true)
        }.width('100%').alignItems(HorizontalAlign.Start)
          .padding(this.readerStyle === 'briefing' || this.readerStyle === 'journal' || this.readerStyle === 'academic' ? 14 : 0)
          .backgroundColor(this.readerStyle === 'briefing' || this.readerStyle === 'journal' ? this.canvas().surface : Color.Transparent)
          .border({ width: { left: this.readerStyle === 'briefing' ? 4 : 0,
            top: this.readerStyle === 'academic' ? 0.5 : 0, bottom: this.readerStyle === 'academic' ? 0.5 : 0 }, color: this.readerStyle === 'briefing' ? D.accent : this.canvas().foreground })
          .borderRadius(this.readerStyle === 'journal' ? 14 : 0)
          .rotate({ angle: this.readerStyle === 'journal' ? -0.4 : 0 })
      }
      if (!nativeArticleHasHierarchy(this.output) && this.output.keyEntities.length > 0) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.output.keyEntities, (entity: string, index: number): void => {
            Text(entity).fontFamily(D.sans).fontSize(11 * this.fontScale).fontColor(D.accent)
              .padding({ left: 9, right: 9, top: 3, bottom: 3 })
              .border({ width: 0.5, color: this.canvas().border }).borderRadius(999)
              .margin({ right: 6, bottom: 6 })
          }, (_entity: string, index: number): string => `magazine-entity-${index}`)
        }.width('100%').margin({ top: 14 * this.fontScale })
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
      .padding(this.readerStyle === 'colorPage' ? 18 : 0)
      .backgroundColor(this.readerStyle === 'colorPage' ? D.accent : Color.Transparent)
      .border({ width: { bottom: this.readerStyle === 'broadsheet' ? 0.5 : 0 }, color: this.canvas().foreground })
  }

  @Builder
  Timeline(): void {
    if ((this.output.timeline?.length ?? 0) > 0 && this.output.timeline !== null) {
      Column() {
        this.Kicker('时间轴')
        ForEach(this.output.timeline, (event: TimelineEvent, index: number): void => {
          Row({ space: 12 }) {
            Stack({ alignContent: Alignment.Top }) {
              Row().width(1).height('100%').backgroundColor(this.canvas().border)
              Circle({ width: 8, height: 8 }).fill(event.isHighlight ? D.accent : this.canvas().background)
                .stroke(event.isHighlight ? D.accent : this.canvas().border).strokeWidth(1.5).margin({ top: 8 })
            }.width(18).height(52)
            Column({ space: 5 }) {
              Text(event.date).fontFamily(D.sans).fontSize(11 * this.fontScale).fontColor(this.canvas().muted)
              this.BodyText(event.event)
              if (event.isHighlight && (event.why?.trim().length ?? 0) > 0) {
                this.BodyText('转折：' + (event.why as string), true)
              }
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          }.width('100%').alignItems(VerticalAlign.Top).padding({ top: 12, bottom: 12 })
            .border({ width: { top: this.readerStyle === 'minimal' || this.readerStyle === 'colorPage' ? 0 : index === 0 ? 0 : 0.5 }, color: this.canvas().border })
        }, (_event: TimelineEvent, index: number): string => `magazine-timeline-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 28 * this.fontScale })
    }
  }

  @Builder
  CorePoints(): void {
    if ((this.output.corePoints?.length ?? 0) > 0 && this.output.corePoints !== null) {
      Column() {
        this.Kicker('关键判断')
        ForEach(this.output.corePoints, (point: CorePoint, index: number): void => {
          Column({ space: 6 }) {
            if (this.readerStyle === 'briefing') { Text((index + 1).toString().padStart(2, '0')).fontSize(24).fontWeight(FontWeight.Bold).fontColor(D.accent) }
            MarkdownText({ content: point.point, fontSize: 18 * this.fontScale, fontColor: this.canvas().foreground,
              readingMode: true, serifForReading: this.usesSerif(), lineHeightScale: 1.34 })
            this.Citations(point.sources)
            if ((point.supporting?.trim().length ?? 0) > 0) {
              this.BodyText(point.supporting as string)
            }
          }.width('100%').alignItems(HorizontalAlign.Start).padding({ top: 13, bottom: 13,
            left: this.readerStyle === 'colorPage' ? 12 : 0, right: this.readerStyle === 'colorPage' ? 12 : 0 })
            .border({ width: { top: this.readerStyle === 'minimal' || this.readerStyle === 'colorPage' ? 0 : index === 0 ? 0 : 0.5 }, color: this.canvas().border })
            .backgroundColor(this.readerStyle === 'colorPage' ? this.canvas().surface : Color.Transparent)
            .borderRadius(this.readerStyle === 'colorPage' ? 12 : 0)
        }, (_point: CorePoint, index: number): string => `magazine-core-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 28 * this.fontScale })
    }
  }

  @Builder
  Analysis(): void {
    if ((this.output.analysis.coreDispute?.trim().length ?? 0) > 0
      || this.output.analysis.perspectives.length > 0 || this.output.analysis.quotes.length > 0) {
      Column({ space: 13 }) {
        this.Kicker('各方立场')
        if ((this.output.analysis.coreDispute?.trim().length ?? 0) > 0) {
          this.BodyText(this.output.analysis.coreDispute as string)
        }
        ForEach(this.output.analysis.perspectives, (perspective: Perspective, index: number): void => {
          Column({ space: 6 }) {
            if ((perspective.holder?.trim().length ?? 0) > 0) {
              Text(perspective.holder as string).fontSize(11 * this.fontScale).fontFamily(D.sans)
                .fontWeight(FontWeight.Medium).fontColor(D.accent)
            }
            if ((perspective.interest?.trim().length ?? 0) > 0) {
              this.BodyText('诉求：' + (perspective.interest as string), true)
            }
            this.BodyText(perspective.viewpoint)
            this.Citations(perspective.sources)
            if ((perspective.quote?.trim().length ?? 0) > 0) {
              this.Quotation(perspective.quote as string, perspective.quoteBy ?? null)
            }
          }.width('100%').alignItems(HorizontalAlign.Start)
            .padding(this.readerLayout === 'debate' ? 14 : 0)
            .backgroundColor(this.readerLayout === 'debate' ? this.canvas().surface : Color.Transparent)
            .border({ width: { left: this.readerLayout === 'debate' ? 3 : 0 }, color: D.accent })
            .borderRadius(this.readerLayout === 'debate' ? 12 : 0)
        }, (_perspective: Perspective, index: number): string => `magazine-viewpoint-${index}`)
        ForEach(this.output.analysis.quotes, (quote: Quote, index: number): void => {
          this.Quotation(quote.text, quote.attribution)
        }, (_quote: Quote, index: number): string => `magazine-quote-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 28 * this.fontScale })
    }
  }

  @Builder
  Quotation(text: string, attribution: string | null): void {
    Column({ space: 8 }) {
      MarkdownText({ content: text, fontSize: (this.readerLayout === 'debate' ? 21 : 18) * this.fontScale, fontColor: this.readerStyle === 'colorPage' ? this.canvas().background : this.canvas().foreground,
        readingMode: true, serifForReading: this.usesSerif(), lineHeightScale: 1.48 })
      if ((attribution?.trim().length ?? 0) > 0) {
        Text(attribution as string).fontFamily(D.sans).fontSize(11 * this.fontScale)
          .fontColor(this.readerStyle === 'colorPage' ? this.canvas().background : this.canvas().muted).width('100%')
      }
    }.width('100%').alignItems(HorizontalAlign.Start)
      .padding({ left: 12 * this.fontScale, top: 2, bottom: 2 })
      .border({ width: { left: this.readerStyle === 'colorPage' || this.readerStyle === 'scroll' || this.readerStyle === 'broadsheet' ? 0 : this.readerStyle === 'minimal' ? 2 : 3 }, color: D.accent })
      .backgroundColor(this.readerStyle === 'colorPage' ? D.accent : this.readerStyle === 'journal' ? this.canvas().surface : Color.Transparent)
      .borderRadius(this.readerStyle === 'journal' || this.readerStyle === 'colorPage' ? 12 : 0).margin({ top: 5, bottom: 5 })
  }

  @Builder
  Citations(ids: number[] | undefined): void {
    if (nativeCitationNumbers(this.output, ids).length > 0) {
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(nativeCitationNumbers(this.output, ids), (id: number): void => {
          Text('[' + id.toString() + ']').fontSize(11 * this.fontScale).fontColor(D.accent)
            .fontFamily(D.sans).constraintSize({ minWidth: 44, minHeight: 44 })
            .textAlign(TextAlign.Center).onClick((): void => {
              const url: string = nativeArticleLinkUrl((this.output.sources as ReadingLink[])[id - 1].url);
              if (url.length > 0) this.onOpenLink(url);
            })
        }, (id: number): string => `magazine-citation-${id}`)
      }.width('100%')
    }
  }

  @Builder
  Outlook(): void {
    if ((this.output.impacts?.some((impact: DeepReadImpact): boolean => impact.effect.trim().length > 0) ?? false)
      || (this.output.watch?.some((item: string): boolean => item.trim().length > 0) ?? false)
      || (this.output.analysis.implications?.trim().length ?? 0) > 0) {
      Column({ space: 13 }) {
        this.Kicker('影响与走向')
        ForEach(this.output.impacts ?? [], (impact: DeepReadImpact, index: number): void => {
          if (impact.effect.trim().length > 0) {
            Column({ space: 6 }) {
              Text(impact.target + (impact.horizon === 'long' ? ' · 长期' : impact.horizon === 'short' ? ' · 短期' : ''))
                .fontSize(11 * this.fontScale).fontFamily(D.sans).fontColor(D.accent)
              this.BodyText(impact.effect)
            }.width('100%').alignItems(HorizontalAlign.Start)
          }
        }, (_impact: DeepReadImpact, index: number): string => `magazine-impact-${index}`)
        if ((this.output.analysis.implications?.trim().length ?? 0) > 0) {
          this.BodyText(this.output.analysis.implications as string)
        }
        if ((this.output.watch?.filter((item: string): boolean => item.trim().length > 0).length ?? 0) > 0) {
          Text('接下来关注').fontSize(11 * this.fontScale).fontFamily(D.sans).fontColor(this.canvas().muted)
          ForEach(this.output.watch ?? [], (item: string, index: number): void => {
            if (item.trim().length > 0) { this.BodyText('• ' + item) }
          }, (_item: string, index: number): string => `magazine-watch-${index}`)
        }
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 28 * this.fontScale })
    }
  }

  @Builder
  Uncertainties(): void {
    if (normalizedDeepReadUncertainties(this.output).length > 0) {
      Column({ space: 13 }) {
        this.Kicker('待核实')
        ForEach(normalizedDeepReadUncertainties(this.output), (item: DeepReadUncertainty, index: number): void => {
          Column({ space: 6 }) {
            if (item.status === 'single_source' || item.status === 'conflicting' || item.status === 'pending_official') {
              Text(item.status === 'single_source' ? '单一来源' : item.status === 'conflicting' ? '来源矛盾' : '待官方确认')
                .fontSize(11 * this.fontScale).fontFamily(D.sans).fontColor(D.accent)
            }
            this.BodyText(item.claim)
          }.width('100%').alignItems(HorizontalAlign.Start)
        }, (_item: DeepReadUncertainty, index: number): string => `magazine-uncertainty-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 28 * this.fontScale })
    }
  }

  @Builder
  OrderedSections(): void {
    ForEach(readerSectionOrder(this.output.topicType, this.readerLayout), (section: string): void => {
      if (section === 'core') { this.CorePoints() }
      else if (section === 'timeline') { this.Timeline() }
      else if (section === 'analysis') { this.Analysis() }
      else if (section === 'outlook') { this.Outlook() }
      else if (section === 'uncertainties') { this.Uncertainties() }
      else if (section === 'diagram' && this.output.diagram !== null) {
        DeepReadDiagram({ diagram: this.output.diagram, fontScale: this.fontScale, fontSerif: this.fontSerif })
          .margin({ top: 28 * this.fontScale })
      }
    }, (section: string): string => `magazine-section-${section}`)
  }

  @Builder
  References(label: string, links: ReadingLink[]): void {
    if (links.length > 0) {
      Column() {
        this.Kicker(label)
        ForEach(links, (link: ReadingLink, index: number): void => {
          Row({ space: 8 }) {
            if (label !== '补充来源') {
              Text('[' + (index + 1).toString() + ']').fontSize(10 * this.fontScale).fontFamily(D.sans).fontColor(D.accent)
                .margin({ top: 2 })
            }
            Column({ space: 4 }) {
              Text(link.title.trim().length > 0 ? link.title : link.url).fontSize(14 * this.fontScale)
                .fontFamily(this.family()).fontColor(this.canvas().foreground).lineHeight(23 * this.fontScale).width('100%')
              if ((link.source?.trim().length ?? 0) > 0 || (link.publishedAt?.trim().length ?? 0) > 0) {
                Text([link.source ?? '', link.publishedAt ?? ''].filter((value: string): boolean => value.trim().length > 0).join(' · '))
                  .fontSize(11 * this.fontScale).fontFamily(D.sans).fontColor(this.canvas().muted).width('100%')
              }
              if (label === '来源' && nativeArticleSourceCited(this.output, index + 1)) {
                Text('本文引用').fontSize(11 * this.fontScale).fontColor(D.accent).fontFamily(D.sans)
              }
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            if (nativeArticleLinkUrl(link.url).length > 0) {
              Text('↗').fontSize(13).fontColor(this.canvas().muted)
            }
          }.width('100%').constraintSize({ minHeight: 44 }).alignItems(VerticalAlign.Top)
            .padding({ top: 12, bottom: 12 }).border({ width: { top: this.readerStyle === 'minimal' ? 0 : 0.5 }, color: this.canvas().border })
            .onClick((): void => {
              const url: string = nativeArticleLinkUrl(link.url);
              if (url.length > 0) this.onOpenLink(url);
            })
        }, (_link: ReadingLink, index: number): string => `${label}-${index}`)
      }.width('100%').alignItems(HorizontalAlign.Start).margin({ top: 28 * this.fontScale })
    }
  }

  build(): void {
    Column() {
      this.Headline()
      this.OrderedSections()
      this.References('扩展阅读', this.standaloneExtendedReading())
      this.References((this.output.sources?.length ?? 0) > 0 ? '来源' : '参考来源', nativeArticleSources(this.output))
      this.References('补充来源', nativeSupplementarySources(this.output))
    }.width('100%').alignItems(HorizontalAlign.Start)
      .padding({ left: 22, right: 22, top: 28, bottom: 12 }).backgroundColor(this.canvas().background)
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
  }
}

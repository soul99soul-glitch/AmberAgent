// ArticleRenderer — DeepRead 文章渲染组件(杂志风)
// Android 基准: feature/ui/pages/board/DeepReadScreen.kt(2076 行,杂志风多帧布局)
//   鸿蒙 MVP:用 ArticleRenderer 统一渲染 DeepReadOutput 全量块;
//   文本块经 MarkdownText(原生 ArkUI 组件树)渲染,对齐聊天 assistant 文本路径。
//
// 覆盖块:Hero 图 / summary(Markdown)/ keyEntities chips / timeline / corePoints /
//   analysis(coreDispute + perspectives + implications + quotes)/ diagram / extendedReading / references
//
// G5-S1 增强:
//   - DeepReadScaledText:字号缩放包装(@State fontSizeMultiplier 驱动,对齐 Android fontScale)
//   - BasicDraftNotice:基础稿提示(sectionQualities 含 BASIC → 显示「基础稿,可继续增强」)
//   - Quote 卡片:accent 左竖条 + 斜体引文(对齐 Android MagazineQuoteFrame 视觉)
//   - Perspective 卡片:holder 名 accent 着色 + viewpoint 文本
//
// 降级(诚实):
//   - diagram:仅列节点 label(Canvas/SVG 绘制节点+边 = 设备验证项,TODO 登记)
//   - 无 hero 图:整块不渲染(非占位)
import type {
  DeepReadOutput, TimelineEvent, CorePoint, ReadingLink,
  Perspective, Quote, DeepReadDiagram, DiagramNode,
} from '@amber/deepread-domain';
import { displayHeroImageUrl, displayHeroCaption } from '@amber/deepread-domain';
import { MarkdownText } from './MarkdownText.ets';
import {
  INK, INK2, INK3, INK4, ACCENT, ACCENT_LIGHT, SURFACE, SURFACE2, LINE, BODY, META,
} from '../design/tokens.ets';

// 字号缩放上下限 — 照搬 Android BoardSettings.kt:131-133
const FONT_SCALE_MIN: number = 0.85;
const FONT_SCALE_MAX: number = 1.25;
const FONT_SCALE_STEP: number = 0.05;
const FONT_SCALE_DEFAULT: number = 1.0;

// sectionQualities 中出现 BASIC 即视为基础稿(对齐 Android hasBasicDraft)
const QUALITY_BASIC: string = 'BASIC';

@Component
export struct ArticleRenderer {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  output: DeepReadOutput = {} as DeepReadOutput;
  // 字号倍率(由父页 zoom 按钮驱动;@Prop 双向同步父 @State)
  @Prop fontSizeMultiplier: number = FONT_SCALE_DEFAULT;

  build(): void {
    Column() {
      // ── 字号缩放包装(对齐 Android DeepReadScaledText) ──
      //   ArkUI 无 CompositionLocal,改用把倍率传给每个文字组件的 fontSize。
      //   此 Column 仅作语义分组,实际缩放在各 Text/MarkdownText 的 fontSize 计算。
      this.ArticleBody()
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 16, right: 16, bottom: 24 })
    .alignItems(HorizontalAlign.Start)
  }

  // 字号计算辅助(非 @Builder,可声明逻辑)
  private scaledSize(base: number): number {
    const raw: number = base * this.fontSizeMultiplier;
    if (raw < 8) { return 8; }
    return raw;
  }

  // 基础稿判定(对齐 Android hasBasicDraft:sectionQualities 含 BASIC)
  private hasBasicDraft(): boolean {
    const q = this.output.sectionQualities;
    const keys = Object.keys(q);
    for (let i = 0; i < keys.length; i++) {
      if (q[keys[i]] === QUALITY_BASIC) { return true; }
    }
    return false;
  }

  @Builder
  ArticleBody(): void {
    // ── Hero 图(无图整块不渲染,诚实降级) ──
    if (displayHeroImageUrl(this.output) !== null) {
      Image(displayHeroImageUrl(this.output) as string)
        .width('100%')
        .height(220)
        .objectFit(ImageFit.Cover)
        .borderRadius(12)
      if (displayHeroCaption(this.output) !== null) {
        Text(displayHeroCaption(this.output) as string)
          .fontSize(this.scaledSize(META)).fontColor(INK3).margin({ top: 4, bottom: 8 })
      }
    }

    // ── 基础稿提示(对齐 Android BasicDraftNotice:846-862) ──
    if (this.hasBasicDraft()) {
      this.BasicDraftNotice()
    }

    // ── 概览 summary(Markdown 富文本) ──
    if (this.output.summary.trim().length > 0) {
      MarkdownText({
        content: this.output.summary,
        fontSize: this.scaledSize(16),
        fontColor: INK2,
        lineHeightScale: 1.7,
      })
        .margin({ top: 12, bottom: 8 })
    }

    // ── 关键实体 chips ──
    if (this.output.keyEntities.length > 0) {
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(this.output.keyEntities, (e: string) => {
          Text(e)
            .fontSize(this.scaledSize(META)).fontColor(ACCENT)
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .backgroundColor(ACCENT_LIGHT).borderRadius(10)
            .margin({ right: 6, bottom: 6 })
        })
      }.margin({ top: 4, bottom: 8 })
    }

    // ── 时间线 ──
    if (this.output.timeline !== null && this.output.timeline.length > 0) {
      this.SectionTitle('时间线')
      ForEach(this.output.timeline, (ev: TimelineEvent) => {
        Row() {
          Text(ev.date).fontSize(this.scaledSize(META)).fontColor(ACCENT).width(80)
          Text(ev.event).fontSize(this.scaledSize(BODY)).fontColor(INK2).layoutWeight(1).lineHeight(22)
        }.width('100%').margin({ bottom: 6 })
      })
    }

    // ── 核心要点 ──
    if (this.output.corePoints !== null && this.output.corePoints.length > 0) {
      this.SectionTitle('核心要点')
      ForEach(this.output.corePoints, (cp: CorePoint) => {
        Column() {
          Text(cp.point).fontSize(this.scaledSize(BODY)).fontWeight(FontWeight.Medium).fontColor(INK)
          if (cp.supporting !== null) {
            Text(cp.supporting).fontSize(this.scaledSize(META)).fontColor(INK3).lineHeight(20).margin({ top: 2 })
          }
        }.width('100%').margin({ bottom: 10 })
      })
    }

    // ── 深度分析(coreDispute + perspectives + implications + quotes) ──
    this.AnalysisBlock(this.output)

    // ── 图解(降级:列节点;Canvas/SVG = 设备验证项) ──
    if (this.output.diagram !== null) {
      this.DiagramBlock(this.output.diagram)
    }

    // ── 扩展阅读 ──
    if (this.output.extendedReading.length > 0) {
      this.SectionTitle('扩展阅读')
      ForEach(this.output.extendedReading, (l: ReadingLink) => {
        Row() {
          Text(l.title)
            .fontSize(this.scaledSize(BODY)).fontColor(ACCENT).layoutWeight(1)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(l.source ?? '').fontSize(this.scaledSize(META)).fontColor(INK4).margin({ left: 8 })
        }.width('100%').margin({ bottom: 4 })
      })
    }

    // ── 参考来源 ──
    if (this.output.references.length > 0) {
      this.SectionTitle('参考来源')
      ForEach(this.output.references, (r: ReadingLink, idx: number) => {
        Text(`[${idx + 1}] ${r.title} — ${r.url}`)
          .fontSize(this.scaledSize(META)).fontColor(INK3).margin({ bottom: 2 })
      })
    }
  }

  @Builder
  BasicDraftNotice(): void {
    // 对齐 Android BasicDraftNotice:accent 8% 底 + 圆角 + 「基础稿,可继续增强」
    Row() {
      Text('基础稿,可继续增强')
        .fontSize(this.scaledSize(META))
        .fontColor(INK3)
        .lineHeight(18)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
    .backgroundColor(ACCENT_LIGHT)
    .borderRadius(18)
    .margin({ top: 12, bottom: 8 })
  }

  @Builder
  SectionTitle(title: string): void {
    Row() {
      Text('//').fontSize(this.scaledSize(META)).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(' ' + title).fontSize(this.scaledSize(BODY)).fontWeight(FontWeight.Medium).fontColor(INK2)
    }
    .width('100%')
    .margin({ top: 24, bottom: 10 })
  }

  @Builder
  AnalysisBlock(o: DeepReadOutput): void {
    // 核心分歧(Markdown)
    if (o.analysis.coreDispute !== null && o.analysis.coreDispute.length > 0) {
      this.SectionTitle('核心分歧')
      MarkdownText({
        content: o.analysis.coreDispute,
        fontSize: this.scaledSize(BODY),
        fontColor: INK2,
        lineHeightScale: 1.7,
      })
    }
    // 各方立场 — Perspective 卡片(holder accent + viewpoint)
    if (o.analysis.perspectives.length > 0) {
      this.SectionTitle('各方立场')
      ForEach(o.analysis.perspectives, (p: Perspective) => {
        this.PerspectiveCard(p)
      })
    }
    // 影响分析(Markdown)
    if (o.analysis.implications !== null && o.analysis.implications.length > 0) {
      this.SectionTitle('影响分析')
      MarkdownText({
        content: o.analysis.implications,
        fontSize: this.scaledSize(BODY),
        fontColor: INK2,
        lineHeightScale: 1.7,
      })
    }
    // 引述 — Quote 卡片(accent 左竖条 + 斜体)
    if (o.analysis.quotes.length > 0) {
      this.SectionTitle('引述')
      ForEach(o.analysis.quotes, (q: Quote) => {
        this.QuoteCard(q)
      })
    }
  }

  @Builder
  PerspectiveCard(p: Perspective): void {
    // holder 名 accent 着色 + viewpoint 正文(对齐 Android MagazinePerspectiveFrame 视觉)
    Column() {
      if (p.holder !== null) {
        Text(p.holder)
          .fontSize(this.scaledSize(META))
          .fontColor(ACCENT)
          .fontWeight(FontWeight.Medium)
      }
      Text(p.viewpoint)
        .fontSize(this.scaledSize(BODY))
        .fontColor(INK2)
        .lineHeight(22)
        .margin({ top: 4 })
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(SURFACE2)
    .borderRadius(10)
    .margin({ bottom: 8 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  QuoteCard(q: Quote): void {
    // accent 左竖条 + 斜体引文 + 归属(对齐 Android MagazineQuoteFrame 视觉)
    Row() {
      // 左竖条(accent)
      Column() {}
        .width(3)
        .height('100%')
        .backgroundColor(ACCENT)
        .borderRadius(2)

      Column() {
        Text(`「${q.text}」`)
          .fontSize(this.scaledSize(BODY))
          .fontColor(INK3)
          .fontStyle(FontStyle.Italic)
          .lineHeight(22)
        if (q.attribution !== null) {
          Text(`— ${q.attribution}`)
            .fontSize(this.scaledSize(META))
            .fontColor(INK4)
            .margin({ top: 4 })
        }
      }
      .layoutWeight(1)
      .padding({ left: 12 })
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .margin({ bottom: 8, top: 4 })
    .alignItems(VerticalAlign.Top)
  }

  @Builder
  DiagramBlock(d: DeepReadDiagram): void {
    this.SectionTitle('图解')
    Text(d.title).fontSize(this.scaledSize(BODY)).fontWeight(FontWeight.Medium).fontColor(INK)
    // 降级:仅列节点 id+label(Canvas/SVG 绘制节点+边 = 设备验证项,登记)
    ForEach(d.nodes, (n: DiagramNode) => {
      Row() {
        Text(n.id).fontSize(this.scaledSize(META)).fontColor(INK4).width(30)
        Text(n.label).fontSize(this.scaledSize(BODY)).fontColor(INK2).layoutWeight(1)
      }.margin({ bottom: 2 })
    })
    if (d.caption !== null && d.caption.length > 0) {
      Text(d.caption).fontSize(this.scaledSize(META)).fontColor(INK4).margin({ top: 4 })
    }
  }
}

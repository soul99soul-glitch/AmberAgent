// ArticleRenderer — 对照设计 §7.3 文章渲染组件(杂志风)
// HeroImageBlock / TimelineBlock / CorePointsBlock / AnalysisBlock / DiagramBlock /
// ExtendedReadingBlock / ReferencesBlock
//
// Markdown 渲染:§7.3 风险 — 鸿蒙 markdown 生态不成熟。MVP 用 Text 组件直出纯文本,
// TODO(phase6): 自建轻量 markdown→ArkUI node 转换 或 接 ArkUI RichText。

import type { DeepReadOutput, TimelineEvent, CorePoint, ReadingLink, Perspective, Quote, DeepReadDiagram } from '@amber/deepread-domain';
import { displayHeroImageUrl, displayHeroCaption } from '@amber/deepread-domain';

@Component
export struct ArticleRenderer {
  output: DeepReadOutput = {} as DeepReadOutput;

  build(): void {
    Column() {
      // Hero
      if (displayHeroImageUrl(this.output) !== null) {
        Image(displayHeroImageUrl(this.output) as string)
          .width('100%')
          .height(220)
          .objectFit(ImageFit.Cover)
          .borderRadius(12)
        if (displayHeroCaption(this.output) !== null) {
          Text(displayHeroCaption(this.output) as string)
            .fontSize(12).fontColor('#666').margin({ top: 4, bottom: 8 })
        }
      }

      // 概览 summary
      if (this.output.summary.trim().length > 0) {
        Text(this.output.summary).fontSize(16).lineHeight(26).margin({ top: 12, bottom: 8 })
      }

      // key entities
      if (this.output.keyEntities.length > 0) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.output.keyEntities, (e: string) => {
            Text(e).fontSize(11).fontColor('#1976D2').padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .backgroundColor('#E3F2FD').borderRadius(10).margin({ right: 6, bottom: 6 })
          })
        }.margin({ top: 4, bottom: 8 })
      }

      // Timeline
      if (this.output.timeline !== null && this.output.timeline.length > 0) {
        this.SectionTitle('时间线')
        ForEach(this.output.timeline, (ev: TimelineEvent) => {
          Row() {
            Text(ev.date).fontSize(12).fontColor('#1976D2').width(80)
            Text(ev.event).fontSize(14).layoutWeight(1).lineHeight(22)
          }.width('100%').margin({ bottom: 6 })
        })
      }

      // Core points
      if (this.output.corePoints !== null && this.output.corePoints.length > 0) {
        this.SectionTitle('核心要点')
        ForEach(this.output.corePoints, (cp: CorePoint) => {
          Column() {
            Text(cp.point).fontSize(14).fontWeight(FontWeight.Medium)
            if (cp.supporting !== null) {
              Text(cp.supporting).fontSize(13).fontColor('#555').lineHeight(20).margin({ top: 2 })
            }
          }.width('100%').margin({ bottom: 10 })
        })
      }

      // Analysis
      this.AnalysisBlock(this.output)

      // Diagram
      if (this.output.diagram !== null) {
        this.DiagramBlock(this.output.diagram)
      }

      // Extended reading
      if (this.output.extendedReading.length > 0) {
        this.SectionTitle('扩展阅读')
        ForEach(this.output.extendedReading, (l: ReadingLink) => {
          Row() {
            Text(l.title).fontSize(13).layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).fontColor('#1976D2')
            Text(l.source ?? '').fontSize(11).fontColor('#999')
          }.width('100%').margin({ bottom: 4 })
        })
      }

      // References
      if (this.output.references.length > 0) {
        this.SectionTitle('参考来源')
        ForEach(this.output.references, (r: ReadingLink, idx: number) => {
          Text(`[${idx + 1}] ${r.title} — ${r.url}`).fontSize(11).fontColor('#777').margin({ bottom: 2 })
        })
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, bottom: 24 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  SectionTitle(title: string): void {
    Text(title).fontSize(18).fontWeight(FontWeight.Bold).margin({ top: 20, bottom: 8 })
  }

  @Builder
  AnalysisBlock(o: DeepReadOutput): void {
    if (o.analysis.coreDispute !== null && o.analysis.coreDispute.length > 0) {
      this.SectionTitle('深度分析')
      Text('核心分歧').fontSize(14).fontWeight(FontWeight.Medium).margin({ top: 4 })
      Text(o.analysis.coreDispute).fontSize(14).lineHeight(22).fontColor('#333').margin({ top: 2, bottom: 8 })
    }
    if (o.analysis.perspectives.length > 0) {
      Text('各方立场').fontSize(14).fontWeight(FontWeight.Medium)
      ForEach(o.analysis.perspectives, (p: Perspective) => {
        Column() {
          if (p.holder !== null) {
            Text(p.holder).fontSize(12).fontColor('#1976D2')
          }
          Text(p.viewpoint).fontSize(13).lineHeight(20).margin({ top: 2 })
        }.margin({ bottom: 8, left: 8 })
      })
    }
    if (o.analysis.implications !== null && o.analysis.implications.length > 0) {
      Text('影响分析').fontSize(14).fontWeight(FontWeight.Medium).margin({ top: 4 })
      Text(o.analysis.implications).fontSize(13).lineHeight(22).margin({ top: 2, bottom: 8 })
    }
    if (o.analysis.quotes.length > 0) {
      Text('引述').fontSize(14).fontWeight(FontWeight.Medium).margin({ top: 4 })
      ForEach(o.analysis.quotes, (q: Quote) => {
        Column() {
          Text(`「${q.text}」`).fontSize(13).fontColor('#555').lineHeight(20)
          if (q.attribution !== null) {
            Text(`— ${q.attribution}`).fontSize(11).fontColor('#999').margin({ top: 2 })
          }
        }.margin({ bottom: 6, left: 8 })
      })
    }
  }

  @Builder
  DiagramBlock(d: DeepReadDiagram): void {
    this.SectionTitle('图解')
    Text(d.title).fontSize(15).fontWeight(FontWeight.Medium)
    // TODO(phase6): 真正的 diagram 渲染(Canvas/SVG 绘制节点+边)。MVP 先列节点。
    ForEach(d.nodes, (n: DiagramNode) => {
      Row() {
        Text(n.id).fontSize(11).fontColor('#999').width(30)
        Text(n.label).fontSize(13).layoutWeight(1)
      }.margin({ bottom: 2 })
    })
    if (d.caption !== null && d.caption.length > 0) {
      Text(d.caption).fontSize(11).fontColor('#999').margin({ top: 4 })
    }
  }
}

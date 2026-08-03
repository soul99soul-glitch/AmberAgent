// ArticleRenderer — DeepRead 文章渲染组件(杂志风)
// Android 基准: feature/ui/pages/board/DeepReadScreen.kt(2076 行,杂志风多帧布局)
//   鸿蒙 MVP:用 ArticleRenderer 统一渲染 DeepReadOutput 全量块;
//   文本块经 MarkdownText(原生 ArkUI 组件树)渲染,对齐聊天 assistant 文本路径。
//
// 覆盖块:Hero 图 / summary(Markdown)/ keyEntities chips / timeline / corePoints /
//   analysis(coreDispute + perspectives + implications + quotes)/ diagram / extendedReading / references
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

@Component
export struct ArticleRenderer {
  output: DeepReadOutput = {} as DeepReadOutput;

  build(): void {
    Column() {
      // ── Hero 图(无图整块不渲染,诚实降级) ──
      if (displayHeroImageUrl(this.output) !== null) {
        Image(displayHeroImageUrl(this.output) as string)
          .width('100%')
          .height(220)
          .objectFit(ImageFit.Cover)
          .borderRadius(12)
        if (displayHeroCaption(this.output) !== null) {
          Text(displayHeroCaption(this.output) as string)
            .fontSize(META).fontColor(INK3).margin({ top: 4, bottom: 8 })
        }
      }

      // ── 概览 summary(Markdown 富文本) ──
      if (this.output.summary.trim().length > 0) {
        MarkdownText({ content: this.output.summary, fontSize: 16, fontColor: INK2 })
          .margin({ top: 12, bottom: 8 })
      }

      // ── 关键实体 chips ──
      if (this.output.keyEntities.length > 0) {
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.output.keyEntities, (e: string) => {
            Text(e)
              .fontSize(META).fontColor(ACCENT)
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
            Text(ev.date).fontSize(META).fontColor(ACCENT).width(80)
            Text(ev.event).fontSize(BODY).fontColor(INK2).layoutWeight(1).lineHeight(22)
          }.width('100%').margin({ bottom: 6 })
        })
      }

      // ── 核心要点 ──
      if (this.output.corePoints !== null && this.output.corePoints.length > 0) {
        this.SectionTitle('核心要点')
        ForEach(this.output.corePoints, (cp: CorePoint) => {
          Column() {
            Text(cp.point).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
            if (cp.supporting !== null) {
              Text(cp.supporting).fontSize(META).fontColor(INK3).lineHeight(20).margin({ top: 2 })
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
              .fontSize(BODY).fontColor(ACCENT).layoutWeight(1)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(l.source ?? '').fontSize(META).fontColor(INK4).margin({ left: 8 })
          }.width('100%').margin({ bottom: 4 })
        })
      }

      // ── 参考来源 ──
      if (this.output.references.length > 0) {
        this.SectionTitle('参考来源')
        ForEach(this.output.references, (r: ReadingLink, idx: number) => {
          Text(`[${idx + 1}] ${r.title} — ${r.url}`)
            .fontSize(META).fontColor(INK3).margin({ bottom: 2 })
        })
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, bottom: 24 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  SectionTitle(title: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(' ' + title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2)
    }
    .width('100%')
    .margin({ top: 20, bottom: 8 })
  }

  @Builder
  AnalysisBlock(o: DeepReadOutput): void {
    // 核心分歧(Markdown)
    if (o.analysis.coreDispute !== null && o.analysis.coreDispute.length > 0) {
      this.SectionTitle('核心分歧')
      MarkdownText({ content: o.analysis.coreDispute, fontSize: BODY, fontColor: INK2 })
    }
    // 各方立场
    if (o.analysis.perspectives.length > 0) {
      this.SectionTitle('各方立场')
      ForEach(o.analysis.perspectives, (p: Perspective) => {
        Column() {
          if (p.holder !== null) {
            Text(p.holder).fontSize(META).fontColor(ACCENT)
          }
          Text(p.viewpoint).fontSize(BODY).fontColor(INK2).lineHeight(20).margin({ top: 2 })
        }.margin({ bottom: 8, left: 8 })
      })
    }
    // 影响分析(Markdown)
    if (o.analysis.implications !== null && o.analysis.implications.length > 0) {
      Text('影响分析').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 4 })
      MarkdownText({ content: o.analysis.implications, fontSize: BODY, fontColor: INK2 })
    }
    // 引述
    if (o.analysis.quotes.length > 0) {
      Text('引述').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK2).margin({ top: 4 })
      ForEach(o.analysis.quotes, (q: Quote) => {
        Column() {
          Text(`「${q.text}」`).fontSize(BODY).fontColor(INK3).lineHeight(20)
          if (q.attribution !== null) {
            Text(`— ${q.attribution}`).fontSize(META).fontColor(INK4).margin({ top: 2 })
          }
        }.margin({ bottom: 6, left: 8 })
      })
    }
  }

  @Builder
  DiagramBlock(d: DeepReadDiagram): void {
    this.SectionTitle('图解')
    Text(d.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
    // 降级:仅列节点 id+label(Canvas/SVG 绘制节点+边 = 设备验证项,登记)
    ForEach(d.nodes, (n: DiagramNode) => {
      Row() {
        Text(n.id).fontSize(META).fontColor(INK4).width(30)
        Text(n.label).fontSize(BODY).fontColor(INK2).layoutWeight(1)
      }.margin({ bottom: 2 })
    })
    if (d.caption !== null && d.caption.length > 0) {
      Text(d.caption).fontSize(META).fontColor(INK4).margin({ top: 4 })
    }
  }
}

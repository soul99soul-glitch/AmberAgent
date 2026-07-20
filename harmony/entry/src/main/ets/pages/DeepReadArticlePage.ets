// DeepReadArticlePage — 杂志风文章页(衬线标题 + 热度 + 正文分段 + 扩展阅读)
// 对照 Android DeepReadArticlePage + 杂志排版标准

import { router } from '@kit.ArkUI';
import type { DeepReadOutput, DeepReadGenerationStage, ReadingLink } from '@amber/deepread-domain';
import {
  STAGE_ORDER, STAGE_LABELS, statusOf, displayHeroImageUrl, displayHeroCaption,
} from '@amber/deepread-domain';
import {
  BG, SURFACE, SURFACE2, INK, INK2, INK3, INK4, LINE, ACCENT, ACCENT_LIGHT,
} from '../design/tokens.ets';

interface ArticleParams {
  topicId: string;
  title: string;
}

@Entry
@Component
struct DeepReadArticlePage {
  @State topicId: string = '';
  @State title: string = '';
  @State heat: string = '';
  @State articleText: string = '';
  @State timeline: string[] = [];
  @State keyPoints: string[] = [];
  @State analysis: string = '';
  @State references: ReadingLink[] = [];
  @State heroUrl: string = '';
  @State running: boolean = false;
  @State status: string = '';

  aboutToAppear(): void {
    const params: ArticleParams = router.getParams() as ArticleParams;
    this.topicId = params.topicId;
    this.title = params.title;
    // 演示模式:预置内容展示排版
    this.heat = '1.2M 热度';
    this.articleText =
      'Google Quantum AI 团队今日宣布推出全新一代纠错芯片 Willow,实现了量子比特错误率的历史性突破,将错误率从 5% 降至 0.1%。这一进展标志着实用化量子计算机距离现实又近了一步。\n\n' +
      '据团队负责人介绍,Willow 芯片采用了全新的表面码纠错架构,能够在保持量子相干性的同时,大幅降低计算过程中的误差累积。该芯片在标准基准测试中达到了 99.99% 的保真度,为构建容错量子计算机奠定了关键基础。\n\n' +
      '业内专家认为,这一突破将加速量子计算在药物研发、材料科学、密码学等领域的实际应用,预计 5-8 年内实现商业化落地。';
    this.timeline = [
      '2023年12月,Google 首次提出表面码纠错架构',
      '2024年3月,Willow 芯片进入原型测试阶段',
      '2025年6月,错误率降至 0.1%,创历史纪录',
      '2025年7月,标准基准测试达到 99.99% 保真度',
    ];
    this.keyPoints = [
      '量子比特错误率从 5% 降至 0.1%',
      '表面码纠错架构大幅降低误差累积',
      '标准基准测试达到 99.99% 保真度',
      '预计 5-8 年内实现商业化落地',
    ];
    this.analysis = '这一突破的核心意义在于解决了量子计算实用化的最大瓶颈——错误率。量子比特极易受环境干扰,导致计算错误率居高不下,严重限制了量子计算机的实际应用。Willow 芯片的表面码纠错架构通过增加冗余量子比特来检测和纠正错误,使得大规模量子计算成为可能。';
    this.references = [
      { title: 'Google Quantum AI 官方博客', url: 'https://quantumai.google/blog', source: 'Google', publishedAt: null },
      { title: 'Nature:Quantum error correction below threshold', url: 'https://example.com/nature', source: 'Nature', publishedAt: null },
      { title: '知乎:如何看待量子计算突破', url: 'https://example.com/zhihu', source: '知乎', publishedAt: null },
    ];
  }

  build(): void {
    Column() {
      // ── 顶栏:返回 + 标题(衬线) ──
      Row() {
        Text('←').fontSize(18).fontColor(INK3)
          .onClick(() => { router.back(); })
        Text('深度阅读').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
          .fontFamily('serif').margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 })
      .backgroundColor(BG)

      // ── 文章内容(滚动) ──
      Scroll() {
        Column() {
          // ── 标题:衬线大字 ──
          Text(this.title)
            .fontSize(24).fontWeight(FontWeight.Bold).fontColor(INK)
            .fontFamily('serif')
            .lineHeight(34).maxLines(3)

          // ── 热度 ──
          if (this.heat.length > 0) {
            Text(this.heat).fontSize(12).fontColor(INK3).margin({ top: 10 })
          }

          // ── 分隔线 ──
          Column() {}.height(1).width('100%').backgroundColor(LINE).margin({ top: 16, bottom: 20 })

          // ── 正文:衬线分段 ──
          ForEach(this.articleText.split('\n\n'), (para: string) => {
            Text(para)
              .fontSize(16).fontColor(INK2)
              .fontFamily('serif')
              .lineHeight(26)
              .margin({ bottom: 20 })
          })

          // ── 时间线 ──
          if (this.timeline.length > 0) {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 时间线').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
            }
            .width('100%').margin({ top: 24, bottom: 10 })

            ForEach(this.timeline, (item: string, idx: number) => {
              Row() {
                Text((idx + 1) + '.').fontSize(13).fontColor(INK4).width(28)
                Text(item).fontSize(14).fontColor(INK2).layoutWeight(1)
              }
              .width('100%').padding({ top: 10, bottom: 10 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            })
          }

          // ── 核心要点 ──
          if (this.keyPoints.length > 0) {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 核心要点').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
            }
            .width('100%').margin({ top: 24, bottom: 10 })

            ForEach(this.keyPoints, (point: string, idx: number) => {
              Row() {
                Text((idx + 1) + '.').fontSize(13).fontColor(INK4).width(28)
                Text(point).fontSize(14).fontColor(INK2).layoutWeight(1)
              }
              .width('100%').padding({ top: 10, bottom: 10 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            })
          }

          // ── 深度分析 ──
          if (this.analysis.length > 0) {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 深度分析').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
            }
            .width('100%').margin({ top: 24, bottom: 10 })

            Text(this.analysis)
              .fontSize(15).fontColor(INK2)
              .fontFamily('serif')
              .lineHeight(24)
          }

          // ── 扩展阅读 ──
          if (this.references.length > 0) {
            Row() {
              Text('//').fontSize(13).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 扩展阅读').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
            }
            .width('100%').margin({ top: 24, bottom: 10 })

            ForEach(this.references, (ref: ReadingLink) => {
              Row() {
                Text(ref.title)
                  .fontSize(14).fontColor(ACCENT).layoutWeight(1).maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(ref.source ?? '').fontSize(11).fontColor(INK4).margin({ left: 8 })
              }
              .width('100%').padding({ top: 10, bottom: 10 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            })
          }

          // 底部留白
          Column() {}.height(40)
        }
        .width('100%').padding({ left: 24, right: 24 })
      }
      .layoutWeight(1)
      .backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}

// NovelPolishCreateSheet — durable batch-polish 的冻结范围与上下文选择。
// 只把用户选择交给父页面；冻结原文、互斥和持久化均由领域/仓库负责。

import {
  ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR, INK, INK2, INK3, INK4,
  LINE, OVERLAY, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import type { PolishContextOptions } from '@amber/deepread-domain';

export interface NovelPolishCreateSheetChapter {
  id: string;
  ordinal: number;
  title: string;
  discarded: boolean;
}

@Component
export struct NovelPolishCreateSheet {
  @Prop visible: boolean = false;
  @Prop chapters: NovelPolishCreateSheetChapter[] = [];
  @Prop modelReady: boolean = false;
  @Prop modelDisabledReason: string = '请先配置可用的写作与审稿模型。';
  @Prop conflictReason: string = '';
  @Prop busy: boolean = false;
  // 父页面传入底部避让高度；sheet 不假设设备安全区尺寸。
  @Prop bottomPadding: number = 0;

  onStart: (startOrdinal: number, endOrdinal: number, context: PolishContextOptions) => void = (
    _startOrdinal: number, _endOrdinal: number, _context: PolishContextOptions,
  ): void => {};
  onClose: () => void = (): void => {};

  @State private startOrdinal: number = 0;
  @State private endOrdinal: number = 0;
  @State private includePlot: boolean = true;
  @State private includeForeshadows: boolean = true;
  @State private includeCharacters: boolean = true;
  @State private includeDecisions: boolean = true;

  aboutToAppear(): void {
    this.resetRange();
  }

  build(): void {
    if (this.visible) {
      Stack() {
        Row() {}
          .width('100%').height('100%').backgroundColor(OVERLAY)
          .onClick((): void => { if (!this.busy) this.onClose(); })
        Column() {
          this.header()
          Scroll() {
            Column() {
              this.rangeSection()
              this.contextSection()
              this.safetySection()
              this.gateSection()
            }
            .width('100%').alignItems(HorizontalAlign.Start)
          }
          .width('100%').layoutWeight(1)
          this.actionRow()
        }
        .width('100%').constraintSize({ maxHeight: '90%' })
        .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ left: 20, right: 20, top: 18, bottom: 24 + this.bottomPadding })
      }
      .width('100%').height('100%').alignContent(Alignment.Bottom)
    }
  }

  @Builder
  private header(): void {
    Row() {
      Column() {
        Text('创建批量润色')
          .fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
        Text('选择一个连续章节区间，任务会持久化到当前工作区。')
          .fontSize(12).fontColor(INK3).lineHeight(18).margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text('关闭').fontSize(13).fontColor(this.busy ? INK4 : INK3)
        .width(44).height(44).textAlign(TextAlign.Center)
        .onClick((): void => { if (!this.busy) this.onClose(); })
        .accessibilityText('关闭创建批量润色面板')
    }
    .width('100%').margin({ bottom: 12 }).alignItems(VerticalAlign.Center)
  }

  @Builder
  private rangeSection(): void {
    Column() {
      Text('章节范围').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Text('仅显示未废弃章节；起止范围内的章节序号必须连续。')
        .fontSize(11).fontColor(INK3).lineHeight(16).margin({ top: 3, bottom: 8 })
      if (this.activeChapters().length === 0) {
        Text('当前没有可润色的章节。').fontSize(13).fontColor(INK3)
          .width('100%').padding({ top: 12, bottom: 12 })
      } else {
        Text('起始章节').fontSize(11).fontColor(INK3).margin({ bottom: 5 })
        this.chapterPicker(this.activeChapters(), this.startOrdinal, true)
        Text('结束章节').fontSize(11).fontColor(INK3).margin({ top: 10, bottom: 5 })
        this.chapterPicker(this.endCandidates(), this.endOrdinal, false)
        Text(this.rangeSummary()).fontSize(11).fontColor(INK3).lineHeight(16)
          .width('100%').margin({ top: 8 })
      }
    }
    .width('100%').padding({ top: 2, bottom: 14 })
  }

  @Builder
  private chapterPicker(
    chapters: NovelPolishCreateSheetChapter[], selectedOrdinal: number, selectingStart: boolean,
  ): void {
    Scroll() {
      Column() {
        ForEach(chapters, (chapter: NovelPolishCreateSheetChapter): void => {
          Row() {
            Text(`第 ${chapter.ordinal} 章`).fontSize(12)
              .fontColor(chapter.ordinal === selectedOrdinal ? ACCENT : INK3).width(58)
            Text(this.chapterTitle(chapter)).fontSize(13)
              .fontColor(chapter.ordinal === selectedOrdinal ? ACCENT : INK)
              .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (chapter.ordinal === selectedOrdinal) {
              Text('已选').fontSize(11).fontColor(ACCENT).margin({ left: 8 })
            }
          }
          .width('100%').height(44).padding({ left: 12, right: 12 })
          .backgroundColor(chapter.ordinal === selectedOrdinal ? ACCENT_LIGHT : SURFACE2)
          .border({ width: 1, color: chapter.ordinal === selectedOrdinal ? ACCENT : LINE }).borderRadius(10)
          .margin({ bottom: 6 }).onClick((): void => { this.selectOrdinal(chapter.ordinal, selectingStart); })
          .accessibilityText(`${selectingStart ? '起始' : '结束'}章节，第 ${chapter.ordinal} 章${this.chapterTitle(chapter)}`)
        }, (chapter: NovelPolishCreateSheetChapter): string => chapter.id)
      }
      .width('100%')
    }
    .width('100%').constraintSize({ maxHeight: '24%' })
  }

  @Builder
  private contextSection(): void {
    Column() {
      Text('冻结上下文').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Text('启动时会将勾选来源连同原稿范围一起冻结，后续修改不会悄悄混入本次任务。')
        .fontSize(11).fontColor(INK3).lineHeight(16).margin({ top: 3, bottom: 7 })
      this.contextToggle('剧情摘要', 'includePlot', this.includePlot)
      this.contextToggle('伏笔', 'includeForeshadows', this.includeForeshadows)
      this.contextToggle('角色资料', 'includeCharacters', this.includeCharacters)
      this.contextToggle('已确认决策', 'includeDecisions', this.includeDecisions)
    }
    .width('100%').padding({ top: 12, bottom: 10 })
    .border({ width: { top: 0.5 }, color: { top: LINE } })
  }

  @Builder
  private contextToggle(title: string, key: string, checked: boolean): void {
    Row() {
      Text(title).fontSize(13).fontColor(INK).layoutWeight(1)
      Toggle({ type: ToggleType.Switch, isOn: checked })
        .selectedColor(ACCENT).onChange((next: boolean): void => { this.setContextOption(key, next); })
        .accessibilityText(`${title}上下文${checked ? '已启用' : '未启用'}`)
    }
    .width('100%').height(44).padding({ left: 2, right: 2 })
  }

  @Builder
  private safetySection(): void {
    Column() {
      Text('安全收录').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Text('原稿会以内容摘要冻结；候选、审稿与最多两次定向重写都不会直接改正文。')
        .fontSize(12).fontColor(INK2).lineHeight(18).margin({ top: 5 })
      Text('只有审稿通过后，版本、正文、receipt 与进度才会原子收录；可在工作区版本记录中恢复。')
        .fontSize(12).fontColor(INK2).lineHeight(18).margin({ top: 5 })
    }
    .width('100%').padding({ top: 12, bottom: 12 })
    .border({ width: { top: 0.5 }, color: { top: LINE } })
  }

  @Builder
  private gateSection(): void {
    Column() {
      this.gateRow('职责模型', this.modelReady, this.modelDisabledReason)
      if (this.conflictReason.trim().length > 0) {
        this.gateRow('工作区作业', false, this.conflictReason)
      }
    }
    .width('100%').padding({ top: 4, bottom: 8 })
  }

  @Builder
  private gateRow(title: string, ready: boolean, reason: string): void {
    Row() {
      Text(ready ? '已就绪' : '需处理').fontSize(11).fontColor(ready ? ACCENT : ERROR)
        .width(52).height(24).textAlign(TextAlign.Center).borderRadius(12)
        .backgroundColor(ready ? ACCENT_LIGHT : SURFACE2)
      Column() {
        Text(title).fontSize(13).fontColor(INK2)
        Text(ready ? '创建时会冻结当前模型策略。' : reason).fontSize(11)
          .fontColor(ready ? INK3 : ERROR).lineHeight(16).maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 2 })
      }
      .layoutWeight(1).alignItems(HorizontalAlign.Start).margin({ left: 10 })
    }
    .width('100%').constraintSize({ minHeight: 52 }).padding({ top: 7, bottom: 7 })
    .border({ width: { top: 0.5 }, color: { top: LINE } }).alignItems(VerticalAlign.Center)
  }

  @Builder
  private actionRow(): void {
    Text(this.busy ? '正在创建…' : '创建润色任务').fontSize(14).fontWeight(FontWeight.Medium)
      .fontColor(this.canStart() ? ACCENT_INK : INK4).textAlign(TextAlign.Center)
      .width('100%').height(44).padding({ top: 12, bottom: 12 })
      .backgroundColor(this.canStart() ? ACCENT : SURFACE2).borderRadius(12)
      .border({ width: 1, color: this.canStart() ? ACCENT : LINE }).opacity(this.canStart() ? 1 : 0.5)
      .margin({ top: 12 }).onClick((): void => { this.start(); })
      .accessibilityText('创建批量润色任务')
  }

  private activeChapters(): NovelPolishCreateSheetChapter[] {
    return this.chapters.filter((chapter: NovelPolishCreateSheetChapter): boolean => !chapter.discarded);
  }

  private endCandidates(): NovelPolishCreateSheetChapter[] {
    const active: NovelPolishCreateSheetChapter[] = this.activeChapters();
    const startIndex: number = this.chapterIndex(active, this.startOrdinal);
    if (startIndex < 0) return [];
    const candidates: NovelPolishCreateSheetChapter[] = [];
    for (let index: number = startIndex; index < active.length; index++) {
      if (index > startIndex && active[index].ordinal !== active[index - 1].ordinal + 1) break;
      candidates.push(active[index]);
    }
    return candidates;
  }

  private chapterIndex(chapters: NovelPolishCreateSheetChapter[], ordinal: number): number {
    for (let index: number = 0; index < chapters.length; index++) {
      if (chapters[index].ordinal === ordinal) return index;
    }
    return -1;
  }

  private resetRange(): void {
    const active: NovelPolishCreateSheetChapter[] = this.activeChapters();
    if (active.length === 0) {
      this.startOrdinal = 0;
      this.endOrdinal = 0;
      return;
    }
    this.startOrdinal = active[0].ordinal;
    this.endOrdinal = active[0].ordinal;
  }

  private selectOrdinal(ordinal: number, selectingStart: boolean): void {
    if (selectingStart) {
      this.startOrdinal = ordinal;
      const candidates: NovelPolishCreateSheetChapter[] = this.endCandidates();
      this.endOrdinal = candidates.length > 0 ? candidates[0].ordinal : ordinal;
    } else {
      this.endOrdinal = ordinal;
    }
  }

  private setContextOption(key: string, value: boolean): void {
    if (key === 'includePlot') this.includePlot = value;
    else if (key === 'includeForeshadows') this.includeForeshadows = value;
    else if (key === 'includeCharacters') this.includeCharacters = value;
    else if (key === 'includeDecisions') this.includeDecisions = value;
  }

  private contextOptions(): PolishContextOptions {
    return {
      includePlot: this.includePlot,
      includeForeshadows: this.includeForeshadows,
      includeCharacters: this.includeCharacters,
      includeDecisions: this.includeDecisions,
    };
  }

  private canStart(): boolean {
    return !this.busy && this.modelReady && this.conflictReason.trim().length === 0 &&
      this.endCandidates().some((chapter: NovelPolishCreateSheetChapter): boolean =>
        chapter.ordinal === this.endOrdinal);
  }

  private start(): void {
    if (!this.canStart()) return;
    this.onStart(this.startOrdinal, this.endOrdinal, this.contextOptions());
  }

  private chapterTitle(chapter: NovelPolishCreateSheetChapter): string {
    return chapter.title.trim().length > 0 ? chapter.title : '未命名章节';
  }

  private rangeSummary(): string {
    if (this.startOrdinal <= 0 || this.endOrdinal <= 0) return '请选择可用章节。';
    const count: number = this.endOrdinal - this.startOrdinal + 1;
    return `将冻结第 ${this.startOrdinal}–${this.endOrdinal} 章，共 ${count} 章。`;
  }
}

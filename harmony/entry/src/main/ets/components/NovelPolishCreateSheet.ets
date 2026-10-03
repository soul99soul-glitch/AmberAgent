// NovelPolishCreateSheet — durable batch-polish 的冻结范围与上下文选择。
// 只把用户选择交给父页面；冻结原文、互斥和持久化均由领域/仓库负责。

import {
  ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR, INK, INK2, INK3, INK4,
  LINE, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import type { PolishContextOptions } from '@amber/deepread-domain';
import { loadNovelPolishContext } from '../platform_impl/NovelStandaloneDefaults.ets';

export interface NovelPolishCreateSheetChapter {
  id: string;
  ordinal: number;
  title: string;
  discarded: boolean;
}

@Component
export struct NovelPolishCreateSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop visible: boolean = false;
  @Prop singleChapter: boolean = false;
  @Prop chapters: NovelPolishCreateSheetChapter[] = [];
  @Prop modelReady: boolean = false;
  @Prop modelDisabledReason: string = '请先配置可用的写作与审稿模型。';
  @Prop conflictReason: string = '';
  @Prop errorMessage: string = '';
  @Prop busy: boolean = false;
  // 父页面传入底部避让高度；sheet 不假设设备安全区尺寸。
  @Prop bottomPadding: number = 0;

  onStart: (startOrdinal: number, endOrdinal: number, context: PolishContextOptions, ordinals: number[]) => void = (
    _startOrdinal: number, _endOrdinal: number, _context: PolishContextOptions, _ordinals: number[],
  ): void => {};
  onClose: () => void = (): void => {};

  @State private selectedOrdinals: number[] = [];
  @State private preferencesError: string = '';
  @State private preferencesLoaded: boolean = false;
  private mounted: boolean = false;
  @State private includePlot: boolean = true;
  @State private includeForeshadows: boolean = true;
  @State private includeCharacters: boolean = true;
  @State private includeDecisions: boolean = true;

  aboutToAppear(): void {
    this.mounted = true;
    this.selectedOrdinals = this.activeChapters().map((chapter: NovelPolishCreateSheetChapter): number => chapter.ordinal);
    this.loadContextDefaults();
  }

  aboutToDisappear(): void { this.mounted = false; }

  private async loadContextDefaults(): Promise<void> {
    try {
      const options: PolishContextOptions = await loadNovelPolishContext();
      if (!this.mounted) return;
      this.includePlot = options.includePlot;
      this.includeForeshadows = options.includeForeshadows;
      this.includeCharacters = options.includeCharacters;
      this.includeDecisions = options.includeDecisions;
      this.preferencesLoaded = true;
    } catch (error) {
      if (this.mounted) this.preferencesError = `润色偏好无法读取：${String(error)}`;
    }
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.visible) {
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
              if (this.preferencesError.length > 0) {
                Text(this.preferencesError).fontSize(12).fontColor(ERROR).lineHeight(18).width('100%')
                  .margin({ top: 8, bottom: 8 })
              }
              if (this.errorMessage.length > 0) {
                Text(this.errorMessage).fontSize(12).fontColor(ERROR).lineHeight(18).width('100%')
                  .margin({ top: 8, bottom: 8 })
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start)
          }
          .width('100%').layoutWeight(1).align(Alignment.TopStart)
          this.actionRow()
        }
        .width('100%').constraintSize({ maxHeight: '90%' })
        .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ left: 20, right: 20, top: 18, bottom: 24 + this.bottomPadding })
        .transition(TransitionEffect.translate({ y: 160 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%').alignContent(Alignment.Bottom)
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }

  @Builder
  private header(): void {
    Row() {
      Column() {
        Text(this.singleChapter ? '整章润色' : '创建批量润色')
          .fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK)
        Text(this.singleChapter ? '为本章创建可暂停、可恢复的润色任务。' : '勾选要润色的章节，可跳过任意章节，任务会保存到当前工作区。')
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
      Text(this.singleChapter ? '本次章节' : '章节范围').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Text(this.singleChapter ? '确认本章及上下文后开始；审稿通过才收录正文。' : '仅处理勾选的未废弃章节；未勾选的正文保持原样。')
        .fontSize(11).fontColor(INK3).lineHeight(16).margin({ top: 3, bottom: 8 })
      if (this.activeChapters().length === 0) {
        Text('当前没有可润色的章节。').fontSize(13).fontColor(INK3)
          .width('100%').padding({ top: 12, bottom: 12 })
      } else {
        if (this.singleChapter) {
          Text(`第 ${this.selectedOrdinals[0]} 章 · ${this.chapterTitle(this.activeChapters()[0])}`)
            .fontSize(14).fontColor(INK).lineHeight(21).width('100%')
        } else {
          Row() {
            Text('全选').fontSize(12).fontColor(ACCENT).height(44).layoutWeight(1)
              .onClick((): void => { if (!this.busy) this.selectAll(); })
            Text('清空').fontSize(12).fontColor(INK3).height(44).constraintSize({ minWidth: 44 })
              .onClick((): void => { if (!this.busy) this.selectedOrdinals = []; })
          }.width('100%')
          this.chapterPicker(this.activeChapters())
        }
        Text(this.rangeSummary()).fontSize(11).fontColor(INK3).lineHeight(16)
          .width('100%').margin({ top: 8 })
      }
    }
    .width('100%').padding({ top: 2, bottom: 14 })
  }

  @Builder
  private chapterPicker(
    chapters: NovelPolishCreateSheetChapter[],
  ): void {
    Scroll() {
      Column() {
        ForEach(chapters, (chapter: NovelPolishCreateSheetChapter): void => {
          Row() {
            Text(`第 ${chapter.ordinal} 章`).fontSize(12)
              .fontColor(this.selectedOrdinals.includes(chapter.ordinal) ? ACCENT : INK3).width(58)
            Text(this.chapterTitle(chapter)).fontSize(13)
              .fontColor(this.selectedOrdinals.includes(chapter.ordinal) ? ACCENT : INK)
              .layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (this.selectedOrdinals.includes(chapter.ordinal)) {
              Text('已选').fontSize(11).fontColor(ACCENT).margin({ left: 8 })
            }
          }
          .width('100%').constraintSize({ minHeight: 44 }).padding({ left: 12, right: 12, top: 8, bottom: 8 })
          .backgroundColor(this.selectedOrdinals.includes(chapter.ordinal) ? ACCENT_LIGHT : SURFACE2)
          .border({ width: 1, color: this.selectedOrdinals.includes(chapter.ordinal) ? ACCENT : LINE }).borderRadius(10)
          .margin({ bottom: 6 }).onClick((): void => { this.toggleChapter(chapter.ordinal); })
          .accessibilityText(`第 ${chapter.ordinal} 章${this.chapterTitle(chapter)}，${this.selectedOrdinals.includes(chapter.ordinal) ? '已选' : '未选'}`)
        }, (chapter: NovelPolishCreateSheetChapter): string => `${chapter.id}:${chapter.ordinal}:${chapter.title}:${this.selectedOrdinals.includes(chapter.ordinal)}`)
      }
      .width('100%')
    }
    .width('100%').constraintSize({ maxHeight: '24%' }).align(Alignment.TopStart)
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
        .selectedColor(ACCENT).enabled(!this.busy && this.preferencesLoaded)
        .onChange((next: boolean): void => { this.setContextOption(key, next); })
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
      Text('审稿通过后会一起保存版本、正文与进度；可在版本记录中恢复原稿。')
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

  private selectAll(): void {
    this.selectedOrdinals = this.activeChapters().map((chapter: NovelPolishCreateSheetChapter): number => chapter.ordinal);
  }

  private toggleChapter(ordinal: number): void {
    if (this.busy) return;
    this.selectedOrdinals = this.selectedOrdinals.includes(ordinal)
      ? this.selectedOrdinals.filter((value: number): boolean => value !== ordinal)
      : this.selectedOrdinals.concat([ordinal]);
  }

  private orderedSelection(): number[] {
    return this.selectedOrdinals.slice().sort((left: number, right: number): number => left - right);
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
    return !this.busy && this.modelReady && this.preferencesLoaded && this.preferencesError.length === 0 && this.conflictReason.trim().length === 0 &&
      this.selectedOrdinals.length > 0;
  }

  private start(): void {
    if (!this.canStart()) return;
    const ordinals: number[] = this.orderedSelection();
    this.onStart(ordinals[0], ordinals[ordinals.length - 1], this.contextOptions(), ordinals);
  }

  private chapterTitle(chapter: NovelPolishCreateSheetChapter): string {
    return chapter.title.trim().length > 0 ? chapter.title : '未命名章节';
  }

  private rangeSummary(): string {
    const ordinals: number[] = this.orderedSelection();
    if (ordinals.length === 0) return '请至少勾选一章。';
    return `将冻结第 ${ordinals.join('、')} 章，共 ${ordinals.length} 章。`;
  }
}

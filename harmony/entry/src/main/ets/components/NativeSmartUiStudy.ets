import type {
  SmartUiNode, SmartUiScalar, SmartUiStateValue, SmartUiStudyItem, SmartUiStudyOption,
  StudyProgress,
} from '@amber/chat-domain';
import {
  chooseStudyOption, emptyStudyProgress, gradedItemCount, hasGradedItems,
  nextStudy, previousStudy, restartStudy, revealStudyAnswer,
  serializeStudyProgress, shouldAdoptStudyProgress, studyProgressFrom,
} from '@amber/chat-domain';
import { ACCENT, ERROR, INK, INK3, SURFACE2, SUCCESS_FG, accentSoftBg } from '../design/tokens.ets';
import { FONT_SANS, FONT_MONO } from '../design/fonts.ets';
import { smartUiString } from './NativeSmartUiValues.ets';

const EMPTY_NODE: SmartUiNode = { id: 'empty', parent: null, type: 'column' };

@Component
export struct NativeSmartUiStudy {
  @Prop @Watch('onValuesChanged') node: SmartUiNode = EMPTY_NODE;
  @Prop @Watch('onValuesChanged') values: SmartUiStateValue[] = [];
  @Prop fontSize: number = 16;
  @Prop fontFamily: string = FONT_SANS;
  @Prop ink: string = INK;
  @Prop secondaryInk: string = INK3;
  @Prop accent: string = ACCENT;
  @Prop surface: string = SURFACE2;
  @State private progress: StudyProgress = emptyStudyProgress(0);
  private lastPersisted: string = '';
  onStateChange: (stateId: string, value: SmartUiScalar) => void = (_id: string, _value: SmartUiScalar): void => {};

  aboutToAppear(): void { this.restore(); }
  private onValuesChanged(): void { this.restore(); }

  private items(): SmartUiStudyItem[] { return this.node.component?.study ?? []; }
  private item(): SmartUiStudyItem | null { return this.items()[this.progress.i] ?? null; }
  private isQuiz(): boolean { return (this.item()?.options?.length ?? 0) > 0; }
  private hasQuiz(): boolean { return hasGradedItems(this.items()); }
  private graded(): number { return gradedItemCount(this.items()); }
  private displayScore(): number { return Math.min(this.progress.score, this.graded()); }
  private textSize(base: number): number { return Math.max(10, base * this.fontSize / 16); }

  private restore(): void {
    const raw: string = smartUiString(this.values, this.node.state);
    if (!shouldAdoptStudyProgress(raw, this.lastPersisted, this.items().length, this.progress.r.length)) return;
    this.lastPersisted = raw;
    this.progress = studyProgressFrom(raw, this.items().length);
  }

  private persist(): void {
    const stateId: string = this.node.state ?? '';
    if (stateId.length === 0) return;
    const raw: string = serializeStudyProgress(this.progress);
    this.lastPersisted = raw;
    this.onStateChange(stateId, raw);
  }

  private apply(next: StudyProgress): void {
    if (next === this.progress) return;
    this.progress = next;
    this.persist();
  }

  private optionState(option: SmartUiStudyOption): string {
    if (!this.progress.answered) return this.surface;
    if (option.id === this.item()?.correctOptionId) return SUCCESS_FG;
    return this.progress.selected === option.id ? ERROR : this.surface;
  }

  private optionStatus(option: SmartUiStudyOption): string {
    if (!this.progress.answered) return '未选择';
    if (option.id === this.item()?.correctOptionId) return '正确答案';
    return this.progress.selected === option.id ? '你的选择，错误' : '未选择';
  }

  private choose(option: SmartUiStudyOption): void {
    if (this.progress.answered) return;
    this.apply(chooseStudyOption(this.progress, this.item(), option.id));
  }

  private next(): void { this.apply(nextStudy(this.progress, this.items().length)); }
  private previous(): void { this.apply(previousStudy(this.progress)); }
  private restart(): void { this.apply(restartStudy(this.items().length)); }

  @Builder
  private Header(): void {
    Row() {
      Blank();
      Text(`${Math.min(this.progress.i + 1, this.items().length)} / ${this.items().length}`)
        .fontSize(this.textSize(12)).fontFamily(FONT_MONO).fontColor(this.secondaryInk);
      if (this.hasQuiz()) {
        Text(`得分 ${this.displayScore()} / ${this.graded()}`).fontSize(this.textSize(12)).fontFamily(FONT_MONO)
          .fontColor(this.accent).margin({ left: 10 });
      }
    }.width('100%');
  }

  @Builder
  private OptionButton(option: SmartUiStudyOption): void {
    Row() {
      Text(option.label).fontSize(this.textSize(14)).fontFamily(this.fontFamily)
        .fontColor(this.ink).layoutWeight(1).lineHeight(this.textSize(20));
      if (this.progress.answered && option.id === this.item()?.correctOptionId) {
        Text('✓ 正确').fontSize(this.textSize(11)).fontColor(SUCCESS_FG).margin({ left: 8 });
      } else if (this.progress.answered && option.id === this.progress.selected) {
        Text('✕ 你的选择').fontSize(this.textSize(11)).fontColor(ERROR).margin({ left: 8 });
      }
    }
    .width('100%').constraintSize({ minHeight: 44 }).padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(this.optionState(option)).borderRadius(8)
    .border({ width: this.progress.answered && option.id === this.progress.selected ? 1 : 0, color: this.accent })
    .accessibilityGroup(true).accessibilityText(`${option.label}，${this.optionStatus(option)}`)
    .onClick((): void => { this.choose(option); });
  }

  @Builder
  private Answer(): void {
    if (this.item() !== null) {
      Text(this.item()?.answer ?? '').width('100%').fontSize(this.textSize(15)).fontFamily(this.fontFamily)
        .fontColor(this.ink).lineHeight(this.textSize(21)).padding(12).backgroundColor(this.surface).borderRadius(8);
      if ((this.item()?.detail ?? '').length > 0) {
        Text(this.item()?.detail ?? '').width('100%').fontSize(this.textSize(12)).fontFamily(this.fontFamily)
          .fontColor(this.secondaryInk).lineHeight(this.textSize(18));
      }
    }
  }

  @Builder
  private Footer(): void {
    Row({ space: 8 }) {
      if (this.progress.i > 0) {
        Text('上一题').fontSize(this.textSize(13)).fontColor(this.secondaryInk).layoutWeight(1)
          .constraintSize({ minHeight: 44 }).textAlign(TextAlign.Center).backgroundColor(this.surface).borderRadius(8)
          .accessibilityText('上一题')
          .onClick((): void => { this.previous(); });
      }
      Text(this.progress.i >= this.items().length - 1 ? '查看结果' : '下一题')
        .fontSize(this.textSize(13)).fontColor(this.accent).layoutWeight(1)
        .constraintSize({ minHeight: 44 }).textAlign(TextAlign.Center)
        .backgroundColor(accentSoftBg(this.accent)).borderRadius(8)
        .accessibilityText(this.progress.i >= this.items().length - 1 ? '查看结果' : '下一题')
        .onClick((): void => { this.next(); });
    }.width('100%');
  }

  @Builder
  private Result(): void {
    Column({ space: 8 }) {
      Text(this.hasQuiz() ? `本轮 ${this.graded()} 题，答对 ${this.displayScore()} 题` : '本轮翻卡已看完')
        .width('100%').fontSize(this.textSize(18)).fontFamily(this.fontFamily).fontColor(this.ink);
      Text(!this.hasQuiz() ? '可以重新练习再看一遍。' : this.displayScore() === this.graded()
        ? '全部答对，可以重新练习巩固。' : '错题会在重新练习时再次出现。')
        .width('100%').fontSize(this.textSize(13)).fontColor(this.secondaryInk).lineHeight(this.textSize(19));
      Text('重新练习').fontSize(this.textSize(13)).fontColor(this.accent).width('100%').constraintSize({ minHeight: 44 })
        .textAlign(TextAlign.Center).backgroundColor(accentSoftBg(this.accent)).borderRadius(8)
        .accessibilityText('重新练习')
        .onClick((): void => { this.restart(); });
    }.width('100%');
  }

  build(): void {
    Column({ space: 10 }) {
      this.Header();
      if (this.items().length === 0) {
        Text('学习内容未完整生成').fontSize(this.textSize(13)).fontColor(this.secondaryInk);
      } else if (this.progress.finished) {
        this.Result();
      } else if (this.item() !== null) {
        Text(this.item()?.prompt ?? '').width('100%').fontSize(this.textSize(16)).fontFamily(this.fontFamily)
          .fontColor(this.ink).fontWeight(FontWeight.Medium).lineHeight(this.textSize(23));
        if (this.isQuiz()) {
          ForEach(this.item()?.options ?? [], (option: SmartUiStudyOption): void => {
            this.OptionButton(option);
          }, (option: SmartUiStudyOption): string => `${this.node.id}:${this.progress.i}:${option.id}`);
          if (this.progress.answered) this.Answer();
        } else {
          if (!this.progress.answered) {
            Text('显示答案').fontSize(this.textSize(13)).fontColor(this.accent).width('100%').constraintSize({ minHeight: 44 })
              .textAlign(TextAlign.Center).backgroundColor(accentSoftBg(this.accent)).borderRadius(8)
              .accessibilityText('显示答案')
              .onClick((): void => { this.apply(revealStudyAnswer(this.progress)); });
          } else {
            this.Answer();
          }
        }
        this.Footer();
      }
    }.width('100%').alignItems(HorizontalAlign.Start);
  }
}

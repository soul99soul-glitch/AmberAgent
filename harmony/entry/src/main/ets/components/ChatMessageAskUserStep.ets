// ChatMessageAskUserStep — ask_user 问题表单组件(阶段 C, audit 1.8)
//
// Android 基准: ChatMessageAskUserStep.kt(461 行)— 多问题表单+提交+已答展示
// Harmony 后端: ask_user_questions.ts(D-105 解析/载荷/提交判定/已答展示全复刻)
// 从 ChatPage.ets MessageRow.build() 内联抽出(line 393-507)
//
// 功能:
//   - 标题:单题→首题文案;多题→'询问 N 个问题'
//   - pending:选项 chips(single/multi)+ 文本框(text)+ 清空/提交
//   - answered:'A' 标记+答案文本

import type { UIMessagePartTool } from '@amber/chat-domain';
import {
  AskUserQuestion, parseAskUserQuestions, parseAskedAnswers,
  askAnswerDisplayText, isAskUserSubmittable, hasAnyAskAnswer,
} from '@amber/chat-domain';
import { INK, INK2, INK3, ACCENT, ACCENT_LIGHT, SURFACE, SURFACE2, LINE } from '../design/tokens.ets';

// Record<string, string> — ArkTS 允许 Record 类型
type AnswerMap = Record<string, string>;
type MultiAnswerMap = Record<string, string[]>;

@Component
export struct ChatMessageAskUserStep {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop part: UIMessagePartTool;
  @Prop answers: AnswerMap;
  @Prop multiAnswers: MultiAnswerMap;
  @Prop interactive: boolean = true;
  onSubmit: (answers: AnswerMap, multiAnswers: MultiAnswerMap) => void = (): void => {};
  onAnswer: (questionId: string, value: string) => void = (): void => {};
  onToggleMulti: (questionId: string, option: string) => void = (): void => {};
  onClear: () => void = (): void => {};

  // 每次构建重新解析(与原始 ChatPage 每帧 askQuestionsOf 一致,避免 @Prop 变化后 questions 过时)
  private getQuestions(): AskUserQuestion[] {
    return parseAskUserQuestions(this.part.input);
  }

  build(): void {
    Column() {
      Text(this.heading())
        .fontSize(12).fontColor(ACCENT).fontWeight(FontWeight.Medium)
        .width('100%').margin({ top: 4 })

      ForEach(this.getQuestions(), (q: AskUserQuestion, qi: number) => {
        Column() {
          if (qi > 0) {
            Divider().color(LINE).strokeWidth(1).margin({ top: 6, bottom: 2 })
          }
          // F17:单问题时 heading 已渲染该问题,正文不再重复同一文本
          if (!this.headingShowsQuestion(qi)) {
            Text(q.question)
              .fontSize(12).fontColor(INK)
              .width('100%').margin({ top: qi > 0 ? 0 : 4 })
          }

          if (this.part.approvalState.type === 'pending') {
            // 选项 chips
            if (q.options.length > 0) {
              Flex({ wrap: FlexWrap.Wrap }) {
                ForEach(q.options, (opt: string) => {
                  if (this.interactive) {
                    Row() { this.OptionChipContent(q, opt); }
                      .constraintSize({ minHeight: 44 })
                      .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                      .margin({ right: 8, top: 6 })
                      .borderRadius(999)
                      .backgroundColor(this.isOptionSelected(q, opt) ? ACCENT : SURFACE2)
                      .border({
                        width: this.isOptionSelected(q, opt) ? 0 : 1,
                        color: LINE,
                      })
                      .onClick((): void => {
                        if (q.selectionType === 'multi') {
                          this.onToggleMulti(q.id, opt);
                        } else {
                          this.onAnswer(q.id, opt);
                        }
                      })
                  } else {
                    Row() { this.OptionChipContent(q, opt); }
                      .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                      .margin({ right: 8, top: 6 })
                      .borderRadius(999)
                      .backgroundColor(this.isOptionSelected(q, opt) ? ACCENT : SURFACE2)
                      .border({
                        width: this.isOptionSelected(q, opt) ? 0 : 1,
                        color: LINE,
                      })
                  }
                }, (opt: string, oi: number): string => `opt-${qi}-${oi}`)
              }
              .width('100%')
            }
            // text 类型 → 自由文本框
            if (this.interactive && q.selectionType !== 'single' && q.selectionType !== 'multi') {
              TextArea({ placeholder: '在此输入回答…', text: this.answerAt(q.id) })
                .width('100%').height(64).fontSize(12).fontColor(INK)
                .backgroundColor(SURFACE).borderRadius(10)
                .margin({ top: 6 })
                .onChange((v: string): void => {
                  this.onAnswer(q.id, v);
                })
            }
          } else if (this.part.approvalState.type === 'answered') {
            // 已应答 → 'A' 标记(等宽 accent) + 答案文本(顶对齐)
            Row() {
              Text('A')
                .fontSize(10)
                .fontColor(ACCENT)
                .fontFamily('JetBrainsMono')
                .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                .backgroundColor(ACCENT_LIGHT)
                .borderRadius(5)
              Text(this.answeredText(q.id))
                .fontSize(12)
                .fontColor(INK)
                .layoutWeight(1)
                .margin({ left: 8 })
            }
            .width('100%')
            .alignItems(VerticalAlign.Top)
            .padding({ left: 10, right: 10, top: 8, bottom: 8 })
            .margin({ top: 4 })
            .backgroundColor(SURFACE2)
            .borderRadius(10)
            .border({ width: 1, color: LINE })
          }
        }
        .width('100%').alignItems(HorizontalAlign.Start)
      }, (q: AskUserQuestion, qi: number): string => `${qi}:${q.id}`)

      // pending → 操作行:清空 + 提交
      if (this.part.approvalState.type === 'pending' && this.interactive) {
        Row() {
          Blank()
          if (hasAnyAskAnswer(this.answers, this.multiAnswers)) {
            Text('清空')
              .fontSize(11).fontColor(INK3)
              .constraintSize({ minWidth: 44, minHeight: 44 })
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .onClick((): void => { this.onClear(); })
          }
          Text('提交')
            .fontSize(12).fontWeight(FontWeight.Medium)
            .constraintSize({ minHeight: 44 })
            .fontColor(this.submittable() ? '#FFFFFF' : INK3)
            .padding({ left: 16, right: 16, top: 6, bottom: 6 })
            .margin({ left: 8 })
            .borderRadius(999)
            .backgroundColor(this.submittable() ? ACCENT : LINE)
            .onClick((): void => {
              if (this.submittable()) {
                this.onSubmit(this.answers, this.multiAnswers);
              }
            })
        }
        .width('100%').margin({ top: 8 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }

  @Builder
  private OptionChipContent(q: AskUserQuestion, opt: string): void {
    if (this.isOptionSelected(q, opt)) {
      Text('✓').fontSize(11).fontColor('#FFFFFF').margin({ right: 4 })
    }
    Text(opt)
      .fontSize(11)
      .fontColor(this.isOptionSelected(q, opt) ? '#FFFFFF' : INK2)
      .fontWeight(this.isOptionSelected(q, opt) ? FontWeight.Medium : FontWeight.Normal)
  }

  private heading(): string {
    const qs: AskUserQuestion[] = this.getQuestions();
    if (qs.length === 0) return '...';
    if (qs.length === 1) return qs[0].question;
    return `询问 ${qs.length} 个问题`;
  }

  // F17:多问题 heading 只显示计数,故正文需逐个渲染;单问题 heading 即正文,跳过
  private headingShowsQuestion(qi: number): boolean {
    return this.getQuestions().length === 1 && qi === 0;
  }

  private isOptionSelected(q: AskUserQuestion, opt: string): boolean {
    if (q.selectionType === 'multi') {
      return this.multiAt(q.id).indexOf(opt) >= 0;
    }
    return this.answerAt(q.id) === opt;
  }

  private answerAt(id: string): string {
    return this.answers[id] ?? '';
  }

  private multiAt(id: string): string[] {
    return this.multiAnswers[id] ?? [];
  }

  private answeredText(id: string): string {
    // 与原始 ChatPage:274-277 一致 — parseAskedAnswers(fallback) + 3 参数
    if (this.part.approvalState.type !== 'answered') return '';
    const fallback: string = this.part.approvalState.answer;
    return askAnswerDisplayText(parseAskedAnswers(fallback), id, fallback);
  }

  private submittable(): boolean {
    return isAskUserSubmittable(this.getQuestions(), this.answers, this.multiAnswers);
  }
}

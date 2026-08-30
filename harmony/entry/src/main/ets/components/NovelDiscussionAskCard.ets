// NovelDiscussionAskCard — 将小说讨论中的结构化 ask 块渲染为可提交的问答表单。
import { ChatMessageAskUserStep } from './ChatMessageAskUserStep.ets';
import type { UIMessagePartTool, AskUserQuestion } from '@amber/chat-domain';
import { buildAskUserAnswerPayload, parseAskUserQuestions } from '@amber/chat-domain';

type AnswerMap = Record<string, string>;
type MultiAnswerMap = Record<string, string[]>;

@Component
export struct NovelDiscussionAskCard {
  // input 必须是 ask_user 同构的 {"questions":[...]} JSON，来自 parseDiscussionAsk。
  @Prop input: string = '';
  @Prop askId: string = '';
  onSubmit: (payload: string) => void = (_payload: string): void => {};

  @State private answers: AnswerMap = {};
  @State private multiAnswers: MultiAnswerMap = {};

  build(): void {
    ChatMessageAskUserStep({
      part: this.asToolPart(),
      answers: this.answers,
      multiAnswers: this.multiAnswers,
      onSubmit: (): void => { this.submit(); },
      onAnswer: (questionId: string, value: string): void => { this.answer(questionId, value); },
      onToggleMulti: (questionId: string, option: string): void => { this.toggleMulti(questionId, option); },
      onClear: (): void => {
        this.answers = {};
        this.multiAnswers = {};
      },
    })
  }

  private asToolPart(): UIMessagePartTool {
    return {
      type: 'tool',
      toolCallId: this.askId,
      toolName: 'ask_user',
      input: this.input,
      output: [],
      approvalState: { type: 'pending' },
      metadata: null,
    };
  }

  private questions(): AskUserQuestion[] {
    return parseAskUserQuestions(this.input);
  }

  private answer(questionId: string, value: string): void {
    const next: AnswerMap = {};
    const keys: string[] = Object.keys(this.answers);
    for (const key of keys) next[key] = this.answers[key];
    next[questionId] = value;
    this.answers = next;
  }

  private toggleMulti(questionId: string, option: string): void {
    const old: string[] = this.multiAnswers[questionId] ?? [];
    const nextValues: string[] = old.indexOf(option) >= 0
      ? old.filter((item: string): boolean => item !== option)
      : [...old, option];
    const next: MultiAnswerMap = {};
    const keys: string[] = Object.keys(this.multiAnswers);
    for (const key of keys) next[key] = this.multiAnswers[key].slice();
    next[questionId] = nextValues;
    this.multiAnswers = next;
  }

  private submit(): void {
    this.onSubmit(buildAskUserAnswerPayload(this.questions(), this.answers, this.multiAnswers));
  }
}

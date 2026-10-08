import type { CouncilApprovalRequest, CouncilToolSnapshot, CouncilToolVerdict, CouncilSource } from '@amber/deepread-domain';
import type { UIMessage, UIMessagePart, UIMessagePartTool, AskUserQuestion } from '@amber/chat-domain';
import { ASK_USER_TOOL_NAME, parseAskUserQuestions, buildAskUserAnswerPayload } from '@amber/chat-domain';
import { common } from '@kit.AbilityKit';
import { ACCENT, LABEL, META, SURFACE } from '../design/tokens.ets';
import { ChatMessageTools } from './ChatMessageTools.ets';
import { ChatMessageAskUserStep } from './ChatMessageAskUserStep.ets';
import { ChatMessageToolPreviewSheet } from './ChatMessageToolPreviewSheet.ets';
import { recipeAskUserPart } from './RecipeApprovalSummary.ets';
import { pluginAskUserPart } from './PluginApprovalSummary.ets';
import { SSHUIColors } from './SSHUIColors.ets';

@Component
struct CouncilToolCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop part: UIMessagePartTool;
  @Prop request: CouncilApprovalRequest | null = null;
  @Prop active: boolean = false;
  onVerdict: (request: CouncilApprovalRequest, verdict: CouncilToolVerdict) => void = (): void => {};
  @State answers: Record<string, string> = {};
  @State multiAnswers: Record<string, string[]> = {};
  @State previewOpen: boolean = false;

  private askPart(): UIMessagePartTool | null {
    return recipeAskUserPart(this.part) ?? pluginAskUserPart(this.part)
      ?? (this.part.toolName === ASK_USER_TOOL_NAME ? this.part : null);
  }
  private interactive(): boolean {
    return this.active && this.request !== null && this.part.approvalState.type === 'pending';
  }
  private submit(kind: 'approved' | 'denied' | 'answered', answer: string | null = null): void {
    if (!this.interactive() || this.request === null) return;
    this.onVerdict(this.request, { kind: kind, reason: kind === 'denied' ? '用户拒绝' : '', answer: answer });
  }
  private setAnswer(id: string, value: string): void {
    const next: Record<string, string> = {};
    for (const key of Object.keys(this.answers)) next[key] = this.answers[key];
    next[id] = value;
    this.answers = next;
  }
  private toggleMulti(id: string, value: string): void {
    const next: Record<string, string[]> = {};
    for (const key of Object.keys(this.multiAnswers)) next[key] = this.multiAnswers[key].slice();
    const values: string[] = next[id] ?? [];
    const index: number = values.indexOf(value);
    if (index < 0) values.push(value); else values.splice(index, 1);
    next[id] = values;
    this.multiAnswers = next;
  }
  private answer(): void {
    const part: UIMessagePartTool | null = this.askPart();
    if (part === null) return;
    const questions: AskUserQuestion[] = parseAskUserQuestions(part.input);
    this.submit('answered', buildAskUserAnswerPayload(questions, this.answers, this.multiAnswers));
  }
  @Builder
  private Preview(): void {
    ChatMessageToolPreviewSheet({ part: this.part, jevSubjectHash: this.request?.subjectHash ?? '',
      onDismiss: (): void => { this.previewOpen = false; } })
  }

  build(): void {
    Column() {
      ChatMessageTools({ part: this.part, jevSubjectHash: this.request?.subjectHash ?? '',
        canApprove: this.interactive() && this.askPart() === null, canDeny: this.interactive(),
        deniedLabel: this.part.approvalState.type === 'denied' ? this.part.approvalState.reason : '',
        streaming: this.active, onApprove: (): void => { this.submit('approved'); },
        onDeny: (): void => { this.submit('denied'); },
        onOpenPreview: (): void => { this.previewOpen = true; } })
      if (this.askPart() !== null) {
        ChatMessageAskUserStep({ part: this.askPart()!, answers: this.answers, multiAnswers: this.multiAnswers,
          interactive: this.interactive(), onAnswer: (id: string, value: string): void => { this.setAnswer(id, value); },
          onToggleMulti: (id: string, value: string): void => { this.toggleMulti(id, value); },
          onClear: (): void => { this.answers = {}; this.multiAnswers = {}; },
          onSubmit: (): void => { this.answer(); } })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').margin({ bottom: 10 })
      .bindSheet(this.previewOpen, this.Preview(), {
        height: SheetSize.LARGE, showClose: false, backgroundColor: SURFACE, onDisappear: (): void => { this.previewOpen = false; },
      })
  }
}

@Component
export struct CouncilSeatTools {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop snapshot: CouncilToolSnapshot;
  @Prop active: boolean = false;
  onVerdict: (request: CouncilApprovalRequest, verdict: CouncilToolVerdict) => void = (): void => {};
  @StorageProp('accentColor') accentColor: string = ACCENT;
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  private refreshColors(): void { this.colors = new SSHUIColors(); }

  private requestFor(messageId: string, partIndex: number, part: UIMessagePartTool): CouncilApprovalRequest | null {
    return this.snapshot.pending.find((request: CouncilApprovalRequest): boolean =>
      request.messageId === messageId && request.partIndex === partIndex && request.toolCallId === part.toolCallId) ?? null;
  }
  private cardKey(messageId: string, partIndex: number, part: UIMessagePart): string {
    const hash: string = part.type === 'tool'
      ? this.requestFor(messageId, partIndex, part as UIMessagePartTool)?.subjectHash ?? '' : '';
    return `${messageId}-${partIndex}-${hash}`;
  }
  private openSource(url: string): void {
    if (!/^https?:\/\//i.test(url)) return;
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    context.startAbility({ action: 'ohos.want.action.viewData', entities: ['entity.system.browsable'], uri: url })
      .catch((_error: Error): void => {});
  }

  build(): void {
    Column() {
      ForEach(this.snapshot.messages, (message: UIMessage): void => {
        ForEach(message.parts, (part: UIMessagePart, index: number): void => {
          if (part.type === 'tool') {
            CouncilToolCard({ part: part as UIMessagePartTool, active: this.active,
              request: this.requestFor(message.id, index, part as UIMessagePartTool), onVerdict: this.onVerdict })
          }
        }, (part: UIMessagePart, index: number): string => this.cardKey(message.id, index, part))
      }, (message: UIMessage): string => message.id)
      if (this.snapshot.sources.length > 0) {
        Text('实际工具来源').fontSize(META).fontColor(this.colors.muted).width('100%').margin({ top: 4, bottom: 6 })
        ForEach(this.snapshot.sources, (source: CouncilSource, index: number): void => {
          Column({ space: 3 }) {
            Text(source.title.length > 0 ? source.title : source.url).fontSize(META)
              .fontColor(this.accentColor).width('100%').wordBreak(WordBreak.BREAK_ALL)
            Text(source.url).fontSize(LABEL).fontColor(this.colors.muted).width('100%').wordBreak(WordBreak.BREAK_ALL)
            if (source.service.length > 0) Text(source.service).fontSize(LABEL).fontColor(this.colors.muted).width('100%')
          }.width('100%').constraintSize({ minHeight: 44 }).padding({ top: 6, bottom: 8 })
            .onClick((): void => { this.openSource(source.url); })
        }, (source: CouncilSource): string => JSON.stringify([source.url, source.service]))
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').alignItems(HorizontalAlign.Start)
  }
}

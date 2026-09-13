// AgentMessageRow — chat/novel 共用的完整 agent 消息行。
// host 通过静态 capabilities 决定可交互入口，并用具名 hostFooter slot 注入宿主尾部。

import { INK, INK3, META, SURFACE2, USER_BG, USER_INK } from '../design/tokens.ets';
import type {
  AskUserQuestion, MessagePartBlock, ReasoningCardState, ThinkingStep, UIMessage,
  UIMessagePartDocument, UIMessagePartReasoning, UIMessagePartTool,
} from '@amber/chat-domain';
import {
  ASK_USER_TOOL_NAME, buildAskUserAnswerPayload, groupMessageParts,
  onReasoningExpandedChange, parseAskUserQuestions, reasoningCardStateExpanded,
  streamToolIndex,
} from '@amber/chat-domain';
import type { ToolApprovalPartLocator } from '@amber/chat-domain';
import { MarkdownText } from './MarkdownText.ets';
import { ChatMessageReasoning } from './ChatMessageReasoning.ets';
import { ChatMessageTools } from './ChatMessageTools.ets';
import { ChatMessageAskUserStep } from './ChatMessageAskUserStep.ets';
import { ChatMessageAvatar } from './ChatMessageAvatar.ets';
import { ChatMessageSubAgentStep } from './ChatMessageSubAgentStep.ets';
import { ChatMessageCouncilStep } from './ChatMessageCouncilStep.ets';
import { ChatMessageActionFooter, resolveActionFooterMode } from './ChatMessageActionFooter.ets';
import { ContentAttachmentChip, urlBasename } from './ContentAttachmentChip.ets';
import { MiniAppChatCard } from './MiniAppChatCard.ets';

export interface AgentMessageRowHostCapabilities {
  copy: boolean;
  fork: boolean;
  branchSwitch: boolean;
  edit: boolean;
  regenerate: boolean;
  favorite: boolean;
  openActions: boolean;
  approveTool: boolean;
  denyTool: boolean;
  answerTool: boolean;
  openToolPreview: boolean;
  openSubAgentRun: boolean;
  runMiniApp: boolean;
  modifyMiniApp: boolean;
  openMiniAppList: boolean;
  // F44:文档附件是否可打开(宿主提供真实 open 路径时置 true)
  openDocument: boolean;
}

export const NO_AGENT_MESSAGE_ROW_CAPABILITIES: AgentMessageRowHostCapabilities = {
  copy: false,
  fork: false,
  branchSwitch: false,
  edit: false,
  regenerate: false,
  favorite: false,
  openActions: false,
  approveTool: false,
  denyTool: false,
  answerTool: false,
  openToolPreview: false,
  openSubAgentRun: false,
  runMiniApp: false,
  modifyMiniApp: false,
  openMiniAppList: false,
  openDocument: false,
};

@Component
export struct AgentMessageRow {
  @StorageProp('darkMode') darkMode: boolean = false;
  @Prop message: UIMessage;
  @Prop capabilities: AgentMessageRowHostCapabilities = NO_AGENT_MESSAGE_ROW_CAPABILITIES;
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  @Prop isLastAssistant: boolean = false;
  // D-053:本行是否属于活动流式尾部(reasoning loading 判定;
  //   Android messageLoading 语义,流式尾/regen 预览传 true)
  @Prop @Watch('onStreamingChange') streaming: boolean = false;
  // generationActive 与网络文本增量分离：reasoning/tool loading 跟随前者，
  // Markdown reveal 只在真实 live text delta 时启用，避免 one-shot 假流式。
  @Prop textDeltasLive: boolean = true;
  @Prop autoCloseThinking: boolean = true;
  // F16:显示设置消费(默认值 == 当前渲染行为,不改变既有外观)
  @Prop showAssistantBubble: boolean = false;
  @Prop showModelName: boolean = true;
  @Prop showModelIcon: boolean = false;
  @Prop fontSizeRatio: number = 1.0;
  @Prop codeBlockAutoCollapse: boolean = false;
  @Prop showLineNumbers: boolean = false;
  // 同一 message id 可同时出现在已提交行与工具续跑预览中；预览使用独立键命名空间。
  @Prop streamKeyTag: string = '';
  onSwitch: (idx: number) => void = (_idx: number): void => {};
  onRegenerate: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onOpenActions: () => void = (): void => {};
  onFork: () => void = (): void => {};
  // D-057/R08:工具审批回调(toolCallId + 可选精确 locator;旧调用只传 id)
  onApproveTool: (toolCallId: string, locator?: ToolApprovalPartLocator) => void =
    (_id: string, _loc?: ToolApprovalPartLocator): void => {};
  onDenyTool: (toolCallId: string, locator?: ToolApprovalPartLocator) => void =
    (_id: string, _loc?: ToolApprovalPartLocator): void => {};
  // D-105/R08:ask_user 应答回调(ChatMessageAskUserStep.kt onToolAnswer)
  onAnswerTool: (toolCallId: string, answer: string, locator?: ToolApprovalPartLocator) => void =
    (_id: string, _a: string, _loc?: ToolApprovalPartLocator): void => {};
  onOpenSubAgentRun: (runId: string, tools: UIMessagePartTool[]) => void =
    (_id: string, _t: UIMessagePartTool[]): void => {};
  onCopy: () => void = (): void => {};
  onFavorite: (text: string) => void = (_t: string): void => {};
  onOpenToolPreview: (part: UIMessagePartTool) => void = (_p: UIMessagePartTool): void => {};
  // F44:文档附件打开(宿主未提供路径时 capabilities.openDocument=false → 纯展示)
  onOpenDocument: (part: UIMessagePartDocument) => void = (_p: UIMessagePartDocument): void => {};
  // P3:可见文本增长回调(逐字 reveal / 终态 drain 推进时触发;冒泡到 ChatPage
  //   复用既有 followStreamingUpdate,不新建第二套滚动状态机)
  onTextGrow: () => void = (): void => {};
  // MiniApp:卡片回调冒泡(onRun 进 Runner;onModify 组装修订 prompt 回填输入框;
  //   onOpenList 进列表页 — 均在 host 层实现,AgentMessageRow 仅透传)
  onRunMiniApp: (appId: string) => void = (_appId: string): void => {};
  onModifyMiniApp: (prompt: string) => void = (_prompt: string): void => {};
  onOpenMiniAppList: () => void = (): void => {};
  @BuilderParam hostHeader: () => void;
  @BuilderParam hostFooter: () => void;
  // D-053:reasoning 卡片态(part 下标 → 用户显式选择;未选走默认迁移)
  @State reasoningStates: Record<string, string> = {};
  @State thinkingExpandedStates: Record<string, boolean> = {};
  // D-105:ask_user 草稿态(题 id → 文本/单选值;:102;题 id → 多选集;:104)
  @State askAnswers: Record<string, string> = {};
  @State askMultiAnswers: Record<string, string[]> = {};
  // D-053:本会话内见过流式(Android sawStreamingMessage:123 —
  //   流式结束后保持 Preview 不自动折叠)
  @State sawStream: boolean = false;
  // 1:1 原型 fadeRise .26s ease-out:行入场 opacity 0→1 + translateY 4→0
  @State enterOpacity: number = 0;
  @State enterY: number = 4;

  aboutToAppear(): void {
    if (this.streaming) this.sawStream = true;
  }

  private onStreamingChange(_propName: string): void {
    if (this.streaming) this.sawStream = true;
  }

  // loading = messageLoading && finishedAt == null(ChatMessageReasoning.kt:107)
  private reasoningLoading(p: UIMessagePartReasoning): boolean {
    return this.streaming && p.finishedAt === null;
  }

  // 纯文本:text part 拼接 + 去 **/`(收藏等用途)
  private plainText(): string {
    let s: string = '';
    for (const p of this.message.parts) {
      if (p.type === 'text') s += p.text;
    }
    return s.replace(/\*\*([^*]+)\*\*/g, '$1').replace(/`([^`]+)`/g, '$1');
  }

  // :952-960 已拒绝展示:'已拒绝' + 非空白 reason → ': reason'(红色;
  //   chat_message_tool_denied zh 逐字,冒号为 ASCII ': ')
  private deniedLabel(t: UIMessagePartTool): string {
    if (t.approvalState.type !== 'denied') return '';
    const reason: string = t.approvalState.reason;
    return reason.trim().length > 0 ? `已拒绝: ${reason}` : '已拒绝';
  }

  private askQuestionsOf(t: UIMessagePartTool): AskUserQuestion[] {
    return parseAskUserQuestions(t.input);
  }

  // R08:partIndex 必须是原 message.parts 索引。先 indexOf 原数组;
  //   group 复制体不在原数组时,仅用 metadata stream_tool_index 作为**匹配键**
  //   在原 parts 中唯一查找,取该候选的真实 parts 下标;不唯一则拒歧义不传。
  //   (streamToolIndex 是流内工具序号,可能与 parts 下标不同,不能直接当 partIndex)
  private locatorFor(t: UIMessagePartTool): ToolApprovalPartLocator | undefined {
    const index: number = this.message.parts.indexOf(t);
    if (index >= 0) return { messageId: this.message.id, partIndex: index };
    const sidx: number | null = streamToolIndex(t);
    if (sidx === null) return undefined;
    let found: number = -1;
    let count: number = 0;
    for (let i: number = 0; i < this.message.parts.length; i++) {
      const p = this.message.parts[i];
      if (p.type === 'tool' && streamToolIndex(p as UIMessagePartTool) === sidx) {
        if (found < 0) found = i;
        count++;
      }
    }
    if (count !== 1) return undefined;
    return { messageId: this.message.id, partIndex: found };
  }

  private setAskAnswer(qid: string, value: string): void {
    const copy: Record<string, string> = {};
    const keys: string[] = Object.keys(this.askAnswers);
    for (const k of keys) copy[k] = this.askAnswers[k];
    copy[qid] = value;
    this.askAnswers = copy;
  }

  private toggleAskMulti(qid: string, option: string): void {
    const copy: Record<string, string[]> = {};
    const keys: string[] = Object.keys(this.askMultiAnswers);
    for (const k of keys) copy[k] = this.askMultiAnswers[k].slice();
    const cur: string[] = copy[qid] !== undefined ? copy[qid] : [];
    const idx: number = cur.indexOf(option);
    if (idx >= 0) cur.splice(idx, 1);
    else cur.push(option);
    copy[qid] = cur;
    this.askMultiAnswers = copy;
  }

  private clearAskDrafts(): void {
    this.askAnswers = {};
    this.askMultiAnswers = {};
  }

  private submitAskAnswers(t: UIMessagePartTool): void {
    const qs: AskUserQuestion[] = this.askQuestionsOf(t);
    this.onAnswerTool(t.toolCallId,
      buildAskUserAnswerPayload(qs, this.askAnswers, this.askMultiAnswers),
      this.locatorFor(t));
  }

  private reasoningStateAt(idx: string): ReasoningCardState {
    const s: string | undefined = this.reasoningStates[idx];
    if (s === 'collapsed' || s === 'preview' || s === 'expanded') return s as ReasoningCardState;
    return (this.streaming || this.sawStream) ? 'preview' : 'collapsed';
  }

  private toggleReasoning(idx: string, loading: boolean): void {
    const cur: ReasoningCardState = this.reasoningStateAt(idx);
    const next: ReasoningCardState =
      onReasoningExpandedChange(!reasoningCardStateExpanded(cur), loading);
    const copy: Record<string, string> = {};
    const keys: string[] = Object.keys(this.reasoningStates);
    for (const k of keys) copy[k] = this.reasoningStates[k];
    copy[idx] = next;
    this.reasoningStates = copy;
  }

  private autoCollapseReasoning(idx: string): void {
    if (this.streaming || this.reasoningStateAt(idx) !== 'expanded') return;
    const copy: Record<string, string> = {};
    const keys: string[] = Object.keys(this.reasoningStates);
    for (const k of keys) copy[k] = this.reasoningStates[k];
    copy[idx] = 'preview';
    this.reasoningStates = copy;
  }

  private getBlocks(): MessagePartBlock[] {
    return groupMessageParts(this.message.parts);
  }

  // F16:字号比例应用到消息正文(默认 1.0 → 15,与既有渲染一致)
  private bodyFontSize(): number {
    const scaled: number = 15 * this.fontSizeRatio;
    return Math.round(scaled * 10) / 10;
  }

  private isLastTextBlock(blocks: MessagePartBlock[], index: number): boolean {
    for (let i: number = blocks.length - 1; i >= 0; i--) {
      const block: MessagePartBlock = blocks[i];
      if (block.kind === 'content' && block.part.type === 'text') return i === index;
    }
    return false;
  }

  private thinkingExpanded(bi: number): boolean {
    const v: boolean | undefined = this.thinkingExpandedStates[`${bi}`];
    return v !== undefined ? v : true;
  }

  private toggleThinkingExpanded(bi: number): void {
    if (this.hasPendingToolInBlock(bi)) return;
    const key: string = `${bi}`;
    const cur: boolean = this.thinkingExpanded(bi);
    const copy: Record<string, boolean> = {};
    const keys: string[] = Object.keys(this.thinkingExpandedStates);
    for (const k of keys) copy[k] = this.thinkingExpandedStates[k];
    copy[key] = !cur;
    this.thinkingExpandedStates = copy;
  }

  private hasPendingToolInBlock(bi: number): boolean {
    const blocks: MessagePartBlock[] = this.getBlocks();
    if (bi >= blocks.length) return false;
    const block: MessagePartBlock = blocks[bi];
    if (block.kind !== 'thinking') return false;
    const steps: ThinkingStep[] = (block as Record<string, Object>)['steps'] as ThinkingStep[];
    for (const step of steps) {
      if (step.kind === 'tool' && step.tool.approvalState.type === 'pending') return true;
    }
    return false;
  }

  private thinkingSummary(steps: ThinkingStep[]): string {
    let reasoningCount: number = 0;
    let toolCount: number = 0;
    for (const step of steps) {
      if (step.kind === 'reasoning') reasoningCount++;
      else if (step.kind === 'tool') toolCount++;
    }
    if (reasoningCount > 0 && toolCount > 0) return `思考 ${reasoningCount} 段 · 工具 ${toolCount} 次`;
    if (reasoningCount > 0) return `思考 ${reasoningCount} 段`;
    if (toolCount > 0) return `工具 ${toolCount} 次`;
    return '思考过程';
  }

  @Builder
  private MessageBlocks(): void {
    ForEach(this.getBlocks(), (block: MessagePartBlock, bi: number): void => {
      if (block.kind === 'thinking') {
        Row() {
          Text(this.thinkingExpanded(bi) ? '▾' : '▸').fontSize(11).fontColor(INK3)
          Text(this.thinkingSummary(block.steps))
            .fontSize(META).fontColor(INK3)
            .margin({ left: 6 })
            .layoutWeight(1)
        }
        .width('100%')
        .padding({ left: 8, right: 8, top: 4, bottom: 4 })
        .backgroundColor(SURFACE2)
        .borderRadius(6)
        .margin({ bottom: 2 })
        .onClick((): void => { this.toggleThinkingExpanded(bi); })

        if (this.thinkingExpanded(bi)) {
          ForEach(block.steps, (step: ThinkingStep, si: number): void => {
            if (step.kind === 'reasoning') {
              ChatMessageReasoning({
                part: step.reasoning,
                state: this.reasoningStateAt(`${bi}-${si}`),
                loading: this.reasoningLoading(step.reasoning),
                autoCloseThinking: this.autoCloseThinking,
                onToggle: (): void => {
                  this.toggleReasoning(`${bi}-${si}`, this.reasoningLoading(step.reasoning));
                },
                onAutoCollapse: (): void => { this.autoCollapseReasoning(`${bi}-${si}`); },
              })
            } else if (step.kind === 'tool') {
              if (step.tool.toolName === ASK_USER_TOOL_NAME) {
                ChatMessageAskUserStep({
                  part: step.tool,
                  answers: this.askAnswers,
                  multiAnswers: this.askMultiAnswers,
                  interactive: this.capabilities.answerTool,
                  onSubmit: (_a: Record<string, string>, _m: Record<string, string[]>): void => {
                    this.submitAskAnswers(step.tool);
                  },
                  onAnswer: (qid: string, value: string): void => { this.setAskAnswer(qid, value); },
                  onToggleMulti: (qid: string, opt: string): void => { this.toggleAskMulti(qid, opt); },
                  onClear: (): void => { this.clearAskDrafts(); },
                })
              } else {
                ChatMessageTools({
                  part: step.tool,
                  canApprove: this.capabilities.approveTool,
                  canDeny: this.capabilities.denyTool,
                  canOpenPreview: this.capabilities.openToolPreview,
                  streaming: this.streaming,
                  onApprove: (toolCallId: string): void => {
                    this.onApproveTool(toolCallId, this.locatorFor(step.tool));
                  },
                  onDeny: (toolCallId: string): void => {
                    this.onDenyTool(toolCallId, this.locatorFor(step.tool));
                  },
                  onOpenPreview: (): void => { this.onOpenToolPreview(step.tool); },
                  deniedLabel: step.tool.approvalState.type === 'denied' ? this.deniedLabel(step.tool) : '',
                })
              }
            }
          }, (step: ThinkingStep, si: number): string => `${bi}-${si}-${step.kind}`)
        }
      } else if (block.kind === 'content') {
        if (block.part.type === 'text') {
          if (this.message.role === 'user') {
            Column() {
              MarkdownText({
                content: block.part.text,
                fontSize: this.bodyFontSize(),
                fontColor: USER_INK,
                fillWidth: false,
                codeBlockAutoCollapse: this.codeBlockAutoCollapse,
                showLineNumbers: this.showLineNumbers,
                streamKey: `${this.streamKeyTag}${this.message.id}#${block.index}`,
              })
            }
            .alignItems(HorizontalAlign.Start)
            .padding({ left: 15, right: 15, top: 11, bottom: 11 })
            .backgroundColor(USER_BG)
            .borderRadius({ topLeft: 16, topRight: 16, bottomRight: 5, bottomLeft: 16 })
            .constraintSize({ maxWidth: '82%' })
            .margin({ bottom: 4 })
          } else {
            Column() {
              MarkdownText({
                content: block.part.text,
                fontSize: this.bodyFontSize(),
                fontColor: INK,
                streaming: this.streaming && this.textDeltasLive &&
                  this.isLastTextBlock(this.getBlocks(), bi),
                codeBlockAutoCollapse: this.codeBlockAutoCollapse,
                showLineNumbers: this.showLineNumbers,
                streamKey: `${this.streamKeyTag}${this.message.id}#${block.index}`,
                onVisibleGrow: (): void => { this.onTextGrow(); },
              })
            }
            .alignItems(HorizontalAlign.Start)
            .padding(this.showAssistantBubble ? { left: 12, right: 12, top: 8, bottom: 8 } : 0)
            .backgroundColor(this.showAssistantBubble ? SURFACE2 : Color.Transparent)
            .borderRadius(this.showAssistantBubble ? 10 : 0)
            .margin({ bottom: 4 })
          }
        } else if (block.part.type === 'image') {
          Image(block.part.url)
            .width(160)
            .objectFit(ImageFit.Cover)
            .borderRadius(8)
            .margin({ bottom: 4 })
        } else if (block.part.type === 'video') {
          ContentAttachmentChip({
            icon: '🎬',
            label: urlBasename(block.part.url).length > 0 ? urlBasename(block.part.url) : '视频',
            sublabel: block.part.mime,
          })
        } else if (block.part.type === 'audio') {
          ContentAttachmentChip({
            icon: '🎵',
            label: block.part.fileName.length > 0 ? block.part.fileName :
              (urlBasename(block.part.url).length > 0 ? urlBasename(block.part.url) : '音频'),
            sublabel: block.part.mime,
          })
        } else if (block.part.type === 'document') {
          ContentAttachmentChip({
            icon: '📄',
            label: block.part.fileName.length > 0 ? block.part.fileName :
              (urlBasename(block.part.url).length > 0 ? urlBasename(block.part.url) : '文档'),
            sublabel: block.part.mime,
            openable: this.capabilities.openDocument,
            onOpen: (): void => { this.onOpenDocument(block.part as UIMessagePartDocument); },
          })
        } else if (block.part.type === 'mini_app') {
          MiniAppChatCard({
            part: block.part,
            canRun: this.capabilities.runMiniApp,
            canModify: this.capabilities.modifyMiniApp,
            canOpenList: this.capabilities.openMiniAppList,
            onRun: (appId: string): void => { this.onRunMiniApp(appId); },
            onOpenList: (): void => { this.onOpenMiniAppList(); },
            onModify: (prompt: string): boolean => {
              this.onModifyMiniApp(prompt);
              return true;
            },
          })
        }
      } else if (block.kind === 'subagent') {
        ChatMessageSubAgentStep({
          tools: block.step.tools,
          runId: block.step.runId,
          canOpenRun: this.capabilities.openSubAgentRun,
          onOpenRun: (rid: string): void => { this.onOpenSubAgentRun(rid, block.step.tools); },
        })
      } else if (block.kind === 'council') {
        ChatMessageCouncilStep({
          tools: block.step.tools,
          runId: block.step.runId,
          canOpenRun: this.capabilities.openToolPreview,
          onOpenRun: (_rid: string): void => {
            if (block.step.tools.length > 0) this.onOpenToolPreview(block.step.tools[0]);
          },
        })
      }
    }, (block: MessagePartBlock, bi: number): string => `block-${bi}-${block.kind}`)
  }

  @Builder
  private MessageContent(): void {
    if (this.message.role === 'user' && this.capabilities.edit && this.capabilities.openActions) {
      Column() { this.MessageBlocks(); }
        .gesture(GestureGroup(
          GestureMode.Exclusive,
          LongPressGesture().onAction((): void => { this.onOpenActions(); }),
          TapGesture().onAction((): void => { this.onEdit(); }),
        ))
    } else if (this.message.role === 'user' && this.capabilities.edit) {
      Column() { this.MessageBlocks(); }
        .gesture(TapGesture().onAction((): void => { this.onEdit(); }))
    } else if (this.message.role === 'user' && this.capabilities.openActions) {
      Column() { this.MessageBlocks(); }
        .gesture(LongPressGesture().onAction((): void => { this.onOpenActions(); }))
    } else {
      Column() { this.MessageBlocks(); }
    }
  }

  build(): void {
    Column() {
      ChatMessageAvatar({
        message: this.message,
        streaming: this.streaming,
        showModelName: this.showModelName,
        showModelIcon: this.showModelIcon,
      })
      this.hostHeader()
      this.MessageContent()
      ChatMessageActionFooter({
        annotations: this.message.annotations,
        mode: resolveActionFooterMode(
          this.message.role, this.isLastAssistant, this.streaming, this.message.parts.length > 0,
        ),
        isUser: this.message.role === 'user',
        isLastAssistant: this.isLastAssistant,
        branchCount: this.branchCount,
        selectIndex: this.selectIndex,
        canCopy: this.capabilities.copy,
        canFork: this.capabilities.fork,
        canBranchSwitch: this.capabilities.branchSwitch,
        canEdit: this.capabilities.edit,
        canRegenerate: this.capabilities.regenerate,
        canFavorite: this.capabilities.favorite,
        onCopy: (): void => { this.onCopy(); },
        onFork: (): void => { this.onFork(); },
        onEdit: (): void => { this.onEdit(); },
        onRegenerate: (): void => { this.onRegenerate(); },
        onFavorite: (): void => { this.onFavorite(this.plainText()); },
        onSwitch: (idx: number): void => { this.onSwitch(idx); },
      })
      this.hostFooter()
    }
    .width('100%')
    .alignItems(this.message.role === 'user' ? HorizontalAlign.End : HorizontalAlign.Start)
    .padding({ top: 8, bottom: 8 })
    .opacity(this.enterOpacity)
    .translate({ y: this.enterY })
    .onAppear((): void => {
      animateTo({ duration: 260, curve: Curve.EaseOut }, (): void => {
        this.enterOpacity = 1;
        this.enterY = 0;
      });
    })
  }
}

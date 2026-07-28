// ChatPage — 聊天页:消息流(text/reasoning/tool)+ 输入发送 + 流式更新
// 回路: runChatTurn(chat-domain) + OpenAIChatApi(RcpHttpClient)+ RDB 仓库(D-021)
// D-024:assistant 分支 — ◀ n/m ▶ 切换(selectMessageBranch) + 末条「重新生成」
//   (runRegenerateAt);D-041:regenerate 流式预览落地(目标节点行下渲染流式
//   快照,visual transforms 与 send 路径一致),MVP spinner 裁剪项清零
// D-038:图片输入 — PhotoViewPicker 选图 → readImageAsDataUrl 物化为 data: URL
//   → UIMessagePart[] 发送(文本可空,图片不可空);渲染 Image(p.url)
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import type {
  Conversation, UIMessage, UIMessagePart, UIMessagePartText, Assistant, TransformerContext,
  PendingUserMessage, DequeueNextResult, UIMessagePartReasoning, ReasoningCardState,
  CompactPolicy, CompactResult, PreparedContext,
  UIMessagePartTool, ToolLoopOptions, IdleToolBlockerResult, ChatToolDefinition,
  TextGenerationParams, ChatStreamProvider, ToolApprovalVerdict, ChatTurnDeps,
  ToolRegistry, DeepReadOpenEvent,
} from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId,
  createTemplateTransformer, thinkTagTransformer, regexOutputTransformer,
  asChatStreamProvider, seedConversationWithPresets,
  nodeCurrentMessage, nodeRole, selectMessageBranch, deleteMessage, editMessage,
  forkConversation, patchConversation,
  runAutoTitle, runSuggestion, makeUserMessage, toText, makeTextGenerationParams,
  applyVisualTransformersStreamingTail,
  makePendingUserMessage, enqueuePendingUserMessage, dequeueNextPendingUserMessage,
  cancelPendingUserMessage, preparePendingMessageForDispatch, pendingPreviewText,
  convertSteerToFollowup, runAppendUserMessage,
  persistPendingQueue, loadPendingQueue, recordPendingQueueEvent,
  toDisplayReasoningText, extractThinkingTitle, reasoningDurationMs, formatThoughtSeconds,
  reasoningCardStateExpanded, onReasoningExpandedChange,
  makeCompactPolicy, prepareContext, invalidateCompacts,
  copyValidCompactsToConversation, ContextCompactionFailedError,
  conversationWithMessagesAsNodes,
  runChatTurnWithTools, runToolLoopContinuation, runRegenerateAtWithTools,
  createTimeTool, makeUIMessage,
  createAskUserTool, createConversationContextTools, createConversationHistoryTools,
  createConversationQueueTools, createToolRegistry,
  createToolSearchTool, createRegistryIntrospectionTools,
  createRunPlanUpdateTool, createClipboardTool, createDeepReadOpenTool,
  parseDeepReadSlashCommand,
  applyToolApprovalToConversation, conversationHasPendingTools,
  conversationHasPendingOrUnexecutedTools, resolveIdleToolBlocker,
} from '@amber/chat-domain';
import {
  getChatRepository, getChatAssistants, resolveChatRuntime, describeChatModel, ChatRuntime,
  getChatKvStore, getCompactStore, getSessionGrantStore,
} from '../di/AppContainer.ets';
import { readImageAsDataUrl } from '../platform_impl/EntryImageEncoder.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { createEntryClipboardPort, sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';

const HILOG_DOMAIN: number = 0x0001;
const HILOG_TAG: string = 'DeepRead.ChatPage';

const partKey = (p: UIMessagePart, i: number): string => `${p.type}-${i}`;

// 节点视图模型:分支切换/重新生成需要节点级信息
interface MsgRow {
  nodeId: string;
  message: UIMessage;
  branchCount: number;
  selectIndex: number;
  isAssistant: boolean;
  isLastAssistant: boolean;
}

const buildRows = (conv: Conversation): MsgRow[] => {
  let lastAssistantIdx: number = -1;
  for (let i = conv.messageNodes.length - 1; i >= 0; i--) {
    if (nodeRole(conv.messageNodes[i]) === 'assistant') {
      lastAssistantIdx = i;
      break;
    }
  }
  return conv.messageNodes.map((n, i: number): MsgRow => ({
    nodeId: n.id,
    message: nodeCurrentMessage(n),
    branchCount: n.messages.length,
    selectIndex: n.selectIndex,
    isAssistant: nodeRole(n) === 'assistant',
    isLastAssistant: i === lastAssistantIdx,
  }));
};

@Component
struct MessageRow {
  @Prop message: UIMessage;
  @Prop branchCount: number = 1;
  @Prop selectIndex: number = 0;
  @Prop isAssistant: boolean = false;
  @Prop isLastAssistant: boolean = false;
  // D-053:本行是否属于活动流式尾部(reasoning loading 判定;
  //   Android messageLoading 语义,流式尾/regen 预览传 true)
  @Prop @Watch('onStreamingChange') streaming: boolean = false;
  onSwitch: (idx: number) => void = (_idx: number): void => {};
  onRegenerate: () => void = (): void => {};
  onDelete: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onFork: () => void = (): void => {};
  // D-057:工具审批回调(toolCallId 定位,ChatService.handleToolApproval)
  onApproveTool: (toolCallId: string) => void = (_id: string): void => {};
  onDenyTool: (toolCallId: string) => void = (_id: string): void => {};
  // D-053:reasoning 卡片态(part 下标 → 用户显式选择;未选走默认迁移)
  @State reasoningStates: Record<number, string> = {};
  // D-053:本会话内见过流式(Android sawStreamingMessage:123 —
  //   流式结束后保持 Preview 不自动折叠)
  @State sawStream: boolean = false;

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

  // D-057:工具状态文案(approvalStateSimpleName 显示面子集)
  private toolStateLabel(t: UIMessagePartTool): string {
    if (t.output.length > 0) return '已执行';
    if (t.approvalState.type === 'pending') return '待审批';
    if (t.approvalState.type === 'approved') return '已批准';
    if (t.approvalState.type === 'denied') return '已拒绝';
    if (t.approvalState.type === 'answered') return '已应答';
    return '';
  }

  // 初始 Collapsed(:88);流式开始 → Preview(:125-126,showThinkingContent
  //   设置项鸿蒙未接,Android 默认 true);流式过 → 保持 Preview(:129-133)
  private reasoningStateAt(idx: number): ReasoningCardState {
    const s: string | undefined = this.reasoningStates[idx];
    if (s === 'collapsed' || s === 'preview' || s === 'expanded') {
      return s as ReasoningCardState;
    }
    return (this.streaming || this.sawStream) ? 'preview' : 'collapsed';
  }

  private toggleReasoning(idx: number, loading: boolean): void {
    const cur: ReasoningCardState = this.reasoningStateAt(idx);
    const next: ReasoningCardState =
      onReasoningExpandedChange(!reasoningCardStateExpanded(cur), loading);
    const copy: Record<number, string> = {};
    const keys: string[] = Object.keys(this.reasoningStates);
    for (const k of keys) {
      copy[Number(k)] = this.reasoningStates[Number(k)];
    }
    copy[idx] = next;
    this.reasoningStates = copy;
  }

  // 头部文案(:296-300/:325-349):流式且有独占加粗行 → title;
  //   否则 showReasoningDuration(finishedAt≠null 或 loading)→ 秒数;否则 深度思考
  //   裁剪:budgetLabel(需 assistant 注入)= P1;流式 title 右侧 duration extra = P1
  private reasoningHeader(p: UIMessagePartReasoning): string {
    const loading: boolean = this.reasoningLoading(p);
    if (loading) {
      const title: string | null = extractThinkingTitle(p.reasoning);
      if (title !== null) return title;
    }
    if (p.finishedAt !== null || loading) {
      const ms: number = reasoningDurationMs(p.createdAt, p.finishedAt, loading, Date.now());
      return formatThoughtSeconds(ms / 1000);
    }
    return '深度思考';
  }

  build() {
    Column() {
      Text(this.message.role === 'user' ? '我' : '助手')
        .fontSize(11).fontColor(INK3).margin({ bottom: 4 })
      ForEach(this.message.parts, (p: UIMessagePart, i: number): void => {
        if (p.type === 'text') {
          Text(p.text)
            .fontSize(15).fontColor(INK)
            .padding(10)
            .backgroundColor(this.message.role === 'user' ? SURFACE2 : SURFACE)
            .borderRadius(8)
            .margin({ bottom: 4 })
        } else if (p.type === 'image') {
          // D-038:data: / http(s): / file:// 直渲(ArkUI Image 支持 data: URL;
          //   真机验证见 Spike C 登记项)
          Image(p.url)
            .width(160)
            .objectFit(ImageFit.Cover)
            .borderRadius(8)
            .margin({ bottom: 4 })
        } else if (p.type === 'reasoning') {
          // D-053:reasoning 卡片(ChatMessageReasoning.kt 全文语义)—
          //   头(脑图标+title/秒数/深度思考+展开箭头)可点切换;
          //   collapsed 仅头;preview 1600 字尾窗(5 行截断,Android 100dp
          //   滚动窗裁剪登记 Spike C);expanded 18000 字尾窗全文;
          //   2dp 左竖线+14dp 缩进(:244-256)
          Column() {
            Row() {
              Text('🧠').fontSize(12)
              Text(this.reasoningHeader(p as UIMessagePartReasoning))
                .fontSize(12).fontColor(ACCENT)
                .margin({ left: 6 })
                .layoutWeight(1)
              Text(this.reasoningStateAt(i) === 'expanded' ? '▾' : '▸')
                .fontSize(11).fontColor(INK3)
            }
            .width('100%')
            .padding({ left: 2, top: 6, bottom: 6 })
            .onClick((): void => {
              this.toggleReasoning(i, this.reasoningLoading(p as UIMessagePartReasoning));
            })
            if (this.reasoningStateAt(i) === 'preview') {
              Text(toDisplayReasoningText((p as UIMessagePartReasoning).reasoning,
                this.reasoningLoading(p as UIMessagePartReasoning), false))
                .fontSize(13).fontColor(INK2)
                .maxLines(5)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
                .padding({ left: 14, top: 2, bottom: 2 })
                .border({ width: { left: 2 }, color: { left: '#D8D2C6' } })
            } else if (this.reasoningStateAt(i) === 'expanded') {
              Text(toDisplayReasoningText((p as UIMessagePartReasoning).reasoning,
                this.reasoningLoading(p as UIMessagePartReasoning), true))
                .fontSize(13).fontColor(INK2)
                .width('100%')
                .padding({ left: 14, top: 2, bottom: 2 })
                .border({ width: { left: 2 }, color: { left: '#D8D2C6' } })
            }
          }
          .width('100%')
          .margin({ bottom: 4 })
        } else if (p.type === 'tool') {
          // D-057:工具卡片 MVP(Android ChatMessageTool 子集)— 名称 + 状态;
          //   pending → 批准/拒绝按钮(ChatPage 工具审批门);
          //   已执行 → 首条 output 文本 3 行预览(完整卡片视觉 = P1)
          Column() {
            Row() {
              Text(`工具:${(p as UIMessagePartTool).toolName}`)
                .fontSize(11).fontColor(ACCENT)
                .layoutWeight(1)
              Text(this.toolStateLabel(p as UIMessagePartTool))
                .fontSize(10).fontColor(INK3)
            }
            .width('100%')
            if ((p as UIMessagePartTool).approvalState.type === 'pending') {
              Row() {
                Text('批准')
                  .fontSize(12).fontColor(ACCENT)
                  .padding({ left: 12, right: 12, top: 4, bottom: 4 })
                  .onClick((): void => {
                    this.onApproveTool((p as UIMessagePartTool).toolCallId);
                  })
                Text('拒绝')
                  .fontSize(12).fontColor(INK2)
                  .padding({ left: 12, right: 12, top: 4, bottom: 4 })
                  .margin({ left: 8 })
                  .onClick((): void => {
                    this.onDenyTool((p as UIMessagePartTool).toolCallId);
                  })
              }
              .margin({ top: 4 })
            }
            if ((p as UIMessagePartTool).output.length > 0
              && (p as UIMessagePartTool).output[0].type === 'text') {
              Text(((p as UIMessagePartTool).output[0] as UIMessagePartText).text)
                .fontSize(11).fontColor(INK2)
                .maxLines(3)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%')
                .margin({ top: 4 })
            }
          }
          .width('100%')
          .padding({ left: 8, right: 8, top: 6, bottom: 6 })
          .backgroundColor(SURFACE2)
          .borderRadius(6)
          .margin({ bottom: 4 })
        }
      }, partKey)
      if ((this.isAssistant && (this.branchCount > 1 || this.isLastAssistant))
        || this.message.role === 'user') {
        Row() {
          if (this.branchCount > 1) {
            Text('◀').fontSize(11).fontColor(this.selectIndex > 0 ? ACCENT : INK3)
              .onClick((): void => {
                if (this.selectIndex > 0) this.onSwitch(this.selectIndex - 1);
              })
            Text(`${this.selectIndex + 1}/${this.branchCount}`)
              .fontSize(10).fontColor(INK3).margin({ left: 6, right: 6 })
            Text('▶').fontSize(11)
              .fontColor(this.selectIndex < this.branchCount - 1 ? ACCENT : INK3)
              .margin({ right: 12 })
              .onClick((): void => {
                if (this.selectIndex < this.branchCount - 1) this.onSwitch(this.selectIndex + 1);
              })
          }
          Text('分叉').fontSize(11).fontColor(INK3)
            .margin({ right: 12 })
            .onClick((): void => this.onFork())
          if (this.message.role === 'user') {
            Text('编辑').fontSize(11).fontColor(INK3)
              .margin({ right: 12 })
              .onClick((): void => this.onEdit())
          }
          if (this.isLastAssistant || this.message.role === 'user') {
            Text('重新生成').fontSize(11).fontColor(ACCENT)
              .onClick((): void => this.onRegenerate())
          }
        }
        .margin({ top: 2 })
      }
    }
    .width('100%')
    .alignItems(this.message.role === 'user' ? HorizontalAlign.End : HorizontalAlign.Start)
    .padding({ left: 16, right: 16, top: 8, bottom: 8 })
    .gesture(
      LongPressGesture()
        .onAction((): void => this.onDelete())
    )
  }
}

// holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制 + arkts-no-obj-literals-as-types)
interface RuntimeHolder {
  rt: ChatRuntime | null;
}

// D-048:排队消息 chip 视图模型
interface PendingChipRow {
  id: string;
  preview: string;
}

@Entry
@Component
struct ChatPage {
  @State rows: MsgRow[] = [];
  @State streamingTail: UIMessage[] = [];
  @State suggestions: string[] = [];
  @State inputText: string = '';
  @State sending: boolean = false;
  @State errorMsg: string = '';
  @State title: string = '新对话';
  @State modelLabel: string = '';
  @State editingNodeId: string = '';
  @State editingMessageId: string = '';
  @State editingTitle: boolean = false;
  @State titleInput: string = '';
  // D-038:待发图片(data: URL 物化,发送后清空)
  @State pendingImages: string[] = [];
  // D-041:regenerate 流式预览(目标节点行下渲染;完成后清空)
  @State regenTail: UIMessage | null = null;
  @State regenNodeId: string = '';
  // D-048:发送排队;D-052:KV 持久化(进程死亡恢复,PendingMessageStore 语义)
  @State pendingRows: PendingChipRow[] = [];
  private pendingQueue: PendingUserMessage[] = [];
  // D-050:重试状态文案(strings.xml:386;null = 清除)
  @State retryStatus: string = '';
  private conversation: Conversation | null = null;
  private abortController: EntryAbortController | null = null;
  private scroller: Scroller = new Scroller();

  aboutToAppear(): void {
    // 每次进入(含从选择器返回)刷新模型标签
    describeChatModel().then((label: string): void => { this.modelLabel = label; });
    const params = router.getParams() as Record<string, string>;
    const convId: string = params ? (params['conversationId'] ?? '') : '';
    if (convId.length > 0) {
      getChatRepository().getById(convId).then((conv: Conversation | null): void => {
        if (conv !== null) {
          this.conversation = conv;
          this.title = conv.title.length > 0 ? conv.title : '对话';
          this.rows = buildRows(conv);
          this.suggestions = conv.chatSuggestions;
          // D-052:恢复持久化队列(session 创建时 load,ChatService.kt:334);
          //   解析失败 → hilog + 空队列(Android Log.w + getOrDefault 语义)
          loadPendingQueue(getChatKvStore(), conv.id, undefined, (e: Error): void => {
            hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'load pending queue failed: %{public}s', e.message);
          }).then((msgs: PendingUserMessage[]): void => {
            if (msgs.length === 0) return;
            this.pendingQueue = msgs;
            this.syncPendingRows();
            // launchPendingMessageLoopIfNeeded(ChatService.kt:588-595):
            //   未生成且队列非空 → 恢复派发循环(进程死亡恢复)
            if (!this.sending) this.drainPendingQueue();
          });
        } else {
          this.errorMsg = '会话不存在';
        }
      });
    } else {
      // 新对话:支持 router 参数 assistantId 指定助手(D-028)
      const wantAssistantId: string = params ? (params['assistantId'] ?? '') : '';
      getChatAssistants().then((assistants): void => {
        let picked: Assistant = assistants[0];
        for (const a of assistants) {
          if (a.id === wantAssistantId) {
            picked = a;
            break;
          }
        }
        const fresh = makeConversation(newId(), [], { assistantId: picked.id });
        this.conversation = seedConversationWithPresets(fresh, picked);
        this.rows = buildRows(this.conversation);
        this.suggestions = this.conversation.chatSuggestions;
      });
    }
  }

  private scrollToBottom(): void {
    this.scroller.scrollEdge(Edge.Bottom);
  }

  private switchBranch(nodeId: string, idx: number): void {
    if (this.conversation === null || this.sending) return;
    const updated: Conversation = selectMessageBranch(this.conversation, nodeId, idx);
    this.conversation = updated;
    this.rows = buildRows(updated);
    getChatRepository().save(updated);
    // D-055:分支切换使 compact 失效(ChatService.kt:2152 'message_branch_changed')
    this.invalidateConvCompacts(updated.id, 'message_branch_changed');
  }

  private regenerate(nodeId: string): void {
    if (this.conversation === null || this.sending) return;
    this.sending = true;
    this.errorMsg = '';
    this.abortController = new EntryAbortController();
    const conv: Conversation = this.conversation;
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime(conv.assistantId)
      .then((rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型'));
        }
        // D-055:重生成使 compact 失效(ChatService.kt:1097 'message_regenerated'),
        //   先于生成完成,避免 prepareContext 读到过期摘要
        return this.invalidateConvCompacts(conv.id, 'message_regenerated')
          .then((): Promise<Conversation> => runRegenerateAtWithTools(conv, nodeId, {
          assistant: rt.assistant,
          inputTransformers: [createTemplateTransformer()],
          outputTransformers: [thinkTagTransformer, regexOutputTransformer],
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          // D-055:压缩引擎接管上下文(同 send 路径)
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          abortSignal: this.abortController?.signal,
          // D-050:重试状态(send 路径同语义)
          onRetryStatus: (status: string | null): void => {
            this.retryStatus = status ?? '';
          },
          // D-041:流式预览 — 快照末条即活动流式消息(assistant 路径占位
          //   合并/user 路径尾部追加,均为末条);visual transforms 与 send 一致
          onUpdate: (msgs: UIMessage[]): void => {
            if (msgs.length === 0 || holder.rt === null) return;
            const ctx: TransformerContext = { assistant: holder.rt.assistant };
            const transformed: UIMessage[] = applyVisualTransformersStreamingTail(
              msgs, [thinkTagTransformer, regexOutputTransformer], ctx);
            this.regenNodeId = nodeId;
            this.regenTail = transformed[transformed.length - 1];
          },
        }, this.buildToolLoop(rt, conv)));
      })
      .then((out: Conversation): void => {
        this.conversation = out;
        this.rows = buildRows(out);
        this.suggestions = out.chatSuggestions;
        this.sending = false;
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
        this.scrollToBottom();
        if (holder.rt !== null) {
          this.maybeSuggestions(out, holder.rt);
        }
      })
      .catch((e: Error): void => {
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
      });
  }

  // 自动命名(D-025):title 空白时非阻塞调用(对齐 Android 独立 launch);
  // 失败上浮到 errorMsg(非静默,Android addError 同语义)
  private maybeAutoTitle(conv: Conversation, rt: ChatRuntime): void {
    if (conv.title.trim().length > 0) return;
    runAutoTitle(conv, {
      generateText: (prompt: string): Promise<string> =>
        rt.api.generateText(
          [makeUserMessage(prompt)],
          // reasoning OFF(对齐 Android AiAuxiliaryGenerator);arkts-no-spread 禁用接口展开
          makeTextGenerationParams({
            model: rt.params.model,
            temperature: rt.params.temperature,
            topP: rt.params.topP,
            maxTokens: rt.params.maxTokens,
            tools: rt.params.tools,
            reasoningLevel: 'off',
            customBody: rt.params.customBody,
          }),
        ).then((chunk): string => {
          const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
          return msg !== null ? toText(msg) : '';
        }),
      store: getChatRepository(),
      locale: '简体中文',
    })
      .then((titled: Conversation): void => {
        if (this.conversation !== null && this.conversation.id === titled.id) {
          this.conversation = titled;
          this.title = titled.title.length > 0 ? titled.title : this.title;
        }
      })
      .catch((e: Error): void => {
        this.errorMsg = `自动命名失败:${e.message}`;
      });
  }

  // 会话建议(D-026):每轮生成完成后非阻塞刷新;失败 hilog 记录(非静默,
  // 不上浮 UI — Android 对建议失败也不打扰用户,仅 printStackTrace)
  private maybeSuggestions(conv: Conversation, rt: ChatRuntime): void {
    runSuggestion(conv, {
      generateText: (prompt: string): Promise<string> =>
        rt.api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({
            model: rt.params.model,
            temperature: rt.params.temperature,
            topP: rt.params.topP,
            maxTokens: rt.params.maxTokens,
            tools: rt.params.tools,
            reasoningLevel: 'off',
            customBody: rt.params.customBody,
          }),
        ).then((chunk): string => {
          const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
          return msg !== null ? toText(msg) : '';
        }),
      store: getChatRepository(),
      locale: '简体中文',
    })
      .then((suggested: Conversation): void => {
        if (this.conversation !== null && this.conversation.id === suggested.id) {
          this.conversation = suggested;
          this.suggestions = suggested.chatSuggestions;
        }
      })
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'suggestion failed: %{public}s', e.message);
      });
  }

  // 手动改名(D-034):Android ChatVM.updateTitle 语义(copy title + save)
  private startEditTitle(): void {
    if (this.conversation === null) return;
    this.titleInput = this.conversation.title;
    this.editingTitle = true;
  }

  private saveTitle(): void {
    if (this.conversation === null) return;
    const updated: Conversation = patchConversation(this.conversation, { title: this.titleInput.trim() });
    this.conversation = updated;
    this.title = updated.title.length > 0 ? updated.title : '对话';
    this.editingTitle = false;
    getChatRepository().save(updated);
  }

  // 会话分叉(D-033):复制 0..目标节点为新会话并跳转
  private forkAt(row: MsgRow): void {
    if (this.conversation === null || this.sending) return;
    const sourceConvId: string = this.conversation.id;
    const fork: Conversation = forkConversation(this.conversation, row.message.id);
    // D-055:分叉复制有效 compact(ChatService.kt:2120 copyValidCompactsToConversation);
    //   失败 hilog 非静默,不阻断分叉
    copyValidCompactsToConversation(
      getCompactStore(), sourceConvId, fork, (): string => newId(), (): number => Date.now())
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'fork compact copy failed: %{public}s', e.message);
      });
    getChatRepository().save(fork).then((): void => {
      const params: Record<string, string> = {};
      params['conversationId'] = fork.id;
      router.pushUrl({ url: 'pages/ChatPage', params: params });
    });
  }

  // 编辑消息(D-031):填入输入框进入编辑态;发送时走「编辑并重发」
  private startEditMessage(row: MsgRow): void {
    if (this.conversation === null || this.sending) return;
    this.editingNodeId = row.nodeId;
    this.editingMessageId = row.message.id;
    this.inputText = toText(row.message);
  }

  private cancelEdit(): void {
    this.editingNodeId = '';
    this.editingMessageId = '';
    this.inputText = '';
  }

  // 图片选择(D-038):PhotoViewPicker → 读为 data: URL → 待发预览;
  //   读取失败逐张告警(非静默),Android 同语义(FileEncoder 失败即抛)
  private pickImages(): void {
    if (this.sending) return;
    const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 3;
    const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    picker.select(options).then((result: photoAccessHelper.PhotoSelectResult): void => {
      const uris: string[] = result.photoUris;
      for (const uri of uris) {
        // 同步读取(fileIo):失败逐张告警,不静默
        try {
          const dataUrl: string = readImageAsDataUrl(uri);
          this.pendingImages = [...this.pendingImages, dataUrl];
        } catch (e) {
          const msg: string = (e as Error).message;
          hilog.warn(0x0001, 'ChatPage', `图片读取失败: ${msg}`);
          this.errorMsg = `图片读取失败: ${msg}`;
        }
      }
    }).catch((e: Error): void => {
      // 用户取消选择也会走 reject(cancel)— 仅记录,不算错误
      hilog.info(0x0001, 'ChatPage', `图片选择结束: ${e.message}`);
    });
  }

  private removePendingImage(index: number): void {
    const next: string[] = [...this.pendingImages];
    next.splice(index, 1);
    this.pendingImages = next;
  }

  // 编辑并重发:editMessage(新分支)→ 持久化 → runRegenerateAt(user 节点截断续跑)
  private sendEdit(text: string): void {
    if (this.conversation === null) return;
    const conv: Conversation = this.conversation;
    const nodeId: string = this.editingNodeId;
    const updated: Conversation = editMessage(conv, this.editingMessageId, makeUserMessage(text).parts);
    this.conversation = updated;
    this.rows = buildRows(updated);
    this.editingNodeId = '';
    this.editingMessageId = '';
    // D-055:编辑使 compact 失效(ChatService.kt:2082 'message_edited')
    this.invalidateConvCompacts(updated.id, 'message_edited');
    getChatRepository().save(updated).then((): void => {
      this.regenerate(nodeId);
    });
  }

  // 删除消息(D-029):长按 → 确认弹窗;生成中不提供入口(Android 同语义:
  // ChatVM.showDeleteBlockedWhileGeneratingError)
  private confirmDeleteMessage(row: MsgRow): void {
    if (this.conversation === null || this.sending) return;
    AlertDialog.show({
      title: '删除消息',
      message: '该消息将被删除,且不可恢复。',
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '删除',
        fontColor: '#B43A3A',
        action: (): void => {
          if (this.conversation === null) return;
          const updated: Conversation = deleteMessage(this.conversation, row.message.id, false);
          this.conversation = updated;
          this.rows = buildRows(updated);
          getChatRepository().save(updated);
          // D-055:删除使 compact 失效(ChatService.kt:2171 'message_deleted')
          this.invalidateConvCompacts(updated.id, 'message_deleted');
        },
      },
    });
  }

  // ===== D-048 发送排队(Android ChatService.sendMessage:687-732 / launchPendingMessageLoop:801-867) =====

  private syncPendingRows(): void {
    this.pendingRows = this.pendingQueue.map(
      (m: PendingUserMessage): PendingChipRow => ({ id: m.id, preview: pendingPreviewText(m) }));
  }

  // D-052:每次队列变更 → KV 持久化(Android ConversationSession 通知点
  //   :128-231 + durable persist :712/:1046);失败 hilog 非静默
  private persistQueue(): void {
    if (this.conversation === null) return;
    const convId: string = this.conversation.id;
    persistPendingQueue(getChatKvStore(), convId, this.pendingQueue)
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'persist pending queue failed: %{public}s', e.message);
      });
  }

  // D-052:审计事件(recordEvent:62-87;entry 现有交互子集 enqueue/dequeue/cancel)
  private auditQueueEvent(event: string, messageId: string, detail?: string): void {
    if (this.conversation === null) return;
    const convId: string = this.conversation.id;
    recordPendingQueueEvent(getChatKvStore(), convId, { event, messageId, detail })
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'pending queue audit failed: %{public}s', e.message);
      });
  }

  // ===== D-055 上下文压缩引擎接线(ConversationContextEngine.kt:112-286) =====

  // 失效钩子(ChatService.kt:1097/:2082/:2152/:2171;失败 hilog 非静默,不阻断交互)
  private invalidateConvCompacts(convId: string, reason: string): Promise<void> {
    return invalidateCompacts(getCompactStore(), convId, reason)
      .catch((e: Error): void => {
        hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'compact invalidate failed: %{public}s', e.message);
      });
  }

  // prepareContextMessages 闭包:chat_turn 在 append user 后以基线消息回调;
  //   重建 messageNodes 供压缩规划(message id 保持);policy 默认值
  //   keepRecentTurns=8(PreferencesStore.kt:241 ContextCompactionSetting 默认,
  //   设置面未做前硬编码);precompact 异步 launch(engine:173-186)
  private buildPrepareContext(conv: Conversation, rt: ChatRuntime):
    (messages: UIMessage[]) => Promise<UIMessage[]> {
    const policy: CompactPolicy = makeCompactPolicy({ keepRecentTurns: 8 });
    return (messages: UIMessage[]): Promise<UIMessage[]> => {
      const effConv: Conversation = conversationWithMessagesAsNodes(conv, messages);
      return prepareContext(effConv, policy, rt.modelContextWindowTokens,
        messages, rt.contextMessageSize, {
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getCompactStore(),
          launchPrecompact: (run: () => Promise<CompactResult>): void => {
            run().catch((e: Error): void => {
              hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'precompact failed: %{public}s', e.message);
            });
          },
        }).then((prepared: PreparedContext): UIMessage[] => prepared.messages);
    };
  }

  // 压缩失败统一文案(Android ChatVM 错误映射)
  private compactErrorText(e: Error): string {
    return e instanceof ContextCompactionFailedError
      ? '上下文已满，压缩失败，请配置任务模型'
      : e.message;
  }

  // ===== D-057 工具循环接线(GenerationHandler:161-391 调用面) =====

  // 工具循环选项:D-058 首批内置工具 = get_time_info + ask_user +
  //   conversation_*(4) + session_*(4),D-059 追加 conversation_queue_*(2),
  //   经 ToolRegistry 包装层(ToolRegistry.kt
  //   :31-161 — 派生 needsApproval/display_title 注入/output budget);
  //   autoApprove 默认 false(设置面 = P1,登记);每步 provider 工厂按步内
  //   工具定义重建 params(arkts-no-spread 逐字段,不用 rt.params 展开)
  private buildToolLoop(rt: ChatRuntime, conv: Conversation): ToolLoopOptions {
    // Android conversationProvider = getConversationFlow().value(最新会话态)
    const conversationProvider: () => Promise<Conversation> = (): Promise<Conversation> =>
      Promise.resolve(this.conversation ?? conv);
    const registry: ToolRegistry = createToolRegistry([
      createTimeTool(),
      createAskUserTool(),
      ...createConversationContextTools({
        conversationProvider,
        compactStore: getCompactStore(),
        // 与 buildPrepareContext 同口径(settings.agentRuntime.contextCompaction)
        policy: makeCompactPolicy({ keepRecentTurns: 8 }),
        modelContextWindowTokens: rt.modelContextWindowTokens,
        compactEngineDeps: {
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getCompactStore(),
        },
      }),
      ...createConversationHistoryTools({
        repository: getChatRepository(),
        currentConversationProvider: conversationProvider,
        grantStore: getSessionGrantStore(),
      }),
      // D-059:conversation_queue_* 工具对(ChatService.kt:2457-2544);
      //   变更经既有页面路径(cancelPendingById/clear)— persist+审计与
      //   ChatService:498-511 同语义(changed/count>0 才记录)
      ...createConversationQueueTools({
        pendingProvider: (): Promise<PendingUserMessage[]> => Promise.resolve(this.pendingQueue),
        cancelOne: (messageId: string): Promise<void> => {
          this.cancelPendingById(messageId);
          return Promise.resolve();
        },
        clearAll: (): Promise<void> => {
          const count: number = this.pendingQueue.length;
          if (count > 0) {
            this.pendingQueue = [];
            this.syncPendingRows();
            this.persistQueue();
            this.auditQueueEvent('clear', '', `count=${count}`);
          }
          return Promise.resolve();
        },
      }),
      // D-061:本地工具三件(LocalTools runPlanUpdateTool/clipboardTool/
      //   deepReadOpenTool);deep_read bus 收集器 = router 打开深读页
      //   (Index.startDeepRead:120-127 同路由;页面存活即有收集者)
      createRunPlanUpdateTool(),
      createClipboardTool(createEntryClipboardPort()),
      createDeepReadOpenTool({
        bus: {
          hasCollectors: (): boolean => true,
          emit: (event: DeepReadOpenEvent): void => {
            this.openDeepRead(event);
          },
        },
        sha256Hex: sha256HexUtf8,
      }),
    ]);
    return {
      // createRunTools(ChatService.kt:2326-2329):registry.tools() +
      //   tool_search + 自省对(profile=null — ToolProfileFilter 未移植,登记)
      tools: [
        ...registry.tools(),
        createToolSearchTool(registry),
        ...createRegistryIntrospectionTools(registry),
      ],
      makeProviderForStep: (defs: ChatToolDefinition[]): ChatStreamProvider =>
        asChatStreamProvider(rt.api, (): TextGenerationParams => makeTextGenerationParams({
          model: rt.params.model,
          temperature: rt.params.temperature,
          topP: rt.params.topP,
          maxTokens: rt.params.maxTokens,
          tools: defs,
          reasoningLevel: rt.params.reasoningLevel,
          customBody: rt.params.customBody,
        })),
      consumeSteerMessages: (): Promise<UIMessage[]> => this.drainSteerMessages(),
    };
  }

  // steer 消息步间消费(Android session.dequeueSteerPendingUserMessages,
  //   GenerationHandler:386-390):生成中到达的 STEER 出队 → user 消息注入会话;
  //   出队后 durable persist + 审计(D-052 同规)
  private drainSteerMessages(): Promise<UIMessage[]> {
    const steers: PendingUserMessage[] = this.pendingQueue.filter(
      (m: PendingUserMessage): boolean => m.mode === 'STEER');
    if (steers.length === 0) return Promise.resolve([]);
    this.pendingQueue = this.pendingQueue.filter(
      (m: PendingUserMessage): boolean => m.mode !== 'STEER');
    this.syncPendingRows();
    this.persistQueue();
    for (const s of steers) {
      this.auditQueueEvent('dequeue', s.id, 'steer');
    }
    return Promise.resolve(steers.map(
      (s: PendingUserMessage): UIMessage => makeUIMessage('user', s.parts)));
  }

  // 工具审批(ChatService.kt:1122-1188):改写 approvalState → 持久化 →
  //   无 pending 残留 → 续跑生成(handleMessageComplete)
  private handleToolApproval(toolCallId: string, approved: boolean): void {
    if (this.conversation === null || this.sending) return;
    const verdict: ToolApprovalVerdict = approved
      ? { kind: 'approved' }
      : { kind: 'denied', reason: '' };
    const out: Conversation = applyToolApprovalToConversation(
      this.conversation, toolCallId, verdict);
    this.conversation = out;
    this.rows = buildRows(out);
    getChatRepository().save(out)
      .then((): void => {
        if (!conversationHasPendingTools(out)) {
          this.resumeToolLoop(out);
        }
      })
      .catch((e: Error): void => {
        this.errorMsg = e.message;
      });
  }

  // 审批后续跑:不追加 user 消息,直接进工具循环;流式预览复用 D-041
  //   regenTail 机制(末条 assistant 行下渲染 — 写回的就是末条消息)
  private resumeToolLoop(conv: Conversation): void {
    if (this.sending) return;
    this.sending = true;
    this.errorMsg = '';
    this.abortController = new EntryAbortController();
    const lastNodeId: string = conv.messageNodes.length > 0
      ? conv.messageNodes[conv.messageNodes.length - 1].id
      : '';
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime(conv.assistantId)
      .then((rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型'));
        }
        return runToolLoopContinuation(conv, {
          assistant: rt.assistant,
          inputTransformers: [createTemplateTransformer()],
          outputTransformers: [thinkTagTransformer, regexOutputTransformer],
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          abortSignal: this.abortController?.signal,
          onRetryStatus: (status: string | null): void => {
            this.retryStatus = status ?? '';
          },
          onUpdate: (msgs: UIMessage[]): void => {
            if (msgs.length === 0 || holder.rt === null) return;
            const ctx: TransformerContext = { assistant: holder.rt.assistant };
            const transformed: UIMessage[] = applyVisualTransformersStreamingTail(
              msgs, [thinkTagTransformer, regexOutputTransformer], ctx);
            this.regenNodeId = lastNodeId;
            this.regenTail = transformed[transformed.length - 1];
          },
        }, this.buildToolLoop(rt, conv));
      })
      .then((out: Conversation): void => {
        this.conversation = out;
        this.rows = buildRows(out);
        this.suggestions = out.chatSuggestions;
        this.sending = false;
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
        this.scrollToBottom();
        if (holder.rt !== null) {
          this.maybeAutoTitle(out, holder.rt);
          this.maybeSuggestions(out, holder.rt);
        }
        this.drainPendingQueue();
      })
      .catch((e: Error): void => {
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
        // 审批态/部分写回已持久化,刷新显示
        getChatRepository().getById(conv.id).then((saved: Conversation | null): void => {
          if (saved !== null) {
            this.conversation = saved;
            this.rows = buildRows(saved);
          }
          this.drainPendingQueue();
        });
      });
  }

  // 生成中发送 → 入队;满 20 拒绝(Android 文案逐字)。
  // 模式(D-051,Android ChatInput.kt:259/272 + ChatService.kt:699):
  //   answer=true(点击发送)→ 恒 FOLLOWUP;answer=false(长按)生成中 → STEER
  //   (插 STEER 前缀位,派发时 asFollowup 转换),否则 FOLLOWUP
  private enqueuePendingParts(parts: UIMessagePart[], answer: boolean): void {
    const msg: PendingUserMessage = makePendingUserMessage({
      parts,
      answer,
      mode: !answer && this.sending ? 'STEER' : 'FOLLOWUP',
    });
    const r = enqueuePendingUserMessage(this.pendingQueue, msg);
    if (!r.accepted) {
      this.errorMsg = '消息队列已满，请先等待或取消一些排队消息。';
      return;
    }
    this.pendingQueue = r.messages;
    this.syncPendingRows();
    this.persistQueue();
    // 审计:enqueue(detail = mode 小写,Android mode.name.lowercase())
    this.auditQueueEvent('enqueue', msg.id, msg.mode.toLowerCase());
  }

  private cancelPendingById(id: string): void {
    const r = cancelPendingUserMessage(this.pendingQueue, id);
    if (r.changed) {
      this.pendingQueue = r.messages;
      this.syncPendingRows();
      this.persistQueue();
      this.auditQueueEvent('cancel', id);
    }
  }

  // 生成完成(成功/失败/中断)后出队下一条继续派发(Android 循环逐条 catch 后继续)
  private drainPendingQueue(): void {
    if (this.sending || this.conversation === null) return;
    // D-057:未执行/待审批工具阻塞 → 停止派发(ChatService.kt:860 break;
    //   审批续跑/「继续」解阻后再 drain)
    if (conversationHasPendingOrUnexecutedTools(this.conversation)) return;
    const dq: DequeueNextResult = dequeueNextPendingUserMessage(this.pendingQueue);
    if (dq.next === null) return;
    const prep = preparePendingMessageForDispatch(dq.rest, dq.next);
    this.pendingQueue = prep.rest;
    this.syncPendingRows();
    // D-052:dequeue 后 durable persist(ChatService.kt:1046-1053)+ 审计
    //   (detail = 派发消息 mode 小写 — STEER 经 prepare 已转 FOLLOWUP)
    this.persistQueue();
    this.auditQueueEvent('dequeue', prep.dispatch.id, prep.dispatch.mode.toLowerCase());
    // D-051:answer=false → 只记录不生成(ChatService.kt:847-850
    //   appendUserMessage;answer 才 handleMessageComplete),随后继续出队
    if (!prep.dispatch.answer) {
      runAppendUserMessage(this.conversation, prep.dispatch.parts, getChatRepository())
        .then((out: Conversation): void => {
          this.conversation = out;
          this.rows = buildRows(out);
          this.scrollToBottom();
          this.drainPendingQueue();
        })
        .catch((e: Error): void => {
          this.errorMsg = e.message;
          this.drainPendingQueue();
        });
      return;
    }
    this.sendParts(prep.dispatch.parts, prep.dispatch.id);
  }

  // ===== D-064 /deepread 斜杠命令(SendMessageOrchestrator.kt:26-41) =====

  // 深读页路由(deep_read_open 工具 bus 与斜杠命令共用)
  private openDeepRead(event: DeepReadOpenEvent): void {
    const params: Record<string, string> = {};
    params['topicId'] = event.topicId;
    params['title'] = event.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  // 命中 → 路由深读页 + 清空输入,不入队/不发给模型(orchestrator:34-38
  //   bus.tryEmit 恒真 = 页面存活即有收集者,与 deep_read_open 同口径)
  private tryRouteDeepReadSlash(inputParts: UIMessagePart[]): boolean {
    const event: DeepReadOpenEvent | null = parseDeepReadSlashCommand(inputParts, sha256HexUtf8);
    if (event === null) return false;
    this.inputText = '';
    this.pendingImages = [];
    this.openDeepRead(event);
    return true;
  }

  // D-051:长按发送 = 只记录不生成(Android ChatInput.kt:272 → queueMode;
  //   ChatVM.sendMessageWithoutAnswer → sendMessage(answer=false))
  //   生成中 → STEER 入队(插在生成后首位);否则直接 appendUserMessage
  //   登记偏差:编辑态不支持(Android 无此交互),编辑态长按按普通发送语义入队
  private sendWithoutAnswer(): void {
    const text: string = this.inputText.trim();
    const hasImages: boolean = this.pendingImages.length > 0;
    if ((text.length === 0 && !hasImages) || this.conversation === null) return;
    const inputParts: UIMessagePart[] = [];
    if (text.length > 0) {
      inputParts.push({ type: 'text', text, metadata: null });
    }
    for (const url of this.pendingImages) {
      inputParts.push({ type: 'image', url, metadata: null });
    }
    // D-064:斜杠命令拦截(编辑态除外 — Android 编辑走 handleMessageEdit 不经 orchestrator)
    if (this.editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    this.inputText = '';
    this.pendingImages = [];
    if (this.editingNodeId.length > 0) this.cancelEdit();
    if (this.sending) {
      this.enqueuePendingParts(inputParts, false);
      return;
    }
    runAppendUserMessage(this.conversation, inputParts, getChatRepository())
      .then((out: Conversation): void => {
        this.conversation = out;
        this.rows = buildRows(out);
        this.scrollToBottom();
      })
      .catch((e: Error): void => { this.errorMsg = e.message; });
  }

  private send(): void {
    const text: string = this.inputText.trim();
    const hasImages: boolean = this.pendingImages.length > 0;
    // D-038:文本可空但图片不可空;两者皆空才拦截(isEmptyInput 对齐 Android)
    if ((text.length === 0 && !hasImages) || this.conversation === null) return;
    // 组装输入 parts:文本 + 图片(data: URL)
    const inputParts: UIMessagePart[] = [];
    if (text.length > 0) {
      inputParts.push({ type: 'text', text, metadata: null });
    }
    for (const url of this.pendingImages) {
      inputParts.push({ type: 'image', url, metadata: null });
    }
    // D-064:斜杠命令拦截(orchestrator 在排队/发送前解析;编辑态除外)
    if (this.editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    // D-048:生成中 → FOLLOWUP 入队(编辑态同 — 编辑重发需等当前生成结束;
    //   入队即退出编辑态,排队消息按新消息派发,登记偏差:Android 无编辑态排队交互)
    if (this.sending) {
      this.enqueuePendingParts(inputParts, true);
      this.inputText = '';
      this.pendingImages = [];
      if (this.editingNodeId.length > 0) this.cancelEdit();
      return;
    }
    // 编辑态:走「编辑并重发」(编辑态不携带待发图片,对齐 Android 编辑仅文本)
    if (this.editingNodeId.length > 0) {
      this.sendEdit(text);
      return;
    }
    this.sendParts(inputParts);
  }

  private sendParts(inputParts: UIMessagePart[], dispatchMessageId: string = ''): void {
    if (this.conversation === null) return;
    // 单文本 part → 退化为 string(与既有 send 路径行为一致)
    let userInput: string | UIMessagePart[] = inputParts;
    if (inputParts.length === 1 && inputParts[0].type === 'text') {
      userInput = (inputParts[0] as UIMessagePartText).text;
    }
    this.sending = true;
    this.errorMsg = '';
    this.abortController = new EntryAbortController();
    const conv: Conversation = this.conversation;
    // 流式尾部起点 = 现有消息 + 即将入列的 user 消息
    const tailStart: number = currentMessages(conv).length + 1;
    // D-057:续跑模式(idle blocker「继续」/应答)— 不追加 user 消息,
    //   流式预览切换为末条消息(写回末条 assistant,regenTail 机制)
    let resumeMode: boolean = false;
    let resumeNodeId: string = '';
    // holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制)
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime(conv.assistantId)
      .then((rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型(providers 未持久化或无可用 auth)'));
        }
        const deps: ChatTurnDeps = {
          assistant: rt.assistant,
          inputTransformers: [createTemplateTransformer()],
          outputTransformers: [thinkTagTransformer, regexOutputTransformer],
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          // D-055:压缩引擎接管上下文(engine 内部编辑/规划/截断;
          //   chat_turn 侧 contextMessageSize 强制 0,避免双重截断)
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          abortSignal: this.abortController?.signal,
          // D-050:重试状态(strings.xml:386 文案;null 清除)
          onRetryStatus: (status: string | null): void => {
            this.retryStatus = status ?? '';
          },
          onUpdate: (msgs: UIMessage[]): void => {
            if (resumeMode) {
              if (msgs.length > 0) {
                this.regenNodeId = resumeNodeId;
                this.regenTail = msgs[msgs.length - 1];
              }
              return;
            }
            this.streamingTail = msgs.slice(tailStart);
          },
        };
        // D-057:idle 工具阻塞(ChatService.kt:757/:838 — 派发前先解阻塞;
        //   「继续」批准/ask_user 应答 → 消息不入列直接续跑;否则取消陈旧工具)
        const isPureText: boolean = inputParts.every(
          (p: UIMessagePart): boolean => p.type === 'text');
        const answerText: string = inputParts
          .filter((p: UIMessagePart): boolean => p.type === 'text')
          .map((p: UIMessagePart): string => (p as UIMessagePartText).text)
          .join('\n');
        const blocker: IdleToolBlockerResult = resolveIdleToolBlocker(conv, answerText, isPureText);
        if (blocker.changed) {
          this.auditQueueEvent(blocker.event ?? 'pending_tool_cancel', dispatchMessageId,
            blocker.blockingToolNames.join(','));
          if (blocker.shouldResume) {
            resumeMode = true;
            resumeNodeId = blocker.conversation.messageNodes.length > 0
              ? blocker.conversation.messageNodes[blocker.conversation.messageNodes.length - 1].id
              : '';
            this.conversation = blocker.conversation;
            this.rows = buildRows(blocker.conversation);
            return getChatRepository().save(blocker.conversation)
              .then((): Promise<Conversation> => runToolLoopContinuation(
                blocker.conversation, deps, this.buildToolLoop(rt, blocker.conversation)));
          }
          return getChatRepository().save(blocker.conversation)
            .then((): Promise<Conversation> => runChatTurnWithTools(
              blocker.conversation, userInput, deps, this.buildToolLoop(rt, blocker.conversation)));
        }
        return runChatTurnWithTools(conv, userInput, deps, this.buildToolLoop(rt, conv));
      })
      .then((out: Conversation): void => {
        this.conversation = out;
        this.rows = buildRows(out);
        this.streamingTail = [];
        this.regenTail = null;
        this.regenNodeId = '';
        this.suggestions = out.chatSuggestions;
        this.inputText = '';
        this.pendingImages = [];
        this.sending = false;
        this.abortController = null;
        this.scrollToBottom();
        // 自动命名 + 会话建议(非阻塞,对齐 Android 独立 launch)
        if (holder.rt !== null) {
          this.maybeAutoTitle(out, holder.rt);
          this.maybeSuggestions(out, holder.rt);
        }
        // D-048:出队下一条(Android 循环:成功 → dequeueNext)
        this.drainPendingQueue();
      })
      .catch((e: Error): void => {
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.abortController = null;
        this.streamingTail = [];
        this.regenTail = null;
        this.regenNodeId = '';
        // 图片已随 user 快照持久化,清空避免重发时重复
        this.pendingImages = [];
        // user 消息快照已持久化(chat_turn 语义),刷新显示
        getChatRepository().getById(conv.id).then((saved: Conversation | null): void => {
          if (saved !== null) {
            this.conversation = saved;
            this.rows = buildRows(saved);
            this.suggestions = saved.chatSuggestions;
          }
          // D-048:失败也继续出队(Android 循环逐条 catch → addError 后继续)
          this.drainPendingQueue();
        });
      });
  }

  build() {
    Column() {
      Column() {
        if (this.editingTitle) {
          Row() {
            TextInput({ text: this.titleInput, placeholder: '会话标题' })
              .fontSize(15).layoutWeight(1).height(36)
              .backgroundColor(SURFACE)
              .onChange((v: string): void => { this.titleInput = v; })
            Text('保存').fontSize(13).fontColor(ACCENT)
              .padding({ left: 10, right: 4 })
              .onClick((): void => this.saveTitle())
            Text('取消').fontSize(13).fontColor(INK3)
              .padding({ left: 10, right: 4 })
              .onClick((): void => { this.editingTitle = false; })
          }
          .padding({ left: 16, right: 16 })
        } else {
          Text(this.title)
            .fontSize(17).fontWeight(FontWeight.Medium).fontColor(INK)
            .onClick((): void => this.startEditTitle())
        }
        Text(this.modelLabel.length > 0 ? this.modelLabel : '未配置模型 — 点我选择')
          .fontSize(11)
          .fontColor(this.modelLabel.length > 0 ? INK3 : ACCENT)
          .margin({ top: 2 })
      }
      .padding({ top: 16, bottom: 8 })
      .onClick((): void => {
        if (!this.editingTitle) {
          router.pushUrl({ url: 'pages/ChatModelPickerPage' });
        }
      })

      List({ scroller: this.scroller }) {
        ForEach(this.rows, (row: MsgRow): void => {
          ListItem() {
            MessageRow({
              message: row.message,
              branchCount: row.branchCount,
              selectIndex: row.selectIndex,
              isAssistant: row.isAssistant,
              isLastAssistant: row.isLastAssistant,
              onSwitch: (idx: number): void => this.switchBranch(row.nodeId, idx),
              onRegenerate: (): void => this.regenerate(row.nodeId),
              onDelete: (): void => this.confirmDeleteMessage(row),
              onEdit: (): void => this.startEditMessage(row),
              onFork: (): void => this.forkAt(row),
              onApproveTool: (toolCallId: string): void => this.handleToolApproval(toolCallId, true),
              onDenyTool: (toolCallId: string): void => this.handleToolApproval(toolCallId, false),
            })
          }
          // D-041:regenerate 流式预览渲染在目标节点行下
          if (row.nodeId === this.regenNodeId && this.regenTail !== null) {
            ListItem() {
              MessageRow({ message: this.regenTail as UIMessage, streaming: true })
            }
          }
        }, (row: MsgRow): string => row.nodeId)
        ForEach(this.streamingTail, (m: UIMessage): void => {
          ListItem() {
            MessageRow({ message: m, streaming: true })
          }
        }, (m: UIMessage): string => `tail-${m.id}`)
        if (this.sending) {
          ListItem() {
            Text('生成中…').fontSize(12).fontColor(INK3)
              .padding({ left: 16, top: 4, bottom: 4 })
          }
        }
      }
      .width('100%').layoutWeight(1)
      .divider({ strokeWidth: 1, color: LINE })

      if (this.errorMsg.length > 0) {
        Text(this.errorMsg)
          .fontSize(12).fontColor('#B33A3A')
          .padding({ left: 16, right: 16, top: 4, bottom: 4 })
      }

      // D-050:重试状态(strings.xml:386 文案,重试等待期间显示)
      if (this.retryStatus.length > 0) {
        Text(this.retryStatus)
          .fontSize(12).fontColor(ACCENT)
          .padding({ left: 16, right: 16, top: 4, bottom: 4 })
      }

      // 会话建议 chips(D-026):生成完成后刷新;点击填入输入框
      if (this.suggestions.length > 0 && !this.sending) {
        Scroll() {
          Row() {
            ForEach(this.suggestions, (s: string): void => {
              Text(s)
                .fontSize(12).fontColor(INK2)
                .maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
                .padding({ left: 10, right: 10, top: 6, bottom: 6 })
                .backgroundColor(SURFACE)
                .borderRadius(12)
                .margin({ right: 8 })
                // Android ChatPage.kt:812-830:点击 = 填入输入框;
                //   长按 = 直接发送(D-042);生成中 → FOLLOWUP 入队(D-048)
                .onClick((): void => { this.inputText = s; })
                .gesture(
                  LongPressGesture()
                    .onAction((): void => {
                      if (this.sending) {
                        this.enqueuePendingParts([{ type: 'text', text: s, metadata: null }], true);
                        return;
                      }
                      this.inputText = s;
                      this.send();
                    })
                )
            }, (s: string): string => s)
          }
          .padding({ left: 16, right: 16 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 4 })
      }

      // D-048:排队消息 chips(Android ChatListIndicators pending 区,可逐条取消)
      if (this.pendingRows.length > 0) {
        Scroll() {
          Row() {
            Text('排队中')
              .fontSize(11).fontColor(INK3)
              .margin({ right: 8 })
            ForEach(this.pendingRows, (row: PendingChipRow): void => {
              Row() {
                Text(row.preview)
                  .fontSize(12).fontColor(INK2)
                  .maxLines(1)
                  .textOverflow({ overflow: TextOverflow.Ellipsis })
                  .constraintSize({ maxWidth: 180 })
                Text('✕')
                  .fontSize(11).fontColor(INK3)
                  .padding({ left: 6 })
                  .onClick((): void => this.cancelPendingById(row.id))
              }
              .padding({ left: 10, right: 8, top: 5, bottom: 5 })
              .backgroundColor(SURFACE2)
              .borderRadius(12)
              .margin({ right: 8 })
            }, (row: PendingChipRow): string => row.id)
          }
          .padding({ left: 16, right: 16 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 6 })
      }

      // D-038:待发图片预览(可移除)
      if (this.pendingImages.length > 0) {
        Scroll() {
          Row() {
            ForEach(this.pendingImages, (url: string, i: number): void => {
              Stack() {
                Image(url)
                  .width(64).height(64)
                  .objectFit(ImageFit.Cover)
                  .borderRadius(8)
                Text('✕')
                  .fontSize(10).fontColor('#FFFFFF')
                  .padding({ left: 4, right: 4, top: 1, bottom: 1 })
                  .backgroundColor('#66000000')
                  .borderRadius(8)
                  .onClick((): void => { this.removePendingImage(i); })
              }
              .margin({ right: 8 })
            }, (url: string): string => url)
          }
          .padding({ left: 16, right: 16 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 6 })
      }

      Row() {
        if (this.editingNodeId.length > 0) {
          Text('取消编辑')
            .fontSize(12).fontColor(INK3)
            .padding({ right: 10 })
            .onClick((): void => this.cancelEdit())
        }
        // 图片选择(D-038)
        Text('图片')
          .fontSize(13).fontColor(this.sending ? INK3 : ACCENT)
          .padding({ right: 10 })
          .onClick((): void => this.pickImages())
        TextInput({ text: this.inputText,
          placeholder: this.editingNodeId.length > 0 ? '编辑消息,发送将重发…' : '输入消息…' })
          .layoutWeight(1)
          .fontSize(15)
          .padding(10)
          .backgroundColor(SURFACE)
          .borderRadius(8)
          .onChange((v: string): void => { this.inputText = v; })
        Text(this.sending ? '停止' : '发送')
          .fontSize(15)
          .fontColor(this.sending ? '#B43A3A' : ACCENT)
          .padding({ left: 14, right: 6 })
          .onClick((): void => {
            if (this.sending) {
              // 停止生成(D-035):abort → 部分内容照常入列(chat_turn 语义)
              this.abortController?.abort();
              // D-051:停止时 STEER 队列转 FOLLOWUP(Android stopGeneration:2419-2454)
              const conv2 = convertSteerToFollowup(this.pendingQueue);
              if (conv2.changed) {
                this.pendingQueue = conv2.messages;
                this.syncPendingRows();
                this.persistQueue();
              }
            } else {
              this.send();
            }
          })
          // D-051:长按 = 只记录不生成(answer=false,Android ChatInput.kt:272)
          .gesture(LongPressGesture().onAction((): void => {
            this.sendWithoutAnswer();
          }))
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 12 })
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

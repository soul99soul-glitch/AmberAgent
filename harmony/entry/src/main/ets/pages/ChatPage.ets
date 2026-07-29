// ChatPage — 聊天页:消息流(text/reasoning/tool)+ 输入发送 + 流式更新
// 回路: runChatTurn(chat-domain) + OpenAIChatApi(RcpHttpClient)+ RDB 仓库(D-021)
// D-024:assistant 分支 — ◀ n/m ▶ 切换(selectMessageBranch) + 末条「重新生成」
//   (runRegenerateAt);D-041:regenerate 流式预览落地(目标节点行下渲染流式
//   快照,visual transforms 与 send 路径一致),MVP spinner 裁剪项清零
// D-038:图片输入 — PhotoViewPicker 选图 → data: URL → UIMessagePart[] 发送
//   (文本可空,图片不可空);渲染 Image(p.url)
// D-079b:物化即压缩(ImageCompress.compressImageAsDataUrl),对齐 FileEncoder 载荷
import { router } from '@kit.ArkUI';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import type {
  Conversation, UIMessage, UIMessagePart, UIMessagePartText, Assistant, TransformerContext,
  PendingUserMessage, PendingUserMessageMode, DequeueNextResult, UIMessagePartReasoning, ReasoningCardState,
  CompactPolicy, CompactResult, PreparedContext,
  UIMessagePartTool, ToolLoopOptions, IdleToolBlockerResult, ChatToolDefinition,
  TextGenerationParams, ChatStreamProvider, ToolApprovalVerdict, ChatTurnDeps,
  ToolRegistry, DeepReadOpenEvent, MessageTransformer, AgentTool, LocalToolOption,
  MessageChunk, OpenAIChatApi, AskUserQuestion, AskedAnswers, ConversationCompact,
  ImageGuardSettings, VisionModelHealth, VisionProbeDeps, ProviderModel, ProviderSetting,
  AgentToolActivityStore, SandboxActivityUiState, McpToolActivityPort, JsonValue,
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
  createToolSearchTool, createRegistryIntrospectionTools, filterToolProfile,
  createRunPlanUpdateTool, createClipboardTool, createDeepReadOpenTool,
  parseDeepReadSlashCommand,
  applyToolApprovalToConversation, conversationHasPendingTools,
  conversationHasPendingOrUnexecutedTools, resolveIdleToolBlocker,
  parseAskUserQuestions, buildAskUserAnswerPayload,
  parseAskedAnswers, askAnswerDisplayText,
  isAskUserSubmittable, hasAnyAskAnswer, ASK_USER_TOOL_NAME,
  estimateConversationInputTokens, formatNumberInt, formatContextTokens,
  loadSearchPrefs, SearchSettings, createSearchTools,
  createTimeReminderTransformer, javaLocalDateTimeString, dayOfWeekFullName,
  createPlaceholderTransformer, createDocumentTransformer,
  createPromptInjectionTransformer, PromptInjection, Lorebook,
  RECENT_CHATS_PROMPT_LIMIT, buildRecentChatsPrompt, RecentChatItem, ConversationSummary,
  buildMemoryRecallPrompt, MemoryRecallRuntimeGate, createMemoryTools,
  runMemoryExtraction, MemoryWorkerModelResolution, memoryWorkerModelCandidates,
  buildAgentSoulPrompt,
  toChatModel, DEFAULT_COMPRESS_PROMPT,
  makeGenerationRetrySetting,
  firstImageBlockingIssueForSend, inspectImageAttachment, imageAttachmentBlocksSend,
  probeVisionModelHealth,
  createMcpManagementTools, createMcpServerTools, makeAgentTool, createSkillTools,
  createWorkspaceTools,
  loadAssistants, saveAssistants, patchAssistant,
  deriveSandboxActivities, mergeSandboxTimeline, idleSandboxActivity,
  withStepProgress, isActiveOperation,
} from '@amber/chat-domain';
import {
  getChatRepository, getChatAssistants, resolveChatRuntime, describeChatModel, ChatRuntime,
  getChatKvStore, getCompactStore, getSessionGrantStore,
  resolveChatProviderChoice, ChatProviderChoice,
  buildProviderApi, currentChatModelUuid, loadTaskModelIds, resolveTaskChatModelChoice,
  resolveTaskModelChain, loadTaskPrompts, TaskPrompts,
  getMcpManager, initMcpManager, getToolActivityStore, getSkillManager,
  getWorkspaceManager,
  getSkillWorkspacePort,
} from '../di/AppContainer.ets';
import { getMcpSettingsStore } from '../platform_impl/McpSettingsStore.ets';
import { inflateRawEntry } from '../platform_impl/EntryDocumentParser.ets';
import { compressImageAsDataUrl } from '../platform_impl/ImageCompress.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';
import { createEntryClipboardPort, sha256HexUtf8 } from '../platform_impl/EntryLocalToolPorts.ets';
import { ensureSearchSdk } from '../platform_impl/SearchSdkInit.ets';
import { createEntryOcrTransformer, findModelByIdInProviders, findProviderOfModel, generateVisionText, loadOcrSeed, OcrSeed } from '../platform_impl/OcrSupport.ets';
import { encodeImageDetailed } from '../platform_impl/EntryImageEncoder.ets';
import {
  AgentRuntimeSnapshot, defaultAgentRuntimeSnapshot, loadAgentRuntimeSnapshot, systemLanguage,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { createEntryPlaceholderValues, loadUserNickname } from '../platform_impl/PlaceholderSupport.ets';
import { createEntryDocumentReader } from '../platform_impl/DocumentSupport.ets';
import { loadLorebooks, loadModeInjections } from '../platform_impl/ExtensionPrefs.ets';
import { createEntryBase64ImageTransformer } from '../platform_impl/Base64ImageSupport.ets';
import {
  getMemoryExtractionStoreOps, getMemoryReadRepository, getMemoryToolDeps,
} from '../platform_impl/MemoryStore.ets';

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const SURFACE2: string = '#EFECE4';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';
// D-122:沙盒 sheet 代码块底色(设置面 ROW 惯例同值)
const ROW: string = '#EFECE4';

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
  // D-105:ask_user 应答回调(ChatMessageAskUserStep.kt onToolAnswer)
  onAnswerTool: (toolCallId: string, answer: string) => void = (_id: string, _a: string): void => {};
  // D-053:reasoning 卡片态(part 下标 → 用户显式选择;未选走默认迁移)
  @State reasoningStates: Record<number, string> = {};
  // D-105:ask_user 草稿态(题 id → 文本/单选值;:102;题 id → 多选集;:104)
  @State askAnswers: Record<string, string> = {};
  @State askMultiAnswers: Record<string, string[]> = {};
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

  // :952-960 已拒绝展示:'已拒绝' + 非空白 reason → ': reason'(红色;
  //   chat_message_tool_denied zh 逐字,冒号为 ASCII ': ')
  private deniedLabel(t: UIMessagePartTool): string {
    if (t.approvalState.type !== 'denied') return '';
    const reason: string = t.approvalState.reason;
    return reason.trim().length > 0 ? `已拒绝: ${reason}` : '已拒绝';
  }

  // ===== D-105:ask_user 问题卡片(ChatMessageAskUserStep.kt 子集) =====
  // :87-99 解析(渲染期重算,题量小;解析失败 → [])
  private askQuestionsOf(t: UIMessagePartTool): AskUserQuestion[] {
    return parseAskUserQuestions(t.input);
  }

  // :106 首题文案;多题 → '询问 N 个问题'(zh strings.xml:192 逐字)
  private askHeading(qs: AskUserQuestion[]): string {
    if (qs.length <= 1) return qs.length > 0 ? qs[0].question : '...';
    return `询问 ${qs.length} 个问题`;
  }

  private askAnswerAt(qid: string): string {
    const v: string | undefined = this.askAnswers[qid];
    return v !== undefined ? v : '';
  }

  private askMultiAt(qid: string): string[] {
    const v: string[] | undefined = this.askMultiAnswers[qid];
    return v !== undefined ? v : [];
  }

  // :174 单选/文本选项 chip 点选
  private setAskAnswer(qid: string, value: string): void {
    const copy: Record<string, string> = {};
    const keys: string[] = Object.keys(this.askAnswers);
    for (const k of keys) {
      copy[k] = this.askAnswers[k];
    }
    copy[qid] = value;
    this.askAnswers = copy;
  }

  // :191-196 多选 chip 切换(Set 增删语义)
  private toggleAskMulti(qid: string, option: string): void {
    const copy: Record<string, string[]> = {};
    const keys: string[] = Object.keys(this.askMultiAnswers);
    for (const k of keys) {
      copy[k] = this.askMultiAnswers[k].slice();
    }
    const cur: string[] = copy[qid] !== undefined ? copy[qid] : [];
    const idx: number = cur.indexOf(option);
    if (idx >= 0) {
      cur.splice(idx, 1);
    } else {
      cur.push(option);
    }
    copy[qid] = cur;
    this.askMultiAnswers = copy;
  }

  // :307-309 清空
  private clearAskDrafts(): void {
    this.askAnswers = {};
    this.askMultiAnswers = {};
  }

  // :330-347 提交(载荷逐字;使能判定 :348-353 由按钮侧调 isAskUserSubmittable)
  private submitAskAnswers(t: UIMessagePartTool): void {
    const qs: AskUserQuestion[] = this.askQuestionsOf(t);
    this.onAnswerTool(t.toolCallId,
      buildAskUserAnswerPayload(qs, this.askAnswers, this.askMultiAnswers));
  }

  // :246-253 已应答展示文本
  private askAnsweredText(t: UIMessagePartTool, qid: string): string {
    if (t.approvalState.type !== 'answered') return '';
    const fallback: string = t.approvalState.answer;
    return askAnswerDisplayText(parseAskedAnswers(fallback), qid, fallback);
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
            if ((p as UIMessagePartTool).toolName === ASK_USER_TOOL_NAME) {
              // ===== D-105:ask_user 问题卡片(ChatMessageAskUserStep.kt:127-374 子集) =====
              //   标题:单题 → 首题文案;多题 → '询问 N 个问题'(zh 逐字)
              Text(this.askHeading(this.askQuestionsOf(p as UIMessagePartTool)))
                .fontSize(12).fontColor(ACCENT).fontWeight(FontWeight.Medium)
                .width('100%').margin({ top: 4 })
              ForEach(this.askQuestionsOf(p as UIMessagePartTool),
                (q: AskUserQuestion, qi: number) => {
                  Column() {
                    if (qi > 0) {
                      // :149-152 题间 hairline
                      Divider().color(LINE).strokeWidth(1).margin({ top: 6, bottom: 2 })
                    }
                    Text(q.question)
                      .fontSize(12).fontColor(INK)
                      .width('100%').margin({ top: qi > 0 ? 0 : 4 })
                    if ((p as UIMessagePartTool).approvalState.type === 'pending') {
                      // :163-243 pending + onToolAnswer → 输入面
                      if (q.options.length > 0) {
                        // single/multi/text+options 均渲染选项 chips(FlowRow → Flex wrap)
                        Flex({ wrap: FlexWrap.Wrap }) {
                          ForEach(q.options, (opt: string) => {
                            Text(opt)
                              .fontSize(11)
                              .fontColor(
                                (q.selectionType === 'multi'
                                  ? this.askMultiAt(q.id).indexOf(opt) >= 0
                                  : this.askAnswerAt(q.id) === opt) ? '#FFFFFF' : INK2)
                              .padding({ left: 12, right: 12, top: 5, bottom: 5 })
                              .margin({ right: 6, top: 4 })
                              .borderRadius(999)
                              .backgroundColor(
                                (q.selectionType === 'multi'
                                  ? this.askMultiAt(q.id).indexOf(opt) >= 0
                                  : this.askAnswerAt(q.id) === opt) ? ACCENT : SURFACE2)
                              .border({
                                width: (q.selectionType === 'multi'
                                  ? this.askMultiAt(q.id).indexOf(opt) >= 0
                                  : this.askAnswerAt(q.id) === opt) ? 0 : 1,
                                color: LINE,
                              })
                              .onClick((): void => {
                                if (q.selectionType === 'multi') {
                                  this.toggleAskMulti(q.id, opt);
                                } else {
                                  this.setAskAnswer(q.id, opt);
                                }
                              })
                          }, (opt: string): string => opt)
                        }
                        .width('100%')
                      }
                      if (q.selectionType !== 'single' && q.selectionType !== 'multi') {
                        // :219-241 text 类型 → 自由文本框('在此输入回答…' 逐字)
                        TextArea({ placeholder: '在此输入回答…', text: this.askAnswerAt(q.id) })
                          .width('100%').height(64).fontSize(12).fontColor(INK)
                          .backgroundColor(SURFACE).borderRadius(10)
                          .margin({ top: 6 })
                          .onChange((v: string): void => {
                            this.setAskAnswer(q.id, v);
                          })
                      }
                    } else if ((p as UIMessagePartTool).approvalState.type === 'answered') {
                      // :244-288 已应答 → 'A' 标记 + 展示文本
                      Row() {
                        Text('A')
                          .fontSize(10).fontColor(ACCENT)
                          .padding({ left: 5, right: 5, top: 1, bottom: 1 })
                          .backgroundColor('#1FB8623A')
                          .borderRadius(4)
                        Text(this.askAnsweredText(p as UIMessagePartTool, q.id))
                          .fontSize(12).fontColor(INK)
                          .layoutWeight(1)
                      }
                      .width('100%')
                      .padding({ left: 8, right: 8, top: 6, bottom: 6 })
                      .margin({ top: 4 })
                      .backgroundColor(SURFACE2)
                      .borderRadius(8)
                      .border({ width: 1, color: LINE })
                    }
                  }
                  .width('100%').alignItems(HorizontalAlign.Start)
                },
                (q: AskUserQuestion, qi: number): string => `${qi}:${q.id}`)
              if ((p as UIMessagePartTool).approvalState.type === 'pending') {
                // :293-374 操作行:清空(任一已答时)+ 提交(全部可答使能)
                Row() {
                  Blank()
                  if (hasAnyAskAnswer(this.askAnswers, this.askMultiAnswers)) {
                    Text('清空')
                      .fontSize(11).fontColor(INK3)
                      .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                      .onClick((): void => {
                        this.clearAskDrafts();
                      })
                  }
                  Text('提交')
                    .fontSize(12).fontWeight(FontWeight.Medium)
                    .fontColor(isAskUserSubmittable(this.askQuestionsOf(p as UIMessagePartTool),
                      this.askAnswers, this.askMultiAnswers) ? '#FFFFFF' : INK3)
                    .padding({ left: 16, right: 16, top: 6, bottom: 6 })
                    .margin({ left: 8 })
                    .borderRadius(999)
                    .backgroundColor(isAskUserSubmittable(this.askQuestionsOf(p as UIMessagePartTool),
                      this.askAnswers, this.askMultiAnswers) ? ACCENT : LINE)
                    .onClick((): void => {
                      if (isAskUserSubmittable(this.askQuestionsOf(p as UIMessagePartTool),
                        this.askAnswers, this.askMultiAnswers)) {
                        this.submitAskAnswers(p as UIMessagePartTool);
                      }
                    })
                }
                .width('100%').margin({ top: 8 })
              }
            } else if ((p as UIMessagePartTool).approvalState.type === 'pending') {
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
            if ((p as UIMessagePartTool).toolName !== ASK_USER_TOOL_NAME
              && (p as UIMessagePartTool).approvalState.type === 'denied') {
              // D-108:已拒绝原因展示(:952-960;ask_user 卡早退不渲染,:676-679)
              Text(this.deniedLabel(p as UIMessagePartTool))
                .fontSize(11).fontColor('#B43A3A')
                .width('100%').margin({ top: 4 })
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
  // D-087:任务模型选择快照(compress/title/suggestion;aboutToAppear 异步加载,
  //   与 ocrSeed/searchSettings 同档;null = 未解析或无可用模型 → 回退 chat 运行态)
  private compressChoice: ChatProviderChoice | null = null;
  private taskChoicesLoaded: boolean = false;
  // D-094:任务提示词快照(compress 同步工具构建位使用;aboutToAppear 加载,
  //   与 compressChoice/ocrSeed 同档;title/suggestion 走调用时新取)
  private taskPromptsCache: TaskPrompts | null = null;
  // D-070:搜索偏好(aboutToAppear 异步加载;SearchTools 工具组输入)
  searchSettings: SearchSettings | null = null;
  @State errorMsg: string = '';
  // D-108:拒绝理由弹窗态(ToolDenyReasonDialog;空 = 关闭)
  @State denyDialogToolCallId: string = '';
  @State denyReasonText: string = '';
  // D-110:上下文用量指示(ContextUsageIndicator)— compacts/窗口快照 + 压缩活动计数
  @State contextCompacts: ConversationCompact[] = [];
  @State contextWindowTokens: number | null = null;
  @State compactActiveCount: number = 0;
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
  // D-122:沙盒活动时间线(ChatPage.kt:500-536)— live 单槽订阅 + 步骤选择
  //   (-1 = 最新,:523-527 coerceIn 等价)+ sheet 开关
  @State liveActivity: SandboxActivityUiState | null = null;
  @State selectedSandboxIndex: number = -1;
  @State sandboxSheetOpen: boolean = false;
  private unsubActivity: () => void = (): void => {};
  private pendingQueue: PendingUserMessage[] = [];
  // D-050:重试状态文案(strings.xml:386;null = 清除)
  @State retryStatus: string = '';
  private conversation: Conversation | null = null;
  private abortController: EntryAbortController | null = null;
  private scroller: Scroller = new Scroller();
  // D-071b:OCR 种子(providers/ocr_model/ocr_prompt 快照,aboutToAppear 加载)
  private ocrSeed: OcrSeed | null = null;

  // D-072:agent_runtime 快照(transform 调用时读 = Android settingsFlow.value 语义)
  private agentRuntime: AgentRuntimeSnapshot = defaultAgentRuntimeSnapshot();
  // D-073:display_setting.userNickname 快照(placeholder nickname/user 取值)
  private userNickname: string = '';
  // D-075:mode_injections/lorebooks 快照(transform 调用时读 = settingsFlow.value 语义)
  private modeInjections: PromptInjection[] = [];
  private lorebooks: Lorebook[] = [];

  // D-071b~D-075:input transformers 链 —
  //   [timeReminder, promptInjection, template, placeholder, document, ocr]
  //   (Android ChatService.kt:169-177:TimeReminder 1/PromptInjection 2/
  //   Placeholder 4/Document 5/Ocr 6,相对序忠实;template 为 D-016 既有位;
  //   MiniAppPrompt 3 依附 webmount 未移植,P1 已登记);
  //   seed 未就绪当轮不带 OCR(同 D-070 搜索工具门模式)
  private buildInputTransformers(rt: ChatRuntime): MessageTransformer[] {
    const list: MessageTransformer[] = [
      createTimeReminderTransformer({
        enabled: (): boolean => this.agentRuntime.enableTimeReminder,
        formatDayOfWeek: (ms: number): string => dayOfWeekFullName(systemLanguage(), ms),
        formatLocalDateTime: javaLocalDateTimeString,
      }),
      createPromptInjectionTransformer({
        modeInjections: (): PromptInjection[] => this.modeInjections,
        lorebooks: (): Lorebook[] => this.lorebooks,
      }),
      createTemplateTransformer(),
      createPlaceholderTransformer(createEntryPlaceholderValues(
        rt.params.model.modelId, rt.params.model.displayName,
        (): string => this.userNickname)),
      createDocumentTransformer(createEntryDocumentReader()),
    ];
    if (this.ocrSeed !== null) {
      list.push(createEntryOcrTransformer(this.ocrSeed, rt.modelSupportsImageInput));
    }
    return list;
  }

  // D-077b:动态 system 块供给(recentChats 子集,GenerationHandler.kt:736);
  //   每次组装调用时重取(Android 每次 generateInternal 重算语义);
  //   enableRecentChatsReference 默认 true(PreferencesStore.kt:155)
  private loadExtraSystemBlocks(): Promise<string[]> {
    if (!this.agentRuntime.enableRecentChatsReference) return Promise.resolve([]);
    return getChatRepository().listRecent(RECENT_CHATS_PROMPT_LIMIT)
      .then((recent: ConversationSummary[]): string[] => {
        const items: RecentChatItem[] = recent.map(
          (s: ConversationSummary): RecentChatItem => ({
            title: s.title,
            lastChat: new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' })
              .format(new Date(s.updateAt)),
          }));
        const prompt: string = buildRecentChatsPrompt(items);
        return prompt.length > 0 ? [prompt] : [];
      });
  }

  // D-085b:记忆召回块(GenerationHandler.kt:414 → :727,动态序最前);
  //   每次组装重取 — 召回对当前会话消息(含刚入列的 user)打分;
  //   空召回/三开关全关 → buildMemoryRecallPrompt 回 '' → 不进块(Android :727 isNotBlank 门)
  private loadMemorySystemBlocks(): Promise<string[]> {
    const conv: Conversation | null = this.conversation;
    if (conv === null) return Promise.resolve([]);
    const gate: MemoryRecallRuntimeGate = {
      enableCoreMemory: this.agentRuntime.enableCoreMemory,
      enableShortTermMemory: this.agentRuntime.enableShortTermMemory,
      enableLongTermMemory: this.agentRuntime.enableLongTermMemory,
      memoryRecall: this.agentRuntime.memoryRecall,
    };
    return buildMemoryRecallPrompt(gate, currentMessages(conv), getMemoryReadRepository())
      .then((prompt: string): string[] => prompt.length > 0 ? [prompt] : []);
  }

  // D-122:页面消失退订(store 为全局单例,防泄漏/跨页回调)
  aboutToDisappear(): void {
    this.unsubActivity();
  }

  aboutToAppear(): void {
    // 每次进入(含从选择器返回)刷新模型标签
    describeChatModel().then((label: string): void => { this.modelLabel = label; });    // D-110:上下文窗口快照(ModelRegistry.MODEL_CONTEXT_WINDOW 注册表查询
    //   = P1 登记;取持久化 model.contextWindowTokens = Android 未知模型
    //   回退路径 :110-111)
    resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
      this.contextWindowTokens = choice !== null ? choice.model.contextWindowTokens : null;
    });
    // D-070:搜索 SDK 运行时装配(幂等)+ 搜索偏好加载(SearchTools 工具组接线)
    ensureSearchSdk();
    loadSearchPrefs(getChatKvStore()).then((s: SearchSettings): void => {
      this.searchSettings = s;
    });
    // D-071b:OCR 种子加载(providers/ocr_model/ocr_prompt 快照)
    loadOcrSeed(getChatKvStore()).then((seed: OcrSeed): void => {
      this.ocrSeed = seed;
    });
    // D-087:compress 任务模型选择快照(resolveTaskChatModel(compressModelId),
    //   未设 = AUTO → findById 必失 → 回退当前 chat 模型,语义逐字)
    loadTaskModelIds().then((ids): void => {
      resolveTaskChatModelChoice(ids.compressModelId).then((choice): void => {
        this.compressChoice = choice;
        this.taskChoicesLoaded = true;
      });
    });
    // D-094:任务提示词快照(compress 同步位;缺失键 → DEFAULT_*)
    loadTaskPrompts().then((prompts: TaskPrompts): void => {
      this.taskPromptsCache = prompts;
    });
    // D-072:agent_runtime 快照加载(enableTimeReminder 等;缺失/损坏 → 默认 false)
    loadAgentRuntimeSnapshot(getChatKvStore()).then((snap: AgentRuntimeSnapshot): void => {
      this.agentRuntime = snap;
    });
    // D-120:MCP manager 初始化(settings hydrate → 订阅回放真值,
    //   Android settingsFlow collect 时序等价);assistant mcpServers 白名单
    //   provider 于 runtime 解析后接线(wireMcpAssistant — 会话绑定 assistant,
    //   Android settings.getCurrentAssistant() 的页面级等价,登记偏差)
    initMcpManager().catch((e: Error): void => {
      hilog.error(0x0001, 'Chat.Page', 'initMcpManager failed: %{public}s', e.message);
    });
    // D-122:activityStore.sandboxActivity collect(:501)— StateFlow 首发射语义
    this.unsubActivity = getToolActivityStore().subscribe(
      (a: SandboxActivityUiState | null): void => {
        this.liveActivity = a;
      });
    // D-073:display_setting.userNickname 快照(placeholder nickname/user)
    loadUserNickname(getChatKvStore()).then((nick: string): void => {
      this.userNickname = nick;
    });
    // D-075:mode_injections/lorebooks 快照(缺失/损坏 → [])
    loadModeInjections(getChatKvStore()).then((list: PromptInjection[]): void => {
      this.modeInjections = list;
    });
    loadLorebooks(getChatKvStore()).then((list: Lorebook[]): void => {
      this.lorebooks = list;
    });
    const params = router.getParams() as Record<string, string>;
    const convId: string = params ? (params['conversationId'] ?? '') : '';
    if (convId.length > 0) {
      getChatRepository().getById(convId).then((conv: Conversation | null): void => {
        if (conv !== null) {
          this.conversation = conv;
          this.title = conv.title.length > 0 ? conv.title : '对话';
          this.rows = buildRows(conv);
          this.suggestions = conv.chatSuggestions;
          this.refreshContextCompacts();
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

  // ===== D-110:ContextUsageIndicator(ChatInputUsage.kt:77-162) =====

  private refreshContextCompacts(): void {
    const conv: Conversation | null = this.conversation;
    if (conv === null) {
      this.contextCompacts = [];
      return;
    }
    getCompactStore().getCompacts(conv.id)
      .then((cs: ConversationCompact[]): void => {
        this.contextCompacts = cs;
      })
      .catch((): void => {});
  }

  // :103-117 — estimate(消费活动 compacts,post-substitution 足迹)+ ratio clamp 0..1
  private contextUsageRatio(): number {
    const w: number | null = this.contextWindowTokens;
    if (w === null || w <= 0 || this.conversation === null) return 0;
    const used: number = estimateConversationInputTokens(
      currentMessages(this.conversation), this.contextCompacts);
    const r: number = used / w;
    return r < 0 ? 0 : (r > 1 ? 1 : r);
  }

  // :150-160 — 文案:压缩中 → '正在压缩上下文'(zh :225 逐字);
  //   否则 'Context <used> / <window|-->'
  private contextUsageText(): string {
    if (this.compactActiveCount > 0) return '正在压缩上下文';
    if (this.conversation === null) return 'Context 0 / --';
    const used: number = estimateConversationInputTokens(
      currentMessages(this.conversation), this.contextCompacts);
    const w: number | null = this.contextWindowTokens;
    return `Context ${formatContextTokens(used)} / ${w !== null ? formatNumberInt(w) : '--'}`;
  }

  // :120-125 — 环色:压缩中 blue;≥0.7 red;≥0.5 amber;否则 muted
  private contextRingColor(): string {
    if (this.compactActiveCount > 0) return '#4EA6FF';
    const r: number = this.contextUsageRatio();
    if (r >= 0.7) return '#B43A3A';
    if (r >= 0.5) return '#C08A2D';
    return '#8F8B80';
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
        this.wireMcpAssistant(rt);
        this.warnIfToolAbilityMissing(rt);
        // D-055:重生成使 compact 失效(ChatService.kt:1097 'message_regenerated'),
        //   先于生成完成,避免 prepareContext 读到过期摘要
        return this.invalidateConvCompacts(conv.id, 'message_regenerated')
          .then((): Promise<Conversation> => runRegenerateAtWithTools(conv, nodeId, {
          assistant: rt.assistant,
          // D-090:agentSoul 静态块首槽位(GenerationHandler.kt:721-724)
          agentSoul: buildAgentSoulPrompt(this.agentRuntime.agentSoulMarkdown),
          inputTransformers: this.buildInputTransformers(rt),
          // D-071b:视觉兜底门(模型 IMAGE 模态 + 有图才兜底,GenerationHandler.kt:431)
          modelSupportsImageInput: rt.modelSupportsImageInput,
          outputTransformers: [thinkTagTransformer, createEntryBase64ImageTransformer(), regexOutputTransformer], // ChatService.kt:179-184 序(MiniApp P1)
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          // D-055:压缩引擎接管上下文(同 send 路径)
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          // D-077b:recentChats 动态块(每次组装重取)
          // D-085b:记忆召回块(动态序最前,GenerationHandler.kt:727)
          memorySystemBlocks: (): Promise<string[]> => this.loadMemorySystemBlocks(),
          extraSystemBlocks: (): Promise<string[]> => this.loadExtraSystemBlocks(),
          abortSignal: this.abortController?.signal,
          // D-115:GenerationHandler.kt:357/475/621 — retrySetting =
          //   settings.agentRuntime.generationRetry(PreferencesStore.kt:181 持久化)
          retrySetting: makeGenerationRetrySetting({
            enabled: this.agentRuntime.generationRetry.enabled,
            maxRetries: this.agentRuntime.generationRetry.maxRetries,
          }),
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
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
        this.scrollToBottom();
        if (holder.rt !== null) {
          this.maybeSuggestions(out, holder.rt);
          this.maybeMemoryExtraction(out, holder.rt);
        }
      })
      .catch((e: Error): void => {
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
      });
  }

  // D-085d:记忆抽取(ChatService.kt:1494-1499 — 生成完成且无 pending/未执行
  //   工具时 appScope.launch 非阻塞抽取);门/去抖/配额/事件在 HAR 编排逐字;
  //   偏差:worker 模型 = chat 模型(任务模型设置未移植,D-025 同类登记);
  //   locale = systemLanguage()(Locale.getDefault().displayName 无直接等价)
  private maybeMemoryExtraction(conv: Conversation, rt: ChatRuntime): void {
    if (conversationHasPendingOrUnexecutedTools(conv)) return;
    const ops = getMemoryExtractionStoreOps();
    // D-087:链解析缓存(resolveWorkerModel 与 generateText 同一次编排共用,
    //   对齐 Android 单次 resolveMemoryModel 后模型+provider 配套使用)
    let extractionChoicePromise: Promise<ChatProviderChoice | null> | null = null;
    const resolveExtractionChoice = (): Promise<ChatProviderChoice | null> => {
      if (extractionChoicePromise === null) {
        extractionChoicePromise = (async (): Promise<ChatProviderChoice | null> => {
          const ids = await loadTaskModelIds();
          const chatUuid: string = await currentChatModelUuid();
          return resolveTaskModelChain(memoryWorkerModelCandidates(
            this.agentRuntime.memoryWorker, ids.compressModelId, chatUuid));
        })();
      }
      return extractionChoicePromise;
    };
    runMemoryExtraction(conv, {
      worker: this.agentRuntime.memoryWorker,
      locale: systemLanguage(),
      // D-087:worker 任务模型链(MemoryExtractor.kt:185-195)— modelId≠AUTO →
      //   followCompress → chat;外层 ?: chat;解析结果在闭包内缓存一次
      resolveWorkerModel: async (): Promise<MemoryWorkerModelResolution> => {
        const choice: ChatProviderChoice | null = await resolveExtractionChoice();
        return choice === null
          ? { kind: 'no_model' }
          : { kind: 'ok', modelId: choice.model.modelId };
      },
      generateText: async (prompt: string): Promise<string> => {
        const choice: ChatProviderChoice | null = await resolveExtractionChoice();
        if (choice === null) throw new Error('no model');
        const api: OpenAIChatApi = buildProviderApi(choice.provider, {});
        // TextGenerationParams(model = model) 默认参(MemoryExtractor.kt:121)
        const chunk: MessageChunk = await api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({ model: toChatModel(choice.model) }),
        );
        const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
        return msg !== null ? toText(msg) : '';
      },
      getAllActiveRecords: ops.getAllActiveRecords,
      addCandidates: ops.addCandidates,
      addCandidate: ops.addCandidate,
      addMemory: ops.addMemory,
      addEvent: ops.addEvent,
      countEventsSince: ops.countEventsSince,
    }).catch((e: Error): void => {
      // 编排体内错误已吞为 EXTRACTION_FAILED 事件;此处仅存储层故障 — 记录不打扰
      hilog.error(0x0000, 'AmberChat', 'memory extraction failed: %{public}s', e.message);
    });
  }

  // 自动命名(D-025):title 空白时非阻塞调用(对齐 Android 独立 launch);
  // 失败上浮到 errorMsg(非静默,Android addError 同语义)
  private async maybeAutoTitle(conv: Conversation, rt: ChatRuntime): Promise<void> {
    if (conv.title.trim().length > 0) return;
    // D-094:settings.titlePrompt(ChatPrefs.kt:81)→ runAutoTitle deps.template
    //   (auto_title.ts:60;调用时新取 = settingsFlow.value 语义)
    const prompts: TaskPrompts = await loadTaskPrompts();
    runAutoTitle(conv, {
      // D-087:title 任务模型(AiAuxiliaryGenerator.kt:41)—
      //   resolveTaskChatModel(titleModelId) ?: return(无模型静默跳过);
      //   params = model + reasoning OFF + model.customHeaders/customBodies(:55-59,
      //   不带 chat 温度/topP/maxTokens)
      generateText: async (prompt: string): Promise<string> => {
        const ids = await loadTaskModelIds();
        const choice: ChatProviderChoice | null =
          await resolveTaskChatModelChoice(ids.titleModelId);
        if (choice === null) throw new Error('no title model');
        const headers: Record<string, string> = {};
        for (const h of choice.model.customHeaders) {
          const name: string = h.name.trim();
          if (name.length === 0) continue;
          headers[name] = h.value.trim();
        }
        const api: OpenAIChatApi = buildProviderApi(choice.provider, headers);
        const chunk: MessageChunk = await api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({
            model: toChatModel(choice.model),
            reasoningLevel: 'off',
            customBody: choice.model.customBodies,
          }),
        );
        const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
        return msg !== null ? toText(msg) : '';
      },
      store: getChatRepository(),
      locale: '简体中文',
      template: prompts.titlePrompt,
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
  private async maybeSuggestions(conv: Conversation, rt: ChatRuntime): Promise<void> {
    // D-094:settings.suggestionPrompt(ChatPrefs.kt:82)→ runSuggestion deps.template
    //   (suggestion.ts:57;调用时新取)
    const prompts: TaskPrompts = await loadTaskPrompts();
    runSuggestion(conv, {
      // D-087:suggestion 任务模型(AiAuxiliaryGenerator.kt:87)— 同 title 链
      generateText: async (prompt: string): Promise<string> => {
        const ids = await loadTaskModelIds();
        const choice: ChatProviderChoice | null =
          await resolveTaskChatModelChoice(ids.suggestionModelId);
        if (choice === null) throw new Error('no suggestion model');
        const headers: Record<string, string> = {};
        for (const h of choice.model.customHeaders) {
          const name: string = h.name.trim();
          if (name.length === 0) continue;
          headers[name] = h.value.trim();
        }
        const api: OpenAIChatApi = buildProviderApi(choice.provider, headers);
        const chunk: MessageChunk = await api.generateText(
          [makeUserMessage(prompt)],
          makeTextGenerationParams({
            model: toChatModel(choice.model),
            reasoningLevel: 'off',
            customBody: choice.model.customBodies,
          }),
        );
        const msg = chunk.choices.length > 0 ? chunk.choices[0].message : null;
        return msg !== null ? toText(msg) : '';
      },
      store: getChatRepository(),
      locale: '简体中文',
      template: prompts.suggestionPrompt,
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

  // 图片选择(D-038):PhotoViewPicker → data: URL → 待发预览;
  //   D-079b:物化时压缩(gif 直通,其余 JPEG q85 长边 ≤2048 + EXIF 归一),
  //   对齐 Android FileEncoder.compressAndEncode 载荷;
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
        compressImageAsDataUrl(uri).then((dataUrl: string): void => {
          this.pendingImages = [...this.pendingImages, dataUrl];
        }).catch((e: Error): void => {
          hilog.warn(0x0001, 'ChatPage', `图片读取失败: ${e.message}`);
          this.errorMsg = `图片读取失败: ${e.message}`;
        });
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
  // D-087:compact 生成 provider(ConversationContextEngine.kt:408-410/:689)—
  //   压缩任务模型(resolveTaskChatModel(compressModelId),未设=AUTO→chat 模型)
  //   + TextGenerationParams(model) 最小参;快照未加载/无模型 → chat 运行态兜底
  private compressProvider(rt: ChatRuntime): ChatStreamProvider {
    const choice: ChatProviderChoice | null = this.compressChoice;
    if (choice === null) return asChatStreamProvider(rt.api, () => rt.params);
    const api: OpenAIChatApi = buildProviderApi(choice.provider, {});
    return asChatStreamProvider(
      api, () => makeTextGenerationParams({ model: toChatModel(choice.model) }));
  }

  private buildPrepareContext(conv: Conversation, rt: ChatRuntime):
    (messages: UIMessage[]) => Promise<UIMessage[]> {
    // D-091:settings.agentRuntime.contextCompaction.toCompactPolicy()
    //   (PreferencesStore.kt:247-255 字段直传;快照加载前 = Android 默认值)
    const cc = this.agentRuntime.contextCompaction;
    const policy: CompactPolicy = makeCompactPolicy({
      enabled: cc.enabled,
      notifyOnly: cc.notifyOnly,
      precompactRatio: cc.precompactRatio,
      forceRatio: cc.forceRatio,
      keepRecentTurns: cc.keepRecentTurns,
      maxSummaryTokens: cc.maxSummaryTokens,
    });
    return async (messages: UIMessage[]): Promise<UIMessage[]> => {
      const effConv: Conversation = conversationWithMessagesAsNodes(conv, messages);
      // D-094:settings.compressPrompt(ChatPrefs.kt:84)→ CompactEngineDeps.compressPrompt
      //   (context_engine.ts:485;调用时新取 = settingsFlow.value 语义)
      const prompts: TaskPrompts = await loadTaskPrompts();
      const prepared: PreparedContext = await prepareContext(effConv, policy,
        rt.modelContextWindowTokens, messages, rt.contextMessageSize, {
          provider: this.compressProvider(rt),
          store: getCompactStore(),
          compressPrompt: prompts.compressPrompt,
          launchPrecompact: (run: () => Promise<CompactResult>): void => {
            // D-110:precompact 活动跟踪(engine:182-191 appScope.launch;
            //   PLANNING/COMPACTING → 完成/失败复位)
            this.compactActiveCount = this.compactActiveCount + 1;
            run().catch((e: Error): void => {
              hilog.warn(HILOG_DOMAIN, HILOG_TAG, 'precompact failed: %{public}s', e.message);
            }).then((): void => {
              this.compactActiveCount = Math.max(0, this.compactActiveCount - 1);
            });
          },
          // D-110:force 压缩活动钩子(context_engine onCompactActiveChange)
          onCompactActiveChange: (active: boolean): void => {
            this.compactActiveCount = Math.max(0,
              this.compactActiveCount + (active ? 1 : -1));
          },
        });
      return prepared.messages;
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
  //   autoApprove/speculative 经 agentRuntime 快照接线(D-104);每步 provider 工厂按步内
  //   工具定义重建 params(arkts-no-spread 逐字段,不用 rt.params 展开)
  private buildToolLoop(rt: ChatRuntime, conv: Conversation): ToolLoopOptions {
    // Android conversationProvider = getConversationFlow().value(最新会话态)
    const conversationProvider: () => Promise<Conversation> = (): Promise<Conversation> =>
      Promise.resolve(this.conversation ?? conv);
    // D-082:ToolProfileFilter(ChatService.kt:2302)— 先按 assistant.toolProfile
    //   过滤原始工具集,再建 registry;tool_search/自省对在过滤后追加(:2326-2329)
    // D-083:LocalToolOption 门控(LocalTools.kt:105-124 + ChatService.kt:2237)—
    //   time_info/ask_user/clipboard 按 assistant.localTools 勾选加入;
    //   run_plan_update/deep_read_open 无门恒在(:182/:188);
    //   workspace_files 六件 D-124 落地(见下);同组
    //   workspaceArtifactTools/externalFileTools 及其余 9 variant
    //   (javascript_engine/tts/terminal/screen_automation/system_access/
    //   webview/icloud_drive/webmount/webmount_eval)未移植 → 门为空转(登记)
    const localOpts: LocalToolOption[] = rt.assistant.localTools;
    const profiledTools: AgentTool[] =
      filterToolProfile([
        ...(localOpts.indexOf('time_info') >= 0 ? [createTimeTool()] : []),
        ...(localOpts.indexOf('ask_user') >= 0 ? [createAskUserTool()] : []),
        // D-124:workspace 六件(LocalTools.kt:122-126 — workspace_files 门内
        //   workspaceTools.getTools(),位置 = ask_user 之后;同组
        //   workspaceArtifactTools/externalFileTools 未移植 → P1 登记);
        //   activityStore = 全局单例(D-122)
        ...(localOpts.indexOf('workspace_files') >= 0
          ? createWorkspaceTools(getWorkspaceManager(), getToolActivityStore())
          : []),
        // D-123:skills 工具组(ChatService.kt:2239-2246 — createSkillTools 五/六件,
        //   位置 = localTools 之后/MCP 管理之前);enabledSkills/allSkills =
        //   createRunTools 时快照(rt.assistant/rt.allSkills 同为本次发送快照);
        //   updateEnabledSkill(:339-350)→ setSkillEnabled 闭包 RMW 当前 assistant
        ...createSkillTools({
          enabledSkills: rt.assistant.enabledSkills,
          allSkills: rt.allSkills,
          skillManager: getSkillManager(),
          setSkillEnabled: (name: string, enable: boolean): Promise<void> =>
            this.setSkillEnabled(rt, name, enable),
          workspace: getSkillWorkspacePort(),
          inflateRaw: inflateRawEntry,
        }),
        // D-120:MCP 工具桥接(ChatService.kt:2247-2282)— 管理四件
        //   (mcp_list/mcp_call_tool/mcp_test + mcp_import_from_skill:D-123
        //   skills 子系统落地并入)+ mcp__ 组(getAllAvailableTools 逐个)
        ...createMcpManagementTools({
          settings: getMcpSettingsStore(),
          manager: getMcpManager(),
          skills: getSkillManager(),
        }),
        // D-122:activityStore 事件面接线(ChatService.kt:2262-2274 —
        //   startTool/complete/fail 三段;scope 由终表统一包覆,见 tools 组装)
        ...createMcpServerTools({
          manager: getMcpManager(),
          activity: this.mcpActivityPort(),
        }),
        // D-085c:memory_* 四件(ChatService.kt:2280 addAll(createMemoryTools),
        //   位置 = MCP 之后/ConversationContextTools 之前);
        //   三开关全关 → 空组(:2346-2351);todayText = toLocalString(true) 等价
        //   (本地化 MEDIUM 日期,建工具时快照)
        ...((this.agentRuntime.enableCoreMemory || this.agentRuntime.enableShortTermMemory
          || this.agentRuntime.enableLongTermMemory)
          ? createMemoryTools(getMemoryToolDeps(
            new Intl.DateTimeFormat(systemLanguage(), { dateStyle: 'medium' }).format(new Date())))
          : []),
        ...createConversationContextTools({
          conversationProvider,
          compactStore: getCompactStore(),
          // 与 buildPrepareContext 同口径(settings.agentRuntime.contextCompaction,D-091)
          policy: makeCompactPolicy({
            enabled: this.agentRuntime.contextCompaction.enabled,
            notifyOnly: this.agentRuntime.contextCompaction.notifyOnly,
            precompactRatio: this.agentRuntime.contextCompaction.precompactRatio,
            forceRatio: this.agentRuntime.contextCompaction.forceRatio,
            keepRecentTurns: this.agentRuntime.contextCompaction.keepRecentTurns,
            maxSummaryTokens: this.agentRuntime.contextCompaction.maxSummaryTokens,
          }),
          modelContextWindowTokens: rt.modelContextWindowTokens,
          compactEngineDeps: {
            provider: this.compressProvider(rt),
            store: getCompactStore(),
            // D-094:settings.compressPrompt(同步构建位 → aboutToAppear 快照,
            //   未就绪 → DEFAULT_COMPRESS_PROMPT,与 deps.compressPrompt ?? DEFAULT 同义)
            compressPrompt: this.taskPromptsCache !== null
              ? this.taskPromptsCache.compressPrompt : DEFAULT_COMPRESS_PROMPT,
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
        ...(localOpts.indexOf('clipboard') >= 0
          ? [createClipboardTool(createEntryClipboardPort())] : []),
        createDeepReadOpenTool({
          bus: {
            hasCollectors: (): boolean => true,
            emit: (event: DeepReadOpenEvent): void => {
              this.openDeepRead(event);
            },
          },
          sha256Hex: sha256HexUtf8,
        }),
        // D-070:搜索工具组(ChatService.kt:2232-2234 — enableWebSearch 门内
        //   addAll(createSearchTools(settings)));searchSettings 未加载完成时
        //   本轮不带搜索工具(偏好加载为异步,与 Android StateFlow 就绪时序等价)
        ...(this.searchSettings !== null && this.searchSettings.enableWebSearch
          ? createSearchTools(this.searchSettings)
          : []),
      ], rt.assistant.toolProfile).tools;
    const registry: ToolRegistry = createToolRegistry(profiledTools);
    return {
      // createRunTools(ChatService.kt:2326-2329):registry.tools() +
      //   tool_search(profile 透传,D-082)+ 自省对;
      //   D-122:scopedToConversation(:2329 → :2332-2343)终表统一包覆
      tools: this.scopedToConversation([
        ...registry.tools(),
        createToolSearchTool(registry, rt.assistant.toolProfile),
        ...createRegistryIntrospectionTools(registry),
      ], conv),
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
      // D-077a:tool system prompt 块所需模型(buildSystemPromptParts:739-741)
      toolPromptModel: rt.params.model,
      // D-104:ChatService.kt:1345-1347 逐字 —
      //   autoApproveTools = settings.agentRuntime.autoApproveAllToolCalls
      //     || conversation.autoApproveToolCalls
      autoApproveTools: this.agentRuntime.autoApproveAllToolCalls || conv.autoApproveToolCalls,
      autoApproveHighRiskTools: this.agentRuntime.autoApproveHighRiskToolCalls,
      // D-104:speculativeToolExecution(PreferencesStore.kt:226-229)门接线
      speculativeEnabled: this.agentRuntime.speculativeToolExecution.enabled,
      speculativeMaxConcurrentTools: this.agentRuntime.speculativeToolExecution.maxConcurrentTools,
      // D-115:ChatService.kt:1349-1352 —
      //   maxSteps = settings.agentRuntime.maxToolLoopSteps.coerceIn(16,512)
      //   (coerceIn 引擎侧 tool_loop.ts:147-148 已忠实,此处原样透传)
      maxSteps: this.agentRuntime.maxToolLoopSteps,
    };
  }

  // D-120:assistant.mcpServers 白名单 provider 接线(runtime 解析后;
  //   Android settings.getCurrentAssistant().mcpServers 的会话级等价)
  private wireMcpAssistant(rt: ChatRuntime): void {
    getMcpSettingsStore().setAssistantIdsProvider((): string[] => rt.assistant.mcpServers);
  }

  // D-121:tools_warning 门(ChatService.kt:1278-1286)— 模型无 TOOL 能力且
  //   (settings.enableWebSearch || mcpManager.getAllAvailableTools() 非空) →
  //   addError(tools_warning, title=error_title_tool_unavailable),**不中断生成**。
  //   须在 wireMcpAssistant 之后调用(getAllAvailableTools 依赖 assistant provider)。
  //   偏差登记:addError 带 title('工具不可用')— harmony 错误条无 title 槽位,
  //   仅正文 values-zh:1277 逐字上浮。
  private warnIfToolAbilityMissing(rt: ChatRuntime): void {
    if (rt.modelAbilities.indexOf('tool') >= 0) return;
    const searchOn: boolean = this.searchSettings !== null && this.searchSettings.enableWebSearch;
    if (!searchOn && getMcpManager().getAllAvailableTools().length === 0) return;
    this.errorMsg = '当前模型未开启工具调用，请在设置内启用模型的工具能力，再使用搜索/MCP等功能';
  }

  // ===== D-122:沙盒活动时间线(ChatPage.kt:500-536 + ChatInputSandbox.kt) =====

  // :503-522 — derive + scoped live + merge + operationPreviewMode 门;
  //   偏差:processingStatus ← retryStatus(harmony 无独立 live-status 流,
  //   enableLiveStatusNotification 子系统未移植,P1 延续)
  private sandboxTimeline(): SandboxActivityUiState[] {
    const conv: Conversation | null = this.conversation;
    if (conv === null) return [];
    const messageActivities: SandboxActivityUiState[] = deriveSandboxActivities(
      conv, this.sending, this.retryStatus.length > 0 ? this.retryStatus : null);
    // scopedLiveSandboxActivity(:508-511):conversationId 匹配,或 null 且
    //   消息时间线含该 toolCallId
    let scoped: SandboxActivityUiState | null = null;
    const live: SandboxActivityUiState | null = this.liveActivity;
    if (live !== null) {
      const inMessages: boolean = messageActivities.some(
        (a: SandboxActivityUiState): boolean => a.toolCallId === live.toolCallId);
      if (live.conversationId === conv.id || (live.conversationId === null && inMessages)) {
        scoped = withStepProgress(live, conv);
      }
    }
    const raw: SandboxActivityUiState[] = mergeSandboxTimeline(messageActivities, scoped);
    // :516-522 — ALWAYS 空 → idle 兜底;AUTO 过滤活动态;HIDDEN 空
    const mode: string = this.agentRuntime.operationPreviewMode;
    if (mode === 'always') {
      return raw.length > 0 ? raw : [idleSandboxActivity(conv)];
    }
    if (mode === 'auto') return raw.filter(isActiveOperation);
    return [];
  }

  // :523-529 — selectedSandboxIndex clamp(-1 = 最新)+ canCancel
  private currentSandboxActivity(timeline: SandboxActivityUiState[]): SandboxActivityUiState | null {
    const latest: number = timeline.length - 1;
    if (latest < 0) return null;
    if (this.selectedSandboxIndex >= 0 && this.selectedSandboxIndex <= latest) {
      return timeline[this.selectedSandboxIndex];
    }
    return timeline[latest];
  }

  // sandboxStatusLabel(:280-286 文案逐字)
  private sandboxStatusZh(status: string): string {
    if (status === 'running') return '执行中';
    if (status === 'waiting_for_permission') return '待授权';
    if (status === 'succeeded') return '成功';
    if (status === 'failed') return '失败';
    return '已取消';
  }

  // stepProgressText(:362-370):agent_idle → '待命';x/y;否则状态文案
  private sandboxStepText(activity: SandboxActivityUiState): string {
    if (activity.toolName === 'agent_idle') return '待命';
    if (activity.stepIndex !== null && activity.stepTotal !== null) {
      return `${activity.stepIndex}/${activity.stepTotal}`;
    }
    return this.sandboxStatusZh(activity.status);
  }

  // ChatService.kt:2254-2282 activityStore 事件面 — store 适配 McpToolActivityPort
  private mcpActivityPort(): McpToolActivityPort {
    const store: AgentToolActivityStore = getToolActivityStore();
    return {
      startTool: (toolName: string, title: string, inputPreview: string, runtime: string): string =>
        store.startTool(toolName, title, inputPreview, runtime),
      complete: (toolCallId: string, outputPreview: string): void => {
        store.complete(toolCallId, outputPreview);
      },
      fail: (toolCallId: string, error: Error): void => {
        store.fail(toolCallId, error);
      },
    };
  }

  // ChatService.kt:2332-2343 — 终表每工具 execute 包 withConversation(scopeId)
  private scopedToConversation(tools: AgentTool[], conv: Conversation): AgentTool[] {
    const scopeId: string = conv.id;
    const store: AgentToolActivityStore = getToolActivityStore();
    return tools.map((tool: AgentTool): AgentTool => makeAgentTool({
      name: tool.name,
      description: tool.description,
      parameters: tool.parameters,
      systemPrompt: tool.systemPrompt,
      needsApproval: tool.needsApproval,
      allowsAutoApproval: tool.allowsAutoApproval,
      mandatoryApproval: tool.mandatoryApproval,
      execute: (input: JsonValue): Promise<UIMessagePart[]> =>
        store.withConversation(scopeId, (): Promise<UIMessagePart[]> => tool.execute(input)),
    }));
  }

  // D-123:updateEnabledSkill(SkillsTools.kt:339-350)— settingsStore.update 对
  //   当前 assistant enabledSkills 做 +name/-name RMW;entry = assistants KV
  //   读改写(rt.assistant.id = 本次发送解析的当前助手 = getCurrentAssistant 同义)
  private async setSkillEnabled(rt: ChatRuntime, name: string, enable: boolean): Promise<void> {
    const kv = getChatKvStore();
    const list: Assistant[] = await loadAssistants(kv) ?? [];
    const next: Assistant[] = list.map((a: Assistant): Assistant => {
      if (a.id !== rt.assistant.id) return a;
      const skills: string[] = enable
        ? (a.enabledSkills.indexOf(name) >= 0 ? a.enabledSkills : a.enabledSkills.concat([name]))
        : a.enabledSkills.filter((n: string): boolean => n !== name);
      return patchAssistant(a, { enabledSkills: skills });
    });
    await saveAssistants(kv, next);
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
  private handleToolApproval(toolCallId: string, approved: boolean, reason: string = ''): void {
    const verdict: ToolApprovalVerdict = approved
      ? { kind: 'approved' }
      : { kind: 'denied', reason };
    this.applyToolVerdict(toolCallId, verdict);
  }

  // ===== D-108:拒绝理由弹窗(ToolDenyReasonDialog :428-461 + 调用点 :966-974) =====
  //   拒绝按钮 → showDenyDialog;confirm(reason) → onToolApproval(id,false,reason)
  private openDenyDialog(toolCallId: string): void {
    this.denyReasonText = '';
    this.denyDialogToolCallId = toolCallId;
  }

  private dismissDenyDialog(): void {
    this.denyDialogToolCallId = '';
    this.denyReasonText = '';
  }

  private confirmDenyDialog(): void {
    const id: string = this.denyDialogToolCallId;
    const reason: string = this.denyReasonText;
    this.denyDialogToolCallId = '';
    this.denyReasonText = '';
    if (id.length > 0) {
      this.handleToolApproval(id, false, reason);
    }
  }

  // D-105:ask_user 应答(ChatService.handleToolApproval answer 分支,:1136-1140
  //   answer 优先 → answered 态;落库 + 无 pending 则续跑,同审批路径)
  private handleToolAnswer(toolCallId: string, answer: string): void {
    this.applyToolVerdict(toolCallId, { kind: 'answered', answer });
  }

  private applyToolVerdict(toolCallId: string, verdict: ToolApprovalVerdict): void {
    if (this.conversation === null || this.sending) return;
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
        this.wireMcpAssistant(rt);
        this.warnIfToolAbilityMissing(rt);
        return runToolLoopContinuation(conv, {
          assistant: rt.assistant,
          // D-090:agentSoul 静态块首槽位(GenerationHandler.kt:721-724)
          agentSoul: buildAgentSoulPrompt(this.agentRuntime.agentSoulMarkdown),
          inputTransformers: this.buildInputTransformers(rt),
          // D-071b:视觉兜底门(模型 IMAGE 模态 + 有图才兜底,GenerationHandler.kt:431)
          modelSupportsImageInput: rt.modelSupportsImageInput,
          outputTransformers: [thinkTagTransformer, createEntryBase64ImageTransformer(), regexOutputTransformer], // ChatService.kt:179-184 序(MiniApp P1)
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          // D-077b:recentChats 动态块(每次组装重取)
          // D-085b:记忆召回块(动态序最前,GenerationHandler.kt:727)
          memorySystemBlocks: (): Promise<string[]> => this.loadMemorySystemBlocks(),
          extraSystemBlocks: (): Promise<string[]> => this.loadExtraSystemBlocks(),
          abortSignal: this.abortController?.signal,
          // D-115:GenerationHandler.kt:357/475/621 — retrySetting =
          //   settings.agentRuntime.generationRetry(续跑路径同接线)
          retrySetting: makeGenerationRetrySetting({
            enabled: this.agentRuntime.generationRetry.enabled,
            maxRetries: this.agentRuntime.generationRetry.maxRetries,
          }),
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
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        this.abortController = null;
        this.regenTail = null;
        this.regenNodeId = '';
        this.scrollToBottom();
        if (holder.rt !== null) {
          this.maybeAutoTitle(out, holder.rt);
          this.maybeSuggestions(out, holder.rt);
          this.maybeMemoryExtraction(out, holder.rt);
        }
        this.drainPendingQueue();
      })
      .catch((e: Error): void => {
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
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
  // ChatService.kt:695-700:mode 显式传入(默认 FOLLOWUP);非生成期 Android 强制
  //   FOLLOWUP — 鸿蒙入队仅在 sending 分支发生,天然等价
  private enqueuePendingParts(
    parts: UIMessagePart[], answer: boolean, mode: PendingUserMessageMode = 'FOLLOWUP'): void {
    const msg: PendingUserMessage = makePendingUserMessage({
      parts,
      answer,
      mode,
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

  // D-051/D-081:长按发送(ChatInput.kt:265-289 + ChatPage.kt:706-710):
  //   生成中 → STEER 入队(插在生成后首位)+ answer=TRUE(:708
  //   answer = loadingJob != null && queueMode == STEER — 插话被进行中的
  //   生成消费,属「已应答」;未被消费则降级 FOLLOWUP 派发时仍要生成);
  //   非生成中 → answer=false 直接 appendUserMessage(只记录不生成)
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
    // D-080:模型门(Android 长按发送 :695-698);门过才清输入(中止时输入保留)
    // D-111:门后图片守卫(ChatInput.kt:279 sendMessageWithoutAnswer);守卫失败同保留
    this.gateModelConfigured((choice: ChatProviderChoice): void => {
      this.guardImagesForSend(inputParts, choice, (): void => {
        this.inputText = '';
        this.pendingImages = [];
        if (this.editingNodeId.length > 0) this.cancelEdit();
        if (this.sending) {
          // D-081:STEER + answer=true(ChatPage.kt:708) — 插话注入进行中生成
          this.enqueuePendingParts(inputParts, true, 'STEER');
          return;
        }
        runAppendUserMessage(this.conversation as Conversation, inputParts, getChatRepository())
          .then((out: Conversation): void => {
            this.conversation = out;
            this.rows = buildRows(out);
            this.scrollToBottom();
          })
          .catch((e: Error): void => { this.errorMsg = e.message; });
      });
    });
  }

  // ===== D-080 模型空值门 =====
  // Android ChatPage.kt:532-533 canSendWithoutChatModel + :676-679/:695-698/
  //   :823-825/:864-869/:880-885 发送门 — currentChatModel == null 且非
  //   /deepread 斜杠(编辑态恒要模型)→ toast '请先选择模型'(逐字)中止:
  //   输入保留,不入队/不生成/不编辑。斜杠路由已在各路径前置拦截,无需在此判断。
  // D-111:choice 透传(图片守卫需要 chat 模型 inputModalities)
  private gateModelConfigured(onConfigured: (choice: ChatProviderChoice) => void): void {
    resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
      if (choice === null) {
        this.errorMsg = '请先选择模型';
        return;
      }
      onConfigured(choice);
    }).catch((e: Error): void => {
      this.errorMsg = e.message;
    });
  }

  // ===== D-111 图片发送守卫(ImageAttachmentValidator.kt:79-100;消费点 ChatInput.kt:250/:279) =====
  // Android:sendMessage/sendMessageWithoutAnswer 协程内 firstBlockingIssueForSend —
  //   blockingIssue != null → toast 错误中止(输入/待发附件保留);null → 放行。
  // 鸿蒙接线:模型门已通过(chat 模型空值分支不可达,Android 同文案等价);
  //   守卫失败 → errorMsg(toast 等价面);回退场景实时探测视觉健康
  //   (generateVisionText 与 OcrTransformer 同口径,OcrSupport D-071b)。
  private guardImagesForSend(
    inputParts: UIMessagePart[], choice: ChatProviderChoice, onPass: () => void,
  ): void {
    const seed: OcrSeed | null = this.ocrSeed;
    const settings: ImageGuardSettings = {
      chatModel: choice.model,
      ocrModelId: seed !== null ? seed.ocrModelId : '',
      providers: seed !== null ? seed.providers : [],
    };
    const encodeCheck = (url: string): void => { encodeImageDetailed(url); };
    const probeHealth = (): Promise<VisionModelHealth> => probeVisionModelHealth({
      findOcrModel: (): ProviderModel | null =>
        findModelByIdInProviders(settings.providers, settings.ocrModelId),
      modelSupportsImage: (m: ProviderModel): boolean => m.inputModalities.includes('image'),
      findProvider: (m: ProviderModel): ProviderSetting | null =>
        findProviderOfModel(settings.providers, m),
      generateText: (p: ProviderSetting, m: ProviderModel, msgs: UIMessage[]): Promise<string | null> =>
        generateVisionText(p, m, msgs),
    });
    firstImageBlockingIssueForSend(inputParts, settings, encodeCheck, probeHealth)
      .then((issue): void => {
        if (issue !== null) {
          this.errorMsg = issue.message;
          return;
        }
        onPass();
      })
      .catch((e: Error): void => { this.errorMsg = e.message; });
  }

  // 附件点按(ChatInputAttachments.kt:onStatusClick)— blocked 才 toast 错误,其余静默
  private inspectPendingImage(url: string): void {
    resolveChatProviderChoice().then((choice: ChatProviderChoice | null): void => {
      const seed: OcrSeed | null = this.ocrSeed;
      const settings: ImageGuardSettings = {
        chatModel: choice !== null ? choice.model : null,
        ocrModelId: seed !== null ? seed.ocrModelId : '',
        providers: seed !== null ? seed.providers : [],
      };
      const status = inspectImageAttachment(url, settings,
        (u: string): void => { encodeImageDetailed(u); });
      if (imageAttachmentBlocksSend(status)) {
        this.errorMsg = status.message;
      }
    }).catch((e: Error): void => { this.errorMsg = e.message; });
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
    // D-080:模型门(编辑态同要模型 — Android canRouteWithoutChatModel 编辑恒 false)
    // D-111:门后图片守卫(ChatInput.kt:250 sendMessage);编辑态无图片 part → 守卫直放行
    this.gateModelConfigured((choice: ChatProviderChoice): void => {
      this.guardImagesForSend(inputParts, choice, (): void => {
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
      });
    });
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
        this.wireMcpAssistant(rt);
        this.warnIfToolAbilityMissing(rt);
        const deps: ChatTurnDeps = {
          assistant: rt.assistant,
          // D-090:agentSoul 静态块首槽位(GenerationHandler.kt:721-724;
          //   buildAgentSoulPrompt 空白 → '' 自然滤除)
          agentSoul: buildAgentSoulPrompt(this.agentRuntime.agentSoulMarkdown),
          inputTransformers: this.buildInputTransformers(rt),
          // D-071b:视觉兜底门(模型 IMAGE 模态 + 有图才兜底,GenerationHandler.kt:431)
          modelSupportsImageInput: rt.modelSupportsImageInput,
          outputTransformers: [thinkTagTransformer, createEntryBase64ImageTransformer(), regexOutputTransformer], // ChatService.kt:179-184 序(MiniApp P1)
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          // D-055:压缩引擎接管上下文(engine 内部编辑/规划/截断;
          //   chat_turn 侧 contextMessageSize 强制 0,避免双重截断)
          prepareContextMessages: this.buildPrepareContext(conv, rt),
          // D-077b:recentChats 动态块(每次组装重取)
          // D-085b:记忆召回块(动态序最前,GenerationHandler.kt:727)
          memorySystemBlocks: (): Promise<string[]> => this.loadMemorySystemBlocks(),
          extraSystemBlocks: (): Promise<string[]> => this.loadExtraSystemBlocks(),
          abortSignal: this.abortController?.signal,
          // D-115:GenerationHandler.kt:357/475/621 — retrySetting =
          //   settings.agentRuntime.generationRetry(PreferencesStore.kt:181 持久化)
          retrySetting: makeGenerationRetrySetting({
            enabled: this.agentRuntime.generationRetry.enabled,
            maxRetries: this.agentRuntime.generationRetry.maxRetries,
          }),
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
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
        this.abortController = null;
        this.scrollToBottom();
        // 自动命名 + 会话建议(非阻塞,对齐 Android 独立 launch)
        if (holder.rt !== null) {
          this.maybeAutoTitle(out, holder.rt);
          this.maybeSuggestions(out, holder.rt);
          this.maybeMemoryExtraction(out, holder.rt);
        }
        // D-048:出队下一条(Android 循环:成功 → dequeueNext)
        this.drainPendingQueue();
      })
      .catch((e: Error): void => {
        this.errorMsg = this.compactErrorText(e);
        this.sending = false;
        this.refreshContextCompacts(); // D-110:压缩可能已写新摘要,刷新足迹
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

  // 当前索引(:523-527 — selected coerceIn(0,latest) ?: latest;-1 = 未选)
  private currentSandboxIndexOf(timeline: SandboxActivityUiState[]): number {
    const latest: number = timeline.length - 1;
    if (latest < 0) return -1;
    if (this.selectedSandboxIndex >= 0 && this.selectedSandboxIndex <= latest) {
      return this.selectedSandboxIndex;
    }
    return latest;
  }

  // SandboxStepStatusIcon(:763-793)— 16dp 圆:成功 accent 勾/失败红 ✕/
  //   取消 muted ✕/进行中 accent 勾(颜色为 theme 近似,登记)
  @Builder
  sandboxStatusIcon(status: string) {
    if (status === 'failed') {
      Text('✕').fontSize(9).fontColor('#FFFFFF').textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor('#E45A5A').borderRadius(8)
    } else if (status === 'cancelled') {
      Text('✕').fontSize(9).fontColor('#FFFFFF').textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(INK3).borderRadius(8)
    } else {
      Text('✓').fontSize(9).fontColor('#FFFFFF').textAlign(TextAlign.Center)
        .width(16).height(16).backgroundColor(ACCENT).borderRadius(8)
    }
  }

  // SandboxStepArrow(:738-760)— 左/右 chevron,不可用淡化
  @Builder
  sandboxArrow(left: boolean, enabled: boolean, onTap: () => void) {
    Text(left ? '‹' : '›')
      .fontSize(13)
      .fontColor(enabled ? INK : '#C9C5BA')
      .padding({ left: 4, right: 4 })
      .onClick((): void => {
        if (enabled) onTap();
      })
  }

  // SandboxStepPeek(:657-736)— 999 圆角 pill:图标+标题+中断+步骤箭头;
  //   点击开 sheet;canCancel = activity.canCancel && loading(:529)
  @Builder
  sandboxStrip(timeline: SandboxActivityUiState[]) {
    Row() {
      this.sandboxStatusIcon(this.currentSandboxActivity(timeline)?.status ?? 'running')
      Text(this.currentSandboxActivity(timeline)?.title ?? '')
        .fontSize(10.5).fontWeight(FontWeight.Medium).fontColor(INK)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .layoutWeight(1).margin({ left: 8 })
      if ((this.currentSandboxActivity(timeline)?.canCancel ?? false) && this.sending) {
        Text('✕')
          .fontSize(10).fontColor(ACCENT)
          .padding({ left: 4, right: 4 })
          .onClick((): void => {
            // onCancelClick → vm.stopGeneration(:640-642;同输入区停止按钮)
            this.abortController?.abort();
          })
      }
      this.sandboxArrow(true, this.currentSandboxIndexOf(timeline) > 0, (): void => {
        this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) - 1;
      })
      Text(this.sandboxStepText(this.currentSandboxActivity(timeline) ??
        timeline[timeline.length - 1]))
        .fontSize(9.5).fontColor(INK3).maxLines(1)
      this.sandboxArrow(false,
        this.currentSandboxIndexOf(timeline) >= 0 &&
        this.currentSandboxIndexOf(timeline) < timeline.length - 1,
        (): void => {
          this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) + 1;
        })
    }
    .width('100%')
    .padding({ left: 6, right: 10, top: 3, bottom: 3 })
    .backgroundColor(SURFACE)
    .borderRadius(999)
    .border({ width: 1, color: LINE })
    .margin({ left: 16, right: 16, bottom: 4 })
    .onClick((): void => { this.sandboxSheetOpen = true; })
  }

  // SandboxActivitySheet(:1113+;web 预览路径 = webview 子系统未移植 P1)
  //   头部 :796-870 + 工具内容 :873-899 + 代码块 :1070-1110
  @Builder
  sandboxSheet(timeline: SandboxActivityUiState[]) {
    Column() {
      // 头部
      Row() {
        this.sandboxStatusIcon(this.currentSandboxActivity(timeline)?.status ?? 'running')
        Column() {
          Text(this.currentSandboxActivity(timeline)?.title ?? '')
            .fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.currentSandboxActivity(timeline);
            if (a === null) return '';
            return a.runtime.trim().length > 0 ? a.runtime : a.toolName;
          })())
            .fontSize(11).fontColor(INK3).margin({ top: 2 })
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 10 })
        if ((this.currentSandboxActivity(timeline)?.canCancel ?? false) && this.sending) {
          Text('中断')
            .fontSize(11).fontColor(ACCENT)
            .padding({ left: 10, right: 10, top: 6, bottom: 6 })
            .backgroundColor(ROW)
            .borderRadius(999)
            .onClick((): void => { this.abortController?.abort(); })
        }
        this.sandboxArrow(true, this.currentSandboxIndexOf(timeline) > 0, (): void => {
          this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) - 1;
        })
        Text(this.sandboxStepText(this.currentSandboxActivity(timeline) ??
          timeline[timeline.length - 1]))
          .fontSize(13).fontColor(INK3).maxLines(1)
        this.sandboxArrow(false,
          this.currentSandboxIndexOf(timeline) >= 0 &&
          this.currentSandboxIndexOf(timeline) < timeline.length - 1,
          (): void => {
            this.selectedSandboxIndex = this.currentSandboxIndexOf(timeline) + 1;
          })
      }
      .width('100%').padding({ left: 12, right: 12, top: 9, bottom: 9 })
      .backgroundColor(SURFACE).borderRadius(22)
      .border({ width: 1, color: LINE })

      // 内容(:873-899)— 调用内容/调用结果两块
      Scroll() {
        Column() {
          Text('调用内容')
            .fontSize(11).fontColor(INK3).width('100%')
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.currentSandboxActivity(timeline);
            if (a === null) return '';
            return a.inputPreview.trim().length > 0 ? a.inputPreview : a.title;
          })())
            .fontSize(12).fontColor(INK).fontFamily('monospace')
            .width('100%').margin({ top: 4 })
            .padding(10).backgroundColor(ROW).borderRadius(10)
          Text('调用结果')
            .fontSize(11).fontColor(INK3).width('100%').margin({ top: 12 })
          Text(((): string => {
            const a: SandboxActivityUiState | null = this.currentSandboxActivity(timeline);
            if (a === null) return '';
            if (a.outputTail.trim().length > 0) return a.outputTail;
            if (a.status === 'running' || a.status === 'waiting_for_permission') {
              return '等待工具返回输出...';
            }
            return '无输出';
          })())
            .fontSize(12).fontColor(INK).fontFamily('monospace')
            .width('100%').margin({ top: 4 })
            .padding(10).backgroundColor(ROW).borderRadius(10)
        }
        .width('100%').alignItems(HorizontalAlign.Start)
      }
      .width('100%').layoutWeight(1).margin({ top: 12 })
    }
    .width('100%').height('70%').padding(16)
  }

  build() {
    Stack() {
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
              onDenyTool: (toolCallId: string): void => this.openDenyDialog(toolCallId),
              onAnswerTool: (toolCallId: string, answer: string): void =>
                this.handleToolAnswer(toolCallId, answer),
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
                //   长按 = 直接发送(D-042);生成中 → FOLLOWUP 入队(D-048);
                //   D-080:模型空值门(:823/:864/:880,建议文本非斜杠恒要模型)
                .onClick((): void => { this.inputText = s; })
                .gesture(
                  LongPressGesture()
                    .onAction((): void => {
                      if (this.sending) {
                        this.gateModelConfigured((_choice: ChatProviderChoice): void => {
                          this.enqueuePendingParts([{ type: 'text', text: s, metadata: null }], true);
                        });
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

      // D-038:待发图片预览(可移除);D-111:点按 = inspectImage 状态检查,
      //   blocked → errorMsg(ChatInputAttachments.kt:onStatusClick,toast 等价面)
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
              .onClick((): void => { this.inspectPendingImage(url); })
            }, (url: string): string => url)
          }
          .padding({ left: 16, right: 16 })
        }
        .scrollable(ScrollDirection.Horizontal)
        .scrollBar(BarState.Off)
        .width('100%')
        .margin({ bottom: 6 })
      }

      // D-122:沙盒活动 peek(ChatInputSandbox.kt SandboxStepPeek)—
      //   时间线非空才显示(:516-522 模式门已在 sandboxTimeline 内)
      if (this.sandboxTimeline().length > 0) {
        this.sandboxStrip(this.sandboxTimeline())
      }

      // D-110:ContextUsageIndicator(:127-161)— 10dp 环(轨道 faint 0.24 +
      //   弧 ringColor,ProgressType.Ring 适配)+ 10sp 文案(muted 0.72)
      Row() {
        Progress({ value: this.contextUsageRatio() * 100, total: 100, type: ProgressType.Ring })
          .width(10).height(10)
          .color(this.contextRingColor())
          .backgroundColor('#3DB6B1A4')
          .style({ strokeWidth: 1.6 })
        Text(this.contextUsageText())
          .fontSize(10).fontColor('#B88F8B80')
          .maxLines(1)
          .margin({ left: 4 })
      }
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 2 })

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

    // ===== D-108:拒绝理由弹窗(ToolDenyReasonDialog :428-461 逐字) =====
    //   title '拒绝工具执行'/hint '原因（可选）'/confirm '拒绝'/dismiss '取消'
    //   (strings.xml:204/:203/:202);AlertDialog → Stack overlay(同 D-101 先例)
    if (this.denyDialogToolCallId.length > 0) {
      Column() {
        Column() {
          Text('拒绝工具执行')
            .fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK)
            .width('100%').margin({ bottom: 10 })
          TextArea({ text: this.denyReasonText, placeholder: '原因（可选）' })
            .width('100%').height(88).fontSize(13).fontColor(INK)
            .backgroundColor(SURFACE2).borderRadius(10)
          Row() {
            Blank()
            Text('取消')
              .fontSize(14).fontColor(INK3)
              .padding({ left: 20, right: 20, top: 8, bottom: 8 })
              .onClick((): void => { this.dismissDenyDialog(); })
            Text('拒绝')
              .fontSize(14).fontColor('#B43A3A')
              .padding({ left: 20, right: 8, top: 8, bottom: 8 })
              .onClick((): void => { this.confirmDenyDialog(); })
          }
          .width('100%').margin({ top: 12 })
        }
        .width('86%')
        .padding(18)
        .backgroundColor(SURFACE)
        .borderRadius(16)
        .border({ width: 1, color: LINE })
        // 消费点击,防穿透到遮罩 onClick(遮罩点击 = onDismissRequest)
        .onClick((): void => {})
      }
      .width('100%').height('100%')
      .justifyContent(FlexAlign.Center)
      .backgroundColor('#66000000')
      .onClick((): void => { this.dismissDenyDialog(); })
    }
    }
    .width('100%').height('100%')
    // D-122:沙盒活动 sheet(sandboxOverlayOpen → sandboxSheetOpen)
    .bindSheet(this.sandboxSheetOpen, this.sandboxSheet(this.sandboxTimeline()), {
      height: SheetSize.LARGE,
      onDisappear: (): void => { this.sandboxSheetOpen = false; },
    })
  }
}

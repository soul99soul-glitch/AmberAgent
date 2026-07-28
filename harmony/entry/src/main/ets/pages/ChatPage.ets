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
  PendingUserMessage, DequeueNextResult,
} from '@amber/chat-domain';
import {
  currentMessages, makeConversation, newId, runChatTurn,
  createTemplateTransformer, thinkTagTransformer, regexOutputTransformer,
  asChatStreamProvider, seedConversationWithPresets,
  nodeCurrentMessage, nodeRole, selectMessageBranch, runRegenerateAt, deleteMessage, editMessage,
  forkConversation, patchConversation,
  runAutoTitle, runSuggestion, makeUserMessage, toText, makeTextGenerationParams,
  applyVisualTransformersStreamingTail,
  makePendingUserMessage, enqueuePendingUserMessage, dequeueNextPendingUserMessage,
  cancelPendingUserMessage, preparePendingMessageForDispatch, pendingPreviewText,
} from '@amber/chat-domain';
import {
  getChatRepository, getChatAssistants, resolveChatRuntime, describeChatModel, ChatRuntime,
} from '../di/AppContainer.ets';
import { readImageAsDataUrl } from '../platform_impl/EntryImageEncoder.ets';
import { EntryAbortController } from '../platform_impl/EntryAbortController.ets';

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
  onSwitch: (idx: number) => void = (_idx: number): void => {};
  onRegenerate: () => void = (): void => {};
  onDelete: () => void = (): void => {};
  onEdit: () => void = (): void => {};
  onFork: () => void = (): void => {};

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
          Text(p.reasoning)
            .fontSize(12).fontColor(INK2)
            .padding(8)
            .backgroundColor(SURFACE2)
            .borderRadius(8)
            .margin({ bottom: 4 })
        } else if (p.type === 'tool') {
          Text(`工具:${p.toolName}`)
            .fontSize(11).fontColor(ACCENT)
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
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
  // D-048:发送排队(FOLLOWUP;内存态 — Android PendingMessageStore 持久化裁 P1)
  @State pendingRows: PendingChipRow[] = [];
  private pendingQueue: PendingUserMessage[] = [];
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
        return runRegenerateAt(conv, nodeId, {
          assistant: rt.assistant,
          inputTransformers: [createTemplateTransformer()],
          outputTransformers: [thinkTagTransformer, regexOutputTransformer],
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          abortSignal: this.abortController?.signal,
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
        });
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
        this.errorMsg = e.message;
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
    const fork: Conversation = forkConversation(this.conversation, row.message.id);
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
        },
      },
    });
  }

  // ===== D-048 发送排队(Android ChatService.sendMessage:687-732 / launchPendingMessageLoop:801-867) =====

  private syncPendingRows(): void {
    this.pendingRows = this.pendingQueue.map(
      (m: PendingUserMessage): PendingChipRow => ({ id: m.id, preview: pendingPreviewText(m) }));
  }

  // 生成中发送 → FOLLOWUP 入队;满 20 拒绝(Android 文案逐字)
  private enqueuePendingParts(parts: UIMessagePart[]): void {
    const msg: PendingUserMessage = makePendingUserMessage({
      parts, answer: true, mode: 'FOLLOWUP',
    });
    const r = enqueuePendingUserMessage(this.pendingQueue, msg);
    if (!r.accepted) {
      this.errorMsg = '消息队列已满，请先等待或取消一些排队消息。';
      return;
    }
    this.pendingQueue = r.messages;
    this.syncPendingRows();
  }

  private cancelPendingById(id: string): void {
    const r = cancelPendingUserMessage(this.pendingQueue, id);
    if (r.changed) {
      this.pendingQueue = r.messages;
      this.syncPendingRows();
    }
  }

  // 生成完成(成功/失败/中断)后出队下一条继续派发(Android 循环逐条 catch 后继续)
  private drainPendingQueue(): void {
    if (this.sending || this.conversation === null) return;
    const dq: DequeueNextResult = dequeueNextPendingUserMessage(this.pendingQueue);
    if (dq.next === null) return;
    const prep = preparePendingMessageForDispatch(dq.rest, dq.next);
    this.pendingQueue = prep.rest;
    this.syncPendingRows();
    this.sendParts(prep.dispatch.parts);
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
    // D-048:生成中 → FOLLOWUP 入队(编辑态同 — 编辑重发需等当前生成结束;
    //   入队即退出编辑态,排队消息按新消息派发,登记偏差:Android 无编辑态排队交互)
    if (this.sending) {
      this.enqueuePendingParts(inputParts);
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

  private sendParts(inputParts: UIMessagePart[]): void {
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
    // holder 模式:跨 then 传递 runtime(ArkTS 闭包收窄限制)
    const holder: RuntimeHolder = { rt: null };
    resolveChatRuntime(conv.assistantId)
      .then((rt: ChatRuntime | null): Promise<Conversation> => {
        holder.rt = rt;
        if (rt === null) {
          return Promise.reject(new Error('未配置可用的 Provider/模型(providers 未持久化或无可用 auth)'));
        }
        return runChatTurn(conv, userInput, {
          assistant: rt.assistant,
          inputTransformers: [createTemplateTransformer()],
          outputTransformers: [thinkTagTransformer, regexOutputTransformer],
          provider: asChatStreamProvider(rt.api, () => rt.params),
          store: getChatRepository(),
          contextMessageSize: rt.contextMessageSize,
          abortSignal: this.abortController?.signal,
          onUpdate: (msgs: UIMessage[]): void => {
            this.streamingTail = msgs.slice(tailStart);
          },
        });
      })
      .then((out: Conversation): void => {
        this.conversation = out;
        this.rows = buildRows(out);
        this.streamingTail = [];
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
        this.errorMsg = e.message;
        this.sending = false;
        this.abortController = null;
        this.streamingTail = [];
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
            })
          }
          // D-041:regenerate 流式预览渲染在目标节点行下
          if (row.nodeId === this.regenNodeId && this.regenTail !== null) {
            ListItem() {
              MessageRow({ message: this.regenTail as UIMessage })
            }
          }
        }, (row: MsgRow): string => row.nodeId)
        ForEach(this.streamingTail, (m: UIMessage): void => {
          ListItem() {
            MessageRow({ message: m })
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
                        this.enqueuePendingParts([{ type: 'text', text: s, metadata: null }]);
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
            } else {
              this.send();
            }
          })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 8, bottom: 12 })
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

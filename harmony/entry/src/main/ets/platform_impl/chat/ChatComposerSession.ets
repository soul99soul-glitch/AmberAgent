import { promptAction } from '@kit.ArkUI';
import { picker } from '@kit.CoreFileKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { util } from '@kit.ArkTS';
import type { common } from '@kit.AbilityKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Conversation, UIMessage, UIMessagePart, PendingUserMessage, PendingUserMessageMode, KeyValueStore, ChatTimelineRow, ImageGuardSettings, VisionModelHealth, DeepReadOpenEvent, MentionRoleItem, JevCompletionNotice, JevCompletionFacts, JevVerificationComposerAction } from '@amber/chat-domain';
import { currentMessages, toText, loadSearchPrefs, SearchSettings, firstImageBlockingIssueForSend, inspectImageAttachment, imageAttachmentBlocksSend, parseDeepReadSlashCommand, appendSmartUiContinuation, isExplicitSmartUiContinuation, appendSmartUiDraft, jevUnverifiedChanges, jevVerificationComposerAction, pendingPreviewText } from '@amber/chat-domain';
import type { ChatProviderChoice } from '../../di/ChatRuntimeContracts.ets';
import type { ConversationRunService, ConversationRunCommand, ConversationRunSnapshot, ConversationEditCommand, ConversationSendCommand } from '../ConversationRunService.ets';
import type { SmartUiContinuationCapture } from './ChatSmartUiSession.ets';
import type { ChatCloudDialogueSession } from './ChatCloudDialogueSession.ets';
import type { OcrSeed } from '../OcrSupport.ets';
import type { QuickMessage } from '../../components/SlashCommandPanel.ets';
import { compressImageAsDataUrl } from '../ImageCompress.ets';
import { encodeImageDetailed } from '../EntryImageEncoder.ets';
import { sha256HexUtf8 } from '../EntryLocalToolPorts.ets';
import { mimeByName, readUriBytes, loadAttachDataUrl } from './ChatAttachmentIO.ets';
type MsgRow = ChatTimelineRow;
// 待发文件附件(选取时一次性读入并转 data: URL)
export interface PendingDoc {
  url: string;
  fileName: string;
  mime: string;
}

// D-048:排队消息 chip 视图模型
export interface PendingChipRow {
  id: string;
  preview: string;
}

const QUICK_MESSAGES_KEY: string = 'quick_messages';

function decodeQuickMessages(raw: string | null): QuickMessage[] {
  if (raw === null) return [];
  try {
    const parsed: QuickMessage[] = JSON.parse(raw) as QuickMessage[];
    if (!Array.isArray(parsed)) return [];
    const out: QuickMessage[] = [];
    for (const item of parsed) {
      if (typeof item.id !== 'string' || typeof item.title !== 'string' || typeof item.content !== 'string') {
        continue;
      }
      if (item.content.trim().length === 0) continue;
      out.push({ id: item.id, title: item.title, content: item.content });
    }
    return out;
  } catch (_e) {
    return [];
  }
}

export interface ChatComposerPorts {
  kvStore: KeyValueStore;
  runService: ConversationRunService;
  currentConversation: () => Conversation | null;
  isSending: () => boolean;
  currentQueue: () => PendingUserMessage[];
  currentCompletionNotice: () => JevCompletionNotice | null;
  setCompletionNotice: (notice: JevCompletionNotice | null) => void;
  error: (message: string) => void;
  resolveChoice: (assistantId?: string) => Promise<ChatProviderChoice | null>;
  loadOcrSeed: () => Promise<OcrSeed>;
  probeOcrHealth: (seed: OcrSeed) => Promise<VisionModelHealth>;
  submit: (command: ConversationRunCommand) => boolean;
  dismissKeyboard: () => void;
  autoScrollEnabled: () => boolean;
  jumpToBottom: () => void;
  openDeepRead: (event: DeepReadOpenEvent) => void;
  captureContinuation: (text: string) => SmartUiContinuationCapture | null;
  continuationAvailable: () => boolean;
  continuationCurrent: (capture: SmartUiContinuationCapture | null) => boolean;
  loadMentionRoles: () => Promise<MentionRoleItem[]>;
  abilityContext: () => common.UIAbilityContext;
  cloudDialogue?: () => ChatCloudDialogueSession | null;
}

@Observed
export class ChatComposerSession {
  inputText: string = '';
  pendingImages: string[] = [];
  pendingDocs: PendingDoc[] = [];
  editingNodeId: string = '';
  editingMessageId: string = '';
  materializingImages: boolean = false;
  pickerToken: number = 0;
  sendPrechecking: boolean = false;
  sendPreflightSeq: number = 0;
  draftSaveTimer: number = -1;
  initialImagePickerConsumed: boolean = false;
  quickMessages: QuickMessage[] = [];
  searchSettings: SearchSettings | null = null;
  mentionRoles: MentionRoleItem[] = [];
  quickMessagesSeq: number = 0;
  searchSettingsSeq: number = 0;
  subAgentEnabled: boolean = false;
  subAgentMode: string = 'roster';
  imageGenConfigured: boolean = false;
  ocrSeed: OcrSeed | null = null;
  pendingRows: PendingChipRow[] = [];
  queueSheetOpen: boolean = false;
  private ports: ChatComposerPorts | null = null;
  private pageAlive: boolean = false;
  private pageVisible: boolean = false;
  private get deps(): ChatComposerPorts { return this.ports as ChatComposerPorts; }
  private get conversation(): Conversation | null { return this.deps.currentConversation(); }
  private get sending(): boolean { return this.deps.isSending(); }
  private get pendingQueue(): PendingUserMessage[] { return this.deps.currentQueue(); }
  private get completionNotice(): JevCompletionNotice | null { return this.deps.currentCompletionNotice(); }
  private set completionNotice(value: JevCompletionNotice | null) { this.deps.setCompletionNotice(value); }
  private set errorMsg(value: string) { this.deps.error(value); }
  start(ports: ChatComposerPorts): void { this.ports = ports; this.pageAlive = true; this.pageVisible = true; }
  show(): void { this.pageVisible = true; }
  hide(): void { this.pageVisible = false; this.sendPreflightSeq++; this.sendPrechecking = false; this.flushInputDraft(); this.queueSheetOpen = false; }
  dispose(): void { this.hide(); this.pageAlive = false; this.invalidatePicker(true); }
  setInputText(value: string): void { this.inputText = value; this.onInputTextDraftChange(); }
  appendSmartUiDraft(draft: string): void { this.setInputText(appendSmartUiDraft(this.inputText, draft)); }

  inputDraftKey(): string {
    const conv: Conversation | null = this.conversation;
    if (conv === null || conv.messageNodes.length === 0) return 'chat_input_draft/__unsaved__';
    return `chat_input_draft/${conv.id}`;
  }

  onInputTextDraftChange(): void {
    if (this.draftSaveTimer !== -1) clearTimeout(this.draftSaveTimer);
    this.draftSaveTimer = setTimeout((): void => {
      this.draftSaveTimer = -1;
      this.persistInputDraft();
    }, 600);
  }

  persistInputDraft(): void {
    if (this.conversation === null) return;
    this.deps.kvStore.put(this.inputDraftKey(), this.inputText).catch((): void => {});
    if (this.inputText.length === 0) {
      this.deps.kvStore.put('chat_input_draft/__unsaved__', '').catch((): void => {});
    }
  }

  flushInputDraft(): void {
    if (this.draftSaveTimer === -1) return;
    clearTimeout(this.draftSaveTimer);
    this.draftSaveTimer = -1;
    this.persistInputDraft();
  }

  restoreInputDraft(key: string): void {
    this.deps.kvStore.get(key)
      .then((raw: string | null): void => {
        if (!this.pageAlive || raw === null || raw.length === 0) return;
        // 页面已有更新的输入(如 initialText 直达发送)时不覆盖
        if (this.inputText.length > 0) return;
        this.setInputText(raw);
      })
      .catch((): void => {});
  }

  refreshQuickMessages(): void {
    const seq: number = ++this.quickMessagesSeq;
    this.deps.kvStore.get(QUICK_MESSAGES_KEY).then((raw: string | null): void => {
      if (!this.pageAlive || seq !== this.quickMessagesSeq) return;
      this.quickMessages = decodeQuickMessages(raw);
    }).catch((): void => {
      if (this.pageAlive && seq === this.quickMessagesSeq) this.quickMessages = [];
    });
  }

  refreshSearchSettings(): void {
    const seq: number = ++this.searchSettingsSeq;
    loadSearchPrefs(this.deps.kvStore).then((s: SearchSettings): void => {
      if (!this.pageAlive || seq !== this.searchSettingsSeq) return;
      this.searchSettings = s;
    }).catch((): void => {});
  }

  startEditMessage(row: MsgRow): void {
    if (this.conversation === null || this.sending) return;
    this.editingNodeId = row.nodeId;
    this.editingMessageId = row.message.id;
    this.setInputText(toText(row.message));
  }

  cancelEdit(): void {
    this.editingNodeId = '';
    this.editingMessageId = '';
    this.setInputText('');
  }

  pickImages(): void {
    if (this.materializingImages) return;
    const token: number = ++this.pickerToken;
    const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
    options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
    options.maxSelectNumber = 3;
    const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
    picker.select(options).then(async (result: photoAccessHelper.PhotoSelectResult): Promise<void> => {
      if (token !== this.pickerToken) return;
      const uris: string[] = result.photoUris;
      this.materializingImages = true;
      try {
        const dataUrls: string[] = [];
        let failed: number = 0;
        for (const uri of uris) {
          if (token !== this.pickerToken) return;
          try { dataUrls.push(await compressImageAsDataUrl(uri)); }
          catch (_e) { failed++; }
        }
        if (token === this.pickerToken) {
          this.pendingImages = [...this.pendingImages, ...dataUrls];
          if (failed > 0) this.errorMsg = `${failed} 张图片读取失败，其他图片已添加`;
        }
      } catch (e) {
        if (token === this.pickerToken) {
          const err: Error = e as Error;
          hilog.warn(0x0001, 'ChatPage', `图片读取失败: ${err.message}`);
          this.errorMsg = `图片读取失败: ${err.message}`;
        }
      } finally {
        if (token === this.pickerToken) this.materializingImages = false;
      }
    }).catch((e: Error): void => {
      if (token === this.pickerToken) {
        hilog.info(0x0001, 'ChatPage', `图片选择结束: ${e.message}`);
        this.materializingImages = false;
      }
    });
  }

  invalidatePicker(clearImages: boolean = false): void {
    this.pickerToken++;
    this.materializingImages = false;
    if (clearImages) {
      this.pendingImages = [];
      this.pendingDocs = [];
    }
  }

  removePendingImage(index: number): void {
    const next: string[] = [...this.pendingImages];
    next.splice(index, 1);
    this.pendingImages = next;
  }

  removePendingDoc(index: number): void {
    const next: PendingDoc[] = [...this.pendingDocs];
    next.splice(index, 1);
    this.pendingDocs = next;
  }

  async takePhoto(): Promise<void> {
    if (this.materializingImages) return;
    const token: number = ++this.pickerToken;
    try {
      const ctx: common.UIAbilityContext = this.deps.abilityContext();
      const profile: cameraPicker.PickerProfile = {
        cameraPosition: camera.CameraPosition.CAMERA_POSITION_BACK,
      };
      const result: cameraPicker.PickerResult = await cameraPicker.pick(
        ctx, [cameraPicker.PickerMediaType.PHOTO], profile);
      if (token !== this.pickerToken) return;
      if (result.resultCode === 0 && result.resultUri.length > 0) {
        this.materializingImages = true;
        const dataUrl: string = await compressImageAsDataUrl(result.resultUri);
        if (token === this.pickerToken) this.pendingImages = [...this.pendingImages, dataUrl];
      }
    } catch (error) {
      if (token === this.pickerToken) this.errorMsg = `拍照未完成：${error instanceof Error ? error.message : String(error)}`;
    } finally {
      if (token === this.pickerToken) this.materializingImages = false;
    }
  }

  async pickFiles(): Promise<void> {
    const token: number = ++this.pickerToken;
    let uris: string[];
    try {
      const docPicker = new picker.DocumentViewPicker();
      const options: picker.DocumentSelectOptions =
        new picker.DocumentSelectOptions();
      options.maxSelectNumber = 3;
      uris = await docPicker.select(options);
    } catch (_e) {
      // 选择器取消，不影响当前附件和错误提示。
      return;
    }
    if (token !== this.pickerToken) return;
    const failures: string[] = [];
    for (const uri of uris) {
      let fileName: string = 'file';
      try {
        const raw: string = uri.substring(uri.lastIndexOf('/') + 1);
        fileName = decodeURIComponent(raw.split('?')[0]) || 'file';
        const bytes: Uint8Array = readUriBytes(uri);
        const b64: string = new util.Base64Helper().encodeToStringSync(bytes);
        const mime: string = mimeByName(fileName);
        this.pendingDocs = [...this.pendingDocs,
          { url: `data:${mime};base64,${b64}`, fileName: fileName, mime: mime } as PendingDoc];
      } catch (error) {
        failures.push(`${fileName}：${error instanceof Error ? error.message : String(error)}`);
      }
    }
    if (failures.length > 0) this.errorMsg = `文件读取失败：${failures.join('；')}`;
  }

  attachPastedText(text: string): void {
    const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(text);
    const b64: string = new util.Base64Helper().encodeToStringSync(bytes);
    const fileName: string = `paste-${Date.now()}.txt`;
    this.pendingDocs = [...this.pendingDocs,
      { url: `data:text/plain;base64,${b64}`, fileName: fileName, mime: 'text/plain' } as PendingDoc];
    promptAction.showToast({ message: `已作为文件附件(${text.length} 字符)`, duration: 1800 });
  }

  modifyGeneratedImage(url: string): void {
    loadAttachDataUrl(url).then((dataUrl: string | null): void => {
      if (!this.pageAlive) return;
      if (dataUrl === null) {
        promptAction.showToast({ message: '读取图片失败,无法修改', duration: 1800 });
        return;
      }
      this.pendingImages = [...this.pendingImages, dataUrl];
      this.setInputText('修改这张图片：');
    }).catch((): void => {});
  }

  sendEdit(text: string): boolean {
    if (!this.pageAlive || this.conversation === null || this.sending) return false;
    const nodeId: string = this.editingNodeId;
    const messageId: string = this.editingMessageId;
    const command: ConversationEditCommand = { kind: 'edit', nodeId: nodeId, messageId: messageId, text: text };
    if (this.deps.submit(command)) {
      this.editingNodeId = ''; this.editingMessageId = '';
      return true;
    }
    return false;
  }

  syncPendingRows(): void {
    this.pendingRows = this.pendingQueue.map(
      (m: PendingUserMessage): PendingChipRow => ({ id: m.id, preview: pendingPreviewText(m) }));
  }

  restorePendingMessage(id: string): void {
    const owner: string = this.conversation?.id ?? '';
    if (owner.length === 0) return;
    this.deps.runService.takePending(owner, id).then((pending: PendingUserMessage | null): void => {
      if (pending === null || !this.pageAlive || this.conversation?.id !== owner) return;
      const texts: string[] = [];
      for (const part of pending.parts) {
        if (part.type === 'text') texts.push(part.text);
        else if (part.type === 'image') this.pendingImages = [...this.pendingImages, part.url];
        else if (part.type === 'document') this.pendingDocs = [...this.pendingDocs,
          { url: part.url, fileName: part.fileName, mime: part.mime } as PendingDoc];
      }
      this.setInputText([this.inputText, texts.join('\n')].filter((text: string): boolean => text.length > 0).join('\n'));
      this.queueSheetOpen = false;
    }).catch((error: Error): void => { if (this.pageAlive && this.conversation?.id === owner) this.errorMsg = error.message; });
  }

  cancelPendingById(id: string): Promise<void> {
    if (this.conversation === null) return Promise.resolve();
    return this.deps.runService.cancelPending(this.conversation.id, id);
  }

  drainPendingQueue(): void {
    if (this.conversation === null) return;
    const id: string = this.conversation.id;
    this.deps.runService.resumePending(id).catch((error: Error): void => {
      if (this.pageAlive && this.conversation?.id === id) this.errorMsg = error.message;
    });
  }

  enqueuePendingParts(
    parts: UIMessagePart[], answer: boolean, mode: PendingUserMessageMode = 'FOLLOWUP'): Promise<void> {
    if (this.conversation === null) return Promise.reject(new Error('会话不存在'));
    return this.deps.runService.enqueue(this.conversation.id, parts, answer, mode);
  }

  tryRouteDeepReadSlash(inputParts: UIMessagePart[]): boolean {
    const event: DeepReadOpenEvent | null = parseDeepReadSlashCommand(inputParts, sha256HexUtf8);
    if (event === null) return false;
    this.setInputText('');
    this.invalidatePicker(true);
    this.deps.openDeepRead(event);
    return true;
  }

  async sendWithoutAnswer(): Promise<void> {
    if (this.materializingImages || this.sendPrechecking) return;
    const text: string = this.inputText.trim();
    if ((text.length === 0 && this.pendingImages.length === 0 && this.pendingDocs.length === 0) ||
      this.conversation === null) return;
    const acceptedText: string = this.inputText;
    const acceptedImages: string[] = this.pendingImages.slice();
    const acceptedDocs: PendingDoc[] = this.pendingDocs.slice();
    const owner: string = this.conversation.id;
    const editingNodeId: string = this.editingNodeId;
    const editingMessageId: string = this.editingMessageId;
    const continuation: SmartUiContinuationCapture | null = editingNodeId.length === 0
      ? this.deps.captureContinuation(text) : null;
    if (editingNodeId.length === 0 && isExplicitSmartUiContinuation(text) &&
      this.deps.continuationAvailable() && continuation === null) {
      this.errorMsg = '当前选择不完整或内容过长，请检查智能 UI 后再继续';
      return;
    }
    const sendText: string = appendSmartUiContinuation(text, continuation?.snapshot ?? null);
    const inputParts: UIMessagePart[] = [];
    if (sendText.length > 0) inputParts.push({ type: 'text', text: sendText, metadata: null });
    for (const url of acceptedImages) inputParts.push({ type: 'image', url, metadata: null });
    for (const doc of acceptedDocs) inputParts.push({ type: 'document', url: doc.url,
      fileName: doc.fileName, mime: doc.mime, metadata: null });
    if (editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    const cloud: ChatCloudDialogueSession | null = this.deps.cloudDialogue !== undefined ? this.deps.cloudDialogue() : null;
    if (editingNodeId.length === 0 && cloud !== null && cloud.isActiveMode()) {
      if (acceptedImages.length > 0 || acceptedDocs.length > 0) {
        this.errorMsg = '云主对话暂不支持图片或文件附件';
        return;
      }
      this.deps.dismissKeyboard();
      const sent: boolean = await cloud.submitComposerText(sendText);
      if (sent && this.pageAlive && this.conversation?.id === owner) {
        this.clearAcceptedComposer(acceptedText, acceptedImages, acceptedDocs);
      }
      return;
    }
    this.sendPrechecking = true;
    const sequence: number = ++this.sendPreflightSeq;
    try {
      if (!await this.prepareComposerSend(inputParts, owner, sequence) || this.editingNodeId !== editingNodeId ||
        this.editingMessageId !== editingMessageId || !this.deps.continuationCurrent(continuation)) return;
      this.deps.dismissKeyboard();
      if (this.sending) await this.enqueuePendingParts(inputParts, true, 'STEER');
      else if (this.deps.runService.isBusy(owner)) await this.enqueuePendingParts(inputParts, false);
      else await this.deps.runService.append(owner, inputParts);
      if (this.pageAlive && this.conversation?.id === owner) {
        this.clearAcceptedComposer(acceptedText, acceptedImages, acceptedDocs);
        if (editingNodeId.length > 0 && this.editingNodeId === editingNodeId && this.editingMessageId === editingMessageId) {
          this.editingNodeId = '';
          this.editingMessageId = '';
        }
      }
    } catch (error) {
      if (this.pageAlive && this.conversation?.id === owner) this.errorMsg = (error as Error).message;
    } finally {
      this.finishComposerPreflight(sequence);
    }
  }

  composerSendCurrent(owner: string, sequence: number): boolean {
    return this.pageAlive && this.pageVisible && this.conversation?.id === owner && sequence === this.sendPreflightSeq;
  }

  finishComposerPreflight(sequence: number): void {
    if (sequence === this.sendPreflightSeq) this.sendPrechecking = false;
  }

  async prepareComposerSend(inputParts: UIMessagePart[], owner: string, sequence: number): Promise<boolean> {
    try {
      const choice: ChatProviderChoice | null = await this.deps.resolveChoice(this.conversation?.assistantId);
      if (!this.composerSendCurrent(owner, sequence)) return false;
      if (choice === null) {
        this.errorMsg = '请先选择模型';
        return false;
      }
      return await this.guardImagesForSend(inputParts, choice, owner, sequence);
    } catch (error) {
      if (this.composerSendCurrent(owner, sequence)) this.errorMsg = (error as Error).message;
      return false;
    }
  }

  gateModelConfigured(onConfigured: (choice: ChatProviderChoice) => void): void {
    this.deps.resolveChoice(this.conversation?.assistantId).then((choice: ChatProviderChoice | null): void => {
      if (choice === null) {
        this.errorMsg = '请先选择模型';
        return;
      }
      onConfigured(choice);
    }).catch((e: Error): void => {
      this.errorMsg = e.message;
    });
  }

  async reloadOcrSeed(): Promise<OcrSeed | null> {
    try {
      const seed: OcrSeed = await this.deps.loadOcrSeed();
      if (this.pageAlive) this.ocrSeed = seed;
      return seed;
    } catch (e) {
      return this.ocrSeed;
    }
  }

  async guardImagesForSend(
    inputParts: UIMessagePart[], choice: ChatProviderChoice, owner: string, sequence: number,
  ): Promise<boolean> {
    const seed: OcrSeed | null = await this.reloadOcrSeed();
    if (!this.composerSendCurrent(owner, sequence)) return false;
    const settings: ImageGuardSettings = {
      chatModel: choice.model,
      ocrModelId: seed !== null ? seed.ocrModelId : '',
      providers: seed !== null ? seed.providers : [],
    };
    const encodeCheck = (url: string): void => { encodeImageDetailed(url); };
    const probeHealth = (): Promise<VisionModelHealth> => {
      if (seed === null) return Promise.reject(new Error('视觉识别配置尚未加载'));
      return this.deps.probeOcrHealth(seed);
    };
    const issue = await firstImageBlockingIssueForSend(inputParts, settings, encodeCheck, probeHealth);
    if (!this.composerSendCurrent(owner, sequence)) return false;
    if (issue !== null) {
      this.errorMsg = issue.message;
      return false;
    }
    return true;
  }

  inspectPendingImage(url: string): void {
    this.deps.resolveChoice(this.conversation?.assistantId).then(async (choice: ChatProviderChoice | null): Promise<void> => {
      const seed: OcrSeed | null = await this.reloadOcrSeed();
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

  dismissCompletionNotice(): void {
    if (this.conversation !== null && this.completionNotice !== null) {
      this.deps.runService.dismissCompletion(this.conversation.id, this.completionNotice.runId);
    }
    this.completionNotice = null;
  }

  requestCompletionVerification(): void {
    const notice: JevCompletionNotice | null = this.completionNotice;
    const conversation: Conversation | null = this.conversation;
    if (notice === null || conversation === null || this.sending || this.sendPrechecking || this.materializingImages) return;
    const snapshot: ConversationRunSnapshot | null = this.deps.runService.snapshot(conversation.id);
    const facts: JevCompletionFacts | null = jevUnverifiedChanges(currentMessages(conversation));
    if (snapshot?.completionNotice?.runId !== notice.runId || snapshot.sending || facts === null
      || facts.finalMessageId !== notice.finalMessageId || facts.finalContentFingerprint !== notice.finalContentFingerprint
      || facts.sourceFingerprint !== notice.sourceFingerprint) {
      this.dismissCompletionNotice();
      return;
    }
    const action: JevVerificationComposerAction = jevVerificationComposerAction(this.inputText,
      this.pendingImages.length > 0 || this.pendingDocs.length > 0, this.editingNodeId.length > 0);
    this.setInputText(action.text);
    this.dismissCompletionNotice();
    if (action.send) this.send();
  }

  async send(queueMode: PendingUserMessageMode = 'FOLLOWUP'): Promise<void> {
    if (this.materializingImages || this.sendPrechecking) return;
    const text: string = this.inputText.trim();
    if ((text.length === 0 && this.pendingImages.length === 0 && this.pendingDocs.length === 0) ||
      this.conversation === null) return;
    const acceptedText: string = this.inputText;
    const acceptedImages: string[] = this.pendingImages.slice();
    const acceptedDocs: PendingDoc[] = this.pendingDocs.slice();
    const owner: string = this.conversation.id;
    const editingNodeId: string = this.editingNodeId;
    const editingMessageId: string = this.editingMessageId;
    const continuation: SmartUiContinuationCapture | null = editingNodeId.length === 0
      ? this.deps.captureContinuation(text) : null;
    if (editingNodeId.length === 0 && isExplicitSmartUiContinuation(text) &&
      this.deps.continuationAvailable() && continuation === null) {
      this.errorMsg = '当前选择不完整或内容过长，请检查智能 UI 后再继续';
      return;
    }
    const sendText: string = appendSmartUiContinuation(text, continuation?.snapshot ?? null);
    const inputParts: UIMessagePart[] = [];
    if (sendText.length > 0) inputParts.push({ type: 'text', text: sendText, metadata: null });
    for (const url of acceptedImages) inputParts.push({ type: 'image', url, metadata: null });
    for (const doc of acceptedDocs) inputParts.push({ type: 'document', url: doc.url,
      fileName: doc.fileName, mime: doc.mime, metadata: null });
    if (editingNodeId.length === 0 && this.tryRouteDeepReadSlash(inputParts)) return;
    const cloud: ChatCloudDialogueSession | null = this.deps.cloudDialogue !== undefined ? this.deps.cloudDialogue() : null;
    if (editingNodeId.length === 0 && cloud !== null && cloud.isActiveMode()) {
      if (acceptedImages.length > 0 || acceptedDocs.length > 0) {
        this.errorMsg = '云主对话暂不支持图片或文件附件';
        return;
      }
      this.deps.dismissKeyboard();
      const sent: boolean = await cloud.submitComposerText(sendText);
      if (sent && this.pageAlive && this.conversation?.id === owner) {
        this.clearAcceptedComposer(acceptedText, acceptedImages, acceptedDocs);
      }
      return;
    }
    this.sendPrechecking = true;
    const sequence: number = ++this.sendPreflightSeq;
    try {
      if (!await this.prepareComposerSend(inputParts, owner, sequence) || this.editingNodeId !== editingNodeId ||
        this.editingMessageId !== editingMessageId || !this.deps.continuationCurrent(continuation)) return;
      this.deps.dismissKeyboard();
      if (this.sending) await this.enqueuePendingParts(inputParts, true, queueMode);
      else if (editingNodeId.length > 0) {
        if (!this.sendEdit(text)) return;
      } else if (!this.sendParts(inputParts)) return;
      if (this.pageAlive && this.conversation?.id === owner) {
        this.clearAcceptedComposer(acceptedText, acceptedImages, acceptedDocs);
      }
    } catch (error) {
      if (this.pageAlive && this.conversation?.id === owner) this.errorMsg = (error as Error).message;
    } finally {
      this.finishComposerPreflight(sequence);
    }
  }

  clearAcceptedComposer(text: string, images: string[], docs: PendingDoc[]): void {
    if (this.inputText === text) this.setInputText('');
    const remainingImages: string[] = this.pendingImages.slice();
    for (const url of images) {
      const index: number = remainingImages.indexOf(url);
      if (index >= 0) remainingImages.splice(index, 1);
    }
    this.pendingImages = remainingImages;
    const remainingDocs: PendingDoc[] = this.pendingDocs.slice();
    for (const doc of docs) {
      const index: number = remainingDocs.findIndex((candidate: PendingDoc): boolean =>
        candidate.url === doc.url && candidate.fileName === doc.fileName);
      if (index >= 0) remainingDocs.splice(index, 1);
    }
    this.pendingDocs = remainingDocs;
  }

  sendParts(
    inputParts: UIMessagePart[], dispatchMessageId: string = '',
  ): boolean {
    const command: ConversationSendCommand = { kind: 'send', parts: inputParts, dispatchMessageId: dispatchMessageId };
    if (!this.deps.submit(command)) return false;
    if (this.deps.autoScrollEnabled()) this.deps.jumpToBottom();
    return true;
  }

  refreshMentionRoles(): void {
    this.deps.loadMentionRoles().then((roles: MentionRoleItem[]): void => { if (this.pageAlive) this.mentionRoles = roles; }).catch((): void => {});
  }
  clearPending(): void {
    if (this.conversation === null) return;
    this.deps.runService.clearPending(this.conversation.id).catch((error: Error): void => { this.errorMsg = error.message; });
  }

  sendSuggestion(text: string): void {
    if (this.sending) {
      this.gateModelConfigured((_choice: ChatProviderChoice): void => {
        this.enqueuePendingParts([{ type: 'text', text: text, metadata: null }], true)
          .catch((error: Error): void => { this.errorMsg = error.message; });
      });
      return;
    }
    this.setInputText(text);
    this.send();
  }

}

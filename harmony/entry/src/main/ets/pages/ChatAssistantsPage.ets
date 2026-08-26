// ChatAssistantsPage — 助手列表 + MVP 编辑(D-027)
// 编辑面(D-039 补齐参数组;D-049 补齐请求自定义组):name/systemPrompt/
// temperature/topP/maxTokens/contextMessageSize/推理力度/streamOutput/
// customHeaders/customBodies;其余 22 字段保留原值
// (替换 by id 回写整个 blob,与 Provider 设置页同构)。全量编辑器 = P1。
// 参数语义(Android AssistantBasicPage.kt 忠实):topP/maxTokens 可空,
//   空白 = null(继承 provider 默认);maxTokens 必须 > 0,否则视为 null
//   (AssistantBasicPage.kt:496-501 toIntOrNull()?.takeIf { it > 0 })
// 请求自定义语义(Android PropertyEditor.kt 忠实):header name/value trim;
//   body value = JSON,逐键解析,非法 JSON 行内报错并保留旧值(:160-176)
import { ACCENT,BG,ERROR,INK,INK2,INK3,LINE,SURFACE,TITLE,GUTTER,RADIUS,BODY,BODY2,META } from '../design/tokens.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router } from '@kit.ArkUI';
import type {
  Assistant, ReasoningLevel, CustomHeader, CustomBody, JsonValue, MainAgentToolProfile,
  Conversation, ConversationSummary,
} from '@amber/chat-domain';
import {
  loadAssistants, saveAssistants, makeAssistant, patchAssistant, newId,
} from '@amber/chat-domain';
import { getChatKvStore, getChatAssistants, getChatRepository } from '../di/AppContainer.ets';
import { cleanupDeletedConversation } from '../platform_impl/ManagedFileStore.ets';

// D-107:工具范围(MainAgentToolProfile.entries 声明序;label() :98-105 逐字)
const TOOL_PROFILES: MainAgentToolProfile[] =
  ['full', 'minimal', 'web_read', 'workspace_read', 'coding', 'mobile_control'];
const TOOL_PROFILE_LABELS: string[] = ['完整', '最小', '网页只读', '文件只读', '编程', '手机控制'];

// 推理力度 7 档(provider_model.ts REASONING_LEVEL_TABLE,与 Android ReasoningLevel.entries 同序)
const REASONING_LEVELS: ReasoningLevel[] = ['auto', 'off', 'low', 'medium', 'high', 'xhigh', 'max'];
const REASONING_LABELS: string[] = ['自动', '关闭', '低', '中', '高', '超高', '最大'];


interface HeaderDraft {
  uid: string;
  name: string;
  value: string;
}

interface BodyDraft {
  uid: string;
  key: string;
  value: JsonValue;
}

@Entry
@Component
struct ChatAssistantsPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State items: Assistant[] = [];
  @State loaded: boolean = false;
  // 编辑态(editingId 非空 = 编辑该助手;= '' = 新建)
  @State editingId: string = '';
  @State editName: string = '';
  @State editSystemPrompt: string = '';
  @State editTemperature: string = '';
  @State editTopP: string = '';
  @State editMaxTokens: string = '';
  @State editContextSize: string = '';
  @State editReasoning: ReasoningLevel = 'auto';
  @State editStreamOutput: boolean = true;
  // D-107:工具范围(AssistantBasicPage.kt:243-261;makeAssistant 默认 'full')
  @State editToolProfile: MainAgentToolProfile = 'full';
  // D-049:请求自定义(PropertyEditor.kt) — headers 直接编辑;bodies 的
  //   JSON 值以文本态编辑(editBodyTexts),解析成功才落 editBodies,
  //   失败行内报错(editBodyErrors)并保留旧值(Android :160-176 语义)
  @State editHeaders: HeaderDraft[] = [];
  private draftSeq: number = 1;
  @State editBodies: BodyDraft[] = [];
  @State bodyTexts: Map<string, string> = new Map<string, string>();
  @State bodyErrors: Map<string, string> = new Map<string, string>();
  @State showEditor: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    getChatAssistants().then((list: Assistant[]): void => {
      this.items = list;
      this.loaded = true;
    });
  }

  private startEdit(a: Assistant): void {
    this.editingId = a.id;
    this.editName = a.name;
    this.editSystemPrompt = a.systemPrompt;
    this.editTemperature = a.temperature !== null ? String(a.temperature) : '';
    this.editTopP = a.topP !== null ? String(a.topP) : '';
    this.editMaxTokens = a.maxTokens !== null ? String(a.maxTokens) : '';
    this.editContextSize = a.contextMessageSize > 0 ? String(a.contextMessageSize) : '';
    this.editReasoning = a.reasoningLevel;
    this.editStreamOutput = a.streamOutput;
    this.editToolProfile = a.toolProfile;
    this.editHeaders = a.customHeaders.map(
      (h: CustomHeader): HeaderDraft =>
        ({ uid: `h${this.draftSeq++}`, name: h.name, value: h.value }));
    this.editBodies = a.customBodies.map(
      (b: CustomBody): BodyDraft => ({ uid: `b${this.draftSeq++}`, key: b.key, value: b.value }));
    this.bodyTexts.clear();
    this.bodyErrors.clear();
    for (const b of this.editBodies) {
      this.bodyTexts.set(b.uid, JSON.stringify(b.value));
      this.bodyErrors.set(b.uid, '');
    }
    this.showEditor = true;
  }

  private startCreate(): void {
    this.editingId = '';
    this.editName = '';
    this.editSystemPrompt = '';
    this.editTemperature = '';
    this.editTopP = '';
    this.editMaxTokens = '';
    this.editContextSize = '';
    this.editReasoning = 'auto';
    this.editStreamOutput = true;
    this.editToolProfile = 'full';
    this.editHeaders = [];
    this.draftSeq = 1;
    this.editBodies = [];
    this.bodyTexts.clear();
    this.bodyErrors.clear();
    this.showEditor = true;
  }

  private parseFloatOrNull(s: string): number | null {
    const t: string = s.trim();
    if (t.length === 0) return null;
    const v: number = Number(t);
    return Number.isFinite(v) ? v : null;
  }

  private parseIntOrZero(s: string): number {
    const t: string = s.trim();
    if (t.length === 0) return 0;
    const v: number = Number(t);
    return Number.isFinite(v) && v > 0 ? Math.floor(v) : 0;
  }

  // Android maxTokens 语义(AssistantBasicPage.kt:496-501):
  //   空白 → null;解析失败或 ≤ 0 → null(而不是 0)
  private parsePositiveIntOrNull(s: string): number | null {
    const t: string = s.trim();
    if (t.length === 0) return null;
    const v: number = Number(t);
    return Number.isFinite(v) && v > 0 ? Math.floor(v) : null;
  }

  // ===== D-049 请求自定义行操作(数组整体替换触发 @State 刷新) =====

  private setHeaderName(uid: string, v: string): void {
    this.editHeaders = this.editHeaders.map((h: HeaderDraft): HeaderDraft =>
      h.uid === uid ? { uid: h.uid, name: v, value: h.value } : h);
  }

  private setHeaderValue(uid: string, v: string): void {
    this.editHeaders = this.editHeaders.map((h: HeaderDraft): HeaderDraft =>
      h.uid === uid ? { uid: h.uid, name: h.name, value: v } : h);
  }

  private removeHeader(uid: string): void {
    this.editHeaders = this.editHeaders.filter((h: HeaderDraft): boolean => h.uid !== uid);
  }

  private addHeader(): void {
    // Android PropertyEditor.kt:108-113 — 追加 CustomHeader("", "")
    // uid 草稿模型:数组索引 key 在删中间行后会让 ArkUI 复用错位(输入值/组合态串行)
    this.editHeaders = [...this.editHeaders,
      { uid: `h${this.draftSeq++}`, name: '', value: '' } as HeaderDraft];
  }

  private setBodyKey(uid: string, v: string): void {
    this.editBodies = this.editBodies.map((b: BodyDraft): BodyDraft =>
      b.uid === uid ? { uid: b.uid, key: v, value: b.value } : b);
  }

  // Android :160-176 — 逐键解析;非法 JSON 行内报错(msg take 100)且保留旧值
  // 文本/错误以 uid 为键:并行数组按索引对齐在删中间行后会整体错位;
  // 每次 set 新 Map 触发 @State 刷新(原地 set 不重建)
  private setBodyValueText(uid: string, v: string): void {
    const texts: Map<string, string> = new Map<string, string>(this.bodyTexts);
    texts.set(uid, v);
    this.bodyTexts = texts;
    const errs: Map<string, string> = new Map<string, string>(this.bodyErrors);
    try {
      const parsed: JsonValue = JSON.parse(v) as JsonValue;
      this.editBodies = this.editBodies.map((b: BodyDraft): BodyDraft =>
        b.uid === uid ? { uid: b.uid, key: b.key, value: parsed } : b);
      errs.set(uid, '');
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      errs.set(uid, `无效 JSON: ${msg.substring(0, 100)}`);
    }
    this.bodyErrors = errs;
  }

  private removeBody(uid: string): void {
    this.editBodies = this.editBodies.filter((b: BodyDraft): boolean => b.uid !== uid);
    const texts: Map<string, string> = new Map<string, string>(this.bodyTexts);
    texts.delete(uid);
    this.bodyTexts = texts;
    const errs: Map<string, string> = new Map<string, string>(this.bodyErrors);
    errs.delete(uid);
    this.bodyErrors = errs;
  }

  private addBody(): void {
    // Android :213-218 — 追加 CustomBody("", JsonPrimitive("")),文本态显示 ""
    const uid: string = `b${this.draftSeq++}`;
    this.editBodies = [...this.editBodies, { uid: uid, key: '', value: '' } as BodyDraft];
    const texts: Map<string, string> = new Map<string, string>(this.bodyTexts);
    texts.set(uid, '""');
    this.bodyTexts = texts;
    const errs: Map<string, string> = new Map<string, string>(this.bodyErrors);
    errs.set(uid, '');
    this.bodyErrors = errs;
  }

  private bodyTextOf(uid: string): string {
    return this.bodyTexts.get(uid) ?? '""';
  }

  private bodyErrorOf(uid: string): string {
    return this.bodyErrors.get(uid) ?? '';
  }

  // 保存前归一:header name/value、body key trim(Android 逐键 trim 等价在保存点收敛)
  private normalizedHeaders(): CustomHeader[] {
    return this.editHeaders.map((h: HeaderDraft): CustomHeader => ({
      name: h.name.trim(), value: h.value.trim(),
    }));
  }

  private normalizedBodies(): CustomBody[] {
    return this.editBodies.map((b: CustomBody): CustomBody => ({
      key: b.key.trim(), value: b.value,
    }));
  }

  private save(): void {
    const name: string = this.editName.trim();
    if (name.length === 0) return;
    loadAssistants(getChatKvStore()).then((loaded: Assistant[] | null): void => {
      const current: Assistant[] = loaded !== null && loaded.length > 0
        ? loaded
        : this.items;
      let next: Assistant[];
      if (this.editingId.length > 0) {
        next = current.map((a: Assistant): Assistant => {
          if (a.id !== this.editingId) return a;
          // arkts-no-spread:经 chat-domain patchAssistant(= Kotlin copy 语义)
          return patchAssistant(a, {
            name,
            systemPrompt: this.editSystemPrompt,
            temperature: this.parseFloatOrNull(this.editTemperature),
            topP: this.parseFloatOrNull(this.editTopP),
            maxTokens: this.parsePositiveIntOrNull(this.editMaxTokens),
            contextMessageSize: this.parseIntOrZero(this.editContextSize),
            reasoningLevel: this.editReasoning,
            streamOutput: this.editStreamOutput,
            toolProfile: this.editToolProfile,
            customHeaders: this.normalizedHeaders(),
            customBodies: this.normalizedBodies(),
          });
        });
      } else {
        next = [...current, makeAssistant({
          name,
          systemPrompt: this.editSystemPrompt,
          temperature: this.parseFloatOrNull(this.editTemperature),
          topP: this.parseFloatOrNull(this.editTopP),
          maxTokens: this.parsePositiveIntOrNull(this.editMaxTokens),
          contextMessageSize: this.parseIntOrZero(this.editContextSize),
          reasoningLevel: this.editReasoning,
          streamOutput: this.editStreamOutput,
          toolProfile: this.editToolProfile,
          customHeaders: this.normalizedHeaders(),
          customBodies: this.normalizedBodies(),
        })];
      }
      saveAssistants(getChatKvStore(), next).then((): void => {
        this.showEditor = false;
        this.refresh();
      });
    });
  }

  // 复制(D-037):Android copyAssistant 语义 — 新 id + name " (Clone)" +
  //   avatar image → dummy(其余保留)
  private copyAssistant(a: Assistant): void {
    const copied: Assistant = patchAssistant(a, {
      id: newId(),
      name: `${a.name} (Clone)`,
      avatar: a.avatar.type === 'image' ? { type: 'dummy' } : a.avatar,
    });
    loadAssistants(getChatKvStore()).then((loaded: Assistant[] | null): void => {
      const current: Assistant[] = loaded !== null && loaded.length > 0 ? loaded : this.items;
      saveAssistants(getChatKvStore(), [...current, copied]).then((): void => this.refresh());
    });
  }

  // 删除(D-037):级联删除该助手的所有会话(Android removeAssistant 语义:
  //   deleteConversationOfAssistant);memory/files 清理无对应物(未落地,登记)
  private confirmDelete(a: Assistant): void {
    AlertDialog.show({
      title: '删除助手',
      message: `「${a.name.length > 0 ? a.name : '未命名助手'}」及其所有对话将被删除,且不可恢复。`,
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '删除',
        fontColor: 'ERROR',
        action: (): void => {
          loadAssistants(getChatKvStore()).then((loaded: Assistant[] | null): void => {
            const current: Assistant[] = loaded !== null && loaded.length > 0 ? loaded : this.items;
            const next: Assistant[] = current.filter((x: Assistant): boolean => x.id !== a.id);
            saveAssistants(getChatKvStore(), next).then((): Promise<void> => {
              // 级联删除该助手的会话
              const repo = getChatRepository();
              return repo.list(a.id).then((convs: ConversationSummary[]): Promise<void> => {
                let chain: Promise<void> = Promise.resolve();
                for (const c of convs) {
                  chain = chain.then((): Promise<void> =>
                    repo.getById(c.id).then((full: Conversation | null): Promise<void> => {
                      if (full === null) return Promise.resolve();
                      return repo.delete(c.id).then((): Promise<void> =>
                        cleanupDeletedConversation(full, repo, getChatKvStore()));
                    }));
                }
                return chain;
              });
            }).then((): void => this.refresh());
          });
        },
      },
    });
  }

  build() {
    Column() {
      Row() {
        Row() {
          Icon({ name: 'back', iconSize: 20, color: INK })
        }
        .width(44).height(44)
        .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
        .onClick((): void => { router.back(); })
        .accessibilityText('返回')
        Text('助手')
          .fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 8 }).layoutWeight(1)
        Text('提示词注入').fontSize(13).fontColor(INK3).margin({ right: 16 })
          .padding({ top: 12, bottom: 12 })
          .onClick((): void => { router.pushUrl({ url: 'pages/ChatPromptsPage' }); })
          .accessibilityText('打开提示词注入')
        Text('+ 新助手')
          .fontSize(14).fontColor(ACCENT)
          .padding({ left: 12, right: 12, top: 12, bottom: 12 })
          .constraintSize({ minHeight: 44 })
          .onClick((): void => this.startCreate())
          .accessibilityText('新建助手')
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
      .alignItems(VerticalAlign.Center)
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY2).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else if (!this.showEditor) {
        List() {
          ForEach(this.items, (a: Assistant): void => {
            ListItem() {
              Column() {
                Row() {
                  Text(a.name.length > 0 ? a.name : '未命名助手')
                    .fontSize(15).fontColor(INK)
                    .layoutWeight(1)
                    .maxLines(1)
                    .textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text('对话')
                    .fontSize(12).fontColor(ACCENT)
                    .margin({ right: 14 })
                    .onClick((): void => {
                      const params: Record<string, string> = {};
                      params['assistantId'] = a.id;
                      router.pushUrl({ url: 'pages/ChatPage', params: params });
                    })
                  Text('扩展')
                    .fontSize(12).fontColor(INK3)
                    .margin({ right: 14 })
                    .onClick((): void => {
                      const params: Record<string, string> = {};
                      params['assistantId'] = a.id;
                      router.pushUrl({ url: 'pages/ChatAssistantExtensionsPage', params: params });
                    })
                  // D-107:本地工具(AssistantLocalToolPage tab → 独立页)
                  Text('工具')
                    .fontSize(12).fontColor(INK3)
                    .margin({ right: 14 })
                    .onClick((): void => {
                      const params: Record<string, string> = {};
                      params['assistantId'] = a.id;
                      router.pushUrl({ url: 'pages/ChatAssistantLocalToolsPage', params: params });
                    })
                  // D-121:助手 MCP 选择(AssistantMcpPage tab 'MCP' → 独立页,
                  //   placement 同 '工具' 先例)
                  Text('MCP')
                    .fontSize(12).fontColor(INK3)
                    .margin({ right: 14 })
                    .onClick((): void => {
                      const params: Record<string, string> = {};
                      params['assistantId'] = a.id;
                      router.pushUrl({ url: 'pages/ChatAssistantMcpPage', params: params });
                    })
                  Text('复制')
                    .fontSize(12).fontColor(INK3)
                    .margin({ right: 14 })
                    .onClick((): void => this.copyAssistant(a))
                  Text('删除')
                    .fontSize(12).fontColor(ERROR)
                    .onClick((): void => this.confirmDelete(a))
                }
                .width('100%')
                if (a.systemPrompt.trim().length > 0) {
                  Text(a.systemPrompt)
                    .fontSize(META).fontColor(INK3).margin({ top: 4 })
                    .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
              .backgroundColor(SURFACE)
              .onClick((): void => this.startEdit(a))
            }
          }, (a: Assistant): string => a.id)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      } else {
        // ── 编辑器 ──
        Scroll() {
        Column() {
          Text(this.editingId.length > 0 ? '编辑助手' : '新建助手')
            .fontSize(15).fontColor(INK2)
            .width('100%').margin({ bottom: 12 })
          TextInput({ text: this.editName, placeholder: '名称' })
            .fontSize(BODY2).height(40).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editName = v; })
          TextArea({ text: this.editSystemPrompt, placeholder: '系统提示词(可空)' })
            .fontSize(14).height(120).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editSystemPrompt = v; })
          TextInput({ text: this.editTemperature, placeholder: '温度(可空,如 0.7)' })
            .fontSize(BODY2).height(40).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editTemperature = v; })
          TextInput({ text: this.editTopP, placeholder: 'top_p(可空,0-1)' })
            .fontSize(BODY2).height(40).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editTopP = v; })
          TextInput({ text: this.editMaxTokens, placeholder: '最大输出 tokens(可空,>0)' })
            .fontSize(BODY2).height(40).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editMaxTokens = v; })
          TextInput({ text: this.editContextSize, placeholder: '上下文消息数(可空,如 20)' })
            .fontSize(BODY2).height(40).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editContextSize = v; })
          // 推理力度 7 档选择(D-032)
          Text('推理力度')
            .fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 6 })
          Flex({ wrap: FlexWrap.Wrap }) {
            ForEach(REASONING_LEVELS, (level: ReasoningLevel, i: number): void => {
              Text(REASONING_LABELS[i])
                .fontSize(13)
                .fontColor(this.editReasoning === level ? Color.White : INK2)
                .backgroundColor(this.editReasoning === level ? ACCENT : SURFACE)
                .borderRadius(12)
                .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                .margin({ right: 8, bottom: 8 })
                .onClick((): void => { this.editReasoning = level; })
            }, (level: ReasoningLevel): string => level)
          }
          .width('100%').margin({ bottom: 8 })
          // 流式输出开关(D-040,Android AssistantBasicPage.kt:459-463)
          Row() {
            Text('流式输出(关闭则整段返回)')
              .fontSize(13).fontColor(INK2)
            Blank()
            Toggle({ type: ToggleType.Switch, isOn: this.editStreamOutput })
              .onChange((isOn: boolean): void => { this.editStreamOutput = isOn; })
          }
          .width('100%').margin({ bottom: 8 })

          // ── D-107 工具范围(AssistantBasicPage.kt:243-261) ──
          //   label/desc 硬编码 zh 逐字;Select → 六档 chips(同推理力度惯例)
          //   位置偏差:Android 居于 chatModel 与 imageModel 之间,本编辑器无
          //   模型选择器 → 置于流式输出之后
          Text('工具范围')
            .fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 4, top: 4 })
          Text('限制此助手可使用的工具类别；不会自动启用未开启的本地工具。')
            .fontSize(11).fontColor(INK3).width('100%').margin({ bottom: 6 })
          Flex({ wrap: FlexWrap.Wrap }) {
            ForEach(TOOL_PROFILES, (profile: MainAgentToolProfile, i: number): void => {
              Text(TOOL_PROFILE_LABELS[i])
                .fontSize(13)
                .fontColor(this.editToolProfile === profile ? Color.White : INK2)
                .backgroundColor(this.editToolProfile === profile ? ACCENT : SURFACE)
                .borderRadius(12)
                .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                .margin({ right: 8, bottom: 8 })
                .onClick((): void => { this.editToolProfile = profile; })
            }, (profile: MainAgentToolProfile): string => profile)
          }
          .width('100%').margin({ bottom: 8 })

          // ── D-049 自定义请求头(PropertyEditor.kt:47-121) ──
          Text('自定义请求头')
            .fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 6, top: 4 })
          ForEach(this.editHeaders, (h: HeaderDraft): void => {
            Row() {
              TextInput({ text: h.name, placeholder: 'Header 名' })
                .fontSize(META).height(40).backgroundColor(SURFACE)
                .layoutWeight(1)
                .onChange((v: string): void => this.setHeaderName(h.uid, v))
              TextInput({ text: h.value, placeholder: '值' })
                .fontSize(META).height(40).backgroundColor(SURFACE)
                .layoutWeight(1).margin({ left: 8 })
                .onChange((v: string): void => this.setHeaderValue(h.uid, v))
              Text('删除')
                .fontSize(12).fontColor(ERROR)
                .padding({ left: 10 })
                .onClick((): void => this.removeHeader(h.uid))
            }
            .width('100%').margin({ bottom: 8 })
          }, (h: HeaderDraft): string => h.uid)
          Text('+ 添加 Header')
            .fontSize(13).fontColor(ACCENT)
            .padding({ top: 4, bottom: 4 })
            .margin({ bottom: 8 })
            .onClick((): void => this.addHeader())

          // ── D-049 自定义请求体(PropertyEditor.kt:124-226) ──
          Text('自定义请求体(JSON 合并)')
            .fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 6, top: 4 })
          ForEach(this.editBodies, (b: BodyDraft): void => {
            Column() {
              Row() {
                TextInput({ text: b.key, placeholder: 'Body 键' })
                  .fontSize(META).height(40).backgroundColor(SURFACE)
                  .layoutWeight(1)
                  .onChange((v: string): void => this.setBodyKey(b.uid, v))
                Text('删除')
                  .fontSize(12).fontColor(ERROR)
                  .padding({ left: 10 })
                  .onClick((): void => this.removeBody(b.uid))
              }
              .width('100%').margin({ bottom: 6 })
              TextArea({ text: this.bodyTextOf(b.uid), placeholder: 'JSON 值,如 {"thinking":{"type":"enabled"}}' })
                .fontSize(12).backgroundColor(SURFACE)
                .height(72)
                .onChange((v: string): void => this.setBodyValueText(b.uid, v))
              if (this.bodyErrorOf(b.uid).length > 0) {
                Text(this.bodyErrorOf(b.uid))
                  .fontSize(11).fontColor(ERROR)
                  .width('100%').margin({ top: 4 })
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 10 })
          }, (b: BodyDraft): string => b.uid)
          Text('+ 添加 Body')
            .fontSize(13).fontColor(ACCENT)
            .padding({ top: 4, bottom: 4 })
            .margin({ bottom: 8 })
            .onClick((): void => this.addBody())

          Row() {
            Text('取消')
              .fontSize(14).fontColor(INK3)
              .padding({ left: 20, right: 20, top: 8, bottom: 8 })
              .onClick((): void => { this.showEditor = false; })
            Blank()
            Text('保存')
              .fontSize(14).fontColor(Color.White)
              .backgroundColor(ACCENT).borderRadius(8)
              .padding({ left: 24, right: 24, top: 8, bottom: 8 })
              .onClick((): void => this.save())
          }
          .width('100%')
        }
        .width('100%').padding({ left: GUTTER, right: GUTTER, bottom: 32 })
        }
        .align(Alignment.Start)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

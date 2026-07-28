// ChatAssistantsPage — 助手列表 + MVP 编辑(D-027)
// 编辑面:name/systemPrompt/temperature/contextMessageSize;其余 26 字段保留原值
// (替换 by id 回写整个 blob,与 Provider 设置页同构)。全量编辑器 = P1。
import { router } from '@kit.ArkUI';
import type { Assistant, ReasoningLevel } from '@amber/chat-domain';
import {
  loadAssistants, saveAssistants, makeAssistant, patchAssistant, newId,
} from '@amber/chat-domain';
import { getChatKvStore, getChatAssistants, getChatRepository } from '../di/AppContainer.ets';

// 推理力度 7 档(provider_model.ts REASONING_LEVEL_TABLE,与 Android ReasoningLevel.entries 同序)
const REASONING_LEVELS: ReasoningLevel[] = ['auto', 'off', 'low', 'medium', 'high', 'xhigh', 'max'];
const REASONING_LABELS: string[] = ['自动', '关闭', '低', '中', '高', '超高', '最大'];

const BG: string = '#F4F2EC';
const SURFACE: string = '#FAF9F5';
const INK: string = '#1B1A17';
const INK2: string = '#57544C';
const INK3: string = '#8F8B80';
const LINE: string = '#E4E0D6';
const ACCENT: string = '#B8623A';

@Entry
@Component
struct ChatAssistantsPage {
  @State items: Assistant[] = [];
  @State loaded: boolean = false;
  // 编辑态(editingId 非空 = 编辑该助手;= '' = 新建)
  @State editingId: string = '';
  @State editName: string = '';
  @State editSystemPrompt: string = '';
  @State editTemperature: string = '';
  @State editContextSize: string = '';
  @State editReasoning: ReasoningLevel = 'auto';
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
    this.editContextSize = a.contextMessageSize > 0 ? String(a.contextMessageSize) : '';
    this.editReasoning = a.reasoningLevel;
    this.showEditor = true;
  }

  private startCreate(): void {
    this.editingId = '';
    this.editName = '';
    this.editSystemPrompt = '';
    this.editTemperature = '';
    this.editContextSize = '';
    this.editReasoning = 'auto';
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
            contextMessageSize: this.parseIntOrZero(this.editContextSize),
            reasoningLevel: this.editReasoning,
          });
        });
      } else {
        next = [...current, makeAssistant({
          name,
          systemPrompt: this.editSystemPrompt,
          temperature: this.parseFloatOrNull(this.editTemperature),
          contextMessageSize: this.parseIntOrZero(this.editContextSize),
          reasoningLevel: this.editReasoning,
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
        fontColor: '#B43A3A',
        action: (): void => {
          loadAssistants(getChatKvStore()).then((loaded: Assistant[] | null): void => {
            const current: Assistant[] = loaded !== null && loaded.length > 0 ? loaded : this.items;
            const next: Assistant[] = current.filter((x: Assistant): boolean => x.id !== a.id);
            saveAssistants(getChatKvStore(), next).then((): Promise<void> => {
              // 级联删除该助手的会话
              const repo = getChatRepository();
              return repo.list(a.id).then((convs): Promise<void> => {
                let chain: Promise<void> = Promise.resolve();
                for (const c of convs) {
                  chain = chain.then((): Promise<void> => repo.delete(c.id));
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
        Text('助手')
          .fontSize(22).fontWeight(FontWeight.Bold).fontColor(INK)
        Blank()
        Text('+ 新助手')
          .fontSize(14).fontColor(ACCENT)
          .padding({ left: 12, right: 12, top: 6, bottom: 6 })
          .onClick((): void => this.startCreate())
      }
      .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 8 })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else if (!this.showEditor) {
        List() {
          ForEach(this.items, (a: Assistant): void => {
            ListItem() {
              Column() {
                Row() {
                  Text(a.name.length > 0 ? a.name : '未命名助手')
                    .fontSize(15).fontColor(INK)
                    .layoutWeight(1)
                  Text('对话')
                    .fontSize(12).fontColor(ACCENT)
                    .margin({ right: 14 })
                    .onClick((): void => {
                      const params: Record<string, string> = {};
                      params['assistantId'] = a.id;
                      router.pushUrl({ url: 'pages/ChatPage', params: params });
                    })
                  Text('复制')
                    .fontSize(12).fontColor(INK3)
                    .margin({ right: 14 })
                    .onClick((): void => this.copyAssistant(a))
                  Text('删除')
                    .fontSize(12).fontColor('#B43A3A')
                    .onClick((): void => this.confirmDelete(a))
                }
                .width('100%')
                if (a.systemPrompt.trim().length > 0) {
                  Text(a.systemPrompt)
                    .fontSize(11).fontColor(INK3).margin({ top: 4 })
                    .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                }
              }
              .width('100%').alignItems(HorizontalAlign.Start)
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .onClick((): void => this.startEdit(a))
            }
          }, (a: Assistant): string => a.id)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      } else {
        // ── 编辑器(MVP 4 字段) ──
        Column() {
          Text(this.editingId.length > 0 ? '编辑助手' : '新建助手')
            .fontSize(15).fontColor(INK2)
            .width('100%').margin({ bottom: 12 })
          TextInput({ text: this.editName, placeholder: '名称' })
            .fontSize(14).height(40).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editName = v; })
          TextArea({ text: this.editSystemPrompt, placeholder: '系统提示词(可空)' })
            .fontSize(14).height(120).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editSystemPrompt = v; })
          TextInput({ text: this.editTemperature, placeholder: '温度(可空,如 0.7)' })
            .fontSize(14).height(40).backgroundColor(SURFACE)
            .margin({ bottom: 8 })
            .onChange((v: string): void => { this.editTemperature = v; })
          TextInput({ text: this.editContextSize, placeholder: '上下文消息数(可空,如 20)' })
            .fontSize(14).height(40).backgroundColor(SURFACE)
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
        .width('100%').padding({ left: 16, right: 16 })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

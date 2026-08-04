// AssistantBasicPage — 助手基础信息编辑
// 真实写回:name / systemPrompt / avatar emoji(Avatar emoji 变体)。
// 占位字段(领域暂无对应,P1 登记):description / model(选项占位)。
import { router } from '@kit.ArkUI';
import { loadAssistants, saveAssistants, patchAssistant } from '@amber/chat-domain';
import type { Assistant, Avatar } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface RouterParams {
  assistantId?: string;
}

@Entry
@Component
struct AssistantBasicPage {
  @State loaded: boolean = false;
  @State editName: string = '';
  @State editDescription: string = '';
  @State editAvatarEmoji: string = '';
  @State editSystemPrompt: string = '';
  @State editModelIndex: number = 0;
  @State saving: boolean = false;
  private assistantId: string = '';
  private models: string[] = ['默认模型', 'GPT-4o (占位)', 'Claude (占位)', 'Gemini (占位)'];

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined ? params.assistantId : '';
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const list: Assistant[] | null = await loadAssistants(getChatKvStore());
    if (list === null) { this.loaded = true; return; }
    const found: Assistant | null = list.find((a: Assistant): boolean => a.id === this.assistantId) ?? null;
    if (found !== null) {
      this.editName = found.name;
      this.editSystemPrompt = found.systemPrompt;
      this.editAvatarEmoji = this.emojiOf(found);
    }
    this.loaded = true;
  }

  // 读取 emoji 头像内容(discriminated union 收窄)
  private emojiOf(a: Assistant): string {
    const av: Avatar = a.avatar;
    if (av.type === 'emoji') return av.content;
    return '';
  }

  private async save(): Promise<void> {
    if (this.assistantId.length === 0) { router.back(); return; }
    this.saving = true;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const emoji: string = this.editAvatarEmoji.trim();
    const next: Assistant[] = base.map((a: Assistant): Assistant => {
      if (a.id !== this.assistantId) return a;
      return patchAssistant(a, {
        name: this.editName,
        systemPrompt: this.editSystemPrompt,
        avatar: emoji.length > 0 ? { type: 'emoji', content: emoji } : { type: 'dummy' },
      });
    });
    await saveAssistants(kv, next);
    this.saving = false;
    router.back();
  }

  @Builder fieldLabel(text: string): void {
    Text(text).fontSize(META).fontColor(INK3).margin({ left: 2, bottom: 6 })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('基础信息').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text(this.saving ? '保存中' : '保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.save(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // 名称
            Column() {
              this.fieldLabel('名称')
              TextInput({ text: this.editName, placeholder: '助手名称' })
                .fontSize(BODY).height(44).backgroundColor(SURFACE)
                .borderRadius(12).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14 })
                .onChange((v: string): void => { this.editName = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })

            // 描述(占位)
            Column() {
              this.fieldLabel('描述')
              TextArea({ text: this.editDescription, placeholder: '一句话描述这个助手做什么(可选)' })
                .fontSize(BODY).height(72).backgroundColor(SURFACE)
                .borderRadius(12).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onChange((v: string): void => { this.editDescription = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })

            // 头像 Emoji
            Column() {
              this.fieldLabel('头像 Emoji')
              Row() {
                TextInput({ text: this.editAvatarEmoji, placeholder: '如 🤖' })
                  .fontSize(BODY).height(44).backgroundColor(SURFACE)
                  .borderRadius(12).border({ width: 1, color: LINE })
                  .padding({ left: 14, right: 14 })
                  .layoutWeight(1)
                  .onChange((v: string): void => { this.editAvatarEmoji = v; })
                Text(this.editAvatarEmoji.length > 0 ? this.editAvatarEmoji : '🤖')
                  .fontSize(24).width(44).height(44).textAlign(TextAlign.Center)
                  .backgroundColor(SURFACE2).borderRadius(22).margin({ left: 10 })
              }
              .width('100%')
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })

            // 模型(占位选项)
            Column() {
              this.fieldLabel('模型')
              Row() {
                Text('当前模型').fontSize(BODY).fontColor(INK).layoutWeight(1)
                PillSelect({
                  options: this.models, selectedIndex: this.editModelIndex,
                  onSelect: (i: number): void => { this.editModelIndex = i; },
                })
              }
              .width('100%').padding({ left: 14, right: 14, top: 10, bottom: 10 })
              .backgroundColor(SURFACE).borderRadius(12).border({ width: 1, color: LINE })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })

            // 系统提示词
            Column() {
              this.fieldLabel('系统提示词')
              TextArea({ text: this.editSystemPrompt, placeholder: '定义助手的行为、角色与约束…' })
                .fontSize(BODY).height(160).backgroundColor(SURFACE)
                .borderRadius(12).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onChange((v: string): void => { this.editSystemPrompt = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16, top: 12 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

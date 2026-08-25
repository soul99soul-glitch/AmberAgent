// AssistantBasicPage — 助手基础信息编辑
// 真实写回:name / systemPrompt / avatar emoji / chatModelId。
// Assistant 域模型没有 description，页面展示 systemPrompt 的只读摘要。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import {
  loadAssistants, saveAssistants, patchAssistant, loadProviders, hasUsableAuth,
} from '@amber/chat-domain';
import type { Assistant, Avatar, ProviderSetting } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, GUTTER, RADIUS } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface RouterParams {
  assistantId?: string;
}

@Entry
@Component
struct AssistantBasicPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State loaded: boolean = false;
  @State editName: string = '';
  @State editAvatarEmoji: string = '';
  @State editSystemPrompt: string = '';
  @State modelOptions: string[] = [];
  @State modelIds: string[] = [];
  @State editModelIndex: number = 0;
  @State saving: boolean = false;
  private assistantId: string = '';
  private pageAlive: boolean = false;
  private loadSeq: number = 0;

  aboutToAppear(): void {
    this.pageAlive = true;
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined ? params.assistantId : '';
    this.refresh();
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
  }

  private async refresh(): Promise<void> {
    const seq: number = ++this.loadSeq;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const providers: ProviderSetting[] | null = await loadProviders(kv);
    if (!this.pageAlive || seq !== this.loadSeq) return;
    if (list !== null) {
      const found: Assistant | null = list.find((a: Assistant): boolean => a.id === this.assistantId) ?? null;
      if (found !== null) {
        this.editName = found.name;
        this.editSystemPrompt = found.systemPrompt;
        this.editAvatarEmoji = this.emojiOf(found);
        this.modelOptions = [];
        this.modelIds = [];
        if (providers !== null) {
          for (const provider of providers) {
            if (!hasUsableAuth(provider)) continue;
            for (const model of provider.models) {
              if (model.type !== 'chat') continue;
              this.modelIds.push(model.id);
              this.modelOptions.push(`${provider.name} · ${model.modelId}`);
            }
          }
        }
        const selected: number = this.modelIds.indexOf(found.chatModelId ?? '');
        this.editModelIndex = selected >= 0 ? selected : 0;
      }
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
    const seq: number = this.loadSeq;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const emoji: string = this.editAvatarEmoji.trim();
    const next: Assistant[] = base.map((a: Assistant): Assistant => {
      if (a.id !== this.assistantId) return a;
      return patchAssistant(a, {
        name: this.editName,
        systemPrompt: this.editSystemPrompt,
        chatModelId: this.modelIds.length > 0 && this.editModelIndex >= 0
          && this.editModelIndex < this.modelIds.length ? this.modelIds[this.editModelIndex] : null,
        avatar: emoji.length > 0 ? { type: 'emoji', content: emoji } : { type: 'dummy' },
      });
    });
    await saveAssistants(kv, next);
    if (!this.pageAlive || seq !== this.loadSeq) return;
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
        Icon({ name: 'back', iconSize: 22, color: INK3 })
          .onClick((): void => { router.back(); })
        Text('基础信息').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text(this.saving ? '保存中' : '保存').fontSize(BODY).fontColor(ACCENT).maxLines(1)
          .constraintSize({ minHeight: 40 })
          .onClick((): void => { this.save(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            // 名称
            Column() {
              this.fieldLabel('名称')
              TextInput({ text: this.editName, placeholder: '助手名称' })
                .fontSize(BODY).height(44).backgroundColor(SURFACE)
                .borderRadius(RADIUS).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14 })
                .onChange((v: string): void => { this.editName = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })

            // 域模型没有 description，展示系统提示词摘要，避免伪造可保存字段。
            Column() {
              this.fieldLabel('描述预览')
              Text(this.editSystemPrompt.trim().length > 200
                ? this.editSystemPrompt.trim().substring(0, 200) + '…'
                : this.editSystemPrompt.trim().length > 0 ? this.editSystemPrompt.trim() : '暂无系统提示词')
                .fontSize(BODY).fontColor(INK2).height(72)
                .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
                .backgroundColor(SURFACE).borderRadius(RADIUS).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })

            // 头像 Emoji
            Column() {
              this.fieldLabel('头像 Emoji')
              Row() {
                TextInput({ text: this.editAvatarEmoji, placeholder: '如 🤖' })
                  .fontSize(BODY).height(44).backgroundColor(SURFACE)
                  .borderRadius(RADIUS).border({ width: 1, color: LINE })
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

            // 模型：仅展示真实 provider 模型，并保存 Assistant.chatModelId。
            Column() {
              this.fieldLabel('模型')
              if (this.modelOptions.length === 0) {
                Text('暂无可用 Provider 模型').fontSize(BODY).fontColor(INK3)
                  .padding({ left: 14, right: 14, top: 12, bottom: 12 })
              } else {
                PillSelect({
                  options: this.modelOptions, selectedIndex: this.editModelIndex,
                  onSelect: (i: number): void => { this.editModelIndex = i; },
                })
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 14 })

            // 系统提示词
            Column() {
              this.fieldLabel('系统提示词')
              TextArea({ text: this.editSystemPrompt, placeholder: '定义助手的行为、角色与约束…' })
                .fontSize(BODY).height(160).backgroundColor(SURFACE)
                .borderRadius(RADIUS).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onChange((v: string): void => { this.editSystemPrompt = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 32 })
          }
          .width('100%').padding({ left: GUTTER, right: GUTTER, top: 12 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

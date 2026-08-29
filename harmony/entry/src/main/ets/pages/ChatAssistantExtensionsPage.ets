// ChatAssistantExtensionsPage — 助手扩展链接面(D-102)
// Android 锚点:
//   AssistantExtensionsPage.kt(全文 218 行)— tab 模式注入(:127-158)/世界书
//     (:160-191):空态(消息 + '前往提示词管理' 按钮 → Screen.Prompts);
//     非空 → 列表 + onToggle(ids + id / ids - id)→ vm.update(assistant.copy)
//   ExtensionContent.kt:35-91(ModeInjectionsContent/LorebooksContent:
//     name 空 → '未命名'/'未命名Lorebook';lorebook desc 非空显示;Switch)
//   values-zh/strings.xml:18-28,284-285
// 偏差登记:
//   - 4 tab(quickMessages/modeInjections/lorebooks/skills)全量对齐(quickMessages
//     与 skills 子系统现已真实:KV quick_messages + SkillManager.listSkills)
//   - HorizontalPager+SecondaryTabRow → 顶部 tab 行(语义等价)
//   - Set<Uuid> ± id → string[] Set 语义(toggle 去重)
import { ACCENT,BG,INK,INK3,LINE,SURFACE,GUTTER } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { router } from '@kit.ArkUI';
import type { SkillMetadata } from '@amber/chat-domain';
import {
  loadAssistants, saveAssistants, patchAssistant,
} from '@amber/chat-domain';
import type { Assistant, Lorebook, PromptInjection } from '@amber/chat-domain';
import { getChatKvStore, getSkillManager } from '../di/AppContainer.ets';
import { loadLorebooks, loadModeInjections } from '../platform_impl/ExtensionPrefs.ets';

// 全局快捷消息 KV(与 QuickMessagesPage 同键同形;此处只读)
const QUICK_MESSAGES_KEY: string = 'quick_messages';
interface QuickMessageEntry {
  id: string;
  title: string;
  content: string;
}


interface RouterParams {
  assistantId?: string;
}

@Entry
@Component
struct ChatAssistantExtensionsPage {
  @State loaded: boolean = false;
  @State tab: number = 1; // 0 = 快捷消息;1 = 模式注入;2 = 世界书;3 = 技能
  @State assistant: Assistant | null = null;
  @State modeInjections: PromptInjection[] = [];
  @State lorebooks: Lorebook[] = [];
  @State quickMessages: QuickMessageEntry[] = [];
  @State skills: SkillMetadata[] = [];
  private assistantId: string = '';

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined
      ? params.assistantId : '';
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const kv = getChatKvStore();
    const assistants: Assistant[] | null = await loadAssistants(kv);
    const found: Assistant | null = assistants === null
      ? null
      : (assistants.find((a: Assistant): boolean => a.id === this.assistantId) ?? null);
    const mis: PromptInjection[] = await loadModeInjections(kv);
    const lbs: Lorebook[] = await loadLorebooks(kv);
    let quicks: QuickMessageEntry[] = [];
    try {
      const raw: string | null = await kv.get(QUICK_MESSAGES_KEY);
      if (raw !== null) {
        quicks = JSON.parse(raw) as QuickMessageEntry[];
      }
    } catch (_e) {
      quicks = [];
    }
    this.assistant = found;
    this.modeInjections = mis;
    this.lorebooks = lbs;
    this.quickMessages = quicks;
    this.skills = getSkillManager().listSkills();
    this.loaded = true;
  }

  // onToggle(:140-145/:182-187):checked → ids + id;否则 ids - id → vm.update
  private async toggleModeInjection(id: string, checked: boolean): Promise<void> {
    if (this.assistant === null) return;
    const cur: Assistant = this.assistant;
    const ids: string[] = checked
      ? (cur.modeInjectionIds.indexOf(id) >= 0
        ? cur.modeInjectionIds.slice() : [...cur.modeInjectionIds, id])
      : cur.modeInjectionIds.filter((x: string): boolean => x !== id);
    await this.saveAssistant(patchAssistant(cur, { modeInjectionIds: ids }));
  }

  private async toggleLorebook(id: string, checked: boolean): Promise<void> {
    if (this.assistant === null) return;
    const cur: Assistant = this.assistant;
    const ids: string[] = checked
      ? (cur.lorebookIds.indexOf(id) >= 0
        ? cur.lorebookIds.slice() : [...cur.lorebookIds, id])
      : cur.lorebookIds.filter((x: string): boolean => x !== id);
    await this.saveAssistant(patchAssistant(cur, { lorebookIds: ids }));
  }

  // :103-109 同构 — quickMessageIds ± id
  private async toggleQuickMessage(id: string, checked: boolean): Promise<void> {
    if (this.assistant === null) return;
    const cur: Assistant = this.assistant;
    const ids: string[] = checked
      ? (cur.quickMessageIds.indexOf(id) >= 0
        ? cur.quickMessageIds.slice() : [...cur.quickMessageIds, id])
      : cur.quickMessageIds.filter((x: string): boolean => x !== id);
    await this.saveAssistant(patchAssistant(cur, { quickMessageIds: ids }));
  }

  // :193-217 同构 — enabledSkills ± name(技能以 name 为键,对齐 createSkillTools)
  private async toggleSkill(name: string, checked: boolean): Promise<void> {
    if (this.assistant === null) return;
    const cur: Assistant = this.assistant;
    const ids: string[] = checked
      ? (cur.enabledSkills.indexOf(name) >= 0
        ? cur.enabledSkills.slice() : [...cur.enabledSkills, name])
      : cur.enabledSkills.filter((x: string): boolean => x !== name);
    await this.saveAssistant(patchAssistant(cur, { enabledSkills: ids }));
  }

  private async saveAssistant(next: Assistant): Promise<void> {
    const kv = getChatKvStore();
    const assistants: Assistant[] | null = await loadAssistants(kv);
    const list: Assistant[] = assistants === null ? [] : assistants.slice();
    const index: number = list.findIndex((a: Assistant): boolean => a.id === next.id);
    if (index >= 0) list[index] = next;
    else list.push(next);
    await saveAssistants(kv, list);
    this.assistant = next;
  }

  private gotoPrompts(): void {
    router.pushUrl({ url: 'pages/ChatPromptsPage' });
  }

  @Builder
  emptyState(message: string) {
    Column() {
      Text(message).fontSize(13).fontColor(INK3)
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      Row() {
        Icon({ name: 'link', iconSize: 13, color: ACCENT })
        Text(' 前往提示词管理').fontSize(13).fontColor(ACCENT)
      }
      .margin({ top: 12 })
      .padding({ left: 12, right: 12, top: 8, bottom: 8 })
      .constraintSize({ minHeight: 40 })
      .onClick((): void => { this.gotoPrompts(); })
    }
    .width('100%').padding({ left: GUTTER, right: GUTTER, top: 64, bottom: 64 }).margin({ top: 0 })
      .alignItems(HorizontalAlign.Center)
  }

  build() {
    Column() {
      PageTitleBar({ title: '扩展' })

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(13).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else if (this.assistant === null) {
        Column() {
          Text('助手不存在').fontSize(13).fontColor(INK3)
        }.width('100%').padding({ top: 64, bottom: 64 }).alignItems(HorizontalAlign.Center)
      } else {
        // 顶部四 tab(SecondaryTabRow 适配)
        Row() {
          Text('快捷消息')
            .fontSize(13).fontColor(this.tab === 0 ? ACCENT : INK3)
            .textAlign(TextAlign.Center)
            .layoutWeight(1).padding({ top: 10, bottom: 10 })
            .onClick((): void => { this.tab = 0; })
          Text('模式注入')
            .fontSize(13).fontColor(this.tab === 1 ? ACCENT : INK3)
            .textAlign(TextAlign.Center)
            .layoutWeight(1).padding({ top: 10, bottom: 10 })
            .onClick((): void => { this.tab = 1; })
          Text('世界书')
            .fontSize(13).fontColor(this.tab === 2 ? ACCENT : INK3)
            .textAlign(TextAlign.Center)
            .layoutWeight(1).padding({ top: 10, bottom: 10 })
            .onClick((): void => { this.tab = 2; })
          Text('技能')
            .fontSize(13).fontColor(this.tab === 3 ? ACCENT : INK3)
            .textAlign(TextAlign.Center)
            .layoutWeight(1).padding({ top: 10, bottom: 10 })
            .onClick((): void => { this.tab = 3; })
        }
        .width('100%').backgroundColor(SURFACE)
        .border({ width: { bottom: 1 }, color: LINE })

        Scroll() {
          Column() {
            if (this.tab === 0) {
              // 快捷消息(:92-126):全局列表 + quickMessageIds 绑定
              if (this.quickMessages.length === 0) {
                this.emptyState('没有快捷消息，前往快捷消息管理创建')
              } else {
                ForEach(this.quickMessages, (qm: QuickMessageEntry) => {
                  Row() {
                    Column() {
                      Text(qm.title.length > 0 ? qm.title : '未命名')
                        .fontSize(14).fontColor(INK)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      if (qm.content.trim().length > 0) {
                        Text(qm.content)
                          .fontSize(11).fontColor(INK3).margin({ top: 2 })
                          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                      }
                    }
                    .alignItems(HorizontalAlign.Start).layoutWeight(1)
                    .margin({ right: 10 })
                    Toggle({
                      type: ToggleType.Switch,
                      isOn: this.assistant !== null
                        && this.assistant.quickMessageIds.indexOf(qm.id) >= 0,
                    })
                      .selectedColor(ACCENT)
                      .onChange((v: boolean): void => {
                        this.toggleQuickMessage(qm.id, v);
                      })
                  }
                  .width('100%')
                  .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
                }, (qm: QuickMessageEntry): string => qm.id)
                Row() {
                  Text('前往快捷消息管理')
                    .fontSize(13).fontColor(ACCENT)
                    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                    .onClick((): void => { router.pushUrl({ url: 'pages/QuickMessagesPage' }); })
                }
                .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
              }
            } else if (this.tab === 1) {
              if (this.modeInjections.length === 0) {
                this.emptyState('没有模式注入，前往提示词管理创建')
              } else {
                ForEach(this.modeInjections, (mi: PromptInjection) => {
                  Row() {
                    Text(mi.name.length > 0 ? mi.name : '未命名')
                      .fontSize(14).fontColor(INK).layoutWeight(1)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Toggle({
                      type: ToggleType.Switch,
                      isOn: this.assistant !== null
                        && this.assistant.modeInjectionIds.indexOf(mi.id) >= 0,
                    })
                      .selectedColor(ACCENT)
                      .onChange((v: boolean): void => {
                        this.toggleModeInjection(mi.id, v);
                      })
                  }
                  .width('100%')
                  .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
                }, (mi: PromptInjection): string => mi.id)
                Row() {
                  Text('前往提示词管理')
                    .fontSize(13).fontColor(ACCENT)
                    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                    .onClick((): void => { this.gotoPrompts(); })
                }
                .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
              }
            } else if (this.tab === 2) {
              if (this.lorebooks.length === 0) {
                this.emptyState('没有世界书，前往提示词管理创建')
              } else {
                ForEach(this.lorebooks, (lb: Lorebook) => {
                  Row() {
                    Column() {
                      Text(lb.name.length > 0 ? lb.name : '未命名Lorebook')
                        .fontSize(14).fontColor(INK)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      if (lb.description.trim().length > 0) {
                        Text(lb.description)
                          .fontSize(11).fontColor(INK3).margin({ top: 2 })
                          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                      }
                    }
                    .alignItems(HorizontalAlign.Start).layoutWeight(1)
                    .margin({ right: 10 })
                    Toggle({
                      type: ToggleType.Switch,
                      isOn: this.assistant !== null
                        && this.assistant.lorebookIds.indexOf(lb.id) >= 0,
                    })
                      .selectedColor(ACCENT)
                      .onChange((v: boolean): void => {
                        this.toggleLorebook(lb.id, v);
                      })
                  }
                  .width('100%')
                  .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
                }, (lb: Lorebook): string => lb.id)
                Row() {
                  Text('前往提示词管理')
                    .fontSize(13).fontColor(ACCENT)
                    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                    .onClick((): void => { this.gotoPrompts(); })
                }
                .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
              }
            } else {
              // 技能(:193-217):全局技能列表 + enabledSkills 绑定(name 为键)
              if (this.skills.length === 0) {
                this.emptyState('没有技能，前往技能管理查看')
              } else {
                ForEach(this.skills, (sk: SkillMetadata) => {
                  Row() {
                    Column() {
                      Text(sk.name)
                        .fontSize(14).fontColor(INK)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                      if (sk.description.trim().length > 0) {
                        Text(sk.description)
                          .fontSize(11).fontColor(INK3).margin({ top: 2 })
                          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                      }
                    }
                    .alignItems(HorizontalAlign.Start).layoutWeight(1)
                    .margin({ right: 10 })
                    Toggle({
                      type: ToggleType.Switch,
                      isOn: this.assistant !== null
                        && this.assistant.enabledSkills.indexOf(sk.name) >= 0,
                    })
                      .selectedColor(ACCENT)
                      .onChange((v: boolean): void => {
                        this.toggleSkill(sk.name, v);
                      })
                  }
                  .width('100%')
                  .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
                }, (sk: SkillMetadata): string => sk.name)
                Row() {
                  Text('前往技能管理')
                    .fontSize(13).fontColor(ACCENT)
                    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                    .onClick((): void => { router.pushUrl({ url: 'pages/SkillsPage' }); })
                }
                .width('100%').justifyContent(FlexAlign.Center).margin({ top: 8 })
              }
            }
          }
          .width('100%').padding({ left: GUTTER, right: GUTTER, bottom: 24 })
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

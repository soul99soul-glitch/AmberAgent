// AssistantPromptPage — 系统提示词 + 注入模式编辑
// 真实写回:systemPrompt。
// 占位字段(领域暂无对应,P1 登记):注入模式(mode)、lorebook 条目列表
//   (keyword + content)。条目增删为本地态,保存时仅落 systemPrompt。
import { router } from '@kit.ArkUI';
import { loadAssistants, saveAssistants, patchAssistant } from '@amber/chat-domain';
import type { Assistant } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface RouterParams {
  assistantId?: string;
}

interface LorebookEntry {
  keyword: string;
  content: string;
}

@Entry
@Component
struct AssistantPromptPage {
  @State loaded: boolean = false;
  @State editSystemPrompt: string = '';
  @State editModeIndex: number = 0;
  @State entries: LorebookEntry[] = [];
  @State saving: boolean = false;
  private assistantId: string = '';
  private modes: string[] = ['无 (none)', '世界书 (lorebook)'];

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
      this.editSystemPrompt = found.systemPrompt;
      // lorebookIds 非空 → 默认选中 lorebook 模式(占位映射)
      this.editModeIndex = found.lorebookIds.length > 0 ? 1 : 0;
    }
    this.loaded = true;
  }

  private setEntryKeyword(index: number, v: string): void {
    const next: LorebookEntry[] = [...this.entries];
    next[index] = { keyword: v, content: next[index].content };
    this.entries = next;
  }

  private setEntryContent(index: number, v: string): void {
    const next: LorebookEntry[] = [...this.entries];
    next[index] = { keyword: next[index].keyword, content: v };
    this.entries = next;
  }

  private removeEntry(index: number): void {
    this.entries = this.entries.filter((_: LorebookEntry, i: number): boolean => i !== index);
  }

  private addEntry(): void {
    this.entries = [...this.entries, { keyword: '', content: '' }];
  }

  private async save(): Promise<void> {
    if (this.assistantId.length === 0) { router.back(); return; }
    this.saving = true;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const next: Assistant[] = base.map((a: Assistant): Assistant => {
      if (a.id !== this.assistantId) return a;
      return patchAssistant(a, { systemPrompt: this.editSystemPrompt });
    });
    await saveAssistants(kv, next);
    this.saving = false;
    router.back();
  }

  @Builder fieldLabel(text: string): void {
    Text(text).fontSize(META).fontColor(INK3).margin({ left: 2, bottom: 6 })
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('提示词').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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
            this.sectionHeader('系统提示词')
            Column() {
              TextArea({ text: this.editSystemPrompt, placeholder: '定义助手的行为、角色与约束…' })
                .fontSize(BODY).height(180).backgroundColor(SURFACE)
                .borderRadius(12).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onChange((v: string): void => { this.editSystemPrompt = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16 }).margin({ bottom: 8 })

            this.sectionHeader('注入模式')
            Row() {
              Text('模式').fontSize(BODY).fontColor(INK).layoutWeight(1)
              PillSelect({
                options: this.modes, selectedIndex: this.editModeIndex,
                onSelect: (i: number): void => { this.editModeIndex = i; },
              })
            }
            .width('100%').padding({ left: 14, right: 14, top: 10, bottom: 10 })
            .backgroundColor(SURFACE).borderRadius(12).border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16 })

            this.sectionHeader('世界书条目')
            Column() {
              if (this.entries.length === 0) {
                Text('暂无条目,点击下方添加').fontSize(META).fontColor(INK3)
                  .width('100%').padding({ left: 2, top: 8, bottom: 8 })
              }
              ForEach(this.entries, (e: LorebookEntry, i: number): void => {
                Column() {
                  TextInput({ text: e.keyword, placeholder: '关键词(触发条件)' })
                    .fontSize(BODY).height(40).backgroundColor(SURFACE)
                    .borderRadius(10).border({ width: 1, color: LINE })
                    .padding({ left: 12, right: 12 })
                    .onChange((v: string): void => this.setEntryKeyword(i, v))
                  TextArea({ text: e.content, placeholder: '命中关键词时注入的内容…' })
                    .fontSize(META).height(72).backgroundColor(SURFACE)
                    .borderRadius(10).border({ width: 1, color: LINE })
                    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                    .margin({ top: 6 })
                    .onChange((v: string): void => this.setEntryContent(i, v))
                  Row() {
                    Blank()
                    Text('删除条目').fontSize(META).fontColor('#C93A2F')
                      .padding({ left: 10, top: 4, bottom: 4 })
                      .onClick((): void => this.removeEntry(i))
                  }
                  .width('100%')
                }
                .width('100%').alignItems(HorizontalAlign.Start)
                .backgroundColor(SURFACE2).borderRadius(12)
                .padding({ left: 12, right: 12, top: 10, bottom: 10 })
                .margin({ bottom: 8 })
              }, (e: LorebookEntry, i: number): string => `lb-${i}`)
            }
            .width('100%').padding({ left: 16, right: 16 })

            Row() {
              Text('+ 添加条目').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => this.addEntry())
            }
            .width('100%').padding({ left: 16, top: 4, bottom: 32 })
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

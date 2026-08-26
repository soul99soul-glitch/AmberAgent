// AssistantPromptPage — 系统提示词 + 世界书条目编辑。
// 世界书真实读写 ExtensionPrefs.lorebooks，并把选中的 lorebookIds 写回 Assistant。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import {
  loadAssistants, saveAssistants, patchAssistant, makeLorebook, makeRegexInjection,
} from '@amber/chat-domain';
import type { Assistant, Lorebook, RegexInjection } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { loadLorebooks, updateLorebooks } from '../platform_impl/ExtensionPrefs.ets';
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, TITLE, GUTTER, RADIUS } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface RouterParams {
  assistantId?: string;
}

interface DraftEntry {
  id: string;
  keyword: string;
  content: string;
  base: RegexInjection;
}

@Entry
@Component
struct AssistantPromptPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State loaded: boolean = false;
  @State editSystemPrompt: string = '';
  @State editModeIndex: number = 0;
  @State entries: DraftEntry[] = [];
  @State saving: boolean = false;
  private assistantId: string = '';
  private selectedLorebookIds: string[] = [];
  private pageAlive: boolean = false;
  private loadSeq: number = 0;
  private modes: string[] = ['无 (none)', '世界书 (lorebook)'];

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
    const lorebooks: Lorebook[] = await loadLorebooks(kv);
    if (!this.pageAlive || seq !== this.loadSeq) return;
    if (list !== null) {
      const found: Assistant | null = list.find((a: Assistant): boolean => a.id === this.assistantId) ?? null;
      if (found !== null) {
        this.editSystemPrompt = found.systemPrompt;
        this.selectedLorebookIds = found.lorebookIds.slice();
        this.editModeIndex = this.selectedLorebookIds.length > 0 ? 1 : 0;
        const drafts: DraftEntry[] = [];
        for (const lorebook of lorebooks) {
          if (this.selectedLorebookIds.indexOf(lorebook.id) < 0) continue;
          for (const entry of lorebook.entries) {
            const keyword: string = entry.keywords.join(', ');
            drafts.push({ id: entry.id, keyword, content: entry.content, base: entry });
          }
        }
        this.entries = drafts;
      }
    }
    this.loaded = true;
  }

  private setEntryKeyword(index: number, value: string): void {
    const current: DraftEntry = this.entries[index];
    if (current === undefined) return;
    const next: DraftEntry = {
      id: current.id, keyword: value, content: current.content, base: current.base,
    };
    const list: DraftEntry[] = this.entries.slice();
    list[index] = next;
    this.entries = list;
  }

  private setEntryContent(index: number, value: string): void {
    const current: DraftEntry = this.entries[index];
    if (current === undefined) return;
    const next: DraftEntry = {
      id: current.id, keyword: current.keyword, content: value, base: current.base,
    };
    const list: DraftEntry[] = this.entries.slice();
    list[index] = next;
    this.entries = list;
  }

  private removeEntry(index: number): void {
    this.entries = this.entries.filter((_: DraftEntry, i: number): boolean => i !== index);
  }

  private addEntry(): void {
    const empty: RegexInjection = makeRegexInjection({ name: '', content: '' });
    this.entries = this.entries.concat([{
      id: empty.id, keyword: '', content: '', base: empty,
    }]);
    this.editModeIndex = 1;
  }

  private toRegexInjection(entry: DraftEntry): RegexInjection {
    const keywords: string[] = entry.keyword.split(',').map((v: string): string => v.trim())
      .filter((v: string): boolean => v.length > 0);
    const base: RegexInjection = entry.base;
    return {
      id: entry.id, name: base.name, enabled: base.enabled, priority: base.priority,
      position: base.position, content: entry.content, injectDepth: base.injectDepth,
      role: base.role, keywords, useRegex: base.useRegex, caseSensitive: base.caseSensitive,
      scanDepth: base.scanDepth, constantActive: base.constantActive,
    };
  }

  private async save(): Promise<void> {
    if (this.assistantId.length === 0) { router.back(); return; }
    this.saving = true;
    const seq: number = this.loadSeq;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const baseAssistants: Assistant[] = list === null ? [] : list.slice();
    let lorebookIds: string[] = this.selectedLorebookIds.slice();
    const editedEntries: RegexInjection[] = this.entries.map(
      (entry: DraftEntry): RegexInjection => this.toRegexInjection(entry));
    await updateLorebooks(kv, (current: Lorebook[]): Lorebook[] => {
      let targetId: string = lorebookIds.length > 0 ? lorebookIds[0] : '';
      if (targetId.length === 0) {
        const created: Lorebook = makeLorebook({ name: '助手世界书', enabled: true, entries: editedEntries });
        lorebookIds = [created.id];
        return current.concat([created]);
      }
      const result: Lorebook[] = [];
      let found: boolean = false;
      for (const lorebook of current) {
        if (lorebook.id !== targetId) {
          result.push(lorebook);
        } else {
          found = true;
          result.push({
            id: lorebook.id, name: lorebook.name, description: lorebook.description,
            enabled: lorebook.enabled, entries: editedEntries,
          });
        }
      }
      if (!found) {
        const created: Lorebook = makeLorebook({ id: targetId, name: '助手世界书', entries: editedEntries });
        result.push(created);
      }
      return result;
    });
    const next: Assistant[] = baseAssistants.map((a: Assistant): Assistant =>
      a.id === this.assistantId
        ? patchAssistant(a, { systemPrompt: this.editSystemPrompt, lorebookIds: lorebookIds })
        : a);
    await saveAssistants(kv, next);
    if (!this.pageAlive || seq !== this.loadSeq) return;
    this.saving = false;
    router.back();
  }

  @Builder fieldLabel(text: string): void {
    Text(text).fontSize(META).fontColor(INK3).margin({ left: 2, bottom: 6 });
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }.width('100%').padding({ left: 16, top: 20, bottom: 6 });
  }

  build(): void {
    Column() {
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 }).onClick((): void => { router.back(); })
        Text('提示词').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text(this.saving ? '保存中' : '保存').fontSize(BODY).fontColor(ACCENT).maxLines(1)
          .constraintSize({ minHeight: 40 })
          .onClick((): void => { this.save(); })
      }.width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 })
        .border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('系统提示词')
            Column() {
              TextArea({ text: this.editSystemPrompt, placeholder: '定义助手的行为、角色与约束…' })
                .fontSize(BODY).height(180).backgroundColor(SURFACE).borderRadius(RADIUS)
                .border({ width: 1, color: LINE }).padding({ left: 14, right: 14, top: 10, bottom: 10 })
                .onChange((v: string): void => { this.editSystemPrompt = v; })
            }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: GUTTER, right: GUTTER }).margin({ bottom: 8 })

            this.sectionHeader('注入模式')
            Row() {
              Text('模式').fontSize(BODY).fontColor(INK).layoutWeight(1)
              PillSelect({ options: this.modes, selectedIndex: this.editModeIndex,
                onSelect: (i: number): void => { this.editModeIndex = i; } })
            }.padding({ left: 14, right: 14, top: 10, bottom: 10 })
              .backgroundColor(SURFACE).borderRadius(RADIUS).border({ width: 1, color: LINE })
              .margin({ left: 16, right: 16 })

            this.sectionHeader('世界书条目')
            Column() {
              if (this.entries.length === 0) {
                Text('暂无条目,点击下方添加').fontSize(META).fontColor(INK3)
                  .width('100%').padding({ left: 2, top: 8, bottom: 8 })
              }
              ForEach(this.entries, (entry: DraftEntry, index: number): void => {
                Column() {
                  TextInput({ text: entry.keyword, placeholder: '关键词(多个关键词用逗号分隔)' })
                    .fontSize(BODY).height(40).backgroundColor(SURFACE).borderRadius(10)
                    .border({ width: 1, color: LINE }).padding({ left: 12, right: 12 })
                    .onChange((v: string): void => { this.setEntryKeyword(index, v); })
                  TextArea({ text: entry.content, placeholder: '命中关键词时注入的内容…' })
                    .fontSize(META).height(72).backgroundColor(SURFACE).borderRadius(10)
                    .border({ width: 1, color: LINE }).padding({ left: 12, right: 12, top: 8, bottom: 8 })
                    .margin({ top: 6 }).onChange((v: string): void => { this.setEntryContent(index, v); })
                  Row() {
                    Blank()
                  Text('删除条目').fontSize(META).fontColor('#C93A2F').maxLines(1)
                    .padding({ left: 10, top: 4, bottom: 4 })
                    .constraintSize({ minHeight: 40 })
                    .onClick((): void => { this.removeEntry(index); })

                  }.width('100%')
                }.width('100%').alignItems(HorizontalAlign.Start).backgroundColor(SURFACE2)
                  .borderRadius(12).padding({ left: 12, right: 12, top: 10, bottom: 10 }).margin({ bottom: 8 })
              }, (entry: DraftEntry): string => entry.id)
            }.width('100%').padding({ left: GUTTER, right: GUTTER })

            Row() {
              Text('+ 添加条目').fontSize(BODY).fontColor(ACCENT).maxLines(1)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .constraintSize({ minHeight: 40 }).backgroundColor(SURFACE2)
                .borderRadius(50).onClick((): void => { this.addEntry(); })
            }.width('100%').padding({ left: GUTTER, right: GUTTER, top: 4, bottom: 32 })
          }.width('100%')
        }
        .align(Alignment.Start).layoutWeight(1)
      }
    }.width('100%').height('100%').backgroundColor(BG)
  }
}

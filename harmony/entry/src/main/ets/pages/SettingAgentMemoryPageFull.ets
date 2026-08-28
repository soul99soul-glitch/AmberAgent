// SettingAgentMemoryPageFull — 核心记忆(接 MemoryStore 真实后端)
// 核心记忆 = MEMORY_BUCKET_GLOBAL 桶；支持列表/新增/删除/编辑。
import type { AssistantMemory } from '@amber/chat-domain';
import { MEMORY_BUCKET_GLOBAL } from '@amber/chat-domain';
import { getMemoryLibraryOps, MemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import {
  BG, INK, INK3, INK4, LINE, ACCENT, ERROR, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, PageSpacer, BtnGhost, Chip } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct SettingAgentMemoryPageFull {
  @State memories: AssistantMemory[] = [];
  @State loaded: boolean = false;
  @State draft: string = '';
  @State editingId: number = -1;
  @State editDraft: string = '';
  private ops: MemoryLibraryOps = getMemoryLibraryOps();

  aboutToAppear(): void {
    this.reload();
  }

  private reload(): void {
    this.ops.listBucket(MEMORY_BUCKET_GLOBAL).then((list: AssistantMemory[]): void => {
      this.memories = list;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private add(): void {
    const text: string = this.draft.trim();
    if (text.length === 0) return;
    this.ops.addMemory({ scope: 'core', kind: 'note', content: text })
      .then((): void => { this.draft = ''; this.reload(); });
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '核心记忆' })
      SectionHeader({ label: '新增记忆' })
      Row() {
        TextInput({ text: this.draft, placeholder: '记一条核心记忆…' })
          .fontSize(TEXT_STYLES.secondary.size)
          .fontWeight(TEXT_STYLES.secondary.weight)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK)
          .placeholderColor(INK4)
          .backgroundColor(Color.Transparent)
          .layoutWeight(1)
          .onChange((v: string): void => { this.draft = v; })
        Column() {
          BtnGhost({ label: '添加', onTap: (): void => { this.add(); } })
        }
        .margin({ left: 10 })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(Color.Transparent)
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })

      SectionHeader({ label: '记忆列表' })
      if (this.loaded && this.memories.length === 0) {
        Column() {
          Text('// 暂无核心记忆')
            .fontSize(TEXT_STYLES.meta.size)
            .fontWeight(TEXT_STYLES.meta.weight)
            .lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM)
            .fontColor(INK3)
          Text('Agent 会在对话中自动沉淀，也可手动添加')
            .fontSize(TEXT_STYLES.secondary.size)
            .fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK4)
            .margin({ top: 8 })
        }
        .width('100%')
        .padding({ top: 44, bottom: 24 })
        .alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.memories, (m: AssistantMemory, idx: number): void => {
            ListItem() {
              Column() {
                Column() {
                  if (this.editingId === m.id) {
                    Row() {
                      TextInput({ text: this.editDraft })
                        .fontSize(TEXT_STYLES.secondary.size)
                        .fontWeight(TEXT_STYLES.secondary.weight)
                        .lineHeight(TEXT_STYLES.secondary.lineHeight)
                        .fontFamily(TEXT_STYLES.secondary.family)
                        .fontColor(INK)
                        .backgroundColor(Color.Transparent)
                        .layoutWeight(1)
                        .onChange((v: string): void => { this.editDraft = v; })
                      Column() {
                        BtnGhost({ label: '保存', onTap: (): void => {
                          this.ops.updateContent(m.id, this.editDraft.trim())
                            .then((): void => { this.editingId = -1; this.reload(); });
                        } })
                      }
                      .margin({ left: 8 })
                      Column() {
                        BtnGhost({ label: '取消', onTap: (): void => { this.editingId = -1; } })
                      }
                      .margin({ left: 8 })
                    }
                    .width('100%')
                  } else {
                    Text(m.content)
                      .fontSize(TEXT_STYLES.secondary.size)
                      .fontWeight(TEXT_STYLES.secondary.weight)
                      .lineHeight(TEXT_STYLES.secondary.lineHeight)
                      .fontFamily(TEXT_STYLES.secondary.family)
                      .fontColor(INK)
                      .width('100%')
                      .onClick((): void => { this.editingId = m.id; this.editDraft = m.content; })
                  }
                  Row() {
                    Chip({ text: `${m.kind}${m.pinned ? ' · pinned' : ''}` })
                    Blank()
                    Icon({ name: 'trash', iconSize: 15, color: ERROR })
                      .onClick((): void => {
                        AlertDialog.show({
                          title: '删除记忆', message: '删除这条核心记忆？',
                          primaryButton: { value: '取消', action: (): void => {} },
                          secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
                            this.ops.deleteMemory(m.id).then((): void => { this.reload(); });
                          } },
                        });
                      })
                  }
                  .width('100%')
                  .margin({ top: 8 })
                }
                .width('100%')
                .constraintSize({ minHeight: 64 })
                .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                if (idx < this.memories.length - 1) {
                  PaperSeparator()
                }
              }
              .width('100%')
            }
          }, (m: AssistantMemory): string => `${m.id}`)
        }
        .width('100%')
        .layoutWeight(1)
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

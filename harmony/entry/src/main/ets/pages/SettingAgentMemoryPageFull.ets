// SettingAgentMemoryPageFull — 核心记忆(接 MemoryStore 真实后端)
//
// Android 基准: SettingAgentMemoryPage.kt(MemoryRepository 增删改)。
//   鸿蒙接 entry MemoryStore.ets getMemoryLibraryOps():
//   核心记忆 = MEMORY_BUCKET_GLOBAL 桶;支持 列表/新增/删除/编辑。

import type { AssistantMemory } from '@amber/chat-domain';
import { MEMORY_BUCKET_GLOBAL } from '@amber/chat-domain';
import { getMemoryLibraryOps, MemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import {
  BG, SURFACE, SURFACE2, INK, INK3, INK4, LINE, ACCENT, ERROR,
  BODY, BODY2, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';
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

      // 新增条(真实写入 MemoryStore)
      Row() {
        TextInput({ text: this.draft, placeholder: '记一条核心记忆…' })
          .fontSize(BODY2).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(Color.Transparent).layoutWeight(1)
          .onChange((v: string): void => { this.draft = v; })
        Text('添加').fontSize(BODY2).fontColor(ACCENT)
          .padding({ left: 10, right: 4 })
          .onClick((): void => { this.add(); })
      }
      .height(44)
      .backgroundColor(SURFACE2).borderRadius(12)
      .padding({ left: 14, right: 10 })
      .margin({ left: 18, right: 18, top: 10 })

      if (this.loaded && this.memories.length === 0) {
        Column() {
          Text('// 暂无核心记忆').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
          Text('Agent 会在对话中自动沉淀，也可手动添加')
            .fontSize(BODY2).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 60 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.memories, (m: AssistantMemory): void => {
            ListItem() {
              Column() {
                if (this.editingId === m.id) {
                  Row() {
                    TextInput({ text: this.editDraft })
                      .fontSize(BODY2).fontColor(INK).backgroundColor(Color.Transparent)
                      .layoutWeight(1)
                      .onChange((v: string): void => { this.editDraft = v; })
                    Text('保存').fontSize(MONO_SMALL).fontColor(ACCENT)
                      .onClick((): void => {
                        this.ops.updateContent(m.id, this.editDraft.trim())
                          .then((): void => { this.editingId = -1; this.reload(); });
                      })
                    Text('取消').fontSize(MONO_SMALL).fontColor(INK3).margin({ left: 10 })
                      .onClick((): void => { this.editingId = -1; })
                  }
                  .width('100%')
                } else {
                  Text(m.content).fontSize(BODY2).fontColor(INK)
                    .width('100%')
                    .onClick((): void => { this.editingId = m.id; this.editDraft = m.content; })
                }
                Row() {
                  Text(`${m.kind}${m.pinned ? ' · pinned' : ''}`)
                    .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono')
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
                .width('100%').margin({ top: 6 })
              }
              .padding(14)
              .backgroundColor(SURFACE)
              .border({ width: 1, color: LINE }).borderRadius(14)
              .margin({ left: 18, right: 18, bottom: 10 })
            }
          }, (m: AssistantMemory): string => `${m.id}`)
        }
        .width('100%').layoutWeight(1).padding({ top: 10 })
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

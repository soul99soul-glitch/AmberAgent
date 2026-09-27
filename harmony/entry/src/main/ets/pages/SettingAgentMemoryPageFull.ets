// SettingAgentMemoryPageFull — 核心记忆(接 MemoryStore 真实后端)
// 核心记忆 = MEMORY_BUCKET_GLOBAL 桶；支持列表/新增/删除/编辑。
// 视觉骨架:iOS 二级页设计语言(CenteredTitleBar + PlainSection + CardGroup r18 描边 + StatusChip + FooterNote)
import type { AssistantMemory } from '@amber/chat-domain';
import { MEMORY_BUCKET_GLOBAL } from '@amber/chat-domain';
import { getMemoryLibraryOps, MemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import {
  BG, INK, INK3, INK4, LINE, ACCENT, ERROR, TEXT_STYLES, GUTTER,
} from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, StatusChip, FooterNote, PageSpacer,
} from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct SettingAgentMemoryPageFull {
  @State memories: AssistantMemory[] = [];
  @State loaded: boolean = false;
  @State draft: string = '';
  @State editingId: number = -1;
  @State editDraft: string = '';
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  private ops: MemoryLibraryOps = getMemoryLibraryOps();
  private reloadInFlight: boolean = false;
  private reloadRequested: boolean = false;

  aboutToAppear(): void {
    this.reload();
  }

  onPageShow(): void {
    this.reload();
  }

  private reload(): void {
    if (this.reloadInFlight) {
      this.reloadRequested = true;
      return;
    }
    this.reloadInFlight = true;
    this.ops.listBucket(MEMORY_BUCKET_GLOBAL).then((list: AssistantMemory[]): void => {
      this.memories = list;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; }).finally((): void => {
      this.reloadInFlight = false;
      if (this.reloadRequested) {
        this.reloadRequested = false;
        this.reload();
      }
    });
  }

  private add(): void {
    const text: string = this.draft.trim();
    if (text.length === 0) return;
    this.ops.addMemory({ scope: 'core', kind: 'note', content: text })
      .then((): void => { this.draft = ''; this.reload(); });
  }

  // 新增记忆卡:iOS 白卡(r18 描边)内嵌输入行 + accent 文本动作
  @Builder
  private AddMemoryCard(): void {
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
      Text('添加')
        .fontSize(15)
        .fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.secondary.lineHeight)
        .fontFamily(TEXT_STYLES.secondary.family)
        .fontColor(this.accentColorStore)
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .textAlign(TextAlign.Center)
        .margin({ left: 8 })
        .onClick((): void => { this.add(); })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ left: 14, right: 10, top: 4, bottom: 4 })
  }

  // 单条记忆行:正文(点按进入编辑)+ kind/pinned 状态 chip + 删除
  @Builder
  private MemoryItem(m: AssistantMemory, idx: number): void {
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
          Text('保存')
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(this.accentColorStore)
            .constraintSize({ minWidth: 44, minHeight: 44 })
            .textAlign(TextAlign.Center)
            .margin({ left: 4 })
            .onClick((): void => {
              this.ops.updateContent(m.id, this.editDraft.trim())
                .then((): void => { this.editingId = -1; this.reload(); });
            })
          Text('取消')
            .fontSize(15)
            .fontWeight(FontWeight.Medium)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .constraintSize({ minWidth: 44, minHeight: 44 })
            .textAlign(TextAlign.Center)
            .margin({ left: 4 })
            .onClick((): void => { this.editingId = -1; })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
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
        StatusChip({ text: `${m.kind}${m.pinned ? ' · pinned' : ''}` })
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
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
    if (idx < this.memories.length - 1) {
      // 发丝分隔线:width('100%')+margin 在本布局下不生效,走外层 padding 内缩
      Row() {
        Row()
          .width('100%')
          .height(1)
          .backgroundColor(LINE)
      }
      .width('100%')
      .padding({ left: 14, right: 14 })
    }
  }

  // 记忆列表卡:iOS 白卡(r18 描边)内的行组
  @Builder
  private MemoryListCard(): void {
    Column() {
      ForEach(this.memories, (m: AssistantMemory, idx: number): void => {
        this.MemoryItem(m, idx);
      }, (m: AssistantMemory): string => `${m.id}`)
    }
    .width('100%')
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '核心记忆' })
      Scroll() {
        Column() {
          PlainSection({ label: '新增记忆' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.AddMemoryCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          FooterNote({ text: 'Agent 会在对话中自动沉淀，也可手动添加' })

          PlainSection({ label: '记忆列表' })
          if (this.loaded && this.memories.length === 0) {
            Column() {
              Text('暂无核心记忆')
                .fontSize(TEXT_STYLES.secondary.size)
                .fontWeight(TEXT_STYLES.secondary.weight)
                .lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK3)
              Text('Agent 会在对话中自动沉淀，也可手动添加')
                .fontSize(TEXT_STYLES.meta.size)
                .fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(TEXT_STYLES.secondary.family)
                .fontColor(INK4)
                .margin({ top: 8 })
            }
            .width('100%')
            .padding({ top: 44, bottom: 24 })
            .alignItems(HorizontalAlign.Center)
          } else if (this.memories.length > 0) {
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.MemoryListCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          }
          PageSpacer()
        }
        .width('100%')
      }
      .width('100%')
      .layoutWeight(1)
      .align(Alignment.Top)
      .scrollBar(BarState.Off)
      .edgeEffect(EdgeEffect.Spring)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

// SettingAgentMemoryPageFull — 灵魂与记忆(对齐 iOS MemoryOverviewView)
// 灵魂 = agentSoulMarkdown(每次对话注入的核心指令),查看/编辑/单次回退;
// 核心记忆 = MEMORY_BUCKET_GLOBAL 桶；支持列表/新增/删除/编辑。
// 视觉骨架:iOS 二级页设计语言(CenteredTitleBar + PlainSection + CardGroup r18 描边 + StatusChip + FooterNote)
import type { MemoryRecord, MemoryEvent } from '@amber/chat-domain';
import { getMemoryLibraryOps, MemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { loadAgentRuntimeSnapshot, updateAgentSoulMarkdown, updateRecallToggles } from '../platform_impl/AgentRuntimePrefs.ets';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { readPreviousSoulMarkdown, rollbackSoulToPrevious } from '../platform_impl/SoulTools.ets';
import {
  BG, INK, INK3, INK4, LINE, ACCENT, ERROR, OVERLAY, SURFACE, SURFACE2, TEXT_STYLES, GUTTER, MOTION_OVERLAY,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote, PageSpacer, StatusChip,
} from '../components/PageTemplate.ets';
import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { MemoryChangeLog } from '../components/MemoryChangeLog.ets';
import { countActiveMemories, isActiveMemoryRecord, memoryKindLabel } from '../components/MemoryPresentation.ets';

@Entry
@Component
struct SettingAgentMemoryPageFull {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State memories: MemoryRecord[] = [];
  @State events: MemoryEvent[] = [];
  @State activeCount: number = 0;
  @State memoryError: string = '';
  @State adding: boolean = false;
  @StorageProp('memoryRevision') @Watch('memoryChanged') memoryRevision: number = 0;
  @State loaded: boolean = false;
  @State draft: string = '';
  @State editingId: number = -1;
  @State editDraft: string = '';
  @State soulMarkdown: string = '';
  @State soulLoaded: boolean = false;
  @State soulRollbackAvailable: boolean = false;
  @State soulEditorOpen: boolean = false;
  @State soulDraft: string = '';
  @State soulNotice: string = '';
  @State soulSaving: boolean = false;
  @State soulSaveError: string = '';
  @State autoMemory: boolean = true;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  private ops: MemoryLibraryOps = getMemoryLibraryOps();
  private reloadInFlight: boolean = false;
  private reloadRequested: boolean = false;
  private previousKeyboardMode: KeyboardAvoidMode | null = null;

  aboutToAppear(): void {
    this.reload();
    this.reloadSoul();
  }

  onPageShow(): void {
    if (this.previousKeyboardMode === null) {
      this.previousKeyboardMode = this.getUIContext().getKeyboardAvoidMode();
    }
    this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
    this.reload();
    this.reloadSoul();
  }

  onPageHide(): void {
    if (this.previousKeyboardMode !== null) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardMode);
      this.previousKeyboardMode = null;
    }
  }

  aboutToDisappear(): void { this.onPageHide(); }

  onBackPress(): boolean {
    if (!this.soulEditorOpen) return false;
    if (!this.soulSaving) this.soulEditorOpen = false;
    return true;
  }

  private reloadSoul(): void {
    loadAgentRuntimeSnapshot(getChatKvStore()).then((snap: AgentRuntimeSnapshot): void => {
      this.soulMarkdown = snap.agentSoulMarkdown;
      this.autoMemory = snap.enableCoreMemory;
      this.soulLoaded = true;
    }).catch((): void => { this.soulLoaded = true; });
    readPreviousSoulMarkdown().then((previous: string | null): void => {
      this.soulRollbackAvailable = previous !== null;
    }).catch((): void => {});
  }

  private openSoulEditor(): void {
    this.soulDraft = this.soulMarkdown;
    this.soulSaveError = '';
    this.soulEditorOpen = true;
  }

  private saveSoul(): void {
    if (this.soulSaving) return;
    this.soulSaving = true;
    this.soulSaveError = '';
    updateAgentSoulMarkdown(getChatKvStore(), this.soulDraft).then((): void => {
      this.soulEditorOpen = false;
      this.soulNotice = '核心指令已保存 ✓';
      this.reloadSoul();
    }).catch((): void => {
      this.soulSaveError = '保存失败，请重试';
    }).finally((): void => { this.soulSaving = false; });
  }

  private rollbackSoul(): void {
    rollbackSoulToPrevious().then((ok: boolean): void => {
      this.soulNotice = ok ? '已回退到上一个核心指令' : '没有可回退的版本';
      this.reloadSoul();
    }).catch((): void => {
      this.soulNotice = '回退失败，请重试';
    });
  }

  private memoryChanged(): void { this.reload(); }

  private reload(): void {
    if (this.reloadInFlight) {
      this.reloadRequested = true;
      return;
    }
    this.reloadInFlight = true;
    this.ops.getAllRecords().then((list: MemoryRecord[]): Promise<void> => {
      const now: number = Date.now();
      this.activeCount = countActiveMemories(list, now);
      this.memories = list.filter((item: MemoryRecord): boolean => item.scope === 'core' && item.kind !== 'topic' && isActiveMemoryRecord(item, now));
      this.loaded = true; this.memoryError = '';
      return this.ops.getRecentEvents(200).then((events: MemoryEvent[]): void => { this.events = events; });
    }).catch((error: Error): void => { this.loaded = true; this.memoryError = error.message; }).finally((): void => {
      this.reloadInFlight = false;
      if (this.reloadRequested) {
        this.reloadRequested = false;
        this.reload();
      }
    });
  }

  private add(): void {
    const text: string = this.draft.trim();
    if (text.length === 0 || this.adding) return;
    this.adding = true; this.memoryError = '';
    this.ops.addMemory({ scope: 'core', kind: 'note', content: text })
      .then((): void => { this.draft = ''; this.reload(); })
      .catch((error: Error): void => { this.memoryError = error.message; })
      .finally((): void => { this.adding = false; });
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
      Text(this.adding ? '保存中' : '添加')
        .fontSize(15)
        .fontWeight(FontWeight.Medium)
        .lineHeight(TEXT_STYLES.secondary.lineHeight)
        .fontFamily(TEXT_STYLES.secondary.family)
        .fontColor(this.accentColorStore)
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .textAlign(TextAlign.Center)
        .margin({ left: 8 })
        .enabled(!this.adding)
        .onClick((): void => { this.add(); })
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .padding({ left: 14, right: 10, top: 4, bottom: 4 })
  }

  // 单条记忆行:正文(点按进入编辑)+ kind/pinned 状态 chip + 删除
  private setEditingMemory(id: number): void {
    this.getUIContext().animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
      this.editingId = id;
    });
  }

  @Builder
  private MemoryItem(m: MemoryRecord, idx: number): void {
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
                .then((): void => { this.setEditingMemory(-1); this.reload(); })
                .catch((error: Error): void => { this.memoryError = error.message; });
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
            .onClick((): void => { this.setEditingMemory(-1); })
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
        .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      } else {
        Text(m.content)
          .fontSize(TEXT_STYLES.secondary.size)
          .fontWeight(TEXT_STYLES.secondary.weight)
          .lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(TEXT_STYLES.secondary.family)
          .fontColor(INK)
          .width('100%')
          .onClick((): void => { this.editDraft = m.content; this.setEditingMemory(m.id); })
          .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      Row() {
        StatusChip({ text: `${memoryKindLabel(m.kind)}${m.pinned ? ' · 置顶' : ''}` })
        Blank()
        Row() { Icon({ name: 'trash', iconSize: 15, color: ERROR }) }
          .width(44).height(44).justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
          .onClick((): void => {
            AlertDialog.show({
              title: '删除记忆', message: '删除这条核心记忆？',
              primaryButton: { value: '取消', action: (): void => {} },
              secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
                this.ops.deleteMemory(m.id).then((): void => { this.reload(); })
                  .catch((error: Error): void => { this.memoryError = error.message; });
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
      ForEach(this.memories, (m: MemoryRecord, idx: number): void => {
        this.MemoryItem(m, idx);
      }, (m: MemoryRecord): string => `${m.id}`)
    }
    .width('100%')
  }

  private toggleAutoMemory(): void {
    const next: boolean = !this.autoMemory;
    this.autoMemory = next;
    updateRecallToggles(getChatKvStore(), { enableCoreMemory: next })
      .then((snap: AgentRuntimeSnapshot): void => { this.autoMemory = snap.enableCoreMemory; })
      .catch((): void => { this.reloadSoul(); });
  }

  // 自动积累卡(对齐 iOS 自动积累区;后端 = recall enableCoreMemory)
  @Builder
  private AutoMemoryCard(): void {
    CardToggleRow({ label: '召回核心记忆', desc: '在对话中使用已保存的核心记忆',
      checked: this.autoMemory, isLast: true,
      onToggle: (): void => { this.toggleAutoMemory(); } })
  }

  // 专题与文档入口(此前 MemoryPage 下线后两页失去入口);iOS 族 CardRow(28 着色方座)
  @Builder
  private MemoryEntryCard(): void {
    CardRow({ label: '管理全部记忆', desc: `${this.activeCount} 条活跃记忆 · 包含已归档记录与恢复入口`, iconName: 'brain',
      plateStyle: 'accent', plateSize: 28,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/MemoryRecordsPage' }); } })
    CardRow({ label: '记忆主题', desc: '自动归并的主题及其源记忆成员', iconName: 'brain',
      plateStyle: 'accent', plateSize: 28,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/MemoryTopicsPage' }); } })
    CardRow({ label: '记忆文档', desc: '由源记忆蒸馏出的只读 Markdown', iconName: 'doc',
      plateStyle: 'accent', plateSize: 28, isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/MemoryDocumentPage' }); } })
  }

  // 灵魂卡:核心指令全文(mono 只读预览)+ 编辑/单次回退(对齐 iOS 灵魂区)
  @Builder
  private SoulCard(): void {
    Column() {
      Scroll() {
        Text(this.soulMarkdown.trim().length === 0 ? '还没有核心指令。' : this.soulMarkdown)
          .fontSize(13)
          .lineHeight(19)
          .fontFamily(FONT_MONO)
          .fontColor(this.soulMarkdown.trim().length === 0 ? INK4 : INK)
          .width('100%')
      }
      .width('100%')
      .constraintSize({ maxHeight: 220 })
      .scrollBar(BarState.Auto)
      .align(Alignment.TopStart)

      Row() {
        Text('编辑核心指令')
          .fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.accentColorStore)
          .constraintSize({ minHeight: 44 })
          .onClick((): void => { this.openSoulEditor(); })
        Blank()
        if (this.soulRollbackAvailable) {
          Text('回退上一个版本')
            .fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor('#C27A2E')
            .constraintSize({ minHeight: 44 })
            .onClick((): void => { this.rollbackSoul(); })
        }
      }
      .width('100%')
      .margin({ top: 6 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 8 })
  }

  // 核心指令编辑叠层(取消/保存)
  @Builder
  private SoulEditor(): void {
    Column() {
      Blank()
        .width('100%')
        .onClick((): void => { if (!this.soulSaving) this.soulEditorOpen = false; })
      Column() {
        Text('编辑核心指令')
          .fontSize(16).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
          .width('100%').padding({ top: 16, bottom: 10 })
        if (this.soulSaveError.length > 0) {
          Text(this.soulSaveError).fontSize(13).fontColor(ERROR)
            .width('100%').padding({ bottom: 8 })
        }
        TextArea({ text: this.soulDraft, placeholder: '每次对话都会注入的核心行为指令…' })
          .id('soul-editor-input')
          .width('100%')
          .layoutWeight(1)
          .fontSize(13).fontColor(INK).fontFamily(FONT_MONO)
          .placeholderColor(INK4)
          .backgroundColor(SURFACE2)
          .borderRadius(10)
          .padding(12)
          .enabled(!this.soulSaving)
          .onChange((v: string): void => { this.soulDraft = v; })
        Row() {
          Text('取消')
            .fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.soulSaving)
            .onClick((): void => { this.soulEditorOpen = false; })
          Text(this.soulSaving ? '保存中…' : '保存')
            .id('soul-editor-save')
            .fontSize(14).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColorStore)
            .layoutWeight(1).textAlign(TextAlign.Center).constraintSize({ minHeight: 44 })
            .enabled(!this.soulSaving)
            .onClick((): void => { this.saveSoul(); })
        }
        .width('100%')
        .padding({ top: 10, bottom: 14 })
      }
      .width('100%').height('85%')
      .padding({ left: 18, right: 18, bottom: this.navBarHeight })
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 20, topRight: 20 })
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%')
    .backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(TransitionEffect.OPACITY
      .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  @Builder
  private ChangeLogCard(): void {
    MemoryChangeLog({ events: this.events, accentColor: this.accentColorStore })
  }

  build(): void {
    Stack() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Column() {
        CenteredTitleBar({ title: '灵魂与记忆', subtitle: 'Amber 的核心指令与长期记忆' })
        Scroll() {
          Column() {
            PlainSection({ label: '灵魂' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SoulCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '核心指令会在每次对话开头注入，约束 Agent 的长期行为。' })
            if (this.soulNotice.length > 0) {
              Text(this.soulNotice)
                .fontSize(TEXT_STYLES.meta.size).fontColor(this.accentColorStore)
                .width('100%').padding({ left: GUTTER, right: GUTTER, top: 6 })
            }

            PlainSection({ label: '记忆召回' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.AutoMemoryCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '记忆管理' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.MemoryEntryCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '新增记忆' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.AddMemoryCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: 'Agent 会在对话中自动沉淀，也可手动添加' })

            if (this.memoryError.length > 0) {
              Text(this.memoryError).fontSize(13).fontColor(ERROR).width('100%')
                .padding({ left: GUTTER, right: GUTTER, top: 8 }).wordBreak(WordBreak.BREAK_ALL)
            }
            PlainSection({ label: '核心记忆列表' })
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
            PlainSection({ label: '记忆变更记录' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ChangeLogCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
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

      if (this.soulEditorOpen) {
        this.SoulEditor()
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
  }
}

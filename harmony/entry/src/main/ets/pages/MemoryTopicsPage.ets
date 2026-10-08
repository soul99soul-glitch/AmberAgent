import { router } from '@kit.ArkUI';
import type { MemoryRecord } from '@amber/chat-domain';
import { isMemoryTopicSource } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { getMemoryTopicStoreOps } from '../platform_impl/MemoryStore.ets';
import { getMemoryTopicCoordinator } from '../platform_impl/MemoryTopicOps.ets';
import type { MemoryTopicRunState } from '../platform_impl/MemoryTopicOps.ets';
import { loadAgentRuntimeSnapshot, updateMemoryWorkerToggles } from '../platform_impl/AgentRuntimePrefs.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT, MOTION_OVERLAY } from '../design/tokens.ets';
import { FONT_MONO_MEDIUM, FONT_SANS_MEDIUM } from '../design/fonts.ets';

@Entry
@Component
struct MemoryTopicsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State topics: MemoryRecord[] = [];
  @State sources: MemoryRecord[] = [];
  @State runState: MemoryTopicRunState = getMemoryTopicCoordinator().state();
  @State loaded: boolean = false;
  @State modelEnabled: boolean = false;
  @State writeEnabled: boolean = false;
  @State settingsSaving: boolean = false;
  @State syncing: boolean = false;
  @State sourcesExpanded: boolean = false;
  @State error: string = '';
  @State notice: string = '';
  @State colors: SSHUIColors = new SSHUIColors();
  @StorageProp('darkMode') @Watch('refreshColors') darkMode: boolean = false;
  @StorageProp('themePackId') @Watch('refreshColors') themePackId: string = '';
  @StorageProp('accentColor') @Watch('refreshColors') accentColor: string = ACCENT;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('memoryRevision') @Watch('memoryChanged') memoryRevision: number = 0;
  private alive: boolean = false;
  private visible: boolean = false;
  private loadSeq: number = 0;
  private unsubscribe: (() => void) | null = null;

  aboutToAppear(): void { this.alive = true; this.enter(); }
  onPageShow(): void { if (!this.visible) this.enter(); }
  onPageHide(): void { this.visible = false; this.loadSeq++; this.unsubscribe?.(); this.unsubscribe = null; }
  aboutToDisappear(): void { this.alive = false; this.onPageHide(); }
  private refreshColors(): void { this.colors = new SSHUIColors(); }
  private memoryChanged(): void { if (this.alive && this.visible) this.reload(); }

  private enter(): void {
    this.visible = true;
    this.refreshColors();
    this.unsubscribe?.();
    this.unsubscribe = getMemoryTopicCoordinator().subscribe((state: MemoryTopicRunState): void => {
      if (!this.alive || !this.visible) return;
      const settled: boolean = this.runState.running && !state.running;
      this.runState = state;
      if (settled) this.reload();
    });
    this.reload();
  }

  private async reload(): Promise<void> {
    const seq: number = ++this.loadSeq;
    try {
      const records: MemoryRecord[] = await getMemoryTopicStoreOps().snapshotForTopics();
      const runtime = await loadAgentRuntimeSnapshot(getChatKvStore());
      if (!this.alive || !this.visible || seq !== this.loadSeq) return;
      this.topics = records.filter((item: MemoryRecord): boolean => item.kind === 'topic');
      this.sources = records.filter((item: MemoryRecord): boolean => item.kind !== 'topic' && item.scope !== 'core');
      this.modelEnabled = runtime.memoryWorker.dreamModelEnabled;
      this.writeEnabled = runtime.memoryWorker.topicWriteEnabled;
      this.loaded = true;
      this.error = '';
    } catch (error) {
      if (this.alive && this.visible && seq === this.loadSeq) {
        this.loaded = true;
        this.error = error instanceof Error ? error.message : String(error);
      }
    }
  }

  private async changeGate(model: boolean, value: boolean): Promise<void> {
    if (this.settingsSaving) return;
    this.settingsSaving = true;
    this.error = '';
    try {
      const runtime = await updateMemoryWorkerToggles(getChatKvStore(), model
        ? { dreamModelEnabled: value } : { topicWriteEnabled: value });
      if (!this.alive) return;
      this.modelEnabled = runtime.memoryWorker.dreamModelEnabled;
      this.writeEnabled = runtime.memoryWorker.topicWriteEnabled;
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? error.message : String(error);
    } finally { if (this.alive) this.settingsSaving = false; }
  }

  private async run(): Promise<void> {
    this.error = ''; this.notice = '';
    try { await getMemoryTopicCoordinator().run(true); }
    catch (error) { if (this.alive) this.error = error instanceof Error ? error.message : String(error); }
  }

  private async syncDocuments(): Promise<void> {
    if (this.syncing) return;
    this.syncing = true; this.error = ''; this.notice = '';
    try {
      await getMemoryTopicStoreOps().syncDocuments();
      if (this.alive) this.notice = '记忆文档已刷新';
    } catch (error) {
      if (this.alive) this.error = error instanceof Error ? error.message : String(error);
    } finally { if (this.alive) this.syncing = false; }
  }

  private statusText(): string {
    if (this.runState.running) return '正在整理主题、画像与同义重复…';
    if (this.runState.status === 'completed') {
      return `整理完成 · ${this.runState.topicCount} 个主题`
        + (this.runState.profileUpdated ? ' · 用户画像已更新' : '')
        + (this.runState.mergedCount > 0 ? ` · ${this.runState.mergedCount} 条重复已归档` : '');
    }
    if (this.runState.status === 'missing_model') return '未配置可用的整理模型';
    if (this.runState.status === 'cancelled') return this.runState.sourceSaved ? '已取消 · 记忆已保存' : '已取消，本次整理未写入';
    if (this.runState.status === 'timed_out') return this.runState.sourceSaved ? '整理超时 · 记忆已保存' : '整理超时，本次整理未写入';
    if (this.runState.status === 'failed') return '记忆整理失败';
    if (this.runState.status === 'skipped') return '本次未执行整理';
    return '尚未运行记忆整理';
  }

  private sourceLabel(item: MemoryRecord): string {
    const state: string = item.archived ? '已归档' : item.expiresAt !== null && item.expiresAt <= Date.now() ? '已过期' : '有效';
    return `#${item.id} · ${item.scope} / ${item.kind} · ${state}`;
  }

  private openTopic(id: number): void {
    router.pushUrl({ url: 'pages/MemoryTopicDetailPage', params: { topicId: id.toString() } });
  }

  // 整理设置:iOS 药丸开关行 + 任务模型导航行
  @Builder
  private SettingsCard(): void {
    CardToggleRow({ label: '启用整理模型', desc: '使用已配置的白日梦 / 记忆任务模型整理主题、用户画像和同义重复。',
      checked: this.modelEnabled, onToggle: (): void => { this.changeGate(true, !this.modelEnabled); } })
    CardToggleRow({ label: '允许写入整理结果', desc: '生成主题和用户画像，并归档已确认的同义重复；原文保留，可在全部记忆中恢复。',
      checked: this.writeEnabled, onToggle: (): void => { this.changeGate(false, !this.writeEnabled); } })
    CardRow({ label: '配置任务模型', desc: '白日梦 / 记忆提取使用的模型', iconName: 'cpu',
      plateStyle: 'accent', plateSize: 28, isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/ChatTaskModelsPage' }); } })
  }

  // 手动整理:状态 + 文本动作(立即整理/取消),脚注说明调度限制
  @Builder
  private RunCard(): void {
    Column({ space: 8 }) {
      Text(this.statusText()).fontSize(16).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
        .fontColor(this.colors.ink).width('100%')
      if (this.runState.error.length > 0) {
        Text(this.runState.error).fontSize(12).fontColor(this.colors.secondary).width('100%').wordBreak(WordBreak.BREAK_ALL)
      }
      if (this.runState.staleCount > 0) {
        Text(`${this.runState.staleCount} 组源记忆已变化，旧摘要未保存。`)
          .fontSize(12).fontColor(this.colors.warning).width('100%')
      }
      if (this.runState.documentError.length > 0) {
        Text(this.runState.sourceSaved ? '源记录已保存，派生文档刷新失败。' : '派生文档刷新失败。')
          .fontSize(12).fontColor(this.colors.error).width('100%')
        Text(this.runState.documentError).fontSize(12).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
      }
      Row() {
        Text(this.runState.running ? '取消本次整理' : '立即整理')
          .fontSize(15).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(this.runState.running || this.loaded && this.modelEnabled && this.writeEnabled && !this.settingsSaving
            ? this.accentColor : this.colors.muted)
          .constraintSize({ minHeight: 44 })
          .enabled(this.runState.running || this.loaded && this.modelEnabled && this.writeEnabled && !this.settingsSaving)
          .onClick((): void => { if (this.runState.running) getMemoryTopicCoordinator().cancel(); else this.run(); })
      }.width('100%').justifyContent(FlexAlign.Center).margin({ top: 4 })
    }.width('100%').padding({ left: 14, right: 14, top: 12, bottom: 10 })
  }

  // 主题行组:白卡内行式条目(标题/成员数/摘要 3 行 + 箭头),发丝线内缩
  @Builder
  private TopicRows(): void {
    Column() {
      ForEach(this.topics, (item: MemoryRecord, idx: number): void => {
        Row({ space: 10 }) {
          Column({ space: 6 }) {
            Text(item.topicTitle ?? `主题 #${item.id}`).fontSize(15).fontWeight(FontWeight.Medium)
              .fontFamily(FONT_SANS_MEDIUM).fontColor(this.colors.ink).width('100%')
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(item.archived ? '已归档 · 原摘要已失效，重新整理后可生成有效主题' : `${item.memberIds.length} 条源记忆 · 自动维护`)
              .fontSize(12).fontColor(item.archived ? this.colors.warning : this.colors.secondary).width('100%')
            Text(item.content).fontSize(13).fontColor(this.colors.secondary).width('100%')
              .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
        }
        .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
        .onClick((): void => { this.openTopic(item.id); })
        if (idx < this.topics.length - 1) {
          Row() {
            Row().width('100%').height(1).backgroundColor(this.colors.line)
          }.width('100%').padding({ left: 14, right: 14 })
        }
      }, (item: MemoryRecord): string => `${item.id}-${item.updatedAt}-${item.archived}`)
    }.width('100%')
  }

  // 源记忆卡:展开行(计数 + 旋转箭头);展开后卡内列出条目行
  @Builder
  private SourcesCard(): void {
    Column() {
      Row({ space: 12 }) {
        Column({ space: 2 }) {
          Text('源记忆').fontSize(16).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(this.colors.ink)
          Text('自动沉淀的原始条目').fontSize(12).fontColor(this.colors.secondary)
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(`${this.sources.length} 条 · ${this.sources.filter((item: MemoryRecord): boolean => isMemoryTopicSource(item, Date.now())).length} 有效`)
          .fontSize(12).fontFamily(FONT_MONO_MEDIUM).fontColor(this.colors.muted).maxLines(1)
        Icon({ name: 'chevron_down', iconSize: 16, color: this.colors.muted })
          .rotate({ angle: this.sourcesExpanded ? 180 : 0 })
      }
      .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .onClick((): void => {
        animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
          this.sourcesExpanded = !this.sourcesExpanded;
        });
      })
      if (this.sourcesExpanded) {
        ForEach(this.sources, (item: MemoryRecord): void => {
          Row() {
            Row().width('100%').height(1).backgroundColor(this.colors.line)
          }.width('100%').padding({ left: 14, right: 14 })
          Row({ space: 10 }) {
            Column({ space: 6 }) {
              Text(this.sourceLabel(item)).fontSize(12).fontColor(this.colors.secondary).width('100%')
              Text(item.content).fontSize(14).fontColor(this.colors.ink).width('100%')
                .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
            }.alignItems(HorizontalAlign.Start).layoutWeight(1)
            Icon({ name: 'chevron_right', iconSize: 16, color: this.colors.muted })
          }
          .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
          .padding({ left: 14, right: 14, top: 10, bottom: 12 })
          .onClick((): void => { router.pushUrl({ url: 'pages/MemoryRecordPage', params: { memoryId: item.id.toString() } }); })
        }, (item: MemoryRecord): string => `${item.id}-${item.updatedAt}-${item.archived}`)
      }
    }.width('100%')
  }

  // 记忆文档入口 + 重新生成(动作行,无箭头)
  @Builder
  private DocsCard(): void {
    CardRow({ label: '查看记忆文档', desc: '只读 Markdown 索引与主题文档', iconName: 'doc',
      plateStyle: 'accent', plateSize: 28,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/MemoryDocumentPage' }); } })
    CardRow({ label: this.syncing ? '正在重新生成…' : '重新生成文档', desc: '按当前源记忆刷新派生文档',
      iconName: 'retry', plateStyle: 'accent', plateSize: 28, isLast: true, showChevron: false,
      onRowClick: (): void => { if (!this.syncing) this.syncDocuments(); } })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '记忆主题' })
      Scroll() {
        Column() {
          PlainSection({ label: '整理设置' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.SettingsCard(); } })
          PlainSection({ label: '手动整理' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.RunCard(); } })
          FooterNote({ text: '手动整理跳过空闲、充电和每日调度限制，仍遵守模型开关、写入许可、取消与超时。自动整理只在应用前台运行。' })
          if (this.error.length > 0) {
            Column({ space: 4 }) {
              Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
              Text('重新读取').fontSize(14).fontFamily(FONT_SANS_MEDIUM).fontColor(this.accentColor)
                .constraintSize({ minHeight: 44 }).onClick((): void => { this.reload(); })
            }.width('100%').padding({ left: 2, right: 2 })
          }
          if (this.notice.length > 0) Text(this.notice).fontSize(13).fontColor(this.colors.secondary).width('100%')

          PlainSection({ label: `主题 · ${this.topics.length}` })
          if (!this.loaded) {
            Text('正在读取记忆…').fontSize(13).fontColor(this.colors.secondary).width('100%')
              .textAlign(TextAlign.Center).padding({ top: 24, bottom: 12 })
          } else if (this.topics.length === 0) {
            Text('尚无主题。至少两条有效的非核心源记忆才能归组。')
              .fontSize(13).fontColor(this.colors.secondary).width('100%')
              .textAlign(TextAlign.Center).padding({ top: 24, bottom: 12 })
          } else {
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.TopicRows(); } })
          }

          PlainSection({ label: '源记忆' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.SourcesCard(); } })

          PlainSection({ label: '记忆文档' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.DocsCard(); } })
          PageSpacer()
        }.width('100%').padding({ left: 16, right: 16, top: 2, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').height('100%').backgroundColor(this.colors.background)
  }
}

import { router } from '@kit.ArkUI';
import type { MemoryRecord } from '@amber/chat-domain';
import { isMemoryTopicSource } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { getMemoryTopicStoreOps } from '../platform_impl/MemoryStore.ets';
import { getMemoryTopicCoordinator } from '../platform_impl/MemoryTopicOps.ets';
import type { MemoryTopicRunState } from '../platform_impl/MemoryTopicOps.ets';
import { loadAgentRuntimeSnapshot, updateMemoryWorkerToggles } from '../platform_impl/AgentRuntimePrefs.ets';
import { CenteredTitleBar, CardGroup } from '../components/PageTemplate.ets';
import { SSHUIColors } from '../components/SSHUIColors.ets';
import { ACCENT } from '../design/tokens.ets';

@Entry
@Component
struct MemoryTopicsPage {
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
    if (this.runState.running) return '正在归组主题…';
    if (this.runState.status === 'completed') return `归组完成 · ${this.runState.topicCount} 个主题`;
    if (this.runState.status === 'missing_model') return '未配置可用的整理模型';
    if (this.runState.status === 'cancelled') return this.runState.sourceSaved ? '已取消 · 源记录已保存' : '已取消，本次主题未写入';
    if (this.runState.status === 'timed_out') return this.runState.sourceSaved ? '整理超时 · 源记录已保存' : '整理超时，本次主题未写入';
    if (this.runState.status === 'failed') return '主题整理失败';
    if (this.runState.status === 'skipped') return '本次未执行整理';
    return '尚未运行主题整理';
  }

  private sourceLabel(item: MemoryRecord): string {
    const state: string = item.archived ? '已归档' : item.expiresAt !== null && item.expiresAt <= Date.now() ? '已过期' : '有效';
    return `#${item.id} · ${item.scope} / ${item.kind} · ${state}`;
  }

  private openTopic(id: number): void {
    router.pushUrl({ url: 'pages/MemoryTopicDetailPage', params: { topicId: id.toString() } });
  }

  @Builder
  private SettingsCard(): void {
    Column({ space: 12 }) {
      Row({ space: 12 }) {
        Column({ space: 4 }) {
          Text('启用整理模型').fontSize(14).fontColor(this.colors.ink).width('100%')
          Text('使用已配置的白日梦 / 记忆任务模型归组已保存的源记忆。')
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        }.layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.modelEnabled }).selectedColor(this.accentColor)
          .enabled(!this.settingsSaving).onChange((value: boolean): void => { this.changeGate(true, value); })
      }.width('100%').constraintSize({ minHeight: 52 })
      Row({ space: 12 }) {
        Column({ space: 4 }) {
          Text('允许写入派生主题').fontSize(14).fontColor(this.colors.ink).width('100%')
          Text('保存主题摘要和成员关联；源记忆的内容与身份保持。')
            .fontSize(12).fontColor(this.colors.secondary).width('100%')
        }.layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.writeEnabled }).selectedColor(this.accentColor)
          .enabled(!this.settingsSaving).onChange((value: boolean): void => { this.changeGate(false, value); })
      }.width('100%').constraintSize({ minHeight: 52 })
      Button('配置任务模型').type(ButtonType.Normal).height(44).width('100%').fontSize(14)
        .fontColor(this.accentColor).backgroundColor(this.colors.inset)
        .onClick((): void => { router.pushUrl({ url: 'pages/ChatTaskModelsPage' }); })
    }.width('100%').padding(16)
  }

  @Builder
  private RunCard(): void {
    Column({ space: 10 }) {
      Text(this.statusText()).fontSize(14).fontColor(this.colors.ink).width('100%')
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
      Button(this.runState.running ? '取消本次整理' : '立即整理').type(ButtonType.Normal).height(44).width('100%')
        .fontSize(14).fontColor(this.accentColor).backgroundColor(this.colors.inset)
        .enabled(this.runState.running || this.loaded && this.modelEnabled && this.writeEnabled && !this.settingsSaving)
        .onClick((): void => { if (this.runState.running) getMemoryTopicCoordinator().cancel(); else this.run(); })
      Text('手动整理跳过空闲、充电和每日调度限制，仍遵守模型开关、写入许可、取消与超时。自动整理只在应用前台运行。')
        .fontSize(12).fontColor(this.colors.secondary).width('100%')
      Row({ space: 8 }) {
        Button('记忆文档').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset)
          .onClick((): void => { router.pushUrl({ url: 'pages/MemoryDocumentPage' }); })
        Button(this.syncing ? '刷新中' : '刷新文档').type(ButtonType.Normal).height(44).layoutWeight(1).fontSize(14)
          .fontColor(this.accentColor).backgroundColor(this.colors.inset).enabled(!this.syncing)
          .onClick((): void => { this.syncDocuments(); })
      }.width('100%')
    }.width('100%').padding(16)
  }

  @Builder
  private TopicList(): void {
    Column({ space: 10 }) {
      Text(`主题 · ${this.topics.length}`).fontSize(15).fontColor(this.colors.ink).width('100%')
      if (!this.loaded) {
        Text('正在读取记忆…').fontSize(13).fontColor(this.colors.secondary).width('100%')
      } else if (this.topics.length === 0) {
        Text('尚无主题。至少两条有效的非核心源记忆才能归组。')
          .fontSize(13).fontColor(this.colors.secondary).width('100%')
      }
      ForEach(this.topics, (item: MemoryRecord): void => {
        Column({ space: 6 }) {
          Text(item.topicTitle ?? `主题 #${item.id}`).fontSize(15).fontWeight(FontWeight.Medium)
            .fontColor(this.colors.ink).width('100%').maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(item.archived ? '已归档 · 原摘要已失效，重新整理后可生成有效主题' : `${item.memberIds.length} 条源记忆 · 自动维护`)
            .fontSize(12).fontColor(item.archived ? this.colors.warning : this.colors.secondary).width('100%')
          Text(item.content).fontSize(13).fontColor(this.colors.secondary).width('100%')
            .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
        }.width('100%').padding(16).constraintSize({ minHeight: 52 })
          .backgroundColor(this.colors.surface).borderRadius(14).border({ width: 1, color: this.colors.line })
          .onClick((): void => { this.openTopic(item.id); })
      }, (item: MemoryRecord): string => `${item.id}-${item.updatedAt}-${item.archived}`)
      Button(`${this.sourcesExpanded ? '收起' : '查看'}源记忆 · ${this.sources.length} 条（${this.sources.filter((item: MemoryRecord): boolean => isMemoryTopicSource(item, Date.now())).length} 条有效）`)
        .type(ButtonType.Normal).height(44).width('100%').fontSize(13).fontColor(this.accentColor).backgroundColor(this.colors.inset)
        .onClick((): void => { this.sourcesExpanded = !this.sourcesExpanded; })
      if (this.sourcesExpanded) {
        ForEach(this.sources, (item: MemoryRecord): void => {
          Column({ space: 6 }) {
            Text(this.sourceLabel(item)).fontSize(12).fontColor(this.colors.secondary).width('100%')
            Text(item.content).fontSize(14).fontColor(this.colors.ink).width('100%')
              .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
          }.width('100%').padding(16).constraintSize({ minHeight: 52 }).backgroundColor(this.colors.surface).borderRadius(14)
            .onClick((): void => { router.pushUrl({ url: 'pages/MemoryRecordPage', params: { memoryId: item.id.toString() } }); })
        }, (item: MemoryRecord): string => `${item.id}-${item.updatedAt}-${item.archived}`)
      }
    }.width('100%')
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: '记忆主题' })
      Scroll() {
        Column({ space: 12 }) {
          CardGroup({ content: (): void => { this.SettingsCard(); } })
          CardGroup({ content: (): void => { this.RunCard(); } })
          if (this.error.length > 0) {
            Text(this.error).fontSize(13).fontColor(this.colors.error).width('100%').wordBreak(WordBreak.BREAK_ALL)
            Button('重新读取').height(44).fontSize(14).fontColor(this.accentColor).backgroundColor(this.colors.inset)
              .onClick((): void => { this.reload(); })
          }
          if (this.notice.length > 0) Text(this.notice).fontSize(13).fontColor(this.colors.secondary).width('100%')
          this.TopicList()
        }.width('100%').padding({ left: 16, right: 16, top: 12, bottom: 16 + this.navBarHeight }).justifyContent(FlexAlign.Start)
      }.width('100%').layoutWeight(1).align(Alignment.TopStart)
    }.width('100%').height('100%').backgroundColor(this.colors.background)
  }
}

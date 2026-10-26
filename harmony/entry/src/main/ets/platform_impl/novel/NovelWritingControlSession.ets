import { NovelPlanDraftStore, NovelControlPlanDraft, sameControlPlanDraft } from '../../novel/NovelPlanDraftState';
import { composerOwner } from '../../novel/NovelComposerState';
import type { NovelModelOptionView } from '../../components/NovelSettingsPane.ets';
import { defaultNovelModelDefaults, resolveNovelDefaultTarget } from '@amber/deepread-domain';
import type {
  NovelCreation, NovelProject, NovelWorkspaceStatus, NovelModelDefaults, NovelModelPolicy,
  NovelModelTarget, NovelBranchSettings,
} from '@amber/deepread-domain';

type ControlPlanDraft = NovelControlPlanDraft;

// Runtime services and canonical workspace state stay with their existing owners.
export interface NovelWritingControlPorts {
  creation: NovelCreation;
  planDraftStore: NovelPlanDraftStore;
  isNovelProduct: boolean;
  loadModelOptions: () => Promise<NovelModelOptionView[]>;
  loadModelDefaults: () => Promise<NovelModelDefaults>;
  describeChatModel: () => Promise<string>;
  currentProject: () => NovelProject | null;
  workspaceStatus: () => NovelWorkspaceStatus | null;
  projectId: () => string;
  hasGhostwriteJob: () => boolean;
  isBusy: () => boolean;
  setBusy: (value: boolean) => void;
  isAlive: () => boolean;
  isVisible: () => boolean;
  isBackgrounded: () => boolean;
  getError: () => string;
  setError: (value: string) => void;
  showOperationError: (action: string, error: Object) => void;
  reload: () => Promise<void>;
}

@Observed
export class NovelWritingControlSession {
  controlSheetOpen: boolean = false;
  controlTab: number = 0;
  planThisChapter: string = '';
  planFuture: string = '';
  planPreferences: string = '';
  controlNotice: string = '';
  controlPlanReady: boolean = false;
  controlPlanLoading: boolean = false;
  controlPlanError: string = '';
  private controlPlanSession: number = 0;
  private controlPlanWriteRevision: number = 0;
  private controlPlanOwner: string = '';
  private controlPlanBaseline: ControlPlanDraft = { thisChapterPlan: '', futurePlan: '', preferences: '' };
  private controlPlanDrafts: Map<string, ControlPlanDraft> = new Map();
  composerModelSheetOpen: boolean = false;
  composerModelSession: number = 0;
  composerModelError: string = '';
  novelModelDefaults: NovelModelDefaults = defaultNovelModelDefaults();
  chatModelLabel: string = '';
  modelOptionsReady: boolean = false;
  modelOptionsLoading: boolean = false;
  modelOptionsError: string = '';
  private modelOptionsLoadToken: number = 0;
  modelOptions: NovelModelOptionView[] = [];
  private connectedPorts: NovelWritingControlPorts | null = null;

  // Called on the page's @State observed proxy before async work begins.
  start(ports: NovelWritingControlPorts): void {
    this.connectedPorts = ports;
  }

  private ports(): NovelWritingControlPorts {
    if (this.connectedPorts === null) throw new Error('Writing control session has not started');
    return this.connectedPorts;
  }

  hide(): void {
    this.closeControlSheet();
    if (this.composerModelSheetOpen) this.closeComposerModelSheet(this.composerModelSession, true);
    this.modelOptionsLoadToken++;
  }

  background(): void {
    this.retainControlPlanDraft();
    if (this.composerModelSheetOpen) this.closeComposerModelSheet(this.composerModelSession, true);
  }

  async loadProjectModelOptions(): Promise<void> {
    const token: number = ++this.modelOptionsLoadToken;
    this.modelOptionsLoading = true;
    this.modelOptionsReady = false;
    this.modelOptionsError = '';
    try {
      const options: NovelModelOptionView[] = await this.ports().loadModelOptions();
      const defaults: NovelModelDefaults = this.ports().isNovelProduct
        ? await this.ports().loadModelDefaults() : defaultNovelModelDefaults();
      const chatLabel: string = this.ports().isNovelProduct ? await this.ports().describeChatModel() : '';
      if (!this.ports().isAlive() || token !== this.modelOptionsLoadToken) return;
      this.modelOptions = options.map((option: NovelModelOptionView): NovelModelOptionView => ({
        providerId: option.providerId,
        modelId: option.modelId,
        label: option.label,
        providerLabel: option.providerLabel,
        available: option.available,
      }));
      this.novelModelDefaults = defaults;
      this.chatModelLabel = chatLabel;
      this.modelOptionsReady = true;
    } catch (error) {
      if (this.ports().isAlive() && token === this.modelOptionsLoadToken) {
        this.modelOptionsError = `模型列表加载失败：${String(error)}`;
        this.ports().setError(this.modelOptionsError);
      }
    } finally {
      if (this.ports().isAlive() && token === this.modelOptionsLoadToken) this.modelOptionsLoading = false;
    }
  }

  async saveNovelModelPolicy(policy: NovelModelPolicy): Promise<void> {
    if (this.ports().isBusy()) return;
    this.ports().setBusy(true);
    try {
      await this.ports().creation.setModelPolicy(this.ports().projectId(), policy);
      this.ports().setError('');
      await this.ports().reload();
    } catch (error) {
      this.ports().showOperationError('保存模型职责失败', error);
    } finally {
      this.ports().setBusy(false);
    }
  }

  writingModelLabel(currentProject: NovelProject | null = this.ports().currentProject()): string {
    const p: NovelProject | null = currentProject;
    if (p === null) return this.ports().isNovelProduct ? '选择模型' : '跟随全局';
    const writing: NovelModelTarget = p.modelPolicy.writing;
    if (this.ports().isNovelProduct) {
      if (!this.modelOptionsReady) return this.modelOptionsLoading ? '读取模型…' : '模型配置读取失败';
      const effective: NovelModelTarget = resolveNovelDefaultTarget(p.modelPolicy, this.novelModelDefaults, 'writing');
      const label: string = this.writingTargetLabel(effective);
      return writing.kind === 'global' ? `小说默认 · ${label}` : label;
    }
    if (writing.kind === 'global') return '跟随全局';
    const option: NovelModelOptionView | undefined = this.modelOptions.find(
      (o: NovelModelOptionView): boolean => o.providerId === writing.providerId && o.modelId === writing.modelId);
    return option !== undefined ? option.label : writing.modelId;
  }

  writingTargetLabel(target: NovelModelTarget): string {
    if (target.kind === 'global') return this.chatModelLabel.length > 0 ? this.chatModelLabel : '未配置可用模型';
    const option: NovelModelOptionView | undefined = this.modelOptions.find(
      (item: NovelModelOptionView): boolean => item.providerId === target.providerId && item.modelId === target.modelId);
    if (option === undefined) return `模型已移除 · ${target.modelId}`;
    return `${option.label}${option.available ? '' : '（当前不可用）'}`;
  }

  defaultWritingModelLabel(): string {
    return this.modelOptionsReady ? this.writingTargetLabel(this.novelModelDefaults.writing) : '读取模型配置后选择';
  }

  openComposerModelSheet(): void {
    if (!this.ports().isNovelProduct) { this.openControlSheet(); return; }
    if (this.ports().currentProject() === null || this.ports().isBusy() || !this.ports().isAlive() ||
      !this.ports().isVisible() || this.ports().isBackgrounded()) return;
    this.composerModelSession++;
    this.composerModelError = '';
    this.composerModelSheetOpen = true;
    this.loadProjectModelOptions();
  }

  composerModelSessionActive(session: number): boolean {
    return this.ports().isAlive() && this.ports().isVisible() && !this.ports().isBackgrounded() &&
      this.composerModelSheetOpen && this.composerModelSession === session;
  }

  closeComposerModelSheet(session: number, force: boolean = false): void {
    if (session !== this.composerModelSession || (this.ports().isBusy() && !force)) return;
    this.composerModelSheetOpen = false;
    this.composerModelSession++;
    this.composerModelError = '';
  }

  async chooseComposerModel(target: NovelModelTarget, session: number): Promise<void> {
    const p: NovelProject | null = this.ports().currentProject();
    if (p === null || this.ports().isBusy() || !this.modelOptionsReady || this.modelOptionsLoading ||
      !this.composerModelSessionActive(session)) return;
    if (target.kind === 'fixed' && !this.modelOptions.some((option: NovelModelOptionView): boolean =>
      option.available && option.providerId === target.providerId && option.modelId === target.modelId)) return;
    this.composerModelError = '';
    await this.saveNovelModelPolicy({ writing: target, review: p.modelPolicy.review, stateSync: p.modelPolicy.stateSync });
    if (!this.composerModelSessionActive(session)) return;
    if (this.ports().getError().length > 0) this.composerModelError = this.ports().getError();
    else this.closeComposerModelSheet(session);
  }

  async openControlSheet(): Promise<void> {
    const p: NovelProject | null = this.ports().currentProject();
    if (p === null || this.ports().isBusy()) return;
    this.retainControlPlanDraft();
    const session: number = ++this.controlPlanSession;
    const owner: string = composerOwner(this.ports().projectId(), this.ports().workspaceStatus()?.activeBranchId ?? '');
    this.controlPlanOwner = owner;
    this.controlPlanBaseline = {
      thisChapterPlan: p.branchSettings.thisChapterPlan, futurePlan: p.branchSettings.futurePlan,
      preferences: p.branchSettings.preferences,
    };
    this.controlTab = this.ports().hasGhostwriteJob() ? 1 : 0;
    this.controlNotice = '';
    this.controlPlanError = '';
    this.controlPlanReady = false;
    this.controlSheetOpen = true;
    const retained: ControlPlanDraft | undefined = this.controlPlanDrafts.get(owner);
    if (retained !== undefined) {
      this.showControlPlanDraft(retained, !sameControlPlanDraft(retained, this.controlPlanBaseline));
      // 缓存可能来自失败的自动保存；重开时再落盘，不能用内存恢复提示掩盖失败。
      this.persistControlPlanDraft(owner, sameControlPlanDraft(retained, this.controlPlanBaseline) ? null : retained);
      return;
    }
    this.showControlPlanDraft(this.controlPlanBaseline, false);
    this.controlPlanReady = false;
    this.controlPlanLoading = true;
    try {
      const saved: ControlPlanDraft | null = await this.ports().planDraftStore.load(owner);
      if (!this.ports().isAlive() || !this.controlSheetOpen || session !== this.controlPlanSession) return;
      const restored: boolean = saved !== null && !sameControlPlanDraft(saved, this.controlPlanBaseline);
      if (restored && saved !== null) this.controlPlanDrafts.set(owner, saved);
      this.showControlPlanDraft(restored && saved !== null ? saved : this.controlPlanBaseline, restored);
    } catch (error) {
      if (this.ports().isAlive() && session === this.controlPlanSession) {
        this.controlPlanError = '读取计划草稿失败：' + String(error);
      }
    } finally {
      if (this.ports().isAlive() && session === this.controlPlanSession) this.controlPlanLoading = false;
    }
  }

  private showControlPlanDraft(plan: ControlPlanDraft, restored: boolean): void {
    this.planThisChapter = plan.thisChapterPlan;
    this.planFuture = plan.futurePlan;
    this.planPreferences = plan.preferences;
    this.controlPlanReady = true;
    this.controlPlanLoading = false;
    this.controlNotice = restored ? '已恢复未保存的计划草稿，请核对后保存。' : '';
  }

  closeControlSheet(): void {
    if (!this.controlSheetOpen) return;
    this.retainControlPlanDraft();
    this.controlPlanSession++;
    this.controlSheetOpen = false;
    this.controlPlanLoading = false;
  }

  changeControlPlan(field: string, value: string): void {
    if (!this.controlPlanReady || this.ports().isBusy()) return;
    if (field === 'chapter') this.planThisChapter = value;
    else if (field === 'future') this.planFuture = value;
    else this.planPreferences = value;
    this.retainControlPlanDraft();
  }

  retainControlPlanDraft(): void {
    if (this.controlPlanOwner.length === 0 || !this.controlPlanReady) return;
    const owner: string = this.controlPlanOwner;
    const plan: ControlPlanDraft = {
      thisChapterPlan: this.planThisChapter, futurePlan: this.planFuture, preferences: this.planPreferences,
    };
    const previous: ControlPlanDraft | undefined = this.controlPlanDrafts.get(owner);
    const clean: boolean = sameControlPlanDraft(plan, this.controlPlanBaseline);
    if (clean && previous === undefined) return;
    if (!clean && previous !== undefined && sameControlPlanDraft(plan, previous)) return;
    // 清理落盘成功前保留撤回后的基线，失败重开时不能复活旧磁盘草稿。
    this.controlPlanDrafts.set(owner, plan);
    this.persistControlPlanDraft(owner, clean ? null : plan);
  }

  private persistControlPlanDraft(owner: string, plan: ControlPlanDraft | null): void {
    const revision: number = ++this.controlPlanWriteRevision;
    const clearing: ControlPlanDraft | undefined = this.controlPlanDrafts.get(owner);
    this.ports().planDraftStore.save(owner, plan).then((): void => {
      if (plan === null && this.controlPlanDrafts.get(owner) === clearing) this.controlPlanDrafts.delete(owner);
      if (this.ports().isAlive() && this.controlPlanOwner === owner && revision === this.controlPlanWriteRevision) {
        this.controlPlanError = '';
        this.controlNotice = plan === null ? '' : '草稿已保存，尚未应用到当前分支。';
      }
    }).catch((error: Object): void => {
      if (this.ports().isAlive() && this.controlPlanOwner === owner && revision === this.controlPlanWriteRevision) {
        this.controlPlanError = '计划草稿保存失败，当前输入仍保留：' + String(error);
      }
    });
  }

  retryControlPlanDraft(): void {
    if (!this.controlPlanReady || this.ports().isBusy()) return;
    const plan: ControlPlanDraft = {
      thisChapterPlan: this.planThisChapter, futurePlan: this.planFuture, preferences: this.planPreferences,
    };
    this.persistControlPlanDraft(this.controlPlanOwner, sameControlPlanDraft(plan, this.controlPlanBaseline) ? null : plan);
  }

  selectWritingTarget(target: NovelModelTarget): void {
    if (this.composerModelSheetOpen) {
      this.chooseComposerModel(target, this.composerModelSession);
      return;
    }
    const p: NovelProject | null = this.ports().currentProject();
    if (p === null || this.ports().isBusy()) return;
    this.saveNovelModelPolicy({
      writing: target, review: p.modelPolicy.review, stateSync: p.modelPolicy.stateSync,
    });
  }

  writingTargetSelected(option: NovelModelOptionView | null,
    currentProject: NovelProject | null = this.ports().currentProject()): boolean {
    const p: NovelProject | null = currentProject;
    if (p === null) return false;
    const writing: NovelModelTarget = p.modelPolicy.writing;
    if (option === null) return writing.kind === 'global';
    if (writing.kind !== 'fixed') return false;
    return writing.providerId === option.providerId && writing.modelId === option.modelId;
  }

  async savePlanPrefs(): Promise<void> {
    const p: NovelProject | null = this.ports().currentProject();
    if (p === null || this.ports().isBusy() || !this.controlPlanReady) return;
    const owner: string = this.controlPlanOwner;
    const s: NovelBranchSettings = p.branchSettings;
    const savedPlan: ControlPlanDraft = {
      thisChapterPlan: this.planThisChapter, futurePlan: this.planFuture, preferences: this.planPreferences,
    };
    const settings: NovelBranchSettings = {
      chapterContract: s.chapterContract, upcomingArc: s.upcomingArc, suggestedChapterCount: s.suggestedChapterCount,
      thisChapterPlan: savedPlan.thisChapterPlan, futurePlan: savedPlan.futurePlan, preferences: savedPlan.preferences,
      foreshadows: s.foreshadows, confirmedDecisions: s.confirmedDecisions,
    };
    await this.savePlanBranchSettings(settings);
    if (this.ports().getError().length === 0) {
      this.controlPlanDrafts.set(owner, savedPlan);
      if (this.controlPlanOwner === owner) {
        this.controlPlanBaseline = savedPlan;
        this.controlNotice = '已保存到当前分支';
      }
      this.ports().setBusy(true);
      try {
        await this.ports().planDraftStore.save(owner, null);
        if (this.controlPlanDrafts.get(owner) === savedPlan) this.controlPlanDrafts.delete(owner);
        if (this.ports().isAlive() && this.controlPlanOwner === owner) this.controlPlanError = '';
      } catch (error) {
        if (this.ports().isAlive() && this.controlPlanOwner === owner) {
          this.controlPlanError = '正式计划已保存，但清理草稿失败：' + String(error);
        }
      } finally {
        this.ports().setBusy(false);
      }
    }
  }

  private async savePlanBranchSettings(settings: NovelBranchSettings): Promise<void> {
    this.ports().setBusy(true);
    try {
      await this.ports().creation.setBranchSettings(this.ports().projectId(), settings);
      this.ports().setError('');
      await this.ports().reload();
    } catch (error) {
      this.ports().showOperationError('保存分支设定失败', error);
    } finally {
      this.ports().setBusy(false);
    }
  }
}

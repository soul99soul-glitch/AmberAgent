import type {
  NovelCreation, NovelProject, NovelWorkspaceStatus, NovelMaterial, NovelMaterialKind,
  NovelMaterialFields, NovelWorkspaceCas, NovelWorkspaceSnapshot, NovelMaterialSuggestion,
  NovelSettingProposal, NovelBranchSettings, NovelForeshadow, NovelConfirmedDecision, NovelMaterialAdoption,
} from '@amber/deepread-domain';
import {
  makeNovelMaterial, emptyNovelBranchSettings, buildSuggestionAdoption,
  buildSettingProposalAdoption, materialAdoptionTargetDigest,
} from '@amber/deepread-domain';
import {
  NovelMaterialEditorDraft, NovelMaterialEditScope, emptyMaterialEditorDraft, materialEditorDraftFor,
} from '../../components/NovelMaterialEditorSheet.ets';
import type { NovelMaterialAdoptionSheetTarget } from '../../components/NovelMaterialAdoptionSheet.ets';
import { ERROR } from '../../design/tokens.ets';

export interface NovelCompendiumPorts {
  creation: NovelCreation;
  projectId: () => string;
  currentProject: () => NovelProject | null;
  currentStatus: () => NovelWorkspaceStatus | null;
  pageAlive: () => boolean;
  isBusy: () => boolean;
  setBusy: (busy: boolean) => void;
  readError: () => string;
  setError: (message: string) => void;
  reload: () => Promise<void>;
  hasBlockingNovelRun: () => boolean;
  showOperationError: (title: string, error: Error | string) => void;
}

// Local editor state lives here; canonical projects and the shared operation lock remain with the page.
@Observed
export class NovelCompendiumSession {
  settingTab: number = 0;
  projectModelsVisible: boolean = false;
  fsTitle: string = '';
  fsContent: string = '';
  decTitle: string = '';
  decContent: string = '';
  expandedMaterialId: string = '';
  materialEditorOpen: boolean = false;
  editingMaterialId: string = '';
  materialEditorDraft: NovelMaterialEditorDraft = emptyMaterialEditorDraft();
  materialEditorKey: string = '';
  materialCloseRequest: number = 0;
  private materialEditorCas: NovelWorkspaceCas | null = null;
  private materialOpenToken: number = 0;
  private materialCreatedAt: number = 0;
  adoptionOpen: boolean = false;
  adoptionKind: NovelMaterialKind = 'world';
  adoptionTitle: string = '';
  adoptionBody: string = '';
  adoptionFields: NovelMaterialFields = {};
  adoptionTargets: NovelMaterialAdoptionSheetTarget[] = [];
  adoptionDraftKey: string = '';
  adoptionError: string = '';
  private adoptionBaseline: NovelMaterialAdoption | null = null;
  private adoptionTargetDigests: Map<string, string> = new Map();
  private adoptionSourceId: string = '';
  private adoptionIsSetting: boolean = false;
  private ports: NovelCompendiumPorts | null = null;

  // Call through the page's observed proxy so asynchronous completions update V1 subscribers.
  start(ports: NovelCompendiumPorts): void { this.ports = ports; }

  private get host(): NovelCompendiumPorts {
    if (this.ports === null) throw new Error('资料会话尚未启动');
    return this.ports;
  }
  private get creation(): NovelCreation { return this.host.creation; }
  private get projectId(): string { return this.host.projectId(); }
  private get project(): NovelProject | null { return this.host.currentProject(); }
  private get workspaceStatus(): NovelWorkspaceStatus | null { return this.host.currentStatus(); }
  private get pageAlive(): boolean { return this.host.pageAlive(); }
  private get busy(): boolean { return this.host.isBusy(); }
  private set busy(value: boolean) { this.host.setBusy(value); }
  private get errorMsg(): string { return this.host.readError(); }
  private set errorMsg(value: string) { this.host.setError(value); }
  private reload(): Promise<void> { return this.host.reload(); }
  private hasBlockingNovelRun(): boolean { return this.host.hasBlockingNovelRun(); }
  private showWorkspaceOperationError(title: string, error: Error | string): void {
    this.host.showOperationError(title, error);
  }

  invalidateMaterialOpen(): void { this.materialOpenToken++; }
  cancelMaterialEditor(): void {
    if (this.busy) return;
    this.materialEditorOpen = false;
    this.invalidateMaterialOpen();
  }

  confirmDiscardMaterial(): void {
    if (!this.busy) this.materialCloseRequest++;
  }

  async resolveSettingProposal(proposal: NovelSettingProposal, accept: boolean): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    if (accept) {
      this.openMaterialAdoption(proposal.id, true);
      return;
    }
    this.busy = true;
    try {
      await this.creation.resolveSettingProposal(this.projectId, proposal.id, false);
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async saveNovelBranchSettings(settings: NovelBranchSettings): Promise<void> {
    if (this.busy) return;
    this.busy = true;
    try {
      await this.creation.setBranchSettings(this.projectId, settings);
      this.errorMsg = '';
      await this.reload();
    } catch (error) {
      this.showWorkspaceOperationError('保存分支设定失败', error);
    } finally {
      this.busy = false;
    }
  }

  materialsIn(kinds: NovelMaterialKind[], currentProject: NovelProject | null = this.project): NovelMaterial[] {
    const p: NovelProject | null = currentProject;
    if (p === null) return [];
    return p.materials.filter((m: NovelMaterial): boolean => kinds.indexOf(m.kind) >= 0);
  }

  pendingSuggestionsIn(kinds: NovelMaterialKind[], currentProject: NovelProject | null = this.project): NovelMaterialSuggestion[] {
    const p: NovelProject | null = currentProject;
    if (p === null) return [];
    return p.materialSuggestions.filter(
      (s: NovelMaterialSuggestion): boolean => s.status === 'pending' && kinds.indexOf(s.kind) >= 0);
  }

  pendingSettingProposalsIn(kinds: NovelMaterialKind[], currentProject: NovelProject | null = this.project): NovelSettingProposal[] {
    const p: NovelProject | null = currentProject;
    if (p === null) return [];
    return p.settingProposals.filter(
      (proposal: NovelSettingProposal): boolean => proposal.status === 'pending' && kinds.indexOf(proposal.kind) >= 0);
  }

  branchSettingsWith(foreshadows: NovelForeshadow[], decisions: NovelConfirmedDecision[]): NovelBranchSettings {
    const p: NovelProject | null = this.project;
    if (p === null) return emptyNovelBranchSettings();
    return {
      chapterContract: p.branchSettings.chapterContract,
      upcomingArc: p.branchSettings.upcomingArc,
      suggestedChapterCount: p.branchSettings.suggestedChapterCount,
      thisChapterPlan: p.branchSettings.thisChapterPlan,
      futurePlan: p.branchSettings.futurePlan,
      preferences: p.branchSettings.preferences,
      foreshadows: foreshadows,
      confirmedDecisions: decisions,
    };
  }

  async addStoryForeshadow(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    const title: string = this.fsTitle.trim();
    const content: string = this.fsContent.trim();
    if (title.length === 0 || content.length === 0) {
      this.errorMsg = '请填写伏笔标题和内容后再新增。';
      return;
    }
    const now: number = Date.now();
    const added: NovelForeshadow = {
      id: `foreshadow-${now}-${p.branchSettings.foreshadows.length + 1}`,
      title: title, content: content, status: 'open', createdAt: now, resolvedAt: null,
    };
    await this.saveNovelBranchSettings(this.branchSettingsWith(
      p.branchSettings.foreshadows.concat([added]), p.branchSettings.confirmedDecisions));
    if (this.errorMsg.length === 0) {
      this.fsTitle = '';
      this.fsContent = '';
    }
  }

  async toggleStoryForeshadow(id: string): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    const now: number = Date.now();
    const updated: NovelForeshadow[] = p.branchSettings.foreshadows.map((item: NovelForeshadow): NovelForeshadow => {
      if (item.id !== id) return item;
      if (item.status === 'open') {
        return { id: item.id, title: item.title, content: item.content, status: 'resolved',
          createdAt: item.createdAt, resolvedAt: now };
      }
      return { id: item.id, title: item.title, content: item.content, status: 'open',
        createdAt: item.createdAt, resolvedAt: null };
    });
    await this.saveNovelBranchSettings(this.branchSettingsWith(updated, p.branchSettings.confirmedDecisions));
  }

  async addStoryDecision(): Promise<void> {
    const p: NovelProject | null = this.project;
    if (p === null || this.busy) return;
    const title: string = this.decTitle.trim();
    const content: string = this.decContent.trim();
    if (title.length === 0 || content.length === 0) {
      this.errorMsg = '请填写决定标题和内容后再确认。';
      return;
    }
    const now: number = Date.now();
    const added: NovelConfirmedDecision = {
      id: `decision-${now}-${p.branchSettings.confirmedDecisions.length + 1}`,
      title: title, content: content, confirmedAt: now,
    };
    await this.saveNovelBranchSettings(this.branchSettingsWith(
      p.branchSettings.foreshadows, p.branchSettings.confirmedDecisions.concat([added])));
    if (this.errorMsg.length === 0) {
      this.decTitle = '';
      this.decContent = '';
    }
  }

  openForeshadows(currentProject: NovelProject | null = this.project): NovelForeshadow[] {
    const p: NovelProject | null = currentProject;
    if (p === null) return [];
    return p.branchSettings.foreshadows.filter((f: NovelForeshadow): boolean => f.status === 'open');
  }

  resolvedForeshadows(currentProject: NovelProject | null = this.project): NovelForeshadow[] {
    const p: NovelProject | null = currentProject;
    if (p === null) return [];
    return p.branchSettings.foreshadows.filter((f: NovelForeshadow): boolean => f.status === 'resolved');
  }

  async openNewMaterial(kind: NovelMaterialKind = 'world'): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const token: number = ++this.materialOpenToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await this.creation.readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || token !== this.materialOpenToken || this.busy || this.hasBlockingNovelRun()) return;
      const draft: NovelMaterialEditorDraft = emptyMaterialEditorDraft();
      draft.kind = kind;
      this.materialEditorDraft = draft;
      this.materialEditorCas = snapshot.status.cas;
      this.materialCreatedAt = Date.now();
      this.editingMaterialId = '';
      this.materialEditorKey = `new-${token}`;
      this.errorMsg = '';
      this.materialEditorOpen = true;
    } catch (error) { if (this.pageAlive && token === this.materialOpenToken) this.showWorkspaceOperationError('无法新建资料', error); }
  }

  async openEditMaterial(material: NovelMaterial, scope: NovelMaterialEditScope = 'branch'): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const token: number = ++this.materialOpenToken;
    try {
      const snapshot: NovelWorkspaceSnapshot = await this.creation.readWorkspaceSnapshot(this.projectId);
      if (!this.pageAlive || token !== this.materialOpenToken || this.busy || this.hasBlockingNovelRun()) return;
      const effective: NovelMaterial | undefined = snapshot.project.materials.find((item: NovelMaterial): boolean => item.id === material.id);
      if (effective === undefined) throw new Error('此资料已不在当前分支，请刷新后再编辑');
      const base: NovelMaterial | undefined = snapshot.project.baseMaterials?.find((item: NovelMaterial): boolean => item.id === material.id);
      const source: NovelMaterial = scope === 'shared' && base !== undefined ? base : effective;
      this.materialEditorDraft = materialEditorDraftFor(source, scope);
      this.materialEditorCas = snapshot.status.cas;
      this.materialCreatedAt = source.createdAt;
      this.editingMaterialId = source.id;
      this.materialEditorKey = `${source.id}-${scope}-${token}`;
      this.errorMsg = '';
      this.materialEditorOpen = true;
    } catch (error) { if (this.pageAlive && token === this.materialOpenToken) this.showWorkspaceOperationError('无法编辑资料', error); }
  }

  async saveMaterial(draft: NovelMaterialEditorDraft): Promise<void> {
    if (this.busy || !this.materialEditorOpen || !this.pageAlive || this.hasBlockingNovelRun()) return;
    const cas: NovelWorkspaceCas | null = this.materialEditorCas;
    if (cas === null) { this.errorMsg = '资料编辑缺少工作区快照，请重新打开'; return; }
    this.busy = true;
    try {
      let savedId: string = this.editingMaterialId;
      const fields: NovelMaterialFields = { aliases: draft.aliases, tags: draft.tags,
        customKind: draft.customKind, injectionMode: draft.injectionMode };
      if (draft.scope === 'shared') {
        const material: NovelMaterial = makeNovelMaterial({ id: this.editingMaterialId,
          kind: draft.kind, title: draft.title, content: draft.content, enabled: draft.enabled,
          aliases: fields.aliases, tags: fields.tags, customKind: fields.customKind, injectionMode: fields.injectionMode, now: Date.now() });
        material.createdAt = this.materialCreatedAt;
        await this.creation.saveSharedMaterial(this.projectId, material, cas);
        savedId = material.id;
      } else {
        const saved: NovelMaterial = await this.creation.upsertMaterial(
          this.projectId, this.editingMaterialId.length > 0 ? this.editingMaterialId : null,
          draft.kind, draft.title, draft.content, draft.enabled, fields, cas);
        savedId = saved.id;
      }
      if (!this.pageAlive) return;
      this.materialEditorOpen = false;
      this.expandedMaterialId = savedId;
      this.errorMsg = '';
      await this.reload();
    } catch (error) { if (this.pageAlive) this.errorMsg = error instanceof Error ? error.message : String(error); }
    finally { if (this.pageAlive) this.busy = false; }
  }

  confirmRestoreMaterialInheritance(material: NovelMaterial): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const cas: NovelWorkspaceCas | null = this.workspaceStatus?.cas ?? null;
    if (cas === null) return;
    AlertDialog.show({ title: '恢复资料继承？',
      message: `当前分支重新使用“${material.title}”的最新共享底稿；此分支的覆盖或隐藏设置会移除，其他分支保持原样。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '恢复继承', action: (): void => { this.restoreMaterialInheritance(material.id, cas); } } });
  }

  async restoreMaterialInheritance(materialId: string, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await this.creation.restoreMaterialInheritance(this.projectId, materialId, cas);
      await this.reload();
    } catch (error) { if (this.pageAlive) this.showWorkspaceOperationError('恢复资料继承失败', error); }
    finally { if (this.pageAlive) this.busy = false; }
  }

  confirmDeleteMaterial(material: NovelMaterial): void {
    if (this.busy || this.hasBlockingNovelRun()) return;
    const cas: NovelWorkspaceCas | null = this.workspaceStatus?.cas ?? null;
    if (cas === null) return;
    const shared: boolean = this.project?.baseMaterials?.some((base: NovelMaterial): boolean => base.id === material.id) ?? false;
    AlertDialog.show({ title: shared ? '在当前分支移除此资料？' : '删除当前分支资料？',
      message: shared ? `当前分支将隐藏“${material.title}”。共享底稿和其他分支保留，可通过恢复继承重新显示。`
        : `删除当前分支的“${material.title}”，其他分支保持原样。`,
      primaryButton: { value: '取消', action: (): void => {} },
      secondaryButton: { value: '确认移除', fontColor: ERROR, action: (): void => { this.deleteMaterial(material.id, cas); } } });
  }

  async deleteMaterial(id: string, cas: NovelWorkspaceCas): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    this.busy = true;
    try {
      await this.creation.deleteMaterial(this.projectId, id, cas);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  async resolveSuggestion(id: string, accept: boolean): Promise<void> {
    if (this.busy || this.hasBlockingNovelRun()) return;
    if (accept) {
      this.openMaterialAdoption(id, false);
      return;
    }
    this.busy = true;
    try {
      await this.creation.resolveMaterialSuggestion(this.projectId, id, false);
      await this.reload();
    } catch (e) {
      this.errorMsg = String(e);
    } finally {
      this.busy = false;
    }
  }

  openMaterialAdoption(sourceId: string, isSetting: boolean): void {
    const project: NovelProject | null = this.project;
    if (project === null || this.busy || this.hasBlockingNovelRun()) return;
    try {
      let baseline: NovelMaterialAdoption;
      let sourceFields: NovelMaterialFields;
      if (isSetting) {
        const source: NovelSettingProposal | undefined = project.settingProposals.find(
          (proposal: NovelSettingProposal): boolean => proposal.id === sourceId);
        if (source === undefined) return;
        baseline = buildSettingProposalAdoption(project, source, null, this.workspaceStatus?.activeBranchId ?? null);
        sourceFields = { aliases: source.aliases, tags: source.tags, customKind: source.customKind, injectionMode: source.injectionMode };
      } else {
        const source: NovelMaterialSuggestion | undefined = project.materialSuggestions.find(
          (suggestion: NovelMaterialSuggestion): boolean => suggestion.id === sourceId);
        if (source === undefined) return;
        baseline = buildSuggestionAdoption(project, source, null, this.workspaceStatus?.activeBranchId ?? null);
        sourceFields = { aliases: source.aliases, tags: source.tags, customKind: source.customKind, injectionMode: source.injectionMode };
      }
      this.adoptionBaseline = baseline;
      this.adoptionSourceId = sourceId;
      this.adoptionIsSetting = isSetting;
      this.adoptionKind = baseline.kind;
      this.adoptionTitle = baseline.title;
      this.adoptionBody = baseline.content;
      this.adoptionFields = sourceFields;
      this.adoptionTargets = project.materials.map((material: NovelMaterial): NovelMaterialAdoptionSheetTarget => {
        return { id: material.id, kind: material.kind, title: material.title, body: material.content, enabled: material.enabled,
          aliases: material.aliases, tags: material.tags, customKind: material.customKind, injectionMode: material.injectionMode };
      });
      this.adoptionTargetDigests = new Map();
      project.materials.forEach((material: NovelMaterial): void => {
        this.adoptionTargetDigests.set(material.id, materialAdoptionTargetDigest(material));
      });
      this.adoptionDraftKey = `${sourceId}-${Date.now()}`;
      this.adoptionError = '';
      this.adoptionOpen = true;
    } catch (e) {
      this.errorMsg = e instanceof Error ? e.message : String(e);
    }
  }

  async confirmMaterialAdoption(
    kind: NovelMaterialKind, title: string, body: string, targetId: string | null, fields: NovelMaterialFields,
  ): Promise<void> {
    if (this.busy || this.adoptionBaseline === null) return;
    const digest: string | null = targetId === null ? null : this.adoptionTargetDigests.get(targetId) ?? null;
    if (targetId !== null && digest === null) {
      this.adoptionError = '目标不在本次预览中，请重新打开后选择';
      return;
    }
    const edit: NovelMaterialAdoption = {
      kind, title, content: body, targetMaterialId: targetId, targetDigest: digest,
      sourceDigest: this.adoptionBaseline.sourceDigest, branchId: this.adoptionBaseline.branchId,
      aliases: fields.aliases, tags: fields.tags, customKind: fields.customKind, injectionMode: fields.injectionMode,
    };
    this.busy = true;
    this.adoptionError = '';
    try {
      if (this.adoptionIsSetting) {
        await this.creation.resolveSettingProposal(this.projectId, this.adoptionSourceId, true, edit);
      } else {
        await this.creation.resolveMaterialSuggestion(this.projectId, this.adoptionSourceId, true, edit);
      }
      this.adoptionOpen = false;
      this.adoptionBaseline = null;
      this.errorMsg = '';
      await this.reload();
    } catch (e) {
      if (this.pageAlive) this.adoptionError = e instanceof Error ? e.message : String(e);
    } finally {
      this.busy = false;
    }
  }

  cancelAdoption(): void {
    if (this.busy) return;
    this.adoptionOpen = false;
    this.adoptionBaseline = null;
  }

  hiddenSharedMaterialsIn(kinds: NovelMaterialKind[], currentProject: NovelProject | null = this.project): NovelMaterial[] {
    const project: NovelProject | null = currentProject;
    if (project === null) return [];
    return (project.baseMaterials ?? []).filter((material: NovelMaterial): boolean =>
      kinds.includes(material.kind) && (project.hiddenMaterialIds ?? []).includes(material.id));
  }
}

// NovelSettingsPane — 小说工作区的 branch durable setting 编辑面板。
// 本组件只管理表单草稿；持久化、CAS 与错误归因由宿主回调负责。

import {
  ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR, INK, INK2, INK3, INK4,
  LINE, RAISED, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import type {
  NovelBranchSettings, NovelConfirmedDecision, NovelForeshadow,
  NovelModelPolicy, NovelModelTarget, NovelProject,
} from '@amber/deepread-domain';

export interface NovelModelOptionView {
  providerId: string;
  modelId: string;
  label: string;
  providerLabel: string;
  available: boolean;
}

@Component
export struct NovelSettingsPane {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('onProjectChanged') project: NovelProject;
  @Prop modelOptions: NovelModelOptionView[] = [];
  @Prop busy: boolean = false;

  onSaveModelPolicy: (policy: NovelModelPolicy) => void = (_policy: NovelModelPolicy): void => {};
  onSaveBranchSettings: (settings: NovelBranchSettings) => void =
    (_settings: NovelBranchSettings): void => {};
  onNewMaterial: () => void = (): void => {};

  // 设定 tab 重组(iOS Compendium 分段):计划偏好已入项目控制 sheet,伏笔/决定入剧情区,
  // 资料由各分类页自理;「更多」页传 false 只留模型职责
  @Prop showPlanSections: boolean = true;
  @Prop showStorySections: boolean = true;
  @Prop showMaterialsEntry: boolean = true;

  @State private writingTarget: NovelModelTarget = { kind: 'global' };
  @State private reviewTarget: NovelModelTarget | null = null;
  @State private stateSyncTarget: NovelModelTarget | null = null;
  @State private thisChapterPlan: string = '';
  @State private futurePlan: string = '';
  @State private preferences: string = '';
  @State private foreshadows: NovelForeshadow[] = [];
  @State private decisions: NovelConfirmedDecision[] = [];
  @State private newForeshadowTitle: string = '';
  @State private newForeshadowContent: string = '';
  @State private newDecisionTitle: string = '';
  @State private newDecisionContent: string = '';
  @State private planFeedback: string = '';
  @State private planFeedbackField: string = '';
  @State private foreshadowFeedback: string = '';
  @State private decisionFeedback: string = '';
  private loadedProjectId: string = '';

  aboutToAppear(): void {
    this.loadedProjectId = this.project.id;
    this.loadProjectDraft();
    this.clearFeedback();
  }

  private onProjectChanged(): void {
    const projectChanged: boolean = this.loadedProjectId !== this.project.id;
    this.loadedProjectId = this.project.id;
    this.loadProjectDraft();
    if (projectChanged) this.clearFeedback();
  }

  private loadProjectDraft(): void {
    const policy: NovelModelPolicy = this.project.modelPolicy;
    const settings: NovelBranchSettings = this.project.branchSettings;
    this.writingTarget = this.copyTarget(policy.writing);
    this.reviewTarget = this.copyOptionalTarget(policy.review);
    this.stateSyncTarget = this.copyOptionalTarget(policy.stateSync);
    this.thisChapterPlan = settings.thisChapterPlan;
    this.futurePlan = settings.futurePlan;
    this.preferences = settings.preferences;
    this.foreshadows = this.copyForeshadows(settings.foreshadows);
    this.decisions = this.copyDecisions(settings.confirmedDecisions);
  }

  private clearFeedback(): void {
    this.planFeedback = '';
    this.planFeedbackField = '';
    this.foreshadowFeedback = '';
    this.decisionFeedback = '';
  }

  private copyTarget(target: NovelModelTarget): NovelModelTarget {
    if (target.kind === 'global') return { kind: 'global' };
    return { kind: 'fixed', providerId: target.providerId, modelId: target.modelId };
  }

  private copyOptionalTarget(target: NovelModelTarget | null): NovelModelTarget | null {
    return target === null ? null : this.copyTarget(target);
  }

  private copyForeshadows(values: NovelForeshadow[]): NovelForeshadow[] {
    const copied: NovelForeshadow[] = [];
    for (let i: number = 0; i < values.length; i++) {
      const value: NovelForeshadow = values[i];
      copied.push({
        id: value.id,
        title: value.title,
        content: value.content,
        status: value.status,
        createdAt: value.createdAt,
        resolvedAt: value.resolvedAt,
      });
    }
    return copied;
  }

  private copyDecisions(values: NovelConfirmedDecision[]): NovelConfirmedDecision[] {
    const copied: NovelConfirmedDecision[] = [];
    for (let i: number = 0; i < values.length; i++) {
      const value: NovelConfirmedDecision = values[i];
      copied.push({
        id: value.id,
        title: value.title,
        content: value.content,
        confirmedAt: value.confirmedAt,
      });
    }
    return copied;
  }

  private targetFor(option: NovelModelOptionView): NovelModelTarget {
    return { kind: 'fixed', providerId: option.providerId, modelId: option.modelId };
  }

  private sameTarget(left: NovelModelTarget | null, right: NovelModelTarget | null): boolean {
    if (left === null || right === null) return left === right;
    if (left.kind !== right.kind) return false;
    if (left.kind === 'global' || right.kind === 'global') return true;
    return left.providerId === right.providerId && left.modelId === right.modelId;
  }

  private optionFor(target: NovelModelTarget | null): NovelModelOptionView | null {
    if (target === null || target.kind !== 'fixed') return null;
    for (let i: number = 0; i < this.modelOptions.length; i++) {
      const option: NovelModelOptionView = this.modelOptions[i];
      if (option.providerId === target.providerId && option.modelId === target.modelId) return option;
    }
    return null;
  }

  private targetLabel(target: NovelModelTarget | null, inherited: string): string {
    if (target === null) return inherited;
    if (target.kind === 'global') return '使用应用中该职责的默认模型';
    const option: NovelModelOptionView | null = this.optionFor(target);
    if (option !== null) return `${option.providerLabel} · ${option.label}`;
    return `${target.providerId} · ${target.modelId}`;
  }

  private targetUnavailable(target: NovelModelTarget | null): boolean {
    if (target === null || target.kind !== 'fixed') return false;
    const option: NovelModelOptionView | null = this.optionFor(target);
    return option === null || !option.available;
  }

  private targetNotice(target: NovelModelTarget | null, role: string): string {
    if (!this.targetUnavailable(target)) return '';
    return `${role}固定模型“${this.targetLabel(target, '')}”当前不可用；已保留原绑定，未自动替换。`;
  }

  private inheritedSelected(selected: NovelModelTarget | null, role: string): boolean {
    return role === 'writing'
      ? selected !== null && selected.kind === 'global'
      : selected === null;
  }

  private foreshadowsFor(status: 'open' | 'resolved'): NovelForeshadow[] {
    return this.foreshadows.filter(
      (item: NovelForeshadow): boolean => item.status === status);
  }

  private savePolicy(): void {
    const policy: NovelModelPolicy = {
      writing: this.copyTarget(this.writingTarget),
      review: this.copyOptionalTarget(this.reviewTarget),
      stateSync: this.copyOptionalTarget(this.stateSyncTarget),
    };
    this.onSaveModelPolicy(policy);
  }

  private saveSettings(): void {
    const settings: NovelBranchSettings = {
      thisChapterPlan: this.thisChapterPlan,
      futurePlan: this.futurePlan,
      preferences: this.preferences,
      foreshadows: this.copyForeshadows(this.foreshadows),
      confirmedDecisions: this.copyDecisions(this.decisions),
    };
    this.onSaveBranchSettings(settings);
  }

  private saveText(title: string, field: string): void {
    if (this.busy) return;
    this.saveSettings();
    this.planFeedback = `${title}已提交保存。`;
    this.planFeedbackField = field;
  }

  private chooseWritingGlobal(): void {
    if (this.busy) return;
    this.writingTarget = { kind: 'global' };
    this.planFeedback = '';
    this.savePolicy();
  }

  private chooseWritingFixed(option: NovelModelOptionView): void {
    if (this.busy) return;
    this.writingTarget = this.targetFor(option);
    this.planFeedback = '';
    this.savePolicy();
  }

  private chooseReviewFollowWriting(): void {
    if (this.busy) return;
    this.reviewTarget = null;
    this.planFeedback = '';
    this.savePolicy();
  }

  private chooseReviewFixed(option: NovelModelOptionView): void {
    if (this.busy) return;
    this.reviewTarget = this.targetFor(option);
    this.planFeedback = '';
    this.savePolicy();
  }

  private chooseStateSyncNone(): void {
    if (this.busy) return;
    this.stateSyncTarget = null;
    this.planFeedback = '';
    this.savePolicy();
  }

  private chooseStateSyncFixed(option: NovelModelOptionView): void {
    if (this.busy) return;
    this.stateSyncTarget = this.targetFor(option);
    this.planFeedback = '';
    this.savePolicy();
  }

  private addForeshadow(): void {
    if (this.busy) return;
    const title: string = this.newForeshadowTitle.trim();
    const content: string = this.newForeshadowContent.trim();
    if (title.length === 0 || content.length === 0) {
      this.foreshadowFeedback = '请填写伏笔标题和内容后再新增。';
      return;
    }
    const now: number = Date.now();
    const added: NovelForeshadow = {
      id: `foreshadow-${now}-${this.foreshadows.length + 1}`,
      title,
      content,
      status: 'open',
      createdAt: now,
      resolvedAt: null,
    };
    this.foreshadows = this.foreshadows.concat([added]);
    this.newForeshadowTitle = '';
    this.newForeshadowContent = '';
    this.foreshadowFeedback = '伏笔已提交保存。';
    this.saveSettings();
  }

  private toggleForeshadow(id: string): void {
    if (this.busy) return;
    const now: number = Date.now();
    const updated: NovelForeshadow[] = [];
    for (let i: number = 0; i < this.foreshadows.length; i++) {
      const item: NovelForeshadow = this.foreshadows[i];
      if (item.id !== id) {
        updated.push(item);
      } else if (item.status === 'open') {
        updated.push({
          id: item.id, title: item.title, content: item.content, status: 'resolved',
          createdAt: item.createdAt, resolvedAt: now,
        });
      } else {
        updated.push({
          id: item.id, title: item.title, content: item.content, status: 'open',
          createdAt: item.createdAt, resolvedAt: null,
        });
      }
    }
    this.foreshadows = updated;
    this.foreshadowFeedback = '伏笔状态已提交保存。';
    this.saveSettings();
  }

  private addDecision(): void {
    if (this.busy) return;
    const title: string = this.newDecisionTitle.trim();
    const content: string = this.newDecisionContent.trim();
    if (title.length === 0 || content.length === 0) {
      this.decisionFeedback = '请填写决定标题和内容后再确认。';
      return;
    }
    const now: number = Date.now();
    const added: NovelConfirmedDecision = {
      id: `decision-${now}-${this.decisions.length + 1}`,
      title,
      content,
      confirmedAt: now,
    };
    this.decisions = this.decisions.concat([added]);
    this.newDecisionTitle = '';
    this.newDecisionContent = '';
    this.decisionFeedback = '决定已提交保存。';
    this.saveSettings();
  }

  build(): void {
    Scroll() {
      Column() {
        this.modelPolicySection();
        if (this.showPlanSections) {
          this.planFilesSection();
        }
        if (this.showStorySections) {
          this.foreshadowSection();
          this.decisionSection();
        }
        if (this.showMaterialsEntry) {
          this.materialLibrarySection();
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 28 })
      .alignItems(HorizontalAlign.Start)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    .backgroundColor(SURFACE)
  }

  @Builder
  private modelPolicySection(): void {
    Column() {
      Text('模型职责').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
      Text('固定模型不可用时会保留绑定并禁止静默替换。')
        .fontSize(12).fontColor(INK3).margin({ top: 4, bottom: 12 })
      this.modelRole('写作模型', '使用默认写作模型', this.writingTarget, 'writing')
      this.modelRole('审稿模型', '默认审稿模型或写作模型', this.reviewTarget, 'review')
      this.modelRole('剧情同步模型', '默认剧情同步模型或写作模型', this.stateSyncTarget, 'stateSync')
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
    .margin({ bottom: 14 })
  }

  @Builder
  private modelRole(title: string, inherited: string, selected: NovelModelTarget | null, role: string): void {
    Column() {
      Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
      Text(this.targetLabel(selected, inherited)).fontSize(12)
        .fontColor(this.targetUnavailable(selected) ? ERROR : INK3).margin({ top: 3, bottom: 8 })
      if (this.targetNotice(selected, title).length > 0) {
        Text(this.targetNotice(selected, title))
          .fontSize(12).fontColor(ERROR).lineHeight(18).margin({ bottom: 8 })
      }
      this.inheritedTargetButton(inherited, selected, role)
      if (this.modelOptions.length === 0) {
        Text('暂无可选择的固定模型。').fontSize(12).fontColor(INK3)
          .width('100%').padding({ top: 8, bottom: 8 })
      } else {
        ForEach(this.modelOptions, (option: NovelModelOptionView): void => {
          this.modelOptionButton(option, selected, role)
        }, (option: NovelModelOptionView): string => `${option.providerId}:${option.modelId}`)
      }
    }
    .width('100%').padding({ top: 10, bottom: 10 })
    .border({ width: { top: 0.5 }, color: { top: LINE } })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  private inheritedTargetButton(label: string, selected: NovelModelTarget | null, role: string): void {
    Row() {
      Column() {
        Text(label).fontSize(13).fontColor(this.inheritedSelected(selected, role) ? ACCENT : INK2)
        Text(role === 'writing' ? '优先使用应用默认写作模型，否则使用设置中的默认模型。' :
          '优先使用应用中该职责的默认模型，否则跟随当前项目的写作模型。')
          .fontSize(11).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text(this.inheritedSelected(selected, role) ? '已选' : '选择').fontSize(12)
        .fontColor(this.inheritedSelected(selected, role) ? ACCENT_INK : ACCENT)
        .width(56).height(44).textAlign(TextAlign.Center)
        .backgroundColor(this.inheritedSelected(selected, role) ? ACCENT : RAISED).borderRadius(10)
        .border({ width: 1, color: this.inheritedSelected(selected, role) ? ACCENT : LINE })
    }
    .width('100%').constraintSize({ minHeight: 56 }).padding({ left: 10, right: 10, top: 6, bottom: 6 })
    .backgroundColor(this.inheritedSelected(selected, role) ? ACCENT_LIGHT : SURFACE).borderRadius(10)
    .margin({ bottom: 6 }).opacity(this.busy ? 0.5 : 1)
    .onClick((): void => { this.selectInheritedRole(role); })
  }

  @Builder
  private modelOptionButton(option: NovelModelOptionView, selected: NovelModelTarget | null, role: string): void {
    Row() {
      Column() {
        Text(option.label).fontSize(13)
          .fontColor(option.available ?
            (this.sameTarget(selected, this.targetFor(option)) ? ACCENT : INK) : INK3)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
        Text(option.available ? option.providerLabel : `${option.providerLabel} · 当前不可用`)
          .fontSize(11).fontColor(option.available ? INK3 : ERROR).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text(this.sameTarget(selected, this.targetFor(option)) ? '已选' : '固定').fontSize(12)
        .fontColor(this.sameTarget(selected, this.targetFor(option)) ? ACCENT_INK : ACCENT)
        .width(56).height(44).textAlign(TextAlign.Center)
        .backgroundColor(this.sameTarget(selected, this.targetFor(option)) ? ACCENT : RAISED).borderRadius(10)
        .border({ width: 1,
          color: this.sameTarget(selected, this.targetFor(option)) ? ACCENT : LINE })
    }
    .width('100%').constraintSize({ minHeight: 56 }).padding({ left: 10, right: 10, top: 6, bottom: 6 })
    .backgroundColor(this.sameTarget(selected, this.targetFor(option)) ? ACCENT_LIGHT : SURFACE)
    .borderRadius(10)
    .margin({ bottom: 6 }).opacity(this.busy || !option.available ? 0.5 : 1)
    .onClick((): void => { if (option.available) this.selectFixedRole(option, role); })
  }

  private selectInheritedRole(role: string): void {
    if (role === 'writing') this.chooseWritingGlobal();
    else if (role === 'review') this.chooseReviewFollowWriting();
    else this.chooseStateSyncNone();
  }

  private selectFixedRole(option: NovelModelOptionView, role: string): void {
    if (role === 'writing') this.chooseWritingFixed(option);
    else if (role === 'review') this.chooseReviewFixed(option);
    else this.chooseStateSyncFixed(option);
  }

  @Builder
  private planFilesSection(): void {
    Column() {
      Text('计划与偏好').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
        .margin({ bottom: 4 })
      Text('以下内容分别保存到当前分支的合法工作区文件。')
        .fontSize(12).fontColor(INK3).margin({ bottom: 10 })
      this.settingTextEditor('本章计划', 'plan/this-chapter.md', this.thisChapterPlan, '本章目标、场景与约束…', 'thisChapter')
      this.settingTextEditor('后续计划', 'plan/future.md', this.futurePlan, '后续章节、未解冲突与节奏…', 'future')
      this.settingTextEditor('写作偏好', 'setting/preferences.md', this.preferences, '叙事视角、语气、禁忌与风格…', 'preferences')
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
    .margin({ bottom: 14 })
  }

  @Builder
  private settingTextEditor(title: string, path: string, value: string, placeholder: string, field: string): void {
    Column() {
      Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
      Text(path).fontSize(11).fontColor(INK3).margin({ top: 2, bottom: 7 })
      TextArea({ text: value, placeholder })
        .fontSize(13).fontColor(INK).placeholderColor(INK4).lineHeight(20)
        .height(120).enabled(!this.busy).backgroundColor(RAISED).borderRadius(10).padding(10)
        .onChange((text: string): void => { this.updateTextDraft(field, text); })
      Text(this.busy ? '正在保存其他工作区变更，当前不可编辑。' : '保存后写入当前分支，并由宿主执行版本校验。')
        .fontSize(11).fontColor(this.busy ? INK4 : INK3).margin({ top: 5 })
      Text('保存').fontSize(13).fontWeight(FontWeight.Medium)
        .fontColor(this.busy ? INK4 : ACCENT).width(72).height(44).textAlign(TextAlign.Center)
        .backgroundColor(this.busy ? SURFACE : ACCENT_LIGHT).borderRadius(10)
        .border({ width: 1, color: this.busy ? LINE : ACCENT }).margin({ top: 8 })
        .onClick((): void => { this.saveText(title, field); })
        .accessibilityText(`保存${title}`)
      if (this.planFeedback.length > 0 && this.planFeedbackField === field) {
        Text(this.planFeedback).fontSize(12).fontColor(INK3)
          .width('100%').padding({ top: 4, bottom: 4 })
      }
    }
    .width('100%').padding({ top: 10, bottom: 12 })
    .border({ width: { top: 0.5 }, color: { top: LINE } })
    .alignItems(HorizontalAlign.Start)
  }

  private updateTextDraft(field: string, text: string): void {
    if (field === 'thisChapter') this.thisChapterPlan = text;
    else if (field === 'future') this.futurePlan = text;
    else this.preferences = text;
  }

  @Builder
  private foreshadowSection(): void {
    Column() {
      Text('伏笔').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
      Text('未解决与已解决状态都随当前分支保存。').fontSize(12).fontColor(INK3)
        .margin({ top: 4, bottom: 10 })
      this.foreshadowRows('未解决伏笔', 'open')
      this.foreshadowRows('已解决伏笔', 'resolved')
      Text('新增伏笔').fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK).margin({ top: 8 })
      TextInput({ text: this.newForeshadowTitle, placeholder: '伏笔标题' })
        .height(44).enabled(!this.busy).fontSize(13).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(RAISED).borderRadius(10).padding({ left: 10, right: 10 }).margin({ top: 8 })
        .onChange((text: string): void => { this.newForeshadowTitle = text; })
      TextArea({ text: this.newForeshadowContent, placeholder: '伏笔内容与预计回收方式' })
        .height(100).enabled(!this.busy).fontSize(13).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(RAISED).borderRadius(10).padding(10).margin({ top: 8 })
        .onChange((text: string): void => { this.newForeshadowContent = text; })
      this.primaryButton('新增伏笔', (): void => { this.addForeshadow(); }, !this.busy)
      if (this.foreshadowFeedback.length > 0) {
        Text(this.foreshadowFeedback).fontSize(12)
          .fontColor(this.foreshadowFeedback.indexOf('请填写') >= 0 ? ERROR : INK3)
          .width('100%').padding({ top: 6, bottom: 2 })
      }
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
    .margin({ bottom: 14 })
  }

  @Builder
  private foreshadowRows(title: string, status: 'open' | 'resolved'): void {
    Text(`${title} · ${this.foreshadowsFor(status).length}`)
      .fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK2)
      .width('100%').margin({ top: 8, bottom: 5 })
    if (this.foreshadowsFor(status).length === 0) {
      Text(status === 'open' ? '暂无未解决伏笔。' : '暂无已解决伏笔。')
        .fontSize(12).fontColor(INK3).width('100%').padding({ top: 6, bottom: 8 })
    } else {
      ForEach(this.foreshadowsFor(status), (item: NovelForeshadow): void => {
        Row() {
          Column() {
            Text(item.title).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
              .width('100%').maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(item.content).fontSize(12).fontColor(INK2).lineHeight(18).maxLines(3)
              .textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 3 })
          }
          .alignItems(HorizontalAlign.Start).layoutWeight(1)
          Text(status === 'open' ? '标为已解' : '重新打开').fontSize(12)
            .fontColor(this.busy ? INK4 : ACCENT).width(76).height(44).textAlign(TextAlign.Center)
            .backgroundColor(this.busy ? SURFACE : RAISED).borderRadius(10)
            .border({ width: 1, color: this.busy ? LINE : ACCENT }).margin({ left: 10 })
            .onClick((): void => { this.toggleForeshadow(item.id); })
            .accessibilityText(`${status === 'open' ? '标记已解决' : '重新打开'}伏笔${item.title}`)
        }
        .width('100%').constraintSize({ minHeight: 64 }).padding({ left: 10, right: 10, top: 8, bottom: 8 })
        .backgroundColor(SURFACE).borderRadius(10).margin({ bottom: 6 })
      }, (item: NovelForeshadow): string => item.id)
    }
  }

  @Builder
  private decisionSection(): void {
    Column() {
      Text('已确认决定').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
      Text('确认后的决定会进入当前分支的活资料。').fontSize(12).fontColor(INK3)
        .margin({ top: 4, bottom: 10 })
      if (this.decisions.length === 0) {
        Text('尚未记录已确认决定。').fontSize(12).fontColor(INK3)
          .width('100%').padding({ top: 8, bottom: 10 })
      } else {
        ForEach(this.decisions, (item: NovelConfirmedDecision): void => {
          Column() {
            Text(item.title).fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
            Text(item.content).fontSize(12).fontColor(INK2).lineHeight(18).margin({ top: 3 })
          }
          .width('100%').padding(10).backgroundColor(SURFACE).borderRadius(10).margin({ bottom: 6 })
          .alignItems(HorizontalAlign.Start)
        }, (item: NovelConfirmedDecision): string => item.id)
      }
      Text('新增已确认决定').fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK).margin({ top: 8 })
      TextInput({ text: this.newDecisionTitle, placeholder: '决定标题' })
        .height(44).enabled(!this.busy).fontSize(13).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(RAISED).borderRadius(10).padding({ left: 10, right: 10 }).margin({ top: 8 })
        .onChange((text: string): void => { this.newDecisionTitle = text; })
      TextArea({ text: this.newDecisionContent, placeholder: '已确认的事实、约束或创作决定' })
        .height(100).enabled(!this.busy).fontSize(13).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(RAISED).borderRadius(10).padding(10).margin({ top: 8 })
        .onChange((text: string): void => { this.newDecisionContent = text; })
      this.primaryButton('确认并新增', (): void => { this.addDecision(); }, !this.busy)
      if (this.decisionFeedback.length > 0) {
        Text(this.decisionFeedback).fontSize(12)
          .fontColor(this.decisionFeedback.indexOf('请填写') >= 0 ? ERROR : INK3)
          .width('100%').padding({ top: 6, bottom: 2 })
      }
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
    .margin({ bottom: 14 })
  }

  @Builder
  private materialLibrarySection(): void {
    Column() {
      Text('资料库').fontSize(17).fontWeight(FontWeight.Bold).fontColor(INK)
      Text('人物、世界观与其他资料仍由资料库统一维护。').fontSize(12).fontColor(INK3)
        .margin({ top: 4, bottom: 8 })
      this.primaryButton('新建资料', (): void => { this.onNewMaterial(); }, !this.busy)
    }
    .width('100%').padding(14).backgroundColor(SURFACE2).borderRadius(14)
  }

  @Builder
  private primaryButton(label: string, action: () => void, enabled: boolean): void {
    Text(label).fontSize(13).fontWeight(FontWeight.Medium)
      .fontColor(enabled ? ACCENT_INK : INK4).width('100%').height(44).textAlign(TextAlign.Center)
      .backgroundColor(enabled ? ACCENT : SURFACE).borderRadius(10)
      .border({ width: 1, color: enabled ? ACCENT : LINE }).margin({ top: 10 })
      .opacity(enabled ? 1 : 0.6).onClick((): void => { if (enabled) action(); })
      .accessibilityText(label)
  }
}

import {
  ACCENT, INK, INK2, INK3, INK4, LINE, MOTION_ENTER, ERROR, SURFACE, SURFACE2,
} from '../../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../../design/fonts.ets';
import { Seg } from '../PageTemplate.ets';
import { StudioTabs } from '../studio/StudioTabs.ets';
import { NovelMaterialCard, NovelHiddenSharedMaterials } from '../NovelMaterialCard.ets';
import { NovelSettingProposalCard } from '../NovelSettingProposalCard.ets';
import { NovelMaterialEditorSheet, NovelMaterialEditorDraft } from '../NovelMaterialEditorSheet.ets';
import { NovelMaterialAdoptionSheet } from '../NovelMaterialAdoptionSheet.ets';
import { NovelCompendiumSession } from '../../platform_impl/novel/NovelCompendiumSession.ets';
import { materialOrigin, MATERIAL_KIND_LABELS } from '@amber/deepread-domain';
import type {
  NovelProject, NovelMaterial, NovelMaterialKind, NovelMaterialSuggestion, NovelSettingProposal,
  NovelChapter, NovelForeshadow, NovelConfirmedDecision, NovelMaterialFields,
} from '@amber/deepread-domain';

@Component
export struct NovelCompendiumView {
  @ObjectLink session: NovelCompendiumSession;
  @Prop project: NovelProject | null = null;
  @Prop busy: boolean = false;
  @Prop blocking: boolean = false;
  @Prop active: boolean = true;
  @Prop novelProduct: boolean = false;
  @Prop paneDuration: number = 180;
  onReanalyze: (chapterId?: string) => void = (_chapterId?: string): void => {};
  analysisFailureText: (error: string) => string = (error: string): string => error;
  @BuilderParam auditContent: () => void = this.EmptyContent;
  @BuilderParam modelRolesContent: () => void = this.EmptyContent;

  @Builder
  EmptyContent(): void {}

  @Builder
  SheetLabel(label: string): void {
    Text(label).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
      .width('100%').margin({ bottom: 8 })
  }

  private workspacePaneTransition(): TransitionEffect {
    if (!this.novelProduct) {
      return TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: 10 }))
        .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut });
    }
    return TransitionEffect.OPACITY.animation({ duration: this.paneDuration, curve: Curve.EaseInOut });
  }

  @Builder
  MaterialCard(material: NovelMaterial): void {
    NovelMaterialCard({ material, origin: this.project === null ? 'branch' : materialOrigin(this.project, material.id),
      sharedExists: this.project?.baseMaterials?.some((base: NovelMaterial): boolean => base.id === material.id) ?? false,
      expanded: this.session.expandedMaterialId === material.id, busy: this.busy || this.blocking,
      onExpand: (): void => { this.session.expandedMaterialId = this.session.expandedMaterialId === material.id ? '' : material.id; },
      onEditBranch: (): void => { this.session.openEditMaterial(material, 'branch'); },
      onEditShared: (): void => { this.session.openEditMaterial(material, 'shared'); },
      onDelete: (): void => { this.session.confirmDeleteMaterial(material); },
      onRestore: (): void => { this.session.confirmRestoreMaterialInheritance(material); },
    })
  }

  @Builder
  HiddenMaterials(kinds: NovelMaterialKind[]): void {
    NovelHiddenSharedMaterials({ materials: this.session.hiddenSharedMaterialsIn(kinds, this.project), busy: this.busy || this.blocking,
      onRestore: (material: NovelMaterial): void => { this.session.confirmRestoreMaterialInheritance(material); } })
  }

  @Builder
  PendingMaterialSuggestions(kinds: NovelMaterialKind[]): void {
    if (this.session.pendingSuggestionsIn(kinds, this.project).length > 0 || this.session.pendingSettingProposalsIn(kinds, this.project).length > 0) {
      Text('待确认').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(INK2).margin({ bottom: 8 })
      Text('建议采用后才会写入资料库。')
        .fontSize(11).fontColor(INK3).margin({ bottom: 10 })
      ForEach(this.session.pendingSettingProposalsIn(kinds, this.project), (proposal: NovelSettingProposal): void => {
        NovelSettingProposalCard({
          title: proposal.title, content: proposal.content, kindLabel: MATERIAL_KIND_LABELS[proposal.kind],
          onResolve: (accept: boolean): void => { this.session.resolveSettingProposal(proposal, accept); },
        })
      }, (proposal: NovelSettingProposal): string => `setting:${proposal.id}`)
      ForEach(this.session.pendingSuggestionsIn(kinds, this.project), (s: NovelMaterialSuggestion): void => {
        NovelSettingProposalCard({
          title: s.title, content: s.content, kindLabel: MATERIAL_KIND_LABELS[s.kind],
          onResolve: (accept: boolean): void => { this.session.resolveSuggestion(s.id, accept); },
        })
      }, (s: NovelMaterialSuggestion): string => s.id)
    }
  }

  @Builder
  MaterialKindPane(kinds: NovelMaterialKind[], addLabel: string, addKind: NovelMaterialKind,
    showReanalyze: boolean): void {
    Scroll() {
      Column() {
        Row() {
          if (showReanalyze) {
            Text('重新分析').fontSize(12).fontColor(INK3)
              .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
              .onClick(() => { this.onReanalyze(); })
          }
          Blank()
          Text(addLabel).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
            .onClick(() => { this.session.openNewMaterial(addKind); })
        }
        .width('100%').padding({ top: 4, bottom: 10 })

        if (this.project !== null) {
          ForEach(this.project.chapters.filter((chapter: NovelChapter): boolean =>
            chapter.suggestionWarning !== undefined && chapter.suggestionWarning !== null && chapter.suggestionWarning.length > 0),
          (chapter: NovelChapter): void => {
            Column({ space: 6 }) {
              Text(`「${chapter.title}」正文已保存，资料分析未完成`).fontSize(12).fontColor(ERROR).lineHeight(18)
              Text(this.analysisFailureText(chapter.suggestionWarning ?? '')).fontSize(11).fontColor(INK3).lineHeight(16)
              Text('重新分析此章').fontSize(12).fontColor(ACCENT).height(44).textAlign(TextAlign.Center)
                .enabled(!this.busy && !this.blocking)
                .onClick((): void => { this.onReanalyze(chapter.id); })
            }
            .width('100%').alignItems(HorizontalAlign.Start).padding(12)
            .backgroundColor(SURFACE2).borderRadius(10).margin({ bottom: 10 })
          }, (chapter: NovelChapter): string => chapter.id)
        }
        this.PendingMaterialSuggestions(kinds)

        if (this.session.materialsIn(kinds, this.project).length === 0) {
          Text('暂无资料。').fontSize(13).fontColor(INK3)
            .width('100%').padding({ top: 12 })
        }
        ForEach(this.session.materialsIn(kinds, this.project), (m: NovelMaterial): void => {
          this.MaterialCard(m)
        }, (m: NovelMaterial): string => m.id)
        this.HiddenMaterials(kinds)
      }
      .width('100%').padding({ left: 20, right: 20, bottom: 24 })
      .alignItems(HorizontalAlign.Start)
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
  }

  @Builder
  ForeshadowCard(item: NovelForeshadow): void {
    Column() {
      Row() {
        Text(item.title).fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK).layoutWeight(1)
        Text(item.status === 'open' ? '标为已解' : '重新打开').fontSize(12).fontColor(ACCENT)
          .constraintSize({ minHeight: 44 }).padding({ left: 10 })
          .enabled(!this.busy)
          .onClick((): void => { this.session.toggleStoryForeshadow(item.id); })
      }
      .width('100%')
      Text(item.content).fontSize(12).fontColor(INK2).lineHeight(18).maxLines(3)
        .width('100%').margin({ top: 4 })
    }
    .width('100%').padding(14).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 10 })
  }

  @Builder
  StoryPane(): void {
    Scroll() {
      Column() {
        this.PendingMaterialSuggestions(['outline'])
        Row() {
          Text('总纲').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
          Blank()
          Text('新增总纲').fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 }).textAlign(TextAlign.Center)
            .onClick(() => { this.session.openNewMaterial('outline'); })
        }
        .width('100%').alignItems(VerticalAlign.Center)
        ForEach(this.session.materialsIn(['outline'], this.project), (m: NovelMaterial): void => {
          this.MaterialCard(m)
        }, (m: NovelMaterial): string => m.id)
        this.HiddenMaterials(['outline'])
        if (this.session.materialsIn(['outline'], this.project).length === 0) {
          Text('暂无总纲资料。').fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 8 })
        }

        Column() {}.height(12)
        this.SheetLabel('伏笔')
        Text('未解决与已解决状态都随当前分支保存。').fontSize(11).fontColor(INK3).margin({ bottom: 8 })
        TextInput({ text: this.session.fsTitle, placeholder: '伏笔标题' })
          .height(44).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.session.fsTitle = v; })
        TextArea({ text: this.session.fsContent, placeholder: '伏笔内容与预计回收方式' })
          .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 8 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.session.fsContent = v; })
        Row() {
          Blank()
          Text('新增伏笔').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
            .constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.session.addStoryForeshadow(); })
        }.width('100%')
        Text(`未解决 · ${this.session.openForeshadows(this.project).length}`).fontSize(12).fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK2).width('100%').margin({ top: 8, bottom: 6 })
        if (this.session.openForeshadows(this.project).length === 0) {
          Text('暂无未解决伏笔。').fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 8 })
        }
        ForEach(this.session.openForeshadows(this.project), (item: NovelForeshadow): void => {
          this.ForeshadowCard(item)
        }, (item: NovelForeshadow): string => item.id)
        if (this.session.resolvedForeshadows(this.project).length > 0) {
          Text(`已解决 · ${this.session.resolvedForeshadows(this.project).length}`).fontSize(12).fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK2).width('100%').margin({ top: 8, bottom: 6 })
          ForEach(this.session.resolvedForeshadows(this.project), (item: NovelForeshadow): void => {
            this.ForeshadowCard(item)
          }, (item: NovelForeshadow): string => item.id)
        }

        Column() {}.height(12)
        this.SheetLabel('已确认决定')
        Text('确认后的决定会进入当前分支的活资料。').fontSize(11).fontColor(INK3).margin({ bottom: 8 })
        TextInput({ text: this.session.decTitle, placeholder: '决定标题' })
          .height(44).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.session.decTitle = v; })
        TextArea({ text: this.session.decContent, placeholder: '已确认的事实、约束或创作决定' })
          .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 8 })
          .enabled(!this.busy)
          .onChange((v: string): void => { this.session.decContent = v; })
        Row() {
          Blank()
          Text('新增决定').fontSize(13).fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
            .constraintSize({ minHeight: 44 })
            .enabled(!this.busy)
            .onClick((): void => { this.session.addStoryDecision(); })
        }.width('100%')
        if (this.project !== null && this.project.branchSettings.confirmedDecisions.length === 0) {
          Text('尚未记录已确认决定。').fontSize(12).fontColor(INK3).width('100%').margin({ top: 8 })
        }
        if (this.project !== null) {
          ForEach(this.project.branchSettings.confirmedDecisions, (item: NovelConfirmedDecision): void => {
            Column() {
              Text(item.title).fontSize(13).fontWeight(FontWeight.Medium).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
              Text(item.content).fontSize(12).fontColor(INK2).lineHeight(18).margin({ top: 3 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding(14).backgroundColor(SURFACE).borderRadius(12)
            .border({ width: 1, color: LINE }).margin({ bottom: 10 })
          }, (item: NovelConfirmedDecision): string => item.id)
        }

        Column() {}.height(12)
        this.auditContent()
      }
      .width('100%').padding({ left: 20, right: 20, bottom: 24 })
      .alignItems(HorizontalAlign.Start)
    }
    .align(Alignment.TopStart)
    .layoutWeight(1)
  }

  @Builder
  ProjectModelRolesPane(): void {
    this.modelRolesContent()
  }

  @Builder
  MorePane(): void {
    if (!this.novelProduct) {
      this.ProjectModelRolesPane()
    } else {
      Column() {
        Row() {
          Text(this.session.projectModelsVisible ? '项目模型职责' : '其他资料').fontSize(13).fontColor(INK3)
          Blank()
          Text(this.session.projectModelsVisible ? '返回其他资料' : '项目模型职责').fontSize(13).fontColor(ACCENT)
            .constraintSize({ minHeight: 44 }).padding({ left: 8, right: 8 })
            .onClick((): void => {
              animateTo({ duration: this.paneDuration, curve: Curve.EaseInOut }, (): void => {
                this.session.projectModelsVisible = !this.session.projectModelsVisible;
              });
            })
        }.width('100%').padding({ left: 20, right: 12 })
        Stack({ alignContent: Alignment.TopStart }) {
          if (this.session.projectModelsVisible) {
            Column() { this.ProjectModelRolesPane() }.width('100%').height('100%').transition(this.workspacePaneTransition())
          } else {
            Column() { this.MaterialKindPane(['requirement', 'other'], '新增资料', 'other', false) }
              .width('100%').height('100%').transition(this.workspacePaneTransition())
          }
        }.width('100%').layoutWeight(1)
      }.width('100%').height('100%')
    }
  }

  @Builder
  settingsPane(): void {
    Column() {
      // 设定分类(iOS Compendium segmented:角色/世界观/剧情/更多)
      Row() {
        if (this.novelProduct) {
          StudioTabs({ options: ['角色', '世界观', '剧情', '更多'], selected: this.session.settingTab,
            active: this.active,
            onSelect: (idx: number): void => {
              if (idx === this.session.settingTab) return;
              animateTo({ duration: this.paneDuration, curve: Curve.EaseInOut }, (): void => { this.session.settingTab = idx; });
            } })
        } else {
          Seg({ options: ['角色', '世界观', '剧情', '更多'], selected: this.session.settingTab,
            onSelect: (idx: number): void => {
              if (idx === this.session.settingTab) return;
              animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => { this.session.settingTab = idx; });
            } })
        }
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 2, bottom: 8 })
      // Keep outgoing and incoming panes in the same bounds during the fade.
      Stack({ alignContent: Alignment.TopStart }) {
        if (this.project === null) {
          Column() {}.height('100%')
        } else if (this.session.settingTab === 0) {
          Column() {
            this.MaterialKindPane(['character', 'relationship'], '新增角色', 'character', false)
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        } else if (this.session.settingTab === 1) {
          Column() {
            this.MaterialKindPane(this.novelProduct ? ['world'] : ['world', 'requirement', 'other'],
              '新增资料', 'world', true)
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        } else if (this.session.settingTab === 2) {
          Column() {
            this.StoryPane()
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        } else {
          Column() {
            this.MorePane()
          }
          .width('100%').height('100%')
          .transition(this.workspacePaneTransition())
        }
      }.width('100%').layoutWeight(1)
    }
    .width('100%').height('100%')
  }

  build(): void { this.settingsPane() }
}

// Separate overlays let the page retain its existing stack order among unrelated editors.
@Component
export struct NovelMaterialEditorOverlay {
  @ObjectLink session: NovelCompendiumSession;
  @Prop busy: boolean = false;
  @Prop errorMessage: string = '';
  @Prop bottomPadding: number = 12;

  build(): void {
    if (this.session.materialEditorOpen) {
      NovelMaterialEditorSheet({ draftKey: this.session.materialEditorKey, closeRequest: this.session.materialCloseRequest,
        initialDraft: this.session.materialEditorDraft, newMaterial: this.session.editingMaterialId.length === 0,
        busy: this.busy, errorMessage: this.errorMessage, bottomPadding: this.bottomPadding,
        onConfirm: (draft: NovelMaterialEditorDraft): void => { this.session.saveMaterial(draft); },
        onCancel: (): void => { this.session.cancelMaterialEditor(); },
      })
    }
  }
}

@Component
export struct NovelMaterialAdoptionOverlay {
  @ObjectLink session: NovelCompendiumSession;
  @Prop busy: boolean = false;
  @Prop bottomPadding: number = 12;

  build(): void {
    if (this.session.adoptionOpen) {
      NovelMaterialAdoptionSheet({
        visible: this.session.adoptionOpen, draftKey: this.session.adoptionDraftKey,
        initialKind: this.session.adoptionKind, initialTitle: this.session.adoptionTitle,
        initialBody: this.session.adoptionBody, initialFields: this.session.adoptionFields,
        existingTargets: this.session.adoptionTargets, busy: this.busy, errorMessage: this.session.adoptionError,
        bottomPadding: this.bottomPadding,
        onConfirm: (kind: NovelMaterialKind, title: string, body: string, targetId: string | null, fields: NovelMaterialFields): void => {
          this.session.confirmMaterialAdoption(kind, title, body, targetId, fields);
        },
        onCancel: (): void => { this.session.cancelAdoption(); },
      })
    }
  }
}

import { ACCENT, ERROR, INK, INK3, INK4, LINE, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2 } from '../../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../../design/fonts.ets';
import { Icon } from '../ui/Icon.ets';
import { Seg, CardGroup } from '../PageTemplate.ets';
import type { NovelModelOptionView } from '../NovelSettingsPane.ets';
import type { NovelModelTarget, NovelProject } from '@amber/deepread-domain';
import { NovelWritingControlSession } from '../../platform_impl/novel/NovelWritingControlSession.ets';

// Both sheets share model rows; job actions remain in the job owner's builder.
@Component
export struct NovelWritingControlView {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @ObjectLink session: NovelWritingControlSession;
  @Prop sheet: string = 'control';
  @Prop busy: boolean = false;
  @Prop projectName: string = '';
  @Prop project: NovelProject | null = null;
  @Prop isNovelProduct: boolean = true;
  @Prop navBarHeight: number = 0;
  @Prop studioPageVisible: boolean = true;
  @Prop studioBackgrounded: boolean = false;
  @Prop reduceMotion: boolean = false;
  @BuilderParam jobSection: () => void = this.EmptySection;
  @BuilderParam actionSection: () => void = this.EmptySection;

  @Builder
  private EmptySection(): void {}

  @Builder
  private SheetLabel(label: string): void {
    Text(label).fontSize(12).fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
      .width('100%').margin({ bottom: 8 })
  }

  private composerModelTransition(card: boolean): TransitionEffect {
    if (this.studioBackgrounded || !this.studioPageVisible) return TransitionEffect.IDENTITY;
    if (!card || this.reduceMotion) return TransitionEffect.OPACITY.animation({ duration: 160 });
    return TransitionEffect.asymmetric(
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 320, curve: Curve.EaseOut }),
      TransitionEffect.move(TransitionEdge.BOTTOM).combine(TransitionEffect.OPACITY)
        .animation({ duration: 250, curve: Curve.EaseIn }));
  }

  // 写作模型选项行(跟随全局 + 固定模型;iOS ComposerModelSheet 的「跟随小说默认」)
  @Builder
  private ModelRow(label: string, detail: string, selected: boolean, available: boolean, isLast: boolean,
    target: NovelModelTarget, session: number = 0): void {
    Column() {
      Row() {
        Column({ space: 2 }) {
          Text(label).fontSize(15).fontFamily(FONT_SANS_MEDIUM).fontColor(available ? INK : INK4)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (detail.length > 0) {
            Text(detail).fontSize(12).fontColor(INK3).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }
        }.alignItems(HorizontalAlign.Start).layoutWeight(1)
        if (selected) {
          Icon({ name: 'check', iconSize: 16, color: ACCENT })
        }
      }
      .width('100%').alignItems(VerticalAlign.Center).constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .opacity(available ? 1 : 0.55)
      .enabled(available && !this.busy && (!this.session.composerModelSheetOpen ||
        (this.session.modelOptionsReady && !this.session.modelOptionsLoading)))
      .onClick((): void => {
        if (session > 0) this.session.chooseComposerModel(target, session);
        else this.session.selectWritingTarget(target);
      })
      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }.width('100%').padding({ left: 14, right: 14 })
      }
    }.width('100%')
  }

  @Builder
  private ModelRows(session: number = 0): void {
    this.ModelRow(this.isNovelProduct ? '跟随小说默认' : '跟随聊天全局模型',
      this.isNovelProduct ? this.session.defaultWritingModelLabel() : '写作使用应用聊天当前模型',
      this.session.writingTargetSelected(null, this.project), true,
      this.session.modelOptions.length === 0, { kind: 'global' }, session)
    ForEach(this.session.modelOptions, (option: NovelModelOptionView, idx: number): void => {
      this.ModelRow(option.label,
        option.available ? option.providerLabel : `${option.providerLabel} · 当前不可用`,
        this.session.writingTargetSelected(option, this.project), option.available, idx === this.session.modelOptions.length - 1,
        { kind: 'fixed', providerId: option.providerId, modelId: option.modelId }, session)
    }, (option: NovelModelOptionView): string => `${option.providerId}-${option.modelId}`)
  }

  @Builder
  private ComposerModelSheet(session: number): void {
    Column() {
      Blank().onClick((): void => { this.session.closeComposerModelSheet(session); })
      Column() {
        Row() {
          Text('创作模型').fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
          Text('完成').fontSize(15).fontColor(ACCENT).constraintSize({ minWidth: 48, minHeight: 44 })
            .textAlign(TextAlign.End).enabled(!this.busy)
            .onClick((): void => { this.session.closeComposerModelSheet(session); })
        }.width('100%').padding({ left: 20, right: 20, top: 10, bottom: 4 })
        Scroll() {
          Column() {
            if (this.session.modelOptionsLoading) {
              Text('正在读取模型…').fontSize(13).fontColor(INK3).padding(20)
            } else if (this.session.modelOptionsError.length > 0) {
              Text(this.session.modelOptionsError).fontSize(13).fontColor(ERROR).width('100%').padding(20)
              Text('重试').fontSize(15).fontColor(ACCENT).constraintSize({ minHeight: 44 })
                .onClick((): void => { if (this.session.composerModelSessionActive(session)) this.session.loadProjectModelOptions(); })
            } else {
              this.ModelRows(session)
              if (this.session.modelOptions.length === 0) {
                Text('暂无模型，请先添加模型服务。').fontSize(13).fontColor(INK3).width('100%').padding(20)
              }
            }
            if (this.session.composerModelError.length > 0) {
              Text(this.session.composerModelError).fontSize(13).fontColor(ERROR).width('100%').padding(20)
            }
            if (this.busy) { Text('保存中…').fontSize(13).fontColor(INK3).padding(12) }
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.constraintSize({ maxHeight: 420 }).scrollBar(BarState.Auto)
      }.width('100%').backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ bottom: Math.max(16, this.navBarHeight) }).transition(this.composerModelTransition(true))
    }.width('100%').height('100%').backgroundColor(OVERLAY)
      .justifyContent(FlexAlign.End).transition(this.composerModelTransition(false))
  }

  // 项目控制 sheet(顶栏 principal 触发;bottom sheet 85%,内容可滚)
  @Builder
  private ControlSheet(): void {
    Column() {
      Blank().onClick((): void => { this.session.closeControlSheet(); })
      Column() {
        Row() {
          Column({ space: 2 }) {
            Text('项目控制').fontSize(17).fontWeight(FontWeight.Bold).fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            Text(this.projectName).fontSize(12).fontColor(INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }.alignItems(HorizontalAlign.Start).layoutWeight(1)
          Text('完成').fontSize(15).fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
            .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)
            .onClick((): void => { this.session.closeControlSheet(); })
        }.width('100%').alignItems(VerticalAlign.Center).padding({ bottom: 10 })
        Scroll() {
          Column() {
            this.SheetLabel('创作模式')
            Seg({ options: ['共创', '代笔'], selected: this.session.controlTab,
              onSelect: (idx: number): void => {
                animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.session.controlTab = idx;
                });
              } })
            Column() {
              if (this.session.controlTab === 1) {
                Column() {
                  this.jobSection()
                }
                .width('100%').margin({ top: 10 }).borderRadius(14).clip(true)
                .transition(TransitionEffect.OPACITY.combine(TransitionEffect.translate({ y: -8 }))
                  .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
              } else {
                Text('与 Amber 逐段讨论、逐章收录正文。').fontSize(12).fontColor(INK3)
                  .width('100%').margin({ top: 8 })
                  .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
              }
            }.width('100%').clip(true)

            Column() {}.height(16)
            this.SheetLabel('写作模型')
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModelRows(); } })

            Column() {}.height(16)
            this.SheetLabel('本章计划')
            Text('编辑内容自动保存为草稿，点击保存才应用到当前分支。').fontSize(12).fontColor(INK3)
              .width('100%').margin({ bottom: 8 })
            if (this.session.controlPlanLoading) { Text('正在读取计划草稿…').fontSize(12).fontColor(INK3).padding(8) }
            if (this.session.controlNotice.length > 0) { Text(this.session.controlNotice).fontSize(12).fontColor(INK3).width('100%').margin({ bottom: 8 }) }
            if (this.session.controlPlanError.length > 0) {
              Text(this.session.controlPlanError).fontSize(12).fontColor(ERROR).width('100%').margin({ bottom: 8 })
              Text(this.session.controlPlanReady ? '重试保存草稿' : '重试读取草稿').fontSize(13).fontColor(ACCENT).height(44)
                .onClick((): void => {
                  if (this.session.controlPlanReady) this.session.retryControlPlanDraft();
                  else this.session.openControlSheet();
                })
            }
            TextArea({ text: this.session.planThisChapter, placeholder: '本章的位置、目标冲突、必须发生与不能发生…' })
              .width('100%').height(100).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy && this.session.controlPlanReady)
              .onChange((v: string): void => { this.session.changeControlPlan('chapter', v); })
            Column() {}.height(12)
            this.SheetLabel('往后计划')
            TextArea({ text: this.session.planFuture, placeholder: '接下来几章的方向…' })
              .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy && this.session.controlPlanReady)
              .onChange((v: string): void => { this.session.changeControlPlan('future', v); })
            Column() {}.height(12)
            this.SheetLabel('写作偏好')
            TextArea({ text: this.session.planPreferences, placeholder: '文风、视角、节奏偏好…' })
              .width('100%').height(80).fontSize(13).fontColor(INK).placeholderColor(INK4)
              .backgroundColor(SURFACE2).borderRadius(12).padding(12)
              .enabled(!this.busy && this.session.controlPlanReady)
              .onChange((v: string): void => { this.session.changeControlPlan('preferences', v); })
            Row() {
              Blank()
              Text(this.busy ? '保存中…' : '保存').fontSize(14).fontWeight(FontWeight.Medium)
                .fontFamily(FONT_SANS_MEDIUM).fontColor(this.busy ? INK4 : ACCENT)
                .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.End)
                .enabled(!this.busy && this.session.controlPlanReady)
                .onClick((): void => { this.session.savePlanPrefs(); })
            }.width('100%').margin({ top: 6 })

            Column() {}.height(16)
            this.SheetLabel('操作')
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.actionSection(); } })
            Column() {}.height(24)
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
      }
      .width('100%').height('85%')
      .padding({ left: 18, right: 18, top: 16, bottom: 14 + this.navBarHeight })
      .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
      .onClick((): void => {})
      .transition(this.isNovelProduct ? this.composerModelTransition(true) :
        TransitionEffect.translate({ y: 160 }).animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .justifyContent(FlexAlign.End)
    .transition(this.isNovelProduct ? this.composerModelTransition(false) :
      TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build() {
    if (this.sheet === 'model') {
      this.ComposerModelSheet(this.session.composerModelSession)
    } else {
      this.ControlSheet()
    }
  }
}

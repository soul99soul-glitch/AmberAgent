// 资料采纳确认：编辑后的内容与明确选定的目标交给宿主；来源和目标校验由领域层负责。
import {
  ACCENT, ACCENT_INK, ACCENT_LIGHT, ERROR, INK, INK2, INK3, INK4, LINE,
  MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2,
} from '../design/tokens.ets';
import { MATERIAL_KIND_LABELS, novelMaterialAdoptionFields } from '@amber/deepread-domain';
import type { NovelMaterialKind, NovelMaterialFields } from '@amber/deepread-domain';
import { NovelMaterialMetadataFields } from './NovelMaterialMetadataFields.ets';

export interface NovelMaterialAdoptionSheetTarget extends NovelMaterialFields {
  id: string;
  kind: NovelMaterialKind;
  title: string;
  body: string;
  enabled: boolean;
}

const MATERIAL_KINDS: NovelMaterialKind[] = ['world', 'character', 'relationship', 'outline', 'requirement', 'other'];

@Component
export struct NovelMaterialAdoptionSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop visible: boolean = false;
  @Prop @Watch('resetDraft') draftKey: string = '';
  @Prop initialKind: NovelMaterialKind = 'world';
  @Prop initialTitle: string = '';
  @Prop initialBody: string = '';
  @Prop initialFields: NovelMaterialFields = {};
  @Prop existingTargets: NovelMaterialAdoptionSheetTarget[] = [];
  @Prop busy: boolean = false;
  @Prop errorMessage: string = '';
  @Prop bottomPadding: number = 0;

  onConfirm: (kind: NovelMaterialKind, title: string, body: string, targetId: string | null, fields: NovelMaterialFields) => void =
    (_kind: NovelMaterialKind, _title: string, _body: string, _targetId: string | null, _fields: NovelMaterialFields): void => {};
  onCancel: () => void = (): void => {};

  @State private kind: NovelMaterialKind = 'world';
  @State private title: string = '';
  @State private body: string = '';
  @State private targetId: string | null = null;
  @State private committingInput: boolean = false;
  @State private fields: NovelMaterialFields = {};
  @State private fieldDraftKey: string = '';
  private titleController: TextInputController = new TextInputController();
  private bodyController: TextAreaController = new TextAreaController();
  private alive: boolean = false;
  private inputToken: number = 0;
  private commitFields: () => void = (): void => {};

  aboutToAppear(): void {
    this.alive = true;
    this.resetDraft();
  }

  aboutToDisappear(): void { this.alive = false; this.inputToken++; }

  private resetDraft(): void {
    this.kind = this.initialKind;
    this.title = this.initialTitle;
    this.body = this.initialBody;
    this.targetId = null;
    this.committingInput = false;
    this.fields = novelMaterialAdoptionFields(this.initialFields, undefined);
    this.fieldDraftKey = `${this.draftKey}-new`;
    this.inputToken++;
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.visible) {
      Stack() {
        Row() {}
          .width('100%').height('100%').backgroundColor(OVERLAY)
          .onClick((): void => { this.cancel(); })
        Column() {
          this.header()
          Scroll() {
            Column() {
              this.editSection()
              this.targetSection()
              NovelMaterialMetadataFields({ draftKey: this.fieldDraftKey, initialFields: this.fields, busy: this.blocked(),
                onReady: (commit: () => void): void => { this.commitFields = commit; },
                onChange: (fields: NovelMaterialFields): void => { this.fields = fields; } })
              if (this.errorMessage.length > 0) {
                Text(this.errorMessage).fontSize(12).fontColor(ERROR).lineHeight(18)
                  .width('100%').wordBreak(WordBreak.BREAK_ALL).margin({ top: 12 })
              }
            }
            .width('100%').alignItems(HorizontalAlign.Start)
          }
          .width('100%').layoutWeight(1).align(Alignment.TopStart)
          this.actions()
        }
        .width('100%').height('88%').alignItems(HorizontalAlign.Start)
        .backgroundColor(SURFACE).borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ left: 20, right: 20, top: 18, bottom: 24 + this.bottomPadding })
        .transition(TransitionEffect.translate({ y: 160 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%').alignContent(Alignment.Bottom)
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }

  @Builder
  private header(): void {
    Row() {
      Text('确认资料采纳').fontSize(18).fontWeight(FontWeight.Bold).fontColor(INK).layoutWeight(1)
      Text('取消').fontSize(13).fontColor(this.blocked() ? INK4 : INK3)
        .width(44).height(44).textAlign(TextAlign.Center)
        .onClick((): void => { this.cancel(); }).accessibilityText('取消资料采纳')
    }
    .width('100%').alignItems(VerticalAlign.Center).margin({ bottom: 10 })
  }

  @Builder
  private editSection(): void {
    Column() {
      Text('核对并编辑资料').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(MATERIAL_KINDS, (kind: NovelMaterialKind): void => {
          Text(MATERIAL_KIND_LABELS[kind]).fontSize(12).height(44).textAlign(TextAlign.Center)
            .padding({ left: 12, right: 12 }).borderRadius(22)
            .fontColor(this.kind === kind ? ACCENT_INK : INK2)
            .backgroundColor(this.kind === kind ? ACCENT : SURFACE2)
            .margin({ right: 6, top: 8 }).enabled(!this.blocked())
            .onClick((): void => { this.kind = kind; })
            .accessibilityText(`资料类型 ${MATERIAL_KIND_LABELS[kind]}${this.kind === kind ? '，已选' : ''}`)
        }, (kind: NovelMaterialKind): string => kind)
      }
      .width('100%').margin({ bottom: 12 })
      TextInput({ text: this.title, placeholder: '资料名称', controller: this.titleController })
        .width('100%').height(44).fontSize(14).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(10).padding({ left: 14, right: 14 })
        .enabled(!this.blocked()).onChange((value: string): void => { this.title = value; })
        .accessibilityText('采纳资料名称')
      TextArea({ text: this.body, placeholder: '资料内容', controller: this.bodyController })
        .width('100%').height(180).fontSize(14).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(10).padding(12).margin({ top: 12 })
        .enabled(!this.blocked()).onChange((value: string): void => { this.body = value; })
        .accessibilityText('采纳资料内容')
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 16 })
  }

  @Builder
  private targetSection(): void {
    Column() {
      Text('采纳位置').fontSize(13).fontWeight(FontWeight.Medium).fontColor(INK)
      Text('默认新建资料。若要修改已有资料，请明确选择目标；同名资料不会自动被覆盖。')
        .fontSize(12).fontColor(INK3).lineHeight(18).margin({ top: 4, bottom: 8 })
      this.targetRow(null, '新建资料', '保留所有已有资料')
      ForEach(this.existingTargets, (target: NovelMaterialAdoptionSheetTarget): void => {
        this.targetRow(target.id, target.title.trim().length > 0 ? target.title : '未命名资料',
          `更新已有${MATERIAL_KIND_LABELS[target.kind]}资料`)
      }, (target: NovelMaterialAdoptionSheetTarget): string => target.id)
      if (this.selectedTarget() !== null) {
        this.previousContent()
      }
    }
    .width('100%').alignItems(HorizontalAlign.Start)
  }

  @Builder
  private targetRow(id: string | null, title: string, subtitle: string): void {
    Row() {
      Column({ space: 3 }) {
        Text(title).fontSize(13).fontWeight(FontWeight.Medium)
          .fontColor(this.targetId === id ? ACCENT : INK).maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(subtitle).fontSize(11).fontColor(INK3).lineHeight(16)
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      if (this.targetId === id) {
        Text('已选').fontSize(11).fontColor(ACCENT).margin({ left: 12 })
      }
    }
    .width('100%').constraintSize({ minHeight: 52 }).padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(this.targetId === id ? ACCENT_LIGHT : SURFACE2)
    .border({ width: 1, color: this.targetId === id ? ACCENT : LINE }).borderRadius(10)
    .margin({ bottom: 6 }).enabled(!this.blocked()).onClick((): void => { this.selectTarget(id); })
    .accessibilityText(`${title}，${subtitle}${this.targetId === id ? '，已选' : ''}`)
  }

  @Builder
  private previousContent(): void {
    Column() {
      Text('原内容预览（前 600 字）').fontSize(12).fontWeight(FontWeight.Medium).fontColor(INK2)
      Text(this.previousBody()).fontSize(12).fontColor(INK2).lineHeight(18)
        .width('100%').wordBreak(WordBreak.BREAK_ALL).margin({ top: 6 })
      Text('确认后，用上方核对的类型、名称、正文、别名、标签和上下文设置更新这一条资料。仅影响当前分支。')
        .fontSize(12).fontColor(ACCENT).lineHeight(18).margin({ top: 8 })
    }
    .width('100%').alignItems(HorizontalAlign.Start).padding(12)
    .backgroundColor(SURFACE2).borderRadius(10).margin({ top: 6 })
  }

  @Builder
  private actions(): void {
    Row({ space: 12 }) {
      Text('取消').fontSize(14).fontColor(this.blocked() ? INK4 : INK2)
        .layoutWeight(1).height(44).textAlign(TextAlign.Center)
        .backgroundColor(SURFACE2).borderRadius(12).border({ width: 1, color: LINE })
        .onClick((): void => { this.cancel(); })
      Text(this.busy ? '正在采纳…' : this.targetId === null ? '确认新建' : '确认更新')
        .fontSize(14).fontWeight(FontWeight.Medium).fontColor(this.canConfirm() ? ACCENT_INK : INK4)
        .layoutWeight(1).height(44).textAlign(TextAlign.Center)
        .backgroundColor(this.canConfirm() ? ACCENT : SURFACE2).borderRadius(12)
        .border({ width: 1, color: this.canConfirm() ? ACCENT : LINE })
        .enabled(this.canConfirm())
        .onClick((): void => { this.confirm(); }).accessibilityText('确认采纳编辑后的资料')
    }
    .width('100%').margin({ top: 14 })
  }

  private blocked(): boolean { return this.busy || this.committingInput; }

  private selectTarget(id: string | null): void {
    if (this.blocked() || id === this.targetId) return;
    this.targetId = id;
    const target: NovelMaterialAdoptionSheetTarget | null = this.selectedTarget();
    this.fields = novelMaterialAdoptionFields(this.initialFields, target ?? undefined, target?.enabled ?? true);
    this.fieldDraftKey = `${this.draftKey}-${id ?? 'new'}`;
  }

  private canConfirm(): boolean {
    return !this.blocked() && this.title.trim().length > 0 && this.body.trim().length > 0 &&
      (this.targetId === null || this.selectedTarget() !== null);
  }

  private selectedTarget(): NovelMaterialAdoptionSheetTarget | null {
    if (this.targetId === null) return null;
    return this.existingTargets.find((target: NovelMaterialAdoptionSheetTarget): boolean =>
      target.id === this.targetId) ?? null;
  }

  private previousBody(): string {
    const target: NovelMaterialAdoptionSheetTarget | null = this.selectedTarget();
    if (target === null) return '';
    if (target.body.trim().length === 0) return '原资料内容为空。';
    return target.body.length > 600 ? `${target.body.substring(0, 600)}…` : target.body;
  }

  private cancel(): void {
    if (this.blocked()) return;
    this.titleController.stopEditing();
    this.bodyController.stopEditing();
    this.commitFields();
    this.onCancel();
  }

  private confirm(): void {
    if (this.blocked()) return;
    this.committingInput = true;
    this.titleController.stopEditing();
    this.bodyController.stopEditing();
    this.commitFields();
    const token: number = ++this.inputToken;
    setTimeout((): void => {
      if (token !== this.inputToken) return;
      this.committingInput = false;
      if (!this.alive || !this.visible || !this.canConfirm()) return;
      this.onConfirm(this.kind, this.title.trim(), this.body.trim(), this.targetId, this.fields);
    }, 0);
  }
}

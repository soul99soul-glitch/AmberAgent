import { ACCENT, ERROR, INK, INK3, LINE, MOTION_OVERLAY, OVERLAY, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { MATERIAL_KIND_LABELS, normalizeNovelMaterialFields } from '@amber/deepread-domain';
import type { NovelMaterial, NovelMaterialFields, NovelMaterialKind } from '@amber/deepread-domain';
import { NovelMaterialMetadataFields } from './NovelMaterialMetadataFields.ets';

export type NovelMaterialEditScope = 'branch' | 'shared';
export interface NovelMaterialEditorDraft extends NovelMaterialFields {
  kind: NovelMaterialKind;
  title: string;
  content: string;
  enabled: boolean;
  scope: NovelMaterialEditScope;
}

export const emptyMaterialEditorDraft = (): NovelMaterialEditorDraft => ({
  kind: 'world', title: '', content: '', enabled: true, scope: 'branch',
  aliases: [], tags: [], customKind: '', injectionMode: 'always',
});

export const materialEditorDraftFor = (material: NovelMaterial, scope: NovelMaterialEditScope): NovelMaterialEditorDraft => {
  const fields = normalizeNovelMaterialFields(material, material.enabled);
  return { kind: material.kind, title: material.title, content: material.content, enabled: fields.injectionMode !== 'off', scope,
    aliases: fields.aliases, tags: fields.tags, customKind: fields.customKind, injectionMode: fields.injectionMode };
};

const KINDS: NovelMaterialKind[] = ['world', 'character', 'relationship', 'outline', 'requirement', 'other'];

@Component
export struct NovelMaterialEditorSheet {
  @StorageProp('reduceMotion') private reduceMotion: boolean = false;
  @StorageProp('appBackgrounded') private appBackgrounded: boolean = false;
  @Prop @Watch('resetDraft') draftKey: string = '';
  @Prop @Watch('cancel') closeRequest: number = 0;
  @Prop initialDraft: NovelMaterialEditorDraft = emptyMaterialEditorDraft();
  @Prop newMaterial: boolean = false;
  @Prop busy: boolean = false;
  @Prop errorMessage: string = '';
  @Prop bottomPadding: number = 0;
  @State private draft: NovelMaterialEditorDraft = emptyMaterialEditorDraft();
  @State private committing: boolean = false;
  private original: string = '';
  private alive: boolean = false;
  private inputToken: number = 0;
  private titleController: TextInputController = new TextInputController();
  private bodyController: TextAreaController = new TextAreaController();
  private commitFields: () => void = (): void => {};
  onConfirm: (draft: NovelMaterialEditorDraft) => void = (_draft: NovelMaterialEditorDraft): void => {};
  onCancel: () => void = (): void => {};

  aboutToAppear(): void { this.alive = true; this.resetDraft(); }
  aboutToDisappear(): void { this.alive = false; this.inputToken++; }

  private copyDraft(): NovelMaterialEditorDraft {
    return { kind: this.draft.kind, title: this.draft.title, content: this.draft.content, enabled: this.draft.enabled,
      scope: this.draft.scope, aliases: this.draft.aliases, tags: this.draft.tags,
      customKind: this.draft.customKind, injectionMode: this.draft.injectionMode };
  }

  private resetDraft(): void {
    const fields = normalizeNovelMaterialFields(this.initialDraft, this.initialDraft.enabled);
    this.draft = { kind: this.initialDraft.kind, title: this.initialDraft.title, content: this.initialDraft.content,
      enabled: fields.injectionMode !== 'off', scope: this.initialDraft.scope, aliases: fields.aliases,
      tags: fields.tags, customKind: fields.customKind, injectionMode: fields.injectionMode };
    this.original = JSON.stringify(this.draft);
    this.committing = false;
    this.inputToken++;
  }

  private updateText(field: string, value: string): void {
    const next: NovelMaterialEditorDraft = this.copyDraft();
    if (field === 'title') next.title = value;
    else next.content = value;
    this.draft = next;
  }

  private updateFields(fields: NovelMaterialFields): void {
    const next: NovelMaterialEditorDraft = this.copyDraft();
    next.aliases = fields.aliases; next.tags = fields.tags; next.customKind = fields.customKind;
    next.injectionMode = fields.injectionMode; next.enabled = fields.injectionMode !== 'off';
    this.draft = next;
  }

  private blocked(): boolean { return this.busy || this.committing; }

  private cancel(): void {
    if (!this.alive || this.blocked()) return;
    this.titleController.stopEditing(); this.bodyController.stopEditing();
    this.commitFields();
    const token: number = ++this.inputToken;
    setTimeout((): void => {
      if (!this.alive || token !== this.inputToken || this.blocked()) return;
      if (JSON.stringify(this.draft) === this.original) { this.onCancel(); return; }
      AlertDialog.show({ title: '放弃资料编辑？', message: '未保存的资料内容和设置将丢失。',
        primaryButton: { value: '继续编辑', action: (): void => {} },
        secondaryButton: { value: '放弃', fontColor: ERROR, action: (): void => {
          if (this.alive && token === this.inputToken && !this.blocked()) this.onCancel();
        } } });
    }, 0);
  }

  private confirm(): void {
    if (this.blocked()) return;
    this.committing = true;
    this.titleController.stopEditing(); this.bodyController.stopEditing();
    this.commitFields();
    const token: number = ++this.inputToken;
    setTimeout((): void => {
      if (!this.alive || token !== this.inputToken) return;
      this.committing = false;
      if (this.busy || this.draft.title.trim().length === 0) return;
      this.onConfirm(this.copyDraft());
    }, 0);
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      Row() {}.width('100%').height('100%').backgroundColor(OVERLAY).onClick((): void => { this.cancel(); })
      Column({ space: 12 }) {
        Text(this.newMaterial ? '新建资料' : '编辑资料').fontSize(18).fontWeight(FontWeight.Medium).fontColor(INK).width('100%')
        Scroll() {
          Column({ space: 12 }) {
            Text(this.draft.scope === 'shared' ? '保存为共享底稿，未覆盖此资料的其他分支也会使用新内容。'
              : '仅修改当前分支，其他分支和共享底稿保持原样。').fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
            if (this.newMaterial) {
              Row({ space: 12 }) {
                Text('仅当前分支').fontSize(14).height(44).fontColor(this.draft.scope === 'branch' ? ACCENT : INK3)
                  .enabled(!this.blocked()).onClick((): void => { const next = this.copyDraft(); next.scope = 'branch'; this.draft = next; })
                Text('所有分支共享').fontSize(14).height(44).fontColor(this.draft.scope === 'shared' ? ACCENT : INK3)
                  .enabled(!this.blocked()).onClick((): void => { const next = this.copyDraft(); next.scope = 'shared'; this.draft = next; })
              }.width('100%')
            }
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(KINDS, (kind: NovelMaterialKind): void => {
                Text(MATERIAL_KIND_LABELS[kind]).fontSize(13).height(44).padding({ left: 12, right: 12 })
                  .fontColor(this.draft.kind === kind ? ACCENT : INK3).backgroundColor(SURFACE2).borderRadius(10)
                  .margin({ right: 6, bottom: 6 }).enabled(!this.blocked()).onClick((): void => {
                    const next = this.copyDraft(); next.kind = kind; this.draft = next;
                  })
              }, (kind: NovelMaterialKind): string => kind)
            }.width('100%')
            TextInput({ text: this.draft.title, placeholder: '资料名称', controller: this.titleController })
              .width('100%').height(44).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
              .enabled(!this.blocked()).onChange((value: string): void => { this.updateText('title', value); })
            TextArea({ text: this.draft.content, placeholder: '资料内容', controller: this.bodyController })
              .width('100%').height(150).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
              .enabled(!this.blocked()).onChange((value: string): void => { this.updateText('content', value); })
            NovelMaterialMetadataFields({ draftKey: this.draftKey, initialFields: this.initialDraft,
              materialEnabled: this.initialDraft.enabled, busy: this.blocked(), onReady: (commit: () => void): void => { this.commitFields = commit; },
              onChange: (fields: NovelMaterialFields): void => { this.updateFields(fields); } })
            if (this.errorMessage.length > 0) { Text(this.errorMessage).fontSize(13).fontColor(ERROR).lineHeight(18).width('100%') }
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
        Row({ space: 12 }) {
          Text('取消').fontSize(14).fontColor(INK3).height(44).layoutWeight(1).textAlign(TextAlign.Center)
            .enabled(!this.blocked()).onClick((): void => { this.cancel(); })
          Text(this.busy ? '保存中…' : '保存').fontSize(14).fontColor(ACCENT).height(44).layoutWeight(1).textAlign(TextAlign.Center)
            .enabled(!this.blocked() && this.draft.title.trim().length > 0)
            .opacity(this.draft.title.trim().length > 0 ? 1 : 0.45)
            .onClick((): void => { this.confirm(); })
        }.width('100%').border({ width: { top: 1 }, color: { top: LINE } })
      }.width('100%').height('88%').alignItems(HorizontalAlign.Start).backgroundColor(SURFACE)
        .borderRadius({ topLeft: 20, topRight: 20 }).padding({ left: 20, right: 20, top: 18, bottom: this.bottomPadding + 16 })
        .transition(TransitionEffect.translate({ y: this.reduceMotion || this.appBackgrounded ? 0 : 160 })
          .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
    }.width('100%').height('100%')
      .transition(TransitionEffect.OPACITY
        .animation({ duration: this.appBackgrounded ? 0 : MOTION_OVERLAY, curve: Curve.EaseOut }))
  }
}

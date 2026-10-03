import { ACCENT, INK, INK3, SURFACE2 } from '../design/tokens.ets';
import { normalizeNovelMaterialFields } from '@amber/deepread-domain';
import type { NovelMaterialFields, NovelMaterialInjectionMode } from '@amber/deepread-domain';

const INJECTION_MODES: NovelMaterialInjectionMode[] = ['always', 'smart', 'off'];
export const materialInjectionLabel = (mode: NovelMaterialInjectionMode): string =>
  mode === 'always' ? '始终加入' : mode === 'smart' ? '智能匹配' : '不加入';

export const parseMaterialNames = (text: string): string[] => text.split(/[,，\n]/)
  .map((value: string): string => value.trim()).filter((value: string): boolean => value.length > 0);

@Component
export struct NovelMaterialMetadataFields {
  @Prop @Watch('resetDraft') draftKey: string = '';
  @Prop initialFields: NovelMaterialFields = {};
  @Prop materialEnabled: boolean = true;
  @Prop busy: boolean = false;
  @State private aliases: string = '';
  @State private tags: string = '';
  @State private customKind: string = '';
  @State private mode: NovelMaterialInjectionMode = 'always';
  private aliasesController: TextAreaController = new TextAreaController();
  private tagsController: TextAreaController = new TextAreaController();
  private kindController: TextInputController = new TextInputController();
  onReady: (commit: () => void) => void = (_commit: () => void): void => {};
  onChange: (fields: NovelMaterialFields) => void = (_fields: NovelMaterialFields): void => {};

  aboutToAppear(): void {
    this.resetDraft();
    this.onReady((): void => {
      this.aliasesController.stopEditing(); this.tagsController.stopEditing(); this.kindController.stopEditing();
    });
  }

  aboutToDisappear(): void { this.onReady((): void => {}); }

  private resetDraft(): void {
    const fields = normalizeNovelMaterialFields(this.initialFields, this.materialEnabled);
    this.aliases = fields.aliases.join(', ');
    this.tags = fields.tags.join(', ');
    this.customKind = fields.customKind;
    this.mode = fields.injectionMode;
  }

  private publish(): void {
    this.onChange({ aliases: parseMaterialNames(this.aliases), tags: parseMaterialNames(this.tags),
      customKind: this.customKind, injectionMode: this.mode });
  }

  build(): void {
    Column({ space: 10 }) {
      Text('别名（逗号或换行分隔）').fontSize(12).fontColor(INK3).width('100%')
      TextArea({ text: this.aliases, placeholder: '例如：小林，林队长', controller: this.aliasesController }).width('100%').height(72)
        .fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
        .enabled(!this.busy).onChange((value: string): void => { this.aliases = value; this.publish(); })
      Text('标签（逗号或换行分隔）').fontSize(12).fontColor(INK3).width('100%')
      TextArea({ text: this.tags, placeholder: '例如：主角，调查组', controller: this.tagsController }).width('100%').height(72)
        .fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
        .enabled(!this.busy).onChange((value: string): void => { this.tags = value; this.publish(); })
      TextInput({ text: this.customKind, placeholder: '自定义分类（可选）', controller: this.kindController }).width('100%').height(44)
        .fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2)
        .enabled(!this.busy).onChange((value: string): void => { this.customKind = value; this.publish(); })
      Text('写作上下文').fontSize(12).fontColor(INK3).width('100%')
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(INJECTION_MODES, (mode: NovelMaterialInjectionMode): void => {
          Text(materialInjectionLabel(mode)).fontSize(13).fontColor(this.mode === mode ? ACCENT : INK3)
            .height(44).padding({ left: 12, right: 12 }).backgroundColor(SURFACE2).borderRadius(10)
            .margin({ right: 8 }).enabled(!this.busy)
            .onClick((): void => { this.mode = mode; this.publish(); })
        }, (mode: NovelMaterialInjectionMode): string => mode)
      }.width('100%')
      Text('智能匹配依据当前输入、章节计划和资料名称、别名、标签选择资料；不加入的资料仍会保留。')
        .fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
}

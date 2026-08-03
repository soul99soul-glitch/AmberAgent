// TagsInput — 可编辑 chip 标签列表(ui/TagList.kt:41-209)
// Android 基准: FlowRow 已选 chip(可删) + 添加按钮 → AlertDialog(现有标签选择 + 新建)
//   Tag 类型: { id: Uuid, name: string }
//
// ArkUI 差异: FlowRow → Flex({ wrap: FlexWrap.Wrap }); AlertDialog → ConfirmDialog/bindSheet
import { INK2, INK3, ACCENT, SURFACE, SURFACE2, LINE, ERROR, BODY, META, LABEL } from '../../design/tokens.ets';

export interface AmberTag {
  id: string;
  name: string;
}

@Component
export struct TagsInput {
  @Prop selectedIds: string[] = [];
  @Prop tags: AmberTag[] = [];
  onChange: (selectedIds: string[], tags: AmberTag[]) => void
    = (_ids: string[], _tags: AmberTag[]): void => {};
  @State showAddDialog: boolean = false;
  @State newTagName: string = '';
  @State showError: boolean = false;

  private isSelected(id: string): boolean {
    return this.selectedIds.indexOf(id) >= 0;
  }

  private removeTag(id: string): void {
    const next: string[] = this.selectedIds.filter((x: string): boolean => x !== id);
    this.onChange(next, this.tags);
  }

  private addExisting(id: string): void {
    const next: string[] = [...this.selectedIds, id];
    this.onChange(next, this.tags);
    this.showAddDialog = false;
    this.newTagName = '';
    this.showError = false;
  }

  private createNew(): void {
    const trimmed: string = this.newTagName.trim();
    if (trimmed.length === 0) return;
    const exists: boolean = this.tags.some((t: AmberTag): boolean =>
      t.name.toLowerCase() === trimmed.toLowerCase());
    if (exists) {
      this.showError = true;
      return;
    }
    const newTag: AmberTag = { id: `tag_${Date.now()}`, name: trimmed };
    this.onChange([...this.selectedIds, newTag.id], [...this.tags, newTag]);
    this.showAddDialog = false;
    this.newTagName = '';
    this.showError = false;
  }

  private unselectedTags(): AmberTag[] {
    return this.tags.filter((t: AmberTag): boolean => !this.isSelected(t.id));
  }

  build(): void {
    Column() {
      // chip 列表 + 添加按钮
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(this.tags, (tag: AmberTag): void => {
          if (this.isSelected(tag.id)) {
            Row() {
              Text(tag.name).fontSize(META).fontColor(INK2)
              Text('✕').fontSize(LABEL).fontColor(INK3).margin({ left: 4 })
                .onClick((): void => { this.removeTag(tag.id); })
            }
            .padding({ left: 10, right: 8, top: 4, bottom: 4 })
            .backgroundColor(SURFACE2)
            .borderRadius(999)
            .border({ width: 1, color: LINE })
            .margin({ right: 8, bottom: 4 })
          }
        }, (tag: AmberTag): string => tag.id)

        // 添加按钮
        Text('+').fontSize(BODY).fontColor(ACCENT)
          .width(28).height(28)
          .textAlign(TextAlign.Center)
          .backgroundColor(SURFACE2)
          .borderRadius(999)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 4 })
          .onClick((): void => {
            this.showAddDialog = true;
            this.newTagName = '';
            this.showError = false;
          })
      }
      .width('100%')
    }
    .width('100%')
    .bindSheet(this.showAddDialog, this.AddDialog(), {
      height: SheetSize.MEDIUM,
      onDisappear: (): void => { this.showAddDialog = false; },
    })
  }

  @Builder AddDialog(): void {
    Column() {
      Text('添加标签').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ left: 20, top: 20, bottom: 12 })

      // 未选标签
      if (this.unselectedTags().length > 0) {
        Text('现有标签').fontSize(LABEL).fontColor(INK3)
          .width('100%').padding({ left: 20, bottom: 8 })
        Flex({ wrap: FlexWrap.Wrap }) {
          ForEach(this.unselectedTags(), (tag: AmberTag): void => {
            Text(tag.name).fontSize(META).fontColor(INK2)
              .padding({ left: 10, right: 10, top: 4, bottom: 4 })
              .backgroundColor(SURFACE2)
              .borderRadius(999)
              .border({ width: 1, color: LINE })
              .margin({ right: 8, bottom: 4 })
              .onClick((): void => { this.addExisting(tag.id); })
          }, (tag: AmberTag): string => tag.id)
        }
        .width('100%').padding({ left: 20, right: 20, bottom: 12 })
      }

      // 新建
      Text('新建标签').fontSize(LABEL).fontColor(INK3)
        .width('100%').padding({ left: 20, bottom: 8 })
      TextInput({ text: this.newTagName, placeholder: '标签名称' })
        .fontSize(BODY).fontColor(INK)
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 1, color: this.showError ? ERROR : LINE })
        .padding({ left: 12, right: 12, top: 10, bottom: 10 })
        .width('80%')
        .onChange((v: string): void => { this.newTagName = v; this.showError = false; })

      if (this.showError) {
        Text('同名标签已存在').fontSize(LABEL).fontColor(ERROR)
          .padding({ left: 20, top: 4 })
      }

      Row() {
        Blank()
        Text('取消').fontSize(BODY).fontColor(INK3)
          .padding({ left: 16, right: 16, top: 8, bottom: 8 })
          .onClick((): void => { this.showAddDialog = false; })
        Text('确认').fontSize(BODY).fontColor(ACCENT)
          .padding({ left: 16, right: 16, top: 8, bottom: 8 })
          .onClick((): void => { this.createNew(); })
      }
      .width('100%').margin({ top: 16 })
    }
    .width('100%')
  }
}

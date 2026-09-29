// TagsInput — 可编辑 chip 标签列表(ui/TagList.kt:209 行)
// Android 基准: FlowRow 已选 chip(可删) + 添加按钮 → AlertDialog(现有标签选择 + 新建)
//   Tag 类型: { id: Uuid, name: string }
//
// 升级 (G6-S3): 内联编辑(tap chip 改名) / 上下重排(▲▼) / 上限告警 / 去重 / 建议下拉(自动补全)
//
// ArkUI 差异: FlowRow → Flex({ wrap: FlexWrap.Wrap }); AlertDialog → bindSheet
import { INK, INK2, INK3, INK4, ACCENT, SURFACE, SURFACE2, LINE, ERROR, SIGNAL, BODY, META, LABEL } from '../../design/tokens.ets';

export interface AmberTag {
  id: string;
  name: string;
}

@Component
export struct TagsInput {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  // ===== 既有 API (向后兼容) =====
  @Prop selectedIds: string[] = [];
  @Prop tags: AmberTag[] = [];
  onChange: (selectedIds: string[], tags: AmberTag[]) => void
    = (_ids: string[], _tags: AmberTag[]): void => {};
  // ===== 新增 Props (默认值保证向后兼容) =====
  @Prop maxTags: number = 0;            // 0 = 不限
  @Prop suggestions: string[] = [];     // 预定义自动补全候选

  // ===== 既有状态 =====
  @State showAddDialog: boolean = false;
  @State newTagName: string = '';
  @State showError: boolean = false;
  // ===== 新增状态 =====
  @State editingId: string = '';
  @State editingName: string = '';
  @State editError: boolean = false;
  @State showMaxWarning: boolean = false;

  private isSelected(id: string): boolean {
    return this.selectedIds.indexOf(id) >= 0;
  }

  private atMax(): boolean {
    return this.maxTags > 0 && this.selectedIds.length >= this.maxTags;
  }

  private removeTag(id: string): void {
    const next: string[] = this.selectedIds.filter((x: string): boolean => x !== id);
    this.onChange(next, this.tags);
    this.showMaxWarning = false;
  }

  // 简化拖拽: ▲▼ 按钮在 selectedIds 序列内前后移动
  private moveTag(id: string, delta: number): void {
    const idx: number = this.selectedIds.indexOf(id);
    if (idx < 0) return;
    const target: number = idx + delta;
    if (target < 0 || target >= this.selectedIds.length) return;
    const next: string[] = this.selectedIds.slice();
    const tmp: string = next[idx];
    next[idx] = next[target];
    next[target] = tmp;
    this.onChange(next, this.tags);
  }

  private startEdit(id: string, name: string): void {
    this.editingId = id;
    this.editingName = name;
    this.editError = false;
  }

  private cancelEdit(): void {
    this.editingId = '';
    this.editingName = '';
    this.editError = false;
  }

  // 内联改名: 去重(排除自身) → 通过 onChange 回写新 tags
  private commitEdit(): void {
    if (this.editingId.length === 0) return;
    const trimmed: string = this.editingName.trim();
    if (trimmed.length === 0) { this.cancelEdit(); return; }
    const dup: boolean = this.tags.some((t: AmberTag): boolean =>
      t.id !== this.editingId && t.name.toLowerCase() === trimmed.toLowerCase());
    if (dup) { this.editError = true; return; }
    const nextTags: AmberTag[] = this.tags.map((t: AmberTag): AmberTag => {
      if (t.id === this.editingId) return { id: t.id, name: trimmed };
      return { id: t.id, name: t.name };
    });
    this.onChange(this.selectedIds, nextTags);
    this.cancelEdit();
  }

  private addExisting(id: string): void {
    if (this.atMax()) { this.showMaxWarning = true; return; }
    const next: string[] = [...this.selectedIds, id];
    this.onChange(next, this.tags);
    this.showAddDialog = false;
    this.newTagName = '';
    this.showError = false;
    this.showMaxWarning = false;
  }

  private createNew(): void {
    if (this.atMax()) { this.showMaxWarning = true; return; }
    const trimmed: string = this.newTagName.trim();
    if (trimmed.length === 0) return;
    // 去重: 大小写不敏感
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
    this.showMaxWarning = false;
  }

  private selectedTags(): AmberTag[] {
    const byId: Record<string, AmberTag> = {};
    for (const tag of this.tags) byId[tag.id] = tag;
    const ordered: AmberTag[] = [];
    for (const id of this.selectedIds) {
      const tag: AmberTag | undefined = byId[id];
      if (tag !== undefined) ordered.push(tag);
    }
    return ordered;
  }

  private unselectedTags(): AmberTag[] {
    return this.tags.filter((t: AmberTag): boolean => !this.isSelected(t.id));
  }

  // 自动补全: suggestions prop + 未选 tag 名,按输入过滤去重,上限 5
  private filteredSuggestions(): string[] {
    const q: string = this.newTagName.trim().toLowerCase();
    if (q.length === 0) return [];
    const result: string[] = [];
    for (let i = 0; i < this.suggestions.length; i++) {
      if (this.suggestions[i].toLowerCase().indexOf(q) >= 0) result.push(this.suggestions[i]);
    }
    for (let i = 0; i < this.tags.length; i++) {
      const t: AmberTag = this.tags[i];
      if (this.isSelected(t.id)) continue;
      if (t.name.toLowerCase().indexOf(q) >= 0) result.push(t.name);
    }
    const seen: string[] = [];
    const unique: string[] = [];
    for (let i = 0; i < result.length; i++) {
      const lower: string = result[i].toLowerCase();
      if (seen.indexOf(lower) < 0) { seen.push(lower); unique.push(result[i]); }
      if (unique.length >= 5) break;
    }
    return unique;
  }

  build(): void {
    Column() {
      // chip 列表 + 添加按钮
      Flex({ wrap: FlexWrap.Wrap }) {
        ForEach(this.selectedTags(), (tag: AmberTag): void => {
          if (this.editingId === tag.id) {
            this.EditChip()
          } else {
            this.Chip(tag)
          }
        }, (tag: AmberTag): string => tag.id)

        // 添加按钮(达上限时置灰;40 热区)
        Text('+').fontSize(BODY).fontColor(this.atMax() ? INK4 : ACCENT)
          .width(40).height(40)
          .textAlign(TextAlign.Center)
          .backgroundColor(SURFACE2)
          .borderRadius(999)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 4 })
          .onClick((): void => {
            if (this.atMax()) { this.showMaxWarning = true; return; }
            this.showAddDialog = true;
            this.newTagName = '';
            this.showError = false;
            this.showMaxWarning = false;
          })
      }
      .width('100%')

      // 上限告警
      if (this.showMaxWarning && this.maxTags > 0) {
        Text(`最多 ${this.maxTags} 个标签`).fontSize(LABEL).fontColor(ERROR)
          .width('100%').margin({ top: 4 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .bindSheet(this.showAddDialog, this.AddDialog(), {
      height: SheetSize.MEDIUM,
      onDisappear: (): void => { this.showAddDialog = false; },
    })
  }

  // 普通态 chip: 名(tap 改名) + ✕(删) + ▲▼(重排)
  @Builder Chip(tag: AmberTag): void {
    Row() {
      Text(tag.name).fontSize(META).fontColor(INK2)
        .onClick((): void => { this.startEdit(tag.id, tag.name); })
      Text('✕').fontSize(LABEL).fontColor(INK3).margin({ left: 4 })
        .onClick((): void => { this.removeTag(tag.id); })
      Text('▲').fontSize(8).fontColor(INK3).margin({ left: 2 })
        .onClick((): void => { this.moveTag(tag.id, -1); })
      Text('▼').fontSize(8).fontColor(INK3)
        .onClick((): void => { this.moveTag(tag.id, 1); })
    }
    .padding({ left: 10, right: 8, top: 4, bottom: 4 })
    .backgroundColor(SURFACE2)
    .borderRadius(999)
    .border({ width: 1, color: LINE })
    .margin({ right: 8, bottom: 4 })
  }

  // 编辑态 chip: TextInput + ✓(提交) + ✕(取消)
  @Builder EditChip(): void {
    Row() {
      TextInput({ text: this.editingName })
        .fontSize(META).fontColor(INK)
        .backgroundColor(SURFACE)
        .borderRadius(999)
        .border({ width: 1, color: this.editError ? ERROR : ACCENT })
        .padding({ left: 10, right: 8, top: 2, bottom: 2 })
        .width(120)
        .onChange((v: string): void => { this.editingName = v; this.editError = false; })
        .onSubmit((_enterKey: EnterKeyType): void => { this.commitEdit(); })
      Text('✓').fontSize(LABEL).fontColor(SIGNAL).margin({ left: 4 })
        .onClick((): void => { this.commitEdit(); })
      Text('✕').fontSize(LABEL).fontColor(INK3).margin({ left: 2 })
        .onClick((): void => { this.cancelEdit(); })
    }
    .margin({ right: 8, bottom: 4 })
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

      // 建议下拉(自动补全)
      if (this.filteredSuggestions().length > 0) {
        Column() {
          ForEach(this.filteredSuggestions(), (s: string): void => {
            Text(s).fontSize(META).fontColor(INK2)
              .width('100%')
              .padding({ left: 12, right: 12, top: 6, bottom: 6 })
              .onClick((): void => { this.newTagName = s; this.showError = false; })
          }, (s: string): string => `sg-${s}`)
        }
        .width('80%')
        .backgroundColor(SURFACE2)
        .borderRadius(8)
        .border({ width: 1, color: LINE })
        .margin({ top: 4 })
      }

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

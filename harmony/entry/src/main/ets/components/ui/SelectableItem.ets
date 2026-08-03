// SelectableItem — 带选中态的列表行(ui/ListSelectableItem.kt:13-39)
// Android 基准: Row { Checkbox + content(weight 1f) }
//   支持多选模式: key in selectedKeys → checked
//
// ArkUI: content slot 用 @BuilderParam;Checkbox 用 ArkUI Checkbox
@Component
export struct SelectableItem {
  @Prop itemKey: string = '';
  @Prop selectedKeys: string[] = [];
  @Prop enabled: boolean = true;
  onSelectChange: (key: string) => void = (_k: string): void => {};
  @BuilderParam content: () => void;

  private isSelected(): boolean {
    return this.selectedKeys.indexOf(this.itemKey) >= 0;
  }

  build(): void {
    Row() {
      if (this.enabled) {
        Checkbox()
          .select(this.isSelected())
          .selectedColor(ACCENT)
          .onChange((v: boolean): void => { this.onSelectChange(this.itemKey); })
      }
      Column() {
        this.content();
      }
      .layoutWeight(1)
    }
    .width('100%')
  }
}

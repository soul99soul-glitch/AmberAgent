// WorkspaceSearchField — 带图标/清除的搜索框(对齐 Android WorkspaceSearchField.kt)
// 左侧搜索图标 + 输入区 + 右侧清除按钮(有文本时显示)
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, ACCENT, BODY, META } from '../../design/tokens.ets';
import { Icon } from './Icon.ets';

@Component
export struct WorkspaceSearchField {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop placeholder: string = '搜索';
  @State searchText: string = '';
  onSearch: (text: string) => void = (_t: string): void => {};

  build(): void {
    Row() {
      // 搜索图标
      Icon({ name: 'search', iconSize: 14, color: INK3 })
        .margin({ right: 8 })

      // 输入区
      TextInput({ text: this.searchText, placeholder: this.placeholder })
        .fontSize(BODY)
        .fontColor(INK)
        .backgroundColor('transparent')
        .layoutWeight(1)
        .onChange((value: string): void => {
          this.searchText = value;
          this.onSearch(value);
        })

      // 清除按钮
      if (this.searchText.length > 0) {
        Text('✕')
          .fontSize(12)
          .fontColor(INK3)
          .padding({ left: 8, right: 4 })
          .onClick((): void => {
            this.searchText = '';
            this.onSearch('');
          })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .backgroundColor(SURFACE2)
    .borderRadius(8)
    .alignItems(VerticalAlign.Center)
  }
}

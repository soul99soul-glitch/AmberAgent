// AmberTextArea — 多行文本输入 + 文件导入 + 全屏编辑(ui/TextArea.kt:68-254)
// Android 基准: label header + 全屏图标 + 文件导入图标 + 多行 OutlinedTextField + FullScreenTextEditor dialog
//
// G6-S5 升级(对齐 Android TextArea.kt 254 行):
//   - 全屏编辑触发(已存在,保留 enableFullscreen)
//   - maxLength 字数上限强制 + 字数计数(maxLength > 0 时显示)
//   - 聚焦态边框色 LINE → ACCENT(onFocus/onBlur)
//   - 错误态:红色边框 + errorText(showError=true 时)
//   - placeholderColor 可配(默认 INK4)
//
// API 兼容:原有 value/label/placeholder/minLines/maxLines/enabled/enableFullscreen/
//   onChange/onImportFile 全部保留;新增 maxLength/showError/errorText 均为可选
//
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;@Prop 无保留字
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, ERROR, BODY, META, LABEL } from '../../design/tokens.ets';
import { Icon } from './Icon.ets';

@Component
export struct AmberTextArea {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop value: string = '';
  @Prop label: string = '';
  @Prop placeholder: string = '';
  @Prop placeholderColor: string = INK4;
  @Prop minLines: number = 5;
  @Prop maxLines: number = 10;
  // inputEnabled:原 prop 名 enabled 与 CommonAttribute.enabled 方法冲突
  //   (组件被实例化时才触发 ArkTS 基类兼容检查),改名规避
  @Prop inputEnabled: boolean = true;
  @Prop enableFullscreen: boolean = true;
  // G6-S5 新增 —— maxLength > 0 启用字数上限 + 计数
  @Prop maxLength: number = 0;
  // G6-S5 新增 —— 错误态:true 时红边 + errorText
  @Prop showError: boolean = false;
  @Prop errorText: string = '';
  onChange: (value: string) => void = (_v: string): void => {};
  onImportFile: () => void = (): void => {};
  @State fullScreenOpen: boolean = false;
  @State fullScreenText: string = '';
  @State focused: boolean = false;

  // maxLength 强制:超长截断(与 Android TextField lineLimits 等价的软上限)
  private clampLength(v: string): string {
    if (this.maxLength > 0 && v.length > this.maxLength) {
      return v.substring(0, this.maxLength);
    }
    return v;
  }

  // 当前边框色:错误态 > 聚焦态 > 默认(命名规避 CommonAttribute.borderColor)
  private fieldBorderColor(): string {
    if (this.showError) return ERROR;
    if (this.focused) return ACCENT;
    return LINE;
  }

  build(): void {
    Column() {
      if (this.label.length > 0) {
        Row() {
          Text(this.label).fontSize(BODY).fontColor(INK2).layoutWeight(1)
          if (this.enableFullscreen) {
            Text('⛶').fontSize(BODY).fontColor(INK3)
              .width(44).height(44).textAlign(TextAlign.Center)
              .onClick((): void => {
                this.fullScreenText = this.value;
                this.fullScreenOpen = true;
              })
          }
          Row() {
            Icon({ name: 'folder', iconSize: BODY, color: INK3 })
          }
            .width(44).height(44).margin({ left: 12 })
            .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
            .onClick((): void => { this.onImportFile(); })
        }
        .width('100%')
        .padding({ top: 8, bottom: 4 })
      }

      TextArea({ text: this.value, placeholder: this.placeholder })
        .fontSize(BODY).fontColor(this.inputEnabled ? INK : INK3)
        .placeholderColor(this.placeholderColor)
        .backgroundColor(SURFACE)
        .borderRadius(12)
        .border({ width: 1, color: this.fieldBorderColor() })
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
        .width('100%')
        .constraintSize({ minHeight: this.minLines * 24 })
        .maxLines(this.maxLines)
        .enabled(this.inputEnabled)
        .onFocus((): void => { this.focused = true; })
        .onBlur((): void => { this.focused = false; })
        .onChange((v: string): void => { this.onChange(this.clampLength(v)); })

      // 字数计数 + 错误文本行(maxLength>0 或 showError 时显示)
      if (this.maxLength > 0 || this.showError) {
        Row() {
          if (this.showError) {
            Text(this.errorText)
              .fontSize(LABEL).fontColor(ERROR)
              .layoutWeight(1)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
          } else {
            Blank().layoutWeight(1)
          }
          if (this.maxLength > 0) {
            Text(`${this.value.length}/${this.maxLength}`)
              .fontSize(LABEL).fontColor(this.value.length > this.maxLength ? ERROR : INK3)
          }
        }
        .width('100%')
        .padding({ top: 4, left: 2, right: 2 })
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .bindSheet(this.fullScreenOpen, this.FullScreenEditor(), {
      height: SheetSize.LARGE,
      showClose: false,
      onDisappear: (): void => { this.fullScreenOpen = false; },
    })
  }

  @Builder FullScreenEditor(): void {
    Column() {
      Row() {
        Text('取消').fontSize(BODY).fontColor(INK3)
          .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.Center)
          .onClick((): void => { this.fullScreenOpen = false; })
        Blank()
        Text('保存').fontSize(BODY).fontColor(INK)
          .constraintSize({ minWidth: 44, minHeight: 44 }).textAlign(TextAlign.Center)
          .onClick((): void => {
            this.onChange(this.clampLength(this.fullScreenText));
            this.fullScreenOpen = false;
          })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 12, bottom: 8 })

      TextArea({ text: this.fullScreenText, placeholder: this.label.length > 0 ? this.label : this.placeholder })
        .fontSize(BODY).fontColor(INK)
        .placeholderColor(this.placeholderColor)
        .backgroundColor(SURFACE2)
        .borderRadius(12)
        .padding(16)
        .width('100%')
        .layoutWeight(1)
        .onChange((v: string): void => { this.fullScreenText = this.clampLength(v); })

      if (this.maxLength > 0) {
        Row() {
          Blank()
          Text(`${this.fullScreenText.length}/${this.maxLength}`)
            .fontSize(LABEL).fontColor(this.fullScreenText.length > this.maxLength ? ERROR : INK3)
        }
        .width('100%')
        .padding({ right: 16, bottom: 12 })
      }
    }
    .width('100%').height('100%')
    .backgroundColor(SURFACE2)
  }
}

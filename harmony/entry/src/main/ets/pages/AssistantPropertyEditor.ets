// AssistantPropertyEditor — 通用属性编辑器
// 路由参数:propertyName / value / type(text | number | boolean)。
// 依 type 渲染对应编辑器:text → TextInput;number → FlatTextField;
//   boolean → AmberSwitch。保存即 router.back()(值由调用方自行回读)。
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface RouterParams {
  propertyName?: string;
  value?: string;
  type?: string;
}

@Entry
@Component
struct AssistantPropertyEditor {
  @State loaded: boolean = false;
  @State propName: string = '';
  @State propType: string = 'text';
  @State editText: string = '';
  @State editBool: boolean = false;

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.propName = params !== null && params.propertyName !== undefined ? params.propertyName : '属性';
    this.propType = params !== null && params.type !== undefined ? params.type : 'text';
    const raw: string = params !== null && params.value !== undefined ? params.value : '';
    if (this.propType === 'boolean') {
      this.editBool = raw === 'true';
    } else {
      this.editText = raw;
    }
    // 异步置 loaded(对齐 aboutToAppear async load 惯例)
    Promise.resolve().then((): void => { this.loaded = true; });
  }

  private save(): void {
    router.back();
  }

  private titleText(): string {
    return this.propName.length > 0 ? this.propName : '编辑属性';
  }

  @Builder fieldLabel(text: string): void {
    Text(text).fontSize(META).fontColor(INK3).margin({ left: 2, bottom: 6 })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text(this.titleText()).fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.save(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Column() {
          // 类型标签
          Row() {
            Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
            Text(` 类型 · ${this.propType}`).fontSize(META).fontColor(INK3)
          }
          .width('100%').padding({ left: 16, top: 20, bottom: 8 })

          if (this.propType === 'boolean') {
            Row() {
              Column() {
                Text(this.propName).fontSize(BODY).fontColor(INK)
                Text(this.editBool ? '当前: true' : '当前: false')
                  .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              AmberSwitch({
                checked: this.editBool,
                switchSize: SwitchSize.Medium,
                onCheckedChange: (c: boolean): void => { this.editBool = c; },
              })
            }
            .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .backgroundColor(SURFACE).borderRadius(12).border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16 })
          } else if (this.propType === 'number') {
            Column() {
              this.fieldLabel(this.propName)
              FlatTextField({
                value: this.editText, label: '数值',
                placeholder: '输入数字',
                onChange: (v: string): void => { this.editText = v; },
              })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .backgroundColor(SURFACE).borderRadius(12).border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16 })
          } else {
            Column() {
              this.fieldLabel(this.propName)
              TextInput({ text: this.editText, placeholder: '输入文本' })
                .fontSize(BODY).height(44).backgroundColor(SURFACE2)
                .borderRadius(12).border({ width: 1, color: LINE })
                .padding({ left: 14, right: 14 })
                .onChange((v: string): void => { this.editText = v; })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 12, bottom: 12 })
            .backgroundColor(SURFACE).borderRadius(12).border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16 })
          }

          Column() {}.height(32)
        }
        .width('100%')
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

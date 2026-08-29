// SettingExperimentalWebMountPage — WebMount 设置页
// 全局开关语义对齐 Android LocalTools.kt:153-166:
//   全局开关 ON → 所有助手获得 WebMount 工具;助手级勾选作为手动覆盖。
//   wm_eval 由独立 eval 全局开关门控,且每次调用仍强制人工确认(工具层
//   mandatoryApproval,不因本页开关放开)。
// 鸿蒙 MVP 工具集:wm_open/state/extract/click/type/scroll/screenshot/eval
//   (单会话;tap/keys/select/find/tab_*/visual_*/fetch 系为后续梯队)。

import { BG, INK, INK3, BODY, META, LABEL, SURFACE, ACCENT, TEXT_STYLES } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import { getChatKvStore } from '../di/AppContainer.ets';

const TOOLS: string[] = [
  'wm_open — 打开网址并等待加载',
  'wm_state — 会话状态(URL / 就绪)',
  'wm_extract — 页面大纲 + 可点元素 ref',
  'wm_click — 点击元素(ref 或 CSS 选择器)',
  'wm_type — 输入框键入文本',
  'wm_scroll — 页面滚动(dx/dy)',
  'wm_screenshot — 视口截图(base64 图片)',
  'wm_eval — 会话内执行任意 JS(逐次人工确认)',
];

@Entry
@Component
struct SettingExperimentalWebMountPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State loaded: boolean = false;
  @State globalEnabled: boolean = false;
  @State evalEnabled: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    const kv = getChatKvStore();
    kv.get('webmount_global_enabled').then((raw: string | null): void => {
      this.globalEnabled = raw === 'true';
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
    kv.get('webmount_eval_enabled').then((raw: string | null): void => {
      this.evalEnabled = raw === 'true';
    }).catch((): void => {});
  }

  private setToggle(key: string, value: boolean): void {
    getChatKvStore().put(key, value ? 'true' : 'false').catch((): void => {});
  }

  build(): void {
    Column() {
      PageTitleBar({ title: 'WebMount' })

      if (!this.loaded) {
        Text('加载中…').fontSize(META).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            SectionHeader({ label: '开关' })

            Row() {
              Column() {
                Text('启用 WebMount 工具').fontSize(BODY).fontColor(INK)
                Text('全局开启后所有助手获得 WebMount 工具;助手级勾选为手动覆盖')
                  .fontSize(LABEL).fontColor(INK3).margin({ top: 3 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
              Toggle({ type: ToggleType.Switch, isOn: this.globalEnabled })
                .selectedColor(ACCENT)
                .onChange((v: boolean): void => {
                  this.globalEnabled = v;
                  this.setToggle('webmount_global_enabled', v);
                })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)

            PaperSeparator()

            Row() {
              Column() {
                Text('允许 wm_eval(任意 JS)').fontSize(BODY).fontColor(INK)
                Text('会话页面 origin 下执行任意 JavaScript;每次调用仍需人工逐次确认')
                  .fontSize(LABEL).fontColor(INK3).margin({ top: 3 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
              Toggle({ type: ToggleType.Switch, isOn: this.evalEnabled })
                .selectedColor(ACCENT)
                .onChange((v: boolean): void => {
                  this.evalEnabled = v;
                  this.setToggle('webmount_eval_enabled', v);
                })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)

            SectionHeader({ label: '工具集(MVP)' })
            Column() {
              ForEach(TOOLS, (t: string, i: number): void => {
                Column() {
                  Text(t)
                    .fontSize(META).fontColor(INK3)
                    .fontFamily(TEXT_STYLES.meta.family)
                    .width('100%')
                    .padding({ left: 16, right: 16, top: 11, bottom: 11 })
                  if (i < TOOLS.length - 1) {
                    PaperSeparator()
                  }
                }.width('100%')
              }, (t: string): string => t)
            }
            .width('100%').backgroundColor(SURFACE)

            Text('会话在聊天页内按需启动(隐藏浏览器);MVP 为单会话,tap/keys/tab 管理与 '
              + 'signed_fetch 等高级件为后续梯队。')
              .fontSize(LABEL).fontColor(INK3).width('100%')
              .padding({ left: 16, right: 16, top: 10, bottom: 10 })

            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

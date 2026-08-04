// SettingExperimentalWebMountPage — 网页挂载设置页
// URL 输入 + 挂载点选择 + 启用开关 + 自动刷新开关 + 刷新间隔
// 占位持久化(本地草稿);后续接入 chat-domain 后替换 load/save
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { PillSelect } from '../components/ui/Select.ets';

interface WebMountSettings {
  url: string;
  mountPointIndex: number;
  enabled: boolean;
  autoRefresh: boolean;
  refreshInterval: number;
}

function makeWebMount(url: string, mountPointIndex: number, enabled: boolean, autoRefresh: boolean, refreshInterval: number): WebMountSettings {
  return { url: url, mountPointIndex: mountPointIndex, enabled: enabled, autoRefresh: autoRefresh, refreshInterval: refreshInterval };
}

function defaultWebMount(): WebMountSettings {
  return makeWebMount('', 0, false, false, 300);
}

function placeholderLoadWebMount(): Promise<WebMountSettings> {
  return Promise.resolve(defaultWebMount());
}

@Entry
@Component
struct SettingExperimentalWebMountPage {
  @State s: WebMountSettings = defaultWebMount();
  @State loaded: boolean = false;
  @State savedMsg: string = '';
  private mountPoints: string[] = ['/context', '/sidebar', '/inline'];

  aboutToAppear(): void {
    placeholderLoadWebMount().then((v: WebMountSettings): void => {
      this.s = v;
      this.loaded = true;
    });
  }

  private setUrl(v: string): void {
    const c: WebMountSettings = this.s;
    this.s = makeWebMount(v, c.mountPointIndex, c.enabled, c.autoRefresh, c.refreshInterval);
  }

  private setMountPoint(i: number): void {
    const c: WebMountSettings = this.s;
    this.s = makeWebMount(c.url, i, c.enabled, c.autoRefresh, c.refreshInterval);
  }

  private setEnabled(v: boolean): void {
    const c: WebMountSettings = this.s;
    this.s = makeWebMount(c.url, c.mountPointIndex, v, c.autoRefresh, c.refreshInterval);
  }

  private setAutoRefresh(v: boolean): void {
    const c: WebMountSettings = this.s;
    this.s = makeWebMount(c.url, c.mountPointIndex, c.enabled, v, c.refreshInterval);
  }

  private setRefreshInterval(v: string): void {
    const n: number = parseInt(v, 10);
    const value: number = isNaN(n) ? 0 : n;
    const c: WebMountSettings = this.s;
    this.s = makeWebMount(c.url, c.mountPointIndex, c.enabled, c.autoRefresh, value);
  }

  private save(): void {
    this.savedMsg = '已保存';
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.save();
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder toggleRow(label: string, desc: string, checked: boolean, onToggle: () => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      AmberSwitch({
        checked: checked,
        switchSize: SwitchSize.Small,
        onCheckedChange: (_c: boolean): void => { onToggle(); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  @Builder selectRow(label: string, desc: string, options: string[], index: number, onSelect: (i: number) => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      PillSelect({
        options: options, selectedIndex: index,
        onSelect: (i: number): void => { onSelect(i); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 保存
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('网页挂载').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('挂载源')

            Column() {
              FlatTextField({
                value: this.s.url, label: '网页 URL', placeholder: 'https://example.com/docs',
                supportingText: '挂载后网页内容将作为上下文引用。',
                onChange: (v: string): void => { this.setUrl(v); },
              })
            }
            .width('100%').padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)

            this.sectionHeader('挂载点')

            this.selectRow('挂载位置', '内容挂载到上下文的位置', this.mountPoints, this.s.mountPointIndex,
              (i: number): void => { this.setMountPoint(i); })

            this.sectionHeader('开关')

            this.toggleRow('启用网页挂载', '开启后该网页将被实时挂载', this.s.enabled,
              (): void => { this.setEnabled(!this.s.enabled); })

            this.toggleRow('自动刷新', '定期重新抓取网页内容', this.s.autoRefresh,
              (): void => { this.setAutoRefresh(!this.s.autoRefresh); })

            this.sectionHeader('刷新间隔')

            Column() {
              FlatTextField({
                value: `${this.s.refreshInterval}`, label: '刷新间隔 (秒)', placeholder: '300',
                onChange: (v: string): void => { this.setRefreshInterval(v); },
              })
              Text('建议 60-3600 秒;设为 0 则不自动刷新。')
                .fontSize(LABEL).fontColor(INK3)
                .margin({ top: 6, left: 2 })
            }
            .width('100%').padding(16).backgroundColor(SURFACE)
            .border({ width: 1, color: LINE }).borderRadius(12)

            Row() {
              Text('立即抓取').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => {
                  this.savedMsg = '抓取中…(占位)';
                  setTimeout((): void => { this.savedMsg = '已抓取(占位)'; }, 600);
                })
              Blank()
              if (this.savedMsg.length > 0) {
                Text(this.savedMsg).fontSize(META).fontColor(ACCENT)
              }
            }
            .width('100%').margin({ top: 12, bottom: 32 })
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

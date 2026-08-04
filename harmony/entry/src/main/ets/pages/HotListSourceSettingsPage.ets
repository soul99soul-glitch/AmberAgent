// HotListSourceSettingsPage — 热榜来源设置
// 各来源启用开关(AmberSwitch)+ 刷新间隔选择(点击循环切换)
// Android 基准: feature/ui/pages/board/HotListSourceSettingsPage.kt
import { router } from '@kit.ArkUI';
import { AmberSwitch } from '../design/components.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

const INTERVALS: number[] = [15, 30, 60, 120, 240];

interface SourceSetting {
  id: string;
  name: string;
  enabled: boolean;
  intervalMinutes: number;
}

function makeSource(id: string, name: string, enabled: boolean, intervalMinutes: number): SourceSetting {
  return { id: id, name: name, enabled: enabled, intervalMinutes: intervalMinutes };
}

function placeholderLoadSources(): Promise<SourceSetting[]> {
  const seed: SourceSetting[] = [
    makeSource('zhihu', '知乎热榜', true, 30),
    makeSource('weibo', '微博热搜', true, 15),
    makeSource('baidu', '百度热点', true, 30),
    makeSource('juejin', '掘金热门', false, 60),
    makeSource('github', 'GitHub Trending', true, 120),
    makeSource('v2ex', 'V2EX', false, 60),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct HotListSourceSettingsPage {
  @State items: SourceSetting[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadSources().then((list: SourceSetting[]): void => {
      this.items = list;
      this.loaded = true;
    });
  }

  private toggleSource(id: string, value: boolean): void {
    this.items = this.items.map((s: SourceSetting): SourceSetting => {
      if (s.id === id) {
        return makeSource(s.id, s.name, value, s.intervalMinutes);
      }
      return s;
    });
  }

  private cycleInterval(id: string): void {
    this.items = this.items.map((s: SourceSetting): SourceSetting => {
      if (s.id === id) {
        const idx: number = INTERVALS.indexOf(s.intervalMinutes);
        const newInterval: number = INTERVALS[(idx + 1) % INTERVALS.length];
        return makeSource(s.id, s.name, s.enabled, newInterval);
      }
      return s;
    });
  }

  private formatInterval(minutes: number): string {
    if (minutes < 60) {
      return `${minutes} 分钟`;
    }
    const hours: number = minutes / 60;
    return `${hours.toString()} 小时`;
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(18).fontColor(INK3).onClick((): void => { router.back(); })
        Text('热榜来源').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 启用的来源将出现在热榜页').fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, top: 20, bottom: 8 })

            Column() {
              ForEach(this.items, (s: SourceSetting): void => {
                Row() {
                  Column() {
                    Text(s.name).fontSize(BODY).fontColor(INK)
                    Text('刷新间隔').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)
                  Text(this.formatInterval(s.intervalMinutes))
                    .fontSize(LABEL).fontColor(ACCENT).margin({ right: 12 })
                    .onClick((): void => { this.cycleInterval(s.id); })
                  AmberSwitch({ checked: s.enabled, onChange: (v: boolean): void => { this.toggleSource(s.id, v); } })
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 14, bottom: 14 })
                .backgroundColor(SURFACE)
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              }, (s: SourceSetting): string => s.id)
            }
            .width('100%').backgroundColor(SURFACE).border({ width: 1, color: LINE })

            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 点击右侧时长可循环切换间隔').fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, top: 20, bottom: 8 })

            Column() {}.height(40)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

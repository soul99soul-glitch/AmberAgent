// HotListSourceSettingsPage — 热榜来源设置
// 数据源 = NewsNowProvider.NEWSNOW_PRESETS(与 BoardPage/SettingDeepReadPage 同一套);
// 开关持久化 `source_<id>`(BoardPage 消费的同一键)。原占位实现用的是假 id
// (github vs github-trending-today 等)且不落盘 — 开关对热榜页完全无效。
// 刷新间隔控件移除:当前无任何调度消费者,保留即假设置。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { AmberSwitch } from '../design/components.ets';
import { INK, INK3, SURFACE, LINE, BG, ACCENT, BODY, META, TITLE } from '../design/tokens.ets';
import { NEWSNOW_PRESETS } from '../platform_impl/NewsNowProvider.ets';
import type { NewsNowPreset } from '../platform_impl/NewsNowProvider.ets';
import { getAppContainer } from '../di/AppContainer.ets';
import type { Storage } from '@amber/deepread-domain';

interface SourceSetting {
  id: string;
  name: string;
  enabled: boolean;
}

function makeSource(id: string, name: string, enabled: boolean): SourceSetting {
  return { id: id, name: name, enabled: enabled };
}

@Entry
@Component
struct HotListSourceSettingsPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State items: SourceSetting[] = [];
  @State loaded: boolean = false;
  private storage: Storage | null = null;

  aboutToAppear(): void {
    const storage: Storage = getAppContainer().storage;
    this.storage = storage;
    const load = async (): Promise<void> => {
      const out: SourceSetting[] = [];
      for (let i = 0; i < NEWSNOW_PRESETS.length; i++) {
        const p: NewsNowPreset = NEWSNOW_PRESETS[i];
        const enabled: string = await storage.get<string>('source_' + p.id, 'true');
        out.push(makeSource(p.id, p.displayName, enabled === 'true'));
      }
      this.items = out;
      this.loaded = true;
    };
    load().catch((): void => { this.loaded = true; });
  }

  private toggleSource(id: string, value: boolean): void {
    if (this.storage === null) return;
    const previous: SourceSetting[] = this.items;
    this.items = this.items.map((s: SourceSetting): SourceSetting => {
      if (s.id === id) {
        return makeSource(s.id, s.name, value);
      }
      return s;
    });
    this.storage.set<string>('source_' + id, value ? 'true' : 'false')
      .catch((): void => {
        this.items = previous; // 写失败回滚开关
      });
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Icon({ name: 'back', iconSize: 20, color: INK3 }).onClick((): void => { router.back(); })
        Text('热榜来源').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 启用的来源将出现在热榜页').fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 18, right: 18, top: 14, bottom: 8 })

            Column() {
              ForEach(this.items, (s: SourceSetting): void => {
                Row() {
                  Column() {
                    Text(s.name).fontSize(BODY).fontColor(INK)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)

                  AmberSwitch({ checked: s.enabled, onChange: (v: boolean): void => {
                    this.toggleSource(s.id, v);
                  } })
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 14, bottom: 14 })
                .backgroundColor(SURFACE)
                .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
              }, (s: SourceSetting): string => s.id)
            }
            .width('100%').backgroundColor(SURFACE).border({ width: 1, color: LINE })

            Column() {}.height(40)
          }
          .width('100%')
        }
        .align(Alignment.Start)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

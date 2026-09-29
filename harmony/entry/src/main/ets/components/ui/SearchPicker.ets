// SearchPicker — 搜索服务选择器(ai/SearchPicker.kt:340 行)
// Android 基准: ToggleSurface 按钮 → ModalBottomSheet(内置搜索开关 / 服务列表)
// ArkUI MVP: 触发行(当前服务名)→ bindSheet(服务列表 toggle)
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';
import { Icon } from './Icon.ets';

export interface SearchServiceItem {
  id: string;
  name: string;
  enabled: boolean;
}

@Component
export struct SearchPicker {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop services: SearchServiceItem[] = [];
  @Prop searchEnabled: boolean = false;
  @State sheetOpen: boolean = false;
  onToggleSearch: () => void = (): void => {};
  onSelectService: (id: string) => void = (_id: string): void => {};
  onOpenSettings: () => void = (): void => {};

  build(): void {
    Row() {
      Icon({ name: 'search', iconSize: BODY, color: INK3 }).margin({ right: 8 })
      Text(this.searchEnabled ? '搜索已启用' : '搜索已关闭')
        .fontSize(BODY)
        .fontColor(this.searchEnabled ? ACCENT : INK3)
        .layoutWeight(1)
      Text('▾').fontSize(LABEL).fontColor(INK3)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(SURFACE)
    .borderRadius(12)
    .border({ width: 1, color: LINE })
    .onClick((): void => { this.sheetOpen = true; })
    .bindSheet(this.sheetOpen, this.PickerSheet(), {
      height: SheetSize.MEDIUM,
      onDisappear: (): void => { this.sheetOpen = false; },
    })
  }

  @Builder PickerSheet(): void {
    Column() {
      Text('搜索设置').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ left: 20, top: 20, bottom: 12 })

      // 主开关
      Row() {
        Text('启用搜索').fontSize(BODY).fontColor(INK2).layoutWeight(1)
        Toggle({ type: ToggleType.Switch, isOn: this.searchEnabled })
          .onChange((_v: boolean): void => { this.onToggleSearch(); })
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 12, bottom: 12 })
      .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

      // 服务列表
      Scroll() {
        Column() {
          ForEach(this.services, (svc: SearchServiceItem): void => {
            Row() {
              Text(svc.name).fontSize(BODY).fontColor(INK2).layoutWeight(1)
              Checkbox()
                .select(svc.enabled)
                .selectedColor(ACCENT)
                .onChange((_v: boolean): void => { this.onSelectService(svc.id); })
            }
            .width('100%')
            .padding({ left: 20, right: 20, top: 12, bottom: 12 })
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
          }, (svc: SearchServiceItem): string => svc.id)

          Text('全部搜索设置 ›').fontSize(BODY).fontColor(ACCENT)
            .width('100%')
            .padding({ left: 20, top: 14, bottom: 14 })
            .onClick((): void => { this.sheetOpen = false; this.onOpenSettings(); })
        }
        .width('100%')
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%')
    .backgroundColor(SURFACE)
  }
}

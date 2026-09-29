// McpPicker — MCP 服务器选择器(ai/McpPicker.kt:251 行)
// Android 基准: ToggleSurface + 状态指示 + BadgedBox → ModalBottomSheet(server 列表 toggle)
// ArkUI MVP: 触发行(server 数量)→ bindSheet(server 状态 + toggle)
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';
import { Icon } from './Icon.ets';

export interface McpServerItem {
  id: string;
  name: string;
  status: string; // 'Idle' | 'Connecting' | 'Connected' | 'Error'
  toolCount: number;
  enabled: boolean;
}

@Component
export struct McpPicker {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop servers: McpServerItem[] = [];
  @State sheetOpen: boolean = false;
  onToggleServer: (id: string) => void = (_id: string): void => {};

  private enabledCount(): number {
    return this.servers.filter((s: McpServerItem): boolean => s.enabled).length;
  }

  build(): void {
    Row() {
      Icon({ name: 'plug', iconSize: BODY, color: INK2 }).margin({ right: 8 })
      Text(`MCP (${this.enabledCount()}/${this.servers.length})`)
        .fontSize(BODY).fontColor(INK2).layoutWeight(1)
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
      Text('MCP 服务器').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        .width('100%').padding({ left: 20, top: 20, bottom: 12 })

      if (this.servers.length === 0) {
        Column() {
          Text('未配置 MCP 服务器').fontSize(BODY).fontColor(INK3)
        }
        .width('100%').height('100%')
        .justifyContent(FlexAlign.Center)
      } else {
        Scroll() {
          Column() {
            ForEach(this.servers, (svc: McpServerItem): void => {
              Row() {
                Column() {
                  Text(svc.name).fontSize(BODY).fontColor(INK2)
                  Text(`${svc.status} · ${svc.toolCount} 工具`)
                    .fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1)
                Toggle({ type: ToggleType.Switch, isOn: svc.enabled })
                  .onChange((_v: boolean): void => { this.onToggleServer(svc.id); })
              }
              .width('100%')
              .padding({ left: 20, right: 20, top: 12, bottom: 12 })
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            }, (svc: McpServerItem): string => svc.id)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%')
    .backgroundColor(SURFACE)
  }
}

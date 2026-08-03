// SettingSystemAccessPage — 系统访问(权限)设置页
// 展示权限状态列表:通讯录 / 日历 / 通知 / 文件
// 每项显示 已授权 / 未授权 / 不支持 状态徽标
// NOTE:HarmonyOS 权限模型与 Android 不同;本页仅展示状态,不发起实际授权请求
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

interface PermissionItem {
  key: string;
  name: string;
  desc: string;
  status: string; // 'granted' | 'denied' | 'unsupported'
}

function makePerm(key: string, name: string, desc: string, status: string): PermissionItem {
  return { key: key, name: name, desc: desc, status: status };
}

function placeholderLoadPermissions(): Promise<PermissionItem[]> {
  const seed: PermissionItem[] = [
    makePerm('contacts', '通讯录', '读取联系人以 @ 提及', 'granted'),
    makePerm('calendar', '日历', '读取日程用于提醒', 'denied'),
    makePerm('notifications', '通知', '消息生成完成时推送通知', 'granted'),
    makePerm('files', '文件存储', '访问文档与附件', 'unsupported'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingSystemAccessPage {
  @State items: PermissionItem[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadPermissions().then((list: PermissionItem[]): void => {
      this.items = list;
      this.loaded = true;
    });
  }

  private badgeText(status: string): string {
    if (status === 'granted') return '已授权';
    if (status === 'denied') return '未授权';
    return '不支持';
  }

  private badgeColor(status: string): string {
    if (status === 'granted') return '#5E9C6E'; // SIGNAL green(tokens.ets)
    if (status === 'denied') return '#C93A2F'; // ERROR red(tokens.ets)
    return INK3;
  }

  private done(): void {
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 完成
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('系统访问').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('权限状态')

            // 说明条
            Row() {
              Text('HarmonyOS 权限需在系统设置中授予,此处仅展示当前状态。')
                .fontSize(LABEL).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, right: 16, bottom: 8 })

            ForEach(this.items, (p: PermissionItem): void => {
              Row() {
                Column() {
                  Text(p.name).fontSize(BODY).fontColor(INK)
                  Text(p.desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1)

                // 状态徽标
                Row() {
                  Text('●').fontSize(8).fontColor(this.badgeColor(p.status)).margin({ right: 4 })
                  Text(this.badgeText(p.status))
                    .fontSize(META).fontColor(this.badgeColor(p.status))
                }
                .padding({ left: 10, right: 10, top: 4, bottom: 4 })
                .backgroundColor(SURFACE2).borderRadius(50)
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
            }, (p: PermissionItem): string => p.key)

            Column() {}.height(32)
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

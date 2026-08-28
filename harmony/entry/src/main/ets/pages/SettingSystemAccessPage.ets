// SettingSystemAccessPage — 系统访问(权限)设置页
// 展示权限状态列表:通讯录 / 日历 / 通知 / 文件
// 每项显示 已授权 / 未授权 / 不支持 状态徽标
// NOTE:HarmonyOS 权限模型与 Android 不同;本页仅展示状态,不发起实际授权请求
import { router } from '@kit.ArkUI';
import { FONT_MONO_MEDIUM, FONT_SANS, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import {
  INK, INK3, INK4, SURFACE2, BG, SIGNAL, ERROR,
  BODY, META, LABEL, TEXT_STYLES,
} from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, IconSeat, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

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
    if (status === 'granted') return SIGNAL;
    if (status === 'denied') return ERROR;
    return INK3;
  }

  private done(): void {
    router.back();
  }

  @Builder permissionRow(p: PermissionItem, isLast: boolean): void {
    Column() {
      Row() {
        IconSeat({ iconName: p.key === 'notifications' ? 'bell' : p.key === 'calendar' ? 'clock' : p.key === 'files' ? 'folder' : 'person' })
          .margin({ right: 12 })
        Column() {
          Text(p.name)
            .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
          Text(p.desc)
            .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3).margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        Row() {
          Text('●').fontSize(8).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.tinyTag.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(this.badgeColor(p.status)).margin({ right: 4 })
          Text(this.badgeText(p.status))
            .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontFamily(FONT_MONO_MEDIUM).fontColor(this.badgeColor(p.status))
        }
        .padding({ left: 10, right: 10, top: 4, bottom: 4 })
        .backgroundColor(SURFACE2).borderRadius(50)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '系统访问', actionText: '完成', onAction: (): void => { this.done(); } })

      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK3)
          .padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            SectionHeader({ label: '权限状态' })

            // 说明条
            Text('HarmonyOS 权限需在系统设置中授予,此处仅展示当前状态。')
              .fontSize(LABEL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_SANS).fontColor(INK3)
              .width('100%').padding({ left: 16, right: 16, bottom: 8 })

            ForEach(this.items, (p: PermissionItem, idx: number): void => {
              this.permissionRow(p, idx === this.items.length - 1);
            }, (p: PermissionItem): string => p.key)

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

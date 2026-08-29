// SettingSystemAccessPage — 系统访问(权限)设置页
// 通讯录 / 日历 / 通知 / 文件 四项:
//   - 通讯录/日历:checkAccessToken 查真状态,未授权点击发起 requestPermissionsFromUser
//   - 通知:isNotificationEnabled 查真状态,未授权点击发起 requestEnableNotification
//   - 文件:鸿蒙文档访问走系统 picker,免权限 → 显示「无需授权」
import { router } from '@kit.ArkUI';
import { abilityAccessCtrl, bundleManager, common, Permissions } from '@kit.AbilityKit';
import { notificationManager } from '@kit.NotificationKit';
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
  status: string; // 'granted' | 'denied' | 'exempt'
}

function makePerm(key: string, name: string, desc: string, status: string): PermissionItem {
  return { key: key, name: name, desc: desc, status: status };
}

const atManager = abilityAccessCtrl.createAtManager();

// 应用级 accessTokenId(权限查询入参)
const appAccessTokenId = (): number => {
  try {
    const info = bundleManager.getBundleInfoForSelfSync(
      bundleManager.BundleFlag.GET_BUNDLE_INFO_WITH_APPLICATION);
    return info.appInfo.accessTokenId;
  } catch (_e) {
    return 0;
  }
};

const checkPermGranted = async (perm: Permissions): Promise<boolean> => {
  const tokenId: number = appAccessTokenId();
  if (tokenId === 0) return false;
  try {
    const grant: abilityAccessCtrl.GrantStatus =
      await atManager.checkAccessToken(tokenId, perm);
    return grant === abilityAccessCtrl.GrantStatus.PERMISSION_GRANTED;
  } catch (_e) {
    return false;
  }
};

@Entry
@Component
struct SettingSystemAccessPage {
  @State items: PermissionItem[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.loadPermissions();
  }

  private async loadPermissions(): Promise<void> {
    const calendar: boolean = await checkPermGranted('ohos.permission.READ_CALENDAR');
    let notifEnabled: boolean = false;
    try {
      notifEnabled = await notificationManager.isNotificationEnabled();
    } catch (_e) {
      notifEnabled = false;
    }
    this.items = [
      makePerm('contacts', '通讯录', '调试 Profile 需加入 ACL 权限(AGC 后台)后解禁', 'acl'),
      makePerm('calendar', '日历', '读取日程用于提醒', calendar ? 'granted' : 'denied'),
      makePerm('notifications', '通知', '消息生成完成时推送通知', notifEnabled ? 'granted' : 'denied'),
      makePerm('files', '文件与附件', '通过系统文件选择器按需选取', 'exempt'),
    ];
    this.loaded = true;
  }

  private badgeText(status: string): string {
    if (status === 'granted') return '已授权';
    if (status === 'denied') return '未授权';
    if (status === 'acl') return '暂不可用';
    return '无需授权';
  }

  private badgeColor(status: string): string {
    if (status === 'granted') return SIGNAL;
    if (status === 'denied') return ERROR;
    return INK3;
  }

  // 未授权项点击 → 发起对应申请;申请回调后重查真状态
  private requestPermission(p: PermissionItem): void {
    if (p.status !== 'denied') return;
    const ctx: common.UIAbilityContext =
      this.getUIContext().getHostContext() as common.UIAbilityContext;
    if (p.key === 'notifications') {
      notificationManager.requestEnableNotification(ctx)
        .then((): void => { this.loadPermissions(); })
        .catch((): void => { this.loadPermissions(); });
      return;
    }
    const perms: Array<Permissions> = ['ohos.permission.READ_CALENDAR'];
    atManager.requestPermissionsFromUser(ctx, perms)
      .then((): void => { this.loadPermissions(); })
      .catch((): void => { this.loadPermissions(); });
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
            .fontColor(p.status === 'denied' ? INK3 : INK4).margin({ top: 4 })
          if (p.status === 'denied') {
            Text('点击申请权限')
              .fontSize(TEXT_STYLES.tinyTag.size).fontWeight(TEXT_STYLES.tinyTag.weight)
              .lineHeight(TEXT_STYLES.tinyTag.lineHeight).fontFamily(FONT_MONO_MEDIUM)
              .fontColor(INK3).margin({ top: 3 })
          }
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
      .onClick((): void => { this.requestPermission(p); })
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
            Text('点击未授权项可发起申请;文件访问由系统选择器提供,无需授权。')
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

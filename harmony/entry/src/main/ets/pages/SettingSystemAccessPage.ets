// SettingSystemAccessPage — 系统访问(权限)设置页
// 日历 / 位置 / 通知 / 剪贴板 / 通讯录 / 照片 / 文件 / 相机:
//   - 日历(READ+WRITE 同组)/位置(APPROXIMATELY_LOCATION):checkAccessToken 查真状态,
//     未授权点击发起 requestPermissionsFromUser
//   - 通知:isNotificationEnabled 查真状态,未授权点击发起 requestEnableNotification
//   - 通讯录/照片/文件/相机:走系统选择器或系统应用,免权限 → 显示「无需授权」
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
//   CenteredTitleBar / PlainSection / CardGroup r18+发丝描边 / 权限行
//   (accent 图标座 + StatusChip 软底状态药丸)+ FooterNote;逻辑零改动。
import { abilityAccessCtrl, bundleManager, common, Permissions } from '@kit.AbilityKit';
import { notificationManager } from '@kit.NotificationKit';
import { ACCENT, BG, INK, INK3, LINE, accentSoftBg, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, StatusChip, FooterNote, PageSpacer,
} from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

interface PermissionItem {
  key: string;
  name: string;
  desc: string;
  status: string; // 'granted' | 'denied' | 'acl' | 'exempt'
}

function makePerm(key: string, name: string, desc: string, status: string): PermissionItem {
  return { key: key, name: name, desc: desc, status: status };
}

const atManager = abilityAccessCtrl.createAtManager();

// 日历/提醒工具(DeviceSchedulePort)读写同组申请;天气工具仅需大致位置
const CALENDAR_PERMS: Array<Permissions> = ['ohos.permission.READ_CALENDAR', 'ohos.permission.WRITE_CALENDAR'];
const LOCATION_PERMS: Array<Permissions> = ['ohos.permission.APPROXIMATELY_LOCATION'];

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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State items: PermissionItem[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.loadPermissions();
  }

  private async loadPermissions(): Promise<void> {
    // 日历工具(日程/提醒)读写同组申请:两项都授予才算已授权
    const calendarRead: boolean = await checkPermGranted(CALENDAR_PERMS[0]);
    const calendarWrite: boolean = await checkPermGranted(CALENDAR_PERMS[1]);
    const calendar: boolean = calendarRead && calendarWrite;
    const location: boolean = await checkPermGranted(LOCATION_PERMS[0]);
    let notifEnabled: boolean = false;
    try {
      notifEnabled = await notificationManager.isNotificationEnabled();
    } catch (_e) {
      notifEnabled = false;
    }
    this.items = [
      makePerm('calendar', '日历与提醒', '允许 Agent 查看和管理日程、提醒事项', calendar ? 'granted' : 'denied'),
      makePerm('location', '位置', '用于天气查询的大致位置', location ? 'granted' : 'denied'),
      makePerm('notifications', '通知', '消息生成完成时推送通知', notifEnabled ? 'granted' : 'denied'),
      makePerm('pasteboard', '剪贴板', '读取受系统管控，当前版本暂不可用；写入剪贴板不受影响', 'acl'),
      makePerm('contacts', '通讯录', '通过系统选择器选择，无需授权', 'exempt'),
      makePerm('photos', '照片', '通过系统选择器选择，无需授权', 'exempt'),
      makePerm('files', '文件与附件', '通过系统文件选择器按需选取', 'exempt'),
      makePerm('camera', '相机', '拍照走系统相机应用，无需授权', 'exempt'),
    ];
    this.loaded = true;
  }

  private badgeText(status: string): string {
    if (status === 'granted') return '已授权';
    if (status === 'denied') return '未授权';
    if (status === 'acl') return '暂不可用';
    return '无需授权';
  }

  // StatusChip 五档软底药丸:granted 绿 / denied 红 / 其余灰
  private chipTone(status: string): string {
    if (status === 'granted') return 'success';
    if (status === 'denied') return 'error';
    return 'muted';
  }

  private iconFor(key: string): string {
    if (key === 'notifications') return 'bell';
    if (key === 'calendar') return 'clock';
    if (key === 'files') return 'folder';
    if (key === 'pasteboard') return 'doc';
    if (key === 'camera') return 'hi_camera';
    if (key === 'location') return 'globe';
    if (key === 'photos') return 'image';
    return 'person';
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
    let perms: Array<Permissions> = [];
    if (p.key === 'calendar') {
      perms = CALENDAR_PERMS;
    } else if (p.key === 'location') {
      perms = LOCATION_PERMS;
    } else {
      return;
    }
    atManager.requestPermissionsFromUser(ctx, perms)
      .then((): void => { this.loadPermissions(); })
      .catch((): void => { this.loadPermissions(); });
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  @Builder
  permCard(): void {
    Column() {
      ForEach(this.items, (p: PermissionItem, idx: number): void => {
        this.permRow(p, idx === this.items.length - 1);
      }, (p: PermissionItem): string => p.key)
    }
    .width('100%')
  }

  // 权限行:28 accent 图标座 + 名称/描述/申请提示 + StatusChip 状态药丸
  @Builder
  permRow(p: PermissionItem, isLast: boolean): void {
    Column() {
      Row() {
        Row() {
          Icon({ name: this.iconFor(p.key), iconSize: 15, color: this.accentColorStore })
        }
        .width(28).height(28)
        .borderRadius(7)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        Column() {
          Text(p.name)
            .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(p.desc)
            .fontSize(12).lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).margin({ top: 2 })
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
          if (p.status === 'denied') {
            Text('点击申请权限')
              .fontSize(12).lineHeight(lineHeightForSize(12))
              .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).margin({ top: 3 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })
        StatusChip({ text: this.badgeText(p.status), tone: this.chipTone(p.status) })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
      .onClick((): void => { this.requestPermission(p); })
      if (!isLast) {
        // 分隔线内缩对齐文字起点(行 padding 14 + 图标座 28 + 间距 12 = 54)
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 54, right: 14 })
      }
    }
    .width('100%')
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '系统访问' })

      if (!this.loaded) {
        Text('加载中…')
          .fontSize(13).lineHeight(lineHeightForSize(13))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3)
          .margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            PlainSection({ label: '权限状态' })
            Column() {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.permCard(); } })
            }
            .width('100%').padding({ left: 16, right: 16 })

            FooterNote({ text: '点击未授权项可发起申请；通讯录、照片、文件经系统选择器提供。' })
            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

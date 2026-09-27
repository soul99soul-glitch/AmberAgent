// SettingPage — 设置入口(2026-09-27 六组卡片重排)
//
// 基准: iOS SettingsHomeView + Android 新版设置(六组卡片 IA):
//   通用设置 / AGENT 设置 / 模型与服务 / 高级功能 / 数据设置 / AMBER。
//   奶油画布 + 圆角卡片分组(CardGroup/CardRow)+ 顶栏右侧账户头像(→ProfilePage)。
//   原实验功能页收编进「高级功能」组;历史/收藏/看板移出(首页与会话列表已可达)。
//   鸿蒙现实取舍见 docs/plans/2026-09-27-harmony-settings-realign.md。

import { router } from '@kit.ArkUI';
import { bundleManager } from '@kit.AbilityKit';
import type { DisplaySetting, ManagedFileEntity } from '@amber/chat-domain';
import { loadDisplaySetting } from '@amber/chat-domain';
import {
  BG, ACCENT, INK, INK2, INK3, LINE, SURFACE2, LABEL, TEXT_STYLES, GUTTER,
} from '../design/tokens.ets';
import { SectionHeader, CardGroup, CardRow, PageSpacer } from '../components/PageTemplate.ets';
import { FONT_MONO, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { describeChatModel, getChatKvStore } from '../di/AppContainer.ets';
import { getFilesRepository, MANAGED_FILE_FOLDERS } from '../platform_impl/ManagedFileStore.ets';
import { Icon } from '../components/ui/Icon.ets';

// 主题选择持久化键(外观页写入;根页只读回显 value)
const UI_COLOR_MODE_KEY: string = 'ui_color_mode';

@Entry
@Component
struct SettingPage {
  // theme swap triggers full-page rebuild (build re-reads mutable tokens)
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('systemDarkMode') systemDarkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State appearanceValue: string = '跟随系统';
  // 真实值装配:默认模型标签 + 聊天记录存储统计 + 版本号(不写死占位)
  @State modelLabel: string = '';
  @State storageLabel: string = '';
  @State versionLabel: string = '';
  @State userNickname: string = '';
  @State userAvatar: string = '';

  aboutToAppear(): void {
    this.reloadValues();
    try {
      const info = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      this.versionLabel = `${info.versionName} (${info.versionCode})`;
    } catch (e) {
      this.versionLabel = '';
    }
  }

  // 从子页返回时回栈不重跑 aboutToAppear — onPageShow 重读回显值,
  // 并借 darkMode 读取触发根页按新主题令牌重建
  onPageShow(): void {
    this.reloadValues();
  }

  private reloadValues(): void {
    this.refreshAppearanceValue();
    describeChatModel().then((label: string): void => {
      this.modelLabel = label;
    }).catch((): void => {});
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      this.userNickname = setting.userNickname;
      this.userAvatar = setting.userAvatar;
    }).catch((): void => {});
    this.refreshStorageLabel();
  }

  private refreshAppearanceValue(): void {
    getChatKvStore().get(UI_COLOR_MODE_KEY).then((raw: string | null): void => {
      if (raw === 'light') this.appearanceValue = '浅色';
      else if (raw === 'dark') this.appearanceValue = '深色';
      else this.appearanceValue = '跟随系统';
    }).catch((): void => {});
  }

  // 聊天记录存储统计:四个托管文件夹的文件数 + 总字节(对齐 Android countChatFiles 的「N · X.XX MB」)
  private refreshStorageLabel(): void {
    const repo = getFilesRepository();
    const folders: string[] = [...MANAGED_FILE_FOLDERS];
    let count: number = 0;
    let bytes: number = 0;
    let failed: boolean = false;
    const step = (i: number): void => {
      if (i >= folders.length) {
        if (!failed) {
          this.storageLabel = `${count} · ${(bytes / 1024 / 1024).toFixed(2)} MB`;
        }
        return;
      }
      repo.listByFolder(folders[i]).then((list: ManagedFileEntity[]): void => {
        count += list.length;
        list.forEach((f: ManagedFileEntity): void => { bytes += f.sizeBytes; });
        step(i + 1);
      }).catch((): void => {
        failed = true;
        step(i + 1);
      });
    };
    step(0);
  }

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  // ── 六组卡片内容(@Builder:组件构造必须发生在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  @Builder
  GeneralCard(): void {
    CardRow({ label: '外观', valueText: this.appearanceValue, iconName: 'ph_sun',
      onRowClick: (): void => { this.go('pages/SettingAppearancePage'); } })
    CardRow({ label: '显示与字体', iconName: 'gear', isLast: true,
      onRowClick: (): void => { this.go('pages/SettingDisplayPage'); } })
  }

  @Builder
  AgentCard(): void {
    CardRow({ label: '灵魂与记忆', iconName: 'brain',
      onRowClick: (): void => { this.go('pages/SettingAgentMemoryPageFull'); } })
    CardRow({ label: '运行环境', iconName: 'ph_code',
      onRowClick: (): void => { this.go('pages/SettingSandboxPage'); } })
    CardRow({ label: '运行与显示', iconName: 'eye',
      onRowClick: (): void => { this.go('pages/ChatAgentExecutionPage'); } })
    CardRow({ label: '技能', iconName: 'puzzle',
      onRowClick: (): void => { this.go('pages/SkillsPage'); } })
    CardRow({ label: '权限与批准', iconName: 'shield',
      onRowClick: (): void => { this.go('pages/ChatAgentPermissionsPage'); } })
    CardRow({ label: '系统权限', iconName: 'ph_lock', isLast: true,
      onRowClick: (): void => { this.go('pages/SettingSystemAccessPage'); } })
  }

  @Builder
  ModelCard(): void {
    CardRow({ label: '服务商', iconName: 'key',
      onRowClick: (): void => { this.go('pages/ChatProviderSettingsPage'); } })
    CardRow({ label: '模型与提示词', valueText: this.modelLabel, iconName: 'spark',
      onRowClick: (): void => { this.go('pages/ChatModelPickerPage'); } })
    CardRow({ label: '任务模型', iconName: 'cpu',
      onRowClick: (): void => { this.go('pages/ChatTaskModelsPage'); } })
    CardRow({ label: '提示词', iconName: 'doc',
      onRowClick: (): void => { this.go('pages/ChatPromptsPage'); } })
    CardRow({ label: '搜索服务', iconName: 'search', isLast: true,
      onRowClick: (): void => { this.go('pages/ChatSearchSettingsPage'); } })
  }

  @Builder
  AdvancedCard(): void {
    CardRow({ label: 'WebMount', iconName: 'globe',
      onRowClick: (): void => { this.go('pages/SettingExperimentalWebMountPage'); } })
    CardRow({ label: '子代理', iconName: 'ph_users',
      onRowClick: (): void => { this.go('pages/SettingSubAgentPage'); } })
    CardRow({ label: '模型议会', iconName: 'ph_quotes',
      onRowClick: (): void => { this.go('pages/SettingModelCouncilPage'); } })
    CardRow({ label: '小应用', iconName: 'box',
      onRowClick: (): void => { this.go('pages/MiniAppSettingsPage'); } })
    CardRow({ label: '小说创作', iconName: 'ph_pencil',
      onRowClick: (): void => { this.go('pages/NovelProjectsPage'); } })
    CardRow({ label: '深度阅读', iconName: 'book',
      onRowClick: (): void => { this.go('pages/SettingDeepReadPage'); } })
    CardRow({ label: 'Mac Gateway', iconName: 'link', isLast: true,
      onRowClick: (): void => { this.go('pages/SettingMacGatewayPage'); } })
  }

  @Builder
  DataCard(): void {
    CardRow({ label: '同步与备份', iconName: 'hi_app_archive',
      onRowClick: (): void => { this.go('pages/SettingBackupPage'); } })
    CardRow({ label: '聊天记录存储', valueText: this.storageLabel, iconName: 'hi_files',
      onRowClick: (): void => { this.go('pages/SettingFilesPage'); } })
    // 历史/收藏在别处无入口(基准截图虽无此两行,鸿蒙不能静默丢功能)
    CardRow({ label: '历史记录', iconName: 'history',
      onRowClick: (): void => { this.go('pages/HistoryPage'); } })
    CardRow({ label: '收藏', iconName: 'star', isLast: true,
      onRowClick: (): void => { this.go('pages/FavoritePage'); } })
  }

  @Builder
  AmberCard(): void {
    CardRow({ label: '关于', iconName: 'info', isLast: true,
      onRowClick: (): void => { this.go('pages/SettingAboutPage'); } })
  }

  build(): void {
    Column() {
      // ── 顶栏:返回 + 标题 + 账户头像(iOS SettingsHome header:头像 38,热区 44)──
      Row() {
        Row() {
          Icon({ name: 'back', iconSize: 24, color: INK })
        }
        .width(48).height(48)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .accessibilityText('返回')
        .onClick((): void => { router.back(); })
        Text('设置')
          .fontSize(TEXT_STYLES.screenTitle.size)
          .fontWeight(TEXT_STYLES.screenTitle.weight)
          .lineHeight(TEXT_STYLES.screenTitle.lineHeight)
          .fontFamily(TEXT_STYLES.screenTitle.family)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .margin({ left: 8 })
          .layoutWeight(1)
        Row() {
          if (this.userAvatar.length > 0) {
            Image(this.userAvatar.startsWith('http') || this.userAvatar.startsWith('data:')
              || this.userAvatar.startsWith('file://') || this.userAvatar.startsWith('resource:')
              ? this.userAvatar : `file://${this.userAvatar}`)
              .width(38)
              .height(38)
              .borderRadius(19)
              .objectFit(ImageFit.Cover)
          } else {
            Stack() {
              Text((this.userNickname.length > 0 ? this.userNickname : 'A').charAt(0).toUpperCase())
                .fontSize(14.5)
                .fontWeight(FontWeight.Medium)
                .fontFamily(FONT_SANS_SEMIBOLD)
                .fontColor(INK2)
            }
            .width(38)
            .height(38)
            .borderRadius(19)
            .backgroundColor(SURFACE2)
          }
        }
        .width(44).height(44)
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .accessibilityText('我的账户')
        .onClick((): void => { router.pushUrl({ url: 'pages/ProfilePage' }); })
      }
      .width('100%')
      .constraintSize({ minHeight: 64 })
      .padding({ left: 4, right: GUTTER, top: this.statusBarHeight, bottom: 8 })
      .border({ width: { bottom: 1 }, color: { bottom: LINE } })
      .backgroundColor(BG)

      Scroll() {
        Column() {
          // ── 通用设置 ──
          SectionHeader({ label: '通用设置' })
          CardGroup({ content: (): void => { this.GeneralCard(); } })
          .margin({ left: 16, right: 16 })

          // ── AGENT 设置 ──
          SectionHeader({ label: 'AGENT 设置' })
          CardGroup({ content: (): void => { this.AgentCard(); } })
          .margin({ left: 16, right: 16 })

          // ── 模型与服务 ──
          SectionHeader({ label: '模型与服务' })
          CardGroup({ content: (): void => { this.ModelCard(); } })
          .margin({ left: 16, right: 16 })

          // ── 高级功能 ──
          SectionHeader({ label: '高级功能' })
          CardGroup({ content: (): void => { this.AdvancedCard(); } })
          .margin({ left: 16, right: 16 })

          // ── 数据设置 ──
          SectionHeader({ label: '数据设置' })
          CardGroup({ content: (): void => { this.DataCard(); } })
          .margin({ left: 16, right: 16 })

          // ── AMBER ──
          SectionHeader({ label: 'AMBER' })
          CardGroup({ content: (): void => { this.AmberCard(); } })
          .margin({ left: 16, right: 16 })

          // 版本行:$ 提示符 accent 色
          Row() {
            Text('$')
              .fontSize(LABEL).fontWeight(FontWeight.Medium)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO)
              .fontColor(this.accentColorStore)
            Text(` amber v${this.versionLabel}`)
              .fontSize(LABEL).fontWeight(FontWeight.Medium)
              .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_MONO)
              .fontColor(INK3)
          }
          .width('100%').justifyContent(FlexAlign.Center).margin({ top: 22 })

          PageSpacer()
        }
        .width('100%')
      }
        .align(Alignment.TopStart)
      .layoutWeight(1).backgroundColor(BG)
    }
    .width('100%').height('100%').backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}

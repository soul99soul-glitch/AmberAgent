// MiniAppSettingsPage — 小应用设置页(三分组 + 17 开关)
//
// 分组:Common(常用能力)/ HostAi(宿主与 AI)/ Advanced(高级与调试);
// 顶部「启用小应用」总开关;Android MiniAppSettingsPage.kt 语义逐字。
// 持久化:MiniAppSetting KV('mini_app_setting')读-改-写;enabled=false 时
// 其余开关禁用(Android enabled = miniApp.enabled 语义)。
//
// 2026-09-27 二级页 iOS 对齐(无截图,沿用规范):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/CardToggleRow),
// 配置读写与方法逻辑零改动。

import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import type { MiniAppSetting } from '@amber/chat-domain';
import { makeMiniAppSetting } from '@amber/chat-domain';
import { ACCENT, BG, GUTTER } from '../design/tokens.ets';
import { CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, PageSpacer } from '../components/PageTemplate.ets';
import { getMiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';
import type { MiniAppPlatform } from '../platform_impl/MiniAppPlatform.ets';

type MiniAppGroupKind = 'common' | 'host_ai' | 'advanced';

const groupTitle = (kind: MiniAppGroupKind): string => {
  switch (kind) {
    case 'common': return '常用能力';
    case 'host_ai': return '宿主与 AI';
    case 'advanced': return '高级与调试';
  }
};

const groupDescription = (kind: MiniAppGroupKind): string => {
  switch (kind) {
    case 'common': return '小应用最常用的网络、图片、搜索和剪贴板写入能力。';
    case 'host_ai': return '涉及模型调用、会话上下文和写回宿主的高权限能力。';
    case 'advanced': return '更偏开发者和实验能力，默认建议谨慎开启。';
  }
};

interface SwitchRowData {
  title: string;
  description: string;
  key: string;
}

const COMMON_ROWS: SwitchRowData[] = [
  { title: '网络请求', description: '允许声明 network 权限的小应用通过 Amber.fetch 访问 HTTPS', key: 'networkEnabled' },
  { title: '外链图片', description: '允许声明 externalImages 权限的小应用通过 Native 代理加载 HTTPS 图片', key: 'externalImagesEnabled' },
  { title: '搜索', description: '允许声明 search 权限的小应用调用 Amber.search 获取结构化结果', key: 'searchEnabled' },
  { title: '复制到剪贴板', description: '允许声明 clipboard.copy 权限的小应用写入剪贴板', key: 'clipboardCopyEnabled' },
];

const HOST_AI_ROWS: SwitchRowData[] = [
  { title: 'Amber.ai', description: '允许声明 ai.generate 权限的小应用在确认后调用当前聊天模型', key: 'aiEnabled' },
  { title: '读取最小上下文', description: '允许声明 host.context 权限的小应用在确认后读取最小化会话上下文', key: 'hostContextEnabled' },
  { title: '写回宿主', description: '允许声明 host.sendToConversation / host.createArtifact 的小应用在确认后写回', key: 'hostWriteEnabled' },
  { title: '更新看板摘要', description: '允许声明 host.updateBoardSummary 权限的小应用更新自己的摘要字段', key: 'boardSummaryUpdateEnabled' },
];

const ADVANCED_ROWS: SwitchRowData[] = [
  { title: '共享存储', description: '允许声明 sharedStore 权限的小应用访问隔离 namespace KV', key: 'sharedStoreEnabled' },
  { title: '事件总线', description: '允许声明 eventBus 权限的小应用在同 namespace 内收发临时事件', key: 'eventBusEnabled' },
  { title: '小应用跳转', description: '允许声明 launch 权限的小应用在确认后打开已保存的小应用', key: 'launchEnabled' },
  { title: '传感器', description: '允许声明 sensor 权限的小应用在确认后订阅加速度/陀螺仪/光照', key: 'sensorEnabled' },
  { title: '定位', description: '允许声明 location 权限的小应用在确认后读取位置', key: 'locationEnabled' },
  { title: '读取剪贴板', description: '允许声明 clipboard.read 权限的小应用在确认后读取剪贴板文本', key: 'clipboardReadEnabled' },
  { title: '显示源码入口', description: '在 Runner 菜单里显示只读源码查看', key: 'showSourceButton' },
  { title: 'WebView 调试', description: '仅调试时开启,允许通过系统 WebView 调试工具检查小应用', key: 'webViewDebugEnabled' },
];

@Entry
@Component
struct MiniAppSettingsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State setting: MiniAppSetting = makeMiniAppSetting();
  @State loaded: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  private platform: MiniAppPlatform | null = null;
  private group: MiniAppGroupKind | null = null;

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    const groupRaw: string = params ? (params['group'] ?? '') : '';
    if (groupRaw === 'common' || groupRaw === 'host_ai' || groupRaw === 'advanced') {
      this.group = groupRaw as MiniAppGroupKind;
    } else {
      this.group = null;
    }
    getMiniAppPlatform().then((platform: MiniAppPlatform): void => {
      this.platform = platform;
      this.setting = platform.settingSnapshot();
      this.loaded = true;
    }).catch((): void => {
      this.loaded = true;
    });
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 1600 });
  }

  private toggle(key: string): void {
    const platform: MiniAppPlatform | null = this.platform;
    if (platform === null) return;
    const current: MiniAppSetting = this.setting;
    const next: MiniAppSetting = this.withKey(current, key, !this.valueForKey(current, key));
    this.setting = next;
    platform.saveSetting(next).catch((e: Error): void => {
      this.toast(`保存失败:${e.message}`);
      // 失败回滚到最新持久化态,而非回调开始时的旧快照(期间可能有其它开关已生效)
      platform.refreshSetting().then((fresh: MiniAppSetting): void => {
        this.setting = fresh;
      }).catch((): void => {
        this.setting = current;
      });
    });
  }

  private valueForKey(setting: MiniAppSetting, key: string): boolean {
    switch (key) {
      case 'enabled': return setting.enabled;
      case 'networkEnabled': return setting.networkEnabled;
      case 'externalImagesEnabled': return setting.externalImagesEnabled;
      case 'searchEnabled': return setting.searchEnabled;
      case 'clipboardCopyEnabled': return setting.clipboardCopyEnabled;
      case 'boardSummaryUpdateEnabled': return setting.boardSummaryUpdateEnabled;
      case 'hostContextEnabled': return setting.hostContextEnabled;
      case 'hostWriteEnabled': return setting.hostWriteEnabled;
      case 'aiEnabled': return setting.aiEnabled;
      case 'sharedStoreEnabled': return setting.sharedStoreEnabled;
      case 'eventBusEnabled': return setting.eventBusEnabled;
      case 'launchEnabled': return setting.launchEnabled;
      case 'sensorEnabled': return setting.sensorEnabled;
      case 'locationEnabled': return setting.locationEnabled;
      case 'clipboardReadEnabled': return setting.clipboardReadEnabled;
      case 'webViewDebugEnabled': return setting.webViewDebugEnabled;
      case 'showSourceButton': return setting.showSourceButton;
      default: return false;
    }
  }

  private withKey(setting: MiniAppSetting, key: string, value: boolean): MiniAppSetting {
    const next: MiniAppSetting = {
      enabled: setting.enabled,
      networkEnabled: setting.networkEnabled,
      externalImagesEnabled: setting.externalImagesEnabled,
      searchEnabled: setting.searchEnabled,
      clipboardCopyEnabled: setting.clipboardCopyEnabled,
      boardSummaryUpdateEnabled: setting.boardSummaryUpdateEnabled,
      hostContextEnabled: setting.hostContextEnabled,
      hostWriteEnabled: setting.hostWriteEnabled,
      aiEnabled: setting.aiEnabled,
      sharedStoreEnabled: setting.sharedStoreEnabled,
      eventBusEnabled: setting.eventBusEnabled,
      launchEnabled: setting.launchEnabled,
      sensorEnabled: setting.sensorEnabled,
      locationEnabled: setting.locationEnabled,
      clipboardReadEnabled: setting.clipboardReadEnabled,
      webViewDebugEnabled: setting.webViewDebugEnabled,
      showSourceButton: setting.showSourceButton,
    };
    switch (key) {
      case 'enabled': next.enabled = value; break;
      case 'networkEnabled': next.networkEnabled = value; break;
      case 'externalImagesEnabled': next.externalImagesEnabled = value; break;
      case 'searchEnabled': next.searchEnabled = value; break;
      case 'clipboardCopyEnabled': next.clipboardCopyEnabled = value; break;
      case 'boardSummaryUpdateEnabled': next.boardSummaryUpdateEnabled = value; break;
      case 'hostContextEnabled': next.hostContextEnabled = value; break;
      case 'hostWriteEnabled': next.hostWriteEnabled = value; break;
      case 'aiEnabled': next.aiEnabled = value; break;
      case 'sharedStoreEnabled': next.sharedStoreEnabled = value; break;
      case 'eventBusEnabled': next.eventBusEnabled = value; break;
      case 'launchEnabled': next.launchEnabled = value; break;
      case 'sensorEnabled': next.sensorEnabled = value; break;
      case 'locationEnabled': next.locationEnabled = value; break;
      case 'clipboardReadEnabled': next.clipboardReadEnabled = value; break;
      case 'webViewDebugEnabled': next.webViewDebugEnabled = value; break;
      case 'showSourceButton': next.showSourceButton = value; break;
      default: break;
    }
    return next;
  }

  // ===== UI =====

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  @Builder
  groupRow(kind: MiniAppGroupKind, isLast: boolean): void {
    CardRow({
      label: groupTitle(kind),
      desc: groupDescription(kind),
      isLast: isLast,
      onRowClick: (): void => {
        router.pushUrl({ url: 'pages/MiniAppSettingsPage', params: { group: kind } });
      },
    })
  }

  @Builder
  switchRow(row: SwitchRowData, enabled: boolean, isLast: boolean): void {
    Column() {
      CardToggleRow({
        label: row.title,
        desc: row.description,
        checked: this.valueForKey(this.setting, row.key),
        isLast: isLast,
        onToggle: (): void => { if (enabled) this.toggle(row.key); },
      })
    }
    .width('100%')
    .opacity(enabled ? 1 : 0.5)
  }

  @Builder
  groupContent(rows: SwitchRowData[]): void {
    Column() {
      ForEach(rows, (row: SwitchRowData, idx: number): void => {
        this.switchRow(row, this.setting.enabled, idx === rows.length - 1)
      }, (row: SwitchRowData): string => row.key)
    }
    .width('100%')
  }

  @Builder
  EnableCard(): void {
    Column() {
      CardToggleRow({
        label: '启用小应用',
        desc: '允许 Amber 生成、保存并运行轻量 HTML 工具',
        checked: this.setting.enabled,
        isLast: true,
        onToggle: (): void => { this.toggle('enabled'); },
      })
    }
    .width('100%')
  }

  @Builder
  GroupsCard(): void {
    Column() {
      this.groupRow('common', false)
      this.groupRow('host_ai', false)
      this.groupRow('advanced', true)
    }
    .width('100%')
  }

  // 分组页行卡:按当前 group 派发到对应行集
  @Builder
  GroupRowsCard(): void {
    Column() {
      if (this.group === 'common') {
        this.groupContent(COMMON_ROWS)
      } else if (this.group === 'host_ai') {
        this.groupContent(HOST_AI_ROWS)
      } else {
        this.groupContent(ADVANCED_ROWS)
      }
    }
    .width('100%')
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: this.group !== null ? groupTitle(this.group) : '小应用设置' })

      Scroll() {
        Column() {
          if (this.group === null) {
            PlainSection({ label: '启用' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.EnableCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '分组' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.GroupsCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          } else {
            PlainSection({ label: this.group === 'common' ? '网络与搜索' : (this.group === 'host_ai' ? '权限与写回' : '存储与调试') })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.GroupRowsCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
          }

          PageSpacer()
        }
        .width('100%')
        .padding({ left: 0, right: 0 })
      }
      .layoutWeight(1)
      .align(Alignment.TopStart)
      .backgroundColor(BG)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
    .backgroundColor(BG)
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}

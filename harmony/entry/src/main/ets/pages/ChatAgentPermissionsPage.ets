// ChatAgentPermissionsPage — 权限与批准设置面(D-104)
// 批准策略与实验性工具执行开关。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1988 风格):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardToggleRow),逻辑零改动。
import { ACCENT, BG, INK3, GUTTER, TEXT_STYLES } from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, PageSpacer,
} from '../components/PageTemplate.ets';
import {
  loadAgentRuntimeSnapshot, updateAgentRuntimePermissions,
} from '../platform_impl/AgentRuntimePrefs.ets';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { router } from '@kit.ArkUI';

@Entry
@Component
struct ChatAgentPermissionsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State loaded: boolean = false;
  @State loadError: boolean = false;
  @State autoApproveAll: boolean = false;
  @State autoApproveHighRisk: boolean = false;
  @State speculativeEnabled: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    loadAgentRuntimeSnapshot(getChatKvStore()).then((snap: AgentRuntimeSnapshot): void => {
      this.autoApproveAll = snap.autoApproveAllToolCalls;
      this.autoApproveHighRisk = snap.autoApproveHighRiskToolCalls;
      this.speculativeEnabled = snap.speculativeToolExecution.enabled;
      this.loadError = false;
      this.loaded = true;
    }).catch((): void => {
      this.loadError = true;
      this.loaded = true;
    });
  }

  private setAutoApproveAll(checked: boolean): void {
    updateAgentRuntimePermissions(getChatKvStore(), { autoApproveAllToolCalls: checked })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.autoApproveAll = snap.autoApproveAllToolCalls;
      });
  }

  private onHighRiskToggle(checked: boolean): void {
    if (checked) {
      AlertDialog.show({
        title: '确认开启高风险自动批准？',
        message: '开启后，如果全局自动批准也开启，AmberAgent 可以无人值守执行原本需要审批的工具，包括发送短信、直接拨号、写入文件/联系人/日历、录音、终端/屏幕动作和 SubAgent 内部工具。ask_user 仍会等待你的回答。只有在你完全信任当前设备、模型和会话上下文时才建议开启。',
        primaryButton: {
          value: '取消',
          action: (): void => {
            this.autoApproveHighRisk = false;
          },
        },
        secondaryButton: {
          value: '确认',
          fontColor: ACCENT,
          action: (): void => {
            updateAgentRuntimePermissions(getChatKvStore(), { autoApproveHighRiskToolCalls: true })
              .then((snap: AgentRuntimeSnapshot): void => {
                this.autoApproveHighRisk = snap.autoApproveHighRiskToolCalls;
              });
          },
        },
      });
    } else {
      updateAgentRuntimePermissions(getChatKvStore(), { autoApproveHighRiskToolCalls: false })
        .then((snap: AgentRuntimeSnapshot): void => {
          this.autoApproveHighRisk = snap.autoApproveHighRiskToolCalls;
        });
    }
  }

  private setSpeculative(checked: boolean): void {
    updateAgentRuntimePermissions(getChatKvStore(), { speculativeEnabled: checked })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.speculativeEnabled = snap.speculativeToolExecution.enabled;
      });
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  // 「系统权限」组:跳转权限与能力子页(iOS PermissionsApprovalView 同构:
  //   系统权限 = 权限与批准下的子页,不再占据设置根页独立行)
  @Builder
  SystemAccessGroup(): void {
    CardRow({
      label: '系统权限',
      desc: '日历、通知、文件、通讯录等系统能力的授权状态与申请',
      iconName: 'ph_lock',
      isLast: true,
      onRowClick: (): void => { router.pushUrl({ url: 'pages/SettingSystemAccessPage' }); },
    })
  }

  // 「批准策略」组:全局自动批准 + 高风险自动批准(确认弹窗逻辑原样)
  @Builder
  ApprovalGroup(): void {
    CardToggleRow({
      label: '全局自动批准',
      desc: '自动批准常规工具调用。和高风险自动批准同时开启时，除需要你回答的 ask_user 外，审批工具会无人值守执行。',
      checked: this.autoApproveAll,
      onToggle: (): void => { this.setAutoApproveAll(!this.autoApproveAll); },
    })
    CardToggleRow({
      label: '高风险自动批准',
      desc: '配合全局自动批准，允许短信、拨号、文件写入、终端/屏幕动作、SubAgent 内部工具等审批工具无人值守执行。',
      checked: this.autoApproveHighRisk,
      isLast: true,
      onToggle: (): void => { this.onHighRiskToggle(!this.autoApproveHighRisk); },
    })
  }

  // 「实验」组:提前执行只读工具
  @Builder
  ExperimentalGroup(): void {
    CardToggleRow({
      label: '实验：提前执行只读工具',
      desc: '仅对完整闭合且无需审批的只读工具生效，默认关闭。',
      checked: this.speculativeEnabled,
      isLast: true,
      onToggle: (): void => { this.setSpeculative(!this.speculativeEnabled); },
    })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '权限与批准', subtitle: '管理 Agent 使用文件、记忆和网页会话前是否需要确认' })
      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK3)
          .margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            if (this.loadError) {
              Text('运行时配置加载失败，以下内容未读取真实配置')
                .fontSize(12)
                .lineHeight(TEXT_STYLES.secondary.lineHeight)
                .fontFamily(FONT_SANS_MEDIUM)
                .fontColor(INK3)
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 4 })
            }
            PlainSection({ label: '系统权限' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SystemAccessGroup(); } })
              .margin({ left: GUTTER, right: GUTTER })
            PlainSection({ label: '批准策略' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ApprovalGroup(); } })
              .margin({ left: GUTTER, right: GUTTER })
            PlainSection({ label: '实验' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ExperimentalGroup(); } })
              .margin({ left: GUTTER, right: GUTTER })
            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

// ChatAgentPermissionsPage — 权限与批准设置面(D-104)
// 批准策略与实验性工具执行开关。
import { ACCENT, BG, INK3, TEXT_STYLES } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, ToggleRow, PageSpacer } from '../components/PageTemplate.ets';
import {
  loadAgentRuntimeSnapshot, updateAgentRuntimePermissions,
} from '../platform_impl/AgentRuntimePrefs.ets';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { FONT_MONO } from '../design/fonts.ets';

@Entry
@Component
struct ChatAgentPermissionsPage {
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

  build(): void {
    Column() {
      PageTitleBar({ title: '权限策略' })
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
              Text('// 运行时配置加载失败，以下内容未读取真实配置')
                .fontSize(TEXT_STYLES.meta.size)
                .fontWeight(TEXT_STYLES.meta.weight)
                .lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(FONT_MONO)
                .fontColor(INK3)
                .width('100%')
                .padding({ left: 16, right: 16, top: 12, bottom: 4 })
            }
            SectionHeader({ label: '批准策略' })
            ToggleRow({
              label: '全局自动批准',
              desc: '自动批准常规工具调用。和高风险自动批准同时开启时，除需要你回答的 ask_user 外，审批工具会无人值守执行。',
              checked: this.autoApproveAll,
              onToggle: (): void => { this.setAutoApproveAll(!this.autoApproveAll); },
            })
            ToggleRow({
              label: '高风险自动批准',
              desc: '配合全局自动批准，允许短信、拨号、文件写入、终端/屏幕动作、SubAgent 内部工具等审批工具无人值守执行。',
              checked: this.autoApproveHighRisk,
              isLast: true,
              onToggle: (): void => { this.onHighRiskToggle(!this.autoApproveHighRisk); },
            })
            SectionHeader({ label: '实验' })
            ToggleRow({
              label: '实验：提前执行只读工具',
              desc: '仅对完整闭合且无需审批的只读工具生效，默认关闭。',
              checked: this.speculativeEnabled,
              isLast: true,
              onToggle: (): void => { this.setSpeculative(!this.speculativeEnabled); },
            })
            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

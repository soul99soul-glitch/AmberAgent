// ChatAgentPermissionsPage — 权限与批准设置面(D-104)
// Android 锚点:
//   SettingAgentPermissionsPage.kt(全文 172 行)— 批准策略 CardGroup:
//     全局自动批准(autoApproveAllToolCalls 直写,:117-131);
//     高风险自动批准(开启 → AlertDialog 确认(:50-78,title/desc 逐字),
//       关闭直写,:133-153)
//   SettingAgentExtensionsPage.kt:216-233(推测执行开关,
//     speculativeToolExecution.enabled;maxConcurrentTools 无 UI)
//   PreferencesStore.kt:164-165,226-229(默认)
//   values-zh/strings.xml:634-635,654-656,996-997,1307-1310,255
// 偏差登记:
//   - '系统权限' section(:95-110 → SettingSystemAccess)= Android 系统权限
//     子系统(短信/拨号/联系人/日历/录音/终端),平台专属未移植 → N/A 登记
//   - 推测执行开关 Android 居于扩展/运行任务页(该页任务调度列表未移植),
//     此处并入'权限与批准'第二卡(placement 偏差登记)
//   - maxConcurrentTools 无 Android UI(恒 4),同不暴露
import { ACCENT,BG,INK,INK3,LINE,SURFACE,TITLE } from '../design/tokens.ets';
import {
  loadAgentRuntimeSnapshot, updateAgentRuntimePermissions,
} from '../platform_impl/AgentRuntimePrefs.ets';
import type { AgentRuntimeSnapshot } from '../platform_impl/AgentRuntimePrefs.ets';
import { getChatKvStore } from '../di/AppContainer.ets';


@Entry
@Component
struct ChatAgentPermissionsPage {
  @State loaded: boolean = false;
  @State autoApproveAll: boolean = false;
  @State autoApproveHighRisk: boolean = false;
  @State speculativeEnabled: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const snap: AgentRuntimeSnapshot = await loadAgentRuntimeSnapshot(getChatKvStore());
    this.autoApproveAll = snap.autoApproveAllToolCalls;
    this.autoApproveHighRisk = snap.autoApproveHighRiskToolCalls;
    this.speculativeEnabled = snap.speculativeToolExecution.enabled;
    this.loaded = true;
  }

  // vm.updateSettings(settings.copy(agentRuntime = ...)) 逐字段直写
  private setAutoApproveAll(checked: boolean): void {
    updateAgentRuntimePermissions(getChatKvStore(), { autoApproveAllToolCalls: checked })
      .then((snap: AgentRuntimeSnapshot): void => {
        this.autoApproveAll = snap.autoApproveAllToolCalls;
      });
  }

  // :136-153 — 开启 → 确认弹窗;关闭 → 直写
  private onHighRiskToggle(checked: boolean): void {
    if (checked) {
      AlertDialog.show({
        title: '确认开启高风险自动批准？',
        message: '开启后，如果全局自动批准也开启，AmberAgent 可以无人值守执行原本需要审批的工具，' +
          '包括发送短信、直接拨号、写入文件/联系人/日历、录音、终端/屏幕动作和 SubAgent 内部工具。' +
          'ask_user 仍会等待你的回答。只有在你完全信任当前设备、模型和会话上下文时才建议开启。',
        primaryButton: {
          value: '取消',
          action: (): void => {
            // 取消 → 开关回弹(Android 不写值,状态保持 false)
            this.autoApproveHighRisk = false;
          },
        },
        secondaryButton: {
          value: '确认',
          fontColor: ACCENT,
          action: (): void => {
            updateAgentRuntimePermissions(
              getChatKvStore(), { autoApproveHighRiskToolCalls: true })
              .then((snap: AgentRuntimeSnapshot): void => {
                this.autoApproveHighRisk = snap.autoApproveHighRiskToolCalls;
              });
          },
        },
      });
    } else {
      updateAgentRuntimePermissions(
        getChatKvStore(), { autoApproveHighRiskToolCalls: false })
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

  @Builder
  switchCard(title: string, desc: string, value: boolean, onChange: (v: boolean) => void) {
    Row() {
      Column() {
        Text(title).fontSize(14).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(desc).fontSize(11).fontColor(INK3).margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
      Toggle({ type: ToggleType.Switch, isOn: value })
        .selectedColor(ACCENT)
        .onChange(onChange)
    }
    .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 8 })
  }

  @Builder
  groupLabel(text: string) {
    Row() {
      Text(text)
        .fontSize(11).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 8, right: 8, top: 16, bottom: 6 })
  }

  build() {
    Column() {
      Row() {
        Text('权限与批准')
          .fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
      }
      .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 4 })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else {
        Scroll() {
          Column() {
            this.groupLabel('批准策略')
            this.switchCard('全局自动批准',
              '自动批准常规工具调用。和高风险自动批准同时开启时，除需要你回答的 ask_user 外，' +
              '审批工具会无人值守执行。',
              this.autoApproveAll,
              (v: boolean): void => { this.setAutoApproveAll(v); })
            this.switchCard('高风险自动批准',
              '配合全局自动批准，允许短信、拨号、文件写入、终端/屏幕动作、' +
              'SubAgent 内部工具等审批工具无人值守执行。',
              this.autoApproveHighRisk,
              (v: boolean): void => { this.onHighRiskToggle(v); })

            this.groupLabel('实验')
            this.switchCard('实验：提前执行只读工具',
              '仅对完整闭合且无需审批的只读工具生效，默认关闭。',
              this.speculativeEnabled,
              (v: boolean): void => { this.setSpeculative(v); })
          }
          .width('100%').padding({ left: 16, right: 16, bottom: 24 })
        }
        .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

// SettingCronTasksPage — 定时任务(接 AgentCronManager 真实后端)
//
// Android 基准: SettingAgentExtensionsPage.kt:319 SettingCronTasksPage
//   (列表/启停/立即运行/删除)。鸿蒙接 chat-domain AgentCronManager +
//   entry AgentCronStore 持久化端口(AppContainer.getAgentCronManager)。

import { promptAction } from '@kit.ArkUI';
import type { AgentCronManager, AgentCronTask } from '@amber/chat-domain';
import {
  getAgentCronManager, isAgentCronExecutionAvailable,
} from '../di/AppContainer.ets';
import {
  BG, SURFACE, INK, INK3, INK4, LINE, ACCENT, ERROR,
  BODY, BODY2, META, MONO_SMALL,
} from '../design/tokens.ets';
import { PageTitleBar, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct SettingCronTasksPage {
  @State tasks: AgentCronTask[] = [];
  @State loaded: boolean = false;
  private readonly executionAvailable: boolean = isAgentCronExecutionAvailable();
  private manager: AgentCronManager | null = null;

  aboutToAppear(): void {
    getAgentCronManager().then((m: AgentCronManager): void => {
      this.manager = m;
      this.reload();
    }).catch((): void => { this.loaded = true; });
  }

  private reload(): void {
    const m: AgentCronManager | null = this.manager;
    if (m === null) return;
    m.listTasks().then((list: AgentCronTask[]): void => {
      this.tasks = list;
      this.loaded = true;
    });
  }

  private fmtTime(ms: number | null): string {
    if (ms === null) return '—';
    const d: Date = new Date(ms);
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }

  build(): void {
    Column() {
      PageTitleBar({ title: '定时任务' })

      if (!this.executionAvailable) {
        Text('当前 Harmony 版本暂不支持后台执行。已有任务仅供查看，不能启用或立即运行。')
          .fontSize(BODY2).fontColor(ERROR).lineHeight(19)
          .width('100%').padding({ left: 18, right: 18, top: 12, bottom: 12 })
          .backgroundColor(SURFACE)
      }

      if (this.loaded && this.tasks.length === 0) {
        Column() {
          Text('// 暂无定时任务').fontSize(META).fontColor(INK3).fontFamily('JetBrainsMono')
          Text('在对话中让 Agent 用 cron 工具创建，会显示在这里')
            .fontSize(BODY2).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        List() {
          ForEach(this.tasks, (t: AgentCronTask): void => {
            ListItem() {
              Column() {
                Row() {
                  Text(t.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
                    .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Toggle({
                    type: ToggleType.Switch,
                    isOn: this.executionAvailable && t.enabled,
                  })
                    .selectedColor(ACCENT)
                    .enabled(this.executionAvailable)
                    .onChange((on: boolean): void => {
                      if (!this.executionAvailable) return;
                      const m: AgentCronManager | null = this.manager;
                      if (m === null) return;
                      m.updateTask(t.id, { enabled: on })
                        .then((): void => { this.reload(); })
                        .catch((e: Error): void => {
                          promptAction.showToast({ message: e.message, duration: 1800 });
                        });
                    })
                }
                .width('100%')

                Text(t.prompt).fontSize(BODY2).fontColor(INK3).margin({ top: 4 })
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })

                Row() {
                  Text(t.cronExpression).fontSize(MONO_SMALL).fontColor(INK3).fontFamily('JetBrainsMono')
                  Text(`下次 ${this.fmtTime(t.nextRunAtMs)}`)
                    .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono').margin({ left: 12 })
                  Text(`已跑 ${t.runCount} 次`)
                    .fontSize(MONO_SMALL).fontColor(INK4).fontFamily('JetBrainsMono').margin({ left: 12 })
                  Blank()
                  Icon({
                    name: 'retry', iconSize: 15,
                    color: this.executionAvailable ? INK3 : INK4,
                  })
                    .onClick((): void => {
                      if (!this.executionAvailable) {
                        promptAction.showToast({
                          message: '当前 Harmony 版本暂不支持 Cron 执行', duration: 1800,
                        });
                        return;
                      }
                      const m: AgentCronManager | null = this.manager;
                      if (m === null) return;
                      m.runTaskNow(t.id).then((ok: boolean): void => {
                        promptAction.showToast({ message: ok ? '已触发运行' : '运行失败', duration: 1500 });
                        this.reload();
                      }).catch((e: Error): void => {
                        promptAction.showToast({ message: e.message, duration: 1800 });
                      });
                    })
                  Icon({ name: 'trash', iconSize: 15, color: ERROR }).margin({ left: 14 })
                    .onClick((): void => {
                      const m: AgentCronManager | null = this.manager;
                      if (m === null) return;
                      AlertDialog.show({
                        title: '删除定时任务', message: `删除「${t.title}」？`,
                        primaryButton: { value: '取消', action: (): void => {} },
                        secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
                          m.deleteTask(t.id).then((): void => { this.reload(); });
                        } },
                      });
                    })
                }
                .width('100%').margin({ top: 6 })
              }
              .padding(14)
              .backgroundColor(SURFACE)
              .border({ width: 1, color: LINE }).borderRadius(14)
              .margin({ left: 18, right: 18, bottom: 10 })
            }
          }, (t: AgentCronTask): string => t.id)
        }
        .width('100%').layoutWeight(1).padding({ top: 10 })
        .divider({ strokeWidth: 0, color: Color.Transparent })
      }
      PageSpacer()
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

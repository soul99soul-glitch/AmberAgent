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
  BG, SURFACE2, INK, INK3, INK4, LINE2, ACCENT, ERROR,
  BODY, BODY2, META, MONO_SMALL, TEXT_STYLES,
} from '../design/tokens.ets';
import { FONT_MONO, FONT_SANS, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import { PageTitleBar, SectionHeader, IconSeat, PaperSeparator, PageSpacer } from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct SettingCronTasksPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
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
    }).catch((e: Error): void => {
      this.loaded = true;
      promptAction.showToast({ message: e.message, duration: 1800 });
    });
  }

  private fmtTime(ms: number | null): string {
    if (ms === null) return '—';
    const d: Date = new Date(ms);
    const p = (n: number): string => n < 10 ? `0${n}` : `${n}`;
    return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }

  // R17:状态摘要——Cancelled/Suspend 与 Succeeded 明示区分,避免用户把
  //   「已取消本轮」误读成正常完成。
  private statusLabel(t: AgentCronTask): string {
    if (t.lastStatus === 'Running') return '运行中';
    if (t.lastStatus === 'Queued') return '已排队';
    if (t.lastStatus === 'Failed') return '上次失败';
    if (t.lastStatus === 'Cancelled') return '本轮已取消';
    if (t.lastStatus === 'Suspend') return '已停止';
    if (t.lastStatus === 'Succeeded') return '上次成功';
    return '';
  }

  private isRunning(t: AgentCronTask): boolean {
    return t.lastStatus === 'Running';
  }

  @State pressedTask: string = '';

  @Builder taskRow(t: AgentCronTask, isLast: boolean): void {
    Column() {
      Row() {
        IconSeat({ iconName: 'clock' }).margin({ right: 12 })
        Column() {
          Row() {
            Text(t.title)
              .fontSize(BODY).fontWeight(600).lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(FONT_SANS_SEMIBOLD).fontColor(INK)
              .layoutWeight(1).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Stack({ alignContent: Alignment.Start }) {
              Column()
                .width(42).height(24).borderRadius(99)
                .backgroundColor(this.executionAvailable && t.enabled ? this.accentColorStore : LINE2)
              Column()
                .width(20).height(20).borderRadius(10)
                .backgroundColor('#FFFFFF')
                .margin({ left: this.executionAvailable && t.enabled ? 22 : 2 })
                .animation({ duration: 150, curve: Curve.EaseOut })
            }
            .clip(true)
            .margin({ left: 12 })
            .opacity(this.executionAvailable ? 1 : 0.5)
            .onClick((): void => {
              if (!this.executionAvailable) return;
              const m: AgentCronManager | null = this.manager;
              if (m === null) return;
              m.updateTask(t.id, { enabled: !t.enabled })
                .then((): void => { this.reload(); })
                .catch((e: Error): void => {
                  promptAction.showToast({ message: e.message, duration: 1800 });
                });
            })
            .accessibilityText(this.executionAvailable && t.enabled ? '已开启' : '已关闭')
          }
          .width('100%')

          Text(t.prompt)
            .fontSize(BODY2).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(FONT_SANS).fontColor(INK3).margin({ top: 4 })
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })

          if (t.lastError !== null && t.lastError.length > 0) {
            Text(t.lastError)
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO).fontColor(ERROR).margin({ top: 4 })
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          }

          Row() {
            Text(t.cronExpression)
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO).fontColor(INK3)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(`下次 ${this.fmtTime(t.nextRunAtMs)}`)
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO).fontColor(INK4).margin({ left: 12 })
            Text(`已跑 ${t.runCount} 次`)
              .fontSize(MONO_SMALL).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.meta.lineHeight)
              .fontFamily(FONT_MONO).fontColor(INK4).margin({ left: 12 })
            if (this.statusLabel(t).length > 0) {
              Text(this.statusLabel(t))
                .fontSize(MONO_SMALL).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(FONT_MONO)
                .fontColor(t.lastStatus === 'Failed' ? ERROR : INK3)
                .margin({ left: 12 })
            }
            Blank()
            // R17:运行中提供「停止本轮」入口(停当前生成;下一周期仍按计划保留)
            if (this.isRunning(t)) {
              Text('停止本轮')
                .fontSize(MONO_SMALL).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
                .fontFamily(FONT_MONO).fontColor(ERROR)
                .padding({ left: 8, right: 8, top: 6, bottom: 6 })
                .accessibilityText('停止本轮生成')
                .onClick((): void => {
                  const m: AgentCronManager | null = this.manager;
                  if (m === null) return;
                  m.cancelRun(t.id).then((stopped: boolean): void => {
                    promptAction.showToast({
                      message: stopped ? '已停止本轮生成' : '当前没有在跑的运行', duration: 1500,
                    });
                    this.reload();
                  }).catch((e: Error): void => {
                    promptAction.showToast({ message: e.message, duration: 1800 });
                  });
                })
            }
            Row() {
              Icon({
                name: 'retry', iconSize: 15,
                color: this.executionAvailable && !this.isRunning(t) ? INK3 : INK4,
              })
            }
            .width(44).height(44)
            .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
            .accessibilityText('立即运行')
            .onClick((): void => {
              if (!this.executionAvailable) {
                promptAction.showToast({
                  message: '当前 Harmony 版本暂不支持 Cron 执行', duration: 1800,
                });
                return;
              }
              if (this.isRunning(t)) {
                promptAction.showToast({ message: '任务正在运行', duration: 1500 });
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
            Row() {
              Icon({ name: 'trash', iconSize: 15, color: ERROR })
            }
            .width(44).height(44)
            .justifyContent(FlexAlign.Center).alignItems(VerticalAlign.Center)
            .margin({ left: 2 })
            .accessibilityText('删除定时任务')
            .onClick((): void => {
              const m: AgentCronManager | null = this.manager;
              if (m === null) return;
              AlertDialog.show({
                title: '删除定时任务',
                message: this.isRunning(t)
                  ? `删除「${t.title}」？正在运行的生成也会被停止，且不再排下一周期。`
                  : `删除「${t.title}」？`,
                primaryButton: { value: '取消', action: (): void => {} },
                secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
                  m.deleteTask(t.id).then((): void => { this.reload(); })
                    .catch((e: Error): void => {
                      promptAction.showToast({ message: e.message, duration: 1800 });
                    });
                } },
              });
            })
          }
          .width('100%').margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 64 })
      .padding({ left: 16, right: 16, top: 12, bottom: 12 })
      .backgroundColor(this.pressedTask === t.id ? SURFACE2 : Color.Transparent)
      .onTouch((e: TouchEvent): void => {
        if (e.type === TouchType.Down) this.pressedTask = t.id;
        else if (e.type === TouchType.Up || e.type === TouchType.Cancel) this.pressedTask = '';
      })
      if (!isLast) {
        PaperSeparator()
      }
    }
    .width('100%')
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      PageTitleBar({ title: '定时任务' })

      if (!this.executionAvailable) {
        Text('当前 Harmony 版本暂不支持后台执行。已有任务仅供查看，不能启用或立即运行。')
          .fontSize(BODY2).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.secondary.lineHeight)
          .fontFamily(FONT_SANS).fontColor(ERROR)
          .width('100%').padding({ left: 16, right: 16, top: 12, bottom: 12 })
      }

      if (this.loaded && this.tasks.length === 0) {
        Column() {
          Text('// 暂无定时任务')
            .fontSize(META).fontWeight(FontWeight.Medium).lineHeight(TEXT_STYLES.meta.lineHeight)
            .fontColor(INK3).fontFamily(FONT_MONO)
          Text('在对话中让 Agent 用 cron 工具创建，会显示在这里')
            .fontSize(BODY2).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(FONT_SANS).fontColor(INK4).margin({ top: 8 })
        }
        .width('100%').padding({ top: 80 }).alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            SectionHeader({ label: '任务列表' })
            ForEach(this.tasks, (t: AgentCronTask, idx: number): void => {
              this.taskRow(t, idx === this.tasks.length - 1);
            }, (t: AgentCronTask): string => t.id)
            PageSpacer()
          }
          .width('100%')
        }
        .layoutWeight(1)
        .align(Alignment.TopStart)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

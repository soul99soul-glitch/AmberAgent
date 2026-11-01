import {
  ACCENT, BG, ERROR, INK, INK2, INK3, LINE, META, SIGNAL, STATUS_AMBER, SURFACE2,
} from '../../design/tokens.ets';
import { FONT_MONO, FONT_SANS } from '../../design/fonts.ets';
import {
  ChatCloudDialogueSession,
  formatCloudInputStateBadge,
  shortWorkItemId,
  type CloudDialogueInputRecord,
  type CloudDialogueTaskRecord,
} from '../../platform_impl/chat/ChatCloudDialogueSession.ets';

@Component
export struct ChatCloudDialogueStrip {
  @ObjectLink session: ChatCloudDialogueSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  private inputBadgeColor(state: string): string {
    if (state === 'received') return STATUS_AMBER;
    if (state === 'consumed') return SIGNAL;
    return ERROR;
  }

  build(): void {
    if (this.session.uiTick >= 0 && this.session.isActiveMode()) {
      Column({ space: 6 }) {
        // 顶部状态与当前输入路由提示（普通新问题 vs 指定任务补充）
        Row({ space: 6 }) {
          Text(`云主对话 · ${this.session.gatewayName}`)
            .fontSize(11)
            .fontFamily(FONT_MONO)
            .fontColor(this.accentColorStore)
          if (this.session.followupTargetTask() !== null) {
            Text(`补充目标：${this.session.followupTargetTask()?.title ?? ''}`)
              .fontSize(11)
              .fontFamily(FONT_SANS)
              .fontColor(INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
            Text('切回新提问')
              .fontSize(11)
              .fontFamily(FONT_SANS)
              .fontColor(this.accentColorStore)
              .padding({ left: 8, right: 8, top: 3, bottom: 3 })
              .backgroundColor(SURFACE2)
              .borderRadius(10)
              .onClick((): void => {
                this.session.clearFollowupTarget();
              })
          } else {
            Text(this.session.runningTasks().length > 0 ? '输入框可直接提新问题，或点任务「补充」追问' : '输入框消息将发往云端执行')
              .fontSize(11)
              .fontFamily(FONT_SANS)
              .fontColor(INK3)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
          }
        }
        .width('100%')

        // 运行中云任务单行进度条（process_event 原地刷新同一处单行进度，不生成每工具卡片）
        ForEach(this.session.runningTasks(), (task: CloudDialogueTaskRecord): void => {
          Row({ space: 8 }) {
            LoadingProgress()
              .width(13)
              .height(13)
              .color(this.accentColorStore)
            Text(`${task.title} · ${task.progressText.length > 0 ? task.progressText : '云端执行中…'}`)
              .fontSize(META)
              .fontFamily(FONT_SANS)
              .fontColor(INK2)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
            Text(this.session.followupTargetWorkItemId === task.workItemId ? '取消补充' : '补充')
              .fontSize(11)
              .fontFamily(FONT_SANS)
              .fontColor(this.session.followupTargetWorkItemId === task.workItemId ? BG : this.accentColorStore)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .backgroundColor(this.session.followupTargetWorkItemId === task.workItemId ? this.accentColorStore : BG)
              .borderRadius(10)
              .border({ width: 0.5, color: this.accentColorStore })
              .onClick((): void => {
                this.session.toggleFollowupTarget(task.workItemId);
              })
            Text(task.stopRequested ? '停止中…' : '停止')
              .fontSize(11)
              .fontFamily(FONT_SANS)
              .fontColor(ERROR)
              .padding({ left: 8, right: 8, top: 4, bottom: 4 })
              .backgroundColor(BG)
              .borderRadius(10)
              .border({ width: 0.5, color: ERROR })
              .enabled(!task.stopRequested)
              .onClick((): void => {
                this.session.stopTask(task.workItemId);
              })
          }
          .width('100%')
          .padding({ left: 10, right: 10, top: 6, bottom: 6 })
          .backgroundColor(SURFACE2)
          .borderRadius(12)
        }, (task: CloudDialogueTaskRecord): string =>
          `${task.workItemId}-${task.status}-${task.stopRequested}-${task.progressText}-${this.session.followupTargetWorkItemId}`)

        // 最近输入回执状态区分展示（received / consumed / cancelled）
        if (this.session.recentInputs().length > 0) {
          Scroll() {
            Row({ space: 6 }) {
              ForEach(this.session.recentInputs(), (item: CloudDialogueInputRecord): void => {
                Text(formatCloudInputStateBadge(item.kind, item.state, shortWorkItemId(item.workItemId)))
                  .fontSize(10.5)
                  .fontFamily(FONT_MONO)
                  .fontColor(this.inputBadgeColor(item.state))
                  .maxLines(1)
                  .padding({ left: 8, right: 8, top: 3, bottom: 3 })
                  .backgroundColor(SURFACE2)
                  .borderRadius(8)
              }, (item: CloudDialogueInputRecord): string =>
                `${item.requestId}-${item.state}-${item.workItemId}-${item.updatedAt}`)
            }
          }
          .scrollable(ScrollDirection.Horizontal)
          .scrollBar(BarState.Off)
          .width('100%')
        }
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 6, bottom: 6 })
      .backgroundColor(BG)
      .border({ width: { top: 0.5 }, color: LINE })
    }
  }
}

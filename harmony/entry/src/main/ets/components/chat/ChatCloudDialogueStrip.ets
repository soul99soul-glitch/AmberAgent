import {
  ACCENT, BG, ERROR, INK, INK2, LINE, META, STATUS_AMBER, SURFACE, SURFACE2, accentEdge, accentSoftBg,
} from '../../design/tokens.ets';
import { FONT_SANS } from '../../design/fonts.ets';
import {
  ChatCloudDialogueSession,
  type CloudDialogueTaskRecord,
} from '../../platform_impl/chat/ChatCloudDialogueSession.ets';

// 仅在有真实运行中后台任务或显式指定补充目标时展示简洁状态行；
// 绝不向用户暴露内部调度模式名或队列回执状态码（如「本地异步执行」「已消费 [consumed]」）。
@Component
export struct ChatCloudDialogueStrip {
  @ObjectLink session: ChatCloudDialogueSession;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  private shouldRender(): boolean {
    if (this.session.uiTick < 0) return false;
    // 普通本机前台对话不需要在输入框上方叠加冗余「补充 / 停止」条；
    // 仅在显式选定了补充目标，或云端主对话有运行中后台任务时展示。
    if (this.session.followupTargetTask() !== null) return true;
    return this.session.isActiveMode() && this.session.runningTasks().length > 0;
  }

  build(): void {
    if (this.shouldRender()) {
      Column({ space: 6 }) {
        if (this.session.followupTargetTask() !== null) {
          Row({ space: 8 }) {
            Text(`补充说明：${this.session.followupTargetTask()?.title ?? ''}`)
              .fontSize(12)
              .fontFamily(FONT_SANS)
              .fontColor(INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .layoutWeight(1)
            Text('取消')
              .fontSize(11.5)
              .fontFamily(FONT_SANS)
              .fontWeight(FontWeight.Medium)
              .fontColor(this.accentColorStore)
              .padding({ left: 8, right: 8, top: 3, bottom: 3 })
              .backgroundColor(accentSoftBg(this.accentColorStore))
              .borderRadius(999)
              .onClick((): void => {
                this.session.clearFollowupTarget();
              })
          }
          .width('100%')
          .padding({ left: 12, right: 10, top: 6, bottom: 6 })
          .backgroundColor(SURFACE)
          .borderRadius(12)
          .border({ width: 0.75, color: accentEdge(this.accentColorStore) })
        }

        if (this.session.isActiveMode()) {
          ForEach(this.session.runningTasks(), (task: CloudDialogueTaskRecord): void => {
            Row({ space: 8 }) {
              if (task.status === 'queued') {
                Text('排队')
                  .fontSize(10.5)
                  .fontFamily(FONT_SANS)
                  .fontColor(STATUS_AMBER)
                  .padding({ left: 6, right: 6, top: 2, bottom: 2 })
                  .backgroundColor(BG)
                  .borderRadius(999)
              } else {
                LoadingProgress()
                  .width(13)
                  .height(13)
                  .color(this.accentColorStore)
              }
              Text(`${task.title}${task.progressText.length > 0 ? ` · ${task.progressText}` : ''}`)
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
                .backgroundColor(this.session.followupTargetWorkItemId === task.workItemId ? this.accentColorStore : SURFACE)
                .borderRadius(999)
                .border({ width: 0.5, color: accentEdge(this.accentColorStore) })
                .onClick((): void => {
                  this.session.toggleFollowupTarget(task.workItemId);
                })
              Text(task.stopRequested ? '停止中…' : '停止')
                .fontSize(11)
                .fontFamily(FONT_SANS)
                .fontColor(ERROR)
                .padding({ left: 8, right: 8, top: 4, bottom: 4 })
                .backgroundColor(SURFACE)
                .borderRadius(999)
                .border({ width: 0.5, color: LINE })
                .enabled(!task.stopRequested)
                .onClick((): void => {
                  this.session.stopTask(task.workItemId);
                })
            }
            .width('100%')
            .padding({ left: 12, right: 10, top: 6, bottom: 6 })
            .backgroundColor(SURFACE2)
            .borderRadius(12)
            .border({ width: 0.5, color: LINE })
          }, (task: CloudDialogueTaskRecord): string =>
            `${task.workItemId}-${task.status}-${task.stopRequested}-${task.progressText}-${this.session.followupTargetWorkItemId}`)
        }
      }
      .width('100%')
      .padding({ left: 20, right: 20, top: 4, bottom: 6 })
    }
  }
}

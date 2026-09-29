// CouncilPage — 模型议会落地页(对齐 iOS CouncilView):介绍 + 开始议会(进房间) + 最近讨论 + 设置入口
import { router } from '@kit.ArkUI';
import { BG, INK3, LABEL, META, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SANS } from '../design/fonts.ets';
import { CenteredTitleBar, PlainSection, CardGroup, NavRow, PageSpacer } from '../components/PageTemplate.ets';
import { getCouncilManager } from '../di/AppContainer.ets';
import { isRunningStatus } from '@amber/deepread-domain';
import type { ModelCouncilRun } from '@amber/deepread-domain';

const POLL_MS: number = 1000;

const statusLabel = (run: ModelCouncilRun): string => {
  switch (run.status) {
    case 'running': return '进行中';
    case 'completed': return '已完成';
    case 'partial_failed': return '部分完成';
    case 'failed': return '失败';
    case 'cancelled': return '已取消';
    case 'timed_out': return '超时';
    case 'interrupted': return '已中断';
    default: return run.status;
  }
};

const relativeTime = (ms: number): string => {
  const diff: number = Math.max(0, Date.now() - ms);
  if (diff < 60 * 1000) return '刚刚';
  if (diff < 3600 * 1000) return `${Math.floor(diff / 60000)} 分钟前`;
  if (diff < 86400 * 1000) return `${Math.floor(diff / 3600000)} 小时前`;
  return `${Math.floor(diff / 86400000)} 天前`;
};

@Entry
@Component
struct CouncilPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State runs: ModelCouncilRun[] = [];
  @State tick: number = 0;
  private timer: number = -1;
  private visible: boolean = false;

  aboutToAppear(): void {
    this.visible = true;
    this.refresh();
    // 1s 轮询:运行中的行状态/相对时间随 tick 更新(与 SubAgentActivityStrip 同一节奏)
    this.timer = setInterval((): void => {
      if (!this.visible) return;
      this.refresh();
      this.tick++;
    }, POLL_MS);
  }

  onPageShow(): void { this.visible = true; this.refresh(); }
  onPageHide(): void { this.visible = false; }

  aboutToDisappear(): void {
    this.visible = false;
    if (this.timer !== -1) { clearInterval(this.timer); this.timer = -1; }
  }

  private refresh(): void {
    const mgr = getCouncilManager();
    this.runs = mgr === null ? [] : mgr.listAll().slice(0, 5);
  }

  private runDesc(run: ModelCouncilRun): string {
    const mode: string = run.mode === 'debate' ? '辩论' : '对比';
    return `${statusLabel(run)} · ${mode} · ${run.seats.length} 席 · ${relativeTime(run.updatedAtMs)}`;
  }

  @Builder
  StartCard(): void {
    CardGroup({ radius: 18, stroke: true }) {
      NavRow({ label: '开始议会', desc: '进入实时议会，邀请不同席位接力讨论', iconName: 'ph_chatCircleDots',
        isLast: true, onRowClick: (): void => { router.pushUrl({ url: 'pages/CouncilRunPage' }); } })
    }
  }

  @Builder
  RecentCard(): void {
    CardGroup({ radius: 18, stroke: true }) {
      if (this.runs.length === 0) {
        Text('暂无模型议会任务。开始议会后，这里会显示席位、状态和结论。')
          .fontSize(LABEL).lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(FONT_SANS).fontColor(INK3)
          .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
      } else {
        ForEach(this.runs, (run: ModelCouncilRun, i: number): void => {
          NavRow({
            label: run.task.objective,
            desc: this.runDesc(run),
            iconName: run.status === 'completed' ? 'check' : (isRunningStatus(run.status) ? 'ph_chatCircleDots' : 'info'),
            isLast: i === this.runs.length - 1,
            onRowClick: (): void => {
              const params: Record<string, string> = { 'runId': run.runId };
              router.pushUrl({ url: 'pages/CouncilRunPage', params: params });
            },
          })
        }, (run: ModelCouncilRun): string => `${run.runId}-${run.status}-${run.updatedAtMs}-${this.tick}`)
      }
    }
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '模型议会', subtitle: '多模型协作评审', actionText: '设置',
        onAction: (): void => { router.pushUrl({ url: 'pages/SettingModelCouncilPage' }); } })
      Scroll() {
        Column() {
          Text('模型议会会让多个席位从不同角度评审同一个问题。你可以手动启动一次，也可以管理自定义席位。')
            .fontSize(META).lineHeight(TEXT_STYLES.meta.lineHeight + 2).fontFamily(FONT_SANS).fontColor(INK3)
            .width('100%').padding({ left: 16, right: 16, top: 4, bottom: 8 })
          PlainSection({ label: '启动' })
          this.StartCard()
          PlainSection({ label: '最近讨论' })
          this.RecentCard()
          PageSpacer()
        }
        .width('100%').padding({ left: 16, right: 16 })
      }
      .align(Alignment.TopStart)
      .layoutWeight(1)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

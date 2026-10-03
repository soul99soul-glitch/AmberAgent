// NovelBranchSheet — 小说工作区的分支选择与轻量历史控制面板。
//
// 组件不持有或修改工作区数据；父页面负责以回调执行创建、切换、撤销及状态同步。
import {
  ACCENT, ACCENT_INK, ACCENT_LIGHT, BODY, ERROR, INK, INK3, INK4,
  LINE, MOTION_OVERLAY, OVERLAY, RAISED, SURFACE, SURFACE2, TEXT_STYLES,
} from '../design/tokens.ets';

export type NovelBranchSheetLifecycle = 'active' | 'deleted';

// 保持为 UI 所需的最小数据形状，调用方可直接传入领域层 NovelBranch。
export interface NovelBranchSheetBranch {
  id: string;
  name: string;
  lifecycle: NovelBranchSheetLifecycle;
  isMain: boolean;
}

@Component
export struct NovelBranchSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop visible: boolean = false;
  @Prop activeBranchId: string = '';
  @Prop branches: NovelBranchSheetBranch[] = [];
  @Prop busy: boolean = false;
  @Prop switchBusy: boolean = false;
  @Prop switchNeedsStop: boolean = false;
  @Prop canUndo: boolean = false;
  @Prop unresolvedFromOrdinal: number | null = null;
  @Prop plotStale: boolean = false;
  @State private newBranchName: string = '';

  onClose: () => void = (): void => {};
  onCreate: (name: string) => void = (_name: string): void => {};
  onSelect: (id: string) => void = (_id: string): void => {};
  onUndo: () => void = (): void => {};
  onSyncPlot: () => void = (): void => {};
  onResolveUnresolved: () => void = (): void => {};
  onOpenHistory: () => void = (): void => {};

  build(): void {
    if (this.themeEpoch >= 0 && this.visible) {
      Stack() {
        Row() {}
          .width('100%').height('100%').backgroundColor(OVERLAY)
          .onClick((): void => { if (!this.switchBusy) this.onClose(); })

        Scroll() {
          Column() {
            this.header()
            this.branchList()
            this.createRow()
            this.historyControls()
          }
          .width('100%')
        }
        .width('100%')
        .constraintSize({ maxHeight: '90%' })
        .backgroundColor(SURFACE)
        .borderRadius({ topLeft: 20, topRight: 20 })
        .padding({ left: 20, right: 20, top: 18, bottom: 24 })
        .transition(TransitionEffect.translate({ y: 160 })
          .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
      }
      .width('100%').height('100%').alignContent(Alignment.Bottom)
      .transition(TransitionEffect.OPACITY
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
  }

  @Builder
  private header(): void {
    Row() {
      Column() {
        Text('故事分支')
          .fontSize(TEXT_STYLES.sessionTitle.size).fontWeight(TEXT_STYLES.sessionTitle.weight)
          .lineHeight(TEXT_STYLES.sessionTitle.lineHeight).fontFamily(TEXT_STYLES.sessionTitle.family)
          .fontColor(INK)
        Text(this.switchBusy ? '正在处理工作区变更…' : this.switchNeedsStop ? '切换前会确认停止当前任务。' : '切换分支不会修改其他分支。')
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(TEXT_STYLES.meta.family)
          .fontColor(INK3).margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Text('关闭')
        .fontSize(TEXT_STYLES.secondary.size).fontWeight(TEXT_STYLES.secondary.weight)
        .lineHeight(TEXT_STYLES.secondary.lineHeight).fontFamily(TEXT_STYLES.secondary.family)
        .fontColor(this.switchBusy ? INK4 : INK3)
        .width(44).height(44).textAlign(TextAlign.Center)
        .onClick((): void => { if (!this.switchBusy) this.onClose(); })
        .accessibilityText('关闭故事分支面板')
    }
    .width('100%').margin({ bottom: 12 }).alignItems(VerticalAlign.Center)
  }

  @Builder
  private branchList(): void {
    Column() {
      Text('可用分支')
        .fontSize(TEXT_STYLES.eyebrow.size).fontWeight(TEXT_STYLES.eyebrow.weight)
        .lineHeight(TEXT_STYLES.eyebrow.lineHeight).fontFamily(TEXT_STYLES.eyebrow.family)
        .fontColor(INK3).margin({ bottom: 6 })

      if (this.activeBranches().length === 0) {
        Text('暂无可用分支。创建后可在这里切换。')
          .fontSize(TEXT_STYLES.secondary.size).fontColor(INK3)
          .width('100%').padding({ top: 12, bottom: 16 })
      } else {
        Scroll() {
          Column() {
            ForEach(this.activeBranches(), (branch: NovelBranchSheetBranch): void => {
              this.branchRow(branch)
            }, (branch: NovelBranchSheetBranch): string => branch.id)
          }
          .width('100%')
        }
        .height(this.branchListHeight())
      }
    }
    .width('100%').margin({ bottom: 16 })
  }

  @Builder
  private branchRow(branch: NovelBranchSheetBranch): void {
    Row() {
      Column() {
        Row() {
          Text(branch.name.length > 0 ? branch.name : '未命名分支')
            .fontSize(BODY).fontWeight(FontWeight.Medium)
            .fontColor(this.isActiveBranch(branch.id) ? ACCENT : INK)
            .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
          if (this.isActiveBranch(branch.id)) {
            Text('✓').fontSize(18).fontWeight(FontWeight.Bold).fontColor(ACCENT).margin({ left: 12 })
          }
        }
        .width('100%')
        Text(branch.isMain ? '主线' : '分支')
          .fontSize(TEXT_STYLES.meta.size).fontWeight(TEXT_STYLES.meta.weight)
          .lineHeight(TEXT_STYLES.meta.lineHeight).fontFamily(TEXT_STYLES.meta.family)
          .fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
    }
    .width('100%').constraintSize({ minHeight: 64 }).padding({ left: 14, right: 14, top: 8, bottom: 8 })
    .backgroundColor(this.isActiveBranch(branch.id) ? ACCENT_LIGHT : SURFACE2)
    .border({ width: 1, color: this.isActiveBranch(branch.id) ? ACCENT : LINE }).borderRadius(12)
    .opacity(this.switchBusy ? 0.5 : 1).margin({ bottom: 8 })
    .onClick((): void => {
      if (!this.switchBusy && !this.isActiveBranch(branch.id)) this.onSelect(branch.id);
    })
    .accessibilityText(`${branch.name}，${this.isActiveBranch(branch.id) ? '当前分支' : '切换到此分支'}`)
  }

  @Builder
  private createRow(): void {
    Column() {
      Text('创建分支')
        .fontSize(TEXT_STYLES.eyebrow.size).fontWeight(TEXT_STYLES.eyebrow.weight)
        .lineHeight(TEXT_STYLES.eyebrow.lineHeight).fontFamily(TEXT_STYLES.eyebrow.family)
        .fontColor(INK3).margin({ bottom: 6 })
      Row() {
        TextInput({ text: this.newBranchName, placeholder: '输入分支名称' })
          .height(44).fontSize(BODY).fontColor(this.busy ? INK4 : INK)
          .placeholderColor(INK4).backgroundColor(SURFACE2).borderRadius(10)
          .padding({ left: 12, right: 12 }).layoutWeight(1).enabled(!this.busy)
          .onChange((name: string): void => { this.newBranchName = name; })
          .accessibilityText('新分支名称')
        Text('创建')
          .fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
          .fontColor(this.canCreate() ? ACCENT_INK : INK4).textAlign(TextAlign.Center)
          .width(64).height(44).backgroundColor(this.canCreate() ? ACCENT : SURFACE2).borderRadius(10)
          .margin({ left: 8 }).opacity(this.busy ? 0.5 : 1)
          .onClick((): void => { this.createBranch(); })
          .accessibilityText('创建分支')
      }
      .width('100%')
    }
    .width('100%').margin({ bottom: 14 })
  }

  @Builder
  private historyControls(): void {
    Column() {
      this.controlRow('历史与分支管理', '查看检查点、分支改名、调整主线。', '打开', false, (): void => {
        this.onOpenHistory();
      })
      if (this.canUndo) {
        this.controlRow('撤销最近变更', '恢复到上一个工作区快照。', '撤销', false, (): void => {
          this.onUndo();
        })
      }
      if (this.plotStale) {
        this.controlRow('同步剧情摘要', '章节已变化，当前剧情摘要需要更新。', '同步', false, (): void => {
          this.onSyncPlot();
        })
      }
      if (this.unresolvedFromOrdinal !== null) {
        this.controlRow(`处理第 ${this.unresolvedFromOrdinal} 章后的影响`, '确认后续剧情已按本次变更处理。', '标记已处理', true, (): void => {
          this.onResolveUnresolved();
        })
      }
    }
    .width('100%')
  }

  @Builder
  private controlRow(title: string, detail: string, actionLabel: string, warning: boolean, action: () => void): void {
    Row() {
      Column() {
        Text(title).fontSize(TEXT_STYLES.secondary.size).fontWeight(FontWeight.Medium)
          .fontColor(warning ? ERROR : INK).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(detail).fontSize(TEXT_STYLES.meta.size).fontColor(INK3)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Text(actionLabel)
        .fontSize(TEXT_STYLES.meta.size).fontWeight(FontWeight.Medium)
        .fontColor(this.busy ? INK4 : ACCENT).textAlign(TextAlign.Center)
        .width(88).height(44).backgroundColor(this.busy ? SURFACE2 : RAISED)
        .border({ width: 1, color: this.busy ? LINE : ACCENT }).borderRadius(10)
        .margin({ left: 12 }).opacity(this.busy ? 0.5 : 1)
        .onClick((): void => { if (!this.busy) action(); })
        .accessibilityText(actionLabel)
    }
    .width('100%').constraintSize({ minHeight: 60 })
    .padding({ left: 2, right: 2, top: 8, bottom: 8 })
    .border({ width: { top: 0.5 }, color: { top: LINE } })
    .alignItems(VerticalAlign.Center)
  }

  private activeBranches(): NovelBranchSheetBranch[] {
    return this.branches.filter((branch: NovelBranchSheetBranch): boolean => branch.lifecycle === 'active');
  }

  private isActiveBranch(branchId: string): boolean {
    return branchId === this.activeBranchId;
  }

  private branchListHeight(): number {
    const branchCount: number = this.activeBranches().length;
    return Math.min(216, Math.max(72, branchCount * 80));
  }

  private canCreate(): boolean {
    return !this.busy && this.newBranchName.trim().length > 0;
  }

  private createBranch(): void {
    if (!this.canCreate()) return;
    const name: string = this.newBranchName.trim();
    this.newBranchName = '';
    this.onCreate(name);
  }
}

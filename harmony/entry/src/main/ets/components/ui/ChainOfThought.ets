// ChainOfThought — 思维链折叠容器(ui/ChainOfThought.kt:600 行)
// Android 基准: 泛型 <T> step 列表 + 时间线竖线 + 可展开/折叠
//   collapsedVisibleCount: 折叠时显示最后 N 步
//   每步: icon + label + extra + 可展开 content
//
// ArkUI 差异:
//   - 无 drawBehind → 时间线用一个 1dp 宽 Column 背景色实现
//   - 无 DSL scope → step renderer 由 @BuilderParam 传入
//   - AnimatedVisibility → if(expanded) 条件渲染(无动画,登记设备验证)
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';

export interface CotStep {
  id: string;
  label: string;
  icon: string; // 文字图标(对齐 ChatMessageBranch ◀▶ 惯例)
}

@Component
export struct ChainOfThought {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop steps: CotStep[] = [];
  @Prop collapsedVisibleCount: number = 2;
  @Prop title: string = '思维链';
  @State expanded: boolean = false;
  @State expandedStepId: string = '';
  @BuilderParam stepContent: (step: CotStep) => void;

  private canCollapse(): boolean {
    return this.steps.length > this.collapsedVisibleCount;
  }

  private visibleSteps(): CotStep[] {
    if (!this.canCollapse() || this.expanded) return this.steps;
    return this.steps.slice(this.steps.length - this.collapsedVisibleCount);
  }

  build(): void {
    Column() {
      // 折叠头部
      if (this.canCollapse()) {
        Row() {
          Text(this.expanded ? '▾' : '▸').fontSize(META).fontColor(ACCENT)
          Text(` ${this.title}`).fontSize(META).fontColor(INK2)
          Blank()
          Text(this.expanded ? '收起' : `展开全部 (${this.steps.length})`)
            .fontSize(LABEL).fontColor(INK3)
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .onClick((): void => { this.expanded = !this.expanded; })
      }

      // step 列表(带时间线)
      Row() {
        // 时间线竖线(需显式高度才能渲染背景色)
        Column()
          .width(2)
          .height('100%')
          .backgroundColor(LINE)
          .margin({ left: 11 })

        Column() {
          ForEach(this.visibleSteps(), (step: CotStep): void => {
            this.StepRow(step)
          }, (step: CotStep): string => step.id)
        }
        .layoutWeight(1)
        .padding({ left: 8 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Top)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
  }

  @Builder StepRow(step: CotStep): void {
    Row() {
      // icon(覆盖在时间线上)
      Text(step.icon.length > 0 ? step.icon : '●')
        .fontSize(META).fontColor(ACCENT)
        .width(24).height(24)
        .textAlign(TextAlign.Center)
        .backgroundColor(SURFACE) // 打穿时间线(对齐 Android LocalCardColor mask)
        .borderRadius(12)

      Text(step.label)
        .fontSize(BODY).fontColor(INK2).layoutWeight(1)
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ left: 6 })
    }
    .width('100%')
    .padding({ top: 4, bottom: 4 })
    .onClick((): void => {
      this.expandedStepId = this.expandedStepId === step.id ? '' : step.id;
    })

    // 可展开内容
    if (this.expandedStepId === step.id) {
      Column() {
        this.stepContent(step);
      }
      .width('100%')
      .padding({ left: 30, bottom: 8 })
    }
  }
}

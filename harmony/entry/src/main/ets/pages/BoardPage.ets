// BoardPage — 今日看板(G7 杂志风重构)
//
// 结构: PageHeader(← + 今日看板 + 🕐/⚙) + BoardTabs(大看板/任务流) + 内容
//   大看板: RubricHead + LeadStory(rank 1) + IndexRow(rank 2-7) + Footer
//   任务流: TaskRow(状态点 + 标题 + 时间 + ›)
// 点击条目 → router.pushUrl 到 DeepReadArticlePage
// 设计来源: Amber Redesign BoardScreen / oc-amber.css

import { router } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, SIGNAL, ERROR,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
} from '../design/tokens.ets';

// ── 数据模型 ──
interface BoardStory {
  id: string;
  rank: number;
  title: string;
  dek: string;
  meta: string;
}
interface TaskItem {
  id: string;
  status: string; // 'run' | 'done' | 'idle'
  title: string;
  when: string;
}

function makeStory(id: string, rank: number, title: string, dek: string, meta: string): BoardStory {
  return { id: id, rank: rank, title: title, dek: dek, meta: meta };
}
function makeTask(id: string, status: string, title: string, when: string): TaskItem {
  return { id: id, status: status, title: title, when: when };
}

// 占位数据(7 条热点 + 5 条任务流)
function placeholderStories(): BoardStory[] {
  const list: BoardStory[] = [
    makeStory('b-1', 1, '国产大模型集体降价,API 价格战再升级',
      '多家厂商同步下调推理价格,开发者侧成本进一步压缩,端侧与云端的取舍重新变得微妙。',
      '财联社 · 8 分钟前'),
    makeStory('b-2', 2, '鸿蒙 NEXT 5.0 发布,纯血原生应用破万', '', '36氪 · 22 分钟前'),
    makeStory('b-3', 3, 'AI 编程助手效率提升实测对比', '', '极客公园 · 1 小时前'),
    makeStory('b-4', 4, '端侧小模型部署最佳实践总结', '', '掘金 · 2 小时前'),
    makeStory('b-5', 5, '开源向量数据库年度横评', '', 'InfoQ · 3 小时前'),
    makeStory('b-6', 6, '智能驾驶城市领航落地进展', '', '晚点 · 5 小时前'),
    makeStory('b-7', 7, '量子计算新突破:误差率显著下降', '', 'PaperWeekly · 8 小时前'),
  ];
  return list;
}
function placeholderTasks(): TaskItem[] {
  const list: TaskItem[] = [
    makeTask('t-1', 'run', '整理本周会议纪要并生成摘要', '运行中 · 已处理 3/5'),
    makeTask('t-2', 'done', '翻译产品需求文档为英文', '今天 09:12 完成'),
    makeTask('t-3', 'run', '审查 pull request #142 代码质量', '运行中 · 静态分析'),
    makeTask('t-4', 'idle', '起草季度复盘邮件', '待启动'),
    makeTask('t-5', 'done', '生成思维导图:产品路线图', '昨天 18:40 完成'),
  ];
  return list;
}

@Entry
@Component
struct BoardPage {
  @State stories: BoardStory[] = [];
  @State tasks: TaskItem[] = [];
  @State tab: number = 0; // 0=大看板 1=任务流

  aboutToAppear(): void {
    this.stories = placeholderStories();
    this.tasks = placeholderTasks();
  }

  private openArticle(story: BoardStory): void {
    const params: Record<string, string> = {};
    params['topicId'] = story.id;
    params['title'] = story.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  private openTask(task: TaskItem): void {
    const params: Record<string, string> = {};
    params['topicId'] = task.id;
    params['title'] = task.title;
    router.pushUrl({ url: 'pages/DeepReadArticlePage', params: params });
  }

  // 状态点颜色: run=ACCENT / done=SIGNAL / idle=INK4
  private statusColor(status: string): string {
    if (status === 'run') return ACCENT;
    if (status === 'done') return SIGNAL;
    return INK4;
  }

  @Builder
  pageHeader() {
    Row() {
      Text('←')
        .fontSize(24)
        .fontColor(INK)
        .onClick((): void => { router.back(); })
      Text('今日看板')
        .fontSize(18.5)
        .fontWeight(700)
        .fontColor(INK)
        .layoutWeight(1)
        .margin({ left: 8 })
      Text('🕐')
        .fontSize(21)
        .fontColor(INK2)
      Text('⚙')
        .fontSize(21)
        .fontColor(INK2)
        .margin({ left: 14 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 2, bottom: 11 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }

  @Builder
  boardTabs() {
    Row() {
      // 大看板
      Column() {
        Text('大看板')
          .fontSize(16)
          .fontWeight(this.tab === 0 ? 700 : 500)
          .fontColor(this.tab === 0 ? INK : INK3)
          .margin({ top: 13, bottom: 12 })
        Column()
          .width(40)
          .height(2.5)
          .backgroundColor(this.tab === 0 ? ACCENT : '#00000000')
      }
      .layoutWeight(1)
      .onClick((): void => { this.tab = 0; })

      // 任务流
      Column() {
        Text('任务流')
          .fontSize(16)
          .fontWeight(this.tab === 1 ? 700 : 500)
          .fontColor(this.tab === 1 ? INK : INK3)
          .margin({ top: 13, bottom: 12 })
        Column()
          .width(40)
          .height(2.5)
          .backgroundColor(this.tab === 1 ? ACCENT : '#00000000')
      }
      .layoutWeight(1)
      .onClick((): void => { this.tab = 1; })
    }
    .width('100%')
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }

  // RubricHead: // + 综合热点 + 刚刚更新(带信号点)
  @Builder
  rubricHead() {
    Row() {
      Text('//')
        .fontSize(11.5)
        .fontWeight(600)
        .fontColor(ACCENT)
        .fontFamily('monospace')
      Text(' 综合热点')
        .fontSize(11.5)
        .fontWeight(600)
        .fontColor(INK2)
        .fontFamily('monospace')
      Blank()
      Circle({ width: 7, height: 7 })
        .fill(SIGNAL)
      Text('刚刚更新')
        .fontSize(11)
        .fontColor(INK3)
        .fontFamily('monospace')
        .margin({ left: 6 })
    }
    .width('100%')
    .padding({ left: 2, right: 2, top: 2, bottom: 0 })
  }

  // LeadStory(rank 1): 大号排名 + 标题 + dek + meta
  @Builder
  leadStory(story: BoardStory) {
    Column() {
      Row() {
        Text('01')
          .fontSize(26)
          .fontWeight(600)
          .fontColor(ACCENT)
          .fontFamily('monospace')
        Column() {
          Text(story.title)
            .fontSize(20)
            .fontWeight(700)
            .fontColor(INK)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(story.dek)
            .fontSize(14)
            .fontColor(INK2)
            .margin({ top: 9 })
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(story.meta)
            .fontSize(11.5)
            .fontColor(INK3)
            .fontFamily('monospace')
            .margin({ top: 9 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 13 })
      }
      .width('100%')
    }
    .width('100%')
    .padding({ left: 0, right: 0, top: 16, bottom: 18 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .onClick((): void => { this.openArticle(story); })
  }

  // IndexRow(rank 2+): 排名 + 标题 + meta
  @Builder
  indexRow(story: BoardStory, last: boolean) {
    Row() {
      Text(story.rank < 10 ? '0' + story.rank.toString() : story.rank.toString())
        .fontSize(15)
        .fontWeight(500)
        .fontColor(INK4)
        .fontFamily('monospace')
        .width(22)
      Column() {
        Text(story.title)
          .fontSize(16)
          .fontWeight(600)
          .fontColor(INK)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(story.meta)
          .fontSize(11)
          .fontColor(INK3)
          .fontFamily('monospace')
          .margin({ top: 7 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 13 })
    }
    .width('100%')
    .padding({ left: 0, right: 0, top: 14, bottom: 14 })
    .border({ width: { bottom: last ? 0 : 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.openArticle(story); })
  }

  @Builder
  boardFooter() {
    Row() {
      Blank()
      Text('· 已到底 · 共 7 条 ·')
        .fontSize(11)
        .fontColor(INK4)
        .fontFamily('monospace')
      Blank()
    }
    .width('100%')
    .padding({ top: 26, bottom: 4 })
  }

  // TaskRow: 状态点 + 标题 + 时间 + ›
  @Builder
  taskRow(task: TaskItem, last: boolean) {
    Row() {
      Circle({ width: 7, height: 7 })
        .fill(this.statusColor(task.status))
      Column() {
        Text(task.title)
          .fontSize(15)
          .fontWeight(500)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(task.when)
          .fontSize(11)
          .fontColor(INK3)
          .fontFamily('monospace')
          .margin({ top: 5 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 12 })
      Text('›')
        .fontSize(16)
        .fontColor(INK4)
    }
    .width('100%')
    .padding({ left: 0, right: 0, top: 15, bottom: 15 })
    .border({ width: { bottom: last ? 0 : 1 }, color: { bottom: LINE } })
    .onClick((): void => { this.openTask(task); })
  }

  build(): void {
    Column() {
      this.pageHeader()
      this.boardTabs()

      if (this.tab === 0) {
        // ── 大看板 ──
        Scroll() {
          Column() {
            this.rubricHead()
            ForEach(this.stories, (story: BoardStory, index: number): void => {
              if (story.rank === 1) {
                this.leadStory(story)
              } else {
                this.indexRow(story, index === this.stories.length - 1)
              }
            }, (story: BoardStory): string => story.id)
            this.boardFooter()
          }
          .width('100%')
        }
        .layoutWeight(1)
        .padding({ left: 18, right: 18, top: 0, bottom: 28 })
        .backgroundColor(BG)
      } else {
        // ── 任务流 ──
        Scroll() {
          Column() {
            this.rubricHead()
            ForEach(this.tasks, (task: TaskItem, index: number): void => {
              this.taskRow(task, index === this.tasks.length - 1)
            }, (task: TaskItem): string => task.id)
          }
          .width('100%')
        }
        .layoutWeight(1)
        .padding({ left: 18, right: 18, top: 0, bottom: 28 })
        .backgroundColor(BG)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }
}

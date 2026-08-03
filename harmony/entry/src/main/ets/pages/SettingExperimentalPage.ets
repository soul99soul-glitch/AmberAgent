// SettingExperimentalPage — 实验性功能入口页
// 列出所有实验性功能入口,点击进入对应子页
// Android 基准: feature/ui/pages/setting/SettingExperimentalPage.kt
import { router } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

interface ExperimentalEntry {
  key: string;
  icon: string;
  title: string;
  desc: string;
  page: string;
  badge: string;
}

function makeEntry(key: string, icon: string, title: string, desc: string, page: string, badge: string): ExperimentalEntry {
  return { key: key, icon: icon, title: title, desc: desc, page: page, badge: badge };
}

function placeholderLoadEntries(): Promise<ExperimentalEntry[]> {
  const seed: ExperimentalEntry[] = [
    makeEntry('sub-agent', '◐', '子代理', '拆分任务到独立子代理并行执行', 'pages/SettingSubAgentPage', 'Beta'),
    makeEntry('model-council', '◇', '模型议会', '多模型协同投票,提升复杂问题质量', 'pages/SettingModelCouncilPage', 'Beta'),
    makeEntry('web-mount', '⊕', '网页挂载', '将网页内容挂载为上下文,实时引用', 'pages/SettingExperimentalWebMountPage', 'Alpha'),
    makeEntry('office-pro', '▣', 'Office Pro', '文档/表格/幻灯片深度编辑能力', 'pages/SettingExperimentalOfficeProPage', 'Alpha'),
    makeEntry('cron-tasks', '◷', '定时任务', '按计划自动执行代理任务', 'pages/SettingCronTasksPage', 'Beta'),
  ];
  return Promise.resolve(seed);
}

@Entry
@Component
struct SettingExperimentalPage {
  @State entries: ExperimentalEntry[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    placeholderLoadEntries().then((list: ExperimentalEntry[]): void => {
      this.entries = list;
      this.loaded = true;
    });
  }

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder entryRow(e: ExperimentalEntry): void {
    Row() {
      Text(e.icon).fontSize(TITLE).fontColor(ACCENT).width(28)
      Column() {
        Text(e.title).fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
        Text(e.desc).fontSize(META).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 12 })

      Text(e.badge).fontSize(LABEL).fontColor(ACCENT)
        .padding({ left: 6, right: 6, top: 2, bottom: 2 })
        .backgroundColor(SURFACE2).borderRadius(50)
        .margin({ right: 8 })

      Text('›').fontSize(BODY).fontColor(INK4)
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
    .onClick((): void => { this.go(e.page); })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('实验性功能').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            this.sectionHeader('功能列表')

            // 提示横幅
            Row() {
              Text('⚠').fontSize(META).fontColor(ACCENT)
              Text(' 以下功能处于实验阶段,可能不稳定或变更。')
                .fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, right: 16, bottom: 10 })

            Column() {
              ForEach(this.entries, (e: ExperimentalEntry): void => {
                this.entryRow(e)
              }, (e: ExperimentalEntry): string => e.key)
            }
            .width('100%').backgroundColor(SURFACE).border({ width: 1, color: LINE })

            this.sectionHeader('说明')

            Column() {
              Text('Alpha').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
              Text('早期原型,功能可能不完整或随时移除。')
                .fontSize(META).fontColor(INK3).margin({ top: 2 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 10, bottom: 10 })
            .backgroundColor(SURFACE).border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            Column() {
              Text('Beta').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
              Text('功能相对稳定,但仍在迭代优化中。')
                .fontSize(META).fontColor(INK3).margin({ top: 2 })
            }
            .width('100%').alignItems(HorizontalAlign.Start)
            .padding({ left: 16, right: 16, top: 10, bottom: 10 })
            .backgroundColor(SURFACE)

            Column() {}.height(40)
          }
          .width('100%').padding({ left: 16, right: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

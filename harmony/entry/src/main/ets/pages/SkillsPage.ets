// SkillsPage — 技能管理(接 SkillManager 真实后端)
//
// Android 基准: pages/extensions/SkillsPage.kt — 列表/启停/删除。
//   鸿蒙接 chat-domain SkillManager(AppContainer.getSkillManager,
//   filesDir/skills 根 + 内置 rawfile 资产);启停 = 当前 assistant.enabledSkills
//   经 patchAssistant/saveAssistants 持久化(对齐 SkillManager.kt setSkillEnabled)。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1987「技能」):仅换视觉骨架
//   (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardRow/StatusChip/FooterNote),
//   列表加载/启停/删除/子入口跳转逻辑零改动。iOS 顶栏右侧「+」为新建技能动作,
//   鸿蒙端无对应功能,不补(铁律:功能零增删)。

import { router } from '@kit.ArkUI';
import type { Assistant, SkillMetadata } from '@amber/chat-domain';
import { saveAssistants, patchAssistant } from '@amber/chat-domain';
import { getSkillManager, getChatAssistants, getChatKvStore } from '../di/AppContainer.ets';
import {
  BG, INK, INK3, LINE, LINE2, ACCENT, ERROR, GUTTER, lineHeightForSize,
} from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, StatusChip, FooterNote, PageSpacer,
} from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';

@Entry
@Component
struct SkillsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State skills: SkillMetadata[] = [];
  @State enabledNames: string[] = [];
  @State loaded: boolean = false;
  private assistant: Assistant | null = null;

  aboutToAppear(): void {
    this.reload();
  }

  private reload(): void {
    getChatAssistants().then((list: Assistant[]): void => {
      this.assistant = list.length > 0 ? list[0] : null;
      this.enabledNames = this.assistant !== null ? this.assistant.enabledSkills : [];
      this.skills = getSkillManager().listSkills();
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private isEnabled(name: string): boolean {
    return this.enabledNames.indexOf(name) >= 0;
  }

  private go(page: string): void {
    router.pushUrl({ url: page });
  }

  private toggle(name: string, on: boolean): void {
    const cur: Assistant | null = this.assistant;
    if (cur === null) return;
    const ids: string[] = on
      ? (this.enabledNames.indexOf(name) >= 0 ? this.enabledNames.slice() : [...this.enabledNames, name])
      : this.enabledNames.filter((x: string): boolean => x !== name);
    // saveAssistants 全量写:patch 进完整列表,不能只存单个
    getChatAssistants().then((all: Assistant[]): void => {
      const next: Assistant[] = [];
      for (const a of all) {
        next.push(a.id === cur.id ? patchAssistant(a, { enabledSkills: ids }) : a);
      }
      saveAssistants(getChatKvStore(), next).then((): void => {
        this.enabledNames = ids;
        this.assistant = patchAssistant(cur, { enabledSkills: ids });
      });
    });
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  // 技能行:iOS 行骨架 = 加粗技能名 + 两行 muted 描述 + 右侧「启用」StatusChip;
  // 启停保留为行尾 accent 药丸开关(同 CardToggleRow 视觉),删除收进状态行。
  @Builder
  SkillItem(s: SkillMetadata, isLast: boolean): void {
    Column() {
      Row() {
        Column() {
          Text(s.name)
            .fontSize(16)
            .fontWeight(600)
            .lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(INK)
            .maxLines(1)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
          Text(s.description.length > 0 ? s.description : '（无描述）')
            .fontSize(12)
            .lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .margin({ top: 2 })
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Row() {
          Stack({ alignContent: Alignment.Start }) {
            Column()
              .width(42).height(24).borderRadius(99)
              .backgroundColor(this.isEnabled(s.name) ? this.accentColorStore : LINE2)
            Column()
              .width(20).height(20).borderRadius(10)
              .backgroundColor(Color.White) // 滑块固定纯白:off 轨上仍需对比
              .margin({ left: this.isEnabled(s.name) ? 22 : 2 })
              .animation({ duration: 150, curve: Curve.EaseOut })
          }
          .clip(true)
        }
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .justifyContent(FlexAlign.End)
        .alignItems(VerticalAlign.Center)
        .margin({ left: 12 })
        .onClick((): void => { this.toggle(s.name, !this.isEnabled(s.name)); })
        .accessibilityText(this.isEnabled(s.name) ? '已开启' : '已关闭')
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })

      Row() {
        StatusChip({ text: this.isEnabled(s.name) ? '启用' : '已禁用',
          tone: this.isEnabled(s.name) ? 'success' : 'muted' })
        Blank()
        Row() {
          Icon({ name: 'trash', iconSize: 15, color: ERROR })
        }
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .margin({ right: -14 })
        .accessibilityText('删除技能')
        .onClick((): void => {
          AlertDialog.show({
            title: '删除技能', message: `删除技能「${s.name}」？`,
            primaryButton: { value: '取消', action: (): void => {} },
            secondaryButton: { value: '删除', fontColor: ERROR, action: (): void => {
              getSkillManager().deleteSkill(s.name);
              this.reload();
            } },
          });
        })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .padding({ left: 14, right: 14, bottom: 8 })

      if (!isLast) {
        // 发丝分隔线:width('100%')+margin 在本布局下不生效,走外层 padding 内缩
        Row() {
          Row()
            .width('100%')
            .height(1)
            .backgroundColor(LINE)
        }
        .width('100%')
        .padding({ left: 14, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  SkillsCard(): void {
    if (this.skills.length === 0) {
      Text('暂无技能')
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .lineHeight(lineHeightForSize(16))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .width('100%')
        .padding({ left: 14, right: 14, top: 14, bottom: 14 })
    } else {
      ForEach(this.skills, (s: SkillMetadata, idx: number): void => {
        this.SkillItem(s, idx === this.skills.length - 1);
      }, (s: SkillMetadata): string => s.name)
    }
  }

  @Builder
  ExtensionsCard(): void {
    CardRow({
      label: 'MCP 服务器',
      desc: '管理外部 MCP 工具',
      iconName: 'plug', plateStyle: 'accent', plateSize: 28,
      onRowClick: (): void => { this.go('pages/ChatMcpSettingsPage'); },
    })
    // F19:移除「Slides 字体资源」入口 —— 该页为私有常量占位,
    //   无存储/无消费者(生成式幻灯片在鸿蒙端未实现),保留入口属误导。
    CardRow({
      label: '定时任务',
      desc: '查看 Agent 的定时任务和计划运行状态',
      valueText: 'Beta',
      iconName: 'clock', plateStyle: 'accent', plateSize: 28,
      isLast: true,
      onRowClick: (): void => { this.go('pages/SettingCronTasksPage'); },
    })
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '技能' })

      Scroll() {
        Column() {
          PlainSection({ label: '本机技能' })
          if (this.loaded) {
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SkillsCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            if (this.skills.length === 0) {
              FooterNote({ text: '内置技能会随首次启动安装；也可在对话中让 Agent 创建' })
            }
          }

          PlainSection({ label: '扩展' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.ExtensionsCard(); } })
            .margin({ left: GUTTER, right: GUTTER })
          PageSpacer()
        }
        .width('100%')
      }
      .layoutWeight(1)
      .align(Alignment.TopStart)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

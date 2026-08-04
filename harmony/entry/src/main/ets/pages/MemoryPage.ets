// MemoryPage — 核心记忆(G7 杂志风重构)
//
// 结构: PageHeader(← + 核心记忆) + Scroll
//   - 自动记忆卡(🧠 + 说明 + Toggle)
//   - 已存 N 条 header + 新增
//   - 记忆列表(文本 + 📌 + 标签 + ✎/🗑)
// 设计来源: Amber Redesign MemoryScreen / oc-amber.css

import { router } from '@kit.ArkUI';
import { promptAction } from '@kit.ArkUI';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, RAISED, LINE, LINE2, BG,
  ACCENT, ACCENT_INK, ACCENT_LIGHT, SIGNAL, ERROR,
  TITLE, HEADER, BODY, BODY2, META, MONO_SMALL, LABEL, TINY,
} from '../design/tokens.ets';

// ── 数据模型 ──
interface MemoryItem {
  id: string;
  text: string;
  tag: string;
  pinned: boolean;
}

function makeMemory(id: string, text: string, tag: string, pinned: boolean): MemoryItem {
  return { id: id, text: text, tag: tag, pinned: pinned };
}

// 5 条占位记忆(偏好/背景/兴趣)
function placeholderMemories(): MemoryItem[] {
  const list: MemoryItem[] = [
    makeMemory('m-1', '偏好中文简洁回复,代码示例用 TypeScript,注释精炼。', '偏好', true),
    makeMemory('m-2', '正在长期改造 AmberAgent,常用 workspace 为 ~/Downloads/AI/AmberAgent-harmony-preview。', '背景', true),
    makeMemory('m-3', '关注鸿蒙 ArkTS / ArkUI 生态,优先阅读中文技术资料。', '兴趣', false),
    makeMemory('m-4', '喜欢杂志风排版,偏好暖中性石墨配色与等宽元信息。', '偏好', false),
    makeMemory('m-5', '正在研究端侧小模型部署,常用设备为 macOS arm64。', '背景', false),
  ];
  return list;
}

@Entry
@Component
struct MemoryPage {
  @State memories: MemoryItem[] = [];
  @State autoMemory: boolean = true;

  aboutToAppear(): void {
    this.memories = placeholderMemories();
  }

  private toast(message: string): void {
    promptAction.showToast({ message: message, duration: 2000 });
  }

  private togglePin(item: MemoryItem): void {
    const next: MemoryItem[] = this.memories.map((m: MemoryItem): MemoryItem => {
      if (m.id === item.id) {
        return { id: m.id, text: m.text, tag: m.tag, pinned: !m.pinned };
      }
      return m;
    });
    this.memories = next;
  }

  private editMemory(item: MemoryItem): void {
    this.toast('编辑记忆:' + item.text.substring(0, 12));
  }

  private deleteMemory(item: MemoryItem): void {
    AlertDialog.show({
      title: '确认删除',
      message: item.text,
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '确认',
        action: (): void => {
          this.memories = this.memories.filter((m: MemoryItem): boolean => m.id !== item.id);
        },
      },
    });
  }

  private addMemory(): void {
    this.toast('新增记忆(占位)');
  }

  @Builder
  pageHeader() {
    Row() {
      Text('←')
        .fontSize(24)
        .fontColor(INK)
        .onClick((): void => { router.back(); })
      Text('核心记忆')
        .fontSize(18.5)
        .fontWeight(700)
        .fontColor(INK)
        .layoutWeight(1)
        .margin({ left: 8 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 2, bottom: 11 })
    .border({ width: { bottom: 1 }, color: { bottom: LINE } })
    .backgroundColor(BG)
  }

  // 自动记忆卡: 🧠 + 标题 + 说明 + Toggle
  @Builder
  autoCard() {
    Row() {
      Text('🧠')
        .fontSize(20)
        .fontColor(ACCENT)
      Column() {
        Text('自动记忆')
          .fontSize(14.5)
          .fontWeight(600)
          .fontColor(INK)
        Text('Amber 会记住对话里值得留存的信息')
          .fontSize(12)
          .fontColor(INK3)
          .margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 13 })
      Toggle({ type: ToggleType.Switch, isOn: this.autoMemory })
        .onChange((v: boolean): void => { this.autoMemory = v; })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(14)
    .margin({ top: 8 })
  }

  // 已存 N 条 header + 新增
  @Builder
  sectionHeader() {
    Row() {
      Text('// 已存 ' + this.memories.length.toString() + ' 条')
        .fontSize(11)
        .fontWeight(600)
        .fontColor(INK3)
        .fontFamily('monospace')
      Blank()
      Row() {
        Text('+')
          .fontSize(13)
          .fontColor(ACCENT)
        Text('新增')
          .fontSize(13)
          .fontWeight(600)
          .fontColor(ACCENT)
          .margin({ left: 3 })
      }
      .onClick((): void => { this.addMemory(); })
    }
    .width('100%')
    .padding({ left: 6, right: 6, top: 24, bottom: 10 })
  }

  // 单条记忆卡: 文本 + 📌 + 标签 + ✎/🗑
  @Builder
  memoryCard(item: MemoryItem) {
    Column() {
      Row() {
        Text(item.text)
          .fontSize(14)
          .fontColor(INK)
          .lineHeight(21)
          .layoutWeight(1)
        Text('📌')
          .fontSize(14)
          .fontColor(item.pinned ? ACCENT : INK4)
          .margin({ left: 10 })
          .onClick((): void => { this.togglePin(item); })
      }
      .width('100%')

      Row() {
        Text(item.tag)
          .fontSize(10.5)
          .fontWeight(600)
          .fontColor(INK3)
          .fontFamily('monospace')
          .backgroundColor(SURFACE2)
          .padding({ left: 7, right: 7, top: 2, bottom: 2 })
          .borderRadius(5)
        Blank()
        Text('✎')
          .fontSize(15)
          .fontColor(INK4)
          .onClick((): void => { this.editMemory(item); })
        Text('🗑')
          .fontSize(15)
          .fontColor(INK4)
          .margin({ left: 8 })
          .onClick((): void => { this.deleteMemory(item); })
      }
      .width('100%')
      .margin({ top: 9 })
    }
    .width('100%')
    .padding({ left: 15, right: 15, top: 13, bottom: 13 })
    .backgroundColor(SURFACE)
    .border({ width: 1, color: LINE })
    .borderRadius(14)
  }

  build(): void {
    Column() {
      this.pageHeader()
      Scroll() {
        Column() {
          this.autoCard()
          this.sectionHeader()
          Column({ space: 10 }) {
            ForEach(this.memories, (item: MemoryItem): void => {
              this.memoryCard(item)
            }, (item: MemoryItem): string => item.id)
          }
          .width('100%')
        }
        .width('100%')
      }
      .layoutWeight(1)
      .padding({ left: 18, right: 18, top: 0, bottom: 28 })
      .backgroundColor(BG)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(BG)
  }
}

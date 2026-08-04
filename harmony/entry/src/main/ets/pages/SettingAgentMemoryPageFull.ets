// SettingAgentMemoryPageFull — 全量记忆设置页(G5-S4a)
// 本页为设置面自治态(不绑 Assistant):全局记忆策略 / 回写 / 梦境 / 列表 / 导入导出
// 持久化:占位本地草稿(后续接入 chat-domain MemoryStore 后替换)
// 依赖:AmberSwitch / PillSelect / FlatTextField / NotionSlider / pasteboard
import { router } from '@kit.ArkUI';
import { pasteboard } from '@kit.BasicServicesKit';
import { promptAction } from '@kit.ArkUI';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';

interface MemoryRecord {
  id: string;
  text: string;
  createdAt: number;
}

function makeRecord(id: string, text: string, createdAt: number): MemoryRecord {
  return { id: id, text: text, createdAt: createdAt };
}

function fmtTime(ts: number): string {
  const d: Date = new Date(ts);
  const y: number = d.getFullYear();
  const m: number = d.getMonth() + 1;
  const day: number = d.getDate();
  const hh: number = d.getHours();
  const mm: number = d.getMinutes();
  const mm2: string = mm < 10 ? `0${mm}` : `${mm}`;
  return `${y}-${m}-${day} ${hh}:${mm2}`;
}

@Entry
@Component
struct SettingAgentMemoryPageFull {
  @State loaded: boolean = false;
  // 总开关 + 策略
  @State enable: boolean = true;
  @State strategyIndex: number = 0; // 0 recall / 1 write / 2 dream
  @State maxMemories: string = '200';
  // 回写
  @State autoWrite: boolean = true;
  @State writeInterval: string = '3'; // 分钟
  // 梦境
  @State autoDream: boolean = false;
  @State dreamInterval: string = '30'; // 分钟
  @State dreamModelIndex: number = 0;
  // 回忆相似度阈值(0~1) + 最大结果数
  @State similarity: number = 0.6;
  @State maxResults: string = '8';
  // 列表
  @State memories: MemoryRecord[] = [];
  // UI
  @State savedMsg: string = '';
  private counter: number = 0;
  private strategies: string[] = ['回忆 (recall)', '写入 (write)', '梦境 (dream)'];
  private dreamModels: string[] = ['gpt-4o-mini', 'glm-4-flash', 'qwen2-1.5b'];

  aboutToAppear(): void {
    // 占位种子
    const now: number = Date.now();
    this.memories = [
      makeRecord('m-1', '用户偏好简洁回复,避免冗长寒暄。', now - 86400000),
      makeRecord('m-2', '当前项目使用 TypeScript + ArkTS 双端架构。', now - 3600000),
      makeRecord('m-3', '周末通常不工作,工作日 10:00-19:00 在线。', now - 7200000),
    ];
    this.counter = 3;
    this.loaded = true;
  }

  private nextId(): string {
    this.counter += 1;
    return `m-${this.counter}`;
  }

  private addMemory(): void {
    const id: string = this.nextId();
    this.memories = [...this.memories, makeRecord(id, '新建记忆,点击编辑内容…', Date.now())];
  }

  private deleteMemory(id: string): void {
    this.memories = this.memories.filter((m: MemoryRecord): boolean => m.id !== id);
  }

  private editMemoryText(id: string, text: string): void {
    this.memories = this.memories.map((m: MemoryRecord): MemoryRecord => {
      if (m.id !== id) return m;
      return makeRecord(m.id, text, m.createdAt);
    });
  }

  private save(): void {
    // 占位保存:接入 MemoryStore 后替换
    this.savedMsg = '已保存';
    setTimeout((): void => { this.savedMsg = ''; }, 1500);
  }

  private done(): void {
    this.save();
    router.back();
  }

  private exportToClipboard(): void {
    let md: string = '';
    md += `# 记忆导出\n`;
    md += `> 导出时间: ${fmtTime(Date.now())}\n`;
    md += `> 条目数: ${this.memories.length}\n\n---\n\n`;
    this.memories.forEach((m: MemoryRecord): void => {
      md += `- [${fmtTime(m.createdAt)}] ${m.text}\n`;
    });
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, md));
    promptAction.showToast({ message: `已复制 ${this.memories.length} 条记忆到剪贴板`, duration: 2000 });
  }

  private importFromClipboard(): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    if (!board.hasDataSync()) {
      promptAction.showToast({ message: '剪贴板为空', duration: 2000 });
      return;
    }
    const raw: string = board.getDataSync().getPrimaryText() ?? '';
    if (raw.length === 0) {
      promptAction.showToast({ message: '剪贴板无文本', duration: 2000 });
      return;
    }
    // 行格式:"- [时间] 内容" 简单解析,忽略不匹配行
    const lines: string[] = raw.split('\n');
    const next: MemoryRecord[] = [];
    lines.forEach((ln: string): void => {
      const t: string = ln.trim();
      if (t.startsWith('- [') && t.indexOf('] ') > 0) {
        const close: number = t.indexOf('] ');
        const content: string = t.substring(close + 2);
        if (content.length > 0) {
          this.counter += 1;
          next.push(makeRecord(`m-${this.counter}`, content, Date.now()));
        }
      }
    });
    if (next.length === 0) {
      promptAction.showToast({ message: '未识别到记忆行("- [时间] 内容")', duration: 2500 });
      return;
    }
    this.memories = [...this.memories, ...next];
    promptAction.showToast({ message: `已导入 ${next.length} 条记忆`, duration: 2000 });
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  @Builder switchRow(label: string, desc: string, checked: boolean, onToggle: () => void): void {
    Row() {
      Column() {
        Text(label).fontSize(BODY).fontColor(INK)
        Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      AmberSwitch({
        checked: checked,
        switchSize: SwitchSize.Small,
        onCheckedChange: (_c: boolean): void => { onToggle(); },
      })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏:返回 + 标题 + 完成
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('记忆设置').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text('完成').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.done(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // 总开关
            this.switchRow('启用记忆', '跨会话记住关键信息并按策略调度', this.enable,
              (): void => { this.enable = !this.enable; })

            // 策略
            this.sectionHeader('策略')
            Row() {
              Text('记忆策略').fontSize(BODY).fontColor(INK).layoutWeight(1)
              PillSelect({
                options: this.strategies,
                selectedIndex: this.strategyIndex,
                onSelect: (i: number): void => { this.strategyIndex = i; },
              })
            }
            .width('100%').padding({ left: 14, right: 14, top: 10, bottom: 10 })
            .backgroundColor(SURFACE).borderRadius(12).border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16 })

            Column() {
              Text('最大记忆条数').fontSize(BODY).fontColor(INK)
              TextInput({ text: this.maxMemories, placeholder: '如 200' })
                .fontSize(BODY).width('100%').height(40).margin({ top: 8 })
                .backgroundColor(SURFACE2).borderRadius(8)
                .type(InputType.Number)
                .onChange((v: string): void => { this.maxMemories = v; })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            // 回忆参数
            this.sectionHeader('回忆 (recall)')
            Column() {
              Row() {
                Text('相似度阈值').fontSize(BODY).fontColor(INK).layoutWeight(1)
                Text(`${Math.round(this.similarity * 100)}%`).fontSize(META).fontColor(ACCENT)
              }
              .width('100%').margin({ bottom: 6 })
              NotionSlider({
                value: this.similarity,
                min: 0,
                max: 1,
                step: 0.05,
                labelMode: '',
                onChange: (v: number): void => { this.similarity = v; },
              })
              Row() {
                Text('最大返回条数').fontSize(BODY).fontColor(INK).layoutWeight(1)
                TextInput({ text: this.maxResults, placeholder: '如 8' })
                  .fontSize(META).width(70).height(36)
                  .backgroundColor(SURFACE2).borderRadius(8)
                  .textAlign(TextAlign.End)
                  .type(InputType.Number)
                  .onChange((v: string): void => { this.maxResults = v; })
              }
              .width('100%').margin({ top: 10 })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            // 回写参数
            this.sectionHeader('写入 (write)')
            this.switchRow('自动写入', '对话结束时自动提炼并写入记忆', this.autoWrite,
              (): void => { this.autoWrite = !this.autoWrite; })
            Column() {
              Text('写入间隔(分钟)').fontSize(BODY).fontColor(INK)
              TextInput({ text: this.writeInterval, placeholder: '如 3' })
                .fontSize(BODY).width('100%').height(40).margin({ top: 8 })
                .backgroundColor(SURFACE2).borderRadius(8)
                .type(InputType.Number)
                .onChange((v: string): void => { this.writeInterval = v; })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            // 梦境参数
            this.sectionHeader('梦境 (dream)')
            this.switchRow('自动梦境', '空闲时合并 / 修剪 / 摘要旧记忆', this.autoDream,
              (): void => { this.autoDream = !this.autoDream; })
            Column() {
              Row() {
                Text('梦境间隔(分钟)').fontSize(BODY).fontColor(INK).layoutWeight(1)
                TextInput({ text: this.dreamInterval, placeholder: '如 30' })
                  .fontSize(META).width(70).height(36)
                  .backgroundColor(SURFACE2).borderRadius(8)
                  .textAlign(TextAlign.End)
                  .type(InputType.Number)
                  .onChange((v: string): void => { this.dreamInterval = v; })
              }
              .width('100%').margin({ top: 8 })
              Row() {
                Text('梦境模型').fontSize(BODY).fontColor(INK).layoutWeight(1)
                PillSelect({
                  options: this.dreamModels,
                  selectedIndex: this.dreamModelIndex,
                  onSelect: (i: number): void => { this.dreamModelIndex = i; },
                })
              }
              .width('100%').margin({ top: 10 })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            // 记忆列表
            this.sectionHeader('当前记忆')
            if (this.memories.length === 0) {
              Column() {
                Text('暂无记忆').fontSize(BODY).fontColor(INK3)
                Text('点击下方"新建记忆"或从剪贴板导入').fontSize(LABEL).fontColor(INK4).margin({ top: 4 })
              }
              .width('100%').alignItems(HorizontalAlign.Center)
              .padding({ top: 24, bottom: 24 })
            } else {
              ForEach(this.memories, (m: MemoryRecord): void => {
                Column() {
                  Row() {
                    Text(m.text)
                      .fontSize(META).fontColor(INK2).layoutWeight(1)
                      .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text('删除').fontSize(META).fontColor('#C93A2F')
                      .padding({ left: 10 })
                      .onClick((): void => { this.deleteMemory(m.id); })
                  }
                  .width('100%')
                  Text(fmtTime(m.createdAt)).fontSize(LABEL).fontColor(INK4).margin({ top: 6 })
                  TextInput({ text: m.text, placeholder: '记忆内容' })
                    .fontSize(META).width('100%').height(36).margin({ top: 8 })
                    .backgroundColor(SURFACE2).borderRadius(8)
                    .onChange((v: string): void => { this.editMemoryText(m.id, v); })
                }
                .width('100%').alignItems(HorizontalAlign.Start)
                .padding(12).backgroundColor(SURFACE2).borderRadius(10)
                .margin({ left: 16, right: 16, bottom: 8 })
              }, (m: MemoryRecord): string => m.id)
            }

            // 操作行:新建 + 导入 + 导出
            Row() {
              Text('+ 新建记忆').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 14, right: 14, top: 8, bottom: 8 })
                .backgroundColor(ACCENT_LIGHT).borderRadius(50)
                .border({ width: 1, color: ACCENT })
                .onClick((): void => { this.addMemory(); })
              Blank()
              Text('导入').fontSize(META).fontColor(INK2)
                .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .onClick((): void => { this.importFromClipboard(); })
              Text('导出').fontSize(META).fontColor(INK2)
                .padding({ left: 12, right: 12, top: 8, bottom: 8 })
                .backgroundColor(SURFACE2).borderRadius(50)
                .margin({ left: 8 })
                .onClick((): void => { this.exportToClipboard(); })
            }
            .width('100%').margin({ top: 8, bottom: 8 })

            if (this.savedMsg.length > 0) {
              Text(this.savedMsg).fontSize(META).fontColor(ACCENT).margin({ bottom: 8 })
            }
            Column() {}.height(32)
          }
          .width('100%').padding({ left: 0, right: 0 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

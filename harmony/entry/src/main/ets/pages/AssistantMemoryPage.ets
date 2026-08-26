// AssistantMemoryPage — 助手记忆配置
// 真实写回:enableMemory。
// 占位字段(领域暂无对应,P1 登记):记忆策略(strategy)、最大条数
//   (maxMemories)、当前记忆列表(memories)。列表为本地占位态。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { loadAssistants, saveAssistants, patchAssistant } from '@amber/chat-domain';
import type { Assistant } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, GUTTER, RADIUS } from '../design/tokens.ets';
import { PillSelect } from '../components/ui/Select.ets';
import { AmberSwitch, SwitchSize } from '../components/ui/Switch.ets';

interface RouterParams {
  assistantId?: string;
}

interface MemoryItem {
  id: string;
  text: string;
}

@Entry
@Component
struct AssistantMemoryPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State loaded: boolean = false;
  @State editEnable: boolean = false;
  @State editStrategyIndex: number = 0;
  @State editMaxMemories: string = '50';
  @State memories: MemoryItem[] = [];
  @State saving: boolean = false;
  private assistantId: string = '';
  private strategies: string[] = ['回忆 (recall)', '写入 (write)', '梦境 (dream)'];

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined ? params.assistantId : '';
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const list: Assistant[] | null = await loadAssistants(getChatKvStore());
    if (list === null) { this.loaded = true; return; }
    const found: Assistant | null = list.find((a: Assistant): boolean => a.id === this.assistantId) ?? null;
    if (found !== null) {
      this.editEnable = found.enableMemory;
    }
    // 占位记忆(子系统未移植,P1 登记)
    this.memories = [
      { id: 'm1', text: '用户偏好简洁回复,避免冗长寒暄。' },
      { id: 'm2', text: '当前项目使用 TypeScript + ArkTS 双端架构。' },
    ];
    this.loaded = true;
  }

  private removeMemory(id: string): void {
    this.memories = this.memories.filter((m: MemoryItem): boolean => m.id !== id);
  }

  private async save(): Promise<void> {
    if (this.assistantId.length === 0) { router.back(); return; }
    this.saving = true;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const next: Assistant[] = base.map((a: Assistant): Assistant => {
      if (a.id !== this.assistantId) return a;
      return patchAssistant(a, { enableMemory: this.editEnable });
    });
    await saveAssistants(kv, next);
    this.saving = false;
    router.back();
  }

  @Builder sectionHeader(text: string): void {
    Row() {
      Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
      Text(` ${text}`).fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
    }
    .width('100%').padding({ left: 16, top: 20, bottom: 6 })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 })
          .onClick((): void => { router.back(); })
        Text('记忆').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text(this.saving ? '保存中' : '保存').fontSize(BODY).fontColor(ACCENT).maxLines(1)
          .constraintSize({ minHeight: 40 })
          .onClick((): void => { this.save(); })
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else {
        Scroll() {
          Column() {
            // 启用开关
            Row() {
              Column() {
                Text('启用记忆').fontSize(BODY).fontColor(INK)
                Text('助手将跨会话记住关键信息').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              AmberSwitch({
                checked: this.editEnable,
                switchSize: SwitchSize.Small,
                onCheckedChange: (c: boolean): void => { this.editEnable = c; },
              })
            }
            .width('100%').padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            this.sectionHeader('策略')
            Row() {
              Text('记忆策略').fontSize(BODY).fontColor(INK).layoutWeight(1).maxLines(1)
                .textOverflow({ overflow: TextOverflow.Ellipsis })
              PillSelect({
                options: this.strategies, selectedIndex: this.editStrategyIndex,
                onSelect: (i: number): void => { this.editStrategyIndex = i; },
              })
            }
            .padding({ left: 14, right: 14, top: 10, bottom: 10 })
            .backgroundColor(SURFACE).borderRadius(RADIUS).border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16 })

            this.sectionHeader('上限')
            Column() {
              Row() {
                Text('最大记忆条数').fontSize(BODY).fontColor(INK)
                Blank()
                TextInput({ text: this.editMaxMemories, placeholder: '如 50' })
                  .fontSize(BODY).width(90).height(40)
                  .backgroundColor(SURFACE2).borderRadius(8)
                  .textAlign(TextAlign.End)
                  .type(InputType.Number)
                  .onChange((v: string): void => { this.editMaxMemories = v; })
              }
              .width('100%')
            }
            .width('100%').padding({ left: GUTTER, right: GUTTER, top: 12, bottom: 12 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            this.sectionHeader('当前记忆')
            Column() {
              if (this.memories.length === 0) {
                Text('暂无记忆').fontSize(META).fontColor(INK3)
                  .width('100%').padding({ left: 2, top: 8, bottom: 8 })
              }
              ForEach(this.memories, (m: MemoryItem): void => {
                Row() {
                  Text(m.text)
                    .fontSize(META).fontColor(INK2).layoutWeight(1)
                    .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
                  Text('删除').fontSize(META).fontColor('#C93A2F').maxLines(1)
                    .padding({ left: 10 }).constraintSize({ minHeight: 40 })
                    .onClick((): void => this.removeMemory(m.id))
                }
                .width('100%')
                .padding({ left: 12, right: 12, top: 10, bottom: 10 })
                .backgroundColor(SURFACE2).borderRadius(8)
                .margin({ bottom: 6 })
              }, (m: MemoryItem): string => m.id)
            }
            .width('100%').padding({ left: GUTTER, right: GUTTER, bottom: 32 })
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

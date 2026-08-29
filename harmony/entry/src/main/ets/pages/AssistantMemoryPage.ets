// AssistantMemoryPage — 助手记忆配置
// 真实写回:enableMemory;当前记忆 = MemoryStore 真数据(该助手记忆桶,可删)。
// (原「策略/上限」两节为域无对应字段的假 UI,已移除;策略语义由核心记忆页与
//   dream 管线承载)
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { loadAssistants, saveAssistants, patchAssistant } from '@amber/chat-domain';
import type { Assistant, MemoryRecord } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { getMemoryLibraryOps } from '../platform_impl/MemoryStore.ets';
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, GUTTER } from '../design/tokens.ets';
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
  @State memories: MemoryItem[] = [];
  @State saving: boolean = false;
  private assistantId: string = '';

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    this.assistantId = params !== null && params.assistantId !== undefined ? params.assistantId : '';
    this.refresh();
  }

  private async refresh(): Promise<void> {
    const list: Assistant[] | null = await loadAssistants(getChatKvStore());
    if (list !== null) {
      const found: Assistant | null = list.find((a: Assistant): boolean => a.id === this.assistantId) ?? null;
      if (found !== null) {
        this.editEnable = found.enableMemory;
      }
    }
    // 该助手记忆桶真数据
    const records: MemoryRecord[] = await getMemoryLibraryOps().listBucketRecords(this.assistantId);
    this.memories = records.map((r: MemoryRecord): MemoryItem => ({ id: `${r.id}`, text: r.content }));
    this.loaded = true;
  }

  private removeMemory(id: string): void {
    const numeric: number = parseInt(id, 10);
    if (Number.isNaN(numeric)) return;
    getMemoryLibraryOps().deleteMemory(numeric).then((): void => {
      this.memories = this.memories.filter((m: MemoryItem): boolean => m.id !== id);
    }).catch((_e: Error): void => {});
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

// AssistantBackgroundPicker — 助手背景色选择
// 真实写回:background(选中色 hex)/ backgroundOpacity(滑块 0-1)。
// 6 色色板 + 预览区 + 不透明度滑块 + 保存。
import { router } from '@kit.ArkUI';
import { loadAssistants, saveAssistants, patchAssistant } from '@amber/chat-domain';
import type { Assistant } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';

interface RouterParams {
  assistantId?: string;
}

@Entry
@Component
struct AssistantBackgroundPicker {
  @State loaded: boolean = false;
  @State selectedColor: string = '#F4F2EC';
  @State editOpacity: number = 1.0;
  @State saving: boolean = false;
  private assistantId: string = '';
  private swatches: string[] = ['#F4F2EC', '#FAF9F5', '#1B1A17', '#B8623A', '#5E9C6E', '#6B5B95'];

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
      if (found.background !== null && found.background.length > 0) {
        this.selectedColor = found.background;
      }
      this.editOpacity = found.backgroundOpacity;
    }
    this.loaded = true;
  }

  private async save(): Promise<void> {
    if (this.assistantId.length === 0) { router.back(); return; }
    this.saving = true;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const next: Assistant[] = base.map((a: Assistant): Assistant => {
      if (a.id !== this.assistantId) return a;
      return patchAssistant(a, {
        background: this.selectedColor,
        backgroundOpacity: this.editOpacity,
      });
    });
    await saveAssistants(kv, next);
    this.saving = false;
    router.back();
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(TITLE).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('背景').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
        Text(this.saving ? '保存中' : '保存').fontSize(BODY).fontColor(ACCENT)
          .onClick((): void => { this.save(); })
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // 预览区
            Column() {
              Text('预览')
                .fontSize(BODY).fontColor(this.selectedColor === '#1B1A17' ? '#FFFFFF' : INK)
                .padding({ top: 28, bottom: 28 })
            }
            .width('100%')
            .backgroundColor(this.selectedColor)
            .borderRadius(12)
            .border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16, top: 16, bottom: 16 })

            // 色板标签
            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 选择颜色').fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, bottom: 8 })

            // 6 色色板(3 列 × 2 行)
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(this.swatches, (c: string, i: number): void => {
                Stack() {
                  Text('')
                    .width(56).height(56).borderRadius(12)
                    .backgroundColor(c)
                    .border({
                      width: this.selectedColor === c ? 3 : 1,
                      color: this.selectedColor === c ? ACCENT : LINE,
                    })
                }
                .width('30%')
                .margin({ right: '3%', bottom: 12 })
                .onClick((): void => { this.selectedColor = c; })
              }, (c: string, i: number): string => `sw-${i}`)
            }
            .width('100%').padding({ left: 16, right: 16 })

            // 不透明度
            Row() {
              Text('//').fontSize(META).fontColor(ACCENT).fontWeight(FontWeight.Bold)
              Text(' 不透明度').fontSize(META).fontColor(INK3)
            }
            .width('100%').padding({ left: 16, top: 12, bottom: 8 })

            Column() {
              NotionSlider({
                value: this.editOpacity, min: 0, max: 1, step: 0.05, labelMode: 'percent',
                onChange: (v: number): void => { this.editOpacity = v; },
              })
            }
            .width('100%').padding({ left: 16, right: 16, top: 4, bottom: 12 })

            Column() {}.height(16)
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

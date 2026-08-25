// AssistantRequestPage — 请求参数编辑
// 真实写回:temperature / topP / maxTokens / customHeaders(语义同
//   ChatAssistantsPage 编辑器:maxTokens 空白或 ≤0 → null)。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { loadAssistants, saveAssistants, patchAssistant } from '@amber/chat-domain';
import type { Assistant, CustomHeader } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, GUTTER, RADIUS } from '../design/tokens.ets';
import { NotionSlider } from '../components/ui/NotionSlider.ets';
import { FlatTextField } from '../components/ui/FlatTextField.ets';

interface RouterParams {
  assistantId?: string;
}

@Entry
@Component
struct AssistantRequestPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State loaded: boolean = false;
  @State editTemperature: number = 0.7;
  @State editTopP: number = 0.9;
  @State editMaxTokens: string = '';
  @State editHeaders: CustomHeader[] = [];
  @State saving: boolean = false;
  private assistantId: string = '';

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
      this.editTemperature = found.temperature !== null ? found.temperature : 0.7;
      this.editTopP = found.topP !== null ? found.topP : 0.9;
      this.editMaxTokens = found.maxTokens !== null ? String(found.maxTokens) : '';
      this.editHeaders = found.customHeaders.map(
        (h: CustomHeader): CustomHeader => ({ name: h.name, value: h.value }));
    }
    this.loaded = true;
  }

  // Android AssistantBasicPage.kt:496-501 — 空白 → null;≤0 → null
  private parsePositiveIntOrNull(s: string): number | null {
    const t: string = s.trim();
    if (t.length === 0) return null;
    const v: number = Number(t);
    return Number.isFinite(v) && v > 0 ? Math.floor(v) : null;
  }

  private setHeaderName(index: number, v: string): void {
    const next: CustomHeader[] = [...this.editHeaders];
    next[index] = { name: v, value: next[index].value };
    this.editHeaders = next;
  }

  private setHeaderValue(index: number, v: string): void {
    const next: CustomHeader[] = [...this.editHeaders];
    next[index] = { name: next[index].name, value: v };
    this.editHeaders = next;
  }

  private removeHeader(index: number): void {
    this.editHeaders = this.editHeaders.filter((_: CustomHeader, i: number): boolean => i !== index);
  }

  private addHeader(): void {
    this.editHeaders = [...this.editHeaders, { name: '', value: '' }];
  }

  private async save(): Promise<void> {
    if (this.assistantId.length === 0) { router.back(); return; }
    this.saving = true;
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const headers: CustomHeader[] = this.editHeaders.map(
      (h: CustomHeader): CustomHeader => ({ name: h.name.trim(), value: h.value.trim() }));
    const next: Assistant[] = base.map((a: Assistant): Assistant => {
      if (a.id !== this.assistantId) return a;
      return patchAssistant(a, {
        temperature: this.editTemperature,
        topP: this.editTopP,
        maxTokens: this.parsePositiveIntOrNull(this.editMaxTokens),
        customHeaders: headers,
      });
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

  @Builder sliderRow(label: string, desc: string, value: number, min: number, max: number, step: number, onChange: (v: number) => void): void {
    Column() {
      Row() {
        Column() {
          Text(label).fontSize(BODY).fontColor(INK)
          Text(desc).fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)
        Text(`${value.toFixed(2)}`).fontSize(BODY).fontColor(INK3)
          .width(56).textAlign(TextAlign.End)
      }
      .width('100%')
      NotionSlider({
        value: value, min: min, max: max, step: step, labelMode: '',
        onChange: (v: number): void => { onChange(v); },
      }).margin({ top: 6 })
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
    .backgroundColor(SURFACE)
    .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 })
          .onClick((): void => { router.back(); })
        Text('请求参数').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
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
            this.sectionHeader('采样')
            this.sliderRow('温度', '越高越随机,0-2', this.editTemperature, 0, 2, 0.05,
              (v: number): void => { this.editTemperature = v; })
            this.sliderRow('top_p', '核采样概率,0-1', this.editTopP, 0, 1, 0.01,
              (v: number): void => { this.editTopP = v; })

            this.sectionHeader('输出')
            Column() {
              FlatTextField({
                value: this.editMaxTokens, label: '最大输出 tokens',
                placeholder: '留空 = 继承 provider 默认(>0)',
                onChange: (v: string): void => { this.editMaxTokens = v; },
              })
            }
            .width('100%').padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)
            .border({ width: { bottom: 0.5 }, color: { bottom: LINE } })

            this.sectionHeader('自定义请求头')
            Column() {
              if (this.editHeaders.length === 0) {
                Text('暂无自定义 Header').fontSize(META).fontColor(INK3)
                  .width('100%').padding({ left: 2, top: 8, bottom: 8 })
              }
              ForEach(this.editHeaders, (h: CustomHeader, i: number): void => {
                Row() {
                  TextInput({ text: h.name, placeholder: 'Header 名' })
                    .fontSize(META).height(40).backgroundColor(SURFACE2)
                    .borderRadius(8).layoutWeight(1)
                    .onChange((v: string): void => this.setHeaderName(i, v))
                  TextInput({ text: h.value, placeholder: '值' })
                    .fontSize(META).height(40).backgroundColor(SURFACE2)
                    .borderRadius(8).layoutWeight(1).margin({ left: 8 })
                    .onChange((v: string): void => this.setHeaderValue(i, v))
                  Text('删除').fontSize(META).fontColor('#C93A2F').maxLines(1)
                    .padding({ left: 10 }).constraintSize({ minHeight: 40 })
                    .onClick((): void => this.removeHeader(i))
                }
                .width('100%').margin({ bottom: 6 })
              }, (h: CustomHeader, i: number): string => `h-${i}`)
              Text('+ 添加 Header').fontSize(BODY).fontColor(ACCENT).maxLines(1)
                .padding({ top: 4, bottom: 4 }).constraintSize({ minHeight: 40 })
                .onClick((): void => this.addHeader())
            }
            .width('100%').padding({ left: GUTTER, right: GUTTER, top: 4, bottom: 32 })
          }
          .width('100%')
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

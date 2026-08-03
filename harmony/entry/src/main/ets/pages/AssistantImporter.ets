// AssistantImporter — 助手导入(JSON 粘贴 → 解析 → 预览 → 导入)
// 解析字段:name / systemPrompt / temperature / topP / maxTokens。
// 导入:makeAssistant 新建并 saveAssistants 追加到列表,随后 router.back()。
import { router } from '@kit.ArkUI';
import { loadAssistants, saveAssistants, makeAssistant } from '@amber/chat-domain';
import type { Assistant } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE } from '../design/tokens.ets';

// JSON 导入结构(字段均可缺省)
interface ImportedAssistant {
  name?: string;
  systemPrompt?: string;
  temperature?: number;
  topP?: number;
  maxTokens?: number;
}

@Entry
@Component
struct AssistantImporter {
  @State jsonText: string = '';
  @State parsed: ImportedAssistant | null = null;
  @State errorMsg: string = '';
  @State importing: boolean = false;
  @State doneMsg: string = '';

  aboutToAppear(): void {
    // 预置示例骨架,降低上手成本
    this.jsonText = '{\n  "name": "",\n  "systemPrompt": "",\n  "temperature": 0.7,\n  "topP": 0.9,\n  "maxTokens": 2048\n}';
  }

  private parse(): void {
    const text: string = this.jsonText.trim();
    if (text.length === 0) {
      this.parsed = null;
      this.errorMsg = '请粘贴 JSON 内容';
      return;
    }
    try {
      const obj = JSON.parse(text) as ImportedAssistant;
      this.parsed = obj;
      this.errorMsg = '';
      this.doneMsg = '';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.parsed = null;
      this.errorMsg = `解析失败:${msg.substring(0, 120)}`;
    }
  }

  private parsedName(): string {
    return this.parsed !== null && this.parsed.name !== undefined ? this.parsed.name : '';
  }
  private parsedPrompt(): string {
    return this.parsed !== null && this.parsed.systemPrompt !== undefined ? this.parsed.systemPrompt : '';
  }
  private parsedTemp(): string {
    return this.parsed !== null && this.parsed.temperature !== undefined ? String(this.parsed.temperature) : '';
  }
  private parsedTopP(): string {
    return this.parsed !== null && this.parsed.topP !== undefined ? String(this.parsed.topP) : '';
  }
  private parsedMaxTokens(): string {
    return this.parsed !== null && this.parsed.maxTokens !== undefined ? String(this.parsed.maxTokens) : '';
  }

  private async doImport(): Promise<void> {
    if (this.parsed === null) return;
    this.importing = true;
    const p: ImportedAssistant = this.parsed;
    const name: string = p.name !== undefined ? p.name.trim() : '';
    if (name.length === 0) {
      this.errorMsg = 'name 不能为空';
      this.importing = false;
      return;
    }
    const kv = getChatKvStore();
    const list: Assistant[] | null = await loadAssistants(kv);
    const base: Assistant[] = list === null ? [] : list.slice();
    const created: Assistant = makeAssistant({
      name: name,
      systemPrompt: p.systemPrompt !== undefined ? p.systemPrompt : '',
      temperature: p.temperature !== undefined ? p.temperature : null,
      topP: p.topP !== undefined ? p.topP : null,
      maxTokens: p.maxTokens !== undefined && p.maxTokens > 0 ? p.maxTokens : null,
    });
    await saveAssistants(kv, [...base, created]);
    this.importing = false;
    this.doneMsg = '已导入,返回列表…';
    setTimeout((): void => { router.back(); }, 600);
  }

  @Builder fieldLabel(text: string): void {
    Text(text).fontSize(META).fontColor(INK3).margin({ left: 2, bottom: 4 })
  }

  @Builder previewRow(label: string, value: string): void {
    Row() {
      Text(label).fontSize(META).fontColor(INK3).width(90)
      Text(value.length > 0 ? value : '—')
        .fontSize(META).fontColor(INK).layoutWeight(1)
        .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
    }
    .width('100%').padding({ left: 12, right: 12, top: 8, bottom: 8 })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('‹').fontSize(22).fontColor(INK3)
          .onClick((): void => { router.back(); })
        Text('导入助手').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').height(56).padding({ left: 24, right: 24 }).backgroundColor(BG)

      Scroll() {
        Column() {
          this.fieldLabel('粘贴 JSON')
          TextArea({ text: this.jsonText, placeholder: '{ "name": "...", "systemPrompt": "..." }' })
            .fontSize(META).height(140).backgroundColor(SURFACE)
            .borderRadius(12).border({ width: 1, color: LINE })
            .padding({ left: 12, right: 12, top: 10, bottom: 10 })
            .onChange((v: string): void => { this.jsonText = v; })

          Row() {
            Text('解析').fontSize(BODY).fontColor(Color.White)
              .backgroundColor(ACCENT).borderRadius(8)
              .padding({ left: 18, right: 18, top: 8, bottom: 8 })
              .onClick((): void => { this.parse(); })
            Blank()
            if (this.errorMsg.length > 0) {
              Text(this.errorMsg).fontSize(LABEL).fontColor('#C93A2F').layoutWeight(1)
                .margin({ left: 8 })
            }
          }
          .width('100%').margin({ top: 10, bottom: 10 })

          // 预览
          if (this.parsed !== null) {
            Column() {
              Text('预览').fontSize(META).fontColor(INK3).margin({ left: 2, bottom: 6 })
              Column() {
                this.previewRow('名称', this.parsedName())
                this.previewRow('系统提示词', this.parsedPrompt())
                this.previewRow('temperature', this.parsedTemp())
                this.previewRow('top_p', this.parsedTopP())
                this.previewRow('max_tokens', this.parsedMaxTokens())
              }
              .width('100%').backgroundColor(SURFACE2).borderRadius(12)
            }
            .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })

            Row() {
              Text(this.importing ? '导入中…' : '导入')
                .fontSize(BODY).fontColor(Color.White)
                .backgroundColor(this.importing ? INK4 : ACCENT).borderRadius(8)
                .padding({ left: 24, right: 24, top: 10, bottom: 10 })
                .onClick((): void => { this.doImport(); })
              Blank()
              if (this.doneMsg.length > 0) {
                Text(this.doneMsg).fontSize(META).fontColor(INK3)
              }
            }
            .width('100%')
          }

          Column() {}.height(32)
        }
        .width('100%').padding({ left: 16, right: 16, top: 12 })
      }
      .layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

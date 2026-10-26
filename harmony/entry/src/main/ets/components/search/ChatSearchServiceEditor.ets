import { ACCENT, ACCENT_INK, BG, INK, INK3, LINE, LINE2, OVERLAY, RAISED,
  SURFACE, ERROR, MOTION_OVERLAY } from '../../design/tokens.ets';
import { BtnFill, BtnGhost } from '../PageTemplate.ets';
import { SEARCH_SERVICE_TYPES } from '@amber/chat-domain';
import type { SearchServiceOptionsType } from '@amber/chat-domain';
import { ChatSearchServiceEditorSession, SEARCH_TYPE_ORDER } from '../../platform_impl/search_settings/ChatSearchServiceEditorSession.ets';

const capitalize = (s: string): string =>
  s.length === 0 ? s : s.charAt(0).toUpperCase() + s.slice(1);

@Component
export struct ChatSearchServiceEditor {
  @ObjectLink editor: ChatSearchServiceEditorSession;
  @Prop serviceCount: number = 0;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;

  @Builder
  inlineToggle(checked: boolean, onToggle: () => void) {
    Stack({ alignContent: Alignment.Start }) {
      Column().width(42).height(24).borderRadius(99)
        .backgroundColor(checked ? this.accentColorStore : LINE2)
      Column().width(20).height(20).borderRadius(10).backgroundColor(RAISED)
        .margin({ left: checked ? 22 : 2 })
        .animation({ duration: 150, curve: Curve.EaseOut })
    }
    .clip(true).margin({ left: 12 }).onClick(onToggle)
    .accessibilityText(checked ? '已开启' : '已关闭')
  }

  @Builder
  formTextField(label: string, value: string, placeholder: string,
    onChange: (v: string) => void) {
    Column() {
      Text(label).fontSize(12).fontColor(INK3).margin({ bottom: 4 })
      TextInput({ text: value, placeholder })
        .width('100%').fontSize(13).fontColor(INK)
        .backgroundColor(BG).borderRadius(8)
        .onChange(onChange)
    }
    .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
  }

  @Builder
  editorOverlay() {
    Column() {
      Blank().onClick((): void => { this.editor.closeEditor(); })
      Column() {
        Text(this.editor.editIndex < 0 ? '添加提供商' : '编辑')
          .fontSize(16).fontWeight(FontWeight.Medium).fontColor(INK)
          .margin({ bottom: 12 })

        Scroll() {
          Column() {
            // 类型选择(Select :82-102 → 展开列表)
            Column() {
              Row() {
                Text('类型').fontSize(12).fontColor(INK3)
                Blank()
                Text(SEARCH_SERVICE_TYPES[this.editor.dType])
                  .fontSize(13).fontColor(INK)
                Text(this.editor.typeMenuOpen ? '▲' : '▼')
                  .fontSize(11).fontColor(INK3).margin({ left: 6 })
              }
              .width('100%').alignItems(VerticalAlign.Center).padding(10)
              .backgroundColor(BG).borderRadius(8)
              .onClick((): void => {
                animateTo({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }, (): void => {
                  this.editor.typeMenuOpen = !this.editor.typeMenuOpen;
                });
              })

              if (this.editor.typeMenuOpen) {
                Scroll() {
                Column() {
                  ForEach(SEARCH_TYPE_ORDER, (t: SearchServiceOptionsType) => {
                    Row() {
                      Text(SEARCH_SERVICE_TYPES[t])
                        .fontSize(13)
                        .fontColor(t === this.editor.dType ? ACCENT : INK)
                        .layoutWeight(1)
                      if (t === this.editor.dType) {
                        Text('✓').fontSize(12).fontColor(ACCENT)
                      }
                    }
                    .width('100%').alignItems(VerticalAlign.Center).padding({ left: 10, right: 10, top: 8, bottom: 8 })
                    .onClick((): void => { this.editor.onTypeChange(t); })
                  }, (t: SearchServiceOptionsType): string => t)
                }
                .width('100%')
                }
                .align(Alignment.TopStart)
                .height(220).margin({ top: 4 })
                .backgroundColor(BG).borderRadius(8)
                .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
              }
            }
            .width('100%').margin({ bottom: 12 }).clip(true)

            // 能力 tag(SearchAbilityTagLine :228-253)
            Row() {
              Text('搜索')
                .fontSize(11).fontColor(INK3)
                .padding({ left: 8, right: 8, top: 2, bottom: 2 })
                .border({ width: 1, color: LINE }).borderRadius(6)
              if (this.editor.scrapeCapable()) {
                Text('抓取')
                  .fontSize(11).fontColor(INK3)
                  .padding({ left: 8, right: 8, top: 2, bottom: 2 })
                  .border({ width: 1, color: LINE }).borderRadius(6)
                  .margin({ left: 8 })
              }
            }
            .width('100%').margin({ bottom: 12 })

            // per-type 字段(SearchServiceOptionsEditor :155-225 分派)
            if (this.editor.hasApiKey()) {
              this.formTextField('API Key', this.editor.dApiKey, '',
                (v: string): void => { this.editor.dApiKey = v; })
            }
            if (this.editor.hasDepth()) {
              Column() {
                Text('Depth').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
                Row() {
                  ForEach(this.editor.depthOptions(), (d: string) => {
                    Text(capitalize(d))
                      .fontSize(13)
                      .fontColor(d === this.editor.dDepth ? ACCENT_INK : INK)
                      .textAlign(TextAlign.Center)
                      .padding({ top: 6, bottom: 6 })
                      .layoutWeight(1)
                      .backgroundColor(d === this.editor.dDepth ? ACCENT : BG)
                      .onClick((): void => { this.editor.dDepth = d; })
                  }, (d: string): string => d)
                }
                .width('100%').borderRadius(8).clip(true)
                .border({ width: 1, color: LINE })
              }
              .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
            }
            if (this.editor.dType === 'searxng') {
              this.formTextField('API URL', this.editor.dUrl, '',
                (v: string): void => { this.editor.dUrl = v; })
              this.formTextField('Engines', this.editor.dEngines, '',
                (v: string): void => { this.editor.dEngines = v; })
              this.formTextField('Language', this.editor.dLanguage, '',
                (v: string): void => { this.editor.dLanguage = v; })
              this.formTextField('Username', this.editor.dUsername, '',
                (v: string): void => { this.editor.dUsername = v; })
              this.formTextField('Password', this.editor.dPassword, '',
                (v: string): void => { this.editor.dPassword = v; })
            }
            if (this.editor.dType === 'perplexity') {
              this.formTextField('Max Tokens', this.editor.dMaxTokens, '',
                (v: string): void => { this.editor.dMaxTokens = v; })
              this.formTextField('Max Tokens / Page', this.editor.dMaxTokensPerPage, '',
                (v: string): void => { this.editor.dMaxTokensPerPage = v; })
            }
            if (this.editor.dType === 'jina') {
              this.formTextField('Search URL', this.editor.dSearchUrl, 'https://s.jina.ai/',
                (v: string): void => { this.editor.dSearchUrl = v; })
              this.formTextField('Scrape URL', this.editor.dScrapeUrl, 'https://r.jina.ai/',
                (v: string): void => { this.editor.dScrapeUrl = v; })
            }
            if (this.editor.dType === 'bocha') {
              Row() {
                Column() {
                  Text('Summary').fontSize(13).fontColor(INK)
                  Text('Enable summary generation').fontSize(11).fontColor(INK3)
                    .margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1)
                this.inlineToggle(this.editor.dSummary, (): void => { this.editor.dSummary = !this.editor.dSummary; })
              }
              .width('100%').alignItems(VerticalAlign.Center).margin({ bottom: 12 })
            }
            if (this.editor.dType === 'grok') {
              this.formTextField('Model', this.editor.dModel, '',
                (v: string): void => { this.editor.dModel = v; })
              this.formTextField('Custom URL', this.editor.dCustomUrl, '',
                (v: string): void => { this.editor.dCustomUrl = v; })
              Column() {
                Text('System Prompt').fontSize(12).fontColor(INK3).margin({ bottom: 4 })
                TextArea({ text: this.editor.dSystemPrompt })
                  .width('100%').height(88).fontSize(13).fontColor(INK)
                  .backgroundColor(BG).borderRadius(8)
                  .onChange((v: string): void => { this.editor.dSystemPrompt = v; })
              }
              .width('100%').alignItems(HorizontalAlign.Start).margin({ bottom: 12 })
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start)
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
        .enabled(!this.editor.editorSaving)

        if (this.editor.editorError.length > 0) {
          Text(this.editor.editorError).fontSize(12).fontColor(ERROR)
            .width('100%').margin({ top: 8 })
        }

        Row() {
          if (this.editor.editIndex >= 0 && this.serviceCount > 1) {
            BtnGhost({ label: '删除提供商', onTap: (): void => { this.editor.confirmDelete(); } })
              .enabled(!this.editor.editorSaving)
          }
          Blank()
          BtnGhost({ label: '取消', onTap: (): void => { this.editor.closeEditor(); } })
            .margin({ right: 8 })
          BtnFill({
            label: this.editor.editorSaving ? '保存中…' : (this.editor.editIndex < 0 ? '添加提供商' : '保存'),
            onTap: (): void => {
              if (this.editor.editIndex < 0) this.editor.confirmAdd();
              else this.editor.confirmEdit();
            },
          })
            .enabled(!this.editor.editorSaving)
        }
        .width('100%').alignItems(VerticalAlign.Center).margin({ top: 8 })
      }
      .width('100%').height('86%').padding(16)
      .backgroundColor(SURFACE)
      .borderRadius({ topLeft: 16, topRight: 16 })
      .transition(TransitionEffect.translate({ y: 160 })
        .animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
    }
    .width('100%').height('100%').backgroundColor(OVERLAY)
    .transition(TransitionEffect.OPACITY.animation({ duration: MOTION_OVERLAY, curve: Curve.EaseOut }))
  }

  build(): void { this.editorOverlay() }
}

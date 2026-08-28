// ChatModelPickerPage — Provider/模型选择器(D-023;D-046 起三变体)
// 列出 hasUsableAuth 且非空 models 的 provider(openai/google/claude)及其模型;
// 点选持久化到 Preferences(chat_selected_provider_id/model_id),
// resolveChatRuntime 优先消费。
import {
  ACCENT, BG, INK, INK2, INK3, LINE, META, BODY, TEXT_STYLES,
} from '../design/tokens.ets';
import { PageTitleBar, PaperSeparator } from '../components/PageTemplate.ets';
import { FONT_MONO_MEDIUM } from '../design/fonts.ets';
import { router } from '@kit.ArkUI';
import { loadProviders, hasUsableAuth } from '@amber/chat-domain';
import {
  getChatKvStore, getChatModelSelection, saveChatModelSelection, ChatModelSelection,
} from '../di/AppContainer.ets';


interface ModelRow {
  providerId: string;
  providerName: string;
  providerType: string;
  modelId: string;
  selected: boolean;
}

@Entry
@Component
struct ChatModelPickerPage {
  @State rows: ModelRow[] = [];
  @State loaded: boolean = false;

  aboutToAppear(): void {
    this.refresh();
  }

  // 从 Provider 设置返回后重读(新增 provider 可见)
  onPageShow(): void {
    this.refresh();
  }

  private refresh(): void {
    loadProviders(getChatKvStore()).then((providers): void => {
      const usable: ModelRow[] = [];
      if (providers !== null) {
        for (const p of providers) {
          // D-046:不再限定 openai;google/claude 变体 auth 可用即可选
          if (hasUsableAuth(p)) {
            for (const m of p.models) {
              if (m.type !== 'chat') continue;
              usable.push({
                providerId: p.id,
                providerName: p.name,
                providerType: p.type,
                modelId: m.modelId,
                selected: false,
              });
            }
          }
        }
      }
      getChatModelSelection().then((sel: ChatModelSelection | null): void => {
        for (const row of usable) {
          row.selected = sel !== null
            && row.providerId === sel.providerId && row.modelId === sel.modelId;
        }
        this.rows = usable;
        this.loaded = true;
      });
    }).catch((): void => { this.loaded = true; });
  }

  private pick(row: ModelRow): void {
    saveChatModelSelection({ providerId: row.providerId, modelId: row.modelId })
      .then((): void => { router.back(); });
  }

  build() {
    Column() {
      PageTitleBar({ title: '选择模型' })

      if (!this.loaded) {
        Text('加载中…')
          .fontSize(TEXT_STYLES.body.size)
          .fontWeight(TEXT_STYLES.body.weight)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontFamily(TEXT_STYLES.body.family)
          .fontColor(INK3)
          .margin({ top: 32 })
      } else if (this.rows.length === 0) {
        Column() {
          Text('没有可用的 Provider/模型')
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(TEXT_STYLES.body.weight)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(INK2)
          Text('先在 Provider 设置页填写 baseUrl、API Key 和模型')
            .fontSize(TEXT_STYLES.secondary.size)
            .fontWeight(TEXT_STYLES.secondary.weight)
            .lineHeight(TEXT_STYLES.secondary.lineHeight)
            .fontFamily(TEXT_STYLES.secondary.family)
            .fontColor(INK3)
            .margin({ top: 8 })
          Text('去设置')
            .fontSize(TEXT_STYLES.body.size)
            .fontWeight(600)
            .lineHeight(TEXT_STYLES.body.lineHeight)
            .fontFamily(TEXT_STYLES.body.family)
            .fontColor(ACCENT)
            .margin({ top: 16 })
            .onClick((): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); })
        }.margin({ top: 64 })
      } else {
        Scroll() {
          Column() {
            ForEach(this.rows, (row: ModelRow, idx: number): void => {
              Column() {
                Row() {
                  Column() {
                    Text(row.modelId)
                      .fontSize(META).fontWeight(600).lineHeight(TEXT_STYLES.meta.lineHeight)
                      .fontFamily(FONT_MONO_MEDIUM).fontColor(row.selected ? ACCENT : INK)
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(`${row.providerName} · ${row.providerType}`)
                      .fontSize(BODY).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.body.lineHeight)
                      .fontFamily(TEXT_STYLES.body.family).fontColor(INK3).margin({ top: 4 })
                      .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                  }
                  .alignItems(HorizontalAlign.Start).layoutWeight(1)
                  if (row.selected) {
                    Text('当前')
                      .fontSize(META).fontWeight(600).lineHeight(TEXT_STYLES.meta.lineHeight)
                      .fontFamily(FONT_MONO_MEDIUM).fontColor(ACCENT)
                  }
                }
                .width('100%').constraintSize({ minHeight: 64 })
                .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                .onClick((): void => this.pick(row))
                if (idx < this.rows.length - 1) {
                  PaperSeparator()
                }
              }
              .width('100%')
            }, (row: ModelRow): string => `${row.providerId}/${row.modelId}`)
          }
          .width('100%')
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

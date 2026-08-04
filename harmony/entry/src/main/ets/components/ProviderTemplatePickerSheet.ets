// ProviderTemplatePickerSheet — 模板选择底部 sheet(G6-S2)
// Android 基准: ProviderTemplatePickerSheet.kt(290 行)
// 必要:Provider 模板列表(OpenAI / Claude / Google / DeepSeek / Zhipu / Azure / Ollama / Custom)
//   每项:名称 + 描述 + 默认 baseUrl + 图标;点击 → onPick(template) 填充配置
//   顶部搜索栏(按名称/描述过滤)
//
// Props:@Prop show / onPick(template) / onDismiss
// ArkUI 规则:无 as unknown / 无对象展开 / 无 any/unknown;@Prop 避开 size/enabled/Select/onClick;
//   ForEach 内不出现 const/let(UI 树优先);过滤逻辑放 private 方法
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, BG, LINE, ACCENT,
  BODY, META, LABEL,
} from '../design/tokens.ets';
import { Icon } from './ui/Icon.ets';

// Provider 模板接口(供 onPick 回调传递)
export interface ProviderTemplate {
  name: string;
  description: string;
  baseUrl: string;
  apiKeyPlaceholder: string;
  type: string;
}

// 内置模板(对齐 Android DEFAULT_PROVIDERS + 模板 sheet)
const TEMPLATES: ProviderTemplate[] = [
  {
    name: 'OpenAI',
    description: 'GPT-4o / o1 系列',
    baseUrl: 'https://api.openai.com/v1',
    apiKeyPlaceholder: 'sk-...',
    type: 'openai',
  },
  {
    name: 'Claude',
    description: 'Claude 3.5 / Opus 系列',
    baseUrl: 'https://api.anthropic.com',
    apiKeyPlaceholder: 'sk-ant-...',
    type: 'claude',
  },
  {
    name: 'Google',
    description: 'Gemini 2.0 系列',
    baseUrl: 'https://generativelanguage.googleapis.com',
    apiKeyPlaceholder: 'AIza...',
    type: 'google',
  },
  {
    name: 'DeepSeek',
    description: 'DeepSeek V3 / R1 系列',
    baseUrl: 'https://api.deepseek.com',
    apiKeyPlaceholder: 'sk-...',
    type: 'deepseek',
  },
  {
    name: 'Zhipu',
    description: '智谱 GLM-4 系列',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    apiKeyPlaceholder: 'xxx.xxx',
    type: 'zhipu',
  },
  {
    name: 'Azure',
    description: 'Azure OpenAI 服务',
    baseUrl: 'https://{resource}.openai.azure.com',
    apiKeyPlaceholder: 'azure-key',
    type: 'azure',
  },
  {
    name: 'Ollama',
    description: '本地 Ollama 服务',
    baseUrl: 'http://localhost:11434',
    apiKeyPlaceholder: '无需密钥',
    type: 'ollama',
  },
  {
    name: 'Custom',
    description: '自定义 OpenAI 兼容接口',
    baseUrl: 'https://api.example.com/v1',
    apiKeyPlaceholder: 'sk-...',
    type: 'custom',
  },
];

@Component
export struct ProviderTemplatePickerSheet {
  @Prop show: boolean = false;
  @State searchText: string = '';
  onPick: (template: ProviderTemplate) => void = (_t: ProviderTemplate): void => {};
  onDismiss: () => void = (): void => {};

  // 类型 → 图标(Unicode emoji,对齐 ChatExportSheet 图标策略)
  private templateIcon(providerType: string): string {
    const t: string = providerType.toLowerCase();
    if (t === 'openai') return '🟢';
    if (t === 'claude') return '🟠';
    if (t === 'google') return '🔵';
    if (t === 'deepseek') return '🟣';
    if (t === 'zhipu') return '🧪';
    if (t === 'azure') return '☁️';
    if (t === 'ollama') return '🦙';
    return '⚙️';
  }

  // 按搜索词过滤模板(名称 + 描述匹配)
  private filteredTemplates(): ProviderTemplate[] {
    const q: string = this.searchText.trim().toLowerCase();
    if (q.length === 0) return TEMPLATES;
    const result: ProviderTemplate[] = [];
    for (let i = 0; i < TEMPLATES.length; i++) {
      const t: ProviderTemplate = TEMPLATES[i];
      if (t.name.toLowerCase().indexOf(q) >= 0 ||
        t.description.toLowerCase().indexOf(q) >= 0) {
        result.push(t);
      }
    }
    return result;
  }

  build(): void {
    if (this.show) {
      Stack({ alignContent: Alignment.Bottom }) {
        // 遮罩层(点击关闭)
        Column()
          .width('100%')
          .height('100%')
          .backgroundColor('rgba(0,0,0,0.4)')
          .onClick((): void => { this.onDismiss(); })

        // sheet 主体
        Column() {
          // 顶部 grabber
          Text('')
            .width(42)
            .height(4)
            .borderRadius(2)
            .backgroundColor(LINE)
            .margin({ top: 10, bottom: 8 })

          // 标题
          Text('添加 Provider')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .width('100%')
            .margin({ bottom: 12 })

          // 搜索栏
          Row() {
            Icon({ name: 'search', iconSize: BODY, color: INK3 }).margin({ right: 10 })
            TextInput({ text: this.searchText, placeholder: '搜索模板...' })
              .fontSize(BODY)
              .fontColor(INK)
              .backgroundColor('transparent')
              .layoutWeight(1)
              .padding({ top: 0, bottom: 0, left: 0, right: 0 })
              .onChange((v: string): void => { this.searchText = v; })
            if (this.searchText.length > 0) {
              Text('✕')
                .fontSize(LABEL)
                .fontColor(INK3)
                .width(20)
                .height(20)
                .textAlign(TextAlign.Center)
                .borderRadius(6)
                .onClick((): void => { this.searchText = ''; })
            }
          }
          .width('100%')
          .padding({ left: 14, right: 14, top: 10, bottom: 10 })
          .backgroundColor(SURFACE2)
          .borderRadius(999)
          .border({ width: 1, color: LINE })
          .margin({ bottom: 12 })

          // 模板列表(可滚动)
          Scroll() {
            Column() {
              ForEach(this.filteredTemplates(), (item: ProviderTemplate): void => {
                Row() {
                  Text(this.templateIcon(item.type))
                    .fontSize(20)
                    .margin({ right: 12 })

                  Column() {
                    Text(item.name)
                      .fontSize(BODY)
                      .fontColor(INK)
                      .fontWeight(FontWeight.Medium)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                    Text(item.description)
                      .fontSize(LABEL)
                      .fontColor(INK3)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .margin({ top: 2 })
                    Text(item.baseUrl)
                      .fontSize(LABEL)
                      .fontColor(INK4)
                      .maxLines(1)
                      .textOverflow({ overflow: TextOverflow.Ellipsis })
                      .margin({ top: 2 })
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)

                  Text('›')
                    .fontSize(18)
                    .fontColor(INK4)
                }
                .width('100%')
                .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                .backgroundColor(SURFACE)
                .borderRadius(12)
                .border({ width: 1, color: LINE })
                .margin({ bottom: 8 })
                .alignItems(VerticalAlign.Center)
                .onClick((): void => { this.onPick(item); })
              }, (item: ProviderTemplate): string => item.name)
            }
            .width('100%')
          }
          .layoutWeight(1)
          .scrollBar(BarState.Off)

          // 关闭按钮
          Text('关闭')
            .fontSize(BODY)
            .fontColor(INK3)
            .width('100%')
            .textAlign(TextAlign.Center)
            .padding({ top: 12, bottom: 12 })
            .margin({ top: 8 })
            .onClick((): void => { this.onDismiss(); })
        }
        .width('100%')
        .height('85%')
        .backgroundColor(BG)
        .borderRadius({ topLeft: 16, topRight: 16 })
        .padding({ left: 18, right: 18, top: 0, bottom: 12 })
        // 消费点击事件,避免穿透到遮罩层(关闭 sheet)
        .onClick((): void => {})
      }
      .width('100%')
      .height('100%')
    }
  }
}

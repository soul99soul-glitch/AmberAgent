// ChatModelPickerPage — Provider/模型选择器(D-023;D-046 起三变体)
// 列出 hasUsableAuth 且非空 models 的 provider(openai/google/claude)及其模型;
// 点选经 ChatModelSelectionSupport 写入口持久化(UI-F34:与运行时同源 ——
// 助手已绑定 chatModelId 时改写绑定内部 UUID,否则写全局 chat_selected_* 键)。
// router 参数 assistantId(可选;由 ChatPage 打开完整选择器时携带)决定「当前」标记
// 与写目标,避免绑定助手会话里菜单/选择器显示与发送实际模型不一致。
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1990):仅换视觉骨架
// (CenteredTitleBar/CardGroup r18+描边/CardRow accent 座),逻辑零改动。
import { ACCENT, BG, INK2, INK3, GUTTER, TEXT_STYLES, lineHeightForSize } from '../design/tokens.ets';
import { CenteredTitleBar, CardGroup, CardRow } from '../components/PageTemplate.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import { router, promptAction } from '@kit.ArkUI';
import { loadProviders, hasUsableAuth } from '@amber/chat-domain';
import { getChatKvStore, ChatProviderChoice } from '../di/AppContainer.ets';
import {
  resolveEffectiveChatChoice, saveEffectiveChatChoice, clearEffectiveChatChoice,
  hasEffectiveChatBinding,
} from '../platform_impl/ChatModelSelectionSupport.ets';


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
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State rows: ModelRow[] = [];
  @State loaded: boolean = false;
  // 助手已绑定模型:顶部提供「跟随全局选择」清除入口(UI-F34)
  @State bound: boolean = false;
  // 会话助手 id(router 参数;'' = 无绑定上下文 → 全局选择语义)
  private assistantId: string = '';

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    this.assistantId = params ? (params['assistantId'] ?? '') : '';
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
      // UI-F34:「当前」= 有效模型(助手绑定优先,未绑定回落全局),与发送同源
      resolveEffectiveChatChoice(this.assistantId).then((choice: ChatProviderChoice | null): void => {
        const currentProvider: string = choice !== null ? choice.provider.id : '';
        const currentModel: string = choice !== null ? choice.model.modelId : '';
        for (const row of usable) {
          row.selected = currentProvider.length > 0 && currentModel.length > 0
            && row.providerId === currentProvider && row.modelId === currentModel;
        }
        this.rows = usable;
        this.loaded = true;
      }).catch((): void => { this.loaded = true; });
      hasEffectiveChatBinding(this.assistantId).then((bound: boolean): void => {
        this.bound = bound;
      }).catch((): void => {});
    }).catch((): void => { this.loaded = true; });
  }

  private pick(row: ModelRow): void {
    saveEffectiveChatChoice({ providerId: row.providerId, modelId: row.modelId }, this.assistantId)
      .then((): void => { router.back(); })
      .catch((e: Error): void => {
        promptAction.showToast({ message: `保存模型选择失败:${e.message}`, duration: 2200 });
      });
  }

  // 清除助手绑定 → 回到跟随全局选择;成功后才返回
  private clearBinding(): void {
    clearEffectiveChatChoice(this.assistantId)
      .then((): void => { router.back(); })
      .catch((e: Error): void => {
        promptAction.showToast({ message: `清除绑定失败:${e.message}`, duration: 2200 });
      });
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,
  //    裸 lambda 直接 new 组件会炸 "class constructor cannot called without 'new'")──

  // 助手绑定清除行(UI-F34):accent 动作标题 + 说明,整行可点
  @Builder
  BindingCard(): void {
    Row() {
      Column() {
        Text('跟随全局选择')
          .fontSize(16).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(ACCENT)
        Text('清除该助手的模型绑定')
          .fontSize(12).fontWeight(FontWeight.Medium).lineHeight(lineHeightForSize(12))
          .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
    }
    .width('100%').constraintSize({ minHeight: 52 })
    .padding({ left: 14, right: 14, top: 10, bottom: 10 })
    .onClick((): void => this.clearBinding())
  }

  // 模型列表行:iOS AmberFormRow 骨架(accent 浅底图标座 + 标题/副标题 + 箭头)
  // 选中项以右侧「当前」值标记(数据与写入口同原实现)
  @Builder
  ModelCards(): void {
    ForEach(this.rows, (row: ModelRow, idx: number): void => {
      CardRow({
        label: row.modelId,
        desc: `${row.providerName} · ${row.providerType}`,
        valueText: row.selected ? '当前' : '',
        iconName: 'brain',
        plateStyle: 'accent',
        plateSize: 28,
        isLast: idx === this.rows.length - 1,
        onRowClick: (): void => this.pick(row),
      })
    }, (row: ModelRow): string => `${row.providerId}/${row.modelId}`)
  }

  build() {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '选择模型' })

      // 清除绑定入口(UI-F34)在所有分支前渲染:已绑定但无可用模型时也必须可达
      if (this.bound) {
        CardGroup({ radius: 18, stroke: true, content: (): void => { this.BindingCard(); } })
          .margin({ left: GUTTER, right: GUTTER, top: 8 })
      }
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
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModelCards(); } })
              .margin({ left: GUTTER, right: GUTTER, top: 8 })
            Column() {}.height(40)
          }
          .width('100%')
        }
        .width('100%').layoutWeight(1).align(Alignment.TopStart)
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }
}

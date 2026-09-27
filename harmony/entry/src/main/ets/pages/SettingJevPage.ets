// SettingJevPage — Jev 快速判断设置页(C5-1;Android SettingJevPage 对齐)
//
// 模式:关 / 影子(只记录不影响行为)/ 主动(结果影响工具发现选择);
// 方言:TypeSafe 原生(/v1/systemone)/ Vercel AI Gateway(evaluation-model)。
// 本批接线用途:工具发现语义重排(tool_search);其余用途登记未接,不展示假开关。

import {
  loadJevSettings, saveJevSettings, makeJevSettings, decodeJevResponse, buildJevRequest,
  JEV_TYPESAFE_ENDPOINT,
} from '@amber/chat-domain';
import type { JevSettings, JevEvaluation, JevQuestion } from '@amber/chat-domain';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { loadJevRuntimeStatus, JevRuntimeStatus } from '../platform_impl/JevSupport.ets';
import {
  ACCENT, BG, ERROR, INK, INK3, INK4, SURFACE2, BODY, LABEL, META, TEXT_STYLES, GUTTER,
} from '../design/tokens.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, CardToggleRow, FooterNote, PageSpacer, Seg,
} from '../components/PageTemplate.ets';
import { FONT_MONO } from '../design/fonts.ets';
import { promptAction } from '@kit.ArkUI';

const MODE_OPTIONS: string[] = ['关', '影子', '主动'];
const MODE_VALUES: string[] = ['off', 'shadow', 'active'];
const API_MODE_OPTIONS: string[] = ['TypeSafe', 'Vercel'];
const API_MODE_VALUES: string[] = ['typesafe', 'vercel'];

@Entry
@Component
struct SettingJevPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State settings: JevSettings = makeJevSettings();
  @State status: JevRuntimeStatus = {
    mode: 'off', apiMode: 'typesafe', todayCount: 0, budget: 200, cooldownUntil: 0, lastError: '',
  };
  @State loaded: boolean = false;
  @State testing: boolean = false;
  @State testMsg: string = '';

  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    loadJevSettings(getChatKvStore()).then((s: JevSettings): void => {
      this.settings = s;
    }).catch((): void => {});
    loadJevRuntimeStatus().then((st: JevRuntimeStatus): void => {
      this.status = st;
      this.loaded = true;
    }).catch((): void => {
      this.loaded = true;
    });
  }

  private persist(): void {
    saveJevSettings(getChatKvStore(), this.settings).catch((): void => {});
  }

  private modeIndex(): number {
    const idx: number = MODE_VALUES.indexOf(this.settings.mode);
    return idx >= 0 ? idx : 0;
  }

  private apiModeIndex(): number {
    const idx: number = API_MODE_VALUES.indexOf(this.settings.apiMode);
    return idx >= 0 ? idx : 0;
  }

  private setMode(idx: number): void {
    this.settings = makeJevSettings({
      mode: MODE_VALUES[idx] === 'shadow' ? 'shadow' : (MODE_VALUES[idx] === 'active' ? 'active' : 'off'),
      apiMode: this.settings.apiMode,
      apiKey: this.settings.apiKey,
      baseUrl: this.settings.baseUrl,
      model: this.settings.model,
    });
    this.persist();
    this.refresh();
  }

  private setApiMode(idx: number): void {
    this.settings = makeJevSettings({
      mode: this.settings.mode,
      apiMode: API_MODE_VALUES[idx] === 'vercel' ? 'vercel' : 'typesafe',
      apiKey: this.settings.apiKey,
      baseUrl: this.settings.baseUrl,
      model: this.settings.model,
    });
    this.persist();
  }

  private patchText(patch: Partial<JevSettings>): void {
    this.settings = makeJevSettings({
      mode: this.settings.mode,
      apiMode: patch.apiMode ?? this.settings.apiMode,
      apiKey: patch.apiKey ?? this.settings.apiKey,
      baseUrl: patch.baseUrl ?? this.settings.baseUrl,
      model: patch.model ?? this.settings.model,
    });
    this.persist();
  }

  // 连接测试:一道 noul 是非题(真实往返;不伪造成功)
  private testConnection(): void {
    if (this.testing) return;
    this.testing = true;
    this.testMsg = '正在测试…';
    const questions: Record<string, JevQuestion> = {
      'ping': {
        kind: 'noul',
        instructions: '回答：2 加 2 等于 4 吗？',
        trueCriteria: '数学上正确',
        falseCriteria: '数学上错误',
      },
    };
    const endpoint: string = this.settings.apiMode === 'vercel'
      ? this.settings.baseUrl : JEV_TYPESAFE_ENDPOINT;
    try {
      const spec = buildJevRequest(
        this.settings.apiMode, endpoint, this.settings.apiKey, this.settings.model,
        { ping: 'pong' }, questions);
      getAppContainer().httpClient.fetch({
        url: spec.url, method: 'POST', headers: spec.headers, body: spec.body,
      }, { signal: undefined }).then((resp): void => {
        this.testing = false;
        if (resp.status < 200 || resp.status >= 300) {
          this.testMsg = `失败:HTTP ${resp.status}`;
          return;
        }
        try {
          const evaluation: JevEvaluation = decodeJevResponse(resp.body, questions, this.settings.apiMode);
          const answer = evaluation.answers['ping'];
          if (answer !== undefined && answer.kind === 'noul') {
            this.testMsg = `连接正常(p=${answer.probability.toFixed(2)})`;
          } else {
            this.testMsg = '连接正常(应答格式已校验)';
          }
        } catch (e) {
          this.testMsg = `应答校验失败:${e instanceof Error ? e.message : String(e)}`;
        }
      }).catch((e: Error): void => {
        this.testing = false;
        this.testMsg = `失败:${e.message}`;
      });
    } catch (e) {
      this.testing = false;
      this.testMsg = `失败:${(e as Error).message}`;
    }
  }

  @Builder
  ModeCard(): void {
    Column() {
      Row() {
        Text('模式')
          .fontSize(16).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontColor(INK)
      }
      .width('100%')
      Seg({
        options: MODE_OPTIONS,
        selected: this.modeIndex(),
        onSelect: (idx: number): void => { this.setMode(idx); },
      })
        .margin({ top: 12 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  ApiCard(): void {
    Column() {
      Row() {
        Text('调用方式')
          .fontSize(16).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontColor(INK)
      }
      .width('100%')
      Seg({
        options: API_MODE_OPTIONS,
        selected: this.apiModeIndex(),
        onSelect: (idx: number): void => { this.setApiMode(idx); },
      })
        .margin({ top: 12 })
      TextInput({ text: this.settings.model, placeholder: '模型(如 systemone-default)' })
        .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(10)
        .padding({ left: 14, right: 14 })
        .margin({ top: 12 })
        .onChange((v: string): void => { this.patchText({ model: v.trim() }); })
      TextInput({ text: this.settings.apiKey, placeholder: 'API Key' })
        .type(InputType.Password)
        .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
        .backgroundColor(SURFACE2).borderRadius(10)
        .padding({ left: 14, right: 14 })
        .margin({ top: 12 })
        .onChange((v: string): void => { this.patchText({ apiKey: v.trim() }); })
      if (this.settings.apiMode === 'vercel') {
        TextInput({ text: this.settings.baseUrl, placeholder: '网关 Base URL' })
          .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK4)
          .backgroundColor(SURFACE2).borderRadius(10)
          .padding({ left: 14, right: 14 })
          .margin({ top: 12 })
          .onChange((v: string): void => { this.patchText({ baseUrl: v.trim() }); })
      }
      Row() {
        Text(this.testMsg)
          .fontSize(META)
          .fontColor(this.testMsg.startsWith('失败') || this.testMsg.indexOf('校验失败') >= 0
            ? ERROR : INK3)
          .layoutWeight(1)
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(this.testing ? '测试中…' : '连接测试')
          .fontSize(META).fontColor(this.testing ? INK3 : this.accentColorStore)
          .padding({ left: 12, top: 8, bottom: 8 })
          .onClick((): void => { this.testConnection(); })
      }
      .width('100%').margin({ top: 10 })
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  @Builder
  StatusCard(): void {
    Column() {
      Row() {
        Text('运行状态')
          .fontSize(16).fontWeight(FontWeight.Medium)
          .lineHeight(TEXT_STYLES.body.lineHeight)
          .fontColor(INK)
          .layoutWeight(1)
        Text(`${this.status.todayCount}/${this.status.budget}`)
          .fontSize(META).fontFamily(FONT_MONO).fontColor(INK3)
      }
      .width('100%')
      if (this.status.cooldownUntil > Date.now()) {
        Text(`错误冷却中,约 ${Math.ceil((this.status.cooldownUntil - Date.now()) / 60000)} 分钟后恢复`)
          .fontSize(META).fontColor(ERROR).width('100%').margin({ top: 8 })
      }
      if (this.status.lastError.length > 0) {
        Text(`最近错误:${this.status.lastError}`)
          .fontSize(LABEL).fontColor(INK3).width('100%').margin({ top: 8 })
          .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      }
    }
    .width('100%')
    .padding({ left: 14, right: 14, top: 12, bottom: 12 })
  }

  build(): void {
    Column() {
      CenteredTitleBar({ title: 'Jev 快速判断' })
      if (this.loaded) {
        Scroll() {
          Column() {
            PlainSection({ label: '模式' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ModeCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '影子模式只做评估记录,不改变任何行为;主动模式用于工具发现的语义重排(tool_search)。' })

            PlainSection({ label: '服务' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.ApiCard(); } })
              .margin({ left: GUTTER, right: GUTTER })

            PlainSection({ label: '状态' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.StatusCard(); } })
              .margin({ left: GUTTER, right: GUTTER })
            FooterNote({ text: '预算:每日 200 次评估;连续错误进入 5 分钟冷却。其余用途(记忆召回/上下文选择/模型路由/自动化)暂未接入。' })
            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
        .backgroundColor(BG)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

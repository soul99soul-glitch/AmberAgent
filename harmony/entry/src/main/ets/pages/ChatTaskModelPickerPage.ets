// ChatTaskModelPickerPage — 任务模型选择器(D-094)
// Android 锚点:SettingModelPage.kt ModelPickerRow(:798)+ ModelSelector —
//   列出 hasUsableAuth 且非空 models 的 provider 模型;点选写回:
//   title/suggestion/compress → saveTaskModelId(key, m.id)(:448-450/:487-489/:570-572)
//   worker → updateMemoryWorkerModels{modelId = it.id, followCompressModel = false}(:631-641)
//   daydream → 同构 {daydreamModelId, daydreamFollowCompressModel = false}(:684-697)
// 与 ChatModelPickerPage 区别:任务模型按模型 uuid(m.id)存储,非 (providerId, modelId) 对。
import { ACCENT,BG,INK,INK2,INK3,LINE,SURFACE,TITLE } from '../design/tokens.ets';
import { router } from '@kit.ArkUI';
import { loadProviders, hasUsableAuth, DEFAULT_AUTO_MODEL_ID } from '@amber/chat-domain';
import type { ProviderModel } from '@amber/chat-domain';
import {
  getChatKvStore, saveTaskModelId, loadTaskModelIds, TaskModelIds,
  TASK_TITLE_MODEL_KEY, TASK_SUGGESTION_MODEL_KEY, TASK_COMPRESS_MODEL_KEY,
} from '../di/AppContainer.ets';
import {
  loadAgentRuntimeSnapshot, updateMemoryWorkerModels, AgentRuntimeSnapshot,
} from '../platform_impl/AgentRuntimePrefs.ets';
import { getOcrModelId, saveOcrModelId } from '../platform_impl/OcrSupport.ets';


interface TaskModelRow {
  providerName: string;
  providerType: string;
  modelUuid: string;
  displayName: string;
  modelId: string;
  selected: boolean;
}

const TASK_TITLES: Record<string, string> = {
  'title': '标题总结模型',
  'suggestion': '聊天建议模型',
  'compress': '压缩模型',
  'worker': '记忆提取模型',
  'daydream': 'Daydream 模型',
  'ocr': '视觉识别模型',
};

@Entry
@Component
struct ChatTaskModelPickerPage {
  @State rows: TaskModelRow[] = [];
  @State loaded: boolean = false;
  @State task: string = 'title';
  @State currentUuid: string = DEFAULT_AUTO_MODEL_ID;

  aboutToAppear(): void {
    const params = router.getParams() as Record<string, string>;
    this.task = params ? (params['task'] ?? 'title') : 'title';
    this.refresh();
  }

  private refresh(): void {
    loadProviders(getChatKvStore()).then((providers): void => {
      const usable: TaskModelRow[] = [];
      if (providers !== null) {
        for (const p of providers) {
          if (p.models.length > 0 && hasUsableAuth(p)) {
            for (const m of p.models) {
              // D-098:ocr 任务按 preferredInputModality=IMAGE 过滤
              //   (SettingModelPage.kt:533;无 image 输入模态的模型不可做视觉识别)
              if (this.task === 'ocr' && m.inputModalities.indexOf('image') < 0) {
                continue;
              }
              usable.push({
                providerName: p.name,
                providerType: p.type,
                modelUuid: m.id,
                displayName: m.displayName,
                modelId: m.modelId,
                selected: false,
              });
            }
          }
        }
      }
      // 当前选中 uuid:title/suggestion/compress 读任务键;worker/daydream 读 runtime
      const markSelected = (uuid: string): void => {
        this.currentUuid = uuid;
        for (const row of usable) {
          row.selected = row.modelUuid === uuid;
        }
        this.rows = usable;
        this.loaded = true;
      };
      if (this.task === 'worker' || this.task === 'daydream') {
        loadAgentRuntimeSnapshot(getChatKvStore()).then((snap: AgentRuntimeSnapshot): void => {
          markSelected(this.task === 'worker'
            ? snap.memoryWorker.modelId : snap.memoryWorker.daydreamModelId);
        });
      } else if (this.task === 'ocr') {
        // ChatPrefs.kt:80 — 未配置 = random uuid(必不命中 → 无选中行)
        getOcrModelId(getChatKvStore()).then((uuid: string): void => {
          markSelected(uuid);
        });
      } else {
        loadTaskModelIds().then((ids: TaskModelIds): void => {
          const uuid: string = this.task === 'title' ? ids.titleModelId
            : this.task === 'suggestion' ? ids.suggestionModelId : ids.compressModelId;
          markSelected(uuid);
        });
      }
    });
  }

  private pick(row: TaskModelRow): void {
    const done = (): void => { router.back(); };
    if (this.task === 'worker') {
      // onSelect → modelId = it.id, followCompressModel = false(:631-641)
      updateMemoryWorkerModels(getChatKvStore(), {
        modelId: row.modelUuid, followCompressModel: false,
      }).then((): void => { done(); });
    } else if (this.task === 'daydream') {
      updateMemoryWorkerModels(getChatKvStore(), {
        daydreamModelId: row.modelUuid, daydreamFollowCompressModel: false,
      }).then((): void => { done(); });
    } else if (this.task === 'ocr') {
      saveOcrModelId(getChatKvStore(), row.modelUuid).then((): void => { done(); });
    } else {
      const key: string = this.task === 'title' ? TASK_TITLE_MODEL_KEY
        : this.task === 'suggestion' ? TASK_SUGGESTION_MODEL_KEY : TASK_COMPRESS_MODEL_KEY;
      saveTaskModelId(key, row.modelUuid).then((): void => { done(); });
    }
  }

  build() {
    Column() {
      Row() {
        Text(TASK_TITLES[this.task] ?? '选择模型')
          .fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
      }
      .width('100%').padding({ left: 16, right: 16, top: 16, bottom: 12 })

      if (!this.loaded) {
        Text('加载中…').fontSize(13).fontColor(INK3).margin({ top: 32 })
      } else if (this.rows.length === 0) {
        Column() {
          Text('没有可用的 Provider/模型').fontSize(15).fontColor(INK2)
          Text('先在 Provider 设置页填写 baseUrl、API Key 和模型')
            .fontSize(12).fontColor(INK3).margin({ top: 8 })
          Text('去设置')
            .fontSize(14).fontColor(ACCENT).margin({ top: 16 })
            .onClick((): void => { router.pushUrl({ url: 'pages/ChatProviderSettingsPage' }); })
        }.margin({ top: 64 })
      } else {
        List() {
          ForEach(this.rows, (row: TaskModelRow): void => {
            ListItem() {
              Row() {
                Column() {
                  Text(row.displayName.length > 0 ? row.displayName : row.modelId)
                    .fontSize(15).fontColor(INK)
                  Text(`${row.modelId} · ${row.providerName} · ${row.providerType}`)
                    .fontSize(11).fontColor(INK3).margin({ top: 4 })
                }
                .alignItems(HorizontalAlign.Start).layoutWeight(1)
                if (row.selected) {
                  Text('当前').fontSize(12).fontColor(ACCENT)
                }
              }
              .width('100%')
              .padding({ left: 16, right: 16, top: 12, bottom: 12 })
              .backgroundColor(SURFACE)
              .onClick((): void => this.pick(row))
            }
          }, (row: TaskModelRow): string => row.modelUuid)
        }
        .width('100%').layoutWeight(1)
        .divider({ strokeWidth: 1, color: LINE })
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

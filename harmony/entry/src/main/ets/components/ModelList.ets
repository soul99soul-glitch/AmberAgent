// ModelList — 可复用模型列表组件(G5-S3,对齐 Android ModelList.kt:444-835)
// 功能:
//   - 搜索栏(按 name / provider 过滤)
//   - 排序(name / contextWindow / added)
//   - 模型卡:name + provider 徽章 + contextWindow + enabled 开关
//   - 添加模型按钮(展开内联表单:name/provider/contextWindow/maxOutput/temperature)
//   - 单卡:编辑(展开内联表单)/ 删除 / 启用-停用
//   - 空态
//
// Props:models(ModelInfo[]) + 回调 onAdd/onEdit/onDelete/onToggle
// 本地 interface ModelInfo 与父页共享(导出)
//
// ArkUI 规则:
//   - 无 as unknown / 无对象展开 / 无 any/unknown
//   - @Prop 避免保留字(name→ 用 providerName 等;此处 ModelInfo.name 为字段,非 @Prop)
//   - ForEach/@Builder 内不声明 const/let(UI 树优先;辅助函数外移)
//   - 排序/过滤结果用 @State 缓存,在事件回调里重算(不放在 build 内)
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE,
} from '../design/tokens.ets';
import { FlatTextField } from './ui/FlatTextField.ets';
import { AmberSwitch, SwitchSize } from './ui/Switch.ets';
import { Tag, TagType } from './ui/Tag.ets';
import { Icon } from './ui/Icon.ets';
import { formatNumberInt } from '@amber/chat-domain';

// 模型信息(与父页共享;导出供 pages 引用)
export interface ModelInfo {
  id: string;
  name: string;
  provider: string;
  contextWindow: number;
  maxOutputTokens: number;
  temperature: number;
  enabled: boolean;
}

// 排序键
export type ModelSortKey = 'name' | 'contextWindow' | 'added';

// 工厂(避免对象展开:全字段显式构造)
export function makeModelInfo(
  id: string, name: string, provider: string,
  contextWindow: number, maxOutputTokens: number, temperature: number, enabled: boolean,
): ModelInfo {
  return {
    id: id, name: name, provider: provider,
    contextWindow: contextWindow, maxOutputTokens: maxOutputTokens,
    temperature: temperature, enabled: enabled,
  };
}

// 纯函数:过滤 + 排序(不依赖 this,可在事件回调中调用)
function filterModels(models: ModelInfo[], query: string): ModelInfo[] {
  const q: string = query.trim().toLowerCase();
  if (q.length === 0) return models;
  return models.filter((m: ModelInfo): boolean => {
    return m.name.toLowerCase().indexOf(q) >= 0
      || m.provider.toLowerCase().indexOf(q) >= 0;
  });
}

function sortModels(models: ModelInfo[], key: ModelSortKey): ModelInfo[] {
  const arr: ModelInfo[] = models.slice();
  if (key === 'name') {
    arr.sort((a: ModelInfo, b: ModelInfo): number => a.name.localeCompare(b.name));
  } else if (key === 'contextWindow') {
    arr.sort((a: ModelInfo, b: ModelInfo): number => b.contextWindow - a.contextWindow);
  }
  // 'added' 保持原顺序(父级维护添加顺序)
  return arr;
}

@Component
export struct ModelList {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop models: ModelInfo[] = [];
  // 回调(父级负责持久化)
  onAdd: (m: ModelInfo) => void = (_m: ModelInfo): void => {};
  onEdit: (m: ModelInfo) => void = (_m: ModelInfo): void => {};
  onDelete: (id: string) => void = (_id: string): void => {};
  onToggle: (id: string, enabled: boolean) => void = (_id: string, _e: boolean): void => {};

  // 本地 UI 状态
  @State query: string = '';
  @State sortKey: ModelSortKey = 'added';
  @State sortMenuOpen: boolean = false;
  @State visibleModels: ModelInfo[] = [];
  // 内联"添加"表单
  @State addOpen: boolean = false;
  @State addName: string = '';
  @State addProvider: string = '';
  @State addContext: string = '';
  @State addMaxOut: string = '';
  @State addTemp: string = '';
  // 内联"编辑"表单(同一时间只展开一个;空串=无展开)
  @State editingId: string = '';
  @State editName: string = '';
  @State editProvider: string = '';
  @State editContext: string = '';
  @State editMaxOut: string = '';
  @State editTemp: string = '';
  // 添加表单的临时 id 生成(父级 onAdd 时可改写)
  private addCounter: number = 0;

  // models 变化或过滤/排序条件变化 → 重算 visibleModels
  // ArkUI 不支持 @Watch 跨 @Prop 联动 @State 的派生计算,
  //   故在 aboutToAppear + 事件回调里显式 refresh()
  aboutToAppear(): void {
    this.refresh();
  }

  private refresh(): void {
    this.visibleModels = sortModels(filterModels(this.models, this.query), this.sortKey);
  }

  private onQueryChange(v: string): void {
    this.query = v;
    this.refresh();
  }

  private onSortPick(key: ModelSortKey): void {
    this.sortKey = key;
    this.sortMenuOpen = false;
    this.refresh();
  }

  private sortLabel(): string {
    if (this.sortKey === 'name') return '名称';
    if (this.sortKey === 'contextWindow') return '上下文';
    return '添加顺序';
  }

  // —— 添加表单 ——
  private openAdd(): void {
    this.addOpen = true;
    this.addName = '';
    this.addProvider = '';
    this.addContext = '8192';
    this.addMaxOut = '4096';
    this.addTemp = '0.7';
  }

  private closeAdd(): void {
    this.addOpen = false;
  }

  private confirmAdd(): void {
    const name: string = this.addName.trim();
    if (name.length === 0) return;
    this.addCounter += 1;
    const id: string = `m-new-${this.addCounter}`;
    const ctx: number = parseInt(this.addContext, 10);
    const maxOut: number = parseInt(this.addMaxOut, 10);
    const temp: number = parseFloat(this.addTemp);
    const fresh: ModelInfo = makeModelInfo(
      id, name, this.addProvider.trim(),
      isNaN(ctx) ? 0 : ctx,
      isNaN(maxOut) ? 0 : maxOut,
      isNaN(temp) ? 0 : temp,
      true,
    );
    this.onAdd(fresh);
    this.addOpen = false;
  }

  // —— 编辑表单 ——
  private openEdit(m: ModelInfo): void {
    this.editingId = m.id;
    this.editName = m.name;
    this.editProvider = m.provider;
    this.editContext = `${m.contextWindow}`;
    this.editMaxOut = `${m.maxOutputTokens}`;
    this.editTemp = `${m.temperature}`;
  }

  private closeEdit(): void {
    this.editingId = '';
  }

  // 编辑保留原 enabled 状态(否则编辑已停用模型会被静默重新启用)
  // 读 this.models(随父组件实时同步),不读派生缓存 visibleModels(可能滞后)
  private editingModelEnabled(): boolean {
    let enabled: boolean = true;
    this.models.forEach((m: ModelInfo): void => {
      if (m.id === this.editingId) enabled = m.enabled;
    });
    return enabled;
  }

  private confirmEdit(): void {
    if (this.editingId.length === 0) return;
    const name: string = this.editName.trim();
    if (name.length === 0) return;
    const ctx: number = parseInt(this.editContext, 10);
    const maxOut: number = parseInt(this.editMaxOut, 10);
    const temp: number = parseFloat(this.editTemp);
    const edited: ModelInfo = makeModelInfo(
      this.editingId, name, this.editProvider.trim(),
      isNaN(ctx) ? 0 : ctx,
      isNaN(maxOut) ? 0 : maxOut,
      isNaN(temp) ? 0 : temp,
      this.editingModelEnabled(),
    );
    this.onEdit(edited);
    this.editingId = '';
  }

  @Builder sortMenu(): void {
    if (this.sortMenuOpen) {
      Column() {
        Row() {
          Text('名称').fontSize(BODY).fontColor(this.sortKey === 'name' ? ACCENT : INK2)
            .layoutWeight(1)
            .onClick((): void => { this.onSortPick('name'); })
        }.width('100%').padding({ left: 14, right: 14, top: 8, bottom: 8 })
        Row() {
          Text('上下文').fontSize(BODY).fontColor(this.sortKey === 'contextWindow' ? ACCENT : INK2)
            .layoutWeight(1)
            .onClick((): void => { this.onSortPick('contextWindow'); })
        }.width('100%').padding({ left: 14, right: 14, top: 8, bottom: 8 })
        Row() {
          Text('添加顺序').fontSize(BODY).fontColor(this.sortKey === 'added' ? ACCENT : INK2)
            .layoutWeight(1)
            .onClick((): void => { this.onSortPick('added'); })
        }.width('100%').padding({ left: 14, right: 14, top: 8, bottom: 8 })
      }
      .width('50%')
      .backgroundColor(SURFACE)
      .borderRadius(8)
      .border({ width: 1, color: LINE })
      .margin({ top: 2 })
    }
  }

  @Builder addForm(): void {
    if (this.addOpen) {
      Column() {
        Text('新增模型').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .margin({ bottom: 8 })
        FlatTextField({
          value: this.addName, label: '模型名称', placeholder: 'gpt-4o',
          onChange: (v: string): void => { this.addName = v; },
        })
        FlatTextField({
          value: this.addProvider, label: 'Provider', placeholder: 'OpenAI',
          onChange: (v: string): void => { this.addProvider = v; },
        }).margin({ top: 8 })
        FlatTextField({
          value: this.addContext, label: '上下文窗口 (tokens)', placeholder: '8192',
          onChange: (v: string): void => { this.addContext = v; },
        }).margin({ top: 8 })
        FlatTextField({
          value: this.addMaxOut, label: '最大输出 tokens', placeholder: '4096',
          onChange: (v: string): void => { this.addMaxOut = v; },
        }).margin({ top: 8 })
        FlatTextField({
          value: this.addTemp, label: '采样温度', placeholder: '0.7',
          onChange: (v: string): void => { this.addTemp = v; },
        }).margin({ top: 8 })
        Row() {
          Text('取消').fontSize(BODY).fontColor(INK3)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.closeAdd(); })
          Blank()
          Text('添加').fontSize(BODY).fontColor(ACCENT).fontWeight(FontWeight.Medium)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.confirmAdd(); })
        }.width('100%').margin({ top: 10 })
      }
      .width('100%').alignItems(HorizontalAlign.Start)
      .padding(12).backgroundColor(SURFACE).borderRadius(12)
      .border({ width: 1, color: ACCENT })
      .margin({ top: 8, bottom: 8 })
    }
  }

  @Builder editForm(m: ModelInfo): void {
    if (this.editingId === m.id) {
      Column() {
        Text('编辑模型').fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .margin({ bottom: 8 })
        FlatTextField({
          value: this.editName, label: '模型名称', placeholder: 'gpt-4o',
          onChange: (v: string): void => { this.editName = v; },
        })
        FlatTextField({
          value: this.editProvider, label: 'Provider', placeholder: 'OpenAI',
          onChange: (v: string): void => { this.editProvider = v; },
        }).margin({ top: 8 })
        FlatTextField({
          value: this.editContext, label: '上下文窗口 (tokens)', placeholder: '8192',
          onChange: (v: string): void => { this.editContext = v; },
        }).margin({ top: 8 })
        FlatTextField({
          value: this.editMaxOut, label: '最大输出 tokens', placeholder: '4096',
          onChange: (v: string): void => { this.editMaxOut = v; },
        }).margin({ top: 8 })
        FlatTextField({
          value: this.editTemp, label: '采样温度', placeholder: '0.7',
          onChange: (v: string): void => { this.editTemp = v; },
        }).margin({ top: 8 })
        Row() {
          Text('取消').fontSize(BODY).fontColor(INK3)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.closeEdit(); })
          Blank()
          Text('保存').fontSize(BODY).fontColor(ACCENT).fontWeight(FontWeight.Medium)
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .onClick((): void => { this.confirmEdit(); })
        }.width('100%').margin({ top: 10 })
      }
      .width('100%').alignItems(HorizontalAlign.Start)
      .padding(12).backgroundColor(SURFACE).borderRadius(12)
      .border({ width: 1, color: ACCENT })
      .margin({ top: 8 })
    }
  }

  @Builder modelCard(m: ModelInfo): void {
    Column() {
      Row() {
        Column() {
          Text(m.name.length > 0 ? m.name : '未命名模型')
            .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
            .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
          Row() {
            if (m.provider.length > 0) {
              Tag({ type: TagType.Info, text: m.provider })
            }
            Text(`ctx ${formatNumberInt(m.contextWindow)}`)
              .fontSize(LABEL).fontColor(INK3)
              .margin({ left: 6 })
          }.margin({ top: 4 })
        }
        .alignItems(HorizontalAlign.Start).layoutWeight(1)

        AmberSwitch({
          checked: m.enabled,
          switchSize: SwitchSize.Small,
          onCheckedChange: (next: boolean): void => { this.onToggle(m.id, next); },
        }).margin({ left: 8 })
      }.width('100%').alignItems(VerticalAlign.Top)

      Row() {
        Text('编辑').fontSize(META).fontColor(ACCENT)
          .padding({ left: 8, right: 8, top: 6, bottom: 6 })
          .onClick((): void => {
            if (this.editingId === m.id) this.closeEdit(); else this.openEdit(m);
          })
        Text('删除').fontSize(META).fontColor('#C0392B')
          .padding({ left: 8, right: 4, top: 6, bottom: 6 })
          .onClick((): void => { this.onDelete(m.id); })
      }.width('100%').margin({ top: 4 })

      this.editForm(m)
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding(12).backgroundColor(SURFACE).borderRadius(12)
    .border({ width: 1, color: LINE }).margin({ bottom: 10 })
  }

  build(): void {
    Column() {
      // 搜索栏 + 排序
      Row() {
        TextInput({ text: this.query, placeholder: '搜索模型 / Provider' })
          .fontSize(BODY).fontColor(INK)
          .backgroundColor(SURFACE2).borderRadius(8)
          .layoutWeight(1)
          .padding({ top: 8, bottom: 8, left: 12, right: 12 })
          .onChange((v: string): void => { this.onQueryChange(v); })

        Text(`排序: ${this.sortLabel()} ▾`)
          .fontSize(META).fontColor(ACCENT)
          .padding({ left: 10, right: 10, top: 8, bottom: 8 })
          .backgroundColor(ACCENT_LIGHT).borderRadius(50)
          .margin({ left: 8 })
          .onClick((): void => { this.sortMenuOpen = !this.sortMenuOpen; })
      }.width('100%')

      this.sortMenu()

      // 列表 / 空态
      Scroll() {
        Column() {
          if (this.visibleModels.length === 0) {
            Column() {
              Icon({ name: 'search', iconSize: 28, color: INK4 })
              Text(this.models.length === 0 ? '暂无模型,点下方添加' : '无匹配模型')
                .fontSize(BODY).fontColor(INK3).margin({ top: 8 })
            }
            .width('100%').alignItems(HorizontalAlign.Center)
            .padding({ top: 40, bottom: 40 })
          }

          ForEach(this.visibleModels, (m: ModelInfo): void => {
            this.modelCard(m)
          }, (m: ModelInfo): string => m.id)

          this.addForm()

          // 添加按钮
          Row() {
            Text(this.addOpen ? '收起' : '+ 添加模型')
              .fontSize(BODY).fontColor(ACCENT)
              .padding({ left: 14, right: 14, top: 8, bottom: 8 })
              .backgroundColor(SURFACE2).borderRadius(50)
              .onClick((): void => { if (this.addOpen) this.closeAdd(); else this.openAdd(); })
          }.width('100%').margin({ top: 4, bottom: 24 })
        }
        .width('100%')
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%')
  }
}

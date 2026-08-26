// PromptTemplatePage — 提示词模板编辑器(简化自 Android PromptPage.kt 1176 行)
// 功能:模板名/内容多行/变量检测({{var}})/预览渲染/分类/剪贴板导入导出/保存删除/列表。
// 偏差登记:
//   - Android PromptPage 双 tab(模式注入/世界书)不在本页范围;本页为独立模板库,
//     存储键 'prompt_templates'(我方 schema,Android 无对应 blob,P1 登记)。
//   - 变量语法 {{name}},预览用内置样例值替换;lastUsed/createdAt 未落地(P1)。
//   - 文件 picker 导入导出未移植(外部目录 picker 先例),改用剪贴板 JSON。
import { router } from '@kit.ArkUI';
import { Icon } from '../components/ui/Icon.ets';
import { pasteboard } from '@kit.BasicServicesKit';
import { newId } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { PillSelect } from '../components/ui/Select.ets';
import {
  INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, ACCENT_LIGHT,
  BODY, META, LABEL, TITLE, GUTTER, RADIUS,
} from '../design/tokens.ets';

interface RouterParams {
  promptId?: string;
}

interface PromptTemplate {
  id: string;
  name: string;
  content: string;
  category: string;
  updatedAt: number;
}

// JSON 导入/导出结构(字段均可缺省)
interface ImportedTemplate {
  name?: string;
  content?: string;
  category?: string;
}

const PROMPT_TEMPLATES_KEY: string = 'prompt_templates';
const CATEGORIES: string[] = ['general', 'role', 'task', 'format'];
const CATEGORY_LABELS: string[] = ['通用', '角色', '任务', '格式'];

const categoryIndexOf = (cat: string): number => {
  const i: number = CATEGORIES.indexOf(cat);
  return i < 0 ? 0 : i;
};

// 变量检测:扫描 {{name}},去重保序(arkts-no-spread:用 indexOf 去重)
const detectVariables = (content: string): string[] => {
  const re: RegExp = /\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*\}\}/g;
  const out: string[] = [];
  let m: RegExpExecArray | null = re.exec(content);
  while (m !== null) {
    const name: string = m[1];
    if (out.indexOf(name) < 0) {
      out.push(name);
    }
    m = re.exec(content);
  }
  return out;
};

// 样例值:常见变量名映射,其余回退占位
const sampleValue = (name: string): string => {
  if (name === 'topic' || name === 'title') return '示例主题';
  if (name === 'user' || name === 'name') return '张三';
  if (name === 'message' || name === 'input') return '你好,请帮我写一段介绍。';
  if (name === 'role') return '助手';
  if (name === 'language') return '中文';
  if (name === 'tone') return '正式';
  return `<${name}>`;
};

const renderPreview = (content: string): string => {
  let out: string = content;
  const vars: string[] = detectVariables(content);
  for (const v of vars) {
    const re: RegExp = new RegExp('\\{\\{\\s*' + v + '\\s*\\}\\}', 'g');
    out = out.replace(re, sampleValue(v));
  }
  return out;
};

@Entry
@Component
struct PromptTemplatePage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @State mode: string = 'list'; // 'list' | 'edit'
  @State templates: PromptTemplate[] = [];
  @State loaded: boolean = false;
  @State editId: string = '';
  @State editName: string = '';
  @State editContent: string = '';
  @State editCategory: string = 'general';
  @State editCategoryIndex: number = 0;
  @State statusMsg: string = '';
  private deepLinked: boolean = false;

  aboutToAppear(): void {
    const params = router.getParams() as RouterParams;
    const pid: string = params !== null && params.promptId !== undefined ? params.promptId : '';
    this.deepLinked = pid.length > 0;
    this.refresh().then((): void => {
      if (this.deepLinked) {
        const found: PromptTemplate | null =
          this.templates.find((t: PromptTemplate): boolean => t.id === pid) ?? null;
        if (found !== null) {
          this.startEdit(found);
        } else {
          this.startCreate();
        }
      }
    });
  }

  private async refresh(): Promise<void> {
    const raw: string | null = await getChatKvStore().get(PROMPT_TEMPLATES_KEY);
    if (raw === null) { this.templates = []; this.loaded = true; return; }
    try {
      const arr = JSON.parse(raw) as PromptTemplate[];
      this.templates = arr;
    } catch (e) {
      this.templates = [];
    }
    this.loaded = true;
  }

  private async persist(list: PromptTemplate[]): Promise<void> {
    await getChatKvStore().put(PROMPT_TEMPLATES_KEY, JSON.stringify(list));
  }

  private startCreate(): void {
    this.editId = '';
    this.editName = '';
    this.editContent = '';
    this.editCategory = 'general';
    this.editCategoryIndex = 0;
    this.statusMsg = '';
    this.mode = 'edit';
  }

  private startEdit(t: PromptTemplate): void {
    this.editId = t.id;
    this.editName = t.name;
    this.editContent = t.content;
    this.editCategory = t.category;
    this.editCategoryIndex = categoryIndexOf(t.category);
    this.statusMsg = '';
    this.mode = 'edit';
  }

  private async save(): Promise<void> {
    const name: string = this.editName.trim();
    if (name.length === 0) { this.statusMsg = '名称不能为空'; return; }
    const now: number = Date.now();
    const t: PromptTemplate = {
      id: this.editId.length > 0 ? this.editId : newId(),
      name: name,
      content: this.editContent,
      category: this.editCategory,
      updatedAt: now,
    };
    const list: PromptTemplate[] = this.templates.slice();
    const idx: number = list.findIndex((x: PromptTemplate): boolean => x.id === t.id);
    if (idx >= 0) list[idx] = t; else list.push(t);
    await this.persist(list);
    this.templates = list;
    this.statusMsg = '已保存';
    if (this.deepLinked) {
      router.back();
    } else {
      this.mode = 'list';
    }
  }

  private async deleteCurrent(): Promise<void> {
    if (this.editId.length === 0) {
      this.mode = 'list';
      return;
    }
    const list: PromptTemplate[] = this.templates.filter(
      (x: PromptTemplate): boolean => x.id !== this.editId);
    await this.persist(list);
    this.templates = list;
    if (this.deepLinked) {
      router.back();
    } else {
      this.mode = 'list';
    }
  }

  // ===== 剪贴板导入/导出 =====

  private readClipboard(): string {
    try {
      const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      if (!board.hasDataSync()) return '';
      return board.getDataSync().getPrimaryText() ?? '';
    } catch (e) {
      return '';
    }
  }

  private writeClipboard(text: string): void {
    const board: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
    board.setDataSync(pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, text));
  }

  private exportToClipboard(): void {
    const obj: ImportedTemplate = {
      name: this.editName,
      content: this.editContent,
      category: this.editCategory,
    };
    this.writeClipboard(JSON.stringify(obj, null, 2));
    this.statusMsg = '已复制到剪贴板';
  }

  private importFromClipboard(): void {
    const text: string = this.readClipboard();
    if (text.trim().length === 0) { this.statusMsg = '剪贴板为空'; return; }
    try {
      const obj = JSON.parse(text) as ImportedTemplate;
      if (obj.name !== undefined) this.editName = obj.name;
      if (obj.content !== undefined) this.editContent = obj.content;
      if (obj.category !== undefined) {
        this.editCategory = obj.category;
        this.editCategoryIndex = categoryIndexOf(obj.category);
      }
      this.statusMsg = '已导入';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.statusMsg = `导入失败:${msg.substring(0, 80)}`;
    }
  }

  // 列表页导入:剪贴板 JSON → 新建模板并入列表
  private async importToList(): Promise<void> {
    const text: string = this.readClipboard();
    if (text.trim().length === 0) { this.statusMsg = '剪贴板为空'; return; }
    try {
      const obj = JSON.parse(text) as ImportedTemplate;
      const name: string = obj.name !== undefined ? obj.name.trim() : '';
      if (name.length === 0) { this.statusMsg = 'name 不能为空'; return; }
      const cat: string = obj.category !== undefined ? obj.category : 'general';
      const t: PromptTemplate = {
        id: newId(),
        name: name,
        content: obj.content !== undefined ? obj.content : '',
        category: cat,
        updatedAt: Date.now(),
      };
      const list: PromptTemplate[] = [...this.templates, t];
      await this.persist(list);
      this.templates = list;
      this.statusMsg = '已导入模板';
    } catch (e) {
      const msg: string = e instanceof Error ? e.message : String(e);
      this.statusMsg = `导入失败:${msg.substring(0, 80)}`;
    }
  }

  private backFromEdit(): void {
    if (this.deepLinked) { router.back(); return; }
    this.mode = 'list';
  }

  private contentPreview(t: PromptTemplate): string {
    const c: string = t.content.trim();
    if (c.length === 0) return '(无内容)';
    const nl: number = c.indexOf('\n');
    return nl > 0 ? c.substring(0, nl) : c;
  }

  @Builder
  templateCard(t: PromptTemplate) {
    Column() {
      Row() {
        Text(t.name.length > 0 ? t.name : '未命名模板')
          .fontSize(BODY).fontWeight(FontWeight.Medium).fontColor(INK)
          .layoutWeight(1)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        Text(CATEGORY_LABELS[categoryIndexOf(t.category)])
          .fontSize(LABEL).fontColor(ACCENT).maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .padding({ left: 6, right: 6, top: 2, bottom: 2 })
          .backgroundColor(ACCENT_LIGHT).borderRadius(6)
      }
      .width('100%')
      Text(this.contentPreview(t))
        .fontSize(META).fontColor(INK3).margin({ top: 4 })
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
    }
    .width('100%').alignItems(HorizontalAlign.Start)
    .padding({ left: GUTTER, right: GUTTER, top: 14, bottom: 14 })
    .backgroundColor(SURFACE).borderRadius(RADIUS)
    .border({ width: 1, color: LINE }).margin({ bottom: 8 })
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Icon({ name: 'back', iconSize: 22, color: INK3 })
          .onClick((): void => {
            if (this.mode === 'edit') this.backFromEdit(); else router.back();
          })
        Text('提示词模板').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: this.statusBarHeight + 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Column() {
          Text('加载中…').fontSize(BODY).fontColor(INK3)
        }.width('100%').padding({ top: 48, bottom: 48 }).alignItems(HorizontalAlign.Center)
      } else if (this.mode === 'list') {
        // ===== 列表模式 =====
        Row() {
          Text('+ 新建').fontSize(BODY).fontColor(Color.White).maxLines(1)
            .backgroundColor(ACCENT).borderRadius(8)
            .padding({ left: 16, right: 16, top: 6, bottom: 6 })
            .constraintSize({ minHeight: 40 })
            .onClick((): void => { this.startCreate(); })
          Blank()
          Text('导入').fontSize(BODY).fontColor(ACCENT).maxLines(1)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .constraintSize({ minHeight: 40 })
            .onClick((): void => { this.importToList(); })
        }
        .width('100%').padding({ left: GUTTER, right: GUTTER, top: 8, bottom: 8 })

        if (this.statusMsg.length > 0) {
          Text(this.statusMsg).fontSize(LABEL).fontColor(INK3)
            .padding({ left: 16, bottom: 4 })
        }

        if (this.templates.length === 0) {
          Column() {
            Text('暂无模板').fontSize(BODY).fontColor(INK3)
            Text('点击「新建」创建第一个提示词模板').fontSize(META).fontColor(INK3)
              .margin({ top: 8 })
          }
          .width('100%').padding({ top: 64, bottom: 64 }).alignItems(HorizontalAlign.Center)
        } else {
          Scroll() {
            Column() {
              ForEach(this.templates, (t: PromptTemplate): void => {
                this.templateCard(t)
              }, (t: PromptTemplate): string => t.id)
            }
            .width('100%').padding({ left: GUTTER, right: GUTTER, bottom: 16 })
          }
        .align(Alignment.TopStart)
          .width('100%').layoutWeight(1)
        }
      } else {
        // ===== 编辑模式 =====
        Scroll() {
          Column() {
            Text(this.editId.length > 0 ? '编辑模板' : '新建模板')
              .fontSize(BODY).fontColor(INK2).width('100%').margin({ bottom: 12 })

            Text('名称').fontSize(META).fontColor(INK3).width('100%').margin({ bottom: 4 })
            TextInput({ text: this.editName, placeholder: '模板名称' })
              .fontSize(BODY).height(40).backgroundColor(SURFACE)
              .borderRadius(8).margin({ bottom: 12 })
              .onChange((v: string): void => { this.editName = v; })

            Row() {
              Text('分类').fontSize(META).fontColor(INK3).margin({ right: 12 })
              PillSelect({
                options: CATEGORY_LABELS,
                selectedIndex: this.editCategoryIndex,
                onSelect: (i: number): void => {
                  this.editCategoryIndex = i;
                  this.editCategory = CATEGORIES[i];
                },
              })
            }
            .width('100%').margin({ bottom: 12 })

            Text('内容').fontSize(META).fontColor(INK3).width('100%').margin({ bottom: 4 })
            TextArea({ text: this.editContent, placeholder: '模板内容,可用 {{变量}} 插入变量' })
              .fontSize(BODY).height(160).backgroundColor(SURFACE)
              .borderRadius(8).margin({ bottom: 12 })
              .onChange((v: string): void => { this.editContent = v; })

            // 变量检测:扫描 {{var}},去重保序
            Text('变量').fontSize(META).fontColor(INK3).width('100%').margin({ bottom: 4 })
            if (detectVariables(this.editContent).length === 0) {
              Text('未检测到变量(用 {{name}} 语法插入)').fontSize(LABEL).fontColor(INK4)
                .width('100%').margin({ bottom: 12 })
            } else {
              Flex({ wrap: FlexWrap.Wrap }) {
                ForEach(detectVariables(this.editContent), (v: string): void => {
                  Text(v)
                    .fontSize(META).fontColor(ACCENT)
                    .padding({ left: 10, right: 10, top: 4, bottom: 4 })
                    .backgroundColor(ACCENT_LIGHT).borderRadius(12)
                    .border({ width: 1, color: ACCENT })
                    .margin({ right: 8, bottom: 8 })
                }, (v: string): string => v)
              }
              .width('100%').margin({ bottom: 12 })
            }

            // 预览:用样例值替换变量
            Text('预览').fontSize(META).fontColor(INK3).width('100%').margin({ bottom: 4 })
            Text(renderPreview(this.editContent))
              .fontSize(BODY).fontColor(INK2)
              .width('100%').backgroundColor(SURFACE2).borderRadius(RADIUS)
              .maxLines(4).textOverflow({ overflow: TextOverflow.Ellipsis })
              .padding(12).margin({ bottom: 12 })

            // 导入/导出
            Row() {
              Text('导出').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                .backgroundColor(ACCENT_LIGHT).borderRadius(8)
                .onClick((): void => { this.exportToClipboard(); })
              Text('导入').fontSize(BODY).fontColor(ACCENT)
                .padding({ left: 12, right: 12, top: 6, bottom: 6 })
                .backgroundColor(ACCENT_LIGHT).borderRadius(8)
                .margin({ left: 8 })
                .onClick((): void => { this.importFromClipboard(); })
              Blank()
              if (this.statusMsg.length > 0) {
                Text(this.statusMsg).fontSize(LABEL).fontColor(INK3)
              }
            }
            .width('100%').margin({ bottom: 16 })

            // 保存/删除
            Row() {
              if (this.editId.length > 0) {
                Text('删除')
                  .fontSize(BODY).fontColor('#C93A2F')
                  .padding({ left: 20, right: 20, top: 8, bottom: 8 })
                  .border({ width: 1, color: '#C93A2F' }).borderRadius(8)
                  .onClick((): void => { this.deleteCurrent(); })
              }
              Blank()
              Text('取消')
                .fontSize(BODY).fontColor(INK3)
                .padding({ left: 20, right: 20, top: 8, bottom: 8 })
                .onClick((): void => { this.backFromEdit(); })
              Text('保存')
                .fontSize(BODY).fontColor(Color.White)
                .backgroundColor(ACCENT).borderRadius(8)
                .padding({ left: 24, right: 24, top: 8, bottom: 8 })
                .margin({ left: 8 })
                .onClick((): void => { this.save(); })
            }
            .width('100%')

            Column() {}.height(32)
          }
          .width('100%').padding({ left: GUTTER, right: GUTTER, top: 12 })
        }
        .align(Alignment.TopStart)
        .width('100%').layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}

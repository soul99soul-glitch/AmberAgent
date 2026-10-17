import type {
  SmartUiComponent, SmartUiComponentCatalogEntry, SmartUiComponentFamily, SmartUiComponentKind,
  SmartUiDraftAction, SmartUiOption, SmartUiScalar,
} from '@amber/chat-domain';
import { SMART_UI_COMPONENT_CATALOG } from '@amber/chat-domain';

// These payloads enter the normal response parser and renderer through ChatVisualFixture.
// They contain local example data only; submission prepares a draft for explicit user sending.
interface ComponentFixtureRecord {
  op: string;
  id?: string;
  initial?: SmartUiScalar;
  parent?: string;
  type?: string;
  text?: string;
  variant?: string;
  component?: SmartUiComponent;
  state?: string;
  label?: string;
  required?: boolean;
  options?: SmartUiOption[];
  action?: SmartUiDraftAction;
}

const FAMILIES: SmartUiComponentFamily[] = ['layout', 'text', 'status', 'input', 'data', 'media', 'task', 'action'];
const FAMILY_LABELS: string[] = ['布局与容器', '文字与说明', '状态与反馈', '输入与选择', '数据与比较', '媒体与资料', '任务与决策', '操作与展开'];
const KIND_LABELS: string[] = [
  '堆叠', '纵向布局', '横向布局', '网格', '卡片', '面板', '分组', '缩进', '分隔线', '留白',
  '标题', '副标题', '正文', '说明', '提示框', '引用', '行内代码', '项目列表', '编号列表', '键值', '脚注', '重点',
  '徽标', '信息胶囊', '标签', '状态点', '进度', '步骤进度', '加载占位', '空状态', '通知', '警示',
  '单选', '多选', '分段选择', '选项选择器', '开关', '文本输入', '多行输入', '数字输入', '滑块', '步进', '日期', '时间', '评分', '颜色选择',
  '指标', '指标网格', '表格', '列表', '比较', '时间线', '步骤', '检查清单', '柱状图', '折线图',
  '图片', '图片网格', '头像', '图标', '链接卡片', '文件卡片', '代码块', '示意图',
  '目标', '背景', '来源', '理由', '取舍', '结果', '假设', '决策摘要',
  '按钮', '按钮组', '提交按钮', '重置按钮', '展开详情', '分页标签', '折叠组', '菜单',
];

const fixtureBlock = (id: string, records: ComponentFixtureRecord[]): string => {
  let content: string = `\`\`\`amber-ui v=1 id=${id}\n`;
  for (const record of records) content += JSON.stringify(record) + '\n';
  return content + '{"op":"end"}\n```';
};

const componentRecord = (id: string, parent: string, component: SmartUiComponent): ComponentFixtureRecord =>
  ({ op: 'node', id, parent, type: 'component', component });

const sampleComponent = (kind: SmartUiComponentKind, label: string): SmartUiComponent => {
  const component: SmartUiComponent = { kind, title: label };
  if (['title', 'subtitle', 'body', 'caption', 'callout', 'quote', 'footnote', 'highlight',
    'emptyState', 'notice', 'alert', 'goal', 'context', 'source', 'reason', 'tradeoff', 'result', 'assumptions', 'decisionSummary'].includes(kind)) {
    component.text = kind === 'source' ? '数据来自本地演示，不代表实时查询。' : `${label}：把信息整理成可直接阅读和操作的内容。`;
  }
  if (['inlineCode', 'codeBlock'].includes(kind)) component.text = 'const budget = 50;';
  if (['bulletList', 'numberedList', 'stepProgress', 'steps', 'diagram'].includes(kind)) {
    component.items = ['确定目标', '比较方案', '确认选择'];
  }
  if (['keyValue', 'table', 'list', 'comparison', 'timeline', 'barChart', 'lineChart'].includes(kind)) {
    component.rows = [
      { label: '方案 A', value: 40, detail: '本地演示：步骤少', cells: ['轻量方案', '40 分钟'] },
      { label: '方案 B', value: 70, detail: '本地演示：信息更充分', cells: ['深入方案', '70 分钟'] },
    ];
  }
  if (kind === 'progress') { component.value = 2; component.max = 3; }
  if (kind === 'stepProgress') component.value = 1;
  if (kind === 'metric') { component.value = 50; component.text = '分钟'; }
  if (kind === 'grid') component.columns = 2;
  if (['numberField', 'slider', 'stepper'].includes(kind)) {
    component.min = 0; component.max = 100; component.step = 5;
  }
  if (kind === 'rating') { component.min = 0; component.max = 5; component.step = 1; }
  if (['badge', 'chip', 'tag', 'statusDot'].includes(kind)) component.text = '本地示例';
  if (['image', 'avatar', 'icon'].includes(kind)) component.symbol = kind === 'avatar' ? 'A' : '▧';
  if (kind === 'fileCard') component.text = '方案说明.txt · 本地示例';
  if (kind === 'linkCard') { component.text = 'HarmonyOS 官方资料'; component.url = 'https://developer.huawei.com/consumer/cn/'; }
  if (kind === 'image') component.text = '图片占位：不依赖网络资源';
  if (kind === 'avatar') component.text = '本地头像';
  if (['card', 'panel', 'callout', 'notice', 'goal'].includes(kind)) component.style = { tone: 'accent', emphasis: 'soft' };
  if (kind === 'card') component.style = { tone: 'danger', emphasis: 'filled', padding: 'small' };
  if (kind === 'grid') component.style = { tone: 'accent', emphasis: 'filled', padding: 'small' };
  if (kind === 'alert') component.style = { tone: 'warning', emphasis: 'outlined' };
  return component;
};

const optionSamples = (kind: SmartUiComponentKind): SmartUiOption[] => kind === 'colorChoice' ? [
  { value: '#6D5EF7', label: '紫色', color: 'purple' }, { value: '#2F8A69', label: '绿色', color: 'green' },
] : [{ value: 'brief', label: '轻量方案' }, { value: 'deep', label: '深入方案' }];

const initialValue = (entry: SmartUiComponentCatalogEntry, childId: string): SmartUiScalar => {
  const kind: SmartUiComponentKind = entry.kind;
  if (entry.stateType === 'boolean') return true;
  if (entry.stateType === 'number') return kind === 'rating' ? 3 : 50;
  if (kind === 'multiChoice' || kind === 'checklist') return '["brief"]';
  if (kind === 'accordion') return JSON.stringify([childId]);
  if (kind === 'tabs') return childId;
  if (kind === 'datePicker') return '2026-10-12';
  if (kind === 'timePicker') return '09:30';
  if (kind === 'colorChoice') return '#6D5EF7';
  if (kind === 'textField' || kind === 'textArea') return '优先照顾时间有限的情况';
  return 'brief';
};

export const debugSmartUiComponentGalleryPayload = (): string => {
  const blocks: string[] = [];
  for (let familyIndex: number = 0; familyIndex < FAMILIES.length; familyIndex++) {
    const family: SmartUiComponentFamily = FAMILIES[familyIndex];
    const records: ComponentFixtureRecord[] = [
      { op: 'node', id: 'root', type: 'column' },
      { op: 'node', id: 'heading', parent: 'root', type: 'text', variant: 'heading', text: FAMILY_LABELS[familyIndex] },
      { op: 'node', id: 'intro', parent: 'root', type: 'text', variant: 'caption', text: '本地组件示例；选择和展开即时生效，按钮仅准备聊天草稿。' },
    ];
    for (let index: number = 0; index < SMART_UI_COMPONENT_CATALOG.length; index++) {
      const entry: SmartUiComponentCatalogEntry = SMART_UI_COMPONENT_CATALOG[index];
      if (entry.family !== family) continue;
      const id: string = `sample-${entry.kind}`;
      const childId: string = `${id}-content`;
      const node: ComponentFixtureRecord = componentRecord(id, 'root', sampleComponent(entry.kind, KIND_LABELS[index]));
      node.label = KIND_LABELS[index];
      if (entry.requiresState) {
        node.state = `value-${entry.kind}`;
        records.push({ op: 'state', id: node.state, initial: initialValue(entry, childId) });
      }
      if (['singleChoice', 'multiChoice', 'segmented', 'picker', 'colorChoice', 'checklist', 'menu'].includes(entry.kind)) node.options = optionSamples(entry.kind);
      if (entry.kind === 'button' || entry.kind === 'submitButton') node.action = { type: 'draft', text: `请进一步解释${KIND_LABELS[index]}的用途。` };
      records.push(node);
      if (entry.allowsChildren) {
        const child: SmartUiComponent = entry.kind === 'metricGrid' ? { kind: 'metric', title: '示例预算', value: 50, text: '分钟' } :
          entry.kind === 'imageGrid' ? { kind: 'image', title: '本地占位', symbol: '▧' } :
          entry.kind === 'buttonGroup' ? { kind: 'button', title: '准备问题' } :
          { kind: 'caption', title: '示例详情', text: '这段内容由父容器组织，保持信息层级清晰。' };
        const childNode: ComponentFixtureRecord = componentRecord(childId, id, child);
        childNode.label = '示例详情';
        if (entry.kind === 'buttonGroup') childNode.action = { type: 'draft', text: '请比较轻量方案与深入方案。' };
        records.push(childNode);
      }
    }
    blocks.push(fixtureBlock(`component-${family}`, records));
  }
  return '以下展示八类原生智能 UI 组件，所有示例数据均在本地。\n\n' + blocks.join('\n\n');
};

export const debugSmartUiTaskPayload = (): string => fixtureBlock('local-decision', [
  { op: 'state', id: 'plan', initial: 'brief' },
  { op: 'state', id: 'budget', initial: 50 },
  { op: 'state', id: 'constraints', initial: '' },
  { op: 'node', id: 'root', type: 'column' },
  componentRecord('goal', 'root', { kind: 'goal', title: '安排一次学习', text: '选择本地方案、时间预算和约束，再准备一条可修改的聊天问题。', style: { tone: 'accent', emphasis: 'soft' } }),
  { op: 'node', id: 'plan-choice', parent: 'root', type: 'component', component: { kind: 'singleChoice', title: '本地方案' }, label: '本地方案', state: 'plan', options: optionSamples('singleChoice') },
  { op: 'node', id: 'budget-slider', parent: 'root', type: 'component', component: { kind: 'slider', title: '时间预算（分钟）', min: 20, max: 100, step: 5 }, label: '时间预算（分钟）', state: 'budget' },
  { op: 'node', id: 'constraints-field', parent: 'root', type: 'component', component: { kind: 'textArea', title: '约束与偏好', text: '例如：只有午休时间，希望先掌握核心概念。' }, label: '约束与偏好', state: 'constraints', required: true },
  componentRecord('progress', 'root', { kind: 'progress', title: '准备步骤', value: 1, max: 3, text: '选择方案 → 补充约束 → 准备草稿' }),
  { op: 'node', id: 'submit', parent: 'root', type: 'component', component: { kind: 'submitButton', title: '准备学习问题' }, label: '准备学习问题', action: { type: 'draft', text: '请为我安排一次学习。选择的本地方案：{{plan}}；时间预算：{{budget}} 分钟；约束与偏好：{{constraints}}。请解释方案并列出具体步骤。' } },
  { op: 'node', id: 'reset', parent: 'root', type: 'component', component: { kind: 'resetButton', title: '重置选择' }, label: '重置选择' },
]);

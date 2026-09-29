// JsonTree — JSON 树查看器(对齐 Android JsonTree.kt)
// 可展开/折叠的 JSON 结构渲染,支持对象/数组/字符串/数字/布尔/null
// 值颜色: string=#6A8759, boolean=#CC7832, number=#6897BB, null=outline
import { INK2, INK3, ACCENT, META } from '../../design/tokens.ets';
import { FONT_MONO } from '../../design/fonts.ets';

// JSON 节点类型
type JsonNodeType = 'object' | 'array' | 'string' | 'number' | 'boolean' | 'null';

interface JsonNodeData {
  key: string;
  type: JsonNodeType;
  value: string;          // 原始值(string/number/boolean/null)
  children: JsonNodeData[]; // object/array 的子节点
  childCount: number;     // object/array 的子节点数
}

// 解析 JSON 字符串为 JsonNodeData 树
const parseJsonToTree = (jsonStr: string): JsonNodeData | null => {
  try {
    const parsed: object = JSON.parse(jsonStr);
    return buildNode('', parsed);
  } catch (_e) {
    return null;
  }
};

const buildNode = (key: string, value: object): JsonNodeData => {
  if (value === null) {
    return { key, type: 'null', value: 'null', children: [], childCount: 0 } as JsonNodeData;
  }
  if (typeof value === 'string') {
    return { key, type: 'string', value: value, children: [], childCount: 0 } as JsonNodeData;
  }
  if (typeof value === 'number') {
    const numVal: number = value as number;
    return { key, type: 'number', value: `${numVal}`, children: [], childCount: 0 } as JsonNodeData;
  }
  if (typeof value === 'boolean') {
    const boolVal: boolean = value as boolean;
    return { key, type: 'boolean', value: boolVal ? 'true' : 'false', children: [], childCount: 0 } as JsonNodeData;
  }
  if (Array.isArray(value)) {
    const children: JsonNodeData[] = [];
    for (let i = 0; i < value.length; i++) {
      children.push(buildNode(i.toString(), value[i] as object));
    }
    return { key, type: 'array', value: '', children, childCount: value.length } as JsonNodeData;
  }
  // object
  const keys: string[] = Object.keys(value);
  const children: JsonNodeData[] = [];
  for (const k of keys) {
    children.push(buildNode(k, (value as Record<string, object>)[k]));
  }
  return { key, type: 'object', value: '', children, childCount: keys.length } as JsonNodeData;
};

const valueColor = (type: JsonNodeType, dark: boolean): string => {
  if (type === 'string') return dark ? '#98C379' : '#6A8759';
  if (type === 'boolean') return dark ? '#D19A66' : '#CC7832';
  if (type === 'number') return dark ? '#61AFEF' : '#6897BB';
  if (type === 'null') return INK3;
  return INK2;
};

const formatValue = (type: JsonNodeType, value: string): string => {
  if (type === 'string') return `"${value}"`;
  return value;
};

const bracketOpen = (type: JsonNodeType): string => {
  return type === 'object' ? '{' : '[';
};

const bracketClose = (type: JsonNodeType): string => {
  return type === 'object' ? '}' : ']';
};

@Component
export struct JsonTree {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('darkMode') darkMode: boolean = false;
  @Prop json: string = '';
  @State root: JsonNodeData | null = null;

  aboutToAppear(): void {
    this.root = parseJsonToTree(this.json);
  }

  build(): void {
    Column() {
      if (this.root !== null) {
        this.RenderNode(this.root as JsonNodeData, 0)
      } else {
        Text('Invalid JSON').fontSize(META).fontColor(INK3)
      }
    }
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  RenderNode(node: JsonNodeData, depth: number): void {
    if (node.type === 'object' || node.type === 'array') {
      this.RenderContainerNode(node, depth)
    } else {
      // 叶子节点
      Row() {
        if (node.key.length > 0) {
          Text(`"${node.key}"`)
            .fontSize(META)
            .fontColor(ACCENT)
            .fontFamily(FONT_MONO)
            .margin({ right: 4 })
          Text(': ')
            .fontSize(META)
            .fontColor(INK2)
            .fontFamily(FONT_MONO)
        }
        Text(formatValue(node.type, node.value))
          .fontSize(META)
          .fontColor(valueColor(node.type, this.darkMode))
          .fontFamily(FONT_MONO)
      }
      .width('100%')
      .padding({ left: depth * 16 + 14, top: 2, bottom: 2 })
    }
  }

  @Builder
  RenderContainerNode(node: JsonNodeData, depth: number): void {
    Column() {
      // 展开头部
      Row() {
        Text(node.children.length > 0 ? '▾' : '▸')
          .fontSize(10)
          .fontColor(INK3)
          .margin({ right: 4 })
        if (node.key.length > 0) {
          Text(`"${node.key}"`)
            .fontSize(META)
            .fontColor(ACCENT)
            .fontFamily(FONT_MONO)
            .margin({ right: 4 })
          Text(': ')
            .fontSize(META)
            .fontColor(INK2)
            .fontFamily(FONT_MONO)
        }
        Text(bracketOpen(node.type))
          .fontSize(META)
          .fontColor(INK2)
          .fontFamily(FONT_MONO)
        Text(` ${node.childCount} `)
          .fontSize(META)
          .fontColor(INK3)
          .fontFamily(FONT_MONO)
        Text(bracketClose(node.type))
          .fontSize(META)
          .fontColor(INK2)
          .fontFamily(FONT_MONO)
      }
      .width('100%')
      .padding({ left: depth * 16, top: 2, bottom: 2 })

      // 子节点
      ForEach(node.children, (child: JsonNodeData, idx: number): void => {
        this.RenderNode(child, depth + 1)
      }, (child: JsonNodeData, idx: number): string => `json-${depth}-${idx}`)

      // 闭合括号
      Row() {
        Text(bracketClose(node.type))
          .fontSize(META)
          .fontColor(INK2)
          .fontFamily(FONT_MONO)
      }
      .width('100%')
      .padding({ left: depth * 16 + 14, top: 0, bottom: 2 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }
}

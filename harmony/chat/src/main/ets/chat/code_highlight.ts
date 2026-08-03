// code_highlight — 轻量代码块语法高亮(M2.3)
//
// Android 基准: richtext/HighlightCodeBlock.kt(517 行)— 自建 tokenizer + 着色
//   鸿蒙 MVP:轻量正则 + 状态机,覆盖 6 主流语言核心 token(关键字/字符串/注释/数字/操作符)
//   不引外部依赖(ohpm 可用性未验证 + ArkTS 互操作风险)
//
// 输出:HTML span 串(已转义 + 着色),供 markdown_html 的 <pre><code> 注入。
//   RichText 渲染 <span style="color:#xxx"> 不确定(无设备验证),降级链:
//     <span style> → <font color> → 纯文本(escapeHtml only)
//
// 降级(诚实):
//   - 未知语言 → 纯文本着色(escapeHtml,不伪高亮)
//   - 嵌套结构(模板字符串/多行注释)简化处理(单行正则,跨行边界不完美 = 显示级,登记)

export interface HighlightToken {
  text: string;
  type: HighlightTokenType;
}

export type HighlightTokenType =
  | 'keyword' | 'string' | 'comment' | 'number' | 'operator' | 'plain';

// 颜色映射(对齐 Android HighlightCodeBlock 色板:关键字紫 / 字符串绿 / 注释灰 / 数字橙)
export const HIGHLIGHT_COLORS: Record<HighlightTokenType, string> = {
  keyword: '#C678DD',
  string: '#98C379',
  comment: '#7F848E',
  number: '#D19A66',
  operator: '#56B6C2',
  plain: '#ABB2BF',
};

// 6 语言关键字表
const KEYWORDS: Record<string, string[]> = {
  javascript: [
    'const', 'let', 'var', 'function', 'return', 'if', 'else', 'for', 'while',
    'do', 'switch', 'case', 'break', 'continue', 'new', 'class', 'extends',
    'super', 'this', 'typeof', 'instanceof', 'in', 'of', 'try', 'catch',
    'finally', 'throw', 'async', 'await', 'yield', 'import', 'export',
    'default', 'from', 'as', 'delete', 'void', 'null', 'undefined', 'true',
    'false', 'static', 'get', 'set',
  ],
  typescript: [
    'const', 'let', 'var', 'function', 'return', 'if', 'else', 'for', 'while',
    'do', 'switch', 'case', 'break', 'continue', 'new', 'class', 'extends',
    'implements', 'interface', 'type', 'enum', 'super', 'this', 'typeof',
    'instanceof', 'in', 'of', 'try', 'catch', 'finally', 'throw', 'async',
    'await', 'yield', 'import', 'export', 'default', 'from', 'as', 'delete',
    'void', 'null', 'undefined', 'true', 'false', 'static', 'get', 'set',
    'public', 'private', 'protected', 'readonly', 'abstract', 'namespace',
    'declare', 'is', 'keyof', 'infer', 'never', 'unknown', 'any', 'string',
    'number', 'boolean', 'object', 'symbol', 'bigint',
  ],
  python: [
    'def', 'class', 'return', 'if', 'elif', 'else', 'for', 'while', 'break',
    'continue', 'pass', 'import', 'from', 'as', 'try', 'except', 'finally',
    'raise', 'with', 'yield', 'lambda', 'global', 'nonlocal', 'del', 'assert',
    'in', 'not', 'and', 'or', 'is', 'None', 'True', 'False', 'self', 'cls',
    'async', 'await', 'print',
  ],
  kotlin: [
    'fun', 'val', 'var', 'class', 'object', 'interface', 'return', 'if', 'else',
    'for', 'while', 'do', 'when', 'is', 'in', 'as', 'try', 'catch', 'finally',
    'throw', 'import', 'package', 'private', 'public', 'protected', 'internal',
    'override', 'open', 'abstract', 'sealed', 'data', 'enum', 'companion',
    'suspend', 'vararg', 'lateinit', 'inline', 'reified', 'by', 'init',
    'constructor', 'super', 'this', 'null', 'true', 'false', 'typeof',
  ],
  json: ['true', 'false', 'null'],
  bash: [
    'if', 'then', 'else', 'elif', 'fi', 'for', 'in', 'do', 'done', 'while',
    'case', 'esac', 'function', 'return', 'break', 'continue', 'echo', 'exit',
    'export', 'local', 'readonly', 'source', 'alias', 'unset', 'set', 'shift',
  ],
};

// 语言别名归一(js → javascript 等)
const normalizeLang = (lang: string): string => {
  const l: string = lang.toLowerCase().trim();
  if (l === 'js' || l === 'jsx' || l === 'javascript') return 'javascript';
  if (l === 'ts' || l === 'tsx' || l === 'typescript') return 'typescript';
  if (l === 'py' || l === 'python') return 'python';
  if (l === 'kt' || l === 'kotlin') return 'kotlin';
  if (l === 'json') return 'json';
  if (l === 'sh' || l === 'bash' || l === 'shell' || l === 'zsh') return 'bash';
  return '';
};

// tokenize:将代码拆为 token 序列(关键字/字符串/注释/数字/操作符/纯文本)
//   策略:单遍正则扫描,按优先级匹配(注释 > 字符串 > 数字 > 关键字 > 操作符 > 纯文本)
export const tokenizeCode = (code: string, lang: string): HighlightToken[] => {
  const normalized: string = normalizeLang(lang);
  if (normalized.length === 0) {
    return [{ text: code, type: 'plain' }];
  }
  const keywords: string[] = KEYWORDS[normalized];
  const keywordSet: Set<string> = new Set(keywords);
  const isBashComment: boolean = normalized === 'bash';

  const tokens: HighlightToken[] = [];
  // 正则按优先序:行注释 / 块注释 / 双引号串 / 单引号串 / 模板串 / 数字 / 标识符 / 操作符
  //   bash 用 # 作行注释;其余用 //
  const lineComment: string = isBashComment ? '#' : '//';
  const tokenRegex: RegExp = new RegExp(
    [
      isBashComment ? '#[^\\n]*' : '\\/\\/[^\\n]*',          // 行注释
      '\\/\\*[\\s\\S]*?\\*\\/',                                // 块注释(js/ts/kotlin)
      '"(?:[^"\\\\]|\\\\.)*"',                                 // 双引号串
      "'(?:[^'\\\\]|\\\\.)*'",                                 // 单引号串
      '`(?:[^`\\\\]|\\\\.)*`',                                 // 模板串(js/ts)
      '\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b',              // 数字
      '\\b[A-Za-z_$][A-Za-z0-9_$]*\\b',                        // 标识符
      '[+\\-*/%=<>!&|^~?:]+',                                  // 操作符
      '[^\\sA-Za-z0-9_$+/\\*=<>!&|^~?:"]+',                    // 其他(标点/空白)
    ].join('|'),
    'g',
  );

  let match: RegExpExecArray | null;
  let lastEnd: number = 0;
  while ((match = tokenRegex.exec(code)) !== null) {
    // 补漏:正则未匹配的间隔文本(纯文本)
    if (match.index > lastEnd) {
      tokens.push({ text: code.substring(lastEnd, match.index), type: 'plain' });
    }
    const text: string = match[0];
    let type: HighlightTokenType = 'plain';
    if (text.startsWith(lineComment) || text.startsWith('/*')) {
      type = 'comment';
    } else if (text.startsWith('"') || text.startsWith("'") || text.startsWith('`')) {
      type = 'string';
    } else if (/^\d/.test(text)) {
      type = 'number';
    } else if (/^[A-Za-z_$]/.test(text)) {
      // 标识符:关键字判定(bash/python 的 True/False/None 也命中)
      type = keywordSet.has(text) ? 'keyword' : 'plain';
    } else if (/^[+\-*/%=<>!&|^~?:]/.test(text)) {
      type = 'operator';
    }
    tokens.push({ text, type });
    lastEnd = match.index + text.length;
  }
  // 尾部纯文本
  if (lastEnd < code.length) {
    tokens.push({ text: code.substring(lastEnd), type: 'plain' });
  }
  return tokens.length > 0 ? tokens : [{ text: code, type: 'plain' }];
};

// highlightCode:代码 → 着色 HTML span 串(已转义)
//   输出格式:<span style="color:#xxx">escaped_text</span>
//   未识别语言 → escapeHtml 纯文本(不伪高亮)
export const highlightCode = (code: string, lang: string): string => {
  if (code.length === 0) return '';
  const normalized: string = normalizeLang(lang);
  if (normalized.length === 0) {
    return escapeHtml(code);
  }
  const tokens: HighlightToken[] = tokenizeCode(code, lang);
  const parts: string[] = [];
  for (const tok of tokens) {
    const color: string = HIGHLIGHT_COLORS[tok.type];
    const escaped: string = escapeHtml(tok.text);
    parts.push(`<span style="color:${color}">${escaped}</span>`);
  }
  return parts.join('');
};

// escapeHtml(markdown_html.ts 同款,避免循环依赖,本文件内联)
const escapeHtml = (text: string): string => {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
};

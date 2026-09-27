// math_compose — LaTeX 子集 → Unicode 文本合成(无 WebView 的轻量公式渲染)
//
// 覆盖:希腊字母/常用符号/\frac/\sqrt[n]/\sqrt/上下标(^{}_{})/\text 系/
//   \vec \hat \bar \dot(组合字符)/\{ \} 转义/裸命令保留。
// 未识别命令一律保留原始写法(诚实可见,不静默丢信息)。

const GREEK: Record<string, string> = {
  alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', epsilon: 'ε', zeta: 'ζ',
  eta: 'η', theta: 'θ', iota: 'ι', kappa: 'κ', lambda: 'λ', mu: 'μ',
  nu: 'ν', xi: 'ξ', omicron: 'ο', pi: 'π', rho: 'ρ', sigma: 'σ',
  tau: 'τ', upsilon: 'υ', phi: 'φ', chi: 'χ', psi: 'ψ', omega: 'ω',
  Gamma: 'Γ', Delta: 'Δ', Theta: 'Θ', Lambda: 'Λ', Xi: 'Ξ', Pi: 'Π',
  Sigma: 'Σ', Upsilon: 'Υ', Phi: 'Φ', Psi: 'Ψ', Omega: 'Ω',
  varepsilon: 'ϵ', vartheta: 'ϑ', varpi: 'ϖ', varrho: 'ϱ', varsigma: 'ς', varphi: 'ϕ',
};

const SYMBOLS: Record<string, string> = {
  times: '×', div: '÷', pm: '±', mp: '∓', cdot: '·', ast: '∗', circ: '∘',
  leq: '≤', le: '≤', geq: '≥', ge: '≥', neq: '≠', ne: '≠', approx: '≈',
  equiv: '≡', sim: '∼', propto: '∝', ll: '≪', gg: '≫',
  infty: '∞', partial: '∂', nabla: '∇',
  sum: '∑', prod: '∏', int: '∫', iint: '∬', oint: '∮', lim: 'lim',
  sin: 'sin', cos: 'cos', tan: 'tan', log: 'log', ln: 'ln', exp: 'exp',
  max: 'max', min: 'min', sup: 'sup', inf: 'inf', det: 'det', arg: 'arg',
  in: '∈', notin: '∉', ni: '∋', subset: '⊂', supset: '⊃',
  subseteq: '⊆', supseteq: '⊇', cup: '∪', cap: '∩', setminus: '∖', emptyset: '∅',
  forall: '∀', exists: '∃', nexists: '∄', neg: '¬', land: '∧', wedge: '∧',
  lor: '∨', vee: '∨', oplus: '⊕', otimes: '⊗',
  to: '→', rightarrow: '→', leftarrow: '←', mapsto: '↦', longrightarrow: '⟶',
  Rightarrow: '⇒', Leftarrow: '⇐', Leftrightarrow: '⇔', implies: '⇒', iff: '⇔',
  ldots: '…', cdots: '⋯', dots: '…', vdots: '⋮', ddots: '⋱',
  degree: '°', angle: '∠', perp: '⊥', parallel: '∥', mid: '∣',
  therefore: '∴', because: '∵', prime: '′', backslash: '\\',
  lbrace: '{', rbrace: '}', lvert: '|', rvert: '|',
  lceil: '⌈', rceil: '⌉', lfloor: '⌊', rfloor: '⌋', langle: '⟨', rangle: '⟩',
  quad: '  ', qquad: '    ', ',': ' ', ';': ' ', ':': ' ', '!': '',
};

const SUPERSCRIPT: Record<string, string> = {
  '0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴',
  '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸', '9': '⁹',
  '+': '⁺', '-': '⁻', '=': '⁼', '(': '⁽', ')': '⁾',
  'n': 'ⁿ', 'i': 'ⁱ', 'a': 'ᵃ', 'b': 'ᵇ', 'c': 'ᶜ', 'd': 'ᵈ', 'e': 'ᵉ',
  'o': 'ᵒ', 'x': 'ˣ', 'T': 'ᵀ', 't': 'ᵗ', 'k': 'ᵏ', 'm': 'ᵐ', 'π': 'ᵠ',
};

const SUBSCRIPT: Record<string, string> = {
  '0': '₀', '1': '₁', '2': '₂', '3': '₃', '4': '₄',
  '5': '₅', '6': '₆', '7': '₇', '8': '₈', '9': '₉',
  '+': '₊', '-': '₋', '=': '₌', '(': '₍', ')': '₎',
  'a': 'ₐ', 'e': 'ₑ', 'i': 'ᵢ', 'j': 'ⱼ', 'k': 'ₖ', 'n': 'ₙ', 'o': 'ₒ',
  'x': 'ₓ', 'h': 'ₕ', 'p': 'ₚ', 's': 'ₛ', 't': 'ₜ', 'u': 'ᵤ', 'v': 'ᵥ',
};

const toSuper = (s: string): string | null => {
  let out: string = '';
  for (const ch of s) {
    const mapped: string | undefined = SUPERSCRIPT[ch];
    if (mapped === undefined) return null;
    out += mapped;
  }
  return out;
};

const toSub = (s: string): string | null => {
  let out: string = '';
  for (const ch of s) {
    const mapped: string | undefined = SUBSCRIPT[ch];
    if (mapped === undefined) return null;
    out += mapped;
  }
  return out;
};

// ===== 递归下降合成 =====

interface ParseState {
  src: string;
  pos: number;
}

// 读取一个「参数」:{...} 组 / 单字符 / \命令
const readAtom = (st: ParseState): string => {
  if (st.pos >= st.src.length) return '';
  const ch: string = st.src[st.pos];
  if (ch === '{') {
    const inner: string = readGroup(st);
    return inner;
  }
  if (ch === '\\') {
    return readCommand(st);
  }
  st.pos += 1;
  return ch;
};

// 读取 {...} 组(内容递归合成);调用时 st.pos 指向 '{'
const readGroup = (st: ParseState): string => {
  st.pos += 1; // 吃 '{'
  let depth: number = 1;
  const start: number = st.pos;
  while (st.pos < st.src.length && depth > 0) {
    const c: string = st.src[st.pos];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) break;
    }
    st.pos += 1;
  }
  const raw: string = st.src.substring(start, st.pos);
  if (st.pos < st.src.length) st.pos += 1; // 吃 '}'
  return composeLatex(raw);
};

// 读取 \command(可带 {...} 参数);调用时 st.pos 指向 '\'
const readCommand = (st: ParseState): string => {
  st.pos += 1; // 吃 '\'
  // 转义单字符:\{ \} \$ \% \& \_ \# \\
  if (st.pos < st.src.length && /[{}$%&_#\\]/.test(st.src[st.pos])) {
    const esc: string = st.src[st.pos];
    st.pos += 1;
    return esc;
  }
  const nameStart: number = st.pos;
  while (st.pos < st.src.length && /[a-zA-Z]/.test(st.src[st.pos])) st.pos += 1;
  const name: string = st.src.substring(nameStart, st.pos);
  if (name.length === 0) return '\\';

  if (name === 'frac' || name === 'dfrac' || name === 'tfrac') {
    const num: string = readAtom(st);
    const den: string = readAtom(st);
    const simple: boolean = num.length <= 8 && den.length <= 8
      && num.indexOf('(') < 0 && den.indexOf('(') < 0;
    return simple ? `${num}⁄${den}` : `(${num})/(${den})`;
  }
  if (name === 'sqrt') {
    // \sqrt[n]{x} / \sqrt{x}
    let index: string = '';
    if (st.pos < st.src.length && st.src[st.pos] === '[') {
      const endIdx: number = st.src.indexOf(']', st.pos);
      if (endIdx > st.pos) {
        index = st.src.substring(st.pos + 1, endIdx);
        st.pos = endIdx + 1;
      }
    }
    const body: string = readAtom(st);
    const rootSym: string = index === '3' ? '∛' : (index === '4' ? '∜' : '√');
    if (index.length > 0 && index !== '3' && index !== '4') return `√[${index}](${body})`;
    return `${rootSym}(${body})`;
  }
  if (name === 'text' || name === 'mathrm' || name === 'mathbf' || name === 'mathit'
    || name === 'textbf' || name === 'textit' || name === 'operatorname') {
    return readAtom(st);
  }
  if (name === 'vec' || name === 'hat' || name === 'bar' || name === 'dot' || name === 'tilde') {
    const body: string = readAtom(st);
    const mark: string = name === 'vec' ? '⃗' : (name === 'hat' ? '̂' : (name === 'bar' ? '̄' : (name === 'dot' ? '̇' : '̃')));
    return `${body}${mark}`;
  }
  if (name === 'overline' || name === 'underline') {
    return readAtom(st);
  }
  if (name === 'left' || name === 'right' || name === 'big' || name === 'Big'
    || name === 'bigl' || name === 'bigr' || name === 'Bigl' || name === 'Bigr') {
    // 定界修饰:吃掉随后一个符号原子,原样返回
    if (st.pos < st.src.length) {
      const next: string = st.src[st.pos];
      st.pos += 1;
      return next === '\\' ? `\\${next}` : next;
    }
    return '';
  }
  const greek: string | undefined = GREEK[name];
  if (greek !== undefined) return greek;
  const sym: string | undefined = SYMBOLS[name];
  if (sym !== undefined) return sym;
  // 未识别:保留原始命令(并丢弃其后空白,LaTeX 命令后空格惯例)
  if (st.pos < st.src.length && st.src[st.pos] === ' ') st.pos += 1;
  return `\\${name}`;
};

const readScript = (st: ParseState, superScript: boolean): string => {
  // 调用时 st.pos 指向 '^' 或 '_'
  st.pos += 1;
  const raw: string = readAtomRaw(st);
  const table: (s: string) => string | null = superScript ? toSuper : toSub;
  const mapped: string | null = table(raw);
  if (mapped !== null) return mapped;
  return superScript ? `^(${raw})` : `_(${raw})`;
};

// 读取上/下标的原始文本(不递归合成,避免上下标内的命令被翻译掉)
const readAtomRaw = (st: ParseState): string => {
  if (st.pos >= st.src.length) return '';
  const ch: string = st.src[st.pos];
  if (ch === '{') {
    st.pos += 1;
    let depth: number = 1;
    const start: number = st.pos;
    while (st.pos < st.src.length && depth > 0) {
      const c: string = st.src[st.pos];
      if (c === '{') depth++;
      else if (c === '}') {
        depth--;
        if (depth === 0) break;
      }
      st.pos += 1;
    }
    const raw: string = st.src.substring(start, st.pos);
    if (st.pos < st.src.length) st.pos += 1;
    return raw;
  }
  if (ch === '\\') {
    const cmdState: ParseState = { src: st.src, pos: st.pos };
    const composed: string = readCommand(cmdState);
    st.pos = cmdState.pos;
    return composed;
  }
  st.pos += 1;
  return ch;
};

export const composeLatex = (src: string): string => {
  const st: ParseState = { src: src, pos: 0 };
  let out: string = '';
  while (st.pos < src.length) {
    const ch: string = src[st.pos];
    if (ch === '\\') {
      out += readCommand(st);
    } else if (ch === '^') {
      out += readScript(st, true);
    } else if (ch === '_') {
      out += readScript(st, false);
    } else if (ch === '{') {
      out += readGroup(st);
    } else if (ch === '}') {
      // 不配对的右括号:原样保留
      out += ch;
      st.pos += 1;
    } else {
      out += ch;
      st.pos += 1;
    }
  }
  return out;
};

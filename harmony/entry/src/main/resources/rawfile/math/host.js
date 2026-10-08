'use strict';
const app = document.getElementById('app');
let revision = 0;
let measuredHeight = 0;

function send(kind, height = 0, url = '') {
  AmberMath.postMessage(JSON.stringify({ kind, revision, height, url }));
}
function measure() {
  const current = revision;
  requestAnimationFrame(() => {
    if (current !== revision) return;
    const height = Math.max(1, Math.ceil(app.getBoundingClientRect().height));
    if (height !== measuredHeight) { measuredHeight = height; send('measure', height); }
  });
}
function element(tag, parent) {
  const node = document.createElement(tag);
  parent.appendChild(node);
  return node;
}
function inline(tokens, parent) {
  for (const token of tokens) {
    if (token.type === 'math') {
      const node = element(token.display ? 'div' : 'span', parent);
      if (token.display) node.className = 'display-math';
      // Official KaTeX render API. Each formula has isolated macros; no user HTML/trusted URLs.
      katex.render(token.latex, node, {
        displayMode: token.display, throwOnError: false,
        trust: false, maxExpand: 1000, maxSize: 20,
      });
    } else if (token.type === 'image') {
      const node = element('img', parent);
      node.alt = token.alt;
      node.src = token.url;
      node.addEventListener('load', measure);
      node.addEventListener('error', measure);
      node.addEventListener('click', () => send('image', 0, token.url));
    } else if (token.type === 'link') {
      const node = element('a', parent);
      node.href = '#';
      node.textContent = token.text;
      node.addEventListener('click', event => { event.preventDefault(); send('link', 0, token.url); });
    } else if (token.type === 'bold' || token.type === 'italic' || token.type === 'code') {
      element(token.type === 'bold' ? 'strong' : token.type === 'italic' ? 'em' : 'code', parent).textContent = token.text;
    } else {
      parent.appendChild(document.createTextNode(token.text));
    }
  }
}
function render(block) {
  if (block.kind === 'list') {
    const counters = [0, 0, 0, 0];
    const seen = [false, false, false, false];
    block.items.forEach((tokens, index) => {
      const depth = (block.depths || [])[index] || 0;
      counters[depth] = seen[depth] ? counters[depth] + 1 : index === 0 ? block.start : 1;
      seen[depth] = true;
      for (let d = depth + 1; d < 4; d++) seen[d] = false;
      const row = element('div', app);
      row.className = block.ordered ? 'list-item ordered' : 'list-item';
      row.style.paddingLeft = `${depth * 12}px`;
      element('span', row).className = 'marker';
      row.lastChild.textContent = block.ordered ? `${counters[depth]}.` : '•';
      const body = element('div', row); body.className = 'item-body'; inline(tokens, body);
    });
  } else if (block.kind === 'table') {
    const scroll = element('div', app); scroll.className = 'table-scroll';
    const table = element('table', scroll);
    function row(cells, header) {
      const tr = element('tr', table);
      cells.forEach((tokens, index) => {
        const cell = element(header ? 'th' : 'td', tr);
        cell.style.textAlign = block.alignments[index] || 'left';
        inline(tokens, cell);
      });
    }
    row(block.headers, true); block.rows.forEach(cells => row(cells, false));
  } else {
    const tag = block.kind === 'heading' ? `h${block.level}` : block.kind === 'blockquote' ? 'blockquote' : 'p';
    inline(block.inlines, element(tag, app));
  }
}
window.renderAmberMathBlock = function(block, style, currentRevision) {
  revision = currentRevision;
  measuredHeight = 0;
  const root = document.documentElement.style;
  root.setProperty('--size', `${style.size}px`);
  root.setProperty('--leading', `${style.lineHeight}px`);
  root.setProperty('--ink', style.color);
  root.setProperty('--accent', style.accent);
  root.setProperty('--muted', style.muted);
  root.setProperty('--line', style.line);
  root.setProperty('--surface', style.surface);
  root.setProperty('--family', style.family);
  root.setProperty('--heading-family', style.headingFamily);
  app.className = style.reading ? 'reading' : '';
  app.replaceChildren();
  render(block);
  measure();
  document.fonts.ready.then(measure);
};
new ResizeObserver(measure).observe(app);
document.fonts.addEventListener('loadingdone', measure);

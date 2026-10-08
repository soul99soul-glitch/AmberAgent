const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const resourceDir = path.resolve(__dirname, '../entry/src/main/resources/rawfile/terminal');
const { Terminal } = require(path.join(resourceDir, 'xterm.js'));
const hostSource = fs.readFileSync(path.join(resourceDir, 'host.js'), 'utf8');
const hostHTML = fs.readFileSync(path.join(resourceDir, 'host.html'), 'utf8');
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function createHost() {
  let terminal;
  let ready;
  const readyPromise = new Promise((resolve) => { ready = resolve; });
  const inputs = [];
  let clicking = false;
  const focusedDuringClick = [];
  const styles = {};
  const buttons = Array.from(hostHTML.matchAll(/<button\b([^>]*)>([^<]*)<\/button>/g), (match) => {
    const attributes = match[1];
    const listeners = {};
    return {
      id: /\bid="([^"]*)"/.exec(attributes)?.[1] ?? '',
      label: match[2], disabled: true, listeners,
      addEventListener: (name, callback) => { listeners[name] = callback; },
      getAttribute: (name) => new RegExp(`\\b${name}="([^"]*)"`).exec(attributes)?.[1] ?? null,
    };
  });
  // Only the DOM mounting and size adapter are omitted. VT parser, UTF8 handling,
  // async write buffer, reset and modes are the actual packaged xterm.js code.
  class UnmountedTerminal extends Terminal {
    constructor(options) { super(options); terminal = this; }
    open() {}
    loadAddon(addon) { addon.terminal = this; }
    focus() { focusedDuringClick.push(clicking); }
  }
  class Fit {
    proposeDimensions() { return { cols: 67, rows: 18 }; }
    fit() { this.terminal.resize(67, 18); }
  }
  const context = {
    Terminal: UnmountedTerminal, FitAddon: { FitAddon: Fit },
    TextEncoder, Uint8Array, Array, Object, Promise,
    document: { getElementById: (id) => id === 'terminal' ? { clientWidth: 360, clientHeight: 400 }
      : { querySelectorAll: () => buttons },
      fonts: { ready: Promise.resolve() }, body: { style: { setProperty: (name, value) => { styles[name] = value; } } } },
    requestAnimationFrame: (callback) => setTimeout(callback, 0),
    ResizeObserver: class { observe() {} },
    AmberTerminalBridge: { ready, resize() {}, input: (bytes) => inputs.push(bytes) },
    window: { addEventListener() {}, visualViewport: { addEventListener() {} } },
  };
  vm.runInNewContext(hostSource, context);
  await context.window.AmberTerminal.boot({ background: '#fff', foreground: '#000', toolbarBackground: '#eee', accent: '#760' });
  await readyPromise;
  const click = (label) => {
    const button = buttons.find((entry) => entry.label === label);
    assert.ok(button, `packaged toolbar button ${label}`);
    clicking = true;
    button.listeners.click();
    clicking = false;
  };
  return { api: context.window.AmberTerminal, terminal, inputs, buttons, click, focusedDuringClick, styles };
}

test('host reset waits for actual VT write callback before clearing old session', async () => {
  const host = await createHost();
  try {
    host.api.write([...new TextEncoder().encode('OLD SESSION')]);
    host.api.reset();
    await delay(100);
    assert.equal(host.terminal.buffer.active.getLine(0).translateToString(true), '');
  } finally { host.terminal.dispose(); }
});

test('new session bytes remain after queued reset, including split Chinese and emoji', async () => {
  const host = await createHost();
  try {
    host.api.write([...new TextEncoder().encode('OLD SESSION\x1b[31m')]);
    host.api.reset();
    const bytes = new TextEncoder().encode('NEW 中文😀');
    host.api.write([...bytes.subarray(0, 5)]);
    host.api.write([...bytes.subarray(5, 11)]);
    host.api.write([...bytes.subarray(11)]);
    await delay(150);
    assert.equal(host.terminal.buffer.active.getLine(0).translateToString(true), 'NEW 中文😀');
  } finally { host.terminal.dispose(); }
});

test('toolbar arrows follow actual xterm application cursor mode', async () => {
  const host = await createHost();
  try {
    host.api.setInputEnabled(true);
    host.click('↑');
    assert.deepEqual(host.inputs.at(-1), [27, 91, 65]);
    host.api.write([...new TextEncoder().encode('\x1b[?1h')]);
    await delay(100);
    host.click('↑');
    assert.deepEqual(host.inputs.at(-1), [27, 79, 65]);
  } finally { host.terminal.dispose(); }
});

test('packaged DOM toolbar focuses synchronously within its click and respects the session gate', async () => {
  const host = await createHost();
  try {
    assert.equal(host.buttons.length, 9);
    assert.ok(host.buttons.every((button) => button.disabled));
    host.click('键盘');
    host.click('Ctrl-C');
    assert.deepEqual(host.focusedDuringClick, []);
    assert.deepEqual(host.inputs, []);
    host.api.setInputEnabled(true);
    assert.ok(host.buttons.every((button) => !button.disabled));
    host.click('键盘');
    assert.deepEqual(host.focusedDuringClick, [true]);
    host.click('Esc'); host.click('Tab'); host.click('Ctrl-C'); host.click('↵');
    assert.deepEqual(host.inputs.slice(-4), [[27], [9], [3], [13]]);
    host.api.setInputEnabled(false);
    host.click('键盘'); host.click('↵');
    assert.equal(host.focusedDuringClick.length, 1);
    assert.equal(host.inputs.length, 4);
    assert.ok(host.buttons.every((button) => button.disabled));
  } finally { host.terminal.dispose(); }
});

test('DOM toolbar keeps the textarea focus on pointerdown and updates its palette', async () => {
  const host = await createHost();
  try {
    let prevented = 0;
    for (const button of host.buttons) button.listeners.pointerdown({ preventDefault() { prevented++; } });
    assert.equal(prevented, 9);
    host.api.setTheme({ background: '#111', foreground: '#eee', toolbarBackground: '#222', accent: '#abc' });
    assert.deepEqual(host.styles, { '--terminal-background': '#111', '--terminal-foreground': '#eee',
      '--toolbar-background': '#222', '--terminal-accent': '#abc' });
  } finally { host.terminal.dispose(); }
});

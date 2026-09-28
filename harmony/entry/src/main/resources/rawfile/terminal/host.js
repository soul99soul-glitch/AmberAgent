'use strict';
(function () {
  let term;
  let fitAddon;
  let initialized = false;
  let fontsReady = false;
  let inputEnabled = false;
  let fitFrame = 0;
  let writeQueue = Promise.resolve();
  const host = document.getElementById('terminal');
  const toolbar = document.getElementById('terminal-toolbar');
  const buttons = toolbar.querySelectorAll('button');

  function applyTheme(theme) {
    document.body.style.backgroundColor = theme.background;
    document.body.style.setProperty('--terminal-background', theme.background);
    document.body.style.setProperty('--terminal-foreground', theme.foreground);
    document.body.style.setProperty('--toolbar-background', theme.toolbarBackground);
    document.body.style.setProperty('--terminal-accent', theme.accent);
  }

  function sendKey(key) {
    const keys = { escape: '\x1b', tab: '\t', interrupt: '\x03', enter: '\r' };
    const arrows = { up: 'A', down: 'B', right: 'C', left: 'D' };
    const data = arrows[key] ? '\x1b' + (term.modes.applicationCursorKeysMode ? 'O' : '[') + arrows[key] : keys[key];
    if (data) input(new TextEncoder().encode(data));
  }

  function fit() {
    if (!fontsReady || !fitAddon || fitFrame) return;
    fitFrame = requestAnimationFrame(function () {
      fitFrame = 0;
      if (host.clientWidth < 1 || host.clientHeight < 1) return;
      const dimensions = fitAddon.proposeDimensions();
      if (!dimensions || dimensions.cols < 1 || dimensions.rows < 1) return;
      fitAddon.fit();
      if (!initialized) {
        initialized = true;
        AmberTerminalBridge.ready(term.cols, term.rows);
      }
    });
  }

  function input(bytes) {
    if (!inputEnabled || !bytes.length) return;
    for (let offset = 0; offset < bytes.length; offset += 65536) {
      AmberTerminalBridge.input(Array.from(bytes.subarray(offset, offset + 65536)));
    }
  }

  window.AmberTerminal = Object.freeze({
    boot: async function (theme) {
      if (term) return;
      term = new Terminal({
        fontFamily: 'monospace', fontSize: 14, scrollback: 3000,
        cursorBlink: true, convertEol: false, allowProposedApi: false,
        theme: theme, disableStdin: true,
        linkHandler: { activate: function () {} },
      });
      applyTheme(theme);
      fitAddon = new FitAddon.FitAddon();
      term.loadAddon(fitAddon);
      term.open(host);
      for (const button of buttons) {
        // Keep the textarea focus while using tool keys with the IME visible.
        button.addEventListener('pointerdown', function (event) { event.preventDefault(); });
        button.addEventListener('click', function () {
          if (!inputEnabled) return;
          if (button.id === 'terminal-keyboard') term.focus();
          else sendKey(button.getAttribute('data-terminal-key'));
        });
      }
      term.onData(function (data) { input(new TextEncoder().encode(data)); });
      term.onBinary(function (data) {
        input(Uint8Array.from(data, function (character) { return character.charCodeAt(0) & 255; }));
      });
      term.onResize(function (size) {
        if (initialized) AmberTerminalBridge.resize(size.cols, size.rows);
      });
      new ResizeObserver(fit).observe(host);
      window.addEventListener('resize', fit);
      if (window.visualViewport) window.visualViewport.addEventListener('resize', fit);
      // Fonts and layout must be measurable before opening the native PTY.
      await document.fonts.ready;
      fontsReady = true;
      fit();
    },
    write: function (numbers) {
      if (!initialized) throw new Error('terminal_not_ready');
      const bytes = new Uint8Array(numbers);
      writeQueue = writeQueue.then(function () {
        return new Promise(function (resolve) { term.write(bytes, resolve); });
      });
    },
    fit: fit,
    reset: function () {
      writeQueue = writeQueue.then(function () { term.reset(); fit(); });
    },
    setInputEnabled: function (enabled) {
      inputEnabled = enabled === true;
      term.options.disableStdin = !inputEnabled;
      for (const button of buttons) button.disabled = !inputEnabled;
    },
    setTheme: function (theme) {
      if (!initialized) return;
      term.options.theme = theme;
      applyTheme(theme);
    },
  });
})();

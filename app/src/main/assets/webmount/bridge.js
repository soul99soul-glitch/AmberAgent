// AmberAgent WebMount — bootstrap JS bridge.
//
// Re-injected after every navigation by the Kotlin side (the JS context
// resets on each load). Provides a single entry point `__amberWm_call`
// that dispatches RPC requests from Kotlin to local handlers and resolves
// via the `AmberWM` interface (Kotlin-bound via addJavascriptInterface).
//
// Phase 1 M1.3.1 handlers: `state`. Subsequent milestones extend the
// switch below with `extract`, `wait`, `click`, `type`, `eval`, etc.

(function () {
  if (window.__amberWmReady) return;
  window.__amberWmReady = true;

  function safeJson(v) {
    try { return JSON.stringify(v); }
    catch (e) { return JSON.stringify({ __error: 'unserializable' }); }
  }

  function getViewport() {
    return {
      w: window.innerWidth | 0,
      h: window.innerHeight | 0,
      dpr: window.devicePixelRatio || 1,
    };
  }

  function snapshotState() {
    return {
      url: location.href,
      title: document.title || '',
      ready_state: document.readyState,
      viewport: getViewport(),
      scroll: {
        x: window.scrollX | 0,
        y: window.scrollY | 0,
        max_y: (document.documentElement.scrollHeight - window.innerHeight) | 0,
      },
    };
  }

  // Console capture — buffer recent messages so wm_state can return a tail.
  if (!window.__amberWmConsole) {
    window.__amberWmConsole = [];
    var BUF_MAX = 64;
    ['log', 'info', 'warn', 'error', 'debug'].forEach(function (level) {
      var orig = console[level].bind(console);
      console[level] = function () {
        try {
          var parts = [];
          for (var i = 0; i < arguments.length; i++) {
            var a = arguments[i];
            parts.push(typeof a === 'string' ? a : safeJson(a));
          }
          window.__amberWmConsole.push({ level: level, msg: parts.join(' '), ts: Date.now() });
          if (window.__amberWmConsole.length > BUF_MAX) {
            window.__amberWmConsole.splice(0, window.__amberWmConsole.length - BUF_MAX);
          }
        } catch (_) { /* ignore */ }
        return orig.apply(this, arguments);
      };
    });
  }

  function consoleTail(n) {
    var max = Math.max(0, Math.min(n || 16, window.__amberWmConsole.length));
    return window.__amberWmConsole.slice(window.__amberWmConsole.length - max);
  }

  window.__amberWm_call = function (method, argsJson, reqId) {
    var args = {};
    try { if (argsJson) args = JSON.parse(argsJson); }
    catch (e) {
      AmberWM.reject(reqId, 'invalid args JSON: ' + (e && e.message));
      return;
    }
    try {
      switch (method) {
        case 'state': {
          var includeConsole = args.include_console !== false;
          var tail = args.console_tail | 0 || 16;
          var payload = snapshotState();
          if (includeConsole) payload.console_tail = consoleTail(tail);
          AmberWM.resolve(reqId, safeJson(payload));
          return;
        }
        default:
          AmberWM.reject(reqId, 'unknown method: ' + method);
      }
    } catch (e) {
      AmberWM.reject(reqId, String(e && e.message || e));
    }
  };
})();

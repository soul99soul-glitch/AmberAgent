(function () {
  const token = window.__AMBER_MINIAPP_SESSION_TOKEN__;
  let nextId = 1;
  const pending = new Map();

  function call(method, params, timeoutMs) {
    return new Promise(function (resolve, reject) {
      const id = nextId++;
      const timeout = window.setTimeout(function () {
        pending.delete(id);
        reject(new Error('MiniApp bridge timeout'));
      }, timeoutMs || 5000);
      pending.set(id, { resolve, reject, timeout });
      try {
        window.AmberNative.postMessage(JSON.stringify({ id, token, method, params: params || {} }));
      } catch (error) {
        window.clearTimeout(timeout);
        pending.delete(id);
        reject(error);
      }
    });
  }

  window.AmberBridge = Object.freeze({
    _handleNativeResponse: function (response) {
      const entry = pending.get(response.id);
      if (!entry) return;
      window.clearTimeout(entry.timeout);
      pending.delete(response.id);
      if (response.ok) {
        entry.resolve(response.data);
      } else {
        entry.reject(new Error(response.error || 'MiniApp bridge error'));
      }
    }
  });

  window.Amber = Object.freeze({
    storage: Object.freeze({
      get: function (key) { return call('storage.get', { key }); },
      set: function (key, value) { return call('storage.set', { key, value }); },
      remove: function (key) { return call('storage.remove', { key }); }
    }),
    fetch: function (options) { return call('fetch', options || {}, 15000); },
    search: function (options) { return call('search', options || {}, 15000); },
    clipboard: Object.freeze({
      copy: function (text) { return call('clipboard.copy', { text: String(text || '') }); }
    }),
    toast: function (message) { return call('toast', { message: String(message || '') }); },
    host: Object.freeze({
      getTheme: function () { return call('host.getTheme', {}); },
      updateBoardSummary: function (summary) { return call('host.updateBoardSummary', { summary: String(summary || '') }); }
    })
  });
})();

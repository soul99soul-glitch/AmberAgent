(function () {
  const token = window.__AMBER_MINIAPP_SESSION_TOKEN__;
  let nextId = 1;
  const pending = new Map();
  const eventHandlers = new Map();
  const sensorHandlers = new Map();

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

  function headersToObject(headers) {
    const out = {};
    if (!headers) return out;
    if (typeof headers.forEach === 'function') {
      headers.forEach(function (value, key) { out[key] = String(value); });
      return out;
    }
    if (Array.isArray(headers)) {
      headers.forEach(function (pair) {
        if (Array.isArray(pair) && pair.length >= 2) out[String(pair[0])] = String(pair[1]);
      });
      return out;
    }
    if (typeof headers === 'object') {
      Object.keys(headers).forEach(function (key) { out[key] = String(headers[key]); });
    }
    return out;
  }

  function bridgeFetch(input, init) {
    const url = typeof input === 'string' ? input : (input && input.url);
    const options = init || {};
    return call('fetch', {
      url: String(url || ''),
      method: options.method || 'GET',
      headers: headersToObject(options.headers),
      body: typeof options.body === 'string' ? options.body : undefined,
      contentType: options.contentType,
      responseType: 'text'
    }, 15000).then(function (result) {
      return Object.freeze({
        ok: !!result.ok,
        status: result.status || 0,
        url: result.url || String(url || ''),
        headers: Object.freeze({
          get: function (name) {
            return String(name || '').toLowerCase() === 'content-type' ? (result.contentType || '') : null;
          }
        }),
        text: function () { return Promise.resolve(String(result.body || '')); },
        json: function () { return Promise.resolve(JSON.parse(String(result.body || 'null'))); }
      });
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
    },
    _emitNativeEvent: function (event) {
      if (!event || !event.type) return;
      if (event.type === 'eventBus') {
        const handler = eventHandlers.get(event.subscriptionId);
        if (handler) handler(event.payload);
      }
      if (event.type === 'sensor') {
        const handler = sensorHandlers.get(event.subscriptionId);
        if (handler) handler(event.payload);
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
    ai: Object.freeze({
      generate: function (options) { return call('ai.generate', options || {}, 65000); }
    }),
    sharedStore: Object.freeze({
      get: function (options) { return call('sharedStore.get', options || {}); },
      set: function (options) { return call('sharedStore.set', options || {}); },
      remove: function (options) { return call('sharedStore.remove', options || {}); }
    }),
    eventBus: Object.freeze({
      subscribe: async function (options, handler) {
        const result = await call('eventBus.subscribe', options || {});
        eventHandlers.set(result.subscriptionId, typeof handler === 'function' ? handler : function () {});
        return Object.freeze({
          subscriptionId: result.subscriptionId,
          unsubscribe: function () {
            eventHandlers.delete(result.subscriptionId);
            return call('eventBus.unsubscribe', { subscriptionId: result.subscriptionId });
          }
        });
      },
      publish: function (options) { return call('eventBus.publish', options || {}); }
    }),
    launch: function (options) { return call('launch', options || {}); },
    sensor: Object.freeze({
      subscribe: async function (options, handler) {
        const result = await call('sensor.subscribe', options || {});
        sensorHandlers.set(result.subscriptionId, typeof handler === 'function' ? handler : function () {});
        return Object.freeze({
          subscriptionId: result.subscriptionId,
          unsubscribe: function () {
            sensorHandlers.delete(result.subscriptionId);
            return call('sensor.unsubscribe', { subscriptionId: result.subscriptionId });
          }
        });
      }
    }),
    location: Object.freeze({
      getCurrent: function (options) { return call('location.getCurrent', options || {}); }
    }),
    clipboard: Object.freeze({
      copy: function (text) { return call('clipboard.copy', { text: String(text || '') }); },
      read: function () { return call('clipboard.read', {}); }
    }),
    toast: function (message) { return call('toast', { message: String(message || '') }); },
    host: Object.freeze({
      getTheme: function () { return call('host.getTheme', {}); },
      updateBoardSummary: function (summary) { return call('host.updateBoardSummary', { summary: String(summary || '') }); },
      getConversationContext: function (options) { return call('host.getConversationContext', options || {}); },
      sendToConversation: function (options) { return call('host.sendToConversation', options || {}); },
      createArtifact: function (options) { return call('host.createArtifact', options || {}); }
    })
  });
  try {
    Object.defineProperty(window, 'fetch', { value: bridgeFetch, writable: false, configurable: false });
  } catch (_) {}
})();

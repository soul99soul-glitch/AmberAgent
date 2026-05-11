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

  // --------------------------------------------------------------- selectors

  /**
   * Resolve a selector string into a NodeList. Supports:
   *   - "css:..."  or "...":   document.querySelectorAll(...)
   *   - "text=..."             elements whose .innerText / .textContent
   *                            contains the substring (case-insensitive)
   *   - "xpath=..."            XPath via document.evaluate(...)
   * Returns an Array (not live).
   */
  function resolveSelector(sel) {
    if (!sel || typeof sel !== 'string') return [];
    if (sel.indexOf('xpath=') === 0) {
      var xp = sel.substring(6);
      var out = [];
      var snap = document.evaluate(xp, document, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
      for (var i = 0; i < snap.snapshotLength; i++) out.push(snap.snapshotItem(i));
      return out;
    }
    if (sel.indexOf('text=') === 0) {
      var needle = sel.substring(5).toLowerCase();
      var all = Array.prototype.slice.call(document.querySelectorAll('a,button,input,[role=button],[role=link],label,h1,h2,h3,h4,li,p,span,div'));
      return all.filter(function (el) {
        var t = (el.innerText || el.textContent || '').toLowerCase();
        return t.indexOf(needle) >= 0;
      });
    }
    var css = sel.indexOf('css:') === 0 ? sel.substring(4) : sel;
    try { return Array.prototype.slice.call(document.querySelectorAll(css)); }
    catch (e) { return []; }
  }

  function isVisible(el) {
    if (!el || el.nodeType !== 1) return false;
    var r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return false;
    var cs = window.getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity || '1') === 0) return false;
    return true;
  }

  function roleOf(el) {
    var r = el.getAttribute && el.getAttribute('role');
    if (r) return r;
    switch ((el.tagName || '').toLowerCase()) {
      case 'a': return 'link';
      case 'button': return 'button';
      case 'input': return el.type ? ('input:' + el.type) : 'input';
      case 'select': return 'combobox';
      case 'textarea': return 'textbox';
      case 'h1': case 'h2': case 'h3': case 'h4': case 'h5': case 'h6': return 'heading';
      default: return null;
    }
  }

  function accessibleName(el) {
    if (!el) return null;
    var aria = el.getAttribute && el.getAttribute('aria-label');
    if (aria) return aria.trim();
    var labelled = el.getAttribute && el.getAttribute('aria-labelledby');
    if (labelled) {
      var ref = document.getElementById(labelled);
      if (ref) return (ref.innerText || ref.textContent || '').trim();
    }
    var alt = el.getAttribute && el.getAttribute('alt');
    if (alt) return alt.trim();
    var title = el.getAttribute && el.getAttribute('title');
    if (title) return title.trim();
    if (el.tagName === 'INPUT' && el.placeholder) return el.placeholder.trim();
    var text = (el.innerText || el.textContent || '').trim();
    if (text.length > 0 && text.length < 200) return text;
    return null;
  }

  function rectOf(el) {
    var r = el.getBoundingClientRect();
    return [r.left | 0, r.top | 0, r.width | 0, r.height | 0];
  }

  function nodePath(el) {
    // Cheap CSS-ish path with nth-of-type indexes. Capped at 6 segments
    // to keep snapshot output small.
    var parts = [];
    var cur = el;
    while (cur && cur.nodeType === 1 && parts.length < 6) {
      var tag = cur.tagName ? cur.tagName.toLowerCase() : '#';
      var parent = cur.parentElement;
      if (parent) {
        var sibs = Array.prototype.filter.call(parent.children, function (c) { return c.tagName === cur.tagName; });
        if (sibs.length > 1) {
          tag += ':nth(' + sibs.indexOf(cur) + ')';
        }
      }
      parts.unshift(tag);
      cur = cur.parentElement;
    }
    return parts.join('>');
  }

  // --------------------------------------------------------------- extract

  function describeNode(el, opts) {
    var node = {
      tag: (el.tagName || '').toLowerCase(),
      path: nodePath(el),
      role: roleOf(el),
      name: accessibleName(el),
      rect: rectOf(el),
      visible: isVisible(el),
    };
    if (el.tagName === 'A' && el.href) node.href = el.href;
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
      try { node.value = el.value; } catch (_) {}
      if (el.type) node.input_type = el.type;
      if (el.disabled) node.disabled = true;
    }
    if (opts && opts.text !== false) {
      var t = (el.innerText || el.textContent || '').trim();
      if (t.length > 0) node.text = t.length > 240 ? (t.substring(0, 240) + '…') : t;
    }
    return node;
  }

  function extractReadable(args) {
    var maxChars = (args.max_chars | 0) || 4000;
    var maxLinks = (args.max_links | 0) || 20;
    var raw = (document.body && document.body.innerText) || '';
    var text = raw.length > maxChars ? raw.substring(0, maxChars) : raw;
    var anchors = Array.prototype.slice.call(document.querySelectorAll('a[href]'));
    var seen = {};
    var links = [];
    for (var i = 0; i < anchors.length && links.length < maxLinks; i++) {
      var a = anchors[i];
      var href = a.href;
      if (!href || seen[href]) continue;
      seen[href] = true;
      var label = accessibleName(a);
      if (!label) continue;
      links.push({ href: href, text: label });
    }
    return {
      mode: 'readable',
      url: location.href,
      title: document.title || '',
      text: text,
      text_chars: text.length,
      truncated: raw.length > maxChars,
      total_chars: raw.length,
      links: links,
      total_links: anchors.length,
    };
  }

  function extractInteractive(args) {
    var maxNodes = Math.min(((args.max_nodes | 0) || 200), 1000);
    var selector = 'a[href],button,input,select,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[onclick]';
    var els = Array.prototype.slice.call(document.querySelectorAll(selector));
    var visible = args.visible_only !== false;
    var nodes = [];
    for (var i = 0; i < els.length && nodes.length < maxNodes; i++) {
      var el = els[i];
      if (visible && !isVisible(el)) continue;
      nodes.push(describeNode(el, { text: true }));
    }
    return {
      mode: 'interactive',
      url: location.href,
      title: document.title || '',
      nodes: nodes,
      truncated: nodes.length >= maxNodes && els.length > maxNodes,
      total_candidates: els.length,
    };
  }

  function extractSnapshot(args) {
    var maxNodes = Math.min(((args.max_nodes | 0) || 1000), 4000);
    var rootSel = args.root_selector;
    var root = document.body;
    if (rootSel) {
      var matched = resolveSelector(rootSel);
      if (matched.length > 0) root = matched[0];
    }
    var visible = args.visible_only !== false;
    var nodes = [];
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT, null, false);
    var cur = walker.currentNode;
    while (cur && nodes.length < maxNodes) {
      if (!visible || isVisible(cur)) {
        var role = roleOf(cur);
        var hasName = accessibleName(cur);
        // Only keep semantically interesting nodes when not in role/name mode.
        if (role || hasName || cur === root) {
          nodes.push(describeNode(cur, { text: true }));
        }
      }
      cur = walker.nextNode();
    }
    return {
      mode: 'snapshot',
      url: location.href,
      title: document.title || '',
      viewport: getViewport(),
      root_selector: rootSel || 'body',
      nodes: nodes,
      truncated: nodes.length >= maxNodes,
    };
  }

  function extractHtml(args) {
    var sel = args.selector;
    var maxChars = ((args.max_chars | 0) || 60000);
    var target = sel ? (resolveSelector(sel)[0] || null) : document.documentElement;
    if (!target) return { mode: 'html', error: 'no element matched: ' + sel };
    var html = target.outerHTML || '';
    var truncated = html.length > maxChars;
    return {
      mode: 'html',
      url: location.href,
      selector: sel || ':root',
      html: truncated ? html.substring(0, maxChars) : html,
      total_chars: html.length,
      truncated: truncated,
    };
  }

  // --------------------------------------------------------------- wait

  function waitForSelector(sel, timeoutMs, visibleOnly, reqId) {
    var deadline = Date.now() + timeoutMs;
    function check() {
      var matched = resolveSelector(sel);
      if (visibleOnly) matched = matched.filter(isVisible);
      if (matched.length > 0) {
        AmberWM.resolve(reqId, safeJson({
          waited_ms: timeoutMs - (deadline - Date.now()),
          matched: matched.length,
          first_path: nodePath(matched[0]),
        }));
        return;
      }
      if (Date.now() >= deadline) {
        AmberWM.reject(reqId, 'wait timed out after ' + timeoutMs + 'ms for selector: ' + sel);
        return;
      }
      setTimeout(check, 100);
    }
    check();
  }

  function waitForReadyState(target, timeoutMs, reqId) {
    var deadline = Date.now() + timeoutMs;
    function check() {
      if (document.readyState === target ||
          (target === 'complete' && document.readyState === 'complete') ||
          (target === 'interactive' && (document.readyState === 'interactive' || document.readyState === 'complete'))) {
        AmberWM.resolve(reqId, safeJson({ waited_ms: timeoutMs - (deadline - Date.now()), ready_state: document.readyState }));
        return;
      }
      if (Date.now() >= deadline) {
        AmberWM.reject(reqId, 'wait timed out, readyState=' + document.readyState);
        return;
      }
      setTimeout(check, 100);
    }
    check();
  }

  // --------------------------------------------------------------- mutations

  function dispatchMouseEvent(el, type) {
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2;
    var y = r.top + r.height / 2;
    var ev = new MouseEvent(type, {
      bubbles: true,
      cancelable: true,
      view: window,
      button: 0,
      clientX: x,
      clientY: y,
    });
    el.dispatchEvent(ev);
  }

  function performClick(args) {
    var sel = args.selector;
    if (!sel) throw new Error('click requires selector');
    var matched = resolveSelector(sel);
    if (matched.length === 0) throw new Error('no element matched: ' + sel);
    var target = matched[0];
    if (args.visible_only !== false && !isVisible(target)) {
      // Try scrollIntoView once.
      if (target.scrollIntoView) target.scrollIntoView({ block: 'center', inline: 'center' });
      if (!isVisible(target)) throw new Error('element not visible: ' + sel);
    }
    if (target.focus) try { target.focus(); } catch (_) {}
    dispatchMouseEvent(target, 'mousedown');
    dispatchMouseEvent(target, 'mouseup');
    // .click() handles default action (e.g. form submit, anchor navigation).
    try { target.click(); } catch (_) {}
    return {
      ok: true,
      target: { tag: (target.tagName || '').toLowerCase(), name: accessibleName(target), rect: rectOf(target) },
      url: location.href,
    };
  }

  function performType(args) {
    var sel = args.selector;
    if (!sel) throw new Error('type requires selector');
    var text = args.text;
    if (typeof text !== 'string') throw new Error('type requires text string');
    var matched = resolveSelector(sel);
    if (matched.length === 0) throw new Error('no element matched: ' + sel);
    var el = matched[0];
    if (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA' && !el.isContentEditable) {
      throw new Error('element is not editable: ' + sel);
    }
    if (el.focus) try { el.focus(); } catch (_) {}
    var clear = args.clear === true;
    if (clear) {
      if (el.isContentEditable) el.textContent = '';
      else el.value = '';
    }
    if (el.isContentEditable) {
      // Simple insert at end.
      el.textContent = (el.textContent || '') + text;
    } else {
      el.value = (el.value || '') + text;
    }
    // Fire input/change so frameworks observe the new value.
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    if (args.press_enter === true) {
      var k = new KeyboardEvent('keydown', { bubbles: true, key: 'Enter', code: 'Enter', keyCode: 13, which: 13 });
      el.dispatchEvent(k);
    }
    return {
      ok: true,
      target: { tag: (el.tagName || '').toLowerCase(), name: accessibleName(el) },
      value_chars: (el.isContentEditable ? (el.textContent || '') : (el.value || '')).length,
    };
  }

  function performEval(args) {
    var expr = args.expression;
    if (typeof expr !== 'string') throw new Error('eval requires expression string');
    // Two passes:
    //  1. Try wrapping as a single expression: `return (expr)`. Fast path
    //     for one-liners like `document.title` or `1+2`.
    //  2. Fall back to multi-statement: just `new Function(expr)`. This
    //     returns undefined unless the script contains its own `return`;
    //     we surface that as a note so the agent knows why result is null.
    var result;
    var multiStatement = false;
    try {
      var fn1 = new Function('return (' + expr + ');');
      result = fn1();
    } catch (_) {
      multiStatement = true;
      var fn2 = new Function(expr);  // may throw — propagated.
      result = fn2();
    }
    var serialized;
    try { serialized = JSON.parse(JSON.stringify(result === undefined ? null : result)); }
    catch (_) { serialized = String(result); }
    var payload = { ok: true, result: serialized, multi_statement: multiStatement };
    if (multiStatement && result === undefined) {
      payload.note = 'Multi-statement script ran but had no explicit return. Wrap in (() => { /* ... */ return value; })() to capture a value.';
    }
    return payload;
  }

  // --------------------------------------------------------------- network

  // Monkey-patch XMLHttpRequest and fetch so the agent can replay POST bodies
  // and inspect response payloads — Android's WebViewClient.shouldInterceptRequest
  // can't see POST bodies, so we capture them client-side. Best effort: the
  // shim is injected on every page load via reinjectBridge, but page bundle JS
  // may fire requests *before* our re-injection — those early requests are
  // missed. Documented in the M1.4 plan.
  if (!window.__amberWmNetShimInstalled) {
    window.__amberWmNetShimInstalled = true;
    var netSeq = { n: 0 };

    function bodyToString(body) {
      if (body == null) return null;
      try {
        if (typeof body === 'string') return body.length > 256000 ? (body.substring(0, 256000) + '…[truncated]') : body;
        if (body instanceof FormData) {
          var entries = [];
          // body.entries() may be unavailable in some old WebViews — guard.
          if (typeof body.entries === 'function') {
            try {
              var it = body.entries();
              while (true) {
                var step = it.next();
                if (step.done) break;
                entries.push([String(step.value[0]), step.value[1] instanceof Blob ? ('<blob ' + step.value[1].size + ' bytes>') : String(step.value[1])]);
                if (entries.length >= 100) break;
              }
            } catch (_) { /* ignore */ }
          }
          return JSON.stringify({ __form: entries });
        }
        if (body instanceof Blob) return '<blob ' + body.size + ' bytes>';
        if (body instanceof ArrayBuffer) return '<arraybuffer ' + body.byteLength + ' bytes>';
        if (body.toString) return body.toString();
      } catch (_) { /* ignore */ }
      return '<unserializable>';
    }

    function trimResponse(t) {
      if (t == null) return null;
      return t.length > 65536 ? (t.substring(0, 65536) + '…[truncated]') : t;
    }

    function sendNet(evt) {
      try {
        AmberWM.onNetworkEvent(JSON.stringify(evt));
      } catch (e) { /* bridge gone — ignore */ }
    }

    // ---- XHR ----
    var XHR_OPEN = XMLHttpRequest.prototype.open;
    var XHR_SEND = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (method, url) {
      this.__amberWm = {
        id: ++netSeq.n,
        method: String(method).toUpperCase(),
        url: String(url),
        ts: Date.now(),
      };
      return XHR_OPEN.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      var meta = this.__amberWm;
      if (meta) {
        meta.body_preview = bodyToString(body);
        sendNet({
          type: 'xhr_send', req_id: meta.id, method: meta.method, url: meta.url,
          ts: meta.ts, body_preview: meta.body_preview,
        });
        var self = this;
        this.addEventListener('loadend', function () {
          sendNet({
            type: 'xhr_done', req_id: meta.id, method: meta.method, url: meta.url,
            status: self.status, response_preview: trimResponse(self.responseText),
            response_chars: self.responseText ? self.responseText.length : 0,
            ts: Date.now(),
          });
        });
      }
      return XHR_SEND.apply(this, arguments);
    };

    // ---- fetch ----
    var ORIG_FETCH = window.fetch;
    if (typeof ORIG_FETCH === 'function') {
      window.fetch = function (input, init) {
        var reqId = ++netSeq.n;
        var url = (typeof input === 'string') ? input : (input && input.url) || '';
        var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
        var body = init && init.body ? bodyToString(init.body) : null;
        sendNet({
          type: 'fetch_send', req_id: reqId, method: method, url: String(url),
          body_preview: body, ts: Date.now(),
        });
        return ORIG_FETCH.apply(this, arguments).then(function (resp) {
          // Tee the response so the page still gets it.
          var clone;
          try { clone = resp.clone(); } catch (_) { clone = null; }
          if (clone) {
            clone.text().then(function (t) {
              sendNet({
                type: 'fetch_done', req_id: reqId, method: method, url: String(url),
                status: resp.status, response_preview: trimResponse(t),
                response_chars: t ? t.length : 0, ts: Date.now(),
              });
            }).catch(function () { /* body already consumed; ignore */ });
          } else {
            sendNet({
              type: 'fetch_done', req_id: reqId, method: method, url: String(url),
              status: resp.status, response_preview: null, response_chars: 0, ts: Date.now(),
            });
          }
          return resp;
        }, function (err) {
          sendNet({
            type: 'fetch_error', req_id: reqId, method: method, url: String(url),
            error: String(err && err.message || err), ts: Date.now(),
          });
          throw err;
        });
      };
    }
  }

  // --------------------------------------------------------------- navigation

  function performScroll(args) {
    var sel = args.selector;
    if (sel) {
      var matched = resolveSelector(sel);
      if (matched.length === 0) throw new Error('no element matched: ' + sel);
      var target = matched[0];
      if (target.scrollIntoView) target.scrollIntoView({ block: 'center', inline: 'center', behavior: 'auto' });
      return { ok: true, mode: 'into_view', selector: sel, after: { y: window.scrollY | 0 } };
    }
    if (args.to) {
      var to = args.to;
      if (to === 'top') { window.scrollTo(0, 0); }
      else if (to === 'bottom') { window.scrollTo(0, document.documentElement.scrollHeight | 0); }
      else if (typeof to === 'object' && to !== null) {
        window.scrollTo((to.x | 0), (to.y | 0));
      } else {
        throw new Error("scroll.to must be 'top' | 'bottom' | {x, y}");
      }
      return { ok: true, mode: 'to', after: { x: window.scrollX | 0, y: window.scrollY | 0 } };
    }
    if (args.by) {
      var by = args.by;
      if (!Array.isArray(by) || by.length !== 2) throw new Error('scroll.by must be [dx, dy]');
      window.scrollBy((by[0] | 0), (by[1] | 0));
      return { ok: true, mode: 'by', after: { x: window.scrollX | 0, y: window.scrollY | 0 } };
    }
    throw new Error('scroll requires selector | to | by');
  }

  function performHistory(direction) {
    var before = location.href;
    if (direction === 'back') window.history.back();
    else if (direction === 'forward') window.history.forward();
    else throw new Error('history requires direction=back|forward');
    return { ok: true, direction: direction, before_url: before };
  }

  function performKeys(args) {
    var key = args.key;
    if (typeof key !== 'string') throw new Error('keys requires key string');
    var target = document.activeElement || document.body;
    if (args.selector) {
      var matched = resolveSelector(args.selector);
      if (matched.length === 0) throw new Error('no element matched: ' + args.selector);
      target = matched[0];
      if (target.focus) try { target.focus(); } catch (_) {}
    }
    var mods = args.modifiers || {};
    var init = {
      bubbles: true,
      cancelable: true,
      key: key,
      code: keyToCode(key),
      keyCode: keyToCode(key, true),
      ctrlKey: !!mods.ctrl,
      shiftKey: !!mods.shift,
      altKey: !!mods.alt,
      metaKey: !!mods.meta,
    };
    target.dispatchEvent(new KeyboardEvent('keydown', init));
    target.dispatchEvent(new KeyboardEvent('keypress', init));
    target.dispatchEvent(new KeyboardEvent('keyup', init));
    return { ok: true, key: key, target: { tag: (target.tagName || '').toLowerCase() } };
  }

  function keyToCode(key, numeric) {
    var map = {
      Enter: numeric ? 13 : 'Enter',
      Escape: numeric ? 27 : 'Escape',
      Tab: numeric ? 9 : 'Tab',
      Backspace: numeric ? 8 : 'Backspace',
      Delete: numeric ? 46 : 'Delete',
      ArrowUp: numeric ? 38 : 'ArrowUp',
      ArrowDown: numeric ? 40 : 'ArrowDown',
      ArrowLeft: numeric ? 37 : 'ArrowLeft',
      ArrowRight: numeric ? 39 : 'ArrowRight',
      Home: numeric ? 36 : 'Home',
      End: numeric ? 35 : 'End',
      PageUp: numeric ? 33 : 'PageUp',
      PageDown: numeric ? 34 : 'PageDown',
      ' ': numeric ? 32 : 'Space',
      Space: numeric ? 32 : 'Space',
    };
    if (map.hasOwnProperty(key)) return map[key];
    if (numeric) return key.length === 1 ? key.charCodeAt(0) : 0;
    // For single characters, return 'Key<X>' code conventionally.
    return key.length === 1 ? 'Key' + key.toUpperCase() : key;
  }

  function performSelect(args) {
    var sel = args.selector;
    if (!sel) throw new Error('select requires selector');
    var matched = resolveSelector(sel);
    if (matched.length === 0) throw new Error('no element matched: ' + sel);
    var el = matched[0];
    if (el.tagName !== 'SELECT') throw new Error('not a <select>: ' + sel);
    var value = args.value;
    if (typeof value !== 'string') throw new Error('select requires value string');
    var found = false;
    for (var i = 0; i < el.options.length; i++) {
      if (el.options[i].value === value || el.options[i].text === value) {
        el.selectedIndex = i;
        found = true;
        break;
      }
    }
    if (!found) throw new Error('no option matched: ' + value);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return { ok: true, selected_index: el.selectedIndex, value: el.value };
  }

  function performFind(args) {
    var needle = args.text;
    if (typeof needle !== 'string' || needle.length === 0) throw new Error('find requires non-empty text');
    var caseSensitive = args.case_sensitive === true;
    var max = Math.min(((args.max | 0) || 20), 100);
    var query = caseSensitive ? needle : needle.toLowerCase();
    var matches = [];
    // Walk text nodes — bail out early at `max` matches.
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var node = walker.nextNode();
    while (node && matches.length < max) {
      var text = node.nodeValue || '';
      var hay = caseSensitive ? text : text.toLowerCase();
      if (hay.indexOf(query) >= 0) {
        var el = node.parentElement;
        if (el && isVisible(el)) {
          matches.push({
            path: nodePath(el),
            rect: rectOf(el),
            text_preview: text.replace(/\s+/g, ' ').trim().substring(0, 120),
          });
        }
      }
      node = walker.nextNode();
    }
    return { ok: true, matches: matches, total_returned: matches.length };
  }

  // --------------------------------------------------------------- dispatch

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
        case 'extract': {
          var mode = args.mode || 'readable';
          var payload;
          switch (mode) {
            case 'readable':    payload = extractReadable(args); break;
            case 'interactive': payload = extractInteractive(args); break;
            case 'snapshot':    payload = extractSnapshot(args); break;
            case 'html':        payload = extractHtml(args); break;
            default:
              AmberWM.reject(reqId, 'unknown extract mode: ' + mode);
              return;
          }
          AmberWM.resolve(reqId, safeJson(payload));
          return;
        }
        case 'wait': {
          var until = args.until || 'selector';
          var timeout = (args.timeout_ms | 0) || 10000;
          if (until === 'selector') {
            if (!args.value) { AmberWM.reject(reqId, 'wait selector requires value'); return; }
            waitForSelector(args.value, timeout, args.visible_only !== false, reqId);
            return;
          }
          if (until === 'ready_state') {
            waitForReadyState(args.value || 'complete', timeout, reqId);
            return;
          }
          AmberWM.reject(reqId, 'unknown wait kind: ' + until);
          return;
        }
        case 'click':    AmberWM.resolve(reqId, safeJson(performClick(args))); return;
        case 'type':     AmberWM.resolve(reqId, safeJson(performType(args))); return;
        case 'eval':     AmberWM.resolve(reqId, safeJson(performEval(args))); return;
        case 'scroll':   AmberWM.resolve(reqId, safeJson(performScroll(args))); return;
        case 'back':     AmberWM.resolve(reqId, safeJson(performHistory('back'))); return;
        case 'forward':  AmberWM.resolve(reqId, safeJson(performHistory('forward'))); return;
        case 'keys':     AmberWM.resolve(reqId, safeJson(performKeys(args))); return;
        case 'select':   AmberWM.resolve(reqId, safeJson(performSelect(args))); return;
        case 'find':     AmberWM.resolve(reqId, safeJson(performFind(args))); return;
        default:
          AmberWM.reject(reqId, 'unknown method: ' + method);
      }
    } catch (e) {
      AmberWM.reject(reqId, String(e && e.message || e));
    }
  };
})();

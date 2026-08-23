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
  window.__amberWmDocumentStart = window.__amberWmDocumentStart === true || document.readyState === 'loading';
  window.__amberWmSnapshotSeq = window.__amberWmSnapshotSeq || 0;
  window.__amberWmSnapshot = window.__amberWmSnapshot || { id: 0, entries: {} };

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

  function redactedUrl(url) {
    try {
      var u = new URL(String(url || ''), location.href);
      var out = u.protocol + '//' + u.host + u.pathname;
      var keys = [];
      u.searchParams.forEach(function (_, key) {
        if (keys.indexOf(key) < 0) keys.push(key);
      });
      if (keys.length > 0) {
        out += '?' + keys.map(function (key) {
          return encodeURIComponent(key) + '=<redacted>';
        }).join('&');
      }
      return out;
    } catch (_) {
      return String(url || '').split('#')[0].split('?')[0];
    }
  }

  function snapshotState() {
    return {
      url: redactedUrl(location.href),
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

  function hashString(s) {
    var h = 2166136261;
    for (var i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h += (h << 1) + (h << 4) + (h << 7) + (h << 8) + (h << 24);
    }
    return (h >>> 0).toString(16);
  }

  function semanticText(maxChars) {
    var raw = (document.body && (document.body.innerText || document.body.textContent)) || '';
    return raw.replace(/\s+/g, ' ').trim().substring(0, maxChars || 4000);
  }

  function semanticState(args) {
    args = args || {};
    var text = semanticText(args.text_fingerprint_chars || 4000);
    var interactive = Array.prototype.slice.call(document.querySelectorAll(
      'a[href],button,input,select,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[onclick]'
    )).filter(isVisible);
    var main = snapshotState();
    var signalSeq = window.__amberWmNetSignalSeq || 0;
    var fpParts = [
      location.href,
      document.title || '',
      document.readyState,
      text,
      interactive.slice(0, 80).map(function (el) {
        return [roleOf(el), accessibleName(el, 80), nodePath(el)].join(':');
      }).join('|'),
      window.scrollX | 0,
      window.scrollY | 0,
      args.ignore_network_signal === true ? 0 : signalSeq,
    ];
    main.semantic_fingerprint = hashString(fpParts.join('\n'));
    main.main_text_chars = ((document.body && (document.body.innerText || document.body.textContent)) || '').length;
    main.visible_text_sample = text.substring(0, args.text_sample_chars || 1200);
    main.interactive_count = interactive.length;
    main.network_signal_seq = signalSeq;
    main.network_pending = window.__amberWmNetPending || 0;
    main.network_coverage = window.__amberWmDocumentStart ? 'document_start' : 'page_finished';
    return main;
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
    // Cheap CSS path with standards-compliant nth-of-type indexes.
    // Capped at 6 segments to keep snapshot output small.
    var parts = [];
    var cur = el;
    while (cur && cur.nodeType === 1 && parts.length < 6) {
      var tag = cur.tagName ? cur.tagName.toLowerCase() : '#';
      var parent = cur.parentElement;
      if (parent) {
        var sibs = Array.prototype.filter.call(parent.children, function (c) { return c.tagName === cur.tagName; });
        if (sibs.length > 1) {
          tag += ':nth-of-type(' + (sibs.indexOf(cur) + 1) + ')';
        }
      }
      parts.unshift(tag);
      cur = cur.parentElement;
    }
    return parts.join('>');
  }

  function textPreview(el) {
    var t = (el && (el.innerText || el.textContent) || '').replace(/\s+/g, ' ').trim();
    return t.length > 120 ? t.substring(0, 120) : t;
  }

  function fingerprintOf(el) {
    var r = rectOf(el);
    var href = el && el.tagName === 'A' ? redactedUrl(el.href || '') : '';
    var inputType = el && el.tagName === 'INPUT' ? (el.type || '') : '';
    var name = accessibleName(el) || '';
    var text = textPreview(el);
    return {
      key: [
        (el.tagName || '').toLowerCase(),
        roleOf(el) || '',
        name,
        text,
        href,
        inputType,
      ].join('|'),
      rect_bucket: [
        Math.round((r[0] || 0) / 16),
        Math.round((r[1] || 0) / 16),
        Math.round((r[2] || 0) / 16),
        Math.round((r[3] || 0) / 16),
      ].join(','),
    };
  }

  function startSnapshot() {
    var id = ++window.__amberWmSnapshotSeq;
    window.__amberWmSnapshot = { id: id, entries: {} };
    return id;
  }

  function rememberSnapshotNode(el, node, forcedRef) {
    var snapshot = window.__amberWmSnapshot;
    var ref = forcedRef != null ? String(forcedRef) : String(Object.keys(snapshot.entries).length + 1);
    var fp = fingerprintOf(el);
    node.ref = ref;
    node.css = node.path;
    node.fingerprint = fp;
    snapshot.entries[ref] = {
      el: el,
      css: node.css,
      fingerprint: fp,
      summary: {
        ref: ref,
        tag: node.tag,
        role: node.role,
        name: node.name,
        text: node.text,
        rect: node.rect,
        visible: node.visible,
        css: node.css,
        fingerprint: fp,
      },
    };
    return node;
  }

  function targetError(code, message, hint, candidates) {
    return {
      ok: false,
      error: {
        code: code,
        message: message,
        hint: hint || null,
        candidates: (candidates || []).slice(0, 5),
      },
    };
  }

  function candidateSummary(el, ref) {
    var node = describeNode(el, { text: true });
    if (ref) node.ref = ref;
    node.css = node.path;
    node.fingerprint = fingerprintOf(el);
    return node;
  }

  function candidateSummaryNoText(el, ref) {
    var node = describeNode(el, { text: false });
    if (ref) node.ref = ref;
    node.css = node.path;
    node.fingerprint = fingerprintOf(el);
    return node;
  }

  function sameFingerprint(a, b, requireRect) {
    return a && b && a.key === b.key && (!requireRect || a.rect_bucket === b.rect_bucket);
  }

  function findByFingerprint(fingerprint, visibleOnly) {
    if (!fingerprint || !fingerprint.key) return [];
    var selector = 'a[href],button,input,select,textarea,[role=button],[role=link],[role=tab],[role=menuitem],[onclick],label,h1,h2,h3,h4,li,p,span,div';
    var all = Array.prototype.slice.call(document.querySelectorAll(selector));
    return all.filter(function (el) {
      if (visibleOnly && !isVisible(el)) return false;
      return sameFingerprint(fingerprintOf(el), fingerprint, true);
    });
  }

  function resolveSelectorTarget(selector, visibleOnly, opts, matchLevel) {
    var candidates = resolveSelector(selector);
    if (visibleOnly) candidates = candidates.filter(isVisible);
    if (candidates.length === 0) {
      return targetError('target_not_found', 'no element matched: ' + selector, 'Use wm_extract(mode="interactive") to inspect visible refs.');
    }
    if (candidates.length > 1 && opts.requireUnique === true) {
      return targetError('ambiguous_target', 'selector matched multiple elements: ' + selector, 'Use a returned ref instead of a broad selector.', candidates.map(function (el) { return candidateSummary(el); }));
    }
    return { ok: true, el: candidates[0], matches_n: candidates.length, match_level: matchLevel };
  }

  function resolveTarget(args, opts) {
    opts = opts || {};
    var visibleOnly = opts.visibleOnly !== false && args.visible_only !== false;
    var allowFingerprintFallback = opts.allowFingerprintFallback !== false;
    var requireStableRect = opts.requireStableRect === true;
    var target = args.target != null ? String(args.target) : null;
    var selector = args.selector;
    var candidates;

    if (target && window.__amberWmSnapshot && window.__amberWmSnapshot.entries[target]) {
      var entry = window.__amberWmSnapshot.entries[target];
      if (entry.el && entry.el.isConnected && (!visibleOnly || isVisible(entry.el)) && sameFingerprint(fingerprintOf(entry.el), entry.fingerprint, requireStableRect)) {
        return { ok: true, el: entry.el, matches_n: 1, match_level: 'exact_ref', ref: target };
      }
      candidates = entry.css ? resolveSelector(entry.css).filter(function (el) {
        return (!visibleOnly || isVisible(el)) && sameFingerprint(fingerprintOf(el), entry.fingerprint, requireStableRect);
      }) : [];
      if (candidates.length === 1) {
        return { ok: true, el: candidates[0], matches_n: 1, match_level: 'css', ref: target };
      }
      if (allowFingerprintFallback) {
        candidates = findByFingerprint(entry.fingerprint, visibleOnly);
        if (candidates.length === 1) {
          return { ok: true, el: candidates[0], matches_n: 1, match_level: 'fingerprint', ref: target };
        }
        if (candidates.length > 1) {
          return targetError('ambiguous_target', 'target ref matched multiple elements after DOM changed', 'Run wm_extract again and use the new ref.', candidates.map(function (el) { return candidateSummary(el); }));
        }
      }
      if (selector) {
        var fallback = resolveSelectorTarget(selector, visibleOnly, opts, 'selector_fallback');
        if (fallback.ok) fallback.ref = target;
        return fallback;
      }
      return targetError('target_not_found', 'target ref is no longer available: ' + target, 'Run wm_extract again and use the new ref.', entry.summary ? [entry.summary] : []);
    }

    var sel = selector || target;
    if (!sel) return targetError('missing_target', 'target or selector is required', 'Call wm_extract first, then pass a returned ref as target.');
    return resolveSelectorTarget(sel, visibleOnly, opts, selector ? 'selector' : 'target_selector');
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
    if (el.tagName === 'A' && el.href) node.href = redactedUrl(el.href);
    if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT') {
      var rawValue = '';
      try { rawValue = el.value || ''; } catch (_) {}
      node.value_chars = rawValue.length;
      if (el.type) node.input_type = el.type;
      if (el.tagName === 'INPUT' && (el.type === 'checkbox' || el.type === 'radio')) {
        node.checked = !!el.checked;
      }
      if (el.tagName === 'SELECT') {
        node.selected_index = el.selectedIndex;
        node.option_count = el.options ? el.options.length : 0;
      }
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
      links.push({ href: redactedUrl(href), text: label });
    }
    return {
      mode: 'readable',
      url: redactedUrl(location.href),
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
    var snapshotId = args.snapshot_id && window.__amberWmSnapshot && window.__amberWmSnapshot.id === args.snapshot_id
      ? args.snapshot_id
      : startSnapshot();
    for (var i = 0; i < els.length && nodes.length < maxNodes; i++) {
      var el = els[i];
      if (visible && !isVisible(el)) continue;
      nodes.push(rememberSnapshotNode(el, describeNode(el, { text: true })));
    }
    return {
      mode: 'interactive',
      snapshot_id: snapshotId,
      url: redactedUrl(location.href),
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
    var snapshotId = startSnapshot();
    while (cur && nodes.length < maxNodes) {
      if (!visible || isVisible(cur)) {
        var role = roleOf(cur);
        var hasName = accessibleName(cur);
        // Only keep semantically interesting nodes when not in role/name mode.
        if (role || hasName || cur === root) {
          nodes.push(rememberSnapshotNode(cur, describeNode(cur, { text: true })));
        }
      }
      cur = walker.nextNode();
    }
    return {
      mode: 'snapshot',
      snapshot_id: snapshotId,
      url: redactedUrl(location.href),
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
      url: redactedUrl(location.href),
      selector: sel || ':root',
      html: truncated ? html.substring(0, maxChars) : html,
      total_chars: html.length,
      truncated: truncated,
    };
  }

  // --------------------------------------------------------------- visual

  function nearestText(el) {
    var node = el;
    for (var i = 0; node && i < 3; i++, node = node.parentElement) {
      var t = cleanText(node.innerText || node.textContent || '', 220);
      if (t) return t;
    }
    return '';
  }

  function backgroundImageUrl(el) {
    try {
      var bg = getComputedStyle(el).backgroundImage || '';
      if (!bg || bg === 'none') return '';
      var m = bg.match(/url\(["']?([^"')]+)["']?\)/);
      return m ? m[1] : '';
    } catch (_) {
      return '';
    }
  }

  function visualCandidate(el, source, ref) {
    var node = describeNode(el, { text: false });
    node.ref = ref || node.ref;
    node.source = source;
    node.alt = (el.getAttribute && (el.getAttribute('alt') || el.getAttribute('title') || el.getAttribute('aria-label'))) || '';
    node.near_text = nearestText(el);
    if (el.tagName === 'IMG' && el.currentSrc) node.src_host = safeHost(el.currentSrc);
    if (source === 'background') node.background_host = safeHost(backgroundImageUrl(el));
    if (el.tagName === 'IFRAME') {
      node.frame_host = safeHost(el.src || '');
      node.cross_origin = !canReadFrame(el);
    }
    return node;
  }

  function safeHost(url) {
    try { return new URL(url, location.href).host; }
    catch (_) { return ''; }
  }

  function absoluteUrl(url) {
    try { return new URL(String(url || ''), location.href).href; }
    catch (_) { return String(url || ''); }
  }

  function canReadFrame(frame) {
    try {
      return !!(frame.contentWindow && frame.contentWindow.document && frame.contentWindow.document.body);
    } catch (_) {
      return false;
    }
  }

  function extractVisualSnapshot(args) {
    args = args || {};
    var maxCandidates = Math.min(((args.max_candidates | 0) || 30), 120);
    var nodes = [];
    var snapshotId = args.snapshot_id && window.__amberWmSnapshot && window.__amberWmSnapshot.id === args.snapshot_id
      ? args.snapshot_id
      : startSnapshot();
    var selector = 'img,canvas,svg,video,iframe,picture,[style*="background-image"]';
    var els = Array.prototype.slice.call(document.querySelectorAll(selector));
    for (var i = 0; i < els.length && nodes.length < maxCandidates; i++) {
      var el = els[i];
      if (!isVisible(el)) continue;
      var tag = (el.tagName || '').toLowerCase();
      var source = tag;
      if (tag !== 'img' && tag !== 'canvas' && tag !== 'svg' && tag !== 'video' && tag !== 'iframe') {
        if (!backgroundImageUrl(el)) continue;
        source = 'background';
      }
      var remembered = rememberSnapshotNode(el, visualCandidate(el, source));
      nodes.push(remembered);
    }
    return {
      mode: 'visual_snapshot',
      snapshot_id: snapshotId,
      url: redactedUrl(location.href),
      title: document.title || '',
      viewport: getViewport(),
      candidates: nodes,
      truncated: nodes.length >= maxCandidates && els.length > nodes.length,
      total_candidates: els.length,
      blind_spots: nodes.filter(function (n) { return n.source === 'canvas' || n.cross_origin === true; }).length,
    };
  }

  function targetRegion(args) {
    var resolved = resolveTarget(args, { requireUnique: true, allowFingerprintFallback: false, requireStableRect: true });
    if (!resolved.ok) return resolved;
    return {
      ok: true,
      target: candidateSummary(resolved.el, resolved.ref),
      rect: rectOf(resolved.el),
      matches_n: resolved.matches_n,
      match_level: resolved.match_level,
    };
  }

  function restoreSnapshotRefs(args) {
    args = args || {};
    var snapshotId = startSnapshot();
    var restored = 0;
    var missing = 0;
    function restoreList(nodes) {
      if (!Array.isArray(nodes)) return;
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i] || {};
        if (!node.ref || !node.css) {
          missing++;
          continue;
        }
        var matches = resolveSelector(node.css).filter(isVisible);
        var expected = node.fingerprint || null;
        var chosen = null;
        for (var j = 0; j < matches.length; j++) {
          if (!expected || sameFingerprint(fingerprintOf(matches[j]), expected, true)) {
            chosen = matches[j];
            break;
          }
        }
        if (chosen) {
          rememberSnapshotNode(chosen, describeNode(chosen, { text: true }), node.ref);
          restored++;
        } else {
          missing++;
        }
      }
    }
    restoreList(args.interactive && args.interactive.nodes);
    restoreList(args.visual && args.visual.candidates);
    return {
      ok: true,
      snapshot_id: snapshotId,
      restored: restored,
      missing: missing,
    };
  }

  function observePage(args) {
    args = args || {};
    var maxText = Math.min(((args.max_text_chars | 0) || 12000), 60000);
    var maxNodes = Math.min(((args.max_nodes | 0) || 80), 300);
    var maxVisual = Math.min(((args.max_visual_candidates | 0) || 30), 120);
    var snapshotId = startSnapshot();
    return {
      mode: 'observe',
      refs_shared: true,
      page: semanticState({ text_sample_chars: Math.min(maxText, 2000) }),
      readable: extractReadable({ max_chars: maxText, max_links: 40 }),
      interactive: extractInteractive({ max_nodes: maxNodes, visible_only: true, snapshot_id: snapshotId }),
      visual: extractVisualSnapshot({ max_candidates: maxVisual, snapshot_id: snapshotId }),
    };
  }

  // --------------------------------------------------------------- Feishu docs

  function isFeishuLikeHost(host) {
    host = String(host || '').toLowerCase();
    return host === 'feishu.cn' || host.slice(-10) === '.feishu.cn' ||
      host === 'larksuite.com' || host.slice(-14) === '.larksuite.com' ||
      host === 'larkoffice.com' || host.slice(-15) === '.larkoffice.com' ||
      host === 'feishu.net' || host.slice(-11) === '.feishu.net';
  }

  function feishuDocInfo() {
    var m = location.pathname.match(/\/(docx|docs|doc|wiki|sheets|base|mindnotes)\/([A-Za-z0-9_-]+)/i);
    if (!isFeishuLikeHost(location.hostname) || !m) return null;
    var t = m[1].toLowerCase();
    if (t === 'doc' || t === 'docs') t = 'docx';
    return { doc_type: t, doc_token: m[2] };
  }

  function cleanText(s, max) {
    var t = String(s || '').replace(/\u00a0/g, ' ').replace(/[ \t]+/g, ' ').replace(/\n{3,}/g, '\n\n').trim();
    max = max || 2000;
    return t.length > max ? (t.substring(0, max) + '…') : t;
  }

  function textFingerprint(text) {
    var s = cleanText(text, 240);
    var h = 0;
    for (var i = 0; i < s.length; i++) h = ((h << 5) - h + s.charCodeAt(i)) | 0;
    return Math.abs(h).toString(36) + ':' + s.length;
  }

  function feishuBlockType(el, text) {
    var tag = (el.tagName || '').toLowerCase();
    var role = el.getAttribute && el.getAttribute('role');
    if (/^h[1-6]$/.test(tag) || role === 'heading') {
      var level = /^h[1-6]$/.test(tag) ? parseInt(tag.substring(1), 10) : parseInt(el.getAttribute('aria-level') || '2', 10);
      return { type: 'heading', level: level || 2 };
    }
    if (tag === 'pre' || tag === 'code') return { type: 'code', level: null };
    if (/^(\u2022|-|\*)\s+/.test(text)) return { type: 'bullet', level: null };
    if (/^\d+[\.)]\s+/.test(text)) return { type: 'ordered', level: null };
    return { type: 'paragraph', level: null };
  }

  function dataBlockId(el) {
    if (!el || !el.getAttribute) return null;
    return el.getAttribute('data-block-id') ||
      el.getAttribute('data-docx-block-id') ||
      el.getAttribute('data-block-token') ||
      el.getAttribute('data-node-id');
  }

  function isElementInViewport(el) {
    if (!isVisible(el)) return false;
    var r = el.getBoundingClientRect();
    return r.bottom >= 0 && r.top <= window.innerHeight && r.right >= 0 && r.left <= window.innerWidth;
  }

  function collectFeishuBlockElements(maxBlocks) {
    var selectors = [
      '[data-block-id]',
      '[data-docx-block-id]',
      '[data-block-token]',
      '[data-node-id]',
      'article h1, article h2, article h3, article h4, article h5, article h6',
      'article p, article li, article pre, article blockquote',
      '[contenteditable="true"] h1, [contenteditable="true"] h2, [contenteditable="true"] h3',
      '[contenteditable="true"] p, [contenteditable="true"] li, [contenteditable="true"] pre',
      '[role="textbox"] h1, [role="textbox"] h2, [role="textbox"] h3',
      '[role="textbox"] p, [role="textbox"] li, [role="textbox"] pre'
    ].join(',');
    var els = Array.prototype.slice.call(document.querySelectorAll(selectors));
    var out = [];
    var seen = {};
    for (var i = 0; i < els.length && out.length < maxBlocks; i++) {
      var el = els[i];
      if (!isElementInViewport(el)) continue;
      var text = cleanText(el.innerText || el.textContent || '', 2000);
      if (!text || text.length < 2) continue;
      var rect = rectOf(el);
      var key = dataBlockId(el) || (textFingerprint(text) + ':' + Math.round((rect[1] || 0) / 8));
      if (seen[key]) continue;
      seen[key] = true;
      out.push(el);
    }
    return out;
  }

  function fallbackVisibleParagraphs(maxBlocks, snapshotId) {
    var raw = cleanText((document.body && document.body.innerText) || '', 12000);
    if (!raw) return [];
    return raw.split(/\n{2,}/).map(function (p) { return cleanText(p, 1200); })
      .filter(function (p) { return p.length > 1; })
      .slice(0, maxBlocks)
      .map(function (text, idx) {
        return {
          block_ref: 'wfblk_' + snapshotId + '_fallback_' + (idx + 1),
          type: 'paragraph',
          text: text,
          level: null,
          rect: null,
          stable_hint: {
            source: 'visible_text_fallback',
            text_fingerprint: textFingerprint(text),
            scope: 'current_webview_session',
            resolvable: false,
          },
        };
      });
  }

  function feishuSnapshot(args) {
    var info = feishuDocInfo();
    if (!info) {
      return {
        ok: false,
        error: {
          code: 'not_feishu_doc_page',
          message: 'Current WebMount page is not a recognized Feishu/Lark document URL.',
          next_action: 'Open a Feishu cloud document in WebMount, then call feishu_docs_snapshot again.',
        },
      };
    }
    var maxBlocks = Math.min(((args.max_blocks | 0) || 80), 300);
    var selected = '';
    try { selected = cleanText(String(window.getSelection && window.getSelection() || ''), 4000); } catch (_) {}
    var snapshotId = startSnapshot();
    var blocks = [];
    var els = collectFeishuBlockElements(maxBlocks);
    for (var i = 0; i < els.length && blocks.length < maxBlocks; i++) {
      var el = els[i];
      var text = cleanText(el.innerText || el.textContent || '', 2000);
      var kind = feishuBlockType(el, text);
      var blockId = dataBlockId(el);
      var ref = 'wfblk_' + snapshotId + '_' + (blocks.length + 1);
      var css = nodePath(el);
      blocks.push({
        block_ref: ref,
        type: kind.type,
        text: text,
        level: kind.level,
        rect: rectOf(el),
        stable_hint: {
          css: css,
          data_block_id: blockId,
          text_fingerprint: textFingerprint(text),
          scope: 'current_webview_session',
        },
      });
    }
    var limitations = [];
    if (blocks.length === 0) {
      blocks = fallbackVisibleParagraphs(maxBlocks, snapshotId);
      limitations.push('structured_block_dom_not_found');
    }
    if (blocks.length >= maxBlocks) limitations.push('visible_blocks_truncated');
    var outline = blocks.filter(function (b) { return b.type === 'heading'; }).slice(0, 80).map(function (b) {
      return { text: b.text, level: b.level || 2, block_ref: b.block_ref };
    });
    return {
      ok: true,
      mode: 'feishu_docs_snapshot',
      session_scope: 'current_webview_session',
      url: redactedUrl(location.href),
      doc_type: info.doc_type,
      doc_token: info.doc_token,
      title: cleanText(document.title || '', 300),
      selected_text: selected,
      outline: outline,
      visible_blocks: blocks,
      limitations: limitations,
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

  function waitForSemanticIdle(args, reqId) {
    var timeoutMs = (args.timeout_ms | 0) || 10000;
    var stableMs = Math.max(250, Math.min((args.stable_ms | 0) || 700, 3000));
    var deadline = Date.now() + timeoutMs;
    var requireNetworkIdle = args.until === 'network_idle';
    var ignoreNetworkSignal = args.until === 'dom_stable';
    var lastFingerprint = null;
    var stableSince = 0;
    function check() {
      var stateArgs = {};
      for (var k in args) if (Object.prototype.hasOwnProperty.call(args, k)) stateArgs[k] = args[k];
      stateArgs.ignore_network_signal = ignoreNetworkSignal;
      var state = semanticState(stateArgs);
      var readyEnough = document.readyState === 'interactive' || document.readyState === 'complete';
      var networkReady = !requireNetworkIdle || (state.network_pending | 0) === 0;
      if (readyEnough && networkReady && state.semantic_fingerprint === lastFingerprint) {
        if (!stableSince) stableSince = Date.now();
        if (Date.now() - stableSince >= stableMs) {
          state.waited_ms = timeoutMs - (deadline - Date.now());
          state.stable_ms = Date.now() - stableSince;
          state.ok = true;
          AmberWM.resolve(reqId, safeJson(state));
          return;
        }
      } else {
        lastFingerprint = state.semantic_fingerprint;
        stableSince = readyEnough && networkReady ? Date.now() : 0;
      }
      if (Date.now() >= deadline) {
        state.ok = false;
        state.timeout = true;
        state.fallback_reason = 'semantic_idle_timeout';
        AmberWM.resolve(reqId, safeJson(state));
        return;
      }
      setTimeout(check, 120);
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
    var resolved = resolveTarget(args, { allowFingerprintFallback: false, requireStableRect: true });
    if (!resolved.ok) return resolved;
    var target = resolved.el;
    if (args.visible_only !== false && !isVisible(target)) {
      // Try scrollIntoView once.
      if (target.scrollIntoView) target.scrollIntoView({ block: 'center', inline: 'center' });
      if (!isVisible(target)) return targetError('target_not_visible', 'element is not visible', 'Run wm_scroll on the same target or call wm_extract again.');
    }
    if (target.focus) try { target.focus(); } catch (_) {}
    dispatchMouseEvent(target, 'mousedown');
    dispatchMouseEvent(target, 'mouseup');
    // .click() handles default action (e.g. form submit, anchor navigation).
    try { target.click(); } catch (_) {}
    return {
      ok: true,
      target: candidateSummary(target, resolved.ref),
      matches_n: resolved.matches_n,
      match_level: resolved.match_level,
      url: redactedUrl(location.href),
    };
  }

  function performTap(args) {
    var x = args.x | 0;
    var y = args.y | 0;
    var target = document.elementFromPoint(x, y);
    if (!target) return targetError('target_not_found', 'no element at viewport coordinate', 'Use wm_visual_snapshot or wm_observe to inspect visible regions.');
    if (target.focus) try { target.focus(); } catch (_) {}
    dispatchMouseEvent(target, 'mousedown');
    dispatchMouseEvent(target, 'mouseup');
    try { target.click(); } catch (_) {}
    return {
      ok: true,
      x: x,
      y: y,
      target: candidateSummary(target),
      url: redactedUrl(location.href),
    };
  }

  function performType(args) {
    var text = args.text;
    if (typeof text !== 'string') throw new Error('type requires text string');
    var resolved = resolveTarget(args, { requireUnique: true, allowFingerprintFallback: false, requireStableRect: true });
    if (!resolved.ok) return resolved;
    var el = resolved.el;
    if (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA' && !el.isContentEditable) {
      return targetError('target_not_editable', 'element is not editable', 'Use wm_extract(mode="interactive") and choose an input, textarea, or contenteditable node.', [candidateSummary(el, resolved.ref)]);
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
      target: candidateSummaryNoText(el, resolved.ref),
      matches_n: resolved.matches_n,
      match_level: resolved.match_level,
      value_chars: (el.isContentEditable ? (el.textContent || '') : (el.value || '')).length,
    };
  }

  function performGet(args) {
    var resolved = resolveTarget(args, { requireUnique: true });
    if (!resolved.ok) return resolved;
    var el = resolved.el;
    var kind = args.kind || 'text';
    var value = null;
    if (kind === 'text') {
      value = el.innerText || el.textContent || '';
    } else if (kind === 'value') {
      value = el.isContentEditable ? (el.textContent || '') : (el.value || '');
    } else if (kind === 'html') {
      value = el.outerHTML || '';
    } else if (kind === 'attr') {
      var attr = args.attr_name;
      if (typeof attr !== 'string' || attr.length === 0) {
        return targetError('missing_attr_name', 'kind=attr requires attr_name', 'Pass attr_name, for example "href" or "aria-label".');
      }
      value = el.getAttribute(attr);
    } else {
      return targetError('invalid_kind', 'unknown wm_get kind: ' + kind, 'Use text, value, attr, or html.');
    }
    var maxChars = Math.min(((args.max_chars | 0) || 20000), 100000);
    var text = value == null ? null : String(value);
    return {
      ok: true,
      kind: kind,
      value: text == null ? null : text.substring(0, maxChars),
      total_chars: text == null ? 0 : text.length,
      truncated: text != null && text.length > maxChars,
      target: candidateSummary(el, resolved.ref),
      matches_n: resolved.matches_n,
      match_level: resolved.match_level,
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

  // Monkey-patch XMLHttpRequest and fetch so Kotlin can build redacted request
  // templates and semantic network signals. Android's WebViewClient cannot see
  // POST bodies, so the raw event stays process-local and is sanitized before
  // any tool output reaches the model. Best effort: page bundle JS may fire
  // requests before fallback re-injection; the Kotlin side reports coverage.
  if (!window.__amberWmNetShimInstalled) {
    window.__amberWmNetShimInstalled = true;
    window.__amberWmNetPending = window.__amberWmNetPending || 0;
    window.__amberWmNetSignalSeq = window.__amberWmNetSignalSeq || 0;
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

    function isSignalRequest(url) {
      var u = String(url || '').toLowerCase();
      return !(
        u.indexOf('google-analytics') >= 0 ||
        u.indexOf('/analytics') >= 0 ||
        u.indexOf('/beacon') >= 0 ||
        u.indexOf('doubleclick') >= 0 ||
        u.indexOf('googletagmanager') >= 0 ||
        u.indexOf('sentry') >= 0 ||
        u.indexOf('log.') >= 0 ||
        u.indexOf('/log') >= 0 ||
        u.indexOf('/metrics') >= 0 ||
        u.indexOf('/collect') >= 0
      );
    }

    function sendNet(evt) {
      try {
        if (evt && evt.url && isSignalRequest(evt.url)) {
          window.__amberWmNetSignalSeq = (window.__amberWmNetSignalSeq || 0) + 1;
        }
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
        url: absoluteUrl(url),
        ts: Date.now(),
      };
      return XHR_OPEN.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function (body) {
      var meta = this.__amberWm;
      if (meta) {
        meta.body_preview = bodyToString(body);
        window.__amberWmNetPending = (window.__amberWmNetPending || 0) + 1;
        sendNet({
          type: 'xhr_send', req_id: meta.id, method: meta.method, url: meta.url,
          ts: meta.ts, body_preview: meta.body_preview,
        });
        var self = this;
        this.addEventListener('loadend', function () {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'xhr_done', req_id: meta.id, method: meta.method, url: meta.url,
            status: self.status, response_preview: trimResponse(self.responseText),
            response_chars: self.responseText ? self.responseText.length : 0,
            ts: Date.now(),
          });
        });
      }
      try {
        return XHR_SEND.apply(this, arguments);
      } catch (err) {
        if (meta) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'xhr_error', req_id: meta.id, method: meta.method, url: meta.url,
            error: String(err && err.message || err), ts: Date.now(),
          });
        }
        throw err;
      }
    };

    // ---- fetch ----
    var ORIG_FETCH = window.fetch;
    if (typeof ORIG_FETCH === 'function') {
      window.fetch = function (input, init) {
        var reqId = ++netSeq.n;
        var url = absoluteUrl((typeof input === 'string') ? input : (input && input.url) || '');
        var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
        var body = init && init.body ? bodyToString(init.body) : null;
        window.__amberWmNetPending = (window.__amberWmNetPending || 0) + 1;
        sendNet({
          type: 'fetch_send', req_id: reqId, method: method, url: String(url),
          body_preview: body, ts: Date.now(),
        });
        var fetchPromise;
        try {
          fetchPromise = ORIG_FETCH.apply(this, arguments);
        } catch (err) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'fetch_error', req_id: reqId, method: method, url: String(url),
            error: String(err && err.message || err), ts: Date.now(),
          });
          throw err;
        }
        return fetchPromise.then(function (resp) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'fetch_done', req_id: reqId, method: method, url: String(url),
            status: resp.status, response_preview: null, response_chars: 0, ts: Date.now(),
          });
          // Tee the response so the page still gets it.
          var clone;
          try { clone = resp.clone(); } catch (_) { clone = null; }
          if (clone) {
            clone.text().then(function (t) {
              sendNet({
                type: 'fetch_preview', req_id: reqId, method: method, url: String(url),
                status: resp.status, response_preview: trimResponse(t),
                response_chars: t ? t.length : 0, ts: Date.now(),
              });
            }).catch(function () { /* body already consumed; ignore */ });
          }
          return resp;
        }, function (err) {
          window.__amberWmNetPending = Math.max(0, (window.__amberWmNetPending || 0) - 1);
          sendNet({
            type: 'fetch_error', req_id: reqId, method: method, url: String(url),
            error: String(err && err.message || err), ts: Date.now(),
          });
          throw err;
        });
      };
    }
  }

  function performFetchReplay(args, reqId) {
    var method = String(args.method || 'GET').toUpperCase();
    if (method !== 'GET' && method !== 'HEAD') {
      AmberWM.reject(reqId, 'fetch_replay only supports GET/HEAD');
      return;
    }
    var url = String(args.url || '');
    if (!url) {
      AmberWM.reject(reqId, 'fetch_replay requires url');
      return;
    }
    var maxChars = Math.max(0, Math.min((args.max_chars | 0) || 60000, 200000));
    fetch(url, { method: method, credentials: 'include', cache: 'no-store' }).then(function (resp) {
      var headers = {};
      try {
        resp.headers.forEach(function (value, key) {
          var lower = String(key).toLowerCase();
          if (lower === 'set-cookie' || lower === 'cookie' || lower === 'authorization') return;
          if (lower.indexOf('token') >= 0 || lower.indexOf('secret') >= 0) return;
          headers[key] = value;
        });
      } catch (_) {}
      return resp.text().then(function (text) {
        AmberWM.resolve(reqId, safeJson({
          ok: true,
          url: redactedUrl(resp.url || url),
          status: resp.status,
          content_type: resp.headers.get('content-type') || '',
          headers: headers,
          text: text.substring(0, maxChars),
          text_chars: text.length,
          truncated: text.length > maxChars,
        }));
      });
    }, function (err) {
      AmberWM.resolve(reqId, safeJson({
        ok: false,
        error: String(err && err.message || err),
      }));
    });
  }

  // --------------------------------------------------------------- navigation

  function performScroll(args) {
    if (args.target || args.selector) {
      var resolved = resolveTarget(args, { allowFingerprintFallback: false, requireStableRect: true });
      if (!resolved.ok) return resolved;
      var target = resolved.el;
      if (target.scrollIntoView) target.scrollIntoView({ block: 'center', inline: 'center', behavior: 'auto' });
      return {
        ok: true,
        mode: 'into_view',
        target: candidateSummary(target, resolved.ref),
        matches_n: resolved.matches_n,
        match_level: resolved.match_level,
        after: { y: window.scrollY | 0 },
      };
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
    var before = redactedUrl(location.href);
    if (direction === 'back') window.history.back();
    else if (direction === 'forward') window.history.forward();
    else throw new Error('history requires direction=back|forward');
    return { ok: true, direction: direction, before_url: before };
  }

  function performKeys(args) {
    var key = args.key;
    if (typeof key !== 'string') throw new Error('keys requires key string');
    var target = document.activeElement || document.body;
    var resolved = null;
    if (args.target || args.selector) {
      resolved = resolveTarget(args, { allowFingerprintFallback: false, requireStableRect: true });
      if (!resolved.ok) return resolved;
      target = resolved.el;
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
    return {
      ok: true,
      key: key,
      target: candidateSummary(target, resolved && resolved.ref),
      matches_n: resolved ? resolved.matches_n : 1,
      match_level: resolved ? resolved.match_level : 'active_element',
    };
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
    var resolved = resolveTarget(args, { requireUnique: true, allowFingerprintFallback: false, requireStableRect: true });
    if (!resolved.ok) return resolved;
    var el = resolved.el;
    if (el.tagName !== 'SELECT') return targetError('target_not_select', 'target is not a <select>', 'Use wm_extract(mode="interactive") and choose a select node.', [candidateSummary(el, resolved.ref)]);
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
    if (!found) return targetError('option_not_found', 'no option matched: ' + value, 'Read the select options with wm_get(kind="html") and pass the option value or text.');
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return {
      ok: true,
      selected_index: el.selectedIndex,
      value: el.value,
      target: candidateSummary(el, resolved.ref),
      matches_n: resolved.matches_n,
      match_level: resolved.match_level,
    };
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
            css: nodePath(el),
            rect: rectOf(el),
            text_preview: text.replace(/\s+/g, ' ').trim().substring(0, 120),
          });
        }
      }
      node = walker.nextNode();
    }
    return { ok: true, matches: matches, total_returned: matches.length };
  }

  function actionResult(fn) {
    try { return fn(); }
    catch (e) {
      return targetError('action_failed', String(e && e.message || e), 'Inspect the page with wm_extract and retry with a returned ref.');
    }
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
        case 'semantic_state':
          AmberWM.resolve(reqId, safeJson(semanticState(args)));
          return;
        case 'observe':
          AmberWM.resolve(reqId, safeJson(observePage(args)));
          return;
        case 'visual_snapshot':
          AmberWM.resolve(reqId, safeJson(extractVisualSnapshot(args)));
          return;
        case 'restore_snapshot_refs':
          AmberWM.resolve(reqId, safeJson(restoreSnapshotRefs(args)));
          return;
        case 'target_region':
          AmberWM.resolve(reqId, safeJson(targetRegion(args)));
          return;
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
          if (until === 'semantic_idle' || until === 'dom_stable' || until === 'network_idle') {
            waitForSemanticIdle(args, reqId);
            return;
          }
          AmberWM.reject(reqId, 'unknown wait kind: ' + until);
          return;
        }
        case 'click':    AmberWM.resolve(reqId, safeJson(actionResult(function () { return performClick(args); }))); return;
        case 'tap':      AmberWM.resolve(reqId, safeJson(actionResult(function () { return performTap(args); }))); return;
        case 'type':     AmberWM.resolve(reqId, safeJson(actionResult(function () { return performType(args); }))); return;
        case 'get':      AmberWM.resolve(reqId, safeJson(actionResult(function () { return performGet(args); }))); return;
        case 'feishu_snapshot':
          AmberWM.resolve(reqId, safeJson(actionResult(function () { return feishuSnapshot(args); })));
          return;
        case 'eval':     AmberWM.resolve(reqId, safeJson(performEval(args))); return;
        case 'scroll':   AmberWM.resolve(reqId, safeJson(actionResult(function () { return performScroll(args); }))); return;
        case 'back':     AmberWM.resolve(reqId, safeJson(performHistory('back'))); return;
        case 'forward':  AmberWM.resolve(reqId, safeJson(performHistory('forward'))); return;
        case 'keys':     AmberWM.resolve(reqId, safeJson(actionResult(function () { return performKeys(args); }))); return;
        case 'select':   AmberWM.resolve(reqId, safeJson(actionResult(function () { return performSelect(args); }))); return;
        case 'find':     AmberWM.resolve(reqId, safeJson(performFind(args))); return;
        case 'fetch_replay':
          performFetchReplay(args, reqId);
          return;
        default:
          AmberWM.reject(reqId, 'unknown method: ' + method);
      }
    } catch (e) {
      AmberWM.reject(reqId, String(e && e.message || e));
    }
  };
})();

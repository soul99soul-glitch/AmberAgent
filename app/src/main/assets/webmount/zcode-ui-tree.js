// AmberAgent WebMount — ZCode v4 semantic UI tree.
//
// This file is loaded after bridge.js and deliberately has no dependency on
// bridge-private symbols. zcode_read supplies the small helper surface below
// so all returned refs belong to the bridge snapshot used by the same read.
// The adapter reads rendered DOM only; it never calls a ZCode private API.

(function () {
  'use strict';

  if (typeof window === 'undefined' || typeof document === 'undefined') return;

  var MAX_CONTROL_LABEL = 220;
  var MAX_MESSAGE_TEXT = 120000;

  function attr(el, name) {
    if (!el || !el.getAttribute) return null;
    var value = el.getAttribute(name);
    return value == null ? null : String(value);
  }

  function cleanText(value, max) {
    var text = String(value == null ? '' : value)
      .replace(/\u00a0/g, ' ')
      .replace(/[ \t]+/g, ' ')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
    if (max != null && text.length > max) return text.substring(0, max) + '…';
    return text;
  }

  function clampInt(value, fallback, min, max) {
    var n = Number(value);
    if (!isFinite(n)) n = fallback;
    n = Math.floor(n);
    if (n < min) n = min;
    if (n > max) n = max;
    return n;
  }

  function booleanAttr(value) {
    if (value == null) return null;
    var normalized = String(value).toLowerCase();
    if (normalized === 'true' || normalized === '1' || normalized === 'open' || normalized === 'selected' || normalized === 'active') return true;
    if (normalized === 'false' || normalized === '0' || normalized === 'closed' || normalized === 'idle') return false;
    return null;
  }

  function isDisabled(el) {
    if (!el) return false;
    if (el.disabled === true) return true;
    return String(attr(el, 'aria-disabled') || '').toLowerCase() === 'true';
  }

  function stateValue(el, ariaName, dataName) {
    var aria = attr(el, ariaName);
    if (aria != null) return booleanAttr(aria);
    var data = attr(el, dataName);
    if (data != null) return booleanAttr(data);
    return null;
  }

  function selectedValue(el) {
    var aria = attr(el, 'aria-selected');
    if (aria != null) return booleanAttr(aria);
    var data = String(attr(el, 'data-state') || '').toLowerCase();
    if (data === 'selected' || data === 'active') return true;
    if (data === 'idle') return false;
    return null;
  }

  function fallbackVisible(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.hidden || String(attr(el, 'aria-hidden') || '').toLowerCase() === 'true') return false;
    var style = '';
    try { style = window.getComputedStyle(el); } catch (_) {}
    if (style && (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity || '1') === 0)) return false;
    return true;
  }

  function visible(helpers, el) {
    try {
      if (helpers && typeof helpers.isVisible === 'function') return helpers.isVisible(el) === true;
    } catch (_) {}
    return fallbackVisible(el);
  }

  function rectOf(el) {
    try {
      var r = el && el.getBoundingClientRect ? el.getBoundingClientRect() : null;
      if (r) return r;
    } catch (_) {}
    return { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
  }

  function inViewport(el) {
    var r = rectOf(el);
    var width = Number(window.innerWidth) || 0;
    var height = Number(window.innerHeight) || 0;
    return r.right > 0 && r.bottom > 0 && r.left < width && r.top < height;
  }

  function tagName(el) {
    return String(el && el.tagName || '').toLowerCase();
  }

  function roleFor(el, described) {
    if (described && described.role) return described.role;
    var explicit = attr(el, 'role');
    if (explicit) return explicit;
    switch (tagName(el)) {
      case 'a': return 'link';
      case 'button': return 'button';
      case 'textarea': return 'textbox';
      case 'select': return 'combobox';
      case 'input': return 'input:' + (attr(el, 'type') || 'text');
      default: return null;
    }
  }

  function nameFor(el, described) {
    var name = described && described.name;
    if (!name) name = attr(el, 'aria-label') || attr(el, 'aria-placeholder');
    if (!name) name = attr(el, 'placeholder') || attr(el, 'title');
    if (!name) name = attr(el, 'data-testid');
    return cleanText(name, MAX_CONTROL_LABEL) || null;
  }

  function suppressedTextElement(el) {
    if (!el || el.nodeType !== 1) return false;
    if (el.hidden || String(attr(el, 'aria-hidden') || '').toLowerCase() === 'true') return true;
    var state = String(attr(el, 'data-state') || '').toLowerCase();
    var testId = String(attr(el, 'data-testid') || '').toLowerCase();
    var slot = String(attr(el, 'data-slot') || '').toLowerCase();
    if (attr(el, 'data-history-open') === 'false') return true;
    if (state === 'closed' && (slot === 'collapsible-content' || /(^|-)content$/.test(testId) || attr(el, 'data-reasoning-content') != null)) return true;
    var style = '';
    try { style = window.getComputedStyle(el); } catch (_) {}
    return !!(style && (style.display === 'none' || style.visibility === 'hidden'));
  }

  // innerText normally excludes display:none content, but React collapsible
  // content can remain in the DOM during an animation. Walk text nodes so an
  // unopened reasoning/history block cannot masquerade as an answer.
  function renderedText(root, maxChars) {
    if (!root) return '';
    var pieces = [];
    var length = 0;
    function walk(node, isRoot) {
      if (!node || length >= MAX_MESSAGE_TEXT) return;
      if (node.nodeType === 3) {
        var value = String(node.nodeValue || '');
        if (value) {
          pieces.push(value);
          length += value.length;
        }
        return;
      }
      if (node.nodeType !== 1) return;
      if (!isRoot && suppressedTextElement(node)) return;
      var tag = tagName(node);
      var block = /^(address|article|aside|blockquote|dd|div|dl|dt|fieldset|figcaption|figure|footer|form|h[1-6]|header|li|main|nav|ol|p|pre|section|table|tbody|td|tfoot|th|thead|tr|ul)$/.test(tag);
      if (tag === 'br') {
        pieces.push('\n');
        length++;
        return;
      }
      if (!isRoot && block) pieces.push('\n');
      var children = node.childNodes || [];
      for (var i = 0; i < children.length; i++) walk(children[i], false);
      if (!isRoot && block) pieces.push('\n');
    }
    walk(root, true);
    var text = cleanText(pieces.join(''), null);
    return maxChars == null ? text : cleanText(text, maxChars);
  }

  function hashText(value) {
    // FNV-1a is intentionally local and non-cryptographic. It is only a
    // change marker for a same-row streaming update, never an identity token.
    var text = String(value || '');
    var hash = 2166136261;
    for (var i = 0; i < text.length; i++) {
      hash ^= text.charCodeAt(i);
      hash += (hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24);
    }
    return (hash >>> 0).toString(16);
  }

  function remember(helpers, el, node) {
    var described = null;
    try { described = helpers && helpers.describe ? helpers.describe(el) : null; } catch (_) {}
    described = described || {};
    // rememberSnapshotNode needs path/rect/visible to populate its internal
    // snapshot entry. They are removed from the public node immediately after
    // the call so zcode_read does not repeat generic CSS/fingerprint payloads.
    if (node.path == null) node.path = described.path;
    if (node.rect == null) node.rect = described.rect;
    if (node.visible == null) node.visible = described.visible;
    var result = node;
    try {
      if (helpers && typeof helpers.remember === 'function') result = helpers.remember(el, node) || node;
    } catch (_) {
      result = node;
    }
    delete result.css;
    delete result.fingerprint;
    delete result.path;
    delete result.rect;
    delete result.visible;
    return result;
  }

  function control(helpers, el, kind, extra) {
    var described = null;
    try { described = helpers && helpers.describe ? helpers.describe(el) : null; } catch (_) {}
    described = described || {};
    var node = {
      kind: kind,
      tag: tagName(el),
      role: roleFor(el, described),
      name: nameFor(el, described),
      disabled: isDisabled(el),
      expanded: stateValue(el, 'aria-expanded', 'data-state'),
      selected: selectedValue(el),
      in_viewport: inViewport(el),
      test_id: attr(el, 'data-testid') || null,
      path: described.path,
      rect: described.rect,
      visible: described.visible,
    };
    var text = renderedText(el, MAX_CONTROL_LABEL);
    // Contenteditable draft text is user input; describe() reports its length
    // but this semantic tree never echoes the draft itself.
    if (tagName(el) !== 'div' || attr(el, 'data-lexical-editor') !== 'true') {
      if (text && text !== node.name) node.text = text;
    }
    if (extra) {
      for (var key in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, key)) node[key] = extra[key];
      }
    }
    return remember(helpers, el, node);
  }

  function container(helpers, el, kind, extra) {
    var described = null;
    try { described = helpers && helpers.describe ? helpers.describe(el) : null; } catch (_) {}
    described = described || {};
    var node = {
      kind: kind,
      tag: tagName(el),
      role: roleFor(el, described),
      name: nameFor(el, described),
      disabled: false,
      expanded: stateValue(el, 'aria-expanded', 'data-state'),
      selected: selectedValue(el),
      in_viewport: inViewport(el),
      test_id: attr(el, 'data-testid') || null,
      path: described.path,
      rect: described.rect,
      visible: described.visible,
    };
    if (extra) {
      for (var key in extra) {
        if (Object.prototype.hasOwnProperty.call(extra, key)) node[key] = extra[key];
      }
    }
    return remember(helpers, el, node);
  }

  function eachSelector(selectors, visitor) {
    var seen = [];
    for (var i = 0; i < selectors.length; i++) {
      var elements = [];
      try { elements = Array.prototype.slice.call(document.querySelectorAll(selectors[i])); } catch (_) {}
      for (var j = 0; j < elements.length; j++) {
        var el = elements[j];
        if (seen.indexOf(el) >= 0) continue;
        seen.push(el);
        visitor(el);
      }
    }
    return seen;
  }

  function directLabel(el) {
    var label = attr(el, 'aria-label') || attr(el, 'title') || attr(el, 'data-testid');
    if (label) return cleanText(label, MAX_CONTROL_LABEL);
    var text = '';
    try { text = el.innerText || el.textContent || ''; } catch (_) {}
    return cleanText(text, MAX_CONTROL_LABEL);
  }

  function workspaceLike(el) {
    if (String(attr(el, 'data-testid') || '').indexOf('task-item-') === 0) return false;
    var label = directLabel(el);
    if (!label) return false;
    // Workspace expanders expose title/path/count text. Do not classify task
    // page branch/menu triggers (which have aria-expanded but no workspace
    // evidence) as workspaces.
    return /(^|\s)(workspace|工作区|项目|tasks?|任务|count|数量)(\s|$)/i.test(label) ||
      /(^|[/\\])[^/\\]+[/\\]/.test(label) || /\b\d+\b/.test(label);
  }

  function homeTask(helpers, el) {
    var testId = String(attr(el, 'data-testid') || '');
    var taskId = testId.indexOf('task-item-') === 0 ? testId.substring('task-item-'.length) : null;
    var statusText = attr(el, 'data-status') || attr(el, 'data-task-status');
    if (!statusText) {
      var statusEl = null;
      try { statusEl = el.querySelector('[data-status],[data-task-status],[data-testid*="status"]'); } catch (_) {}
      if (statusEl) statusText = renderedText(statusEl, 80);
    }
    if (!statusText) statusText = attr(el, 'data-state');
    var selected = attr(el, 'data-state') === 'selected' || attr(el, 'aria-selected') === 'true';
    var pinned = attr(el, 'data-pinned');
    var node = control(helpers, el, 'task_item', {
      task_id: taskId,
      current: selected,
      status: cleanText(statusText, 80) || null,
    });
    if (pinned != null) node.pinned = String(pinned).toLowerCase() === 'true';
    // The item name is sufficient for selection. Avoid echoing the same
    // title again through the generic control text field.
    delete node.text;
    return node;
  }

  function dataNumber(el, name) {
    var value = Number(attr(el, name));
    return isFinite(value) && value >= 0 ? Math.floor(value) : null;
  }

  function scrollNode(helpers, el, kind) {
    var timeline = attr(el, 'data-testid') === 'v4-timeline';
    var top = Number(el.scrollTop) || 0;
    var client = Number(el.clientHeight) || 0;
    var height = Number(el.scrollHeight) || 0;
    var maxTop = Math.max(0, height - client);
    var following = attr(el, 'data-following');
    var node = container(helpers, el, kind, {
      name: timeline ? 'messages' : (nameFor(el, {}) || kind),
      scroll_top: top,
      client_height: client,
      scroll_height: height,
      can_scroll_up: top > 0,
      can_scroll_down: height > client && top < maxTop,
      scrollable: height > client,
      direction: [top > 0 ? 'up' : null, height > client && top < maxTop ? 'down' : null].filter(Boolean),
    });
    if (timeline) {
      node.row_count = dataNumber(el, 'data-row-count');
      node.window_row_count = dataNumber(el, 'data-window-row-count');
      node.total_row_count = dataNumber(el, 'data-total-row-count');
      node.following = booleanAttr(following);
      node.loading_older = booleanAttr(attr(el, 'data-loading-older'));
      node.scrollable = node.scrollable || (node.total_row_count != null && node.total_row_count > (node.window_row_count || 0));
      if (node.following === true && node.scroll_height <= node.client_height) node.can_scroll_down = false;
    }
    return node;
  }

  function currentPane(helpers, pane) {
    var taskId = attr(pane, 'data-session-id');
    var titleEl = document.querySelector('[data-testid="workspace-title"]') || document.querySelector('[data-testid="v4-session-title"][data-title]');
    var pathEl = document.querySelector('[data-testid="workspace-path"]');
    var title = titleEl ? (attr(titleEl, 'data-title') || renderedText(titleEl, 300)) : null;
    var project = pathEl ? renderedText(pathEl, 200) : null;
    var projectPath = pathEl ? attr(pathEl, 'title') : null;
    return container(helpers, pane, 'current_task', {
      current: true,
      task_id: taskId,
      title: cleanText(title, 300) || null,
      project: cleanText(project, 200) || null,
      project_path: cleanText(projectPath, 500) || null,
      projection_seq: attr(pane, 'data-projection-seq'),
      running_subagent_count: String(attr(pane, 'data-running-subagent-ids') || '').split(',').filter(Boolean).length,
      running_subagent_work_count: String(attr(pane, 'data-running-subagent-work-ids') || '').split(',').filter(Boolean).length,
    });
  }

  function messageKind(row, tools, reasoning) {
    if (row.querySelector && row.querySelector('[data-v4-user-input-collapsible-content]')) return 'user';
    if (/assistant-row/.test(String(row.className || ''))) return 'assistant';
    if (tools.length > 0) return 'tool';
    if (reasoning) return 'reasoning';
    if (/user-row/.test(String(row.className || ''))) return 'user';
    return 'event';
  }

  function rowOrder(row, fallback) {
    var value = Number(attr(row, 'data-row-id'));
    return isFinite(value) ? value : fallback;
  }

  function messageNode(helpers, row, index) {
    var tools = [];
    var toolEls = [];
    try { toolEls = Array.prototype.slice.call(row.querySelectorAll('[data-tool-name][data-status]')); } catch (_) {}
    for (var i = 0; i < toolEls.length; i++) {
      var tool = toolEls[i];
      tools.push({
        name: attr(tool, 'data-tool-name'),
        status: attr(tool, 'data-status'),
      });
    }
    var reasoning = null;
    try { reasoning = row.querySelector('[data-testid="chat-reasoning-trigger"]'); } catch (_) {}
    var kind = messageKind(row, tools, reasoning);
    var userContent = null;
    try { userContent = row.querySelector('[data-v4-user-input-collapsible-content]'); } catch (_) {}
    var fullText = renderedText(userContent || row, null);
    var hashInput = kind + '|' + fullText + '|' + tools.map(function (tool) { return tool.name + ':' + tool.status; }).join('|') + '|' + (reasoning ? String(attr(reasoning, 'aria-expanded') || attr(reasoning, 'data-state') || '') : '');
    var node = {
      kind: kind,
      row_id: attr(row, 'data-row-id'),
      in_viewport: inViewport(row),
      rendered: true,
      text: fullText,
      text_chars: fullText.length,
      _content_hash: hashText(hashInput),
    };
    if (tools.length > 0) node.tools = tools;
    if (reasoning) {
      node.expanded = stateValue(reasoning, 'aria-expanded', 'data-state');
      node.reasoning_trigger = control(helpers, reasoning, 'reasoning_toggle');
    }
    // Give the row itself a ref so wm_scroll can move a virtualized timeline
    // item into view. Action controls nested in the row receive their own refs.
    var remembered = remember(helpers, row, node);
    var controls = [];
    var controlEls = [];
    try {
      controlEls = Array.prototype.slice.call(row.querySelectorAll('button,[role="button"],a[href]'));
    } catch (_) {}
    for (var j = 0; j < controlEls.length && controls.length < 8; j++) {
      var candidate = controlEls[j];
      if (!visible(helpers, candidate)) continue;
      if (reasoning && candidate === reasoning) continue;
      controls.push(control(helpers, candidate, 'message_control'));
    }
    if (remembered.reasoning_trigger) {
      controls.unshift(remembered.reasoning_trigger);
      delete remembered.reasoning_trigger;
    }
    if (controls.length > 0) remembered.controls = controls;
    remembered._order = rowOrder(row, index);
    return remembered;
  }

  function parseCursor(raw) {
    if (typeof raw !== 'string' || raw.trim() === '') return null;
    try {
      var parsed = JSON.parse(raw);
      if (!parsed || typeof parsed !== 'object' || parsed.v !== 1 || parsed.task_id == null) return { invalid: true };
      return parsed;
    } catch (_) {
      return { invalid: true };
    }
  }

  function makeCursor(taskId, last, projectionSeq) {
    if (!taskId) return null;
    return JSON.stringify({
      v: 1,
      task_id: String(taskId),
      row_id: last ? String(last.row_id) : null,
      row_hash: last ? String(last._content_hash || '') : '',
      projection_seq: projectionSeq == null ? null : String(projectionSeq),
    });
  }

  function sameRow(a, b) {
    return a != null && b != null && String(a) === String(b);
  }

  function pageMessages(helpers, args, timeline, taskId, projectionSeq) {
    var maxMessages = clampInt(args.max_messages, 12, 1, 40);
    // First inspect only row ids and visibility. A live ZCode task can keep
    // hundreds of virtualized rows in the DOM; building text, refs, and
    // nested controls for all of them made every poll scale with history.
    var rows = [];
    var rowEls = [];
    try { rowEls = Array.prototype.slice.call((timeline || document).querySelectorAll('[data-row-id]')); } catch (_) {}
    for (var i = 0; i < rowEls.length; i++) {
      var row = rowEls[i];
      if (!attr(row, 'data-row-id') || !visible(helpers, row)) continue;
      rows.push({
        el: row,
        row_id: attr(row, 'data-row-id'),
        order: rowOrder(row, i),
      });
    }
    rows.sort(function (a, b) { return (a.order || 0) - (b.order || 0); });
    var renderedCount = rows.length;
    var total = timeline ? dataNumber(timeline, 'data-total-row-count') : null;
    if (total == null && timeline) total = dataNumber(timeline, 'data-row-count');
    if (total == null) total = renderedCount;
    var windowCount = timeline ? dataNumber(timeline, 'data-window-row-count') : null;
    var loadingOlder = timeline ? booleanAttr(attr(timeline, 'data-loading-older')) === true : false;
    var earlierUnavailable = loadingOlder || total > renderedCount || (windowCount != null && windowCount > renderedCount);

    var parsed = parseCursor(args.cursor);
    var reset = false;
    var resetReason = null;
    if (parsed && parsed.invalid) {
      reset = true;
      resetReason = 'invalid_cursor';
      parsed = null;
    } else if (parsed && String(parsed.task_id) !== String(taskId || '')) {
      reset = true;
      resetReason = 'task_changed';
      parsed = null;
    }

    var selectedEntries = [];
    var cursorState = 'initial';
    var pageTruncated = false;
    if (!parsed) {
      selectedEntries = rows.slice(Math.max(0, rows.length - maxMessages));
      pageTruncated = rows.length > maxMessages;
      if (args.cursor != null && args.cursor !== '') cursorState = 'reset';
    } else {
      var newer = [];
      var cursorEntry = null;
      var cursorOrder = Number(parsed.row_id);
      var numericCursor = isFinite(cursorOrder);
      for (var j = 0; j < rows.length; j++) {
        var candidate = rows[j];
        if (sameRow(candidate.row_id, parsed.row_id)) cursorEntry = candidate;
        var after = parsed.row_id == null
          ? true
          : (numericCursor && isFinite(candidate.order)
            ? candidate.order > cursorOrder
            : String(candidate.row_id) > String(parsed.row_id == null ? '' : parsed.row_id));
        if (after) newer.push(candidate);
      }
      var cursorNode = null;
      var changed = false;
      // Only the cursor row is materialized for change detection. This is the
      // important streaming case: the final row can keep the same row id while
      // its text/tool status is being replaced in place.
      if (cursorEntry) {
        cursorNode = messageNode(helpers, cursorEntry.el, cursorEntry.order);
        changed = String(cursorNode._content_hash || '') !== String(parsed.row_hash || '');
        // A projection update can replace a final row without changing its
        // visible text (for example a tool status). Treat it as fresh only
        // when the cursor row remains the rendered tail.
        if (!changed && newer.length === 0 && cursorEntry === rows[rows.length - 1] &&
            projectionSeq != null && parsed.projection_seq != null &&
            String(projectionSeq) !== String(parsed.projection_seq)) {
          changed = true;
        }
      }
      if (changed) {
        // Preserve the changed cursor row, then deliver the earliest unread
        // rows. Taking the tail here would skip an intermediate page forever.
        selectedEntries = [cursorNode].concat(newer.slice(0, Math.max(0, maxMessages - 1)));
        pageTruncated = newer.length > Math.max(0, maxMessages - 1);
        cursorState = 'changed';
      } else {
        selectedEntries = newer.slice(0, maxMessages);
        pageTruncated = newer.length > maxMessages;
        cursorState = selectedEntries.length > 0 ? 'advanced' : 'unchanged';
      }
    }

    var selected = [];
    for (var s = 0; s < selectedEntries.length; s++) {
      var entry = selectedEntries[s];
      // changed entries already carry the one materialized cursor node.
      selected.push(entry && entry._content_hash != null
        ? entry
        : messageNode(helpers, entry.el, entry.order));
    }
    var textBudget = clampInt(args.max_text_chars, 16000, 1, 60000);
    var textTruncated = false;
    var remaining = textBudget;
    for (var m = 0; m < selected.length; m++) {
      var item = selected[m];
      var original = String(item.text || '');
      if (remaining <= 0 && original.length > 0) {
        item.text = '';
        item.text_truncated = true;
        textTruncated = true;
      } else if (original.length > remaining) {
        item.text = original.substring(0, remaining) + '…';
        item.text_truncated = true;
        textTruncated = true;
        remaining = 0;
      } else {
        remaining -= original.length;
      }
    }
    // Do not advance past text that was never delivered. The caller can issue
    // the same read with a larger budget and receive that row again. When the
    // first selected row is too large there is no safe new watermark, so keep
    // the incoming cursor (or return null on the initial read).
    var deliveredLast = null;
    for (var q = selected.length - 1; q >= 0; q--) {
      if (selected[q].text_truncated !== true) {
        deliveredLast = selected[q];
        break;
      }
    }
    var nextCursor = deliveredLast
      ? makeCursor(taskId, deliveredLast, projectionSeq)
      : (!reset && args.cursor != null && args.cursor !== ''
        ? String(args.cursor)
        : makeCursor(taskId, null, projectionSeq));
    for (var clean = 0; clean < selected.length; clean++) {
      delete selected[clean]._content_hash;
      delete selected[clean]._order;
    }
    return {
      items: selected,
      total: total,
      rendered: renderedCount,
      row_count: timeline ? dataNumber(timeline, 'data-row-count') : null,
      window_row_count: windowCount,
      loading_older: loadingOlder,
      returned: selected.length,
      truncated: pageTruncated || textTruncated,
      earlier_unavailable: earlierUnavailable,
      message_cursor: nextCursor,
      cursor_state: cursorState,
      cursor_reset: reset,
      cursor_reset_reason: resetReason,
      task_id: taskId || null,
      projection_seq: projectionSeq == null ? null : String(projectionSeq),
    };
  }

  function guidanceFor(pageKind, taskId, tree, messages) {
    if (pageKind === 'home') {
      return {
        next_actions: [{
          kind: 'select_task',
          tool: 'wm_click',
          target_groups: ['workspaces', 'tasks'],
          reason: 'Choose a returned workspace or task ref before reading ZCode.',
        }],
      };
    }
    if (pageKind === 'task') {
      if (tree.composer && tree.composer.busy) {
        var actions = [{
          kind: 'poll',
          tool: 'wm_zcode_read',
          cursor: messages.message_cursor,
          reason: 'The remote task is running; read the same task with the message cursor.',
        }];
        var timeline = tree.scroll_regions.filter(function (region) { return region.kind === 'timeline'; })[0];
        if (timeline && timeline.ref) {
          actions.push({
            kind: 'scroll',
            tool: 'wm_scroll',
            target: timeline.ref,
            reason: 'Scroll the rendered timeline ref when older rows are needed.',
          });
        }
        return { next_actions: actions };
      }
      if (tree.composer && tree.composer.draft_chars === 0 && tree.composer.send && taskId) {
        return {
          next_actions: [{
            kind: 'ask',
            tool: 'wm_zcode_ask',
            remote_task_id: taskId,
            requires: ['snapshot_id', 'remote_task_id', 'input_target', 'send_target'],
            approval_required: true,
            reason: 'After explicit approval, send one message with the fresh snapshot and composer refs.',
          }],
        };
      }
      return {
        next_actions: [{
          kind: 'read',
          tool: 'wm_zcode_read',
          cursor: messages.message_cursor,
          reason: 'Read the current remote task before deciding on an action.',
        }],
      };
    }
    return {
      next_actions: [{
        kind: 'observe',
        tool: 'wm_observe',
        reason: 'The page does not match the known ZCode v4 DOM contract.',
      }],
    };
  }

  function zcodeUiTree(args, helpers) {
    args = args || {};
    helpers = helpers || {};
    var snapshotId = helpers.snapshotId == null ? null : helpers.snapshotId;
    var tree = {
      navigation: [],
      workspaces: [],
      tasks: [],
      messages: null,
      composer: null,
      dialogs: [],
      scroll_regions: [],
    };

    var timeline = null;
    try { timeline = document.querySelector('[data-testid="v4-timeline"]'); } catch (_) {}
    var panes = [];
    try { panes = Array.prototype.slice.call(document.querySelectorAll('[data-testid^="v4-session-pane-"][data-session-id]')); } catch (_) {}
    var pane = null;
    for (var p = 0; p < panes.length; p++) {
      if (visible(helpers, panes[p])) { pane = panes[p]; break; }
    }
    var taskId = pane ? attr(pane, 'data-session-id') : null;
    var projectionSeq = pane ? attr(pane, 'data-projection-seq') : null;
    var homeItems = [];
    try { homeItems = Array.prototype.slice.call(document.querySelectorAll('button[data-testid^="task-item-"]')); } catch (_) {}
    var pageKind = pane ? 'task' : (homeItems.length > 0 ? 'home' : 'unknown');

    eachSelector([
      'button[aria-label="返回任务首页"]',
      '[data-testid="workspace-header"] button',
      '[data-testid="workspace-header"] [role="button"]',
      '[data-testid="side-pane-toggle"]',
      '[data-testid="workspace-more-button"]',
      '[data-testid="git-action-trigger"]',
    ], function (el) {
      if (visible(helpers, el)) tree.navigation.push(control(helpers, el, 'navigation'));
    });

    // Workspace/task list controls belong to the home view. On a task page
    // aria-expanded is heavily used by history/tool rows, and treating those
    // timers as workspace entries makes the tree noisy and misleading.
    if (pageKind === 'home') {
      eachSelector([
        'button[data-testid^="task-item-"]',
        'button[aria-expanded]'
      ], function (el) {
        if (!visible(helpers, el)) return;
        var testId = String(attr(el, 'data-testid') || '');
        if (testId.indexOf('task-item-') === 0) {
          tree.tasks.push(homeTask(helpers, el));
        } else if (workspaceLike(el)) {
          tree.workspaces.push(control(helpers, el, 'workspace'));
        }
      });
    }

    if (panes.length > 0) {
      for (var t = 0; t < panes.length; t++) {
        if (!visible(helpers, panes[t])) continue;
        tree.tasks.push(currentPane(helpers, panes[t]));
      }
    }
    if (tree.tasks.length === 0) {
      var sessionTitle = null;
      try { sessionTitle = document.querySelector('[data-testid="v4-session-title"][data-title]'); } catch (_) {}
      if (sessionTitle && visible(helpers, sessionTitle)) {
        tree.tasks.push(control(helpers, sessionTitle, 'task_title', { current: true }));
      }
    }

    if (timeline && visible(helpers, timeline)) {
      tree.scroll_regions.push(scrollNode(helpers, timeline, 'timeline'));
    }
    // Keep generic scrolling useful for side panes and virtualized workspace
    // lists while avoiding a snapshot entry for every layout wrapper.
    eachSelector(['aside', 'main', 'section', 'div'], function (el) {
      if (tree.scroll_regions.length >= 8 || !visible(helpers, el) || el === timeline) return;
      var style = null;
      try { style = window.getComputedStyle(el); } catch (_) {}
      var overflowY = style && String(style.overflowY || '').toLowerCase();
      var overflowX = style && String(style.overflowX || '').toLowerCase();
      var scrollable = overflowY === 'auto' || overflowY === 'scroll' || overflowX === 'auto' || overflowX === 'scroll';
      if (!scrollable && !attr(el, 'data-v4-timeline-scroll')) return;
      if (attr(el, 'data-v4-timeline-scroll') === 'true' || (Number(el.scrollHeight) || 0) > (Number(el.clientHeight) || 0)) {
        tree.scroll_regions.push(scrollNode(helpers, el, attr(el, 'data-v4-timeline-scroll') === 'true' ? 'timeline' : 'scroll_region'));
      }
    });

    var composer = null;
    try { composer = Array.prototype.slice.call(document.querySelectorAll('[data-testid="v4-composer"]')).filter(function (el) { return visible(helpers, el); })[0] || null; } catch (_) {}
    if (composer) {
      var editor = null;
      var send = null;
      var stop = null;
      try { editor = Array.prototype.slice.call(composer.querySelectorAll('[data-testid="v4-composer-input"]')).filter(function (el) { return visible(helpers, el); })[0] || null; } catch (_) {}
      try { send = Array.prototype.slice.call(composer.querySelectorAll('[data-testid="v4-composer-send"]')).filter(function (el) { return visible(helpers, el); })[0] || null; } catch (_) {}
      try { stop = Array.prototype.slice.call(composer.querySelectorAll('[data-testid="v4-stop"]')).filter(function (el) { return visible(helpers, el); })[0] || null; } catch (_) {}
      var composerControls = [];
      eachSelector([
        '[data-testid="chat-model-select-trigger"]',
        '[data-testid="chat-mode-select-trigger"]',
        '[data-testid="chat-thought-level-select-trigger"]',
      ], function (el) {
        if (composer.contains(el) && visible(helpers, el)) composerControls.push(control(helpers, el, 'composer_control'));
      });
      var draftChars = 0;
      if (editor) draftChars = renderedText(editor, MAX_MESSAGE_TEXT).length;
      var attachments = 0;
      try { attachments = composer.querySelectorAll('[data-composer-attachment-kind]').length; } catch (_) {}
      var loading = null;
      try { loading = document.querySelector('[data-testid="chat-loading"][role="status"]'); } catch (_) {}
      tree.composer = container(helpers, composer, 'composer', {
        name: 'composer',
        routing: attr(composer, 'data-input-routing'),
        draft_chars: draftChars,
        attachment_count: attachments,
        busy: !!((loading && visible(helpers, loading)) || stop),
        input: editor ? control(helpers, editor, 'composer_input', { name: attr(editor, 'aria-placeholder') || nameFor(editor, {}) }) : null,
        send: send ? control(helpers, send, 'composer_send') : null,
        stop: stop ? control(helpers, stop, 'composer_stop') : null,
        controls: composerControls,
      });
    }

    eachSelector(['[role="dialog"]', 'dialog', '[data-radix-popper-content-wrapper]'], function (el) {
      if (!visible(helpers, el)) return;
      var controls = [];
      var descendants = [];
      try { descendants = Array.prototype.slice.call(el.querySelectorAll('button,[role="button"],[role="menuitem"],[role="option"]')); } catch (_) {}
      for (var d = 0; d < descendants.length && controls.length < 20; d++) {
        if (visible(helpers, descendants[d])) controls.push(control(helpers, descendants[d], 'dialog_control'));
      }
      tree.dialogs.push(container(helpers, el, attr(el, 'role') === 'dialog' || tagName(el) === 'dialog' ? 'dialog' : 'popover', {
        controls: controls,
      }));
    });

    var messages = pageMessages(helpers, args, timeline, taskId, projectionSeq);
    tree.messages = messages;
    var guidance = guidanceFor(pageKind, taskId, tree, messages);

    var result = {
      ok: true,
      mode: 'zcode_ui_tree',
      snapshot_id: snapshotId,
      page_kind: pageKind,
      task_id: taskId,
      projection_seq: projectionSeq,
      ui_tree: tree,
      message_cursor: messages.message_cursor,
      cursor_reset: messages.cursor_reset,
      guidance: guidance,
    };
    if (args.include_page_text === true) {
      var maxText = clampInt(args.max_text_chars, 16000, 1, 60000);
      result.page_text = renderedText(document.body, maxText);
      result.page_text_chars = result.page_text.length;
      result.page_text_truncated = ((document.body && (document.body.innerText || document.body.textContent) || '').length > maxText);
    }
    return result;
  }

  window.__amberZCodeUiTree = zcodeUiTree;
})();

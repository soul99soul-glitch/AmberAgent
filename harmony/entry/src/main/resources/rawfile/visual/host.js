'use strict';
(() => {
  const MAX_VALIDATION_SVG_CHARS = 120000;
  const frame = document.getElementById('content');
  const scene = document.getElementById('scene');
  const threeApi = window.AmberThree;
  const hasThree = scene !== null && threeApi !== null && typeof threeApi === 'object';
  let latest = null;
  let observer = null;
  let revision = 0;
  let active = true;
  let three = false;
  let disposed = false;
  let measurement = 0;
  let threeReady = 0;
  let lastReport = '';
  let lastSafeSvg = null;
  let lastSafeSvgHeight = 180;
  let strictMode = false;
  let strictValidation = null;
  let fallbackError = '';

  function report(height, ok, content, error = '', validation = null) {
    if (disposed) return;
    const message = JSON.stringify({
      revision,
      height,
      ok,
      content,
      error: String(error || '').slice(0, 240),
      validationRevision: validation === null ? -1 : revision,
      validationOk: validation === null ? false : validation.ok === true,
      validationError: validation === null ? '' : String(validation.error || '').slice(0, 240),
      validationSvg: validation !== null && validation.ok === true && typeof validation.svg === 'string' &&
        validation.svg.length <= MAX_VALIDATION_SVG_CHARS ? validation.svg : '',
    });
    if (message !== lastReport) {
      lastReport = message;
      if (window.AmberVisualHost && typeof window.AmberVisualHost.postMessage === 'function') {
        window.AmberVisualHost.postMessage(message);
      }
    }
  }

  // Preserve nodes, open details and scroll offsets while new markup arrives.
  function patch(current, next) {
    for (const attr of [...current.attributes]) {
      if (!next.hasAttribute(attr.name) && !(current.localName === 'details' && attr.name === 'open')) {
        current.removeAttribute(attr.name);
      }
    }
    for (const attr of [...next.attributes]) {
      if (current.getAttribute(attr.name) !== attr.value) {
        if (attr.namespaceURI) current.setAttributeNS(attr.namespaceURI, attr.name, attr.value);
        else current.setAttribute(attr.name, attr.value);
      }
    }
    let cursor = current.firstChild;
    for (const child of [...next.childNodes]) {
      let found = cursor;
      if (child.nodeType === 1 && child.id) {
        found = [...current.children].find(node => node.id === child.id) || null;
      }
      if (found && found.nodeType === child.nodeType && found.nodeName === child.nodeName) {
        if (found !== cursor) current.insertBefore(found, cursor);
        if (child.nodeType === 1) patch(found, child);
        else if (found.nodeValue !== child.nodeValue) found.nodeValue = child.nodeValue;
        cursor = found.nextSibling;
      } else {
        const added = current.ownerDocument.importNode(child, true);
        current.insertBefore(added, cursor);
        if (found === cursor && found) {
          cursor = found.nextSibling;
          current.removeChild(found);
        }
      }
    }
    while (cursor) {
      const next = cursor.nextSibling;
      current.removeChild(cursor);
      cursor = next;
    }
  }

  function measure() {
    if (disposed || three || measurement || frame === null ||
      (strictMode && strictValidation !== null && strictValidation.ok === false)) return;
    measurement = requestAnimationFrame(() => {
      measurement = 0;
      if (disposed || three || frame.contentDocument === null || !frame.contentDocument.body ||
        (strictMode && strictValidation !== null && strictValidation.ok === false)) return;
      const body = frame.contentDocument.body;
      const height = Math.min(10000, Math.max(120, Math.ceil(Math.max(body.scrollHeight, body.getBoundingClientRect().height))));
      if (frame.style.height !== `${height}px`) frame.style.height = `${height}px`;
      const content = !!body.querySelector('svg,img,table,ul,ol,article,section,div,p,h1,h2,h3,details') || body.innerText.trim().length > 0;
      report(height, fallbackError.length === 0, content, fallbackError);
    });
  }

  function renderDocument(document) {
    const next = new DOMParser().parseFromString(document, 'text/html');
    if (!frame.contentDocument || !frame.contentDocument.body) throw new Error('预览宿主文档无效');
    // The iframe never receives allow-scripts, forms, popups, downloads or navigation.
    patch(frame.contentDocument.head, next.head);
    patch(frame.contentDocument.body, next.body);
    if (observer) observer.disconnect();
    observer = new ResizeObserver(measure);
    observer.observe(frame.contentDocument.body);
    if (frame.contentDocument.fonts) frame.contentDocument.fonts.ready.then(measure);
    measure();
  }

  function parseStrictSvg(raw) {
    if (typeof raw !== 'string' || raw.trim().length === 0) throw new Error('SVG 内容为空');
    let parsed = new DOMParser().parseFromString(raw, 'image/svg+xml');
    let root = parsed.documentElement;
    if (!root || parsed.getElementsByTagName('parsererror').length > 0 || root.localName.toLowerCase() !== 'svg') {
      throw new Error('SVG XML 结构无效');
    }
    if (root.namespaceURI !== 'http://www.w3.org/2000/svg') {
      if (root.namespaceURI || root.localName.toLowerCase() !== 'svg') throw new Error('SVG 根节点无效');
      // XML without xmlns is accepted only after an explicit namespace repair and a second parse.
      root.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
      parsed = new DOMParser().parseFromString(new XMLSerializer().serializeToString(root), 'image/svg+xml');
      root = parsed.documentElement;
      if (!root || parsed.getElementsByTagName('parsererror').length > 0 ||
        root.localName.toLowerCase() !== 'svg' || root.namespaceURI !== 'http://www.w3.org/2000/svg') {
        throw new Error('SVG 根节点命名空间无效');
      }
    }
    return root;
  }

  function applyStrictSvg(root, payload, observe = true) {
    const serialized = new XMLSerializer().serializeToString(root);
    if (serialized.length > MAX_VALIDATION_SVG_CHARS) throw new Error('SVG 规范化结果超过导出限制');
    const next = new DOMParser().parseFromString(payload.document || '', 'text/html');
    if (!next.body) throw new Error('SVG 宿主文档无效');
    next.body.replaceChildren(next.importNode(root, true));
    patch(frame.contentDocument.head, next.head);
    patch(frame.contentDocument.body, next.body);
    lastSafeSvg = serialized;
    lastSafeSvgHeight = Math.min(10000, Math.max(120, Math.ceil(frame.contentDocument.body.scrollHeight || 180)));
    if (observe) {
      if (observer) observer.disconnect();
      observer = new ResizeObserver(measure);
      observer.observe(frame.contentDocument.body);
      if (frame.contentDocument.fonts) frame.contentDocument.fonts.ready.then(measure);
    }
    return serialized;
  }

  function renderStrictSvg(payload) {
    const root = parseStrictSvg(payload.rawSVG);
    applyStrictSvg(root, payload);
    strictValidation = { ok: true, error: '' };
    measure();
    report(lastSafeSvgHeight, true, true, '', { ok: true, error: '', svg: lastSafeSvg });
  }

  function update(payload) {
    if (disposed) return;
    if (threeReady) cancelAnimationFrame(threeReady);
    threeReady = 0;
    latest = payload;
    revision = payload.revision;
    if (payload.renderer !== 'threejs') fallbackError = '';
    if ((payload.strictSvg === true) !== strictMode) {
      strictMode = payload.strictSvg === true;
      strictValidation = null;
      lastSafeSvg = null;
      lastSafeSvgHeight = 180;
      if (strictMode && frame !== null && frame.contentDocument && frame.contentDocument.body) {
        frame.contentDocument.body.replaceChildren();
      }
    }
    if (payload.renderer === 'threejs') {
      fallbackError = '';
      if (!hasThree) {
        three = false;
        fallbackError = '三维预览不可用，显示静态预览';
        if (frame === null || !frame.contentDocument || !frame.contentDocument.body) {
          report(180, false, false, fallbackError);
          return;
        }
        frame.style.display = 'block';
        if (scene !== null) scene.style.display = 'none';
        try {
          renderDocument(payload.document || '');
        } catch (error) {
          report(180, false, false, error instanceof Error ? error.message : String(error));
        }
        return;
      }
      if (observer) observer.disconnect();
      three = true;
      fallbackError = '';
      if (frame !== null) frame.style.display = 'none';
      scene.style.display = 'block';
      const result = threeApi.update(payload.scene || { objects: [] }, payload.dark, scene);
      if (threeApi.setActive) threeApi.setActive(active);
      if (result.ok) {
        if (typeof payload.view === 'string' && threeApi.restoreView) threeApi.restoreView(payload.view);
        // Cross a browser frame boundary before native code captures or reveals the canvas.
        threeReady = requestAnimationFrame(() => {
          threeReady = 0;
          if (!disposed && three) report(280, true, result.objectCount > 0, '');
        });
      } else {
        three = false;
        fallbackError = result.error || '三维预览不可用，显示静态预览';
        if (frame !== null && frame.contentDocument && frame.contentDocument.body) {
          frame.style.display = 'block';
          scene.style.display = 'none';
          try {
            renderDocument(payload.document || '');
            report(180, false, true, fallbackError);
          } catch (error) {
            report(180, false, false, error instanceof Error ? error.message : String(error));
          }
        } else {
          report(180, false, false, fallbackError);
        }
      }
      return;
    }
    if (three && hasThree && threeApi.dispose) threeApi.dispose();
    three = false;
    if (scene !== null) scene.style.display = 'none';
    if (frame === null) {
      report(180, false, false, '预览宿主缺少内容容器');
      return;
    }
    frame.style.display = 'block';
    if (!frame.contentDocument || !frame.contentDocument.body) return;
    if (payload.strictSvg) {
      try {
        renderStrictSvg(payload);
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        strictValidation = { ok: false, error: message };
        if (observer) observer.disconnect();
        if (measurement) cancelAnimationFrame(measurement);
        measurement = 0;
        if (lastSafeSvg !== null && !frame.contentDocument.body.querySelector('svg')) {
          try { applyStrictSvg(parseStrictSvg(lastSafeSvg), payload, false); } catch (_) {}
        }
        report(lastSafeSvgHeight, false, lastSafeSvg !== null, message, { ok: false, error: message });
      }
      return;
    }
    try {
      renderDocument(payload.document);
    } catch (error) {
      report(180, false, false, error instanceof Error ? error.message : String(error));
    }
  }

  frame?.addEventListener('load', () => { if (latest) update(latest); });
  if (hasThree && threeApi.setFrameListener) threeApi.setFrameListener(() => {
    if (!disposed && three && active && window.AmberVisualHost &&
      typeof window.AmberVisualHost.postMessage === 'function') {
      window.AmberVisualHost.postMessage(JSON.stringify({ kind: 'three-frame', revision }));
    }
  });
  window.AmberVisual = {
    update,
    setActive(value) {
      active = value === true;
      if (hasThree && threeApi.setActive) threeApi.setActive(active);
    },
    setInteracting(value) {
      if (hasThree && threeApi.setInteracting) threeApi.setInteracting(value === true && three);
    },
    dispose() {
      disposed = true;
      if (observer) observer.disconnect();
      if (measurement) cancelAnimationFrame(measurement);
      if (threeReady) cancelAnimationFrame(threeReady);
      threeReady = 0;
      if (hasThree && threeApi.setFrameListener) threeApi.setFrameListener(null);
      if (hasThree && threeApi.dispose) threeApi.dispose();
      latest = null;
      lastSafeSvg = null;
    },
  };
  window.addEventListener('pagehide', () => {
    if (observer) observer.disconnect();
    if (measurement) cancelAnimationFrame(measurement);
    if (threeReady) cancelAnimationFrame(threeReady);
    measurement = 0;
    threeReady = 0;
  });
  window.addEventListener('pageshow', () => {
    if (!disposed && latest) {
      lastReport = '';
      update(latest);
      window.AmberVisual.setActive(active);
    }
  });
})();

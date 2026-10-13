// Host-owned decoration. Generated HTML keeps its existing script/network policy.
(() => {
  let state = 'idle';
  let generation = 0;
  let run = null;
  let cleanup = () => {};

  function cancel() {
    generation++;
    cleanup();
    cleanup = () => {};
    if (run) run.cancel();
    run = null;
    state = 'idle';
  }

  function contextFor(iframe) {
    const frame = iframe ? document.getElementById('content') : null;
    if (iframe && (!frame || !frame.contentDocument)) return null;
    return { doc: frame ? frame.contentDocument : document, win: frame ? frame.contentWindow : window };
  }

  function findTarget(doc) {
    return doc.querySelector('[data-amber-reveal]') || doc.querySelector('main') ||
      doc.querySelector('#app') || doc.querySelector('#root') ||
      [...doc.body.children].find(node => !node.matches('script,style,.t-gen') &&
        node.getBoundingClientRect().height >= 60);
  }

  function installStyle(doc, dark) {
    let style = doc.getElementById('amber-ui-generation-style');
    if (!style) {
      style = doc.createElement('style');
      style.id = 'amber-ui-generation-style';
      doc.head.appendChild(style);
    }
    // Use the app theme, which can differ from the system media query.
    const theme = dark
      ? '--gen-line:#6f6f6f;--gen-strong:#a3a3a3;--gen-ink:rgba(245,245,245,.3);--gen-surface:#181818;'
      : '--gen-line:#9a9a9a;--gen-strong:#6c6c6c;--gen-ink:rgba(13,13,13,.32);--gen-surface:#fff;';
    style.textContent = amberGenerationCSS + '\n.t-gen{' + theme + 'overflow:hidden;contain:layout paint}';
  }

  function play(iframe, reduceMotion, dark = false) {
    if (state === 'pending' || state === 'started') return state;
    const context = contextFor(iframe);
    if (!context) return (state = 'skipped');
    const { doc, win } = context;
    if (reduceMotion || win.matchMedia('(prefers-reduced-motion: reduce)').matches) return (state = 'skipped');
    const target = findTarget(doc);
    if (!target) return (state = 'skipped');
    state = 'pending';
    const token = ++generation;
    const removers = [];
    const timers = new Set();
    let observer = null;
    let previous = null;
    const current = () => token === generation;
    const later = (callback, ms) => {
      const timer = setTimeout(() => { timers.delete(timer); callback(); }, ms);
      timers.add(timer);
      return timer;
    };
    const pause = ms => new Promise(resolve => later(resolve, ms));
    const restore = () => {
      if (!previous) return;
      Object.assign(target.style, previous);
      target.__tGenLayer?.remove();
    };
    const skip = () => {
      if (!current()) return;
      cancel();
      restore();
      state = 'skipped';
    };
    const listen = (object, name, callback) => {
      object.addEventListener(name, callback, { capture: true, passive: true });
      removers.push(() => object.removeEventListener(name, callback, true));
    };
    cleanup = () => {
      for (const remove of removers) remove();
      for (const timer of timers) clearTimeout(timer);
      timers.clear();
      observer?.disconnect();
    };
    for (const page of new Set([doc, document])) {
      for (const event of ['pointerdown', 'touchstart', 'keydown', 'scroll']) listen(page, event, skip);
      listen(page, 'visibilitychange', () => { if (page.hidden) skip(); });
    }
    for (const view of new Set([win, window])) {
      for (const event of ['scroll', 'resize', 'pagehide']) listen(view, event, skip);
      listen(view.matchMedia('(prefers-reduced-motion: reduce)'), 'change', skip);
    }

    async function prepare() {
      await Promise.race([doc.fonts.ready, pause(500)]);
      if (!current()) return;
      const images = [...target.querySelectorAll('img')].filter(image => !image.complete);
      await Promise.race([Promise.all(images.map(image => new Promise(resolve => {
        const done = () => {
          image.removeEventListener('load', done);
          image.removeEventListener('error', done);
          resolve();
        };
        image.addEventListener('load', done, { once: true });
        image.addEventListener('error', done, { once: true });
        removers.push(done);
      }))), pause(3000)]);
      if (!current()) return;
      if (images.some(image => !image.complete)) { skip(); return; }
      // Native height reports and iframe layout must settle before taking coordinates.
      await pause(120);
      if (!current()) return;
      let rect = target.getBoundingClientRect();
      for (let frame = 0; frame < 2; frame++) {
        await new Promise(resolve => window.requestAnimationFrame(resolve));
        if (!current()) return;
        const next = target.getBoundingClientRect();
        if (Math.abs(next.width - rect.width) > 1 || Math.abs(next.height - rect.height) > 1) { skip(); return; }
        rect = next;
      }
      // shortcut: reveal a viewport-sized result; long pages remain immediately readable.
      if (rect.width < 120 || rect.height < 60 || rect.width > win.innerWidth + 2 ||
        rect.height > win.innerHeight + 2 || rect.bottom <= 0 || rect.top >= win.innerHeight ||
        target.querySelectorAll('*').length > 400 || doc.hidden) { skip(); return; }
      installStyle(doc, dark);
      previous = { opacity: target.style.opacity, clipPath: target.style.clipPath,
        filter: target.style.filter, transition: target.style.transition };
      observer = new ResizeObserver(() => {
        const next = target.getBoundingClientRect();
        if (Math.abs(next.width - rect.width) > 1 || Math.abs(next.height - rect.height) > 1) skip();
      });
      observer.observe(target);
      run = AmberCreateGenerationRuntime(doc, win).generate(target, { mode: 'skeleton', maxParts: 40 });
      state = 'started';
      later(skip, 3200);
      await run.finished;
      if (current()) {
        cleanup();
        cleanup = () => {};
        run = null;
      }
    }
    prepare().catch(error => {
      if (!current()) return;
      skip();
      console.warn('Amber UI reveal skipped; keeping the result visible', error);
    });
    return state;
  }
  window.AmberUIGeneration = { play, status: () => state, cancel };
})();

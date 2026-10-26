const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const runtimePath = path.join(__dirname, '../main/resources/rawfile/visual/three-runtime.js');
const runtimeSource = fs.readFileSync(runtimePath, 'utf8');

function vector() {
  return {
    x: 0, y: 0, z: 0,
    set(x, y, z) { this.x = x; this.y = y; this.z = z; },
  };
}

function fixture() {
  class Node {
    constructor(tag) {
      this.tagName = tag;
      this.children = [];
      this.parentNode = null;
      this.style = {};
      this.listeners = Object.create(null);
      this.clientWidth = 320;
      this.clientHeight = 220;
      this.textContent = '';
    }
    appendChild(node) { node.parentNode = this; this.children.push(node); return node; }
    removeChild(node) {
      const index = this.children.indexOf(node);
      if (index >= 0) this.children.splice(index, 1);
      node.parentNode = null;
      return node;
    }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(name, listener) { this.listeners[name] = listener; }
    removeEventListener(name) { delete this.listeners[name]; }
    getBoundingClientRect() { return { width: this.clientWidth, height: this.clientHeight }; }
  }

  class Canvas extends Node {
    constructor() { super('canvas'); }
    getContext(type, options) {
      this.contextOptions = options;
      return type === 'webgl2' ? { flushCount: 0, flush() { this.flushCount++; } } : null;
    }
    toDataURL(type) { assert.equal(type, 'image/png'); return 'data:image/png;base64,thumbnail'; }
  }

  class Color {
    constructor(value) { this.value = value; }
    set(value) { this.value = value; }
  }

  class Geometry {
    constructor(...args) { this.args = args; this.disposed = false; }
    dispose() { this.disposed = true; }
  }

  class Material {
    constructor(options) { this.color = new Color(options.color); this.disposed = false; }
    dispose() { this.disposed = true; }
  }

  class Object3D {
    constructor() {
      this.children = [];
      this.position = vector();
      this.rotation = vector();
      this.scale = vector();
      this.userData = {};
    }
    add(node) { node.parent = this; this.children.push(node); }
    remove(node) {
      const index = this.children.indexOf(node);
      if (index >= 0) this.children.splice(index, 1);
      node.parent = null;
    }
  }

  class Mesh extends Object3D {
    constructor(geometry, material) { super(); this.geometry = geometry; this.material = material; this.name = ''; Mesh.instances.push(this); }
  }
  Mesh.instances = [];

  class Scene extends Object3D {}
  class PerspectiveCamera extends Object3D {
    constructor(fov, aspect) { super(); this.fov = fov; this.aspect = aspect; }
    updateProjectionMatrix() {}
  }
  class Light extends Object3D {
    constructor(color, intensity) { super(); this.color = color; this.intensity = intensity; }
  }

  class Renderer {
    constructor(options) {
      this.canvas = options.canvas;
      this.context = options.context;
      this.options = options;
      this.disposed = false;
      this.contextLost = false;
      this.renderCount = 0;
      Renderer.instances.push(this);
    }
    setPixelRatio() {}
    getContext() { return this.context; }
    setSize() {}
    setClearColor(color) { this.clearColor = color; }
    render() { this.renderCount += 1; }
    dispose() { this.disposed = true; }
    forceContextLoss() { this.contextLost = true; }
  }
  Renderer.instances = [];

  class Controls {
    constructor(camera, element) {
      this.camera = camera; this.element = element; this.target = vector(); this.listeners = {}; this.disposed = false;
      Controls.instances.push(this);
    }
    addEventListener(name, listener) { this.listeners[name] = listener; }
    update() {}
    dispose() { this.disposed = true; }
  }
  Controls.instances = [];

  const rootListeners = Object.create(null);
  const document = {
    hidden: false,
    listeners: Object.create(null),
    createElement(tag) { return tag === 'canvas' ? new Canvas() : new Node(tag); },
    addEventListener(name, listener) { this.listeners[name] = listener; },
  };
  const library = {
    Color, Scene, PerspectiveCamera, WebGLRenderer: Renderer, OrbitControls: Controls,
    AmbientLight: Light, DirectionalLight: Light, Mesh, MeshStandardMaterial: Material,
    BoxGeometry: Geometry, SphereGeometry: Geometry, CylinderGeometry: Geometry,
    ConeGeometry: Geometry, TorusGeometry: Geometry, PlaneGeometry: Geometry,
  };
  const sandbox = {
    document,
    devicePixelRatio: 1,
    AmberThreeThree: library,
    addEventListener(name, listener) { rootListeners[name] = listener; },
    removeEventListener(name) { delete rootListeners[name]; },
  };
  sandbox.window = sandbox;
  vm.runInNewContext(runtimeSource, sandbox, { filename: runtimePath });
  return { api: sandbox.AmberThree, library, document, rootListeners, container: new Node('div') };
}

function object(id, type, overrides = {}) {
  return {
    id, type, color: '#5f5ce6', position: [0, 0, 0], rotation: [0, 0, 0], scale: [1, 1, 1], size: [1, 1, 1], ...overrides,
  };
}

test('Three runtime incrementally retains meshes, removes stale objects, and disposes GPU resources', () => {
  const f = fixture();
  const first = f.api.update({ objects: [object('keep', 'box'), object('remove', 'sphere', { size: [0.4] })] }, false, f.container);
  assert.equal(first.ok, true);
  assert.equal(first.error, null);
  assert.equal(first.objectCount, 2);
  const keepMesh = f.library.Mesh.instances.find(mesh => mesh.name.endsWith('-keep'));
  const removedMesh = f.library.Mesh.instances.find(mesh => mesh.name.endsWith('-remove'));
  const renderer = f.library.WebGLRenderer.instances[0];
  assert.ok(keepMesh && removedMesh);

  const rendersWhileActive = renderer.renderCount;
  f.api.setActive(false);
  f.api.update({ objects: [object('keep', 'box')] }, false, f.container);
  assert.equal(renderer.renderCount, rendersWhileActive, 'inactive previews do not render on every stream update');
  f.api.setActive(true);
  assert.ok(renderer.renderCount > rendersWhileActive);

  const second = f.api.update({ objects: [object('keep', 'box', { color: '#ff8844', position: [2, 0, 0] }), object('new', 'torus', { size: [0.7, 0.1] })] }, true, f.container);
  assert.equal(second.ok, true);
  assert.equal(second.error, null);
  assert.equal(second.objectCount, 2);
  assert.strictEqual(f.library.Mesh.instances.find(mesh => mesh.name.endsWith('-keep')), keepMesh);
  assert.equal(removedMesh.geometry.disposed, true);
  assert.equal(removedMesh.material.disposed, true);
  assert.equal(keepMesh.material.color.value, '#ff8844');

  const disposed = f.api.dispose();
  assert.equal(disposed.ok, true);
  assert.equal(disposed.error, null);
  assert.equal(disposed.objectCount, 0);
  assert.equal(renderer.disposed, true);
  assert.equal(renderer.contextLost, true);
  assert.equal(keepMesh.geometry.disposed, true);
  assert.equal(keepMesh.material.disposed, true);
});

test('Three runtime caps object count and sanitizes non-finite values at the WebGL boundary', () => {
  const f = fixture();
  const objects = [];
  for (let index = 0; index < 540; index += 1) {
    objects.push(object(`object-${index}`, 'box', {
      position: [100, -100, 100], rotation: [100, -100, 100], scale: [10, 10, 10], size: [40, 40, 40],
    }));
  }
  const result = f.api.update({ objects }, false, f.container);
  assert.equal(result.ok, true);
  assert.equal(result.objectCount, 512);
  assert.equal(f.library.Mesh.instances.length, 512);
  assert.equal(f.library.Mesh.instances[0].position.x, 100);
  assert.equal(f.library.Mesh.instances[0].rotation.y, -100);
  assert.equal(f.library.Mesh.instances[0].scale.x, 10);
  assert.equal(f.library.Mesh.instances[0].geometry.args[0], 40);
  f.api.dispose();
});

test('Three runtime supports compact voxels and centers camera target on 3D bounding box', () => {
  const f = fixture();
  const result = f.api.update({
    objects: [{ id: 'base', color: '#4caf50', position: [0, 0, 0], size: [10, 2, 10] }],
    voxels: [[0, 10, 0, '#ff8800', 2, 2, 2]],
  }, false, f.container);
  assert.equal(result.ok, true);
  assert.equal(result.objectCount, 2);
  const scene = f.library.Mesh.instances[0].parent;
  assert.ok(scene);
  f.api.dispose();
});

test('Three runtime rejects malformed direct payloads and preserves the two-value cylinder contract', () => {
  const f = fixture();
  const invalid = f.api.update({ objects: [object('bad', 'box', { position: [Infinity, 0, 0] })] }, false, f.container);
  assert.equal(invalid.ok, false);
  assert.equal(invalid.error, 'Invalid 3D spec');
  const longId = f.api.update({ objects: [object('x'.repeat(49), 'box')] }, false, f.container);
  assert.equal(longId.ok, false);

  const valid = f.api.update({ objects: [object('drum', 'cylinder', { size: [2, 3] })] }, false, f.container);
  assert.equal(valid.ok, true);
  assert.deepEqual(f.library.Mesh.instances[0].geometry.args, [2, 2, 3, 24, 1]);
  f.api.dispose();
});

test('Static frames survive the interval between scene updates', () => {
  const f = fixture();
  assert.equal(f.api.update({ objects: [object('box', 'box')] }, false, f.container).ok, true);
  const renderer = f.library.WebGLRenderer.instances[0];
  assert.equal(renderer.canvas.contextOptions.preserveDrawingBuffer, true);
  assert.equal(renderer.options.preserveDrawingBuffer, true);
  assert.equal(renderer.context.flushCount, renderer.renderCount);
});

test('a native cover snapshot comes from the rendered canvas and retains no extra WebGL context', () => {
  const f = fixture();
  assert.equal(f.api.snapshot(), '');
  f.api.update({ objects: [object('box', 'box')] }, false, f.container);
  const renderer = f.library.WebGLRenderer.instances[0];
  const before = renderer.renderCount;
  assert.equal(f.api.snapshot(), 'data:image/png;base64,thumbnail');
  assert.equal(renderer.renderCount, before + 1);
  assert.equal(f.library.WebGLRenderer.instances.length, 1);
  f.api.setActive(false);
  assert.equal(f.api.snapshot(), '');
  f.api.dispose();
  assert.equal(f.api.snapshot(), '');
  assert.equal(renderer.disposed, true);
});

test('cover frames retain the matching camera and restore it without subsequent auto-fit', () => {
  const f = fixture();
  assert.equal(f.api.captureFrame().snapshot, '');
  assert.equal(f.api.restoreView('{"position":[3,4,5],"target":[1,2,3]}'), false);
  f.api.update({ objects: [object('box', 'box')] }, false, f.container);
  const controls = f.library.OrbitControls.instances[0];
  const view = '{"position":[3,4,5],"target":[1,2,3]}';
  assert.equal(f.api.restoreView(view), true);
  const frame = f.api.captureFrame();
  assert.equal(frame.snapshot, 'data:image/png;base64,thumbnail');
  assert.equal(frame.view, view);
  for (const invalid of ['null', '{}', '{', '{"position":[1,2,3],"target":[1,2,3]}',
    '{"position":[null,4,5],"target":[1,2,3]}']) {
    assert.equal(f.api.restoreView(invalid), false);
    assert.equal(f.api.captureFrame().view, view);
  }
  f.api.update({ objects: [object('box', 'box', { position: [100, 0, 0] })] }, false, f.container);
  assert.equal(f.api.captureFrame().view, view, 'stream updates retain the restored view');

  let frames = 0;
  f.api.setFrameListener(() => { frames++; });
  controls.listeners.end();
  assert.equal(frames, 0, 'passive previews do not notify native');
  f.api.setInteracting(true);
  controls.listeners.end();
  assert.equal(frames, 1);
  f.api.setActive(false);
  controls.listeners.end();
  assert.equal(frames, 1);
  assert.equal(f.api.captureFrame().view, '');
  f.api.dispose();
});

test('the Three host restores the view before a single deferred ready report', () => {
  const hostPath = path.join(__dirname, '../main/resources/rawfile/visual/host.js');
  const scene = { style: {} };
  const pending = new Map();
  const messages = [];
  const calls = [];
  let nextFrame = 0;
  let frameListener;
  const sandbox = {
    document: { getElementById(id) { return id === 'scene' ? scene : null; } },
    requestAnimationFrame(callback) { const id = ++nextFrame; pending.set(id, callback); return id; },
    cancelAnimationFrame(id) { pending.delete(id); },
    AmberThree: {
      update() { calls.push('update'); return { ok: true, objectCount: 1 }; },
      setActive() {},
      restoreView(view) { calls.push(view); },
      setFrameListener(listener) { frameListener = listener; },
      dispose() {},
    },
    AmberVisualHost: { postMessage(message) { messages.push(JSON.parse(message)); } },
    addEventListener() {},
  };
  sandbox.window = sandbox;
  vm.runInNewContext(fs.readFileSync(hostPath, 'utf8'), sandbox, { filename: hostPath });
  sandbox.AmberVisual.update({ renderer: 'threejs', revision: 1, scene: {}, view: 'saved-view' });
  assert.deepEqual(calls, ['update', 'saved-view']);
  assert.equal(messages.length, 0, 'native cannot capture until the first frame boundary');
  sandbox.AmberVisual.update({ renderer: 'threejs', revision: 2, scene: {} });
  assert.equal(pending.size, 1, 'new updates replace the pending ready report');
  for (const callback of pending.values()) callback();
  pending.clear();
  assert.equal(messages.length, 1);
  assert.equal(messages[0].revision, 2);
  assert.equal(messages[0].ok, true);
  frameListener();
  assert.deepEqual(messages[1], { kind: 'three-frame', revision: 2 });
  sandbox.AmberVisual.setActive(false);
  frameListener();
  assert.equal(messages.length, 2);
  sandbox.AmberVisual.update({ renderer: 'threejs', revision: 3, scene: {} });
  sandbox.AmberVisual.dispose();
  assert.equal(pending.size, 0);
  assert.equal(frameListener, null);
});

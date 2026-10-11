(function (root) {
  'use strict';

  // The domain layer owns schema normalization; this boundary still caps work before it reaches WebGL.
  var MAX_OBJECTS = 48;
  var MAX_ID_CHARS = 48;
  var MAX_COORDINATE = 100;
  var MAX_SIZE = 40;
  var MAX_SCALE = 10;
  var TYPES = { box: true, sphere: true, cylinder: true, cone: true, torus: true, plane: true };
  var SIZE_LENGTH = {
    box: 3, sphere: 1, cylinder: 2, cone: 2, torus: 2, plane: 2
  };
  var CLEAR_DARK = '#10131a';
  var CLEAR_LIGHT = '#f4f1eb';

  var state = {
    container: null,
    canvas: null,
    renderer: null,
    scene: null,
    camera: null,
    controls: null,
    resizeObserver: null,
    resizeHandler: null,
    canvasHandlers: null,
    meshes: Object.create(null),
    specs: Object.create(null),
    lastSpec: null,
    dark: false,
    active: true,
    interacting: false,
    cameraReady: false,
    userInteracted: false,
    hidden: false,
    pageHidden: false,
    disposed: true,
    contextLost: false,
    listenersInstalled: false,
    errorNode: null
  };

  function result(ok, error, objectCount) {
    return { ok: ok, error: error || null, objectCount: objectCount || 0 };
  }

  function finite(value, fallback) {
    return typeof value === 'number' && isFinite(value) ? value : fallback;
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function boundedArray(value, length, max, positive) {
    if (!Array.isArray(value) || value.length !== length) return null;
    var array = [];
    for (var index = 0; index < length; index += 1) {
      var candidate = value[index];
      if (typeof candidate !== 'number' || !isFinite(candidate) ||
        (positive ? candidate <= 0 || candidate > max : Math.abs(candidate) > max)) return null;
      array.push(candidate);
    }
    return array;
  }

  function sanitizeObject(value) {
    if (!value || typeof value !== 'object' || typeof value.id !== 'string') return null;
    var id = value.id.trim();
    var type = value.type;
    if (!id || id.length > MAX_ID_CHARS || !TYPES[type] ||
      typeof value.color !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(value.color)) return null;
    var position = boundedArray(value.position, 3, MAX_COORDINATE, false);
    var rotation = boundedArray(value.rotation, 3, MAX_COORDINATE, false);
    var scale = boundedArray(value.scale, 3, MAX_SCALE, true);
    var size = boundedArray(value.size, SIZE_LENGTH[type], MAX_SIZE, true);
    if (position === null || rotation === null || scale === null || size === null) return null;
    return {
      id: id,
      type: type,
      color: value.color,
      position: position,
      rotation: rotation,
      scale: scale,
      size: size
    };
  }

  function sanitizeSpec(spec) {
    if (!spec || typeof spec !== 'object' || !Array.isArray(spec.objects)) return null;
    var objects = [];
    var seen = Object.create(null);
    for (var index = 0; index < spec.objects.length && objects.length < MAX_OBJECTS; index += 1) {
      var object = sanitizeObject(spec.objects[index]);
      if (!object || seen[object.id]) return null;
      seen[object.id] = true;
      objects.push(object);
    }
    return { objects: objects };
  }

  function cloneSpec(spec) {
    var objects = [];
    for (var index = 0; index < spec.objects.length; index += 1) {
      var object = spec.objects[index];
      objects.push({
        id: object.id, type: object.type, color: object.color,
        position: object.position.slice(), rotation: object.rotation.slice(),
        scale: object.scale.slice(), size: object.size.slice()
      });
    }
    return { objects: objects };
  }

  function showError(container, message) {
    if (!container || !container.appendChild || !root.document || !root.document.createElement) return;
    if (!state.errorNode || state.errorNode.parentNode !== container) {
      if (state.errorNode && state.errorNode.parentNode) state.errorNode.parentNode.removeChild(state.errorNode);
      state.errorNode = root.document.createElement('div');
      state.errorNode.className = 'amber-three-error';
      state.errorNode.setAttribute('role', 'status');
      state.errorNode.style.position = 'absolute';
      state.errorNode.style.left = '12px';
      state.errorNode.style.right = '12px';
      state.errorNode.style.top = '50%';
      state.errorNode.style.transform = 'translateY(-50%)';
      state.errorNode.style.padding = '12px 14px';
      state.errorNode.style.borderRadius = '12px';
      state.errorNode.style.background = 'rgba(120, 38, 45, .92)';
      state.errorNode.style.color = '#fff';
      state.errorNode.style.font = '14px/1.4 sans-serif';
      state.errorNode.style.textAlign = 'center';
      container.appendChild(state.errorNode);
    }
    state.errorNode.textContent = message;
  }

  function clearError() {
    if (state.errorNode && state.errorNode.parentNode) state.errorNode.parentNode.removeChild(state.errorNode);
    state.errorNode = null;
  }

  function setTransform(mesh, object) {
    mesh.position.set(object.position[0], object.position[1], object.position[2]);
    mesh.rotation.set(object.rotation[0], object.rotation[1], object.rotation[2]);
    mesh.scale.set(object.scale[0], object.scale[1], object.scale[2]);
  }

  function createGeometry(object) {
    var size = object.size;
    if (object.type === 'box') return new root.AmberThreeThree.BoxGeometry(size[0], size[1], size[2]);
    if (object.type === 'sphere') return new root.AmberThreeThree.SphereGeometry(size[0], 24, 16);
    if (object.type === 'cylinder') return new root.AmberThreeThree.CylinderGeometry(size[0], size[0], size[1], 24, 1);
    if (object.type === 'cone') return new root.AmberThreeThree.ConeGeometry(size[0], size[1], 24, 1);
    if (object.type === 'torus') return new root.AmberThreeThree.TorusGeometry(size[0], size[1], 8, 24);
    return new root.AmberThreeThree.PlaneGeometry(size[0], size[1]);
  }

  function geometryKey(object) {
    return object.type + ':' + object.size.join(',');
  }

  function setMaterialColor(material, color) {
    if (material && material.color && typeof material.color.set === 'function') material.color.set(color);
  }

  function applyTheme() {
    if (!state.scene || !state.renderer) return;
    var color = state.dark ? CLEAR_DARK : CLEAR_LIGHT;
    state.scene.background = new root.AmberThreeThree.Color(color);
    if (typeof state.renderer.setClearColor === 'function') state.renderer.setClearColor(color, 1);
  }

  function render() {
    if (!state.renderer || !state.scene || !state.camera || !state.active || state.hidden || state.pageHidden || state.contextLost) return;
    try {
      state.renderer.render(state.scene, state.camera);
    } catch (error) {
      state.contextLost = true;
      releaseGpu(true, true);
      showError(state.container, '3D 预览暂时不可用：WebGL 上下文已丢失');
    }
  }

  function resize() {
    if (!state.renderer || !state.camera || !state.container) return;
    var width = Math.max(1, state.container.clientWidth || 320);
    var height = Math.max(1, state.container.clientHeight || 220);
    if (state.container.getBoundingClientRect) {
      var rect = state.container.getBoundingClientRect();
      width = Math.max(1, Math.round(rect.width || width));
      height = Math.max(1, Math.round(rect.height || height));
    }
    if (typeof state.renderer.setPixelRatio === 'function') {
      state.renderer.setPixelRatio(Math.min(2, Math.max(1, finite(root.devicePixelRatio, 1))));
    }
    state.renderer.setSize(width, height, false);
    state.camera.aspect = width / height;
    state.camera.updateProjectionMatrix();
    render();
  }

  function fitCamera(objects) {
    if (!state.camera || !state.controls || state.userInteracted) return;
    var extent = 1;
    for (var index = 0; index < objects.length; index += 1) {
      var object = objects[index];
      var largestSize = 1;
      for (var sizeIndex = 0; sizeIndex < object.size.length; sizeIndex += 1) {
        largestSize = Math.max(largestSize, object.size[sizeIndex]);
      }
      for (var scaleIndex = 0; scaleIndex < object.scale.length; scaleIndex += 1) {
        largestSize = Math.max(largestSize, object.size[scaleIndex % object.size.length] * object.scale[scaleIndex]);
      }
      extent = Math.max(extent, Math.abs(object.position[0]), Math.abs(object.position[1]), Math.abs(object.position[2]), largestSize);
    }
    var distance = clamp(extent * 3.1, 3, 180);
    state.camera.position.set(distance * 0.9, distance * 0.65, distance * 1.15);
    state.controls.target.set(0, 0, 0);
    state.controls.update();
    state.cameraReady = true;
  }

  function removeMesh(id) {
    var mesh = state.meshes[id];
    if (!mesh) return;
    if (state.scene) state.scene.remove(mesh);
    if (mesh.geometry && typeof mesh.geometry.dispose === 'function') mesh.geometry.dispose();
    if (mesh.material && typeof mesh.material.dispose === 'function') mesh.material.dispose();
    delete state.meshes[id];
    delete state.specs[id];
  }

  function reconcile(objects) {
    var next = Object.create(null);
    for (var index = 0; index < objects.length; index += 1) {
      var object = objects[index];
      next[object.id] = true;
      var mesh = state.meshes[object.id];
      var key = geometryKey(object);
      if (!mesh) {
        var geometry = createGeometry(object);
        var material = new root.AmberThreeThree.MeshStandardMaterial({
          color: object.color, roughness: 0.62, metalness: 0.05
        });
        mesh = new root.AmberThreeThree.Mesh(geometry, material);
        mesh.name = 'amber-three-' + object.id;
        mesh.userData = mesh.userData || {};
        mesh.userData.geometryKey = key;
        state.meshes[object.id] = mesh;
        state.scene.add(mesh);
      } else if (mesh.userData.geometryKey !== key) {
        var oldGeometry = mesh.geometry;
        mesh.geometry = createGeometry(object);
        mesh.userData.geometryKey = key;
        if (oldGeometry && typeof oldGeometry.dispose === 'function') oldGeometry.dispose();
      }
      setMaterialColor(mesh.material, object.color);
      setTransform(mesh, object);
      state.specs[object.id] = object;
    }
    var existingIds = Object.keys(state.meshes);
    for (var existingIndex = 0; existingIndex < existingIds.length; existingIndex += 1) {
      if (!next[existingIds[existingIndex]]) removeMesh(existingIds[existingIndex]);
    }
    fitCamera(objects);
  }

  function bindCanvas(canvas) {
    var lost = function (event) {
      if (event && typeof event.preventDefault === 'function') event.preventDefault();
      state.contextLost = true;
      releaseGpu(true, true);
      showError(state.container, '3D 预览暂时不可用：WebGL 上下文已丢失');
    };
    var restored = function () {
      state.contextLost = false;
      showError(state.container, '3D 预览正在恢复，请稍候');
    };
    state.canvasHandlers = { lost: lost, restored: restored };
    if (canvas.addEventListener) {
      canvas.addEventListener('webglcontextlost', lost, false);
      canvas.addEventListener('webglcontextrestored', restored, false);
    }
  }

  function installControls() {
    if (!state.controls || !state.controls.addEventListener) return;
    state.controls.enableDamping = false;
    state.controls.enablePan = false;
    state.controls.minDistance = 0.4;
    state.controls.maxDistance = 240;
    state.controls.addEventListener('start', function () {
      state.userInteracted = true;
    });
    state.controls.addEventListener('change', render);
  }

  function createRenderer(container) {
    var library = root.AmberThreeThree;
    if (!library || typeof library.WebGLRenderer !== 'function') throw new Error('three.js bundle unavailable');
    if (!root.document || typeof root.document.createElement !== 'function') throw new Error('document unavailable');
    var canvas = root.document.createElement('canvas');
    var context = null;
    if (typeof canvas.getContext === 'function') {
      // The host draws on changes; keep that frame until the next render.
      context = canvas.getContext('webgl2', { alpha: true, antialias: true, preserveDrawingBuffer: true });
    }
    if (!context) throw new Error('webgl2 unavailable');
    canvas.setAttribute('data-amber-three', 'true');
    canvas.style.display = 'block';
    canvas.style.width = '100%';
    canvas.style.height = '100%';
    if (container.style && !container.style.position) container.style.position = 'relative';
    var renderer = new library.WebGLRenderer({
      canvas: canvas, context: context, antialias: true, alpha: true, preserveDrawingBuffer: true, powerPreference: 'low-power'
    });
    renderer.setPixelRatio(Math.min(2, Math.max(1, finite(root.devicePixelRatio, 1))));
    var scene = new library.Scene();
    var camera = new library.PerspectiveCamera(38, 1, 0.01, 500);
    var ambient = new library.AmbientLight(0xffffff, 1.8);
    var keyLight = new library.DirectionalLight(0xffffff, 2.6);
    keyLight.position.set(4, 6, 8);
    var fillLight = new library.DirectionalLight(0x9aaeff, 0.8);
    fillLight.position.set(-5, 2, -4);
    scene.add(ambient);
    scene.add(keyLight);
    scene.add(fillLight);
    var controls = new library.OrbitControls(camera, canvas);
    controls.enabled = state.interacting;
    canvas.style.touchAction = state.interacting ? 'none' : 'pan-y';
    state.container = container;
    state.canvas = canvas;
    state.renderer = renderer;
    state.scene = scene;
    state.camera = camera;
    state.controls = controls;
    state.meshes = Object.create(null);
    state.specs = Object.create(null);
    state.cameraReady = false;
    state.userInteracted = false;
    state.disposed = false;
    state.contextLost = false;
    container.appendChild(canvas);
    bindCanvas(canvas);
    installControls();
    applyTheme();
    if (typeof root.ResizeObserver === 'function') {
      state.resizeObserver = new root.ResizeObserver(resize);
      state.resizeObserver.observe(container);
    } else if (root.addEventListener) {
      state.resizeHandler = resize;
      root.addEventListener('resize', state.resizeHandler);
    }
    resize();
  }

  function releaseGpu(retainSpec, keepError) {
    if (state.resizeObserver && typeof state.resizeObserver.disconnect === 'function') state.resizeObserver.disconnect();
    state.resizeObserver = null;
    if (state.resizeHandler && root.removeEventListener) root.removeEventListener('resize', state.resizeHandler);
    state.resizeHandler = null;
    if (state.canvas && state.canvasHandlers && state.canvas.removeEventListener) {
      state.canvas.removeEventListener('webglcontextlost', state.canvasHandlers.lost, false);
      state.canvas.removeEventListener('webglcontextrestored', state.canvasHandlers.restored, false);
    }
    if (state.controls && typeof state.controls.dispose === 'function') state.controls.dispose();
    var ids = Object.keys(state.meshes);
    for (var index = 0; index < ids.length; index += 1) removeMesh(ids[index]);
    var renderer = state.renderer;
    var canvas = state.canvas;
    state.canvas = null;
    state.renderer = null;
    state.scene = null;
    state.camera = null;
    state.controls = null;
    state.canvasHandlers = null;
    state.cameraReady = false;
    state.userInteracted = false;
    state.disposed = true;
    state.contextLost = false;
    if (renderer) {
      if (typeof renderer.dispose === 'function') renderer.dispose();
      if (typeof renderer.forceContextLoss === 'function') renderer.forceContextLoss();
    }
    if (canvas && canvas.parentNode) canvas.parentNode.removeChild(canvas);
    if (!retainSpec) {
      state.container = null;
      state.lastSpec = null;
      state.specs = Object.create(null);
    }
    state.meshes = Object.create(null);
    if (!keepError) clearError();
  }

  function ensureRenderer(container) {
    if (state.renderer && state.container === container && !state.contextLost) return true;
    if (state.renderer || (state.container && state.container !== container)) releaseGpu(false, false);
    try {
      createRenderer(container);
      return true;
    } catch (error) {
      releaseGpu(false, true);
      showError(container, error && error.message === 'webgl2 unavailable'
        ? '3D 预览不可用：此设备不支持 WebGL 2'
        : '3D 预览不可用：渲染器初始化失败');
      return false;
    }
  }

  function update(spec, dark, container) {
    var normalized = sanitizeSpec(spec);
    if (!normalized) {
      showError(container, '3D 预览数据无效');
      return result(false, 'Invalid 3D spec', 0);
    }
    if (!container || typeof container.appendChild !== 'function') {
      return result(false, '3D preview container is unavailable', 0);
    }
    state.dark = !!dark;
    state.lastSpec = cloneSpec(normalized);
    state.pageHidden = false;
    if (!ensureRenderer(container)) return result(false, 'WebGL renderer unavailable', 0);
    applyTheme();
    clearError();
    try {
      reconcile(normalized.objects);
      resize();
      render();
      return result(true, null, normalized.objects.length);
    } catch (error) {
      releaseGpu(true, true);
      showError(container, '3D 预览渲染失败');
      return result(false, '3D preview render failed', 0);
    }
  }

  function dispose() {
    setInteracting(false);
    releaseGpu(false, false);
    state.pageHidden = false;
    return result(true, null, 0);
  }

  function setActive(value) {
    state.active = value === true;
    if (!state.active) setInteracting(false);
    if (state.active) render();
  }

  function setInteracting(value) {
    state.interacting = value === true;
    if (state.controls) state.controls.enabled = state.interacting;
    if (state.canvas) state.canvas.style.touchAction = state.interacting ? 'none' : 'pan-y';
  }

  function onVisibilityChange() {
    state.hidden = !!(root.document && root.document.hidden);
    if (!state.hidden) render();
  }

  function onPageHide() {
    setInteracting(false);
    state.pageHidden = true;
    releaseGpu(true, false);
  }

  function onPageShow() {
    state.pageHidden = false;
    if (state.lastSpec && state.container) update(state.lastSpec, state.dark, state.container);
  }

  function installGlobalListeners() {
    if (state.listenersInstalled) return;
    state.listenersInstalled = true;
    if (root.document && root.document.addEventListener) root.document.addEventListener('visibilitychange', onVisibilityChange);
    if (root.addEventListener) {
      root.addEventListener('pagehide', onPageHide);
      root.addEventListener('pageshow', onPageShow);
    }
  }

  installGlobalListeners();
  root.AmberThree = { update: update, dispose: dispose, setActive: setActive, setInteracting: setInteracting };
}(typeof globalThis === 'object' ? globalThis : window));

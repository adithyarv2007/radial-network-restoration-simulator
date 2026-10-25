/*
 * Radial Restoration Simulator - 3D network view.
 *
 * Requires web/vendor/three.min.js (three.js r128, served locally). If the file is missing,
 * the panel shows a note and the 2D dashboard keeps working.
 *
 * This file never decides topology or electrical results. It reads /api/state, which the Java
 * simulator produces, and sends operations through the existing #operation-form.
 */
(function () {
  'use strict';

  const container = document.getElementById('scene3d');
  const infoBar = document.getElementById('scene3d-info');
  if (!container) return;

  if (typeof THREE === 'undefined') {
    container.innerHTML = '<div class="scene3d-note">3D view needs web/vendor/three.min.js. ' +
      'See DASHBOARD.md for the one-time download. The 2D diagram still works.</div>';
    return;
  }

  let renderer;
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true });
  } catch (err) {
    container.innerHTML = '<div class="scene3d-note">WebGL is unavailable in this browser. ' +
      'The 2D diagram still works.</div>';
    return;
  }

  const COLORS = {
    background: 0x0b1220,
    sourceBus: 0xfbbf24,
    liveBus: 0x34d399,
    deadBus: 0xf87171,
    closedLine: 0x38bdf8,
    openLine: 0x64748b,
    faultLine: 0xf43f5e,
    orphanLine: 0xf59e0b
  };
  const UP = new THREE.Vector3(0, 1, 0);
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  const canvas = renderer.domElement;
  canvas.style.touchAction = 'none';
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setClearColor(COLORS.background, 1);
  container.appendChild(canvas);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(45, 1, 0.1, 500);
  camera.position.set(0, 16, 30);
  camera.lookAt(0, 0, 0);

  scene.add(new THREE.AmbientLight(0xffffff, 0.55));
  const keyLight = new THREE.DirectionalLight(0xffffff, 0.8);
  keyLight.position.set(8, 18, 12);
  scene.add(keyLight);

  const grid = new THREE.GridHelper(60, 60, 0x1e293b, 0x111827);
  grid.position.y = -5;
  scene.add(grid);

  const root = new THREE.Group();
  root.rotation.x = -0.35;
  scene.add(root);

  // Shared geometry: every line and bus is a scaled copy of a unit shape.
  const unitCylinder = new THREE.CylinderGeometry(1, 1, 1, 12);
  const unitSphere = new THREE.SphereGeometry(1, 24, 18);
  const hitMaterial = new THREE.MeshBasicMaterial({ transparent: true, opacity: 0, depthWrite: false });

  const materialCache = new Map();
  function material(color, opts) {
    const o = Object.assign({ opacity: 1, emissive: 0x000000, emissiveIntensity: 0 }, opts || {});
    const key = [color, o.opacity, o.emissive, o.emissiveIntensity].join('|');
    if (!materialCache.has(key)) {
      materialCache.set(key, new THREE.MeshStandardMaterial({
        color: color,
        roughness: 0.45,
        metalness: 0.15,
        emissive: o.emissive,
        emissiveIntensity: o.emissiveIntensity,
        transparent: o.opacity < 1,
        opacity: o.opacity
      }));
    }
    return materialCache.get(key);
  }

  let faultMaterials = [];
  let lastState = null;
  let selectedLineId = null;
  let layoutKey = null;
  let layoutPos = new Map();
  let lastInteraction = 0;

  function makeLabel(text, cssColor, scale) {
    const tex = document.createElement('canvas');
    tex.width = 256;
    tex.height = 128;
    const ctx = tex.getContext('2d');
    ctx.font = 'bold 72px system-ui, Segoe UI, sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = cssColor;
    ctx.fillText(text, 128, 64);
    const map = new THREE.CanvasTexture(tex);
    const sprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: map, transparent: true, depthWrite: false }));
    sprite.scale.set(scale * 2, scale, 1);
    return sprite;
  }

  function clearRoot() {
    while (root.children.length) {
      const child = root.children[0];
      root.remove(child);
      if (child.isSprite) {
        if (child.material.map) child.material.map.dispose();
        child.material.dispose();
      }
    }
    faultMaterials = [];
  }

  // Layout: hop distance from the substations over all lines. Stable across operations,
  // so the picture does not jump when a line is opened or closed.
  function computeLayout(state) {
    const key = [
      state.scenarioId,
      state.buses.map(b => b.id).join(','),
      state.lines.map(l => l.id + ':' + l.busAId + '-' + l.busBId).join(',')
    ].join('|');
    if (key === layoutKey) return layoutPos;
    layoutKey = key;

    const adj = new Map();
    state.buses.forEach(b => adj.set(b.id, []));
    state.lines.forEach(l => {
      if (adj.has(l.busAId) && adj.has(l.busBId)) {
        adj.get(l.busAId).push(l.busBId);
        adj.get(l.busBId).push(l.busAId);
      }
    });

    const depth = new Map();
    const queue = [];
    state.buses.forEach(b => {
      if (b.hasSubstation) { depth.set(b.id, 0); queue.push(b.id); }
    });
    for (let qi = 0; qi < queue.length; qi++) {
      const u = queue[qi];
      adj.get(u).forEach(v => {
        if (!depth.has(v)) { depth.set(v, depth.get(u) + 1); queue.push(v); }
      });
    }
    let maxDepth = 0;
    depth.forEach(d => { if (d > maxDepth) maxDepth = d; });
    state.buses.forEach(b => { if (!depth.has(b.id)) depth.set(b.id, maxDepth + 1); });

    const rings = new Map();
    state.buses.forEach(b => {
      const d = depth.get(b.id);
      if (!rings.has(d)) rings.set(d, []);
      rings.get(d).push(b.id);
    });

    layoutPos = new Map();
    rings.forEach((ids, d) => {
      ids.sort((a, b) => a - b);
      const n = ids.length;
      ids.forEach((id, i) => {
        const angle = (2 * Math.PI * i) / n + d * 0.37;
        const radius = d === 0 ? (n === 1 ? 0 : 2.4) : 3.4 + d * 3.1;
        const y = 0.5 * Math.sin(id * 1.7);
        layoutPos.set(id, new THREE.Vector3(radius * Math.cos(angle), y, radius * Math.sin(angle)));
      });
    });
    return layoutPos;
  }

  function render3D(state) {
    if (!state || !state.buses || !state.buses.length) return;
    const pos = computeLayout(state);
    clearRoot();

    // Energised status comes from the simulator's last component snapshot.
    const liveByBus = new Map();
    ((state.snapshot && state.snapshot.components) || []).forEach(c => {
      (c.busIds || []).forEach(id => liveByBus.set(id, !!c.hasSource));
    });
    const isLive = id => (liveByBus.has(id) ? liveByBus.get(id) : true);

    state.lines.forEach(line => {
      const pa = pos.get(line.busAId);
      const pb = pos.get(line.busBId);
      if (!pa || !pb) return;

      const dir = new THREE.Vector3().subVectors(pb, pa);
      const len = dir.length();
      const mid = new THREE.Vector3().addVectors(pa, pb).multiplyScalar(0.5);
      const unitDir = dir.clone().normalize();

      let color, opts, labelColor, thickness;
      if (line.faulted) {
        color = COLORS.faultLine;
        opts = { emissive: COLORS.faultLine, emissiveIntensity: 0.6 };
        labelColor = '#fda4af';
        thickness = 0.1;
      } else if (!line.closed) {
        color = COLORS.openLine;
        opts = { opacity: 0.4 };
        labelColor = '#94a3b8';
        thickness = 0.06;
      } else if (!isLive(line.busAId)) {
        color = COLORS.orphanLine;
        opts = {};
        labelColor = '#fcd34d';
        thickness = 0.1;
      } else {
        color = COLORS.closedLine;
        opts = {};
        labelColor = '#7dd3fc';
        thickness = 0.1;
      }

      const mesh = new THREE.Mesh(unitCylinder, material(color, opts));
      mesh.scale.set(thickness, len, thickness);
      mesh.position.copy(mid);
      mesh.quaternion.setFromUnitVectors(UP, unitDir);
      mesh.userData = { type: 'line', id: line.id };
      root.add(mesh);
      if (line.faulted) faultMaterials.push(mesh.material);

      // Wider invisible proxy so thin lines are easy to click.
      const hit = new THREE.Mesh(unitCylinder, hitMaterial);
      hit.scale.set(0.35, len, 0.35);
      hit.position.copy(mid);
      hit.quaternion.copy(mesh.quaternion);
      hit.userData = { type: 'line', id: line.id };
      root.add(hit);

      const label = makeLabel('L' + line.id, labelColor, 0.9);
      label.position.copy(mid).add(new THREE.Vector3(0, 0.55, 0));
      root.add(label);
    });

    state.buses.forEach(bus => {
      const p = pos.get(bus.id);
      if (!p) return;
      let color;
      if (bus.hasSubstation) color = COLORS.sourceBus;
      else color = isLive(bus.id) ? COLORS.liveBus : COLORS.deadBus;
      const r = bus.hasSubstation ? 0.6 : 0.36 + 0.22 * Math.min(1, bus.customers / 100);

      const mesh = new THREE.Mesh(unitSphere, material(color, {}));
      mesh.scale.setScalar(r);
      mesh.position.copy(p);
      mesh.userData = { type: 'bus', id: bus.id };
      root.add(mesh);

      const label = makeLabel('B' + bus.id, '#e2e8f0', 0.9);
      label.position.copy(p).add(new THREE.Vector3(0, r + 0.55, 0));
      root.add(label);
    });
  }

  // ---- Interaction -------------------------------------------------------

  const raycaster = new THREE.Raycaster();
  const pointer = new THREE.Vector2();
  let press = null;
  let lastX = 0;
  let lastY = 0;

  canvas.addEventListener('pointerdown', e => {
    press = { x: e.clientX, y: e.clientY, moved: false };
    lastX = e.clientX;
    lastY = e.clientY;
    lastInteraction = performance.now();
    try { canvas.setPointerCapture(e.pointerId); } catch (err) { /* not critical */ }
  });

  canvas.addEventListener('pointermove', e => {
    if (!press) return;
    const dx = e.clientX - lastX;
    const dy = e.clientY - lastY;
    if (Math.hypot(e.clientX - press.x, e.clientY - press.y) > 5) press.moved = true;
    if (press.moved) {
      root.rotation.y += dx * 0.008;
      root.rotation.x = clamp(root.rotation.x + dy * 0.005, -1.1, 0.25);
    }
    lastX = e.clientX;
    lastY = e.clientY;
    lastInteraction = performance.now();
  });

  canvas.addEventListener('pointerup', e => {
    if (press && !press.moved) pick(e);
    press = null;
    lastInteraction = performance.now();
  });

  canvas.addEventListener('pointercancel', () => { press = null; });

  function pick(evt) {
    const rect = canvas.getBoundingClientRect();
    pointer.x = ((evt.clientX - rect.left) / rect.width) * 2 - 1;
    pointer.y = -((evt.clientY - rect.top) / rect.height) * 2 + 1;
    raycaster.setFromCamera(pointer, camera);
    const hit = raycaster.intersectObjects(root.children, false)
      .find(h => h.object.userData && h.object.userData.type);
    if (!hit) return;
    if (hit.object.userData.type === 'line') selectLine(hit.object.userData.id);
    else selectBus(hit.object.userData.id);
  }

  function selectLine(id) {
    selectedLineId = id;
    const lineSelect = document.getElementById('line-select');
    if (lineSelect) {
      lineSelect.value = String(id);
      lineSelect.dispatchEvent(new Event('change'));
    }
    showInfo();
  }

  function selectBus(busId) {
    if (!lastState) return;
    const line = lastState.lines.find(l => l.busAId === busId || l.busBId === busId);
    if (line) selectLine(line.id);
  }

  // Operations go through the existing 2D form, so the Java simulator decides every outcome.
  function submitOperation(op, lineId) {
    const opSelect = document.getElementById('operation-select');
    const lineSelect = document.getElementById('line-select');
    const form = document.getElementById('operation-form');
    if (!opSelect || !lineSelect || !form) return;
    opSelect.value = op;
    lineSelect.value = String(lineId);
    lineSelect.dispatchEvent(new Event('change'));
    if (typeof form.requestSubmit === 'function') form.requestSubmit();
    else form.dispatchEvent(new Event('submit', { cancelable: true }));
  }

  function showInfo() {
    if (!infoBar) return;
    infoBar.innerHTML = '';
    const line = lastState && selectedLineId !== null
      ? lastState.lines.find(l => l.id === selectedLineId)
      : null;
    if (!line) {
      infoBar.textContent = 'Click a line or bus in the 3D view to act on it.';
      return;
    }
    const status = line.faulted ? 'FAULTED' : (line.closed ? 'CLOSED' : 'OPEN');
    const title = document.createElement('strong');
    title.textContent = 'Line ' + line.id + ' (bus ' + line.busAId + ' to bus ' + line.busBId + ') is ' + status + '.';
    infoBar.appendChild(title);

    let actions;
    if (line.faulted) actions = [['CLEAR_FAULT', 'Clear fault']];
    else if (line.closed) actions = [['OPEN', 'Open line'], ['FAULT', 'Mark faulted']];
    else actions = [['CLOSE', 'Close line'], ['FAULT', 'Mark faulted']];

    actions.forEach(a => {
      const button = document.createElement('button');
      button.type = 'button';
      button.className = 'btn btn-outline';
      button.textContent = a[1];
      button.addEventListener('click', () => submitOperation(a[0], line.id));
      infoBar.appendChild(button);
    });
  }

  // ---- Data refresh ------------------------------------------------------

  async function refresh() {
    try {
      const res = await fetch('/api/state', { cache: 'no-store' });
      if (!res.ok) return;
      lastState = await res.json();
      render3D(lastState);
      showInfo();
    } catch (err) {
      // The 2D dashboard reports connection problems; the 3D view stays quiet.
    }
  }

  let refreshTimer = null;
  function scheduleRefresh() {
    clearTimeout(refreshTimer);
    refreshTimer = setTimeout(refresh, 40);
  }

  // The 2D code redraws these tables after every operation, scenario load and reset.
  // Watching them lets the 3D view follow without editing app.js.
  ['history-body', 'components-body'].forEach(id => {
    const el = document.getElementById(id);
    if (el) new MutationObserver(scheduleRefresh).observe(el, { childList: true, subtree: true });
  });

  // ---- Sizing and animation ---------------------------------------------

  function resize() {
    const w = Math.max(320, container.clientWidth);
    const h = Math.max(280, container.clientHeight);
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  if (typeof ResizeObserver !== 'undefined') new ResizeObserver(resize).observe(container);
  window.addEventListener('resize', resize);
  resize();

  const startTime = performance.now();
  function frame(now) {
    if (now - lastInteraction > 3000) root.rotation.y += 0.0025;
    const pulse = 0.35 + 0.35 * Math.sin((now - startTime) / 250);
    faultMaterials.forEach(m => { m.emissiveIntensity = pulse; });
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  showInfo();
  refresh();
})();

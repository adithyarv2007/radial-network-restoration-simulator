(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const SVG_NS = 'http://www.w3.org/2000/svg';
  let state = null;
  let selectedLineId = null;
  let toastTimer = null;

  function showToast(message, isError = false) {
    const el = $('toast');
    el.textContent = message;
    el.className = `toast show${isError ? ' error' : ''}`;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.className = 'toast'; }, 3300);
  }

  async function api(path, options = {}) {
    const response = await fetch(path, { cache: 'no-store', ...options });
    const contentType = response.headers.get('content-type') || '';
    const body = contentType.includes('application/json') ? await response.json() : await response.text();
    if (!response.ok) throw new Error(body && body.error ? body.error : `Request failed (${response.status})`);
    return body;
  }

  function fmt(value, digits = 2) {
    const n = Number(value);
    return Number.isFinite(n) ? n.toLocaleString(undefined, { minimumFractionDigits: digits, maximumFractionDigits: digits }) : '—';
  }
  function fmtInt(value) { return Number(value || 0).toLocaleString(); }
  function clear(el) { while (el.firstChild) el.removeChild(el.firstChild); }
  function element(tag, className, text) {
    const el = document.createElement(tag);
    if (className) el.className = className;
    if (text !== undefined && text !== null) el.textContent = String(text);
    return el;
  }
  function cell(row, text, className) {
    const td = element('td', className, text);
    row.appendChild(td);
    return td;
  }
  function badge(text, className) { return element('span', `state-badge ${className}`, text); }
  function svgEl(name, attrs = {}, text = null) {
    const n = document.createElementNS(SVG_NS, name);
    Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, String(v)));
    if (text !== null) n.textContent = String(text);
    return n;
  }

  async function initialize() {
    try {
      const scenarios = await api('/api/scenarios');
      const select = $('scenario-select');
      clear(select);
      scenarios.forEach(item => {
        const opt = element('option', '', item.title);
        opt.value = item.id;
        select.appendChild(opt);
      });
      state = await api('/api/state');
      select.value = state.scenarioId;
      render();
      $('connection-label').textContent = 'Local Java engine connected';
      $('connection-label').previousElementSibling.classList.add('connected');
    } catch (error) {
      $('connection-label').textContent = 'Java engine unavailable';
      $('last-action').className = 'action-result rejected';
      $('last-action').querySelector('strong').textContent = 'Could not connect';
      $('last-action').querySelector('p').textContent = error.message;
      showToast(`Cannot reach the Java dashboard server: ${error.message}`, true);
    }
  }

  function render() {
    if (!state) return;
    const summary = state.summary;
    const scenarioSelect = $('scenario-select');
    if (state.custom && !Array.from(scenarioSelect.options).some(option => option.value === state.scenarioId)) {
      const customOption = element('option', '', state.title);
      customOption.value = state.scenarioId;
      scenarioSelect.appendChild(customOption);
    }
    scenarioSelect.value = state.scenarioId;
    $('scenario-description').textContent = state.custom
      ? 'Custom network loaded from a local text file.'
      : `${state.buses.length} buses · ${state.lines.length} lines · ${state.title}`;
    $('unserved-kw').textContent = fmt(summary.unservedPKw);
    $('unserved-kvar').textContent = `${fmt(summary.unservedQKvar)} kVAr reactive demand`;
    $('unserved-customers').textContent = fmtInt(summary.unservedCustomers);
    $('warning-count').textContent = fmtInt(summary.violations);
    $('warning-foot').textContent = summary.violations === 0 ? 'No current electrical warnings' : 'Review component warnings below';
    $('accepted-count').textContent = fmtInt(summary.accepted);
    $('rejected-count').textContent = fmtInt(summary.rejected);
    $('command-count').textContent = fmtInt(summary.commands);
    $('network-counts').textContent = `${state.buses.length} buses · ${state.lines.length} lines`;
    const islands = (state.snapshot.components || []).filter(c => !c.hasSource).length;
    const topStatus = $('topology-status');
    topStatus.textContent = islands === 0 ? 'All islands energized' : `${islands} de-energized island${islands === 1 ? '' : 's'}`;
    topStatus.className = `status-chip ${islands === 0 ? 'good' : 'warn'}`;
    renderAction(state.lastAction);
    renderLineSelect();
    renderDiagram();
    renderComponents();
    renderLines();
    renderHistory();
  }

  function renderAction(result) {
    const box = $('last-action');
    clear(box);
    const accepted = result ? Boolean(result.accepted) : true;
    const kind = !result ? 'neutral' : (accepted ? 'accepted' : 'rejected');
    box.className = `action-result ${kind}`;
    box.appendChild(element('span', 'result-icon', !result ? 'i' : (accepted ? '✓' : '!')));
    const content = element('div');
    content.appendChild(element('strong', '', result ? `${result.operationText} · ${accepted ? 'Accepted' : 'Rejected'}` : 'Ready'));
    content.appendChild(element('p', '', result ? result.statusMessage : 'Load a scenario to begin.'));
    box.appendChild(content);
  }

  function renderLineSelect() {
    const select = $('line-select');
    const previous = selectedLineId !== null ? selectedLineId : select.value;
    clear(select);
    state.lines.forEach(line => {
      const status = line.faulted ? 'FAULTED' : (line.closed ? 'CLOSED' : 'OPEN');
      const opt = element('option', '', `Line ${line.id} · ${line.busAId}—${line.busBId} · ${status}`);
      opt.value = String(line.id);
      select.appendChild(opt);
    });
    const valid = state.lines.some(l => String(l.id) === String(previous));
    if (valid) select.value = String(previous);
    else if (state.lines.length) select.value = String(state.lines[0].id);
    selectedLineId = select.value === '' ? null : Number(select.value);
  }

  function componentByBus() {
    const map = new Map();
    (state.snapshot.components || []).forEach(component => {
      (component.busIds || []).forEach(id => map.set(Number(id), component));
    });
    return map;
  }

  function renderDiagram() {
    const svg = $('network-diagram');
    clear(svg);
    const busList = state.buses;
    const lineList = state.lines;
    if (!busList.length) { $('diagram-empty').classList.remove('hidden'); return; }
    $('diagram-empty').classList.add('hidden');
    const positions = new Map();
    const n = busList.length;
    const cols = Math.min(5, Math.max(1, Math.ceil(Math.sqrt(n * 1.5))));
    const rows = Math.ceil(n / cols);
    busList.forEach((bus, i) => {
      const col = i % cols;
      const row = Math.floor(i / cols);
      const usableW = 780, usableH = 310;
      const x = cols === 1 ? 450 : 60 + col * (usableW / (cols - 1));
      const y = rows === 1 ? 210 : 58 + row * (usableH / (rows - 1));
      positions.set(bus.id, { x, y, bus });
    });
    lineList.forEach(line => {
      const a = positions.get(line.busAId), b = positions.get(line.busBId);
      if (!a || !b) return;
      const g = svgEl('g', { class: 'edge-group', 'data-line-id': line.id });
      const cls = line.faulted ? 'faulted' : (line.closed ? 'closed' : 'open');
      const edge = svgEl('line', { x1: a.x, y1: a.y, x2: b.x, y2: b.y, class: `edge ${cls}${Number(line.id) === Number(selectedLineId) ? ' selected' : ''}` });
      edge.addEventListener('click', () => selectLine(line.id));
      g.appendChild(edge);
      const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2 - 9;
      g.appendChild(svgEl('text', { x: mx, y: my, class: 'edge-label', 'text-anchor': 'middle' }, `L${line.id}`));
      svg.appendChild(g);
    });
    const compMap = componentByBus();
    busList.forEach(bus => {
      const p = positions.get(bus.id);
      const comp = compMap.get(Number(bus.id));
      const classes = ['bus-node'];
      if (comp && comp.hasSource) classes.push('energized'); else classes.push('island');
      if (bus.hasSubstation) classes.push('source');
      const group = svgEl('g', { class: classes.join(' '), transform: `translate(${p.x} ${p.y})`, tabindex: '0', role: 'button', 'aria-label': `Bus ${bus.id}` });
      group.appendChild(svgEl('circle', { cx: 0, cy: 0, r: 23, class: 'bus-circle' }));
      group.appendChild(svgEl('text', { x: 0, y: 0, class: 'bus-id' }, String(bus.id)));
      group.appendChild(svgEl('text', { x: 0, y: 39, class: 'bus-load' }, `${fmt(bus.pKw, 0)} kW`));
      group.addEventListener('click', () => {
        const lines = state.lines.filter(l => l.busAId === bus.id || l.busBId === bus.id);
        if (lines.length) selectLine(lines[0].id);
      });
      group.addEventListener('keydown', (event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); group.dispatchEvent(new MouseEvent('click')); } });
      svg.appendChild(group);
    });
  }

  function selectLine(id) {
    selectedLineId = Number(id);
    $('line-select').value = String(id);
    renderDiagram();
  }

  function renderComponents() {
    const body = $('components-body'); clear(body);
    const components = state.snapshot.components || [];
    if (!components.length) { const row = element('tr'); cell(row, 'No component snapshot available', 'empty-cell').colSpan = 6; body.appendChild(row); return; }
    components.forEach(c => {
      const row = element('tr');
      cell(row, `C${c.componentId}`, 'component-name');
      cell(row, (c.busIds || []).join(', '));
      const s = cell(row, ''); s.appendChild(c.hasSource ? badge(`ENERGIZED · S${c.sourceBusId}`, 'energized') : badge('DE-ENERGIZED', 'island'));
      cell(row, `${fmt(c.totalPKw)} kW / ${fmt(c.totalQKvar)} kVAr`);
      cell(row, fmtInt(c.totalCustomers));
      const v = c.electricalReport || { violationCount: 0, violations: [] };
      const warningCell = cell(row, '');
      if (v.violationCount > 0) warningCell.appendChild(badge(`${v.violationCount} warning${v.violationCount === 1 ? '' : 's'}`, 'warning'));
      else warningCell.appendChild(badge('None', 'closed'));
      if (v.violations && v.violations.length) row.title = v.violations.join('\n');
      body.appendChild(row);
    });
  }

  function renderLines() {
    const body = $('lines-body'); clear(body);
    if (!state.lines.length) { const row = element('tr'); cell(row, 'No lines defined', 'empty-cell').colSpan = 4; body.appendChild(row); return; }
    state.lines.forEach(line => {
      const row = element('tr');
      cell(row, `L${line.id}`, 'line-name');
      cell(row, `${line.busAId} — ${line.busBId}`);
      const stateCell = cell(row, '');
      stateCell.appendChild(line.faulted ? badge('FAULTED', 'faulted') : (line.closed ? badge('CLOSED', 'closed') : badge('OPEN', 'open')));
      const actionCell = cell(row, '');
      let op, text;
      if (line.faulted) { op = 'CLEAR_FAULT'; text = 'Clear fault'; }
      else if (line.closed) { op = 'OPEN'; text = 'Open'; }
      else { op = 'CLOSE'; text = 'Close'; }
      const button = element('button', 'tiny-action', text);
      button.type = 'button';
      button.addEventListener('click', () => { selectLine(line.id); $('operation-select').value = op; submitOperation(op, line.id); });
      actionCell.appendChild(button);
      row.addEventListener('click', (event) => { if (event.target === row || event.target.tagName === 'TD') selectLine(line.id); });
      body.appendChild(row);
    });
  }

  function renderHistory() {
    const body = $('history-body'); clear(body);
    $('history-count').textContent = `${state.history.length} step${state.history.length === 1 ? '' : 's'}`;
    if (!state.history.length) { const row = element('tr'); cell(row, 'No operations yet.', 'empty-cell').colSpan = 6; body.appendChild(row); return; }
    [...state.history].reverse().forEach(h => {
      const row = element('tr');
      cell(row, h.sequence);
      cell(row, h.operation);
      const resultCell = cell(row, '');
      resultCell.appendChild(element('span', `result-label ${h.accepted ? 'accepted' : 'rejected'}`, h.accepted ? 'ACCEPTED' : 'REJECTED'));
      cell(row, `${fmt(h.unservedPKw)} kW / ${fmt(h.unservedQKvar)} kVAr`);
      cell(row, fmtInt(h.customers));
      cell(row, h.message);
      body.appendChild(row);
    });
  }

  async function submitOperation(op, lineId) {
    try {
      state = await api('/api/operation', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ operation: op, lineId: String(lineId) }) });
      render();
      showToast(`${op} ${lineId}: ${state.lastAction.accepted ? 'accepted' : 'rejected'}`, !state.lastAction.accepted);
    } catch (error) { showToast(error.message, true); }
  }

  $('operation-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!state || selectedLineId === null) return;
    await submitOperation($('operation-select').value, Number($('line-select').value));
  });
  $('line-select').addEventListener('change', () => { selectedLineId = Number($('line-select').value); renderDiagram(); });
  $('scenario-select').addEventListener('change', async () => {
    try {
      state = await api('/api/load', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ scenarioId: $('scenario-select').value }) });
      selectedLineId = state.lines.length ? state.lines[0].id : null;
      render(); showToast(`Loaded ${state.title}`);
    } catch (error) { showToast(error.message, true); }
  });
  $('reset-button').addEventListener('click', async () => {
    try { state = await api('/api/reset', { method: 'POST' }); selectedLineId = state.lines.length ? state.lines[0].id : null; render(); showToast('Scenario reset to its initial topology.'); }
    catch (error) { showToast(error.message, true); }
  });
  $('verify-button').addEventListener('click', async () => {
    try { state = await api('/api/verify', { method: 'POST' }); render(); showToast('Topology invariants and electrical screen checked.'); }
    catch (error) { showToast(error.message, true); }
  });
  $('network-file').addEventListener('change', async () => {
    const file = $('network-file').files && $('network-file').files[0];
    if (!file) return;
    try {
      const raw = await file.text();
      state = await api('/api/load-raw', { method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: raw });
      selectedLineId = state.lines.length ? state.lines[0].id : null;
      render(); showToast(`Loaded custom network: ${file.name}`);
    } catch (error) { showToast(`Could not load file: ${error.message}`, true); }
    finally { $('network-file').value = ''; }
  });

  initialize();
})();

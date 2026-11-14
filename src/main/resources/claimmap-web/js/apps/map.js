import { icon } from '../icons.js';
import { el, state, authHeaders, api, notifyUnauthorized, showError, toast } from '../core.js';

const CELL = 500;
const STALE_MS = 30000;

export function render(container) {
  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'แผนที่ดินแดน' }),
        el('p', { class: 'hint', text: 'สำรวจพื้นที่จอง chunk 500×500 บล็อก · สีส้ม = ของคุณ, สีแดง = ของคนอื่น' })))
  );

  const xInput = el('input', { type: 'number', value: '0', min: -30000000, max: 30000000 });
  const zInput = el('input', { type: 'number', value: '0', min: -30000000, max: 30000000 });
  const goButton = el('button', { class: 'secondary', type: 'button', text: 'ไปยังพิกัด' });
  const zoomIn = el('button', { class: 'secondary', type: 'button', title: 'ขยาย' }, icon('plus', 14));
  const zoomOut = el('button', { class: 'secondary', type: 'button', title: 'ย่อ' }, icon('close', 14));
  const claimButton = el('button', { class: 'primary', type: 'button', text: 'จองช่องนี้', disabled: true });
  const transferButton = el('button', { class: 'secondary', type: 'button', text: 'โอนกรรมสิทธิ์', hidden: true });
  const unclaimButton = el('button', { class: 'secondary', type: 'button', text: 'ยกเลิกการจอง', hidden: true });
  const toolbar = el('div', { class: 'map-toolbar' },
    el('div', { class: 'field' }, el('label', { text: 'พิกัด X' }), xInput),
    el('div', { class: 'field' }, el('label', { text: 'พิกัด Z' }), zInput),
    goButton, zoomIn, zoomOut, claimButton, transferButton, unclaimButton);
  const canvas = el('canvas', { id: 'map-canvas' });
  const info = el('div', { class: 'map-info', text: 'ลากเพื่อเลื่อน · แตะเพื่อเลือกช่อง · สีส้ม = ของคุณ สีแดง = ของคนอื่น' });
  container.append(toolbar, canvas, info);

  const g = canvas.getContext('2d');
  let cx = typeof state.lastX === 'number' ? state.lastX : 0;
  let cz = typeof state.lastZ === 'number' ? state.lastZ : 0;
  let scale = 0.25;
  let owned = {};
  let me = state.me;
  let sel = null;
  let peek = null;
  let drag = null;
  let moved = false;
  const tiles = new Map();
  let disposed = false;

  function resize() {
    const w = Math.max(1, Math.floor(canvas.clientWidth));
    const h = Math.max(1, Math.floor(canvas.clientHeight));
    if (canvas.width !== w || canvas.height !== h) {
      canvas.width = w;
      canvas.height = h;
    }
    draw();
  }

  function getTile(tx, tz) {
    const id = tx + ',' + tz;
    const entry = tiles.get(id);
    if (!state.session) return null;
    if (!entry || (!entry.busy && Date.now() - entry.t > STALE_MS)) {
      const old = entry ? entry.img : null;
      tiles.set(id, { img: old, t: Date.now(), busy: true });
      fetch('/api/map/' + id + '.png', { headers: authHeaders() })
        .then(r => {
          if (r.status === 401) { notifyUnauthorized(); return null; }
          return r.status === 200 ? r.blob() : null;
        })
        .then(blob => {
          if (disposed) return;
          if (!blob) { tiles.set(id, { img: null, t: Date.now() }); draw(); return; }
          const img = new Image();
          img.onload = () => {
            if (disposed) return;
            URL.revokeObjectURL(img.src);
            tiles.set(id, { img, t: Date.now() });
            draw();
          };
          img.src = URL.createObjectURL(blob);
        })
        .catch(() => { if (!disposed) tiles.set(id, { img: old, t: Date.now() }); });
    }
    const current = tiles.get(id);
    return current ? current.img : null;
  }

  function draw() {
    if (disposed) return;
    const W = canvas.width;
    const H = canvas.height;
    const px = CELL * scale;
    g.fillStyle = '#111';
    g.fillRect(0, 0, W, H);
    const x0 = Math.floor((cx - W / 2 / scale) / CELL);
    const x1 = Math.floor((cx + W / 2 / scale) / CELL);
    const z0 = Math.floor((cz - H / 2 / scale) / CELL);
    const z1 = Math.floor((cz + H / 2 / scale) / CELL);
    g.imageSmoothingEnabled = false;
    for (let tx = x0; tx <= x1; tx++) {
      for (let tz = z0; tz <= z1; tz++) {
        const sx = W / 2 + (tx * CELL - cx) * scale;
        const sz = H / 2 + (tz * CELL - cz) * scale;
        const tile = getTile(tx, tz);
        g.fillStyle = '#262626';
        g.fillRect(sx, sz, px, px);
        if (tile) g.drawImage(tile, sx, sz, px, px);
        else if (px > 90) {
          g.fillStyle = '#777';
          g.font = '12px sans-serif';
          g.fillText('ยังไม่ได้สำรวจ', sx + 4, sz + px / 2);
        }
        const id = tx + ',' + tz;
        if (owned[id]) {
          g.fillStyle = owned[id] === me ? 'rgba(255,150,0,.45)' : 'rgba(220,40,40,.4)';
          g.fillRect(sx, sz, px, px);
        }
        if (sel === id) { g.fillStyle = 'rgba(255,230,0,.4)'; g.fillRect(sx, sz, px, px); }
        if (peek === id) {
          g.strokeStyle = '#fff';
          g.lineWidth = 3;
          g.strokeRect(sx + 1, sz + 1, px - 2, px - 2);
          g.lineWidth = 1;
        }
        g.strokeStyle = 'rgba(0,0,0,.6)';
        g.strokeRect(sx, sz, px, px);
        if (px > 70) {
          g.fillStyle = '#fff';
          g.font = '12px sans-serif';
          g.fillText(id + (owned[id] ? ' · ' + owned[id] : ''), sx + 4, sz + 14);
        }
      }
    }
    g.strokeStyle = '#fff';
    g.beginPath();
    g.moveTo(W / 2 - 8, H / 2);
    g.lineTo(W / 2 + 8, H / 2);
    g.moveTo(W / 2, H / 2 - 8);
    g.lineTo(W / 2, H / 2 + 8);
    g.stroke();
    xInput.value = Math.round(cx);
    zInput.value = Math.round(cz);
  }

  async function loadOwned() {
    try {
      const data = await api('GET', '/api/cells');
      owned = data || {};
    } catch (e) {
      if (e.status !== 401) showError(e);
    }
  }

  canvas.addEventListener('pointerdown', onDown);
  canvas.addEventListener('pointermove', onMove);
  canvas.addEventListener('pointerup', onUp);

  function onDown(e) {
    drag = { x: e.clientX, y: e.clientY, cx, cz };
    moved = false;
    canvas.setPointerCapture(e.pointerId);
  }

  function onMove(e) {
    if (!drag) return;
    const dx = e.clientX - drag.x;
    const dy = e.clientY - drag.y;
    if (Math.abs(dx) + Math.abs(dy) > 4) moved = true;
    cx = drag.cx - dx / scale;
    cz = drag.cz - dy / scale;
    draw();
  }

  function onUp(e) {
    drag = null;
    if (moved) return;
    const rect = canvas.getBoundingClientRect();
    const bx = cx + (e.clientX - rect.left - canvas.width / 2) / scale;
    const bz = cz + (e.clientY - rect.top - canvas.height / 2) / scale;
    const tx = Math.floor(bx / CELL);
    const tz = Math.floor(bz / CELL);
    const id = tx + ',' + tz;
    sel = null;
    peek = null;
    claimButton.disabled = true;
    transferButton.hidden = true;
    unclaimButton.hidden = true;
    if (owned[id]) {
      peek = id;
      const isOwner = owned[id] === me;
      const canManage = isOwner || state.admin;
      if (canManage) {
        transferButton.hidden = false;
        unclaimButton.hidden = false;
      }
      info.textContent = 'ช่อง ' + id + ' | เจ้าของ: ' + owned[id] + (isOwner ? ' (ช่องของคุณ)' : (state.admin ? ' (แอดมินจัดการได้)' : ''));
    } else {
      sel = id;
      claimButton.disabled = false;
      info.textContent = 'ช่อง ' + id + ' | X ' + (tx * CELL) + '…' + (tx * CELL + CELL - 1)
        + '  Z ' + (tz * CELL) + '…' + (tz * CELL + CELL - 1) + ' | ว่าง';
    }
    draw();
  }

  goButton.addEventListener('click', () => {
    cx = Number(xInput.value) || 0;
    cz = Number(zInput.value) || 0;
    draw();
  });
  zoomIn.addEventListener('click', () => { scale = Math.min(scale * 1.5, 2); draw(); });
  zoomOut.addEventListener('click', () => { scale = Math.max(scale / 1.5, 0.03); draw(); });

  claimButton.addEventListener('click', async () => {
    if (!sel || owned[sel]) return;
    if (!state.session) { toast('ต้องเข้าสู่ระบบก่อน', 'error'); return; }
    const id = sel;
    claimButton.disabled = true;
    try {
      await api('POST', '/api/cells/' + id + '/claim', {});
      info.textContent = 'จองสำเร็จ: ' + id;
      toast('จองช่อง ' + id + ' สำเร็จ', 'ok');
      await loadOwned();
    } catch (e) {
      info.textContent = 'จองไม่สำเร็จ: ' + (e.message || e.status);
      showError(e);
    } finally {
      sel = null;
      claimButton.disabled = true;
      draw();
    }
  });

  transferButton.addEventListener('click', async () => {
    if (!peek || !owned[peek]) return;
    const to = window.prompt('โอนสิทธิ์เขต ' + peek + ' ให้แก่ผู้เล่น (ใส่ชื่อผู้เล่น):');
    if (!to || !to.trim()) return;
    try {
      await api('POST', '/api/cells/' + peek + '/transfer', { to: to.trim() });
      toast('โอนกรรมสิทธิ์เขต ' + peek + ' ให้แก่ ' + to.trim() + ' สำเร็จ', 'ok');
      await loadOwned();
      transferButton.hidden = true;
      unclaimButton.hidden = true;
      peek = null;
      draw();
    } catch (e) {
      showError(e);
    }
  });

  unclaimButton.addEventListener('click', async () => {
    if (!peek || !owned[peek]) return;
    if (!window.confirm('คุณแน่ใจหรือไม่ว่าต้องการยกเลิกการจองเขต ' + peek + ' ?')) return;
    try {
      await api('DELETE', '/api/cells/' + peek);
      toast('ยกเลิกการจองเขต ' + peek + ' แล้ว', 'ok');
      await loadOwned();
      transferButton.hidden = true;
      unclaimButton.hidden = true;
      peek = null;
      draw();
    } catch (e) {
      showError(e);
    }
  });

  const observer = new ResizeObserver(() => resize());
  observer.observe(canvas);
  const refresh = setInterval(() => { if (document.visibilityState === 'visible') draw(); }, STALE_MS);

  if (state.session && typeof state.lastX !== 'number') {
    api('GET', '/api/me').then(meData => {
      if (meData && meData.hasPos && !disposed) {
        cx = meData.x;
        cz = meData.z;
        state.lastX = meData.x;
        state.lastZ = meData.z;
        state.hasPos = true;
        draw();
      }
    }).catch(() => {});
  }

  resize();
  loadOwned().then(draw);

  return {
    dispose() {
      disposed = true;
      clearInterval(refresh);
      observer.disconnect();
      canvas.removeEventListener('pointerdown', onDown);
      canvas.removeEventListener('pointermove', onMove);
      canvas.removeEventListener('pointerup', onUp);
    }
  };
}

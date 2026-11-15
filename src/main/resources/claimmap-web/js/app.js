/* Time SMP web UI — rebuilt from scratch. No old markup, classes or components. */
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const TOKEN_KEY = 'time_smp_token';

const state = {
  token: localStorage.getItem(TOKEN_KEY) || '',
  me: null,
  pos: null,
  names: null,
  balance: 0,
  currency: 'เหรียญ',
  mail: 0,
  adminTab: 'overview',
  sellTab: 'list',
  auctionTab: 'active',
  walletTab: 'history',
};

let pageTimer = null;
let mapCleanup = null;
let toastTimer = null;

/* ---------- tiny DOM + format helpers ---------- */
function el(tag, attrs, ...kids) {
  const node = document.createElement(tag);
  if (tag === 'input') {
    attrs = Object.assign({ type: 'text' }, attrs, { type: 'text' });
  }
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value == null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
    else if (key === 'value') node.value = value;
    else if (key === 'checked' || key === 'disabled' || key === 'selected') node[key] = !!value;
    else if (value === true) node.setAttribute(key, '');
    else node.setAttribute(key, String(value));
  }
  for (const kid of kids.flat(9)) {
    if (kid == null || kid === false || kid === true) continue;
    node.append(kid.nodeType ? kid : document.createTextNode(String(kid)));
  }
  return node;
}
function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
function money(n) { return Number(n || 0).toLocaleString('en-US'); }
function num(v) { const n = Number.parseInt(v, 10); return Number.isFinite(n) ? n : 0; }
function txt(v, fallback = '-') { return v == null || v === '' ? fallback : String(v); }
function errText(error, fallback = 'เกิดข้อผิดพลาด') { return txt(error?.message, fallback); }
function toastError(error, fallback) { toast(errText(error, fallback), 'error'); }

/* Local item icons named after the canonical minecraft:<id> path. */
function iconPath(itemId) {
  const id = String(itemId || 'minecraft:air').toLowerCase();
  const path = id.includes(':') ? id.slice(id.indexOf(':') + 1) : id;
  return '/icons/items/' + path.replace(/[^a-z0-9_./-]/g, '') + '.png';
}
function itemIcon(itemId, cls = '') {
  const box = el('span', { class: 'iicon ' + cls, 'aria-hidden': 'true' });
  const img = el('img', { src: iconPath(itemId), alt: '', loading: 'lazy', width: '32', height: '32' });
  img.addEventListener('error', () => {
    img.remove();
    box.classList.add('missing');
  });
  box.append(img);
  return box;
}
function itemHeading(itemId, text) {
  return el('span', { class: 'item-title item-with-icon' }, itemIcon(itemId, 'sm'), el('span', {}, txt(text, 'ไอเท็ม')));
}
function clock(ms) { return new Date(Number(ms) || 0).toLocaleString('th-TH', { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }); }
function shortClock(ms) { return new Date(Number(ms) || 0).toLocaleTimeString('th-TH', { hour: '2-digit', minute: '2-digit' }); }
function left(ms) {
  const d = Number(ms) - Date.now();
  if (d <= 0) return 'ปิดแล้ว';
  const m = Math.floor(d / 60000);
  if (m < 60) return `${m} นาที`;
  const h = Math.floor(m / 60);
  if (h < 48) return `${h} ชม.`;
  return `${Math.floor(h / 24)} วัน`;
}
function go(route) { location.hash = '#' + route; }
function active(route) { return (location.hash.replace(/^#\/?/, '') || 'home') === route; }

/* ---------- API ---------- */
async function api(method, path, body) {
  const headers = {};
  if (state.token) headers.Authorization = 'Bearer ' + state.token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  let data = null;
  try { data = await res.json(); } catch { /* empty or binary */ }
  if (!res.ok) {
    const err = new Error(data?.message || `HTTP ${res.status}`);
    err.status = res.status;
    err.data = data;
    if (res.status === 401 && state.token) {
      state.token = '';
      state.me = null;
      localStorage.removeItem(TOKEN_KEY);
      renderShell();
      if (!location.hash.startsWith('#login')) go('login');
    }
    throw err;
  }
  return data;
}
function authHeaders() { return state.token ? { Authorization: 'Bearer ' + state.token } : {}; }

/* ---------- feedback ---------- */
function toast(message, kind = '') {
  const node = $('#toast');
  node.textContent = message;
  node.className = 'show ' + kind;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { node.className = kind; }, 3200);
}
function skeleton(n = 3) { const box = el('div', { class: 'stack' }); for (let i = 0; i < n; i++) box.append(el('div', { class: 'skeleton' })); return box; }
function emptyState(title, desc, action) {
  return el('div', { class: 'empty' }, el('h2', {}, title), desc ? el('p', { class: 'hint' }, desc) : null, action || null);
}
function errorState(error) {
  return el('div', { class: 'empty' },
    el('h2', {}, 'โหลดไม่สำเร็จ'),
    el('p', { class: 'hint' }, error?.message || 'เกิดข้อผิดพลาด'),
    el('button', { class: 'btn secondary', type: 'button', onclick: () => render() }, 'ลองใหม่'));
}
function loading(box) { clear(box); box.append(skeleton(3)); }
async function fill(box, load, draw) {
  loading(box);
  try {
    const data = await load();
    clear(box);
    draw(data);
  } catch (error) {
    clear(box);
    box.append(errorState(error));
  }
}

function confirmDialog({ title, body, confirm = 'ยืนยัน', danger = false }) {
  const dialog = $('#confirm');
  clear(dialog);
  const form = el('form', { method: 'dialog', class: 'dialog-body' },
    el('h2', {}, title),
    el('p', { class: 'hint' }, body || ''),
    el('div', { class: 'dialog-actions' },
      el('button', { class: 'btn secondary', value: 'cancel', type: 'submit' }, 'ยกเลิก'),
      el('button', { class: 'btn ' + (danger ? 'danger' : 'primary'), value: 'ok', type: 'submit' }, confirm)));
  dialog.append(form);
  dialog.returnValue = '';
  dialog.showModal();
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok'), { once: true });
  });
}
function reasonDialog(title) {
  const dialog = $('#confirm');
  clear(dialog);
  const input = el('input', { class: 'input', type: 'text', id: 'reason-input', maxLength: '200', required: true, placeholder: 'อย่างน้อย 3 ตัวอักษร' });
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { method: 'dialog', class: 'dialog-body' },
    el('h2', {}, title),
    el('div', { class: 'field' }, el('label', { for: 'reason-input' }, 'เหตุผล'), input, error),
    el('div', { class: 'dialog-actions' },
      el('button', { class: 'btn secondary', value: 'cancel', type: 'submit' }, 'ยกเลิก'),
      el('button', { class: 'btn primary', value: 'ok', type: 'submit' }, 'ยืนยัน')));
  form.addEventListener('submit', (event) => {
    if (event.submitter?.value === 'cancel') return;
    const value = input.value.trim();
    if (value.length < 3) { event.preventDefault(); error.textContent = 'ต้องมีเหตุผลอย่างน้อย 3 ตัวอักษร'; }
  });
  dialog.append(form);
  dialog.returnValue = '';
  dialog.showModal();
  setTimeout(() => input.focus(), 30);
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => resolve(dialog.returnValue === 'ok' ? input.value.trim() : null), { once: true });
  });
}

/* ---------- custom dropdown ---------- */
let openDD = null;
function closeOpenDD(focus) { if (openDD) openDD.close(focus); }

function dropdown(opts) {
  const options = (opts.options || []).map((o) => {
    if (o == null) return null;
    const item = typeof o === 'string' ? { value: o, label: o } : o;
    return { value: item.value ?? '', label: txt(item.label, txt(item.value, 'ไม่ระบุ')) };
  }).filter((o) => o && o.value !== '');
  const id = opts.id || 'dd-' + Math.random().toString(36).slice(2);
  const searchable = opts.searchable ?? options.length > 8;
  const wrap = el('div', { class: 'field dd' });
  const label = el('label', { for: id + '-trigger' }, opts.label || '', opts.required ? el('span', { class: 'req' }, ' *') : null);
  const trigger = el('button', {
    type: 'button', id: id + '-trigger', class: 'dd-trigger', role: 'combobox',
    'aria-haspopup': 'listbox', 'aria-expanded': 'false', 'aria-controls': id + '-panel',
  });
  const valueEl = el('span', { class: 'dd-value placeholder' }, opts.placeholder || 'เลือก');
  trigger.append(valueEl, el('span', { class: 'dd-chev', 'aria-hidden': 'true' }));
  const closeBtn = el('button', { type: 'button', class: 'btn ghost', onclick: () => close(true) }, 'ปิด');
  const mobileHead = el('div', { class: 'dd-mobile-head' }, el('strong', {}, opts.label || 'เลือก'), closeBtn);
  const search = searchable ? el('input', { class: 'dd-search', type: 'text', placeholder: 'ค้นหา', autocomplete: 'off', 'aria-label': 'ค้นหา' }) : null;
  const list = el('div', { class: 'dd-list', id: id + '-list' });
  const panel = el('div', { class: 'dd-panel', id: id + '-panel', role: 'listbox', 'aria-label': opts.label || 'ตัวเลือก' },
    mobileHead, search, list, el('div', { class: 'dd-hint', 'aria-hidden': 'true' }, '↑↓ เลือก · Enter ยืนยัน · Esc ปิด'));
  const scrim = el('div', { class: 'dd-scrim' });
  const hidden = el('select', { name: opts.name || id, id, tabindex: '-1', 'aria-hidden': 'true', style: 'display:none' });
  const helper = opts.helper ? el('p', { class: 'helper' }, opts.helper) : null;
  const error = el('p', { class: 'err', role: 'alert' });
  // Anchor the panel to the trigger, not to the whole field (which includes
  // the label), so every dropdown opens at the same Y offset.
  const anchor = el('div', { class: 'dd-anchor' }, trigger, panel);
  wrap.append(...[label, anchor, scrim, hidden, helper, error].filter(Boolean));

  let value = opts.value ?? '';
  let filtered = options.slice();
  let activeIndex = -1;
  let typing = '';
  let typeTimer = null;

  for (const opt of options) {
    hidden.append(el('option', { value: opt.value, disabled: opt.disabled ? true : null }, opt.label));
  }

  function byValue(v) { return options.find((o) => String(o.value) === String(v)); }
  function sync() {
    const found = byValue(value);
    valueEl.textContent = found ? found.label : (opts.placeholder || 'เลือก');
    valueEl.classList.toggle('placeholder', !found);
    hidden.value = found ? String(found.value) : '';
    trigger.setAttribute('aria-invalid', error.textContent ? 'true' : 'false');
  }
  function render() {
    clear(list);
    activeIndex = -1;
    let group = null;
    filtered.forEach((opt, index) => {
      if (opt.group && opt.group !== group) { group = opt.group; list.append(el('div', { class: 'dd-group' }, group)); }
      const option = el('div', {
        class: 'dd-option', role: 'option', id: `${id}-opt-${index}`,
        'aria-selected': String(String(opt.value) === String(value)),
        'aria-disabled': opt.disabled ? 'true' : null,
      }, opt.label);
      if (String(opt.value) === String(value)) {
        option.classList.add('active');
        activeIndex = index;
      }
      if (opt.disabled) option.addEventListener('click', (event) => event.stopPropagation());
      else option.addEventListener('click', () => { choose(opt.value); close(true); });
      list.append(option);
    });
    if (!filtered.length) list.append(el('div', { class: 'dd-empty' }, 'ไม่พบรายการ'));
    if (activeIndex < 0 && filtered.length) activeIndex = 0;
    setActive(activeIndex, false);
  }
  function setActive(index, scroll = true) {
    const items = $$('.dd-option', list);
    items.forEach((n) => n.classList.remove('active'));
    if (!items.length) { trigger.removeAttribute('aria-activedescendant'); return; }
    activeIndex = Math.max(0, Math.min(items.length - 1, index));
    const node = items[activeIndex];
    node.classList.add('active');
    trigger.setAttribute('aria-activedescendant', node.id);
    if (scroll) node.scrollIntoView({ block: 'nearest' });
  }
  function choose(v) {
    value = v;
    sync();
    render();
    if (opts.onChange) opts.onChange(v);
  }
  function open() {
    if (opts.disabled) return;
    if (openDD && openDD !== component) closeOpenDD(false);
    openDD = component;
    panel.classList.add('open');
    scrim.classList.add('open');
    trigger.setAttribute('aria-expanded', 'true');
    if (window.innerWidth <= 640) document.body.classList.add('dd-open');
    filtered = options.slice();
    if (search) search.value = '';
    render();
    if (window.innerWidth > 640) {
      panel.classList.remove('up');
      const rect = panel.getBoundingClientRect();
      if (rect.bottom > window.innerHeight - 8 && trigger.getBoundingClientRect().top > rect.height + 8) panel.classList.add('up');
      if (search) setTimeout(() => search.focus(), 0);
    }
  }
  function close(focus) {
    if (!panel.classList.contains('open')) return;
    panel.classList.remove('open', 'up');
    scrim.classList.remove('open');
    trigger.setAttribute('aria-expanded', 'false');
    trigger.removeAttribute('aria-activedescendant');
    document.body.classList.remove('dd-open');
    if (openDD === component) openDD = null;
    if (focus) trigger.focus();
  }
  function typeAhead(char) {
    clearTimeout(typeTimer);
    typing += char.toLowerCase();
    typeTimer = setTimeout(() => { typing = ''; }, 600);
    const hit = options.find((o) => !o.disabled && o.label.toLowerCase().startsWith(typing));
    if (hit) choose(hit.value);
  }

  trigger.addEventListener('click', () => panel.classList.contains('open') ? close(true) : open());
  trigger.addEventListener('keydown', (event) => {
    if (['Enter', ' ', 'ArrowDown', 'ArrowUp'].includes(event.key)) {
      event.preventDefault();
      if (!panel.classList.contains('open')) open();
      if (event.key === 'ArrowUp') setActive(filtered.length - 1);
    } else if (event.key.length === 1 && /\S/.test(event.key)) {
      typeAhead(event.key);
    } else if (event.key === 'Escape') {
      close(true);
    }
  });
  scrim.addEventListener('click', () => close(false));
  closeBtn.addEventListener('click', () => close(true));
  panel.addEventListener('keydown', (event) => {
    if (event.key === 'Escape') { event.preventDefault(); close(true); return; }
    if (event.key === 'Tab') { close(false); return; }
    if (event.key === 'ArrowDown') { event.preventDefault(); setActive(activeIndex + 1); }
    else if (event.key === 'ArrowUp') { event.preventDefault(); setActive(activeIndex - 1); }
    else if (event.key === 'Home') { event.preventDefault(); setActive(0); }
    else if (event.key === 'End') { event.preventDefault(); setActive(filtered.length - 1); }
    else if (event.key === 'Enter') {
      event.preventDefault();
      const opt = filtered[activeIndex];
      if (opt && !opt.disabled) { choose(opt.value); close(true); }
    }
  });
  if (search) {
    search.addEventListener('input', () => {
      const q = search.value.trim().toLowerCase();
      filtered = options.filter((o) => o.label.toLowerCase().includes(q));
      render();
    });

  }

  const component = {
    el: wrap,
    contains: (node) => wrap.contains(node),
    getValue: () => value,
    setValue: (v) => { value = v; sync(); render(); },
    setError: (msg) => { error.textContent = msg || ''; sync(); },
    setOptions: (next) => { options.splice(0, options.length, ...next.map((o) => typeof o === 'string' ? { value: o, label: o } : o)); filtered = options.slice(); sync(); render(); },
    open,
    close,
  };
  sync();
  render();
  return component;
}

document.addEventListener('pointerdown', (event) => {
  if (openDD && !openDD.contains(event.target)) openDD.close(false);
}, true);

/* ---------- shell ---------- */
const NAV = [
  { id: 'home', label: 'หน้าแรก' },
  { id: 'map', label: 'แผนที่' },
  { id: 'buy', label: 'ซื้อ' },
  { id: 'sell', label: 'ขาย' },
  { id: 'auction', label: 'ประมูล' },
  { id: 'wallet', label: 'เงิน' },
  { id: 'mailbox', label: 'กล่อง' },
  { id: 'about', label: 'ช่วยเหลือ' },
];

function renderShell() {
  const nav = $('#nav');
  clear(nav);
  for (const item of NAV) {
    nav.append(el('a', { href: '#' + item.id, 'aria-current': active(item.id) ? 'page' : null }, item.label));
  }
  if (state.me?.admin) nav.append(el('a', { href: '#admin', 'aria-current': active('admin') ? 'page' : null }, 'แอดมิน'));

  const tabs = $('#tabbar');
  clear(tabs);
  const primary = [{ id: 'home', label: 'หน้าแรก' }, { id: 'map', label: 'แผนที่' }, { id: 'buy', label: 'ซื้อ' }, { id: 'sell', label: 'ขาย' }];
  for (const item of primary) {
    tabs.append(el('button', { type: 'button', 'aria-current': active(item.id) ? 'page' : null, onclick: () => go(item.id) }, item.label));
  }
  tabs.append(el('button', { type: 'button', onclick: openMenu }, 'เพิ่มเติม'));

  $('#balance').textContent = state.me ? money(state.balance) : '—';
  $('#mailcount').textContent = state.me ? 'กล่อง ' + state.mail : '';
  const user = $('#userBtn');
  user.textContent = state.me ? state.me.name : 'เข้าใช้';
  user.onclick = () => state.me ? openAccount() : go('login');
}

function openMenu() {
  const menu = el('div', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': 'เมนู' },
    el('div', { class: 'sheet-head' },
      el('strong', {}, 'เมนู'),
      el('button', { type: 'button', class: 'btn ghost', onclick: closeMenu }, 'ปิด')),
    el('div', { class: 'sheet-body' },
      ...[...NAV, ...(state.me?.admin ? [{ id: 'admin', label: 'แอดมิน' }] : [])].map((item) =>
        el('a', { href: '#' + item.id, 'aria-current': active(item.id) ? 'page' : null, onclick: closeMenu }, item.label)),
      el('a', { href: '#login', onclick: () => { closeMenu(); state.me ? logout() : null; } }, state.me ? 'ออกจากระบบ' : 'เข้าสู่ระบบ')));
  document.body.append(menu);
  document.body.classList.add('sheet-open');
  function onKey(event) { if (event.key === 'Escape') closeMenu(); }
  function closeMenu() {
    document.removeEventListener('keydown', onKey);
    document.body.classList.remove('sheet-open');
    menu.remove();
    const scrim = $('#scrim');
    scrim.hidden = true;
    scrim.onclick = null;
  }
  document.addEventListener('keydown', onKey);
  menu._close = closeMenu;
  $('#scrim').hidden = false;
  $('#scrim').onclick = closeMenu;
}
function closeMenu() {
  const menu = $('.sheet');
  if (menu?._close) menu._close();
  $('#scrim').hidden = true;
}

function openAccount() {
  const dialog = $('#confirm');
  clear(dialog);
  const form = el('form', { method: 'dialog', class: 'dialog-body' },
    el('h2', {}, state.me.name),
    el('p', { class: 'hint' }, `ยอดเงิน ${money(state.balance)} ${state.currency} · กล่องของ ${state.mail}`),
    el('div', { class: 'dialog-actions' },
      el('button', { class: 'btn secondary', value: 'close', type: 'submit' }, 'ปิด'),
      el('button', { class: 'btn danger', value: 'logout', type: 'submit', onclick: () => setTimeout(logout, 0) }, 'ออกจากระบบ')));
  dialog.append(form);
  dialog.returnValue = '';
  dialog.showModal();
}
async function logout() {
  state.token = '';
  state.me = null;
  state.balance = 0;
  state.mail = 0;
  localStorage.removeItem(TOKEN_KEY);
  renderShell();
  go('home');
  render();
  toast('ออกจากระบบแล้ว');
}
async function refreshWallet() {
  if (!state.me) { state.balance = 0; state.mail = 0; return; }
  try {
    const data = await api('GET', '/api/wallet');
    state.balance = data.balance || 0;
    state.currency = data.currency || state.currency;
    state.mail = data.mailboxCount || 0;
    renderShell();
  } catch { /* market may be disabled */ }
}
async function fetchNames() {
  if (state.names) return state.names;
  try { state.names = (await api('GET', '/api/names')).names || []; } catch { state.names = []; }
  return state.names;
}

/* ---------- router ---------- */
const PAGES = { home: pageHome, map: pageMap, buy: pageBuy, sell: pageSell, auction: pageAuction, wallet: pageWallet, mailbox: pageMailbox, about: pageAbout, admin: pageAdmin, login: pageLogin };

function render() {
  closeOpenDD(false);
  closeMenu();
  if (pageTimer) { clearInterval(pageTimer); pageTimer = null; }
  if (mapCleanup) { mapCleanup(); mapCleanup = null; }
  const route = location.hash.replace(/^#\/?/, '') || 'home';
  if (route !== 'login' && route !== 'home' && !state.me) {
    go('login');
    return;
  }
  renderShell();
  const view = $('#view');
  clear(view);
  const page = (PAGES[route] || page404)(view);
  document.title = (route === 'home' ? 'Time SMP' : `${navLabel(route)} · Time SMP`);
  window.scrollTo(0, 0);
  view.focus({ preventScroll: true });
  if (page?.then) page.catch((error) => { clear(view); view.append(errorState(error)); });
}
function navLabel(route) {
  if (route === 'admin') return 'แอดมิน';
  if (route === 'login') return 'เข้าสู่ระบบ';
  if (route === '404') return '404';
  return NAV.find((n) => n.id === route)?.label || 'ไม่พบหน้า';
}
function pageHead(route, title, desc) {
  return el('div', { class: 'page-head' },
    el('span', { class: 'mono muted eyebrow' }, navLabel(route)),
    el('h1', {}, title),
    desc ? el('p', { class: 'hint' }, desc) : null);
}
function page404(view) {
  view.append(el('div', { class: 'page' }, pageHead('404', 'ไม่พบหน้านี้', 'ลิงก์อาจเก่าหรือถูกเปลี่ยน'),
    el('a', { class: 'btn primary', href: '#home' }, 'กลับหน้าแรก')));
}

/* ---------- login ---------- */
async function pageLogin(view) {
  if (state.me) { go('home'); return; }
  const page = el('div', { class: 'page' });
  page.append(pageHead('login', 'เข้าสู่ระบบ', 'ใช้ชื่อที่ลงทะเบียนกับเซิร์ฟเวอร์'));
  view.append(page);
  const names = await fetchNames();
  const form = el('form', { class: 'stack', novalidate: true });
  const nameDD = dropdown({ label: 'ชื่อผู้เล่น', required: true, options: names, placeholder: 'เลือกชื่อ', name: 'name', id: 'login-name' });
  const fallback = names.length ? null : el('input', { class: 'input', type: 'text', name: 'name', id: 'login-name-input', placeholder: 'ชื่อในเกม', autocomplete: 'username', maxLength: '32', required: true });
  const password = el('input', { class: 'input', id: 'login-password', type: 'text', name: 'password', autocomplete: 'current-password', required: true, placeholder: 'รหัสผ่าน' });
  const error = el('p', { class: 'err', role: 'alert' });
  const submit = el('button', { class: 'btn primary block', type: 'submit' }, 'เข้าสู่ระบบ');
  form.append(
    names.length ? nameDD.el : el('div', { class: 'field' }, el('label', { for: 'login-name-input' }, 'ชื่อผู้เล่น', el('span', { class: 'req' }, ' *')), fallback),
    el('div', { class: 'field' }, el('label', { for: 'login-password' }, 'รหัสผ่าน', el('span', { class: 'req' }, ' *')), password),
    error, submit);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    error.textContent = '';
    const pass = password.value;
    const name = names.length ? nameDD.getValue() : fallback.value.trim();
    if (!name) { error.textContent = 'เลือกหรือกรอกชื่อผู้เล่น'; return; }
    if (!pass) { error.textContent = 'กรอกรหัสผ่าน'; return; }
    submit.disabled = true;
    submit.textContent = 'กำลังเข้าสู่ระบบ…';
    try {
      const data = await api('POST', '/api/login', { name, password: pass });
      state.token = data.token;
      localStorage.setItem(TOKEN_KEY, data.token);
      state.me = { name: data.name, admin: !!data.admin };
      await refreshWallet();
      toast('ยินดีต้อนรับ ' + data.name);
      go('home');
    } catch (err) {
      error.textContent = errText(err);
    } finally {
      submit.disabled = false;
      submit.textContent = 'เข้าสู่ระบบ';
    }
  });
  page.append(form);
}

/* ---------- home ---------- */
function pageHome(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('home', 'Time SMP', 'เศรษฐกิจ แผนที่ และการค้าของเซิร์ฟเวอร์'));
  if (state.me) {
    page.append(el('div', { class: 'stats' },
      el('div', { class: 'stat' }, el('strong', {}, money(state.balance)), el('span', {}, state.currency + ' คงเหลือ')),
      el('div', { class: 'stat' }, el('strong', {}, String(state.mail)), el('span', {}, 'ของในกล่อง')),
      el('div', { class: 'stat' }, el('strong', {}, state.me.name), el('span', {}, 'ผู้เล่น')),
      el('div', { class: 'stat' }, el('strong', {}, state.me.admin ? 'แอดมิน' : 'ผู้เล่น'), el('span', {}, 'สิทธิ์'))));
    page.append(el('div', { class: 'row', style: 'margin-top:32px' },
      el('a', { class: 'btn primary', href: '#buy' }, 'ซื้อของ'),
      el('a', { class: 'btn secondary', href: '#sell' }, 'ลงขาย')));
    page.append(el('div', { class: 'rows', style: 'margin-top:48px' },
      rowLink('แผนที่เขต', 'จอง ดูเจ้าของ และโอนย้าย', '#map'),
      rowLink('ตลาดผู้เล่น', 'ของราคาคงที่แยกจากประมูล', '#buy'),
      rowLink('ประมูล', 'เฉพาะเซิร์ฟเวอร์ที่เปิดรายการ', '#auction'),
      rowLink('กล่องของ', 'รับของได้แม้ออฟไลน์', '#mailbox')));
  } else {
    page.append(el('p', { class: 'hint' }, 'เข้าสู่ระบบเพื่อดูเงิน ลงขาย และจองเขต'),
      el('div', { class: 'row', style: 'margin-top:24px' }, el('a', { class: 'btn primary', href: '#login' }, 'เข้าสู่ระบบ')));
  }
  view.append(page);
}
function rowLink(title, desc, href) {
  return el('a', { class: 'item', href },
    el('div', { class: 'item-main' }, el('span', { class: 'item-title' }, title), el('span', { class: 'item-meta' }, desc)),
    el('span', { class: 'mono muted' }, 'เปิด'));
}

/* ---------- map ---------- */
async function pageMap(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('map', 'แผนที่', 'ลากเพื่อเลื่อน แตะช่องเพื่อเลือก'));
  view.append(page);
  const bar = el('div', { class: 'mapbar' });
  const start = state.pos || { x: 0, z: 0 };
  const xInput = el('input', { class: 'input', type: 'text', inputmode: 'numeric', value: String(Math.floor(start.x / 500)), 'aria-label': 'พิกัด X' });
  const zInput = el('input', { class: 'input', type: 'text', inputmode: 'numeric', value: String(Math.floor(start.z / 500)), 'aria-label': 'พิกัด Z' });
  const canvas = el('canvas', { id: 'mapcanvas', width: '800', height: '500', tabindex: '0', 'aria-label': 'แผนที่เขต' });
  const info = el('div', { class: 'stack', style: 'margin-top:16px' });
  const zoomLabel = el('span', { class: 'mono muted' }, '100%');
  let zoom = 1;
  const cam = { x: start.x, z: start.z };
  let cells = {};
  let selected = null;
  let selectedDD = null;

  bar.append(
    el('div', { class: 'field' }, el('label', { for: 'mx' }, 'X'), xInput),
    el('div', { class: 'field' }, el('label', { for: 'mz' }, 'Z'), zInput),
    el('button', { class: 'btn secondary', type: 'button', onclick: () => { cam.x = num(xInput.value) * 500 + 250; cam.z = num(zInput.value) * 500 + 250; draw(); } }, 'ไป'),
    el('button', { class: 'btn secondary', type: 'button', 'aria-label': 'ซูมออก', title: 'ซูมออก', onclick: () => setZoom(zoom / 1.35) }, '−'),
    el('button', { class: 'btn secondary', type: 'button', 'aria-label': 'ซูมเข้า', title: 'ซูมเข้า', onclick: () => setZoom(zoom * 1.35) }, '+'),
    zoomLabel);
  page.append(bar, canvas, el('div', { class: 'legend' },
    el('span', {}, el('i', { class: 'own' }), 'ของคุณ'),
    el('span', {}, el('i', { class: 'other' }), 'ผู้อื่น'),
    el('span', {}, el('i', { class: 'sel' }), 'เลือกอยู่')), info);
  info.append(skeleton(2));

  const scale = () => 0.22 * zoom;
  const tiles = new Map();
  const dpr = () => window.devicePixelRatio || 1;
  function setZoom(next) {
    zoom = Math.max(0.6, Math.min(3.2, next));
    zoomLabel.textContent = Math.round(zoom * 100) + '%';
    draw();
  }

  function resize() {
    const w = canvas.clientWidth || 800;
    const h = canvas.clientHeight || 500;
    if (canvas.width !== Math.round(w * dpr()) || canvas.height !== Math.round(h * dpr())) {
      canvas.width = Math.round(w * dpr());
      canvas.height = Math.round(h * dpr());
    }
    draw();
  }
  function tile(cx, cz) {
    const key = cx + ',' + cz;
    if (tiles.has(key)) return tiles.get(key);
    tiles.set(key, null);
    fetch(`/api/map/${cx},${cz}.png`, { headers: authHeaders() })
      .then((res) => res.ok && res.status !== 204 ? res.blob() : null)
      .then((blob) => blob ? createImageBitmap(blob) : false)
      .then((image) => { tiles.set(key, image); draw(); })
      .catch(() => tiles.set(key, false));
    return null;
  }
  function draw() {
    if (!canvas.isConnected) return;
    const ctx = canvas.getContext('2d');
    const w = canvas.clientWidth;
    const h = canvas.clientHeight;
    const ratio = dpr();
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
    ctx.fillStyle = '#0A0A0A';
    ctx.fillRect(0, 0, w, h);
    const s = scale();
    const cell = 500;
    // The visible world span is pixels / scale, not pixels in blocks.
    const halfW = w / (2 * s);
    const halfH = h / (2 * s);
    const minX = Math.floor((cam.x - halfW) / cell) - 1;
    const maxX = Math.floor((cam.x + halfW) / cell) + 1;
    const minZ = Math.floor((cam.z - halfH) / cell) - 1;
    const maxZ = Math.floor((cam.z + halfH) / cell) + 1;
    for (let cx = minX; cx <= maxX; cx++) {
      for (let cz = minZ; cz <= maxZ; cz++) {
        const x = w / 2 + (cx * cell - cam.x) * s;
        const y = h / 2 + (cz * cell - cam.z) * s;
        const size = cell * s;
        const image = tile(cx, cz);
        ctx.fillStyle = '#111';
        ctx.fillRect(x, y, size, size);
        if (image) ctx.drawImage(image, x, y, size, size);
        const owner = cells[cx + ',' + cz];
        ctx.lineWidth = owner ? 1.5 : 1;
        ctx.strokeStyle = owner === state.me?.name ? '#FF4D00' : owner ? '#808080' : '#232323';
        ctx.strokeRect(x + 0.5, y + 0.5, size - 1, size - 1);
        if (cx + ',' + cz === selected) {
          ctx.lineWidth = 2;
          ctx.strokeStyle = '#FF4D00';
          ctx.strokeRect(x + 1, y + 1, size - 2, size - 2);
        }
        if (owner && size > 110) {
          ctx.fillStyle = '#F2F2F2';
          ctx.font = '11px "JetBrains Mono", monospace';
          ctx.fillText(owner.slice(0, Math.floor(size / 8)), x + 8, y + 20);
        }
      }
    }
  }
  function selectAt(clientX, clientY) {
    const rect = canvas.getBoundingClientRect();
    const s = scale();
    const wx = cam.x + (clientX - rect.left - rect.width / 2) / s;
    const wz = cam.z + (clientY - rect.top - rect.height / 2) / s;
    const cx = Math.floor(wx / 500);
    const cz = Math.floor(wz / 500);
    selected = cx + ',' + cz;
    draw();
    renderInfo();
  }
  function renderInfo() {
    closeOpenDD(false);
    clear(info);
    if (!selected) { info.append(el('p', { class: 'hint' }, 'เลือกช่องบนแผนที่เพื่อจองหรือโอน')); return; }
    const [cx, cz] = selected.split(',').map(Number);
    const owner = cells[selected];
    const mine = owner === state.me?.name;
    info.append(el('div', { class: 'item', style: 'border-top:0' },
      el('div', { class: 'item-main' },
        el('span', { class: 'item-title' }, `ช่อง ${selected}`),
        el('span', { class: 'item-meta' }, `${owner ? 'เจ้าของ ' + owner : 'ยังไม่มีเจ้าของ'} · X ${cx * 500}–${cx * 500 + 499} · Z ${cz * 500}–${cz * 500 + 499}`)),
      el('div', { class: 'item-side' },
        !owner ? el('button', { class: 'btn primary', type: 'button', onclick: () => claimCell(selected) }, 'จองช่องนี้') : null,
        (mine || state.me?.admin) && owner ? el('button', { class: 'btn danger', type: 'button', onclick: () => removeCell(selected) }, 'ปล่อยช่อง') : null)));
    if (owner && (mine || state.me?.admin)) {
      const form = el('div', { class: 'item-form' });
      const dd = dropdown({ label: 'โอนให้', options: (state.names || []).filter((n) => n !== owner), placeholder: 'เลือกผู้เล่น', searchable: true, onChange: () => {} });
      selectedDD = dd;
      form.append(dd.el, el('button', { class: 'btn secondary', type: 'button', onclick: () => transferCell(selected, dd.getValue()) }, 'โอนเขต'));
      info.append(form);
    }
  }
  async function reload() {
    try { cells = await api('GET', '/api/cells'); draw(); renderInfo(); }
    catch (error) { clear(info); info.append(errorState(error)); }
  }
  async function claimCell(id) {
    try { await api('POST', `/api/cells/${id}/claim`, {}); toast('จองช่องแล้ว'); await reload(); }
    catch (error) { toastError(error); }
  }
  async function removeCell(id) {
    if (!await confirmDialog({ title: 'ปล่อยช่อง ' + id, body: 'ของในเขตจะไม่ถูกลบ แต่พื้นที่จะกลับเป็นที่ว่าง', confirm: 'ปล่อยช่อง', danger: true })) return;
    try { await api('DELETE', `/api/cells/${id}`); toast('ปล่อยช่องแล้ว'); await reload(); }
    catch (error) { toastError(error); }
  }
  async function transferCell(id, to) {
    if (!to) { toast('เลือกผู้รับก่อน', 'error'); return; }
    if (!await confirmDialog({ title: 'โอนเขต ' + id, body: `โอนให้ ${to}`, confirm: 'โอนเขต' })) return;
    try { await api('POST', `/api/cells/${id}/transfer`, { to }); toast('โอนเขตแล้ว'); await reload(); }
    catch (error) { toastError(error); }
  }

  let down = null;
  canvas.addEventListener('pointerdown', (event) => {
    down = { x: event.clientX, y: event.clientY, cx: cam.x, cz: cam.z, moved: false };
    canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener('pointermove', (event) => {
    if (!down) return;
    const dx = event.clientX - down.x;
    const dy = event.clientY - down.y;
    if (Math.abs(dx) + Math.abs(dy) > 4) down.moved = true;
    cam.x = down.cx - dx / scale();
    cam.z = down.cz - dy / scale();
    draw();
  });
  canvas.addEventListener('pointerup', (event) => {
    if (down && !down.moved) selectAt(event.clientX, event.clientY);
    down = null;
    xInput.value = String(Math.floor(cam.x / 500));
    zInput.value = String(Math.floor(cam.z / 500));
  });
  canvas.addEventListener('pointercancel', () => { down = null; });

  if (!canvas.isConnected) return;
  const observer = new ResizeObserver(resize);
  observer.observe(canvas);
  mapCleanup = () => observer.disconnect();
  resize();
  state.names = state.names || await fetchNames();
  await reload();
  zoomLabel.textContent = '100%';
}

/* ---------- buy ---------- */
async function pageBuy(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('buy', 'ซื้อของ', 'เฉพาะของที่ผู้เล่นลงขาย'));
  view.append(page);
  await buyMarket(page);
}

async function buyMarket(panel) {
  const box = el('div', { class: 'stack', style: 'margin-top:24px' });
  const search = el('input', { class: 'input', type: 'text', placeholder: 'ค้นหาไอเท็มหรือผู้ขาย', 'aria-label': 'ค้นหาตลาด' });
  box.append(el('div', { class: 'field' }, el('label', { for: 'market-search' }, 'ค้นหา'), search));
  const listBox = el('div');
  box.append(listBox);
  panel.append(box);
  let listings = [];
  const labelOf = (l) => txt(l.label, txt(l.itemId, 'ไอเท็ม'));
  const sellerOf = (l) => txt(l.sellerName, 'ไม่ทราบชื่อ');
  function draw() {
    clear(listBox);
    const q = search.value.trim().toLowerCase();
    const rows = listings.filter((l) => !q || labelOf(l).toLowerCase().includes(q) || sellerOf(l).toLowerCase().includes(q));
    if (!rows.length) { listBox.append(emptyState('ยังไม่มีของลงขาย', 'ผู้เล่นสามารถลงขายได้ที่หน้าขาย')); return; }
    const rowsEl = el('div', { class: 'rows' });
    for (const l of rows) {
      rowsEl.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' },
          itemHeading(l.itemId, `${labelOf(l)} ×${num(l.count)}`),
          el('span', { class: 'item-meta' }, `ผู้ขาย ${sellerOf(l)} · ${clock(l.createdAt)}`)),
        el('div', { class: 'item-side' },
          el('span', { class: 'price' }, money(l.price)),
          l.canBuy ? el('button', { class: 'btn primary', type: 'button', onclick: () => buy(l) }, 'ซื้อ') :
            l.mine ? el('button', { class: 'btn danger', type: 'button', onclick: () => cancel(l) }, 'ยกเลิก') :
              el('span', { class: 'mono muted' }, 'ปิดแล้ว'))));
    }
    listBox.append(rowsEl);
  }
  async function buy(l) {
    if (!await confirmDialog({ title: 'ซื้อ ' + labelOf(l), body: `จ่าย ${money(l.price)} ${txt(state.currency, 'เหรียญ')} ให้ ${sellerOf(l)}`, confirm: 'ซื้อ' })) return;
    try {
      const data = await api('POST', `/api/market/${l.id}/buy`, {});
      toast('ซื้อแล้ว ของอยู่ในกล่องของ');
      state.balance = data.balance;
      state.mail = data.mailboxCount;
      renderShell();
      await load();
    } catch (error) { toastError(error); }
  }
  async function cancel(l) {
    if (!await confirmDialog({ title: 'ยกเลิกรายการ', body: `${labelOf(l)} ×${num(l.count)} จะถูกส่งเข้ากล่องของ`, confirm: 'ยืนยัน', danger: true })) return;
    try { await api('POST', `/api/market/${l.id}/cancel`, {}); toast('ยกเลิกแล้ว'); await load(); }
    catch (error) { toastError(error); }
  }
  async function load() {
    loading(listBox);
    try { const data = await api('GET', '/api/market'); listings = data.listings || []; clear(listBox); draw(); }
    catch (error) { clear(listBox); listBox.append(errorState(error)); }
  }
  search.addEventListener('input', draw);
  await load();
}

/* ---------- sell ---------- */
async function pageSell(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('sell', 'ขายของ', 'ดึงของจากกระเป๋า Ender Chest หรือกล่องในเขตได้แม้ออฟไลน์'));
  view.append(page);
  tabbed(page, [{ id: 'list', label: 'ลงขาย' }, { id: 'consign', label: 'ฝากเซิร์ฟเวอร์' }, { id: 'shop', label: 'ขายร้าน' }], state.sellTab, (panel, id) => { state.sellTab = id; id === 'list' ? sellList(panel) : id === 'consign' ? sellConsign(panel) : sellShop(panel); });
}

async function sourcePicker(options = {}) {
  const box = el('div', { class: 'stack' });
  box.append(skeleton(2));
  try {
    let data;
    try {
      data = await api('GET', '/api/sources');
    } catch (error) {
      // The live scan needs the game thread; fall back to the saved JSON
      // projection so the player can still see where their items are.
      if (error.status !== 503 && error.status !== 404) throw error;
      data = await api('GET', '/api/items');
      data.offline = data.source === 'offline';
      data.note = 'แสดงจากแคช player-items (สแกนสดไม่พร้อม)';
    }
    const items = data.items || [];
    clear(box);
    if (!items.length) {
      box.append(emptyState('ไม่พบของที่ขายได้', 'ของต้องอยู่ในกระเป๋า Ender Chest หรือกล่องในเขตที่คุณเป็นเจ้าของ'));
      return { el: box, items: [], getValue: () => '', getItem: () => null };
    }

    let selected = null;
    let limit = 120;
    const labelOf = (item) => txt(item.label, txt(item.itemId, 'ไอเท็ม'));
    const search = el('input', { class: 'input', type: 'text', placeholder: 'ค้นหาชื่อ ไอเท็ม หรือตำแหน่ง', 'aria-label': 'ค้นหาไอเท็ม' });
    const grid = el('div', { class: 'pick-grid', role: 'listbox', 'aria-label': options.label || 'ไอเท็ม' });
    const more = el('button', { class: 'btn secondary block', type: 'button', hidden: true }, 'แสดงเพิ่ม');
    const detail = el('div', { class: 'pick-detail', 'aria-live': 'polite' });
    const countLine = el('p', { class: 'hint' });

    function where(item) {
      let place = item.area || '';
      if (item.container === 'inventory') place = 'กระเป๋า';
      else if (item.container === 'ender_chest') place = 'Ender Chest';
      if (item.x != null && item.y != null && item.z != null) place += ` · ${item.x}, ${item.y}, ${item.z}`;
      if (item.slot != null && item.slot >= 0) place += ` · ช่อง ${Number(item.slot) + 1}`;
      return place;
    }
    function renderDetail(item) {
      clear(detail);
      if (!item) { detail.append(el('p', { class: 'hint' }, 'เลือกไอเท็มจากช่องด้านบน')); return; }
      detail.append(itemIcon(item.itemId, 'lg'),
        el('div', { class: 'item-main' },
          el('span', { class: 'item-title' }, `${labelOf(item)} ×${num(item.count)}`),
          el('span', { class: 'item-meta mono' }, txt(item.itemId, 'unknown')),
          el('span', { class: 'pick-pos' }, where(item))),
        el('span', { class: 'mono muted' }, item.dimension || ''));
    }
    function select(item) {
      selected = item;
      $$('.pick-cell', grid).forEach((cell) => cell.setAttribute('aria-selected', String(cell.dataset.ref === item.ref)));
      renderDetail(item);
      if (options.onChange) options.onChange(item);
    }
    function draw() {
      clear(grid);
      const q = search.value.trim().toLowerCase();
      const filtered = items.filter((item) => !q || labelOf(item).toLowerCase().includes(q) || txt(item.itemId, '').toLowerCase().includes(q) || txt(item.area, '').toLowerCase().includes(q));
      const visible = filtered.slice(0, limit);
      for (const item of visible) {
        const cell = el('button', {
          class: 'pick-cell', type: 'button', role: 'option', 'data-ref': item.ref,
          'aria-selected': String(selected?.ref === item.ref),
          'aria-label': `${labelOf(item)} จำนวน ${num(item.count)} ${where(item)}`,
          title: `${labelOf(item)} ×${num(item.count)} · ${where(item)}`,
          onclick: () => select(item),
        }, itemIcon(item.itemId), el('span', { class: 'pick-count' }, '×' + num(item.count)),
          el('span', { class: 'sr-only' }, labelOf(item)));
        grid.append(cell);
      }
      if (!visible.length) grid.append(el('div', { class: 'dd-empty', style: 'grid-column:1/-1' }, 'ไม่พบไอเท็มที่ตรงกับคำค้น'));
      more.hidden = filtered.length <= limit;
      const stamp = data.updatedAt ? `แคช ${shortClock(data.updatedAt)}` : '';
      countLine.textContent = [`แสดง ${visible.length} จาก ${filtered.length} ช่อง`, data.offline ? 'ออฟไลน์' : '', stamp, data.note || ''].filter(Boolean).join(' · ');
    }

    search.addEventListener('input', () => { limit = 120; draw(); });
    more.addEventListener('click', () => { limit += 120; draw(); });
    box.append(el('div', { class: 'field' }, el('label', {}, options.label || 'ไอเท็ม'), search), grid, more, detail, countLine);
    renderDetail(null);
    draw();
    return { el: box, items, getValue: () => selected?.ref || '', getItem: () => selected };
  } catch (error) {
    clear(box); box.append(errorState(error));
    return { el: box, items: [], getValue: () => '', getItem: () => null };
  }
}

async function sellList(panel) {
  const formBox = el('div', { class: 'stack', style: 'margin-top:24px' });
  panel.append(formBox);
  const count = el('input', { class: 'input', type: 'text', min: '1', value: '1', inputmode: 'numeric' });
  const picker = await sourcePicker({ onChange: (item) => { count.value = '1'; count.max = String(item.count); } });
  formBox.append(picker.el);
  if (!picker.items.length) return;
  const price = el('input', { class: 'input', type: 'text', min: '1', inputmode: 'numeric', placeholder: 'ราคารวม' });
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'row sticky-actions', novalidate: true },
    el('div', { class: 'field' }, el('label', {}, 'จำนวน'), count),
    el('div', { class: 'field' }, el('label', {}, 'ราคารวม'), price),
    el('div', { class: 'field shrink' }, el('button', { class: 'btn primary', type: 'submit' }, 'ลงขาย')));
  formBox.append(form, error);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    error.textContent = '';
    const ref = picker.getValue();
    if (!ref) { error.textContent = 'เลือกไอเท็มก่อน'; return; }
    if (num(count.value) < 1 || num(price.value) < 1) { error.textContent = 'จำนวนและราคาต้องมากกว่า 0'; return; }
    const button = form.querySelector('button');
    button.disabled = true;
    try {
      await api('POST', '/api/market', { ref, count: num(count.value), price: num(price.value) });
      toast('ลงขายแล้ว');
      await refreshWallet();
      panel.replaceChildren();
      sellList(panel);
    } catch (err) { error.textContent = errText(err); }
    finally { button.disabled = false; }
  });

  const mineBox = el('div', { class: 'stack', style: 'margin-top:48px' });
  mineBox.append(el('div', { class: 'section-label' }, 'รายการของฉัน'));
  const mineList = el('div');
  mineBox.append(mineList);
  formBox.append(mineBox);
  loading(mineList);
  try {
    const data = await api('GET', '/api/market');
    clear(mineList);
    const mine = data.mine || [];
    if (!mine.length) { mineList.append(emptyState('ยังไม่มีรายการ', 'รายการที่ลงขายจะแสดงที่นี่')); return; }
    const rows = el('div', { class: 'rows' });
    for (const l of mine) {
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, itemHeading(l.itemId, `${txt(l.label, txt(l.itemId, 'ไอเท็ม'))} ×${num(l.count)}`), el('span', { class: 'item-meta' }, `${txt(l.status, 'ไม่ทราบสถานะ')} · ${clock(l.createdAt)}`)),
        el('div', { class: 'item-side' }, el('span', { class: 'price' }, money(l.price)),
          l.canCancel ? el('button', { class: 'btn danger', type: 'button', onclick: async () => {
            if (!await confirmDialog({ title: 'ยกเลิกรายการ', body: 'ของจะถูกส่งเข้ากล่อง', confirm: 'ยืนยัน', danger: true })) return;
            try { await api('POST', `/api/market/${l.id}/cancel`, {}); toast('ยกเลิกแล้ว'); panel.replaceChildren(); sellList(panel); }
            catch (error) { toastError(error); }
          } }, 'ยกเลิก') : null)));
    }
    mineList.append(rows);
  } catch (error) { clear(mineList); mineList.append(errorState(error)); }
}

async function sellConsign(panel) {
  const box = el('div', { class: 'stack', style: 'margin-top:24px' });
  panel.append(box);
  box.append(el('p', { class: 'hint' }, 'ส่งของให้เซิร์ฟเวอร์เพื่อให้แอดมินนำขึ้นประมูล ของจะรอในคลังฝากจนกว่าจะถูกใช้'));
  const count = el('input', { class: 'input', type: 'text', min: '1', value: '1', inputmode: 'numeric' });
  const picker = await sourcePicker({ onChange: (item) => { count.value = '1'; count.max = String(item.count); } });
  box.append(picker.el);
  if (!picker.items.length) return;
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'row', novalidate: true },
    el('div', { class: 'field' }, el('label', {}, 'จำนวน'), count),
    el('div', { class: 'field shrink' }, el('button', { class: 'btn primary', type: 'submit' }, 'ฝากให้เซิร์ฟเวอร์')));
  box.append(form, error);
  const pendingBox = el('div', { class: 'stack', style: 'margin-top:32px' });
  pendingBox.append(el('div', { class: 'section-label' }, 'ของที่ฝากไว้'));
  const pending = el('div');
  pendingBox.append(pending);
  box.append(pendingBox);

  async function reloadPending() {
    loading(pending);
    try {
      const data = await api('GET', '/api/consign');
      clear(pending);
      const entries = data.entries || [];
      if (!entries.length) { pending.append(emptyState('ยังไม่มีของฝาก', 'ของที่ส่งให้เซิร์ฟเวอร์จะแสดงที่นี่')); return; }
      const rows = el('div', { class: 'rows' });
      for (const e of entries) rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, itemHeading(e.itemId, `${txt(e.label, txt(e.itemId, 'ไอเท็ม'))} ×${num(e.count)}`), el('span', { class: 'item-meta' }, 'ฝากเมื่อ ' + clock(e.t))),
        el('span', { class: 'mono muted' }, 'รอแอดมิน')));
      pending.append(rows);
    } catch (error) { clear(pending); pending.append(errorState(error)); }
  }
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    error.textContent = '';
    const ref = picker.getValue();
    if (!ref) { error.textContent = 'เลือกไอเท็มก่อน'; return; }
    if (num(count.value) < 1) { error.textContent = 'จำนวนไม่ถูกต้อง'; return; }
    const button = form.querySelector('button');
    button.disabled = true;
    try { await api('POST', '/api/consign', { ref, count: num(count.value) }); toast('ฝากของแล้ว'); panel.replaceChildren(); sellConsign(panel); }
    catch (err) { error.textContent = errText(err); }
    finally { button.disabled = false; }
  });
  await reloadPending();
}

async function sellShop(panel) {
  const box = el('div', { class: 'stack', style: 'margin-top:24px' });
  panel.append(box);
  box.append(el('p', { class: 'hint' }, 'ขายคืนร้านค้ากลางในราคาที่ระบบกำหนด'));
  const count = el('input', { class: 'input', type: 'text', min: '1', value: '1', inputmode: 'numeric' });
  const picker = await sourcePicker({ label: 'ขายจาก', onChange: (item) => { count.value = '1'; count.max = String(item.count); } });
  box.append(picker.el);
  if (!picker.items.length) return;
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'row', novalidate: true },
    el('div', { class: 'field' }, el('label', {}, 'จำนวน'), count),
    el('div', { class: 'field shrink' }, el('button', { class: 'btn primary', type: 'submit' }, 'ขาย')));
  box.append(form, error);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    error.textContent = '';
    const ref = picker.getValue();
    if (!ref) { error.textContent = 'เลือกไอเท็มก่อน'; return; }
    if (num(count.value) < 1) { error.textContent = 'จำนวนไม่ถูกต้อง'; return; }
    const item = picker.getItem();
    const button = form.querySelector('button');
    button.disabled = true;
    try {
      const data = await api('POST', '/api/shop/sell', { item: item.itemId, qty: num(count.value), ref });
      toast(`ขายได้ ${money(data.earned)} ${state.currency}`);
      state.balance = data.balance;
      renderShell();
      panel.replaceChildren();
      sellShop(panel);
    } catch (err) { error.textContent = errText(err); }
    finally { button.disabled = false; }
  });
}

/* ---------- auction ---------- */
async function pageAuction(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('auction', 'ประมูล', 'ไอเท็มและเขตจากเซิร์ฟเวอร์ ผู้เล่นร่วมประมูลได้'));
  view.append(page);
  tabbed(page, [{ id: 'active', label: 'กำลังประมูล' }, { id: 'mine', label: 'ของฉัน' }], state.auctionTab, (panel, id) => { state.auctionTab = id; auctionList(panel, id === 'mine'); });
}

async function auctionList(panel, mineOnly) {
  const box = el('div', { class: 'stack', style: 'margin-top:24px' });
  panel.append(box);
  loading(box);
  try {
    const data = await api('GET', '/api/auctions');
    clear(box);
    const list = mineOnly ? (data.mine || []) : (data.auctions || []);
    if (!list.length) { box.append(emptyState(mineOnly ? 'ยังไม่มีประวัติ' : 'ยังไม่มีรายการประมูล', 'แอดมินเป็นผู้เปิดรายการจากของที่ผู้เล่นฝากไว้')); return; }
    const rows = el('div', { class: 'rows' });
    const live = [];
    for (const a of list) {
      const isCell = a.kind === 'CELL';
      const open = auctionOpen(a);
      const bid = el('input', { class: 'input', type: 'text', min: String(a.minBid || a.startPrice), value: String(a.minBid || a.startPrice), inputmode: 'numeric', 'aria-label': isCell ? 'ราคาประมูลเขต' : 'ราคาประมูล' });
      const main = isCell
        ? el('div', { class: 'item-main' },
            el('span', { class: 'item-title' }, 'เขต ' + txt(a.cellId, '-')),
            el('span', { class: 'item-meta' }, `เจ้าของปัจจุบัน ${txt(a.cellOwnerName, 'ที่ว่าง')} · ผู้ชนะจะได้รับกรรมสิทธิ์เขต`))
        : itemHeading(a.itemId, `${txt(a.label, txt(a.itemId, 'ไอเท็ม'))} ×${num(a.count)}`);
      const timingEl = el('span', { class: 'mono' }, auctionTiming(a));
      main.append(el('span', { class: 'item-meta' }, `ผู้ขาย ${txt(a.sellerName, 'ไม่ทราบชื่อ')} · เริ่ม ${money(a.startPrice)} · สูงสุด ${a.highBid ? money(a.highBid) + ' โดย ' + txt(a.highBidderName, '-') : 'ยังไม่มี'} · `, timingEl));
      const side = el('div', { class: 'item-side' });
      const controls = [];
      if (a.buyout) side.append(el('span', { class: 'mono muted' }, 'ซื้อทันที ' + money(a.buyout)));
      if (a.canBid && open) {
        const bidBtn = el('button', { class: 'btn primary', type: 'button', onclick: () => placeBid(a, num(bid.value)) }, 'เสนอ');
        controls.push(bid, bidBtn);
        side.append(el('div', { class: 'item-form' },
          el('div', { class: 'field' }, el('label', {}, isCell ? 'ประมูลเขต' : 'ประมูล'), bid),
          bidBtn));
      }
      if (a.canBuyout && open) {
        const buyBtn = el('button', { class: 'btn secondary', type: 'button', onclick: () => buyout(a) }, 'ซื้อทันที');
        controls.push(buyBtn);
        side.append(buyBtn);
      }
      if (a.highest) side.append(el('span', { class: 'mono muted' }, 'คุณเป็นผู้นำ'));
      if (a.canCancel && open) side.append(el('button', { class: 'btn danger', type: 'button', onclick: () => cancelAuction(a) }, 'ยกเลิก'));
      const stateEl = el('span', { class: 'mono muted' });
      if (a.status === 'ACTIVE' && (!open || (!controls.length && !a.highest && !a.canCancel))) {
        stateEl.textContent = !open ? 'หมดเวลา · รอสรุปผล' : a.mine && a.highBid ? 'มีผู้ประมูลแล้ว' : a.mine ? 'ของคุณ' : 'รอผล';
        side.append(stateEl);
      }
      if (a.status !== 'ACTIVE') side.append(el('span', { class: 'mono muted' }, a.mine ? 'ของคุณ' : 'ปิดแล้ว'));
      rows.append(el('div', { class: 'item' }, main, side));
      if (a.status === 'ACTIVE') live.push({ a, timingEl, controls, side, stateEl, closed: !open });
    }
    box.append(rows);
    startAuctionClock(box, live);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

/* Client-side guard: an auction only accepts bids while ACTIVE and before endsAt. */
function auctionOpen(a) {
  return a.status === 'ACTIVE' && Number(a.endsAt) > Date.now();
}
function auctionTiming(a) {
  if (a.status !== 'ACTIVE') return txt(a.status, 'ปิดแล้ว');
  const d = Number(a.endsAt) - Date.now();
  if (d <= 0) return 'หมดเวลาแล้ว';
  const s = Math.floor(d / 1000);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
  const pad = (n) => String(n).padStart(2, '0');
  return 'เหลือ ' + (h >= 48 ? `${Math.floor(h / 24)} วัน` : `${pad(h)}:${pad(m)}:${pad(sec)}`);
}
let auctionClock = null;
function startAuctionClock(box, live) {
  if (auctionClock) clearInterval(auctionClock);
  auctionClock = null;
  if (!live.length) return;
  let refreshed = false;
  auctionClock = setInterval(() => {
    if (!box.isConnected) { clearInterval(auctionClock); auctionClock = null; return; }
    let anyJustClosed = false;
    for (const row of live) {
      row.timingEl.textContent = auctionTiming(row.a);
      if (!row.closed && !auctionOpen(row.a)) {
        row.closed = true;
        anyJustClosed = true;
        for (const c of row.controls) { c.disabled = true; c.remove(); }
        for (const f of row.side.querySelectorAll('.item-form, .btn')) f.remove();
        row.stateEl.textContent = 'หมดเวลา · รอสรุปผล';
        if (!row.stateEl.isConnected) row.side.append(row.stateEl);
      }
    }
    // Re-fetch once shortly after an auction ends so the server's settlement shows up.
    if (anyJustClosed && !refreshed) {
      refreshed = true;
      setTimeout(() => { if (box.isConnected) render(); }, 3000);
    }
  }, 1000);
}

async function placeBid(a, amount) {
  if (!a.canBid || !auctionOpen(a)) { toast('รายการนี้หมดเวลาประมูลแล้ว', 'error'); return; }
  if (amount < 1) { toast('จำนวนไม่ถูกต้อง', 'error'); return; }
  try {
    const data = await api('POST', `/api/auctions/${a.id}/bid`, { amount });
    toast(`ประมูล ${money(data.highBid)} แล้ว · รายการยังเปิดจนหมดเวลา`);
    state.balance = data.balance; renderShell(); render();
  } catch (error) { toastError(error); }
}
async function buyout(a) {
  if (!a.canBuyout || !auctionOpen(a)) { toast('รายการนี้หมดเวลาประมูลแล้ว', 'error'); return; }
  if (!await confirmDialog({ title: 'ซื้อทันที', body: `จ่าย ${money(a.buyout)} ${state.currency}`, confirm: 'ซื้อทันที' })) return;
  try { const data = await api('POST', `/api/auctions/${a.id}/buyout`, {}); toast('ซื้อแล้ว ของอยู่ในกล่อง'); state.balance = data.balance; state.mail = data.mailboxCount; renderShell(); render(); }
  catch (error) { toastError(error); }
}
async function cancelAuction(a) {
  if (!await confirmDialog({ title: 'ยกเลิกประมูล', body: 'ของจะถูกส่งคืน', confirm: 'ยืนยัน', danger: true })) return;
  try { await api('POST', `/api/auctions/${a.id}/cancel`, {}); toast('ยกเลิกแล้ว'); render(); }
  catch (error) { toastError(error); }
}

/* ---------- wallet ---------- */
async function pageWallet(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('wallet', 'เงิน', 'โอนและดูประวัติการเงิน'));
  view.append(page);
  const top = el('div', { class: 'stack', style: 'margin-top:24px' });
  const historyBox = el('div', { class: 'stack', style: 'margin-top:48px' });
  historyBox.append(el('div', { class: 'section-label' }, 'ประวัติ'));
  const historyList = el('div');
  historyBox.append(historyList);
  page.append(top, historyBox);

  const names = await fetchNames();
  const dd = dropdown({ label: 'ผู้รับ', options: names.filter((n) => n !== state.me.name), placeholder: 'เลือกผู้เล่น', searchable: true, name: 'to' });
  const amount = el('input', { class: 'input', type: 'text', min: '1', inputmode: 'numeric', placeholder: 'จำนวนเงิน' });
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'row sticky-actions', novalidate: true },
    dd.el,
    el('div', { class: 'field' }, el('label', {}, 'จำนวน'), amount),
    el('div', { class: 'field shrink' }, el('button', { class: 'btn primary', type: 'submit' }, 'โอน')));
  top.append(form, error);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    error.textContent = '';
    if (!dd.getValue()) { error.textContent = 'เลือกผู้รับ'; return; }
    if (num(amount.value) < 1) { error.textContent = 'จำนวนไม่ถูกต้อง'; return; }
    if (!await confirmDialog({ title: 'ยืนยันการโอน', body: `โอน ${money(num(amount.value))} ${state.currency} ให้ ${dd.getValue()}`, confirm: 'โอน' })) return;
    try {
      const data = await api('POST', '/api/pay', { to: dd.getValue(), amount: num(amount.value) });
      state.balance = data.balance; renderShell(); toast('โอนแล้ว'); render();
    } catch (err) { error.textContent = errText(err); }
  });

  loading(historyList);
  try {
    const data = await api('GET', '/api/wallet');
    state.balance = data.balance; state.currency = data.currency; state.mail = data.mailboxCount; renderShell();
    clear(historyList);
    const history = data.history || [];
    if (!history.length) { historyList.append(emptyState('ยังไม่มีรายการ', 'การซื้อ ขาย และโอนจะแสดงที่นี่')); return; }
    const rows = el('div', { class: 'rows' });
    for (const t of history) {
      const inbound = t.dir === 'in';
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, el('span', { class: 'item-title' }, `${inbound ? '+' : '−'}${money(t.amount)} ${txt(data.currency, 'เหรียญ')}`), el('span', { class: 'item-meta' }, `${txt(t.reason, 'โอน')} · ${txt(t.other, '')} · ${clock(t.t)}`)),
        el('span', { class: 'mono muted' }, inbound ? 'เข้า' : 'ออก')));
    }
    historyList.append(rows);
  } catch (error) { clear(historyList); historyList.append(errorState(error)); }
}

/* ---------- mailbox ---------- */
async function pageMailbox(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('mailbox', 'กล่องของ', 'รับของเข้าตัวได้แม้ผู้เล่นออฟไลน์'));
  view.append(page);
  const box = el('div', { style: 'margin-top:24px' });
  const claim = el('button', { class: 'btn primary', type: 'button' }, 'รับทั้งหมด');
  page.append(claim, box);
  async function load() {
    loading(box);
    try {
      const data = await api('GET', '/api/mailbox');
      state.mail = data.count || 0; renderShell();
      clear(box);
      const entries = data.entries || [];
      if (!entries.length) { box.append(emptyState('กล่องว่าง', 'ของที่ซื้อหรือชนะประมูลจะมาที่นี่')); claim.disabled = true; return; }
      claim.disabled = false;
      const rows = el('div', { class: 'rows' });
      for (const entry of entries) rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, itemHeading(entry.itemId, `${txt(entry.label, txt(entry.itemId, 'ไอเท็ม'))} ×${num(entry.count)}`), el('span', { class: 'item-meta' }, `${txt(entry.from, 'ระบบ')} · ${clock(entry.t)}`)),
        el('span', { class: 'mono muted' }, entry.id)));
      box.append(rows);
    } catch (error) { clear(box); box.append(errorState(error)); }
  }
  claim.addEventListener('click', async () => {
    claim.disabled = true;
    try { const data = await api('POST', '/api/mailbox/claim', {}); toast(`รับ ${data.claimed} รายการ${data.offline ? ' (โหมดออฟไลน์)' : ''}`); await load(); }
    catch (error) { toastError(error); claim.disabled = false; }
  });
  await load();
}

/* ---------- about ---------- */
function pageAbout(view) {
  const page = el('div', { class: 'page' });
  page.append(pageHead('about', 'ช่วยเหลือ', 'คำสั่งและกติกาหลัก'));
  const commands = [
    ['/claim', 'จองช่องที่ยืนอยู่ เปิดเมนูเขต'],
    ['/claimmap', 'เปิดลิงก์เว็บและสถานะเขต'],
    ['/ah', 'เปิดรายการประมูล'],
    ['/pay <ชื่อ> <จำนวน>', 'โอนเงินให้ผู้เล่น'],
    ['/mail', 'เปิดกล่องของ'],
    ['/shop', 'เปิดร้านค้ากลาง'],
  ];
  const rows = el('div', { class: 'rows' });
  for (const [cmd, desc] of commands) rows.append(el('div', { class: 'item' },
    el('div', { class: 'item-main' }, el('span', { class: 'item-title mono' }, cmd), el('span', { class: 'item-meta' }, desc))));
  page.append(rows, el('p', { class: 'hint', style: 'margin-top:32px' }, 'ตลาดผู้เล่นกับประมูลแยกกัน: ผู้เล่นลงขายราคาคงที่ได้เอง ส่วนประมูลเปิดโดยเซิร์ฟเวอร์จากของที่ผู้เล่นฝากไว้เท่านั้น'));
  view.append(page);
}

/* ---------- admin ---------- */
async function pageAdmin(view) {
  if (!state.me?.admin) { view.append(el('div', { class: 'page' }, pageHead('admin', 'ไม่มีสิทธิ์', 'หน้านี้สำหรับแอดมิน'), el('a', { class: 'btn secondary', href: '#home' }, 'กลับ'))); return; }
  const page = el('div', { class: 'page' });
  page.append(pageHead('admin', 'แอดมิน', 'จัดการเศรษฐกิจ เขต และรายการประมูล'));
  view.append(page);
  const tabs = [
    ['overview', 'ภาพรวม'], ['money', 'เงิน'], ['prices', 'ราคา'], ['auctions', 'ประมูล'], ['cellAuctions', 'ประมูลเขต'], ['consign', 'ของฝาก'], ['cells', 'เขต'], ['accounts', 'บัญชี'], ['worlds', 'โลก'], ['audit', 'บันทึก'],
  ];
  tabbed(page, tabs.map(([id, label]) => ({ id, label })), state.adminTab, (panel, id) => {
    state.adminTab = id;
    adminPanel(panel, id);
  });
}

function adminPanel(panel, id) {
  const box = el('div', { class: 'stack', style: 'margin-top:24px' });
  panel.append(box);
  if (id === 'overview') adminOverview(box);
  else if (id === 'money') adminMoney(box);
  else if (id === 'prices') adminPrices(box);
  else if (id === 'auctions') adminAuctions(box);
  else if (id === 'cellAuctions') adminCellAuctions(box);
  else if (id === 'consign') adminConsign(box);
  else if (id === 'cells') adminCells(box);	else if (id === 'accounts') adminAccounts(box);
	else if (id === 'worlds') adminWorlds(box);
	else adminAudit(box);
}

async function adminOverview(box) {
  loading(box);
  try {
    const data = await api('GET', '/api/admin/overview');
    clear(box);
    const t = data.totals || {}, m = data.market || {};
    box.append(el('div', { class: 'stats' },
      el('div', { class: 'stat' }, el('strong', {}, money(t.balances)), el('span', {}, 'ยอดผู้เล่น')),
      el('div', { class: 'stat' }, el('strong', {}, money(t.escrow)), el('span', {}, 'เงินพัก')),
      el('div', { class: 'stat' }, el('strong', {}, money(t.systemNet)), el('span', {}, 'ระบบสุทธิ')),
      el('div', { class: 'stat' }, el('strong', {}, t.invariantOk ? 'ตรง' : 'ไม่ตรง'), el('span', {}, 'สมดุลบัญชี'))));
    const status = el('div', { class: 'rows', style: 'margin-top:32px' });
    for (const [label, value] of [
      ['เศรษฐกิจ', m.economyDisabled ? 'ปิด' : 'ทำงาน'],
      ['ร้านค้า', m.shopDisabled ? 'ปิด' : 'ทำงาน'],
      ['ประมูล', m.auctionsDisabled ? 'ปิด' : 'ทำงาน'],
      ['ราคากลาง', m.pricesStale ? 'เก่า' : 'สดใหม่'],
    ]) status.append(el('div', { class: 'item' }, el('div', { class: 'item-main' }, el('span', { class: 'item-title' }, label)), el('span', { class: 'mono muted' }, value)));
    box.append(status);
    box.append(el('button', { class: 'btn secondary', type: 'button', onclick: async () => {
      try { const r = await api('POST', '/api/admin/config/reload', {}); toast(r.restartNeeded ? 'โหลดแล้ว · ต้องรีสตาร์ทพอร์ต' : 'โหลด config แล้ว'); }
      catch (error) { toastError(error); }
    } }, 'โหลด config ใหม่'));
    const players = data.players || [];
    const list = el('div', { class: 'rows', style: 'margin-top:32px' });
    for (const p of players) list.append(el('div', { class: 'item' },
      el('div', { class: 'item-main' }, el('span', { class: 'item-title' }, txt(p.name, 'ไม่ทราบชื่อ')), el('span', { class: 'item-meta' }, `${p.online ? 'ออนไลน์' : 'ออฟไลน์'}${p.frozen ? ' · ถูกระงับ' : ''} · กล่อง ${num(p.mailboxCount)}`)),
      el('span', { class: 'price' }, money(p.balance))));
    box.append(el('div', { class: 'section-label' }, 'ผู้เล่น'), list);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

async function adminMoney(box) {
  const names = await fetchNames();
  const player = dropdown({ label: 'ผู้เล่น', options: names, placeholder: 'เลือกผู้เล่น', searchable: true, name: 'player' });
  const op = dropdown({ label: 'การกระทำ', options: [{ value: 'give', label: 'เพิ่ม' }, { value: 'take', label: 'ลด' }, { value: 'set', label: 'ตั้งเป็น' }], value: 'give', name: 'op' });
  const amount = el('input', { class: 'input', type: 'text', min: '0', inputmode: 'numeric' });
  const reason = el('input', { class: 'input', type: 'text', maxLength: '200', placeholder: 'เหตุผล 3–200 ตัวอักษร' });
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'stack', novalidate: true },
    el('div', { class: 'grid2' }, player.el, op.el),
    el('div', { class: 'grid2' }, el('div', { class: 'field' }, el('label', {}, 'จำนวน'), amount), el('div', { class: 'field' }, el('label', {}, 'เหตุผล'), reason)),
    error,
    el('button', { class: 'btn primary', type: 'submit' }, 'บันทึก'));
  form.addEventListener('submit', async (event) => {
    event.preventDefault(); error.textContent = '';
    if (!player.getValue()) { error.textContent = 'เลือกผู้เล่น'; return; }
    if (reason.value.trim().length < 3) { error.textContent = 'ต้องมีเหตุผลอย่างน้อย 3 ตัวอักษร'; return; }
    try { const data = await api('POST', '/api/admin/money', { player: player.getValue(), op: op.getValue(), amount: num(amount.value), reason: reason.value.trim() }); toast('บันทึกแล้ว · ยอดใหม่ ' + money(data.balance)); form.reset(); }
    catch (err) { error.textContent = errText(err); }
  });
  box.append(form);
}

function adminPrices(box) {
  const item = el('input', { class: 'input', type: 'text', placeholder: 'minecraft:stone' });
  const base = el('input', { class: 'input', type: 'text', inputmode: 'numeric', placeholder: 'เว้นว่างเพื่อล้าง' });
  const reason = el('input', { class: 'input', type: 'text', maxLength: '200', placeholder: 'เหตุผล 3–200 ตัวอักษร' });
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'stack', novalidate: true },
    el('div', { class: 'field' }, el('label', {}, 'ไอเท็ม'), item),
    el('div', { class: 'grid2' }, el('div', { class: 'field' }, el('label', {}, 'ราคาฐาน'), base), el('div', { class: 'field' }, el('label', {}, 'เหตุผล'), reason)),
    error,
    el('div', { class: 'row' },
      el('button', { class: 'btn primary', type: 'submit' }, 'ตั้งราคา'),
      el('button', { class: 'btn secondary', type: 'button', onclick: () => priceAction(true) }, 'บล็อก'),
      el('button', { class: 'btn secondary', type: 'button', onclick: () => priceAction(false) }, 'ปลดบล็อก'),
      el('button', { class: 'btn secondary', type: 'button', onclick: refreshPrices }, 'ดึงราคาใหม่')));
  async function priceAction(blocked) {
    error.textContent = '';
    if (reason.value.trim().length < 3) { error.textContent = 'ต้องมีเหตุผลอย่างน้อย 3 ตัวอักษร'; return; }
    try { await api('POST', '/api/admin/prices/block', { item: item.value.trim(), blocked, reason: reason.value.trim() }); toast(blocked ? 'บล็อกแล้ว' : 'ปลดบล็อกแล้ว'); }
    catch (err) { error.textContent = errText(err); }
  }
  async function refreshPrices() {
    try { const data = await api('POST', '/api/admin/prices/refresh', {}); toast(data.ok ? 'ดึงราคาแล้ว' : data.message || 'ดึงไม่สำเร็จ'); }
    catch (err) { toastError(err); }
  }
  form.addEventListener('submit', async (event) => {
    event.preventDefault(); error.textContent = '';
    if (reason.value.trim().length < 3) { error.textContent = 'ต้องมีเหตุผลอย่างน้อย 3 ตัวอักษร'; return; }
    try { await api('POST', '/api/admin/prices/override', { item: item.value.trim(), base: base.value === '' ? null : num(base.value), reason: reason.value.trim() }); toast('ตั้งราคาแล้ว'); }
    catch (err) { error.textContent = errText(err); }
  });
  box.append(el('p', { class: 'hint', style: 'margin-bottom:16px' },
    'ใช้กำหนดราคาฐาน (base) ของไอเท็ม บล็อก/ปลดบล็อกการซื้อขาย และสั่งดึงราคาจากแหล่งกลางใหม่ · เว้นราคาฐานว่างเพื่อล้าง override'), form);
}

async function adminAuctions(box) {
  loading(box);
  try {
    const data = await api('GET', '/api/admin/auctions');
    clear(box);
    const list = (data.auctions || []).filter((a) => a.kind !== 'CELL');
    if (!list.length) { box.append(emptyState('ยังไม่มีประมูลไอเท็ม', 'สร้างจากแท็บของฝาก')); return; }
    const rows = el('div', { class: 'rows' });
    for (const a of list) {
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' },
          itemHeading(a.itemId, `${txt(a.label, txt(a.itemId, 'ไอเท็ม'))} ×${num(a.count)}`),
          el('span', { class: 'item-meta' }, `${txt(a.sellerName, 'ไม่ทราบชื่อ')} · ${txt(a.status, 'ไม่ทราบสถานะ')} · เริ่ม ${money(a.startPrice)} · สูงสุด ${money(a.highBid)}`)),
        el('div', { class: 'item-side' },
          a.status === 'ACTIVE' ? el('button', { class: 'btn danger', type: 'button', onclick: async () => {
            const reason = await reasonDialog('ยกเลิกประมูล ' + a.id);
            if (!reason) return;
            try { await api('POST', `/api/admin/auctions/${a.id}/cancel`, { reason }); toast('ยกเลิกแล้ว'); render(); }
            catch (error) { toastError(error); }
          } }, 'ยกเลิก') : null)));
    }
    box.append(rows);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

async function adminCellAuctions(box) {
  const cellId = el('input', { class: 'input', type: 'text', placeholder: 'เช่น 0,0', maxLength: '32' });
  const start = el('input', { class: 'input', type: 'text', value: '100', inputmode: 'numeric' });
  const buyout = el('input', { class: 'input', type: 'text', inputmode: 'numeric', placeholder: 'ไม่บังคับ' });
  const hours = el('input', { class: 'input', type: 'text', value: '24', inputmode: 'numeric' });
  const reason = el('input', { class: 'input', type: 'text', maxLength: '200', placeholder: 'เหตุผล 3–200 ตัวอักษร' });
  const error = el('p', { class: 'err', role: 'alert' });
  const form = el('form', { class: 'stack', novalidate: true },
    el('p', { class: 'hint' }, 'เปิดประมูลกรรมสิทธิ์เขต 1 ช่อง · ถ้าเขตมีเจ้าของอยู่ ผู้ชนะจะได้กรรมสิทธิ์แทน'),
    el('div', { class: 'grid2' }, el('div', { class: 'field' }, el('label', {}, 'เขต'), cellId), el('div', { class: 'field' }, el('label', {}, 'เหตุผล'), reason)),
    el('div', { class: 'grid2' }, el('div', { class: 'field' }, el('label', {}, 'ราคาเริ่มต้น'), start), el('div', { class: 'field' }, el('label', {}, 'ซื้อทันที'), buyout)),
    el('div', { class: 'field' }, el('label', {}, 'ชั่วโมง'), hours), error,
    el('button', { class: 'btn primary', type: 'submit' }, 'เปิดประมูลเขต'));
  form.addEventListener('submit', async (event) => {
    event.preventDefault(); error.textContent = '';
    if (!cellId.value.trim()) { error.textContent = 'ระบุเขต เช่น 0,0'; return; }
    if (!/^-?\d+,-?\d+$/.test(cellId.value.trim())) { error.textContent = 'รูปแบบเขตต้องเป็น cx,cz'; return; }
    if (reason.value.trim().length < 3) { error.textContent = 'ต้องมีเหตุผลอย่างน้อย 3 ตัวอักษร'; return; }
    try {
      await api('POST', '/api/admin/cell-auctions', {
        cellId: cellId.value.trim(), startPrice: num(start.value),
        buyout: buyout.value === '' ? null : num(buyout.value), hours: num(hours.value), reason: reason.value.trim(),
      });
      toast('เปิดประมูลเขตแล้ว'); cellId.value = ''; reason.value = ''; render();
    } catch (err) { error.textContent = errText(err); }
  });
  box.append(form);

  const listBox = el('div', { style: 'margin-top:32px' });
  box.append(listBox);
  loading(listBox);
  try {
    const data = await api('GET', '/api/admin/cell-auctions');
    clear(listBox);
    const list = data.auctions || [];
    if (!list.length) { listBox.append(emptyState('ยังไม่มีประมูลเขต', 'เปิดรายการแรกจากฟอร์มด้านบน')); return; }
    const rows = el('div', { class: 'rows' });
    for (const a of list) {
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' },
          el('span', { class: 'item-title' }, 'เขต ' + txt(a.cellId, '-')),
          el('span', { class: 'item-meta' }, `${txt(a.status, 'ไม่ทราบสถานะ')} · เจ้าของเดิม ${txt(a.cellOwnerName, 'ที่ว่าง')} · เริ่ม ${money(a.startPrice)} · สูงสุด ${money(a.highBid)}${a.highBidderName ? ' โดย ' + a.highBidderName : ''} · ${a.status === 'ACTIVE' ? left(a.endsAt) : clock(a.endsAt)}`)),
        el('div', { class: 'item-side' },
          a.status === 'ACTIVE' ? el('button', { class: 'btn danger', type: 'button', onclick: async () => {
            const why = await reasonDialog('ยกเลิกประมูลเขต ' + a.cellId);
            if (!why) return;
            try { await api('POST', `/api/admin/auctions/${a.id}/cancel`, { reason: why }); toast('ยกเลิกแล้ว'); render(); }
            catch (err) { toastError(err); }
          } }, 'ยกเลิก') : null)));
    }
    listBox.append(rows);
  } catch (err) { clear(listBox); listBox.append(errorState(err)); }
}

async function adminConsign(box) {
  loading(box);
  try {
    const data = await api('GET', '/api/admin/consignments');
    clear(box);
    const list = data.consignments || [];
    if (!list.length) { box.append(emptyState('ยังไม่มีของฝาก', 'ผู้เล่นส่งของได้ที่หน้าขาย')); return; }
    const rows = el('div', { class: 'rows' });
    for (const c of list) {
      const start = el('input', { class: 'input', type: 'text', value: '1', inputmode: 'numeric', 'aria-label': 'ราคาเริ่มต้น' });
      const buyout = el('input', { class: 'input', type: 'text', inputmode: 'numeric', placeholder: 'ไม่บังคับ', 'aria-label': 'ซื้อทันที' });
      const hours = el('input', { class: 'input', type: 'text', value: '24', inputmode: 'numeric', 'aria-label': 'ชั่วโมง' });
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, itemHeading(c.itemId, `${txt(c.label, txt(c.itemId, 'ไอเท็ม'))} ×${num(c.count)}`), el('span', { class: 'item-meta' }, `จาก ${txt(c.fromName, 'ไม่ทราบชื่อ')} · ${clock(c.t)}`)),
        el('div', { class: 'item-form' },
          el('div', { class: 'field' }, el('label', {}, 'เริ่ม'), start),
          el('div', { class: 'field' }, el('label', {}, 'ซื้อทันที'), buyout),
          el('div', { class: 'field' }, el('label', {}, 'ชั่วโมง'), hours),
          el('button', { class: 'btn primary', type: 'button', onclick: async () => {
            try { await api('POST', '/api/admin/auctions', { consignmentId: c.id, startPrice: num(start.value), buyout: buyout.value === '' ? null : num(buyout.value), hours: num(hours.value) }); toast('เปิดประมูลแล้ว'); render(); }
            catch (error) { toastError(error); }
          } }, 'เปิดประมูล'))));
    }
    box.append(rows);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

async function adminCells(box) {
  const names = await fetchNames();
  loading(box);
  try {
    const data = await api('GET', '/api/admin/cells');
    clear(box);
    const cells = data.cells || [];
    if (!cells.length) { box.append(emptyState('ยังไม่มีเขต', 'ผู้เล่นยังไม่จองช่อง')); return; }
    const rows = el('div', { class: 'rows' });
    for (const c of cells) {
      const dd = dropdown({ label: 'โอนให้', options: names.filter((n) => n !== c.owner), placeholder: 'เลือกผู้เล่น', searchable: true, id: 'cell-dd-' + c.id.replace(',', '-') });
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, el('span', { class: 'item-title mono' }, txt(c.id, '-')), el('span', { class: 'item-meta' }, 'เจ้าของ ' + txt(c.owner, 'ไม่ทราบชื่อ'))),
        el('div', { class: 'item-form' }, dd.el,
          el('button', { class: 'btn secondary', type: 'button', onclick: async () => {
            if (!dd.getValue()) { toast('เลือกผู้รับ', 'error'); return; }
            if (!await confirmDialog({ title: 'โอนเขต ' + c.id, body: `โอนให้ ${dd.getValue()}`, confirm: 'โอน' })) return;
            try { await api('POST', `/api/cells/${c.id}/transfer`, { to: dd.getValue() }); toast('โอนแล้ว'); render(); }
            catch (error) { toastError(error); }
          } }, 'โอน'),
          el('button', { class: 'btn danger', type: 'button', onclick: async () => {
            if (!await confirmDialog({ title: 'ปล่อยเขต ' + c.id, body: 'พื้นที่จะกลับเป็นที่ว่าง', confirm: 'ปล่อย', danger: true })) return;
            try { await api('DELETE', `/api/cells/${c.id}`); toast('ปล่อยแล้ว'); render(); }
            catch (error) { toastError(error); }
          } }, 'ปล่อย'))));
    }
    box.append(rows);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

async function adminAccounts(box) {
  loading(box);
  try {
    const data = await api('GET', '/api/admin/overview');
    clear(box);
    const players = data.players || [];
    if (!players.length) { box.append(emptyState('ยังไม่มีบัญชี', '')); return; }
    const rows = el('div', { class: 'rows' });
    for (const p of players) {
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, el('span', { class: 'item-title' }, txt(p.name, 'ไม่ทราบชื่อ')), el('span', { class: 'item-meta' }, `${p.online ? 'ออนไลน์' : 'ออฟไลน์'}${p.frozen ? ' · ระงับอยู่' : ''}`)),
        el('div', { class: 'item-side' },
          el('button', { class: 'btn secondary', type: 'button', onclick: async () => {
            const reason = await reasonDialog((p.frozen ? 'ปลดระงับ ' : 'ระงับ ') + p.name);
            if (!reason) return;
            try { await api('POST', `/api/admin/accounts/${encodeURIComponent(p.name)}/freeze`, { frozen: !p.frozen, reason }); toast('บันทึกแล้ว'); render(); }
            catch (error) { toastError(error); }
          } }, p.frozen ? 'ปลดระงับ' : 'ระงับ'),
          el('button', { class: 'btn danger', type: 'button', onclick: async () => {
            const reason = await reasonDialog('รีเซ็ตรหัสผ่าน ' + p.name);
            if (!reason) return;
            try { await api('POST', `/api/admin/accounts/${encodeURIComponent(p.name)}/reset-password`, { reason }); toast('รีเซ็ตรหัสแล้ว'); }
            catch (error) { toastError(error); }
          } }, 'รีเซ็ตรหัส'))));
    }
    box.append(rows);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

const WORLD_STATE = {
  DISCOVERED: 'รอโหลด', LOADING: 'กำลังโหลด', LOADED: 'โหลดแล้ว',
  UNLOADING: 'กำลังปิด', UNLOADED: 'ปิดอยู่', ERROR: 'ผิดพลาด',
};

async function adminWorlds(box) {
  loading(box);
  try {
    const data = await api('GET', '/api/admin/worlds');
    drawAdminWorlds(box, data);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

function drawAdminWorlds(box, data) {
  clear(box);
  const rows = data.worlds || [];
  const loaded = rows.filter((w) => w.loaded).length;
  box.append(el('div', { class: 'stats' },
    el('div', { class: 'stat' }, el('strong', {}, String(rows.length)), el('span', {}, 'โลกที่พบ')),
    el('div', { class: 'stat' }, el('strong', {}, String(loaded)), el('span', {}, 'โหลดอยู่')),
    el('div', { class: 'stat' }, el('strong', {}, data.inventoryPolicy === 'shared' ? 'ร่วมกัน' : txt(data.inventoryPolicy)), el('span', {}, 'กระเป๋า')),
    el('div', { class: 'stat' }, el('strong', {}, num(data.idleSeconds) + ' วิ'), el('span', {}, 'ปิดเมื่อว่าง'))));
  box.append(el('p', { class: 'hint', style: 'margin-top:20px' },
    `โฟลเดอร์ ${txt(data.directory)} · สแกนทุก ${num(data.scanSeconds)} วินาที · ผู้เล่นใช้คำสั่ง /ชื่อโลก`));
  box.append(el('button', { class: 'btn secondary', type: 'button', onclick: async () => {
    try { await api('POST', '/api/admin/worlds/scan', {}); toast('สแกน worlds แล้ว'); render(); }
    catch (error) { toastError(error); }
  } }, 'สแกนโฟลเดอร์ใหม่'));
  if (!rows.length) {
    box.append(emptyState('ยังไม่พบโลก', 'วางโฟลเดอร์โลกที่มี level.dat ไว้ในโฟลเดอร์ worlds แล้วกดสแกน'));
    return;
  }
  const list = el('div', { class: 'rows', style: 'margin-top:24px' });
  for (const w of rows) {
    const actions = [];
    if (w.loaded) {
      actions.push(el('button', { class: 'btn primary', type: 'button', onclick: () => {
        api('POST', `/api/admin/worlds/${encodeURIComponent(w.name)}/tp`, {})
          .then(() => { toast('ย้ายไป ' + w.name + ' แล้ว'); render(); })
          .catch((error) => toastError(error));
      } }, 'เข้าโลก'));
      actions.push(el('button', { class: 'btn danger', type: 'button', onclick: async () => {
        if (!await confirmDialog({ title: 'ปิดโลก ' + w.name, body: 'บันทึกและปิดโลกนี้ ถ้ายังมีผู้เล่นอยู่ระบบจะไม่ปิดให้', confirm: 'ปิดโลก', danger: true })) return;
        try { await api('POST', `/api/admin/worlds/${encodeURIComponent(w.name)}/unload`, {}); toast('ปิดโลกแล้ว'); render(); }
        catch (error) { toastError(error); }
      } }, 'ปิดโลก'));
    } else {
      actions.push(el('button', { class: 'btn primary', type: 'button', onclick: async () => {
        try { await api('POST', `/api/admin/worlds/${encodeURIComponent(w.name)}/load`, {}); toast('โหลดโลกแล้ว'); render(); }
        catch (error) { toastError(error); }
      } }, 'โหลด'));
    }
    const meta = [];
    meta.push(WORLD_STATE[w.state] || txt(w.state));
    meta.push(w.loaded ? `ผู้เล่น ${num(w.players)}` : 'ยังไม่ใช้หน่วยความจำ');
    if (w.commandAvailable) meta.push('/' + w.name);
    else if (w.commandConflict) meta.push('คำสั่งชน: ' + w.commandConflict);
    if (!w.present) meta.push('ไม่พบโฟลเดอร์');
    if (w.error) meta.push(txt(w.error));
    list.append(el('div', { class: 'item' },
      el('div', { class: 'item-main' },
        el('span', { class: 'item-title mono' }, w.name),
        el('span', { class: 'item-meta' }, meta.join(' · '))),
      el('div', { class: 'item-side' }, actions)));
  }
  box.append(list);
  box.append(el('p', { class: 'hint', style: 'margin-top:20px' },
    'กระเป๋าไอเท็มใช้ร่วมกันทุกโลก และโลกหลักของเซิร์ฟเวอร์จะไม่ถูกปิดจากหน้านี้'));
}

async function adminAudit(box) {
  loading(box);
  try {
    const data = await api('GET', '/api/admin/audit?limit=50');
    clear(box);
    const lines = data.lines || [];
    if (!lines.length) { box.append(emptyState('ยังไม่มีบันทึก', '')); return; }
    const rows = el('div', { class: 'rows' });
    for (const line of lines.slice().reverse()) {
      const details = line.details ? Object.entries(line.details).map(([k, v]) => `${k}=${txt(v, '-')}`).join(' ') : '';
      rows.append(el('div', { class: 'item' },
        el('div', { class: 'item-main' }, el('span', { class: 'item-title mono' }, line.action || '-'), el('span', { class: 'item-meta' }, `${line.actor || '-'} · ${clock(line.t)} ${details}`)),
        el('span', { class: 'mono muted' }, line.ok ? 'สำเร็จ' : 'ไม่สำเร็จ')));
    }
    box.append(rows);
  } catch (error) { clear(box); box.append(errorState(error)); }
}

/* ---------- tabs ---------- */
function tabbed(page, tabs, initial, renderPanel) {
  const bar = el('div', { class: 'tabs', role: 'tablist' });
  const panel = el('div');
  let current = initial || tabs[0].id;
  panel.setAttribute('role', 'tabpanel');
  for (const t of tabs) {
    bar.append(el('button', {
      type: 'button', role: 'tab', id: 'tab-' + t.id, 'aria-controls': 'tabpanel', 'data-id': t.id, 'aria-selected': String(t.id === current),
      onclick: () => { current = t.id; $$('button', bar).forEach((b) => b.setAttribute('aria-selected', String(b.dataset.id === current))); clear(panel); renderPanel(panel, current); },
    }, t.label));
  }
  page.append(bar, panel);
  renderPanel(panel, current);
}

/* ---------- boot ---------- */
async function boot() {
  renderShell();
  if (state.token) {
    try {
      const me = await api('GET', '/api/me');
      state.me = { name: me.name, admin: !!me.admin };
      state.pos = me.hasPos ? { x: Number(me.x) || 0, z: Number(me.z) || 0 } : null;
      await refreshWallet();
    } catch {
      state.token = '';
      localStorage.removeItem(TOKEN_KEY);
      state.me = null;
    }
  }
  renderShell();
  window.addEventListener('hashchange', render);
  window.addEventListener('offline', () => toast('ออฟไลน์ · ใช้ข้อมูลที่โหลดไว้ได้', 'error'));
  window.addEventListener('online', () => toast('กลับมาออนไลน์แล้ว'));
  render();
  fetchNames();
}
boot();

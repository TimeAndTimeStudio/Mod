import { icon, hydrateIcons } from './icons.js';

// Shared session, API, router and minimal Swiss UI helpers for Time SMP.
// Everything from the server is written with textContent, never innerHTML.

const SESSION_KEY = 'claimmap_session';
const THEME_KEY = 'claimmap_theme';
const CURRENCY_FALLBACK = 'เหรียญ';

export const state = {
  session: localStorage.getItem(SESSION_KEY) || null,
  me: null,
  admin: false,
  balance: 0,
  currency: CURRENCY_FALLBACK,
  mailboxCount: 0,
  lastX: null,
  lastZ: null,
  hasPos: false
};

export class ApiError extends Error {
  constructor(status, code, message, retryAfterSeconds) {
    super(message || 'เกิดข้อผิดพลาด');
    this.status = status;
    this.code = code;
    this.retryAfterSeconds = retryAfterSeconds || 0;
  }
}

export function setSession(token) {
  state.session = token || null;
  if (token) localStorage.setItem(SESSION_KEY, token);
  else localStorage.removeItem(SESSION_KEY);
}

export function clearSession() {
  setSession(null);
  state.me = null;
  state.admin = false;
  state.balance = 0;
  state.mailboxCount = 0;
}

export function authHeaders(json) {
  const h = {};
  if (state.session) h.Authorization = 'Bearer ' + state.session;
  if (json) h['Content-Type'] = 'application/json';
  return h;
}

let unauthorizedHandler = null;
export function setUnauthorizedHandler(fn) { unauthorizedHandler = fn; }

export async function api(method, path, body, options = {}) {
  const init = { method, headers: authHeaders(body !== undefined && body !== null) };
  if (body !== undefined && body !== null) init.body = JSON.stringify(body);
  if (options.requestId) init.headers['X-Request-Id'] = options.requestId;

  let response;
  try {
    response = await fetch(path, init);
  } catch (e) {
    throw new ApiError(0, 'network', 'เชื่อมต่อเซิร์ฟเวอร์ไม่ได้');
  }

  let data = null;
  const text = await response.text();
  if (text) {
    try { data = JSON.parse(text); } catch (e) { data = null; }
  }

  if (response.status === 401) {
    clearSession();
    if (unauthorizedHandler) unauthorizedHandler();
    throw new ApiError(401, 'unauthorized', (data && data.message) || 'เซสชันหมดอายุ กรุณาเข้าสู่ระบบใหม่');
  }
  if (!response.ok) {
    throw new ApiError(
      response.status,
      (data && data.error) || 'http_' + response.status,
      (data && data.message) || 'ทำรายการไม่สำเร็จ (' + response.status + ')',
      data && data.retryAfterSeconds
    );
  }
  return data;
}

// ---- DOM helpers ----------------------------------------------------------

export function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  if (attrs) {
    for (const [key, value] of Object.entries(attrs)) {
      if (value === undefined || value === null || value === false) continue;
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key === 'onClick') node.addEventListener('click', value);
      else if (key === 'onInput') node.addEventListener('input', value);
      else if (key === 'onSubmit') node.addEventListener('submit', value);
      else if (key in node && key !== 'list' && key !== 'form') node[key] = value;
      else node.setAttribute(key, value);
    }
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

export function money(value) {
  return Number(value || 0).toLocaleString('th-TH');
}

export function dateTime(ms) {
  if (!ms) return '-';
  return new Date(ms).toLocaleString('th-TH', { dateStyle: 'short', timeStyle: 'short' });
}

export function timeLeft(endsAt, nowMs) {
  const ms = endsAt - (nowMs || Date.now());
  if (ms <= 0) return 'หมดเวลา';
  const s = Math.floor(ms / 1000);
  const d = Math.floor(s / 86400);
  const h = Math.floor((s % 86400) / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (d > 0) return d + ' วัน ' + h + ' ชม.';
  if (h > 0) return h + ' ชม. ' + m + ' น.';
  if (m > 0) return m + ' น. ' + sec + ' วิ';
  return sec + ' วิ';
}

export function randomId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return 'r-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

// ---- toast & dialog -------------------------------------------------------

let toastTimer = null;
export function toast(message, kind) {
  const box = document.getElementById('toast');
  if (!box) return;
  box.textContent = message || '';
  box.className = 'show' + (kind ? ' ' + kind : '');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.className = ''; }, 3800);
}

export function showError(e) {
  if (e && e.status === 401) return;
  toast((e && e.message) || 'ทำรายการไม่สำเร็จ', 'error');
}

/* ADDED: Clean Native Dialog Confirmation */
export function confirmModal({ title, lines = [], confirmText = 'ยืนยัน', danger = false }) {
  return new Promise(resolve => {
    const dialog = document.getElementById('confirm-dialog');
    if (!dialog || typeof dialog.showModal !== 'function') {
      const ok = window.confirm(title + '\n' + lines.map(l => l.label + ': ' + l.value).join('\n'));
      resolve(ok);
      return;
    }
    const titleEl = document.getElementById('confirm-dialog-title');
    const bodyEl = document.getElementById('confirm-dialog-body');
    const btnCancel = document.getElementById('confirm-btn-cancel');
    const btnSubmit = document.getElementById('confirm-btn-submit');

    if (titleEl) titleEl.textContent = title;
    clear(bodyEl);
    for (const l of lines) {
      bodyEl.append(el('div', { class: 'confirm-line', style: 'display: flex; justify-content: space-between; padding: 4px 0;' },
        el('span', { class: 'mono-label', text: l.label }),
        el('strong', { class: 'num', text: String(l.value) })
      ));
    }

    btnSubmit.textContent = confirmText;
    btnSubmit.className = danger ? 'btn btn-primary danger' : 'btn btn-primary';

    const onCancel = () => { cleanup(); resolve(false); };
    const onSubmit = e => { e.preventDefault(); cleanup(); resolve(true); };

    function cleanup() {
      dialog.close();
      btnCancel.removeEventListener('click', onCancel);
      dialog.removeEventListener('cancel', onCancel);
      btnSubmit.removeEventListener('click', onSubmit);
    }

    btnCancel.addEventListener('click', onCancel);
    dialog.addEventListener('cancel', onCancel);
    btnSubmit.addEventListener('click', onSubmit);
    dialog.showModal();
  });
}

// ---- router ---------------------------------------------------------------

const routes = new Map();
let currentApp = null;
let started = false;

/* ADDED: Route titles mapping for breadcrumb wayfinding */
const ROUTE_TITLES = {
  home: 'หน้าแรก',
  map: 'แผนที่ดินแดน',
  buy: 'ซื้อของ',
  sell: 'ลงขาย',
  auction: 'ตลาดประมูล',
  prices: 'ดัชนีราคากลาง',
  wallet: 'กระเป๋าเงิน',
  mailbox: 'กล่องรับของ',
  about: 'คู่มือ / คำสั่งในเกม',
  admin: 'แผงควบคุมแอดมิน'
};

export function register(name, app) {
  routes.set(name, app);
}

export function currentRoute() {
  const raw = (location.hash || '#home').slice(1);
  return routes.has(raw) ? raw : 'home';
}

export function navigate(name) {
  const target = '#' + name;
  if (location.hash === target) renderRoute();
  else location.hash = target;
}

function updateBreadcrumb(route) {
  const crumb = document.getElementById('view-breadcrumb');
  if (crumb) {
    crumb.textContent = ROUTE_TITLES[route] || 'หน้าแรก';
  }
}

function renderLogin(container) {
  const name = el('input', { id: 'login-name', type: 'text', placeholder: 'ชื่อผู้เล่นในเกม', autocomplete: 'username', maxLength: 32 });
  const password = el('input', { id: 'login-pass', type: 'password', placeholder: 'รหัสผ่าน', autocomplete: 'current-password', maxLength: 64 });
  const submit = el('button', { class: 'primary', type: 'submit', text: 'เข้าสู่ระบบ' });
  const error = el('p', { class: 'field-error', id: 'login-error', text: '' });

  const form = el('form', {
    onSubmit: async e => {
      e.preventDefault();
      error.textContent = '';
      const user = name.value.trim();
      const pass = password.value;
      if (!user || !pass) { error.textContent = 'กรอกชื่อและรหัสผ่านก่อนเข้าสู่ระบบ'; return; }
      submit.disabled = true;
      try {
        const data = await api('POST', '/api/login', { name: user, password: pass });
        setSession(data.session || data.token);
        state.me = data.name;
        state.admin = !!data.admin;
        password.value = '';
        refreshStatus();
        if (location.hash === '#home') renderRoute();
        else navigate('home');
      } catch (e) {
        if (e.status === 429) {
          const wait = e.retryAfterSeconds || 60;
          error.textContent = 'ลองผิดหลายครั้งเกินไป — รออีก ' + wait + ' วินาที';
        } else if (e.status === 401) {
          error.textContent = 'ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง';
        } else {
          error.textContent = e.message || 'เข้าสู่ระบบไม่สำเร็จ';
        }
      } finally {
        submit.disabled = false;
      }
    }
  },
    el('div', { class: 'field' },
      el('label', null, el('span', { text: 'ชื่อผู้เล่น' }), el('span', { class: 'req', text: '*' })),
      name,
      el('span', { class: 'field-help', text: 'ชื่อเดียวกับที่ใช้ในเกม Time SMP' })
    ),
    el('div', { class: 'field' },
      el('label', null, el('span', { text: 'รหัสผ่าน' }), el('span', { class: 'req', text: '*' })),
      password,
      el('span', { class: 'field-help', text: 'รหัสที่ตั้งไว้ตอนเข้าเซิร์ฟเวอร์ครั้งแรก' })
    ),
    error,
    el('div', { class: 'form-actions' }, submit)
  );

  const box = el('div', { class: 'login-wrap' },
    el('div', { class: 'card' },
      el('div', { style: 'display: flex; align-items: center; gap: 10px; margin-bottom: 16px;' },
        el('img', { src: '/img/logo.png', alt: 'Time SMP', style: 'height: 32px; width: auto; display: block;' }),
        el('h2', { style: 'font-size: 18px;', text: 'Time SMP Login' })
      ),
      el('p', { class: 'hint', text: 'เข้าสู่ระบบตลาดและระบบจัดการพื้นที่ส่วนตัวของคุณ' }),
      form,
      el('p', { class: 'hint', style: 'font-size: 12px; margin-top: 20px;' },
        'ยังไม่มีรหัส? เข้าเกม Minecraft แล้วพิมพ์ ',
        el('code', { class: 'mono', text: '/pass <รหัส>' })
      )
    )
  );
  container.append(box);
  name.focus();
}

function paintTabs() {
  const active = currentRoute();
  for (const button of document.querySelectorAll('[data-route]')) {
    button.classList.toggle('active', button.getAttribute('data-route') === active);
  }
  updateBreadcrumb(active);
  document.body.classList.toggle('logged-out', !state.session);
}

export async function renderRoute() {
  paintTabs();
  const app = document.getElementById('app');
  if (!app) return;
  if (currentApp && typeof currentApp.dispose === 'function') {
    try { currentApp.dispose(); } catch (e) { /* keep going */ }
  }
  currentApp = null;
  clear(app);

  if (!state.session) {
    renderLogin(app);
    return;
  }

  const name = currentRoute();
  if (name === 'admin' && !state.admin) {
    if (location.hash !== '#home') { location.hash = '#home'; return; }
    return;
  }
  const route = routes.get(name);
  if (!route) {
    /* ADDED: Clean 404 View */
    app.append(
      el('div', { class: 'empty-state' },
        el('div', { class: 'empty-state-title', text: '404 — ไม่พบหน้าที่ต้องการ' }),
        el('p', { class: 'empty-state-desc', text: 'เส้นทางที่คุณเรียกไม่มีอยู่หรือถูกย้ายแล้ว' }),
        el('button', { class: 'btn btn-primary', onClick: () => navigate('home') }, 'กลับหน้าแรก')
      )
    );
    return;
  }

  try {
    const handle = await route.render(app);
    currentApp = handle && typeof handle.dispose === 'function' ? handle : null;
  } catch (e) {
    if (e && e.status === 401) return;
    app.append(
      el('div', { class: 'empty-state' },
        el('div', { class: 'empty-state-title', text: 'โหลดหน้านี้ไม่สำเร็จ' }),
        el('p', { class: 'empty-state-desc', text: (e && e.message) || 'กรุณาลองใหม่อีกครั้ง' }),
        el('button', { class: 'btn btn-secondary', onClick: () => renderRoute() }, 'ลองใหม่')
      )
    );
  }
}

export function notifyUnauthorized() {
  clearSession();
  if (unauthorizedHandler) unauthorizedHandler();
}

/* ADDED: Quick Theme Initializer */
export function initTheme() {
  const saved = localStorage.getItem(THEME_KEY) || 'dark';
  document.documentElement.setAttribute('data-theme', saved);
  const toggle = document.getElementById('theme-toggle');
  if (toggle) {
    toggle.addEventListener('click', () => {
      const current = document.documentElement.getAttribute('data-theme') || 'dark';
      const next = current === 'dark' ? 'light' : 'dark';
      document.documentElement.setAttribute('data-theme', next);
      localStorage.setItem(THEME_KEY, next);
    });
  }
}

/* ADDED: Keyboard Shortcuts */
export function initShortcuts() {
  document.addEventListener('keydown', e => {
    if (e.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes(e.target.tagName)) {
      e.preventDefault();
      const s = document.querySelector('input[type="search"], input[type="text"]');
      if (s) { s.focus(); s.select(); }
    }
  });
}

export function startRouter() {
  if (started) return;
  started = true;
  initTheme();
  initShortcuts();
  setUnauthorizedHandler(() => {
    if (currentApp && typeof currentApp.dispose === 'function') currentApp.dispose();
    currentApp = null;
    renderRoute();
  });
  window.addEventListener('hashchange', renderRoute);
  hydrateIcons();
  for (const button of document.querySelectorAll('[data-route]')) {
    button.addEventListener('click', () => navigate(button.getAttribute('data-route')));
  }
  const logout = document.getElementById('btn-logout');
  if (logout) logout.addEventListener('click', () => { notifyUnauthorized(); paintStatus(); });
}

// ---- status bar & polling -------------------------------------------------

let statusTimer = null;
let statusFailures = 0;
let statusDelay = 5000;

function paintStatus() {
  const who = document.getElementById('who-name');
  const balance = document.getElementById('balance');
  const currency = document.getElementById('currency');
  const box = document.getElementById('mailbox');
  if (who) who.textContent = state.me || (state.session ? '…' : 'Guest');
  if (balance) balance.textContent = money(state.balance);
  if (currency) currency.textContent = state.currency || CURRENCY_FALLBACK;
  if (box) {
    box.textContent = String(state.mailboxCount || 0);
    box.hidden = !state.mailboxCount;
  }
  for (const b of document.querySelectorAll('[data-mailbox-count]')) {
    b.textContent = String(state.mailboxCount || 0);
    b.hidden = !state.mailboxCount;
  }
  for (const node of document.querySelectorAll('[data-admin-only]')) node.hidden = !state.admin;
  const logout = document.getElementById('btn-logout');
  if (logout) logout.hidden = !state.session;
}

export async function refreshStatus() {
  const banner = document.getElementById('offline-banner');
  try {
    if (!state.session) {
      await api('GET', '/api/health');
      statusFailures = 0;
      statusDelay = 5000;
      if (banner) {
        banner.hidden = true;
        banner.style.display = 'none';
      }
      paintStatus();
      return;
    }
    const data = await api('GET', '/api/wallet');
    state.balance = data.balance || 0;
    state.currency = data.currency || CURRENCY_FALLBACK;
    if (typeof data.mailboxCount === 'number') state.mailboxCount = data.mailboxCount;
    statusFailures = 0;
    statusDelay = 5000;
    if (banner) {
      banner.hidden = true;
      banner.style.display = 'none';
    }
    paintStatus();
  } catch (e) {
    if (e.status === 401 || e.status === 403) {
      if (banner) {
        banner.hidden = true;
        banner.style.display = 'none';
      }
      return;
    }
    statusFailures++;
    if (statusFailures >= 2) {
      if (banner) {
        banner.hidden = false;
        banner.style.display = 'flex';
      }
    }
    statusDelay = 2500;
  }
}

function scheduleStatus() {
  if (statusTimer) clearTimeout(statusTimer);
  statusTimer = setTimeout(async () => {
    if (document.visibilityState === 'visible') await refreshStatus();
    scheduleStatus();
  }, statusDelay);
}

export function startStatus() {
  paintStatus();
  refreshStatus();
  scheduleStatus();
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') refreshStatus();
  });
}

export async function boot() {
  const banner = document.getElementById('offline-banner');
  if (banner) {
    banner.hidden = true;
    banner.style.display = 'none';
  }
  startRouter();
  if (state.session) {
    try {
      const me = await api('GET', '/api/me');
      state.me = me.name;
      state.admin = !!me.admin;
      if (me.hasPos) {
        state.lastX = me.x;
        state.lastZ = me.z;
        state.hasPos = true;
      }
    } catch (e) {
      if (e.status !== 401) toast('ตรวจสอบเซสชันไม่สำเร็จ', 'error');
    }
  }
  startStatus();
  await renderRoute();
}

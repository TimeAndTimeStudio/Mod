// Shared session, API, router and small UI helpers for the mobile web app.
// Everything that comes from the server is written with textContent, never
// innerHTML (PHASES.md 8.3).

const SESSION_KEY = 'claimmap_session';
const CURRENCY_FALLBACK = 'เหรียญ';

export const state = {
  session: localStorage.getItem(SESSION_KEY) || null,
  me: null,
  admin: false,
  balance: 0,
  currency: CURRENCY_FALLBACK,
  mailboxCount: 0
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

// ---- DOM helpers (no innerHTML for server data) ---------------------------

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

// ---- toast + confirm modal ------------------------------------------------

let toastTimer = null;
export function toast(message, kind) {
  const box = document.getElementById('toast');
  if (!box) return;
  box.textContent = message || '';
  box.className = 'show' + (kind ? ' ' + kind : '');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.className = ''; }, 4200);
}

export function showError(e) {
  if (e && e.status === 401) return;
  toast((e && e.message) || 'ทำรายการไม่สำเร็จ', 'error');
}

/**
 * Confirmation dialog. lines = [{ label, value }].
 * Resolves true only when the user presses the confirm button.
 */
export function confirmModal({ title, lines = [], confirmText = 'ยืนยัน', danger = false, busyText = 'กำลังดำเนินการ…' }) {
  return new Promise(resolve => {
    const backdrop = el('div', { class: 'modal-backdrop' });
    const confirm = el('button', {
      class: danger ? 'danger' : 'primary',
      type: 'button',
      text: confirmText
    });
    const cancel = el('button', { class: 'secondary', type: 'button', text: 'ยกเลิก' });
    const panel = el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' },
      el('h2', { text: title || 'ยืนยันรายการ' }),
      el('ul', { class: 'modal-lines' }, lines.map(line =>
        el('li', null, el('span', { text: line.label }), el('span', { text: line.value })))),
      el('div', { class: 'modal-actions' }, cancel, confirm)
    );
    backdrop.append(panel);

    const finish = value => {
      document.removeEventListener('keydown', onKey);
      backdrop.remove();
      resolve(value);
    };
    const onKey = e => { if (e.key === 'Escape') finish(false); };
    document.addEventListener('keydown', onKey);
    backdrop.addEventListener('click', e => { if (e.target === backdrop) finish(false); });
    cancel.addEventListener('click', () => finish(false));
    confirm.addEventListener('click', () => {
      confirm.disabled = true;
      cancel.disabled = true;
      confirm.textContent = busyText;
      finish(true);
    });
    document.body.append(backdrop);
    confirm.focus();
  });
}

// ---- router ---------------------------------------------------------------

const routes = new Map();
let currentApp = null;
let started = false;

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

function renderLogin(container) {
  const name = el('input', { id: 'login-name', type: 'text', placeholder: 'ชื่อผู้เล่น', autocomplete: 'username', maxLength: 32 });
  const password = el('input', { id: 'login-pass', type: 'password', placeholder: 'รหัสผ่าน', autocomplete: 'current-password', maxLength: 64 });
  const submit = el('button', { class: 'primary', type: 'submit', text: 'เข้าสู่ระบบ' });
  const error = el('p', { class: 'hint', id: 'login-error', text: '' });

  const form = el('form', {
    onSubmit: async e => {
      e.preventDefault();
      error.textContent = '';
      const user = name.value.trim();
      const pass = password.value;
      if (!user || !pass) { error.textContent = 'กรอกชื่อและรหัสผ่านก่อน'; return; }
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
  }, el('label', { text: 'ชื่อผู้เล่น' }), name,
    el('label', { text: 'รหัสผ่าน' }), password,
    el('div', { class: 'row' }, submit),
    error);

  const box = el('div', { class: 'login-wrap' },
    el('div', { class: 'card' },
      el('h1', { text: 'Claim Map' }),
      el('p', { class: 'hint', text: 'ใช้ชื่อและรหัสเดียวกับที่ตั้งไว้ในหน้าต่างตอนเข้าเกม' }),
      form,
      el('p', { class: 'hint', text: '⚠️ ระบบนี้ใช้ HTTP ธรรมดา อย่าใช้รหัสซ้ำกับบริการอื่น' })
    ));
  container.append(box);
  name.focus();
}

export async function renderRoute() {
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
  const route = routes.get(name) || routes.get('home');
  try {
    const handle = await route.render(app);
    currentApp = handle && typeof handle.dispose === 'function' ? handle : null;
  } catch (e) {
    if (e && e.status === 401) return;
    app.append(el('div', { class: 'empty', text: (e && e.message) || 'เปิดหน้านี้ไม่ได้' }));
  }
}

export function notifyUnauthorized() {
  clearSession();
  if (unauthorizedHandler) unauthorizedHandler();
}

export function startRouter() {
  if (started) return;
  started = true;
  setUnauthorizedHandler(() => {
    if (currentApp && typeof currentApp.dispose === 'function') currentApp.dispose();
    currentApp = null;
    renderRoute();
  });
  window.addEventListener('hashchange', renderRoute);
  document.getElementById('btn-home').addEventListener('click', () => navigate('home'));
  document.getElementById('btn-back').addEventListener('click', () => {
    if (history.length > 1) history.back();
    else navigate('home');
  });
}

// ---- status bar -----------------------------------------------------------

let statusTimer = null;
let statusFailures = 0;
let statusDelay = 5000;

function paintStatus() {
  const who = document.getElementById('who-name');
  const role = document.getElementById('who-role');
  const balance = document.getElementById('balance');
  const currency = document.getElementById('currency');
  const box = document.getElementById('mailbox');
  if (who) who.textContent = state.me || (state.session ? '…' : 'ยังไม่ได้เข้าสู่ระบบ');
  if (role) role.textContent = state.admin ? 'แอดมิน' : (state.me ? 'ผู้เล่น' : '');
  if (balance) balance.textContent = money(state.balance);
  if (currency) currency.textContent = state.currency || CURRENCY_FALLBACK;
  if (box) box.textContent = '📦 ' + (state.mailboxCount || 0);
}

export async function refreshStatus() {
  if (!state.session) { paintStatus(); return; }
  try {
    const data = await api('GET', '/api/wallet');
    state.balance = data.balance || 0;
    state.currency = data.currency || CURRENCY_FALLBACK;
    if (typeof data.mailboxCount === 'number') state.mailboxCount = data.mailboxCount;
    statusFailures = 0;
    statusDelay = 5000;
    const banner = document.getElementById('offline-banner');
    if (banner) banner.hidden = true;
    paintStatus();
  } catch (e) {
    if (e.status === 401 || e.status === 403) return;
    statusFailures++;
    if (statusFailures >= 3) {
      const banner = document.getElementById('offline-banner');
      if (banner) banner.hidden = false;
      statusDelay = Math.min(30000, statusDelay * 2);
    }
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
  startRouter();
  if (state.session) {
    try {
      const me = await api('GET', '/api/me');
      state.me = me.name;
      state.admin = !!me.admin;
    } catch (e) {
      if (e.status !== 401) toast('ตรวจสอบเซสชันไม่สำเร็จ', 'error');
    }
  }
  startStatus();
  await renderRoute();
}

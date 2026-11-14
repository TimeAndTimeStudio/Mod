import { icon, itemSlot } from '../icons.js';
import { el, clear, api, state, money, dateTime, navigate } from '../core.js';

const JSONBIN_ENDPOINT = 'https://api.jsonbin.io/v3/b/6adc9639ffd5d16053881c70/latest';
const JSONBIN_KEY = '$2a$10$EXkHeIJC/q2Te/E8MoZHAe3H0Rgl5x/mDjTqhNpIY1eYgsnJLpw8W';

function formatItemName(id) {
  const clean = (id || '').replace(/^minecraft:/, '');
  return clean.split('_').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ');
}

export function render(container) {
  let disposed = false;
  let data = null;

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'ดัชนีราคากลาง' }),
        el('p', { class: 'hint', text: 'ตารางราคารับซื้อและขายออกของร้านค้าเซิร์ฟเวอร์ (ช่องเก็บของ Minecraft)' })))
  );
  const stale = el('div');
  const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็มที่มีราคา...' });
  const updated = el('p', { class: 'hint', text: '' });
  const tableWrap = el('div');
  container.append(stale, el('div', { class: 'toolbar' }, el('div', { class: 'field' }, search)), updated, tableWrap);

  function renderGrid() {
    if (disposed) return;
    clear(tableWrap);
    if (!data) {
      tableWrap.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการราคา…' }));
      return;
    }
    const query = search.value.trim().toLowerCase();
    const entries = Object.entries(data.items || {})
      .filter(([id]) => !query || id.toLowerCase().includes(query) || formatItemName(id).toLowerCase().includes(query))
      .sort((a, b) => a[0].localeCompare(b[0]));
    if (!entries.length) {
      tableWrap.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่มีราคา' }));
      return;
    }
    const grid = el('div', { class: 'inv-grid' });
    for (const [id, price] of entries) {
      const card = el('div', {
        class: 'inv-slot-card',
        onClick: () => navigate('buy')
      },
        itemSlot(id, 1, 40),
        el('div', { class: 'inv-name', text: formatItemName(id), title: id }),
        el('div', { class: 'inv-price-tag' },
          el('span', { class: 'buy', text: 'ซื้อ: ' + money(price.buy) }),
          el('span', { class: 'sell', text: 'ขาย: ' + money(price.sell) })
        )
      );
      grid.append(card);
    }
    tableWrap.append(grid);
  }

  async function load() {
    if (disposed) return;
    try {
      data = await api('GET', '/api/prices');
      if (!data || !data.items || Object.keys(data.items).length === 0) {
        throw new Error('empty server prices');
      }
    } catch (e) {
      try {
        const res = await fetch(JSONBIN_ENDPOINT, {
          cache: 'no-store',
          headers: { 'X-Access-Key': JSONBIN_KEY, 'X-Bin-Meta': 'false' }
        });
        if (res.ok) {
          const binData = await res.json();
          const pricesMap = binData.prices || (binData.record && binData.record.prices) || {};
          const items = {};
          for (const [k, v] of Object.entries(pricesMap)) {
            const num = Number(v) || 0;
            items[k] = { buy: Math.round(num * 1.5), sell: num };
          }
          data = {
            currency: state.currency || 'เหรียญ',
            stale: false,
            updatedAt: Date.now(),
            items
          };
        }
      } catch (jsonBinErr) {
        // ignore
      }
    }
    clear(stale);
    if (data && data.stale) {
      stale.append(el('p', { class: 'notice' }, icon('warn', 16), 'ราคายังเป็นข้อมูลเก่า ระบบกำลังพยายามอัปเดต'));
    }
    updated.textContent = data && data.updatedAt ? 'อัปเดตล่าสุด ' + dateTime(data.updatedAt) : 'ยังไม่มีเวลาอัปเดต';
    renderGrid();
  }

  search.addEventListener('input', renderGrid);
  load();

  return {
    dispose() { disposed = true; }
  };
}

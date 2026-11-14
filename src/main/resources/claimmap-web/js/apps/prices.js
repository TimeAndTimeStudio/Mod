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
  let sortMode = 'name-asc';

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'ดัชนีราคากลาง' }),
        el('p', { class: 'hint', text: 'ตารางราคารับซื้อและขายออกของระบบเศรษฐกิจเซิร์ฟเวอร์ Time SMP' })))
  );

  const staleNotice = el('div');
  const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็ม (เช่น diamond, iron, oak)...' });

  /* ADDED: Sort selector for price indexing */
  const sortSelect = el('select', { style: 'width: auto; min-width: 180px;' },
    el('option', { value: 'name-asc', text: 'เรียงตามชื่อ (A-Z)' }),
    el('option', { value: 'buy-desc', text: 'ราคาซื้อสูงสุด' }),
    el('option', { value: 'buy-asc', text: 'ราคาซื้อต่ำสุด' }),
    el('option', { value: 'sell-desc', text: 'ราคาขายสูงสุด' })
  );
  sortSelect.addEventListener('change', () => {
    sortMode = sortSelect.value;
    renderGrid();
  });

  const updatedHint = el('p', { class: 'hint', text: '', style: 'margin-top: 8px;' });
  const tableWrap = el('div');

  const toolbar = el('div', { class: 'toolbar', style: 'margin-bottom: 12px;' },
    el('div', { class: 'field', style: 'flex: 1;' }, search),
    el('div', { class: 'field' }, sortSelect)
  );

  container.append(staleNotice, toolbar, updatedHint, tableWrap);

  search.addEventListener('input', () => renderGrid());

  function renderGrid() {
    if (disposed) return;
    clear(tableWrap);
    if (!data) {
      tableWrap.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการราคา…' }));
      return;
    }
    const query = search.value.trim().toLowerCase();
    let entries = Object.entries(data.items || {})
      .filter(([id]) => !query || id.toLowerCase().includes(query) || formatItemName(id).toLowerCase().includes(query));

    // Sort entries
    if (sortMode === 'name-asc') {
      entries.sort((a, b) => a[0].localeCompare(b[0]));
    } else if (sortMode === 'buy-desc') {
      entries.sort((a, b) => (b[1].buy || 0) - (a[1].buy || 0));
    } else if (sortMode === 'buy-asc') {
      entries.sort((a, b) => (a[1].buy || 0) - (b[1].buy || 0));
    } else if (sortMode === 'sell-desc') {
      entries.sort((a, b) => (b[1].sell || 0) - (a[1].sell || 0));
    }

    if (!entries.length) {
      tableWrap.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่ค้นหา' }));
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
    clear(staleNotice);
    if (data && data.stale) {
      staleNotice.append(el('p', { class: 'notice' }, icon('warn', 14), 'ราคายังเป็นข้อมูลเก่า ระบบกำลังเชื่อมต่ออัปเดตอัตโนมัติ'));
    }
    updatedHint.textContent = data && data.updatedAt ? 'อัปเดตข้อมูลล่าสุดเมื่อ ' + dateTime(data.updatedAt) : 'พร้อมใช้งาน';
    renderGrid();
  }

  load();

  return {
    dispose() { disposed = true; }
  };
}

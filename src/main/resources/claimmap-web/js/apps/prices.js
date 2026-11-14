import { icon } from '../icons.js';
import { el, clear, api, state, money, dateTime } from '../core.js';

export function render(container) {
  let disposed = false;
  let data = null;

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'ดัชนีราคากลาง' }),
        el('p', { class: 'hint', text: 'ตารางราคารับซื้อและขายออกของร้านค้าเซิร์ฟเวอร์' })))
  );
  const stale = el('div');
  const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็ม' });
  const updated = el('p', { class: 'hint', text: '' });
  const tableWrap = el('div');
  container.append(stale, el('div', { class: 'toolbar' }, el('div', { class: 'field' }, search)), updated, tableWrap);

  function renderTable() {
    if (disposed) return;
    clear(tableWrap);
    if (!data) {
      tableWrap.append(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
      return;
    }
    const query = search.value.trim().toLowerCase();
    const entries = Object.entries(data.items || {})
      .filter(([id]) => !query || id.toLowerCase().includes(query))
      .sort((a, b) => a[0].localeCompare(b[0]));
    if (!entries.length) {
      tableWrap.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็ม' }));
      return;
    }
    const tbody = el('tbody');
    for (const [id, price] of entries) {
      tbody.append(el('tr', null,
        el('td', { text: id }),
        el('td', { class: 'num', text: money(price.buy) }),
        el('td', { class: 'num', text: money(price.sell) })));
    }
    tableWrap.append(el('table', null,
      el('thead', null, el('tr', null,
        el('th', { text: 'ไอเท็ม' }),
        el('th', { class: 'num', text: 'ซื้อ (' + (data.currency || state.currency) + ')' }),
        el('th', { class: 'num', text: 'ขาย' }))),
      tbody));
  }

  async function load() {
    if (disposed) return;
    try {
      data = await api('GET', '/api/prices');
      clear(stale);
      if (data.stale) {
        stale.append(el('p', { class: 'notice' }, icon('warn', 16), 'ราคายังเป็นข้อมูลเก่า ระบบกำลังพยายามอัปเดต'));
      }
      updated.textContent = data.updatedAt ? 'อัปเดตล่าสุด ' + dateTime(data.updatedAt) : 'ยังไม่มีเวลาอัปเดต';
      renderTable();
    } catch (e) {
      if (e.status === 401) return;
      clear(tableWrap);
      tableWrap.append(el('div', { class: 'empty', text: e.message || 'โหลดราคาไม่สำเร็จ' }));
    }
  }

  search.addEventListener('input', renderTable);
  load();

  return {
    dispose() { disposed = true; }
  };
}

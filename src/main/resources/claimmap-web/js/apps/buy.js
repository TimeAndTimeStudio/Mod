import { el, clear, api, state, money, confirmModal, showError, toast, refreshStatus } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let shop = null;

  container.append(el('h1', { text: 'ซื้อจากร้าน' }));
  const note = el('p', { class: 'hint', text: 'ของที่ซื้อจะเข้ากล่องของก่อน แล้วเข้าเกมพิมพ์ /mail claim เพื่อรับ' });
  const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็ม' });
  const list = el('div', { class: 'list' });
  const stale = el('div');
  container.append(note, el('div', { class: 'toolbar' }, el('div', { class: 'field' }, search)), stale, list);

  function renderList() {
    if (disposed) return;
    clear(list);
    if (!shop) {
      list.append(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
      return;
    }
    const query = search.value.trim().toLowerCase();
    const entries = Object.entries(shop.items || {})
      .filter(([id, info]) => !query || id.toLowerCase().includes(query) || String(info.label || '').toLowerCase().includes(query))
      .sort((a, b) => String(a[1].label || a[0]).localeCompare(String(b[1].label || b[0]), 'th'));

    if (!entries.length) {
      list.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่ซื้อได้' }));
      return;
    }
    for (const [id, info] of entries) {
      const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
      const button = el('button', { class: 'primary', type: 'button', text: 'ซื้อ' });
      button.addEventListener('click', async () => {
        const n = Number(qty.value);
        if (!Number.isInteger(n) || n < 1) { toast('จำนวนต้องเป็นจำนวนเต็มตั้งแต่ 1', 'error'); return; }
        if (busy) return;
        const total = Number(info.buy) * n;
        const currency = shop.currency || state.currency;
        const ok = await confirmModal({
          title: 'ยืนยันการซื้อ',
          confirmText: 'ซื้อ',
          lines: [
            { label: 'ไอเท็ม', value: String(info.label || id) },
            { label: 'จำนวน', value: money(n) + ' ชิ้น' },
            { label: 'ราคารวม', value: money(total) + ' ' + currency },
            { label: 'ยอดคงเหลือตอนนี้', value: money(state.balance) + ' ' + currency },
            { label: 'ยอดคงเหลือหลังซื้อ', value: money(state.balance - total) + ' ' + currency }
          ]
        });
        if (!ok) return;
        busy = true;
        button.disabled = true;
        try {
          const data = await api('POST', '/api/shop/buy', { item: id, qty: n });
          toast('ซื้อแล้ว ' + money(data.bought) + ' ชิ้น ใช้ ' + money(data.spent) + ' ' + currency + ' — ของอยู่ในกล่องของ', 'ok');
          state.balance = data.balance;
          state.mailboxCount = data.mailboxCount;
          refreshStatus();
        } catch (e) {
          showError(e);
        } finally {
          busy = false;
          await load();
        }
      });
      list.append(el('div', { class: 'item' },
        el('div', { class: 'main' },
          el('div', { class: 'title', text: String(info.label || id) }),
          el('div', { class: 'sub', text: id + ' · ซื้อ ' + money(info.buy) + ' ' + (shop.currency || state.currency) + ' ต่อชิ้น' })),
        el('div', { class: 'actions' }, qty, button)));
    }
  }

  async function load() {
    if (disposed) return;
    try {
      shop = await api('GET', '/api/shop');
      clear(stale);
      if (shop.stale) {
        stale.append(el('p', { class: 'hint', text: '⚠️ ราคาที่แสดงเป็นข้อมูลเก่า ระบบจะอัปเดตเมื่อเชื่อมต่อได้' }));
      }
      if (typeof shop.mailboxCount === 'number') state.mailboxCount = shop.mailboxCount;
      renderList();
    } catch (e) {
      if (e.status === 401) return;
      clear(list);
      list.append(el('div', { class: 'empty', text: e.message || 'โหลดร้านค้าไม่สำเร็จ' }));
    }
  }

  search.addEventListener('input', renderList);
  load();

  return {
    dispose() { disposed = true; }
  };
}

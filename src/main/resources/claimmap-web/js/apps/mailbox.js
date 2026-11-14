import { el, clear, api, state, money, dateTime, confirmModal, showError, toast, refreshStatus } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let data = null;

  container.append(el('h1', { text: 'กล่องของ' }));
  const summary = el('p', { class: 'hint', text: 'ของที่ซื้อ/ได้จากประมูลจะรออยู่ที่นี่' });
  const claimButton = el('button', { class: 'primary', type: 'button', text: 'รับของทั้งหมด' });
  const list = el('div', { class: 'list' });
  container.append(summary, el('div', { class: 'row' }, claimButton), list);

  claimButton.addEventListener('click', async () => {
    if (busy) return;
    const ok = await confirmModal({
      title: 'ยืนยันรับของ',
      confirmText: 'รับของ',
      lines: [
        { label: 'รายการที่รอ', value: money(data ? data.count : 0) + ' รายการ' },
        { label: 'ปลายทาง', value: 'กระเป๋าของคุณในเกม' },
        { label: 'หมายเหตุ', value: 'ต้องออนไลน์ในเกมก่อน' }
      ]
    });
    if (!ok) return;
    busy = true;
    claimButton.disabled = true;
    try {
      const out = await api('POST', '/api/mailbox/claim', {});
      toast('รับแล้ว ' + money(out.claimed) + ' ชิ้น' + (out.remaining ? ' เหลือ ' + money(out.remaining) + ' ในกล่อง' : ''), 'ok');
      state.mailboxCount = out.remaining || 0;
      refreshStatus();
      await load();
    } catch (e) {
      if (e.status === 409 && e.code === 'player_offline') toast('ต้องเข้าเกมก่อนจึงรับของได้', 'error');
      else showError(e);
    } finally {
      busy = false;
      claimButton.disabled = false;
    }
  });

  function renderList() {
    if (disposed) return;
    clear(list);
    if (!data) {
      list.append(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
      return;
    }
    const entries = data.entries || [];
    summary.textContent = entries.length ? 'มีของรอรับ ' + money(entries.length) + ' รายการ' : 'ไม่มีของรอรับ';
    if (!entries.length) {
      list.append(el('div', { class: 'empty', text: 'กล่องว่าง' }));
      return;
    }
    for (const entry of entries) {
      list.append(el('div', { class: 'item' },
        el('div', { class: 'main' },
          el('div', { class: 'title', text: String(entry.label || entry.itemId) }),
          el('div', { class: 'sub', text: entry.itemId + ' × ' + money(entry.count) + ' · จาก ' + entry.from + ' · ' + dateTime(entry.t) })),
        el('span', { class: 'badge', text: money(entry.count) + ' ชิ้น' })));
    }
  }

  async function load() {
    if (disposed) return;
    try {
      data = await api('GET', '/api/mailbox');
      state.mailboxCount = data.count || 0;
      renderList();
    } catch (e) {
      if (e.status === 401) return;
      clear(list);
      list.append(el('div', { class: 'empty', text: e.message || 'โหลดกล่องของไม่สำเร็จ' }));
    }
  }

  load();

  return {
    dispose() { disposed = true; }
  };
}

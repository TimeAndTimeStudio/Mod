import { icon, itemSlot } from '../icons.js';
import { el, clear, api, state, money, dateTime, confirmModal, showError, toast, refreshStatus } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let data = null;

  const claimButton = el('button', { class: 'primary', type: 'button' },
    icon('mailbox', 14),
    el('span', { text: 'รับของทั้งหมดเข้าตัว' })
  );

  /* ADDED: Refresh button to quickly re-sync with server */
  const refreshBtn = el('button', { class: 'ghost', type: 'button', onClick: () => load() },
    icon('search', 14),
    el('span', { text: 'รีเฟรช' })
  );

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'กล่องรับของ' }),
        el('p', { class: 'hint', text: 'ไอเท็มจากการซื้อของในร้านค้าหรือชนะการประมูลจะถูกส่งมารอที่นี่' })),
      el('div', { style: 'display: flex; gap: 8px;' }, refreshBtn, claimButton)
    )
  );

  /* ADDED: Info alert explaining game requirement */
  const infoNotice = el('div', { class: 'card', style: 'padding: 12px 16px; margin-bottom: 16px;' },
    el('div', { style: 'display: flex; align-items: center; gap: 10px;' },
      icon('warn', 14),
      el('span', { class: 'hint', style: 'margin: 0;', text: 'ต้องออนไลน์ในเกมเพื่อรับไอเท็มเข้ากระเป๋าตัวละคร และควรตรวจสอบช่องว่างในตัวก่อนกดรับ' })
    )
  );

  const summary = el('p', { class: 'hint', text: 'กำลังโหลดข้อมูล…' });
  const list = el('div', { class: 'list' });
  container.append(infoNotice, summary, list);

  claimButton.addEventListener('click', async () => {
    if (busy) return;
    const count = data ? data.count : 0;
    if (count <= 0) return;

    const ok = await confirmModal({
      title: 'ยืนยันรับของทั้งหมด',
      confirmText: 'รับไอเท็มเข้าตัว',
      lines: [
        { label: 'จำนวนรายการที่รอ', value: money(count) + ' รายการ' },
        { label: 'ปลายทาง', value: 'กระเป๋าตัวละครในเกม' },
        { label: 'ข้อกำหนด', value: 'ต้องออนไลน์อยู่ในเซิร์ฟเวอร์ขณะกดรับ' }
      ]
    });
    if (!ok) return;

    busy = true;
    claimButton.disabled = true;
    try {
      const out = await api('POST', '/api/mailbox/claim', {});
      if (out.inventoryFull) {
        toast('รับได้ ' + money(out.claimed) + ' ชิ้น แต่ช่องเก็บของเต็ม! ยังเหลือ ' + money(out.remaining) + ' ในกล่อง', 'warn');
      } else {
        toast('รับของเข้าตัวแล้ว ' + money(out.claimed) + ' ชิ้น' + (out.remaining ? ' เหลืออีก ' + money(out.remaining) + ' ชิ้น' : ''), 'ok');
      }
      state.mailboxCount = out.remaining || 0;
      refreshStatus();
      await load();
    } catch (e) {
      if (e.status === 409 && e.code === 'player_offline') {
        toast('กรุณาออนไลน์ในเกมก่อนกดรับของ', 'error');
      } else if (e.status === 409 && e.code === 'inventory_full') {
        toast(e.message || 'ช่องเก็บของในตัวไม่พอ กรุณาเคลียร์ช่องว่างในกระเป๋าก่อน', 'error');
      } else {
        showError(e);
      }
    } finally {
      busy = false;
      claimButton.disabled = false;
    }
  });

  function renderList() {
    if (disposed) return;
    clear(list);
    if (!data) {
      list.append(el('div', { class: 'empty', text: 'กำลังโหลดข้อมูลกล่องรับของ…' }));
      return;
    }
    const entries = data.entries || [];
    summary.textContent = entries.length ? 'มีไอเท็มรอรับทั้งหมด ' + money(entries.length) + ' รายการ' : 'ไม่มีไอเท็มรอรับในกล่องขณะนี้';
    if (!entries.length) {
      list.append(
        el('div', { class: 'empty' },
          el('div', { style: 'font-weight: 600; margin-bottom: 4px;', text: 'กล่องรับของว่างเปล่า' }),
          el('div', { class: 'hint', text: 'เมื่อคุณสั่งซื้อของจากตลาดหรือชนะการประมูล ไอเท็มจะปรากฏที่นี่' })
        )
      );
      claimButton.disabled = true;
      return;
    }
    claimButton.disabled = false;

    for (const entry of entries) {
      list.append(
        el('div', { class: 'item' },
          itemSlot(entry.itemId, entry.count, 40),
          el('div', { class: 'main' },
            el('div', { class: 'title', text: String(entry.label || entry.itemId) }),
            el('div', { class: 'sub', text: entry.itemId + ' × ' + money(entry.count) + ' · ที่มา: ' + (entry.from || 'ระบบ') + ' · ' + dateTime(entry.t) })
          ),
          el('span', { class: 'badge', text: money(entry.count) + ' ชิ้น' })
        )
      );
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
      list.append(el('div', { class: 'empty', text: e.message || 'ไม่สามารถโหลดกล่องรับของได้' }));
    }
  }

  load();

  return {
    dispose() { disposed = true; }
  };
}

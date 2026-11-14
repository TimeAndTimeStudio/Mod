import { el, clear, api, state, money, dateTime, confirmModal, showError, toast, randomId, refreshStatus } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let data = null;

  container.append(el('h1', { text: 'กระเป๋าเงิน' }));
  const balanceLine = el('div', { class: 'card' },
    el('div', { class: 'hint', text: 'ยอดคงเหลือ' }),
    el('div', { style: 'font-size:28px;font-weight:700', text: money(state.balance) + ' ' + state.currency }));
  const toInput = el('input', { type: 'text', placeholder: 'ชื่อผู้รับ', maxLength: 32, autocomplete: 'off' });
  const amountInput = el('input', { type: 'number', placeholder: 'จำนวน', min: 1, inputMode: 'numeric' });
  const payButton = el('button', { class: 'primary', type: 'button', text: 'โอนเงิน' });
  const form = el('div', { class: 'card' },
    el('h2', { text: 'โอนเงินให้ผู้เล่น' }),
    el('label', { text: 'ผู้รับ' }), toInput,
    el('label', { text: 'จำนวน (' + state.currency + ')' }), amountInput,
    el('div', { class: 'row' }, payButton));
  const history = el('div', { class: 'list' });
  container.append(balanceLine, form, el('h2', { text: 'ประวัติล่าสุด' }), history);

  payButton.addEventListener('click', async () => {
    if (busy) return;
    const to = toInput.value.trim();
    const amount = Number(amountInput.value);
    if (!to) { toast('กรอกชื่อผู้รับ', 'error'); return; }
    if (!Number.isInteger(amount) || amount < 1) { toast('จำนวนต้องเป็นจำนวนเต็มตั้งแต่ 1', 'error'); return; }
    const currency = (data && data.currency) || state.currency;
    const ok = await confirmModal({
      title: 'ยืนยันการโอนเงิน',
      confirmText: 'โอน',
      lines: [
        { label: 'ผู้รับ', value: to },
        { label: 'จำนวน', value: money(amount) + ' ' + currency },
        { label: 'ยอดคงเหลือตอนนี้', value: money(state.balance) + ' ' + currency },
        { label: 'ยอดคงเหลือหลังโอน', value: money(state.balance - amount) + ' ' + currency }
      ]
    });
    if (!ok) return;
    busy = true;
    payButton.disabled = true;
    try {
      const out = await api('POST', '/api/pay', { to, amount }, { requestId: randomId() });
      toast('โอนแล้ว ' + money(amount) + ' ' + currency + ' ให้ ' + to, 'ok');
      state.balance = out.balance;
      toInput.value = '';
      amountInput.value = '';
      refreshStatus();
      await load();
    } catch (e) {
      showError(e);
    } finally {
      busy = false;
      payButton.disabled = false;
    }
  });

  function renderHistory() {
    if (disposed) return;
    clear(history);
    if (!data) {
      history.append(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
      return;
    }
    const entries = data.history || [];
    if (!entries.length) {
      history.append(el('div', { class: 'empty', text: 'ยังไม่มีรายการ' }));
      return;
    }
    for (const entry of entries) {
      const incoming = entry.dir === 'in';
      history.append(el('div', { class: 'item' },
        el('div', { class: 'main' },
          el('div', { class: 'title', text: (incoming ? '+ ' : '− ') + money(entry.amount) + ' ' + (data.currency || state.currency) }),
          el('div', { class: 'sub', text: (incoming ? 'จาก ' : 'ถึง ') + (entry.other || 'ระบบ') + ' · ' + entry.reason + ' · ' + dateTime(entry.t) })),
        el('span', { class: 'badge ' + (incoming ? 'ok' : 'muted'), text: incoming ? 'รับ' : 'จ่าย' })));
    }
  }

  async function load() {
    if (disposed) return;
    try {
      data = await api('GET', '/api/wallet');
      state.balance = data.balance || 0;
      state.currency = data.currency || state.currency;
      if (typeof data.mailboxCount === 'number') state.mailboxCount = data.mailboxCount;
      balanceLine.lastChild.textContent = money(state.balance) + ' ' + state.currency;
      renderHistory();
    } catch (e) {
      if (e.status === 401) return;
      clear(history);
      history.append(el('div', { class: 'empty', text: e.message || 'โหลดกระเป๋าเงินไม่สำเร็จ' }));
    }
  }

  load();

  return {
    dispose() { disposed = true; }
  };
}

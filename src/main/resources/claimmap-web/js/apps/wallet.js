import { icon } from '../icons.js';
import { el, clear, api, state, money, dateTime, confirmModal, showError, toast, randomId, refreshStatus } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let data = null;

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'กระเป๋าเงิน' }),
        el('p', { class: 'hint', text: 'ตรวจสอบยอดเงินคงเหลือ ประวัติธุรกรรม และโอนเงินให้ผู้เล่นอื่น' })))
  );

  const balanceVal = el('div', { class: 'stat-value', text: money(state.balance) });
  const balanceUnit = el('span', { class: 'stat-unit', text: state.currency || 'เหรียญ' });
  const balanceCard = el('div', { class: 'card' },
    el('div', { class: 'stat-header' },
      icon('coin', 16),
      el('span', { text: 'ยอดเงินคงเหลือในบัญชี' })),
    el('div', { class: 'stat-num', style: 'margin-top: 8px;' }, balanceVal, balanceUnit));

  const toInput = el('input', { type: 'text', placeholder: 'ชื่อผู้รับ', maxLength: 32, autocomplete: 'off' });
  const amountInput = el('input', { type: 'number', placeholder: 'จำนวน', min: 1, inputMode: 'numeric' });
  const payButton = el('button', { class: 'primary', type: 'button' },
    icon('arrow', 14),
    el('span', { text: 'โอนเงิน' })
  );

  const form = el('div', { class: 'card stack' },
    el('h2', { text: 'โอนเงินให้ผู้เล่น' }),
    el('div', { class: 'form-grid' },
      el('div', null, el('label', { text: 'ผู้รับ' }), toInput),
      el('div', null, el('label', { text: 'จำนวน (' + (state.currency || 'เหรียญ') + ')' }), amountInput),
      el('div', null, payButton)
    )
  );

  const history = el('div', { class: 'list' });
  container.append(balanceCard, form, el('h2', { text: 'ประวัติธุรกรรมล่าสุด' }), history);

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
      history.append(el('div', { class: 'empty', text: 'ยังไม่มีประวัติการทำรายการ' }));
      return;
    }
    for (const entry of entries) {
      const incoming = entry.dir === 'in';
      history.append(el('div', { class: 'item' },
        el('div', { class: 'main' },
          el('div', { class: 'title', style: incoming ? 'color: var(--ok)' : 'color: var(--orange-core)', text: (incoming ? '+ ' : '- ') + money(entry.amount) + ' ' + (data.currency || state.currency) }),
          el('div', { class: 'sub', text: (incoming ? 'จาก ' : 'ถึง ') + (entry.other || 'ระบบ') + ' · ' + entry.reason + ' · ' + dateTime(entry.t) })),
        el('span', { class: 'badge ' + (incoming ? 'ok' : 'muted'), text: incoming ? 'รับเข้า' : 'จ่ายออก' })));
    }
  }

  async function load() {
    if (disposed) return;
    try {
      data = await api('GET', '/api/wallet');
      state.balance = data.balance || 0;
      state.currency = data.currency || state.currency;
      if (typeof data.mailboxCount === 'number') state.mailboxCount = data.mailboxCount;
      balanceVal.textContent = money(state.balance);
      balanceUnit.textContent = state.currency || 'เหรียญ';
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

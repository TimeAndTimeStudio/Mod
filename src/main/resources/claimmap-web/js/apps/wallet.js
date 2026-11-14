import { icon } from '../icons.js';
import { el, clear, api, state, money, dateTime, confirmModal, showError, toast, randomId, refreshStatus } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let data = null;

  /* App page header */
  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'กระเป๋าเงิน' }),
        el('p', { class: 'hint', text: 'ตรวจสอบยอดเงินคงเหลือ โอนเหรียญให้ผู้เล่น และดูประวัติธุรกรรม' }))
    )
  );

  /* Balance metric display */
  const balanceVal = el('div', { class: 'stat-value', text: money(state.balance) });
  const balanceUnit = el('span', { class: 'stat-unit', text: state.currency || 'เหรียญ' });
  const balanceCard = el('div', { class: 'card stat-card' },
    el('div', { class: 'stat-header' },
      icon('coin', 14),
      el('span', { text: 'ยอดเงินคงเหลือในบัญชี' })
    ),
    el('div', { class: 'stat-num' }, balanceVal, balanceUnit)
  );

  /* Transfer Form */
  const toInput = el('input', { type: 'text', placeholder: 'ชื่อผู้เล่นในเซิร์ฟเวอร์', maxLength: 32, autocomplete: 'off' });
  const amountInput = el('input', { type: 'number', placeholder: 'จำนวนเงิน', min: 1, inputMode: 'numeric' });
  const payButton = el('button', { class: 'primary', type: 'button' },
    icon('arrow', 14),
    el('span', { text: 'ยืนยันโอนเงิน' })
  );

  /* ADDED: Quick amount preset chips for faster workflow */
  const quickChips = el('div', { style: 'display: flex; gap: 8px; margin-top: 8px;' },
    ...[100, 500, 1000, 5000].map(amt => {
      const chip = el('button', {
        type: 'button',
        class: 'ghost',
        style: 'padding: 4px 10px; font-size: 11px; font-family: var(--font-mono);',
        text: '+' + amt,
        onClick: () => {
          const curr = Number(amountInput.value) || 0;
          amountInput.value = curr + amt;
        }
      });
      return chip;
    })
  );

  const form = el('div', { class: 'card' },
    el('h2', { text: 'โอนเงินให้ผู้เล่น' }),
    el('p', { class: 'hint', text: 'โอนเหรียญเข้าบัญชีผู้เล่นอื่นในเซิร์ฟเวอร์ทันที ไม่สามารถยกเลิกรายการได้' }),
    el('div', { style: 'display: grid; grid-template-columns: repeat(auto-fit, minmax(240px, 1fr)); gap: 16px; margin-top: 16px;' },
      el('div', { class: 'field' },
        el('label', { text: 'ชื่อผู้รับ (RECIPIENT)' }),
        toInput
      ),
      el('div', { class: 'field' },
        el('label', { text: 'จำนวนเงิน (' + (state.currency || 'เหรียญ') + ')' }),
        amountInput,
        quickChips
      )
    ),
    el('div', { class: 'form-actions', style: 'margin-top: 16px;' }, payButton)
  );

  /* Transaction History */
  const historyList = el('div', { class: 'list' });
  /* ADDED: Filter controls for incoming / outgoing */
  let activeFilter = 'all';
  const filterWrap = el('div', { class: 'tabs', style: 'margin-top: 24px;' },
    el('button', {
      type: 'button',
      class: 'active',
      text: 'ทั้งหมด',
      onClick: (e) => setFilter('all', e.target)
    }),
    el('button', {
      type: 'button',
      text: 'รับเข้า (+)',
      onClick: (e) => setFilter('in', e.target)
    }),
    el('button', {
      type: 'button',
      text: 'จ่ายออก (-)',
      onClick: (e) => setFilter('out', e.target)
    })
  );

  function setFilter(f, btn) {
    activeFilter = f;
    filterWrap.querySelectorAll('button').forEach(b => b.classList.remove('active'));
    if (btn) btn.classList.add('active');
    renderHistory();
  }

  container.append(
    balanceCard,
    form,
    filterWrap,
    historyList
  );

  payButton.addEventListener('click', async () => {
    if (busy) return;
    const to = toInput.value.trim();
    const amount = Number(amountInput.value);
    if (!to) { toast('กรุณาระบุชื่อผู้รับ', 'error'); return; }
    if (!Number.isInteger(amount) || amount < 1) { toast('จำนวนเงินต้องเป็นจำนวนเต็มบวกตั้งแต่ 1 ขึ้นไป', 'error'); return; }
    if (amount > state.balance) { toast('ยอดเงินในบัญชีไม่เพียงพอ', 'error'); return; }

    const currency = (data && data.currency) || state.currency;
    const ok = await confirmModal({
      title: 'ยืนยันการโอนเงิน',
      confirmText: 'ยืนยันโอน',
      lines: [
        { label: 'ผู้รับ', value: to },
        { label: 'จำนวน', value: money(amount) + ' ' + currency },
        { label: 'ยอดเงินก่อนโอน', value: money(state.balance) + ' ' + currency },
        { label: 'ยอดเงินคงเหลือหลังโอน', value: money(state.balance - amount) + ' ' + currency }
      ]
    });
    if (!ok) return;

    busy = true;
    payButton.disabled = true;
    try {
      const out = await api('POST', '/api/pay', { to, amount }, { requestId: randomId() });
      toast('โอนเงิน ' + money(amount) + ' ' + currency + ' ให้คุณ ' + to + ' สำเร็จ', 'ok');
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
    clear(historyList);
    if (!data) {
      historyList.append(el('div', { class: 'empty', text: 'กำลังโหลดประวัติธุรกรรม…' }));
      return;
    }
    let entries = data.history || [];
    if (activeFilter === 'in') entries = entries.filter(e => e.dir === 'in');
    if (activeFilter === 'out') entries = entries.filter(e => e.dir === 'out');

    if (!entries.length) {
      historyList.append(el('div', { class: 'empty', text: 'ไม่พบรายการธุรกรรม' }));
      return;
    }

    for (const entry of entries) {
      const incoming = entry.dir === 'in';
      const itemEl = el('div', { class: 'item' },
        el('div', { class: 'main' },
          el('div', {
            class: 'title',
            style: incoming ? 'color: #2ECC71;' : 'color: var(--accent);',
            text: (incoming ? '+ ' : '- ') + money(entry.amount) + ' ' + (data.currency || state.currency)
          }),
          el('div', { class: 'sub', text: (incoming ? 'ได้รับจาก ' : 'โอนให้ ') + (entry.other || 'ระบบ') + ' · ' + (entry.reason || 'ธุรกรรม') + ' · ' + dateTime(entry.t) })
        ),
        el('span', { class: 'badge', text: incoming ? 'รับเข้า' : 'จ่ายออก' })
      );
      historyList.append(itemEl);
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
      clear(historyList);
      historyList.append(el('div', { class: 'empty', text: e.message || 'โหลดกระเป๋าเงินไม่สำเร็จ' }));
    }
  }

  load();

  return {
    dispose() { disposed = true; }
  };
}

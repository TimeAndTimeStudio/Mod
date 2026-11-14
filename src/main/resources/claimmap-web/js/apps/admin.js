import { el, clear, api, state, money, dateTime, confirmModal, showError, toast } from '../core.js';

const TABS = [
  ['overview', 'ภาพรวม'],
  ['money', 'เงิน'],
  ['prices', 'ราคา'],
  ['auctions', 'ประมูล'],
  ['accounts', 'บัญชี'],
  ['audit', 'Audit']
];

export function render(container) {
  let disposed = false;
  let tab = 'overview';
  let overview = null;

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'แผงควบคุมระบบแอดมิน' }),
        el('p', { class: 'hint', text: 'เครื่องมือจัดการระบบเศรษฐกิจ ราคากลาง การประมูล และบัญชีผู้เล่น' })))
  );
  const tabsBar = el('div', { class: 'tabs' });
  const body = el('div');
  container.append(tabsBar, body);

  function reasonField(placeholder) {
    return el('input', { type: 'text', placeholder: placeholder || 'เหตุผล (3-200 ตัวอักษร)', maxLength: 200 });
  }

  async function run(button, fn) {
    if (button) button.disabled = true;
    try {
      await fn();
    } catch (e) {
      showError(e);
    } finally {
      if (button) button.disabled = false;
    }
  }

  function card(title, ...children) {
    return el('div', { class: 'card' }, el('h2', { text: title }), ...children);
  }

  function renderTabs() {
    clear(tabsBar);
    for (const [key, label] of TABS) {
      tabsBar.append(el('button', {
        type: 'button',
        class: key === tab ? 'active' : '',
        text: label,
        onClick: () => { tab = key; renderTabs(); renderBody(); }
      }));
    }
  }

  async function loadOverview() {
    overview = await api('GET', '/api/admin/overview');
  }

  function totalsCard() {
    if (!overview) return el('div', { class: 'empty', text: 'กำลังโหลด…' });
    const t = overview.totals || {};
    const market = overview.market || {};
    return card('ภาพรวมระบบ',
      el('ul', { class: 'modal-lines' },
        el('li', null, el('span', { text: 'ยอดผู้เล่นรวม' }), el('span', { text: money(t.balances) })),
        el('li', null, el('span', { text: 'เงินใน escrow' }), el('span', { text: money(t.escrow) })),
        el('li', null, el('span', { text: 'systemNet' }), el('span', { text: money(t.systemNet) })),
        el('li', null, el('span', { text: 'invariant' }), el('span', { text: t.invariantOk ? 'ปกติ' : 'ผิดปกติ!' })),
        el('li', null, el('span', { text: 'ตลาด' }), el('span', {
          text: (market.economyDisabled ? 'เศรษฐกิจปิด ' : 'เศรษฐกิจเปิด ')
            + (market.shopDisabled ? '· ร้านปิด ' : '')
            + (market.auctionsDisabled ? '· ประมูลปิด ' : '')
        })),
        el('li', null, el('span', { text: 'ราคา' }), el('span', {
          text: market.pricesStale ? 'ข้อมูลเก่า' : 'ปกติ'
        }))));
  }

  function playersTable() {
    if (!overview) return el('div');
    const tbody = el('tbody');
    for (const p of overview.players || []) {
      tbody.append(el('tr', null,
        el('td', { text: String(p.name || '') }),
        el('td', { class: 'num', text: money(p.balance) }),
        el('td', { text: p.frozen ? 'ระงับ' : '-' }),
        el('td', { text: p.online ? 'ออนไลน์' : 'ออฟไลน์' }),
        el('td', { class: 'num', text: money(p.mailboxCount) })));
    }
    return el('table', null,
      el('thead', null, el('tr', null,
        el('th', { text: 'ผู้เล่น' }),
        el('th', { class: 'num', text: 'เงิน' }),
        el('th', { text: 'สถานะ' }),
        el('th', { text: 'เกม' }),
        el('th', { class: 'num', text: 'กล่องของ' }))),
      tbody);
  }

  function renderOverview(target) {
    loadOverview().then(() => {
      if (disposed) return;
      clear(target);
      target.append(totalsCard(), card('ผู้เล่น', playersTable()));
    }).catch(e => {
      clear(target);
      target.append(el('div', { class: 'empty', text: e.message || 'โหลดภาพรวมไม่สำเร็จ' }));
    });
    target.append(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
  }

  function renderMoney(target) {
    const player = el('input', { type: 'text', placeholder: 'ชื่อผู้เล่น', maxLength: 32 });
    const op = el('select', null,
      el('option', { value: 'give', text: 'ให้เงิน (give)' }),
      el('option', { value: 'take', text: 'หักเงิน (take)' }),
      el('option', { value: 'set', text: 'ตั้งยอด (set)' }));
    const amount = el('input', { type: 'number', min: 0, inputMode: 'numeric' });
    const reason = reasonField();
    const button = el('button', { class: 'primary', type: 'button', text: 'ทำรายการ' });
    button.addEventListener('click', async () => {
      const name = player.value.trim();
      const value = Number(amount.value);
      if (!name) { toast('กรอกชื่อผู้เล่น', 'error'); return; }
      if (!Number.isInteger(value) || value < 0) { toast('จำนวนต้องเป็นจำนวนเต็ม', 'error'); return; }
      if (reason.value.trim().length < 3) { toast('กรอกเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
      const ok = await confirmModal({
        title: 'ยืนยันการจัดการเงิน',
        confirmText: 'ยืนยัน',
        lines: [
          { label: 'ผู้เล่น', value: name },
          { label: 'การกระทำ', value: op.value },
          { label: 'จำนวน', value: money(value) + ' ' + state.currency },
          { label: 'เหตุผล', value: reason.value.trim() }
        ]
      });
      if (!ok) return;
      await run(button, async () => {
        const out = await api('POST', '/api/admin/money', { player: name, op: op.value, amount: value, reason: reason.value.trim() });
        toast('สำเร็จ ยอดใหม่ ' + money(out.balance) + ' ' + state.currency, 'ok');
        reason.value = '';
      });
    });
    target.append(card('ปรับเงินผู้เล่น',
      el('label', { text: 'ผู้เล่น' }), player,
      el('label', { text: 'การกระทำ' }), op,
      el('label', { text: 'จำนวน' }), amount,
      el('label', { text: 'เหตุผล' }), reason,
      el('div', { class: 'row' }, button)));
  }

  function renderPrices(target) {
    const item = el('input', { type: 'text', placeholder: 'item id เช่น diamond', maxLength: 100 });
    const base = el('input', { type: 'number', min: 1, placeholder: 'base (เว้นว่าง = ลบ override)' });
    const reason = reasonField();
    const setButton = el('button', { class: 'primary', type: 'button', text: 'ตั้ง/ลบ override' });
    const blockButton = el('button', { class: 'danger', type: 'button', text: 'บล็อก' });
    const unblockButton = el('button', { class: 'secondary', type: 'button', text: 'ปลดบล็อก' });
    const refreshButton = el('button', { class: 'secondary', type: 'button', text: 'ดึงราคาใหม่' });

    function requireReason() {
      if (reason.value.trim().length < 3) { toast('กรอกเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return false; }
      return true;
    }

    setButton.addEventListener('click', async () => {
      if (!requireReason()) return;
      const id = item.value.trim();
      if (!id) { toast('กรอก item id', 'error'); return; }
      const value = base.value.trim() === '' ? null : Number(base.value);
      if (value !== null && (!Number.isInteger(value) || value < 1)) { toast('base ต้องเป็นจำนวนเต็ม ≥ 1', 'error'); return; }
      await run(setButton, async () => {
        await api('POST', '/api/admin/prices/override', { item: id, base: value, reason: reason.value.trim() });
        toast(value === null ? 'ลบ override แล้ว' : 'ตั้ง override = ' + money(value), 'ok');
      });
    });
    blockButton.addEventListener('click', async () => {
      if (!requireReason()) return;
      const id = item.value.trim();
      if (!id) { toast('กรอก item id', 'error'); return; }
      await run(blockButton, async () => {
        await api('POST', '/api/admin/prices/block', { item: id, blocked: true, reason: reason.value.trim() });
        toast('บล็อก ' + id + ' แล้ว', 'ok');
      });
    });
    unblockButton.addEventListener('click', async () => {
      if (!requireReason()) return;
      const id = item.value.trim();
      if (!id) { toast('กรอก item id', 'error'); return; }
      await run(unblockButton, async () => {
        await api('POST', '/api/admin/prices/block', { item: id, blocked: false, reason: reason.value.trim() });
        toast('ปลดบล็อก ' + id + ' แล้ว', 'ok');
      });
    });
    refreshButton.addEventListener('click', async () => {
      await run(refreshButton, async () => {
        const out = await api('POST', '/api/admin/prices/refresh', {});
        toast(out.ok ? 'ดึงราคาใหม่สำเร็จ' : (out.message || 'ดึงราคาไม่สำเร็จ'), out.ok ? 'ok' : 'error');
      });
    });

    target.append(card('จัดการราคา',
      el('label', { text: 'item id' }), item,
      el('label', { text: 'base (ราคากลาง)' }), base,
      el('label', { text: 'เหตุผล' }), reason,
      el('div', { class: 'row' }, setButton, blockButton, unblockButton),
      el('div', { class: 'row' }, refreshButton)));
  }

  function renderAuctions(target) {
    const list = el('div', { class: 'list' });
    target.append(list);
    api('GET', '/api/admin/auctions').then(data => {
      if (disposed) return;
      clear(list);
      const rows = data.auctions || [];
      if (!rows.length) { list.append(el('div', { class: 'empty', text: 'ไม่มีรายการประมูล' })); return; }
      for (const a of rows) {
        const reason = reasonField('เหตุผลที่ยกเลิก');
        const cancel = el('button', { class: 'danger', type: 'button', text: 'ยกเลิก' });
        cancel.disabled = a.status !== 'ACTIVE';
        cancel.addEventListener('click', async () => {
          if (reason.value.trim().length < 3) { toast('กรอกเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
          const ok = await confirmModal({
            title: 'ยืนยันยกเลิกประมูล',
            confirmText: 'ยกเลิก',
            danger: true,
            lines: [
              { label: 'รายการ', value: a.id },
              { label: 'ไอเท็ม', value: String(a.label || a.itemId) },
              { label: 'ผู้ขาย', value: a.sellerName },
              { label: 'ผู้นำ', value: a.highBidderName || '-' },
              { label: 'ยอดสูงสุด', value: money(a.highBid) }
            ]
          });
          if (!ok) return;
          await run(cancel, async () => {
            await api('POST', '/api/admin/auctions/' + encodeURIComponent(a.id) + '/cancel', { reason: reason.value.trim() });
            toast('ยกเลิกและคืนเงิน/ของแล้ว', 'ok');
            renderBody();
          });
        });
        list.append(el('div', { class: 'item' },
          el('div', { class: 'main' },
            el('div', { class: 'title', text: String(a.label || a.itemId) + ' · ' + a.status }),
            el('div', { class: 'sub', text: a.id + ' · ผู้ขาย ' + a.sellerName + ' · ยอดสูงสุด ' + money(a.highBid) + ' · ผู้นำ ' + (a.highBidderName || '-') + ' · จบ ' + dateTime(a.endsAt) })),
          el('div', { class: 'actions' }, reason, cancel)));
      }
    }).catch(e => {
      clear(list);
      list.append(el('div', { class: 'empty', text: e.message || 'โหลดประมูลไม่สำเร็จ' }));
    });
  }

  function renderAccounts(target) {
    const list = el('div', { class: 'list' });
    target.append(list);
    loadOverview().then(() => {
      if (disposed) return;
      clear(list);
      for (const p of overview.players || []) {
        const reason = reasonField('เหตุผล');
        const freeze = el('button', { class: p.frozen ? 'secondary' : 'danger', type: 'button', text: p.frozen ? 'คืนสิทธิ์' : 'ระงับ' });
        const reset = el('button', { class: 'danger', type: 'button', text: 'รีเซ็ตรหัส' });
        freeze.addEventListener('click', async () => {
          if (reason.value.trim().length < 3) { toast('กรอกเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
          await run(freeze, async () => {
            await api('POST', '/api/admin/accounts/' + encodeURIComponent(p.name) + '/freeze', { frozen: !p.frozen, reason: reason.value.trim() });
            toast((p.frozen ? 'คืนสิทธิ์ ' : 'ระงับ ') + p.name + ' แล้ว', 'ok');
            renderBody();
          });
        });
        reset.addEventListener('click', async () => {
          if (reason.value.trim().length < 3) { toast('กรอกเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
          const ok = await confirmModal({
            title: 'ยืนยันรีเซ็ตรหัสผ่าน',
            confirmText: 'รีเซ็ต',
            danger: true,
            lines: [
              { label: 'ผู้เล่น', value: p.name },				{ label: 'ผลลัพธ์', value: 'ต้องเข้าเกมและตั้งรหัสในหน้าต่างให้ทันที' },
              { label: 'คำเตือน', value: 'ระหว่างนี้ใครสวมชื่ออาจตั้งรหัสได้' }
            ]
          });
          if (!ok) return;
          await run(reset, async () => {
            await api('POST', '/api/admin/accounts/' + encodeURIComponent(p.name) + '/reset-password', { reason: reason.value.trim() });
            toast('ลบรหัสของ ' + p.name + ' แล้ว — แจ้งให้เข้าเกมทันที', 'ok');
          });
        });
        list.append(el('div', { class: 'item' },
          el('div', { class: 'main' },
            el('div', { class: 'title', text: String(p.name || '') }),
            el('div', { class: 'sub', text: 'เงิน ' + money(p.balance) + ' · ' + (p.frozen ? 'ถูกระงับ' : 'ปกติ') + ' · ' + (p.online ? 'ออนไลน์' : 'ออฟไลน์') })),
          el('div', { class: 'actions' }, reason, freeze, reset)));
      }
    }).catch(e => {
      clear(list);
      list.append(el('div', { class: 'empty', text: e.message || 'โหลดบัญชีไม่สำเร็จ' }));
    });
  }

  function renderAudit(target) {
    const list = el('div', { class: 'list' });
    const reload = el('button', { class: 'secondary', type: 'button', text: 'โหลดใหม่' });
    const load = () => {
      api('GET', '/api/admin/audit?limit=100').then(data => {
        if (disposed) return;
        clear(list);
        const lines = data.lines || [];
        if (!lines.length) { list.append(el('div', { class: 'empty', text: 'ยังไม่มีบันทึก' })); return; }
        for (const line of lines.slice().reverse()) {
          const text = (line && line.action ? line.action : '?')
            + ' · ' + (line.actor || '?')
            + ' · ' + (line.ok ? 'สำเร็จ' : 'ถูกปฏิเสธ')
            + ' · ' + dateTime(line.t)
            + (line.ip ? ' · ' + line.ip : '');
          list.append(el('div', { class: 'item' },
            el('div', { class: 'main' },
              el('div', { class: 'title', text }),
              el('div', { class: 'sub mono', text: JSON.stringify(line.details || {}) }))));
        }
      }).catch(e => {
        clear(list);
        list.append(el('div', { class: 'empty', text: e.message || 'โหลด audit ไม่สำเร็จ' }));
      });
    };
    reload.addEventListener('click', load);
    target.append(el('div', { class: 'row' }, reload), list);
    load();
  }

  function renderBody() {
    if (disposed) return;
    clear(body);
    if (tab === 'overview') renderOverview(body);
    else if (tab === 'money') renderMoney(body);
    else if (tab === 'prices') renderPrices(body);
    else if (tab === 'auctions') renderAuctions(body);
    else if (tab === 'accounts') renderAccounts(body);
    else renderAudit(body);
  }

  renderTabs();
  renderBody();

  return {
    dispose() { disposed = true; }
  };
}

import { icon } from '../icons.js';
import { el, clear, api, state, money, dateTime, confirmModal, showError, toast } from '../core.js';

const TABS = [
  ['overview', 'ภาพรวมระบบ'],
  ['money', 'จัดการเงิน'],
  ['prices', 'ราคากลาง'],
  ['auctions', 'การประมูล'],
  ['accounts', 'บัญชีผู้เล่น'],
  ['audit', 'บันทึก Audit']
];

export function render(container) {
  let disposed = false;
  let tab = 'overview';
  let overview = null;

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'แผงควบคุมระบบแอดมิน' }),
        el('p', { class: 'hint', text: 'เครื่องมือจัดการระบบเศรษฐกิจ ราคากลาง การประมูล และบัญชีผู้เล่น Time SMP' })))
  );
  const tabsBar = el('div', { class: 'tabs', style: 'margin-bottom: 20px;' });
  const body = el('div');
  container.append(tabsBar, body);

  function reasonField(placeholder) {
    return el('input', { type: 'text', placeholder: placeholder || 'ระบุเหตุผล (อย่างน้อย 3 ตัวอักษร)', maxLength: 200 });
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
    return el('div', { class: 'card', style: 'margin-bottom: 16px;' }, el('h2', { text: title }), ...children);
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
    if (!overview) return el('div', { class: 'empty', text: 'กำลังโหลดข้อมูล…' });
    const t = overview.totals || {};
    const market = overview.market || {};

    /* ADDED: Structured stat-grid instead of raw modal-lines list */
    return el('div', { style: 'margin-bottom: 20px;' },
      el('div', { class: 'stat-grid' },
        el('div', { class: 'card stat-card' },
          el('div', { class: 'stat-header' }, icon('coin', 14), el('span', { text: 'ยอดเงินผู้เล่นรวม' })),
          el('div', { class: 'stat-num' }, money(t.balances), el('span', { class: 'stat-unit', text: state.currency || 'เหรียญ' }))
        ),
        el('div', { class: 'card stat-card' },
          el('div', { class: 'stat-header' }, icon('coin', 14), el('span', { text: 'เงินในระบบ ESCROW' })),
          el('div', { class: 'stat-num' }, money(t.escrow), el('span', { class: 'stat-unit', text: state.currency || 'เหรียญ' }))
        ),
        el('div', { class: 'card stat-card' },
          el('div', { class: 'stat-header' }, icon('shop', 14), el('span', { text: 'SYSTEM NET' })),
          el('div', { class: 'stat-num' }, money(t.systemNet), el('span', { class: 'stat-unit', text: state.currency || 'เหรียญ' }))
        ),
        el('div', { class: 'card stat-card' },
          el('div', { class: 'stat-header' }, icon('check', 14), el('span', { text: 'ความถูกต้องของระบบ (INVARIANT)' })),
          el('div', { class: 'stat-num', style: t.invariantOk ? 'color: #2ECC71;' : 'color: var(--accent);' }, t.invariantOk ? 'ปกติ (PASS)' : 'ผิดปกติ (FAIL)')
        )
      ),
      el('div', { class: 'card', style: 'margin-top: 16px; padding: 12px 16px;' },
        el('div', { class: 'hint', style: 'margin: 0;' },
          'สถานะระบบตลาด: ',
          el('strong', { style: 'color: var(--text);', text: (market.economyDisabled ? 'ปิด ' : 'เปิด ') + (market.shopDisabled ? '· ร้านปิด ' : '') + (market.auctionsDisabled ? '· ประมูลปิด ' : '') }),
          ' | ข้อมูลราคา: ',
          el('strong', { style: 'color: var(--text);', text: market.pricesStale ? 'เก่า (STALE)' : 'อัปเดตแล้ว' })
        )
      )
    );
  }

  function playersTable() {
    if (!overview) return el('div');
    const tbody = el('tbody');
    for (const p of overview.players || []) {
      tbody.append(el('tr', null,
        el('td', { style: 'font-weight: 600;', text: String(p.name || '') }),
        el('td', { class: 'num', text: money(p.balance) }),
        el('td', null, el('span', { class: 'badge', text: p.frozen ? 'ระงับ' : 'ปกติ' })),
        el('td', null, el('span', { class: 'badge', text: p.online ? 'ออนไลน์' : 'ออฟไลน์' })),
        el('td', { class: 'num', text: money(p.mailboxCount) })));
    }
    return el('table', null,
      el('thead', null, el('tr', null,
        el('th', { text: 'ชื่อผู้เล่น' }),
        el('th', { class: 'num', text: 'เงินคงเหลือ' }),
        el('th', { text: 'สถานะบัญชี' }),
        el('th', { text: 'สถานะในเกม' }),
        el('th', { class: 'num', text: 'ของในกล่อง' }))),
      tbody);
  }

  function renderOverview(target) {
    loadOverview().then(() => {
      if (disposed) return;
      clear(target);
      target.append(totalsCard(), card('รายชื่อผู้เล่นในระบบ', playersTable()));
    }).catch(e => {
      clear(target);
      target.append(el('div', { class: 'empty', text: e.message || 'โหลดภาพรวมไม่สำเร็จ' }));
    });
    target.append(el('div', { class: 'empty', text: 'กำลังโหลดข้อมูล…' }));
  }

  function renderMoney(target) {
    const player = el('input', { type: 'text', placeholder: 'ชื่อผู้เล่นในเกม', maxLength: 32 });
    const op = el('select', null,
      el('option', { value: 'give', text: 'ให้เงิน (GIVE)' }),
      el('option', { value: 'take', text: 'หักเงิน (TAKE)' }),
      el('option', { value: 'set', text: 'ตั้งยอดเงินคงเหลือ (SET)' }));
    const amount = el('input', { type: 'number', min: 0, placeholder: 'จำนวนเงิน', inputMode: 'numeric' });
    const reason = reasonField();
    const button = el('button', { class: 'primary', type: 'button', text: 'ยืนยันทำรายการ' });

    button.addEventListener('click', async () => {
      const name = player.value.trim();
      const value = Number(amount.value);
      if (!name) { toast('กรุณาระบุชื่อผู้เล่น', 'error'); return; }
      if (!Number.isInteger(value) || value < 0) { toast('จำนวนเงินต้องเป็นจำนวนเต็มบวก', 'error'); return; }
      if (reason.value.trim().length < 3) { toast('กรุณาระบุเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
      const ok = await confirmModal({
        title: 'ยืนยันการปรับยอดเงิน',
        confirmText: 'ยืนยัน',
        lines: [
          { label: 'ผู้เล่นเป้าหมาย', value: name },
          { label: 'การกระทำ', value: op.value.toUpperCase() },
          { label: 'จำนวนเงิน', value: money(value) + ' ' + (state.currency || 'เหรียญ') },
          { label: 'เหตุผล', value: reason.value.trim() }
        ]
      });
      if (!ok) return;
      await run(button, async () => {
        const out = await api('POST', '/api/admin/money', { player: name, op: op.value, amount: value, reason: reason.value.trim() });
        toast('สำเร็จ ปรับยอดเงินของ ' + name + ' เป็น ' + money(out.balance) + ' ' + (state.currency || 'เหรียญ'), 'ok');
        reason.value = '';
        amount.value = '';
      });
    });

    target.append(card('ปรับยอดเงินบัญชีผู้เล่น',
      el('div', { class: 'field' }, el('label', { text: 'ชื่อผู้เล่น (PLAYER NAME)' }), player),
      el('div', { class: 'field' }, el('label', { text: 'การกระทำ (ACTION)' }), op),
      el('div', { class: 'field' }, el('label', { text: 'จำนวนเงิน (AMOUNT)' }), amount),
      el('div', { class: 'field' }, el('label', { text: 'เหตุผลประกอบการบันทึก AUDIT' }), reason),
      el('div', { class: 'form-actions', style: 'margin-top: 16px;' }, button)));
  }

  function renderPrices(target) {
    const item = el('input', { type: 'text', placeholder: 'minecraft:diamond หรือ diamond', maxLength: 100 });
    const base = el('input', { type: 'number', min: 1, placeholder: 'ราคา base (เว้นว่างเพื่อลบ override)' });
    const reason = reasonField();
    const setButton = el('button', { class: 'primary', type: 'button', text: 'บันทึก Override' });
    const blockButton = el('button', { class: 'danger', type: 'button', text: 'บล็อกการซื้อขาย' });
    const unblockButton = el('button', { class: 'ghost', type: 'button', text: 'ปลดบล็อก' });
    const refreshButton = el('button', { class: 'ghost', type: 'button', text: 'ดึงราคาใหม่ทันที' });

    function requireReason() {
      if (reason.value.trim().length < 3) { toast('กรุณาระบุเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return false; }
      return true;
    }

    setButton.addEventListener('click', async () => {
      if (!requireReason()) return;
      const id = item.value.trim();
      if (!id) { toast('กรุณาระบุ Item ID', 'error'); return; }
      const value = base.value.trim() === '' ? null : Number(base.value);
      if (value !== null && (!Number.isInteger(value) || value < 1)) { toast('ราคา base ต้องเป็นจำนวนเต็ม ≥ 1', 'error'); return; }
      await run(setButton, async () => {
        await api('POST', '/api/admin/prices/override', { item: id, base: value, reason: reason.value.trim() });
        toast(value === null ? 'ลบ override เรียบร้อย' : 'ตั้ง override สำหรับ ' + id + ' = ' + money(value), 'ok');
      });
    });

    blockButton.addEventListener('click', async () => {
      if (!requireReason()) return;
      const id = item.value.trim();
      if (!id) { toast('กรุณาระบุ Item ID', 'error'); return; }
      await run(blockButton, async () => {
        await api('POST', '/api/admin/prices/block', { item: id, blocked: true, reason: reason.value.trim() });
        toast('บล็อกการซื้อขายไอเท็ม ' + id + ' แล้ว', 'ok');
      });
    });

    unblockButton.addEventListener('click', async () => {
      if (!requireReason()) return;
      const id = item.value.trim();
      if (!id) { toast('กรุณาระบุ Item ID', 'error'); return; }
      await run(unblockButton, async () => {
        await api('POST', '/api/admin/prices/block', { item: id, blocked: false, reason: reason.value.trim() });
        toast('ปลดบล็อกการซื้อขายไอเท็ม ' + id + ' แล้ว', 'ok');
      });
    });

    refreshButton.addEventListener('click', async () => {
      await run(refreshButton, async () => {
        const out = await api('POST', '/api/admin/prices/refresh', {});
        toast(out.ok ? 'ดึงราคาใหม่สำเร็จ' : (out.message || 'ดึงราคาไม่สำเร็จ'), out.ok ? 'ok' : 'error');
      });
    });

    target.append(card('จัดการราคากลางและการจำหน่าย',
      el('div', { class: 'field' }, el('label', { text: 'ไอเท็ม ID (ITEM ID)' }), item),
      el('div', { class: 'field' }, el('label', { text: 'ราคากลาง BASE PRICE' }), base),
      el('div', { class: 'field' }, el('label', { text: 'เหตุผลประกอบการปรับปรุง' }), reason),
      el('div', { class: 'form-actions', style: 'display: flex; gap: 8px; flex-wrap: wrap; margin-top: 16px;' },
        setButton, blockButton, unblockButton, refreshButton)));
  }

  function renderAuctions(target) {
    const list = el('div', { class: 'list' });
    target.append(list);
    api('GET', '/api/admin/auctions').then(data => {
      if (disposed) return;
      clear(list);
      const rows = data.auctions || [];
      if (!rows.length) { list.append(el('div', { class: 'empty', text: 'ไม่มีรายการประมูลในขณะนี้' })); return; }
      for (const a of rows) {
        const reason = reasonField('เหตุผลที่บังคับยกเลิก');
        const cancel = el('button', { class: 'danger', type: 'button', text: 'บังคับยกเลิก' });
        cancel.disabled = a.status !== 'ACTIVE';
        cancel.addEventListener('click', async () => {
          if (reason.value.trim().length < 3) { toast('กรุณาระบุเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
          const ok = await confirmModal({
            title: 'ยืนยันบังคับยกเลิกประมูล',
            confirmText: 'ยกเลิกประมูล',
            danger: true,
            lines: [
              { label: 'รหัสรายการ', value: a.id },
              { label: 'ไอเท็ม', value: String(a.label || a.itemId) },
              { label: 'ผู้ขาย', value: a.sellerName },
              { label: 'ผู้เสนอราคาสูงสุด', value: a.highBidderName || '-' },
              { label: 'ยอดสูงสุด', value: money(a.highBid) }
            ]
          });
          if (!ok) return;
          await run(cancel, async () => {
            await api('POST', '/api/admin/auctions/' + encodeURIComponent(a.id) + '/cancel', { reason: reason.value.trim() });
            toast('ยกเลิกรายการ คืนเงินผู้ประมูลและคืนของเข้ากล่องผู้ขายแล้ว', 'ok');
            renderBody();
          });
        });
        list.append(el('div', { class: 'item' },
          el('div', { class: 'main' },
            el('div', { class: 'title', text: String(a.label || a.itemId) + ' · [' + a.status + ']' }),
            el('div', { class: 'sub', text: 'ID: ' + a.id + ' · ผู้ขาย: ' + a.sellerName + ' · ยอดสูงสุด: ' + money(a.highBid) + ' · ผู้นำ: ' + (a.highBidderName || '-') + ' · สิ้นสุด: ' + dateTime(a.endsAt) })),
          el('div', { class: 'actions', style: 'display: flex; gap: 8px;' }, reason, cancel)));
      }
    }).catch(e => {
      clear(list);
      list.append(el('div', { class: 'empty', text: e.message || 'โหลดรายการประมูลไม่สำเร็จ' }));
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
        const freeze = el('button', { class: p.frozen ? 'ghost' : 'danger', type: 'button', text: p.frozen ? 'คืนสิทธิ์' : 'ระงับบัญชี' });
        const reset = el('button', { class: 'danger', type: 'button', text: 'รีเซ็ตรหัสผ่าน' });

        freeze.addEventListener('click', async () => {
          if (reason.value.trim().length < 3) { toast('กรุณาระบุเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
          await run(freeze, async () => {
            await api('POST', '/api/admin/accounts/' + encodeURIComponent(p.name) + '/freeze', { frozen: !p.frozen, reason: reason.value.trim() });
            toast((p.frozen ? 'คืนสิทธิ์ให้คุณ ' : 'ระงับบัญชีคุณ ') + p.name + ' เรียบร้อย', 'ok');
            renderBody();
          });
        });

        reset.addEventListener('click', async () => {
          if (reason.value.trim().length < 3) { toast('กรุณาระบุเหตุผลอย่างน้อย 3 ตัวอักษร', 'error'); return; }
          const ok = await confirmModal({
            title: 'ยืนยันรีเซ็ตรหัสผ่าน',
            confirmText: 'ยืนยันรีเซ็ต',
            danger: true,
            lines: [
              { label: 'ผู้เล่นเป้าหมาย', value: p.name },
              { label: 'ผลลัพธ์', value: 'ผู้เล่นต้องเข้าเซิร์ฟเวอร์ในเกมเพื่อตั้งรหัสผ่านใหม่ทันที' },
              { label: 'คำเตือน', value: 'ควรแจ้งผู้เล่นให้รีบเข้าเกมก่อนถูกผู้อื่นสวมชื่อ' }
            ]
          });
          if (!ok) return;
          await run(reset, async () => {
            await api('POST', '/api/admin/accounts/' + encodeURIComponent(p.name) + '/reset-password', { reason: reason.value.trim() });
            toast('ลบรหัสผ่านของ ' + p.name + ' เรียบร้อยแล้ว', 'ok');
          });
        });

        list.append(el('div', { class: 'item' },
          el('div', { class: 'main' },
            el('div', { class: 'title', text: String(p.name || '') }),
            el('div', { class: 'sub', text: 'ยอดเงิน: ' + money(p.balance) + ' · สถานะ: ' + (p.frozen ? 'ถูกระงับ' : 'ปกติ') + ' · สถานะเกม: ' + (p.online ? 'ออนไลน์' : 'ออฟไลน์') })),
          el('div', { class: 'actions', style: 'display: flex; gap: 8px;' }, reason, freeze, reset)));
      }
    }).catch(e => {
      clear(list);
      list.append(el('div', { class: 'empty', text: e.message || 'โหลดบัญชีผู้เล่นไม่สำเร็จ' }));
    });
  }

  function renderAudit(target) {
    const list = el('div', { class: 'list' });
    const reload = el('button', { class: 'ghost', type: 'button', text: 'โหลดบันทึกใหม่' });
    const load = () => {
      api('GET', '/api/admin/audit?limit=100').then(data => {
        if (disposed) return;
        clear(list);
        const lines = data.lines || [];
        if (!lines.length) { list.append(el('div', { class: 'empty', text: 'ยังไม่มีบันทึก Audit' })); return; }
        for (const line of lines.slice().reverse()) {
          const titleText = (line && line.action ? line.action : 'EVENT')
            + ' · ดำเนินการโดย: ' + (line.actor || 'SYSTEM')
            + ' · ' + (line.ok ? 'สำเร็จ' : 'ล้มเหลว')
            + ' · ' + dateTime(line.t)
            + (line.ip ? ' · IP: ' + line.ip : '');
          list.append(el('div', { class: 'item' },
            el('div', { class: 'main' },
              el('div', { class: 'title', text: titleText }),
              el('div', { class: 'sub mono', style: 'font-size: 11px; margin-top: 4px;', text: JSON.stringify(line.details || {}) }))));
        }
      }).catch(e => {
        clear(list);
        list.append(el('div', { class: 'empty', text: e.message || 'โหลด Audit log ไม่สำเร็จ' }));
      });
    };
    reload.addEventListener('click', load);
    target.append(el('div', { style: 'margin-bottom: 12px;' }, reload), list);
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

import { el, clear, api, state, money, timeLeft, confirmModal, showError, toast, refreshStatus } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let data = null;
  const timers = [];

  container.append(el('h1', { text: 'ประมูล' }));
  container.append(el('p', { class: 'hint', text: 'ลงขายด้วยคำสั่ง /ah sell <ราคาเริ่ม> [ซื้อทันที] [ชั่วโมง] ในเกม · ระบบจะรีเฟรชรายการทุก 4 วินาที' }));
  const activeList = el('div', { class: 'list' });
  const mineList = el('div', { class: 'list' });
  container.append(el('h2', { text: 'กำลังประมูล' }), activeList, el('h2', { text: 'รายการของฉัน (7 วัน)' }), mineList);

  function countdownText(auction) {
    return timeLeft(auction.endsAt, Date.now());
  }

  function auctionCard(auction, isMine) {
    const currency = (data && data.currency) || state.currency;
    const countdown = el('span', { text: countdownText(auction) });
    const title = el('div', { class: 'title', text: String(auction.label || auction.itemId) });
    const sub = el('div', { class: 'sub' },
      auction.itemId + ' × ' + money(auction.count)
        + ' · ผู้ขาย ' + auction.sellerName
        + ' · ' + (auction.highBid > 0
          ? 'สูงสุด ' + money(auction.highBid) + ' ' + currency + ' (' + (auction.highBidderName || '-') + ')'
          : 'ยังไม่มีผู้ประมูล')
        + ' · เริ่ม ' + money(auction.startPrice) + ' ' + currency);
    const meta = el('div', { class: 'sub' },
      'เหลือ ', countdown,
      auction.buyout ? ' · ซื้อทันที ' + money(auction.buyout) + ' ' + currency : '',
      auction.status !== 'ACTIVE' ? ' · สถานะ ' + auction.status : '');

    const actions = el('div', { class: 'actions' });
    if (auction.status === 'ACTIVE' && auction.canBid) {
      const amount = el('input', {
        type: 'number', value: String(auction.minBid || auction.startPrice), min: 1, inputMode: 'numeric'
      });
      const bidButton = el('button', { class: 'primary', type: 'button', text: 'เสนอราคา' });
      bidButton.addEventListener('click', async () => {
        if (busy) return;
        const value = Number(amount.value);
        if (!Number.isInteger(value) || value < 1) { toast('จำนวนเงินไม่ถูกต้อง', 'error'); return; }
        const ok = await confirmModal({
          title: 'ยืนยันการเสนอราคา',
          confirmText: 'เสนอราคา',
          lines: [
            { label: 'ไอเท็ม', value: String(auction.label || auction.itemId) },
            { label: 'ราคาขั้นต่ำ', value: money(auction.minBid) + ' ' + currency },
            { label: 'ราคาที่เสนอ', value: money(value) + ' ' + currency },
            { label: 'ยอดคงเหลือตอนนี้', value: money(state.balance) + ' ' + currency }
          ]
        });
        if (!ok) return;
        busy = true;
        bidButton.disabled = true;
        try {
          const out = await api('POST', '/api/auctions/' + encodeURIComponent(auction.id) + '/bid', { amount: value });
          toast('เสนอราคาแล้ว ' + money(out.highBid) + ' ' + currency, 'ok');
          state.balance = out.balance;
          refreshStatus();
        } catch (e) {
          showError(e);
        } finally {
          busy = false;
          await load();
        }
      });
      actions.append(amount, bidButton);
    }
    if (auction.status === 'ACTIVE' && auction.buyout && !auction.mine) {
      const buyButton = el('button', { class: 'primary', type: 'button', text: 'ซื้อทันที' });
      buyButton.addEventListener('click', async () => {
        if (busy) return;
        const currencyNow = (data && data.currency) || state.currency;
        const ok = await confirmModal({
          title: 'ยืนยันซื้อทันที',
          confirmText: 'ซื้อทันที',
          lines: [
            { label: 'ไอเท็ม', value: String(auction.label || auction.itemId) },
            { label: 'ราคา', value: money(auction.buyout) + ' ' + currencyNow },
            { label: 'ยอดคงเหลือตอนนี้', value: money(state.balance) + ' ' + currencyNow },
            { label: 'ยอดคงเหลือหลังซื้อ', value: money(state.balance - auction.buyout) + ' ' + currencyNow }
          ]
        });
        if (!ok) return;
        busy = true;
        buyButton.disabled = true;
        try {
          const out = await api('POST', '/api/auctions/' + encodeURIComponent(auction.id) + '/buyout', {});
          toast('ซื้อสำเร็จ ของอยู่ในกล่องของ', 'ok');
          state.balance = out.balance;
          state.mailboxCount = out.mailboxCount;
          refreshStatus();
        } catch (e) {
          showError(e);
        } finally {
          busy = false;
          await load();
        }
      });
      actions.append(buyButton);
    }
    if (auction.canCancel) {
      const cancelButton = el('button', { class: 'danger', type: 'button', text: 'ยกเลิก' });
      cancelButton.addEventListener('click', async () => {
        if (busy) return;
        const ok = await confirmModal({
          title: 'ยืนยันยกเลิกรายการ',
          confirmText: 'ยกเลิก',
          danger: true,
          lines: [
            { label: 'ไอเท็ม', value: String(auction.label || auction.itemId) },
            { label: 'ผลลัพธ์', value: 'ของจะกลับเข้ากล่องของ' }
          ]
        });
        if (!ok) return;
        busy = true;
        cancelButton.disabled = true;
        try {
          const out = await api('POST', '/api/auctions/' + encodeURIComponent(auction.id) + '/cancel', {});
          toast('ยกเลิกแล้ว ของอยู่ในกล่องของ', 'ok');
          state.mailboxCount = out.mailboxCount;
          refreshStatus();
        } catch (e) {
          showError(e);
        } finally {
          busy = false;
          await load();
        }
      });
      actions.append(cancelButton);
    }

    const card = el('div', { class: 'item' },
      el('div', { class: 'main' }, title, sub, meta),
      actions.childElementCount ? actions : null);
    card.dataset.auctionId = auction.id;
    return card;
  }

  function renderLists() {
    if (disposed) return;
    clear(activeList);
    clear(mineList);
    if (!data) {
      activeList.append(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
      return;
    }
    const active = (data.auctions || []);
    if (!active.length) activeList.append(el('div', { class: 'empty', text: 'ยังไม่มีรายการประมูล' }));
    else for (const auction of active) activeList.append(auctionCard(auction, false));

    const mine = (data.mine || []);
    if (!mine.length) mineList.append(el('div', { class: 'empty', text: 'ยังไม่มีรายการของคุณ' }));
    else for (const auction of mine) mineList.append(auctionCard(auction, true));
  }

  async function load() {
    if (disposed) return;
    try {
      data = await api('GET', '/api/auctions');
      renderLists();
    } catch (e) {
      if (e.status === 401) return;
      clear(activeList);
      activeList.append(el('div', { class: 'empty', text: e.message || 'โหลดรายการประมูลไม่สำเร็จ' }));
    }
  }

  timers.push(setInterval(() => {
    if (disposed || document.visibilityState !== 'visible' || !data) return;
    // Only the countdown text is touched every second; inputs stay intact.
    for (const card of activeList.children) {
      const id = card.dataset.auctionId;
      if (!id) continue;
      const auction = (data.auctions || []).find(a => a.id === id);
      if (!auction) continue;
      const meta = card.querySelectorAll('.sub')[1];
      if (!meta) continue;
      meta.textContent = 'เหลือ ' + countdownText(auction)
        + (auction.buyout ? ' · ซื้อทันที ' + money(auction.buyout) : '')
        + (auction.status !== 'ACTIVE' ? ' · สถานะ ' + auction.status : '');
    }
  }, 1000));

  timers.push(setInterval(() => {
    if (document.visibilityState === 'visible') load();
  }, 4000));

  load();

  return {
    dispose() {
      disposed = true;
      for (const timer of timers) clearInterval(timer);
    }
  };
}

import { icon, itemSlot } from '../icons.js';
import { el, clear, api, state, money, timeLeft, confirmModal, showError, toast, refreshStatus, navigate } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let data = null;
  const timers = [];
  let currentTab = 'active';

  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { text: 'ตลาดประมูล' }),
        el('p', { class: 'hint', text: 'เสนอราคาประมูลแบบสด หรือตั้งราคาซื้อทันที · ลงขายไอเท็มผ่านหน้าขายหรือพิมพ์ /ah sell ในเกม' })),
      el('button', { class: 'primary', type: 'button', onClick: () => navigate('sell') },
        icon('plus', 14),
        el('span', { text: 'ลงประมูลไอเท็ม' })
      )
    )
  );

  /* ADDED: Section switcher tabs */
  const tabs = el('div', { class: 'tabs', style: 'margin-bottom: 16px;' },
    el('button', {
      type: 'button',
      class: 'active',
      text: 'กำลังเปิดประมูล',
      onClick: (e) => switchTab('active', e.target)
    }),
    el('button', {
      type: 'button',
      text: 'รายการของฉัน',
      onClick: (e) => switchTab('mine', e.target)
    })
  );

  function switchTab(t, btn) {
    currentTab = t;
    tabs.querySelectorAll('button').forEach(b => b.classList.remove('active'));
    if (btn) btn.classList.add('active');
    renderLists();
  }

  const listContainer = el('div', { class: 'list' });
  container.append(tabs, listContainer);

  function countdownText(auction) {
    return timeLeft(auction.endsAt, Date.now());
  }

  function auctionCard(auction, isMine) {
    const currency = (data && data.currency) || state.currency;
    const countdown = el('span', { style: 'color: var(--accent); font-family: var(--font-mono); font-weight: 600;', text: countdownText(auction) });
    const title = el('div', { class: 'title', text: String(auction.label || auction.itemId) });
    const sub = el('div', { class: 'sub' },
      auction.itemId + ' × ' + money(auction.count)
        + ' · ผู้ขาย: ' + auction.sellerName
        + ' · ' + (auction.highBid > 0
          ? 'สูงสุด ' + money(auction.highBid) + ' ' + currency + ' (' + (auction.highBidderName || '-') + ')'
          : 'ยังไม่มีผู้เสนอราคา')
        + ' · เริ่มต้น ' + money(auction.startPrice) + ' ' + currency);
    const meta = el('div', { class: 'sub', style: 'margin-top: 4px;' },
      'เหลือเวลา: ', countdown,
      auction.buyout ? ' · ซื้อทันที ' + money(auction.buyout) + ' ' + currency : '',
      auction.status !== 'ACTIVE' ? ' · [' + auction.status + ']' : '');

    const actions = el('div', { class: 'actions', style: 'display: flex; gap: 8px; align-items: flex-end;' });

    if (auction.status === 'ACTIVE' && auction.canBid) {
      const amount = el('input', {
        type: 'number',
        value: String(auction.minBid || auction.startPrice),
        min: 1,
        inputMode: 'numeric',
        style: 'width: 110px;'
      });
      const bidButton = el('button', { class: 'primary', type: 'button', text: 'เสนอราคา' });
      bidButton.addEventListener('click', async () => {
        if (busy) return;
        const value = Number(amount.value);
        if (!Number.isInteger(value) || value < 1) { toast('กรุณาระบุจำนวนเงินที่ถูกต้อง', 'error'); return; }
        const ok = await confirmModal({
          title: 'ยืนยันเสนอราคาประมูล',
          confirmText: 'ยืนยันเสนอราคา',
          lines: [
            { label: 'ไอเท็ม', value: String(auction.label || auction.itemId) },
            { label: 'ราคาเสนอขั้นต่ำ', value: money(auction.minBid) + ' ' + currency },
            { label: 'ราคาที่เสนอ', value: money(value) + ' ' + currency },
            { label: 'ยอดเงินคงเหลือ', value: money(state.balance) + ' ' + currency }
          ]
        });
        if (!ok) return;
        busy = true;
        bidButton.disabled = true;
        try {
          const out = await api('POST', '/api/auctions/' + encodeURIComponent(auction.id) + '/bid', { amount: value });
          toast('เสนอราคา ' + money(out.highBid) + ' ' + currency + ' สำเร็จ', 'ok');
          state.balance = out.balance;
          refreshStatus();
        } catch (e) {
          showError(e);
        } finally {
          busy = false;
          await load();
        }
      });

      const amountWrap = el('div', { class: 'field', style: 'margin-bottom: 0;' },
        el('label', { text: 'ราคา' }),
        amount
      );
      actions.append(amountWrap, bidButton);
    }

    if (auction.status === 'ACTIVE' && auction.buyout && !auction.mine) {
      const buyButton = el('button', { class: 'ghost', type: 'button', text: 'ซื้อทันที' });
      buyButton.addEventListener('click', async () => {
        if (busy) return;
        const currencyNow = (data && data.currency) || state.currency;
        const ok = await confirmModal({
          title: 'ยืนยันซื้อทันที',
          confirmText: 'ซื้อทันที',
          lines: [
            { label: 'ไอเท็ม', value: String(auction.label || auction.itemId) },
            { label: 'ราคาซื้อทันที', value: money(auction.buyout) + ' ' + currencyNow },
            { label: 'ยอดคงเหลือตอนนี้', value: money(state.balance) + ' ' + currencyNow },
            { label: 'ยอดคงเหลือหลังซื้อ', value: money(state.balance - auction.buyout) + ' ' + currencyNow }
          ]
        });
        if (!ok) return;
        busy = true;
        buyButton.disabled = true;
        try {
          const out = await api('POST', '/api/auctions/' + encodeURIComponent(auction.id) + '/buyout', {});
          toast('สั่งซื้อไอเท็มสำเร็จ ของถูกส่งไปยังกล่องรับของแล้ว', 'ok');
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
          title: 'ยืนยันยกเลิกการประมูล',
          confirmText: 'ยกเลิกรายการ',
          danger: true,
          lines: [
            { label: 'ไอเท็ม', value: String(auction.label || auction.itemId) },
            { label: 'ผลลัพธ์', value: 'ไอเท็มจะถูกส่งกลับเข้ากล่องรับของของคุณ' }
          ]
        });
        if (!ok) return;
        busy = true;
        cancelButton.disabled = true;
        try {
          const out = await api('POST', '/api/auctions/' + encodeURIComponent(auction.id) + '/cancel', {});
          toast('ยกเลิกรายการประมูลแล้ว ไอเท็มกลับสู่กล่องรับของ', 'ok');
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
      itemSlot(auction.itemId, auction.count, 40),
      el('div', { class: 'main' }, title, sub, meta),
      actions.childElementCount ? actions : null
    );
    card.dataset.auctionId = auction.id;
    return card;
  }

  function renderLists() {
    if (disposed) return;
    clear(listContainer);
    if (!data) {
      listContainer.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการประมูล…' }));
      return;
    }

    if (currentTab === 'active') {
      const active = data.auctions || [];
      if (!active.length) {
        listContainer.append(
          el('div', { class: 'empty' },
            el('div', { style: 'font-weight: 600; margin-bottom: 4px;', text: 'ยังไม่มีรายการประมูลที่กำลังดำเนินอยู่' }),
            el('div', { class: 'hint', text: 'คุณสามารถเป็นคนแรกที่ลงประมูลไอเท็มได้เลย' })
          )
        );
      } else {
        for (const auction of active) listContainer.append(auctionCard(auction, false));
      }
    } else {
      const mine = data.mine || [];
      if (!mine.length) {
        listContainer.append(
          el('div', { class: 'empty' },
            el('div', { style: 'font-weight: 600; margin-bottom: 4px;', text: 'คุณยังไม่มีรายการประมูลของตนเอง' }),
            el('div', { class: 'hint', text: 'เมื่อคุณลงประมูลไอเท็ม รายการจะปรากฏในแท็บนี้' })
          )
        );
      } else {
        for (const auction of mine) listContainer.append(auctionCard(auction, true));
      }
    }
  }

  async function load() {
    if (disposed) return;
    try {
      data = await api('GET', '/api/auctions');
      renderLists();
    } catch (e) {
      if (e.status === 401) return;
      clear(listContainer);
      listContainer.append(el('div', { class: 'empty', text: e.message || 'โหลดรายการประมูลไม่สำเร็จ' }));
    }
  }

  // Periodic countdown text updater
  timers.push(setInterval(() => {
    if (disposed || document.visibilityState !== 'visible' || !data) return;
    for (const card of listContainer.children) {
      const id = card.dataset.auctionId;
      if (!id) continue;
      const auction = (data.auctions || []).find(a => a.id === id) || (data.mine || []).find(a => a.id === id);
      if (!auction) continue;
      const countdownEl = card.querySelector('.sub span');
      if (countdownEl) {
        countdownEl.textContent = countdownText(auction);
      }
    }
  }, 1000));

  // Live polling
  timers.push(setInterval(() => {
    if (document.visibilityState === 'visible') load();
  }, 5000));

  load();

  return {
    dispose() {
      disposed = true;
      for (const timer of timers) clearInterval(timer);
    }
  };
}

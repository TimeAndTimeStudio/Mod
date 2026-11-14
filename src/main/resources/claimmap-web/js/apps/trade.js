import { el, clear, api, state, money, timeLeft, confirmModal, showError, toast, refreshStatus } from '../core.js';
import { fetchSources, sourceLabel, sourceOptions } from '../sources.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let sources = null;
  let data = null;

  container.append(el('div', { class: 'page-head' },
    el('div', null,
      el('h1', { text: 'เทรด' }),
      el('p', { class: 'hint', text: 'แลกไอเท็มกับผู้เล่นคนอื่น ของทั้งสองฝ่ายเข้าทางกล่องของ' }))));

  const refresh = el('button', { class: 'secondary', type: 'button', text: '↻ รีเฟรช' });
  const createCard = el('div', { class: 'card stack' });
  const offersTitle = el('h2', { text: 'ข้อเสนอที่เปิดอยู่' });
  const offers = el('div', { class: 'list' });
  const mineTitle = el('h2', { text: 'ของฉัน' });
  const mine = el('div', { class: 'list' });
  container.append(refresh, createCard, offersTitle, offers, mineTitle, mine);

  // ---- create -------------------------------------------------------------

  const giveSelect = el('select', { 'aria-label': 'ไอเท็มที่จะให้' });
  const giveCount = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
  const wantItem = el('input', { type: 'text', placeholder: 'เช่น minecraft:diamond', maxLength: 128, autocomplete: 'off' });
  const wantCount = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
  const createButton = el('button', { class: 'primary', type: 'button', text: 'ตั้งข้อเสนอเทรด' });
  const givePreview = el('p', { class: 'pick-preview', text: 'กำลังโหลดของ…' });

  createCard.append(
    el('h2', { text: 'สร้างข้อเสนอ' }),
    el('label', { text: 'ของที่คุณจะให้' }), giveSelect,
    givePreview,
    el('div', { class: 'form-grid' },
      el('div', null, el('label', { text: 'จำนวนที่ให้' }), giveCount),
      el('div', null, el('label', { text: 'ไอเท็มที่ต้องการ (item id)' }), wantItem),
      el('div', null, el('label', { text: 'จำนวนที่ต้องการ' }), wantCount)),
    el('div', { class: 'row' }, createButton),
    el('p', { class: 'hint', text: 'ของที่ให้จะถูกย้ายออกทันที · ถ้ายกเลิก ของจะเข้ากล่องของคุณ' }));

  function fillGive() {
    clear(giveSelect);
    if (!sources || !sources.length) {
      giveSelect.append(el('option', { value: '', text: 'ไม่พบของ (ต้องออนไลน์)' }));
      givePreview.textContent = 'ไม่พบของที่พร้อมเทรด';
      return;
    }
    for (const item of sources) giveSelect.append(el('option', { value: item.ref, text: sourceLabel(item) }));
    updateGivePreview();
  }

  function updateGivePreview() {
    const item = (sources || []).find(s => s.ref === giveSelect.value);
    if (!item) { givePreview.textContent = 'เลือกของก่อน'; return; }
    giveCount.max = String(item.count);
    if (Number(giveCount.value) > item.count) giveCount.value = String(item.count);
    givePreview.textContent = 'ให้: ' + item.label + ' × ' + item.count + ' จาก ' + item.area;
  }

  giveSelect.addEventListener('change', updateGivePreview);

  createButton.addEventListener('click', async () => {
    if (busy) return;
    const item = (sources || []).find(s => s.ref === giveSelect.value);
    if (!item) { toast('เลือกของที่จะให้ก่อน', 'error'); return; }
    const n = Number(giveCount.value);
    const want = wantItem.value.trim().toLowerCase();
    const wn = Number(wantCount.value);
    if (!Number.isInteger(n) || n < 1 || n > item.count) { toast('จำนวนที่ให้ไม่ถูกต้อง', 'error'); return; }
    if (!want) { toast('ระบุไอเท็มที่ต้องการ', 'error'); return; }
    if (!Number.isInteger(wn) || wn < 1) { toast('จำนวนที่ต้องการไม่ถูกต้อง', 'error'); return; }
    const ok = await confirmModal({
      title: 'ยืนยันตั้งข้อเสนอเทรด', confirmText: 'ตั้งข้อเสนอ',
      lines: [
        { label: 'คุณให้', value: item.label + ' × ' + money(n) },
        { label: 'คุณต้องการ', value: want + ' × ' + money(wn) },
        { label: 'แหล่งของ', value: item.area }
      ]
    });
    if (!ok) return;
    busy = true; createButton.disabled = true;
    try {
      await api('POST', '/api/trades', { ref: item.ref, giveCount: n, wantItem: want, wantCount: wn });
      toast('ตั้งข้อเสนอเทรดแล้ว', 'ok');
      wantItem.value = '';
      await load();
    } catch (e) { showError(e); }
    finally { busy = false; createButton.disabled = false; }
  });

  // ---- lists --------------------------------------------------------------

  function offerCard(offer, isMine) {
    const actions = el('div', { class: 'actions' });
    if (offer.canAccept) {
      const candidates = sourceOptions(sources || [], offer.wantItemId, offer.wantCount);
      const select = el('select', { 'aria-label': 'ช่องของที่จะจ่าย' });
      if (!candidates.length) {
        select.append(el('option', { value: '', text: 'ไม่มี ' + offer.wantLabel + ' พอ' }));
        select.disabled = true;
      } else {
        for (const item of candidates) select.append(el('option', { value: item.ref, text: sourceLabel(item) }));
      }
      const accept = el('button', { class: 'primary', type: 'button', text: 'รับเทรด' });
      accept.disabled = !candidates.length;
      accept.addEventListener('click', async () => {
        if (busy || !select.value) return;
        const ok = await confirmModal({
          title: 'ยืนยันรับเทรด', confirmText: 'รับเทรด',
          lines: [
            { label: 'คุณได้รับ', value: offer.giveLabel + ' × ' + money(offer.giveCount) },
            { label: 'คุณจ่าย', value: offer.wantLabel + ' × ' + money(offer.wantCount) },
            { label: 'ของจะเข้าทาง', value: 'กล่องของทั้งสองฝ่าย' }
          ]
        });
        if (!ok) return;
        busy = true; accept.disabled = true;
        try {
          const out = await api('POST', '/api/trades/' + encodeURIComponent(offer.id) + '/accept', { ref: select.value });
          toast('รับเทรดแล้ว ของอยู่ในกล่องของคุณ', 'ok');
          state.mailboxCount = out.mailboxCount; refreshStatus();
          await load();
        } catch (e) { showError(e); }
        finally { busy = false; accept.disabled = false; }
      });
      actions.append(select, accept);
    }
    if (offer.canCancel) {
      const cancel = el('button', { class: 'danger', type: 'button', text: 'ยกเลิก' });
      cancel.addEventListener('click', async () => {
        if (busy) return;
        const ok = await confirmModal({ title: 'ยกเลิกข้อเสนอ', confirmText: 'ยกเลิก', danger: true,
          lines: [{ label: 'ของ', value: offer.giveLabel + ' × ' + money(offer.giveCount) }, { label: 'ผลลัพธ์', value: 'ของจะเข้ากล่องของคุณ' }] });
        if (!ok) return;
        busy = true; cancel.disabled = true;
        try {
          const out = await api('POST', '/api/trades/' + encodeURIComponent(offer.id) + '/cancel', {});
          toast('ยกเลิกแล้ว ของอยู่ในกล่องของคุณ', 'ok');
          state.mailboxCount = out.mailboxCount; refreshStatus();
          await load();
        } catch (e) { showError(e); }
        finally { busy = false; cancel.disabled = false; }
      });
      actions.append(cancel);
    }
    return el('div', { class: 'item' },
      el('div', { class: 'main' },
        el('div', { class: 'title', text: offer.giveLabel + ' × ' + money(offer.giveCount) }),
        el('div', { class: 'sub', text: 'จาก ' + offer.ownerName + ' · ต้องการ ' + offer.wantLabel + ' × ' + money(offer.wantCount) }),
        el('div', { class: 'sub', text: 'เหลือ ' + timeLeft(offer.endsAt, Date.now()) })),
      actions.childElementCount ? actions : null);
  }

  function renderLists() {
    if (disposed) return;
    clear(offers); clear(mine);
    if (!data) { offers.append(el('div', { class: 'empty', text: 'กำลังโหลด…' })); return; }
    const list = data.offers || [];
    if (!list.length) offers.append(el('div', { class: 'empty', text: 'ยังไม่มีข้อเสนอเทรด' }));
    else for (const o of list) offers.append(offerCard(o, false));
    const own = data.mine || [];
    if (!own.length) mine.append(el('div', { class: 'empty', text: 'คุณยังไม่มีข้อเสนอ' }));
    else for (const o of own) mine.append(offerCard(o, true));
  }

  async function load() {
    if (disposed) return;
    try {
      sources = await fetchSources();
      fillGive();
      data = await api('GET', '/api/trades');
      renderLists();
    } catch (e) {
      if (e.status === 401) return;
      if (e.status === 409) {
        sources = [];
        fillGive();
        try { data = await api('GET', '/api/trades'); renderLists(); } catch (ignored) { /* keep list empty */ }
        return;
      }
      clear(offers); offers.append(el('div', { class: 'empty', text: e.message || 'โหลดเทรดไม่สำเร็จ' }));
    }
  }

  refresh.addEventListener('click', load);
  load();

  return { dispose() { disposed = true; } };
}

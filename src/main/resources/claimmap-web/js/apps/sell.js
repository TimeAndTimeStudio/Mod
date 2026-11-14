import { icon } from '../icons.js';
import { el, clear, api, state, money, confirmModal, showError, toast, refreshStatus } from '../core.js';
import { fetchSources, sourceLabel } from '../sources.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let sources = null;
  let tab = 'market';

  const head = el('div', { class: 'page-head' },
    el('div', null,
      el('h1', { text: 'ลงขาย' }),
      el('p', { class: 'hint', text: 'ดึงของจากกระเป๋า, Ender Chest และกล่องในพื้นที่ของคุณ' })));
  const tabs = el('div', { class: 'tabs' });
  const body = el('div');
  container.append(head, tabs, body);

  const marketTab = el('button', { type: 'button', text: 'ตลาดผู้เล่น', onClick: () => switchTab('market') });
  const shopTab = el('button', { type: 'button', text: 'ขายให้ร้าน', onClick: () => switchTab('shop') });
  tabs.append(marketTab, shopTab);

  function switchTab(next) {
    tab = next;
    marketTab.classList.toggle('active', tab === 'market');
    shopTab.classList.toggle('active', tab === 'shop');
    renderBody();
  }

  function renderBody() {
    if (disposed) return;
    clear(body);
    if (tab === 'market') renderMarket();
    else renderShop();
  }

  // ---- player market -------------------------------------------------------

  function renderMarket() {
    const note = el('p', { class: 'hint', text: 'ของจะถูกย้ายออกจากช่องที่เลือกทันทีที่ลงขาย ถ้ายกเลิกหรือหมดอายุ ของจะเข้ากล่องของ' });
    const select = el('select', { 'aria-label': 'เลือกไอเท็มที่จะลงขาย' });
    const refresh = el('button', { class: 'secondary', type: 'button' },
      icon('refresh', 16),
      el('span', { text: 'รีเฟรชของ' })
    );
    const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
    const start = el('input', { type: 'number', value: '100', min: 1, inputMode: 'numeric' });
    const buyout = el('input', { type: 'number', min: 0, placeholder: 'ไม่บังคับ', inputMode: 'numeric' });
    const hours = el('input', { type: 'number', value: '48', min: 1, max: 168, inputMode: 'numeric' });
    const submit = el('button', { class: 'primary', type: 'button', text: 'ลงขายในตลาด' });
    const selected = el('p', { class: 'pick-preview', text: 'กำลังโหลดของ…' });

    function fillSources() {
      clear(select);
      if (!sources) {
        select.append(el('option', { value: '', text: 'กำลังโหลด…' }));
        return;
      }
      if (!sources.length) {
        select.append(el('option', { value: '', text: 'ไม่พบของ (ต้องออนไลน์และอยู่ในพื้นที่ที่โหลด)' }));
        selected.textContent = 'ไม่พบของที่พร้อมลงขาย';
        return;
      }
      for (const item of sources) {
        select.append(el('option', { value: item.ref, text: sourceLabel(item) }));
      }
      select.selectedIndex = 0;
      updatePreview();
    }

    function updatePreview() {
      const item = (sources || []).find(s => s.ref === select.value);
      if (!item) { selected.textContent = 'เลือกไอเท็มก่อน'; return; }
      qty.max = String(item.count);
      if (Number(qty.value) > item.count) qty.value = String(item.count);
      selected.textContent = 'เลือก: ' + item.label + ' × ' + item.count + ' จาก ' + item.area;
    }

    async function load() {
      if (disposed) return;
      try {
        sources = await fetchSources();
        fillSources();
        note.textContent = 'ของจะถูกย้ายออกจากช่องที่เลือกทันทีที่ลงขาย · เฉพาะกล่องในพื้นที่ที่โหลดอยู่';
      } catch (e) {
        sources = [];
        fillSources();
        if (e.status === 409) note.textContent = 'ต้องเข้าเกมก่อนจึงจะดึงของจากกระเป๋าและกล่องได้';
        else if (e.status !== 401) showError(e);
      }
    }

    select.addEventListener('change', updatePreview);
    refresh.addEventListener('click', load);
    submit.addEventListener('click', async () => {
      if (busy) return;
      const item = (sources || []).find(s => s.ref === select.value);
      if (!item) { toast('เลือกไอเท็มก่อน', 'error'); return; }
      const n = Number(qty.value);
      const s = Number(start.value);
      const b = buyout.value === '' ? null : Number(buyout.value);
      const h = Number(hours.value);
      if (!Number.isInteger(n) || n < 1 || n > item.count) { toast('จำนวนไม่ถูกต้อง', 'error'); return; }
      if (!Number.isInteger(s) || s < 1) { toast('ราคาเริ่มต้นไม่ถูกต้อง', 'error'); return; }
      if (b !== null && (!Number.isInteger(b) || b <= s)) { toast('ซื้อทันทีต้องมากกว่าราคาเริ่ม', 'error'); return; }
      const ok = await confirmModal({
        title: 'ยืนยันลงขาย',
        confirmText: 'ลงขาย',
        lines: [
          { label: 'ไอเท็ม', value: item.label + ' × ' + money(n) },
          { label: 'แหล่งของ', value: item.area },
          { label: 'ราคาเริ่ม', value: money(s) + ' ' + state.currency },
          { label: 'ซื้อทันที', value: b === null ? 'ไม่ตั้ง' : money(b) + ' ' + state.currency },
          { label: 'ระยะเวลา', value: money(h) + ' ชั่วโมง' }
        ]
      });
      if (!ok) return;
      busy = true;
      submit.disabled = true;
      try {
        const out = await api('POST', '/api/auctions', {
          ref: item.ref, count: n, startPrice: s, buyout: b === null ? 0 : b, hours: h
        });
        toast('ลงขายแล้ว รหัส ' + out.id, 'ok');
        await load();
      } catch (e) {
        showError(e);
      } finally {
        busy = false;
        submit.disabled = false;
      }
    });

    body.append(note,
      el('div', { class: 'card stack' },
        el('label', { text: 'ไอเท็มจากกระเป๋า / กล่อง' }), select,
        el('div', { class: 'row' }, refresh),
        selected,
        el('div', { class: 'form-grid' },
          el('div', null, el('label', { text: 'จำนวน' }), qty),
          el('div', null, el('label', { text: 'ราคาเริ่ม' }), start),
          el('div', null, el('label', { text: 'ซื้อทันที (ไม่บังคับ)' }), buyout),
          el('div', null, el('label', { text: 'ชั่วโมง' }), hours)),
        el('div', { class: 'row' }, submit)),
      el('p', { class: 'hint', text: 'ดูรายการที่ลงแล้วได้ที่หน้า “ซื้อ” หรือ “ประมูล”' }));
    load();
  }

  // ---- shop selling --------------------------------------------------------

  function renderShop() {
    let shop = null;
    const note = el('p', { class: 'hint', text: 'ขายให้ร้านได้เฉพาะของในกระเป๋าหลักหรือแถบด่วน และต้องไม่มี enchant/ชื่อกำหนดเอง/ความเสียหาย' });
    const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็ม' });
    const list = el('div', { class: 'list' });
    body.append(note, el('div', { class: 'toolbar' }, el('div', { class: 'field' }, search)), list);

    function renderList() {
      if (disposed) return;
      clear(list);
      if (!shop) { list.append(el('div', { class: 'empty', text: 'กำลังโหลด…' })); return; }
      const query = search.value.trim().toLowerCase();
      const entries = Object.entries(shop.items || {})
        .filter(([id, info]) => !query || id.toLowerCase().includes(query) || String(info.label || '').toLowerCase().includes(query))
        .sort((a, b) => String(a[1].label || a[0]).localeCompare(String(b[1].label || b[0]), 'th'));
      if (!entries.length) { list.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่ขายได้' })); return; }
      for (const [id, info] of entries) {
        const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
        const button = el('button', { class: 'primary', type: 'button', text: 'ขาย' });
        button.addEventListener('click', async () => {
          const n = Number(qty.value);
          if (!Number.isInteger(n) || n < 1) { toast('จำนวนต้องเป็นจำนวนเต็มตั้งแต่ 1', 'error'); return; }
          if (busy) return;
          const total = Number(info.sell) * n;
          const ok = await confirmModal({
            title: 'ยืนยันการขาย', confirmText: 'ขาย',
            lines: [
              { label: 'ไอเท็ม', value: String(info.label || id) },
              { label: 'จำนวน', value: money(n) + ' ชิ้น' },
              { label: 'เงินที่จะได้รับ', value: money(total) + ' ' + (shop.currency || state.currency) }
            ]
          });
          if (!ok) return;
          busy = true; button.disabled = true;
          try {
            const data = await api('POST', '/api/shop/sell', { item: id, qty: n });
            toast('ขายแล้ว ' + money(data.sold) + ' ชิ้น ได้ ' + money(data.earned), 'ok');
            state.balance = data.balance; refreshStatus();
          } catch (e) {
            if (e.status === 409 && e.code === 'player_offline') toast('ต้องเข้าเกมก่อนจึงจะขายของได้', 'error');
            else showError(e);
          } finally { busy = false; await load(); }
        });
        list.append(el('div', { class: 'item' },
          el('div', { class: 'main' },
            el('div', { class: 'title', text: String(info.label || id) }),
            el('div', { class: 'sub', text: id + ' · ขายได้ ' + money(info.sell) + ' ต่อชิ้น' })),
          el('div', { class: 'actions' }, qty, button)));
      }
    }

    async function load() {
      if (disposed) return;
      try {
        shop = await api('GET', '/api/shop');
        renderList();
      } catch (e) {
        if (e.status === 401) return;
        clear(list); list.append(el('div', { class: 'empty', text: e.message || 'โหลดร้านค้าไม่สำเร็จ' }));
      }
    }

    search.addEventListener('input', renderList);
    load();
  }

  switchTab('market');
  return { dispose() { disposed = true; } };
}

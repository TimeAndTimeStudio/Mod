import { icon, itemSlot } from '../icons.js';
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
    const note = el('p', { class: 'hint', text: 'เลือกไอเท็มจากตาราง 1×1 ด้านล่าง (รวมของจากกระเป๋า, Ender Chest และกล่องในเขตที่คุณจองไว้)' });
    const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็มในตัว / ในเขต...' });
    const refresh = el('button', { class: 'secondary', type: 'button' },
      icon('refresh', 16),
      el('span', { text: 'รีเฟรชรายการของ' })
    );

    const gridWrap = el('div');
    const selectedWrap = el('div', { class: 'notice' }, 'กรุณาคลิกเลือกไอเท็มจากตาราง 1×1 ด้านบนเพื่อลงขาย');

    let selectedItem = null;
    const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
    const start = el('input', { type: 'number', value: '100', min: 1, inputMode: 'numeric' });
    const buyout = el('input', { type: 'number', min: 0, placeholder: 'ไม่บังคับ', inputMode: 'numeric' });
    const hours = el('input', { type: 'number', value: '48', min: 1, max: 168, inputMode: 'numeric' });
    const submit = el('button', { class: 'primary', type: 'button', text: 'ลงขายในตลาด', disabled: true });

    function renderSourceGrid() {
      clear(gridWrap);
      if (!sources) {
        gridWrap.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการของในตัวและในเขต…' }));
        return;
      }
      if (!sources.length) {
        gridWrap.append(el('div', { class: 'empty', text: 'ไม่พบของที่พร้อมลงขาย (ต้องออนไลน์ในเกมและมีของในตัวหรือในกล่องเขตของคุณ)' }));
        return;
      }
      const q = search.value.trim().toLowerCase();
      const filtered = sources.filter(s => !q || (s.label || s.itemId).toLowerCase().includes(q) || s.area.toLowerCase().includes(q));
      if (!filtered.length) {
        gridWrap.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่ค้นหา' }));
        return;
      }
      const grid = el('div', { class: 'source-grid' });
      for (const item of filtered) {
        const isSelected = selectedItem && selectedItem.ref === item.ref;
        const card = el('div', {
          class: 'source-slot-card' + (isSelected ? ' selected' : ''),
          onClick: () => {
            selectedItem = item;
            updateSelection();
            renderSourceGrid();
          }
        },
          itemSlot(item.itemId, item.count, 44),
          el('div', { class: 'source-item-name', text: item.label || item.itemId }),
          el('span', { class: 'source-area-tag', text: item.area })
        );
        grid.append(card);
      }
      gridWrap.append(grid);
    }

    function updateSelection() {
      clear(selectedWrap);
      if (!selectedItem) {
        selectedWrap.textContent = 'กรุณาคลิกเลือกไอเท็มจากตาราง 1×1 ด้านบนเพื่อลงขาย';
        submit.disabled = true;
        return;
      }
      submit.disabled = false;
      qty.max = String(selectedItem.count);
      if (Number(qty.value) > selectedItem.count || Number(qty.value) <= 1) {
        qty.value = String(selectedItem.count);
      }
      selectedWrap.append(
        itemSlot(selectedItem.itemId, selectedItem.count, 40),
        el('div', { style: 'display: flex; flex-direction: column; gap: 4px;' },
          el('strong', { style: 'color: var(--white); font-size: 14px;', text: 'เลือกแล้ว: ' + (selectedItem.label || selectedItem.itemId) + ' × ' + selectedItem.count + ' ชิ้น' }),
          el('span', { class: 'hint', style: 'color: var(--orange); margin: 0;', text: 'แหล่งที่เก็บ: ' + selectedItem.area + ' · รหัส ' + selectedItem.ref })
        )
      );
    }

    async function load() {
      if (disposed) return;
      try {
        sources = await fetchSources();
        if (selectedItem) {
          selectedItem = sources.find(s => s.ref === selectedItem.ref) || null;
        }
        updateSelection();
        renderSourceGrid();
      } catch (e) {
        sources = [];
        updateSelection();
        renderSourceGrid();
        if (e.status === 409) note.textContent = 'ต้องเข้าเกมก่อนจึงจะดึงของจากกระเป๋าและกล่องได้';
        else if (e.status !== 401) showError(e);
      }
    }

    search.addEventListener('input', renderSourceGrid);
    refresh.addEventListener('click', load);

    submit.addEventListener('click', async () => {
      if (busy || !selectedItem) return;
      const item = selectedItem;
      const n = Number(qty.value);
      const s = Number(start.value);
      const b = buyout.value === '' ? null : Number(buyout.value);
      const h = Number(hours.value);
      if (!Number.isInteger(n) || n < 1 || n > item.count) { toast('จำนวนไม่ถูกต้อง (1-' + item.count + ')', 'error'); return; }
      if (!Number.isInteger(s) || s < 1) { toast('ราคาเริ่มต้นไม่ถูกต้อง', 'error'); return; }
      if (b !== null && (!Number.isInteger(b) || b <= s)) { toast('ซื้อทันทีต้องมากกว่าราคาเริ่ม', 'error'); return; }
      const ok = await confirmModal({
        title: 'ยืนยันลงขายในตลาด',
        confirmText: 'ลงขาย',
        lines: [
          { label: 'ไอเท็ม', value: (item.label || item.itemId) + ' × ' + money(n) },
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
        toast('ลงขายแล้ว รหัสประมูล #' + out.id, 'ok');
        selectedItem = null;
        await load();
      } catch (e) {
        showError(e);
      } finally {
        busy = false;
        submit.disabled = false;
      }
    });

    body.append(note,
      el('div', { class: 'card' },
        el('div', { style: 'display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; flex-wrap: wrap; margin-bottom: 12px;' },
          el('div', { class: 'field', style: 'flex: 1 1 240px; margin-bottom: 0;' },
            el('label', { text: 'ตารางของในตัว + เขต (1×1 INVENTORY GRID)' }),
            search
          ),
          refresh
        ),
        gridWrap,
        selectedWrap,
        el('div', { style: 'display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 16px; margin: 24px 0 10px;' },
          el('div', { class: 'field' }, el('label', { text: 'จำนวน (QTY)' }), qty),
          el('div', { class: 'field' }, el('label', { text: 'ราคาเริ่ม (START)' }), start),
          el('div', { class: 'field' }, el('label', { text: 'ซื้อทันที (BUYOUT)' }), buyout),
          el('div', { class: 'field' }, el('label', { text: 'ระยะเวลา ชม. (HOURS)' }), hours)
        ),
        el('div', { class: 'form-actions' }, submit)
      ),
      el('p', { class: 'hint', text: 'ดูรายการที่ลงขายแล้วได้ที่หน้า “ซื้อ” หรือ “ประมูล”' }));
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
          itemSlot(id, 1, 40),
          el('div', { class: 'main' },
            el('div', { class: 'title', text: String(info.label || id) }),
            el('div', { class: 'sub', text: id + ' · ขายได้ ' + money(info.sell) + ' ต่อชิ้น' })),
          el('div', { class: 'actions' },
            el('div', { style: 'display: flex; flex-direction: column; gap: 4px;' },
              el('label', { class: 'mono-label', style: 'font-size: 10px;', text: 'จำนวน' }),
              qty
            ),
            button
          )));
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

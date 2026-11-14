import { icon, itemSlot } from '../icons.js';
import { el, clear, api, state, money, confirmModal, showError, toast, refreshStatus } from '../core.js';
import { fetchSources } from '../sources.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let sources = null;
  let tab = 'market'; // 'market' | 'shop'

  const head = el('div', { class: 'page-head' },
    el('div', null,
      el('h1', { class: 'page-title', text: 'ลงขายไอเท็ม' }),
      el('p', { class: 'hint', text: 'เลือกไอเท็มจากตาราง 1×1 (ดึงจากกระเป๋าตัวละคร, Ender Chest และกล่องในพื้นที่ที่คุณจองไว้)' }))
  );

  const marketTab = el('button', { type: 'button', class: 'active', text: 'ลงขายในตลาดผู้เล่น', onClick: () => switchTab('market') });
  const shopTab = el('button', { type: 'button', text: 'ขายให้ร้านค้ากลาง', onClick: () => switchTab('shop') });
  const tabs = el('div', { class: 'tabs' }, marketTab, shopTab);
  const body = el('div');

  container.append(head, tabs, body);

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

  // ---- Player Market Selling (1x1 Inventory Slot Table) --------------------

  function renderMarket() {
    const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็มในตัว / ในเขต...' });
    const refresh = el('button', { class: 'btn btn-secondary', type: 'button' },
      icon('refresh', 14),
      el('span', { text: 'รีเฟรชของ' })
    );

    const gridWrap = el('div');
    const selectedWrap = el('div', { class: 'notice' }, 'แตะเลือกไอเท็มจากตาราง 1×1 ด้านบนเพื่อเริ่มลงขาย');

    let selectedItem = null;
    const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
    const start = el('input', { type: 'number', value: '100', min: 1, inputMode: 'numeric' });
    const buyout = el('input', { type: 'number', min: 0, placeholder: 'ไม่บังคับ', inputMode: 'numeric' });
    const hours = el('input', { type: 'number', value: '48', min: 1, max: 168, inputMode: 'numeric' });
    const submit = el('button', { class: 'btn btn-primary', type: 'button', text: 'นำขึ้นตลาดประมูล', disabled: true });

    function renderSourceGrid() {
      clear(gridWrap);
      if (!sources) {
        gridWrap.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการไอเท็มจากในตัวและในเขต…' }));
        return;
      }
      if (!sources.length) {
        gridWrap.append(
          el('div', { class: 'empty-state' },
            el('div', { class: 'empty-state-title', text: 'ไม่พบของที่พร้อมลงขาย' }),
            el('p', { class: 'empty-state-desc', text: 'ต้องออนไลน์ในเกมและมีไอเท็มในกระเป๋า หรือในกล่องในเขตของคุณ' })
          )
        );
        return;
      }

      const q = search.value.trim().toLowerCase();
      const filtered = sources.filter(s => !q || (s.label || s.itemId).toLowerCase().includes(q) || s.area.toLowerCase().includes(q));

      if (!filtered.length) {
        gridWrap.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่ตรงกับคำค้นหา' }));
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
          itemSlot(item.itemId, item.count, 36),
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
        selectedWrap.textContent = 'แตะเลือกไอเท็มจากตาราง 1×1 ด้านบนเพื่อเริ่มลงขาย';
        submit.disabled = true;
        return;
      }
      submit.disabled = false;
      qty.max = String(selectedItem.count);
      if (Number(qty.value) > selectedItem.count || Number(qty.value) <= 1) {
        qty.value = String(selectedItem.count);
      }
      selectedWrap.append(
        itemSlot(selectedItem.itemId, selectedItem.count, 32),
        el('div', null,
          el('strong', { style: 'font-size: 14px;', text: 'เลือกแล้ว: ' + (selectedItem.label || selectedItem.itemId) + ' × ' + selectedItem.count + ' ชิ้น' }),
          el('div', { class: 'hint', style: 'color: var(--accent); margin-top: 2px;', text: 'แหล่งที่มา: ' + selectedItem.area })
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
        if (e.status !== 401) showError(e);
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
      if (b !== null && (!Number.isInteger(b) || b <= s)) { toast('ราคาซื้อทันทีต้องมากกว่าราคาเริ่ม', 'error'); return; }

      const ok = await confirmModal({
        title: 'ยืนยันนำของขึ้นประมูล',
        confirmText: 'ลงขาย',
        lines: [
          { label: 'ไอเท็ม', value: (item.label || item.itemId) + ' × ' + money(n) },
          { label: 'แหล่งของ', value: item.area },
          { label: 'ราคาเริ่มต้น', value: money(s) + ' ' + state.currency },
          { label: 'ราคาซื้อทันที', value: b === null ? 'ไม่ตั้ง' : money(b) + ' ' + state.currency },
          { label: 'ระยะเวลาประมูล', value: money(h) + ' ชั่วโมง' }
        ]
      });
      if (!ok) return;

      busy = true;
      submit.disabled = true;
      try {
        const out = await api('POST', '/api/auctions', {
          ref: item.ref, count: n, startPrice: s, buyout: b === null ? 0 : b, hours: h
        });
        toast('นำไอเท็มขึ้นตลาดประมูลเรียบร้อย (รหัส #' + out.id + ')', 'ok');
        selectedItem = null;
        await load();
      } catch (e) {
        showError(e);
      } finally {
        busy = false;
        submit.disabled = false;
      }
    });

    body.append(
      el('div', { class: 'card' },
        el('div', { class: 'data-toolbar', style: 'margin-bottom: 0;' },
          el('div', { class: 'toolbar-left', style: 'flex: 1 1 240px;' }, search),
          el('div', { class: 'toolbar-right' }, refresh)
        ),
        gridWrap,
        selectedWrap,
        el('div', { style: 'display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 16px; margin: 20px 0 8px;' },
          el('div', { class: 'field' },
            el('label', null, el('span', { text: 'จำนวนที่ขาย' }), el('span', { class: 'req', text: '*' })),
            qty
          ),
          el('div', { class: 'field' },
            el('label', null, el('span', { text: 'ราคาเริ่มต้น' }), el('span', { class: 'req', text: '*' })),
            start
          ),
          el('div', { class: 'field' },
            el('label', { text: 'ซื้อทันที (ไม่บังคับ)' }),
            buyout
          ),
          el('div', { class: 'field' },
            el('label', { text: 'ชั่วโมงประมูล' }),
            hours
          )
        ),
        el('div', { class: 'form-actions' }, submit)
      )
    );
    load();
  }

  // ---- Server Shop Selling --------------------------------------------------

  function renderShop() {
    let shop = null;
    const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็มที่ร้านรับซื้อ...' });
    const listWrap = el('div');

    body.append(
      el('p', { class: 'hint', text: 'ขายให้ร้านค้ากลางได้เฉพาะของในกระเป๋าตัวละคร (ของต้องไม่มี enchant หรือความเสียหาย)' }),
      el('div', { class: 'data-toolbar' },
        el('div', { class: 'toolbar-left', style: 'flex: 1 1 240px;' }, search)
      ),
      listWrap
    );

    function renderList() {
      if (disposed) return;
      clear(listWrap);
      if (!shop) {
        listWrap.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการที่ร้านรับซื้อ…' }));
        return;
      }
      const q = search.value.trim().toLowerCase();
      const entries = Object.entries(shop.items || {})
        .filter(([id, info]) => !q || id.toLowerCase().includes(q) || String(info.label || '').toLowerCase().includes(q))
        .sort((a, b) => String(a[1].label || a[0]).localeCompare(String(b[1].label || b[0]), 'th'));

      if (!entries.length) {
        listWrap.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่ค้นหา' }));
        return;
      }

      const list = el('div', { class: 'ruled-list' });
      for (const [id, info] of entries) {
        const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
        const sellBtn = el('button', { class: 'btn btn-primary', type: 'button', text: 'ขาย' });

        sellBtn.addEventListener('click', async () => {
          const n = Number(qty.value);
          if (!Number.isInteger(n) || n < 1) { toast('จำนวนต้องเป็นจำนวนเต็มตั้งแต่ 1', 'error'); return; }
          if (busy) return;
          const total = Number(info.sell) * n;
          const ok = await confirmModal({
            title: 'ยืนยันการขายให้ร้านค้า',
            confirmText: 'ขายไอเท็ม',
            lines: [
              { label: 'ไอเท็ม', value: String(info.label || id) },
              { label: 'จำนวน', value: money(n) + ' ชิ้น' },
              { label: 'เงินที่จะได้รับ', value: money(total) + ' ' + (shop.currency || state.currency) }
            ]
          });
          if (!ok) return;

          busy = true;
          sellBtn.disabled = true;
          try {
            const data = await api('POST', '/api/shop/sell', { item: id, qty: n });
            toast('ขายเรียบร้อยแล้ว ได้รับ ' + money(data.earned) + ' ' + (shop.currency || state.currency), 'ok');
            state.balance = data.balance;
            refreshStatus();
          } catch (e) {
            if (e.status === 409 && e.code === 'player_offline') toast('ต้องเข้าเกมก่อนจึงจะขายของได้', 'error');
            else showError(e);
          } finally {
            busy = false;
            await load();
          }
        });

        list.append(
          el('div', { class: 'ruled-row' },
            itemSlot(id, 1, 36),
            el('div', { class: 'row-main' },
              el('div', { class: 'row-title', text: String(info.label || id) }),
              el('div', { class: 'row-meta' },
                el('span', { class: 'mono', text: id }),
                el('span', { style: 'margin: 0 6px;', text: '·' }),
                el('span', { style: 'color: var(--accent);', text: 'รับซื้อชิ้นละ ' + money(info.sell) + ' ' + (shop.currency || state.currency) })
              )
            ),
            el('div', { class: 'row-actions' },
              el('div', { style: 'display: flex; flex-direction: column; gap: 3px;' },
                el('label', { class: 'mono-label', style: 'font-size: 10px;', text: 'จำนวน' }),
                qty
              ),
              sellBtn
            )
          )
        );
      }
      listWrap.append(list);
    }

    async function load() {
      if (disposed) return;
      try {
        shop = await api('GET', '/api/shop');
        renderList();
      } catch (e) {
        if (e.status !== 401) showError(e);
      }
    }

    search.addEventListener('input', renderList);
    load();
  }

  return {
    dispose() { disposed = true; }
  };
}

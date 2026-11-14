import { icon, itemSlot } from '../icons.js';
import { el, clear, api, state, money, confirmModal, showError, toast, refreshStatus, navigate } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;
  let shop = null;
  let auctions = null;
  let activeTab = 'shop'; // 'shop' | 'players'
  let sortMode = 'name'; // 'name' | 'price-asc' | 'price-desc'
  let pageSize = 20;

  // Header
  const head = el('div', { class: 'page-head' },
    el('div', null,
      el('h1', { class: 'page-title', text: 'ซื้อของ' }),
      el('p', { class: 'hint', text: 'ไอเท็มที่ซื้อจะเข้าสู่กล่องรับของก่อนเสมอ สามารถรับเข้าตัวได้ทันที' })),
    el('button', { class: 'btn btn-secondary', onClick: () => navigate('sell') }, 'ลงขายของในตลาด')
  );

  // Tabs
  const tabShop = el('button', { type: 'button', class: 'active', text: 'ร้านค้าเซิร์ฟเวอร์', onClick: () => switchTab('shop') });
  const tabPlayers = el('button', { type: 'button', text: 'ตลาดของผู้เล่น', onClick: () => switchTab('players') });
  const tabs = el('div', { class: 'tabs' }, tabShop, tabPlayers);

  // ADDED: Toolbar with Search and Sort
  const searchInput = el('input', { type: 'search', placeholder: 'ค้นหาชื่อหรือรหัสไอเท็ม...' });
  const sortSelect = el('select', {
    onInput: e => { sortMode = e.target.value; renderContent(); }
  },
    el('option', { value: 'name', text: 'เรียงตามชื่อ (A-Z)' }),
    el('option', { value: 'price-asc', text: 'ราคา: ต่ำไปสูง' }),
    el('option', { value: 'price-desc', text: 'ราคา: สูงไปต่ำ' })
  );

  const toolbar = el('div', { class: 'data-toolbar' },
    el('div', { class: 'toolbar-left', style: 'flex: 1 1 260px;' }, searchInput),
    el('div', { class: 'toolbar-right' },
      el('span', { class: 'mono-label', text: 'จัดเรียง:' }),
      sortSelect
    )
  );

  const contentWrap = el('div');
  container.append(head, tabs, toolbar, contentWrap);

  function switchTab(tab) {
    activeTab = tab;
    tabShop.classList.toggle('active', activeTab === 'shop');
    tabPlayers.classList.toggle('active', activeTab === 'players');
    pageSize = 20;
    renderContent();
  }

  function renderContent() {
    if (disposed) return;
    clear(contentWrap);
    if (activeTab === 'shop') renderShop();
    else renderPlayers();
  }

  function renderShop() {
    if (!shop) {
      contentWrap.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการสินค้าในร้าน…' }));
      return;
    }
    const q = searchInput.value.trim().toLowerCase();
    let entries = Object.entries(shop.items || {})
      .filter(([id, info]) => !q || id.toLowerCase().includes(q) || String(info.label || '').toLowerCase().includes(q));

    if (sortMode === 'name') {
      entries.sort((a, b) => String(a[1].label || a[0]).localeCompare(String(b[1].label || b[0]), 'th'));
    } else if (sortMode === 'price-asc') {
      entries.sort((a, b) => Number(a[1].buy) - Number(b[1].buy));
    } else if (sortMode === 'price-desc') {
      entries.sort((a, b) => Number(b[1].buy) - Number(a[1].buy));
    }

    if (!entries.length) {
      /* ADDED: Clean empty state */
      contentWrap.append(
        el('div', { class: 'empty-state' },
          el('div', { class: 'empty-state-title', text: 'ไม่พบไอเท็มที่ค้นหา' }),
          el('p', { class: 'empty-state-desc', text: 'ลองพิมพ์ชื่อภาษาอังกฤษ เช่น diamond หรือ iron_ingot' })
        )
      );
      return;
    }

    const visible = entries.slice(0, pageSize);
    const list = el('div', { class: 'ruled-list' });

    for (const [id, info] of visible) {
      const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
      const buyBtn = el('button', { class: 'btn btn-primary', type: 'button', text: 'ซื้อ' });

      buyBtn.addEventListener('click', async () => {
        const n = Number(qty.value);
        if (!Number.isInteger(n) || n < 1) { toast('จำนวนต้องเป็นจำนวนเต็มตั้งแต่ 1', 'error'); return; }
        if (busy) return;
        const total = Number(info.buy) * n;
        const currency = shop.currency || state.currency;
        const ok = await confirmModal({
          title: 'ยืนยันการสั่งซื้อ',
          confirmText: 'ซื้อไอเท็ม',
          lines: [
            { label: 'ไอเท็ม', value: String(info.label || id) },
            { label: 'จำนวน', value: money(n) + ' ชิ้น' },
            { label: 'ราคารวม', value: money(total) + ' ' + currency },
            { label: 'ยอดคงเหลือหลังซื้อ', value: money(state.balance - total) + ' ' + currency }
          ]
        });
        if (!ok) return;
        busy = true;
        buyBtn.disabled = true;
        try {
          const data = await api('POST', '/api/shop/buy', { item: id, qty: n });
          toast('ซื้อแล้ว ' + money(data.bought) + ' ชิ้น (ของถูกส่งเข้ากล่องรับของ)', 'ok');
          state.balance = data.balance;
          state.mailboxCount = data.mailboxCount;
          refreshStatus();
        } catch (e) {
          showError(e);
        } finally {
          busy = false;
          await load();
        }
      });

      list.append(
        el('div', { class: 'ruled-row' },
          itemSlot(id, 1, 40),
          el('div', { class: 'row-main' },
            el('div', { class: 'row-title', text: String(info.label || id) }),
            el('div', { class: 'row-meta' },
              el('span', { class: 'mono', text: id }),
              el('span', { style: 'margin: 0 6px;', text: '·' }),
              el('span', { style: 'color: var(--accent); font-weight: 600;', text: money(info.buy) + ' ' + (shop.currency || state.currency) + '/ชิ้น' })
            )
          ),
          el('div', { class: 'row-actions' },
            el('div', { style: 'display: flex; flex-direction: column; gap: 3px;' },
              el('label', { class: 'mono-label', style: 'font-size: 10px;', text: 'จำนวน' }),
              qty
            ),
            buyBtn
          )
        )
      );
    }

    contentWrap.append(list);

    // ADDED: Pagination / Load more
    if (entries.length > pageSize) {
      const moreBtn = el('button', {
        class: 'btn btn-secondary',
        style: 'width: 100%; margin-top: 8px;',
        onClick: () => { pageSize += 20; renderContent(); }
      }, `แสดงเพิ่ม (${entries.length - pageSize} รายการที่เหลือ)`);
      contentWrap.append(moreBtn);
    }
  }

  function renderPlayers() {
    if (!auctions) {
      contentWrap.append(el('div', { class: 'empty', text: 'กำลังโหลดรายการขายของผู้เล่น…' }));
      return;
    }
    const q = searchInput.value.trim().toLowerCase();
    let items = (auctions.auctions || [])
      .filter(a => a.status === 'ACTIVE' && !a.mine)
      .filter(a => !q || (a.label || a.itemId).toLowerCase().includes(q) || a.sellerName.toLowerCase().includes(q));

    if (sortMode === 'name') {
      items.sort((a, b) => String(a.label || a.itemId).localeCompare(String(b.label || b.itemId), 'th'));
    } else if (sortMode === 'price-asc') {
      items.sort((a, b) => Number(a.buyout || a.startPrice) - Number(b.buyout || b.startPrice));
    } else if (sortMode === 'price-desc') {
      items.sort((a, b) => Number(b.buyout || b.startPrice) - Number(a.buyout || a.startPrice));
    }

    if (!items.length) {
      contentWrap.append(
        el('div', { class: 'empty-state' },
          el('div', { class: 'empty-state-title', text: 'ยังไม่มีผู้เล่นลงขายไอเท็มในหมวดนี้' }),
          el('p', { class: 'empty-state-desc', text: 'คุณสามารถเป็นผู้ขายคนแรกได้โดยไปที่หน้า “ลงขาย”' }),
          el('button', { class: 'btn btn-primary', onClick: () => navigate('sell') }, 'ลงขายของ')
        )
      );
      return;
    }

    const list = el('div', { class: 'ruled-list' });
    for (const a of items.slice(0, pageSize)) {
      const actions = el('div', { class: 'row-actions' });
      if (a.buyout) {
        const buyBtn = el('button', { class: 'btn btn-primary', type: 'button', text: 'ซื้อทันที ' + money(a.buyout) });
        buyBtn.addEventListener('click', async () => {
          if (busy) return;
          const ok = await confirmModal({
            title: 'ยืนยันซื้อทันทีจากผู้เล่น',
            confirmText: 'ซื้อทันที',
            lines: [
              { label: 'ไอเท็ม', value: a.label + ' × ' + money(a.count) },
              { label: 'ผู้ขาย', value: a.sellerName },
              { label: 'ราคา', value: money(a.buyout) + ' ' + (auctions.currency || state.currency) }
            ]
          });
          if (!ok) return;
          busy = true;
          buyBtn.disabled = true;
          try {
            const out = await api('POST', '/api/auctions/' + encodeURIComponent(a.id) + '/buyout', {});
            toast('ซื้อสำเร็จแล้ว ไอเท็มถูกส่งเข้ากล่องรับของของคุณ', 'ok');
            state.balance = out.balance;
            state.mailboxCount = out.mailboxCount;
            refreshStatus();
            await load();
          } catch (e) {
            showError(e);
          } finally {
            busy = false;
          }
        });
        actions.append(buyBtn);
      } else {
        actions.append(el('button', { class: 'btn btn-secondary', onClick: () => navigate('auction') }, 'เสนอราคาประมูล'));
      }

      list.append(
        el('div', { class: 'ruled-row' },
          itemSlot(a.itemId, a.count, 40),
          el('div', { class: 'row-main' },
            el('div', { class: 'row-title', text: a.label + ' × ' + money(a.count) }),
            el('div', { class: 'row-meta' },
              el('span', { text: 'ผู้ขาย: ' + a.sellerName }),
              el('span', { style: 'margin: 0 6px;', text: '·' }),
              el('span', { style: 'color: var(--accent);', text: a.buyout ? 'ซื้อทันที ' + money(a.buyout) : 'ประมูลเริ่ม ' + money(a.startPrice) })
            )
          ),
          actions
        )
      );
    }
    contentWrap.append(list);
  }

  async function load() {
    if (disposed) return;
    try {
      const [shopData, auctionsData] = await Promise.all([
        api('GET', '/api/shop').catch(() => null),
        api('GET', '/api/auctions').catch(() => null)
      ]);
      shop = shopData;
      auctions = auctionsData;
      renderContent();
    } catch (e) {
      if (e.status !== 401) showError(e);
    }
  }

  searchInput.addEventListener('input', renderContent);
  load();

  return {
    dispose() { disposed = true; }
  };
}

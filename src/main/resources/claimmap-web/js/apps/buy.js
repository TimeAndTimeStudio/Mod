import { el, clear, api, state, money, confirmModal, showError, toast, refreshStatus, navigate } from '../core.js';

export function render(container) {
  let disposed = false;
  let busy = false;	let shop = null;
	let auctions = null;

	container.append(el('div', { class: 'page-head' },
		el('div', null,
			el('h1', { text: 'ซื้อ' }),
			el('p', { class: 'hint', text: 'ของจากร้านและของผู้เล่นจะเข้ากล่องของก่อน' })),
		el('button', { class: 'primary', type: 'button', text: '＋ ลงขาย', onClick: () => navigate('sell') })));
	const note = el('p', { class: 'hint', text: 'ของที่ซื้อจะเข้ากล่องของก่อน แล้วเข้าเกมพิมพ์ /mail claim เพื่อรับ' });
  const search = el('input', { type: 'search', placeholder: 'ค้นหาไอเท็ม' });
  const list = el('div', { class: 'list' });
  const stale = el('div');	const playersTitle = el('h2', { text: 'ของผู้เล่น (ตลาด)' });
	const playersList = el('div', { class: 'list' });
	container.append(note, el('div', { class: 'toolbar' }, el('div', { class: 'field' }, search)), stale, list,
		playersTitle, playersList);

  function renderList() {
    if (disposed) return;
    clear(list);
    if (!shop) {
      list.append(el('div', { class: 'empty', text: 'กำลังโหลด…' }));
      return;
    }
    const query = search.value.trim().toLowerCase();
    const entries = Object.entries(shop.items || {})
      .filter(([id, info]) => !query || id.toLowerCase().includes(query) || String(info.label || '').toLowerCase().includes(query))
      .sort((a, b) => String(a[1].label || a[0]).localeCompare(String(b[1].label || b[0]), 'th'));

    if (!entries.length) {
      list.append(el('div', { class: 'empty', text: 'ไม่พบไอเท็มที่ซื้อได้' }));
      return;
    }
    for (const [id, info] of entries) {
      const qty = el('input', { type: 'number', value: '1', min: 1, max: 2304, inputMode: 'numeric' });
      const button = el('button', { class: 'primary', type: 'button', text: 'ซื้อ' });
      button.addEventListener('click', async () => {
        const n = Number(qty.value);
        if (!Number.isInteger(n) || n < 1) { toast('จำนวนต้องเป็นจำนวนเต็มตั้งแต่ 1', 'error'); return; }
        if (busy) return;
        const total = Number(info.buy) * n;
        const currency = shop.currency || state.currency;
        const ok = await confirmModal({
          title: 'ยืนยันการซื้อ',
          confirmText: 'ซื้อ',
          lines: [
            { label: 'ไอเท็ม', value: String(info.label || id) },
            { label: 'จำนวน', value: money(n) + ' ชิ้น' },
            { label: 'ราคารวม', value: money(total) + ' ' + currency },
            { label: 'ยอดคงเหลือตอนนี้', value: money(state.balance) + ' ' + currency },
            { label: 'ยอดคงเหลือหลังซื้อ', value: money(state.balance - total) + ' ' + currency }
          ]
        });
        if (!ok) return;
        busy = true;
        button.disabled = true;
        try {
          const data = await api('POST', '/api/shop/buy', { item: id, qty: n });
          toast('ซื้อแล้ว ' + money(data.bought) + ' ชิ้น ใช้ ' + money(data.spent) + ' ' + currency + ' — ของอยู่ในกล่องของ', 'ok');
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
      list.append(el('div', { class: 'item' },
        el('div', { class: 'main' },
          el('div', { class: 'title', text: String(info.label || id) }),
          el('div', { class: 'sub', text: id + ' · ซื้อ ' + money(info.buy) + ' ' + (shop.currency || state.currency) + ' ต่อชิ้น' })),
        el('div', { class: 'actions' }, qty, button)));
    }
  }	function renderPlayers() {
		if (disposed) return;
		clear(playersList);
		if (!auctions) { playersList.append(el('div', { class: 'empty', text: 'กำลังโหลด…' })); return; }
		const listItems = (auctions.auctions || []).filter(a => a.status === 'ACTIVE' && !a.mine);
		if (!listItems.length) { playersList.append(el('div', { class: 'empty', text: 'ยังไม่มีผู้เล่นลงขาย' })); return; }
		for (const a of listItems) {
			const actions = el('div', { class: 'actions' });
			if (a.buyout) {
				const buy = el('button', { class: 'primary', type: 'button', text: 'ซื้อทันที ' + money(a.buyout) });
				buy.addEventListener('click', async () => {
					if (busy) return;
					const ok = await confirmModal({
						title: 'ยืนยันซื้อทันที', confirmText: 'ซื้อ',
						lines: [{ label: 'ไอเท็ม', value: a.label + ' × ' + money(a.count) }, { label: 'ผู้ขาย', value: a.sellerName }, { label: 'ราคา', value: money(a.buyout) + ' ' + (auctions.currency || state.currency) }]
					});
					if (!ok) return;
					busy = true; buy.disabled = true;
					try {
						const out = await api('POST', '/api/auctions/' + encodeURIComponent(a.id) + '/buyout', {});
						toast('ซื้อแล้ว ของอยู่ในกล่องของ', 'ok');
						state.balance = out.balance; state.mailboxCount = out.mailboxCount; refreshStatus();
						await load();
					} catch (e) { showError(e); } finally { busy = false; }
				});
				actions.append(buy);
			} else {
				actions.append(el('button', { class: 'secondary', type: 'button', text: 'ดูการประมูล', onClick: () => navigate('auction') }));
			}
			playersList.append(el('div', { class: 'item' },
				el('div', { class: 'main' },
					el('div', { class: 'title', text: a.label + ' × ' + money(a.count) }),
					el('div', { class: 'sub', text: 'ผู้ขาย ' + a.sellerName + ' · เริ่ม ' + money(a.startPrice) + (a.highBid ? ' · สูงสุด ' + money(a.highBid) : '') })),
				actions));
		}
	}

	async function load() {
		if (disposed) return;
		try {
			shop = await api('GET', '/api/shop');
			clear(stale);
			if (shop.stale) {
				stale.append(el('p', { class: 'hint', text: '⚠️ ราคาที่แสดงเป็นข้อมูลเก่า ระบบจะอัปเดตเมื่อเชื่อมต่อได้' }));
			}
			if (typeof shop.mailboxCount === 'number') state.mailboxCount = shop.mailboxCount;
			renderList();
		} catch (e) {
			if (e.status === 401) return;
			clear(list);
			list.append(el('div', { class: 'empty', text: e.message || 'โหลดร้านค้าไม่สำเร็จ' }));
		}
		try {
			auctions = await api('GET', '/api/auctions');
			renderPlayers();
		} catch (e) {
			if (e.status === 401) return;
			clear(playersList);
			playersList.append(el('div', { class: 'empty', text: e.message || 'โหลดตลาดผู้เล่นไม่สำเร็จ' }));
		}
	}

  search.addEventListener('input', renderList);
  load();

  return {
    dispose() { disposed = true; }
  };
}

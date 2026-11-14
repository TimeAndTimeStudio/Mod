import { el, state, navigate, money } from '../core.js';
import { icon } from '../icons.js';

export function render(container) {
  // Page Header
  container.append(
    el('div', { class: 'page-head' },
      el('div', null,
        el('h1', { class: 'page-title', text: state.me ? 'ยินดีต้อนรับ, ' + state.me : 'Time SMP Terminal' }),
        el('p', { class: 'hint', text: 'ระบบศูนย์กลางเศรษฐกิจ การค้าไอเท็ม และการถือครองกรรมสิทธิ์ที่ดิน' })),
      el('div', { style: 'display: flex; gap: 8px;' },
        el('button', { class: 'btn btn-secondary', onClick: () => navigate('prices') }, 'ดัชนีราคา'),
        el('button', { class: 'btn btn-primary', onClick: () => navigate('buy') }, 'เข้าสู่ร้านค้า')
      )
    )
  );

  // Top Metrics Strip
  const metrics = el('div', { style: 'display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 16px; margin-bottom: 32px;' },
    el('div', { class: 'card', style: 'margin-bottom: 0;' },
      el('span', { class: 'mono-label', text: 'ยอดเงินคงเหลือ' }),
      el('div', { style: 'font-size: 24px; font-weight: 600; margin: 4px 0;' },
        el('span', { class: 'num', text: money(state.balance) }),
        el('span', { class: 'hint', style: 'font-size: 14px; margin-left: 6px;', text: state.currency || 'เหรียญ' })
      ),
      el('button', { class: 'btn btn-ghost btn-sm', style: 'padding: 0;', onClick: () => navigate('wallet') }, 'เปิดกระเป๋าเงิน →')
    ),
    el('div', { class: 'card', style: 'margin-bottom: 0;' },
      el('span', { class: 'mono-label', text: 'ของในกล่องจดหมาย' }),
      el('div', { style: 'font-size: 24px; font-weight: 600; margin: 4px 0;' },
        el('span', { class: 'num', text: String(state.mailboxCount || 0) }),
        el('span', { class: 'hint', style: 'font-size: 14px; margin-left: 6px;', text: 'รายการรอรับ' })
      ),
      el('button', { class: 'btn btn-ghost btn-sm', style: 'padding: 0;', onClick: () => navigate('mailbox') }, 'รับของเข้าตัว →')
    ),
    el('div', { class: 'card', style: 'margin-bottom: 0;' },
      el('span', { class: 'mono-label', text: 'พิกัดผู้เล่นล่าสุด' }),
      el('div', { style: 'font-size: 24px; font-weight: 600; margin: 4px 0;' },
        el('span', { class: 'num', text: typeof state.lastX === 'number' ? `X ${state.lastX}, Z ${state.lastZ}` : 'ยังไม่มีพิกัด' })
      ),
      el('button', { class: 'btn btn-ghost btn-sm', style: 'padding: 0;', onClick: () => navigate('map') }, 'เปิดแผนที่สำรวจ →')
    )
  );
  container.append(metrics);

  // Fast Navigation Ruled List (Swiss Technical Architecture)
  const navItems = [
    { key: 'buy', index: '01', title: 'ซื้อของ (Market & Shop)', desc: 'ซื้อสินค้าจากร้านค้ากลางเซิร์ฟเวอร์ หรือเลือกซื้อจากผู้เล่นอื่นในราคาซื้อทันที' },
    { key: 'sell', index: '02', title: 'ลงขาย (Inventory & Territory)', desc: 'ดึงของจากในกระเป๋าตัวละคร, Ender Chest หรือกล่องในเขตของคุณขึ้นสู่ตลาด' },
    { key: 'auction', index: '03', title: 'ตลาดประมูล (Live Auctions)', desc: 'แข่งขันเสนอราคาแบบสด ชนะการประมูลเพื่อรับของ หรือตั้งราคาซื้อทันที' },
    { key: 'prices', index: '04', title: 'ดัชนีราคากลาง (Price Engine)', desc: 'ตรวจสอบราคาซื้อและราคารับซื้อของไอเท็มทุกชนิด เชื่อมต่อฐานข้อมูลระบบของคุณ' },
    { key: 'map', index: '05', title: 'แผนที่ดินแดน (Territory Map)', desc: 'สำรวจพื้นที่จอง chunk 500×500 บล็อก จัดการและโอนกรรมสิทธิ์เขต' },
    { key: 'wallet', index: '06', title: 'กระเป๋าเงินและธุรกรรม (Ledger)', desc: 'ตรวจสอบประวัติการเงิน โอนเงินให้ผู้เล่นอื่นโดยไม่มีค่าธรรมเนียม' },
    { key: 'mailbox', index: '07', title: 'กล่องรับของ (Mailbox Depot)', desc: 'รับไอเท็มทั้งหมดที่ได้จากการซื้อและประมูล พร้อมระบบเช็คช่องว่างในกระเป๋า' }
  ];

  if (state.admin) {
    navItems.push({
      key: 'admin',
      index: '08',
      title: 'แผงควบคุมแอดมิน (Admin Console)',
      desc: 'ควบคุมระบบเศรษฐกิจ ปรับยอดเงิน ปลดบล็อกไอเท็ม และจัดการบัญชีผู้เล่น'
    });
  }

  const list = el('div', { class: 'ruled-list' });
  for (const item of navItems) {
    list.append(
      el('div', {
        class: 'ruled-row',
        style: 'cursor: pointer; padding: 16px 8px;',
        onClick: () => navigate(item.key)
      },
        el('div', { class: 'row-main' },
          el('div', { style: 'display: flex; align-items: baseline; gap: 12px;' },
            el('span', { class: 'mono-label', style: 'color: var(--accent);', text: item.index }),
            el('span', { class: 'row-title', text: item.title })
          ),
          el('div', { class: 'row-meta', style: 'padding-left: 26px;', text: item.desc })
        ),
        el('div', { class: 'row-actions' },
          el('span', { class: 'btn btn-ghost btn-sm', text: 'เปิด →' })
        )
      )
    );
  }

  container.append(
    el('div', { style: 'margin-top: 16px; margin-bottom: 8px;' },
      el('h3', { style: 'font-size: 16px; font-weight: 600;', text: 'บริการทั้งหมดในระบบ' })
    ),
    list
  );
}

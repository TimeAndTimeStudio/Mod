import { el, state, navigate, money } from '../core.js';
import { icon } from '../icons.js';

const PRIMARY = [
  { key: 'buy', icon: 'buy', name: 'ซื้อของ', code: 'MARKET.BUY', desc: 'ซื้อจากร้านค้าเซิร์ฟเวอร์และตลาดผู้เล่น' },
  { key: 'sell', icon: 'sell', name: 'ลงขาย', code: 'MARKET.SELL', desc: 'ดึงของจากกระเป๋า, Ender Chest และกล่องในพื้นที่' },
  { key: 'auction', icon: 'auction', name: 'ประมูล', code: 'MARKET.AUCTION', desc: 'เสนอราคาแข่งกันแบบสด หรือตั้งซื้อทันที' }
];

const SECONDARY = [
  { key: 'map', icon: 'map', name: 'แผนที่โลก', code: 'WORLD.GRID', desc: 'ตรวจสอบพิกัดและพื้นที่จอง' },
  { key: 'wallet', icon: 'wallet', name: 'กระเป๋าเงิน', code: 'LEDGER.WALLET', desc: 'ยอดเงิน ประวัติ และการโอน' },
  { key: 'mailbox', icon: 'mailbox', name: 'กล่องรับของ', code: 'DEPOT.MAILBOX', desc: 'พัสดุและไอเท็มรอรับเข้าตัว' },
  { key: 'prices', icon: 'prices', name: 'ดัชนีราคา', code: 'INDEX.PRICES', desc: 'ราคากลางซื้อขายของเซิร์ฟเวอร์' }
];

export function render(container) {
  // Hero Command Deck
  const hero = el('section', { class: 'hero' },
    el('div', { class: 'hero-main' },
      el('div', { class: 'hero-kicker' },
        el('span', { class: 'sys-tag', text: '00 // SYSTEM DECK' }),
        el('span', { class: 'sys-code', text: 'NODE: 0.0.0.0:8085' })
      ),
      el('h1', { text: state.me ? 'OPERATOR: ' + state.me : 'TIME SMP' }),
      el('p', { class: 'hint', text: 'ศูนย์กลางการค้าเสรี จัดการกรรมสิทธิ์ที่ดิน และระบบเศรษฐกิจอัตโนมัติ' })
    ),
    el('div', { class: 'hero-stats' },
      el('div', { class: 'stat-box' },
        el('div', { class: 'stat-header' },
          icon('coin', 14),
          el('span', { class: 'stat-label', text: 'ยอดเงินคงเหลือ' })
        ),
        el('div', { class: 'stat-num' },
          el('strong', { class: 'stat-value', text: money(state.balance) }),
          el('span', { class: 'stat-unit', text: state.currency || 'เหรียญ' })
        ),
        el('button', { class: 'stat-btn', type: 'button', onClick: () => navigate('wallet') },
          icon('arrow', 14),
          el('span', { text: 'ดูกระเป๋าเงิน' })
        )
      ),
      el('div', { class: 'stat-box' },
        el('div', { class: 'stat-header' },
          icon('mailbox', 14),
          el('span', { class: 'stat-label', text: 'พัสดุในกล่อง' })
        ),
        el('div', { class: 'stat-num' },
          el('strong', { class: 'stat-value', text: String(state.mailboxCount || 0) }),
          el('span', { class: 'stat-unit', text: 'รายการ' })
        ),
        el('button', { class: 'stat-btn', type: 'button', onClick: () => navigate('mailbox') },
          icon('arrow', 14),
          el('span', { text: 'เปิดกล่องของ' })
        )
      )
    )
  );
  container.append(hero);

  // Primary Commerce Grid
  container.append(
    el('div', { class: 'section-head' },
      el('div', { class: 'section-title-wrap' },
        el('span', { class: 'section-prefix', text: '01 //' }),
        el('h2', { text: 'ตลาดและการค้า' }),
        el('span', { class: 'section-tag', text: 'COMMERCE HUB' })
      )
    ),
    el('div', { class: 'tile-grid primary-grid' }, PRIMARY.map(app =>
      el('button', { class: 'app-tile primary-tile', type: 'button', onClick: () => navigate(app.key) },
        el('div', { class: 'tile-top' },
          el('span', { class: 'tile-code', text: app.code }),
          el('span', { class: 'tile-ico' }, icon(app.icon, 22))
        ),
        el('div', { class: 'tile-content' },
          el('span', { class: 'tile-name', text: app.name }),
          el('span', { class: 'tile-desc', text: app.desc })
        ),
        el('div', { class: 'tile-bottom' },
          el('span', { class: 'tile-action', text: 'เข้าสู่แอป' }),
          icon('arrow', 14)
        )
      )
    ))
  );

  // Secondary Tools Grid
  const secondaryApps = SECONDARY.slice();
  if (state.admin) {
    secondaryApps.push({
      key: 'admin',
      icon: 'admin',
      name: 'ระบบแอดมิน',
      code: 'SYS.SECURITY',
      desc: 'ควบคุมระบบเงิน ราคากลาง และความปลอดภัย'
    });
  }

  container.append(
    el('div', { class: 'section-head' },
      el('div', { class: 'section-title-wrap' },
        el('span', { class: 'section-prefix', text: '02 //' }),
        el('h2', { text: 'เครื่องมือและทรัพยากร' }),
        el('span', { class: 'section-tag', text: 'UTILITIES' })
      )
    ),
    el('div', { class: 'tile-grid secondary-grid' }, secondaryApps.map(app =>
      el('button', { class: 'app-tile secondary-tile', type: 'button', onClick: () => navigate(app.key) },
        el('div', { class: 'tile-top' },
          el('span', { class: 'tile-code', text: app.code }),
          el('span', { class: 'tile-ico' }, icon(app.icon, 18))
        ),
        el('div', { class: 'tile-content' },
          el('span', { class: 'tile-name', text: app.name }),
          el('span', { class: 'tile-desc', text: app.desc })
        ),
        el('div', { class: 'tile-bottom' },
          el('span', { class: 'tile-action', text: 'เปิดใช้งาน' }),
          icon('arrow', 12)
        )
      )
    ))
  );

  // In-Game Commands Reference Terminal
  container.append(
    el('div', { class: 'section-head' },
      el('div', { class: 'section-title-wrap' },
        el('span', { class: 'section-prefix', text: '03 //' }),
        el('h2', { text: 'คำสั่งคอนโซลในเกม' }),
        el('span', { class: 'section-tag', text: 'CLI REFERENCE' })
      )
    ),
    el('div', { class: 'card' },
      el('div', { class: 'list' },
        el('div', { class: 'item' }, el('code', { class: 'mono-num', text: '/bal' }), el('span', { class: 'hint', text: 'ตรวจสอบยอดเงินคงเหลือในบัญชี' })),
        el('div', { class: 'item' }, el('code', { class: 'mono-num', text: '/pay <ผู้เล่น> <จำนวน>' }), el('span', { class: 'hint', text: 'โอนเงินให้ผู้เล่นคนอื่นในระบบ' })),
        el('div', { class: 'item' }, el('code', { class: 'mono-num', text: '/sell hand | /sell all' }), el('span', { class: 'hint', text: 'ขายไอเท็มในมือหรือในกระเป๋าให้ร้านค้า' })),
        el('div', { class: 'item' }, el('code', { class: 'mono-num', text: '/buy <item> <จำนวน>' }), el('span', { class: 'hint', text: 'ซื้อไอเท็มจากร้านค้า (ส่งเข้ากล่องของ)' })),
        el('div', { class: 'item' }, el('code', { class: 'mono-num', text: '/mail | /mail claim' }), el('span', { class: 'hint', text: 'ตรวจสอบและรับไอเท็มจากกล่องเข้าตัว' })),
        el('div', { class: 'item' }, el('code', { class: 'mono-num', text: '/claim | /claim transfer <ผู้เล่น>' }), el('span', { class: 'hint', text: 'จองพื้นที่ chunk 500x500 หรือโอนเขตให้ผู้เล่นอื่น' })),
        el('div', { class: 'item' }, el('code', { class: 'mono-num', text: '/claimmap here | /claimmap transfer' }), el('span', { class: 'hint', text: 'ตรวจสอบพิกัดหรือคำสั่งจัดการเขตแอดมิน' }))
      )
    )
  );
}

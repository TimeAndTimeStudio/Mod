import { el, state, navigate, money } from '../core.js';
import { icon } from '../icons.js';

const PRIMARY = [
  { key: 'buy', icon: 'buy', name: 'ซื้อของ', code: 'MARKET.BUY', desc: 'ซื้อจากร้านค้าเซิร์ฟเวอร์และตลาดผู้เล่น' },
  { key: 'sell', icon: 'sell', name: 'ลงขาย', code: 'MARKET.SELL', desc: 'ดึงของจากกระเป๋า, Ender Chest และกล่องในพื้นที่' },
  { key: 'auction', icon: 'auction', name: 'ประมูล', code: 'MARKET.AUCTION', desc: 'เสนอราคาแข่งกันแบบสด หรือตั้งซื้อทันที' },
  { key: 'trade', icon: 'trade', name: 'เทรด', code: 'MARKET.TRADE', desc: 'ระบบแลกเปลี่ยนไอเท็มของต่อของผ่านกล่องพัสดุ' }
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
        el('span', { class: 'sys-tag', text: '// SYSTEM DECK' }),
        el('span', { class: 'sys-code', text: 'NODE: 0.0.0.0:8085' })
      ),
      el('h1', { text: state.me ? 'ยินดีต้อนรับ, ' + state.me : 'CLAIM MAP // INDUSTRIAL MARKET' }),
      el('p', { class: 'hint', text: 'ระบบศูนย์กลางเศรษฐกิจ การค้าเสรี แลกเปลี่ยนไอเท็ม และการจัดการกรรมสิทธิ์ที่ดิน' })
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
        el('span', { class: 'section-prefix', text: '//' }),
        el('h2', { text: 'ระบบตลาดและการค้า' }),
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
        el('span', { class: 'section-prefix', text: '//' }),
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
    el('div', { class: 'terminal-card' },
      el('div', { class: 'terminal-header' },
        el('span', { class: 'terminal-title', text: 'IN-GAME CONSOLE COMMANDS // คำสั่งในเกม' }),
        el('span', { class: 'terminal-badge', text: 'MINECRAFT 1.21' })
      ),
      el('div', { class: 'terminal-body' },
        el('div', { class: 'cmd-row' }, el('code', { text: '/bal' }), el('span', { text: 'ตรวจสอบยอดเงินคงเหลือในบัญชี' })),
        el('div', { class: 'cmd-row' }, el('code', { text: '/pay <ผู้เล่น> <จำนวน>' }), el('span', { text: 'โอนเงินให้ผู้เล่นคนอื่นในระบบ' })),
        el('div', { class: 'cmd-row' }, el('code', { text: '/sell hand | /sell all' }), el('span', { text: 'ขายไอเท็มในมือหรือในกระเป๋าให้ร้านค้า' })),
        el('div', { class: 'cmd-row' }, el('code', { text: '/buy <item> <จำนวน>' }), el('span', { text: 'ซื้อไอเท็มจากร้านค้า (ส่งเข้ากล่องของ)' })),
        el('div', { class: 'cmd-row' }, el('code', { text: '/mail | /mail claim' }), el('span', { text: 'ตรวจสอบและรับไอเท็มจากกล่องเข้าตัว' })),
        el('div', { class: 'cmd-row' }, el('code', { text: '/ah | /ah sell' }), el('span', { text: 'เปิดดูหรือลงขายไอเท็มในตลาดประมูล' })),
        el('div', { class: 'cmd-row' }, el('code', { text: '/claim | /claimmap here' }), el('span', { text: 'จองพื้นที่ chunk 500x500 หรือตรวจสอบที่ดินปัจจุบัน' }))
      )
    )
  );
}

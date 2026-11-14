import { el, state, navigate } from '../core.js';

const APPS = [
  { key: 'map', icon: '🗺', name: 'แผนที่' },
  { key: 'sell', icon: '🏷', name: 'ลงขาย' },
  { key: 'buy', icon: '🛒', name: 'ซื้อ' },
  { key: 'auction', icon: '🔨', name: 'ประมูล' },
  { key: 'trade', icon: '🤝', name: 'เทรด' },
  { key: 'wallet', icon: '👛', name: 'กระเป๋าเงิน' },
  { key: 'mailbox', icon: '📦', name: 'กล่องของ' },
  { key: 'prices', icon: '🏷', name: 'ราคา' }
];

export function render(container) {
  container.append(el('div', { class: 'hero' },
    el('div', { class: 'hero-kicker', text: 'CLAIM MAP MARKET' }),
    el('h1', { text: 'ตลาดของทุกคน' }),
    el('p', { class: 'hint', text: state.me ? 'สวัสดี ' + state.me + (state.admin ? ' (แอดมิน)' : '') : 'ซื้อ ขาย เทรด และดูแลพื้นที่ของคุณ' })));

  const tiles = APPS.map(app => el('button', {
    class: 'app-tile',
    type: 'button',
    onClick: () => navigate(app.key)
  }, el('span', { class: 'ico', text: app.icon }), el('span', { class: 'name', text: app.name })));

  if (state.admin) {
    tiles.push(el('button', {
      class: 'app-tile',
      type: 'button',
      onClick: () => navigate('admin')
    }, el('span', { class: 'ico', text: '⚙' }), el('span', { class: 'name', text: 'แอดมิน' })));
  }

  container.append(el('div', { class: 'home-grid' }, tiles));
  container.append(el('p', { class: 'hint', text: 'ลงขายได้จากหน้า “ลงขาย” โดยดึงของจากกระเป๋าและกล่องในพื้นที่ของคุณ · คำสั่งหลัก: /bal /pay /sell /buy /mail /ah' }));
}

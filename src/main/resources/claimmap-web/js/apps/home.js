import { el, state, navigate } from '../core.js';

const APPS = [
  { key: 'map', icon: '🗺', name: 'แผนที่' },
  { key: 'sell', icon: '🏪', name: 'ขาย' },
  { key: 'buy', icon: '🛒', name: 'ซื้อ' },
  { key: 'auction', icon: '🔨', name: 'ประมูล' },
  { key: 'wallet', icon: '👛', name: 'กระเป๋าเงิน' },
  { key: 'mailbox', icon: '📦', name: 'กล่องของ' },
  { key: 'prices', icon: '🏷', name: 'ราคา' }
];

export function render(container) {
  container.append(el('h1', { text: 'Claim Map' }));
  if (state.me) {
    container.append(el('p', { class: 'hint', text: 'สวัสดี ' + state.me + (state.admin ? ' (แอดมิน)' : '') }));
  }

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
  container.append(el('p', { class: 'hint', text: 'ลงขายของด้วยคำสั่ง /ah sell ในเกม · คำสั่งหลัก: /bal /pay /sell /buy /mail /ah' }));
}

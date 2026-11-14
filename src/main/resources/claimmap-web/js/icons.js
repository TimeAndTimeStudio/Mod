// Zero-emoji SVG icon registry for the sharp orange-black theme.
// All icons are crisp, geometric line SVGs with square line caps.

const ICONS = {
  home: '<path d="M3 11L12 3l9 8v10H3V11zm6 10v-6h6v6h-6z"/>',
  map: '<path d="M3 6l6-3 6 3 6-3v15l-6 3-6-3-6 3V6zm6-3v15m6-12v15"/>',
  buy: '<path d="M1 3h4l2.5 12h13l2-8H6.5 M9 20h.01 M17 20h.01"/>',
  sell: '<path d="M2 2h8l12 12-8 8L2 10V2zm5 5h.01"/>',
  auction: '<path d="M14 5l5 5-2.5 2.5-5-5L14 5zm-3 7L4 19l2 2 7-7-2-2zm-2-9l5 5M2 22h8"/>',
  trade: '<path d="M4 7h14l-4-4 M20 17H6l4 4"/>',
  wallet: '<path d="M2 5h20v14H2V5zm0 5h20 M15 14h3"/>',
  mailbox: '<path d="M3 4h18v16H3V4zm0 0l9 7 9-7 M12 11v9"/>',
  prices: '<path d="M3 21h18 M4 17l6-6 4 4 6-8 M18 7h2v2"/>',
  admin: '<path d="M4 17l6-6-6-6 M12 19h8"/>',
  plus: '<path d="M12 4v16 M4 12h16"/>',
  refresh: '<path d="M21 12a9 9 0 0 1-15 6.7L3 16 M3 12a9 9 0 0 1 15-6.7L21 8 M3 11V16h5 M21 13V8h-5"/>',
  warn: '<path d="M12 2L2 21h20L12 2zm0 7v6 M12 17h.01"/>',
  check: '<path d="M4 12l5 5L20 6"/>',
  close: '<path d="M18 6L6 18 M6 6l12 12"/>',
  arrow: '<path d="M5 12h14 M13 5l7 7-7 7"/>',
  search: '<path d="M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm10 2l-4.35-4.35"/>',
  logout: '<path d="M9 21H3V3h6 M16 17l5-5-5-5 M21 12H9"/>',
  user: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2 M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"/>',
  coin: '<path d="M12 2l8 4.5v11L12 22l-8-4.5v-11L12 2z"/>',
  box: '<path d="M21 16V8l-9-5-9 5v8l9 5 9-5z M3.5 8L12 13l8.5-5 M12 13v9"/>'
};

export function icon(name, size = 18, className = '') {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', String(size));
  svg.setAttribute('height', String(size));
  svg.setAttribute('fill', 'none');
  svg.setAttribute('stroke', 'currentColor');
  svg.setAttribute('stroke-width', '2');
  svg.setAttribute('stroke-linecap', 'square');
  svg.setAttribute('stroke-linejoin', 'miter');
  svg.setAttribute('class', `ui-icon ui-icon-${name}${className ? ' ' + className : ''}`);
  svg.setAttribute('aria-hidden', 'true');
  const path = ICONS[name] || ICONS.box;
  svg.innerHTML = path;
  return svg;
}

export function hydrateIcons(root = document) {
  for (const node of root.querySelectorAll('[data-icon]')) {
    const name = node.getAttribute('data-icon');
    const size = parseInt(node.getAttribute('data-icon-size') || '18', 10);
    const cls = node.getAttribute('data-icon-class') || '';
    while (node.firstChild) node.removeChild(node.firstChild);
    node.append(icon(name, size, cls));
  }
}

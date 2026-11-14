import { api } from './core.js';

export async function fetchSources() {
  const data = await api('GET', '/api/sources');
  return data.items || [];
}

export function sourceLabel(item) {
  return String(item.label || item.itemId) + ' × ' + item.count + ' · ' + item.area;
}

export function sourceOptions(items, filterItemId, minCount) {
  return items.filter(item => {
    if (filterItemId && item.itemId !== filterItemId) return false;
    if (minCount && item.count < minCount) return false;
    return true;
  });
}

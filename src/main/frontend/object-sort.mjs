const nameCollator = new Intl.Collator(undefined, {
  numeric: true,
  sensitivity: 'base'
});

const compareNames = (left, right) => nameCollator.compare(left.name, right.name);

export function nextSortDirection(current, key) {
  return current.key === key && current.direction === 'asc' ? 'desc' : 'asc';
}

export function compareObjectItems(left, right, key, direction) {
  if (left.directory !== right.directory) return left.directory ? -1 : 1;

  if (left.directory) {
    const folderDirection = key === 'name' && direction === 'desc' ? -1 : 1;
    return compareNames(left, right) * folderDirection;
  }

  if (key === 'name') return compareNames(left, right) * (direction === 'asc' ? 1 : -1);

  const leftValue = left[key];
  const rightValue = right[key];
  const leftMissing = leftValue == null;
  const rightMissing = rightValue == null;
  if (leftMissing !== rightMissing) return leftMissing ? 1 : -1;

  const difference = leftMissing ? 0 : leftValue - rightValue;
  if (difference !== 0) return difference * (direction === 'asc' ? 1 : -1);
  return compareNames(left, right);
}

export function sortObjectItems(items, key, direction) {
  return [...items].sort((left, right) => compareObjectItems(left, right, key, direction));
}

export function normalizeObjectSearch(query) {
  return String(query ?? '').trim().toLocaleLowerCase();
}

export function matchesObjectSearch(item, query) {
  const normalizedQuery = normalizeObjectSearch(query);
  return !normalizedQuery || item.name.toLocaleLowerCase().includes(normalizedQuery);
}

export function filterObjectItems(items, query) {
  return items.filter(item => matchesObjectSearch(item, query));
}

const rowToItem = row => ({
  row,
  name: row.dataset.objectName ?? '',
  size: Number(row.dataset.objectSize),
  lastModified: row.dataset.objectModified ? Number(row.dataset.objectModified) : null,
  directory: row.dataset.objectDirectory === 'true'
});

function updateSortHeaders(root, key, direction) {
  root.querySelectorAll('[data-object-sort-header]').forEach(header => {
    const active = header.dataset.objectSortHeader === key;
    header.setAttribute('aria-sort', active ? `${direction}ending` : 'none');
    const indicator = header.querySelector('[data-object-sort-indicator]');
    if (indicator) indicator.textContent = active ? (direction === 'asc' ? '↑' : '↓') : '↕';
    const button = header.querySelector('button');
    if (button) {
      const label = header.dataset.objectSortLabel;
      const nextDirection = active && direction === 'asc' ? 'descending' : 'ascending';
      button.setAttribute('aria-label', `Sort by ${label} ${nextDirection}`);
    }
  });
}

const visibleObjectCheckboxes = root =>
  [...root.querySelectorAll('#objectTableBody tr:not([hidden]) .obj-check')];

export function syncObjectSelectAll(root = document) {
  const selectAll = root.getElementById('selectAll');
  if (!selectAll) return;
  const visible = visibleObjectCheckboxes(root);
  const selected = visible.filter(checkbox => checkbox.checked).length;
  selectAll.checked = visible.length > 0 && selected === visible.length;
  selectAll.indeterminate = selected > 0 && selected < visible.length;
}

export function toggleVisibleObjectSelection(checked, root = document) {
  visibleObjectCheckboxes(root).forEach(checkbox => { checkbox.checked = checked; });
  syncObjectSelectAll(root);
}

export function createObjectTableFilter(root = document) {
  return query => {
    const body = root.getElementById('objectTableBody');
    if (!body) return;

    const normalizedQuery = normalizeObjectSearch(query);
    const items = [...body.rows].map(rowToItem);
    let visibleCount = 0;
    items.forEach(item => {
      const matches = matchesObjectSearch(item, normalizedQuery);
      item.row.hidden = !matches;
      if (matches) visibleCount++;
    });

    const status = root.getElementById('objectSearchStatus');
    if (status) {
      status.textContent = normalizedQuery
        ? `${visibleCount} of ${items.length} items`
        : `${items.length} items`;
    }
    const empty = root.getElementById('objectSearchEmpty');
    if (empty) empty.classList.toggle('hidden', !normalizedQuery || visibleCount > 0);
    syncObjectSelectAll(root);
  };
}

export function createObjectTableSorter(root = document) {
  const current = { key: null, direction: null };
  return key => {
    const body = root.getElementById('objectTableBody');
    if (!body) return;

    const direction = nextSortDirection(current, key);
    sortObjectItems([...body.rows].map(rowToItem), key, direction)
      .forEach(item => body.append(item.row));
    current.key = key;
    current.direction = direction;
    updateSortHeaders(root, key, direction);
  };
}

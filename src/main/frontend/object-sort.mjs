const nameCollator = new Intl.Collator(undefined, {
  numeric: true,
  sensitivity: 'base'
});

export const DEFAULT_OBJECT_PAGE_SIZE = 200;
export const OBJECT_PAGE_SIZES = [50, 100, 200, 500, 'all'];

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

export function normalizeObjectPageSize(value) {
  if (String(value).toLocaleLowerCase() === 'all') return 'all';
  const parsed = Number(value);
  return OBJECT_PAGE_SIZES.includes(parsed) ? parsed : DEFAULT_OBJECT_PAGE_SIZE;
}

export function paginateObjectItems(items, requestedPage, requestedPageSize) {
  const pageSize = normalizeObjectPageSize(requestedPageSize);
  const totalPages = pageSize === 'all' ? 1 : Math.max(1, Math.ceil(items.length / pageSize));
  const page = Math.min(Math.max(1, Number(requestedPage) || 1), totalPages);
  const offset = pageSize === 'all' ? 0 : (page - 1) * pageSize;
  const pageItems = pageSize === 'all' ? [...items] : items.slice(offset, offset + pageSize);

  return {
    items: pageItems,
    page,
    pageSize,
    totalPages,
    totalItems: items.length,
    start: pageItems.length ? offset + 1 : 0,
    end: offset + pageItems.length
  };
}

export function objectPaginationEntries(currentPage, totalPages) {
  if (totalPages <= 7) return Array.from({ length: totalPages }, (_, index) => index + 1);

  const pages = [...new Set([1, totalPages, currentPage - 1, currentPage, currentPage + 1]
    .filter(page => page >= 1 && page <= totalPages))].sort((left, right) => left - right);
  const entries = [];
  pages.forEach((page, index) => {
    const previous = pages[index - 1];
    if (previous != null && page - previous === 2) entries.push(previous + 1);
    else if (previous != null && page - previous > 2) entries.push('ellipsis');
    entries.push(page);
  });
  return entries;
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

function updatePaginationButtons(root, pageData, goToPage) {
  const previous = root.getElementById('objectPagePrevious');
  const next = root.getElementById('objectPageNext');
  if (previous) {
    previous.disabled = pageData.page <= 1 || pageData.pageSize === 'all';
    previous.onclick = () => goToPage(pageData.page - 1);
  }
  if (next) {
    next.disabled = pageData.page >= pageData.totalPages || pageData.pageSize === 'all';
    next.onclick = () => goToPage(pageData.page + 1);
  }

  const pages = root.getElementById('objectPageNumbers');
  if (!pages) return;
  pages.replaceChildren();
  objectPaginationEntries(pageData.page, pageData.totalPages).forEach(entry => {
    if (entry === 'ellipsis') {
      const ellipsis = root.createElement('span');
      ellipsis.className = 'px-1 text-slate-500';
      ellipsis.textContent = '…';
      ellipsis.setAttribute('aria-hidden', 'true');
      pages.append(ellipsis);
      return;
    }

    const button = root.createElement('button');
    button.type = 'button';
    button.className = entry === pageData.page ? 'btn-primary min-w-9' : 'btn-secondary min-w-9';
    button.textContent = String(entry);
    button.setAttribute('aria-label', `Go to page ${entry}`);
    if (entry === pageData.page) button.setAttribute('aria-current', 'page');
    button.addEventListener('click', () => goToPage(entry));
    pages.append(button);
  });
}

export function createObjectTableController(root = document) {
  const body = root.getElementById('objectTableBody');
  let allItems = body ? [...body.rows].map(rowToItem) : [];
  let orderedItems = [...allItems];
  const state = {
    query: '',
    sortKey: null,
    sortDirection: null,
    page: 1,
    pageSize: DEFAULT_OBJECT_PAGE_SIZE
  };

  const render = () => {
    if (!body) return;
    const query = normalizeObjectSearch(state.query);
    const matchingItems = filterObjectItems(orderedItems, query);
    const pageData = paginateObjectItems(matchingItems, state.page, state.pageSize);
    state.page = pageData.page;
    state.pageSize = pageData.pageSize;

    const visibleRows = new Set(pageData.items.map(item => item.row));
    allItems.forEach(item => { item.row.hidden = !visibleRows.has(item.row); });

    const searchStatus = root.getElementById('objectSearchStatus');
    if (searchStatus) {
      searchStatus.textContent = query
        ? `${matchingItems.length} of ${allItems.length} items`
        : `${allItems.length} items`;
    }
    const empty = root.getElementById('objectSearchEmpty');
    if (empty) empty.classList.toggle('hidden', !query || matchingItems.length > 0);

    const range = root.getElementById('objectPageRange');
    if (range) {
      range.textContent = matchingItems.length
        ? `Showing ${pageData.start}–${pageData.end} of ${matchingItems.length}${query ? ' matching' : ''} items`
        : `Showing 0 of 0${query ? ' matching' : ''} items`;
    }
    const summary = root.getElementById('objectPageSummary');
    if (summary) summary.textContent = `Page ${pageData.page} of ${pageData.totalPages}`;
    const pageSize = root.getElementById('objectPageSize');
    if (pageSize) pageSize.value = String(pageData.pageSize);

    updatePaginationButtons(root, pageData, goToPage);
    syncObjectSelectAll(root);
  };

  const filter = query => {
    state.query = query;
    state.page = 1;
    render();
  };

  const sort = key => {
    const direction = nextSortDirection({ key: state.sortKey, direction: state.sortDirection }, key);
    orderedItems = sortObjectItems(orderedItems, key, direction);
    orderedItems.forEach(item => body?.append(item.row));
    state.sortKey = key;
    state.sortDirection = direction;
    state.page = 1;
    updateSortHeaders(root, key, direction);
    render();
  };

  function goToPage(page) {
    state.page = page;
    render();
  }

  const setPageSize = pageSize => {
    state.pageSize = normalizeObjectPageSize(pageSize);
    state.page = 1;
    render();
  };

  const syncRows = () => {
    allItems = body ? [...body.rows].map(rowToItem) : [];
    orderedItems = state.sortKey
      ? sortObjectItems(allItems, state.sortKey, state.sortDirection)
      : [...allItems];
    orderedItems.forEach(item => body?.append(item.row));
    state.page = 1;
    render();
  };

  return { filter, sort, goToPage, setPageSize, syncRows, render };
}

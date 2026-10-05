import assert from 'node:assert/strict';
import test from 'node:test';
import {
  DEFAULT_OBJECT_PAGE_SIZE,
  OBJECT_PAGE_SIZES,
  createObjectTableController,
  filterObjectItems,
  matchesObjectSearch,
  nextSortDirection,
  normalizeObjectSearch,
  normalizeObjectPageSize,
  objectPaginationEntries,
  paginateObjectItems,
  sortObjectItems,
  syncObjectSelectAll,
  toggleVisibleObjectSelection
} from '../../main/frontend/object-sort.mjs';

const item = (name, { size = 0, lastModified = null, directory = false } = {}) => ({
  name,
  size,
  lastModified,
  directory
});
const names = items => items.map(value => value.name);

const fakeElement = () => ({
  children: [],
  classList: { toggle() {} },
  setAttribute() {},
  addEventListener() {},
  append(child) { this.children.push(child); },
  replaceChildren() { this.children = []; }
});

function objectTableRoot(count) {
  const rows = Array.from({ length: count }, (_, index) => ({
    dataset: {
      objectName: `file-${index + 1}`,
      objectSize: String(index + 1),
      objectModified: String(index + 1),
      objectDirectory: 'false'
    },
    hidden: false,
    checkbox: { checked: false }
  }));
  const body = {
    rows,
    append(row) {
      const index = rows.indexOf(row);
      if (index >= 0) rows.splice(index, 1);
      rows.push(row);
    }
  };
  const elements = {
    objectTableBody: body,
    selectAll: { checked: false, indeterminate: false },
    objectSearchStatus: fakeElement(),
    objectSearchEmpty: fakeElement(),
    objectPageRange: fakeElement(),
    objectPageSummary: fakeElement(),
    objectPageSize: fakeElement(),
    objectPagePrevious: fakeElement(),
    objectPageNext: fakeElement(),
    objectPageNumbers: fakeElement()
  };
  const root = {
    rows,
    elements,
    getElementById: id => elements[id] ?? null,
    createElement: fakeElement,
    querySelectorAll: selector => {
      if (selector === '[data-object-sort-header]') return [];
      if (selector === '#objectTableBody tr:not([hidden]) .obj-check') {
        return rows.filter(row => !row.hidden).map(row => row.checkbox);
      }
      throw new Error(`Unexpected selector: ${selector}`);
    }
  };
  return root;
}

test('sort direction starts ascending, toggles, and resets for another column', () => {
  assert.equal(nextSortDirection({ key: null, direction: null }, 'name'), 'asc');
  assert.equal(nextSortDirection({ key: 'name', direction: 'asc' }, 'name'), 'desc');
  assert.equal(nextSortDirection({ key: 'name', direction: 'desc' }, 'name'), 'asc');
  assert.equal(nextSortDirection({ key: 'size', direction: 'desc' }, 'name'), 'asc');
});

test('name sorting is case-insensitive, natural, and keeps folders first', () => {
  const items = [
    item('file10.txt'),
    item('Folder10', { directory: true }),
    item('file2.txt'),
    item('folder2', { directory: true })
  ];

  assert.deepEqual(names(sortObjectItems(items, 'name', 'asc')),
    ['folder2', 'Folder10', 'file2.txt', 'file10.txt']);
  assert.deepEqual(names(sortObjectItems(items, 'name', 'desc')),
    ['Folder10', 'folder2', 'file10.txt', 'file2.txt']);
});

test('size sorting is numeric while folders stay alphabetically above files', () => {
  const items = [
    item('ten.bin', { size: 10 }),
    item('z-folder', { directory: true }),
    item('two.bin', { size: 2 }),
    item('a-folder', { directory: true })
  ];

  assert.deepEqual(names(sortObjectItems(items, 'size', 'asc')),
    ['a-folder', 'z-folder', 'two.bin', 'ten.bin']);
  assert.deepEqual(names(sortObjectItems(items, 'size', 'desc')),
    ['a-folder', 'z-folder', 'ten.bin', 'two.bin']);
});

test('last-modified sorting is chronological and always puts missing dates last', () => {
  const items = [
    item('unknown.txt'),
    item('new.txt', { lastModified: 200 }),
    item('old-b.txt', { lastModified: 100 }),
    item('old-a.txt', { lastModified: 100 })
  ];

  assert.deepEqual(names(sortObjectItems(items, 'lastModified', 'asc')),
    ['old-a.txt', 'old-b.txt', 'new.txt', 'unknown.txt']);
  assert.deepEqual(names(sortObjectItems(items, 'lastModified', 'desc')),
    ['new.txt', 'old-a.txt', 'old-b.txt', 'unknown.txt']);
});

test('search trims the query and matches visible names by case-insensitive substring', () => {
  const items = [
    item('Annual Report.pdf'),
    item('reports', { directory: true }),
    item('notes.txt')
  ];

  assert.equal(normalizeObjectSearch('  REPORT  '), 'report');
  assert.equal(matchesObjectSearch(items[0], 'REPORT'), true);
  assert.deepEqual(names(filterObjectItems(items, ' report ')),
    ['Annual Report.pdf', 'reports']);
  assert.deepEqual(names(filterObjectItems(items, 'missing')), []);
  assert.deepEqual(filterObjectItems(items, '   '), items);
});

test('filtering preserves the active sorted order across folders and files', () => {
  const items = [
    item('archive10', { directory: true }),
    item('archive2.txt', { size: 2 }),
    item('notes.txt', { size: 30 }),
    item('archive10.txt', { size: 10 })
  ];
  const sorted = sortObjectItems(items, 'size', 'desc');

  assert.deepEqual(names(filterObjectItems(sorted, 'archive')),
    ['archive10', 'archive10.txt', 'archive2.txt']);
});

test('pagination defaults to 200 and supports every configured size including all', () => {
  assert.equal(DEFAULT_OBJECT_PAGE_SIZE, 200);
  assert.deepEqual(OBJECT_PAGE_SIZES, [50, 100, 200, 500, 'all']);
  assert.equal(normalizeObjectPageSize('50'), 50);
  assert.equal(normalizeObjectPageSize('100'), 100);
  assert.equal(normalizeObjectPageSize('200'), 200);
  assert.equal(normalizeObjectPageSize('500'), 500);
  assert.equal(normalizeObjectPageSize('all'), 'all');
  assert.equal(normalizeObjectPageSize('invalid'), 200);
});

test('pagination returns the requested slice, range, and clamped page', () => {
  const items = Array.from({ length: 450 }, (_, index) => item(`file-${index + 1}`));
  const secondPage = paginateObjectItems(items, 2, 200);

  assert.equal(secondPage.page, 2);
  assert.equal(secondPage.totalPages, 3);
  assert.equal(secondPage.start, 201);
  assert.equal(secondPage.end, 400);
  assert.equal(secondPage.items[0].name, 'file-201');
  assert.equal(secondPage.items.at(-1).name, 'file-400');

  const clamped = paginateObjectItems(items, 99, 200);
  assert.equal(clamped.page, 3);
  assert.equal(clamped.start, 401);
  assert.equal(clamped.end, 450);
});

test('all page size returns every matching item on a single page', () => {
  const items = Array.from({ length: 650 }, (_, index) => item(`file-${index + 1}`));
  const page = paginateObjectItems(items, 4, 'all');

  assert.equal(page.page, 1);
  assert.equal(page.totalPages, 1);
  assert.equal(page.start, 1);
  assert.equal(page.end, 650);
  assert.equal(page.items.length, 650);
});

test('compact pagination keeps nearby and boundary pages', () => {
  assert.deepEqual(objectPaginationEntries(1, 10), [1, 2, 'ellipsis', 10]);
  assert.deepEqual(objectPaginationEntries(5, 10),
    [1, 'ellipsis', 4, 5, 6, 'ellipsis', 10]);
  assert.deepEqual(objectPaginationEntries(10, 10), [1, 'ellipsis', 9, 10]);
  assert.deepEqual(objectPaginationEntries(3, 5), [1, 2, 3, 4, 5]);
});

test('sorting and filtering happen before the page slice', () => {
  const items = [
    item('report10.txt', { size: 10 }),
    item('notes.txt', { size: 100 }),
    item('report2.txt', { size: 2 }),
    item('reports', { directory: true }),
    item('report30.txt', { size: 30 })
  ];
  const sorted = sortObjectItems(items, 'size', 'desc');
  const matching = filterObjectItems(sorted, 'report');
  const page = paginateObjectItems(matching, 1, 50);

  assert.deepEqual(names(page.items), ['reports', 'report30.txt', 'report10.txt', 'report2.txt']);
});

test('table controller resets pages for filtering and preserves selections outside the page', () => {
  const root = objectTableRoot(205);
  const controller = createObjectTableController(root);
  controller.render();

  assert.equal(root.rows.filter(row => !row.hidden).length, 200);
  root.rows[0].checkbox.checked = true;

  controller.goToPage(2);
  assert.equal(root.rows[0].hidden, true);
  assert.equal(root.rows[0].checkbox.checked, true);
  assert.equal(root.rows.filter(row => !row.hidden).length, 5);
  assert.equal(root.elements.objectPageSummary.textContent, 'Page 2 of 2');

  controller.filter('file-205');
  assert.equal(root.rows.filter(row => !row.hidden).length, 1);
  assert.equal(root.rows.find(row => !row.hidden).dataset.objectName, 'file-205');
  assert.equal(root.elements.objectPageSummary.textContent, 'Page 1 of 1');

  controller.filter('');
  controller.setPageSize('all');
  assert.equal(root.rows.filter(row => !row.hidden).length, 205);
  assert.equal(root.rows[0].checkbox.checked, true);
  assert.equal(root.elements.objectPagePrevious.disabled, true);
  assert.equal(root.elements.objectPageNext.disabled, true);
});

test('select-all changes visible files only and reports partial visible selection', () => {
  const visible = [{ checked: false }, { checked: false }];
  const hidden = { checked: false };
  const selectAll = { checked: false, indeterminate: false };
  const root = {
    getElementById: id => id === 'selectAll' ? selectAll : null,
    querySelectorAll: selector => {
      assert.equal(selector, '#objectTableBody tr:not([hidden]) .obj-check');
      return visible;
    }
  };

  toggleVisibleObjectSelection(true, root);
  assert.deepEqual(visible.map(checkbox => checkbox.checked), [true, true]);
  assert.equal(hidden.checked, false);
  assert.equal(selectAll.checked, true);
  assert.equal(selectAll.indeterminate, false);

  visible[0].checked = false;
  syncObjectSelectAll(root);
  assert.equal(selectAll.checked, false);
  assert.equal(selectAll.indeterminate, true);
});

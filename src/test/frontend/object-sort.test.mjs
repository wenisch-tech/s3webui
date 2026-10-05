import assert from 'node:assert/strict';
import test from 'node:test';
import {
  filterObjectItems,
  matchesObjectSearch,
  nextSortDirection,
  normalizeObjectSearch,
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

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  nextSortDirection,
  sortObjectItems
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

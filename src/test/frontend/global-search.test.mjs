import test from 'node:test';
import assert from 'node:assert/strict';
import { globalSearchPagePath, globalSearchResultPath } from '../../main/frontend/global-search.mjs';

test('file search links encode bucket, prefix and highlighted object key', () => {
  assert.equal(
    globalSearchResultPath({
      type: 'FILE',
      bucket: 'team files',
      key: 'reports/2026/q&a final.pdf',
      parentPrefix: 'reports/2026/'
    }),
    '/buckets/team%20files?prefix=reports%2F2026%2F&highlight=reports%2F2026%2Fq%26a+final.pdf'
  );
});

test('folder search links open the folder itself', () => {
  assert.equal(
    globalSearchResultPath({
      type: 'FOLDER',
      bucket: 'bucket-a',
      key: 'photos/summer trip/',
      parentPrefix: 'photos/'
    }),
    '/buckets/bucket-a?prefix=photos%2Fsummer+trip%2F'
  );
});

test('more-results links encode the complete query', () => {
  assert.equal(globalSearchPagePath('quarterly & final'), '/search?q=quarterly+%26+final');
});

import assert from 'node:assert/strict';
import test from 'node:test';
import {
  bucketSearchApiPath,
  formatObjectSize,
  normalizeBucketPrefix,
  relativeBucketResultParent,
  relativeBucketResultPath
} from '../../main/frontend/bucket-search.mjs';

test('bucket search URLs encode bucket, prefix, query and special characters', () => {
  assert.equal(
    bucketSearchApiPath('team files', 'reports/2026', 'final & signed'),
    '/api/buckets/team%20files/objects/search?q=final+%26+signed&prefix=reports%2F2026%2F'
  );
});

test('relative result paths and parents are scoped to the current folder', () => {
  assert.equal(normalizeBucketPrefix('reports/2026'), 'reports/2026/');
  assert.equal(
    relativeBucketResultPath('reports/2026/nested/final.pdf', 'reports/2026'),
    'nested/final.pdf'
  );
  assert.equal(
    relativeBucketResultParent('reports/2026/nested/deeper/', 'reports/2026/'),
    'nested/'
  );
});

test('object sizes use the same units as server-rendered rows', () => {
  assert.equal(formatObjectSize(12), '12 B');
  assert.equal(formatObjectSize(1536), '1.5 KB');
  assert.equal(formatObjectSize(2 * 1024 * 1024), '2.0 MB');
  assert.equal(formatObjectSize(null), '0 B');
});

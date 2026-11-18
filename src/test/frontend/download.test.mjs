import assert from 'node:assert/strict';
import test from 'node:test';
import { isDownloadPath, startDownload } from '../../main/frontend/download.mjs';

test('only object download and zip endpoints count as downloads', () => {
  assert.ok(isDownloadPath('/api/buckets/photos/objects/download'));
  assert.ok(isDownloadPath('/api/buckets/photos/objects/zip'));
  assert.ok(isDownloadPath('/s3/api/buckets/photos/objects/download'));
  assert.ok(!isDownloadPath('/api/buckets/photos/objects/search'));
  assert.ok(!isDownloadPath('/buckets/photos'));
});

test('a desktop bridge receives the absolute URL instead of a navigation', () => {
  const seen = [];
  const location = { href: 'http://127.0.0.1:5000/buckets/photos' };
  startDownload('/api/buckets/photos/objects/zip?key=a', { bridge: { download: url => seen.push(url) }, location });
  assert.deepEqual(seen, ['http://127.0.0.1:5000/api/buckets/photos/objects/zip?key=a']);
  assert.equal(location.href, 'http://127.0.0.1:5000/buckets/photos');
});

test('without a bridge the browser navigates as before', () => {
  const location = { href: 'http://localhost:8080/buckets/photos' };
  startDownload('/api/buckets/photos/objects/zip?key=a', { bridge: null, location });
  assert.equal(location.href, '/api/buckets/photos/objects/zip?key=a');
});

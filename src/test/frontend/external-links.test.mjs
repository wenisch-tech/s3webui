import assert from 'node:assert/strict';
import test from 'node:test';
import { isExternalLink, interceptExternalClick } from '../../main/frontend/external-links.mjs';

const location = { href: 'http://127.0.0.1:5000/buckets', origin: 'http://127.0.0.1:5000' };

test('links to other sites and mail addresses are external', () => {
  assert.ok(isExternalLink('https://s3webui.wenisch.tech', location));
  assert.ok(isExternalLink('http://example.com/a', location));
  assert.ok(isExternalLink('mailto:me@example.com', location));
});

test('links inside the app and non-web links stay put', () => {
  assert.ok(!isExternalLink('/buckets/photos', location));
  assert.ok(!isExternalLink('http://127.0.0.1:5000/login', location));
  assert.ok(!isExternalLink('#top', location));
  assert.ok(!isExternalLink('javascript:void(0)', location));
});

test('without a desktop bridge clicks are left alone', () => {
  assert.equal(interceptExternalClick({ defaultPrevented: false, button: 0, target: {} }, null), false);
});

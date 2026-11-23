import assert from 'node:assert/strict';
import test from 'node:test';
import {
  breadcrumbs, buildSubmitRequest, countsLine, createJobPoller, describeSelection, etaSeconds,
  formatDuration, isActiveStatus, normalizeFolder, parentFolder, progressPercent, statusLabel,
  transferRate, visibleJobs, widgetSummary,
} from '../../main/frontend/migration-model.mjs';

const job = (overrides = {}) => ({
  id: 'j1', status: 'RUNNING', totalObjects: 100, totalBytes: 1000, copiedObjects: 20, copiedBytes: 200,
  skippedObjects: 5, failedObjects: 0, startedAt: '2026-01-01T10:00:00Z', finishedAt: null, ...overrides,
});
const at = (iso) => Date.parse(iso);

test('folders are normalised to the form the API expects', () => {
  assert.equal(normalizeFolder(''), '');
  assert.equal(normalizeFolder('  /a/b '), 'a/b/');
  assert.equal(normalizeFolder('a/'), 'a/');
  assert.equal(normalizeFolder(null), '');
  assert.equal(parentFolder('a/b/c/'), 'a/b/');
  assert.equal(parentFolder('a/'), '');
  assert.equal(parentFolder(''), '');
});

test('breadcrumbs lead from the root to the current folder', () => {
  assert.deepEqual(breadcrumbs('a/b/', 'photos'), [
    { label: 'photos', prefix: '' }, { label: 'a', prefix: 'a/' }, { label: 'b', prefix: 'a/b/' },
  ]);
  assert.deepEqual(breadcrumbs('', 'photos'), [{ label: 'photos', prefix: '' }]);
});

test('the submit request carries normalised paths and defaults', () => {
  assert.deepEqual(buildSubmitRequest({
    bucket: 'src', prefix: 'docs', keys: ['docs/a.txt'], targetCredentialId: '7', targetBucket: ' dst ',
    targetPrefix: '/inbox', conflictPolicy: '', deleteSource: 1,
  }), {
    sourceBucket: 'src', sourcePrefix: 'docs/', keys: ['docs/a.txt'], targetCredentialId: '7',
    targetBucket: 'dst', targetPrefix: 'inbox/', conflictPolicy: 'SKIP', deleteSource: true,
  });
});

test('the selection is described for the confirmation line', () => {
  assert.equal(describeSelection('src', '', 0), 'The whole bucket src');
  assert.equal(describeSelection('src', 'a', 0), 'Everything in src/a/');
  assert.equal(describeSelection('src', 'a/', 1), '1 selected item from src/a/');
  assert.equal(describeSelection('src', '', 3), '3 selected items from src/');
});

test('progress is unknown while scanning and complete once finished', () => {
  assert.equal(progressPercent(job({ status: 'SCANNING' })), null);
  assert.equal(progressPercent(job({ status: 'QUEUED' })), null);
  assert.equal(progressPercent(job()), 25);
  assert.equal(progressPercent(job({ status: 'COMPLETED', copiedObjects: 1 })), 100);
  assert.equal(progressPercent(job({ status: 'RUNNING', totalObjects: 0 })), null);
  assert.equal(progressPercent(job({ status: 'CANCELLED', totalObjects: 0 })), 100);
  assert.equal(progressPercent(job({ copiedObjects: 500 })), 100);
});

test('rate and ETA come from the time since the job started', () => {
  const now = at('2026-01-01T10:00:50Z');
  assert.equal(transferRate(job(), now), 4);
  assert.equal(etaSeconds(job(), now), 150); // 25 of 100 objects in 50s -> 75 left at 0.5/s
  assert.equal(transferRate(job(), at('2026-01-01T10:00:01Z')), null);
  assert.equal(etaSeconds(job({ status: 'SCANNING' }), now), null);
  assert.equal(etaSeconds(job({ copiedObjects: 0, skippedObjects: 0 }), now), null);
  assert.equal(transferRate(job({ finishedAt: '2026-01-01T10:01:40Z', status: 'COMPLETED' }), now), 2);
});

test('durations read naturally', () => {
  assert.equal(formatDuration(5), '5s');
  assert.equal(formatDuration(65), '1m 05s');
  assert.equal(formatDuration(3725), '1h 02m');
  assert.equal(formatDuration(-3), '0s');
});

test('counts leave out the zero parts', () => {
  assert.equal(countsLine(job({ skippedObjects: 0 })), '20 copied');
  assert.equal(countsLine(job({ failedObjects: 2 })), '20 copied · 5 skipped · 2 failed');
});

test('statuses have labels and an active flag', () => {
  assert.equal(statusLabel('COMPLETED_WITH_ERRORS'), 'Completed with errors');
  assert.ok(isActiveStatus('CANCELLING'));
  assert.ok(!isActiveStatus('COMPLETED'));
});

test('the collapsed widget summarises what is going on', () => {
  assert.equal(widgetSummary([job()]), 'Migration 25% (25/100)');
  assert.equal(widgetSummary([job({ status: 'SCANNING' })]), 'Migration Scanning');
  assert.equal(widgetSummary([job(), job({ id: 'j2' })]), '2 migrations running');
  assert.equal(widgetSummary([job({ status: 'COMPLETED' })]), 'Migration finished');
  assert.equal(widgetSummary([job({ status: 'FAILED' })]), 'Migration finished with errors');
});

test('finished jobs fade out of the widget after a while', () => {
  const now = at('2026-01-02T00:00:00Z');
  const recent = job({ id: 'r', status: 'COMPLETED', finishedAt: '2026-01-01T22:00:00Z' });
  const old = job({ id: 'o', status: 'COMPLETED', finishedAt: '2025-12-30T22:00:00Z' });
  assert.deepEqual(visibleJobs([job(), recent, old], now).map((j) => j.id), ['j1', 'r']);
});

test('the poller keeps polling while jobs are active and stops when they are done', async () => {
  const timers = [];
  const answers = [[job()], [job({ copiedObjects: 90 })], [job({ status: 'COMPLETED' })]];
  const seen = [];
  const finished = [];
  const poller = createJobPoller({
    fetchJobs: async () => answers.shift(),
    onUpdate: (jobs) => seen.push(jobs[0].status),
    onFinished: (j) => finished.push(j.id),
    setTimer: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimer: () => {},
  });

  await poller.refresh();
  assert.equal(timers.length, 1);
  assert.equal(timers[0].ms, 2000);
  await timers[0].fn();
  await timers[1].fn();
  assert.deepEqual(seen, ['RUNNING', 'RUNNING', 'COMPLETED']);
  assert.deepEqual(finished, ['j1']);
  assert.equal(timers.length, 2, 'no further poll is scheduled once nothing is active');
});

test('the poller does not announce jobs that were already finished when first seen', async () => {
  const finished = [];
  const poller = createJobPoller({
    fetchJobs: async () => [job({ status: 'COMPLETED' })],
    onUpdate: () => {},
    onFinished: (j) => finished.push(j.id),
    setTimer: () => 1,
    clearTimer: () => {},
  });
  await poller.refresh();
  await poller.refresh();
  assert.deepEqual(finished, []);
});

test('the poller retries later after a failed request and can be stopped', async () => {
  const timers = [];
  let calls = 0;
  const poller = createJobPoller({
    fetchJobs: async () => { calls += 1; throw new Error('offline'); },
    onUpdate: () => {},
    setTimer: (fn, ms) => { timers.push({ fn, ms }); return timers.length; },
    clearTimer: () => {},
  });
  await poller.refresh();
  assert.equal(timers[0].ms, 5000);
  poller.stop();
  await timers[0].fn();
  assert.equal(calls, 2);
  assert.equal(timers.length, 1, 'a stopped poller schedules nothing more');
});

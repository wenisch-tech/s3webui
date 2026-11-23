// Pure logic behind the migration dialog and the progress widget. Nothing here touches the DOM or
// the network directly, so it can be unit tested with injected fetch/timer functions.
import { formatObjectSize } from './bucket-search.mjs';

export const ACTIVE_STATUSES = ['QUEUED', 'SCANNING', 'RUNNING', 'CANCELLING'];

const STATUS_LABELS = {
  QUEUED: 'Queued',
  SCANNING: 'Scanning',
  RUNNING: 'Copying',
  CANCELLING: 'Cancelling',
  COMPLETED: 'Completed',
  COMPLETED_WITH_ERRORS: 'Completed with errors',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

export const CONFLICT_POLICIES = [
  { value: 'SKIP', label: 'Skip existing files' },
  { value: 'OVERWRITE', label: 'Overwrite existing files' },
  { value: 'NEWER_ONLY', label: 'Overwrite only if source is newer' },
];

export const isActiveStatus = (status) => ACTIVE_STATUSES.includes(status);

export const statusLabel = (status) => STATUS_LABELS[status] ?? String(status ?? '');

/** Folder path as the API expects it: no leading slash, one trailing slash, empty for the root. */
export function normalizeFolder(path) {
  const trimmed = String(path ?? '').trim().replace(/^\/+/, '');
  return trimmed && !trimmed.endsWith('/') ? `${trimmed}/` : trimmed;
}

/** The folder one level up, or '' at the root. */
export function parentFolder(prefix) {
  const folder = normalizeFolder(prefix).replace(/\/$/, '');
  const cut = folder.lastIndexOf('/');
  return cut < 0 ? '' : folder.slice(0, cut + 1);
}

/** Clickable path segments for a folder: [{label, prefix}], root first. */
export function breadcrumbs(prefix, rootLabel) {
  const crumbs = [{ label: rootLabel, prefix: '' }];
  let acc = '';
  for (const part of normalizeFolder(prefix).split('/').filter(Boolean)) {
    acc += `${part}/`;
    crumbs.push({ label: part, prefix: acc });
  }
  return crumbs;
}

/** The last path segment of a key or folder, for display. */
export function baseName(key) {
  const parts = String(key ?? '').split('/').filter(Boolean);
  return parts.length ? parts[parts.length - 1] : '';
}

/** Body for POST /api/migrations. */
export function buildSubmitRequest({ bucket, prefix, keys, targetCredentialId, targetBucket, targetPrefix, conflictPolicy, deleteSource }) {
  return {
    sourceBucket: bucket,
    sourcePrefix: normalizeFolder(prefix),
    keys: [...(keys ?? [])],
    targetCredentialId,
    targetBucket: String(targetBucket ?? '').trim(),
    targetPrefix: normalizeFolder(targetPrefix),
    conflictPolicy: conflictPolicy || 'SKIP',
    deleteSource: Boolean(deleteSource),
  };
}

/** One-line description of what is being migrated. */
export function describeSelection(bucket, prefix, selectedCount) {
  if (selectedCount > 0) return `${selectedCount} selected ${selectedCount === 1 ? 'item' : 'items'} from ${bucket}/${normalizeFolder(prefix)}`;
  const folder = normalizeFolder(prefix);
  return folder ? `Everything in ${bucket}/${folder}` : `The whole bucket ${bucket}`;
}

/** Objects handled so far: copied, skipped (already there) and failed. */
export function processedObjects(job) {
  return (job.copiedObjects ?? 0) + (job.skippedObjects ?? 0) + (job.failedObjects ?? 0);
}

/** 0-100, or null while the total is still being counted. */
export function progressPercent(job) {
  if (job.status === 'COMPLETED') return 100;
  if (job.status === 'SCANNING' || job.status === 'QUEUED') return null;
  const total = job.totalObjects ?? 0;
  if (total <= 0) return isActiveStatus(job.status) ? null : 100;
  return Math.min(100, Math.floor((processedObjects(job) / total) * 100));
}

function elapsedSeconds(job, nowMillis) {
  if (!job.startedAt) return 0;
  const end = job.finishedAt ? Date.parse(job.finishedAt) : nowMillis;
  return Math.max(0, (end - Date.parse(job.startedAt)) / 1000);
}

/** Average copy speed in bytes per second, or null when it is too early to tell. */
export function transferRate(job, nowMillis) {
  const seconds = elapsedSeconds(job, nowMillis);
  return seconds < 2 || !(job.copiedBytes > 0) ? null : job.copiedBytes / seconds;
}

/** Estimated seconds left, from the object throughput so far; null when unknown. */
export function etaSeconds(job, nowMillis) {
  if (job.status !== 'RUNNING') return null;
  const seconds = elapsedSeconds(job, nowMillis);
  const done = processedObjects(job);
  const remaining = (job.totalObjects ?? 0) - done;
  if (seconds < 2 || done <= 0 || remaining <= 0) return null;
  return Math.ceil(remaining / (done / seconds));
}

export function formatDuration(totalSeconds) {
  const seconds = Math.max(0, Math.round(Number(totalSeconds) || 0));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ${String(seconds % 60).padStart(2, '0')}s`;
  const hours = Math.floor(minutes / 60);
  return `${hours}h ${String(minutes % 60).padStart(2, '0')}m`;
}

export const formatBytes = (bytes) => formatObjectSize(bytes) || '0 B';

/** "Copied 120 · skipped 3 · failed 1" without the zero parts. */
export function countsLine(job) {
  const parts = [`${job.copiedObjects ?? 0} copied`];
  if (job.skippedObjects) parts.push(`${job.skippedObjects} skipped`);
  if (job.failedObjects) parts.push(`${job.failedObjects} failed`);
  return parts.join(' · ');
}

/** Short text for the collapsed widget. */
export function widgetSummary(jobs) {
  const active = jobs.filter((job) => isActiveStatus(job.status));
  if (active.length === 0) {
    const failed = jobs.some((job) => job.status === 'FAILED' || job.status === 'COMPLETED_WITH_ERRORS');
    return failed ? 'Migration finished with errors' : 'Migration finished';
  }
  const first = active[0];
  const percent = progressPercent(first);
  const detail = percent === null ? statusLabel(first.status) : `${percent}% (${processedObjects(first)}/${first.totalObjects})`;
  return active.length > 1 ? `${active.length} migrations running` : `Migration ${detail}`;
}

/** Finished jobs stay visible for a while so the result can be read; active ones always show. */
export function visibleJobs(jobs, nowMillis, keepFinishedMillis = 6 * 60 * 60 * 1000) {
  return jobs.filter((job) => isActiveStatus(job.status)
    || (job.finishedAt && nowMillis - Date.parse(job.finishedAt) < keepFinishedMillis));
}

/**
 * Keeps the job list fresh while something is running and goes quiet otherwise. Fetching and timers
 * are injected so the schedule can be tested without a browser.
 */
export function createJobPoller({ fetchJobs, onUpdate, onFinished = () => {}, setTimer = setTimeout, clearTimer = clearTimeout, activeIntervalMs = 2000, errorIntervalMs = 5000 }) {
  let timer = null;
  let stopped = false;
  const lastStatus = new Map();

  async function poll() {
    timer = null;
    let delay = null;
    try {
      const jobs = await fetchJobs();
      if (stopped) return;
      for (const job of jobs) {
        const before = lastStatus.get(job.id);
        if (before && isActiveStatus(before) && !isActiveStatus(job.status)) onFinished(job);
        lastStatus.set(job.id, job.status);
      }
      onUpdate(jobs);
      if (jobs.some((job) => isActiveStatus(job.status))) delay = activeIntervalMs;
    } catch {
      delay = errorIntervalMs;
    }
    if (delay !== null && !stopped) timer = setTimer(poll, delay);
  }

  return {
    /** Fetch now and keep going while jobs are active. Safe to call repeatedly. */
    refresh() {
      stopped = false;
      if (timer !== null) clearTimer(timer);
      timer = null;
      return poll();
    },
    stop() {
      stopped = true;
      if (timer !== null) clearTimer(timer);
      timer = null;
    },
  };
}

// Migration: a Total-Commander style dialog (source left, destination right) that starts a
// server-side copy between two S3 keys, and a widget in the bottom-right corner that follows the
// running jobs. The copy itself runs on the server, so closing the tab does not stop it.
import {
  breadcrumbs, buildSubmitRequest, countsLine, createJobPoller, describeSelection, etaSeconds,
  formatBytes, formatDuration, isActiveStatus, normalizeFolder, parentFolder, progressPercent,
  statusLabel, transferRate, visibleJobs, widgetSummary, baseName, CONFLICT_POLICIES,
} from './migration-model.mjs';

const NEW_BUCKET = '__new__';
const state = {
  bucket: '', base: '', browse: '', selected: new Set(), credentials: [], activeId: null,
  targetBucket: '', targetBrowse: '', busy: false,
};
const widget = { jobs: [], open: false, confirming: new Set(), openErrors: new Set() };

const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const refreshIcons = () => window.refreshIcons?.();

async function api(path, options = {}) {
  const response = await window.apiFetch(path, { headers: { Accept: 'application/json', ...(options.headers || {}) }, ...options });
  if (!response.ok) throw new Error(await window.getResponseErrorMessage(response, 'Request failed'));
  return response.status === 204 ? null : response.json();
}

// ── Dialog ───────────────────────────────────────────────────────────────

/** Opens the dialog for a bucket. `keys` preselects items (folders end with a slash) below `prefix`. */
window.openMigration = async (bucket, { prefix = '', keys = [] } = {}) => {
  if (!$('migrationModal')) return;
  Object.assign(state, {
    bucket, base: normalizeFolder(prefix), browse: normalizeFolder(prefix), selected: new Set(keys),
    targetBucket: '', targetBrowse: '', busy: false,
  });
  setError('');
  $('migrationStart').disabled = true;
  openDialog('migrationModal');
  try {
    const session = await api('/api/s3/session');
    state.credentials = session.credentials ?? [];
    state.activeId = session.activeCredentialId ?? null;
  } catch (error) {
    return setError(error.message);
  }
  const active = state.credentials.find((c) => c.id === state.activeId);
  $('migrationSourceKey').textContent = active ? active.name : 'Own credentials';
  if (!active) setError('Migration needs a stored S3 key as the source. Switch to a stored key first; keys typed in for this session only cannot be used.');

  const select = $('migrationTargetKey');
  select.innerHTML = state.credentials.map((c) => `<option value="${esc(c.id)}">${esc(c.name)}${c.id === state.activeId ? ' (current)' : ''}</option>`).join('');
  const other = state.credentials.find((c) => c.id !== state.activeId) ?? state.credentials[0];
  if (other) select.value = other.id;
  $('migrationConflict').innerHTML = CONFLICT_POLICIES.map((p) => `<option value="${p.value}">${esc(p.label)}</option>`).join('');
  $('migrationDeleteSource').checked = false;
  $('migrationDeleteHint').classList.add('hidden');

  await Promise.all([loadSource(), loadTargetBuckets()]);
  updateSummary();
};

async function loadSource() {
  const list = $('migrationSourceList');
  list.innerHTML = '<p class="migration-empty">Loading…</p>';
  renderSourceCrumbs();
  try {
    const items = await api(`/api/buckets/${encodeURIComponent(state.bucket)}/objects?prefix=${encodeURIComponent(state.browse)}`);
    renderSourceList(items);
  } catch (error) {
    list.innerHTML = `<p class="migration-empty text-bad-500">${esc(error.message)}</p>`;
  }
}

function renderSourceCrumbs() {
  const crumbs = breadcrumbs(state.browse, state.bucket).filter((c) => c.prefix.length >= state.base.length);
  if (state.base) crumbs[0] = { label: `${state.bucket}/${state.base}`, prefix: state.base };
  $('migrationSourceCrumbs').innerHTML = crumbs.map((c, i) => `${i ? '<span aria-hidden="true">/</span>' : ''}<button type="button" class="migration-crumb" data-src-prefix="${esc(c.prefix)}">${esc(c.label)}</button>`).join('');
}

function renderSourceList(items) {
  const rows = items.filter((item) => item.key !== state.browse).map((item) => {
    const folder = item.directory;
    const key = item.key;
    const label = esc(item.name || baseName(key));
    return `<div class="migration-row">
      <input type="checkbox" class="migration-check" value="${esc(key)}" ${state.selected.has(key) ? 'checked' : ''} aria-label="Select ${label}">
      ${folder
        ? `<button type="button" class="migration-name" data-src-prefix="${esc(key)}"><i data-icon="folder"></i><span>${label}</span></button>`
        : `<span class="migration-name"><i data-icon="document"></i><span>${label}</span></span>`}
      <span class="migration-size">${folder ? '' : esc(formatBytes(item.size))}</span>
    </div>`;
  });
  $('migrationSourceList').innerHTML = rows.length ? rows.join('') : '<p class="migration-empty">This folder is empty.</p>';
  syncSelectAll();
  refreshIcons();
}

function syncSelectAll() {
  const boxes = [...document.querySelectorAll('#migrationSourceList .migration-check')];
  const all = $('migrationSelectAll');
  all.checked = boxes.length > 0 && boxes.every((box) => box.checked);
  all.indeterminate = boxes.some((box) => box.checked) && !all.checked;
}

async function loadTargetBuckets() {
  const select = $('migrationTargetBucket');
  const credentialId = $('migrationTargetKey').value;
  select.innerHTML = '<option value="">Loading…</option>';
  if (!credentialId) return;
  try {
    const buckets = await api(`/api/migrations/targets/${encodeURIComponent(credentialId)}/buckets`);
    const sameName = buckets.includes(state.bucket);
    select.innerHTML = buckets.map((b) => `<option value="${esc(b)}">${esc(b)}</option>`).join('')
      + `<option value="${NEW_BUCKET}">＋ New bucket…</option>`;
    select.value = sameName ? state.bucket : NEW_BUCKET;
    $('migrationNewBucket').value = sameName ? '' : state.bucket;
  } catch (error) {
    select.innerHTML = `<option value="${NEW_BUCKET}">＋ New bucket…</option>`;
    select.value = NEW_BUCKET;
    $('migrationNewBucket').value = state.bucket;
    setError(`Could not list the buckets of that key: ${error.message}`);
  }
  onTargetBucketChanged();
}

function targetBucketName() {
  const value = $('migrationTargetBucket').value;
  return value === NEW_BUCKET ? $('migrationNewBucket').value.trim() : value;
}

function onTargetBucketChanged() {
  const isNew = $('migrationTargetBucket').value === NEW_BUCKET;
  $('migrationNewBucketRow').classList.toggle('hidden', !isNew);
  state.targetBrowse = '';
  $('migrationTargetPrefix').value = '';
  loadTargetFolders();
  updateSummary();
}

async function loadTargetFolders() {
  const list = $('migrationTargetFolders');
  const crumbs = $('migrationTargetCrumbs');
  const bucket = $('migrationTargetBucket').value;
  const credentialId = $('migrationTargetKey').value;
  if (!bucket || bucket === NEW_BUCKET) {
    crumbs.innerHTML = '';
    list.innerHTML = '<p class="migration-empty">The bucket will be created when the migration starts.</p>';
    return;
  }
  crumbs.innerHTML = breadcrumbs(state.targetBrowse, bucket).map((c, i) => `${i ? '<span aria-hidden="true">/</span>' : ''}<button type="button" class="migration-crumb" data-dst-prefix="${esc(c.prefix)}">${esc(c.label)}</button>`).join('');
  list.innerHTML = '<p class="migration-empty">Loading…</p>';
  try {
    const query = `?prefix=${encodeURIComponent(state.targetBrowse)}`;
    const folders = await api(`/api/migrations/targets/${encodeURIComponent(credentialId)}/buckets/${encodeURIComponent(bucket)}/folders${query}`);
    list.innerHTML = folders.length
      ? folders.map((f) => `<div class="migration-row"><button type="button" class="migration-name" data-dst-prefix="${esc(f)}"><i data-icon="folder"></i><span>${esc(baseName(f))}</span></button></div>`).join('')
      : '<p class="migration-empty">No subfolders.</p>';
    refreshIcons();
  } catch (error) {
    list.innerHTML = `<p class="migration-empty text-bad-500">${esc(error.message)}</p>`;
  }
}

function updateSummary() {
  const target = targetBucketName();
  const folder = normalizeFolder($('migrationTargetPrefix').value);
  const keyName = $('migrationTargetKey').selectedOptions[0]?.textContent ?? '';
  $('migrationSummary').textContent = `${describeSelection(state.bucket, state.base, state.selected.size)} → ${target ? `${keyName.replace(' (current)', '')} / ${target}/${folder}` : 'pick a target bucket'}`;
  $('migrationStart').disabled = state.busy || !state.activeId || !target || !$('migrationTargetKey').value;
}

function setError(message) {
  const box = $('migrationError');
  box.textContent = message;
  box.classList.toggle('hidden', !message);
}

async function startMigration() {
  setError('');
  state.busy = true;
  updateSummary();
  try {
    await api('/api/migrations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(buildSubmitRequest({
        bucket: state.bucket,
        prefix: state.base,
        keys: [...state.selected],
        targetCredentialId: $('migrationTargetKey').value,
        targetBucket: targetBucketName(),
        targetPrefix: $('migrationTargetPrefix').value,
        conflictPolicy: $('migrationConflict').value,
        deleteSource: $('migrationDeleteSource').checked,
      })),
    });
    closeDialog('migrationModal');
    window.showToast?.('Migration started. It keeps running on the server.', 'success');
    setWidgetOpen(true);
    poller.refresh();
  } catch (error) {
    setError(error.message);
  } finally {
    state.busy = false;
    updateSummary();
  }
}

// ── Dialog events ────────────────────────────────────────────────────────

document.addEventListener('click', (event) => {
  const src = event.target.closest('[data-src-prefix]');
  if (src && $('migrationModal')?.contains(src)) {
    state.browse = src.dataset.srcPrefix;
    if (state.browse.length < state.base.length) state.browse = state.base;
    loadSource();
    return;
  }
  const dst = event.target.closest('[data-dst-prefix]');
  if (dst && $('migrationModal')?.contains(dst)) {
    state.targetBrowse = dst.dataset.dstPrefix;
    $('migrationTargetPrefix').value = state.targetBrowse;
    loadTargetFolders();
    updateSummary();
  }
});

document.addEventListener('change', (event) => {
  const target = event.target;
  if (!$('migrationModal')?.contains(target)) return;
  if (target.matches('.migration-check')) {
    if (target.checked) state.selected.add(target.value); else state.selected.delete(target.value);
    syncSelectAll();
  } else if (target.id === 'migrationSelectAll') {
    document.querySelectorAll('#migrationSourceList .migration-check').forEach((box) => {
      box.checked = target.checked;
      if (target.checked) state.selected.add(box.value); else state.selected.delete(box.value);
    });
  } else if (target.id === 'migrationTargetKey') {
    setError('');
    loadTargetBuckets();
  } else if (target.id === 'migrationTargetBucket') {
    onTargetBucketChanged();
  } else if (target.id === 'migrationDeleteSource') {
    $('migrationDeleteHint').classList.toggle('hidden', !target.checked);
  }
  updateSummary();
});

document.addEventListener('input', (event) => {
  if (['migrationNewBucket', 'migrationTargetPrefix'].includes(event.target.id)) updateSummary();
});

document.addEventListener('DOMContentLoaded', () => {
  $('migrationStart')?.addEventListener('click', startMigration);
  $('migrationSourceUp')?.addEventListener('click', () => {
    state.browse = parentFolder(state.browse);
    if (state.browse.length < state.base.length) state.browse = state.base;
    loadSource();
  });
  $('migrationTargetUp')?.addEventListener('click', () => {
    state.targetBrowse = parentFolder(state.targetBrowse);
    $('migrationTargetPrefix').value = state.targetBrowse;
    loadTargetFolders();
    updateSummary();
  });
});

// ── Progress widget ──────────────────────────────────────────────────────

function setWidgetOpen(open) {
  widget.open = open;
  try { sessionStorage.setItem('s3webui-migration-open', open ? '1' : '0'); } catch { /* storage unavailable */ }
  renderWidget();
}

function jobCard(job) {
  const percent = progressPercent(job);
  const now = Date.now();
  const active = isActiveStatus(job.status);
  const rate = transferRate(job, now);
  const eta = etaSeconds(job, now);
  const tone = job.status === 'FAILED' || job.status === 'COMPLETED_WITH_ERRORS' ? 'bad' : job.status === 'COMPLETED' ? 'good' : 'brand';
  const target = `${job.targetBucket}/${job.targetPrefix || ''}`;
  const facts = [
    job.totalObjects ? `${job.copiedObjects + job.skippedObjects + job.failedObjects} of ${job.totalObjects} objects` : '',
    `${formatBytes(job.copiedBytes)}${job.totalBytes ? ` of ${formatBytes(job.totalBytes)}` : ''}`,
    rate ? `${formatBytes(rate)}/s` : '',
    eta ? `${formatDuration(eta)} left` : '',
  ].filter(Boolean).join(' · ');
  const errors = job.errors?.length
    ? `<details class="migration-errors" data-job="${esc(job.id)}" ${widget.openErrors.has(job.id) ? 'open' : ''}><summary>${job.failedObjects} failed</summary><ul>${job.errors.map((e) => `<li>${esc(e)}</li>`).join('')}</ul></details>`
    : '';
  const confirming = widget.confirming.has(job.id);
  return `<article class="migration-job" data-job="${esc(job.id)}">
    <header>
      <div class="min-w-0">
        <strong class="block truncate">${esc(job.sourceBucket)} → ${esc(target)}</strong>
        <small class="block truncate">${esc(job.sourceCredentialName)} → ${esc(job.targetCredentialName)}${job.deleteSource ? ' · moves (deletes source)' : ''}</small>
      </div>
      <span class="badge migration-status-${tone}">${esc(statusLabel(job.status))}</span>
    </header>
    <div class="progress" role="progressbar" aria-valuemin="0" aria-valuemax="100" ${percent === null ? '' : `aria-valuenow="${percent}"`}>
      <div class="progress-bar ${percent === null ? 'migration-indeterminate' : ''}" style="width:${percent === null ? 100 : percent}%"></div>
    </div>
    <p class="migration-facts">${esc(facts)}</p>
    ${job.skippedObjects || job.failedObjects ? `<p class="migration-facts">${esc(countsLine(job))}</p>` : ''}
    ${active && job.currentKey ? `<p class="migration-current" title="${esc(job.currentKey)}">${esc(job.currentKey)}</p>` : ''}
    ${job.lastError ? `<p class="migration-failure">${esc(job.lastError)}</p>` : ''}
    ${errors}
    <footer>
      ${active
        ? (job.status === 'CANCELLING'
          ? '<span class="text-xs text-slate-500">Stopping…</span>'
          : `<button type="button" class="btn-danger btn-sm" data-migration-action="${confirming ? 'cancel-confirmed' : 'cancel'}" data-id="${esc(job.id)}">${confirming ? 'Really cancel?' : 'Cancel'}</button>`)
        : `<button type="button" class="btn-secondary btn-sm" data-migration-action="dismiss" data-id="${esc(job.id)}">Dismiss</button>`}
    </footer>
  </article>`;
}

function renderWidget() {
  const root = $('migrationWidget');
  if (!root) return;
  const jobs = visibleJobs(widget.jobs, Date.now());
  root.classList.toggle('hidden', jobs.length === 0);
  document.body.classList.toggle('has-migration-widget', jobs.length > 0);
  if (!jobs.length) return;

  $('migrationWidgetLabel').textContent = widgetSummary(jobs);
  const lead = jobs.find((job) => isActiveStatus(job.status)) ?? jobs[0];
  const percent = progressPercent(lead);
  const bar = $('migrationWidgetBar');
  bar.style.width = `${percent === null ? 100 : percent}%`;
  bar.classList.toggle('migration-indeterminate', percent === null && isActiveStatus(lead.status));
  $('migrationWidgetToggle').setAttribute('aria-expanded', String(widget.open));
  $('migrationWidgetPanel').classList.toggle('hidden', !widget.open);
  if (widget.open) $('migrationWidgetList').innerHTML = jobs.map(jobCard).join('');
}

document.addEventListener('click', async (event) => {
  const toggle = event.target.closest('#migrationWidgetToggle');
  if (toggle) return setWidgetOpen(!widget.open);
  if (event.target.closest('#migrationWidgetClose')) return setWidgetOpen(false);

  const button = event.target.closest('[data-migration-action]');
  if (!button) return;
  const { id } = button.dataset;
  const action = button.dataset.migrationAction;
  try {
    if (action === 'cancel') {
      widget.confirming.add(id);
      setTimeout(() => { widget.confirming.delete(id); renderWidget(); }, 5000);
      renderWidget();
    } else if (action === 'cancel-confirmed') {
      widget.confirming.delete(id);
      await api(`/api/migrations/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
      poller.refresh();
    } else if (action === 'dismiss') {
      await api(`/api/migrations/${encodeURIComponent(id)}`, { method: 'DELETE' });
      poller.refresh();
    }
  } catch (error) {
    window.showToast?.(error.message, 'danger');
  }
});

document.addEventListener('toggle', (event) => {
  const details = event.target;
  if (!details.classList?.contains('migration-errors')) return;
  if (details.open) widget.openErrors.add(details.dataset.job); else widget.openErrors.delete(details.dataset.job);
}, true);

const poller = createJobPoller({
  async fetchJobs() {
    const response = await window.apiFetch('/api/migrations', { headers: { Accept: 'application/json' } });
    // Not signed in (or sent to the login page): nothing to show, and no reason to keep asking.
    if (response.redirected || response.status === 401 || response.status === 403) return [];
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  },
  onUpdate(jobs) {
    widget.jobs = jobs;
    renderWidget();
  },
  onFinished(job) {
    const ok = job.status === 'COMPLETED';
    const text = ok ? `Migration to ${job.targetBucket} finished: ${job.copiedObjects} copied.`
      : `Migration to ${job.targetBucket}: ${statusLabel(job.status).toLowerCase()}.`;
    window.showToast?.(text, ok ? 'success' : job.status === 'CANCELLED' ? 'info' : 'warning');
  },
});

document.addEventListener('DOMContentLoaded', () => {
  if (!$('migrationWidget')) return;
  try { widget.open = sessionStorage.getItem('s3webui-migration-open') === '1'; } catch { /* storage unavailable */ }
  poller.refresh();
  setInterval(renderWidget, 30000);
});

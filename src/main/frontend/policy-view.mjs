// A policy document editor with two interchangeable views: the visual grant editor and the raw
// JSON editor. The JSON text is the single source of truth - every visual edit is written straight
// through to it - so switching views at any moment never loses work. When the text has errors the
// visual view says so, locks itself and offers a jump to the editor at the offending position.

import { createJsonEditor } from './editor.js';
import { createGrantEditor } from './grant-editor.mjs';
import {
  PUBLIC_PRINCIPAL,
  buildBucketPolicy,
  buildIdentityPolicy,
  formatPolicy,
  parseBucketPolicy,
  parseIdentityPolicy,
  validatePolicy,
} from './policy-model.mjs';

const escape = (value) =>
  String(value ?? '').replace(
    /[&<>"']/g,
    (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch],
  );

const pretty = (text) => {
  try {
    return formatPolicy(JSON.parse(text));
  } catch {
    return text ?? '';
  }
};

/**
 * @param {HTMLElement} host
 * @param {object} options
 * @param {'identity'|'bucket'} options.kind
 * @param {string} [options.bucket] required for kind "bucket"
 * @param {Function} [options.loadSubjects] see createGrantEditor
 * @param {boolean} [options.allowCustom]
 * @param {boolean} [options.allowPublic]
 * @param {() => void} [options.onChange] any user edit, in either view
 * @param {(valid: boolean) => void} [options.onValidity] whether the document can be saved
 */
export function createPolicyView(host, options) {
  const { kind, bucket, onChange } = options;
  let mode = 'visual';
  let parsed = { grants: new Map(), preserved: [], rest: {} };
  let errors = [];
  let dirty = false;
  let readOnly = false;

  host.classList.add('policy-view');
  host.innerHTML = `
    <div class="policy-view-bar">
      <div class="segmented" role="tablist" aria-label="Editor">
        <button type="button" class="segmented-btn" role="tab" data-mode="visual"><i data-icon="adjustments-horizontal"></i>Visual</button>
        <button type="button" class="segmented-btn" role="tab" data-mode="json"><i data-icon="code-bracket-square"></i>JSON</button>
      </div>
      <span class="policy-view-status" aria-live="polite"></span>
    </div>
    <div class="policy-pane" data-pane="visual">
      <div class="policy-errors hidden" role="alert"></div>
      <div class="alert alert-warn policy-public-warning hidden">
        <i data-icon="shield-exclamation"></i>
        <span>Everyone can change or delete files in this bucket. Anyone who knows its address can do this, without signing in.</span>
      </div>
      <div class="policy-grants"></div>
    </div>
    <div class="policy-pane hidden" data-pane="json">
      <p class="field-help mb-2">An IAM-style JSON policy document. Switch back to Visual at any time — your edits carry over.</p>
      <div class="json-editor"></div>
    </div>`;

  const statusEl = host.querySelector('.policy-view-status');
  const errorsEl = host.querySelector('.policy-errors');
  const publicWarningEl = host.querySelector('.policy-public-warning');
  const grantsHost = host.querySelector('.policy-grants');
  if (host.id) grantsHost.id = `${host.id}-grants`;

  const markDirty = () => {
    dirty = true;
    onChange?.();
  };

  const editor = createJsonEditor(host.querySelector('.json-editor'), '', {
    onChange: () => {
      markDirty();
      updateStatus();
    },
  });

  const grantEditor = createGrantEditor(grantsHost, {
    kind,
    loadSubjects: options.loadSubjects,
    allowCustom: options.allowCustom,
    allowPublic: options.allowPublic,
    onShowJson: () => setMode('json'),
    onChange: () => {
      // Visual → JSON write-through. The grant editor is locked whenever the text is invalid, so
      // this never overwrites a broken document the user is still fixing.
      parsed = { ...parsed, grants: grantEditor.getGrants() };
      editor.setValue(formatPolicy(build()));
      errors = [];
      options.onValidity?.(true);
      markDirty();
      updateWarnings();
      updateStatus();
    },
  });

  function build() {
    return kind === 'bucket'
      ? buildBucketPolicy(bucket, parsed.grants, parsed.preserved, parsed.rest)
      : buildIdentityPolicy(parsed.grants, parsed.preserved, parsed.rest);
  }

  /** JSON → visual: re-reads the text, refreshing grants or surfacing errors. */
  function syncFromText() {
    const result = validatePolicy(editor.getValue(), { kind, bucket });
    errors = result.errors;
    if (!errors.length) {
      parsed = kind === 'bucket' ? parseBucketPolicy(result.doc, bucket) : parseIdentityPolicy(result.doc);
      grantEditor.setGrants(parsed.grants, parsed.preserved.length);
    }
    grantEditor.setLocked(errors.length > 0);
    options.onValidity?.(errors.length === 0);
    renderErrors();
    updateWarnings();
    updateStatus();
  }

  function renderErrors() {
    errorsEl.classList.toggle('hidden', !errors.length);
    if (!errors.length) {
      errorsEl.innerHTML = '';
      return;
    }
    const shown = errors.slice(0, 5);
    const more = errors.length - shown.length;
    errorsEl.innerHTML = `
      <div class="policy-errors-icon"><i data-icon="shield-exclamation"></i></div>
      <div class="policy-errors-body">
        <p class="policy-errors-title">${errors.length === 1 ? 'The policy has an error' : `The policy has ${errors.length} errors`}, so it can't be shown visually or saved</p>
        <ul>${shown.map((error) => `<li>${escape(error.message)}</li>`).join('')}${more > 0 ? `<li>…and ${more} more</li>` : ''}</ul>
      </div>
      <button type="button" class="btn-primary policy-errors-action"><i data-icon="code-bracket-square"></i>Switch to editor</button>`;
    errorsEl.querySelector('button').addEventListener('click', () => {
      setMode('json');
      editor.focusAt(errors.find((error) => typeof error.offset === 'number')?.offset ?? 0);
    });
    window.refreshIcons?.();
  }

  function updateWarnings() {
    const publicGrant = kind === 'bucket' && !errors.length ? parsed.grants.get(PUBLIC_PRINCIPAL) : null;
    const risky = publicGrant && ['write', 'delete', 'full'].some((id) => publicGrant.has(id));
    publicWarningEl.classList.toggle('hidden', !risky);
  }

  function updateStatus() {
    // In JSON mode the status re-validates lazily: errors appear as a badge on the Visual tab.
    if (mode === 'json') {
      errors = validatePolicy(editor.getValue(), { kind, bucket }).errors;
      options.onValidity?.(errors.length === 0);
    }
    const visualTab = host.querySelector('[data-mode="visual"]');
    visualTab.classList.toggle('has-error', errors.length > 0);
    visualTab.title = errors.length ? 'The JSON has errors — open Visual to see them' : '';
    statusEl.textContent = readOnly ? 'Read-only' : dirty ? 'Unsaved changes' : '';
    statusEl.classList.toggle('is-dirty', dirty && !readOnly);
  }

  function setMode(next) {
    if (next === 'visual' && mode !== 'visual') syncFromText();
    mode = next;
    host.querySelectorAll('[data-mode]').forEach((tab) => {
      const active = tab.getAttribute('data-mode') === mode;
      tab.setAttribute('aria-selected', String(active));
      tab.tabIndex = active ? 0 : -1;
    });
    host.querySelectorAll('[data-pane]').forEach((pane) => pane.classList.toggle('hidden', pane.getAttribute('data-pane') !== mode));
    if (mode === 'json') editor.view.requestMeasure();
    updateStatus();
  }

  host.querySelectorAll('[data-mode]').forEach((tab) => {
    tab.addEventListener('click', () => setMode(tab.getAttribute('data-mode')));
    tab.addEventListener('keydown', (event) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      const target = tab.getAttribute('data-mode') === 'visual' ? 'json' : 'visual';
      setMode(target);
      host.querySelector(`[data-mode="${target}"]`).focus();
    });
  });

  setMode('visual');

  return {
    /** Loads a stored document (raw text or null for "no policy") and resets the dirty flag. */
    setDocument(text, { mode: initialMode = 'visual' } = {}) {
      editor.setValue(text ? pretty(text) : '');
      parsed = { grants: new Map(), preserved: [], rest: {} };
      dirty = false;
      syncFromText();
      setMode(initialMode);
    },
    /**
     * The document to save. `doc` is null when the policy would contain no statements, which
     * callers treat as "remove the policy".
     * @returns {{errors: {message: string}[], doc: object|null, text: string|null}}
     */
    getResult() {
      const text = editor.getValue();
      const result = validatePolicy(text, { kind, bucket });
      if (result.errors.length) {
        if (mode === 'visual') syncFromText();
        return { errors: result.errors, doc: null, text: null };
      }
      const statements = result.doc ? [].concat(result.doc.Statement ?? []) : [];
      return statements.length ? { errors: [], doc: result.doc, text } : { errors: [], doc: null, text: null };
    },
    isDirty: () => dirty,
    markClean() {
      dirty = false;
      updateStatus();
    },
    setReadOnly(value) {
      readOnly = Boolean(value);
      grantEditor.setReadOnly(readOnly);
      editor.setEditable(!readOnly);
      updateStatus();
    },
    setMode,
    reloadSubjects: () => grantEditor.reloadSubjects(),
  };
}

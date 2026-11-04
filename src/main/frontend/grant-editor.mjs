// The visual half of the permission editor: one row per bucket (identity policies) or per
// principal (bucket policies), each with toggle chips for List / Read / Write / Delete and a
// separate "Full control" chip. Holds only grants; the policy document is assembled elsewhere.

import {
  BASIC_PERMISSION_IDS,
  FULL_PERMISSION,
  PERMISSIONS,
  PRESETS,
  PUBLIC_PRINCIPAL,
  normalizePermissions,
} from './policy-model.mjs';

const CUSTOM_OPTION = '__custom__';

const escape = (value) =>
  String(value ?? '').replace(
    /[&<>"']/g,
    (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[ch],
  );

/** "arn:aws:iam::123:user/team/alice" → "alice"; anything else is returned unchanged. */
export function principalDisplayName(value) {
  if (value === PUBLIC_PRINCIPAL) return 'Everyone';
  const match = /:(?:user|group|role)\/(?:.*\/)?([^/]+)$/.exec(value);
  return match ? match[1] : value;
}

/**
 * @param {HTMLElement} host
 * @param {object} options
 * @param {'identity'|'bucket'} options.kind identity → rows are buckets; bucket → rows are principals
 * @param {() => Promise<{value: string, label: string, sublabel?: string}[]>} [options.loadSubjects]
 * @param {boolean} [options.allowCustom] offer a free-text principal ARN (bucket policies)
 * @param {boolean} [options.allowPublic] offer "Everyone" (bucket policies)
 * @param {() => void} [options.onChange]
 * @param {() => void} [options.onShowJson]
 */
export function createGrantEditor(host, options) {
  const { kind, loadSubjects, allowCustom = false, allowPublic = false, onChange, onShowJson } = options;
  const noun = kind === 'identity' ? 'bucket' : 'user';

  let grants = new Map();
  let preservedCount = 0;
  let subjects = [];
  let subjectsError = null;
  let readOnly = Boolean(options.readOnly);
  let locked = false;
  let addPreset = 'read';
  let customMode = false;

  host.classList.add('grant-editor');
  host.innerHTML = `
    <div class="grant-list" role="list"></div>
    <div class="grant-add"></div>
    <p class="grant-preserved hidden"></p>`;
  const listEl = host.querySelector('.grant-list');
  const addEl = host.querySelector('.grant-add');
  const preservedEl = host.querySelector('.grant-preserved');

  const subjectInfo = (value) => {
    if (value === PUBLIC_PRINCIPAL)
      return { label: 'Everyone', sublabel: 'Anyone on the internet, without signing in', public: true };
    const known = subjects.find((subject) => subject.value === value);
    if (known) return known;
    if (kind === 'identity') return { label: value, sublabel: 'Bucket' };
    const name = principalDisplayName(value);
    return { label: name, sublabel: name === value ? 'Principal' : value };
  };

  const changed = () => {
    render();
    onChange?.();
  };

  // ── Rows ────────────────────────────────────────────────────────────────

  function chipHtml(permission, state, subjectLabel) {
    const { pressed, implied } = state;
    const classes = ['perm-chip'];
    if (permission.danger) classes.push('perm-chip--danger');
    if (implied) classes.push('is-implied');
    const title = implied ? `${permission.hint} (included in Full control)` : permission.hint;
    return `<button type="button" class="${classes.join(' ')}" data-perm="${permission.id}"
        aria-pressed="${pressed || implied}" title="${escape(title)}"
        aria-label="${escape(`${permission.label} — ${subjectLabel}`)}" ${readOnly || locked ? 'disabled' : ''}>
        <i data-lucide="${pressed || implied ? 'check' : permission.icon}"></i><span>${escape(permission.label)}</span>
      </button>`;
  }

  function rowHtml(subject, permissions) {
    const info = subjectInfo(subject);
    const full = permissions.has('full');
    const avatar = info.public
      ? '<span class="grant-avatar is-public"><i data-lucide="globe"></i></span>'
      : kind === 'identity'
        ? '<span class="grant-avatar"><i data-lucide="archive"></i></span>'
        : `<span class="grant-avatar">${escape((info.label || '?').trim().charAt(0).toUpperCase() || '?')}</span>`;
    const chips = PERMISSIONS.map((permission) =>
      chipHtml(permission, { pressed: !full && permissions.has(permission.id), implied: full }, info.label),
    ).join('');
    const hints = [];
    if (!permissions.size) hints.push('No permissions selected — this row will be dropped when you save.');
    else if (!full && !permissions.has('list') && (permissions.has('write') || permissions.has('delete') || permissions.has('read')))
      hints.push('Tip: without List, people can only reach files whose exact name they already know.');
    const publicBadge = info.public ? '<span class="badge-warn">Public</span>' : '';
    return `<div class="grant-row${permissions.size ? '' : ' is-empty'}" role="listitem" data-subject="${escape(subject)}">
        <div class="grant-subject">
          ${avatar}
          <div class="min-w-0">
            <div class="grant-name">${escape(info.label)} ${publicBadge}</div>
            ${info.sublabel ? `<div class="grant-sub${String(info.sublabel).startsWith('arn:') ? ' is-arn' : ''}" title="${escape(info.sublabel)}">${escape(info.sublabel)}</div>` : ''}
          </div>
        </div>
        <div class="grant-perms" role="group" aria-label="${escape(`Permissions for ${info.label}`)}">
          <div class="perm-chips">${chips}</div>
          <span class="perm-divider" aria-hidden="true"></span>
          ${chipHtml(FULL_PERMISSION, { pressed: full, implied: false }, info.label)}
        </div>
        ${
          readOnly
            ? ''
            : `<button type="button" class="btn-icon grant-remove" title="${escape(`Remove access for ${info.label}`)}" ${locked ? 'disabled' : ''}><i data-lucide="x"></i></button>`
        }
        ${hints.length ? `<p class="grant-hint">${escape(hints[0])}</p>` : ''}
      </div>`;
  }

  function toggle(subject, permissionId) {
    const current = new Set(grants.get(subject) ?? []);
    if (permissionId === 'full') {
      grants.set(subject, current.has('full') ? new Set(BASIC_PERMISSION_IDS) : new Set(['full']));
    } else if (current.has('full')) {
      // Unpicking one basic chip out of Full control keeps the other basics on.
      grants.set(subject, new Set(BASIC_PERMISSION_IDS.filter((id) => id !== permissionId)));
    } else {
      current.has(permissionId) ? current.delete(permissionId) : current.add(permissionId);
      grants.set(subject, normalizePermissions(current));
    }
    changed();
  }

  listEl.addEventListener('click', (event) => {
    const row = event.target.closest('.grant-row');
    if (!row || readOnly || locked) return;
    const subject = row.getAttribute('data-subject');
    const chip = event.target.closest('.perm-chip');
    if (chip) return toggle(subject, chip.getAttribute('data-perm'));
    if (event.target.closest('.grant-remove')) {
      grants.delete(subject);
      changed();
    }
  });

  // ── Add row ─────────────────────────────────────────────────────────────

  function availableSubjects() {
    const list = subjects.filter((subject) => !grants.has(subject.value));
    if (allowPublic && !grants.has(PUBLIC_PRINCIPAL))
      list.push({ value: PUBLIC_PRINCIPAL, label: 'Everyone (public access)', group: 'Public' });
    return list;
  }

  /** Options, wrapped in <optgroup>s when subjects carry a `group` (IAM users, S3 keys, ...). */
  function optionsHtml(options) {
    const option = (subject) =>
      `<option value="${escape(subject.value)}">${escape(subject.optionLabel || subject.label)}</option>`;
    if (!options.some((subject) => subject.group)) return options.map(option).join('');
    const groups = new Map();
    for (const subject of options) {
      const key = subject.group || 'Other';
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(subject);
    }
    return [...groups]
      .map(([label, members]) => `<optgroup label="${escape(label)}">${members.map(option).join('')}</optgroup>`)
      .join('');
  }

  function renderAdd() {
    if (readOnly) {
      addEl.innerHTML = '';
      return;
    }
    const options = availableSubjects();
    const optionHtml = optionsHtml(options);
    const customHtml = allowCustom
      ? `<optgroup label="Other"><option value="${CUSTOM_OPTION}">Enter a principal ARN…</option></optgroup>`
      : '';
    const nothing = !options.length && !allowCustom;
    const placeholder = nothing
      ? `<option value="">${escape(subjectsError || `Every ${noun} already has a row`)}</option>`
      : `<option value="">Choose a ${noun}…</option>`;
    const presets = PRESETS.map(
      (preset) =>
        `<button type="button" class="segmented-btn" role="radio" data-preset="${preset.id}" aria-checked="${preset.id === addPreset}">${escape(preset.label)}</button>`,
    ).join('');

    addEl.innerHTML = `
      <div class="grant-add-title"><i data-lucide="plus"></i>Give a ${noun} access</div>
      <div class="grant-add-controls">
        <label class="sr-only" for="${host.id || 'grant'}-subject">${noun === 'bucket' ? 'Bucket' : 'User'}</label>
        <select class="input grant-add-subject" id="${host.id || 'grant'}-subject" ${nothing || locked ? 'disabled' : ''}>
          ${placeholder}${optionHtml}${customHtml}
        </select>
        <input class="input grant-add-custom font-mono text-xs${customMode ? '' : ' hidden'}" type="text"
          placeholder="arn:aws:iam::123456789012:user/name" aria-label="Principal ARN" autocomplete="off" />
        <div class="segmented segmented--sm" role="radiogroup" aria-label="Starting permissions">${presets}</div>
        <button type="button" class="btn-secondary grant-add-button" disabled><i data-lucide="plus"></i>Add</button>
      </div>`;

    const select = addEl.querySelector('.grant-add-subject');
    const custom = addEl.querySelector('.grant-add-custom');
    const button = addEl.querySelector('.grant-add-button');
    const subjectValue = () => (select.value === CUSTOM_OPTION ? custom.value.trim() : select.value);
    const sync = () => {
      customMode = select.value === CUSTOM_OPTION;
      custom.classList.toggle('hidden', !customMode);
      button.disabled = locked || !subjectValue() || grants.has(subjectValue());
    };
    if (customMode) select.value = CUSTOM_OPTION;
    select.addEventListener('change', () => {
      sync();
      if (customMode) custom.focus();
    });
    custom.addEventListener('input', sync);
    custom.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' && !button.disabled) {
        event.preventDefault();
        button.click();
      }
    });
    addEl.querySelectorAll('[data-preset]').forEach((presetButton) =>
      presetButton.addEventListener('click', () => {
        addPreset = presetButton.getAttribute('data-preset');
        addEl.querySelectorAll('[data-preset]').forEach((other) =>
          other.setAttribute('aria-checked', String(other === presetButton)),
        );
      }),
    );
    button.addEventListener('click', () => {
      const value = subjectValue();
      if (!value || grants.has(value)) return;
      const preset = PRESETS.find((candidate) => candidate.id === addPreset) ?? PRESETS[0];
      grants.set(value, normalizePermissions(preset.permissions));
      customMode = false;
      // Always fall back to the least privilege, so adding "Everyone" next is never accidentally
      // read-write just because the previous row was.
      addPreset = 'read';
      changed();
      host.querySelector(`.grant-row[data-subject="${CSS.escape(value)}"] .perm-chip`)?.focus();
    });
    sync();
  }

  // ── Render ──────────────────────────────────────────────────────────────

  function render() {
    host.classList.toggle('is-locked', locked);
    listEl.innerHTML = grants.size
      ? [...grants].map(([subject, permissions]) => rowHtml(subject, permissions)).join('')
      : `<div class="grant-empty">
          <span class="grant-empty-icon"><i data-lucide="shield"></i></span>
          <div>
            <p class="grant-empty-title">${kind === 'identity' ? 'No bucket access yet' : 'Nobody has been granted access here'}</p>
            <p class="grant-empty-copy">${
              kind === 'identity'
                ? 'Pick a bucket below and choose what this identity may do with it.'
                : 'Pick a user below — or Everyone — and choose what they may do with this bucket.'
            }</p>
          </div>
        </div>`;
    renderAdd();
    preservedEl.classList.toggle('hidden', !preservedCount);
    if (preservedCount) {
      preservedEl.innerHTML = `<i data-lucide="braces"></i><span>${preservedCount} advanced ${
        preservedCount === 1 ? 'statement is' : 'statements are'
      } kept as-is.</span> <button type="button" class="link-button">View in JSON</button>`;
      preservedEl.querySelector('button').addEventListener('click', () => onShowJson?.());
    }
    window.refreshIcons?.();
  }

  async function reloadSubjects() {
    if (!loadSubjects) return;
    try {
      subjects = (await loadSubjects()) ?? [];
      subjectsError = null;
    } catch (error) {
      subjects = [];
      subjectsError = error?.message || `Could not load ${noun}s`;
    }
    render();
  }

  render();

  return {
    /** Replace the grants shown. `preservedCount` drives the "kept as-is" note. */
    setGrants(next, nextPreservedCount = 0) {
      grants = new Map([...next].map(([subject, permissions]) => [subject, new Set(permissions)]));
      preservedCount = nextPreservedCount;
      render();
    },
    /** Rows with no permissions are dropped, so an emptied row behaves like a removed one. */
    getGrants: () => new Map([...grants].filter(([, permissions]) => permissions.size)),
    setReadOnly(value) {
      readOnly = Boolean(value);
      render();
    },
    /** Locked while the JSON has errors: shown, but not editable, so it cannot drift from the text. */
    setLocked(value) {
      locked = Boolean(value);
      render();
    },
    reloadSubjects,
  };
}

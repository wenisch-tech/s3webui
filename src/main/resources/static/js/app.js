/**
 * Shared utilities available on all pages.
 */

// ── Theme management ─────────────────────────────────────────────────────────

/** Apply saved theme preference as early as possible (call from <head>). */
function initTheme() {
    const saved = localStorage.getItem('s3webui-theme') || 'light';
    document.documentElement.setAttribute('data-bs-theme', saved);
    updateThemeIcon(saved);
}

function toggleTheme() {
    const current = document.documentElement.getAttribute('data-bs-theme') || 'light';
    const next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-bs-theme', next);
    localStorage.setItem('s3webui-theme', next);
    updateThemeIcon(next);
}

function updateThemeIcon(theme) {
    const icon = document.getElementById('themeIcon');
    if (!icon) return;
    icon.className = theme === 'dark' ? 'bi bi-sun-fill' : 'bi bi-moon-fill';
}

// Run icon update once DOM is ready (the attribute was already set in <head>).
document.addEventListener('DOMContentLoaded', () => {
    const current = document.documentElement.getAttribute('data-bs-theme') || 'light';
    updateThemeIcon(current);
});

// ── CSRF aware fetch ─────────────────────────────────────────────────────────

/** Read the CSRF token Spring Security writes into a readable cookie. */
function csrfToken() {
    const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : null;
}

/**
 * fetch() with the CSRF header attached for state-changing methods. Use this instead of fetch()
 * for every call to /api, otherwise Spring Security rejects the request with 403.
 */
function apiFetch(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const headers = new Headers(options.headers || {});
    if (!['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method)) {
        const token = csrfToken();
        if (token) {
            headers.set('X-XSRF-TOKEN', token);
        }
    }
    return fetch(url, {...options, headers, credentials: 'same-origin'});
}

// ── S3 session key picker ────────────────────────────────────────────────────

let _s3SessionModal;

/** Show the key picker when this session has no usable S3 key yet. */
async function ensureS3SessionDialog() {
    const status = await loadS3SessionStatus();
    if (!status || !status.selectionRequired) return;

    // A single key and no way to type your own: nothing to ask, just use it.
    if (status.credentials.length === 1 && !status.allowOwnCredentials) {
        await switchCredential(status.credentials[0].id, {silent: true});
        return;
    }

    renderS3SessionDialog(status, false);
}

/** Open the picker on demand from the navbar switcher. */
async function openS3SessionDialog() {
    const status = await loadS3SessionStatus();
    if (!status) return;
    renderS3SessionDialog(status, true);
}

async function loadS3SessionStatus() {
    if (!document.getElementById('s3SessionModal')) return null;
    try {
        const response = await apiFetch('/api/s3/session', {headers: {'Accept': 'application/json'}});
        if (!response.ok) return null;
        return await response.json();
    } catch (error) {
        showToast(error.message || 'Failed to read the S3 session status', 'danger');
        return null;
    }
}

function renderS3SessionDialog(status, dismissible) {
    const modalEl = document.getElementById('s3SessionModal');
    if (!modalEl) return;

    const list = document.getElementById('s3SessionKeyList');
    list.innerHTML = '';
    status.credentials.forEach(credential => {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'list-group-item list-group-item-action d-flex align-items-center gap-3';
        if (credential.id === status.activeCredentialId) {
            item.classList.add('active');
        }
        item.onclick = () => switchCredential(credential.id);

        const icon = document.createElement('i');
        icon.className = credential.builtIn ? 'bi bi-hdd-network fs-5' : 'bi bi-key fs-5';

        const text = document.createElement('span');
        const name = document.createElement('span');
        name.className = 'd-block fw-semibold';
        name.textContent = credential.name;
        const endpoint = document.createElement('small');
        endpoint.className = 'd-block text-muted';
        endpoint.textContent = [credential.endpointUrl, credential.region].filter(Boolean).join(' · ');
        text.append(name, endpoint);

        item.append(icon, text);
        list.appendChild(item);
    });

    toggleHidden('s3SessionKeys', status.credentials.length === 0);
    toggleHidden('s3SessionNoKeys', status.credentials.length > 0);
    toggleHidden('s3SessionOwn', !status.allowOwnCredentials);
    toggleHidden('s3SessionClose', !dismissible);
    hideS3SessionError();

    if (!_s3SessionModal) {
        _s3SessionModal = new bootstrap.Modal(modalEl, {backdrop: 'static', keyboard: false});
    }
    _s3SessionModal.show();
}

/** Pick a stored key for this session and reload with it. */
async function switchCredential(credentialId, options = {}) {
    try {
        const response = await apiFetch('/api/s3/session', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
            body: JSON.stringify({credentialId})
        });

        if (!response.ok) {
            const message = await getResponseErrorMessage(response, 'Unable to select that S3 key');
            if (options.silent) {
                showToast(message, 'danger');
            } else {
                showS3SessionError(message);
            }
            return;
        }

        location.reload();
    } catch (error) {
        showS3SessionError(error.message || 'Unable to select that S3 key');
    }
}

/** Connect with credentials the user typed in, when the administrator allows it. */
async function useOwnCredentials() {
    const payload = {
        accessKey: (document.getElementById('s3AccessKey')?.value || '').trim(),
        secretKey: (document.getElementById('s3SecretKey')?.value || '').trim(),
        endpointUrl: (document.getElementById('s3EndpointUrl')?.value || '').trim(),
        region: (document.getElementById('s3Region')?.value || '').trim()
    };

    hideS3SessionError();
    try {
        const response = await apiFetch('/api/s3/session', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
            body: JSON.stringify(payload)
        });

        if (!response.ok) {
            showS3SessionError(await getResponseErrorMessage(response, 'Unable to save these credentials'));
            return;
        }

        location.reload();
    } catch (error) {
        showS3SessionError(error.message || 'Unable to save these credentials');
    }
}

function toggleHidden(elementId, hidden) {
    const element = document.getElementById(elementId);
    if (element) {
        element.classList.toggle('d-none', hidden);
    }
}

function showS3SessionError(message) {
    const errorEl = document.getElementById('s3SessionError');
    if (!errorEl) {
        showToast(message, 'danger');
        return;
    }
    errorEl.textContent = message;
    errorEl.classList.remove('d-none');
}

function hideS3SessionError() {
    const errorEl = document.getElementById('s3SessionError');
    if (!errorEl) return;
    errorEl.textContent = '';
    errorEl.classList.add('d-none');
}

/** Extract a useful error message from a failed fetch response. */
async function getResponseErrorMessage(response, fallback = 'Request failed') {
    if (!response) return fallback;

    const text = await response.text();
    if (!text) return fallback;

    try {
        const payload = JSON.parse(text);
        if (payload && typeof payload.message === 'string' && payload.message.trim()) {
            return payload.message;
        }
        if (payload && typeof payload.error === 'string' && payload.error.trim()) {
            return payload.error;
        }
    } catch (ignored) {
        // Body is not JSON, return as-is.
    }

    return text;
}

// ── Toast notifications ───────────────────────────────────────────────────────

/** Show a Bootstrap toast notification */
function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    if (!container) return;
    const safeMessage = String(message || '').replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
    const id = 'toast-' + Date.now();
    const icons = {
        success: 'bi-check-circle-fill',
        danger:  'bi-exclamation-triangle-fill',
        info:    'bi-info-circle-fill',
        warning: 'bi-exclamation-circle-fill'
    };
    const html = `
        <div id="${id}" class="toast align-items-center text-bg-${type} border-0" role="alert" aria-live="assertive">
            <div class="d-flex">
                <div class="toast-body d-flex align-items-center gap-2">
                    <i class="bi ${icons[type] || icons.info}"></i>
                    ${safeMessage}
                </div>
                <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>
            </div>
        </div>`;
    container.insertAdjacentHTML('beforeend', html);
    const toastEl = document.getElementById(id);
    const toast = new bootstrap.Toast(toastEl, {delay: 4000});
    toast.show();
    toastEl.addEventListener('hidden.bs.toast', () => toastEl.remove());
}

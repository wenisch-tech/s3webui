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

// ── Runtime S3 configuration dialog ─────────────────────────────────────────

let _s3ConfigModal;

async function ensureS3ConfigDialog() {
    const modalEl = document.getElementById('s3ConfigModal');
    if (!modalEl) return;

    try {
        const response = await fetch('/api/s3/config/status', {headers: {'Accept': 'application/json'}});
        if (!response.ok) return;

        const status = await response.json();
        if (!status.required) return;

        setS3FieldValueAndLock('s3AccessKey', status.accessKey || '', !!status.accessKeyLocked);
        setS3FieldValueAndLock('s3SecretKey', status.secretKey || '', !!status.secretKeyLocked);
        setS3FieldValueAndLock('s3EndpointUrl', status.endpointUrl || '', !!status.endpointUrlLocked);
        setS3FieldValueAndLock('s3Region', status.region || 'us-east-1', !!status.regionLocked);
        protectSecretField();

        const regionInput = document.getElementById('s3Region');
        if (regionInput && !regionInput.value) {
            regionInput.value = status.region || 'us-east-1';
        }

        if (!_s3ConfigModal) {
            _s3ConfigModal = new bootstrap.Modal(modalEl, {backdrop: 'static', keyboard: false});
        }
        _s3ConfigModal.show();
    } catch (error) {
        showToast(error.message || 'Failed to read S3 configuration status', 'danger');
    }
}

function setS3FieldValueAndLock(inputId, value, locked) {
    const input = document.getElementById(inputId);
    if (!input) return;

    input.value = value;
    input.readOnly = locked;
    input.disabled = locked;
}

async function saveS3Config() {
    const accessKey = (document.getElementById('s3AccessKey')?.value || '').trim();
    const secretKey = (document.getElementById('s3SecretKey')?.value || '').trim();
    const endpointUrl = (document.getElementById('s3EndpointUrl')?.value || '').trim();
    const region = (document.getElementById('s3Region')?.value || '').trim();

    const errorEl = document.getElementById('s3ConfigError');
    if (errorEl) {
        errorEl.classList.add('d-none');
        errorEl.textContent = '';
    }

    try {
        const response = await fetch('/api/s3/config', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            },
            body: JSON.stringify({accessKey, secretKey, endpointUrl, region})
        });

        if (!response.ok) {
            const payload = await response.json().catch(() => ({}));
            const message = payload.message || 'Unable to save S3 configuration';
            showS3ConfigError(message);
            return;
        }

        location.reload();
    } catch (error) {
        showS3ConfigError(error.message || 'Unable to save S3 configuration');
    }
}

function showS3ConfigError(message) {
    const errorEl = document.getElementById('s3ConfigError');
    if (!errorEl) {
        showToast(message, 'danger');
        return;
    }
    errorEl.textContent = message;
    errorEl.classList.remove('d-none');
}

function protectSecretField() {
    const secretInput = document.getElementById('s3SecretKey');
    if (!secretInput || secretInput.dataset.copyProtected === 'true') {
        return;
    }

    const block = (event) => {
        event.preventDefault();
    };

    secretInput.addEventListener('copy', block);
    secretInput.addEventListener('cut', block);
    secretInput.addEventListener('contextmenu', block);
    secretInput.dataset.copyProtected = 'true';
}

// ── Toast notifications ───────────────────────────────────────────────────────

/** Show a Bootstrap toast notification */
function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer');
    if (!container) return;
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
                    ${message}
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

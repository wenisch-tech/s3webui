/**
 * Shared utilities available on all pages.
 */

// ── Theme management ─────────────────────────────────────────────────────────

/** Apply saved theme preference as early as possible (call from <head>). */
function initTheme() {
    const saved = localStorage.getItem('s3webui-theme') || 'dark';
    document.documentElement.setAttribute('data-bs-theme', saved);
    updateThemeIcon(saved);
}

function toggleTheme() {
    const current = document.documentElement.getAttribute('data-bs-theme') || 'dark';
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
    const current = document.documentElement.getAttribute('data-bs-theme') || 'dark';
    updateThemeIcon(current);
});

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

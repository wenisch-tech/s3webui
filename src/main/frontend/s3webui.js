import Alpine from 'alpinejs';
import './upload.js';
import { createJsonEditor } from './editor.js';
import { createIcons, Archive, ArrowDownUp, Box, CheckCircle2, ChevronDown, Clock3,
  CloudUpload, Download, File, Folder, FolderOpen, FolderPlus, Globe, History, Info, LayoutGrid, LogOut,
  Key, Menu, Moon, MoreHorizontal, Network, Pencil, PieChart, Plus, ScrollText, Settings, ShieldAlert, Sun,
  Table2, Trash2, Upload, UserCircle, Users, X } from 'lucide';

const icons = { Archive, ArrowDownUp, Box, CheckCircle2, ChevronDown, Clock3, CloudUpload,
  Download, File, Folder, FolderOpen, FolderPlus, Globe, History, Info, Key, LayoutGrid, LogOut, Menu, Moon,
  MoreHorizontal, Network, Pencil, PieChart, Plus, ScrollText, Settings, ShieldAlert, Sun, Table2, Trash2,
  Upload, UserCircle, Users, X };

window.createJsonEditor = createJsonEditor;

const preferredTheme = (() => { try { return localStorage.getItem('s3webui-theme') || 'light'; } catch { return 'light'; } })();
function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  document.dispatchEvent(new CustomEvent('s3webui:themechange', { detail: { theme } }));
}
applyTheme(preferredTheme);

Alpine.data('shell', () => ({ mobileOpen: false, menuOpen: false, theme: preferredTheme,
  toggleTheme() { this.theme = this.theme === 'dark' ? 'light' : 'dark'; applyTheme(this.theme); try { localStorage.setItem('s3webui-theme', this.theme); } catch {} },
  init() { document.addEventListener('s3webui:themechange', () => this.$nextTick(() => createIcons({ icons }))); }
}));
window.Alpine = Alpine;
document.addEventListener('alpine:initialized', () => createIcons({ icons }));
Alpine.start();
window.refreshIcons = () => createIcons({ icons });

window.showPageLoading = () => {
  const loader = document.getElementById('pageLoader');
  if (!loader) return;
  loader.classList.add('is-visible');
  loader.setAttribute('aria-hidden', 'false');
};
window.hidePageLoading = () => {
  const loader = document.getElementById('pageLoader');
  if (!loader) return;
  loader.classList.remove('is-visible');
  loader.setAttribute('aria-hidden', 'true');
};
// A bfcache restore reuses the document we left mid-navigation, overlay and all.
window.addEventListener('pageshow', () => window.hidePageLoading());

document.addEventListener('click', event => {
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  const link = event.target.closest('a[href]');
  if (!link || link.target || link.hasAttribute('download')) return;
  const url = new URL(link.href, window.location.href);
  if (url.origin !== window.location.origin || url.pathname.startsWith('/api/')) return;
  window.showPageLoading();
});
document.addEventListener('submit', event => {
  if (!event.defaultPrevented) window.showPageLoading();
});
// Mirrors the card's own navigation guard in buckets.html: a click on a link or a button
// (the "..." menu, its items) does not navigate, so it must not raise the overlay either.
document.addEventListener('click', event => {
  if (event.target.closest('a,button')) return;
  if (event.target.closest('.bucket-card[data-bucket-url]')) window.showPageLoading();
});

window.openDialog = id => { const dialog = document.getElementById(id); if (!dialog) return; dialog.classList.remove('hidden'); dialog.setAttribute('aria-hidden', 'false'); dialog.querySelector('[data-autofocus]')?.focus(); };
window.closeDialog = id => { const dialog = document.getElementById(id); if (!dialog) return; dialog.classList.add('hidden'); dialog.setAttribute('aria-hidden', 'true'); dialog.dispatchEvent(new CustomEvent('s3webui:dialogclosed')); };
document.addEventListener('keydown', event => { if (event.key !== 'Escape') return; const dialogs = [...document.querySelectorAll('.modal-backdrop:not(.hidden)')]; const dialog = dialogs.at(-1); if (dialog?.dataset.static !== 'true') closeDialog(dialog.id); });
document.addEventListener('click', event => { if (event.target.classList.contains('modal-backdrop') && event.target.dataset.static !== 'true') closeDialog(event.target.id); });

const csrfToken = () => { const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/); return match ? decodeURIComponent(match[1]) : null; };
window.csrfToken = csrfToken;
const apiFetch = (url, options = {}) => {
  const method = (options.method || 'GET').toUpperCase(), headers = new Headers(options.headers || {});
  if (!['GET','HEAD','OPTIONS','TRACE'].includes(method)) { const token = csrfToken(); if (token) headers.set('X-XSRF-TOKEN', token); }
  return fetch(url, {...options, headers, credentials:'same-origin'});
};
window.apiFetch = apiFetch;
async function loadS3SessionStatus() { if (!document.getElementById('s3SessionModal')) return null; try { const response = await apiFetch('/api/s3/session', {headers:{Accept:'application/json'}}); return response.ok ? response.json() : null; } catch (error) { showToast(error.message || 'Failed to read the S3 session status', 'danger'); return null; } }
async function ensureS3SessionDialog() { const status = await loadS3SessionStatus(); if (!status?.selectionRequired) return; if (status.credentials.length === 1 && !status.allowOwnCredentials) return switchCredential(status.credentials[0].id, {silent:true}); renderS3SessionDialog(status, false); }
window.ensureS3SessionDialog = ensureS3SessionDialog;
window.ensureS3ConfigDialog = ensureS3SessionDialog;
window.openS3SessionDialog = async () => { const status = await loadS3SessionStatus(); if (status) renderS3SessionDialog(status, true); };
function renderS3SessionDialog(status, dismissible) {
  const list = document.getElementById('s3SessionKeyList'); list.innerHTML = '';
  status.credentials.forEach(credential => { const item = document.createElement('button'); item.type='button'; item.className='session-key'; if (credential.id === status.activeCredentialId) item.classList.add('active'); item.onclick=()=>switchCredential(credential.id); item.innerHTML=`<i data-lucide="${credential.builtIn?'network':'key'}"></i><span><strong>${escapeMarkup(credential.name)}</strong><small>${escapeMarkup([credential.endpointUrl,credential.region].filter(Boolean).join(' · '))}</small></span>`; list.appendChild(item); });
  document.getElementById('s3SessionKeys')?.classList.toggle('hidden', !status.credentials.length); document.getElementById('s3SessionNoKeys')?.classList.toggle('hidden', !!status.credentials.length); document.getElementById('s3SessionOwn')?.classList.toggle('hidden', !status.allowOwnCredentials); document.getElementById('s3SessionClose')?.classList.toggle('hidden', !dismissible); hideS3SessionError(); openDialog('s3SessionModal'); refreshIcons();
}
async function switchCredential(credentialId, options={}) { try { const response=await apiFetch('/api/s3/session',{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify({credentialId})}); if(response.ok)return location.reload(); const message=await getResponseErrorMessage(response,'Unable to select that S3 key'); options.silent?showToast(message,'danger'):showS3SessionError(message); } catch(error){showS3SessionError(error.message||'Unable to select that S3 key');} }
window.useOwnCredentials = async () => { const payload={accessKey:s3AccessKey.value.trim(),secretKey:s3SecretKey.value.trim(),endpointUrl:s3EndpointUrl.value.trim(),region:s3Region.value.trim(),insecureSkipTlsVerify:s3InsecureTls.checked}; hideS3SessionError(); try { const response=await apiFetch('/api/s3/session',{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify(payload)}); if(response.ok)return location.reload(); showS3SessionError(await getResponseErrorMessage(response,'Unable to save these credentials')); } catch(error){showS3SessionError(error.message||'Unable to save these credentials');} };
function showS3SessionError(message){const error=document.getElementById('s3SessionError');if(error){error.textContent=message;error.classList.remove('hidden')}else showToast(message,'danger')}
function hideS3SessionError(){const error=document.getElementById('s3SessionError');if(error){error.textContent='';error.classList.add('hidden')}}
function escapeMarkup(value){return String(value||'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
window.getResponseErrorMessage = async (response, fallback = 'Request failed') => { const text = await response.text(); if (!text) return fallback; try { const data = JSON.parse(text); return data.message || data.error || text; } catch { return text; } };
window.showToast = (message, type = 'info') => { const container = document.getElementById('toastContainer'); if (!container) return; const toast = document.createElement('div'); toast.className = `toast ${type}`; toast.setAttribute('role', 'status'); toast.innerHTML = `<span>${String(message || '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}</span><button class="btn-icon shrink-0" aria-label="Dismiss"><i data-lucide="x"></i></button>`; toast.querySelector('button').onclick = () => toast.remove(); container.appendChild(toast); createIcons({ icons, attrs: { width: 16, height: 16 } }); setTimeout(() => toast.remove(), 4500); };

import Alpine from 'alpinejs';
import './upload.js';
import { createJsonEditor } from './editor.js';
import { createObjectTableController, syncObjectSelectAll,
  toggleVisibleObjectSelection } from './object-sort.mjs';
import { appUrl } from './url.js';
import { globalSearchPagePath, globalSearchResultPath } from './global-search.mjs';
import { bucketSearchApiPath, formatObjectSize, relativeBucketResultParent,
  relativeBucketResultPath } from './bucket-search.mjs';
import { createIcons, Archive, ArrowDown, ArrowDownUp, ArrowUp, ArrowUpRight, Box, CalendarDays, CheckCircle2,
  ChevronDown, ChevronLeft, ChevronRight, Clock3, CloudUpload, Copy, Download, Eye, File, Folder, FolderOpen,
  FolderPlus, Globe, History, Info, LayoutGrid, LogIn, LogOut,
  Key, KeyRound, Link2, Menu, Moon, MoreHorizontal, Network, Pencil, PieChart, Plus, RefreshCw, Search, SearchX, ScrollText, Settings,
  ShieldAlert, ShieldCheck, Sun, Table2, Trash2, Unlink2, Upload, UserCircle, UserPlus, Users, X } from 'lucide';

const icons = { Archive, ArrowDown, ArrowDownUp, ArrowUp, ArrowUpRight, Box, CalendarDays, CheckCircle2,
  ChevronDown, ChevronLeft, ChevronRight, Clock3, CloudUpload, Copy, Eye,
  Download, File, Folder, FolderOpen, FolderPlus, Globe, History, Info, Key, KeyRound, Link2, LayoutGrid, LogOut,
  LogIn, Menu, Moon, MoreHorizontal, Network, Pencil, PieChart, Plus, RefreshCw, Search, SearchX, ScrollText, Settings, ShieldAlert, ShieldCheck,
  Sun, Table2, Trash2, Unlink2, Upload, UserCircle, UserPlus, Users, X };

function renderIcons() {
  createIcons({ icons });
  document.querySelectorAll('svg[data-lucide]').forEach(icon => icon.setAttribute('aria-hidden', 'true'));
  document.querySelectorAll('.btn-icon[title]:not([aria-label])').forEach(button => {
    button.setAttribute('aria-label', button.title);
  });
}

window.createJsonEditor = createJsonEditor;
window.appUrl = appUrl;
const objectTable = createObjectTableController();
window.filterObjectTable = query => objectTable.filter(query);
window.sortObjectTable = key => objectTable.sort(key);
window.goToObjectPage = page => objectTable.goToPage(page);
window.setObjectPageSize = pageSize => objectTable.setPageSize(pageSize);
window.syncObjectSelectAll = () => syncObjectSelectAll();
window.toggleSelectAll = box => toggleVisibleObjectSelection(box.checked);
objectTable.render();

const preferredTheme = (() => { try { return localStorage.getItem('s3webui-theme') || 'light'; } catch { return 'light'; } })();
function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  document.dispatchEvent(new CustomEvent('s3webui:themechange', { detail: { theme } }));
}
applyTheme(preferredTheme);

Alpine.data('shell', () => ({ mobileOpen: false, menuOpen: false, theme: preferredTheme,
  toggleTheme() { this.theme = this.theme === 'dark' ? 'light' : 'dark'; applyTheme(this.theme); try { localStorage.setItem('s3webui-theme', this.theme); } catch {} },
  init() { document.addEventListener('s3webui:themechange', () => this.$nextTick(renderIcons)); }
}));
window.Alpine = Alpine;
document.addEventListener('alpine:initialized', renderIcons);
Alpine.start();
window.refreshIcons = renderIcons;

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
  if (url.origin !== window.location.origin || url.pathname.startsWith(appUrl('/api/'))) return;
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

// Shared helpers for the JSON-document dialogs (bucket policy, CORS, IAM policies).
window.cfgError = (id, msg) => { const el = document.getElementById(id); el.textContent = msg; el.classList.remove('hidden'); };
window.cfgClearError = id => { const el = document.getElementById(id); el.textContent = ''; el.classList.add('hidden'); };
window.cfgResetRemove = id => { const b = document.getElementById(id); if (!b) return; b.dataset.confirm = ''; b.innerHTML = '<i data-lucide="trash-2"></i>Remove'; window.refreshIcons(); };
window.cfgEnsureEditor = (hostId, instance) => instance || createJsonEditor(document.getElementById(hostId));
window.cfgPretty = s => { try { return JSON.stringify(JSON.parse(s), null, 2); } catch { return s; } };

window.openDialog = id => { const dialog = document.getElementById(id); if (!dialog) return; dialog.classList.remove('hidden'); dialog.setAttribute('aria-hidden', 'false'); dialog.querySelector('[data-autofocus]')?.focus(); };
window.closeDialog = id => { const dialog = document.getElementById(id); if (!dialog) return; dialog.classList.add('hidden'); dialog.setAttribute('aria-hidden', 'true'); dialog.dispatchEvent(new CustomEvent('s3webui:dialogclosed')); };
document.addEventListener('keydown', event => { if (event.key !== 'Escape') return; const dialogs = [...document.querySelectorAll('.modal-backdrop:not(.hidden)')]; const dialog = dialogs.at(-1); if (dialog?.dataset.static !== 'true') closeDialog(dialog.id); });
document.addEventListener('click', event => { if (event.target.classList.contains('modal-backdrop') && event.target.dataset.static !== 'true') closeDialog(event.target.id); });

const csrfToken = () => { const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/); return match ? decodeURIComponent(match[1]) : null; };
window.csrfToken = csrfToken;
const apiFetch = (url, options = {}) => {
  const method = (options.method || 'GET').toUpperCase(), headers = new Headers(options.headers || {});
  if (!['GET','HEAD','OPTIONS','TRACE'].includes(method)) { const token = csrfToken(); if (token) headers.set('X-XSRF-TOKEN', token); }
  return fetch(appUrl(url), {...options, headers, credentials:'same-origin'});
};
window.apiFetch = apiFetch;
async function loadS3SessionStatus() { if (!document.getElementById('s3SessionModal')) return null; try { const response = await apiFetch('/api/s3/session', {headers:{Accept:'application/json'}}); return response.ok ? response.json() : null; } catch (error) { showToast(error.message || 'Failed to read the S3 session status', 'danger'); return null; } }
async function ensureS3SessionDialog() { const status = await loadS3SessionStatus(); if (!status?.selectionRequired) return; if (status.credentials.length === 1 && !status.allowOwnCredentials) return switchCredential(status.credentials[0].id, {silent:true}); renderS3SessionDialog(status, false); }
window.ensureS3SessionDialog = ensureS3SessionDialog;
window.ensureS3ConfigDialog = ensureS3SessionDialog;
window.openS3SessionDialog = async () => { const status = await loadS3SessionStatus(); if (status) renderS3SessionDialog(status, true); };
function renderS3SessionDialog(status, dismissible) {
  const list = document.getElementById('s3SessionKeyList'); list.innerHTML = '';
  status.credentials.forEach(credential => { const item = document.createElement('div'); item.className='session-key'; if (credential.id === status.activeCredentialId) item.classList.add('active'); const select = document.createElement('button'); select.type='button'; select.className='session-key-select'; select.onclick=()=>switchCredential(credential.id); select.innerHTML=`<i data-lucide="${credential.builtIn?'network':'key'}"></i><span><strong>${escapeMarkup(credential.name)}</strong><small>${escapeMarkup([credential.endpointUrl,credential.region].filter(Boolean).join(' · '))}</small></span>`; item.appendChild(select); if (status.allowUsersToRevealKeys) { const reveal = document.createElement('button'); reveal.type='button'; reveal.className='btn-icon shrink-0'; reveal.title='Reveal access and secret key'; reveal.setAttribute('aria-label', reveal.title); reveal.innerHTML='<i data-lucide="eye"></i>'; reveal.onclick=()=>revealS3Credential(credential.id); item.appendChild(reveal); } list.appendChild(item); });
  document.getElementById('s3SessionKeys')?.classList.toggle('hidden', !status.credentials.length); document.getElementById('s3SessionNoKeys')?.classList.toggle('hidden', !!status.credentials.length); document.getElementById('s3SessionOwn')?.classList.toggle('hidden', !status.allowOwnCredentials); document.getElementById('s3SessionClose')?.classList.toggle('hidden', !dismissible); hideS3SessionError(); openDialog('s3SessionModal'); refreshIcons();
}
async function switchCredential(credentialId, options={}) { try { const response=await apiFetch('/api/s3/session',{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify({credentialId})}); if(response.ok)return location.reload(); const message=await getResponseErrorMessage(response,'Unable to select that S3 key'); options.silent?showToast(message,'danger'):showS3SessionError(message); } catch(error){showS3SessionError(error.message||'Unable to select that S3 key');} }
async function revealS3Credential(credentialId) { try { const response=await apiFetch(`/api/s3/session/credentials/${encodeURIComponent(credentialId)}/reveal`,{headers:{Accept:'application/json'}}); if(!response.ok) return showS3SessionError(await getResponseErrorMessage(response,'Unable to reveal that S3 key')); const credential=await response.json(); document.getElementById('s3RevealKeyTitle').textContent=`Keys for ${credential.name}`; document.getElementById('s3RevealedAccessKey').value=credential.accessKey; document.getElementById('s3RevealedSecretKey').value=credential.secretKey; openDialog('s3RevealKeyModal'); refreshIcons(); } catch(error){showS3SessionError(error.message||'Unable to reveal that S3 key');} }
window.closeS3RevealKeyDialog = () => closeDialog('s3RevealKeyModal');
window.copyS3RevealedKey = async id => { const input=document.getElementById(id); input.select(); input.setSelectionRange(0,input.value.length); try { await navigator.clipboard.writeText(input.value); showToast('Copied to clipboard','success'); } catch (_) { const copied=document.execCommand('copy'); showToast(copied?'Copied to clipboard':'Could not copy to clipboard',copied?'success':'danger'); } };
document.getElementById('s3RevealKeyModal')?.addEventListener('s3webui:dialogclosed', () => { document.getElementById('s3RevealedAccessKey').value=''; document.getElementById('s3RevealedSecretKey').value=''; });
window.useOwnCredentials = async () => { const payload={accessKey:s3AccessKey.value.trim(),secretKey:s3SecretKey.value.trim(),endpointUrl:s3EndpointUrl.value.trim(),region:s3Region.value.trim(),insecureSkipTlsVerify:s3InsecureTls.checked}; hideS3SessionError(); try { const response=await apiFetch('/api/s3/session',{method:'POST',headers:{'Content-Type':'application/json',Accept:'application/json'},body:JSON.stringify(payload)}); if(response.ok)return location.reload(); showS3SessionError(await getResponseErrorMessage(response,'Unable to save these credentials')); } catch(error){showS3SessionError(error.message||'Unable to save these credentials');} };
function showS3SessionError(message){const error=document.getElementById('s3SessionError');if(error){error.textContent=message;error.classList.remove('hidden')}else showToast(message,'danger')}
function hideS3SessionError(){const error=document.getElementById('s3SessionError');if(error){error.textContent='';error.classList.add('hidden')}}
function escapeMarkup(value){return String(value||'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
window.getResponseErrorMessage = async (response, fallback = 'Request failed') => { const text = await response.text(); if (!text) return fallback; try { const data = JSON.parse(text); return data.message || data.error || text; } catch { return text; } };

function createBucketSearchResultRow(result) {
  const row = document.createElement('tr');
  const relativePath = relativeBucketResultPath(result.key, window.PREFIX);
  const relativeParent = relativeBucketResultParent(result.key, window.PREFIX);
  const isFolder = result.type === 'FOLDER';
  row.dataset.subfolderSearchResult = 'true';
  row.dataset.objectName = relativePath;
  row.dataset.objectKey = result.key;
  row.dataset.objectSize = isFolder ? '0' : String(result.size ?? 0);
  row.dataset.objectModified = isFolder || !result.lastModified
    ? ''
    : String(new Date(result.lastModified).getTime());
  row.dataset.objectDirectory = String(isFolder);
  row.className = 'object-subfolder-result';

  const selectionCell = document.createElement('td');
  if (!isFolder) {
    const checkbox = document.createElement('input');
    checkbox.className = 'obj-check';
    checkbox.type = 'checkbox';
    checkbox.value = result.key;
    checkbox.addEventListener('change', () => syncObjectSelectAll());
    selectionCell.appendChild(checkbox);
  }

  const nameCell = document.createElement('td');
  const content = isFolder ? document.createElement('a') : document.createElement('div');
  content.className = isFolder
    ? 'flex min-w-0 items-center gap-2 font-medium hover:text-brand-700'
    : 'flex min-w-0 items-center gap-2';
  if (isFolder) content.href = appUrl(globalSearchResultPath(result));
  const icon = document.createElement('i');
  icon.setAttribute('data-lucide', isFolder ? 'folder' : 'file');
  if (!isFolder) icon.className = 'shrink-0 text-brand-600';
  const label = document.createElement('span');
  label.className = 'min-w-0 break-all';
  if (relativeParent) {
    const path = document.createElement('span');
    path.className = 'object-subfolder-path';
    path.textContent = relativeParent;
    label.appendChild(path);
  }
  const name = document.createElement('span');
  name.textContent = result.name;
  label.appendChild(name);
  content.append(icon, label);
  nameCell.appendChild(content);

  const sizeCell = document.createElement('td');
  sizeCell.className = 'text-slate-500';
  sizeCell.textContent = isFolder ? '' : formatObjectSize(result.size);
  const modifiedCell = document.createElement('td');
  modifiedCell.className = 'text-slate-500';
  modifiedCell.textContent = isFolder
    ? ''
    : result.lastModified
      ? new Date(result.lastModified).toLocaleString([], {
          year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit'
        })
      : '-';

  const actionsCell = document.createElement('td');
  if (!isFolder) {
    const actions = document.createElement('div');
    actions.className = 'flex gap-1';
    const download = document.createElement('a');
    download.className = 'btn-icon';
    download.title = 'Download';
    download.setAttribute('aria-label', `Download ${result.name}`);
    download.href = appUrl(`/api/buckets/${encodeURIComponent(result.bucket)}/objects/download?${new URLSearchParams({ key: result.key })}`);
    const downloadIcon = document.createElement('i');
    downloadIcon.setAttribute('data-lucide', 'download');
    download.appendChild(downloadIcon);

    const rename = document.createElement('button');
    rename.type = 'button';
    rename.className = 'btn-icon';
    rename.title = 'Rename';
    rename.setAttribute('aria-label', `Rename ${result.name}`);
    rename.addEventListener('click', () => window.openRenameModal(result.key, result.name));
    const renameIcon = document.createElement('i');
    renameIcon.setAttribute('data-lucide', 'pencil');
    rename.appendChild(renameIcon);

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'btn-icon text-bad-500';
    remove.title = 'Delete';
    remove.setAttribute('aria-label', `Delete ${result.name}`);
    remove.addEventListener('click', () => window.confirmDeleteObject(result.key));
    const removeIcon = document.createElement('i');
    removeIcon.setAttribute('data-lucide', 'trash-2');
    remove.appendChild(removeIcon);
    actions.append(download, rename, remove);
    actionsCell.appendChild(actions);
  }

  row.append(selectionCell, nameCell, sizeCell, modifiedCell, actionsCell);
  return row;
}

function initBucketSubfolderSearch() {
  const input = document.getElementById('objectSearch');
  const checkbox = document.getElementById('searchSubfolders');
  const status = document.getElementById('objectSubfolderSearchStatus');
  const body = document.getElementById('objectTableBody');
  if (!input || !checkbox || !status || !body || !window.BUCKET) return;
  let debounceTimer = null;
  let requestController = null;

  const setStatus = (message, isError = false) => {
    status.textContent = message;
    status.classList.toggle('hidden', !message);
    status.classList.toggle('text-bad-500', isError);
    status.classList.toggle('text-slate-500', !isError);
  };
  const removeTemporaryRows = () => {
    const rows = [...body.querySelectorAll('[data-subfolder-search-result]')];
    rows.forEach(row => row.remove());
    if (rows.length) objectTable.syncRows();
  };
  const stopPendingSearch = () => {
    clearTimeout(debounceTimer);
    requestController?.abort();
    requestController = null;
  };
  const renderResults = data => {
    const existingKeys = new Set([...body.rows]
      .filter(row => !row.dataset.subfolderSearchResult)
      .map(row => row.dataset.objectKey));
    const fragment = document.createDocumentFragment();
    let added = 0;
    data.results.forEach(result => {
      if (existingKeys.has(result.key)) return;
      existingKeys.add(result.key);
      fragment.appendChild(createBucketSearchResultRow(result));
      added += 1;
    });
    body.appendChild(fragment);
    objectTable.syncRows();
    refreshIcons();
    setStatus(`${added} subfolder result${added === 1 ? '' : 's'} added`);
  };
  const search = async query => {
    requestController = new AbortController();
    const activeController = requestController;
    setStatus('Searching subfolders…');
    try {
      const response = await apiFetch(bucketSearchApiPath(window.BUCKET, window.PREFIX, query), {
        headers: { Accept: 'application/json' },
        signal: activeController.signal,
      });
      if (!response.ok) throw new Error(await getResponseErrorMessage(response, 'Subfolder search failed'));
      const data = await response.json();
      if (activeController !== requestController || !checkbox.checked || input.value.trim() !== query) return;
      renderResults(data);
    } catch (error) {
      if (error.name !== 'AbortError') setStatus(error.message || 'Subfolder search failed.', true);
    }
  };
  const scheduleSearch = () => {
    stopPendingSearch();
    removeTemporaryRows();
    const query = input.value.trim();
    if (!checkbox.checked) {
      setStatus('');
      return;
    }
    if (query.length < 2) {
      setStatus(query.length ? 'Type one more character to search subfolders.' : 'Type at least 2 characters to search subfolders.');
      return;
    }
    debounceTimer = setTimeout(() => search(query), 300);
  };

  input.addEventListener('input', scheduleSearch);
  checkbox.addEventListener('change', scheduleSearch);
}
document.addEventListener('DOMContentLoaded', initBucketSubfolderSearch);

function initGlobalSearch() {
  const root = document.querySelector('[data-global-search]');
  if (!root) return;
  const form = root.querySelector('form');
  const input = root.querySelector('[role="combobox"]');
  const suggestions = root.querySelector('[role="listbox"]');
  const live = root.querySelector('[data-global-search-live]');
  let debounceTimer = null;
  let requestController = null;
  let activeIndex = -1;

  const selectableItems = () => [...suggestions.querySelectorAll('[data-search-selectable]')];
  const announce = message => { live.textContent = message; };
  const open = () => {
    suggestions.classList.remove('hidden');
    input.setAttribute('aria-expanded', 'true');
  };
  const close = () => {
    suggestions.classList.add('hidden');
    input.setAttribute('aria-expanded', 'false');
    input.removeAttribute('aria-activedescendant');
    activeIndex = -1;
  };
  const clear = () => {
    suggestions.replaceChildren();
    activeIndex = -1;
  };
  const renderMessage = (message, className = '') => {
    clear();
    const row = document.createElement('div');
    row.className = `global-search-message ${className}`.trim();
    row.setAttribute('role', 'status');
    row.textContent = message;
    suggestions.appendChild(row);
    open();
    announce(message);
  };
  const resultUrl = result => appUrl(globalSearchResultPath(result));
  const appendHighlighted = (element, value, query) => {
    const index = value.toLocaleLowerCase().indexOf(query.toLocaleLowerCase());
    if (index < 0) {
      element.textContent = value;
      return;
    }
    element.append(
      document.createTextNode(value.slice(0, index)),
      Object.assign(document.createElement('mark'), { textContent: value.slice(index, index + query.length) }),
      document.createTextNode(value.slice(index + query.length)),
    );
  };
  const updateActiveItem = nextIndex => {
    const items = selectableItems();
    if (!items.length) return;
    activeIndex = (nextIndex + items.length) % items.length;
    items.forEach((item, index) => {
      const active = index === activeIndex;
      item.classList.toggle('active', active);
      item.setAttribute('aria-selected', String(active));
    });
    input.setAttribute('aria-activedescendant', items[activeIndex].id);
    items[activeIndex].scrollIntoView({ block: 'nearest' });
  };
  const addResult = (result, query, index) => {
    const link = document.createElement('a');
    link.id = `globalSearchOption${index}`;
    link.className = 'global-search-option';
    link.href = resultUrl(result);
    link.setAttribute('role', 'option');
    link.setAttribute('aria-selected', 'false');
    link.dataset.searchSelectable = 'true';

    const iconWrap = document.createElement('span');
    iconWrap.className = 'global-search-option-icon';
    const icon = document.createElement('i');
    icon.setAttribute('data-lucide', result.type === 'FOLDER' ? 'folder' : 'file');
    iconWrap.appendChild(icon);

    const content = document.createElement('span');
    content.className = 'min-w-0 flex-1';
    const name = document.createElement('span');
    name.className = 'global-search-option-name';
    appendHighlighted(name, result.name, query);
    const path = document.createElement('span');
    path.className = 'global-search-option-path';
    path.textContent = result.parentPrefix ? `${result.bucket} / ${result.parentPrefix}` : result.bucket;
    content.append(name, path);

    const arrow = document.createElement('i');
    arrow.setAttribute('data-lucide', 'arrow-up-right');
    arrow.className = 'size-4 shrink-0 text-slate-500';
    link.append(iconWrap, content, arrow);
    link.addEventListener('mouseenter', () => updateActiveItem(selectableItems().indexOf(link)));
    suggestions.appendChild(link);
  };
  const renderResults = (data, query) => {
    clear();
    if (data.incomplete) {
      const warning = document.createElement('div');
      warning.className = 'global-search-warning';
      warning.textContent = `${data.failedBuckets.length} bucket${data.failedBuckets.length === 1 ? '' : 's'} could not be searched.`;
      suggestions.appendChild(warning);
    }
    if (!data.results.length) {
      const empty = document.createElement('div');
      empty.className = 'global-search-message';
      empty.textContent = 'No matching files or folders.';
      suggestions.appendChild(empty);
    } else {
      data.results.forEach((result, index) => addResult(result, query, index));
    }
    if (data.hasMore) {
      const more = document.createElement('a');
      more.id = 'globalSearchMoreResults';
      more.className = 'global-search-more';
      more.href = appUrl(globalSearchPagePath(query));
      more.setAttribute('role', 'option');
      more.setAttribute('aria-selected', 'false');
      more.dataset.searchSelectable = 'true';
      const label = document.createElement('span');
      label.textContent = `More results (${data.total})`;
      const icon = document.createElement('i');
      icon.setAttribute('data-lucide', 'arrow-up-right');
      more.append(label, icon);
      more.addEventListener('mouseenter', () => updateActiveItem(selectableItems().indexOf(more)));
      suggestions.appendChild(more);
    }
    open();
    announce(`${data.total} result${data.total === 1 ? '' : 's'} found.`);
    refreshIcons();
  };
  const search = async query => {
    requestController?.abort();
    requestController = new AbortController();
    renderMessage('Searching all buckets…', 'is-loading');
    try {
      const response = await apiFetch(`/api/search?${new URLSearchParams({ q: query, limit: '5' })}`, {
        headers: { Accept: 'application/json' },
        signal: requestController.signal,
      });
      if (!response.ok) throw new Error(await getResponseErrorMessage(response, 'Search failed'));
      renderResults(await response.json(), query);
    } catch (error) {
      if (error.name !== 'AbortError') renderMessage(error.message || 'Search failed.', 'is-error');
    }
  };

  input.addEventListener('input', () => {
    input.setCustomValidity('');
    clearTimeout(debounceTimer);
    requestController?.abort();
    const query = input.value.trim();
    if (query.length < 2) {
      close();
      announce(query.length ? 'Type one more character to search.' : '');
      return;
    }
    debounceTimer = setTimeout(() => search(query), 300);
  });
  input.addEventListener('focus', () => {
    if (suggestions.childElementCount && input.value.trim().length >= 2) open();
  });
  input.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      close();
      return;
    }
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      const items = selectableItems();
      if (!items.length) return;
      event.preventDefault();
      updateActiveItem(activeIndex + (event.key === 'ArrowDown' ? 1 : -1));
      return;
    }
    if (event.key === 'Enter' && activeIndex >= 0) {
      const target = selectableItems()[activeIndex];
      if (target) {
        event.preventDefault();
        window.location.href = target.href;
      }
    }
  });
  form.addEventListener('submit', event => {
    if (input.value.trim().length < 2) {
      event.preventDefault();
      input.setCustomValidity('Enter at least 2 characters.');
      input.reportValidity();
    } else {
      input.setCustomValidity('');
    }
  });
  document.addEventListener('click', event => {
    if (!root.contains(event.target)) close();
  });
}
document.addEventListener('DOMContentLoaded', initGlobalSearch);

window.showToast = (message, type = 'info') => { const container = document.getElementById('toastContainer'); if (!container) return; const toast = document.createElement('div'); toast.className = `toast ${type}`; toast.setAttribute('role', 'status'); toast.innerHTML = `<span>${String(message || '').replace(/[<>"'&]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}</span><button class="btn-icon shrink-0" aria-label="Dismiss"><i data-lucide="x"></i></button>`; toast.querySelector('button').onclick = () => toast.remove(); container.appendChild(toast); renderIcons(); setTimeout(() => toast.remove(), 4500); };

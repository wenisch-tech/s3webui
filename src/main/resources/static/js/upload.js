/**
 * Client-side multipart upload with progress tracking and ETA.
 *
 * Flow:
 *   1. Initiate multipart upload   POST /api/buckets/{b}/multipart/initiate
 *   2. For each part:
 *      a. Get presigned URL         GET  /api/buckets/{b}/multipart/presign
 *      b. Upload part directly to S3 via XHR (PUT with presigned URL)
 *   3. Complete multipart upload   POST /api/buckets/{b}/multipart/complete
 *
 * Falls back to simple PUT upload for files < 5 MB.
 */

const PART_SIZE = 5 * 1024 * 1024; // 5 MB minimum S3 part size

let _pendingFiles = [];

// ── File selection ───────────────────────────────────────────────────────────

function handleFileSelect(input) {
    addFiles(Array.from(input.files));
    input.value = '';
}

function handleDrop(event) {
    event.preventDefault();
    event.currentTarget.classList.remove('dragover');
    addFiles(Array.from(event.dataTransfer.files));
}

function addFiles(files) {
    files.forEach(file => {
        if (_pendingFiles.find(f => f.name === file.name && f.size === file.size)) return;
        _pendingFiles.push(file);
    });
    renderFileList();
}

function removeFile(index) {
    _pendingFiles.splice(index, 1);
    renderFileList();
}

function renderFileList() {
    const container = document.getElementById('fileList');
    if (!container) return;
    if (_pendingFiles.length === 0) {
        container.innerHTML = '';
        return;
    }
    container.innerHTML = _pendingFiles.map((f, i) => `
        <div class="upload-item d-flex align-items-start gap-2" id="upload-item-${i}">
            <i class="bi bi-file-earmark flex-shrink-0 mt-1 text-muted"></i>
            <div class="flex-grow-1 min-w-0">
                <div class="d-flex justify-content-between align-items-center">
                    <span class="text-truncate small fw-semibold">${escapeHtml(f.name)}</span>
                    <span class="text-muted small ms-2 flex-shrink-0">${formatBytes(f.size)}</span>
                </div>
                <div class="progress d-none" id="progress-bar-wrap-${i}">
                    <div class="progress-bar progress-bar-striped progress-bar-animated"
                         id="progress-bar-${i}" style="width:0%"></div>
                </div>
                <div class="small text-muted d-none" id="progress-text-${i}"></div>
            </div>
            <button class="btn btn-sm btn-link text-danger p-0 flex-shrink-0"
                    onclick="removeFile(${i})" id="remove-btn-${i}">
                <i class="bi bi-x-lg"></i>
            </button>
        </div>`).join('');
}

function resetUploadModal() {
    _pendingFiles = [];
    renderFileList();
    document.getElementById('overallProgressArea').classList.add('d-none');
    document.getElementById('overallProgressBar').style.width = '0%';
    document.getElementById('overallStatus').textContent = 'Uploading…';
    document.getElementById('overallEta').textContent = '';
    document.getElementById('startUploadBtn').disabled = false;
    document.getElementById('uploadCloseBtn').disabled = false;
}

// ── Upload orchestration ─────────────────────────────────────────────────────

async function startUpload() {
    if (_pendingFiles.length === 0) {
        showToast('Please select at least one file.', 'warning');
        return;
    }

    document.getElementById('startUploadBtn').disabled = true;
    document.getElementById('uploadCloseBtn').disabled = true;
    document.getElementById('overallProgressArea').classList.remove('d-none');

    const totalBytes = _pendingFiles.reduce((s, f) => s + f.size, 0);
    let uploadedBytes = 0;
    const startTime = Date.now();

    for (let i = 0; i < _pendingFiles.length; i++) {
        const file = _pendingFiles[i];
        const progressBar = document.getElementById(`progress-bar-${i}`);
        const progressWrap = document.getElementById(`progress-bar-wrap-${i}`);
        const progressText = document.getElementById(`progress-text-${i}`);
        const removeBtn = document.getElementById(`remove-btn-${i}`);

        if (progressWrap) progressWrap.classList.remove('d-none');
        if (progressText) progressText.classList.remove('d-none');
        if (removeBtn) removeBtn.remove();

        const key = PREFIX ? (PREFIX.endsWith('/') ? PREFIX : PREFIX + '/') + file.name : file.name;

        const onProgress = (loaded) => {
            const pct = Math.round((loaded / file.size) * 100);
            if (progressBar) progressBar.style.width = pct + '%';
            if (progressText) progressText.textContent = `${formatBytes(loaded)} / ${formatBytes(file.size)}`;

            const totalUploaded = uploadedBytes + loaded;
            const overallPct = Math.round((totalUploaded / totalBytes) * 100);
            const elapsed = (Date.now() - startTime) / 1000;
            const speed = totalUploaded / elapsed;
            const remaining = (totalBytes - totalUploaded) / (speed || 1);

            document.getElementById('overallProgressBar').style.width = overallPct + '%';
            document.getElementById('overallStatus').textContent =
                `${overallPct}% — ${formatBytes(totalUploaded)} / ${formatBytes(totalBytes)}`;
            document.getElementById('overallEta').textContent =
                remaining > 0 ? `ETA: ${formatDuration(remaining)}` : '';
        };

        try {
            if (file.size <= PART_SIZE) {
                await simpleUpload(file, BUCKET, key, onProgress);
            } else {
                await multipartUpload(file, BUCKET, key, onProgress);
            }
            uploadedBytes += file.size;
            if (progressBar) {
                progressBar.classList.remove('progress-bar-animated', 'progress-bar-striped');
                progressBar.classList.add('bg-success');
                progressBar.style.width = '100%';
            }
            if (progressText) progressText.textContent = '✓ Done';
        } catch (err) {
            if (progressBar) progressBar.classList.add('bg-danger');
            if (progressText) progressText.textContent = '✗ ' + err.message;
            showToast(`Failed to upload ${file.name}: ${err.message}`, 'danger');
        }
    }

    document.getElementById('overallStatus').textContent = 'Upload complete!';
    document.getElementById('overallEta').textContent = '';
    document.getElementById('uploadCloseBtn').disabled = false;
    showToast('Upload complete!', 'success');
    setTimeout(() => location.reload(), 1500);
}

// ── Simple upload (file ≤ 5 MB, goes through backend) ───────────────────────

function simpleUpload(file, bucket, key, onProgress) {
    return new Promise((resolve, reject) => {
        const formData = new FormData();
        formData.append('file', file, file.name);

        const xhr = new XMLHttpRequest();
        xhr.upload.addEventListener('progress', e => {
            if (e.lengthComputable) onProgress(e.loaded);
        });
        xhr.addEventListener('load', () => {
            if (xhr.status >= 200 && xhr.status < 300) resolve();
            else reject(new Error(`HTTP ${xhr.status}: ${xhr.responseText}`));
        });
        xhr.addEventListener('error', () => reject(new Error('Network error')));
        xhr.open('POST', `/api/buckets/${encodeURIComponent(bucket)}/objects/upload` +
                         `?prefix=${encodeURIComponent(PREFIX || '')}`);
        xhr.send(formData);
    });
}

// ── Multipart upload (client-side, presigned URLs) ───────────────────────────

async function multipartUpload(file, bucket, key, onProgress) {
    // 1. Initiate
    const initRes = await fetch(
        `/api/buckets/${encodeURIComponent(bucket)}/multipart/initiate` +
        `?key=${encodeURIComponent(key)}&contentType=${encodeURIComponent(file.type || 'application/octet-stream')}`,
        {method: 'POST'});
    if (!initRes.ok) throw new Error('Failed to initiate multipart upload');
    const {uploadId} = await initRes.json();

    const parts = Math.ceil(file.size / PART_SIZE);
    const completedParts = [];
    let loadedTotal = 0;

    // 2. Upload each part
    for (let p = 1; p <= parts; p++) {
        const start = (p - 1) * PART_SIZE;
        const end = Math.min(p * PART_SIZE, file.size);
        const blob = file.slice(start, end);

        // Get presigned URL
        const presignRes = await fetch(
            `/api/buckets/${encodeURIComponent(bucket)}/multipart/presign` +
            `?key=${encodeURIComponent(key)}&uploadId=${encodeURIComponent(uploadId)}&partNumber=${p}`);
        if (!presignRes.ok) throw new Error(`Failed to get presigned URL for part ${p}`);
        const {url} = await presignRes.json();

        // Upload part via XHR (so we get progress events)
        const eTag = await uploadPart(url, blob, loaded => {
            onProgress(loadedTotal + loaded);
        });
        loadedTotal += blob.size;
        completedParts.push({partNumber: p, eTag});
    }

    // 3. Complete
    const completeRes = await fetch(
        `/api/buckets/${encodeURIComponent(bucket)}/multipart/complete` +
        `?key=${encodeURIComponent(key)}`,
        {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({uploadId, parts: completedParts})
        });
    if (!completeRes.ok) throw new Error('Failed to complete multipart upload');
}

function uploadPart(presignedUrl, blob, onProgress) {
    return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        xhr.upload.addEventListener('progress', e => {
            if (e.lengthComputable) onProgress(e.loaded);
        });
        xhr.addEventListener('load', () => {
            if (xhr.status >= 200 && xhr.status < 300) {
                const eTag = xhr.getResponseHeader('ETag') || '';
                resolve(eTag.replace(/"/g, ''));
            } else {
                reject(new Error(`HTTP ${xhr.status}`));
            }
        });
        xhr.addEventListener('error', () => reject(new Error('Network error during part upload')));
        xhr.open('PUT', presignedUrl);
        xhr.send(blob);
    });
}

// ── Utilities ────────────────────────────────────────────────────────────────

function formatBytes(bytes) {
    if (bytes < 1024) return bytes + ' B';
    const kb = bytes / 1024;
    if (kb < 1024) return kb.toFixed(1) + ' KB';
    const mb = kb / 1024;
    if (mb < 1024) return mb.toFixed(1) + ' MB';
    return (mb / 1024).toFixed(2) + ' GB';
}

function formatDuration(seconds) {
    if (seconds < 60) return Math.ceil(seconds) + 's';
    const m = Math.floor(seconds / 60);
    const s = Math.ceil(seconds % 60);
    return `${m}m ${s}s`;
}

function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * @fileoverview WebSocket Client for ServerAccessHub - Personal Cloud Hub
 * 
 * Manages file browser, WebSocket communication, navigation, uploads, and UI interactions.
 * Provides real-time file/folder operations via WebSocket protocol.
 * 
 * @author ServerAccessHub Team
 * @version 2.0.0
 * @license MIT
 * 
 * Features:
 * - Real-time file listing via WebSocket
 * - File upload (single files and folders via webkitdirectory)
 * - Download, rename, and delete operations
 * - Autocomplete path suggestions
 * - Browser history navigation support
 * - Dark theme UI with context menus
 * - File-type specific icons
 */

// ============================================================================
// DOM Element References
// ============================================================================

/** @type {WebSocket|null} Active WebSocket connection to server */
let socket;

/** @type {HTMLElement} Container element for file/folder list */
const fileListElement = document.getElementById('fileList');

/** @type {HTMLInputElement} Path input field for navigation */
const pathInput = document.getElementById('currentPath');

/** @type {HTMLElement} Dropdown container for autocomplete suggestions */
const suggestionList = document.getElementById('suggestionList');

/** @type {HTMLElement} Modal overlay background (click to close) */
const modalOverlay = document.getElementById('modalOverlay');

/** @type {HTMLElement} Modal dialog container */
const modalContainer = document.getElementById('modalContainer');

/** @type {HTMLInputElement} Text input inside modal */
const modalInput = document.getElementById('modalInput');

/** @type {HTMLElement} Icon display element in modal header */
const modalIcon = document.getElementById('modalIcon');

/** @type {HTMLButtonElement} Confirm/OK button in modal */
const modalConfirmBtn = document.getElementById('modalConfirmBtn');

// Upload UI Elements
/** @type {HTMLInputElement} Hidden file input for multi-file selection */
const filePicker = document.getElementById('filePicker');

/** @type {HTMLInputElement} Hidden folder input (webkitdirectory attribute) */
const folderPicker = document.getElementById('folderPicker');

/** @type {HTMLButtonElement} Floating Action Button for upload menu */
const fabUpload = document.getElementById('fabUpload');

/** @type {HTMLElement} Upload menu container (shows on FAB click) */
const fabMenu = document.getElementById('fabMenu');

/** @type {HTMLElement} Invisible overlay to detect outside clicks */
const fabOverlay = document.getElementById('fabOverlay');

/** @type {HTMLButtonElement} "Upload Files" menu option */
const fabUploadFiles = document.getElementById('fabUploadFiles');

/** @type {HTMLButtonElement} "Upload Folder" menu option */
const fabUploadFolder = document.getElementById('fabUploadFolder');

/** @type {HTMLElement} Toast notification for upload progress */
const uploadToast = document.getElementById('uploadToast');

// ============================================================================
// Application State
// ============================================================================

/** @type {boolean} True when user is typing in path input (prevents auto-update) */
let isTyping = false;

/** @type {number} Currently focused suggestion index for keyboard navigation (-1 = none) */
let focusedIndex = -1;

/** @type {string} Last known current directory path from server */
let lastPathFromServer = "/";

/** @type {Array<Function>} Queue of Promise resolvers waiting for pwd response */
const pendingPwdResolvers = [];

/** @type {number} Count of files currently being uploaded */
let uploadInProgress = 0;

/** Connect to WebSocket and restore state from URL hash */
function connect() {
    socket = new WebSocket("wss://" + window.location.host);
    socket.onopen = () => {
        const hash = window.location.hash.replace('#', '');
        hash ? sendCommand("goto " + hash) : refreshUI();
    };
    socket.onmessage = (e) => {
        const msg = e.data;
        console.log("WS received:", msg.substring(0, 100)); // Debug log
        if (msg.startsWith("PATH: ")) {
            // Update current directory path display
            lastPathFromServer = msg.replace("PATH: ", "");
            if (!isTyping) {
                pathInput.value = lastPathFromServer.endsWith("/") ? lastPathFromServer : lastPathFromServer + "/";
            }

            // Resolve any pending pwd() requests.
            while (pendingPwdResolvers.length > 0) {
                try { pendingPwdResolvers.shift()(lastPathFromServer); } catch (_) {}
            }
        } 
        else if (msg.startsWith("LIST: ")) renderFileList(msg.replace("LIST: ", ""));
        else if (msg.startsWith("LIST_CHUNK: ")) {
            // Streaming: append chunk of files to list
            console.log("Received chunk, length:", msg.length);
            appendFileListChunk(msg.replace("LIST_CHUNK: ", ""));
        }
        else if (msg.startsWith("LIST_END: ")) {
            // Streaming complete - hide loading indicator
            console.log("Received LIST_END");
            hideListLoading();
        }
        else if (msg.startsWith("ERR: ")) {
            // Error received - hide loading and show error
            console.log("Error:", msg);
            hideListLoading();
            showToast(msg.replace("ERR: ", ""), 3000);
        }
        else if (msg.startsWith("SUGGEST: ")) renderSuggestions(msg.replace("SUGGEST: ", ""));
        else if (msg === "RES: OK") { isTyping = false; refreshUI(); }
    };
    socket.onclose = () => setTimeout(connect, 2000); // Reconnect on disconnect
}


/** Request current directory from server (WebSocket pwd). */
function requestPwd(timeoutMs = 1200) {
    return new Promise((resolve) => {
        if (!socket || socket.readyState !== WebSocket.OPEN) {
            resolve(lastPathFromServer);
            return;
        }

        const timer = setTimeout(() => {
            // If server doesn't respond quickly, fall back to last known path.
            resolve(lastPathFromServer);
        }, timeoutMs);

        pendingPwdResolvers.push((path) => {
            clearTimeout(timer);
            resolve(path);
        });

        sendCommand("pwd");
    });
}

function showToast(text, ms = 2200) {
    if (!uploadToast) return;
    uploadToast.textContent = text;
    uploadToast.style.display = "block";
    if (ms > 0) setTimeout(() => { uploadToast.style.display = "none"; }, ms);
}

function toggleFabMenu(show) {
    const shouldShow = (typeof show === 'boolean') ? show : (fabMenu.style.display !== 'block');
    fabMenu.style.display = shouldShow ? 'block' : 'none';
    fabOverlay.style.display = shouldShow ? 'block' : 'none';
}

fabUpload?.addEventListener('click', async () => {
    // Ask server for current directory so uploads go to the right place.
    // This is safe to do on every open; it also refreshes lastPathFromServer.
    await requestPwd();
    toggleFabMenu(true);
});

fabOverlay?.addEventListener('click', () => toggleFabMenu(false));

fabUploadFiles?.addEventListener('click', () => {
    toggleFabMenu(false);
    if (!filePicker) return;
    filePicker.value = "";
    filePicker.click();
});

fabUploadFolder?.addEventListener('click', () => {
    toggleFabMenu(false);
    if (!folderPicker) return;

    // Folder picking is Chromium-first. If not supported (or on iOS), fall back to file picker.
    const ua = navigator.userAgent || "";
    const isIOS = /iPhone|iPad|iPod/i.test(ua);
    const supportsFolder = !isIOS && ('webkitdirectory' in folderPicker || folderPicker.hasAttribute('webkitdirectory'));

    if (!supportsFolder) {
        showToast("Folder upload not supported here. Pick files instead.");
        filePicker.value = "";
        filePicker.click();
        return;
    }

    folderPicker.value = "";
    folderPicker.click();
});

filePicker?.addEventListener('change', async (e) => {
    const files = Array.from(e.target.files || []);
    if (files.length === 0) return;
    const base = await requestPwd();
    startUploads(files, base);
});

folderPicker?.addEventListener('change', async (e) => {
    const files = Array.from(e.target.files || []);
    if (files.length === 0) return;
    const base = await requestPwd();
    startUploads(files, base);
});

function normalizeBaseDirForUpload(pwdPath) {
    // pwdPath looks like "/" or "/folder/sub"
    let p = (pwdPath || "/").trim();
    if (p === "") p = "/";
    if (!p.startsWith("/")) p = "/" + p;
    if (p !== "/" && p.endsWith("/")) p = p.slice(0, -1);

    // Server expects path relative to cloudStorage; it will strip leading '/'
    // We build relative string without leading slash.
    return p === "/" ? "" : p.slice(1) + "/";
}

function safeJoinPath(baseRel, relFilePath) {
    let rel = (relFilePath || "").replace(/\\/g, '/');
    while (rel.startsWith('/')) rel = rel.slice(1);
    // Prevent accidental traversal coming from browser path fields.
    rel = rel.split('/').filter(part => part !== "" && part !== "." && part !== "..").join('/');
    return baseRel + rel;
}

async function startUploads(files, pwdPath) {
    const baseRel = normalizeBaseDirForUpload(pwdPath);
    const tasks = files.map(f => {
        const rel = f.webkitRelativePath && f.webkitRelativePath.length > 0 ? f.webkitRelativePath : f.name;
        return { file: f, relPath: rel };
    });

    showToast(`Uploading ${tasks.length} item(s) to /${baseRel}`);
    uploadInProgress += tasks.length;

    // Limit concurrency so the UI stays responsive.
    const concurrency = 3;
    let done = 0;
    let failed = 0;

    const queue = tasks.slice();
    const workers = Array.from({ length: Math.min(concurrency, queue.length) }, async () => {
        while (queue.length > 0) {
            const t = queue.shift();
            const fullRelPath = safeJoinPath(baseRel, t.relPath);
            const ok = await uploadOneFile(fullRelPath, t.file);
            done++;
            if (!ok) failed++;
            showToast(`Uploading… ${done}/${tasks.length}${failed ? ` (failed: ${failed})` : ""}`, 0);
        }
    });

    await Promise.all(workers);
    uploadInProgress = Math.max(0, uploadInProgress - tasks.length);

    if (failed === 0) showToast(`Upload complete: ${tasks.length} item(s)`);
    else showToast(`Upload finished: ${tasks.length - failed}/${tasks.length} succeeded`);

    // Refresh file list after uploads.
    refreshUI();
}

function uploadOneFile(relPath, file) {
    return new Promise((resolve) => {
        const url = `/upload?path=${encodeURIComponent(relPath)}`;
        const xhr = new XMLHttpRequest();
        xhr.open('PUT', url, true);

        xhr.onload = () => {
            resolve(xhr.status >= 200 && xhr.status < 300);
        };
        xhr.onerror = () => resolve(false);
        xhr.onabort = () => resolve(false);

        xhr.send(file);
    });
}

/** Navigate to folder (relative or absolute) and update browser history */
function navigate(folderOrPath, isAbsolute = false) {
    isTyping = false;
    suggestionList.style.display = "none";
    
    // Show loading indicator immediately for responsive feel
    showListLoading();
    
    if (isAbsolute) {
        // Jump to absolute path
        sendCommand("goto " + folderOrPath);
    } else {
        // Change to subdirectory (relative)
        sendCommand("cd " + folderOrPath);
    }
    // Save to browser history for back button
    setTimeout(() => {
        history.pushState({ path: lastPathFromServer }, '', '#' + lastPathFromServer);
    }, 200);
}

/** Handle path input and show autocomplete suggestions */
pathInput.addEventListener('input', (e) => {
    isTyping = true;
    const val = e.target.value;
    // Extract last folder name for autocompletion
    const query = val.split('/').filter(x => x.length > 0).pop() || "";
    if (query.length > 0) sendCommand(`suggest ${query}`);
    else suggestionList.style.display = "none";
});

/** Handle keyboard navigation in path input (arrows and Enter) */
pathInput.addEventListener('keydown', (e) => {
    const items = suggestionList.getElementsByClassName('suggestion-item');
    if (e.key === "ArrowDown") {
        // Move down in suggestions list
        e.preventDefault();
        focusedIndex = (focusedIndex + 1) % items.length;
        updateFocus(items);
    } else if (e.key === "ArrowUp") {
        // Move up in suggestions list
        e.preventDefault();
        focusedIndex = (focusedIndex - 1 + items.length) % items.length;
        updateFocus(items);
    } else if (e.key === "Enter") {
        // Select suggestion or navigate to path
        if (focusedIndex > -1 && items[focusedIndex]) {
            items[focusedIndex].click();
        } else {
            // Navigate to entered path
            let targetPath = pathInput.value.trim();
            if (targetPath === "") targetPath = "/";
            navigate(targetPath, true);
        }
        suggestionList.style.display = "none";
    }
});

/** Update visual focus in suggestions list */
function updateFocus(items) {
    for (let i = 0; i < items.length; i++) items[i].classList.toggle('active', i === focusedIndex);
}

function renderSuggestions(data) {
    const folders = data.split('|').filter(f => f.length > 0);
    if (folders.length === 0) { suggestionList.style.display = "none"; return; }
    suggestionList.innerHTML = "";
    focusedIndex = -1;
    folders.forEach(f => {
        const div = document.createElement('div');
        div.className = 'suggestion-item';
        div.innerHTML = `<span>📂</span> <b>${f}</b>`;
        div.onclick = () => navigate(f); 
        suggestionList.appendChild(div);
    });
    suggestionList.style.display = "block";
}

// ============================================================================
// SVG Icons Library - File Type Icons
// ============================================================================

/**
 * SVG icon definitions for UI elements.
 * Each icon is an inline SVG string optimized for 24x24 viewBox.
 * Color-coded by file type for instant visual recognition.
 * 
 * @constant {Object.<string, string>}
 */
const icons = {
    // Context Menu Action Icons
    kebab: `<svg viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>`,
    download: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 16l-6-6h4V4h4v6h4l-6 6zm-8 2v2h16v-2H4z"/></svg>`,
    rename: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"/></svg>`,
    trash: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>`,
    
    // Folder Icon (Blue)
    folder: `<svg viewBox="0 0 24 24" fill="#5aa9ff"><path d="M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"/></svg>`,
    
    // Generic File Icon (Gray)
    file: `<svg viewBox="0 0 24 24" fill="#94a3b8"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 7V3.5L18.5 9H13z"/></svg>`,
    
    // Image Files (Green) - jpg, png, gif, webp, svg, bmp, ico
    image: `<svg viewBox="0 0 24 24" fill="#10b981"><path d="M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z"/></svg>`,
    
    // PDF Documents (Red)
    pdf: `<svg viewBox="0 0 24 24" fill="#ef4444"><path d="M20 2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-8.5 7.5c0 .83-.67 1.5-1.5 1.5H9v2H7.5V7H10c.83 0 1.5.67 1.5 1.5v1zm5 2c0 .83-.67 1.5-1.5 1.5h-2.5V7H15c.83 0 1.5.67 1.5 1.5v3zm4-3H19v1h1.5V11H19v2h-1.5V7h3v1.5zM9 9.5h1v-1H9v1zM4 6H2v14c0 1.1.9 2 2 2h14v-2H4V6zm10 5.5h1v-3h-1v3z"/></svg>`,
    
    // Video Files (Purple) - mp4, mkv, avi, mov, webm
    video: `<svg viewBox="0 0 24 24" fill="#8b5cf6"><path d="M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V4h-4z"/></svg>`,
    
    // Audio Files (Amber) - mp3, wav, flac, ogg, aac
    audio: `<svg viewBox="0 0 24 24" fill="#f59e0b"><path d="M12 3v9.28c-.47-.17-.97-.28-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z"/></svg>`,
    
    // Archive Files (Indigo) - zip, rar, 7z, tar, gz
    archive: `<svg viewBox="0 0 24 24" fill="#6366f1"><path d="M20 6h-8l-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm-2 6h-2v2h2v2h-2v2h-2v-2h2v-2h-2v-2h2v-2h-2V8h2v2h2v2z"/></svg>`,
    
    // Code/Source Files (Cyan) - js, py, java, html, css, json
    code: `<svg viewBox="0 0 24 24" fill="#06b6d4"><path d="M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"/></svg>`,
    
    // Text Documents (Blue) - doc, docx, txt, rtf, odt
    document: `<svg viewBox="0 0 24 24" fill="#3b82f6"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"/></svg>`,
    
    // Spreadsheet Files (Green) - xls, xlsx, csv, ods
    spreadsheet: `<svg viewBox="0 0 24 24" fill="#22c55e"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 2v3H5V5h14zm-9 14H5v-9h5v9zm2 0v-9h7v9h-7z"/></svg>`,
    
    // Presentation Files (Orange) - ppt, pptx, odp
    presentation: `<svg viewBox="0 0 24 24" fill="#f97316"><path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V5h14v14zM13.5 13v-2.5H16v-2h-2.5V6h-2v2.5H9v2h2.5V13z"/></svg>`,
    
    // Executable/Binary Files (Slate) - exe, msi, dmg, app
    executable: `<svg viewBox="0 0 24 24" fill="#64748b"><path d="M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14zm-4.2-5.78v1.75l3.2-2.99-3.2-2.99v1.7c-3.11.43-4.35 2.56-4.8 4.7 1.11-1.5 2.58-2.18 4.8-2.17z"/></svg>`,
    
    // FAB Menu Icons
    uploadFile: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11zm-5-6v4h-2v-4H8l4-4 4 4h-3z"/></svg>`,
    uploadFolder: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M20 6h-8l-2-2H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2zm0 12H4V6h5.17l2 2H20v10zm-8-4v2h-2v-2H8l4-4 4 4h-3z"/></svg>`,
    
    // Toolbar Icons
    newFolder: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M20 6h-8l-2-2H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V8c0-1.11-.89-2-2-2zm-1 8h-3v3h-2v-3h-3v-2h3V9h2v3h3v2z"/></svg>`,
    refresh: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/></svg>`,
    back: `<svg viewBox="0 0 24 24" fill="currentColor"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>`
};

/**
 * File extension to icon type mapping.
 * Maps common file extensions to their corresponding icon key.
 * Used by getFileIcon() to determine visual representation.
 * 
 * @constant {Object.<string, string>}
 */
const fileTypeMap = {
    // Image formats
    jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', webp: 'image',
    svg: 'image', bmp: 'image', ico: 'image', tiff: 'image', heic: 'image', raw: 'image',
    
    // PDF
    pdf: 'pdf',
    
    // Video formats
    mp4: 'video', mkv: 'video', avi: 'video', mov: 'video', webm: 'video',
    wmv: 'video', flv: 'video', m4v: 'video', '3gp': 'video', mpeg: 'video',
    
    // Audio formats
    mp3: 'audio', wav: 'audio', flac: 'audio', ogg: 'audio', aac: 'audio',
    wma: 'audio', m4a: 'audio', opus: 'audio', mid: 'audio', midi: 'audio',
    
    // Archive formats
    zip: 'archive', rar: 'archive', '7z': 'archive', tar: 'archive',
    gz: 'archive', bz2: 'archive', xz: 'archive', iso: 'archive', cab: 'archive',
    
    // Programming/Code files
    js: 'code', ts: 'code', jsx: 'code', tsx: 'code', py: 'code',
    java: 'code', c: 'code', cpp: 'code', h: 'code', hpp: 'code',
    cs: 'code', go: 'code', rs: 'code', rb: 'code', php: 'code',
    html: 'code', htm: 'code', css: 'code', scss: 'code', sass: 'code', less: 'code',
    json: 'code', xml: 'code', yaml: 'code', yml: 'code', toml: 'code',
    sql: 'code', sh: 'code', bash: 'code', bat: 'code', ps1: 'code', cmd: 'code',
    vue: 'code', svelte: 'code', kt: 'code', swift: 'code', r: 'code',
    pl: 'code', lua: 'code', dart: 'code', scala: 'code', groovy: 'code',
    
    // Document formats
    doc: 'document', docx: 'document', txt: 'document', rtf: 'document',
    odt: 'document', tex: 'document', log: 'document', md: 'document',
    
    // Spreadsheet formats
    xls: 'spreadsheet', xlsx: 'spreadsheet', csv: 'spreadsheet',
    ods: 'spreadsheet', tsv: 'spreadsheet', numbers: 'spreadsheet',
    
    // Presentation formats
    ppt: 'presentation', pptx: 'presentation', odp: 'presentation',
    key: 'presentation',
    
    // Executable/Binary formats
    exe: 'executable', msi: 'executable', dmg: 'executable',
    app: 'executable', deb: 'executable', rpm: 'executable',
    apk: 'executable', jar: 'executable', bin: 'executable', run: 'executable'
};

/**
 * Human-readable labels for file types.
 * Displayed as subtitle under file name in the list.
 * 
 * @constant {Object.<string, string>}
 */
const fileTypeLabels = {
    image: 'Image',
    pdf: 'PDF Document',
    video: 'Video',
    audio: 'Audio',
    archive: 'Archive',
    code: 'Source Code',
    document: 'Document',
    spreadsheet: 'Spreadsheet',
    presentation: 'Presentation',
    executable: 'Executable'
};

/**
 * Get the appropriate SVG icon for a file based on its extension.
 * Falls back to generic file icon for unknown extensions.
 * 
 * @param {string} fileName - The file name including extension
 * @returns {string} SVG string for the file icon
 * 
 * @example
 * getFileIcon('photo.jpg')    // Returns green image icon
 * getFileIcon('report.pdf')   // Returns red PDF icon
 * getFileIcon('data.xyz')     // Returns gray generic file icon
 */
function getFileIcon(fileName) {
    const ext = fileName.split('.').pop()?.toLowerCase() || '';
    const iconType = fileTypeMap[ext] || 'file';
    return icons[iconType] || icons.file;
}

/**
 * Get human-readable file type label based on extension.
 * Used as subtitle text under file name.
 * 
 * @param {string} fileName - The file name including extension
 * @returns {string} Human-readable type label (e.g., "Image", "PDF Document")
 */
function getFileTypeLabel(fileName) {
    const ext = fileName.split('.').pop()?.toLowerCase() || '';
    const iconType = fileTypeMap[ext];
    return fileTypeLabels[iconType] || 'File';
}

// ============================================================================
// Action Sheet Management (replaces old context menu)
// ============================================================================

/** @type {HTMLElement} Action sheet overlay */
const actionSheetOverlay = document.getElementById('actionSheetOverlay');

/** @type {string|null} Currently selected item name for action sheet */
let actionSheetItemName = null;

/** @type {boolean} Is the currently selected item a directory */
let actionSheetIsDir = false;

/** @type {number} Size in KB of current item */
let actionSheetSizeKB = 0;

/**
 * Open action sheet for a file or folder
 * @param {string} name - File/folder name
 * @param {boolean} isDir - Is it a directory
 * @param {string} icon - SVG icon HTML
 * @param {string} typeLabel - Type label (e.g., "Folder", "PDF Document")
 * @param {number} sizeKB - Size in KB (0 for folders)
 */
function openActionSheet(name, isDir, icon, typeLabel, sizeKB) {
    actionSheetItemName = name;
    actionSheetIsDir = isDir;
    actionSheetSizeKB = sizeKB;
    
    document.getElementById('actionSheetIcon').innerHTML = icon;
    document.getElementById('actionSheetTitle').textContent = name;
    // Show size only for files, not for folders
    const subtitle = isDir ? typeLabel : `${typeLabel} • ${formatFileSize(sizeKB)}`;
    document.getElementById('actionSheetSubtitle').textContent = subtitle;
    
    actionSheetOverlay.classList.add('active');
    document.body.style.overflow = 'hidden';
}

/**
 * Close the action sheet
 */
function closeActionSheet() {
    actionSheetOverlay.classList.remove('active');
    document.body.style.overflow = '';
    actionSheetItemName = null;
}

// Close action sheet when clicking on overlay background
actionSheetOverlay?.addEventListener('click', (e) => {
    if (e.target === actionSheetOverlay) {
        closeActionSheet();
    }
});

// Action sheet button handlers
document.querySelectorAll('.action-sheet-item').forEach(btn => {
    btn.addEventListener('click', () => {
        const action = btn.dataset.action;
        const name = actionSheetItemName;
        const isDir = actionSheetIsDir;
        closeActionSheet();
        
        if (!name) return;
        
        switch (action) {
            case 'download':
                if (isDir) {
                    downloadFolder(name); // For folders, direct download is also ZIP
                } else {
                    downloadFile(name);
                }
                break;
            case 'downloadZip':
                downloadAsZip(name, isDir);
                break;
            case 'rename':
                showRenameModal(name);
                break;
            case 'delete':
                showDeleteModal(name);
                break;
        }
    });
});

// Close action sheet on Escape key
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && actionSheetOverlay?.classList.contains('active')) {
        closeActionSheet();
    }
});

// Close burger menu when clicking outside
document.addEventListener('click', (e) => {
    if (!e.target.closest('.burger-container')) {
        const burgerMenu = document.getElementById('burgerMenu');
        if (burgerMenu) burgerMenu.classList.remove('active');
    }
});

// ============================================================================
// Burger Menu
// ============================================================================

const burgerBtn = document.getElementById('burgerBtn');
const burgerMenu = document.getElementById('burgerMenu');

burgerBtn?.addEventListener('click', (e) => {
    e.stopPropagation();
    burgerMenu?.classList.toggle('active');
});

// ============================================================================
// File List Rendering
// ============================================================================

/**
 * Format file size in human-readable format (KB, MB, GB).
 * @param {number} sizeInKB - File size in kilobytes
 * @returns {string} Formatted size string
 */
function formatFileSize(sizeInKB) {
    if (sizeInKB < 1024) {
        return sizeInKB + ' KB';
    } else if (sizeInKB < 1048576) {
        return (sizeInKB / 1024).toFixed(1) + ' MB';
    } else {
        return (sizeInKB / 1048576).toFixed(2) + ' GB';
    }
}

/** Flag to track if we're in streaming mode */
let isStreamingList = false;

/** Show loading indicator in file list */
function showListLoading() {
    isStreamingList = true;
    fileListElement.innerHTML = '<div class="loading-indicator" style="text-align:center; padding:20px; color:var(--muted);">Ładowanie...</div>';
}

/** Hide loading indicator (called when LIST_END received) */
function hideListLoading() {
    isStreamingList = false;
    const loader = fileListElement.querySelector('.loading-indicator');
    if (loader) loader.remove();
    // If list is still empty, show empty message
    if (fileListElement.children.length === 0) {
        fileListElement.innerHTML = '<div style="text-align:center; padding:20px; color:var(--muted);">(pusty folder)</div>';
    }
}

/** Append a chunk of files to the list (streaming mode) */
function appendFileListChunk(data) {
    // Remove loading indicator on first chunk
    const loader = fileListElement.querySelector('.loading-indicator');
    if (loader) loader.remove();
    
    const lines = data.split("\n");
    lines.forEach(line => {
        if (!line.trim() || line.includes("(empty")) return;
        const item = createFileListItem(line);
        if (item) fileListElement.appendChild(item);
    });
}

/** Create a single file list item element from a line of server data */
function createFileListItem(line) {
    if (!line.trim() || line.includes("(empty")) return null;
    
    const isDir = line.includes("[DIR]");
    
    // Parse file/folder name and size from server format
    let name, fileSizeKB = 0;
    if (isDir) {
        const match = line.replace(/\[DIR\]/, "").trim().match(/^(.+?)\s*\((\d+)\s*KB\)$/);
        if (match) {
            name = match[1].trim();
            fileSizeKB = parseInt(match[2], 10);
        } else {
            name = line.replace(/\[DIR\]/, "").trim();
        }
    } else {
        const match = line.replace(/\[FILE\]/, "").trim().match(/^(.+?)\s*\((\d+)\s*KB\)$/);
        if (match) {
            name = match[1].trim();
            fileSizeKB = parseInt(match[2], 10);
        } else {
            name = line.replace(/\[FILE\]/, "").split("(")[0].trim();
        }
    }
    
    const safeName = escapeHtml(name);
    const icon = isDir ? icons.folder : getFileIcon(name);
    const typeLabel = isDir ? 'Folder' : getFileTypeLabel(name);
    const sizeDisplay = isDir ? '' : ` • ${formatFileSize(fileSizeKB)}`;
    
    const item = document.createElement('div');
    item.className = 'file-item';
    item.innerHTML = `
        <div style="width:28px; height:28px">${icon}</div>
        <div class="file-info" data-name="${safeName}" data-isdir="${isDir}">
            <div class="file-name">${safeName}</div>
            <div style="font-size:12px; color:var(--muted)">${typeLabel}${sizeDisplay}</div>
        </div>
        <div class="item-actions">
            <button class="btn-kebab" data-name="${safeName}" data-isdir="${isDir}" data-size="${fileSizeKB}" data-type="${typeLabel}" title="Actions">${icons.kebab}</button>
        </div>
    `;
    
    // Click handler for navigating into folders OR previewing files
    const fileInfo = item.querySelector('.file-info');
    fileInfo.addEventListener('click', () => {
        if (isDir) {
            navigate(name);
        } else {
            openPreview(name);
        }
    });
    
    // Kebab button opens action sheet
    const kebabBtn = item.querySelector('.btn-kebab');
    kebabBtn.addEventListener('click', (e) => {
        e.stopPropagation();
        openActionSheet(name, isDir, icon, typeLabel, fileSizeKB);
    });
    
    return item;
}

/**
 * Render the file list from server response data.
 * Creates interactive file/folder items with type-specific icons and context menus.
 * 
 * @param {string} data - Newline-separated list from server
 *                        Format per line: "[DIR] FolderName" or "[FILE] FileName (123 KB)"
 */
function renderFileList(data) {
    fileListElement.innerHTML = "";
    const lines = data.split("\n");
    
    lines.forEach(line => {
        const item = createFileListItem(line);
        if (item) fileListElement.appendChild(item);
    });
    
    // Show empty message if no items
    if (fileListElement.children.length === 0) {
        fileListElement.innerHTML = '<div style="text-align:center; padding:20px; color:var(--muted);">(pusty folder)</div>';
    }
}

function escapeHtml(str) {
    return str.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}


function openModal(title, text, icon, color, onConfirm, showInput) {
    document.getElementById('modalTitle').innerText = title;
    document.getElementById('modalText').innerText = text;
    modalIcon.innerText = icon;
    modalConfirmBtn.style.backgroundColor = color;
    modalInput.style.display = showInput ? "block" : "none";
    modalInput.value = "";
    modalOverlay.style.display = "flex";

    showInput ? modalInput.focus() : modalContainer.focus();

    const doConfirm = () => { onConfirm(modalInput.value); closeModal(); };
    modalConfirmBtn.onclick = doConfirm;

    const keyListener = (e) => {
        if (e.key === "Enter") {
            e.preventDefault();
            doConfirm();
        } else if (e.key === "Escape") {
            closeModal();
        }
    };

    modalInput.onkeydown = keyListener;
    modalContainer.onkeydown = keyListener;
}

function showCreateModal() { openModal("New Folder", "Enter name:", "📂", "#2ed573", (v) => v && sendCommand(`mkdir ${v}`), true); }
function showDeleteModal(name) { openModal("Delete", `Delete "${name}"?`, "🗑️", "#ff4757", () => sendCommand(`rm ${name}`), false); }
function showRenameModal(oldName) { 
    openModal("Rename", `New name for "${oldName}":`, "✏️", "var(--primary)", (newName) => {
        if (newName && newName.trim() && newName !== oldName) {
            sendCommand(`rename ${oldName}\t${newName.trim()}`);
        }
    }, true); 
    // Pre-fill with current name for convenience
    modalInput.value = oldName;
    modalInput.select();
}
function closeModal() { modalOverlay.style.display = "none"; }

function goBack() { window.history.back(); }
function refreshUI() { 
    showListLoading();
    sendCommand("pwd"); 
    sendCommand("ls"); 
}
function sendCommand(c) { if (socket?.readyState === WebSocket.OPEN) socket.send(c); }

function downloadFile(n) {
    const cur = pathInput.value;
    const path = cur.endsWith("/") ? cur.substring(1) + n : cur.substring(1) + "/" + n;
    window.location.href = `/download?name=${encodeURIComponent(path)}`;
}

/**
 * Download a folder as a ZIP archive (direct download).
 * @param {string} folderName - Name of the folder to download
 */
function downloadFolder(folderName) {
    const cur = pathInput.value;
    const path = cur.endsWith("/") ? cur.substring(1) + folderName : cur.substring(1) + "/" + folderName;
    showToast(`Preparing download for "${folderName}"...`, 3000);
    window.location.href = `/downloadFolder?name=${encodeURIComponent(path)}`;
}

/**
 * Download a file or folder as a ZIP archive.
 * For files: wraps the single file in a ZIP
 * For folders: creates ZIP with all contents
 * @param {string} name - Name of the file/folder to download
 * @param {boolean} isDir - Is it a directory
 */
function downloadAsZip(name, isDir) {
    const cur = pathInput.value;
    const path = cur.endsWith("/") ? cur.substring(1) + name : cur.substring(1) + "/" + name;
    showToast(`Creating ZIP archive for "${name}"...`, 3000);
    // Use downloadZip endpoint which handles both files and folders
    window.location.href = `/downloadZip?name=${encodeURIComponent(path)}&type=${isDir ? 'folder' : 'file'}`;
}

window.onpopstate = (e) => {
    const targetPath = e.state?.path || "/";
    sendCommand("goto " + targetPath);
};

// ============================================================================
// File Preview System
// ============================================================================

/** @type {HTMLElement} Preview overlay element */
const previewOverlay = document.getElementById('previewOverlay');
/** @type {HTMLElement} Preview content container */
const previewContent = document.getElementById('previewContent');
/** @type {HTMLElement} Preview title element */
const previewTitle = document.getElementById('previewTitle');
/** @type {HTMLButtonElement} Preview download button */
const previewDownloadBtn = document.getElementById('previewDownloadBtn');
/** @type {HTMLButtonElement} Preview close button */
const previewCloseBtn = document.getElementById('previewCloseBtn');

/** @type {string|null} Currently previewed file name */
let currentPreviewFile = null;

/**
 * File extensions that can be previewed, grouped by type.
 * @constant {Object.<string, string[]>}
 */
const previewableTypes = {
    image: ['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp', 'ico'],
    pdf: ['pdf'],
    video: ['mp4', 'webm', 'ogg', 'mov'],
    audio: ['mp3', 'wav', 'ogg', 'flac', 'aac', 'm4a', 'opus'],
    text: ['txt', 'md', 'json', 'xml', 'html', 'htm', 'css', 'js', 'ts', 'jsx', 'tsx',
           'py', 'java', 'c', 'cpp', 'h', 'hpp', 'cs', 'go', 'rs', 'rb', 'php',
           'yaml', 'yml', 'toml', 'ini', 'cfg', 'conf', 'sh', 'bash', 'bat', 'ps1',
           'sql', 'log', 'csv', 'tex', 'rtf', 'vue', 'svelte', 'cls', 'sty']
};

/**
 * Get the preview type for a file based on extension.
 * @param {string} fileName - File name with extension
 * @returns {string|null} Preview type or null if not previewable
 */
function getPreviewType(fileName) {
    const ext = fileName.split('.').pop()?.toLowerCase() || '';
    for (const [type, extensions] of Object.entries(previewableTypes)) {
        if (extensions.includes(ext)) return type;
    }
    return null;
}

/**
 * Check if a file can be previewed.
 * @param {string} fileName - File name with extension
 * @returns {boolean} True if file can be previewed
 */
function canPreview(fileName) {
    return getPreviewType(fileName) !== null;
}

/**
 * Get the download URL for a file.
 * @param {string} fileName - File name
 * @param {boolean} inline - If true, request inline display for preview
 */
function getFileUrl(fileName, inline = false) {
    const cur = pathInput.value;
    const path = cur.endsWith("/") ? cur.substring(1) + fileName : cur.substring(1) + "/" + fileName;
    let url = `/download?name=${encodeURIComponent(path)}`;
    if (inline) url += '&inline=true';
    return url;
}

/**
 * Show loading state in preview.
 */
function showPreviewLoading() {
    previewContent.innerHTML = `
        <div class="preview-loading">
            <div class="preview-spinner"></div>
            <p>Loading preview...</p>
        </div>
    `;
}

/**
 * Show unsupported file type message.
 * @param {string} fileName - File name
 */
function showUnsupportedPreview(fileName) {
    const ext = fileName.split('.').pop()?.toUpperCase() || 'Unknown';
    previewContent.innerHTML = `
        <div class="preview-unsupported">
            <p>Preview not available for .${ext} files</p>
            <button class="preview-btn download" onclick="downloadFile('${escapeHtml(fileName)}')" style="width:auto; padding: 12px 24px;">
                Download File
            </button>
        </div>
    `;
}

/**
 * Open file preview modal.
 * @param {string} fileName - Name of file to preview
 */
async function openPreview(fileName) {
    currentPreviewFile = fileName;
    previewTitle.textContent = fileName;
    previewOverlay.classList.add('active');
    document.body.style.overflow = 'hidden';
    
    // Use inline=true so browser displays file instead of downloading
    const fileUrl = getFileUrl(fileName, true);
    const previewType = getPreviewType(fileName);
    
    showPreviewLoading();
    
    try {
        switch (previewType) {
            case 'image':
                previewContent.innerHTML = `<img src="${fileUrl}" alt="${escapeHtml(fileName)}" onload="this.style.opacity=1" style="opacity:0; transition: opacity 0.3s">`;
                break;
                
            case 'pdf':
                previewContent.innerHTML = `<iframe src="${fileUrl}#toolbar=1&navpanes=0"></iframe>`;
                break;
                
            case 'video':
                previewContent.innerHTML = `<video controls autoplay><source src="${fileUrl}">Your browser doesn't support video playback.</video>`;
                break;
                
            case 'audio':
                previewContent.innerHTML = `
                    <div class="preview-audio-container">
                        <div class="preview-audio-icon">
                            <svg viewBox="0 0 24 24"><path d="M12 3v9.28c-.47-.17-.97-.28-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z"/></svg>
                        </div>
                        <p style="color: var(--text); font-weight: 500;">${escapeHtml(fileName)}</p>
                        <audio controls autoplay><source src="${fileUrl}">Your browser doesn't support audio playback.</audio>
                    </div>
                `;
                break;
                
            case 'text':
                const response = await fetch(fileUrl);
                if (!response.ok) throw new Error('Failed to load file');
                const text = await response.text();
                previewContent.innerHTML = `<div class="preview-text"><pre>${escapeHtml(text)}</pre></div>`;
                break;
                
            default:
                showUnsupportedPreview(fileName);
        }
    } catch (error) {
        console.error('Preview error:', error);
        previewContent.innerHTML = `
            <div class="preview-unsupported">
                ${icons.file}
                <p>Failed to load preview</p>
                <p style="font-size: 14px;">${escapeHtml(error.message)}</p>
            </div>
        `;
    }
}

/**
 * Close the preview modal.
 */
function closePreview() {
    previewOverlay.classList.remove('active');
    document.body.style.overflow = '';
    currentPreviewFile = null;
    
    // Stop any playing media
    const video = previewContent.querySelector('video');
    const audio = previewContent.querySelector('audio');
    if (video) video.pause();
    if (audio) audio.pause();
    
    // Clear content after animation
    setTimeout(() => {
        if (!previewOverlay.classList.contains('active')) {
            previewContent.innerHTML = '';
        }
    }, 300);
}

// Preview event listeners
previewCloseBtn?.addEventListener('click', closePreview);
previewDownloadBtn?.addEventListener('click', () => {
    if (currentPreviewFile) downloadFile(currentPreviewFile);
});

// Close preview on Escape key
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && previewOverlay?.classList.contains('active')) {
        closePreview();
    }
});

// Close preview when clicking outside content
previewOverlay?.addEventListener('click', (e) => {
    if (e.target === previewOverlay || e.target === previewContent) {
        closePreview();
    }
});

connect();
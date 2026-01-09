// WebSocket client for ServerAccessHub
// Manages file browser, navigation, and real-time folder operations

let socket;
const fileListElement = document.getElementById('fileList');
const pathInput = document.getElementById('currentPath');
const suggestionList = document.getElementById('suggestionList');
const modalOverlay = document.getElementById('modalOverlay');
const modalContainer = document.getElementById('modalContainer');
const modalInput = document.getElementById('modalInput');
const modalIcon = document.getElementById('modalIcon');
const modalConfirmBtn = document.getElementById('modalConfirmBtn');

let isTyping = false;              // User actively typing in path input
let focusedIndex = -1;             // Keyboard navigation index
let lastPathFromServer = "/";      // Track current directory from server

/** Connect to WebSocket and restore state from URL hash */
function connect() {
    socket = new WebSocket("wss://" + window.location.host);
    socket.onopen = () => {
        const hash = window.location.hash.replace('#', '');
        hash ? sendCommand("goto " + hash) : refreshUI();
    };
    socket.onmessage = (e) => {
        const msg = e.data;
        if (msg.startsWith("PATH: ")) {
            // Update current directory path display
            lastPathFromServer = msg.replace("PATH: ", "");
            if (!isTyping) {
                pathInput.value = lastPathFromServer.endsWith("/") ? lastPathFromServer : lastPathFromServer + "/";
            }
        } 
        else if (msg.startsWith("LIST: ")) renderFileList(msg.replace("LIST: ", ""));
        else if (msg.startsWith("SUGGEST: ")) renderSuggestions(msg.replace("SUGGEST: ", ""));
        else if (msg === "RES: OK") { isTyping = false; refreshUI(); }
    };
    socket.onclose = () => setTimeout(connect, 2000); // Reconnect on disconnect
}

/** Navigate to folder (relative or absolute) and update browser history */
function navigate(folderOrPath, isAbsolute = false) {
    isTyping = false;
    suggestionList.style.display = "none";
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

pathInput.addEventListener('keydown', (e) => {
    const items = suggestionList.getElementsByClassName('suggestion-item');
    if (e.key === "ArrowDown") {
        e.preventDefault();
        focusedIndex = (focusedIndex + 1) % items.length;
        updateFocus(items);
    } else if (e.key === "ArrowUp") {
        e.preventDefault();
        focusedIndex = (focusedIndex - 1 + items.length) % items.length;
        updateFocus(items);
    } else if (e.key === "Enter") {
        if (focusedIndex > -1 && items[focusedIndex]) {
            items[focusedIndex].click();
        } else {
            // Przejdź do wpisanej ścieżki (nawet jeśli skrócona)
            let targetPath = pathInput.value.trim();
            if (targetPath === "") targetPath = "/";
            navigate(targetPath, true);
        }
        suggestionList.style.display = "none";
    }
});

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

function renderFileList(data) {
    fileListElement.innerHTML = "";
    const lines = data.split("\n");
    lines.forEach(line => {
        if (!line.trim() || line.includes("(empty")) return;
        const isDir = line.includes("[DIR]");
        const name = line.replace(/\[DIR\]|\[FILE\]/, "").split("(")[0].trim();
        const item = document.createElement('div');
        item.className = 'file-item';
        item.innerHTML = `
            <div style="font-size:24px">${isDir ? "📂" : "📄"}</div>
            <div class="file-info" onclick="${isDir ? `navigate('${name}')` : ''}">
                <div class="file-name">${name}</div>
                <div style="font-size:12px; color:#aaa">${isDir ? 'Folder' : 'Plik'}</div>
            </div>
            <div style="display:flex; gap:10px">
                ${!isDir ? `<button style="background:none; font-size:18px" onclick="downloadFile('${name}')">📥</button>` : ""}
                <button style="background:none; font-size:18px; color:#ff4757" onclick="showDeleteModal('${name}')">🗑️</button>
            </div>
        `;
        fileListElement.appendChild(item);
    });
}

// MODAL Z OBSŁUGĄ ENTER
function openModal(title, text, icon, color, onConfirm, showInput) {
    document.getElementById('modalTitle').innerText = title;
    document.getElementById('modalText').innerText = text;
    modalIcon.innerText = icon;
    modalConfirmBtn.style.backgroundColor = color;
    modalInput.style.display = showInput ? "block" : "none";
    modalInput.value = "";
    modalOverlay.style.display = "flex";

    // Ustaw focus, aby Enter działał od razu
    showInput ? modalInput.focus() : modalContainer.focus();

    const doConfirm = () => { onConfirm(modalInput.value); closeModal(); };
    modalConfirmBtn.onclick = doConfirm;

    // Obsługa klawiszy (Enter i Escape)
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

function showCreateModal() { openModal("Nowy Folder", "Podaj nazwę:", "📂", "#2ed573", (v) => v && sendCommand(`mkdir ${v}`), true); }
function showDeleteModal(name) { openModal("Usuwanie", `Usunąć "${name}"?`, "🗑️", "#ff4757", () => sendCommand(`rm ${name}`), false); }
function closeModal() { modalOverlay.style.display = "none"; }

function goBack() { window.history.back(); }
function refreshUI() { sendCommand("pwd"); sendCommand("ls"); }
function sendCommand(c) { if (socket?.readyState === WebSocket.OPEN) socket.send(c); }

function downloadFile(n) {
    const cur = pathInput.value;
    const path = cur.endsWith("/") ? cur.substring(1) + n : cur.substring(1) + "/" + n;
    window.location.href = `/download?name=${encodeURIComponent(path)}`;
}

window.onpopstate = (e) => {
    const targetPath = e.state?.path || "/";
    sendCommand("goto " + targetPath);
};

connect();
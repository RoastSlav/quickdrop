function confirmDelete() {
    return confirm("Are you sure you want to delete this file? This action cannot be undone.");
}

function updateCheckboxState(event, checkbox) {
    event.preventDefault();

    const hiddenField = checkbox.form.querySelector(`input[name="${checkbox.name}"][type="hidden"]`);
    if (hiddenField) {
        hiddenField.value = checkbox.checked;
    }

    checkbox.form.submit();
}

function initializeModal() {
    const downloadLinkEl = document.getElementById("downloadLink");
    const unrestricted = document.getElementById('unrestrictedLink');
    const linkOptions = document.getElementById('linkOptions');
    const generateButton = document.getElementById('generateLinkButton');

    if (downloadLinkEl) {
        updateShareLink(downloadLinkEl.innerText);
    }

    if (unrestricted) {
        unrestricted.checked = false;
    }
    if (linkOptions) {
        linkOptions.classList.add('hidden');
    }
    if (generateButton) {
        generateButton.disabled = true;
        generateButton.classList.add('hidden');
    }
}

function generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads) {
    const csrfToken = document.querySelector('meta[name="_csrf"]').content;
    const expirationDate = new Date();
    expirationDate.setDate(expirationDate.getDate() + daysValid);
    const expirationDateStr = expirationDate.toISOString().split('T')[0];

    return fetch(`/api/file/share/${fileUuid}?expirationDate=${expirationDateStr}&nOfDownloads=${allowedNumberOfDownloads}`, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-XSRF-TOKEN': csrfToken,
        },
    })
        .then((response) => {
            if (!response.ok) throw new Error("Failed to generate share link");
            return response.text();
        });
}


function copyShareLink() {
    const shareLinkInput = document.getElementById('shareLink');
    navigator.clipboard.writeText(shareLinkInput.value)
        .then(() => {
            alert("Link copied to clipboard!");
        })
        .catch((err) => {
            console.error("Failed to copy link:", err);
        });
}

function createShareLink() {
    const fileUuid = document.getElementById('fileUuid').textContent.trim();
    const daysValidInput = document.getElementById('daysValid');
    const daysValid = parseInt(daysValidInput.value, 10);
    const allowedNumberOfDownloadsInput = document.getElementById('allowedNumberOfDownloadsCount');
    const allowedNumberOfDownloads = parseInt(allowedNumberOfDownloadsInput.value, 10);

    if (isNaN(daysValid) || daysValid < 1) {
        alert("Please enter a valid number of days.");
        return;
    }

    if (isNaN(allowedNumberOfDownloads) || allowedNumberOfDownloads < 1) {
        alert("Please enter a valid number of downloads.");
        return;
    }

    const spinner = document.getElementById('spinner');
    const generateLinkButton = document.getElementById('generateLinkButton');

    spinner.style.display = 'inline-block';
    generateLinkButton.disabled = true;

    generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads)
        .then((shareLink) => {
            updateShareLink(shareLink); // Update with the token-based link
        })
        .catch((error) => {
            console.error(error);
            alert("Failed to generate share link.");
        }).finally(() => {
        spinner.style.display = 'none';
        generateLinkButton.disabled = false;
    });
}

function updateShareLink(link) {
    const shareLinkInput = document.getElementById('shareLink');
    const canvas = document.getElementById('shareQRCode');

    shareLinkInput.value = link;

    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    QRCode.toCanvas(canvas, link, {width: 150, height: 150});
}


function toggleLinkType() {
    const unrestrictedLinkCheckbox = document.getElementById('unrestrictedLink');
    const linkOptions = document.getElementById('linkOptions');
    const generateLinkButton = document.getElementById('generateLinkButton');

    if (unrestrictedLinkCheckbox && unrestrictedLinkCheckbox.checked) {
        linkOptions.classList.remove('hidden');
        generateLinkButton.classList.remove('hidden');
        generateLinkButton.disabled = false;
    } else if (unrestrictedLinkCheckbox) {
        linkOptions.classList.add('hidden');
        generateLinkButton.classList.add('hidden');
        generateLinkButton.disabled = true;
        initializeModal();
    }
}

function openShareModal() {
    const modal = document.getElementById('shareModal');
    modal.classList.remove('hidden');
    positionShareModal();
}

function closeShareModal() {
    document.getElementById('shareModal').classList.add('hidden');
}

function positionShareModal() {
    const card = document.getElementById('fileInfoCard');
    const modal = document.getElementById('shareModalContent');
    if (!card || !modal) return;

    const margin = 16; // space between modal, card and screen edge
    const cardRect = card.getBoundingClientRect();
    const modalRect = modal.getBoundingClientRect();

    let left = cardRect.left - modalRect.width - margin;
    if (left < margin) {
        left = margin;
    }

    const top = cardRect.top + cardRect.height / 2 - modalRect.height / 2;

    modal.style.left = `${left}px`;
    modal.style.top = `${Math.max(top, margin)}px`;
}

document.addEventListener('DOMContentLoaded', () => {
    initializeModal();
    renderFolderTree();
    setupPreviewInit();
    setupDeleteConfirm();
});

let previewBlob = null;
let previewFetched = false;
let previewFetching = false;
let previewReadyPromise = null;

function setupPreviewInit() {
    const container = document.getElementById('previewContainer');
    if (!container) return;
    const requireManual = container.dataset.requireManual === 'true';
    const loadBtn = document.getElementById('loadPreviewBtn');

    const startFetch = () => {
        if (!previewReadyPromise) {
            previewReadyPromise = initPreview();
        }
    };

    if (requireManual && loadBtn) {
        loadBtn.addEventListener('click', startFetch, {once: true});
    } else {
        window.addEventListener('load', () => startFetch(), {once: true});
    }
}

async function initPreview() {
    const container = document.getElementById('previewContainer');
    const content = document.getElementById('previewContent');
    const status = document.getElementById('previewStatus');
    if (!container || !content) return;

    let previewUrl = container.dataset.previewUrl;
    const isImage = container.dataset.previewImage === 'true';
    const isText = container.dataset.previewText === 'true';
    const isPdf = container.dataset.previewPdf === 'true';
    const isJson = container.dataset.previewJson === 'true';
    const isCsv = container.dataset.previewCsv === 'true';
    const previewType = container.dataset.previewType || '';
    const fileName = container.dataset.fileName || 'download';
    const requireManual = container.dataset.requireManual === 'true';

    if (previewFetching || previewFetched) return;
    previewFetching = true;

    try {
        if (requireManual) {
            previewUrl = `${previewUrl}?manual=true`;
        }

        const resp = await fetch(previewUrl, {credentials: 'same-origin'});
        if (!resp.ok) throw new Error('Preview unavailable');
        const blob = await resp.blob();
        previewBlob = blob;
        previewFetched = true;

        if (status) status.remove();
        content.innerHTML = '';

        const objectUrl = URL.createObjectURL(blob);
        if (isImage) {
            renderImagePreview(content, objectUrl);
        } else if (isPdf || previewType === 'pdf') {
            renderPdfPreview(content, objectUrl, fileName);
        } else {
            const text = await blob.text();
            const extension = extractExtension(fileName);
            if (isJson || previewType === 'json') {
                renderJsonPreview(content, text);
            } else if (isCsv || previewType === 'csv') {
                renderCsvPreview(content, text, extension);
            } else if (isText || previewType === 'text') {
                renderCodePreview(content, text, extension);
            }
        }

        attachDownloadOverride(fileName);
    } catch (e) {
        if (status) {
            status.textContent = 'Preview unavailable.';
            status.className = 'text-sm text-red-600 dark:text-red-400';
        }
    }
    previewFetching = false;
}

function extractExtension(name) {
    const idx = name.lastIndexOf('.');
    if (idx === -1 || idx === name.length - 1) return '';
    return name.slice(idx + 1).toLowerCase();
}

function renderImagePreview(container, objectUrl) {
    const img = document.createElement('img');
    img.src = objectUrl;
    img.alt = 'Preview';
    img.className = 'max-h-[28rem] rounded-lg shadow';
    img.onload = () => URL.revokeObjectURL(objectUrl);
    container.appendChild(img);
}

function renderPdfPreview(container, objectUrl, fileName) {
    const frame = document.createElement('object');
    frame.type = 'application/pdf';
    frame.data = objectUrl;
    frame.className = 'preview-pdf-frame';
    const fallback = document.createElement('div');
    fallback.className = 'text-sm text-gray-600 dark:text-gray-300 mt-2';
    const link = document.createElement('a');
    link.href = objectUrl;
    link.target = '_blank';
    link.rel = 'noopener noreferrer';
    link.textContent = `Open ${fileName} in a new tab`;
    fallback.appendChild(link);
    frame.appendChild(fallback);
    container.appendChild(frame);
}

function renderJsonPreview(container, text) {
    let parsed;
    try {
        parsed = JSON.parse(text);
    } catch (err) {
        renderCodePreview(container, text, 'json');
        return;
    }

    const toolbar = document.createElement('div');
    toolbar.className = 'preview-toolbar';
    const formattedBtn = document.createElement('button');
    formattedBtn.type = 'button';
    formattedBtn.textContent = 'Formatted';
    formattedBtn.className = 'preview-toggle-button active';
    const treeBtn = document.createElement('button');
    treeBtn.type = 'button';
    treeBtn.textContent = 'Tree';
    treeBtn.className = 'preview-toggle-button';
    toolbar.append(formattedBtn, treeBtn);

    const formatted = renderCodeBlock(JSON.stringify(parsed, null, 2), 'json');
    const tree = document.createElement('div');
    tree.className = 'json-tree hidden';
    tree.appendChild(buildJsonNode(parsed, null));

    const swap = (showTree) => {
        if (showTree) {
            tree.classList.remove('hidden');
            formatted.classList.add('hidden');
            treeBtn.classList.add('active');
            formattedBtn.classList.remove('active');
        } else {
            tree.classList.add('hidden');
            formatted.classList.remove('hidden');
            formattedBtn.classList.add('active');
            treeBtn.classList.remove('active');
        }
    };

    formattedBtn.addEventListener('click', () => swap(false));
    treeBtn.addEventListener('click', () => swap(true));

    container.append(toolbar, formatted, tree);
}

function buildJsonNode(value, label) {
    const wrapper = document.createElement('div');
    wrapper.className = 'json-node';

    if (value !== null && typeof value === 'object') {
        const isArray = Array.isArray(value);
        const details = document.createElement('details');
        details.open = true;
        const summary = document.createElement('summary');
        summary.textContent = label ? `${label} ${isArray ? '[ ]' : '{ }'}` : isArray ? '[ ]' : '{ }';
        details.appendChild(summary);
        Object.entries(value).forEach(([key, val]) => {
            details.appendChild(buildJsonNode(val, key));
        });
        wrapper.appendChild(details);
    } else {
        const leaf = document.createElement('div');
        leaf.className = 'json-leaf';
        const name = label ? `${label}: ` : '';
        leaf.textContent = `${name}${String(value)}`;
        wrapper.appendChild(leaf);
    }

    return wrapper;
}

function renderCsvPreview(container, text, extension) {
    const delimiter = extension === 'tsv' ? '\t' : ',';
    const rows = parseDelimited(text, delimiter);
    if (!rows.length) {
        const empty = document.createElement('div');
        empty.className = 'text-sm text-gray-600 dark:text-gray-300';
        empty.textContent = 'No rows to display.';
        container.appendChild(empty);
        return;
    }

    const table = document.createElement('table');
    table.className = 'preview-table';
    const thead = document.createElement('thead');
    const headerRow = document.createElement('tr');
    rows[0].forEach((cell) => {
        const th = document.createElement('th');
        th.textContent = cell;
        headerRow.appendChild(th);
    });
    thead.appendChild(headerRow);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');
    const maxRows = 200;
    const renderRows = rows.slice(1, maxRows + 1);
    renderRows.forEach((row) => {
        const tr = document.createElement('tr');
        row.forEach((cell) => {
            const td = document.createElement('td');
            td.textContent = cell;
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);

    if (rows.length - 1 > maxRows) {
        const note = document.createElement('div');
        note.className = 'text-xs text-gray-500 dark:text-gray-400 mt-2';
        note.textContent = `Showing first ${maxRows} rows out of ${rows.length - 1}.`;
        container.appendChild(note);
    }
}

function parseDelimited(text, delimiter) {
    const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
    const rows = [];
    lines.forEach((line) => {
        const cells = [];
        let current = '';
        let inQuotes = false;
        for (let i = 0; i < line.length; i++) {
            const char = line[i];
            const next = line[i + 1];
            if (char === '"') {
                if (inQuotes && next === '"') {
                    current += '"';
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (!inQuotes && char === delimiter) {
                cells.push(current);
                current = '';
            } else {
                current += char;
            }
        }
        cells.push(current);
        rows.push(cells);
    });
    return rows;
}

function renderCodePreview(container, text, extension) {
    const block = renderCodeBlock(text, extension);
    container.appendChild(block);
}

function renderCodeBlock(text, extension) {
    const pre = document.createElement('pre');
    pre.className = 'code-preview max-h-[28rem] overflow-auto';
    const code = document.createElement('code');
    code.className = 'hljs';
    const limit = 20000;
    const body = text.length > limit ? `${text.slice(0, limit)}\n... (truncated)` : text;
    code.textContent = body;
    if (extension) {
        code.classList.add(`language-${extension}`);
    }
    pre.appendChild(code);
    applyHighlight(code);
    return pre;
}

function applyHighlight(codeEl) {
    if (window.hljs && codeEl) {
        try {
            hljs.highlightElement(codeEl);
        } catch (err) {
            // Highlighting is best-effort; fall back silently
        }
    }
}

function attachDownloadOverride(fileName) {
    const btn = document.getElementById('downloadButton');
    if (!btn || !previewBlob) return;

    btn.addEventListener('click', async (e) => {
        e.preventDefault();
        await ensurePreviewReady();
        await logDownload();
        if (!previewBlob) return;
        const url = URL.createObjectURL(previewBlob);
        const a = document.createElement('a');
        a.href = url;
        a.download = fileName || 'download';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 2000);
    }, {once: true});
}

function setupDeleteConfirm() {
    const startBtn = document.getElementById('deleteStartBtn');
    const confirmBtn = document.getElementById('deleteConfirmBtn');
    const cancelBtn = document.getElementById('deleteCancelBtn');
    if (!startBtn || !confirmBtn || !cancelBtn) return;

    const showConfirm = () => {
        startBtn.classList.add('hidden');
        confirmBtn.classList.remove('hidden');
        cancelBtn.classList.remove('hidden');
    };

    const reset = () => {
        startBtn.classList.remove('hidden');
        confirmBtn.classList.add('hidden');
        cancelBtn.classList.add('hidden');
    };

    startBtn.addEventListener('click', showConfirm);
    cancelBtn.addEventListener('click', reset);
}

async function ensurePreviewReady() {
    if (previewFetched && previewBlob) return;
    if (previewReadyPromise) {
        await previewReadyPromise;
    } else {
        previewReadyPromise = initPreview();
        await previewReadyPromise;
    }
}

async function logDownload() {
    const fileUuidEl = document.getElementById('fileUuid');
    const csrfMeta = document.querySelector('meta[name="_csrf"]');
    if (!fileUuidEl || !csrfMeta) return;
    try {
        await fetch(`/file/download/log/${fileUuidEl.textContent.trim()}`, {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
                'X-XSRF-TOKEN': csrfMeta.content,
                'X-CSRF-TOKEN': csrfMeta.content,
            },
        });
    } catch (e) {
        console.warn('Download log failed', e);
    }
}

function renderFolderTree() {
    const treeEl = document.getElementById('folderTree');
    if (!treeEl) return;

    const manifestScript = document.getElementById('folderManifestData');
    const folderName = treeEl.dataset.folderName || 'folder';
    if (!manifestScript || !manifestScript.textContent) {
        treeEl.textContent = 'No manifest available.';
        return;
    }

    let entries;
    try {
        entries = JSON.parse(manifestScript.textContent);
    } catch (e) {
        console.warn('Folder manifest parse failed', e);
        treeEl.textContent = 'Unable to render folder contents.';
        return;
    }

    const root = createTreeRoot(folderName);
    entries.forEach((entry) => {
        if (!entry || !entry.path) return;
        addPathToTree(root, entry.path, folderName);
    });

    const lines = [];
    printTree(root, '', true, lines, true);

    treeEl.innerHTML = '';
    const frag = document.createDocumentFragment();

    lines.forEach((segments) => {
        const lineEl = document.createElement('div');
        lineEl.style.whiteSpace = 'pre';

        segments.forEach((segment) => {
            const span = document.createElement('span');
            if (segment.type === 'root') {
                span.className = 'folder-tree-root';
            } else if (segment.type === 'folder') {
                span.className = 'folder-tree-folder';
            } else if (segment.type === 'file') {
                span.className = 'folder-tree-file';
            } else if (segment.type === 'connector') {
                span.className = 'folder-tree-connector';
            }
            span.textContent = segment.text;
            lineEl.appendChild(span);
        });

        frag.appendChild(lineEl);
    });

    treeEl.appendChild(frag);
}

function createTreeRoot(name) {
    return {name, children: [], files: []};
}

function addPathToTree(root, path, folderName) {
    const parts = path.split(/[\\/]/).filter(Boolean);
    let idx = 0;
    if (parts[0] === folderName) {
        idx = 1; // skip duplicated root segment
    }

    let node = root;
    for (; idx < parts.length; idx++) {
        const part = parts[idx];
        const isFile = idx === parts.length - 1;
        if (isFile && path && path.endsWith('/')) {
            // directory marker encoded with trailing slash
            const dirNode = createTreeRoot(part);
            node.children.push(dirNode);
            node = dirNode;
        } else if (isFile && part.includes('.')) {
            node.files.push(part);
        } else {
            let child = node.children.find((c) => c.name === part);
            if (!child) {
                child = createTreeRoot(part);
                node.children.push(child);
            }
            node = child;
        }
    }
}

function printTree(node, prefix, isLast, lines, isRoot = false) {
    const connector = prefix === '' ? '' : (isLast ? '└─ ' : '├─ ');
    const lineSegments = [
        {text: `${prefix}${connector}`, type: 'connector'},
        {text: node.name, type: isRoot ? 'root' : 'folder'},
    ];
    lines.push(lineSegments);

    const nextPrefix = prefix === '' ? '   ' : (isLast ? `${prefix}   ` : `${prefix}│  `);
    const children = [...node.children.sort((a, b) => a.name.localeCompare(b.name)), ...node.files.sort()];

    children.forEach((child, index) => {
        const lastChild = index === children.length - 1;
        if (typeof child === 'string') {
            const fileLine = [
                {text: `${nextPrefix}${lastChild ? '└─ ' : '├─ '}`, type: 'connector'},
                {text: child, type: 'file'},
            ];
            lines.push(fileLine);
        } else {
            printTree(child, nextPrefix, lastChild, lines, false);
        }
    });
}

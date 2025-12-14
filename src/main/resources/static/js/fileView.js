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

        if (isImage) {
            const img = document.createElement('img');
            img.src = URL.createObjectURL(blob);
            img.alt = 'Preview';
            img.className = 'max-h-96 rounded-lg shadow';
            content.appendChild(img);
        } else if (isText) {
            const text = await blob.text();
            const pre = document.createElement('pre');
            pre.className = 'text-xs bg-slate-100 dark:bg-slate-900 text-slate-800 dark:text-slate-100 p-3 rounded-lg max-h-96 overflow-auto whitespace-pre-wrap';
            const limit = 20000;
            pre.textContent = text.length > limit ? text.slice(0, limit) + '\n... (truncated)' : text;
            content.appendChild(pre);
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

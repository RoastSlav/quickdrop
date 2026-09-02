function getI18nStr(path, defaultStr) {
    if (!window.i18n || !window.i18n.fileView) return defaultStr;
    const parts = path.split('.');
    let obj = window.i18n.fileView;
    for (const part of parts) {
        if (obj[part] === undefined) return defaultStr;
        obj = obj[part];
    }
    return obj;
}

function getCsrfToken() {
    return document.querySelector('meta[name="_csrf"]')?.content ?? '';
}

async function updateCheckboxState(event, checkbox) {
    event.preventDefault();

    const hiddenField = checkbox.form.querySelector(
        `input[name="${checkbox.name}"][type="hidden"]`
    );
    if (hiddenField) hiddenField.value = checkbox.checked;

    updateFileViewBadges(checkbox.name, checkbox.checked);

    try {
        const res = await fetch(checkbox.form.action, {
            method: 'POST',
            body: new FormData(checkbox.form)
        });
        if (!res.ok && !res.redirected) throw new Error(res.status);
        window.QD?.flashSaved(checkbox.closest('.toggle'));
    } catch {
        checkbox.checked = !checkbox.checked;
        if (hiddenField) hiddenField.value = checkbox.checked;
        updateFileViewBadges(checkbox.name, checkbox.checked);
        window.notify(getI18nStr('toggleFailed', 'Could not save that change. Please try again.'));
    }
}

function updateFileViewBadges(name, checked) {
    if (name === 'keepIndefinitely') {
        document.getElementById('badge-permanent')?.classList.toggle('hidden', !checked);
        document.getElementById('badge-expires')?.classList.toggle('hidden', checked);
        const renewForm = document.getElementById('renewForm');
        renewForm?.classList.toggle('hidden', checked);
    } else if (name === 'hidden') {
        document.getElementById('badge-hidden')?.classList.toggle('hidden', !checked);
    }
}

function showPreparingMessage() {
    document.getElementById("preparingMessage")?.removeAttribute("hidden");
}

function setupDownloadPreparingMessage() {
    const btn = document.getElementById("downloadButton");
    if (!btn || btn.dataset.showPreparing !== "true") return;
    btn.addEventListener("click", showPreparingMessage);
}

Object.assign(window, {
    copyShareLink,
    createShareLink,
    copyPublicLink,
    showPreparingMessage,
    toggleDownloadLimit,
    toggleExpirationLimit,
    updateCheckboxState,
});

function initializeModal() {
    updateShareLink("");

    if (!isShareLinksEnabled()) {
        return;
    }

    const daysValidInput = document.getElementById("daysValid");
    const downloadsInput = document.getElementById(
        "allowedNumberOfDownloadsCount"
    );
    const noExpiration = document.getElementById("noExpiration");
    const unlimitedDownloads = document.getElementById("unlimitedDownloads");

    if (daysValidInput) {
        daysValidInput.disabled = false;
        daysValidInput.value = "30";
    }
    if (downloadsInput) {
        downloadsInput.disabled = false;
        downloadsInput.value = "1";
    }
    if (noExpiration) {
        noExpiration.checked = false;
    }
    if (unlimitedDownloads) {
        unlimitedDownloads.checked = false;
    }

    setupSimplifiedShareLinks();
}

function isSimplifiedShareLinksEnabled() {
    const panel = document.getElementById("sharePanel");
    return (
        isShareLinksEnabled() && !isPubliclyAccessible() &&
        panel?.dataset?.simplifiedShareLinks === "true"
    );
}

/** Nothing gates the file, so the panel offers the page link instead of minting a share link. */
function isPubliclyAccessible() {
    const panel = document.getElementById("sharePanel");
    return panel?.dataset?.publiclyAccessible === "true";
}

function copyPublicLink() {
    const input = document.getElementById("publicLink");
    const button = document.getElementById("copyPublicLinkButton");
    if (!input?.value || !button) return;

    const flash = (state) => {
        button.textContent = getI18nStr(
            state === "success" ? "copied" : "failed",
            state === "success" ? "Copied" : "Failed"
        );
        button.style.background = state === "success" ? "var(--c-emerald)" : "var(--c-danger)";
        button.style.color = "#ffffff";
        setTimeout(() => {
            button.textContent = getI18nStr("copy", "Copy");
            button.style.background = "";
            button.style.color = "";
        }, 1500);
    };

    navigator.clipboard.writeText(input.value).then(() => flash("success")).catch(() => flash("error"));
}

function isShareLinksEnabled() {
    const panel = document.getElementById("sharePanel");
    return panel?.dataset?.shareLinksEnabled === "true";
}

function disableShareOptionsForSimplifiedMode() {
    const inputs = [
        document.getElementById("daysValid"),
        document.getElementById("allowedNumberOfDownloadsCount"),
        document.getElementById("noExpiration"),
        document.getElementById("unlimitedDownloads"),
        document.getElementById("generateLinkButton"),
    ];
    inputs.forEach((el) => {
        if (!el) return;
        el.disabled = true;
        el.classList.add("opacity-60", "cursor-not-allowed");
    });
}

function setupSimplifiedShareLinks() {
    if (!isSimplifiedShareLinksEnabled()) {
        return;
    }

    disableShareOptionsForSimplifiedMode();

    const fileUuidEl = document.getElementById("fileUuid");
    const fileUuid = fileUuidEl?.textContent?.trim();
    if (!fileUuid) return;

    const spinner = document.getElementById("spinner");
    if (spinner) {
        spinner.classList.remove("hidden");
        spinner.style.display = "inline-block";
    }

    generateShareLink(fileUuid, null, null)
        .then((shareLink) => updateShareLink(shareLink))
        .catch((error) => {
            console.error("Failed to auto-generate share link", error);
        })
        .finally(() => {
            if (spinner) {
                spinner.classList.add("hidden");
                spinner.style.display = "none";
            }
        });
}

function generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads) {
    if (!isShareLinksEnabled()) {
        return Promise.reject(new Error("Share links are disabled."));
    }

    const csrfToken = getCsrfToken();
    const params = new URLSearchParams();

    if (typeof daysValid === "number" && daysValid > 0) {
        const expirationDate = new Date();
        expirationDate.setDate(expirationDate.getDate() + daysValid);
        params.append("expirationDate", expirationDate.toISOString().split("T")[0]);
    }

    if (
        allowedNumberOfDownloads !== null &&
        allowedNumberOfDownloads !== undefined
    ) {
        params.append("nOfDownloads", allowedNumberOfDownloads);
    }

    const query = params.toString();
    const url = query
        ? `/api/file/share/${fileUuid}?${query}`
        : `/api/file/share/${fileUuid}`;

    return fetch(url, {
        method: "POST",
        credentials: "same-origin",
        headers: {
            "Content-Type": "application/json",
            "X-XSRF-TOKEN": csrfToken,
            Accept: "application/json",
        },
    }).then(async (response) => {
        let data = {};
        try {
            data = await response.json();
        } catch (_) {
            // Non-JSON response; leave data empty for error handling below.
        }

        if (!response.ok) {
            const message = data?.message || "Failed to generate share link";
            throw new Error(message);
        }

        if (data.preparingMessage === "true" || data.preparingMessage === true) {
            window.toast?.(getI18nStr('sharePreparingNotice', 'Your share link will be functional in a couple of minutes.'), 'info');
        }

        const sharePath =
            data.sharePath || (data.token ? `/share/${data.token}` : "");
        if (!sharePath) return "";

        const absolute = new URL(sharePath, window.location.origin).toString();
        return absolute;
    });
}

function setCopyButtonState(state) {
    const button = document.getElementById("copyShareButton");
    if (!button) return;
    const label = getI18nStr(
        state === 'success' ? 'copied' : state === 'error' ? 'failed' : 'copy',
        state === 'success' ? 'Copied' : state === 'error' ? 'Failed' : 'Copy'
    );
    button.textContent = label;
    button.style.background = state === 'success' ? 'var(--c-emerald)'
        : state === 'error' ? 'var(--c-danger)'
            : '';
    button.style.color = (state === 'success' || state === 'error') ? '#ffffff' : '';
}

function copyShareLink() {
    const shareLinkInput = document.getElementById("shareLink");
    if (!shareLinkInput.value) {
        setCopyButtonState("error");
        setTimeout(() => setCopyButtonState("default"), 1500);
        return;
    }

    navigator.clipboard
        .writeText(shareLinkInput.value)
        .then(() => {
            setCopyButtonState("success");
            setTimeout(() => setCopyButtonState("default"), 1500);
        })
        .catch((err) => {
            console.error("Failed to copy link:", err);
            setCopyButtonState("error");
            setTimeout(() => setCopyButtonState("default"), 1500);
        });
}

function createShareLink() {
    if (!isShareLinksEnabled()) {
        window.notify(getI18nStr('shareDisabled', 'Share links are disabled by the administrator.'), 'warning');
        return;
    }

    const fileUuid = document.getElementById("fileUuid").textContent.trim();
    const daysValidInput = document.getElementById("daysValid");
    const noExpiration = document.getElementById("noExpiration");
    const daysValid = parseInt(daysValidInput.value, 10);
    const allowedNumberOfDownloadsInput = document.getElementById(
        "allowedNumberOfDownloadsCount"
    );
    const unlimitedDownloads = document.getElementById("unlimitedDownloads");
    const allowedNumberOfDownloads = parseInt(
        allowedNumberOfDownloadsInput.value,
        10
    );

    if (!noExpiration.checked && !isNaN(daysValid) && daysValid < 0) {
        window.notify(getI18nStr('daysValidNonNegative', 'Days valid cannot be negative.'));
        return;
    }

    if (
        !unlimitedDownloads.checked &&
        !isNaN(allowedNumberOfDownloads) &&
        allowedNumberOfDownloads < 0
    ) {
        window.notify(getI18nStr('allowedDownloadsNonNegative', 'Allowed downloads cannot be negative.'));
        return;
    }

    const spinner = document.getElementById("spinner");
    const generateLinkButton = document.getElementById("generateLinkButton");

    if (spinner) {
        spinner.classList.remove("hidden");
        spinner.style.display = "inline-block";
    }
    generateLinkButton.disabled = true;

    const effectiveDaysValid =
        noExpiration.checked || isNaN(daysValid) || daysValid === 0
            ? null
            : daysValid;
    const effectiveDownloads =
        unlimitedDownloads.checked ||
        isNaN(allowedNumberOfDownloads) ||
        allowedNumberOfDownloads === 0
            ? null
            : allowedNumberOfDownloads;

    generateShareLink(fileUuid, effectiveDaysValid, effectiveDownloads)
        .then((shareLink) => {
            updateShareLink(shareLink);
        })
        .catch((error) => {
            console.error(error);
            const msg = error?.message || getI18nStr('shareGenerateFailed', 'Failed to generate share link.');
            announceShareStatus(msg);
            window.notify(msg);
        })
        .finally(() => {
            if (spinner) {
                spinner.classList.add("hidden");
                spinner.style.display = "none";
            }
            generateLinkButton.disabled = false;
        });
}

function announceShareStatus(message) {
    const el = document.getElementById("shareStatus");
    if (!el) return;
    // Clear first so re-announcing the same message still fires the live region
    el.textContent = "";
    requestAnimationFrame(() => {
        el.textContent = message;
    });
}

function updateShareLink(link) {
    const shareLinkInput = document.getElementById("shareLink");
    const qrImage = document.getElementById("shareQRCode");
    const qrContainer = document.getElementById("shareQRCodeContainer");
    const copyRow = document.getElementById("shareCopyRow");
    const divider = document.getElementById("shareDivider");

    if (!shareLinkInput || !qrImage || !qrContainer) {
        return;
    }

    shareLinkInput.value = link || "";

    const hasLink = !!link;
    copyRow?.classList.toggle("hidden", !hasLink);
    divider?.classList.toggle("hidden", !hasLink);

    if (hasLink) {
        announceShareStatus("Share link generated.");
    }

    if (!link) {
        qrContainer.classList.add("hidden");
        qrImage.removeAttribute("src");
        return;
    }

    qrContainer.classList.remove("hidden");
    // The share link is always /share/{code} (or, once the general shortener resolver
    // exists, /{prefix}/{code}) -- either way the code is the last path segment, so this
    // works without needing generateShareLink() to separately thread the code through.
    const code = new URL(link, window.location.origin).pathname.split("/").filter(Boolean).pop();
    qrImage.src = `/api/link/${encodeURIComponent(code)}/qr.svg?size=150`;
}

function toggleExpirationLimit() {
    const checkbox = document.getElementById("noExpiration");
    const input = document.getElementById("daysValid");
    if (!checkbox || !input) return;

    if (checkbox.checked) {
        input.disabled = true;
        input.value = "";
    } else {
        input.disabled = false;
        if (!input.value) {
            input.value = "30";
        }
    }
}

function toggleDownloadLimit() {
    const checkbox = document.getElementById("unlimitedDownloads");
    const input = document.getElementById("allowedNumberOfDownloadsCount");
    if (!checkbox || !input) return;

    if (checkbox.checked) {
        input.disabled = true;
        input.value = "";
    } else {
        input.disabled = false;
        if (!input.value) {
            input.value = "1";
        }
    }
}


document.addEventListener("DOMContentLoaded", () => {
    initializeModal();
    setupDownloadPreparingMessage();
    setupPreviewInit();
    if (window.hljs && typeof window.hljs.highlightAll === "function") {
        window.hljs.highlightAll();
    }
});

let previewBlob = null;
let previewFetched = false;
let previewFetching = false;
let previewReadyPromise = null;

function setupPreviewInit() {
    const container = document.getElementById("previewContainer");
    if (!container) return;
    const requireManual = container.dataset.requireManual === "true";
    const loadBtn = document.getElementById("loadPreviewBtn");

    const startFetch = () => {
        if (!previewReadyPromise) {
            previewReadyPromise = initPreview();
        }
    };

    if (requireManual && loadBtn) {
        loadBtn.addEventListener("click", startFetch, {once: true});
    } else {
        window.addEventListener("load", () => startFetch(), {once: true});
    }
}

async function initPreview() {
    const container = document.getElementById("previewContainer");
    const content = document.getElementById("previewContent");
    const status = document.getElementById("previewStatus");
    if (!container || !content) return;

    let previewUrl = container.dataset.previewUrl;
    const isImage = container.dataset.previewImage === "true";
    const isText = container.dataset.previewText === "true";
    const isPdf = container.dataset.previewPdf === "true";
    const isJson = container.dataset.previewJson === "true";
    const isCsv = container.dataset.previewCsv === "true";
    const previewType = container.dataset.previewType || "";
    const fileName = container.dataset.fileName || "download";
    const requireManual = container.dataset.requireManual === "true";

    if (previewFetching || previewFetched) return;
    previewFetching = true;

    try {
        if (requireManual) {
            previewUrl = `${previewUrl}?manual=true`;
        }

        const resp = await fetch(previewUrl, {credentials: "same-origin"});
        if (!resp.ok) throw new Error("Preview unavailable");
        const blob = await resp.blob();
        previewBlob = blob;
        previewFetched = true;

        if (status) status.remove();
        content.innerHTML = "";

        const objectUrl = URL.createObjectURL(blob);
        if (isImage) {
            content.classList.remove("preview-stack");
            renderImagePreview(content, objectUrl);
        } else if (isPdf || previewType === "pdf") {
            content.classList.add("preview-stack");
            renderPdfPreview(content, objectUrl, fileName);
        } else {
            content.classList.add("preview-stack");
            const text = await blob.text();
            const extension = extractExtension(fileName);
            if (isJson || previewType === "json") {
                renderJsonPreview(content, text);
            } else if (isCsv || previewType === "csv") {
                renderCsvPreview(content, text, extension);
            } else if (isText || previewType === "text") {
                renderCodePreview(content, text, extension);
            }
        }

        attachDownloadOverride(fileName);
    } catch (e) {
        if (status) {
            status.textContent = getI18nStr('previewUnavailable', 'Preview unavailable.');
            status.className = "text-sm text-red-600 dark:text-red-400";
        }
    }
    previewFetching = false;
}

function extractExtension(name) {
    const idx = name.lastIndexOf(".");
    if (idx === -1 || idx === name.length - 1) return "";
    return name.slice(idx + 1).toLowerCase();
}

function normalizeLanguageExtension(ext) {
    switch (ext) {
        case "cxx":
        case "hpp":
        case "hh":
            return "cpp";
        case "h":
            return "c";
        case "js":
            return "javascript";
        case "ts":
            return "typescript";
        case "jsx":
            return "javascript";
        case "tsx":
            return "typescript";
        case "py":
            return "python";
        case "rb":
            return "ruby";
        case "sh":
        case "bash":
        case "zsh":
            return "shell";
        case "rs":
            return "rust";
        case "cs":
            return "csharp";
        case "yml":
            return "yaml";
        case "md":
            return "markdown";
        case "htm":
            return "html";
        default:
            return ext;
    }
}

function renderImagePreview(container, objectUrl) {
    const img = document.createElement("img");
    img.src = objectUrl;
    img.alt = getI18nStr('previewImageAlt', 'Preview');
    img.className = "max-h-[28rem] rounded-lg shadow";
    img.onload = () => URL.revokeObjectURL(objectUrl);
    container.appendChild(img);
}

function renderPdfPreview(container, objectUrl, fileName) {
    const frame = document.createElement("object");
    frame.type = "application/pdf";
    frame.data = objectUrl;
    frame.className = "preview-pdf-frame";
    const fallback = document.createElement("div");
    fallback.className = "text-sm text-gray-600 dark:text-gray-300 mt-2";
    const link = document.createElement("a");
    link.href = objectUrl;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = getI18nStr('previewOpenInNewTab', 'Open {0} in a new tab').replace('{0}', fileName);
    fallback.appendChild(link);
    frame.appendChild(fallback);
    container.appendChild(frame);
}

function renderJsonPreview(container, text) {
    let parsed;
    try {
        parsed = JSON.parse(text);
    } catch (err) {
        renderCodePreview(container, text, "json");
        return;
    }

    const toolbar = document.createElement("div");
    toolbar.className = "preview-toolbar";
    const formattedBtn = document.createElement("button");
    formattedBtn.type = "button";
    formattedBtn.textContent = getI18nStr('jsonFormatted', 'Formatted');
    formattedBtn.className = "preview-toggle-button active";
    const treeBtn = document.createElement("button");
    treeBtn.type = "button";
    treeBtn.textContent = getI18nStr('jsonTree', 'Tree');
    treeBtn.className = "preview-toggle-button";
    toolbar.append(formattedBtn, treeBtn);

    const formatted = renderCodeBlock(JSON.stringify(parsed, null, 2), "json");
    const tree = document.createElement("div");
    tree.className = "json-tree hidden";
    tree.appendChild(buildJsonNode(parsed, null));

    const swap = (showTree) => {
        if (showTree) {
            tree.classList.remove("hidden");
            formatted.classList.add("hidden");
            treeBtn.classList.add("active");
            formattedBtn.classList.remove("active");
        } else {
            tree.classList.add("hidden");
            formatted.classList.remove("hidden");
            formattedBtn.classList.add("active");
            treeBtn.classList.remove("active");
        }
    };

    formattedBtn.addEventListener("click", () => swap(false));
    treeBtn.addEventListener("click", () => swap(true));

    container.append(toolbar, formatted, tree);
}

function buildJsonNode(value, label) {
    const wrapper = document.createElement("div");
    wrapper.className = "json-node";

    if (value !== null && typeof value === "object") {
        const isArray = Array.isArray(value);
        const details = document.createElement("details");
        details.open = true;
        const summary = document.createElement("summary");
        summary.textContent = label
            ? `${label} ${isArray ? "[ ]" : "{ }"}`
            : isArray
                ? "[ ]"
                : "{ }";
        details.appendChild(summary);
        Object.entries(value).forEach(([key, val]) => {
            details.appendChild(buildJsonNode(val, key));
        });
        wrapper.appendChild(details);
    } else {
        const leaf = document.createElement("div");
        leaf.className = "json-leaf";
        const name = label ? `${label}: ` : "";
        leaf.textContent = `${name}${String(value)}`;
        wrapper.appendChild(leaf);
    }

    return wrapper;
}

function renderCsvPreview(container, text, extension) {
    const delimiter = extension === "tsv" ? "\t" : ",";
    const rows = parseDelimited(text, delimiter);
    if (!rows.length) {
        const empty = document.createElement("div");
        empty.className = "text-sm text-gray-600 dark:text-gray-300";
        empty.textContent = getI18nStr('csvNoRows', 'No rows to display.');
        container.appendChild(empty);
        return;
    }

    const table = document.createElement("table");
    table.className = "preview-table";
    const thead = document.createElement("thead");
    const headerRow = document.createElement("tr");
    rows[0].forEach((cell) => {
        const th = document.createElement("th");
        th.textContent = cell;
        headerRow.appendChild(th);
    });
    thead.appendChild(headerRow);
    table.appendChild(thead);

    const tbody = document.createElement("tbody");
    const maxRows = 200;
    const renderRows = rows.slice(1, maxRows + 1);
    renderRows.forEach((row) => {
        const tr = document.createElement("tr");
        row.forEach((cell) => {
            const td = document.createElement("td");
            td.textContent = cell;
            tr.appendChild(td);
        });
        tbody.appendChild(tr);
    });
    table.appendChild(tbody);
    container.appendChild(table);

    if (rows.length - 1 > maxRows) {
        const note = document.createElement("div");
        note.className = "text-xs text-gray-500 dark:text-gray-400 mt-2";
        note.textContent = `Showing first ${maxRows} rows out of ${
            rows.length - 1
        }.`;
        container.appendChild(note);
    }
}

/**
 * Parses delimited text (CSV or TSV) into a 2D array of strings.
 * Handles RFC 4180 double-quoted fields and escaped quotes (`""`).
 * @param {string} text      - Raw file content
 * @param {string} delimiter - Field delimiter (',' for CSV, '\t' for TSV)
 * @returns {string[][]} Rows of cells; first row is the header
 */
function parseDelimited(text, delimiter) {
    const lines = text.split(/\r?\n/).filter((l) => l.trim().length > 0);
    const rows = [];
    lines.forEach((line) => {
        const cells = [];
        let current = "";
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
                current = "";
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
    const block = renderCodeBlock(text, normalizeLanguageExtension(extension));
    container.appendChild(block);
}

function renderCodeBlock(text, extension) {
    const pre = document.createElement("pre");
    pre.className = "code-preview max-h-[28rem] overflow-auto";
    const code = document.createElement("code");
    const limit = 20000;
    const body =
        text.length > limit ? `${text.slice(0, limit)}\n${getI18nStr('previewCodeTruncated', '... (truncated)')}` : text;
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
    const btn = document.getElementById("downloadButton");
    if (!btn || !previewBlob) return;

    btn.addEventListener(
        "click",
        async (e) => {
            e.preventDefault();
            await ensurePreviewReady();
            await logDownload();
            if (!previewBlob) return;
            const url = URL.createObjectURL(previewBlob);
            const a = document.createElement("a");
            a.href = url;
            a.download = fileName || "download";
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            setTimeout(() => URL.revokeObjectURL(url), 2000);
        },
        {once: true}
    );
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
    const fileUuidEl = document.getElementById("fileUuid");
    if (!fileUuidEl) return;
    const csrf = getCsrfToken();
    try {
        await fetch(`/file/download/log/${fileUuidEl.textContent.trim()}`, {
            method: "POST",
            credentials: "same-origin",
            headers: {
                "X-XSRF-TOKEN": csrf,
                "X-CSRF-TOKEN": csrf,
            },
        });
    } catch (e) {
        console.warn("Download log failed", e);
    }
}

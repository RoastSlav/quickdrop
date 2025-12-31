function confirmDelete() {
  return confirm(
    "Are you sure you want to delete this file? This action cannot be undone."
  );
}

function updateCheckboxState(event, checkbox) {
  event.preventDefault();

  const hiddenField = checkbox.form.querySelector(
    `input[name="${checkbox.name}"][type="hidden"]`
  );
  if (hiddenField) {
    hiddenField.value = checkbox.checked;
  }

  checkbox.form.submit();
}

function initializeModal() {
  updateShareLink("");

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
}

function generateShareLink(fileUuid, daysValid, allowedNumberOfDownloads) {
  const csrfToken = document.querySelector('meta[name="_csrf"]').content;
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
    },
  }).then((response) => {
    if (!response.ok) throw new Error("Failed to generate share link");
    return response.text();
  });
}

function setCopyButtonState(state) {
  const button = document.getElementById("copyShareButton");
  if (!button) return;

  const baseClasses = [
    "w-full",
    "sm:w-auto",
    "shrink-0",
    "rounded-lg",
    "text-white",
    "font-medium",
    "px-4",
    "py-2",
    "transition-colors",
    "active:scale-95",
    "focus:outline-none",
    "focus:ring-2",
  ];
  const skyClasses = [
    "bg-sky-500",
    "hover:bg-sky-600",
    "dark:bg-sky-400",
    "dark:hover:bg-sky-500",
  ];
  const greenClasses = [
    "bg-green-600",
    "hover:bg-green-700",
    "dark:bg-green-500",
    "dark:hover:bg-green-600",
  ];
  const redClasses = [
    "bg-red-600",
    "hover:bg-red-700",
    "dark:bg-red-600",
    "dark:hover:bg-red-500",
  ];

  button.className = [
    ...baseClasses,
    ...(state === "success"
      ? greenClasses
      : state === "error"
      ? redClasses
      : skyClasses),
  ].join(" ");

  // Inline fallback colors to avoid transient invisibility if Tailwind classes are purged in some builds
  const bgFallback =
    state === "success" ? "#16a34a" : state === "error" ? "#dc2626" : "#0284c7";
  button.style.backgroundColor = bgFallback;
  button.style.color = "#ffffff";

  if (state === "success") {
    button.textContent = "Copied";
  } else if (state === "error") {
    button.textContent = "Failed";
  } else {
    button.textContent = "Copy";
  }
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
    alert("Days valid cannot be negative.");
    return;
  }

  if (
    !unlimitedDownloads.checked &&
    !isNaN(allowedNumberOfDownloads) &&
    allowedNumberOfDownloads < 0
  ) {
    alert("Allowed downloads cannot be negative.");
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
      updateShareLink(shareLink); // Update with the token-based link
    })
    .catch((error) => {
      console.error(error);
      alert("Failed to generate share link.");
    })
    .finally(() => {
      if (spinner) {
        spinner.classList.add("hidden");
        spinner.style.display = "none";
      }
      generateLinkButton.disabled = false;
    });
}

function updateShareLink(link) {
  const shareLinkInput = document.getElementById("shareLink");
  const canvas = document.getElementById("shareQRCode");
  const qrContainer = document.getElementById("shareQRCodeContainer");

  shareLinkInput.value = link || "";

  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, canvas.width, canvas.height);

  if (!link) {
    if (qrContainer) qrContainer.classList.add("hidden");
    return;
  }

  if (qrContainer) qrContainer.classList.remove("hidden");
  QRCode.toCanvas(canvas, link, { width: 150, height: 150 });
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

function openShareModal() {
  const modal = document.getElementById("shareModal");
  modal.classList.remove("hidden");
  positionShareModal();
}

function closeShareModal() {
  document.getElementById("shareModal").classList.add("hidden");
}

function positionShareModal() {
  const card = document.getElementById("fileInfoCard");
  const modal = document.getElementById("shareModalContent");
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

document.addEventListener("DOMContentLoaded", () => {
  initializeModal();
  renderFolderTree();
  setupPreviewInit();
  setupDeleteConfirm();
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
    loadBtn.addEventListener("click", startFetch, { once: true });
  } else {
    window.addEventListener("load", () => startFetch(), { once: true });
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

    const resp = await fetch(previewUrl, { credentials: "same-origin" });
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
      status.textContent = "Preview unavailable.";
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
  img.alt = "Preview";
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
    renderCodePreview(container, text, "json");
    return;
  }

  const toolbar = document.createElement("div");
  toolbar.className = "preview-toolbar";
  const formattedBtn = document.createElement("button");
  formattedBtn.type = "button";
  formattedBtn.textContent = "Formatted";
  formattedBtn.className = "preview-toggle-button active";
  const treeBtn = document.createElement("button");
  treeBtn.type = "button";
  treeBtn.textContent = "Tree";
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
    empty.textContent = "No rows to display.";
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
    text.length > limit ? `${text.slice(0, limit)}\n... (truncated)` : text;
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
    { once: true }
  );
}

function setupDeleteConfirm() {
  const startBtn = document.getElementById("deleteStartBtn");
  const confirmBtn = document.getElementById("deleteConfirmBtn");
  const cancelBtn = document.getElementById("deleteCancelBtn");
  if (!startBtn || !confirmBtn || !cancelBtn) return;

  const showConfirm = () => {
    startBtn.classList.add("hidden");
    confirmBtn.classList.remove("hidden");
    cancelBtn.classList.remove("hidden");
  };

  const reset = () => {
    startBtn.classList.remove("hidden");
    confirmBtn.classList.add("hidden");
    cancelBtn.classList.add("hidden");
  };

  startBtn.addEventListener("click", showConfirm);
  cancelBtn.addEventListener("click", reset);
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
  const csrfMeta = document.querySelector('meta[name="_csrf"]');
  if (!fileUuidEl || !csrfMeta) return;
  try {
    await fetch(`/file/download/log/${fileUuidEl.textContent.trim()}`, {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "X-XSRF-TOKEN": csrfMeta.content,
        "X-CSRF-TOKEN": csrfMeta.content,
      },
    });
  } catch (e) {
    console.warn("Download log failed", e);
  }
}

function renderFolderTree() {
  const treeEl = document.getElementById("folderTree");
  if (!treeEl) return;

  const manifestScript = document.getElementById("folderManifestData");
  const folderName = treeEl.dataset.folderName || "folder";
  if (!manifestScript || !manifestScript.textContent) {
    treeEl.textContent = "No manifest available.";
    return;
  }

  let entries;
  try {
    entries = JSON.parse(manifestScript.textContent);
  } catch (e) {
    console.warn("Folder manifest parse failed", e);
    treeEl.textContent = "Unable to render folder contents.";
    return;
  }

  const root = createTreeRoot(folderName);
  entries.forEach((entry) => {
    if (!entry || !entry.path) return;
    addPathToTree(root, entry.path, folderName);
  });

  const lines = [];
  printTree(root, "", true, lines, true);

  treeEl.innerHTML = "";
  const frag = document.createDocumentFragment();

  lines.forEach((segments) => {
    const lineEl = document.createElement("div");
    lineEl.style.whiteSpace = "pre";

    segments.forEach((segment) => {
      const span = document.createElement("span");
      if (segment.type === "root") {
        span.className = "folder-tree-root";
      } else if (segment.type === "folder") {
        span.className = "folder-tree-folder";
      } else if (segment.type === "file") {
        span.className = "folder-tree-file";
      } else if (segment.type === "connector") {
        span.className = "folder-tree-connector";
      }
      span.textContent = segment.text;
      lineEl.appendChild(span);
    });

    frag.appendChild(lineEl);
  });

  treeEl.appendChild(frag);
}

function createTreeRoot(name) {
  return { name, children: [], files: [] };
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
    if (isFile && path && path.endsWith("/")) {
      // directory marker encoded with trailing slash
      const dirNode = createTreeRoot(part);
      node.children.push(dirNode);
      node = dirNode;
    } else if (isFile && part.includes(".")) {
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
  const connector = prefix === "" ? "" : isLast ? "└─ " : "├─ ";
  const lineSegments = [
    { text: `${prefix}${connector}`, type: "connector" },
    { text: node.name, type: isRoot ? "root" : "folder" },
  ];
  lines.push(lineSegments);

  const nextPrefix =
    prefix === "" ? "   " : isLast ? `${prefix}   ` : `${prefix}│  `;
  const children = [
    ...node.children.sort((a, b) => a.name.localeCompare(b.name)),
    ...node.files.sort(),
  ];

  children.forEach((child, index) => {
    const lastChild = index === children.length - 1;
    if (typeof child === "string") {
      const fileLine = [
        {
          text: `${nextPrefix}${lastChild ? "└─ " : "├─ "}`,
          type: "connector",
        },
        { text: child, type: "file" },
      ];
      lines.push(fileLine);
    } else {
      printTree(child, nextPrefix, lastChild, lines, false);
    }
  });
}

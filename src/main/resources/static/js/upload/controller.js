import {
  UploadState,
  setUploadState,
  renderStripWarning,
  clearStripWarning,
  showMessage,
  getUIRefs,
} from "./state.js";
import { buildSingleCandidates } from "./metadata-pipeline.js";
import { buildFolderCandidates, parseSize } from "./zip-builder.js";
import { uploadCandidate } from "./network.js";

export function initUploadPage(config = {}) {
  const ui = getUIRefs();
  const form = document.getElementById("uploadForm");
  const dropZone = document.getElementById("dropZone");
  const fileInput = document.getElementById("file");
  const folderInput = document.getElementById("folderInput");
  const fileNameEl = document.getElementById("selectedFile");
  const dropZoneText = document.getElementById("dropZoneInstructions");
  const uploadIndicator = document.getElementById("uploadIndicator");
  const uploadStatus = document.getElementById("uploadStatus");
  const uploadProgress = document.getElementById("uploadProgress");

  const defaultText = dropZoneText
    ? dropZoneText.dataset.defaultText || dropZoneText.textContent
    : "";
  const uploadPasswordEnabled = config.uploadPasswordEnabled !== false;
  const metadataEnabled = config.metadataEnabled === true;

  let uploadCandidates = null;
  let processingToken = 0;
  let isUploading = false;
  let uploadState = UploadState.IDLE;

  const getRelativePath = (file) =>
    file?.relativePath || file?.webkitRelativePath || file?.path || file?.name;

  const withRelativePath = (file, rel) => {
    try {
      const cloned = new File([file], file.name, {
        type: file.type,
        lastModified: file.lastModified,
      });
      Object.defineProperty(cloned, "relativePath", { value: rel });
      try {
        Object.defineProperty(cloned, "webkitRelativePath", {
          value: rel,
          configurable: true,
        });
      } catch (_) {}
      return cloned;
    } catch (_) {
      try {
        file.relativePath = rel;
      } catch (_) {}
      return file;
    }
  };
  const maxSizeSpan = document.querySelector(".maxFileSize");
  const maxSizeLabel = maxSizeSpan ? maxSizeSpan.innerText : "the allowed";
  const maxSize = maxSizeSpan ? parseSize(maxSizeSpan.innerText) : Infinity;

  function applyState(state) {
    uploadState = state;
    setUploadState(state, ui);
  }

  function resetUploadUI() {
    uploadIndicator?.classList.add("hidden");
    isUploading = false;
    uploadCandidates = null;
    clearStripWarning(ui);
    applyState(UploadState.IDLE);

    if (fileInput) fileInput.value = "";
    if (folderInput) folderInput.value = "";
    if (fileNameEl) {
      fileNameEl.textContent = "";
      fileNameEl.classList.add("hidden");
    }
    if (dropZoneText && typeof defaultText === "string") {
      dropZoneText.textContent = defaultText;
      dropZoneText.classList.remove("hidden");
    }
  }

  function resetFileSelection() {
    isUploading = false;
    uploadCandidates = null;
    processingToken++;
    if (fileInput) fileInput.value = "";
    if (folderInput) folderInput.value = "";
    if (fileNameEl) {
      fileNameEl.textContent = "";
      fileNameEl.classList.add("hidden");
    }
    if (dropZoneText) {
      dropZoneText.textContent = defaultText;
      dropZoneText.classList.remove("hidden");
    }
    clearStripWarning(ui);
    applyState(UploadState.IDLE);
  }

  async function handleSingleFile(files) {
    if (!fileNameEl || !dropZoneText) return;

    if (!files || files.length === 0) {
      resetFileSelection();
      return;
    }

    const file = files[0];
    if (file.size > maxSize) {
      dropZoneText.textContent = `File exceeds the ${maxSizeLabel} limit.`;
      dropZoneText.classList.remove("hidden");
      fileNameEl.textContent = "";
      fileNameEl.classList.add("hidden");
      if (fileInput) fileInput.value = "";
      uploadCandidates = null;
      applyState(UploadState.IDLE);
      return;
    }

    const size = (file.size / (1024 * 1024)).toFixed(2) + " MB";
    fileNameEl.textContent = `${file.name} (${size})`;
    fileNameEl.classList.remove("hidden");
    dropZoneText.textContent = defaultText;
    dropZoneText.classList.add("hidden");
    if (folderInput) folderInput.value = "";

    const token = ++processingToken;
    applyState(UploadState.PROCESSING);

    const candidates = await buildSingleCandidates(file, metadataEnabled);
    if (token !== processingToken) return;

    uploadCandidates = { ...candidates, source: "single" };
    if (candidates.failures.length > 0) {
      applyState(UploadState.NEEDS_CONFIRMATION);
      renderStripWarning(candidates.failures, "single", ui);
    } else {
      applyState(UploadState.READY);
      if (candidates.warnings.length > 0) {
        renderStripWarning(candidates.warnings, "single", ui, {
          type: "warning",
        });
      }
    }
  }

  async function handleFolderSelection(fileList) {
    if (!fileList || fileList.length === 0) {
      resetFileSelection();
      return;
    }

    let totalOriginalSize = 0;
    for (const file of fileList) totalOriginalSize += file.size;
    if (totalOriginalSize > maxSize) {
      showMessage("danger", `Folder exceeds the ${maxSizeLabel} limit.`);
      resetFileSelection();
      return;
    }

    if (dropZoneText) {
      dropZoneText.textContent = "Processing folder...";
      dropZoneText.classList.remove("hidden");
    }
    if (fileNameEl) fileNameEl.classList.add("hidden");

    const token = ++processingToken;
    applyState(UploadState.PROCESSING);

    let candidates;
    try {
      candidates = await buildFolderCandidates(fileList, { metadataEnabled });
    } catch (err) {
      console.error("Folder processing failed", err);
      showMessage("danger", "Unable to prepare folder for upload.");
      resetFileSelection();
      return;
    }
    if (token !== processingToken) return;

    const sizeLabel =
      (candidates.cleanCandidate.size / (1024 * 1024)).toFixed(2) + " MB";
    if (fileNameEl) {
      fileNameEl.textContent = `${candidates.cleanCandidate.name} (${sizeLabel})`;
      fileNameEl.classList.remove("hidden");
    }
    if (dropZoneText) {
      dropZoneText.textContent = `Folder selected: ${candidates.rootFolder} (${fileList.length} items)`;
      dropZoneText.classList.remove("hidden");
    }
    if (fileInput) fileInput.value = "";

    uploadCandidates = { ...candidates, source: "folder" };
    if (candidates.failures.length > 0) {
      applyState(UploadState.NEEDS_CONFIRMATION);
      renderStripWarning(candidates.failures, "folder", ui);
    } else {
      applyState(UploadState.READY);
      if (candidates.warnings.length > 0) {
        renderStripWarning(candidates.warnings, "folder", ui, {
          type: "warning",
        });
      }
    }
  }

  async function onUploadPrimaryClick() {
    if (!uploadCandidates) {
      showMessage("warning", "Select a file or folder to upload first.");
      return;
    }
    if (isUploading) return;

    const { cleanCandidate, fallbackCandidate } = uploadCandidates;
    const shouldUseFallback =
      uploadState === UploadState.NEEDS_CONFIRMATION &&
      Boolean(fallbackCandidate);
    const candidate = shouldUseFallback
      ? fallbackCandidate
      : cleanCandidate || fallbackCandidate;
    if (!candidate || !candidate.file) {
      showMessage("danger", "No upload candidate is available.");
      return;
    }

    isUploading = true;
    applyState(UploadState.UPLOADING);

    try {
      await uploadCandidate(candidate, {
        uploadPasswordEnabled,
        form,
        progressBar: uploadProgress,
        statusEl: uploadStatus,
        indicatorEl: uploadIndicator,
        onSuccess: (uuid) => {
          window.location.href = `/file/${uuid}`;
        },
        onWarn: () => {
          showMessage(
            "warning",
            "Upload finished but no file information was returned from the server."
          );
          isUploading = false;
          applyState(UploadState.READY);
        },
        onError: () => {
          showMessage("danger", "Upload failed. Please try again.");
          resetUploadUI();
        },
      });
    } catch (err) {
      console.error("Upload failed", err);
      showMessage("danger", "Upload failed. Please try again.");
      resetUploadUI();
    }
  }

  function setupDropZone() {
    document.addEventListener("dragover", (e) => {
      if (e.target && e.target.closest && e.target.closest("#dropZone")) return;
      e.preventDefault();
    });
    document.addEventListener("drop", (e) => {
      if (e.target && e.target.closest && e.target.closest("#dropZone")) return;
      e.preventDefault();
    });

    if (!dropZone) return;
    ["dragenter", "dragover"].forEach((eventName) => {
      dropZone.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.add("ring-2", "ring-sky-500");
      });
    });
    ["dragleave", "drop"].forEach((eventName) => {
      dropZone.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        dropZone.classList.remove("ring-2", "ring-sky-500");
      });
    });
    dropZone.addEventListener("drop", async (e) => {
      const items = e.dataTransfer.items;
      const files = e.dataTransfer.files;

      if (items && items.length > 0) {
        const collected = await getFilesFromItems(items);
        if (!collected || collected.length === 0) {
          resetFileSelection();
          return;
        }
        const hasRelative = collected.some((f) => {
          const rel = getRelativePath(f);
          return rel && rel.includes("/");
        });
        if (hasRelative || collected.length > 1) {
          await handleFolderSelection(collected);
        } else {
          const dt = new DataTransfer();
          dt.items.add(collected[0]);
          if (fileInput) fileInput.files = dt.files;
          handleSingleFile(fileInput.files);
        }
        return;
      }

      if (files && files.length > 0) {
        const dt = new DataTransfer();
        for (const f of files) dt.items.add(f);
        if (fileInput) fileInput.files = dt.files;
        handleSingleFile(fileInput.files);
      }
    });
  }

  function wireInputs() {
    form?.addEventListener("submit", (e) => e.preventDefault());

    const fileButton = document.getElementById("fileSelectButton");
    const folderButton = document.getElementById("folderSelectButton");
    fileButton?.addEventListener("click", () => fileInput?.click());
    folderButton?.addEventListener("click", () => folderInput?.click());

    fileInput?.addEventListener("change", () =>
      handleSingleFile(fileInput.files)
    );
    folderInput?.addEventListener("change", () =>
      handleFolderSelection(folderInput.files)
    );

    ui.uploadPrimary?.addEventListener("click", onUploadPrimaryClick);
    ui.uploadCancel?.addEventListener("click", resetUploadUI);

    ui.uploadWarningDetails?.addEventListener("click", () => {
      if (!ui.uploadWarningList) return;
      ui.uploadWarningList.classList.toggle("hidden");
      const nowHidden = ui.uploadWarningList.classList.contains("hidden");
      ui.uploadWarningDetails.textContent = nowHidden
        ? "Show details"
        : "Hide details";
    });
  }

  function getFilesFromItems(items) {
    const entries = [];
    for (const item of items) {
      if (item.kind === "file" && item.webkitGetAsEntry) {
        const entry = item.webkitGetAsEntry();
        if (entry) entries.push(entry);
      } else if (item.kind === "file") {
        const file = item.getAsFile();
        if (file) entries.push(file);
      }
    }

    const files = [];

    async function walkEntry(entry, pathPrefix = "") {
      if (entry.isFile) {
        return new Promise((resolve, reject) => {
          entry.file((file) => {
            const rel = pathPrefix ? `${pathPrefix}/${file.name}` : file.name;
            const fileWithPath = withRelativePath(file, rel);
            resolve([fileWithPath]);
          }, reject);
        });
      }
      if (entry.isDirectory) {
        const reader = entry.createReader();
        const allEntries = [];
        async function readAll() {
          return new Promise((resolve) => reader.readEntries(resolve));
        }
        let batch = await readAll();
        while (batch.length) {
          for (const child of batch) {
            const childFiles = await walkEntry(
              child,
              pathPrefix ? `${pathPrefix}/${entry.name}` : entry.name
            );
            allEntries.push(...childFiles);
          }
          batch = await readAll();
        }
        return allEntries;
      }
      return [];
    }

    return (async () => {
      for (const entry of entries) {
        if (entry.isFile || entry.isDirectory) {
          const walked = await walkEntry(entry);
          files.push(...walked);
        } else if (entry instanceof File) {
          const rel = entry.webkitRelativePath || entry.name;
          files.push(withRelativePath(entry, rel));
        }
      }
      return files;
    })();
  }

  setupDropZone();
  wireInputs();
  applyState(UploadState.IDLE);
}

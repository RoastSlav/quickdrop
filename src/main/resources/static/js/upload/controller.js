import {
    clearStripWarning,
    getUIRefs,
    renderStripReview,
    setReviewToggleLabel,
    setUploadState,
    showMessage,
    UploadState,
} from "./state.js";
import {buildSingleCandidates} from "./metadata-pipeline.js";
import {buildArchiveCandidates, describeSelection, parseSize} from "./zip-builder.js";
import {formatBytes} from "./format.js";
import {uploadCandidate} from "./network.js";

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
    let preparation = null;

    const withRelativePath = (file, rel) => {
        try {
            const cloned = new File([file], file.name, {
                type: file.type,
                lastModified: file.lastModified,
            });
            Object.defineProperty(cloned, "relativePath", {value: rel});
            try {
                Object.defineProperty(cloned, "webkitRelativePath", {
                    value: rel,
                    configurable: true,
                });
            } catch (_) {
            }
            return cloned;
        } catch (_) {
            try {
                file.relativePath = rel;
            } catch (_) {
            }
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
        abortPreparation();
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
        abortPreparation();
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

    function abortPreparation() {
        preparation?.abort();
        preparation = null;
    }

    function setDropZoneText(text) {
        if (!dropZoneText) return;
        dropZoneText.textContent = text;
        dropZoneText.classList.remove("hidden");
    }

    const i18nUpload = (key, fallback) => window.i18n?.upload?.[key] || fallback;

    /**
     * Everything that differs between the three kinds of selection: a lone file, a picked
     * folder, and a loose bundle. A single file that carries a directory in its path is
     * still an archive -- dropping a folder that happens to hold one file must not turn
     * into a bare upload of that file.
     */
    function planSelection(files) {
        const {isBundle} = describeSelection(files);

        if (files.length === 1 && isBundle) {
            const file = files[0];
            return {
                source: "single",
                totalSize: file.size,
                pendingName: `${file.name} (${formatBytes(file.size)})`,
                startedMessage: null,
                limitMessage: i18nUpload("fileExceedsLimit", "File exceeds the {0} limit."),
                failureMessage: i18nUpload("uploadFailed", "Upload failed. Please try again."),
                build: () => buildSingleCandidates(file, metadataEnabled),
                describe: () => ({
                    name: `${file.name} (${formatBytes(file.size)})`,
                    hint: null,
                }),
            };
        }

        let totalSize = 0;
        for (const file of files) totalSize += file.size;

        return {
            source: isBundle ? "files" : "folder",
            totalSize,
            pendingName: null,
            startedMessage: isBundle
                ? i18nUpload("processingFiles", "Processing files...")
                : i18nUpload("processingFolder", "Processing folder..."),
            limitMessage: isBundle
                ? i18nUpload("filesExceedLimit", "Selected files exceed the {0} limit.")
                : i18nUpload("folderExceedsLimit", "Folder exceeds the {0} limit."),
            failureMessage: isBundle
                ? i18nUpload("filesFailed", "Unable to prepare the files for upload.")
                : i18nUpload("folderFailed", "Unable to prepare folder for upload."),
            build: (options) => buildArchiveCandidates(files, {metadataEnabled, ...options}),
            describe: (candidates) => ({
                name: `${candidates.cleanCandidate.name} (${formatBytes(candidates.cleanCandidate.size)})`,
                hint: candidates.isBundle
                    ? i18nUpload("filesSelected", "{0} files selected")
                        .replace("{0}", candidates.fileCount)
                    : i18nUpload("folderSelected", "Folder selected: {0} ({1} items)")
                        .replace("{0}", candidates.archiveName)
                        .replace("{1}", candidates.fileCount),
            }),
        };
    }

    /** The one path every selection takes, whichever picker or drop produced it. */
    async function handleSelection(fileList) {
        const files = Array.from(fileList || []);
        if (files.length === 0) {
            resetFileSelection();
            return;
        }

        const plan = planSelection(files);

        if (plan.totalSize > maxSize) {
            resetFileSelection();
            setDropZoneText(plan.limitMessage.replace("{0}", maxSizeLabel));
            return;
        }

        if (fileNameEl) {
            fileNameEl.textContent = plan.pendingName || "";
            fileNameEl.classList.toggle("hidden", !plan.pendingName);
        }
        if (plan.startedMessage) setDropZoneText(plan.startedMessage);
        else dropZoneText?.classList.add("hidden");

        const token = ++processingToken;
        abortPreparation();
        preparation = new AbortController();
        const {signal} = preparation;
        applyState(UploadState.PROCESSING);

        let candidates;
        try {
            candidates = await plan.build({
                signal,
                onProgress: (done, total) => {
                    if (token !== processingToken) return;
                    setDropZoneText(
                        i18nUpload("preparing", "Preparing {0} of {1} files...")
                            .replace("{0}", done)
                            .replace("{1}", total)
                    );
                },
            });
        } catch (err) {
            if (err?.name === "AbortError" || token !== processingToken) return;
            console.error("Selection processing failed", err);
            showMessage("danger", plan.failureMessage);
            resetFileSelection();
            return;
        }
        if (token !== processingToken) return;
        preparation = null;

        const {name, hint} = plan.describe(candidates);
        if (fileNameEl) {
            fileNameEl.textContent = name;
            fileNameEl.classList.remove("hidden");
        }
        if (hint) setDropZoneText(hint);
        else if (dropZoneText) {
            dropZoneText.textContent = defaultText;
            dropZoneText.classList.add("hidden");
        }

        // The candidates hold their own File references, so both pickers can be cleared --
        // which also lets re-picking the same file or folder fire another change event.
        if (fileInput) fileInput.value = "";
        if (folderInput) folderInput.value = "";

        uploadCandidates = {...candidates, source: plan.source};
        applyState(
            candidates.failures.length > 0
                ? UploadState.NEEDS_CONFIRMATION
                : UploadState.READY
        );
        renderStripReview(candidates, ui);
    }

    async function onUploadPrimaryClick() {
        if (!uploadCandidates) {
            showMessage("warning", window.i18n?.upload?.selectionRequired || 'Select a file or folder to upload first.');
            return;
        }
        if (isUploading) return;

        const {cleanCandidate, fallbackCandidate} = uploadCandidates;
        const shouldUseFallback =
            uploadState === UploadState.NEEDS_CONFIRMATION &&
            Boolean(fallbackCandidate);
        const candidate = shouldUseFallback
            ? fallbackCandidate
            : cleanCandidate || fallbackCandidate;
        if (!candidate || (!candidate.file && !candidate.streamUpload)) {
            showMessage("danger", window.i18n?.upload?.noCandidate || 'No upload candidate is available.');
            return;
        }

        isUploading = true;
        applyState(UploadState.UPLOADING);

        let handledUploadFailure = false;
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
                    handledUploadFailure = true;
                    showMessage("warning", window.i18n?.upload?.noFileInfo || 'Upload finished but no file information was returned from the server.');
                    isUploading = false;
                    applyState(UploadState.READY);
                },
                onError: (serverMessage) => {
                    handledUploadFailure = true;
                    showMessage("danger", serverMessage || window.i18n?.upload?.uploadFailed || 'Upload failed. Please try again.');
                    resetUploadUI();
                },
            });
        } catch (err) {
            console.error("Upload failed", err);
            if (!handledUploadFailure) {
                showMessage("danger", err?.message || window.i18n?.upload?.uploadFailed || 'Upload failed. Please try again.');
                resetUploadUI();
            }
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
                dropZone.classList.add("drag-active");
            });
        });
        ["dragleave", "drop"].forEach((eventName) => {
            dropZone.addEventListener(eventName, (e) => {
                e.preventDefault();
                e.stopPropagation();
                dropZone.classList.remove("drag-active");
            });
        });
        dropZone.addEventListener("drop", async (e) => {
            const items = e.dataTransfer.items;
            if (items && items.length > 0) {
                await handleSelection(await getFilesFromItems(items));
                return;
            }
            await handleSelection(e.dataTransfer.files);
        });
    }

    function wireInputs() {
        form?.addEventListener("submit", (e) => e.preventDefault());

        const fileButton = document.getElementById("fileSelectButton");
        const folderButton = document.getElementById("folderSelectButton");
        fileButton?.addEventListener("click", () => fileInput?.click());
        folderButton?.addEventListener("click", () => folderInput?.click());

        fileInput?.addEventListener("change", () => handleSelection(fileInput.files));
        folderInput?.addEventListener("change", () => handleSelection(folderInput.files));

        ui.uploadPrimary?.addEventListener("click", onUploadPrimaryClick);
        ui.uploadCancel?.addEventListener("click", () => {
            if (uploadState === UploadState.PROCESSING) resetFileSelection();
            else resetUploadUI();
        });

        ui.uploadWarningDetails?.addEventListener("click", () => {
            if (!ui.uploadWarningList) return;
            ui.uploadWarningList.classList.toggle("hidden");
            const nowHidden = ui.uploadWarningList.classList.contains("hidden");
            ui.uploadWarningDetails.setAttribute(
                "aria-expanded",
                nowHidden ? "false" : "true"
            );
            setReviewToggleLabel(ui.uploadWarningDetails, !nowHidden);
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

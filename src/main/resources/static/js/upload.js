// upload.js

let isUploading = false;
let indefiniteNoPwWarningShown = false;
let selectedUpload = null; // { file: Blob|File, name, size, folderUpload, folderName, folderManifest }

document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    uploadForm.addEventListener("submit", onUploadFormSubmit);

    const dropZone = document.getElementById("dropZone");
    const fileInput = document.getElementById("file");
    const fileNameEl = document.getElementById("selectedFile");
    const dropZoneText = document.getElementById("dropZoneInstructions");
    const defaultText = dropZoneText ? dropZoneText.dataset.defaultText || dropZoneText.textContent : "";
    const folderInput = document.getElementById("folderInput");
    const folderButton = document.getElementById("folderSelectButton");
    if (dropZone) {
        dropZone.addEventListener("click", () => fileInput.click());
        ["dragenter", "dragover"].forEach((eventName) => {
            dropZone.addEventListener(eventName, (e) => {
                e.preventDefault();
                dropZone.classList.add("ring-2", "ring-sky-500");
            });
        });
        ["dragleave", "drop"].forEach((eventName) => {
            dropZone.addEventListener(eventName, (e) => {
                e.preventDefault();
                dropZone.classList.remove("ring-2", "ring-sky-500");
            });
        });
        dropZone.addEventListener("drop", (e) => {
            const items = e.dataTransfer.items;
            const files = e.dataTransfer.files;
            // We intentionally do not support folder drops; instruct the user to use the folder button
            if (items && items.length > 0) {
                const firstItem = items[0];
                if (firstItem.webkitGetAsEntry && firstItem.webkitGetAsEntry().isDirectory) {
                    showMessage("info", "Use the ‘Select a Folder’ button to upload a folder.");
                    resetFileSelection(fileInput, fileNameEl, dropZoneText, defaultText);
                    return;
                }
            }

            if (fileInput) {
                const dt = new DataTransfer();
                for (const f of files) dt.items.add(f);
                fileInput.files = dt.files;
            }
            handleSingleFile(fileInput.files);
        });
    }

    if (fileInput) {
        fileInput.addEventListener("change", () => {
            handleSingleFile(fileInput.files);
        });
        handleSingleFile(fileInput.files);
    }

    if (folderButton && folderInput) {
        folderButton.addEventListener("click", async () => {
            if (window.showDirectoryPicker) {
                try {
                    await handleDirectoryPickerSelection();
                    return;
                } catch (e) {
                    console.warn("Directory picker failed, falling back to input", e);
                }
            }
            folderInput.click();
        });
        folderInput.addEventListener("change", () => handleFolderSelection(folderInput.files));
    }

    function resetFileSelection(input, fileNameEl, dropZoneText, defaultText) {
        if (input) input.value = "";
        selectedUpload = null;
        fileNameEl.textContent = "";
        fileNameEl.classList.add("hidden");
        dropZoneText.textContent = defaultText;
        dropZoneText.classList.remove("hidden");
    }

    function handleSingleFile(files) {
        if (!fileNameEl || !dropZoneText) return;

        if (!files || files.length === 0) {
            resetFileSelection(fileInput, fileNameEl, dropZoneText, defaultText);
            return;
        }

        const file = files[0];
        const maxSizeSpan = document.querySelector('.maxFileSize');
        const maxSize = maxSizeSpan ? parseSize(maxSizeSpan.innerText) : Infinity;

        if (file.size > maxSize) {
            dropZoneText.textContent = `File exceeds the ${maxSizeSpan.innerText} limit.`;
            dropZoneText.classList.remove("hidden");
            fileNameEl.textContent = "";
            fileNameEl.classList.add("hidden");
            if (fileInput) fileInput.value = "";
            selectedUpload = null;
            return;
        }

        const size = (file.size / (1024 * 1024)).toFixed(2) + ' MB';
        fileNameEl.textContent = `${file.name} (${size})`;
        fileNameEl.classList.remove("hidden");
        dropZoneText.textContent = defaultText;
        dropZoneText.classList.add("hidden");
        selectedUpload = {
            file,
            name: file.name,
            size: file.size,
            folderUpload: false,
            folderName: null,
            folderManifest: null
        };
        if (folderInput) {
            folderInput.value = ""; // clear folder selection when switching back
        }
    }

    async function handleFolderSelection(fileList) {
        if (!fileList || fileList.length === 0) {
            resetFileSelection(fileInput, fileNameEl, dropZoneText, defaultText);
            return;
        }

        const maxSizeSpan = document.querySelector('.maxFileSize');
        const maxSize = maxSizeSpan ? parseSize(maxSizeSpan.innerText) : Infinity;

        const firstPath = fileList[0].webkitRelativePath || fileList[0].name;
        const rootFolder = firstPath.split(/[/\\]/)[0];
        const manifest = new Set();
        let totalOriginalSize = 0;

        for (const file of fileList) {
            totalOriginalSize += file.size;
            const rel = file.webkitRelativePath || file.name;
            manifest.add(JSON.stringify({path: rel, size: file.size, type: 'file'}));
            // add all ancestor directories
            const parts = rel.split(/[/\\]/);
            let prefix = '';
            for (let i = 0; i < parts.length - 1; i++) {
                prefix = prefix ? `${prefix}/${parts[i]}` : parts[i];
                manifest.add(JSON.stringify({path: prefix, type: 'dir'}));
            }
        }

        if (totalOriginalSize > maxSize) {
            showMessage("danger", `Folder exceeds the ${maxSizeSpan.innerText} limit.`);
            resetFileSelection(fileInput, fileNameEl, dropZoneText, defaultText);
            return;
        }

        dropZoneText.textContent = "Building zip from folder...";
        dropZoneText.classList.remove("hidden");
        fileNameEl.classList.add("hidden");

        const zip = new JSZip();
        const manifestArray = Array.from(manifest).map((s) => JSON.parse(s));
        manifestArray.forEach((entry) => {
            if (entry.type === 'dir') {
                zip.folder(entry.path);
            }
        });
        for (const file of fileList) {
            const path = file.webkitRelativePath || file.name;
            zip.file(path, file);
        }

        const zipBlob = await zip.generateAsync({type: "blob"});
        const zipName = `${rootFolder}.zip`;

        const size = (zipBlob.size / (1024 * 1024)).toFixed(2) + ' MB';
        fileNameEl.textContent = `${zipName} (${size})`;
        fileNameEl.classList.remove("hidden");
        dropZoneText.textContent = `Prepared from folder “${rootFolder}” (${fileList.length} files)`;
        dropZoneText.classList.remove("hidden");

        selectedUpload = {
            file: zipBlob,
            name: zipName,
            size: zipBlob.size,
            folderUpload: true,
            folderName: rootFolder,
            folderManifest: JSON.stringify(manifestArray)
        };

        if (fileInput) fileInput.value = ""; // clear single-file selection
    }

    async function handleDirectoryPickerSelection() {
        const dirHandle = await window.showDirectoryPicker();
        const files = [];
        async function walkDirectory(handle, pathParts) {
            for await (const [name, entry] of handle.entries()) {
                const newPath = [...pathParts, name];
                if (entry.kind === 'file') {
                    const file = await entry.getFile();
                    file.relativePath = newPath.join('/');
                    files.push(file);
                } else if (entry.kind === 'directory') {
                    // ensure empty dirs are captured via manifest later
                    files.push({relativePath: newPath.join('/'), isDirMarker: true});
                    await walkDirectory(entry, newPath);
                }
            }
        }

        await walkDirectory(dirHandle, [dirHandle.name]);

        const fileListLike = [];
        files.forEach((item) => {
            if (item.isDirMarker) return;
            const f = item;
            // fabricate webkitRelativePath for consistency
            f.webkitRelativePath = item.relativePath;
            fileListLike.push(f);
        });

        await handleFolderSelection(fileListLike);
    }
});

// Unified way to show an inline message in our #messageContainer
function showMessage(type, text) {
    const styles = {
        success: "bg-sky-100 text-sky-700 dark:bg-sky-900 dark:text-sky-100",
        info: "bg-sky-100 text-sky-700 dark:bg-sky-900 dark:text-sky-100",
        danger: "bg-red-100 text-red-700 dark:bg-red-900 dark:text-red-100",
        warning: "bg-yellow-100 text-yellow-700 dark:bg-yellow-900 dark:text-yellow-100"
    };
    const container = document.getElementById("messageContainer");
    container.innerHTML = `<div class="rounded-lg p-4 mb-4 ${styles[type] || styles.info}">${text}</div>`;
}

// Called when user hits "Upload"
function onUploadFormSubmit(event) {
    event.preventDefault();

    if (isUploading) return; // Prevent duplicate clicks
    isUploading = true;

    // 1) Check "Keep Indefinitely" + no password
    const keepIndefinitelyCheckbox = document.getElementById("keepIndefinitely");
    const keepIndefinitely = keepIndefinitelyCheckbox ? keepIndefinitelyCheckbox.checked : false;
    const password = document.getElementById("password").value.trim();

    if (keepIndefinitely && !password) {
        // If we haven’t shown the warning yet, show it now and bail
        if (!indefiniteNoPwWarningShown) {
            indefiniteNoPwWarningShown = true;
            showMessage("warning",
                "You selected ‘Keep indefinitely’ but provided no password. " +
                "This file will only be deletable by an admin. " +
                "If that’s what you want, click ‘Upload’ again to confirm. " +
                "Otherwise, add a password or uncheck ‘Keep indefinitely’."
            );
            isUploading = false;  // Let them try again
            return;
        }
        // If the warning was already shown, we just proceed here
    }

    // 2) Everything is good, proceed
    startChunkUpload();
}


function startChunkUpload() {
    if (!selectedUpload || !selectedUpload.file) {
        showMessage("danger", "No file or folder selected.");
        isUploading = false;
        return;
    }

    const file = selectedUpload.file;

    // Initialize progress bar
    document.getElementById("uploadIndicator").classList.remove("hidden");
    const progressBar = document.getElementById("uploadProgress");
    progressBar.style.width = "0%";
    progressBar.setAttribute("aria-valuenow", 0);
    document.getElementById("uploadStatus").innerText = "Upload started...";

    const chunkSize = 10 * 1024 * 1024; // 10MB
    const totalChunks = Math.ceil(file.size / chunkSize);
    let currentChunk = 0;

    // Recursive function to upload chunk by chunk
    function uploadNextChunk() {
        const start = currentChunk * chunkSize;
        const end = Math.min(start + chunkSize, file.size);
        const chunk = file.slice(start, end);

        const formData = buildChunkFormData(chunk, currentChunk, selectedUpload.name, totalChunks, selectedUpload.size, selectedUpload);

        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/file/upload-chunk", true);

        // Set CSRF token if present
        const csrfTokenElement = document.querySelector('input[name="_csrf"]');
        if (csrfTokenElement) {
            xhr.setRequestHeader("X-CSRF-TOKEN", csrfTokenElement.value);
        }

        xhr.onload = () => {
            if (xhr.status === 200) {
                // If responseText is empty (null response), ignore it.
                let response = null;
                if (xhr.responseText && xhr.responseText.trim().length > 0) {
                    try {
                        response = JSON.parse(xhr.responseText);
                    } catch (e) {
                        console.warn("Failed to parse server response:", e);
                    }
                }

                currentChunk++;
                const percentComplete = (currentChunk / totalChunks) * 100;
                progressBar.style.width = percentComplete + "%";
                progressBar.setAttribute("aria-valuenow", percentComplete);

                if (currentChunk < totalChunks) {
                    // Continue uploading remaining chunks.
                    if (currentChunk === totalChunks - 1 && document.getElementById("password").value.trim()) {
                        document.getElementById("uploadStatus").innerText = "Upload complete. Encrypting...";
                    }
                    uploadNextChunk();
                } else {
                    // Final chunk response handling.
                    document.getElementById("uploadStatus").innerText = "Upload complete.";
                    if (response && response.uuid) {
                        window.location.href = "/file/" + response.uuid;
                    } else {
                        // No file entity returned; warn the user.
                        showMessage("warning", "Upload finished but no file information was returned from the server.");
                        isUploading = false;
                    }
                }
            } else {
                console.error("Upload error:", xhr.responseText);
                showMessage("danger", "Upload failed. Please try again.");
                resetUploadUI();
            }
        };

        xhr.onerror = () => {
            showMessage("danger", "An error occurred during the upload. Please try again.");
            resetUploadUI();
        };

        xhr.send(formData);
    }

    // Begin the upload process.
    uploadNextChunk();
}

function buildChunkFormData(chunk, chunkNumber, fileName, totalChunks, fileSize, uploadMeta) {
    const uploadForm = document.getElementById("uploadForm");
    const formData = new FormData();

    // Chunk metadata
    formData.append("file", chunk);
    formData.append("fileName", fileName);
    formData.append("chunkNumber", chunkNumber);
    formData.append("totalChunks", totalChunks);
    formData.append("fileSize", fileSize);

    if (uploadMeta) {
        formData.append("folderUpload", uploadMeta.folderUpload ? "true" : "false");
        if (uploadMeta.folderUpload) {
            formData.append("folderName", uploadMeta.folderName || "");
            formData.append("folderManifest", uploadMeta.folderManifest || "[]");
        }
    }

    // Keep Indefinitely + hidden
    const keepIndefinitelyCheckbox = document.getElementById("keepIndefinitely");
    formData.append("keepIndefinitely", keepIndefinitelyCheckbox && keepIndefinitelyCheckbox.checked ? "true" : "false");
    const hiddenCheckbox = document.getElementById("hidden");
    if (hiddenCheckbox) {
        formData.append("hidden", hiddenCheckbox.checked ? "true" : "false");
    }

    // Gather other fields (excluding file inputs/checkboxes)
    Array.from(uploadForm.elements).forEach((el) => {
        if (el.name && el.type !== "file" && el.type !== "checkbox") {
            formData.append(el.name, el.value.trim());
        }
    });

    return formData;
}

// Reset UI if something fails
function resetUploadUI() {
    document.getElementById("uploadIndicator").classList.add("hidden");
    isUploading = false;
}


function parseSize(size) {
    // Example: "1GB" -> parse
    const units = {B: 1, KB: 1024, MB: 1024 * 1024, GB: 1024 * 1024 * 1024};
    const unitMatch = size.match(/[a-zA-Z]+/);
    const valueMatch = size.match(/[0-9.]+/);

    if (!unitMatch || !valueMatch) {
        throw new Error("Invalid maxFileSize format");
    }
    const unit = unitMatch[0];
    const value = parseFloat(valueMatch[0]);
    return value * (units[unit] || 1);
}

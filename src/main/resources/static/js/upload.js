// upload.js

let isUploading = false;
let indefiniteNoPwWarningShown = false;

document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    uploadForm.addEventListener("submit", onUploadFormSubmit);

    const dropZone = document.getElementById("dropZone");
    const fileInput = document.getElementById("file");
    const fileNameEl = document.getElementById("selectedFile");
    const dropZoneText = document.getElementById("dropZoneInstructions");
    const defaultText = dropZoneText ? dropZoneText.dataset.defaultText || dropZoneText.textContent : "";
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
            handleFiles(files, items);
        });
    }

    if (fileInput) {
        fileInput.addEventListener("change", () => {
            handleFiles(fileInput.files);
        });
        handleFiles(fileInput.files);
    }

    function handleFiles(files, items) {
        if (!fileNameEl || !dropZoneText) return;

        let isFolder = false;
        const firstFile = files && files[0];
        if (items && items.length > 0 && items[0].webkitGetAsEntry) {
            const entry = items[0].webkitGetAsEntry();
            if (entry && entry.isDirectory) isFolder = true;
        } else if (firstFile && firstFile.webkitRelativePath && firstFile.webkitRelativePath !== "") {
            isFolder = true;
        }

        if (isFolder) {
            dropZoneText.textContent = "Folders cannot be uploaded.";
            dropZoneText.classList.remove("hidden");
            fileNameEl.textContent = "";
            fileNameEl.classList.add("hidden");
            if (fileInput) fileInput.value = "";
            return;
        }

        if (!files || files.length === 0) {
            fileNameEl.textContent = "";
            fileNameEl.classList.add("hidden");
            dropZoneText.textContent = defaultText;
            dropZoneText.classList.remove("hidden");
            return;
        }

        const file = firstFile;

        const maxSizeSpan = document.querySelector('.maxFileSize');
        const maxSize = maxSizeSpan ? parseSize(maxSizeSpan.innerText) : Infinity;


        if (file.size > maxSize) {
            dropZoneText.textContent = `File exceeds the ${maxSizeSpan.innerText} limit.`;
            fileNameEl.textContent = "";
            fileNameEl.classList.add("hidden");
            fileInput.value = "";
            const fileSizeAlert = document.getElementById('fileSizeAlert');
            if (fileSizeAlert) fileSizeAlert.classList.remove('hidden');
            return;
        }

        const fileSizeAlert = document.getElementById('fileSizeAlert');
        if (fileSizeAlert) fileSizeAlert.classList.add('hidden');
        const size = (file.size / (1024 * 1024)).toFixed(2) + ' MB';
        fileNameEl.textContent = `${file.name} (${size})`;
        fileNameEl.classList.remove("hidden");
        dropZoneText.textContent = defaultText;
        dropZoneText.classList.add("hidden");
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
    const keepIndefinitely = document.getElementById("keepIndefinitely").checked;
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
    const file = document.getElementById("file").files[0];
    if (!file) {
        showMessage("danger", "No file selected.");
        isUploading = false;
        return;
    }

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

        const formData = buildChunkFormData(chunk, currentChunk, file.name, totalChunks, file.size);

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

function buildChunkFormData(chunk, chunkNumber, fileName, totalChunks, fileSize) {
    const uploadForm = document.getElementById("uploadForm");
    const formData = new FormData();

    // Chunk metadata
    formData.append("file", chunk);
    formData.append("fileName", fileName);
    formData.append("chunkNumber", chunkNumber);
    formData.append("totalChunks", totalChunks);
    formData.append("fileSize", fileSize);

    // Keep Indefinitely + hidden
    const keepIndefinitelyCheckbox = document.getElementById("keepIndefinitely");
    formData.append("keepIndefinitely", keepIndefinitelyCheckbox.checked ? "true" : "false");
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

// upload.js

let isUploading = false;
let indefiniteNoPwWarningShown = false;

document.addEventListener("DOMContentLoaded", () => {
    const uploadForm = document.getElementById("uploadForm");
    uploadForm.addEventListener("submit", onUploadFormSubmit);
});

// Unified way to show an inline message in our #messageContainer
function showMessage(type, text) {
    // type: "success", "info", "danger", "warning"
    const container = document.getElementById("messageContainer");
    container.innerHTML = `
    <div class="alert alert-${type} alert-dismissible fade show" role="alert">
      ${text}
      <button type="button" class="btn-close" data-bs-dismiss="alert" aria-label="Close"></button>
    </div>`;
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
    document.getElementById("uploadIndicator").style.display = "block";
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

        const formData = buildChunkFormData(chunk, currentChunk, file.name, totalChunks);

        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/file/upload-chunk", true);

        // Set CSRF token if present
        const csrfTokenElement = document.querySelector('input[name="_csrf"]');
        if (csrfTokenElement) {
            xhr.setRequestHeader("X-CSRF-TOKEN", csrfTokenElement.value);
        }

        xhr.onload = () => {
            if (xhr.status === 200) {
                try {
                    const response = JSON.parse(xhr.responseText);
                    currentChunk++;
                    const percentComplete = (currentChunk / totalChunks) * 100;
                    progressBar.style.width = percentComplete + "%";
                    progressBar.setAttribute("aria-valuenow", percentComplete);

                    if (currentChunk < totalChunks) {
                        // If chunks remain, keep uploading
                        if (currentChunk === totalChunks - 1 && document.getElementById("password").value.trim()) {
                            document.getElementById("uploadStatus").innerText = "Upload complete. Encrypting...";
                        }
                        uploadNextChunk();
                    } else {
                        // Final chunk: check response
                        document.getElementById("uploadStatus").innerText = "Upload complete.";
                        if (response.uuid) {
                            window.location.href = "/file/" + response.uuid;
                        } else {
                            showMessage("danger", "Upload finished but no UUID returned from server.");
                        }
                        isUploading = false;
                    }
                } catch (err) {
                    console.error(err);
                    showMessage("danger", "Unexpected server response. Please try again.");
                    resetUploadUI();
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

    // Begin
    uploadNextChunk();
}

function buildChunkFormData(chunk, chunkNumber, fileName, totalChunks) {
    const uploadForm = document.getElementById("uploadForm");
    const formData = new FormData();

    // Chunk metadata
    formData.append("file", chunk);
    formData.append("fileName", fileName);
    formData.append("chunkNumber", chunkNumber);
    formData.append("totalChunks", totalChunks);

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
    document.getElementById("uploadIndicator").style.display = "none";
    isUploading = false;
}

function validateFileSize() {
    const fileSizeSpan = document.querySelector('.maxFileSize');
    const file = document.getElementById('file').files[0];
    if (!file || !fileSizeSpan) return;

    const maxSize = parseSize(fileSizeSpan.innerText);
    const fileSizeAlert = document.getElementById('fileSizeAlert');

    if (file.size > maxSize) {
        fileSizeAlert.style.display = 'block';
        document.getElementById('file').value = '';
    } else {
        fileSizeAlert.style.display = 'none';
    }
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

let isUploading = false;

// Event listener for form submission
document.getElementById("uploadForm").addEventListener("submit", function (event) {
    event.preventDefault();

    if (isUploading) return; // Prevent duplicate uploads
    isUploading = true;

    if (!validateKeepIndefinitely()) {
        isUploading = false;
        return; // Stop here if they chose not to proceed
    }

    const uploadForm = event.target;
    const fileInput = document.getElementById("file");
    const passwordInput = document.getElementById("password");
    const uploadIndicator = document.getElementById("uploadIndicator");
    const progressBar = document.getElementById("uploadProgress");
    const uploadStatus = document.getElementById("uploadStatus");
    const CSRF_SELECTOR = 'input[name="_csrf"]';

    const file = fileInput.files[0];
    const chunkSize = 10 * 1024 * 1024; // 10MB
    const totalChunks = Math.ceil(file.size / chunkSize);
    let currentChunk = 0;

    const isPasswordProtected = passwordInput && passwordInput.value.trim() !== "";

    // Initialize progress bar
    function initializeProgressBar() {
        uploadIndicator.style.display = "block";
        progressBar.style.width = "0%";
        progressBar.setAttribute("aria-valuenow", 0);
    }

    // Create FormData for a specific chunk
    function createChunkFormData(chunk, chunkNumber) {
        const formData = new FormData();

        formData.append("file", chunk); // Only append the current chunk
        formData.append("fileName", file.name);
        formData.append("chunkNumber", chunkNumber);
        formData.append("totalChunks", totalChunks);

        // Explicitly append the checkbox states as true or false
        const keepIndefinitelyCheckbox = document.getElementById("keepIndefinitely");
        const hiddenCheckbox = document.getElementById("hidden");
        formData.append("keepIndefinitely", keepIndefinitelyCheckbox.checked ? "true" : "false");
        formData.append("hidden", hiddenCheckbox.checked ? "true" : "false");

        // Include other form fields except the file input
        Array.from(uploadForm.elements).forEach((element) => {
            if (element.name && element.type !== "file" && element.type !== "checkbox") {
                formData.append(element.name, element.value);
            }
        });

        return formData;
    }

    // Handle response for chunk upload
    function handleChunkUploadResponse(xhr, onSuccess, onError) {
        if (xhr.status === 200) {
            try {
                const response = JSON.parse(xhr.responseText);
                if (onSuccess) onSuccess(response);
            } catch (error) {
                console.error("Error parsing JSON:", error);
                alert("Unexpected server response. Please try again.");
            }
        } else {
            console.error("Upload error:", xhr.responseText);
            alert("Chunk upload failed. Please try again.");
            uploadIndicator.style.display = "none";
            isUploading = false;
        }
    }

    // Upload the next chunk
    function uploadNextChunk() {
        const start = currentChunk * chunkSize;
        const end = Math.min(start + chunkSize, file.size);
        const chunk = file.slice(start, end);
        const chunkFormData = createChunkFormData(chunk, currentChunk);

        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/file/upload-chunk", true);

        const csrfTokenElement = document.querySelector(CSRF_SELECTOR);
        if (csrfTokenElement) {
            xhr.setRequestHeader("X-CSRF-TOKEN", csrfTokenElement.value);
        }

        xhr.onload = function () {
            handleChunkUploadResponse(xhr, (response) => {
                currentChunk++;
                const percentComplete = (currentChunk / totalChunks) * 100;
                progressBar.style.width = percentComplete + "%";
                progressBar.setAttribute("aria-valuenow", percentComplete);

                if (currentChunk < totalChunks) {
                    uploadNextChunk();
                    if (currentChunk === totalChunks - 1 && isPasswordProtected) {
                        uploadStatus.innerText = "Upload complete. Encrypting...";
                    }
                } else {
                    uploadStatus.innerText = "Upload complete.";
                    if (response.uuid) {
                        window.location.href = "/file/" + response.uuid;
                    } else {
                        alert("Upload completed but no UUID received.");
                    }
                    isUploading = false;
                }
            });
        };

        xhr.onerror = function () {
            alert("An error occurred during the upload. Please try again.");
            uploadIndicator.style.display = "none";
            isUploading = false;
        };

        xhr.send(chunkFormData);
    }

    // Start upload process
    initializeProgressBar();
    uploadNextChunk();
});

function validateKeepIndefinitely() {
    const keepIndefinitely = document.getElementById("keepIndefinitely").checked;
    const password = document.getElementById("password").value;

    if (keepIndefinitely && !password) {
        return confirm(
            "You have selected 'Keep indefinitely' but haven't set a password. " +
            "This means the file will only be deletable by an admin. " +
            "Do you want to proceed?"
        );
    }

    return true; // Allow form submission if conditions are not met
}

function validateFileSize() {
    const maxFileSize = document.getElementsByClassName('maxFileSize')[0].innerText;
    const file = document.getElementById('file').files[0];
    const maxSize = parseSize(maxFileSize);
    const fileSizeAlert = document.getElementById('fileSizeAlert');

    if (file.size > maxSize) {
        fileSizeAlert.style.display = 'block';
        document.getElementById('file').value = '';
    } else {
        fileSizeAlert.style.display = 'none';
    }
}

function parseSize(size) {
    const units = {
        B: 1,
        KB: 1024,
        MB: 1024 * 1024,
        GB: 1024 * 1024 * 1024
    };

    const unitMatch = size.match(/[a-zA-Z]+/);
    const valueMatch = size.match(/[0-9.]+/);

    if (!unitMatch || !valueMatch) {
        throw new Error("Invalid size format");
    }

    const unit = unitMatch[0];
    const value = parseFloat(valueMatch[0]);

    return value * (units[unit] || 1);
}
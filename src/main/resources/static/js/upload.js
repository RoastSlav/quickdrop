document.getElementById("uploadForm").addEventListener("submit", function (event) {
    event.preventDefault();

    const file = document.getElementById("file").files[0];
    const passwordField = document.getElementById("password");
    const isPasswordProtected = passwordField && passwordField.value.trim() !== "";

    const chunkSize = 10 * 1024 * 1024; // 10MB chunks
    const totalChunks = Math.ceil(file.size / chunkSize);
    let currentChunk = 0;

    // Display the indicator
    document.getElementById("uploadIndicator").style.display = "block";
    const progressBar = document.getElementById("uploadProgress");
    const uploadStatus = document.getElementById("uploadStatus");
    progressBar.style.width = "0%";
    progressBar.setAttribute("aria-valuenow", 0);

    const formData = new FormData(event.target);

    function uploadChunk() {
        const start = currentChunk * chunkSize;
        const end = Math.min(start + chunkSize, file.size);
        const chunk = file.slice(start, end);

        const chunkFormData = new FormData();
        chunkFormData.append("file", chunk);
        chunkFormData.append("fileName", file.name);
        chunkFormData.append("chunkNumber", currentChunk);
        chunkFormData.append("totalChunks", totalChunks);
        chunkFormData.append("description", formData.get("description"));
        chunkFormData.append("keepIndefinitely", formData.get("keepIndefinitely") || "false");
        chunkFormData.append("hidden", formData.get("hidden") || "false");
        chunkFormData.append("password", passwordField.value.trim() || ""); // Add password field

        const xhr = new XMLHttpRequest();
        xhr.open("POST", "/api/file/upload-chunk", true);

        const csrfTokenElement = document.querySelector('input[name="_csrf"]');
        if (csrfTokenElement) {
            xhr.setRequestHeader("X-CSRF-TOKEN", csrfTokenElement.value);
        }

        xhr.onload = function () {
            if (xhr.status === 200) {
                try {
                    const response = JSON.parse(xhr.responseText); // Parse JSON response

                    currentChunk++;
                    const percentComplete = (currentChunk / totalChunks) * 100;
                    progressBar.style.width = percentComplete + "%";
                    progressBar.setAttribute("aria-valuenow", percentComplete);

                    if (currentChunk < totalChunks) {
                        uploadChunk();
                        if (currentChunk === totalChunks - 1 && isPasswordProtected) {
                            uploadStatus.innerText = "Upload complete. Encrypting..."
                        }
                    } else {
                        uploadStatus.innerText = "Upload complete.";
                        if (response.uuid) {
                            window.location.href = "/file/" + response.uuid;
                        } else {
                            alert("Upload completed but no UUID received.");
                        }
                    }
                } catch (error) {
                    console.error("Error parsing JSON:", error);
                    alert("Unexpected server response. Please try again.");
                }
            } else {
                console.error("Upload error:", xhr.responseText);
                alert("Chunk upload failed. Please try again.");
                document.getElementById("uploadIndicator").style.display = "none";
            }
        };

        xhr.onerror = function () {
            alert("An error occurred during the upload. Please try again.");
            document.getElementById("uploadIndicator").style.display = "none";
        };

        xhr.send(chunkFormData);
    }

    uploadChunk();
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
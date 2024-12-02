document.getElementById("uploadForm").addEventListener("submit", function (event) {
    event.preventDefault(); // Prevent the form from submitting synchronously

    // Display the indicator
    document.getElementById("uploadIndicator").style.display = "block";
    const progressBar = document.getElementById("uploadProgress");
    const uploadStatus = document.getElementById("uploadStatus");
    progressBar.style.width = "0%";
    progressBar.setAttribute("aria-valuenow", 0);

    // Prepare form data
    const formData = new FormData(event.target);

    // Create an AJAX request
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/file/upload", true);

    // Add CSRF token if required
    const csrfTokenElement = document.querySelector('input[name="_csrf"]');
    if (csrfTokenElement) {
        xhr.setRequestHeader("X-CSRF-TOKEN", csrfTokenElement.value);
    }

    // Handle response
    xhr.onload = function () {
        if (xhr.status === 200) {
            const response = JSON.parse(xhr.responseText);
            if (response.uuid) {
                // Redirect to the view page using the UUID from the JSON response
                window.location.href = "/file/" + response.uuid;
            } else {
                alert("Unexpected response. Please try again.");
                document.getElementById("uploadIndicator").style.display = "none";
            }
        } else {
            alert("Upload failed. Please try again.");
            console.log(xhr.responseText);
            document.getElementById("uploadIndicator").style.display = "none";
        }
    };

    // Handle network or server errors
    xhr.onerror = function () {
        alert("An error occurred during the upload. Please try again.");
        document.getElementById("uploadIndicator").style.display = "none";
    };

    // Track upload progress
    xhr.upload.onprogress = function (event) {
        if (event.lengthComputable) {
            const percentComplete = (event.loaded / event.total) * 100;
            progressBar.style.width = percentComplete + "%";
            progressBar.setAttribute("aria-valuenow", percentComplete);

            // Update the status text when upload is complete (100%)
            if (percentComplete === 100 && isPasswordProtected()) {
                uploadStatus.innerText = "Upload complete. Encrypting...";
            }
        }
    };

    // Send the form data
    xhr.send(formData);
});

function isPasswordProtected() {
    const passwordField = document.getElementById("password");

    return passwordField && passwordField.value.trim() !== "";
}

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
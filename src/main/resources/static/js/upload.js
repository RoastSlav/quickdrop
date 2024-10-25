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

function validateFileSize() {
    const file = document.getElementById('file').files[0];
    const maxSize = 1024 * 1024 * 1024; // 1GB
    const fileSizeAlert = document.getElementById('fileSizeAlert');

    if (file.size > maxSize) {
        fileSizeAlert.style.display = 'block';
        document.getElementById('file').value = '';
    } else {
        fileSizeAlert.style.display = 'none';
    }
}
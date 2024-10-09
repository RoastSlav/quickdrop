document.getElementById("uploadForm").addEventListener("submit", function (event) {
    event.preventDefault(); // Prevent the form from submitting synchronously

    // Display the indicator
    document.getElementById("uploadIndicator").style.display = "block";

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

    // Send the form data
    xhr.send(formData);
});